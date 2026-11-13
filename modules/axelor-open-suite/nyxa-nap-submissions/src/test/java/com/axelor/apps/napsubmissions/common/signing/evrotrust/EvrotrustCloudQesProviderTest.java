package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProviderIds;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.QesSignature;
import com.axelor.apps.napsubmissions.common.signing.QesSigningRequest;
import com.axelor.apps.napsubmissions.common.signing.QesSigningSession;
import com.axelor.apps.napsubmissions.common.signing.QesSigningStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Drives the Evrotrust provider through its documented lifecycle with a fake transport. These prove
 * the mapping, the digest binding and the format guard — NOT a real signature against Evrotrust.
 */
class EvrotrustCloudQesProviderTest {

  private static final byte[] PAYLOAD = "Д1/Д6 locked bytes".getBytes(StandardCharsets.UTF_8);
  private static final String SIGNER_REF = "egn=1234567890";

  private static final String PUBLIC_KEY = "cHVibGljLWtleQ==";

  /** Config with our publicKey present — the online flow refuses outright without it. */
  private EvrotrustCloudQesProvider provider(FakeEvrotrustTransport transport) {
    return new EvrotrustCloudQesProvider(configWithPublicKey(PUBLIC_KEY), transport);
  }

  /** Our key pair is configured: publicKey present and a private-key source declared. */
  private EvrotrustConfig configWithPublicKey(String publicKey) {
    return config(publicKey, true);
  }

  private EvrotrustConfig config(String publicKey, boolean privateKeySource) {
    return new EvrotrustConfig() {
      @Override
      public String getPublicKeyBase64() {
        return publicKey;
      }

      @Override
      public boolean hasPrivateKeySource() {
        return privateKeySource;
      }
    };
  }

  private QesSigningRequest request() {
    return new QesSigningRequest(PAYLOAD, "D1.txt", SIGNER_REF);
  }

  @Test
  void withoutOurPublicKeyTheOnlineFlowRefusesBeforeTheNetwork() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport();
    EvrotrustCloudQesProvider provider =
        new EvrotrustCloudQesProvider(config(null, true), transport);

    QesException refused = assertThrows(QesException.class, () -> provider.startSigning(request()));
    assertEquals(QesException.Category.CONFIG, refused.getCategory());
    // Nothing was started: no init request was ever built.
    assertEquals(null, transport.initRequest);
  }

  @Test
  void withoutOurPrivateKeyTheOnlineFlowRefusesBeforeAskingAnyoneToConfirm() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport();
    EvrotrustCloudQesProvider provider =
        new EvrotrustCloudQesProvider(config(PUBLIC_KEY, false), transport);

    // The result comes back encrypted to our publicKey; without the private key we could never
    // read it, so no signer may be asked to confirm anything.
    QesException refused = assertThrows(QesException.class, () -> provider.startSigning(request()));
    assertEquals(QesException.Category.CONFIG, refused.getCategory());
    assertEquals(null, transport.initRequest);
  }

  @Test
  void providerIdIsEvrotrust() {
    assertEquals(
        CloudQesProviderIds.EVROTRUST, provider(new FakeEvrotrustTransport()).getProviderId());
  }

  @Test
  void startBuildsRequestFromExactBoundHash() throws Exception {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport();
    QesSigningSession session = provider(transport).startSigning(request());

    assertEquals("tx-1", session.getExternalRequestId());
    assertEquals(QesSigningStatus.PENDING, session.getStatus());
    // The hash sent to Evrotrust is the Base64 of the raw SHA-256 of the exact payload.
    byte[] raw = MessageDigest.getInstance("SHA-256").digest(PAYLOAD);
    assertEquals(Base64.getEncoder().encodeToString(raw), transport.initRequest.getHashBase64());
    // Documented literal for /document/hash/online is "SHA256" — no hyphen.
    assertEquals("SHA256", transport.initRequest.getAlgorithm());
    // publicKey is REQUIRED by the reference; it must actually travel in the init request.
    assertEquals(PUBLIC_KEY, transport.initRequest.getPublicKeyBase64());
    assertEquals("1234567890", transport.initRequest.getSigner().getIdentificationNumber());
    assertEquals("BG", transport.initRequest.getSigner().getCountry());
    // certificateSerialNumber came from /user/certificate/get for that ЕГН — never from the user.
    assertEquals(1, transport.certificateCalls);
    assertEquals("1234567890", transport.certificateSigner.getIdentificationNumber());
    assertEquals(
        transport.certificate.getSerialNumber(),
        transport.initRequest.getSigner().getCertificateSerialNumber());
  }

  @Test
  void happyPathSignedYieldsSignatureBoundToPayload() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport().withStatus(2, false, null);
    EvrotrustCloudQesProvider provider = provider(transport);

    String id = provider.startSigning(request()).getExternalRequestId();
    QesSignature signature = provider.pollStatus(id).getSignature();

    assertEquals(QesSigningStatus.COMPLETED, provider.pollStatus(id).getStatus());
    assertEquals(CloudQesProviderIds.EVROTRUST, signature.getProviderId());
    assertEquals(RegulatoryDigest.sha256Hex(PAYLOAD), signature.getSignedPayloadSha256Hex());
    assertArrayEquals(transport.download.getSignatureBytes(), signature.getDetachedPkcs7());
    // THE certificate that actually produced the signature, read out of the container itself.
    assertArrayEquals(
        FakeEvrotrustTransport.SIGNER.certificateDer(), signature.getCertificateBytes());
    // Only the one lookup before start; the signature carries its own certificate.
    assertEquals(1, transport.certificateCalls);
  }

  @Test
  void aSignatureByADifferentCertificateThanTheRequestedSerialIsRefused() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport().withStatus(2, false, null);
    // /user/certificate/get answered with another (e.g. renewed) certificate's serial…
    byte[] other =
        new com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner("ДРУГ")
            .certificateDer();
    transport.certificate =
        new EvrotrustTransport.CertificateResult(other, FakeEvrotrustTransport.serialHex(other));
    EvrotrustCloudQesProvider provider = provider(transport);
    String id = provider.startSigning(request()).getExternalRequestId();

    // …but the signature was produced by SIGNER's certificate → refused, never forwarded to НАП.
    QesException refused = assertThrows(QesException.class, () -> provider.pollStatus(id));
    assertEquals(QesException.Category.INVALID_REQUEST, refused.getCategory());
  }

  @Test
  void aContainerWithoutCertificatesFallsBackToTheDocumentedEndpointOnlyOnAMatch() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport().withStatus(2, false, null);
    // A detached CMS stripped of its certificates — legal, and the only case the endpoint is for.
    transport.download =
        new EvrotrustTransport.DownloadResult(
            EvrotrustEncryptedDownloadFixture.withoutCertificates(
                FakeEvrotrustTransport.SIGNER.detachedPkcs7(FakeEvrotrustTransport.SIGNED_PAYLOAD)),
            EvrotrustSignatureFormat.DETACHED_PKCS7);
    EvrotrustCloudQesProvider provider = provider(transport);
    String id = provider.startSigning(request()).getExternalRequestId();

    QesSignature signature = provider.pollStatus(id).getSignature();
    assertArrayEquals(
        FakeEvrotrustTransport.SIGNER.certificateDer(), signature.getCertificateBytes());
    // Keyed by the SIGNER (documented body), not by the transaction.
    assertEquals("1234567890", transport.certificateSigner.getIdentificationNumber());
  }

  @Test
  void aMismatchedCertificateFromTheEndpointIsRefused() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport().withStatus(2, false, null);
    transport.download =
        new EvrotrustTransport.DownloadResult(
            EvrotrustEncryptedDownloadFixture.withoutCertificates(
                FakeEvrotrustTransport.SIGNER.detachedPkcs7(FakeEvrotrustTransport.SIGNED_PAYLOAD)),
            EvrotrustSignatureFormat.DETACHED_PKCS7);
    // A different person's certificate — e.g. a renewed or wrong qualified certificate.
    transport.certificate =
        new EvrotrustTransport.CertificateResult(
            new com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner("ДРУГ")
                .certificateDer(),
            "SERIAL-2");
    EvrotrustCloudQesProvider provider = provider(transport);
    String id = provider.startSigning(request()).getExternalRequestId();

    QesException refused = assertThrows(QesException.class, () -> provider.pollStatus(id));
    assertEquals(QesException.Category.INVALID_REQUEST, refused.getCategory());
  }

  @Test
  void pendingThenSigned() {
    FakeEvrotrustTransport transport =
        new FakeEvrotrustTransport().withStatus(1, false, null).withStatus(2, false, null);
    EvrotrustCloudQesProvider provider = provider(transport);
    String id = provider.startSigning(request()).getExternalRequestId();

    assertEquals(QesSigningStatus.PENDING, provider.pollStatus(id).getStatus());
    assertEquals(QesSigningStatus.COMPLETED, provider.pollStatus(id).getStatus());
  }

  @Test
  void signedButStillProcessingStaysPending() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport().withStatus(2, true, null);
    EvrotrustCloudQesProvider provider = provider(transport);
    String id = provider.startSigning(request()).getExternalRequestId();
    assertEquals(QesSigningStatus.PENDING, provider.pollStatus(id).getStatus());
    assertEquals(0, transport.downloadCalls);
  }

  @Test
  void rejectedExpiredFailedMapCorrectly() {
    assertEquals(QesSigningStatus.REJECTED, poll(3, "no"));
    assertEquals(QesSigningStatus.EXPIRED, poll(4, null));
    assertEquals(QesSigningStatus.FAILED, poll(5, null));
    assertEquals(QesSigningStatus.FAILED, poll(7, null));
  }

  private QesSigningStatus poll(int code, String reason) {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport().withStatus(code, false, reason);
    EvrotrustCloudQesProvider provider = provider(transport);
    String id = provider.startSigning(request()).getExternalRequestId();
    return provider.pollStatus(id).getStatus();
  }

  @Test
  void rawSignatureFormatIsRefused() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport().withStatus(2, false, null);
    transport.download =
        new EvrotrustTransport.DownloadResult(
            "raw".getBytes(StandardCharsets.UTF_8), EvrotrustSignatureFormat.RAW_SIGNATURE);
    EvrotrustCloudQesProvider provider = provider(transport);
    String id = provider.startSigning(request()).getExternalRequestId();

    QesException e = assertThrows(QesException.class, () -> provider.pollStatus(id));
    assertEquals(QesException.Category.CONFIG, e.getCategory());
  }

  @Test
  void unknownTransactionFailsClosed() {
    EvrotrustCloudQesProvider provider = provider(new FakeEvrotrustTransport());
    QesException e = assertThrows(QesException.class, () -> provider.pollStatus("never-started"));
    assertEquals(QesException.Category.NOT_FOUND, e.getCategory());
  }

  @Test
  void signerNotReadyIsRefusedBeforeInit() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport();
    transport.userCheck = new EvrotrustTransport.UserCheck(false, false, null);
    QesException e =
        assertThrows(QesException.class, () -> provider(transport).startSigning(request()));
    assertEquals(QesException.Category.INVALID_REQUEST, e.getCategory());
  }

  @Test
  void signerWithoutAQualifiedCertificateIsRefusedBeforeInit() {
    FakeEvrotrustTransport transport = new FakeEvrotrustTransport();
    transport.certificate = new EvrotrustTransport.CertificateResult(new byte[0], null);
    QesException e =
        assertThrows(QesException.class, () -> provider(transport).startSigning(request()));
    assertEquals(QesException.Category.INVALID_REQUEST, e.getCategory());
    assertEquals(null, transport.initRequest);
  }
}
