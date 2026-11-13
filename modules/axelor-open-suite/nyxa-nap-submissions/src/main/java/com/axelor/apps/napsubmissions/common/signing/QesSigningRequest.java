package com.axelor.apps.napsubmissions.common.signing;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;

/**
 * Immutable input to {@link CloudQesProvider#startSigning}: the <b>exact bytes to be signed</b> and
 * nothing secret. The SHA-256 of those bytes is computed once here and travels with the request, so
 * the digest can never drift from the content it describes — the provider binds its signing request
 * to this value and the caller later verifies the returned {@link QesSignature} against it.
 *
 * <p><b>No private key.</b> This DTO deliberately has no field for key material. A cloud-QES signer
 * holds the private key on the provider side; Nyxa supplies only the bytes and (optionally) a
 * non-secret reference to the signer's profile.
 */
public final class QesSigningRequest {

  private final byte[] payloadBytes;
  private final String payloadSha256Hex;
  private final String fileName;
  private final String signerProfileRef;

  /**
   * @param payloadBytes the exact bytes to sign (defensively copied; must be non-empty)
   * @param fileName optional human-facing name of the payload, for provider display/audit
   * @param signerProfileRef optional non-secret reference to the signer's cloud-QES profile (the
   *     one-user-one-profile mapping is introduced in a later phase); never a key or a password
   */
  public QesSigningRequest(byte[] payloadBytes, String fileName, String signerProfileRef) {
    if (payloadBytes == null || payloadBytes.length == 0) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST, "Signing payload must be non-empty");
    }
    this.payloadBytes = payloadBytes.clone();
    this.payloadSha256Hex = RegulatoryDigest.sha256Hex(this.payloadBytes);
    this.fileName = fileName;
    this.signerProfileRef = signerProfileRef;
  }

  /** The exact bytes to sign (a copy; the caller cannot mutate the request's state). */
  public byte[] getPayloadBytes() {
    return payloadBytes.clone();
  }

  /** Lower-case hex SHA-256 of {@link #getPayloadBytes()}, bound to this request. */
  public String getPayloadSha256Hex() {
    return payloadSha256Hex;
  }

  /** Optional display/audit name; may be {@code null}. */
  public String getFileName() {
    return fileName;
  }

  /** Optional non-secret signer-profile reference; may be {@code null}. */
  public String getSignerProfileRef() {
    return signerProfileRef;
  }
}
