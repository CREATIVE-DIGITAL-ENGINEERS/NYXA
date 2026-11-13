package com.axelor.apps.bglocalization.service.vat.dto;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * One row of the purchase diary (Дневник за покупките). Identification fields (03-00…03-10) plus
 * the numeric к.09–к.15 amount columns and the чл.163а/167а supply-type code (поле 03-45).
 *
 * <p>Mirror of {@code BgVatSalesRowDto}. Plain carrier — the physical file layout lives entirely in
 * {@code BgVatPurchaseAdapter#formatRow}.
 */
public class BgVatPurchaseRowDto {

  // Identification (03-00…03-10).
  private String companyVatNumber; // 03-00 ИН по ДДС на регистрираното лице
  private String periodCode; // 03-01 отчетен период ГГГГММ
  private String branch; // 03-02 клон
  private int sequence; // 03-03 пореден номер на документа в дневника
  private String napDocumentType; // 03-04 вид на документа
  private String documentNumber; // 03-06 номер на документа (символен, дословно)
  private LocalDate documentDate; // 03-07 дата на документа
  private String counterpartyVatNumber; // 03-08 ИН на доставчика
  private String counterpartyName; // 03-09 име на доставчика
  private String subject; // 03-10 предмет на доставката (свободен текст)

  // Поле 03-45 „Доставка по чл.163а/чл.167а ЗДДС" — Символен(2), в края на записа.
  private String supplyTypeArt163a = "";

  private final Map<BgVatPurchaseColumn, BigDecimal> amounts =
      new EnumMap<>(BgVatPurchaseColumn.class);

  /** Add {@code value} to a к. column (accumulates when several buckets hit the same column). */
  public void addAmount(BgVatPurchaseColumn column, BigDecimal value) {
    if (column == null || value == null || value.signum() == 0) {
      return;
    }
    amounts.merge(column, value, BigDecimal::add);
  }

  public BigDecimal getAmount(BgVatPurchaseColumn column) {
    return amounts.getOrDefault(column, BigDecimal.ZERO);
  }

  public Map<BgVatPurchaseColumn, BigDecimal> getAmounts() {
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
