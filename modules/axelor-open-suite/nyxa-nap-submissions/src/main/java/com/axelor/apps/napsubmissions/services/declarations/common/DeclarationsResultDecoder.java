package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.google.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Decodes the НАП result response into raw bytes + a detected format, WITHOUT interpreting business
 * meaning. The raw bytes are always preserved (the caller stores them as an immutable artifact);
 * the derived Nyxa outcome stays {@code UNKNOWN} here — parsing accepted/rejected from HTML is
 * service-specific business logic for a later phase. Raw HTML is never rendered; only a short,
 * sanitized summary is produced.
 */
@Singleton
public class DeclarationsResultDecoder {

  /** Immutable decode result. {@code rawBytes} are the exact bytes to persist. */
  public static class Decoded {
    public final byte[] rawBytes;
    public final String format; // matches nap.processing.result.format.select
    public final String fileExtension;
    public final String derivedOutcome; // conservative: unknown
    public final String safeSummary; // sanitized, no raw HTML

    Decoded(byte[] rawBytes, String format, String fileExtension, String safeSummary) {
      this.rawBytes = rawBytes;
      this.format = format;
      this.fileExtension = fileExtension;
      this.derivedOutcome = NapProcessingResultRepository.OUTCOME_UNKNOWN;
      this.safeSummary = safeSummary;
    }
  }

  /**
   * Choose the populated result field (full HTML &gt; HTML fragments &gt; non-HTML base64), decode
   * it and detect the format. Returns {@code null} if the response carried no result body.
   */
  public Decoded decode(DeclarationsResultResponse response) {
    if (response == null) {
      return null;
    }
    if (notBlank(response.base64FullHtmlResultInfo)) {
      byte[] bytes = decodeBase64(response.base64FullHtmlResultInfo);
      return new Decoded(
          bytes, NapProcessingResultRepository.FORMAT_HTML, "html", summary("HTML page", bytes));
    }
    if (notBlank(response.base64HtmlResult)) {
      byte[] bytes = decodeBase64(response.base64HtmlResult);
      return new Decoded(
          bytes,
          NapProcessingResultRepository.FORMAT_HTML,
          "html",
          summary("HTML fragment", bytes));
    }
    if (notBlank(response.base64Result)) {
      byte[] bytes = decodeBase64(response.base64Result);
      String format = sniff(bytes);
      return new Decoded(bytes, format, extensionFor(format), summary(format.toUpperCase(), bytes));
    }
    return null;
  }

  /** Best-effort format detection for a non-HTML result. Never throws. */
  protected String sniff(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return NapProcessingResultRepository.FORMAT_UNKNOWN;
    }
    int i = 0;
    while (i < bytes.length
        && (bytes[i] == ' ' || bytes[i] == '\n' || bytes[i] == '\r' || bytes[i] == '\t')) {
      i++;
    }
    if (i < bytes.length && (bytes[i] == '{' || bytes[i] == '[')) {
      return NapProcessingResultRepository.FORMAT_JSON;
    }
    return isMostlyText(bytes)
        ? NapProcessingResultRepository.FORMAT_TEXT
        : NapProcessingResultRepository.FORMAT_BINARY;
  }

  protected boolean isMostlyText(byte[] bytes) {
    int sample = Math.min(bytes.length, 512);
    int nonPrintable = 0;
    for (int i = 0; i < sample; i++) {
      int b = bytes[i] & 0xFF;
      boolean printable = b == 0x09 || b == 0x0A || b == 0x0D || (b >= 0x20 && b != 0x7F);
      if (!printable) {
        nonPrintable++;
      }
    }
    return nonPrintable <= sample / 20; // <=5% control bytes => treat as text
  }

  protected byte[] decodeBase64(String value) {
    try {
      return Base64.getDecoder().decode(value.trim());
    } catch (IllegalArgumentException e) {
      // Preserve the original characters rather than losing the result to a decode error.
      return value.getBytes(StandardCharsets.UTF_8);
    }
  }

  protected String extensionFor(String format) {
    switch (format) {
      case "json":
        return "json";
      case "text":
        return "txt";
      case "binary":
        return "bin";
      default:
        return "dat";
    }
  }

  protected String summary(String kind, byte[] bytes) {
    return kind
        + " result, "
        + (bytes == null ? 0 : bytes.length)
        + " bytes (raw stored immutably)";
  }

  protected boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
