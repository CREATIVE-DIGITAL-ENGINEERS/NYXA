package com.axelor.apps.napsubmissions.common.signing;

/**
 * Provider-neutral, <b>asynchronous</b> cloud-QES signing seam. This is the ONLY contract the
 * submission layer depends on for signing; a concrete provider (the deterministic mock today,
 * B-Trust or Evrotrust later) is chosen by configuration and can be swapped without touching the
 * D1/D6 submission service.
 *
 * <p>The flow mirrors how real cloud-QES works:
 *
 * <ol>
 *   <li>{@link #startSigning} with the exact payload bytes → returns an external request id;
 *   <li>the signer authorizes out-of-band (phone/app);
 *   <li>{@link #pollStatus} until a terminal {@link QesSigningStatus}; on {@link
 *       QesSigningStatus#COMPLETED} the response carries the detached PKCS#7 + public certificate.
 * </ol>
 *
 * <p><b>The private key never enters Nyxa.</b> This contract neither accepts nor returns key
 * material: inputs are bytes + non-secret references, outputs are a detached signature and a public
 * certificate. Implementations must fail closed (throw {@link QesException} with {@link
 * QesException.Category#CONFIG}) rather than silently no-op when unconfigured.
 */
public interface CloudQesProvider {

  /**
   * Stable identifier of this provider (e.g. {@code "mock"}, {@code "btrust"}, {@code
   * "evrotrust"}).
   */
  String getProviderId();

  /**
   * Start an asynchronous signing request for the exact bytes in {@code request}. The returned
   * {@link QesSigningSession} carries the external request id used to poll, and is bound to {@code
   * request.getPayloadSha256Hex()}.
   *
   * @throws QesException on configuration/transport/validation problems
   */
  QesSigningSession startSigning(QesSigningRequest request);

  /**
   * Poll the current status of a previously started signing request. On {@link
   * QesSigningStatus#COMPLETED} the response's {@link QesSignature} is present.
   *
   * @throws QesException when the provider is unconfigured, the id is unknown, or transport fails
   */
  QesSigningStatusResponse pollStatus(String externalRequestId);
}
