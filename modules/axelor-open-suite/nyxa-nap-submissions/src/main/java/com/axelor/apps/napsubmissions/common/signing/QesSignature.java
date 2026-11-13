package com.axelor.apps.napsubmissions.common.signing;

import java.time.Instant;

/**
 * The completed result of a cloud-QES signing request — everything the НАП transport needs for one
 * file, and nothing secret.
 *
 * <p>The НАП Declarations contract wants, per file, a detached <b>PKCS#7</b> signature ({@code
 * base64EncodedPkcs7}) and the signer's public certificate ({@code userSignatureBase64}). This DTO
 * carries exactly those, plus provenance (provider id, external request id, timestamp) and the
 * signed payload's SHA-256 so the caller can verify the signature was produced over the bytes it
 * intended to sign.
 *
 * <p><b>No private key.</b> Only the detached signature and the public X.509 certificate are
 * present.
 */
public final class QesSignature {

  private final byte[] detachedPkcs7;
  private final byte[] certificateBytes;
  private final String providerId;
  private final String externalRequestId;
  private final Instant signedAt;
  private final String signedPayloadSha256Hex;

  public QesSignature(
      byte[] detachedPkcs7,
      byte[] certificateBytes,
      String providerId,
      String externalRequestId,
      Instant signedAt,
      String signedPayloadSha256Hex) {
    this.detachedPkcs7 = detachedPkcs7 == null ? new byte[0] : detachedPkcs7.clone();
    this.certificateBytes = certificateBytes == null ? new byte[0] : certificateBytes.clone();
    this.providerId = providerId;
    this.externalRequestId = externalRequestId;
    this.signedAt = signedAt;
    this.signedPayloadSha256Hex = signedPayloadSha256Hex;
  }

  /**
   * Detached PKCS#7 signature bytes of the payload ({@code base64EncodedPkcs7} once
   * base64-encoded).
   */
  public byte[] getDetachedPkcs7() {
    return detachedPkcs7.clone();
  }

  /**
   * Public X.509 certificate bytes of the signer ({@code userSignatureBase64} once base64-encoded).
   */
  public byte[] getCertificateBytes() {
    return certificateBytes.clone();
  }

  /**
   * Identifier of the provider that produced this signature (e.g. {@code "mock"}, {@code
   * "btrust"}).
   */
  public String getProviderId() {
    return providerId;
  }

  public String getExternalRequestId() {
    return externalRequestId;
  }

  public Instant getSignedAt() {
    return signedAt;
  }

  /**
   * Lower-case hex SHA-256 of the payload the provider reports it signed. The caller MUST compare
   * this to the digest it requested and reject a mismatch — a signature over different bytes is not
   * a signature of the declaration.
   */
  public String getSignedPayloadSha256Hex() {
    return signedPayloadSha256Hex;
  }
}
