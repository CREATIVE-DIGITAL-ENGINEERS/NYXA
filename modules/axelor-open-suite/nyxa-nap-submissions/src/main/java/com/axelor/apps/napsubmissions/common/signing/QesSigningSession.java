package com.axelor.apps.napsubmissions.common.signing;

import java.time.Instant;

/**
 * What {@link CloudQesProvider#startSigning} returns: a handle to a started asynchronous signing
 * request. It carries the provider's external request identifier (the key for later {@link
 * CloudQesProvider#pollStatus} calls) and the payload digest the request is bound to.
 *
 * <p>A freshly started request is normally {@link QesSigningStatus#PENDING}; the real outcome is
 * retrieved by polling.
 */
public final class QesSigningSession {

  private final String externalRequestId;
  private final QesSigningStatus status;
  private final String payloadSha256Hex;
  private final Instant createdAt;

  public QesSigningSession(
      String externalRequestId,
      QesSigningStatus status,
      String payloadSha256Hex,
      Instant createdAt) {
    this.externalRequestId = externalRequestId;
    this.status = status;
    this.payloadSha256Hex = payloadSha256Hex;
    this.createdAt = createdAt;
  }

  /** Provider-assigned identifier of the signing request; the key used to poll its status. */
  public String getExternalRequestId() {
    return externalRequestId;
  }

  public QesSigningStatus getStatus() {
    return status;
  }

  /** Lower-case hex SHA-256 of the payload this signing request is bound to. */
  public String getPayloadSha256Hex() {
    return payloadSha256Hex;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
