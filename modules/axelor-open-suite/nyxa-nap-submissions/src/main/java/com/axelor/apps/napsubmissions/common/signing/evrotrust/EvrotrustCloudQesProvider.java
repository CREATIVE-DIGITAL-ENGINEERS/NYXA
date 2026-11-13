package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProviderIds;
import com.axelor.apps.napsubmissions.common.signing.DetachedPkcs7Signers;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.QesSignature;
import com.axelor.apps.napsubmissions.common.signing.QesSigningRequest;
import com.axelor.apps.napsubmissions.common.signing.QesSigningSession;
import com.axelor.apps.napsubmissions.common.signing.QesSigningStatus;
import com.axelor.apps.napsubmissions.common.signing.QesSigningStatusResponse;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REAL Evrotrust cloud-QES provider driving the documented asynchronous ONLINE hash-signing flow:
 * {@code /document/hash/online} (start) → {@code /document/status} (poll) → {@code
 * /document/download} (the signature) + the signer's public certificate on success. It owns NO
 * transport details — those live behind {@link EvrotrustTransport} — and NO submission business
 * rules; it only maps the documented lifecycle onto the provider-neutral {@link CloudQesProvider}
 * contract and enforces the guards that keep the result honest.
 *
 * <p>⚠️ ONLINE, never offline: the offline flow is a different sequence with different endpoints,
 * and nothing here may be swapped for one of its look-alike names.
 *
 * <p><b>No persistence, no secrets.</b> Between start and poll the provider keeps ONLY a non-secret
 * {@code transactionId → bound SHA-256} mapping, in memory. No certificate, PKCS#7, private key or
 * PIN is ever stored — on a completed poll the signature and certificate are fetched fresh and
 * returned to the caller, which consumes them transiently. A restart loses the in-memory map, so an
 * unknown transaction id fails closed ({@link QesException.Category#NOT_FOUND}) — a clear refusal,
 * never a blind re-POST.
 *
 * <p><b>Two guards.</b> (1) the Base64 hash sent to Evrotrust is derived from the exact payload
 * bytes and its hex is asserted equal to the request's bound digest, so the request can never drift
 * from the content; (2) a completed download must be a {@link
 * EvrotrustSignatureFormat#DETACHED_PKCS7} — a bare signature is refused, never forwarded as a
 * PKCS#7. The caller additionally runs {@code DetachedPkcs7Verifier} over the payload before any
 * POST to НАП, which is the real cryptographic binding.
 */
public class EvrotrustCloudQesProvider implements CloudQesProvider {

  /**
   * Evrotrust's documented {@code signInfo.algorithm} literal for {@code /document/hash/online}:
   * {@code "SHA256"}, with no hyphen, exactly as the reference spells it.
   */
  static final String ALGORITHM_SHA256 = "SHA256";

  private final EvrotrustConfig config;
  private final EvrotrustTransport transport;

  /**
   * transactionId → the bound payload SHA-256 (hex) and the signer it was started for. Both are
   * non-secret and in-memory only; the signer is kept because {@code /user/certificate/get} is
   * keyed by the signer, not by the transaction.
   */
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

  public EvrotrustCloudQesProvider(EvrotrustConfig config, EvrotrustTransport transport) {
    this.config = config;
    this.transport = transport;
  }

  @Override
  public String getProviderId() {
    return CloudQesProviderIds.EVROTRUST;
  }

  @Override
  public QesSigningSession startSigning(QesSigningRequest request) {
    if (request == null) {
      throw new QesException(QesException.Category.INVALID_REQUEST, "Signing request is required");
    }
    EvrotrustSigner identity =
        EvrotrustSigner.fromProfileRef(request.getSignerProfileRef(), config.getDefaultCountry());
    // BEFORE any network call: a request we cannot build conformingly must not be started at all.
    String publicKey = requirePublicKey();

    // Fail closed early if the signer is not in a state that can sign.
    EvrotrustTransport.UserCheck check = transport.userCheck(identity);
    if (check.isRejected()) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "Evrotrust signer is rejected and cannot sign"
              + (check.getReason() == null ? "" : ": " + check.getReason()));
    }
    if (!check.isReadyToSign()) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "Evrotrust signer is not ready to sign (registration/identification incomplete).");
    }

    // certificateSerialNumber is REQUIRED by /document/hash/online when addressing a signer by
    // id-number; it is looked up here from the documented /user/certificate/get, never typed.
    EvrotrustTransport.CertificateResult issued = transport.getCertificate(identity);
    String serial = issued.getSerialNumber();
    if (serial == null || serial.isBlank()) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "Evrotrust returned no qualified eSign certificate for this signer; the signer needs an"
              + " active qualified certificate in the Evrotrust app. Signing refused.");
    }
    EvrotrustSigner signer = identity.withCertificateSerialNumber(serial.trim());

    String boundSha256Hex = request.getPayloadSha256Hex();
    String hashBase64 = base64Sha256(request.getPayloadBytes(), boundSha256Hex);

    EvrotrustTransport.InitRequest initRequest =
        new EvrotrustTransport.InitRequest(
            hashBase64,
            ALGORITHM_SHA256,
            signer,
            describe(request),
            Instant.now().getEpochSecond() + config.getExpireSeconds(),
            publicKey);

    EvrotrustTransport.InitResult init = transport.initHashSign(initRequest);
    String transactionId = init.getTransactionId();
    if (transactionId == null || transactionId.isBlank()) {
      throw new QesException(
          QesException.Category.TRANSPORT, "Evrotrust did not return a transaction id.");
    }
    sessions.put(transactionId, new Session(boundSha256Hex, signer, issued.getCertificateDer()));
    return new QesSigningSession(
        transactionId, QesSigningStatus.PENDING, boundSha256Hex, Instant.now());
  }

  @Override
  public QesSigningStatusResponse pollStatus(String externalRequestId) {
    Session session = externalRequestId == null ? null : sessions.get(externalRequestId);
    if (session == null) {
      // Unknown here means either never started by this process or lost to a restart: never guess,
      // never blindly re-POST — refuse and let the human re-sign.
      throw new QesException(
          QesException.Category.NOT_FOUND,
          "Unknown Evrotrust signing request: " + externalRequestId);
    }
    EvrotrustTransport.StatusResult status = transport.status(externalRequestId);
    switch (status.getStatusCode()) {
      case 1: // Pending
      case 99: // On hold (not yet delivered / queued)
        return QesSigningStatusResponse.pending(externalRequestId);
      case 2: // Signed
        if (status.isProcessing()) {
          return QesSigningStatusResponse.pending(externalRequestId);
        }
        return QesSigningStatusResponse.completed(
            externalRequestId, collectSignature(externalRequestId, session));
      case 3: // Rejected by the signer
        return QesSigningStatusResponse.rejected(externalRequestId, reason(status, "rejected"));
      case 4: // Expired
        return QesSigningStatusResponse.expired(externalRequestId, reason(status, "expired"));
      case 6: // Withdrawn by sender
        return QesSigningStatusResponse.failed(externalRequestId, reason(status, "withdrawn"));
      case 5: // System failure
      case 7: // Undeliverable
      case 8: // Failed face recognition
      default:
        return QesSigningStatusResponse.failed(
            externalRequestId, reason(status, "failed (code " + status.getStatusCode() + ")"));
    }
  }

  /**
   * Our own Base64 public key for the ONLINE flow — the reference marks {@code publicKey} REQUIRED
   * ("necessary for encryption process") and the result comes back encrypted to it.
   *
   * <p><b>Fail closed BEFORE the network.</b> Sending the request without it would be a
   * non-conforming request; sending it without holding the matching private key would produce a
   * result we cannot read. Either way nothing may be started, so this refuses with the exact thing
   * that is missing instead of letting a signer confirm a request whose outcome Nyxa cannot use.
   */
  private String requirePublicKey() {
    String publicKey = config.getPublicKeyBase64();
    if (publicKey == null || publicKey.isBlank()) {
      throw new QesException(
          QesException.Category.CONFIG,
          "Evrotrust online hash signing requires our own Base64 publicKey (documented as REQUIRED"
              + " on /document/hash/online — the signed result is encrypted to it). Configure"
              + " nyxa.qes.evrotrust.publicKey; signing refused.");
    }
    if (!config.hasPrivateKeySource()) {
      // Starting without it would make a human confirm a signature we could never read back.
      throw new QesException(
          QesException.Category.CONFIG,
          "Evrotrust returns the signed result encrypted to our publicKey, and no private key is"
              + " configured to decrypt it. Set nyxa.qes.evrotrust.privateKeyPath (or"
              + " nyxa.qes.evrotrust.privateKeySecretRef) to the matching RSA-2048 PEM, kept"
              + " outside Git; signing refused before anything is started.");
    }
    return publicKey;
  }

  /** Download the signature + certificate and assemble a {@link QesSignature}, with both guards. */
  private QesSignature collectSignature(String transactionId, Session session) {
    EvrotrustTransport.DownloadResult download = transport.downloadSignature(transactionId);
    if (download.getFormat() != EvrotrustSignatureFormat.DETACHED_PKCS7) {
      // Guard: a raw signature value is NOT a detached PKCS#7 and must never be forwarded as one.
      throw new QesException(
          QesException.Category.CONFIG,
          "Evrotrust returned a signature the transport could not confirm is a detached PKCS#7"
              + " (format="
              + download.getFormat()
              + "); refusing rather than forwarding a non-PKCS#7 artifact to NAP.");
    }
    byte[] pkcs7 = download.getSignatureBytes();
    if (pkcs7.length == 0) {
      throw new QesException(
          QesException.Category.TRANSPORT, "Evrotrust returned an empty signature artifact.");
    }
    byte[] certDer = signerCertificate(pkcs7, session);
    requireRequestedCertificate(certDer, session);
    // signedPayloadSha256Hex is the exact hash this request was bound to; the caller re-verifies
    // the detached PKCS#7 against the payload bytes with DetachedPkcs7Verifier before any НАП POST.
    return new QesSignature(
        pkcs7, certDer, getProviderId(), transactionId, Instant.now(), session.boundSha256Hex);
  }

  /**
   * WHOSE certificate НАП is told about — read from the signature itself whenever possible.
   *
   * <p>The detached CMS normally carries the very certificate that produced the signature, matched
   * by {@code SignerId}. That is strictly better than asking {@code /user/certificate/get}, which
   * is keyed by the PERSON: a renewed or second qualified certificate would come back, the
   * signature would not verify against it, and НАП would be handed a certificate that does not
   * belong to the signature.
   *
   * <p>Only when the container carries no certificates at all do we fall back to the documented
   * endpoint — and then the answer is accepted ONLY if it matches the signer's {@code SignerId}. An
   * arbitrary certificate is never accepted on either path, and the binding to the exact locked
   * bytes is still proved later by {@code DetachedPkcs7Verifier} before any POST to НАП.
   */
  private byte[] signerCertificate(byte[] pkcs7, Session session) {
    byte[] embedded = DetachedPkcs7Signers.embeddedSignerCertificate(pkcs7);
    if (embedded != null && embedded.length > 0) {
      return embedded;
    }
    // The certificate already fetched before /document/hash/online — no second lookup.
    byte[] certDer = session.issuedCertificateDer;
    if (certDer == null || certDer.length == 0) {
      throw new QesException(
          QesException.Category.TRANSPORT,
          "The signature carries no certificate and Evrotrust returned none for the signer;"
              + " refusing rather than submitting without the signer's certificate.");
    }
    if (!DetachedPkcs7Signers.matchesSigner(pkcs7, certDer)) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "The certificate Evrotrust returned for this signer is not the one that produced the"
              + " signature (SignerId mismatch — most likely a renewed or different qualified"
              + " certificate); refusing rather than sending NAP a mismatched certificate.");
    }
    return certDer;
  }

  /**
   * The certificate that ACTUALLY signed must be the one whose serial was requested in {@code
   * /document/hash/online}. A renewed or second certificate between start and completion is
   * refused, never silently accepted.
   */
  private static void requireRequestedCertificate(byte[] certDer, Session session) {
    String requested = session.signer.getCertificateSerialNumber();
    BigInteger actual;
    try {
      actual =
          ((X509Certificate)
                  CertificateFactory.getInstance("X.509")
                      .generateCertificate(new ByteArrayInputStream(certDer)))
              .getSerialNumber();
    } catch (CertificateException e) {
      throw new QesException(
          QesException.Category.TRANSPORT, "The signer certificate could not be parsed.");
    }
    if (!sameSerial(actual, requested)) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "The certificate that signed is not the one requested from Evrotrust (serial mismatch —"
              + " most likely a renewed certificate); refusing. Sign again.");
    }
  }

  /** Evrotrust documents the serial only as "string": accept its hex or decimal spelling. */
  static boolean sameSerial(BigInteger actual, String requested) {
    String normalized = requested == null ? "" : requested.replaceAll("[\\s:]", "");
    if (normalized.isEmpty()) {
      return false;
    }
    for (int radix : new int[] {16, 10}) {
      try {
        if (new BigInteger(normalized, radix).equals(actual)) {
          return true;
        }
      } catch (NumberFormatException ignored) {
        // not this spelling
      }
    }
    return false;
  }

  /** The non-secret state one pending transaction needs. In memory only; lost on restart. */
  private static final class Session {
    final String boundSha256Hex;
    final EvrotrustSigner signer;
    final byte[] issuedCertificateDer;

    Session(String boundSha256Hex, EvrotrustSigner signer, byte[] issuedCertificateDer) {
      this.boundSha256Hex = boundSha256Hex;
      this.signer = signer;
      this.issuedCertificateDer = issuedCertificateDer;
    }
  }

  /** Base64 of the raw SHA-256 digest, asserting it matches the request's bound hex digest. */
  private static String base64Sha256(byte[] payload, String expectedHex) {
    try {
      byte[] raw = MessageDigest.getInstance(RegulatoryDigest.ALGORITHM).digest(payload);
      String hex = RegulatoryDigest.sha256Hex(payload);
      if (!hex.equals(expectedHex)) {
        throw new QesException(
            QesException.Category.INVALID_REQUEST,
            "Payload digest drifted from the request's bound digest.");
      }
      return Base64.getEncoder().encodeToString(raw);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String describe(QesSigningRequest request) {
    String name = request.getFileName();
    return name == null || name.isBlank() ? "НАП declaration" : name;
  }

  private static String reason(EvrotrustTransport.StatusResult status, String fallback) {
    return status.getReason() == null || status.getReason().isBlank()
        ? fallback
        : status.getReason();
  }
}
