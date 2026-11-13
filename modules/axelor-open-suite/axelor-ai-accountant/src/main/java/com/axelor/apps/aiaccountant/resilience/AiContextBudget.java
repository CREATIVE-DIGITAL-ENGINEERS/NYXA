package com.axelor.apps.aiaccountant.resilience;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.google.inject.Singleton;
import javax.inject.Inject;

/**
 * The one decision about whether the retained conversation is still affordable — and still legal.
 *
 * <h2>What was missing</h2>
 *
 * There was one number: reset the chain when the previous turn's last request exceeded 40,000 input
 * tokens. It is a good cost heuristic and it was the only thing here, which left three holes.
 *
 * <ol>
 *   <li><b>Nothing knew the provider's limit.</b> 40,000 is a judgement about what is worth paying
 *       for, not a boundary. A conversation that grew past the model's context window would not be
 *       trimmed or degraded — the request would be refused and the user would lose their turn, with
 *       nothing in the system having seen it coming.
 *   <li><b>The measurement was always one turn stale.</b> {@code lastInputTokens} is the last
 *       request of the <em>previous</em> turn. The turn about to run adds the user's message, the
 *       instruction documents, and then a tool result on every iteration — measured at around three
 *       requests per turn, each larger than the last. Checking a bound against the smallest request
 *       of the previous turn is how a chain sails past it.
 *   <li><b>Growth was invisible.</b> One number cannot say whether a chain is creeping up by two
 *       thousand tokens a turn or by twenty, so the decision could never be made early.
 * </ol>
 *
 * <h2>What it does instead</h2>
 *
 * It projects. Given what the previous turn measured and how fast the chain has been growing, it
 * estimates the largest request the turn about to run will make, and resets if that projection
 * crosses either bound. Resetting one turn early costs one re-sent operating procedure, most of
 * which the prompt cache absorbs. Resetting one turn late costs the turn.
 *
 * <h2>Deliberately arithmetic, deliberately pessimistic</h2>
 *
 * No model call, no summarisation, no estimate of what the text "probably" costs. Every input is a
 * figure the provider itself reported in a {@code usage} object, and every unknown resolves toward
 * resetting — an absent measurement, an absurd one, a client that sent a number it had no business
 * changing. A reset is cheap and recoverable; a refused request is neither.
 */
@Singleton
public class AiContextBudget {

  /**
   * What one turn adds on top of the retained history, before any tool result.
   *
   * <p>The user's message, the operating procedure on a fresh chain, the domain guide, the playbook
   * and the context block. Measured at roughly ten thousand tokens for a first request; kept as a
   * floor under the projection so that a chain with no growth history is still assumed to grow.
   */
  private static final int TURN_FLOOR_TOKENS = 10_000;

  /**
   * How much bigger the last request of a turn is than its first, as a multiplier.
   *
   * <p>A turn is a loop: every iteration sends the previous request's contents plus a tool result
   * back. Three requests per turn was the measured average and each carries the last one's payload,
   * so the final request is materially larger than the first. 1.6 is deliberately a round,
   * conservative figure — the point is that the projection is of the turn's <em>peak</em>, not of
   * its opening.
   */
  private static final double TURN_PEAK_FACTOR = 1.6d;

  private final AiAccountantConfig config;

  @Inject
  public AiContextBudget(AiAccountantConfig config) {
    this.config = config;
  }

  /** A budget reading the default configuration, for callers outside Guice. */
  public AiContextBudget() {
    this(new AiAccountantConfig());
  }

  /**
   * The model's context window, in tokens.
   *
   * <p>Configured rather than discovered: the Responses API reports usage but not the window, and
   * guessing it from a model name would break silently the day the agent is pointed at another
   * deployment. The fallback (400,000) matches this deployment's confirmed gpt-5-mini ceiling —
   * under-stating the window costs an early reset, over-stating it costs a refused request.
   */
  public int contextWindowTokens() {
    return Math.max(8_000, read(config::contextWindowTokens, 400_000));
  }

  /**
   * Tokens held back from the window for what the request itself will need.
   *
   * <p>The window has to hold the retained history <em>and</em> this turn's instructions, tool
   * results and the model's own answer including its reasoning. Only the first of those is what the
   * bound is checked against, so the rest has to be reserved rather than discovered when the
   * request is refused.
   */
  public int reservedTokens() {
    return Math.max(1_000, read(config::contextReserveTokens, 32_000));
  }

  /**
   * The largest a request may be projected to reach before the chain is reset.
   *
   * <p>Clamped to whichever is smaller: the model's real headroom ({@link #contextWindowTokens()}
   * minus {@link #reservedTokens()}), and the administrator's self-imposed conversation-memory
   * preset ({@link AiAccountantConfig#conversationMemoryTokens()}). The preset changes how much the
   * assistant remembers, but it can never itself push a request past what the model can actually
   * accept — that physical bound always wins.
   */
  public int usableChainTokens() {
    int physical = Math.max(1_000, contextWindowTokens() - reservedTokens());
    return Math.max(1_000, Math.min(physical, config.conversationMemoryTokens()));
  }

  /**
   * The largest request the turn about to run is expected to make.
   *
   * <p>{@code previous} is what the last request of the previous turn actually measured — a real
   * figure the provider reported, already reflecting the fixed per-turn overhead (tool schemas, the
   * operating procedure, and so on), and not itself uncertain. What is uncertain is only the
   * <em>growth</em> this turn about to run will add on top of it — the observed per-turn growth, or
   * a floor when there is no history to observe — so only that part is inflated by the peak factor,
   * because a turn's last request is bigger than its first. Multiplying the previous figure again
   * as well would double-count overhead the provider has already told us is there.
   *
   * @param previousInputTokens the previous turn's last measured input size, 0 when unknown
   * @param perTurnGrowthTokens how much the chain grew over the previous turn, 0 when unknown
   */
  public int projectedPeakTokens(int previousInputTokens, int perTurnGrowthTokens) {
    if (previousInputTokens <= 0) {
      return 0;
    }
    int growth = Math.max(TURN_FLOOR_TOKENS, Math.max(0, perTurnGrowthTokens));
    long projected = previousInputTokens + (long) (growth * TURN_PEAK_FACTOR);
    return (int) Math.min(Integer.MAX_VALUE, projected);
  }

  /**
   * Whether the chain may be continued, and if not, which bound stopped it.
   *
   * <h2>Order, and what no longer happens here</h2>
   *
   * The context limit is checked first because it is the only one that is not a preference: past it
   * the provider refuses and the turn is lost. The projection then catches the chain that is under
   * that bound now and will not be by the end of this turn about to run.
   *
   * <p>A cost-only threshold used to be checked here too — reset once the previous turn's last
   * request grew past a configured figure, whether or not the window was anywhere close. It is
   * removed: production v1 resets the chain only when it is genuinely approaching the hard limit
   * (this method's other two reasons), because a reset drops the model's retained context and hands
   * back a continuity note in its place, for a reason the user never asked for and, on a purely
   * cost-driven reset, never even approached running out of room.
   *
   * @param previousInputTokens the previous turn's last measured input size, 0 when unknown
   * @param perTurnGrowthTokens how much the chain grew over the previous turn, 0 when unknown
   * @return {@link AiChainResetReason#NONE} to continue, or the bound that was crossed
   */
  public AiChainResetReason verdict(int previousInputTokens, int perTurnGrowthTokens) {
    if (previousInputTokens <= 0) {
      // Nothing was measured. That is ordinary on the first chained turn after an upgrade, and it
      // is not evidence of a large chain, so it is not a reason to reset by itself.
      return AiChainResetReason.NONE;
    }
    int usable = usableChainTokens();
    if (previousInputTokens >= usable) {
      // Already over. The next request cannot be smaller than this one, so there is nothing to
      // project.
      return AiChainResetReason.CONTEXT_LIMIT;
    }
    int projected = projectedPeakTokens(previousInputTokens, perTurnGrowthTokens);
    if (projected >= usable) {
      // Inside both bounds now, over one of them by the end of this turn. Resetting here is the
      // whole point: the alternative is discovering it when the provider refuses the third request
      // of a turn that has already written to two invoices.
      return AiChainResetReason.GROWTH_PROJECTION;
    }
    return AiChainResetReason.NONE;
  }

  /** Headroom left before the context limit, for the metric line. Never negative. */
  public int headroomTokens(int previousInputTokens) {
    return Math.max(0, usableChainTokens() - Math.max(0, previousInputTokens));
  }

  private static int read(java.util.function.IntSupplier accessor, int fallback) {
    try {
      return accessor.getAsInt();
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
