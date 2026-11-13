package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * Transport abstraction for the НАП <em>Declarations</em> API (submit + fetch result). This is
 * declarations-specific and must NOT be used for SAF-T. The two operations mirror the official
 * contract exactly; nothing else is assumed (no cancel, no polling format, no invented endpoints).
 *
 * <p>Processing is asynchronous: {@link #submit} returns entry identifiers only; the real outcome
 * is retrieved separately via {@link #getResult}. No implementation is wired yet — see {@link
 * UnsupportedDeclarationsApiClient}.
 */
public interface DeclarationsApiClient {

  /** POST the declaration files. Returns the НАП entry identifiers, or throws on a hard failure. */
  DeclarationsSubmitResponse submit(DeclarationsSubmitRequest request);

  /** Fetch the asynchronous processing result by documentId or entryNumber+entryDate. */
  DeclarationsResultResponse getResult(DeclarationsResultRequest request);
}
