package com.axelor.apps.napsubmissions.common.signing.evrotrust;

/**
 * The Evrotrust vendor REST API as a thin, injectable seam — the single boundary the {@link
 * EvrotrustCloudQesProvider} crosses to reach the network. A real HMAC/HTTP implementation ({@link
 * HttpEvrotrustTransport}) is bound in production; a deterministic in-memory fake drives the
 * provider in tests. No business logic lives here: methods map one-to-one to documented endpoints
 * and carry only documented, non-secret fields.
 *
 * <p>Each method throws {@code QesException} (via the implementation) on configuration or transport
 * failure; it never returns key material.
 */
public interface EvrotrustTransport {

  /** POST {@code /user/check/extended} — is this signer registered and ready to sign. */
  UserCheck userCheck(EvrotrustSigner signer);

  /** POST {@code /document/hash/online} — start an asynchronous hash-signing request. */
  InitResult initHashSign(InitRequest request);

  /**
   * POST {@code /document/status} — current state of a started request, keyed by transaction id.
   */
  StatusResult status(String transactionId);

  /**
   * POST {@code /document/download} — the produced artifact of the ONLINE hash flow.
   *
   * <p>⚠️ ONLINE, not offline. The documented online flow is {@code /document/hash/online} → {@code
   * /document/status} → {@code /document/download} (group variants: {@code
   * /document/group/hash/online} → {@code /document/group/status} → {@code
   * /document/group/download}). {@code /hash/offline/download} belongs to the SEPARATE offline flow
   * and must never be substituted here just because the name mentions "hash".
   */
  DownloadResult downloadSignature(String transactionId);

  /**
   * POST {@code /user/certificate/get} — the signer's public X.509 certificate.
   *
   * <p>⚠️ Keyed by the SIGNER, not by the transaction: the documented request body carries {@code
   * user} + {@code certificate.type}, and no transaction id. The caller therefore has to keep the
   * (non-secret) signer identity alongside the transaction while the request is pending.
   */
  CertificateResult getCertificate(EvrotrustSigner signer);

  // --- request DTOs -----------------------------------------------------------------------------

  /**
   * Documented {@code /document/hash/online} request: a Base64 SHA-256, the signer identity and our
   * own {@code publicKey}, which the reference marks REQUIRED ("necessary for encryption process").
   */
  final class InitRequest {
    private final String hashBase64;
    private final String algorithm;
    private final EvrotrustSigner signer;
    private final String description;
    private final long dateExpireEpochSeconds;
    private final String publicKeyBase64;

    public InitRequest(
        String hashBase64,
        String algorithm,
        EvrotrustSigner signer,
        String description,
        long dateExpireEpochSeconds,
        String publicKeyBase64) {
      this.hashBase64 = hashBase64;
      this.algorithm = algorithm;
      this.signer = signer;
      this.description = description;
      this.dateExpireEpochSeconds = dateExpireEpochSeconds;
      this.publicKeyBase64 = publicKeyBase64;
    }

    /** Our Base64 public key — the documented REQUIRED {@code publicKey} field. */
    public String getPublicKeyBase64() {
      return publicKeyBase64;
    }

    public String getHashBase64() {
      return hashBase64;
    }

    public String getAlgorithm() {
      return algorithm;
    }

    public EvrotrustSigner getSigner() {
      return signer;
    }

    public String getDescription() {
      return description;
    }

    public long getDateExpireEpochSeconds() {
      return dateExpireEpochSeconds;
    }
  }

  // --- response DTOs ----------------------------------------------------------------------------

  /** Subset of {@code /user/check/extended} the provider gates on. */
  final class UserCheck {
    private final boolean readyToSign;
    private final boolean rejected;
    private final String reason;

    public UserCheck(boolean readyToSign, boolean rejected, String reason) {
      this.readyToSign = readyToSign;
      this.rejected = rejected;
      this.reason = reason;
    }

    public boolean isReadyToSign() {
      return readyToSign;
    }

    public boolean isRejected() {
      return rejected;
    }

    public String getReason() {
      return reason;
    }
  }

  /** {@code /document/hash/online} response: the ids used to poll and correlate. */
  final class InitResult {
    private final String transactionId;
    private final String threadId;

    public InitResult(String transactionId, String threadId) {
      this.transactionId = transactionId;
      this.threadId = threadId;
    }

    public String getTransactionId() {
      return transactionId;
    }

    public String getThreadId() {
      return threadId;
    }
  }

  /**
   * {@code /document/status} response. {@code statusCode} is the documented integer (1 Pending, 2
   * Signed, 3 Rejected, 4 Expired, 5 Failed, 6 Withdrawn, 7 Undeliverable, 8 Failed face
   * recognition, 99 On hold); {@code processing} is the {@code isProcessing} flag that keeps a
   * "Signed" request non-terminal until the artifact is ready.
   */
  final class StatusResult {
    private final int statusCode;
    private final boolean processing;
    private final String reason;

    public StatusResult(int statusCode, boolean processing, String reason) {
      this.statusCode = statusCode;
      this.processing = processing;
      this.reason = reason;
    }

    public int getStatusCode() {
      return statusCode;
    }

    public boolean isProcessing() {
      return processing;
    }

    public String getReason() {
      return reason;
    }
  }

  /** The downloaded signature artifact and the transport's classification of its format. */
  final class DownloadResult {
    private final byte[] signatureBytes;
    private final EvrotrustSignatureFormat format;

    public DownloadResult(byte[] signatureBytes, EvrotrustSignatureFormat format) {
      this.signatureBytes = signatureBytes == null ? new byte[0] : signatureBytes.clone();
      this.format = format == null ? EvrotrustSignatureFormat.UNKNOWN : format;
    }

    public byte[] getSignatureBytes() {
      return signatureBytes.clone();
    }

    public EvrotrustSignatureFormat getFormat() {
      return format;
    }
  }

  /** The signer's public certificate (DER) and its serial number. Never a private key. */
  final class CertificateResult {
    private final byte[] certificateDer;
    private final String serialNumber;

    public CertificateResult(byte[] certificateDer, String serialNumber) {
      this.certificateDer = certificateDer == null ? new byte[0] : certificateDer.clone();
      this.serialNumber = serialNumber;
    }

    public byte[] getCertificateDer() {
      return certificateDer.clone();
    }

    public String getSerialNumber() {
      return serialNumber;
    }
  }
}
