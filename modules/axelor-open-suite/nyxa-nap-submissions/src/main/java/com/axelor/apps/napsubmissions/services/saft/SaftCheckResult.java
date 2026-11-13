package com.axelor.apps.napsubmissions.services.saft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * РЕЗУЛТАТЪТ НА ЕДНА ПРОВЕРКА НА SAF-T ФАЙЛА (G6b): находките и — само без ГРЕШКА — байтовете.
 *
 * <p><b>⚠️ „ПРОВЕРЕНО“ Е ОТДЕЛНО ОТ „БЕЗ ГРЕШКИ“.</b> {@link #isChecked()} казва, че проверката
 * наистина е минала — сглобяването, централните правила и XSD-то над самите байтове. Без нея
 * отчетът никога не става READY: празен списък находки от несъществуваща проверка е риск 6 от
 * одита, не готовност.
 */
public final class SaftCheckResult {

  public static final String SEVERITY_ERROR = "ERROR";
  public static final String SEVERITY_WARNING = "WARNING";

  /** Една находка с указател към записа, който я поправя. */
  public static final class Finding {
    private final String severity;
    private final String code;
    private final String message;
    private final String targetModel;
    private final Long targetId;

    public Finding(
        String severity, String code, String message, String targetModel, Long targetId) {
      this.severity = severity;
      this.code = code;
      this.message = message;
      this.targetModel = targetModel;
      this.targetId = targetId;
    }

    public static Finding error(String code, String message) {
      return new Finding(SEVERITY_ERROR, code, message, null, null);
    }

    public String getSeverity() {
      return severity;
    }

    public String getCode() {
      return code;
    }

    public String getMessage() {
      return message;
    }

    public String getTargetModel() {
      return targetModel;
    }

    public Long getTargetId() {
      return targetId;
    }

    public boolean isError() {
      return SEVERITY_ERROR.equals(severity);
    }

    @Override
    public String toString() {
      return severity + " " + code + ": " + message;
    }
  }

  private final boolean checked;
  private final List<Finding> findings;
  private final String fileName;
  private final byte[] bytes;
  private final String sha256;
  private final String auditFileVersion;

  private SaftCheckResult(
      boolean checked,
      List<Finding> findings,
      String fileName,
      byte[] bytes,
      String sha256,
      String auditFileVersion) {
    this.checked = checked;
    this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
    this.fileName = fileName;
    this.bytes = bytes;
    this.sha256 = sha256;
    this.auditFileVersion = auditFileVersion;
  }

  /** Проверката не е налична (модулът със схемите липсва) — отчетът не може да стане READY. */
  public static SaftCheckResult unavailable(String reason) {
    return new SaftCheckResult(
        false, List.of(Finding.error("SAFT_CHECK_UNAVAILABLE", reason)), null, null, null, null);
  }

  /** Проверката мина; има ГРЕШКА или файлът не е поискан — без байтове. */
  public static SaftCheckResult findingsOnly(List<Finding> findings, String auditFileVersion) {
    return new SaftCheckResult(true, findings, null, null, null, auditFileVersion);
  }

  /** Проверката мина без ГРЕШКА — файлът с името си и SHA-256. */
  public static SaftCheckResult file(
      List<Finding> findings,
      String fileName,
      byte[] bytes,
      String sha256,
      String auditFileVersion) {
    if (findings.stream().anyMatch(Finding::isError)) {
      throw new IllegalArgumentException("SAF-T: файл с ГРЕШКА не се произвежда");
    }
    return new SaftCheckResult(true, findings, fileName, bytes, sha256, auditFileVersion);
  }

  public boolean isChecked() {
    return checked;
  }

  public List<Finding> getFindings() {
    return findings;
  }

  public boolean hasErrors() {
    return findings.stream().anyMatch(Finding::isError);
  }

  /** {@code null}, когато има ГРЕШКА. */
  public String getFileName() {
    return fileName;
  }

  /** {@code null}, когато има ГРЕШКА. */
  public byte[] getBytes() {
    return bytes;
  }

  public String getSha256() {
    return sha256;
  }

  /** S.H.1 — версията от таблицата по датата на генериране. */
  public String getAuditFileVersion() {
    return auditFileVersion;
  }

  /** Готов за запис: проверено, без ГРЕШКА, с байтове. */
  public boolean isGenerated() {
    return checked && !hasErrors() && bytes != null;
  }
}
