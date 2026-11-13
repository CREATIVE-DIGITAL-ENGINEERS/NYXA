package com.axelor.apps.bglocalization.service.vat.dto;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * One row of the sales diary (Дневник за продажбите). Identification fields (кл.01–кл.08) plus the
 * numeric кл.09–кл.25 amount columns.
 *
 * <p>Built by {@code BgVatSalesAdapter} from a {@link BgVatDocumentDto}; the amount columns are
 * populated per the {@code BgVatSalesTaxCodeMapping}. This is a plain carrier — the physical file
 * layout lives entirely in the adapter's row formatter.
 */
public class BgVatSalesRowDto {

  // Identification (кл.01–кл.08 + per-row header fields).
  private String companyVatNumber; // ЕИК по ДДС на регистрираното лице
  private String periodCode; // идентификатор на дневника ГГГГММ
  private String branch; // клон (по подразбиране "0")
  private int sequence; // кл.01 пореден номер на документа в дневника
  private String napDocumentType; // кл.03 вид на документа (01/02/03/07/09)
  private String documentNumber; // кл.04 номер на документа
  private LocalDate documentDate; // кл.05 дата на документа
  private String counterpartyVatNumber; // кл.06 идентификационен номер на контрагента
  private String counterpartyName; // кл.07 име на контрагента
  private String subject; // кл.08 вид на стоката/услугата (свободен текст)

  // Поле 02-27 „Доставка по чл.163а/чл.167а ЗДДС" — Символен(2), в края на записа.
  // "01" = част I, "02" = част II (Приложение №2); "" за нормална доставка.
  private String supplyTypeArt163a = "";

  private final Map<BgVatSalesColumn, BigDecimal> amounts = new EnumMap<>(BgVatSalesColumn.class);

  /** Add {@code value} to a кл. column (accumulates when several buckets hit the same column). */
  public void addAmount(BgVatSalesColumn column, BigDecimal value) {
    if (column == null || value == null || value.signum() == 0) {
      return;
    }
    amounts.merge(column, value, BigDecimal::add);
  }

  public BigDecimal getAmount(BgVatSalesColumn column) {
    return amounts.getOrDefault(column, BigDecimal.ZERO);
  }

  public Map<BgVatSalesColumn, BigDecimal> getAmounts() {
    return amounts;
  }

  public String getCompanyVatNumber() {
    return companyVatNumber;
  }

  public void setCompanyVatNumber(String companyVatNumber) {
    this.companyVatNumber = companyVatNumber;
  }

  public String getPeriodCode() {
    return periodCode;
  }

  public void setPeriodCode(String periodCode) {
    this.periodCode = periodCode;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public int getSequence() {
    return sequence;
  }

  public void setSequence(int sequence) {
    this.sequence = sequence;
  }

  public String getNapDocumentType() {
    return napDocumentType;
  }

  public void setNapDocumentType(String napDocumentType) {
    this.napDocumentType = napDocumentType;
  }

  public String getDocumentNumber() {
    return documentNumber;
  }

  public void setDocumentNumber(String documentNumber) {
    this.documentNumber = documentNumber;
  }

  public LocalDate getDocumentDate() {
    return documentDate;
  }

  public void setDocumentDate(LocalDate documentDate) {
    this.documentDate = documentDate;
  }

  public String getCounterpartyVatNumber() {
    return counterpartyVatNumber;
  }

  public void setCounterpartyVatNumber(String counterpartyVatNumber) {
    this.counterpartyVatNumber = counterpartyVatNumber;
  }

  public String getCounterpartyName() {
    return counterpartyName;
  }

  public void setCounterpartyName(String counterpartyName) {
    this.counterpartyName = counterpartyName;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getSupplyTypeArt163a() {
    return supplyTypeArt163a;
  }

  public void setSupplyTypeArt163a(String supplyTypeArt163a) {
    this.supplyTypeArt163a = supplyTypeArt163a == null ? "" : supplyTypeArt163a;
  }
}
