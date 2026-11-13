package com.axelor.apps.napsubmissions.common.signing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The production cryptographic gate: a detached PKCS#7 verifies only over the exact bytes it covers
 * and only against the certificate whose key produced it. Uses the TEST-ONLY signer for genuine
 * material.
 */
class DetachedPkcs7VerifierTest {

  private static final byte[] PAYLOAD = "0010010000010001 Д1".getBytes(StandardCharsets.UTF_8);

  @Test
  void aGenuineSignatureVerifiesOverTheExactBytesAndCertificate() {
    TestOnlyQesSigner signer = new TestOnlyQesSigner("ПОДПИСВАЩ");
    byte[] pkcs7 = signer.detachedPkcs7(PAYLOAD);

    assertTrue(DetachedPkcs7Verifier.verifies(PAYLOAD, pkcs7, signer.certificateDer()));
  }

  @Test
  void aChangedPayloadDoesNotVerify() {
    TestOnlyQesSigner signer = new TestOnlyQesSigner("ПОДПИСВАЩ");
    byte[] pkcs7 = signer.detachedPkcs7(PAYLOAD);

    byte[] tampered = "0010010000010002 Д1".getBytes(StandardCharsets.UTF_8);
    assertFalse(DetachedPkcs7Verifier.verifies(tampered, pkcs7, signer.certificateDer()));
  }

  @Test
  void anotherSignersCertificateDoesNotVerify() {
    TestOnlyQesSigner signer = new TestOnlyQesSigner("ПОДПИСВАЩ");
    TestOnlyQesSigner impostor = new TestOnlyQesSigner("ДРУГ");
    byte[] pkcs7 = signer.detachedPkcs7(PAYLOAD);

    // Right bytes, right signature, WRONG certificate → the signer does not correspond.
    assertFalse(DetachedPkcs7Verifier.verifies(PAYLOAD, pkcs7, impostor.certificateDer()));
  }

  @Test
  void emptyOrMissingSignatureDoesNotVerify() {
    TestOnlyQesSigner signer = new TestOnlyQesSigner("ПОДПИСВАЩ");
    assertFalse(DetachedPkcs7Verifier.verifies(PAYLOAD, new byte[0], signer.certificateDer()));
    assertFalse(DetachedPkcs7Verifier.verifies(PAYLOAD, null, signer.certificateDer()));
  }
}
