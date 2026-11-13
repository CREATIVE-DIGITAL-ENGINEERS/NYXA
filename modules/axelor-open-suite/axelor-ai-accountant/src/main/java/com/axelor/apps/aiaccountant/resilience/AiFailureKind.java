package com.axelor.apps.aiaccountant.resilience;

/**
 * What kind of failure something was, for the one decision that depends on it: may this be tried
 * again, and does anybody know whether it took effect?
 *
 * <h2>Why three and not two</h2>
 *
 * A retry policy needs "could this work next time?". A <em>write</em> policy needs a second, harder
 * question: "did it already work?". Those are not the same axis, and collapsing them is how a
 * timeout ends up retried — the request that timed out is exactly the one most likely to have
 * committed, because the server had it long enough to be slow.
 *
 * <p>So a transport that hangs up before the server could have read the request is {@link
 * #TRANSIENT}: nothing happened, and repeating it is free. A transport that gave up <em>while</em>
 * the server was working is {@link #TIMEOUT}: something may well have happened, and repeating it
 * blindly is how one payment becomes two.
 */
public enum AiFailureKind {

  /**
   * The call did not reach a server that could have acted on it, or the server explicitly said "not
   * now" — a connection refused, a 429, a 503. Safe to repeat: there is nothing on the other side
   * to duplicate.
   */
  TRANSIENT,

  /**
   * The deadline expired with the outcome unknown. The request may have been read, executed and
   * committed; only the answer was lost.
   *
   * <p>Never retryable on its own. A write that ends here has to be reconciled against the record's
   * own state before anything else is decided about it — see {@link AiWriteReconciliation}.
   */
  TIMEOUT,

  /**
   * The call was answered, and the answer was no. Bad arguments, a refusal, a business rule, a
   * programming error. Repeating it produces the same answer, so the only useful move is to report
   * it.
   */
  PERMANENT;

  /** True when repeating the call could conceivably produce a different answer. */
  public boolean isRetryable() {
    return this == TRANSIENT;
  }

  /**
   * True when the outcome of the call is genuinely unknown.
   *
   * <p>The load-bearing property of this enum. Everything that must not guess about a write reads
   * this and stops.
   */
  public boolean isAmbiguous() {
    return this == TIMEOUT;
  }

  /** The wire form used in refusal envelopes and metric lines. */
  public String wire() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
