package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPreparedFile;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPreparedPackage;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.math.BigDecimal;
import java.nio.charset.Charset;

/**
 * Turns a {@link BgVatExportResultDto} (the exact bytes the generators produced) into a pure {@link
 * BgVatPreparedPackage}: per-file SHA-256 / byte size / record count / status / validation messages
 * / safe text preview, plus package-level readiness, the zero-declaration flag and summary totals.
 *
 * <p>Dependency-free (no DB / no Guice) so it is fully unit-testable. It never re-generates or
 * mutates the bytes — the prepared file carries the SAME bytes, guaranteeing preview == package ==
 * (later) submission.
 */
public final class BgVatPackageBuilder {

  private static final Charset CP1251 = Charset.forName("windows-1251");
  private static final int PREVIEW_LIMIT = 4000;

  // DEKLAR computed-cell byte offsets — only the two result cells needed
  // for the package summary. Kept in sync with BgVatReturnController.COMPUTED_CELLS.
  private static final int K50_START = 485;
  private static final int K50_END = 500;
  private static final int K60_START = 500;
  private static final int K60_END = 515;

  private BgVatPackageBuilder() {}

  public static BgVatPreparedPackage build(BgVatExportResultDto result) {
    BgVatPreparedPackage pkg = new BgVatPreparedPackage();
    pkg.setReportType(result.getReportType());
    pkg.setPeriodLabel(result.getPeriodLabel());
    pkg.setCompanyName(result.getCompanyName());
    pkg.setDocumentsFound(result.getDocumentsFound());
    pkg.setTotalBase(nz(result.getTotalBase()));
    pkg.setTotalVat(nz(result.getTotalVat()));
    pkg.setTotalGross(nz(result.getTotalGross()));
    pkg.setZeroDeclaration(result.getDocumentsFound() == 0);

    int errorCount = 0;
    int warningCount = result.getWarnings().size();
    for (BgVatValidationErrorDto err : result.getValidationErrors()) {
      if (BgVatValidationErrorDto.SEVERITY_ERROR.equals(err.getSeverity())) {
        errorCount++;
      } else {
        warningCount++;
      }
    }
    pkg.setErrorCount(errorCount);
    pkg.setWarningCount(warningCount);
    pkg.setReadiness(
        errorCount > 0
            ? BgVatPreparedPackage.READINESS_ERRORS
            : (warningCount > 0
                ? BgVatPreparedPackage.READINESS_WARNINGS
                : BgVatPreparedPackage.READINESS_READY));

    for (BgVatGeneratedFileDto file : result.getGeneratedFiles()) {
      byte[] content = file.getContent() == null ? new byte[0] : file.getContent();
      String status =
          content.length == 0 ? BgVatPreparedFile.STATUS_EMPTY : BgVatPreparedFile.STATUS_OK;
      pkg.getFiles()
          .add(
              new BgVatPreparedFile(
                  file.getScope(),
                  file.getFileName(),
                  content,
                  content.length,
                  BgVatHashUtil.sha256Hex(content),
                  recordCount(file, content, result),
                  status,
                  fileMessages(file, content, result),
                  textPreview(content)));
      if ("declaration".equals(file.getScope())) {
        pkg.setK50(parseCell(content, K50_START, K50_END));
        pkg.setK60(parseCell(content, K60_START, K60_END));
      }
    }

    pkg.setSummary(buildSummary(pkg, result));
    return pkg;
  }

  /** Record count is semantic per scope, not a raw line count. */
  private static int recordCount(
      BgVatGeneratedFileDto file, byte[] content, BgVatExportResultDto result) {
    switch (file.getScope()) {
      case "sales":
        return result.getSalesRows().size();
      case "purchases":
        return result.getPurchaseRows().size();
      case "declaration":
        return countNonEmptyLines(content);
      case "vies":
        return countPrefix(content, "VIR");
      default:
        return countNonEmptyLines(content);
    }
  }

  private static int countNonEmptyLines(byte[] content) {
    if (content.length == 0) {
      return 0;
    }
    int count = 0;
    for (String line : new String(content, CP1251).split("\r\n", -1)) {
      if (!line.isEmpty()) {
        count++;
      }
    }
    return count;
  }

  private static int countPrefix(byte[] content, String prefix) {
    if (content.length == 0) {
      return 0;
    }
    int count = 0;
    for (String line : new String(content, CP1251).split("\r\n", -1)) {
      if (line.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  /** Per-file messages: generator warnings that name this file, plus an empty-file note. */
  private static String fileMessages(
      BgVatGeneratedFileDto file, byte[] content, BgVatExportResultDto result) {
    StringBuilder sb = new StringBuilder();
    for (String warning : result.getWarnings()) {
      if (warning != null && warning.contains(file.getFileName())) {
        if (sb.length() > 0) {
          sb.append("\n");
        }
        sb.append(warning);
      }
    }
    if (content.length == 0) {
      if (sb.length() > 0) {
        sb.append("\n");
      }
      sb.append("Празен файл (нулева декларация) — 0 записа.");
    }
    return sb.toString();
  }

  /** Safe, truncated plain-text preview of a fixed-width diary file (CP1251). */
  private static String textPreview(byte[] content) {
    if (content.length == 0) {
      return "";
    }
    String text = new String(content, CP1251);
    boolean truncated = text.length() > PREVIEW_LIMIT;
    if (truncated) {
      text = text.substring(0, PREVIEW_LIMIT);
    }
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      // Keep printable characters + CR/LF/tab; drop other control chars.
      if (c == '\r' || c == '\n' || c == '\t' || c >= 0x20) {
        sb.append(c);
      }
    }
    if (truncated) {
      sb.append("\n…(съкратено)");
    }
    return sb.toString();
  }

  private static String buildSummary(BgVatPreparedPackage pkg, BgVatExportResultDto result) {
    StringBuilder sb = new StringBuilder();
    sb.append(pkg.isZeroDeclaration() ? "НУЛЕВА декларация" : "Стандартна декларация");
    sb.append("\nДокументи: ").append(pkg.getDocumentsFound());
    sb.append("\nГрешки: ").append(pkg.getErrorCount());
    sb.append("\nПредупреждения: ").append(pkg.getWarningCount());
    sb.append("\nкл.50 (за внасяне): ").append(pkg.getK50());
    sb.append("\nкл.60 (за възстановяване): ").append(pkg.getK60());
    for (BgVatValidationErrorDto err : result.getValidationErrors()) {
      sb.append("\n[").append(nzs(err.getSeverity())).append("] ");
      if (err.getCode() != null) {
        sb.append(err.getCode()).append(": ");
      }
      sb.append(nzs(err.getMessage()));
    }
    for (String warning : result.getWarnings()) {
      sb.append("\n[WARNING] ").append(nzs(warning));
    }
    return sb.toString();
  }

  private static BigDecimal parseCell(byte[] content, int start, int end) {
    if (content.length == 0) {
      return BigDecimal.ZERO;
    }
    String record = new String(content, CP1251).split("\r\n", -1)[0];
    if (record.length() < end) {
      return BigDecimal.ZERO;
    }
    String segment = record.substring(start, end).trim();
    if (segment.isEmpty()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(segment);
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String nzs(String v) {
    return v == null ? "" : v;
  }
}
