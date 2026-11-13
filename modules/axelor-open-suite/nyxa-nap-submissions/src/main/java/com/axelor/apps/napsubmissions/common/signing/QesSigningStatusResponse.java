package com.axelor.apps.napsubmissions.common.signing;

/**
 * What {@link CloudQesProvider#pollStatus} returns: the current {@link QesSigningStatus} of an
 * external signing request, plus the {@link QesSignature} when (and only when) it is {@link
 * QesSigningStatus#COMPLETED}, or a short human-readable {@code reason} for a non-success terminal
 * state.
 */
public final class QesSigningStatusResponse {

  private final String externalRequestId;
  private final QesSigningStatus status;
  private final QesSignature signature;
  private final String reason;

  private QesSigningStatusResponse(
      String externalRequestId, QesSigningStatus status, QesSignature signature, String reason) {
    this.externalRequestId = externalRequestId;
    this.status = status;
    this.signature = signature;
    this.reason = reason;
  }

  public static QesSigningStatusResponse pending(String externalRequestId) {
    return new QesSigningStatusResponse(externalRequestId, QesSigningStatus.PENDING, null, null);
  }

  public static QesSigningStatusResponse completed(
      String externalRequestId, QesSignature signature) {
    return new QesSigningStatusResponse(
        externalRequestId, QesSigningStatus.COMPLETED, signature, null);
  }

  public static QesSigningStatusResponse rejected(String externalRequestId, String reason) {
    return new QesSigningStatusResponse(externalRequestId, QesSigningStatus.REJECTED, null, reason);
  }

  public static QesSigningStatusResponse failed(String externalRequestId, String reason) {
    return new QesSigningStatusResponse(externalRequestId, QesSigningStatus.FAILED, null, reason);
  }

  public static QesSigningStatusResponse expired(String externalRequestId, String reason) {
    return new QesSigningStatusResponse(externalRequestId, QesSigningStatus.EXPIRED, null, reason);
  }

  public String getExternalRequestId() {
    return externalRequestId;
  }

  public QesSigningStatus getStatus() {
    return status;
  }

  /**
   * The signature, present only when {@link #getStatus()} is {@link QesSigningStatus#COMPLETED}.
   */
  public QesSignature getSignature() {
    return signature;
  }

  /** Short human-readable reason for a REJECTED / FAILED / EXPIRED outcome; may be {@code null}. */
  public String getReason() {
    return reason;
  }
}
