package com.axelor.apps.aiaccountant.context;

/**
 * Thrown by an {@link AiTargetRelationship} when it cannot yet answer — not because the two
 * references are unrelated, but because the evidence it would compare them against has not been
 * committed yet.
 *
 * <h2>Why this is not just a refusal</h2>
 *
 * {@link AiTargetRegistry#relatedTarget} treats an ordinary exception from a relationship resolver
 * as "no relationship proven", which is right when the resolver could not make sense of the
 * pairing. It is the wrong answer for "the document exists but its OCR extraction has not finished
 * yet": that is a fact about timing, not about the two records, and it should never be
 * indistinguishable from a contradicting ЕИК. Silently downgrading it to an ordinary refusal
 * previously meant a caller had no way to tell a genuinely wrong request apart from one that would
 * succeed a moment later.
 *
 * <p>{@link AiTargetRegistry#relatedTarget} lets this type escape rather than swallowing it, so
 * {@code AiWriteAuthorisation.checkTargets} can classify the refusal as retryable — no sleep, no
 * retry loop, just an honest, differently-coded "not yet" instead of a hard "no".
 */
public class AiEvidenceNotReadyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AiEvidenceNotReadyException(String message) {
    super(message);
  }
}
