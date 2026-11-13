package com.axelor.apps.aiaccountant.resilience;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A write tool that can check its own postcondition after an answer is lost.
 *
 * <h2>What the generic mechanism cannot do</h2>
 *
 * {@code AiWriteReconciliation} compares a fingerprint and can therefore prove exactly one thing:
 * that nothing changed. Everything else it reports as uncertain, which is honest and, for the
 * operations that matter most, unnecessarily so:
 *
 * <ul>
 *   <li><b>A creation has no fingerprint at all</b> — there was no record to describe before the
 *       call, so the generic path can only ever say "unknown". Every timed-out {@code create_move}
 *       would end as an uncertain outcome, and the user would be sent to look for a move they
 *       cannot name.
 *   <li><b>A lifecycle transition has an exact postcondition</b> — the invoice is VENTILATED or it
 *       is not, and after a lost answer that single field settles the question completely. The
 *       fingerprint says "the record moved", which is true and useless.
 * </ul>
 *
 * <p>So a tool that knows what its own success looks like implements this and answers precisely.
 * What it must not do is <em>search</em>: the identity it checks has to have been established
 * before the call — a primary key the server generated and recorded through {@code AiWriteEffects},
 * a unique business key the operation itself enforces, or a state this call and nothing else could
 * have produced. Finding a record that looks like what was asked for is a guess, and a wrong
 * "completed" is worse than any uncertainty because it ends the conversation.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Called <b>only</b> after an ambiguous failure — a timeout or a transport fault. Never after
 *       a business refusal, which answered the question itself.
 *   <li>Must be company-scoped, exactly as {@code AiTargetIdentity} must: a record of another
 *       company proves nothing about this one's write.
 *   <li>Must read the database, not the session. The persistence context is cleared before this is
 *       called, so an ordinary query is already authoritative.
 *   <li>Must not throw. Anything unexpected is {@link AiWriteOutcome#unknown()}, which falls back
 *       to the generic reconciliation.
 *   <li>Returning {@link AiWriteOutcome#completed} means the turn reports an ordinary success, so
 *       the value carried with it has to be the <em>same shape</em> the tool returns normally.
 * </ul>
 */
public interface AiWriteVerifier {

  /**
   * Whatever this tool will need to know about the record as it stood before the call.
   *
   * <h2>Why a postcondition usually needs a precondition</h2>
   *
   * "The move is ACCOUNTED" settles a posting completely. "The move is DAYBOOK" settles nothing on
   * its own: in a company that runs a daybook, the first post takes NEW to DAYBOOK and the second
   * takes DAYBOOK to ACCOUNTED, so the same reading means "it worked" or "nothing happened"
   * depending on where it started. Without the before-state a verifier has to answer unknown for
   * exactly the cases it exists to resolve.
   *
   * <p>So this runs immediately before {@code execute}, while the answer is still certain, and
   * whatever it returns comes back on {@link AiWriteProbe#toolState()}. Keep it cheap — it runs on
   * every write, including the overwhelming majority that succeed — and keep it a value, not a
   * managed entity: the persistence context is cleared before the verifier runs, so an entity
   * captured here would be detached by the time it is read.
   *
   * <p>Returning null is fine and is the default. A tool whose postcondition needs no baseline — a
   * creation checking an id, an idempotent flag-setter checking flags — simply does not override
   * it.
   */
  default Object beforeExecute(JsonNode args) {
    return null;
  }

  /**
   * Did the write this call was making actually land?
   *
   * @param args the same arguments {@code execute} received
   * @param probe what was captured before the call — the canonical target, its fingerprint, this
   *     tool's own {@link AiWriteProbe#toolState() baseline}, and any record ids the attempt itself
   *     reported
   */
  AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe);
}
