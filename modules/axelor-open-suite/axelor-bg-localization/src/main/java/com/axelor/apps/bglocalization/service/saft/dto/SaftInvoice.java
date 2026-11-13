package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Invoice (InvoiceStructure). Дали контрагентът се пише като CustomerInfo или SupplierInfo, решава
 * разделът (продажби / покупки) — освен когато {@link #partyRole(PartyRole)} го казва изрично:
 * протоколът за ВОП в SalesInvoices носи SupplierInfo (QA с. 58), а схемата допуска избора
 * (S.I.2/S.I.3 са {@code xs:choice}).
 */
public final class SaftInvoice {

  /** Коя от двете структури носи контрагента. */
  public enum PartyRole {
    CUSTOMER,
    SUPPLIER
  }

  private String invoiceNo;

  /** {@code null} — по раздела: продажби → CustomerInfo, покупки → SupplierInfo. */
  private PartyRole partyRole;

  private String partyId;

  private String partyName;

  private SaftAddress billingAddress;

  private String accountId;

  private Integer period;

  private Integer periodYear;

  private LocalDate invoiceDate;

  /** Nom_Invoice_Types: 01, 02, 03… */
  private String invoiceType;

  /** Y / N. */
  private String selfBillingIndicator;

  private String transactionId;

  /** Поне един. */
  private final List<SaftInvoiceLine> lines = new ArrayList<>();

  private final List<SaftTaxInformation> taxInformationTotals = new ArrayList<>();

  /** InvoiceDocumentTotals се пише само при попълнени нето и бруто. */
  private BigDecimal netTotal;

  private BigDecimal grossTotal;

  public String getInvoiceNo() {
    return invoiceNo;
  }

  public SaftInvoice invoiceNo(String value) {
    this.invoiceNo = value;
    return this;
  }

  public PartyRole getPartyRole() {
    return partyRole;
  }

  public SaftInvoice partyRole(PartyRole value) {
    this.partyRole = value;
    return this;
  }

  public String getPartyId() {
    return partyId;
  }

  public SaftInvoice partyId(String value) {
    this.partyId = value;
    return this;
  }

  public String getPartyName() {
    return partyName;
  }

  public SaftInvoice partyName(String value) {
    this.partyName = value;
    return this;
  }

  public SaftAddress getBillingAddress() {
    return billingAddress;
  }

  public SaftInvoice billingAddress(SaftAddress value) {
    this.billingAddress = value;
    return this;
  }

  public String getAccountId() {
    return accountId;
  }

  public SaftInvoice accountId(String value) {
    this.accountId = value;
    return this;
  }

  public Integer getPeriod() {
    return period;
  }

  public SaftInvoice period(Integer value) {
    this.period = value;
    return this;
  }

  public Integer getPeriodYear() {
    return periodYear;
  }

  public SaftInvoice periodYear(Integer value) {
    this.periodYear = value;
    return this;
  }

  public LocalDate getInvoiceDate() {
    return invoiceDate;
  }

  public SaftInvoice invoiceDate(LocalDate value) {
    this.invoiceDate = value;
    return this;
  }

  public String getInvoiceType() {
    return invoiceType;
  }

  public SaftInvoice invoiceType(String value) {
    this.invoiceType = value;
    return this;
  }

  public String getSelfBillingIndicator() {
    return selfBillingIndicator;
  }

  public SaftInvoice selfBillingIndicator(String value) {
    this.selfBillingIndicator = value;
    return this;
  }

  public String getTransactionId() {
    return transactionId;
  }

  public SaftInvoice transactionId(String value) {
    this.transactionId = value;
    return this;
  }

  public List<SaftInvoiceLine> getLines() {
    return lines;
  }

  public SaftInvoice addLine(SaftInvoiceLine value) {
    this.lines.add(value);
    return this;
  }

  public List<SaftTaxInformation> getTaxInformationTotals() {
    return taxInformationTotals;
  }

  public SaftInvoice addTaxInformationTotal(SaftTaxInformation value) {
    this.taxInformationTotals.add(value);
    return this;
  }

  public BigDecimal getNetTotal() {
    return netTotal;
  }

  public SaftInvoice netTotal(BigDecimal value) {
    this.netTotal = value;
    return this;
  }

  public BigDecimal getGrossTotal() {
    return grossTotal;
  }

  public SaftInvoice grossTotal(BigDecimal value) {
    this.grossTotal = value;
    return this;
  }
}
