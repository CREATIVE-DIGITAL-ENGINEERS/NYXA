package com.axelor.apps.napsubmissions.common.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The non-secret certificate description, with the one property that matters operationally: it
 * never throws. The mock's placeholder certificate is not a parsable X.509 on purpose, and a
 * completed signature must not be lost because its display metadata could not be built.
 */
class QesCertificateMetadataTest {

  private static final String FINGERPRINT =
      "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

  private static byte[] mockCertificate() {
    // Exactly what MockCloudQesProvider returns: a marked placeholder, not DER.
    return ("MOCK-QES-CERT-V1:" + CloudQesProviderIds.MOCK).getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void theMockPlaceholderCertificateYieldsSafeDeterministicText() {
    String metadata =
        QesCertificateMetadata.describe(mockCertificate(), CloudQesProviderIds.MOCK, FINGERPRINT);

    assertEquals(
        "Provider: mock\nSHA-256: " + FINGERPRINT + "\n" + QesCertificateMetadata.MOCK_PLACEHOLDER,
        metadata);
  }

  /** Deterministic: the same input always produces the same text, with no timestamp of its own. */
  @Test
  void describeIsDeterministic() {
    assertEquals(
        QesCertificateMetadata.describe(mockCertificate(), CloudQesProviderIds.MOCK, FINGERPRINT),
        QesCertificateMetadata.describe(mockCertificate(), CloudQesProviderIds.MOCK, FINGERPRINT));
  }

  /** No private-key material can appear: only the provider id and the public fingerprint are in. */
  @Test
  void theMockDescriptionLeaksNoCertificateBytes() {
    String metadata =
        QesCertificateMetadata.describe(mockCertificate(), CloudQesProviderIds.MOCK, FINGERPRINT);

    assertFalse(metadata.contains("MOCK-QES-CERT-V1"));
    assertFalse(metadata.toLowerCase().contains("private"));
  }

  /**
   * A REAL provider returning unparsable bytes is a different situation from the mock and says so,
   * but it still must not throw — the signature was already verified against the requested digest.
   */
  @Test
  void anUnparsableCertificateFromARealProviderIsReportedNotThrown() {
    String metadata =
        QesCertificateMetadata.describe(
            "not-a-certificate".getBytes(StandardCharsets.UTF_8),
            CloudQesProviderIds.BTRUST,
            FINGERPRINT);

    assertTrue(metadata.startsWith("Provider: btrust\nSHA-256: " + FINGERPRINT));
    assertTrue(metadata.contains(QesCertificateMetadata.UNPARSABLE_PLACEHOLDER));
    assertFalse(metadata.contains(QesCertificateMetadata.MOCK_PLACEHOLDER));
  }

  /**
   * The fingerprint is read back out of a stored description so the caller can tell a renewed
   * certificate from the one it already recorded, without diffing free text.
   */
  @Test
  void theRecordedFingerprintIsReadBackFromAStoredDescription() {
    String metadata =
        QesCertificateMetadata.describe(mockCertificate(), CloudQesProviderIds.MOCK, FINGERPRINT);

    assertEquals(FINGERPRINT, QesCertificateMetadata.recordedFingerprint(metadata));
  }

  @Test
  void aMissingOrFingerprintlessDescriptionRecordsNoFingerprint() {
    assertNull(QesCertificateMetadata.recordedFingerprint(null));
    assertNull(QesCertificateMetadata.recordedFingerprint(""));
    assertNull(QesCertificateMetadata.recordedFingerprint("Provider: mock\nSubject: CN=x"));
    assertNull(QesCertificateMetadata.recordedFingerprint("Provider: mock\nSHA-256:   "));
  }

  @Test
  void absentCertificateBytesAndFingerprintDegradeToUnknown() {
    String metadata = QesCertificateMetadata.describe(new byte[0], CloudQesProviderIds.MOCK, null);

    assertEquals(
        "Provider: mock\nSHA-256: unknown\n" + QesCertificateMetadata.MOCK_PLACEHOLDER, metadata);
  }
}
