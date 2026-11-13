package com.axelor.apps.bglocalization.service.vat.dto;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One accounting DOCUMENT (invoice / credit note / protocol / Z-report) as a diary row candidate.
 *
 * <p>The collector (WS-2) groups posted move data by document into these DTOs. Adapters consume
 * DTOs only — never Axelor entities — so the legal formatting layer stays isolated from the ORM.
 */
public class BgVatDocumentDto {

  /**
   * NAP numeric document-type code (вид на документа, 01–95). NOT AVAILABLE in the data model yet —
   * there is no {@code napDocumentTypeSelect} field. Must be provided by Block C; must not be
   * guessed/derived heuristically in production.
   */
  private String napDocumentType;

  private String documentNumber;
  private LocalDate documentDate;

  /**
   * Датата на данъчното събитие на фактурата ({@code Invoice.bgVatTaxEventDate}); {@code null} — не
   * е въведена. От нея VIES извежда периода на изискуемостта (ППЗДДС чл. 117 ал. 2 т. 11).
   */
  private LocalDate taxEventDate;

  /**
   * Изричният „Период на отразяване по ЗДДС“ на фактурата ({@code ГГГГММ}); {@code null} — месецът
   * на статията. С него документ от по-ранен месец е в този период нарочно.
   */
  private String reportingPeriod;

  /**
   * Авансова фактура ({@code Invoice.operationSubTypeSelect} = аванс). ВОД и тристранната по нея не
   * са във VIES (ППЗДДС чл. 117 ал. 4); услугата по чл. 21 ал. 2 — да (ал. 2 т. 4 б. „б“).
   */
  private boolean advancePayment;

  private String counterpartyName;

  /**
   * „Вид на стоката / обхват и вид на услугата“ (02-08 / 03-10) — {@code BgVatSubject}; празно тук
   * значи „неизвестно“, адаптерите пишат резервната стойност.
   */
  private String subject;

  /**
   * Resolved counterparty VAT / ЕИК number that goes into the diary (Invoice.partnerTaxNbr first,
   * then Partner.taxNbr).
   */
  private String counterpartyVatNumber;

  /**
   * The counterparty's identification for the diaries (02-07 / 03-08): VAT number, ИН по § 1 ДР
   * ППЗДДС or „999999999999999“ ({@code BgVatCounterpartyId}). {@link #counterpartyVatNumber} is
   * only the VAT number — the one VIES reads.
   */
  private String counterpartyDiaryId;

  /** Raw candidate: {@code Invoice.partnerTaxNbr} (kept for dry-run verification of real data). */
  private String invoicePartnerTaxNbr;

  /** Raw candidate: {@code Partner.taxNbr} (kept for dry-run verification of real data). */
  private String partnerTaxNbr;

  private String journalCode;
  private String fiscalPositionCode;

  /**
   * Mirrors Invoice.operationTypeSelect (1 supplier purchase / 2 supplier refund / 3 customer sale
   * / 4 customer refund).
   */
  private Integer operationType;

  private BigDecimal totalBase;
  private BigDecimal totalVat;
  private BigDecimal totalGross;

  private List<BgVatAmountDto> amounts = new ArrayList<>();

  /**
   * Вид на доставката (поле 02-27 продажби / 03-45 покупки) — read from Invoice/Move {@code
   * bgVatSupplyType}. Flows straight into the row DTO's {@code supplyTypeArt163a}.
   */
  private String supplyTypeArt163a;

  /**
   * PURCHASE value columns к.09–к.15, accumulated by the collector from each line's {@code
   * bgVatCreditType}. The purchase adapter copies these into the row (no further routing).
   */
  private final Map<BgVatPurchaseColumn, BigDecimal> purchaseAmounts =
      new EnumMap<>(BgVatPurchaseColumn.class);

  /**
   * PRODAGBI value columns filled DIRECTLY (not via tax-code routing) — used for manual VAT
   * protocols (ВОП/чл.163а/услуги) whose self-charged output must also appear in the sales diary.
   * {@code BgVatSalesAdapter.toRow} merges them into the row before the к.09/к.10 totals; DEKLAR
   * and VIES read the row only.
   */
  private final Map<BgVatSalesColumn, BigDecimal> salesAmounts =
      new EnumMap<>(BgVatSalesColumn.class);

  /**
   * A row with identification only — columns 1–8, no amounts — that still belongs in the diaries:
   * the customs document of a чл. 167а import (ППЗДДС чл. 49а ал. 4 т. 2 и ал. 5 т. 2). The diary
   * filters let it in by its document type; DEKLAR counts it and sums nothing.
   */
  private boolean identificationOnly;

  /**
   * The row belongs to the PURCHASE diary only, whatever its document type allows: the supplier's
   * invoice under чл. 163а, which the recipient reflects „в дневника за покупките, като попълва
   * само данните в колони от 1 до 8а“ (ППЗДДС чл. 97а ал. 5). Its type „01“ is allowed in both
   * diaries.
   */
  private boolean purchasesOnly;

  /**
   * Покупна фактура с поне един ред с обратно начисляване — основата и данъкът ѝ са на протокола по
   * чл. 117 ЗДДС. Казва защо фактурата може да не е в нито един дневник.
   */
  private boolean reverseChargeInvoice;

  /**
   * Покупна фактура, чиито редове (без тези с обратно начисляване) са всичките с нулева основа и
   * нулев ДДС във валутата на дружеството — колоните на POKUPKI остават празни.
   */
  private boolean zeroAmountPurchase;

  /**
   * Закъснял протокол: датата на документа (издаването) е след датата на статията, която е в
   * периода на изискуемостта (ППЗДДС чл. 81 ал. 5) — датата е извън периода нарочно.
   */
  private boolean issuedLate;

  /**
   * Основата на протокола, изведена от данъка (данък / ставка) без изрична основа и без базови
   * редове, които да я сверят — за ПРЕДУПРЕЖДЕНИЕТО; {@code null}, когато основата не е такава.
   */
  private BigDecimal derivedBase;

  /** Collector-level, non-blocking findings (e.g. rate/base mismatch on a protocol). */
  private final List<String> warnings = new ArrayList<>();

  /**
   * Collector-level BLOCKING findings on this document (e.g. a protocol rate that is not a
   * statutory one). {@code BgVatValidator} reports each one as an ERROR, so the package is not
   * ready for filing.
   */
  private final List<BgVatValidationErrorDto> errors = new ArrayList<>();

  /** Add {@code value} to a POKUPKI column (accumulates when several lines hit the same column). */
  public void addPurchaseAmount(BgVatPurchaseColumn column, BigDecimal value) {
    if (column == null || value == null || value.signum() == 0) {
      return;
    }
    purchaseAmounts.merge(column, value, BigDecimal::add);
  }

  /**
   * Колоните на POKUPKI за документа: сборът по колона с пълната точност, закръглен до стотинка
   * (HALF_UP). Това четат POKUPKI, DEKLAR и SAF-T; {@link #getPurchaseAmounts} остава пълната
   * точност за маршрутизацията.
   */
  public Map<BgVatPurchaseColumn, BigDecimal> getPurchaseDiaryAmounts() {
    Map<BgVatPurchaseColumn, BigDecimal> rounded = new EnumMap<>(BgVatPurchaseColumn.class);
    purchaseAmounts.forEach(
        (column, value) -> rounded.put(column, BgVatFieldFormatter.roundAmount(value)));
    return rounded;
  }

  public BigDecimal getPurchaseAmount(BgVatPurchaseColumn column) {
    return purchaseAmounts.getOrDefault(column, BigDecimal.ZERO);
  }

  public Map<BgVatPurchaseColumn, BigDecimal> getPurchaseAmounts() {
    return purchaseAmounts;
  }

  /** Add {@code value} to a PRODAGBI column directly (protocol self-charged output side). */
  public void addSalesAmount(BgVatSalesColumn column, BigDecimal value) {
    if (column == null || value == null || value.signum() == 0) {
      return;
    }
    salesAmounts.merge(column, value, BigDecimal::add);
  }

  public BigDecimal getSalesAmount(BgVatSalesColumn column) {
    return salesAmounts.getOrDefault(column, BigDecimal.ZERO);
  }

  public Map<BgVatSalesColumn, BigDecimal> getSalesAmounts() {
    return salesAmounts;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public void addWarning(String warning) {
    if (warning != null && !warning.trim().isEmpty()) {
      warnings.add(warning);
    }
  }

  public List<BgVatValidationErrorDto> getErrors() {
    return errors;
  }

  /** Add a blocking finding; {@code code} is one of the {@code BgVatValidator} document codes. */
  public void addError(String code, String message) {
    errors.add(new BgVatValidationErrorDto(code, BgVatValidationErrorDto.SEVERITY_ERROR, message));
  }

  public boolean isIdentificationOnly() {
    return identificationOnly;
  }

  public void setIdentificationOnly(boolean identificationOnly) {
    this.identificationOnly = identificationOnly;
  }

  public BigDecimal getDerivedBase() {
    return derivedBase;
  }

  public void setDerivedBase(BigDecimal derivedBase) {
    this.derivedBase = derivedBase;
  }

  public boolean isIssuedLate() {
    return issuedLate;
  }

  public void setIssuedLate(boolean issuedLate) {
    this.issuedLate = issuedLate;
  }

  public boolean isPurchasesOnly() {
    return purchasesOnly;
  }

  public void setPurchasesOnly(boolean purchasesOnly) {
    this.purchasesOnly = purchasesOnly;
  }

  public boolean isReverseChargeInvoice() {
    return reverseChargeInvoice;
  }

  public void setReverseChargeInvoice(boolean reverseChargeInvoice) {
    this.reverseChargeInvoice = reverseChargeInvoice;
  }

  public boolean isZeroAmountPurchase() {
    return zeroAmountPurchase;
  }

  public void setZeroAmountPurchase(boolean zeroAmountPurchase) {
    this.zeroAmountPurchase = zeroAmountPurchase;
  }

  public String getSupplyTypeArt163a() {
    return supplyTypeArt163a;
  }

  public void setSupplyTypeArt163a(String supplyTypeArt163a) {
    this.supplyTypeArt163a = supplyTypeArt163a;
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

  public LocalDate getTaxEventDate() {
    return taxEventDate;
  }

  public void setTaxEventDate(LocalDate taxEventDate) {
    this.taxEventDate = taxEventDate;
  }

  public String getReportingPeriod() {
    return reportingPeriod;
  }

  public void setReportingPeriod(String reportingPeriod) {
    this.reportingPeriod = reportingPeriod;
  }

  public boolean isAdvancePayment() {
    return advancePayment;
  }

  public void setAdvancePayment(boolean advancePayment) {
    this.advancePayment = advancePayment;
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

  public String getCounterpartyVatNumber() {
    return counterpartyVatNumber;
  }

  public void setCounterpartyVatNumber(String counterpartyVatNumber) {
    this.counterpartyVatNumber = counterpartyVatNumber;
  }

  /**
   * The diary identification; a document built without the collector (no diary id set) falls back
   * to its VAT number.
   */
  public String getCounterpartyDiaryId() {
    return counterpartyDiaryId != null ? counterpartyDiaryId : counterpartyVatNumber;
  }

  public void setCounterpartyDiaryId(String counterpartyDiaryId) {
    this.counterpartyDiaryId = counterpartyDiaryId;
  }

  public String getInvoicePartnerTaxNbr() {
    return invoicePartnerTaxNbr;
  }

  public void setInvoicePartnerTaxNbr(String invoicePartnerTaxNbr) {
    this.invoicePartnerTaxNbr = invoicePartnerTaxNbr;
  }

  public String getPartnerTaxNbr() {
    return partnerTaxNbr;
  }

  public void setPartnerTaxNbr(String partnerTaxNbr) {
    this.partnerTaxNbr = partnerTaxNbr;
  }

  public String getJournalCode() {
    return journalCode;
  }

  public void setJournalCode(String journalCode) {
    this.journalCode = journalCode;
  }

  public String getFiscalPositionCode() {
    return fiscalPositionCode;
  }

  public void setFiscalPositionCode(String fiscalPositionCode) {
    this.fiscalPositionCode = fiscalPositionCode;
  }

  public Integer getOperationType() {
    return operationType;
  }

  public void setOperationType(Integer operationType) {
    this.operationType = operationType;
  }

  public BigDecimal getTotalBase() {
    return totalBase;
  }

  public void setTotalBase(BigDecimal totalBase) {
    this.totalBase = totalBase;
  }

  public BigDecimal getTotalVat() {
    return totalVat;
  }

  public void setTotalVat(BigDecimal totalVat) {
    this.totalVat = totalVat;
  }

  public BigDecimal getTotalGross() {
    return totalGross;
  }

  public void setTotalGross(BigDecimal totalGross) {
    this.totalGross = totalGross;
  }

  public List<BgVatAmountDto> getAmounts() {
    return amounts;
  }

  public void setAmounts(List<BgVatAmountDto> amounts) {
    this.amounts = amounts;
  }
}
