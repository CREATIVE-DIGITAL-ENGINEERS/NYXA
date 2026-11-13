package com.axelor.apps.aiaccountant.tool;

/**
 * Answers "has this record changed?" for {@link AiWriteRepeatGuard}, without this module knowing
 * what the record is.
 *
 * <h2>Why a fingerprint rather than a read counter</h2>
 *
 * The guard originally released a remembered failure when the model had <em>read</em> the target
 * again. That proxy is wrong in both directions. A read that returns exactly the same record
 * released the block, so the identical call ran and failed identically — the loop it exists to
 * break, one round trip longer. And a genuine change made outside the chat — the accountant fixing
 * the configuration in another tab and saying "готово" — released nothing at all, because no read
 * had happened, so the assistant kept refusing a call that would now have worked and started
 * inventing reasons why.
 *
 * <p>Both cases are the same mistake: counting looks instead of looking at state. A fingerprint
 * answers the actual question. If it differs from the one taken when the write failed, something
 * relevant changed and the attempt is worth making again; if it is identical, nothing changed and
 * the same call would fail the same way no matter how many times the record was read.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Implementations are discovered by {@code MetaScanner}, exactly like {@code AiTool} and
 *       {@code AiPageRecordResolver}, so a feature module contributes one without this module
 *       depending on it.
 *   <li>{@link #fingerprint} returns null when this implementation does not handle that target key,
 *       or when the record cannot be read. Null means "unknown", and the guard then falls back to
 *       its older read-based behaviour rather than guessing.
 *   <li>It must cover everything a refusal can depend on, not only the record itself. A ventilation
 *       blocked by a payment condition is released by that condition changing, not by the invoice
 *       changing.
 *   <li>It must be cheap — one indexed query — and must not throw.
 *   <li>The value is opaque, never shown to the model and never logged, so it may be any stable
 *       string. It must not embed anything confidential: it is compared, not read.
 * </ul>
 */
public interface AiWriteTargetState {

  /**
   * @param targetKey the guard's target key, e.g. {@code upload:42}
   * @return an opaque value that changes whenever relevant state changes, or null if not handled
   */
  String fingerprint(String targetKey);
}
