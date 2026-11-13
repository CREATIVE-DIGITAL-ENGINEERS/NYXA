package com.axelor.apps.bglocalization.service.vat.submission;

/**
 * VAT-specific submission transport seam. Kept entirely separate from the official {@code
 * NraDeclarationsApiClient} (nyxa-nap-submissions): the real NRA VAT API contract does not exist
 * yet, so the only implementation is a LOCAL MOCK. VAT is deliberately NOT part of the official
 * Declarations API document-type matrix.
 */
public interface VatSubmissionGateway {

  /** Target code shown in the UI (currently always {@code LOCAL_MOCK}). */
  String targetCode();

  /** Whether sending is enabled AND the configured URL is a safe local endpoint. */
  boolean isUsable();

  /** A short, user-facing description of the configured target (for confirmations/messages). */
  String describeTarget();

  /**
   * Send a package for the first time. A timeout yields {@link VatSubmissionResult#outcomeUnknown}.
   */
  VatSubmissionResult submit(VatSubmissionRequest request);

  /** Poll the status of a previously accepted submission. Safe to retry. */
  VatSubmissionResult checkStatus(String submissionRef);
}
