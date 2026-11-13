package com.axelor.apps.napsubmissions.common.signing;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Builds the NON-SECRET, display-only description of the signer certificate that a completed
 * signature carried, for {@code UserQesProfile.certificateMetadata}.
 *
 * <p><b>Public material only.</b> The input is the public X.509 certificate the provider returned
 * alongside the detached PKCS#7 — a private key never reaches this class, and nothing it writes can
 * be used to sign. The fields extracted are the ones an operator needs to recognise their own КЕП:
 * provider, SHA-256 fingerprint, subject, issuer, serial number and validity window.
 *
 * <p><b>Never throws.</b> A certificate that cannot be parsed as X.509 is a normal, expected case,
 * not an error: {@link MockCloudQesProvider} deliberately returns a placeholder byte string so the
 * workflow can be exercised without real cryptography. Failing here would turn a successful mock
 * signature into a failed one, so a parse failure degrades to a deterministic text line and the
 * fingerprint, which is computed from the raw bytes and is therefore always available.
 *
 * <p>Output is a short, stable, line-per-fact text. It is deterministic for the same input:
 * ISO-8601 UTC instants and a lower-case hex serial, with no locale- or timezone-dependent
 * formatting.
 */
public final class QesCertificateMetadata {

  /** What is recorded in place of X.509 fields for the deterministic test-only mock certificate. */
  public static final String MOCK_PLACEHOLDER = "Mock certificate — test only";

  /** What is recorded when a REAL provider returns bytes that are not a parsable X.509. */
  static final String UNPARSABLE_PLACEHOLDER =
      "Certificate could not be parsed as X.509 — only the fingerprint above is recorded";

  /** Defensive cap per value: a certificate subject/issuer DN can be pathologically long. */
  private static final int MAX_VALUE_LENGTH = 512;

  /** The line that carries the certificate fingerprint; the one field compared across renewals. */
  private static final String FINGERPRINT_LINE_PREFIX = "SHA-256: ";

  private QesCertificateMetadata() {}

  /**
   * The certificate fingerprint a previously stored description records, or {@code null} when the
   * text is absent or does not carry one.
   *
   * <p>This is what makes "replace only on renewal" cheap: the caller compares the fingerprint of
   * the certificate that just signed against the one already on the profile, instead of diffing two
   * free-text blocks whose wording may change between releases.
   */
  public static String recordedFingerprint(String metadata) {
    if (metadata == null) {
      return null;
    }
    for (String line : metadata.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith(FINGERPRINT_LINE_PREFIX)) {
        String fingerprint = trimmed.substring(FINGERPRINT_LINE_PREFIX.length()).trim();
        return fingerprint.isEmpty() ? null : fingerprint;
      }
    }
    return null;
  }

  /**
   * Describe the signature's certificate.
   *
   * @param certificateBytes the public certificate bytes the provider returned; may be empty
   * @param providerId the provider that produced the signature (see {@link CloudQesProviderIds})
   * @param fingerprintSha256Hex the SHA-256 of {@code certificateBytes}, already computed by the
   *     caller so the same hash is shown here and stored on the file
   * @return a non-secret, display-only text; never {@code null}, never throws
   */
  public static String describe(
      byte[] certificateBytes, String providerId, String fingerprintSha256Hex) {
    List<String> lines = new ArrayList<>();
    lines.add("Provider: " + value(providerId));
    lines.add(FINGERPRINT_LINE_PREFIX + value(fingerprintSha256Hex));

    X509Certificate certificate = parse(certificateBytes);
    if (certificate != null) {
      lines.add("Subject: " + value(name(certificate.getSubjectX500Principal().getName())));
      lines.add("Issuer: " + value(name(certificate.getIssuerX500Principal().getName())));
      lines.add("Serial: " + value(serial(certificate.getSerialNumber())));
      lines.add("Valid from: " + value(instant(certificate.getNotBefore())));
      lines.add("Valid to: " + value(instant(certificate.getNotAfter())));
      return String.join("\n", lines);
    }

    // Not an X.509. For the mock this is by design; for a real provider it is worth saying plainly.
    // Either way the signature itself was already verified against the requested digest by
    // QesSigningService — this text is evidence for a human, not a security decision.
    lines.add(
        CloudQesProviderIds.MOCK.equals(providerId) ? MOCK_PLACEHOLDER : UNPARSABLE_PLACEHOLDER);
    return String.join("\n", lines);
  }

  /** The certificate, or {@code null} when the bytes are absent or not a parsable X.509. */
  private static X509Certificate parse(byte[] certificateBytes) {
    if (certificateBytes == null || certificateBytes.length == 0) {
      return null;
    }
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(certificateBytes));
    } catch (Exception e) {
      // Deliberately swallowed: see the class comment. No bytes are logged — a certificate is
      // public, but echoing provider output into a log line is still noise we do not need.
      return null;
    }
  }

  private static String name(String distinguishedName) {
    return distinguishedName == null || distinguishedName.isEmpty() ? null : distinguishedName;
  }

  private static String serial(BigInteger serialNumber) {
    return serialNumber == null ? null : serialNumber.toString(16);
  }

  private static String instant(Date date) {
    if (date == null) {
      return null;
    }
    return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(date.getTime()));
  }

  /** A present, single-line, length-capped value, or {@code "unknown"}. */
  private static String value(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return "unknown";
    }
    String cleaned = raw.replace('\r', ' ').replace('\n', ' ').trim();
    return cleaned.length() <= MAX_VALUE_LENGTH
        ? cleaned
        : cleaned.substring(0, MAX_VALUE_LENGTH) + "…";
  }
}
