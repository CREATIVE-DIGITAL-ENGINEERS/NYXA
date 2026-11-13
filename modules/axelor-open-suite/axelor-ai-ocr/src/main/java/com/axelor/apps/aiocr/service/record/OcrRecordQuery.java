package com.axelor.apps.aiocr.service.record;

import java.time.LocalDate;

/**
 * Search criteria for OCR records. A plain bean so the same query can be built by an AI tool, a
 * controller, or any future Nyxa UI without going through JSON.
 *
 * <p>Every criterion here is backed by an indexed column, a foreign key, or a short string column.
 * Large text columns ({@code splitSourceJson}, {@code cachedOcrJson}, {@code extractionErrors},
 * {@code rawExtractedJson}) are deliberately NOT searchable — a LIKE over them would scan megabytes
 * per row.
 */
public class OcrRecordQuery {

  /** Hard ceiling on rows any search may return, regardless of what the caller asks for. */
  public static final int MAX_LIMIT = 25;

  public static final int DEFAULT_LIMIT = 10;

  private Long uploadId;
  private Long invoiceId;
  private String invoiceNumber;
  private String fileName;
  private String partnerName;
  private String eik;
  private String iban;
  private String statusSelect;
  private String ocrReviewStatus;
  private String segmentType;
  private String carrierKind;
  private String docKey;
  private String fileHash;
  private Long importArchiveId;
  private Long companyId;
  private LocalDate createdFrom;
  private LocalDate createdTo;
  private int limit = DEFAULT_LIMIT;
  private int offset;

  public Long getUploadId() {
    return uploadId;
  }

  public OcrRecordQuery setUploadId(Long uploadId) {
    this.uploadId = uploadId;
    return this;
  }

  public Long getInvoiceId() {
    return invoiceId;
  }

  public OcrRecordQuery setInvoiceId(Long invoiceId) {
    this.invoiceId = invoiceId;
    return this;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public OcrRecordQuery setInvoiceNumber(String invoiceNumber) {
    this.invoiceNumber = invoiceNumber;
    return this;
  }

  public String getFileName() {
    return fileName;
  }

  public OcrRecordQuery setFileName(String fileName) {
    this.fileName = fileName;
    return this;
  }

  public String getPartnerName() {
    return partnerName;
  }

  public OcrRecordQuery setPartnerName(String partnerName) {
    this.partnerName = partnerName;
    return this;
  }

  public String getEik() {
    return eik;
  }

  public OcrRecordQuery setEik(String eik) {
    this.eik = eik;
    return this;
  }

  public String getIban() {
    return iban;
  }

  public OcrRecordQuery setIban(String iban) {
    this.iban = iban;
    return this;
  }

  public String getStatusSelect() {
    return statusSelect;
  }

  public OcrRecordQuery setStatusSelect(String statusSelect) {
    this.statusSelect = statusSelect;
    return this;
  }

  public String getOcrReviewStatus() {
    return ocrReviewStatus;
  }

  public OcrRecordQuery setOcrReviewStatus(String ocrReviewStatus) {
    this.ocrReviewStatus = ocrReviewStatus;
    return this;
  }

  public String getSegmentType() {
    return segmentType;
  }

  public OcrRecordQuery setSegmentType(String segmentType) {
    this.segmentType = segmentType;
    return this;
  }

  public String getCarrierKind() {
    return carrierKind;
  }

  public OcrRecordQuery setCarrierKind(String carrierKind) {
    this.carrierKind = carrierKind;
    return this;
  }

  public String getDocKey() {
    return docKey;
  }

  public OcrRecordQuery setDocKey(String docKey) {
    this.docKey = docKey;
    return this;
  }

  public String getFileHash() {
    return fileHash;
  }

  public OcrRecordQuery setFileHash(String fileHash) {
    this.fileHash = fileHash;
    return this;
  }

  public Long getImportArchiveId() {
    return importArchiveId;
  }

  public OcrRecordQuery setImportArchiveId(Long importArchiveId) {
    this.importArchiveId = importArchiveId;
    return this;
  }

  public Long getCompanyId() {
    return companyId;
  }

  public OcrRecordQuery setCompanyId(Long companyId) {
    this.companyId = companyId;
    return this;
  }

  public LocalDate getCreatedFrom() {
    return createdFrom;
  }

  public OcrRecordQuery setCreatedFrom(LocalDate createdFrom) {
    this.createdFrom = createdFrom;
    return this;
  }

  public LocalDate getCreatedTo() {
    return createdTo;
  }

  public OcrRecordQuery setCreatedTo(LocalDate createdTo) {
    this.createdTo = createdTo;
    return this;
  }

  public int getLimit() {
    return limit;
  }

  /** Clamped into 1..{@link #MAX_LIMIT}; callers cannot widen it. */
  public OcrRecordQuery setLimit(int limit) {
    this.limit = limit < 1 ? 1 : Math.min(limit, MAX_LIMIT);
    return this;
  }

  public int getOffset() {
    return offset;
  }

  public OcrRecordQuery setOffset(int offset) {
    this.offset = Math.max(0, offset);
    return this;
  }

  /** True when nothing was supplied — an unfiltered scan is refused rather than run. */
  public boolean isEmpty() {
    return uploadId == null
        && invoiceId == null
        && isBlank(invoiceNumber)
        && isBlank(fileName)
        && isBlank(partnerName)
        && isBlank(eik)
        && isBlank(iban)
        && isBlank(statusSelect)
        && isBlank(ocrReviewStatus)
        && isBlank(segmentType)
        && isBlank(carrierKind)
        && isBlank(docKey)
        && isBlank(fileHash)
        && importArchiveId == null
        && companyId == null
        && createdFrom == null
        && createdTo == null;
  }

  private static boolean isBlank(String v) {
    return v == null || v.trim().isEmpty();
  }
}
