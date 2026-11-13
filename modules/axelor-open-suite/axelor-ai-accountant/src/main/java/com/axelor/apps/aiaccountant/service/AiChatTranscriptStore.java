package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.db.AiChatMessage;
import com.axelor.apps.aiaccountant.db.repo.AiChatMessageRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.persistence.EntityTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The durable, visible chat transcript: every bubble the AI Accountant panel shows, in order, kept
 * until the user deletes the conversation.
 *
 * <h2>Why this exists next to {@link AiTurnMemoryStore}</h2>
 *
 * They look alike and are not the same thing. {@code AiTurnMemoryStore} keeps routing context,
 * keyed by {@code (user, company, conversationId)} because a chain and an authorised job must not
 * cross between browser tabs. This keeps what the user is reading, keyed by {@code (user, company)}
 * alone, because a transcript authorises nothing and must survive precisely what invalidates a
 * chain — a reload, a new tab, a new session, a redeploy. Before this class existed the transcript
 * lived only in the browser's {@code localStorage} under a key containing the per-tab conversation
 * id, so every refresh that minted a new id emptied the panel while the words sat unreachable in
 * the database.
 *
 * <h2>Not best-effort</h2>
 *
 * Every other store in this package swallows its exceptions, and is right to: losing a token
 * measurement costs a conservative chain decision, not a fact. This one throws {@link
 * AiTranscriptPersistenceException}, because the alternative is telling the caller a message was
 * saved when it was not, and then losing it at the next refresh with nothing anywhere saying so.
 *
 * <h2>Every attempt is a genuinely fresh transaction</h2>
 *
 * A concurrent append collides on {@code seq}, which is exactly what the unique constraint is for.
 * Retrying that inside the transaction that just failed would re-flush the same rejected row into a
 * rollback-only unit of work and fail again for a different reason. So an attempt is one call to
 * {@link #runInTransaction}, which begins and commits a transaction <b>of its own</b> and refuses
 * to join one it did not open, and a failed attempt is followed by {@link #clearPersistenceContext}
 * before the next one — dropping the entity the database refused, so the retry starts from the row
 * as it now actually stands. See that method for why joining, which is what {@code
 * JPA.runInTransaction} does, would silently break the commit-before-execution guarantee.
 */
@Singleton
public class AiChatTranscriptStore {

  private static final Logger LOG = LoggerFactory.getLogger(AiChatTranscriptStore.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Attempts per append.
   *
   * <p>Three, not one: a collision on {@code seq} means another tab of the same user committed
   * first, and the answer is to take the next position, not to fail. Bounded so a genuinely broken
   * database surfaces as an error rather than as a loop the user waits through.
   */
  static final int MAX_ATTEMPTS = 3;

  /** The three bubbles the panel renders. Nothing else is ever stored. */
  public static final int ROLE_USER = 1;

  public static final int ROLE_ASSISTANT = 2;

  public static final int ROLE_ERROR = 3;

  /** One bubble, as the panel needs it. */
  public static final class Message {

    private final int role;
    private final String text;
    private final List<String> tools;

    public Message(int role, String text, List<String> tools) {
      this.role = role;
      this.text = text;
      this.tools = tools == null ? Collections.emptyList() : tools;
    }

    public int role() {
      return role;
    }

    public String text() {
      return text;
    }

    public List<String> tools() {
      return tools;
    }
  }

  /**
   * The whole transcript for this user in this company, oldest first.
   *
   * <p>Ordered by {@code seq} and then by id, so two rows that somehow share a position still come
   * back in a stable order rather than in whatever order the database felt like.
   *
   * <p>Reading may fail — unlike writing, that is survivable and must be visible: the caller turns
   * it into an explicit load error, never into an empty transcript presented as success.
   */
  public List<Message> load(Long userId, Long companyId) {
    if (userId == null || companyId == null) {
      return Collections.emptyList();
    }
    List<AiChatMessage> rows =
        repository()
            .all()
            .filter("self.user.id = :userId AND self.company.id = :companyId")
            .bind("userId", userId)
            .bind("companyId", companyId)
            .order("seq")
            .order("id")
            .fetch();
    List<Message> out = new ArrayList<>(rows.size());
    for (AiChatMessage row : rows) {
      out.add(
          new Message(
              row.getRoleSelect() == null ? ROLE_ASSISTANT : row.getRoleSelect(),
              row.getContent(),
              parseTools(row.getToolsJson())));
    }
    return out;
  }

  /**
   * Appends one bubble and commits it.
   *
   * <p>Returns only once the row is in the database. A {@code turnKey} that already has a row with
   * this role is a committed no-op — the case of a retried or replayed request, which must not
   * produce a second bubble.
   *
   * @throws AiTranscriptPersistenceException when the row could not be committed
   */
  public void append(
      Long userId,
      Long companyId,
      String conversationId,
      String turnKey,
      int role,
      String content,
      List<String> tools) {

    if (userId == null || companyId == null) {
      // No books in scope: every tool would refuse this turn anyway, and a transcript row that
      // cannot say which company it belongs to has no key the panel could ever read it back by.
      LOG.warn(
          "AI Accountant: not storing a chat message with no {} in scope.",
          userId == null ? "user" : "company");
      return;
    }

    RuntimeException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        runInTransaction(
            () -> persist(userId, companyId, conversationId, turnKey, role, content, tools));
        return;
      } catch (AiTranscriptPersistenceException ambient) {
        // The caller already holds a transaction, so no attempt here can commit. Retrying would
        // run twice more against the same doomed transaction and report the same refusal three
        // attempts later; there is nothing about this that a second try changes.
        throw ambient;
      } catch (RuntimeException e) {
        last = e;
        // The attempt that just failed left a rejected entity in the persistence context and its
        // transaction rolled back. Dropping it is what makes the next attempt a fresh read of the
        // row as it now stands, rather than a second flush of the same refused state.
        clearPersistenceContext();
        LOG.debug(
            "AI Accountant: transcript append attempt {} of {} failed: {}",
            attempt,
            MAX_ATTEMPTS,
            e.getMessage());
      }
    }

    // Never silent. The message the user typed, or the answer they were just shown, is not in the
    // database, and both call sites are written to say so rather than to carry on.
    LOG.error(
        "AI Accountant: could not store a chat message (role {}) for user {} in company {},"
            + " conversation {}.",
        role,
        userId,
        companyId,
        conversationId,
        last);
    throw new AiTranscriptPersistenceException(
        "The chat message could not be stored after " + MAX_ATTEMPTS + " attempts.", last);
  }

  /**
   * The insert itself, inside whatever transaction {@link #runInTransaction} opened.
   *
   * <p>Reads the current highest position and takes the next one. Two tabs doing this at the same
   * instant both read the same maximum; the unique constraint on {@code (user, company, seq)} is
   * what stops them both taking it, and the retry above is what resolves it.
   */
  protected void persist(
      Long userId,
      Long companyId,
      String conversationId,
      String turnKey,
      int role,
      String content,
      List<String> tools) {

    AiChatMessageRepository repo = repository();

    if (turnKey != null
        && !turnKey.trim().isEmpty()
        && existing(userId, companyId, turnKey, role)) {
      // A replay of a send that already landed. Committing nothing is the correct outcome, and the
      // caller is told the message is stored — because it is.
      return;
    }

    AiChatMessage row = new AiChatMessage();
    row.setUser(Beans.get(UserRepository.class).find(userId));
    row.setCompany(Beans.get(CompanyRepository.class).find(companyId));
    row.setSeq(nextSeq(userId, companyId));
    row.setRoleSelect(role);
    row.setContent(content);
    row.setToolsJson(serializeTools(tools));
    row.setTurnKey(turnKey == null || turnKey.trim().isEmpty() ? null : turnKey.trim());
    row.setConversationId(conversationId);
    repo.save(row);
  }

  /** True when this exact send has already produced this bubble. */
  protected boolean existing(Long userId, Long companyId, String turnKey, int role) {
    return repository()
            .all()
            .filter(
                "self.user.id = :userId AND self.company.id = :companyId"
                    + " AND self.turnKey = :turnKey AND self.roleSelect = :role")
            .bind("userId", userId)
            .bind("companyId", companyId)
            .bind("turnKey", turnKey.trim())
            .bind("role", role)
            .count()
        > 0;
  }

  /** The next free position for this user in this company. */
  protected long nextSeq(Long userId, Long companyId) {
    AiChatMessage last =
        repository()
            .all()
            .filter("self.user.id = :userId AND self.company.id = :companyId")
            .bind("userId", userId)
            .bind("companyId", companyId)
            .order("-seq")
            .fetchOne();
    return last == null || last.getSeq() == null ? 1L : last.getSeq() + 1L;
  }

  /**
   * Runs one attempt in a transaction this method owns, and refuses to run in one it does not.
   *
   * <h2>Why not {@code JPA.runInTransaction}</h2>
   *
   * That helper <b>joins</b>: {@code if (!txn.isActive()) { txn.begin(); txnStarted = true; }}, and
   * when it joined it commits nothing, because the commit belongs to whoever opened the transaction
   * (see {@code JPA#runInTransaction}). For every other store in this module that is the right
   * behaviour. Here it would quietly break the one guarantee this class exists to make.
   *
   * <p>The caller commits the user's message <b>before</b> the model runs and before any write tool
   * executes, precisely so that a turn cannot change the books without the transcript recording
   * that somebody asked it to. Joining an enclosing transaction would leave the row uncommitted
   * while the whole tool loop ran — and if that enclosing transaction later rolled back, the
   * message would vanish while the invoice it posted stayed posted. A failed attempt would also
   * mark the caller's transaction rollback-only, so the retry loop above would spend two more
   * attempts against a doomed transaction that {@link #clearPersistenceContext} cannot rescue.
   *
   * <h2>Whether that can happen today</h2>
   *
   * On the HTTP route it cannot, and this was verified rather than assumed. {@code /ws/*} passes
   * through Guice's stock {@code PersistFilter}, whose {@code doFilter} is {@code
   * unitOfWork.begin() / filterChain.doFilter / unitOfWork.end()}, and {@code
   * JpaPersistService.begin()} is only {@code emFactory.createEntityManager()} — a persistence
   * context, never a transaction. None of the other filters in {@code AppServletModule} ({@code
   * ProxyFilter}, {@code CorsFilter}, {@code TenantFilter}, {@code AppFilter}, {@code
   * GuiceShiroFilter}, {@code MaintenanceFilter}) opens one. Neither {@code
   * AiAccountantRestController}, {@code AiAccountantService} nor {@code AiAccountantServiceImpl}
   * carries {@code @Transactional}, {@code AiAccountantModule} binds no interceptor, and the AOP in
   * {@code AppServletModule} matches {@code com.axelor.rpc.Response} and {@code
   * com.axelor.rpc.Request}, neither of which this controller uses. The reads that run before the
   * append — config, {@code AuthUtils.getUser()}, {@code AiCompanyScope} — do not begin one either.
   *
   * <p>So this changes nothing about today's behaviour. It makes an accidental property into a
   * stated one: the guarantee currently holds because nobody has wrapped {@code chat()}, and the
   * failure it would cause is silent and looks like a database problem months later. A caller who
   * does wrap it now gets told, at the first message, exactly what is wrong.
   */
  protected void runInTransaction(Runnable task) {
    EntityTransaction txn = JPA.em().getTransaction();
    if (txn.isActive()) {
      throw new AiTranscriptPersistenceException(
          "The chat transcript cannot be written inside a caller's transaction: the user's message"
              + " must be committed before the model or any tool runs, and a joined transaction"
              + " commits only when the caller says so.",
          null);
    }
    txn.begin();
    try {
      task.run();
      if (txn.getRollbackOnly()) {
        // Something in the attempt doomed the transaction without throwing. Committing is not an
        // option and reporting success would be a lie, so this joins the retry path.
        throw new IllegalStateException("the transaction was marked rollback-only");
      }
      txn.commit();
    } finally {
      // Reached with an active transaction only when the attempt or the commit threw.
      if (txn.isActive()) {
        txn.rollback();
      }
    }
  }

  /** Drops the persistence context a failed attempt left behind. See the class documentation. */
  protected void clearPersistenceContext() {
    try {
      JPA.clear();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not clear the persistence context: {}", e.getMessage());
    }
  }

  protected AiChatMessageRepository repository() {
    return Beans.get(AiChatMessageRepository.class);
  }

  static String serializeTools(List<String> tools) {
    if (tools == null || tools.isEmpty()) {
      return null;
    }
    ArrayNode array = MAPPER.createArrayNode();
    tools.forEach(array::add);
    return array.toString();
  }

  static List<String> parseTools(String json) {
    if (json == null || json.trim().isEmpty()) {
      return Collections.emptyList();
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      if (!root.isArray()) {
        return Collections.emptyList();
      }
      List<String> out = new ArrayList<>();
      for (JsonNode entry : root) {
        out.add(entry.asText());
      }
      return out;
    } catch (Exception e) {
      // Display metadata only: an unreadable list costs the tool chips under one bubble, never the
      // bubble itself.
      LOG.debug("AI Accountant: could not parse the stored tool list: {}", e.getMessage());
      return Collections.emptyList();
    }
  }
}
