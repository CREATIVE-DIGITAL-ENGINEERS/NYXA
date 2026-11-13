package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reads one annual accounting export file.
 *
 * <p>Same construction as the payroll {@code MicroinvestExportFileParser}: one static mapper,
 * {@code JavaTimeModule} for the date fields, and {@code FAIL_ON_UNKNOWN_PROPERTIES = false} so the
 * exporter can grow new sections (fixed assets, analytic dimensions) without breaking files that
 * predate them. Malformed JSON propagates as an IOException and is caught by the controller.
 */
final class MicroinvestAccountingExportFileParser {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private MicroinvestAccountingExportFileParser() {}

  static MicroinvestAccountingSourceModels.ExportRoot parse(byte[] jsonContent) throws IOException {
    return MAPPER.readValue(jsonContent, MicroinvestAccountingSourceModels.ExportRoot.class);
  }

  /**
   * SHA-256 of the exact uploaded bytes.
   *
   * <p>⚠️ AUDIT ONLY. Re-running the exporter over unchanged source data produces a different value
   * (the file carries {@code exportedAt} and the exporter version), so this must never decide
   * whether a year has already been imported — that is what the exporter's {@code contentChecksum}
   * is for.
   */
  static String fileSha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JLS; unreachable on any supported JRE.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Convenience for tests and for hashing canonical strings. */
  static String sha256Utf8(String text) {
    return fileSha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
