package com.axelor.apps.aiaccountant.metrics;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one chat turn cost, measured rather than estimated.
 *
 * <h2>Why this exists</h2>
 *
 * Every judgement about the assistant's speed and cost so far has been a guess: nobody could say
 * how many model requests a turn takes, how much of the input was served from cache, which tool is
 * slow, or which tool result is large. Tuning without those numbers is guesswork with a bill
 * attached.
 *
 * <h2>What is recorded, and what deliberately is not</h2>
 *
 * Sizes, counts, durations, token figures, tool <em>names</em> and response ids. Never a tool
 * argument, never a tool result, never the user's message or the model's answer — those carry
 * supplier names, IBANs, invoice numbers and line descriptions, and a metrics log is exactly the
 * wrong place for them. Result payloads are recorded as byte counts.
 *
 * <p>Token numbers come from the response's own {@code usage} object ({@link
 * FoundryResponse.Usage}) — the service's accounting, not a local estimate.
 *
 * <h2>Shape</h2>
 *
 * One line per turn on the {@code com.axelor.ai.metrics} logger, {@code key=value} separated by
 * spaces, values never containing spaces. That is greppable, and fifty conversations can be
 * aggregated with a one-liner rather than a parser:
 *
 * <pre>
 * grep 'ai.turn ' app.log | sed 's/.*ai.turn //' | awk '{for(i=1;i&lt;=NF;i++){split($i,a,"=");s[a[1]]+=a[2];n[a[1]]++}}
 *   END{for(k in s) printf "%-22s sum=%-12.0f avg=%.1f\n", k, s[k], s[k]/n[k]}'
 * </pre>
 *
 * <h2>Lifetime</h2>
 *
 * Thread-local and bound by the service around the whole turn, the same way {@code AiWriteEffects}
 * and {@code AiTurnReadMemory} are — a turn runs on one request thread, and the tool loop has to be
 * inside the same window as the model requests it interleaves with. {@link #end()} is what emits;
 * it must run in a {@code finally} so a failed turn is still measured. Everything here is
 * best-effort: no accessor may ever throw into the turn it is measuring.
 */
public final class AiTurnMetrics {

  /**
   * The metrics logger.
   *
   * <p>Under {@code com.axelor} deliberately. A name outside every configured category (such as
   * {@code ai.metrics}) inherits {@code logging.level.root = ERROR}, and the INFO line below is
   * then dropped before it reaches an appender. A name is a level in logback, not a label, so a
   * metrics logger has to live where the application's own level applies. The message still begins
   * {@code ai.turn}, so grepping is unchanged.
   */
  private static final Logger LOG = LoggerFactory.getLogger("com.axelor.ai.metrics");

  private static final ThreadLocal<Turn> CURRENT = new ThreadLocal<>();

  /**
   * The wire name of {@code AiFailureKind.TIMEOUT}, kept as a literal.
   *
   * <p>Measuring must not make this package depend on the resilience package — a metric that could
   * fail to load because a policy class moved would be a measuring apparatus that breaks the thing
   * it measures. The value is a two-way constant in the same sense a log format is.
   */
  private static final String TIMEOUT_KIND = "timeout";

  private AiTurnMetrics() {}

  /** One turn's accumulated numbers. */
  public static final class Turn {
    private final long startedNanos = System.nanoTime();
    private boolean chained;
    private String model;
    private String agentVersion;
    private final List<Long> requestMillis = new ArrayList<>();
    private int requests;
    private int inputTokens;
    private int cachedInputTokens;
    private int cacheWriteTokens;
    private int outputTokens;
    private int reasoningTokens;
    private int totalTokens;
    private int firstInputTokens = -1;
    private int lastInputTokens;
    private final List<String> toolNames = new ArrayList<>();
    private final Map<String, Long> toolMillis = new LinkedHashMap<>();
    private final Map<String, Long> toolBytes = new LinkedHashMap<>();
    private long totalToolMillis;
    private long totalToolBytes;
    private int toolErrors;
    private String outcome = "unknown";
    private boolean truncated;

    /**
     * The tool-iteration cap was reached, whether or not the work was left unfinished.
     *
     * <p>Kept separate from {@code truncated}: a turn can use its whole budget and still have
     * posted the invoice on the last iteration, and that is a success. Without both flags there is
     * no way to distinguish "the cap is too low" from "the cap is fine and the model is chatty",
     * which is exactly the question the batching work needs answered.
     */
    private boolean capReached;

    /**
     * How the tool calls arrived, so a future decision about batching rests on observation.
     *
     * <p>Today the model is not asked to batch and is believed to emit one call per response. That
     * belief has never been measured. {@code maxCallsPerResponse} is what actually decides whether
     * ordered batching is worth encouraging, and {@code reordered}/{@code aborted} say whether the
     * new safety rules ever fired — which is the only way to know they are not silently mangling
     * something.
     */
    private int batches;

    private int maxCallsPerResponse;
    private boolean reordered;
    private boolean aborted;

    /**
     * Why this turn did or did not continue the previous Azure chain, and what it cost.
     *
     * <p>Chain bounding is invisible in every other field: a turn that starts fresh simply has a
     * small firstInputTokens, indistinguishable from a short conversation. Without the reason and
     * the size of the chain that was dropped, there is no way to tell whether the threshold is set
     * anywhere near right.
     */
    private String chainReset = "none";

    private int prevChainTokens;
    private int continuityBytes;
    private boolean resumed;

    /**
     * 6C: what the context budget saw, on every turn rather than only on the resets.
     *
     * <p>{@code contextHeadroomTokens} on a turn that continued is the number that says whether the
     * bounds are set anywhere near right; a log of resets alone cannot distinguish a healthy
     * conversation from one that ran for forty turns two thousand tokens short of a refusal.
     */
    private int projectedChainTokens;

    private int chainGrowthTokens;
    private int contextHeadroomTokens;
    private int contextLimitTokens;
    private boolean continuityFromStore;

    /** Which chain the measurements were taken against. Carried, never emitted. */
    private String measuredChainId;

    /**
     * What the turn was working from and how autonomously it behaved.
     *
     * <p>{@code pageContextType} separates "a record was open" from "a list was open" from "the
     * client said nothing" — three cases a stale page context would make indistinguishable. {@code
     * delegatedSelection} counts the turns where the user said "any/one/random" and left the choice
     * to the assistant, which is a behaviour the assistant supports and therefore one that has to
     * be measurable. {@code bulkTargets} is how many distinct records one turn wrote.
     *
     * <p>All three are counts and categories. None of them can carry a supplier, an amount or a
     * message.
     */
    private String pageContextType = "none";

    private boolean delegatedSelection;
    private int bulkTargets;
    private boolean asyncPromiseCorrected;

    /**
     * C6.1: was the turn goal-driven, and did it get there without asking?
     *
     * <p>{@code resolvedGoal} is the destination the user's own words name — POSTED, VALIDATED,
     * DRAFT or NONE. {@code deterministicPrerequisite} says the turn ran a write on the way there
     * that the user never named (validating before posting), which is the whole behaviour this
     * phase is trying to produce. {@code clarificationAsked} counts the opposite: a turn that wrote
     * nothing and ended by asking, which is the friction being removed.
     *
     * <p>Deliberately absent: a resolved TARGET COUNT. What the user meant by "останалите" cannot
     * be established before the work runs without re-implementing their intent, and a guessed count
     * in a metric is worse than no count. {@code bulkTargets} already records how many records were
     * actually written, which is a fact.
     */
    private String resolvedGoal = "NONE";

    private boolean clarificationAsked;
    private boolean deterministicPrerequisite;

    /**
     * Which job the turn was routed to, and which playbook it therefore received.
     *
     * <p>The routing is deterministic and the two fields are recorded from the same resolution the
     * context builder used, so a turn that behaved oddly can be checked against the page of
     * instructions it actually had. Without them a wrong route is invisible: the model simply looks
     * as though it forgot a rule it was never given.
     *
     * <p>{@code playbookId} is redundant with {@code taskType} today — one playbook per task — and
     * is kept separate anyway, because tasks sharing a playbook is the obvious next shape and a
     * metric that silently changes meaning is worse than one extra column.
     */
    private String taskType = "GENERAL";

    private String playbookId = "-";

    /**
     * The instruction documents this turn was given, with their versions — {@code
     * supplier-invoice@2+supplier-invoice-post@1}.
     *
     * <p>{@code playbookId} says which playbook was chosen; this says which revision of it was
     * deployed and what else went with it. The difference matters the day a rule is edited: two
     * turns with the same task and opposite behaviour are otherwise indistinguishable in the log,
     * and the version is what ties the change in answers to the change in text.
     */
    private String instructions = "-";

    /**
     * How much of this turn was spent surviving rather than working.
     *
     * <p>None of it carries business data — they are counts, a kind and a verdict name. Without
     * them every one of these events is invisible: a turn that retried a model request three times
     * and a tool twice looks, in the log, exactly like a turn that did neither, only slower.
     * Without them there is no way to answer "is the deadline too tight?", "does the retry ever
     * help?" or, most importantly, "how often does a write end without a knowable outcome?" — and
     * that last number is the one that has to be watched.
     *
     * <p>{@code uncertainWrites} deliberately counts records, not calls: two lost answers on one
     * invoice is one record an accountant has to open.
     */
    private int modelRetries;

    private int toolRetries;
    private int timeouts;
    private final Map<String, Long> reconciliations = new LinkedHashMap<>();
    private final Map<String, Long> verifications = new LinkedHashMap<>();
    private int confirmedAfterLoss;
    private int uncertainWrites;
    private boolean deadlineExceeded;

    Turn(boolean chained) {
      this.chained = chained;
    }
  }

  /** Opens the window. Always pair with {@link #end()} in a {@code finally}. */
  public static void begin(boolean chained) {
    CURRENT.set(new Turn(chained));
  }

  /**
   * Corrects {@code chained} once the server has decided whether it really is continuing the chain.
   * {@link #begin(boolean)} can only see that the client sent an id; a company change makes the
   * turn unchained after the fact, and a metric that says otherwise would misattribute the
   * first-input-token jump that follows.
   */
  public static void chained(boolean chained) {
    Turn t = CURRENT.get();
    if (t != null) {
      t.chained = chained;
    }
  }

  /**
   * Whether this turn actually continued the previous chain, after the server decided.
   *
   * <p>Read at the end of the turn by whatever records the chain's measurement, so a fresh chain's
   * turn counter restarts with it. The value is the corrected one — what the server did, not what
   * the client believed when the request arrived.
   */
  public static boolean wasChained() {
    Turn t = CURRENT.get();
    return t != null && t.chained;
  }

  /**
   * Which chain this turn's measurements were taken against.
   *
   * <p>Not a metric, and not emitted: it is carried here because this class is already the turn's
   * shared read-back — {@link #lastInputTokens()} and {@link #wasChained()} are read the same way,
   * by the same caller, at the same moment. The alternative was a thread-local of its own holding
   * one string for the same lifetime as this one.
   *
   * <p>Its purpose is fencing. A concurrent turn of the same panel may reset the conversation while
   * this one is running, and a measurement taken against the chain that ended must not be written
   * down as describing the one that replaced it.
   */
  public static void measuredChain(String chainId) {
    Turn t = CURRENT.get();
    if (t != null) {
      t.measuredChainId = chainId;
    }
  }

  /** The chain the turn measured, or null when it read none. */
  public static String measuredChain() {
    Turn t = CURRENT.get();
    return t == null ? null : t.measuredChainId;
  }

  /**
   * Why this turn started a new Azure conversation, and how big the abandoned one had become.
   *
   * @param reason none | new_conversation | company_change | token_threshold
   * @param prevChainTokens the previous turn's last input size, 0 when unknown
   */
  public static void chainReset(String reason, int prevChainTokens) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.chainReset = reason == null || reason.isEmpty() ? "none" : reason;
    t.prevChainTokens = Math.max(0, prevChainTokens);
  }

  /**
   * What the budget policy saw when it decided, whether or not it reset.
   *
   * <p>Recorded on every turn, not only on the resets, because the number that says whether the
   * bounds are set anywhere near right is the headroom on the turns that <em>continued</em>. A log
   * containing only resets can show that resets happen; it cannot show a conversation that ran for
   * forty turns two thousand tokens short of a refusal.
   *
   * <p>Four counts and no content: nothing here can carry a supplier, an amount or a message.
   *
   * @param projected the peak input size this turn was expected to reach
   * @param growth how much the chain grew over the previous turn
   * @param headroom what was left before the context limit
   * @param limit the usable window the projection was compared against
   */
  public static void contextBudget(int projected, int growth, int headroom, int limit) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.projectedChainTokens = Math.max(0, projected);
    t.chainGrowthTokens = Math.max(0, growth);
    t.contextHeadroomTokens = Math.max(0, headroom);
    t.contextLimitTokens = Math.max(0, limit);
  }

  /** Size of the continuity handoff prepended to a fresh chain, and whether a workflow resumed. */
  public static void continuity(int bytes, boolean resumed) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.continuityBytes = Math.max(0, bytes);
    t.resumed = resumed;
  }

  /**
   * Whether the continuity handoff was built from the stored job rather than the returned blob.
   *
   * <p>Worth a column of its own: it is the difference between a reset that carried the server's
   * own record of the work across and one that carried the browser's copy of it, and those are not
   * the same claim. A run of resets with this false is the signal that the store is not being
   * reached.
   */
  public static void continuityFromStore(boolean fromStore) {
    Turn t = CURRENT.get();
    if (t != null) {
      t.continuityFromStore = fromStore;
    }
  }

  /** The last request's input size, so the next turn can decide whether to keep chaining. */
  public static int lastInputTokens() {
    Turn t = CURRENT.get();
    return t == null ? 0 : t.lastInputTokens;
  }

  /**
   * How much the chain grew across this turn, in input tokens.
   *
   * <p>The turn's own contribution: the difference between its last request and its first. That is
   * the figure the next turn's projection needs — how much a turn of this conversation typically
   * adds — and it is a measurement rather than an estimate, since both ends come from the
   * provider's own usage object.
   *
   * <p>Zero when nothing was measured, or when a turn made a single request and therefore has
   * nothing to say about growth.
   */
  public static int chainGrowthTokens() {
    Turn t = CURRENT.get();
    if (t == null || t.firstInputTokens < 0) {
      return 0;
    }
    return Math.max(0, t.lastInputTokens - t.firstInputTokens);
  }

  /**
   * Records one model response that asked for tools.
   *
   * @param callCount how many function calls arrived together in that one response
   * @param reordered whether execution order differed from the order the model emitted
   * @param aborted whether a failure stopped the rest of the batch
   */
  public static void toolBatch(int callCount, boolean reordered, boolean aborted) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.batches++;
    t.maxCallsPerResponse = Math.max(t.maxCallsPerResponse, callCount);
    t.reordered |= reordered;
    t.aborted |= aborted;
  }

  /** Emits the line and closes the window. Safe to call when no turn was opened. */
  public static void end() {
    Turn t = CURRENT.get();
    CURRENT.remove();
    if (t == null) {
      return;
    }
    try {
      LOG.info("ai.turn {}", format(t));
    } catch (RuntimeException e) {
      // Measuring must never be the reason a turn is reported as broken.
      LOG.debug("AI Accountant: could not emit turn metrics: {}", e.getMessage());
    }
  }

  /** Records one completed model request: its wall time and its own token accounting. */
  public static void modelRequest(long millis, FoundryResponse response) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.requests++;
    t.requestMillis.add(millis);
    if (response == null) {
      return;
    }
    try {
      if (t.model == null) {
        t.model = response.getModel();
      }
      if (t.agentVersion == null) {
        t.agentVersion = response.getAgentVersion();
      }
      FoundryResponse.Usage u = response.usage();
      if (!u.isPresent()) {
        return;
      }
      t.inputTokens += u.inputTokens();
      t.cachedInputTokens += u.cachedInputTokens();
      t.cacheWriteTokens += u.cacheWriteTokens();
      t.outputTokens += u.outputTokens();
      t.reasoningTokens += u.reasoningTokens();
      t.totalTokens += u.totalTokens();
      // Kept separately from the sums: how far the context grew within one turn is the number that
      // says whether the tool loop is inflating the conversation, and a sum cannot show that.
      if (t.firstInputTokens < 0) {
        t.firstInputTokens = u.inputTokens();
      }
      t.lastInputTokens = u.inputTokens();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read usage: {}", e.getMessage());
    }
  }

  /** Records one model request that failed before returning a usable response. */
  public static void modelRequestFailed(long millis) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.requests++;
    t.requestMillis.add(millis);
  }

  /**
   * Records one executed tool.
   *
   * @param name the tool name — safe to log; arguments and results are not
   * @param millis wall time of the tool itself, excluding model time
   * @param resultBytes size of the serialised result handed back to the model
   * @param failed whether the dispatcher answered with an error envelope
   */
  public static void tool(String name, long millis, long resultBytes, boolean failed) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    String key = name == null ? "unknown" : name;
    t.toolNames.add(key);
    t.toolMillis.merge(key, millis, Long::sum);
    t.toolBytes.merge(key, resultBytes, Long::sum);
    t.totalToolMillis += millis;
    t.totalToolBytes += resultBytes;
    if (failed) {
      t.toolErrors++;
    }
  }

  /** How the turn ended: {@code ok}, {@code truncated}, {@code error} … */
  public static void outcome(String outcome) {
    Turn t = CURRENT.get();
    if (t != null) {
      t.outcome = outcome;
    }
  }

  /** What the client said was on screen: {@code record}, {@code list} or {@code none}. */
  public static void pageContext(String kind, boolean delegatedSelection) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.pageContextType = kind == null || kind.isEmpty() ? "none" : kind;
    t.delegatedSelection = delegatedSelection;
  }

  /** Which job the turn was routed to, and the playbook that routing selected. */
  public static void task(String taskType, String playbookId) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.taskType = taskType == null || taskType.isEmpty() ? "GENERAL" : taskType;
    t.playbookId = playbookId == null || playbookId.isEmpty() ? "-" : playbookId;
  }

  /** The instruction documents and versions the turn was assembled from. */
  public static void instructions(String describe) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.instructions = describe == null || describe.isEmpty() ? "-" : describe;
  }

  /** The lifecycle destination the user's words named: POSTED, VALIDATED, DRAFT or NONE. */
  public static void resolvedGoal(String goal) {
    Turn t = CURRENT.get();
    if (t != null) {
      t.resolvedGoal = goal == null || goal.isEmpty() ? "NONE" : goal;
    }
  }

  /** The turn ran a write on the way to the goal that the user had not named. */
  public static void deterministicPrerequisite() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.deterministicPrerequisite = true;
    }
  }

  /** The turn wrote nothing and ended by asking the user something. */
  public static void clarificationAsked() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.clarificationAsked = true;
    }
  }

  /** How many distinct records this turn wrote. One is ordinary; four is a bulk turn working. */
  public static void bulkTargets(int count) {
    Turn t = CURRENT.get();
    if (t != null) {
      t.bulkTargets = Math.max(0, count);
    }
  }

  /** The model promised background work that cannot exist, and was asked once to act instead. */
  public static void asyncPromiseCorrected() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.asyncPromiseCorrected = true;
    }
  }

  /**
   * One model request was attempted again after a transient failure.
   *
   * @param kind the failure that prompted it — {@code transient} or {@code timeout}
   */
  public static void modelRetried(String kind) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.modelRetries++;
    if (TIMEOUT_KIND.equals(kind)) {
      t.timeouts++;
    }
  }

  /** One tool call was attempted again. The tool name is safe to record; its arguments are not. */
  public static void toolRetried(String toolName, String kind) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.toolRetries++;
    if (TIMEOUT_KIND.equals(kind)) {
      t.timeouts++;
    }
  }

  /**
   * A write whose outcome was lost was reconciled against the record's own state.
   *
   * @param verdict {@code not_written}, {@code changed} or {@code unknown} — the verdict name only,
   *     never the fingerprint, which describes a document
   */
  public static void writeReconciled(String verdict) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.reconciliations.merge(verdict == null ? "unknown" : verdict, 1L, Long::sum);
  }

  /**
   * A tool's own postcondition was consulted after a lost answer.
   *
   * @param outcome {@code completed}, {@code not_written} or {@code unknown} — the verdict name
   *     only. How many of these come back {@code unknown} is what says which tool needs a verifier.
   */
  public static void writeVerified(String outcome) {
    Turn t = CURRENT.get();
    if (t == null) {
      return;
    }
    t.verifications.merge(outcome == null ? "unknown" : outcome, 1L, Long::sum);
  }

  /**
   * A write whose answer was lost was proven to have landed, and reported as a success.
   *
   * <p>The counterpart of {@code uncertainWrites}: together they say how much of the ambiguity this
   * mechanism actually resolves, which is the only way to tell a verifier that works from one that
   * silently always answers "unknown".
   */
  public static void writeConfirmedAfterLoss() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.confirmedAfterLoss++;
    }
  }

  /** A record this turn wrote to, about which nothing can be asserted. The number to watch. */
  public static void uncertainWrite() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.uncertainWrites++;
    }
  }

  /** The turn's time budget was spent, so something was refused rather than started. */
  public static void deadlineExceeded() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.deadlineExceeded = true;
    }
  }

  /** The tool budget ran out. Says nothing about whether the user's work was finished. */
  public static void capReached() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.capReached = true;
    }
  }

  public static void truncated() {
    Turn t = CURRENT.get();
    if (t != null) {
      t.truncated = true;
      t.outcome = "truncated";
    }
  }

  /** The formatted line, exposed so it can be asserted on without touching a log appender. */
  public static String format(Turn t) {
    long totalMillis = (System.nanoTime() - t.startedNanos) / 1_000_000L;
    StringBuilder sb = new StringBuilder();
    kv(sb, "outcome", t.outcome);
    kv(sb, "model", t.model == null ? "-" : t.model);
    kv(sb, "agentVersion", t.agentVersion == null ? "-" : t.agentVersion);
    kv(sb, "chained", String.valueOf(t.chained));
    kv(sb, "chainReset", t.chainReset);
    kv(sb, "prevChainTokens", String.valueOf(t.prevChainTokens));
    kv(sb, "projectedChainTokens", String.valueOf(t.projectedChainTokens));
    kv(sb, "chainGrowthTokens", String.valueOf(t.chainGrowthTokens));
    kv(sb, "contextHeadroomTokens", String.valueOf(t.contextHeadroomTokens));
    kv(sb, "contextLimitTokens", String.valueOf(t.contextLimitTokens));
    kv(sb, "continuityBytes", String.valueOf(t.continuityBytes));
    kv(sb, "continuityFromStore", String.valueOf(t.continuityFromStore));
    kv(sb, "resumed", String.valueOf(t.resumed));
    kv(sb, "truncated", String.valueOf(t.truncated));
    kv(sb, "capReached", String.valueOf(t.capReached));
    kv(sb, "totalMs", String.valueOf(totalMillis));
    kv(sb, "modelRequests", String.valueOf(t.requests));
    kv(sb, "modelMs", String.valueOf(sum(t.requestMillis)));
    kv(sb, "reqMs", join(t.requestMillis));
    kv(sb, "inputTokens", String.valueOf(t.inputTokens));
    kv(sb, "cachedInputTokens", String.valueOf(t.cachedInputTokens));
    kv(sb, "uncachedInputTokens", String.valueOf(Math.max(0, t.inputTokens - t.cachedInputTokens)));
    kv(sb, "cacheWriteTokens", String.valueOf(t.cacheWriteTokens));
    kv(sb, "outputTokens", String.valueOf(t.outputTokens));
    kv(sb, "reasoningTokens", String.valueOf(t.reasoningTokens));
    kv(sb, "totalTokens", String.valueOf(t.totalTokens));
    kv(sb, "firstInputTokens", String.valueOf(Math.max(0, t.firstInputTokens)));
    kv(sb, "lastInputTokens", String.valueOf(t.lastInputTokens));
    kv(
        sb,
        "contextGrowthTokens",
        String.valueOf(t.firstInputTokens < 0 ? 0 : t.lastInputTokens - t.firstInputTokens));
    kv(sb, "taskType", t.taskType);
    kv(sb, "playbookId", t.playbookId);
    kv(sb, "instructions", t.instructions);
    kv(sb, "resolvedGoal", t.resolvedGoal);
    kv(sb, "deterministicPrerequisite", String.valueOf(t.deterministicPrerequisite));
    kv(sb, "clarificationAsked", String.valueOf(t.clarificationAsked));
    kv(sb, "pageContextType", t.pageContextType);
    kv(sb, "delegatedSelection", String.valueOf(t.delegatedSelection));
    kv(sb, "bulkTargets", String.valueOf(t.bulkTargets));
    kv(sb, "asyncPromiseCorrected", String.valueOf(t.asyncPromiseCorrected));
    kv(sb, "toolCalls", String.valueOf(t.toolNames.size()));
    kv(sb, "toolBatches", String.valueOf(t.batches));
    kv(sb, "maxCallsPerResponse", String.valueOf(t.maxCallsPerResponse));
    kv(sb, "batchReordered", String.valueOf(t.reordered));
    kv(sb, "batchAborted", String.valueOf(t.aborted));
    kv(sb, "toolErrors", String.valueOf(t.toolErrors));
    kv(sb, "modelRetries", String.valueOf(t.modelRetries));
    kv(sb, "toolRetries", String.valueOf(t.toolRetries));
    kv(sb, "timeouts", String.valueOf(t.timeouts));
    kv(sb, "reconciliations", pairs(t.reconciliations));
    kv(sb, "verifications", pairs(t.verifications));
    kv(sb, "confirmedAfterLoss", String.valueOf(t.confirmedAfterLoss));
    kv(sb, "uncertainWrites", String.valueOf(t.uncertainWrites));
    kv(sb, "deadlineExceeded", String.valueOf(t.deadlineExceeded));
    kv(sb, "toolMs", String.valueOf(t.totalToolMillis));
    kv(sb, "toolBytes", String.valueOf(t.totalToolBytes));
    kv(sb, "tools", t.toolNames.isEmpty() ? "-" : String.join(",", t.toolNames));
    kv(sb, "toolMsEach", pairs(t.toolMillis));
    kv(sb, "toolBytesEach", pairs(t.toolBytes));
    return sb.toString().trim();
  }

  private static void kv(StringBuilder sb, String key, String value) {
    sb.append(key).append('=').append(value == null || value.isEmpty() ? "-" : value).append(' ');
  }

  private static long sum(List<Long> values) {
    long total = 0;
    for (Long v : values) {
      total += v == null ? 0 : v;
    }
    return total;
  }

  private static String join(List<Long> values) {
    if (values.isEmpty()) {
      return "-";
    }
    StringBuilder sb = new StringBuilder();
    for (Long v : values) {
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(v);
    }
    return sb.toString();
  }

  private static String pairs(Map<String, Long> values) {
    if (values.isEmpty()) {
      return "-";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Long> e : values.entrySet()) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(e.getKey()).append(':').append(e.getValue());
    }
    return sb.toString();
  }
}
