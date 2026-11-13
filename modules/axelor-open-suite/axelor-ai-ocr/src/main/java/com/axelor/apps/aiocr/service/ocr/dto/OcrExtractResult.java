package com.axelor.apps.aiocr.service.ocr.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured result returned by the OCR extraction service ({@code ai-services POST /extract}). It
 * is a pure data carrier — no accounting logic. Axelor maps it to a DRAFT supplier invoice.
 */
public class OcrExtractResult {

  /** True when the extractor itself failed (no text / engine error / timeout) — no data usable. */
  private boolean extractionFailed;

  private String supplierName;
  private String supplierEik;
  private String supplierVat;

  private String supplierAddress;
  private String supplierAddressRecipient;

  /**
   * Structured supplier-address parts from Azure's {@code VendorAddress.valueAddress} (when
   * present). Preferred over regex-parsing the free text; any may be null.
   */
  private String supplierAddrCity;

  private String supplierAddrStreet;
  private String supplierAddrNumber;
  private String supplierAddrZip;

  /**
   * Recipient/customer identity as read from the invoice (Azure CustomerName / CustomerTaxId).
   * Captured only to cross-check against the WeAccount company that owns the upload (swap / wrong
   * company detection) — never used to pick or create the company.
   */
  private String customerName;

  private String customerEik;
  private String customerVat;
  private String customerAddress;
  private String customerAddressRecipient;
  private String customerId;

  private String invoiceNumber;
  private LocalDate invoiceDate;
  private LocalDate dueDate;
  private String currency;
  private String napDocumentType;
  private String paymentTerm;
  private String iban;
  private String swift;

  /** Upload/job id echoed for tracing (never derived from user input). */
  private String correlationId;

  private List<OcrExtractLine> lines = new ArrayList<>();

  private BigDecimal totalBase;
  private BigDecimal totalVat;
  private BigDecimal totalGross;

  /**
   * Full OCR text (Azure {@code analyzeResult.content}). Present for both prebuilt-invoice and
   * prebuilt-read responses. Used only as a fallback for BG-specific signals not reliably carried
   * by structured fields. Amounts and identities always come from structured fields.
   */
  private String rawText;

  /**
   * Raw Azure {@code analyzeResult} JSON for the whole analysis (documents[], pages[], tables[],
   * boundingRegions, spans). Populated only by the Azure engine on the top-level result of a single
   * OCR call; used by the PDF document splitter to segment a multi-invoice PDF without a second
   * Azure call. Not carried on per-segment child results.
   */
  private String rawJson;

  /** Overall extraction confidence, 0..100. */
  private BigDecimal confidence;

  private String engineUsed;
  private String blobUrl;

  /**
   * Pre-OCR optimization audit: original size and the size actually sent to Azure (derived copy).
   */
  private Long originalSizeBytes;

  private Long optimizedSizeBytes;

  private List<String> errors = new ArrayList<>();
  private List<String> warnings = new ArrayList<>();

  /** Non-blocking anomalies (e.g. totals that don't reconcile) surfaced for human review. */
  private List<String> discrepancies = new ArrayList<>();

  /**
   * Structured record of the transient transport compression applied to oversized pages — one entry
   * per compressed page (page number + before/after byte size). Kept structured (never a formatted
   * string) so the intake can build a short, client-friendly summary for the upload's Extraction
   * information field while the full technical detail is logged separately at INFO. Empty when no
   * page needed compression.
   */
  private List<PageCompression> pageCompressions = new ArrayList<>();

  /** One page's transient transport-compression sizes (the lossless page was never modified). */
  public static final class PageCompression {
    public final int page;
    public final long originalBytes;
    public final long compressedBytes;

    public PageCompression(int page, long originalBytes, long compressedBytes) {
      this.page = page;
      this.originalBytes = originalBytes;
      this.compressedBytes = compressedBytes;
    }
  }

  /** Per-critical-field confidence (0..1) from the engine, when available. */
  private java.util.Map<String, Double> fieldConfidence = new java.util.HashMap<>();

  public boolean isExtractionFailed() {
    return extractionFailed;
  }

  public void setExtractionFailed(boolean extractionFailed) {
    this.extractionFailed = extractionFailed;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public void setSupplierName(String supplierName) {
    this.supplierName = supplierName;
  }

  public String getSupplierEik() {
    return supplierEik;
  }

  public void setSupplierEik(String supplierEik) {
    this.supplierEik = supplierEik;
  }

  public String getSupplierVat() {
    return supplierVat;
  }

  public void setSupplierVat(String supplierVat) {
    this.supplierVat = supplierVat;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public void setInvoiceNumber(String invoiceNumber) {
    this.invoiceNumber = invoiceNumber;
  }

  public LocalDate getInvoiceDate() {
    return invoiceDate;
  }

  public void setInvoiceDate(LocalDate invoiceDate) {
    this.invoiceDate = invoiceDate;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getNapDocumentType() {
    return napDocumentType;
  }

  public void setNapDocumentType(String napDocumentType) {
    this.napDocumentType = napDocumentType;
  }

  public List<OcrExtractLine> getLines() {
    return lines;
  }

  public void setLines(List<OcrExtractLine> lines) {
    this.lines = lines;
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

  public String getRawText() {
    return rawText;
  }

  public void setRawText(String rawText) {
    this.rawText = rawText;
  }

  public String getRawJson() {
    return rawJson;
  }

  public void setRawJson(String rawJson) {
    this.rawJson = rawJson;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public void setConfidence(BigDecimal confidence) {
    this.confidence = confidence;
  }

  public String getEngineUsed() {
    return engineUsed;
  }

  public void setEngineUsed(String engineUsed) {
    this.engineUsed = engineUsed;
  }

  public String getBlobUrl() {
    return blobUrl;
  }

  public void setBlobUrl(String blobUrl) {
    this.blobUrl = blobUrl;
  }

  public Long getOriginalSizeBytes() {
    return originalSizeBytes;
  }

  public void setOriginalSizeBytes(Long originalSizeBytes) {
    this.originalSizeBytes = originalSizeBytes;
  }

  public Long getOptimizedSizeBytes() {
    return optimizedSizeBytes;
  }

  public void setOptimizedSizeBytes(Long optimizedSizeBytes) {
    this.optimizedSizeBytes = optimizedSizeBytes;
  }

  public List<String> getErrors() {
    return errors;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public List<String> getDiscrepancies() {
    return discrepancies;
  }

  public java.util.Map<String, Double> getFieldConfidence() {
    return fieldConfidence;
  }

  // In-memory only: populated on the top-level extraction and consumed immediately when persisting
  // the upload's Extraction information. It is never part of a cached per-segment split payload, so
  // it is excluded from JSON (de)serialization (PageCompression has no default constructor).
  @com.fasterxml.jackson.annotation.JsonIgnore
  public List<PageCompression> getPageCompressions() {
    return pageCompressions;
  }

  public String getSupplierAddress() {
    return supplierAddress;
  }

  public void setSupplierAddress(String supplierAddress) {
    this.supplierAddress = supplierAddress;
  }

  public String getSupplierAddressRecipient() {
    return supplierAddressRecipient;
  }

  public void setSupplierAddressRecipient(String supplierAddressRecipient) {
    this.supplierAddressRecipient = supplierAddressRecipient;
  }

  public String getSupplierAddrCity() {
    return supplierAddrCity;
  }

  public void setSupplierAddrCity(String supplierAddrCity) {
    this.supplierAddrCity = supplierAddrCity;
  }

  public String getSupplierAddrStreet() {
    return supplierAddrStreet;
  }

  public void setSupplierAddrStreet(String supplierAddrStreet) {
    this.supplierAddrStreet = supplierAddrStreet;
  }

  public String getSupplierAddrNumber() {
    return supplierAddrNumber;
  }

  public void setSupplierAddrNumber(String supplierAddrNumber) {
    this.supplierAddrNumber = supplierAddrNumber;
  }

  public String getSupplierAddrZip() {
    return supplierAddrZip;
  }

  public void setSupplierAddrZip(String supplierAddrZip) {
    this.supplierAddrZip = supplierAddrZip;
  }

  public String getCustomerName() {
    return customerName;
  }

  public void setCustomerName(String customerName) {
    this.customerName = customerName;
  }

  public String getCustomerEik() {
    return customerEik;
  }

  public void setCustomerEik(String customerEik) {
    this.customerEik = customerEik;
  }

  public String getCustomerVat() {
    return customerVat;
  }

  public void setCustomerVat(String customerVat) {
    this.customerVat = customerVat;
  }

  public String getCustomerAddress() {
    return customerAddress;
  }

  public void setCustomerAddress(String customerAddress) {
    this.customerAddress = customerAddress;
  }

  public String getCustomerAddressRecipient() {
    return customerAddressRecipient;
  }

  public void setCustomerAddressRecipient(String customerAddressRecipient) {
    this.customerAddressRecipient = customerAddressRecipient;
  }

  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public void setDueDate(LocalDate dueDate) {
    this.dueDate = dueDate;
  }

  public String getPaymentTerm() {
    return paymentTerm;
  }

  public void setPaymentTerm(String paymentTerm) {
    this.paymentTerm = paymentTerm;
  }

  public String getIban() {
    return iban;
  }

  public void setIban(String iban) {
    this.iban = iban;
  }

  public String getSwift() {
    return swift;
  }

  public void setSwift(String swift) {
    this.swift = swift;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }
}
