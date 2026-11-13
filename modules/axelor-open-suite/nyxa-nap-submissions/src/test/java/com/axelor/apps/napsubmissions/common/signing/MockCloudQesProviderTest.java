package com.axelor.apps.napsubmissions.common.signing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The deterministic mock QES provider: one test per scenario, plus binding and guard behaviour. */
class MockCloudQesProviderTest {

  private static final byte[] PAYLOAD = "D1-payload-bytes".getBytes(StandardCharsets.UTF_8);

  private QesSigningRequest request() {
    return new QesSigningRequest(PAYLOAD, "EMPL2021.TXT", null);
  }

  private String payloadSha() {
    return RegulatoryDigest.sha256Hex(PAYLOAD);
  }

  @Test
  void requestComputesAndBindsSha256() {
    QesSigningRequest req = request();
    assertEquals(payloadSha(), req.getPayloadSha256Hex());
    assertArrayEquals(PAYLOAD, req.getPayloadBytes());
  }

  @Test
  void emptyPayloadRejected() {
    QesException e =
        assertThrows(QesException.class, () -> new QesSigningRequest(new byte[0], "x", null));
    assertEquals(QesException.Category.INVALID_REQUEST, e.getCategory());
  }

  @Test
  void immediateSuccessCompletesOnFirstPoll() {
    MockCloudQesProvider provider = new MockCloudQesProvider(MockQesScenario.IMMEDIATE_SUCCESS);
    QesSigningSession session = provider.startSigning(request());
    assertEquals(QesSigningStatus.PENDING, session.getStatus());
    assertEquals(payloadSha(), session.getPayloadSha256Hex());

    QesSigningStatusResponse res = provider.pollStatus(session.getExternalRequestId());
    assertEquals(QesSigningStatus.COMPLETED, res.getStatus());
    QesSignature sig = res.getSignature();
    assertNotNull(sig);
    // Deterministically bound to the exact payload digest.
    assertEquals(payloadSha(), sig.getSignedPayloadSha256Hex());
    assertTrue(
        new String(sig.getDetachedPkcs7(), StandardCharsets.UTF_8).contains(payloadSha()),
        "placeholder PKCS#7 must embed the payload digest");
    assertTrue(sig.getCertificateBytes().length > 0);
    assertEquals(MockCloudQesProvider.PROVIDER_ID, sig.getProviderId());
    assertEquals(session.getExternalRequestId(), sig.getExternalRequestId());
    assertNotNull(sig.getSignedAt());
  }

  @Test
  void pendingThenSuccess() {
    MockCloudQesProvider provider = new MockCloudQesProvider(MockQesScenario.PENDING_THEN_SUCCESS);
    String id = provider.startSigning(request()).getExternalRequestId();
    assertEquals(QesSigningStatus.PENDING, provider.pollStatus(id).getStatus());
    QesSigningStatusResponse second = provider.pollStatus(id);
    assertEquals(QesSigningStatus.COMPLETED, second.getStatus());
    assertNotNull(second.getSignature());
  }

  @Test
  void userRejection() {
    assertTerminalWithoutSignature(MockQesScenario.USER_REJECTION, QesSigningStatus.REJECTED);
  }

  @Test
  void providerFailure() {
    assertTerminalWithoutSignature(MockQesScenario.PROVIDER_FAILURE, QesSigningStatus.FAILED);
  }

  @Test
  void expiration() {
    assertTerminalWithoutSignature(MockQesScenario.EXPIRATION, QesSigningStatus.EXPIRED);
  }

  @Test
  void wrongDigestCompletesButBindsWrongSha() {
    MockCloudQesProvider provider = new MockCloudQesProvider(MockQesScenario.WRONG_DIGEST);
    String id = provider.startSigning(request()).getExternalRequestId();
    QesSigningStatusResponse res = provider.pollStatus(id);
    assertEquals(QesSigningStatus.COMPLETED, res.getStatus());
    assertNotNull(res.getSignature());
    // The caller's mandatory verification must catch this: the signed digest is NOT the payload's.
    assertNotEquals(payloadSha(), res.getSignature().getSignedPayloadSha256Hex());
  }

  @Test
  void unknownRequestIdIsNotFound() {
    MockCloudQesProvider provider = new MockCloudQesProvider(MockQesScenario.IMMEDIATE_SUCCESS);
    QesException e =
        assertThrows(QesException.class, () -> provider.pollStatus("mock-does-not-exist"));
    assertEquals(QesException.Category.NOT_FOUND, e.getCategory());
  }

  private void assertTerminalWithoutSignature(MockQesScenario scenario, QesSigningStatus expected) {
    MockCloudQesProvider provider = new MockCloudQesProvider(scenario);
    String id = provider.startSigning(request()).getExternalRequestId();
    QesSigningStatusResponse res = provider.pollStatus(id);
    assertEquals(expected, res.getStatus());
    assertTrue(res.getStatus().isTerminal());
    assertNull(res.getSignature());
    assertNotNull(res.getReason());
  }
}
