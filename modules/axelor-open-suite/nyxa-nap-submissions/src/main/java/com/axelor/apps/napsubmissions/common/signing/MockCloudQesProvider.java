package com.axelor.apps.napsubmissions.common.signing;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, in-memory mock {@link CloudQesProvider} for development and tests. It reproduces
 * the asynchronous start/poll lifecycle and a fixed set of outcomes ({@link MockQesScenario})
 * without any real cryptography or network call.
 *
 * <p><b>⚠️ Test-only output — NOT a valid production QES.</b> The {@link
 * QesSignature#getDetachedPkcs7()} and {@link QesSignature#getCertificateBytes()} this mock returns
 * are placeholder byte strings, not a real CMS/PKCS#7 structure and not a real X.509 certificate —
 * they would be rejected by НАП. What the mock DOES guarantee is the one property the workflow must
 * rely on: the returned signature is <b>deterministically bound to the exact payload SHA-256</b>
 * (the digest is embedded in the placeholder bytes), so a happy-path flow and the digest-mismatch
 * guard can both be exercised faithfully.
 *
 * <p>Never the default provider: it is reached only when configuration explicitly selects {@code
 * mock} (see {@link CloudQesProviderSelector}); the fail-closed {@link
 * UnconfiguredCloudQesProvider} is the default, so the mock is never active in production by
 * default.
 */
public class MockCloudQesProvider implements CloudQesProvider {

  public static final String PROVIDER_ID = "mock";

  /** Prefix that marks the placeholder PKCS#7 bytes as unmistakably test-only. */
  static final String PKCS7_PREFIX = "MOCK-QES-PKCS7-V1:";

  /** Prefix that marks the placeholder certificate bytes as unmistakably test-only. */
  static final String CERT_PREFIX = "MOCK-QES-CERT-V1:";

  private final MockQesScenario scenario;
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

  public MockCloudQesProvider(MockQesScenario scenario) {
    this.scenario = scenario == null ? MockQesScenario.IMMEDIATE_SUCCESS : scenario;
  }

  @Override
  public String getProviderId() {
    return PROVIDER_ID;
  }

  @Override
  public QesSigningSession startSigning(QesSigningRequest request) {
    if (request == null) {
      throw new QesException(QesException.Category.INVALID_REQUEST, "Signing request is required");
    }
    String id = PROVIDER_ID + "-" + UUID.randomUUID();
    Instant now = Instant.now();
    sessions.put(id, new Session(request.getPayloadSha256Hex()));
    // Real providers start asynchronously: a started request is PENDING; the outcome is polled.
    return new QesSigningSession(id, QesSigningStatus.PENDING, request.getPayloadSha256Hex(), now);
  }

  @Override
  public QesSigningStatusResponse pollStatus(String externalRequestId) {
    Session session = externalRequestId == null ? null : sessions.get(externalRequestId);
    if (session == null) {
      throw new QesException(
          QesException.Category.NOT_FOUND, "Unknown signing request: " + externalRequestId);
    }
    int poll = session.polls.incrementAndGet();

    switch (scenario) {
      case USER_REJECTION:
        return QesSigningStatusResponse.rejected(
            externalRequestId, "Signer rejected the signing request");
      case PROVIDER_FAILURE:
        return QesSigningStatusResponse.failed(
            externalRequestId, "Provider failed to complete the signing request");
      case EXPIRATION:
        return QesSigningStatusResponse.expired(
            externalRequestId, "Signing request expired before the signer acted");
      case PENDING_THEN_SUCCESS:
        if (poll < 2) {
          return QesSigningStatusResponse.pending(externalRequestId);
        }
        return QesSigningStatusResponse.completed(
            externalRequestId, sign(externalRequestId, session, session.payloadSha256Hex));
      case WRONG_DIGEST:
        return QesSigningStatusResponse.completed(
            externalRequestId, sign(externalRequestId, session, wrongDigestOf(session)));
      case IMMEDIATE_SUCCESS:
      default:
        return QesSigningStatusResponse.completed(
            externalRequestId, sign(externalRequestId, session, session.payloadSha256Hex));
    }
  }

  /**
   * Build a placeholder signature whose bytes embed {@code boundSha256Hex}. For the success
   * scenarios this is the real payload digest; for {@link MockQesScenario#WRONG_DIGEST} it is a
   * deliberately mismatching digest so the caller's verification fails.
   */
  private QesSignature sign(String externalRequestId, Session session, String boundSha256Hex) {
    byte[] pkcs7 = (PKCS7_PREFIX + boundSha256Hex).getBytes(StandardCharsets.UTF_8);
    byte[] cert = (CERT_PREFIX + PROVIDER_ID).getBytes(StandardCharsets.UTF_8);
    return new QesSignature(
        pkcs7, cert, PROVIDER_ID, externalRequestId, Instant.now(), boundSha256Hex);
  }

  /** A deterministic, valid-shaped SHA-256 hex that is guaranteed to differ from the payload's. */
  private static String wrongDigestOf(Session session) {
    return RegulatoryDigest.sha256Hex(
        (session.payloadSha256Hex + "-wrong").getBytes(StandardCharsets.UTF_8));
  }

  /** Minimal per-request state: the bound digest and a poll counter. No payload bytes retained. */
  private static final class Session {
    private final String payloadSha256Hex;
    private final AtomicInteger polls = new AtomicInteger(0);

    private Session(String payloadSha256Hex) {
      this.payloadSha256Hex = payloadSha256Hex;
    }
  }
}
