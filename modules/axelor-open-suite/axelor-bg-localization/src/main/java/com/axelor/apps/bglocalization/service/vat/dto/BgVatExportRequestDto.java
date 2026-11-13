package com.axelor.apps.bglocalization.service.vat.dto;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable-ish input carrier for a VAT/NAP export run. The wizard/return controller builds this so
 * that the service layer never depends on the trigger entity.
 */
public class BgVatExportRequestDto {

  private Long companyId;
  private String companyName;

  /**
   * Reporting entity VAT number (ЕИК по ДДС) — company.partner.taxNbr. Header field of the file.
   */
  private String companyVatNumber;

  /**
   * Registered-person address (VTR / DEKLAR header). Empty until wired to Company/Partner (1б-2).
   */
  private String companyAddress = "";

  /**
   * Клонът (позиции 22–25 на дневниците). Без клонове — „0“ ({@link
   * BgVatFieldFormatter#DEFAULT_BRANCH}), както в приетите от portal.nra.bg файлове.
   */
  private String branchNumber = BgVatFieldFormatter.DEFAULT_BRANCH;

  private LocalDate periodFrom;
  private LocalDate periodTo;
  private String reportType; // purchases | sales | declaration | vies | all
  private String encoding; // cp1251 | utf8
  private boolean dryRun;

  /**
   * Zero-declaration mode: when {@code true}, every scoped file is emitted even when its diary has
   * no rows (empty PRODAGBI/POKUPKI, header-only VIES, valid zero DEKLAR) so the НАП package always
   * contains the full set of four files. Left {@code false} for the quick sales-only wizard, which
   * keeps its historic skip-on-empty behaviour.
   */
  private boolean emitEmptyFiles;

  // ---- Declarant (декларатор) placeholders — VDR (VIES) / submitter (DEKLAR). ----
  // Source in 1б-2: BG App config (firm default) → Company override. Empty stubs for now.
  private String declarantEgn = "";
  private String declarantNames = "";
  private String declarantCity = "";
  private String declarantPostCode = "";
  private String declarantAddress = "";
  private String declarantCapacity = ""; // "A" пълномощник / "R" представляващ

  // ---- Manual DEKLAR cells — source in 1б-2 (k33 = parametric entity). Stub 0 now. ----
  private BigDecimal coefficientK33 = BigDecimal.ZERO; // кл.33
  private BigDecimal cell70 = BigDecimal.ZERO;
  private BigDecimal cell71 = BigDecimal.ZERO;
  private BigDecimal cell80 = BigDecimal.ZERO;
  private BigDecimal cell81 = BigDecimal.ZERO;
  private BigDecimal cell82 = BigDecimal.ZERO;

  /** DEKLAR "submitter" header field = "ЕГН/имена" (empty when the declarant is not configured). */
  public String getDeclarantSubmitter() {
    if ((declarantEgn == null || declarantEgn.isEmpty())
        && (declarantNames == null || declarantNames.isEmpty())) {
      return "";
    }
    return nz(declarantEgn) + "/" + nz(declarantNames);
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  public Long getCompanyId() {
    return companyId;
  }

  public void setCompanyId(Long companyId) {
    this.companyId = companyId;
  }

  public String getCompanyName() {
    return companyName;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
  }

  public String getCompanyVatNumber() {
    return companyVatNumber;
  }

  public void setCompanyVatNumber(String companyVatNumber) {
    this.companyVatNumber = companyVatNumber;
  }

  public String getBranchNumber() {
    return branchNumber;
  }

  public void setBranchNumber(String branchNumber) {
    this.branchNumber = branchNumber;
  }

  public LocalDate getPeriodFrom() {
    return periodFrom;
  }

  public void setPeriodFrom(LocalDate periodFrom) {
    this.periodFrom = periodFrom;
  }

  public LocalDate getPeriodTo() {
    return periodTo;
  }

  public void setPeriodTo(LocalDate periodTo) {
    this.periodTo = periodTo;
  }

  public String getReportType() {
    return reportType;
  }

  public void setReportType(String reportType) {
    this.reportType = reportType;
  }

  public String getEncoding() {
    return encoding;
  }

  public void setEncoding(String encoding) {
    this.encoding = encoding;
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public void setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public boolean isEmitEmptyFiles() {
    return emitEmptyFiles;
  }

  public void setEmitEmptyFiles(boolean emitEmptyFiles) {
    this.emitEmptyFiles = emitEmptyFiles;
  }

  public String getCompanyAddress() {
    return companyAddress;
  }

  public void setCompanyAddress(String companyAddress) {
    this.companyAddress = companyAddress == null ? "" : companyAddress;
  }

  public String getDeclarantEgn() {
    return declarantEgn;
  }

  public void setDeclarantEgn(String declarantEgn) {
    this.declarantEgn = declarantEgn == null ? "" : declarantEgn;
  }

  public String getDeclarantNames() {
    return declarantNames;
  }

  public void setDeclarantNames(String declarantNames) {
    this.declarantNames = declarantNames == null ? "" : declarantNames;
  }

  public String getDeclarantCity() {
    return declarantCity;
  }

  public void setDeclarantCity(String declarantCity) {
    this.declarantCity = declarantCity == null ? "" : declarantCity;
  }

  public String getDeclarantPostCode() {
    return declarantPostCode;
  }

  public void setDeclarantPostCode(String declarantPostCode) {
    this.declarantPostCode = declarantPostCode == null ? "" : declarantPostCode;
  }

  public String getDeclarantAddress() {
    return declarantAddress;
  }

  public void setDeclarantAddress(String declarantAddress) {
    this.declarantAddress = declarantAddress == null ? "" : declarantAddress;
  }

  public String getDeclarantCapacity() {
    return declarantCapacity;
  }

  public void setDeclarantCapacity(String declarantCapacity) {
    this.declarantCapacity = declarantCapacity == null ? "" : declarantCapacity;
  }

  public BigDecimal getCoefficientK33() {
    return coefficientK33;
  }

  public void setCoefficientK33(BigDecimal coefficientK33) {
    this.coefficientK33 = coefficientK33 == null ? BigDecimal.ZERO : coefficientK33;
  }

  public BigDecimal getCell70() {
    return cell70;
  }

  public void setCell70(BigDecimal cell70) {
    this.cell70 = cell70 == null ? BigDecimal.ZERO : cell70;
  }

  public BigDecimal getCell71() {
    return cell71;
  }

  public void setCell71(BigDecimal cell71) {
    this.cell71 = cell71 == null ? BigDecimal.ZERO : cell71;
  }

  public BigDecimal getCell80() {
    return cell80;
  }

  public void setCell80(BigDecimal cell80) {
    this.cell80 = cell80 == null ? BigDecimal.ZERO : cell80;
  }

  public BigDecimal getCell81() {
    return cell81;
  }

  public void setCell81(BigDecimal cell81) {
    this.cell81 = cell81 == null ? BigDecimal.ZERO : cell81;
  }

  public BigDecimal getCell82() {
    return cell82;
  }

  public void setCell82(BigDecimal cell82) {
    this.cell82 = cell82 == null ? BigDecimal.ZERO : cell82;
  }
}
