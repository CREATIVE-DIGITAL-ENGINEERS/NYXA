package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.db.AiChatSession;
import com.axelor.apps.aiaccountant.db.repo.AiChatSessionRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's own record of how big each chat panel's model conversation has grown.
 *
 * <h2>Why a client-carried number could not decide this</h2>
 *
 * The context bound is a safety decision: past the provider's window the request is refused and the
 * user loses their turn, mid-job. It was being made from a figure the browser stored and sent back,
 * which means a client reporting a <em>smaller</em> chain than it really had would keep the
 * conversation chained straight into that refusal. The exposure was one-directional and pointed the
 * wrong way — over-reporting costs an unnecessary reset, under-reporting costs the turn — and it
 * needed no malice to happen: a stale bundle that omits the field reports zero.
 *
 * <p>So the measurement lives here, written by the turn that observed it from the provider's own
 * usage object. What the client sends is still read, but only to notice that the two disagree, and
 * a disagreement is itself a reason to reset. Nothing a caller supplies can lower the number the
 * decision is made on.
 *
 * <h2>Failing toward the reset</h2>
 *
 * Every unknown resolves to "start a fresh chain": no row, an unreadable row, a row too old to
 * describe the chain the client is still sending an id for. A reset costs one re-sent operating
 * procedure that the prompt cache mostly absorbs, and the job survives it — the workflow row is
 * untouched and the continuity handoff is rebuilt from it. A missed reset costs the whole turn.
 *
 * <h2>What this is not</h2>
 *
 * It authorises nothing. It holds no target, no goal and no record reference, and no write is ever
 * permitted or refused on the strength of it. Whether a change may be made is {@link
 * AiWorkflowStore}'s question alone.
 */
@Singleton
public class AiChainBudgetStore {

  private static final Logger LOG = LoggerFactory.getLogger(AiChainBudgetStore.class);

  /**
   * How long a measurement describes the chain it was taken from.
   *
   * <p>A conversation the user comes back to hours later is one whose provider-side history may
   * well have been evicted, and whose recorded size is in any case a claim about a state nobody has
   * observed since. Treating an old row as no measurement at all costs a reset on the first turn
   * after a long pause, which is the turn where a fresh chain is cheapest anyway.
   */
  private static final Duration MAX_AGE = Duration.ofHours(12);

  /**
   * How far the client's copy may differ from the server's before the two are called incompatible.
   *
   * <p>Not zero. The client stores what the server told it at the end of a turn, so in the ordinary
   * case they are equal; a small difference is the kind of thing a rounding or an interrupted write
   * produces. A large one means the two are describing different chains — a stale tab, a replayed
   * blob, an edited number — and there is no way to tell which is right, so the chain is not
   * continued.
   */
  private static final int TOLERANCE_TOKENS = 500;

  /** What the server has measured about one chain. Never null-valued; absent is a null Reading. */
  public static final class Reading {

    private final int lastInputTokens;
    private final int chainGrowthTokens;
    private final int turnCount;
    private final String chainId;

    Reading(int lastInputTokens, int chainGrowthTokens, int turnCount, String chainId) {
      this.lastInputTokens = Math.max(0, lastInputTokens);
      this.chainGrowthTokens = Math.max(0, chainGrowthTokens);
      this.turnCount = Math.max(0, turnCount);
      this.chainId = chainId;
    }

    /**
     * Which chain these figures describe.
     *
     * <p>Carried back when the turn records its own measurement, so a write made against a chain
     * that has since been reset by a concurrent turn can be recognised as stale and discarded.
     */
    public String chainId() {
      return chainId;
    }

    public int lastInputTokens() {
      return lastInputTokens;
    }

    public int chainGrowthTokens() {
      return chainGrowthTokens;
    }

    public int turnCount() {
      return turnCount;
    }

    /**
     * Whether a client-reported size can be describing the same chain as this one.
     *
     * <p>Deliberately symmetric. A client reporting less is the dangerous direction — it is what
     * would suppress a required reset — but a client reporting more is equally a sign that the two
     * are not talking about the same conversation, and continuing on either is a guess.
     */
    public boolean agreesWith(int clientLastInputTokens) {
      return Math.abs(lastInputTokens - Math.max(0, clientLastInputTokens)) <= TOLERANCE_TOKENS;
    }
  }

  /**
   * What was measured for this chain, or null when nothing usable was.
   *
   * <p>Null means "no measurement", and every caller treats that as a reason to start fresh rather
   * than as permission to continue. Never throws: a store that cannot be read must not be the
   * reason a turn fails, and the conservative answer is already the safe one.
   */
  public Reading load(Long userId, Long companyId, String conversationId) {
    AiChatSession row = find(userId, companyId, conversationId);
    if (row == null) {
      return null;
    }
    LocalDateTime taken = row.getLastTurnDateTime();
    if (taken == null || taken.isBefore(LocalDateTime.now().minus(MAX_AGE))) {
      LOG.info(
          "AI Accountant: chain measurement for this conversation is missing or stale; treating it"
              + " as unmeasured.");
      return null;
    }
    return new Reading(
        row.getLastInputTokens() == null ? 0 : row.getLastInputTokens(),
        row.getChainGrowthTokens() == null ? 0 : row.getChainGrowthTokens(),
        row.getTurnCount() == null ? 0 : row.getTurnCount(),
        row.getChainId());
  }

  /**
   * Whether this conversation has been measured under some <em>other</em> company.
   *
   * <h2>Why this exists</h2>
   *
   * A company change used to be detected only from the stamp the client returns with its chain, so
   * a client that omitted the field suppressed the detection entirely — and jobs stayed open in a
   * set of books the user had left. This is the same fact, established from a row the server wrote:
   * the panel's conversation id has been seen before, under a different company, so the panel has
   * moved.
   *
   * <p>Note the direction it is used in. It only ever causes <em>more</em> to be ended, never less,
   * so a false negative costs nothing that was not already the old behaviour and a false positive
   * abandons a job in a company the user is not looking at.
   */
  public boolean conversationSeenInAnotherCompany(
      Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      return false;
    }
    try {
      return Beans.get(AiChatSessionRepository.class)
              .all()
              .filter(
                  "self.user.id = :userId AND self.conversationId = :conversationId"
                      + " AND self.company.id != :companyId")
              .bind("userId", userId)
              .bind("conversationId", conversationId)
              .bind("companyId", companyId)
              .count()
          > 0;
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not check the conversation's companies: {}", e.getMessage());
      return false;
    }
  }

  /** The row for this user, company and conversation, or null. */
  public AiChatSession find(Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      return null;
    }
    try {
      return Beans.get(AiChatSessionRepository.class)
          .all()
          .filter(
              "self.user.id = :userId AND self.company.id = :companyId"
                  + " AND self.conversationId = :conversationId")
          .bind("userId", userId)
          .bind("companyId", companyId)
          .bind("conversationId", conversationId)
          .order("-id")
          .fetchOne();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not look up the chain measurement: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Records what this turn observed, and returns whether it could be stored.
   *
   * <p>Best-effort in the sense that a failure never reaches the user, but not in its consequences:
   * a turn whose measurement was not written leaves no row for the next turn to read, and the next
   * turn therefore starts a fresh chain. That is the intended behaviour — an unmeasured chain is
   * one nothing can vouch for — and it is why this is worth a warning rather than a debug line.
   *
   * @param lastInputTokens the last request's input size, from the provider's own usage object
   * @param growthTokens the turn's last request minus its first
   * @param reset true when this turn started a fresh chain, so the counter restarts with it
   */
  public boolean record(
      Long userId,
      Long companyId,
      String conversationId,
      String measuredChainId,
      int lastInputTokens,
      int growthTokens,
      boolean reset) {
    if (userId == null || companyId == null || conversationId == null || lastInputTokens <= 0) {
      // Nothing to attribute the measurement to, or nothing measured. Writing a zero would be worse
      // than writing nothing: it would look like a small chain rather than like an absent reading.
      return false;
    }
    // Through the injector: @Transactional is a method interceptor, and a self-call would bypass
    // the proxy and run outside any transaction.
    AiChainBudgetStore proxied = Beans.get(AiChainBudgetStore.class);
    try {
      return proxied.persist(
          userId, companyId, conversationId, measuredChainId, lastInputTokens, growthTokens, reset);
    } catch (RuntimeException e) {
      // Includes the optimistic-lock failure, which is the ordinary outcome when two turns of one
      // panel finish at once. One retry, against the row as it now stands: the first attempt's
      // transaction has been rolled back, so this re-reads and re-applies — and the rules below are
      // written so that re-applying to a newer row is safe, because they never lower a number.
      LOG.info("AI Accountant: retrying the chain measurement after {}", e.getMessage());
      try {
        return proxied.persist(
            userId,
            companyId,
            conversationId,
            measuredChainId,
            lastInputTokens,
            growthTokens,
            reset);
      } catch (RuntimeException retry) {
        LOG.warn(
            "AI Accountant: could not record the chain measurement: {}. The next turn will find no"
                + " usable reading and will start a fresh chain.",
            retry.getMessage());
        return false;
      }
    }
  }

  /**
   * Writes the row, refusing to let an older measurement replace a newer one.
   *
   * <h2>The two ways a concurrent turn can do damage, and the rule for each</h2>
   *
   * Two turns of one chat panel can be in flight together — the user sends a second message while
   * the first is still working, or the client retries a request whose answer was lost. Both will
   * write here when they finish, in whatever order they happen to commit.
   *
   * <ul>
   *   <li><b>A stale chain.</b> The other turn reset the conversation while this one was running,
   *       so this measurement describes a chain that no longer exists. Written down, it would
   *       report a large chain for a conversation that has just started fresh, and every following
   *       turn would reset for nothing. Discarded: the chain id it was taken against no longer
   *       matches the row.
   *   <li><b>A smaller number arriving last.</b> Within one chain {@code lastInputTokens} only ever
   *       grows, because every request re-reads the whole retained history. So an older turn's
   *       figure is always the smaller one, and letting it land would report a chain smaller than
   *       the real one — the exact under-measurement that suppresses a required reset, arriving
   *       through concurrency rather than through the client. The two are merged by keeping the
   *       larger, which is the same value both turns would have agreed on had they run in sequence.
   * </ul>
   *
   * <p>A reset is the one case that legitimately lowers the number, and it is allowed to: it mints
   * a new chain id and replaces the row outright, which also fences every in-flight turn of the
   * chain it just ended.
   *
   * @param measuredChainId the chain this turn read at its start, or null when it did not read one
   * @return true when the row now reflects this measurement or something newer
   */
  @Transactional(rollbackOn = Exception.class)
  public boolean persist(
      Long userId,
      Long companyId,
      String conversationId,
      String measuredChainId,
      int lastInputTokens,
      int growthTokens,
      boolean reset) {
    AiChatSessionRepository repo = Beans.get(AiChatSessionRepository.class);
    AiChatSession row = find(userId, companyId, conversationId);
    if (row == null) {
      row = new AiChatSession();
      row.setUser(Beans.get(UserRepository.class).find(userId));
      row.setCompany(Beans.get(CompanyRepository.class).find(companyId));
      row.setConversationId(conversationId);
      row.setTurnCount(0);
    } else if (reset) {
      // A fresh chain replaces whatever was there, and its new id fences every turn still running
      // against the old one.
      LOG.debug("AI Accountant: chain restarted for this conversation.");
    } else if (!sameChain(row, measuredChainId)) {
      // Measured against a chain the row has since moved off. Nothing here describes the
      // conversation as it now stands, so nothing is written.
      LOG.info(
          "AI Accountant: discarding a chain measurement taken against {} — the conversation is now"
              + " on {}.",
          measuredChainId == null ? "(no chain)" : measuredChainId,
          row.getChainId() == null ? "(no chain)" : row.getChainId());
      return false;
    } else if (row.getLastInputTokens() != null && row.getLastInputTokens() >= lastInputTokens) {
      // A newer turn of the same chain has already recorded a larger figure. Keeping it is the
      // union of what both turns observed, and the only direction that cannot hide a needed reset.
      LOG.info(
          "AI Accountant: keeping the recorded chain size of {} over an older turn's {}.",
          row.getLastInputTokens(),
          lastInputTokens);
      return true;
    }

    row.setChainId(reset || row.getChainId() == null ? newChainId() : row.getChainId());
    row.setLastInputTokens(Math.max(0, lastInputTokens));
    row.setChainGrowthTokens(Math.max(0, growthTokens));
    // Restarted with the chain rather than accumulated across resets: the count answers "how many
    // turns is THIS chain deep", which is what makes a small measurement readable as a young
    // conversation rather than as a broken one.
    row.setTurnCount(reset ? 1 : (row.getTurnCount() == null ? 0 : row.getTurnCount()) + 1);
    row.setLastTurnDateTime(LocalDateTime.now());
    repo.save(row);
    return true;
  }

  /**
   * Whether this measurement was taken against the chain the row currently holds.
   *
   * <p>A row with no chain id predates this field, and a turn that read no id could not have seen
   * one. Both are treated as matching, so an upgrade does not throw away the first measurement of
   * every open conversation — the monotonic rule below still protects the number itself, which is
   * the part that matters.
   */
  protected boolean sameChain(AiChatSession row, String measuredChainId) {
    String stored = row.getChainId();
    if (stored == null || measuredChainId == null) {
      return true;
    }
    return stored.equals(measuredChainId);
  }

  /** A fresh chain identity. Opaque, never shown, only ever compared. */
  protected String newChainId() {
    return UUID.randomUUID().toString();
  }
}
