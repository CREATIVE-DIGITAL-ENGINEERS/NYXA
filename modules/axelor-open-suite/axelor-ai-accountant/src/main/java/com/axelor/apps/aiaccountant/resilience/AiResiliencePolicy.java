package com.axelor.apps.aiaccountant.resilience;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.google.inject.Singleton;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;

/**
 * The one place that says how long anything may take and how often it may be tried again.
 *
 * <h2>Why it is central</h2>
 *
 * Before this, every outbound call carried its own answer. The chat client capped itself at five
 * attempts with a thirty-second backoff, so a turn could spend well over a minute of a user's
 * attention on retries alone and still fail. The token request borrowed the chat's 120-second
 * deadline for a call that either answers in under a second or is broken. The router had its own
 * short deadline, correctly, but by coincidence rather than by rule. And nothing anywhere bounded a
 * <em>turn</em>: five iterations × (retries × per-request timeout) had no ceiling at all, so the
 * worst case was minutes with no answer and no way to say so.
 *
 * <p>Every one of those numbers now comes from here, so the ceiling is visible in one class and a
 * request cannot be added without deciding what its deadline is.
 *
 * <h2>The rules the numbers encode</h2>
 *
 * <ul>
 *   <li><b>A per-turn deadline bounds everything.</b> Individual request deadlines say how long one
 *       call may hang; the turn deadline says how long the user waits in total. A retry that cannot
 *       finish inside the remaining budget is not started, and an iteration that cannot start is
 *       reported rather than attempted.
 *   <li><b>Reads retry, writes do not.</b> {@link #readRetryAttempts()} is small and applies only
 *       to {@link AiFailureKind#TRANSIENT}. A write gets one attempt, and what happens after an
 *       ambiguous failure is decided by reconciliation, never by a counter.
 *   <li><b>Backoff is bounded by what is left.</b> The old curve doubled to a 30s cap, which is
 *       longer than most of the turn budget; it is now capped by the policy AND clipped to the
 *       remaining turn deadline, so backing off can never be the reason a turn produces nothing.
 * </ul>
 *
 * <p>Everything is overridable from {@code axelor-config.properties} with dotted keys, so the
 * numbers can be tuned per deployment from the container environment without a rebuild.
 */
@Singleton
public class AiResiliencePolicy {

  /**
   * The ceiling on any single backoff sleep, whatever the curve or a {@code Retry-After} says.
   *
   * <p>Not configurable, deliberately. A backoff longer than this is indistinguishable from a hang
   * to the person waiting, and the remaining-deadline clip below is the mechanism that is supposed
   * to shorten waits — not an operator's ability to raise a cap.
   */
  public static final long MAX_BACKOFF_MILLIS = 8_000L;

  private final AiAccountantConfig config;

  @Inject
  public AiResiliencePolicy(AiAccountantConfig config) {
    this.config = config;
  }

  /** A policy reading the default configuration, for callers outside Guice. */
  public AiResiliencePolicy() {
    this(new AiAccountantConfig());
  }

  // ── deadlines ──────────────────────────────────────────────────────────────

  /**
   * How long one whole chat turn may take, including every model request, retry, backoff and tool.
   *
   * <p>The number the user actually experiences. Past it the turn stops asking for more work and
   * answers with what it has — which is a truthful, bounded answer rather than a browser waiting on
   * a request nobody is going to complete.
   */
  public long turnDeadlineMillis() {
    int seconds = read(config::turnDeadlineSeconds, 180);
    // 0 is a documented value meaning "no bound", and it has to survive the floor below.
    return seconds <= 0 ? 0L : Math.max(1_000L, seconds * 1000L);
  }

  /** Per-request deadline for a model call, in seconds, as the HTTP client wants it. */
  public int modelRequestTimeoutSeconds() {
    return Math.max(1, read(config::requestTimeout, 120));
  }

  public int modelConnectTimeoutSeconds() {
    return Math.max(1, read(config::connectTimeout, 15));
  }

  /**
   * Deadline for an Entra token request.
   *
   * <p>Its own number rather than the chat's. A token endpoint answers in well under a second or is
   * unavailable; giving it the chat's two minutes meant a broken identity provider could consume
   * the entire turn budget before the first model request was even attempted.
   */
  public int tokenRequestTimeoutSeconds() {
    return Math.max(1, read(config::tokenRequestTimeout, 20));
  }

  public int tokenConnectTimeoutSeconds() {
    return Math.max(1, Math.min(modelConnectTimeoutSeconds(), tokenRequestTimeoutSeconds()));
  }

  public int routerRequestTimeoutSeconds() {
    return Math.max(1, read(config::routerRequestTimeout, 15));
  }

  public int routerConnectTimeoutSeconds() {
    return Math.max(1, read(config::routerConnectTimeout, 10));
  }

  // ── iteration and attempt bounds ───────────────────────────────────────────

  /** How many model round trips one turn may make. */
  public int maxModelIterations() {
    return Math.max(1, read(config::maxToolIterations, 5));
  }

  /**
   * Total attempts for one model request, retries included.
   *
   * <p>Capped at three rather than the previous five. A transient Azure failure clears on the
   * second attempt or it is not transient; attempts four and five only ever spent the user's time
   * to arrive at the same answer.
   */
  public int maxModelAttempts() {
    return clamp(read(config::maxRetries, 3) + 1, 1, 3);
  }

  /**
   * Extra attempts a READ tool gets after a transient failure.
   *
   * <p>Small by design. A read is safe to repeat, but a tool that fails twice in a row is failing
   * for a reason the third call will not change, and the model is perfectly able to ask again.
   */
  public int readRetryAttempts() {
    return clamp(read(config::readRetryAttempts, 1), 0, 2);
  }

  /**
   * Extra attempts a WRITE tool gets, and only ever after Java has proven nothing was written.
   *
   * <p>Zero or one. There is no configuration that makes a write retry on an unknown outcome; the
   * proof is a precondition in code, not a setting.
   */
  public int writeRetryAttemptsWhenProvenUnwritten() {
    return clamp(read(config::writeRetryAttempts, 1), 0, 1);
  }

  // ── backoff ────────────────────────────────────────────────────────────────

  /**
   * How long to wait before attempt {@code attempt + 1}, bounded by everything that could shorten
   * it.
   *
   * <p>Exponential with full jitter, capped at {@link #MAX_BACKOFF_MILLIS}, overridden by a numeric
   * {@code Retry-After}, and finally clipped to what is left of the turn. The clip is the part that
   * matters: a service asking for a sixty-second wait is asking for more than the turn has, and
   * honouring it would trade a fast honest failure for a slow one.
   *
   * @param attempt 1 for the wait after the first failure
   * @param retryAfter the header value, or null
   * @param remainingMillis what is left of the turn deadline, or {@link Long#MAX_VALUE} when
   *     unbounded
   * @return milliseconds to sleep; 0 means do not wait, and a negative budget means do not retry
   */
  public long backoffMillis(int attempt, String retryAfter, long remainingMillis) {
    long base = 500L;
    long exponential = Math.min(MAX_BACKOFF_MILLIS, base * (1L << Math.max(0, attempt - 1)));
    long jittered =
        exponential / 2 + (long) (ThreadLocalRandom.current().nextDouble() * exponential / 2);
    long wait = jittered;
    if (retryAfter != null) {
      try {
        wait = Math.max(0L, Long.parseLong(retryAfter.trim()) * 1000L);
      } catch (NumberFormatException ignore) {
        // HTTP-date form. Keeping the computed delay is closer to the server's intent than parsing
        // a date badly, and the clip below bounds it either way.
      }
    }
    wait = Math.min(wait, MAX_BACKOFF_MILLIS);
    if (remainingMillis == Long.MAX_VALUE) {
      return wait;
    }
    // Leave room for the attempt the wait exists to enable. A backoff that consumes the whole
    // remaining budget buys nothing.
    long usable = remainingMillis - 250L;
    return usable <= 0 ? 0L : Math.min(wait, usable);
  }

  /**
   * Whether another attempt is worth starting at all.
   *
   * @param remainingMillis what the turn has left, or {@link Long#MAX_VALUE} when unbounded
   */
  public boolean hasTimeForAnotherAttempt(long remainingMillis) {
    return remainingMillis == Long.MAX_VALUE || remainingMillis > 1_000L;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  /**
   * One configured number, or its default when the settings cannot be read at all.
   *
   * <p>Not defensiveness for its own sake: this policy is also constructed directly, outside Guice
   * and sometimes outside a running application, and a missing {@code AppSettings} must not be the
   * reason a tool call fails. Falling back to the same default the accessor documents keeps the
   * bounds in force where they matter most — when something is already wrong.
   */
  private static int read(java.util.function.IntSupplier accessor, int fallback) {
    try {
      return accessor.getAsInt();
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
