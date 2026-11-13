package com.axelor.apps.aiocr.service.ingest;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.bankstatement.OcrBankStatementCreator;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.DocSegment;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.SegmentPlan;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.OcrResultJson;
import com.axelor.apps.aiocr.service.optimize.PdfSplitService;
import com.axelor.apps.aiocr.service.process.AiOcrCarrierService;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands a "split PDF" carrier (one uploaded PDF containing several documents) into one child
 * {@link AiOcrUpload} per detected document, reusing the SINGLE Azure OCR result stored on the
 * carrier ({@code splitSourceJson}) — never a second Azure call. Mirrors {@link
 * AiOcrArchiveExtractService}: runs in the background, restart-safe and idempotent via a
 * deterministic {@code extractionKey} per segment (carrier hash + type + page range + crop +
 * ordinal → the {@code (importArchive, extractionKey)} unique index).
 *
 * <p>Per segment: an invoice/other-document/review segment becomes a {@code queued} child carrying
 * its slice PDF as {@code originalFile} + the per-segment {@code cachedOcrJson}. A stock receipt
 * ("Стокова разписка") creates NO record of its own — it is matched to an invoice by CONTENT (total
 * + item overlap) via {@link StockReceiptMatcher} and its slice added to that invoice child's
 * {@code stockReceiptFileSet} (one invoice may gather several receipts; duplicate copies are
 * dropped; an unmatched/ambiguous receipt is preserved on the carrier and flagged for review). A
 * POS/fiscal receipt is attached to the preceding invoice by adjacency. The carrier keeps the
 * original big PDF and is never routed to an invoice/move itself.
 */
@Singleton
public class AiOcrPdfSplitService {

  private static final Logger LOG = LoggerFactory.getLogger(AiOcrPdfSplitService.class);

  protected final AiOcrUploadRepository uploadRepo;
  protected final AiOcrCarrierService carrierService;
  protected final PdfSplitService pdfSplit;

  @Inject
  public AiOcrPdfSplitService(
      AiOcrUploadRepository uploadRepo,
      AiOcrCarrierService carrierService,
      PdfSplitService pdfSplit) {
    this.uploadRepo = uploadRepo;
    this.carrierService = carrierService;
    this.pdfSplit = pdfSplit;
  }

  /**
   * Split the carrier's PDF into child records (idempotent). Safe to re-run after a crash.
   *
   * <p>Runs inside an explicit system context scoped to the carrier's company — see the same note
   * on {@code AiOcrArchiveExtractService.extract}: split children must be created {@code queued}
   * and never reclassified as manual {@code uploaded} rows, or the carrier rollup never terminates.
   */
  public void split(Long carrierId) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null || carrier.getOriginalFile() == null) {
      finishCarrier(carrierId, "No original PDF to split.");
      return;
    }
    if (carrier.getSplitSourceJson() == null || carrier.getSplitSourceJson().trim().isEmpty()) {
      finishCarrier(carrierId, "No stored OCR result to split from.");
      return;
    }
    Path pdfPath = MetaFiles.getPath(carrier.getOriginalFile());
    if (pdfPath == null || !Files.exists(pdfPath)) {
      finishCarrier(carrierId, "The uploaded PDF could not be found in storage.");
      return;
    }
    Long companyId = carrier.getCompany() != null ? carrier.getCompany().getId() : null;
    if (AiExecutionContext.isSystem()) {
      doSplit(carrierId, carrier, pdfPath);
    } else {
      AiExecutionContext.runAsSystem(
          companyId, "ocr-pdf-split", () -> doSplit(carrierId, carrier, pdfPath));
    }
  }

  protected void doSplit(Long carrierId, AiOcrUpload carrier, Path pdfPath) {
    beginSplit(carrierId);
    Long companyId = carrier.getCompany() != null ? carrier.getCompany().getId() : null;
    Long userId = carrier.getUploadedBy() != null ? carrier.getUploadedBy().getId() : null;
    String carrierHash = carrierHash(carrier, pdfPath);
    String baseName = leaf(carrier.getFileName());

    try {
      SegmentPlan plan = new BgOcrPdfSegmenter().segment(carrier.getSplitSourceJson());
      List<DocSegment> segs = plan.segments;

      // When the plan's ONE record covers the whole PDF and nothing else is kept, its "slice" would
      // be a byte-for-byte-equivalent re-serialisation of the carrier's own file. Detected here,
      // once, against the REAL page count (not only Azure's), and passed down so the child shares
      // the carrier's MetaFile instead of storing that second copy. See
      // isWholeDocumentSingleRecord.
      boolean wholeDocument = isWholeDocumentSingleRecord(plan, pdfSplit.pageCountOrNull(pdfPath));

      // Pass 1 — create the record children (invoice / move / review). A stock or POS receipt makes
      // NO record; it is attached in pass 2. The map lets pass 2 attach a receipt to ANY invoice
      // child (its content match), even one that appears later in the PDF than the receipt.
      Map<Integer, Long> childBySeg = new HashMap<>();
      for (int i = 0; i < segs.size(); i++) {
        DocSegment seg = segs.get(i);
        if (seg.type == BgOcrPdfSegmenter.Type.BLANK
            || seg.type == BgOcrPdfSegmenter.Type.STOCK_RECEIPT
            || seg.type == BgOcrPdfSegmenter.Type.POS_RECEIPT) {
          continue;
        }
        String key = extractionKey(carrierHash, seg, i);
        Long existing = findChildId(carrierId, key);
        if (existing != null) {
          childBySeg.put(i, existing); // idempotent skip; still needed as an attach target
          continue;
        }
        Long childId =
            createChildForSegment(
                carrierId, companyId, userId, key, seg, pdfPath, baseName, i, wholeDocument);
        if (childId != null) {
          childBySeg.put(i, childId);
        }
      }

      // Match every stock receipt to an invoice by CONTENT (total + item overlap), dropping
      // duplicate copies — never by document position.
      Map<Integer, StockReceiptMatcher.Decision> decisions = new StockReceiptMatcher().match(segs);

      // Pass 2 — attach receipts.
      List<String> reviewNotes = new ArrayList<>();
      for (int i = 0; i < segs.size(); i++) {
        DocSegment seg = segs.get(i);
        if (seg.type == BgOcrPdfSegmenter.Type.STOCK_RECEIPT) {
          StockReceiptMatcher.Decision d = decisions.get(i);
          if (d == null || d.status == StockReceiptMatcher.Status.DUPLICATE) {
            continue; // duplicate physical copy → detected and ignored
          }
          if (d.status == StockReceiptMatcher.Status.MATCHED && d.invoiceSegIndex != null) {
            Long invoiceChildId = childBySeg.get(d.invoiceSegIndex);
            if (invoiceChildId != null) {
              addStockReceipt(invoiceChildId, pdfPath, seg, baseName, i);
            } else {
              reviewNotes.add(receiptLabel(seg) + " — matched invoice was not created; review.");
              preserveUnmatchedReceipt(carrierId, pdfPath, seg, baseName, i);
            }
          } else {
            // Ambiguous or no reliable invoice → never force it onto a random invoice. Preserve the
            // receipt (a separate attachment on the carrier) and flag the carrier for review.
            reviewNotes.add(
                receiptLabel(seg) + " — " + (d != null ? d.reason : "no reliable invoice match."));
            preserveUnmatchedReceipt(carrierId, pdfPath, seg, baseName, i);
          }
        } else if (seg.type == BgOcrPdfSegmenter.Type.POS_RECEIPT) {
          Long invoiceChildId = precedingInvoiceChild(segs, childBySeg, i);
          if (invoiceChildId != null) {
            attachPosReceipt(invoiceChildId, pdfPath, seg, baseName, i);
          }
        }
      }
      finishCarrier(
          carrierId,
          reviewNotes.isEmpty()
              ? null
              : "Stock receipt(s) needing review:\n" + String.join("\n", reviewNotes));
    } catch (Exception e) {
      LOG.error("PDF split failed for carrier {}: {}", carrierId, e.getMessage(), e);
      finishCarrier(carrierId, "Split stopped: " + e.getMessage());
    }
  }

  /** The nearest invoice/review child created for a segment BEFORE {@code idx} (POS adjacency). */
  private static Long precedingInvoiceChild(
      List<DocSegment> segs, Map<Integer, Long> childBySeg, int idx) {
    for (int j = idx - 1; j >= 0; j--) {
      DocSegment s = segs.get(j);
      if ((s.type == BgOcrPdfSegmenter.Type.INVOICE || s.type == BgOcrPdfSegmenter.Type.REVIEW)
          && childBySeg.containsKey(j)) {
        return childBySeg.get(j);
      }
    }
    return null;
  }

  private static String receiptLabel(DocSegment seg) {
    String pages =
        seg.fromPage == seg.toPage
            ? ("page " + seg.fromPage)
            : ("pages " + seg.fromPage + "-" + seg.toPage);
    return "Stock receipt on "
        + pages
        + (seg.receiptNumber != null ? " (No " + seg.receiptNumber + ")" : "");
  }

  /**
   * Split a "bank statements" carrier (one PDF holding several bank statements) into one child
   * {@link AiOcrUpload} per detected statement, reusing the SINGLE stored Azure OCR result ({@code
   * splitSourceJson}) — never a second Azure call. Mirrors {@link #split}: each child carries only
   * its statement's page slice as {@code originalFile}, a {@code bankStatement} {@code
   * segmentType}, and a tiny {@code {"fromPage":..,"toPage":..}} in {@code cachedOcrJson} so {@code
   * process()} re-parses that page range from the carrier's stored JSON. Idempotent via the
   * deterministic {@code extractionKey}. Statement boundaries come from the bank-statement backend
   * extension.
   */
  public void splitBankStatements(Long carrierId) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null || carrier.getOriginalFile() == null) {
      finishCarrier(carrierId, "No original PDF to split.");
      return;
    }
    if (carrier.getSplitSourceJson() == null || carrier.getSplitSourceJson().trim().isEmpty()) {
      finishCarrier(carrierId, "No stored OCR result to split from.");
      return;
    }
    Path pdfPath = MetaFiles.getPath(carrier.getOriginalFile());
    if (pdfPath == null || !Files.exists(pdfPath)) {
      finishCarrier(carrierId, "The uploaded PDF could not be found in storage.");
      return;
    }
    Long scopeCompanyId = carrier.getCompany() != null ? carrier.getCompany().getId() : null;
    if (AiExecutionContext.isSystem()) {
      doSplitBankStatements(carrierId, carrier, pdfPath);
    } else {
      AiExecutionContext.runAsSystem(
          scopeCompanyId,
          "ocr-bank-statement-split",
          () -> doSplitBankStatements(carrierId, carrier, pdfPath));
    }
  }

  protected void doSplitBankStatements(Long carrierId, AiOcrUpload carrier, Path pdfPath) {
    beginSplit(carrierId, AiOcrUploadStatus.CARRIER_KIND_PDF_BANK_STATEMENT);
    Long companyId = carrier.getCompany() != null ? carrier.getCompany().getId() : null;
    Long userId = carrier.getUploadedBy() != null ? carrier.getUploadedBy().getId() : null;
    String carrierHash = carrierHash(carrier, pdfPath);
    String baseName = leaf(carrier.getFileName());

    try {
      List<int[]> ranges;
      try {
        ranges =
            Beans.get(OcrBankStatementCreator.class)
                .detectStatementRanges(carrier.getSplitSourceJson());
      } catch (RuntimeException e) {
        finishCarrier(carrierId, "No bank-statement backend available to split.");
        return;
      }
      if (ranges == null || ranges.isEmpty()) {
        ranges = java.util.List.of(new int[] {1, Integer.MAX_VALUE});
      }
      for (int i = 0; i < ranges.size(); i++) {
        int from = ranges.get(i)[0];
        int to = ranges.get(i)[1];
        String key = bankStatementKey(carrierHash, from, to, i);
        if (findChildId(carrierId, key) != null) {
          continue; // idempotent skip (created on a previous run)
        }
        createBankStatementChild(carrierId, companyId, userId, key, pdfPath, baseName, from, to, i);
      }
      finishCarrier(carrierId, null);
    } catch (Exception e) {
      LOG.error("Bank-statement split failed for carrier {}: {}", carrierId, e.getMessage(), e);
      finishCarrier(carrierId, "Split stopped: " + e.getMessage());
    }
  }

  private Long createBankStatementChild(
      Long carrierId,
      Long companyId,
      Long userId,
      String key,
      Path pdfPath,
      String baseName,
      int from,
      int to,
      int ordinal) {
    Path slice = null;
    try {
      slice = pdfSplit.slicePages(pdfPath, from, to);
      if (slice == null || !Files.exists(slice)) {
        return null;
      }
      String fileName = bankStatementSliceName(baseName, from, to, ordinal);
      String hash = AiOcrFileHash.sha256(slice);
      String cachedJson = "{\"fromPage\":" + from + ",\"toPage\":" + to + "}";
      return upsertSplitChild(
          carrierId,
          companyId,
          userId,
          key,
          AiOcrUploadStatus.SEGMENT_TYPE_BANK_STATEMENT,
          fileName,
          hash,
          cachedJson,
          slice);
    } catch (Exception e) {
      LOG.warn(
          "Could not slice bank-statement {} of carrier {}: {}",
          ordinal,
          carrierId,
          e.getMessage());
      return null;
    } finally {
      deleteQuietly(slice);
    }
  }

  static String bankStatementKey(String carrierHash, int from, int to, int ordinal) {
    return (carrierHash == null ? "nohash" : carrierHash)
        + "|bankStatement|pages="
        + from
        + "-"
        + to
        + "|#"
        + ordinal;
  }

  private static String bankStatementSliceName(String baseName, int from, int to, int ordinal) {
    String pages = from == to ? ("p" + from) : ("p" + from + "-" + to);
    return baseName + "-bankstatement-" + pages + "-" + ordinal + ".pdf";
  }

  // ---------------------------------------------------------------------------
  // Child creation
  // ---------------------------------------------------------------------------

  /**
   * Slice the segment's PDF outside the DB transaction, then create the child in its own tx.
   * Returns the child id, or null when the slice could not be produced.
   */
  private Long createChildForSegment(
      Long carrierId,
      Long companyId,
      Long userId,
      String key,
      DocSegment seg,
      Path pdfPath,
      String baseName,
      int ordinal,
      boolean wholeDocument) {
    boolean forceReview = seg.type == BgOcrPdfSegmenter.Type.REVIEW;
    if (wholeDocument) {
      // The "slice" would be this exact document. Share the carrier's MetaFile instead of writing a
      // second blob — no PDFBox pass, no upload, no bytes. Everything else about the child is
      // unchanged, including its own row, extraction key, segment type and cached OCR result.
      Long shared =
          upsertSharedSplitChild(
              carrierId,
              companyId,
              userId,
              key,
              segmentTypeCode(seg, forceReview),
              sliceName(baseName, seg, ordinal),
              OcrResultJson.toJson(seg.mappedResult));
      if (shared != null) {
        return shared;
      }
      // There is nothing to share (the carrier lost its file between split() checking it and now).
      // Falling through to the slice path rather than returning null: this is the ONLY planned
      // child of this carrier, and dropping it would leave a finished carrier with no children,
      // which the rollup reads as FAILED. Slicing re-reads the same pdfPath the caller already
      // resolved, so it either succeeds or fails loudly for the real reason.
      LOG.warn(
          "Carrier {} has no file to share for segment {}; slicing it instead.",
          carrierId,
          ordinal);
    }
    Path slice = null;
    try {
      if (seg.isCrop()) {
        slice =
            pdfSplit.cropRegion(pdfPath, seg.fromPage, seg.cropBbox, seg.cropPageW, seg.cropPageH);
        if (slice == null) {
          // Region unreliable → attach the whole page and mark the record To review (never cut
          // content incorrectly).
          slice = pdfSplit.slicePages(pdfPath, seg.fromPage, seg.fromPage);
          forceReview = true;
        }
      } else {
        slice = pdfSplit.slicePages(pdfPath, seg.fromPage, seg.toPage);
      }
      if (slice == null || !Files.exists(slice)) {
        return null;
      }
      String segmentType = segmentTypeCode(seg, forceReview);
      String cachedJson = OcrResultJson.toJson(seg.mappedResult);
      String fileName = sliceName(baseName, seg, ordinal);
      String hash = AiOcrFileHash.sha256(slice);
      return upsertSplitChild(
          carrierId, companyId, userId, key, segmentType, fileName, hash, cachedJson, slice);
    } catch (Exception e) {
      LOG.warn("Could not slice segment {} of carrier {}: {}", ordinal, carrierId, e.getMessage());
      return null;
    } finally {
      deleteQuietly(slice);
    }
  }

  @Transactional
  public Long upsertSplitChild(
      Long carrierId,
      Long companyId,
      Long userId,
      String key,
      String segmentType,
      String fileName,
      String hash,
      String cachedJson,
      Path slice)
      throws Exception {
    Long existing = findChildId(carrierId, key);
    if (existing != null) {
      return existing; // idempotent — created on a previous (crashed) run
    }
    MetaFile metaFile;
    try (java.io.InputStream in = Files.newInputStream(slice)) {
      metaFile = Beans.get(MetaFiles.class).upload(in, fileName);
    }
    AiOcrUpload child = new AiOcrUpload();
    child.setImportArchive(uploadRepo.find(carrierId));
    child.setExtractionKey(key);
    child.setSegmentType(segmentType);
    child.setOriginalFile(metaFile);
    child.setFileName(fileName);
    child.setFileHash(hash);
    // Deserialized in process() so the child is routed from the single Azure call (OCR-once).
    child.setCachedOcrJson(cachedJson);
    child.setStatusSelect(AiOcrUploadStatus.QUEUED);
    if (companyId != null) {
      child.setCompany(
          Beans.get(com.axelor.apps.base.db.repo.CompanyRepository.class).find(companyId));
    }
    if (userId != null) {
      child.setUploadedBy(Beans.get(com.axelor.auth.db.repo.UserRepository.class).find(userId));
    }
    uploadRepo.save(child);
    return child.getId();
  }

  /**
   * The {@link #upsertSplitChild} twin for a record that IS the whole carrier document: same row,
   * same key, same segment type, same cached OCR result — but it points at the carrier's existing
   * {@code originalFile} rather than storing a re-serialised copy of it.
   *
   * <p><b>Why this is safe, and why it needs no change to the delete path.</b> {@code originalFile}
   * is a plain foreign key; it creates no {@code MetaAttachment}, so a second FK holder is exactly
   * the sharing {@link com.axelor.apps.aiocr.service.storage.OcrFileSharingService} documents as
   * supported ("one DMS consumer plus any number of FK holders"). The generated Invoice then shares
   * the same file too, because {@code BgOcrIntakeService.attachDocument} attaches the child's own
   * {@code originalFile} and is the single DMS consumer. Deletion stays correct by construction:
   * {@code OcrUploadFileCleanupService.isUnreferenced} asks {@code OcrMetaFileReferenceScanner},
   * which enumerates MetaFile relations from the JPA metamodel rather than a hand-written list — so
   * the carrier's own reference is counted automatically, deleting only the leaf leaves the blob in
   * place, and deleting the whole tree drops the last reference and quarantines it (restored on
   * rollback, removed after commit).
   *
   * <p>The child keeps the carrier's {@code fileHash} because its bytes ARE the carrier's bytes —
   * blanking it to dodge the collision would corrupt the dedup marker instead of resolving it. Two
   * live rows then legitimately hold one hash, so the byte-identical guard must CHOOSE between them
   * rather than take whichever the database returns first: {@code
   * BgOcrIntakeService.pickDuplicateSource} ranks the document leaf above the structural carrier,
   * so a later direct upload of the same PDF resolves to the leaf and inherits its Invoice/Move.
   * The boolean guards ({@code AiOcrArchiveExtractService.isDuplicate}, the folder monitor's) ask
   * {@code count() > 0} and are unaffected by the multiplicity.
   *
   * <p>Returns {@code null} when the carrier has no file to share; the caller then falls back to
   * slicing rather than losing the child.
   */
  @Transactional
  public Long upsertSharedSplitChild(
      Long carrierId,
      Long companyId,
      Long userId,
      String key,
      String segmentType,
      String fileName,
      String cachedJson) {
    Long existing = findChildId(carrierId, key);
    if (existing != null) {
      return existing; // idempotent — created on a previous (crashed) run
    }
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null || carrier.getOriginalFile() == null) {
      return null;
    }
    AiOcrUpload child = new AiOcrUpload();
    child.setImportArchive(carrier);
    child.setExtractionKey(key);
    child.setSegmentType(segmentType);
    child.setOriginalFile(carrier.getOriginalFile()); // SHARED — no second blob is written
    child.setFileName(fileName);
    child.setFileHash(carrier.getFileHash());
    // Deserialized in process() so the child is routed from the single Azure call (OCR-once).
    child.setCachedOcrJson(cachedJson);
    child.setStatusSelect(AiOcrUploadStatus.QUEUED);
    if (companyId != null) {
      child.setCompany(
          Beans.get(com.axelor.apps.base.db.repo.CompanyRepository.class).find(companyId));
    }
    if (userId != null) {
      child.setUploadedBy(Beans.get(com.axelor.auth.db.repo.UserRepository.class).find(userId));
    }
    uploadRepo.save(child);
    return child.getId();
  }

  /**
   * Add a content-matched stock-receipt slice to an invoice child's {@code stockReceiptFileSet}.
   * One invoice may accumulate several distinct receipts; each is a separate PDF slice with a
   * deterministic name, so re-running the split never duplicates an attachment (idempotent by
   * name). Creates NO record of its own.
   */
  private void addStockReceipt(
      Long invoiceChildId, Path pdfPath, DocSegment seg, String baseName, int ord) {
    String fileName = stockReceiptName(baseName, seg, ord);
    if (invoiceChildId == null || childHasReceiptFileNamed(invoiceChildId, fileName)) {
      return; // idempotent: this receipt is already attached to this invoice
    }
    Path slice = null;
    try {
      slice = pdfSplit.slicePages(pdfPath, seg.fromPage, seg.toPage);
      if (slice == null || !Files.exists(slice)) {
        return;
      }
      try (java.io.InputStream in = Files.newInputStream(slice)) {
        MetaFile metaFile = Beans.get(MetaFiles.class).upload(in, fileName);
        addStockReceiptFile(invoiceChildId, metaFile.getId(), fileName);
      }
    } catch (Exception e) {
      LOG.warn(
          "Could not attach stock receipt to invoice child {}: {}", invoiceChildId, e.getMessage());
    } finally {
      deleteQuietly(slice);
    }
  }

  /**
   * Attach a POS/fiscal receipt slice to the preceding invoice child (adjacency — a card slip
   * belongs to its fiscal invoice). Single POS per invoice, idempotent.
   */
  private void attachPosReceipt(
      Long invoiceChildId, Path pdfPath, DocSegment seg, String baseName, int ord) {
    if (invoiceChildId == null) {
      return;
    }
    AiOcrUpload invoiceChild = uploadRepo.find(invoiceChildId);
    if (invoiceChild == null || invoiceChild.getPosReceiptFile() != null) {
      return; // idempotent
    }
    Path slice = null;
    try {
      slice = pdfSplit.slicePages(pdfPath, seg.fromPage, seg.toPage);
      if (slice == null || !Files.exists(slice)) {
        return;
      }
      String fileName = baseName + "-pos-receipt-p" + seg.fromPage + ".pdf";
      try (java.io.InputStream in = Files.newInputStream(slice)) {
        MetaFile metaFile = Beans.get(MetaFiles.class).upload(in, fileName);
        setPosReceipt(invoiceChildId, metaFile.getId());
      }
    } catch (Exception e) {
      LOG.warn(
          "Could not attach POS receipt to invoice child {}: {}", invoiceChildId, e.getMessage());
    } finally {
      deleteQuietly(slice);
    }
  }

  /**
   * Preserve an ambiguous / unmatched stock receipt as a separate DMS attachment on the CARRIER
   * (which also keeps the full PDF), so nothing is lost and it is never force-attached to a wrong
   * invoice. Idempotent by attachment name.
   */
  private void preserveUnmatchedReceipt(
      Long carrierId, Path pdfPath, DocSegment seg, String baseName, int ord) {
    String fileName = stockReceiptName(baseName, seg, ord);
    Path slice = null;
    try {
      slice = pdfSplit.slicePages(pdfPath, seg.fromPage, seg.toPage);
      if (slice == null || !Files.exists(slice)) {
        return;
      }
      try (java.io.InputStream in = Files.newInputStream(slice)) {
        MetaFile metaFile = Beans.get(MetaFiles.class).upload(in, fileName);
        attachUnmatchedToCarrier(carrierId, metaFile.getId(), fileName);
      }
    } catch (Exception e) {
      LOG.warn(
          "Could not preserve unmatched stock receipt on carrier {}: {}",
          carrierId,
          e.getMessage());
    } finally {
      deleteQuietly(slice);
    }
  }

  private static String stockReceiptName(String baseName, DocSegment seg, int ord) {
    String pages =
        seg.fromPage == seg.toPage ? ("p" + seg.fromPage) : ("p" + seg.fromPage + "-" + seg.toPage);
    return baseName + "-stock-receipt-" + pages + "-" + ord + ".pdf";
  }

  /** True when the invoice child already carries a stock-receipt attachment of this name. */
  protected boolean childHasReceiptFileNamed(Long invoiceChildId, String fileName) {
    AiOcrUpload child = uploadRepo.find(invoiceChildId);
    if (child == null || child.getStockReceiptFileSet() == null) {
      return false;
    }
    for (MetaFile mf : child.getStockReceiptFileSet()) {
      if (mf != null && fileName.equals(mf.getFileName())) {
        return true;
      }
    }
    return false;
  }

  @Transactional
  public void addStockReceiptFile(Long invoiceChildId, Long metaFileId, String fileName) {
    AiOcrUpload child = uploadRepo.find(invoiceChildId);
    if (child == null || metaFileId == null) {
      return;
    }
    if (childHasReceiptFileNamed(invoiceChildId, fileName)) {
      return; // idempotent
    }
    MetaFile mf = Beans.get(com.axelor.meta.db.repo.MetaFileRepository.class).find(metaFileId);
    if (mf != null) {
      child.addStockReceiptFileSetItem(mf);
      uploadRepo.save(child);
    }
  }

  @Transactional
  public void setPosReceipt(Long invoiceChildId, Long metaFileId) {
    AiOcrUpload child = uploadRepo.find(invoiceChildId);
    if (child == null || metaFileId == null || child.getPosReceiptFile() != null) {
      return;
    }
    MetaFile mf = Beans.get(com.axelor.meta.db.repo.MetaFileRepository.class).find(metaFileId);
    if (mf != null) {
      child.setPosReceiptFile(mf);
      uploadRepo.save(child);
    }
  }

  /** Attach an unmatched receipt slice to the carrier as a DMS attachment (idempotent by name). */
  @Transactional
  public void attachUnmatchedToCarrier(Long carrierId, Long metaFileId, String fileName) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null || metaFileId == null) {
      return;
    }
    long existing =
        com.axelor
            .db
            .JPA
            .em()
            .createQuery(
                "SELECT count(d) FROM DMSFile d WHERE d.relatedModel = :n AND d.relatedId = :id"
                    + " AND d.fileName = :fn",
                Long.class)
            .setParameter("n", AiOcrUpload.class.getName())
            .setParameter("id", carrierId)
            .setParameter("fn", fileName)
            .getSingleResult();
    if (existing > 0) {
      return; // idempotent
    }
    MetaFile mf = Beans.get(com.axelor.meta.db.repo.MetaFileRepository.class).find(metaFileId);
    if (mf != null) {
      Beans.get(MetaFiles.class).attach(mf, fileName, carrier);
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Transactional
  public void beginSplit(Long carrierId) {
    beginSplit(carrierId, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
  }

  @Transactional
  public void beginSplit(Long carrierId, String carrierKind) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null) {
      return;
    }
    if (carrier.getExtractionStartedOn() == null) {
      carrier.setExtractionStartedOn(LocalDateTime.now());
    }
    if ((carrier.getFileName() == null || carrier.getFileName().trim().isEmpty())
        && carrier.getOriginalFile() != null) {
      carrier.setFileName(carrier.getOriginalFile().getFileName());
    }
    if (carrier.getExtractionAttemptId() == null) {
      carrier.setExtractionAttemptId(UUID.randomUUID().toString());
    }
    carrier.setCarrierKind(carrierKind);
    carrier.setExtractionFinishedOn(null);
    carrier.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
    uploadRepo.save(carrier);
  }

  @Transactional
  public void finishCarrier(Long carrierId, String reason) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier != null) {
      carrier.setExtractionFinishedOn(LocalDateTime.now());
      if (reason != null && !reason.trim().isEmpty()) {
        carrier.setExtractionErrors(reason.trim());
      }
      uploadRepo.save(carrier);
    }
    carrierService.recompute(carrierId);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  protected Long findChildId(Long carrierId, String key) {
    AiOcrUpload child =
        uploadRepo
            .all()
            .filter("self.importArchive.id = :carrierId AND self.extractionKey = :key")
            .bind("carrierId", carrierId)
            .bind("key", key)
            .fetchOne();
    return child != null ? child.getId() : null;
  }

  private static String segmentTypeCode(DocSegment seg, boolean forceReview) {
    if (forceReview || seg.type == BgOcrPdfSegmenter.Type.REVIEW) {
      return AiOcrUploadStatus.SEGMENT_TYPE_REVIEW;
    }
    if (seg.type == BgOcrPdfSegmenter.Type.MOVE) {
      return AiOcrUploadStatus.SEGMENT_TYPE_MOVE;
    }
    return AiOcrUploadStatus.SEGMENT_TYPE_INVOICE;
  }

  /**
   * True when this plan's single record segment IS the whole source PDF — so slicing it would only
   * re-serialise the carrier's own file into a second blob of identical content.
   *
   * <p>Every condition is a way the slice could differ from the source, and all of them must be
   * ruled out. Deliberately strict: when in doubt, slice (the cost is one duplicated file), because
   * the opposite mistake shares a file that should have excluded something.
   *
   * <ul>
   *   <li><b>exactly one segment in the whole plan</b> — not merely one <i>record</i> segment. A
   *       stock receipt, a POS receipt or a blank page is a segment too, and its presence means the
   *       PDF holds evidence the invoice slice must not carry (a receipt becomes its own attachment
   *       and the carrier keeps the full document);
   *   <li><b>it is a record segment</b> — INVOICE / MOVE / REVIEW, the kinds that get a child;
   *   <li><b>no crop</b> — a same-page crop is by definition a part of a page;
   *   <li><b>it starts at page 1 and runs to the last page</b> — a duplicate physical copy that was
   *       dropped, or any page the boundary walk excluded, leaves the range short at one end;
   *   <li><b>Azure saw every page of the real PDF</b> ({@code plan.pageCount == actualPageCount}).
   *       Without this the range could span everything Azure reported while the file itself holds
   *       more, and "covers the whole document" would be a claim about the OCR result rather than
   *       about the bytes. {@code actualPageCount} comes from PDFBox reading the file.
   * </ul>
   *
   * <p>Pure and static so the rule is unit-testable without a PDF, Guice or JPA.
   */
  static boolean isWholeDocumentSingleRecord(SegmentPlan plan, Integer actualPageCount) {
    if (plan == null || plan.segments == null || plan.segments.size() != 1) {
      return false;
    }
    if (actualPageCount == null || actualPageCount < 1 || plan.pageCount != actualPageCount) {
      return false;
    }
    DocSegment seg = plan.segments.get(0);
    if (seg == null || seg.isCrop()) {
      return false;
    }
    if (seg.type != BgOcrPdfSegmenter.Type.INVOICE
        && seg.type != BgOcrPdfSegmenter.Type.MOVE
        && seg.type != BgOcrPdfSegmenter.Type.REVIEW) {
      return false;
    }
    return seg.fromPage <= 1 && seg.toPage >= actualPageCount;
  }

  /** Deterministic identity from the ORIGINAL file, never the (non-stable) slice bytes (A3b). */
  static String extractionKey(String carrierHash, DocSegment seg, int ordinal) {
    String crop = seg.isCrop() ? fmtBbox(seg.cropBbox) : "none";
    return (carrierHash == null ? "nohash" : carrierHash)
        + "|"
        + seg.type
        + "|pages="
        + seg.fromPage
        + "-"
        + seg.toPage
        + "|crop="
        + crop
        + "|#"
        + ordinal;
  }

  private static String fmtBbox(double[] b) {
    if (b == null || b.length < 4) {
      return "none";
    }
    return String.format(Locale.ROOT, "%.2f_%.2f_%.2f_%.2f", b[0], b[1], b[2], b[3]);
  }

  private String carrierHash(AiOcrUpload carrier, Path pdfPath) {
    if (carrier.getFileHash() != null && !carrier.getFileHash().isEmpty()) {
      return carrier.getFileHash();
    }
    try {
      return AiOcrFileHash.sha256(pdfPath);
    } catch (Exception e) {
      return "carrier" + carrier.getId();
    }
  }

  private static String sliceName(String baseName, DocSegment seg, int ordinal) {
    String type = seg.type.name().toLowerCase(Locale.ROOT);
    String pages =
        seg.fromPage == seg.toPage ? ("p" + seg.fromPage) : ("p" + seg.fromPage + "-" + seg.toPage);
    return baseName + "-" + type + "-" + pages + "-" + ordinal + ".pdf";
  }

  private static String leaf(String fileName) {
    if (fileName == null || fileName.trim().isEmpty()) {
      return "document";
    }
    String n = fileName.trim();
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  private static void deleteQuietly(Path p) {
    if (p != null) {
      try {
        Files.deleteIfExists(p);
      } catch (Exception ignore) {
        // best-effort cleanup
      }
    }
  }
}
