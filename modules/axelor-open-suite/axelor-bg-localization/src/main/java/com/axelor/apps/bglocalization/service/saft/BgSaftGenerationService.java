package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ПРОВЕРКА И ГЕНЕРИРАНЕ НА SAF-T ФАЙЛА — ГРЕШКАТА СПИРА ФАЙЛА (G6a; годишният и при поискване — T1,
 * по вида на отчета).
 *
 * <p><b>⚠️ ГРЕШКА = НЯМА ФАЙЛ, ИМА НАХОДКИ.</b> Произведен въпреки грешка, файлът пътува до
 * портала, за да бъде отхвърлен там (Прил. 3 т. II). Затова {@link #generate} връща байтове САМО
 * когато нито една находка не блокира — на сглобяването, на централната проверка по QA IV.2.4 и на
 * XSD проверката над самите байтове. Предупрежденията не спират — човекът решава.
 *
 * <p><b>⚠️ XSD-ТО Е ПОСЛЕДНО И ВЪРХУ БАЙТОВЕТЕ</b> — същият ред като при справката по чл. 73
 * ({@code BgArt73Para1SubmissionPlan}): схемата приема почти всичко, тоест не замества проверките;
 * пази от дефект в писача. Минала над низ, тя би мерила друго съдържание от това, което тръгва към
 * НАП.
 *
 * <p><b>НЕ ТУК (G6b):</b> записът на файла в {@code NapArtifact}, резултатът в {@code
 * SaftValidationIssue} и бутоните на {@code SaftReport}. Тази услуга дава байтовете, името, SHA-256
 * и находките — по една на запис, с указател към него.
 */
@Singleton
public class BgSaftGenerationService {

  protected final BgSaftAuditFileService auditFileService;
  protected final BgSaftAnnualOnDemandService annualOnDemandService;

  @Inject
  public BgSaftGenerationService(
      BgSaftAuditFileService auditFileService, BgSaftAnnualOnDemandService annualOnDemandService) {
    this.auditFileService = auditFileService;
    this.annualOnDemandService = annualOnDemandService;
  }

  /** Резултатът: находките и — само без ГРЕШКА — файлът. */
  public static final class Generated {
    private final List<BgDeclarationFileFinding> findings;
    private final String fileName;
    private final byte[] bytes;
    private final String sha256;

    private Generated(
        List<BgDeclarationFileFinding> findings, String fileName, byte[] bytes, String sha256) {
      this.findings = Collections.unmodifiableList(findings);
      this.fileName = fileName;
      this.bytes = bytes;
      this.sha256 = sha256;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }

    /** {@code null}, когато има ГРЕШКА. */
    public String getFileName() {
      return fileName;
    }

    /** {@code null}, когато има ГРЕШКА — файл не се произвежда. */
    public byte[] getBytes() {
      return bytes;
    }

    /** SHA-256 на байтовете, малки шестнадесетични; {@code null} без файл. */
    public String getSha256() {
      return sha256;
    }

    public boolean isGenerated() {
      return bytes != null;
    }
  }

  /** Само проверката — находките, без писане на файл (бутонът „Проверка на готовността“). */
  public BgSaftAuditFileService.Result check(
      SaftReport report, LocalDate createdOn, LocalDateTime lastGenerated) {
    return assemble(report, createdOn, lastGenerated);
  }

  /** Проверката и — без ГРЕШКА — файлът. */
  public Generated generate(SaftReport report, LocalDate createdOn, LocalDateTime lastGenerated) {
    return generate(assemble(report, createdOn, lastGenerated));
  }

  /**
   * Сглобяването по вида на отчета (T1): месечният — {@link BgSaftAuditFileService}; годишният и
   * при поискване — {@link BgSaftAnnualOnDemandService}.
   */
  protected BgSaftAuditFileService.Result assemble(
      SaftReport report, LocalDate createdOn, LocalDateTime lastGenerated) {
    if (SaftReportRepository.REPORT_TYPE_MONTHLY.equals(report.getReportTypeSelect())) {
      return auditFileService.assemble(report, createdOn, lastGenerated);
    }
    return annualOnDemandService.assemble(report, createdOn);
  }

  /** Чистата част: от сглобения модел до байтовете, или до находките, които ги спират. */
  static Generated generate(BgSaftAuditFileService.Result assembled) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>(assembled.getFindings());
    if (assembled.isBlocked() || assembled.getFileName() == null) {
      return new Generated(findings, null, null, null);
    }
    byte[] bytes = BgSaftXmlWriter.toBytes(assembled.getFile());
    // Схемата — по датата на създаване в Header-а, тоест по същия ред от таблицата, от който е
    // S.H.1.
    BgSaftXsd.Version version =
        BgSaftXsd.forDate(assembled.getFile().getHeader().getAuditFileDateCreated());
    List<BgDeclarationFileFinding> schema =
        BgXsdValidator.validate(
            bytes, version.getResource(), version.describe(), BgSaftXsd.CODE_PREFIX);
    findings.addAll(schema);
    if (!schema.isEmpty()) {
      return new Generated(findings, null, null, null);
    }
    return new Generated(findings, assembled.getFileName(), bytes, sha256(bytes));
  }

  static String sha256(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 липсва в JVM-а", e);
    }
  }
}
