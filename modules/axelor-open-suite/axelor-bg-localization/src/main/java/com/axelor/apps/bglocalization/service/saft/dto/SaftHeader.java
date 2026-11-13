package com.axelor.apps.bglocalization.service.saft.dto;

import java.time.LocalDate;

/**
 * Header на одитния файл — HeaderStructure (S.H.*) плюс разширението H.2/H.3.
 *
 * <p><b>⚠️ SelectionCriteria Е CHOICE — ТОЧНО ЕДИН КЛОН.</b> Или периодите S.SC.5–8 (месечният и
 * годишният файл: 1–12 на календарната година), или датите S.SC.3–4 (файлът при поискване).
 * Попълнени и двата клона, или нито един, спират writer-а — схемата би отказала първото, но с
 * „неочакван елемент“, а второто изобщо не е изявено решение.
 */
public final class SaftHeader {

  /** S.H.1. */
  private String auditFileVersion;

  /** S.H.2 — незадължителен. */
  private String auditFileCountry;

  /** S.H.4. */
  private LocalDate auditFileDateCreated;

  /** S.H.5. */
  private String softwareCompanyName;

  /** S.H.6. */
  private String softwareId;

  /** S.H.7. */
  private String softwareVersion;

  private SaftCompanyHeader company;

  /** S.H.8.1. */
  private SaftOwnership ownership;

  /** S.H.9 — схемата допуска само EUR. */
  private String defaultCurrencyCode;

  /** S.SC.3 — клонът с дати (файлът при поискване). */
  private LocalDate selectionStartDate;

  /** S.SC.4. */
  private LocalDate selectionEndDate;

  /** S.SC.5 — клонът с периоди (месечният и годишният файл). */
  private Integer periodStart;

  /** S.SC.6. */
  private Integer periodStartYear;

  /** S.SC.7. */
  private Integer periodEnd;

  /** S.SC.8. */
  private Integer periodEndYear;

  /** S.H.11 — M / A / D. */
  private String headerComment;

  /** H.2 — A / P / BANK / INSURANCE. */
  private String taxAccountingBasis;

  /** H.3 — незадължителен. */
  private String taxEntity;

  public String getAuditFileVersion() {
    return auditFileVersion;
  }

  public SaftHeader auditFileVersion(String value) {
    this.auditFileVersion = value;
    return this;
  }

  public String getAuditFileCountry() {
    return auditFileCountry;
  }

  public SaftHeader auditFileCountry(String value) {
    this.auditFileCountry = value;
    return this;
  }

  public LocalDate getAuditFileDateCreated() {
    return auditFileDateCreated;
  }

  public SaftHeader auditFileDateCreated(LocalDate value) {
    this.auditFileDateCreated = value;
    return this;
  }

  public String getSoftwareCompanyName() {
    return softwareCompanyName;
  }

  public SaftHeader softwareCompanyName(String value) {
    this.softwareCompanyName = value;
    return this;
  }

  public String getSoftwareId() {
    return softwareId;
  }

  public SaftHeader softwareId(String value) {
    this.softwareId = value;
    return this;
  }

  public String getSoftwareVersion() {
    return softwareVersion;
  }

  public SaftHeader softwareVersion(String value) {
    this.softwareVersion = value;
    return this;
  }

  public SaftCompanyHeader getCompany() {
    return company;
  }

  public SaftHeader company(SaftCompanyHeader value) {
    this.company = value;
    return this;
  }

  public SaftOwnership getOwnership() {
    return ownership;
  }

  public SaftHeader ownership(SaftOwnership value) {
    this.ownership = value;
    return this;
  }

  public String getDefaultCurrencyCode() {
    return defaultCurrencyCode;
  }

  public SaftHeader defaultCurrencyCode(String value) {
    this.defaultCurrencyCode = value;
    return this;
  }

  public LocalDate getSelectionStartDate() {
    return selectionStartDate;
  }

  public SaftHeader selectionStartDate(LocalDate value) {
    this.selectionStartDate = value;
    return this;
  }

  public LocalDate getSelectionEndDate() {
    return selectionEndDate;
  }

  public SaftHeader selectionEndDate(LocalDate value) {
    this.selectionEndDate = value;
    return this;
  }

  public Integer getPeriodStart() {
    return periodStart;
  }

  public SaftHeader periodStart(Integer value) {
    this.periodStart = value;
    return this;
  }

  public Integer getPeriodStartYear() {
    return periodStartYear;
  }

  public SaftHeader periodStartYear(Integer value) {
    this.periodStartYear = value;
    return this;
  }

  public Integer getPeriodEnd() {
    return periodEnd;
  }

  public SaftHeader periodEnd(Integer value) {
    this.periodEnd = value;
    return this;
  }

  public Integer getPeriodEndYear() {
    return periodEndYear;
  }

  public SaftHeader periodEndYear(Integer value) {
    this.periodEndYear = value;
    return this;
  }

  public String getHeaderComment() {
    return headerComment;
  }

  public SaftHeader headerComment(String value) {
    this.headerComment = value;
    return this;
  }

  public String getTaxAccountingBasis() {
    return taxAccountingBasis;
  }

  public SaftHeader taxAccountingBasis(String value) {
    this.taxAccountingBasis = value;
    return this;
  }

  public String getTaxEntity() {
    return taxEntity;
  }

  public SaftHeader taxEntity(String value) {
    this.taxEntity = value;
    return this;
  }
}
