package com.axelor.apps.aiaccountant.resilience;

/**
 * What an operation-specific postcondition established about a write whose answer was lost.
 *
 * <h2>Three answers, and only one of them is new</h2>
 *
 * The generic fingerprint reconciliation can prove a write did <em>not</em> happen, and nothing
 * else. That is enough to decide whether a retry is safe, but it leaves the commonest good case
 * unclaimed: the write did land, the connection died carrying the answer back, and the record is
 * sitting in exactly the state the user asked for. Reporting that as "unknown — go and check" is
 * honest but wasteful, and it teaches people to ignore the warning that matters.
 *
 * <p>So a tool that can check its own postcondition <em>exactly</em> — the invoice is VENTILATED,
 * the move exists under the id we recorded, the account's two flags are on — gets to say {@link
 * #completed}, and the turn reports an ordinary success with the tool's own result shape.
 *
 * <h2>What may produce COMPLETED</h2>
 *
 * Only a check on an identity established <em>before</em> the call: a primary key the server
 * generated, a unique business key the operation itself enforces, or a lifecycle state that this
 * operation and nothing else could have produced within the turn. A search that merely finds a
 * plausible record is not a postcondition — it is a guess with a database behind it, and a wrong
 * COMPLETED is worse than any uncertainty, because it ends the conversation.
 *
 * <p>When in doubt the answer is {@link #unknown}, which falls back to the generic reconciliation
 * and then to an uncertain outcome.
 */
public final class AiWriteOutcome {

  /** Which of the three answers this is. */
  public enum Status {
    /** The intended write is present in the database. Reported as an ordinary success. */
    COMPLETED,
    /** The intended write is provably absent. A bounded retry is permitted. */
    NOT_WRITTEN,
    /** Nothing could be established. The caller falls back to the generic reconciliation. */
    UNKNOWN
  }

  private static final AiWriteOutcome NOT_WRITTEN = new AiWriteOutcome(Status.NOT_WRITTEN, null);
  private static final AiWriteOutcome UNKNOWN = new AiWriteOutcome(Status.UNKNOWN, null);

  private final Status status;
  private final Object result;

  private AiWriteOutcome(Status status, Object result) {
    this.status = status;
    this.result = result;
  }

  /**
   * The write completed.
   *
   * @param result the value the tool would have returned had the answer arrived — the <em>same
   *     shape</em>, built from the record's real state. Returning a different shape here would make
   *     a published tool contract depend on whether the network held up, which is precisely the
   *     kind of difference nothing downstream is written to expect.
   */
  public static AiWriteOutcome completed(Object result) {
    return new AiWriteOutcome(Status.COMPLETED, result);
  }

  /** The write is provably absent: the record does not exist, or the state is untouched. */
  public static AiWriteOutcome notWritten() {
    return NOT_WRITTEN;
  }

  /** Nothing could be established — no identity, no readable record, or the check itself failed. */
  public static AiWriteOutcome unknown() {
    return UNKNOWN;
  }

  public Status status() {
    return status;
  }

  public Object result() {
    return result;
  }

  public boolean isCompleted() {
    return status == Status.COMPLETED;
  }

  public boolean isNotWritten() {
    return status == Status.NOT_WRITTEN;
  }

  public boolean isUnknown() {
    return status == Status.UNKNOWN;
  }

  /** The wire form used in refusal envelopes and metric lines. */
  public String wire() {
    return status.name().toLowerCase(java.util.Locale.ROOT);
  }
}
