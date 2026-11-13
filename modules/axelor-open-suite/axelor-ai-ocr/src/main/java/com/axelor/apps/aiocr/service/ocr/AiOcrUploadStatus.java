package com.axelor.apps.aiocr.service.ocr;

/**
 * Single source of truth for {@code AiOcrUpload.statusSelect} codes (selection
 * ai.ocr.upload.status.select).
 */
public final class AiOcrUploadStatus {

  /**
   * A manually uploaded document/archive that is stored but NOT runnable: the background dispatcher
   * only drains {@link #QUEUED}, so nothing starts until the user presses Process / Extract archive
   * (which enqueues the row first). Never used for pipeline children — they are created {@link
   * #QUEUED} inside a trusted system context, which the carrier rollup depends on.
   */
  public static final String UPLOADED = "uploaded";

  public static final String QUEUED = "queued";
  public static final String PROCESSING = "processing";
  public static final String COMPLETED = "completed";
  public static final String FAILED = "failed";
  public static final String DUPLICATE = "duplicate";
  public static final String CANCELLED = "cancelled";
  public static final String UNSUPPORTED = "unsupported";

  /** Uncertain document type — the user chooses Supplier Invoice or Move. */
  public static final String NEEDS_REVIEW = "needsReview";

  /**
   * The produced supplier invoice was deleted from the Suppl. Invoices module. The upload row is
   * kept (unlinked) so the client can reprocess the original document into a fresh draft.
   */
  public static final String DELETED_FROM_SUPPL_INVOICES = "deletedFromSupplInvoices";

  /**
   * The produced accounting move was deleted from the Moves module. The upload row + source file
   * are kept (unlinked) so the client can reprocess the original document.
   */
  public static final String DELETED_FROM_MOVES = "deletedFromMoves";

  /**
   * The produced bank statement(s) were deleted from the Bank Statements module. The upload row +
   * source PDF are kept (unlinked) so the client can reprocess the original document into fresh
   * Bank Statement(s) — never into an invoice or a move.
   */
  public static final String DELETED_FROM_BANK_STATEMENTS = "deletedFromBankStatements";

  // Carrier-only lifecycle states (a record holding a ZIP in archiveFile).
  public static final String EXTRACTING = "extracting";
  public static final String COMPLETED_WITH_ISSUES = "completedWithIssues";

  /** Carrier-only terminal state: a finished ZIP archive kept as the import record. */
  public static final String ARCHIVE = "archive";

  // Carrier kind (AiOcrUpload.carrierKind, selection ai.ocr.carrier.kind.select).
  public static final String CARRIER_KIND_ZIP = "zip";
  public static final String CARRIER_KIND_PDF_SPLIT = "pdfSplit";

  /** A PDF holding several bank statements: split into one child per statement. */
  public static final String CARRIER_KIND_PDF_BANK_STATEMENT = "pdfBankStatement";

  // Split-child segment type (AiOcrUpload.segmentType, selection ai.ocr.segment.type.select).
  public static final String SEGMENT_TYPE_INVOICE = "invoice";
  public static final String SEGMENT_TYPE_MOVE = "move";
  public static final String SEGMENT_TYPE_REVIEW = "review";

  /** A split child that is one bank statement of a multi-statement PDF. */
  public static final String SEGMENT_TYPE_BANK_STATEMENT = "bankStatement";

  private AiOcrUploadStatus() {}
}
