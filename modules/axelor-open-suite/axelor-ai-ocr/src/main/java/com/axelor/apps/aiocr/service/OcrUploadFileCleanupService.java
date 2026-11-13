package com.axelor.apps.aiocr.service;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.bankstatement.OcrBankStatementCreator;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner;
import com.axelor.apps.base.AxelorException;
import com.axelor.db.JPA;
import com.axelor.dms.db.DMSFile;
import com.axelor.dms.db.repo.DMSFileRepository;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaAttachment;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaAttachmentRepository;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.persistence.PersistenceException;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OCR delete rules shared by the two entry points:
 *
 * <ul>
 *   <li><b>Delete from Suppl. Invoices</b> ({@code OcrInvoiceRepository.remove}) — affects ONLY
 *       that module: the invoice is removed and any linked {@link AiOcrUpload} is merely
 *       <i>unlinked</i> ({@code invoice = null}), so the FK never blocks the delete and the upload
 *       survives in Upload Invoices.
 *   <li><b>Delete from Upload Invoices</b> ({@code AiOcrUploadManagementRepository.remove}) — FULL
 *       deletion: the upload (and its ZIP-carrier children), their unreferenced {@link MetaFile}
 *       rows + physical blobs (rollback-safe quarantine), AND the produced supplier invoice.
 * </ul>
 *
 * <p><b>Tax-compliance hard stop:</b> an invoice that has ever been VENTILATED can never be deleted
 * from either path — {@link #wasVentilated(Invoice)} blocks it (a ventilated invoice carries posted
 * accounting entries; deleting it, even after a later cancel, would be fraud).
 */
@Singleton
public class OcrUploadFileCleanupService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrUploadFileCleanupService.class);

  protected final AiOcrUploadRepository uploadRepo;
  protected final InvoiceRepository invoiceRepo;
  protected final InvoiceService invoiceService;
  protected final MoveRepository moveRepo;
  protected final MetaFileRepository metaFileRepo;
  protected final MetaAttachmentRepository metaAttachmentRepo;
  protected final DMSFileRepository dmsFileRepo;
  protected final OcrFileQuarantineService quarantineService;
  protected final OcrMetaFileReferenceScanner referenceScanner;

  @Inject
  public OcrUploadFileCleanupService(
      AiOcrUploadRepository uploadRepo,
      InvoiceRepository invoiceRepo,
      InvoiceService invoiceService,
      MoveRepository moveRepo,
      MetaFileRepository metaFileRepo,
      MetaAttachmentRepository metaAttachmentRepo,
      DMSFileRepository dmsFileRepo,
      OcrFileQuarantineService quarantineService,
      OcrMetaFileReferenceScanner referenceScanner) {
    this.uploadRepo = uploadRepo;
    this.invoiceRepo = invoiceRepo;
    this.invoiceService = invoiceService;
    this.moveRepo = moveRepo;
    this.metaFileRepo = metaFileRepo;
    this.metaAttachmentRepo = metaAttachmentRepo;
    this.dmsFileRepo = dmsFileRepo;
    this.quarantineService = quarantineService;
    this.referenceScanner = referenceScanner;
  }

  // ---------------------------------------------------------------------------------------------
  // Pure helpers (unit-tested without a DB)
  // ---------------------------------------------------------------------------------------------

  /**
   * True when the invoice has EVER been ventilated (posted) — the tax-compliance hard stop. The
   * stock cancel workflow only flips {@code statusSelect} to CANCELED and clears NOTHING else, so a
   * cancel-after-ventilation invoice keeps its ventilation markers. We therefore consider ALL
   * surviving evidence, not a single field:
   *
   * <ul>
   *   <li>current status = VENTILATED;
   *   <li>{@code ventilatedDateTime} / {@code ventilatedByUser} (set only at ventilation, never
   *       cleared by cancel; cleared only on copy, which also resets the copy to DRAFT);
   *   <li>an accounting {@code move} (the posted entry is created only at ventilation).
   * </ul>
   */
  public static boolean wasVentilated(Invoice inv) {
    return inv != null
        && (Objects.equals(InvoiceRepository.STATUS_VENTILATED, inv.getStatusSelect())
            || inv.getVentilatedDateTime() != null
            || inv.getVentilatedByUser() != null
            || inv.getMove() != null);
  }

  /**
   * The full set of uploads deleted together: the roots plus every {@code importArchive}
   * descendant, traversed cycle-safely. {@code childrenOf} is injected so the traversal is
   * unit-testable.
   */
  public static Set<AiOcrUpload> collectDeletionSet(
      Collection<AiOcrUpload> roots,
      java.util.function.Function<AiOcrUpload, List<AiOcrUpload>> childrenOf) {
    Set<AiOcrUpload> result = new LinkedHashSet<>();
    Set<Long> seenIds = new HashSet<>();
    Set<AiOcrUpload> seenObjs = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<AiOcrUpload> stack = new ArrayDeque<>();
    if (roots != null) {
      for (AiOcrUpload r : roots) {
        if (r != null) {
          stack.push(r);
        }
      }
    }
    while (!stack.isEmpty()) {
      AiOcrUpload u = stack.pop();
      if (!markSeen(u, seenIds, seenObjs)) {
        continue; // already visited — breaks any carrier/child cycle
      }
      result.add(u);
      List<AiOcrUpload> kids = childrenOf.apply(u);
      if (kids != null) {
        for (AiOcrUpload k : kids) {
          if (k != null) {
            stack.push(k);
          }
        }
      }
    }
    return result;
  }

  private static boolean markSeen(AiOcrUpload u, Set<Long> ids, Set<AiOcrUpload> objs) {
    Long id = u.getId();
    return id != null ? ids.add(id) : objs.add(u);
  }

  /**
   * Distinct MetaFiles (originalFile + archiveFile + a split child's stock-receipt set + POS
   * receipt + the deprecated singular stock-receipt field) referenced by the uploads, deduped by
   * id.
   *
   * <p>{@code stockReceiptFile} is the legacy field superseded by {@code stockReceiptFileSet}. It
   * is never populated by current code, so including it changes nothing today — it is here so the
   * candidate set can never silently miss a reference that the (now mapped) column can hold.
   */
  public static Set<MetaFile> collectCandidateFiles(Collection<AiOcrUpload> uploads) {
    Map<Long, MetaFile> byId = new LinkedHashMap<>();
    Set<MetaFile> noId = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<MetaFile> result = new LinkedHashSet<>();
    if (uploads != null) {
      for (AiOcrUpload u : uploads) {
        if (u == null) {
          continue;
        }
        addCandidate(u.getOriginalFile(), byId, noId, result);
        addCandidate(u.getArchiveFile(), byId, noId, result);
        if (u.getStockReceiptFileSet() != null) {
          for (MetaFile receipt : u.getStockReceiptFileSet()) {
            addCandidate(receipt, byId, noId, result);
          }
        }
        addCandidate(u.getPosReceiptFile(), byId, noId, result);
        addCandidate(u.getStockReceiptFile(), byId, noId, result); // deprecated, never populated
      }
    }
    return result;
  }

  private static void addCandidate(
      MetaFile mf, Map<Long, MetaFile> byId, Set<MetaFile> noId, Set<MetaFile> result) {
    if (mf == null) {
      return;
    }
    Long id = mf.getId();
    if (id != null) {
      if (byId.putIfAbsent(id, mf) == null) {
        result.add(mf);
      }
    } else if (noId.add(mf)) {
      result.add(mf);
    }
  }

  /**
   * True when an accounting move must not be removed. Mirrors the stock {@code
   * MoveManagementRepository.remove} guard: only NEW and SIMULATED are unposted.
   */
  public static boolean isMoveProtected(Integer moveStatusSelect) {
    return !Objects.equals(MoveRepository.STATUS_NEW, moveStatusSelect)
        && !Objects.equals(MoveRepository.STATUS_SIMULATED, moveStatusSelect);
  }

  /**
   * Why ONE upload's own documents forbid deleting it, or {@code null} when they do not.
   *
   * <p>Protection is a property of a single document, never of a family: a ventilated invoice or a
   * posted move protects <em>its own</em> upload and nothing else. A sibling in the same archive is
   * a different document with a different legal status.
   */
  public static String protectionReason(
      boolean ventilatedInvoice, String invoiceLabel, boolean postedMove, String moveLabel) {
    if (ventilatedInvoice) {
      return "its invoice " + describe(invoiceLabel) + " has been ventilated (tax compliance)";
    }
    if (postedMove) {
      return "its accounting move " + describe(moveLabel) + " is posted (not new/simulated)";
    }
    return null;
  }

  /**
   * The refusal text for a delete that cannot proceed, naming every protected document.
   *
   * @param rootLabel the upload the user asked to delete
   * @param rootProtected whether that upload is itself protected (rather than a descendant)
   * @param protectedRefs one entry per protected document, most useful first
   */
  public static String refusalMessage(
      String rootLabel, boolean rootProtected, List<String> protectedRefs) {
    List<String> refs = protectedRefs == null ? Collections.emptyList() : protectedRefs;
    if (rootProtected) {
      return "Upload "
          + describe(rootLabel)
          + " cannot be deleted: "
          + (refs.isEmpty() ? "it is protected" : refs.get(0))
          + ". Nothing was deleted.";
    }
    // The carrier case. Say plainly that the requested record still exists — a partial delete
    // reported as a success would be a lie, and Repository.remove has no channel for "partly done".
    return "\""
        + describe(rootLabel)
        + "\" was NOT deleted: "
        + refs.size()
        + " of its document(s) must be kept — "
        + String.join("; ", refs)
        + ". Nothing was deleted, including the other documents. Delete the unprotected documents"
        + " individually from the list, then delete this record again.";
  }

  private static String describe(String label) {
    return (label == null || label.trim().isEmpty()) ? "(unnamed)" : label;
  }

  // ---------------------------------------------------------------------------------------------
  // Suppl. Invoices path — only this module is affected
  // ---------------------------------------------------------------------------------------------

  /** Block the delete (both paths) if any of the invoices has ever been ventilated. */
  public void assertNotVentilated(Collection<Invoice> invoices) {
    if (invoices == null) {
      return;
    }
    for (Invoice inv : invoices) {
      if (wasVentilated(inv)) {
        throw new PersistenceException(
            "A ventilated invoice cannot be deleted (tax compliance): " + label(inv));
      }
    }
  }

  /**
   * Refuse the automatic pre-delete cancellation when the invoice carries any accounting dependency
   * that makes silent cancellation unsafe. (Ventilation is already blocked separately.) Reports the
   * exact blocking dependencies instead of force-deleting. A plain draft/validated OCR invoice has
   * none of these, so it passes.
   */
  public void assertSafeToAutoCancel(Invoice inv) {
    if (inv == null) {
      return;
    }
    List<String> blockers = new java.util.ArrayList<>();
    if (inv.getMove() != null) {
      blockers.add("a posted accounting move");
    }
    if (inv.getOldMove() != null) {
      blockers.add("a previous accounting move");
    }
    if (inv.getPaymentMove() != null) {
      blockers.add("a payment move");
    }
    if (inv.getPaymentSchedule() != null) {
      blockers.add("a payment schedule");
    }
    if (inv.getSubrogationRelease() != null) {
      blockers.add("a subrogation release");
    }
    if (inv.getAmountPaid() != null && inv.getAmountPaid().compareTo(BigDecimal.ZERO) > 0) {
      blockers.add("a paid amount");
    }
    if (hasValidatedPayment(inv)) {
      blockers.add("registered payment(s)");
    }
    if (!blockers.isEmpty()) {
      throw new PersistenceException(
          "Cannot delete the upload: its invoice "
              + label(inv)
              + " has "
              + String.join(", ", blockers)
              + ". Resolve these in Accounting first — the invoice was not deleted.");
    }
  }

  private static boolean hasValidatedPayment(Invoice inv) {
    if (inv.getInvoicePaymentList() == null) {
      return false;
    }
    for (InvoicePayment p : inv.getInvoicePaymentList()) {
      if (p != null
          && Objects.equals(InvoicePaymentRepository.STATUS_VALIDATED, p.getStatusSelect())) {
        return true;
      }
    }
    return false;
  }

  private static String label(Invoice inv) {
    if (inv == null) {
      return "(null)";
    }
    return inv.getInvoiceId() != null ? inv.getInvoiceId() : ("id=" + inv.getId());
  }

  /**
   * Clear the {@code invoice} link on every upload pointing at this invoice, so deleting the
   * invoice does not touch the uploads (they stay in Upload Invoices) and the FK does not block the
   * delete. The upload is flagged {@code deletedFromSupplInvoices} so the client can reprocess the
   * original document into a fresh draft (instead of a misleading "completed").
   */
  public void unlinkUploads(Invoice invoice) {
    if (invoice == null) {
      return;
    }
    for (AiOcrUpload up :
        uploadRepo.all().filter("self.invoice = :inv").bind("inv", invoice).fetch()) {
      // Clear the FK either way so the invoice delete is not blocked. Only the CANONICAL upload
      // that
      // produced this invoice becomes reprocessable; an upload that was itself a DUPLICATE (it
      // linked
      // to this invoice without creating it) stays Duplicate and is NOT reprocessable — otherwise
      // reprocessing both would recreate two invoices.
      up.setInvoice(null);
      if (!AiOcrUploadStatus.DUPLICATE.equals(up.getStatusSelect())) {
        up.setStatusSelect(AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES);
      }
      uploadRepo.save(up);
    }
  }

  /**
   * Clear the {@code move} link on every upload pointing at this move, so deleting the move from
   * the Moves module does not touch the uploads (they stay in Upload Invoices, source file
   * preserved) and the FK does not block the delete. Flagged {@code deletedFromMoves} so the client
   * can reprocess the original document.
   */
  public void unlinkMoveUploads(Move move) {
    if (move == null) {
      return;
    }
    for (AiOcrUpload up : uploadRepo.all().filter("self.move = :m").bind("m", move).fetch()) {
      up.setMove(null);
      up.setStatusSelect(AiOcrUploadStatus.DELETED_FROM_MOVES);
      uploadRepo.save(up);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Upload Invoices path — full deletion
  // ---------------------------------------------------------------------------------------------

  /**
   * Fully delete an upload tree: block if a linked invoice was ventilated, then remove the upload +
   * carrier children (bottom-up), quarantine their unreferenced blobs, and delete the produced
   * invoices. {@code rowRemover} is the plain {@code super.remove} of the upload repository (so the
   * carrier cascade is done here explicitly, without re-entering this method).
   */
  public void fullyDeleteUploads(AiOcrUpload root, Consumer<AiOcrUpload> rowRemover) {
    if (root == null) {
      return;
    }
    Set<AiOcrUpload> tree = collectDeletionSet(List.of(root), this::children);

    // Resolve the documents this tree actually PRODUCED, FK-safely. A DUPLICATE upload only *links*
    // to an invoice it did not create (deleting it must never delete that invoice); and any link
    // may
    // be stale (the invoice/move was already deleted, leaving a dangling FK). We read the FK id
    // from
    // the proxy WITHOUT initializing it, and keep only ids that still exist — so no delete path
    // ever
    // loads a missing Invoice/Move (the source of the EntityNotFoundException).
    Set<Invoice> invoices = producedInvoices(tree);
    Set<Move> moves = producedMoves(tree);

    // Validate ALL safety guards BEFORE any destructive work, so a blocked delete rolls back clean.
    //
    // Protection is evaluated PER DOCUMENT and re-read from the database here (see
    // evaluateProtections). Deleting one child therefore weighs only that child's own invoice/move:
    // a ventilated sibling in the same archive is a different document and never blocks it.
    List<ProtectedUpload> protections = evaluateProtections(tree);
    if (!protections.isEmpty()) {
      boolean rootProtected =
          protections.stream()
              .anyMatch(p -> root.getId() != null && root.getId().equals(p.getUploadId()));
      List<String> refs = new ArrayList<>();
      // The requested record's own reason first — it is the answer to what the user asked.
      protections.stream()
          .filter(p -> root.getId() != null && root.getId().equals(p.getUploadId()))
          .forEach(p -> refs.add(p.getReason()));
      protections.stream()
          .filter(p -> root.getId() == null || !root.getId().equals(p.getUploadId()))
          .forEach(p -> refs.add(p.describeRef()));
      // Refuse rather than half-delete: this runs from Repository.remove, which is void, so the
      // caller (grid delete, REST, removeAll) can only render "deleted" or an error — there is no
      // channel for "partly done". Reporting success while the requested carrier still exists
      // would be a lie, so nothing is removed and every protected document is named.
      throw new PersistenceException(refusalMessage(root.getFileName(), rootProtected, refs));
    }

    // Nothing is protected → the original complete-tree deletion, unchanged. These guards stay as
    // the second line of defence (and remain the entry point used by the Suppl. Invoices path).
    assertNotVentilated(invoices); // tax-compliance hard stop
    for (Invoice inv : invoices) {
      assertSafeToAutoCancel(inv); // refuse silent cancel if accounting dependencies exist
    }
    assertMovesDeletable(moves); // only NEW/SIMULATED (unposted) moves may be removed

    Set<MetaFile> candidates = collectCandidateFiles(tree);
    List<QuarantinedFile> quarantined = new ArrayList<>();
    JPA.em()
        .unwrap(Session.class)
        .getTransaction()
        .registerSynchronization(
            new OcrFileTransactionSynchronization(quarantined, quarantineService));

    // Detach the exact DMS attachments this tree owns while the upload rows STILL reference the
    // files. Without this the attachments survive until AuditTracker removes them in
    // beforeCompletion — where registerSynchronization is no longer accepted — and the platform
    // would physically delete the (by then unreferenced) shared blob inside the transaction, so a
    // commit failure would restore the rows but not the bytes. Doing it here, and quarantining at
    // the end of this method instead, keeps the whole delete rollback-safe.
    detachTreeAttachments(tree, invoices, moves, candidates);

    // Detach every generated-document reference from the uploads BEFORE removing the rows: a bulk
    // update nulls the FK columns directly (no entity load, so no audit listener can dereference a
    // missing Invoice/Move, and the FK can never block the row delete). Refresh the managed copies
    // so
    // the in-memory state used by the delete/audit listeners matches the nulled DB state.
    detachDocumentRefs(tree);

    // Delete any Bank Statements this tree produced (bg-localization backend) BEFORE removing the
    // uploads — a produced statement holds a FK back to its upload. Goes through the extension
    // point
    // because this module cannot reference the BankStatement type. deleteForUpload clears the link
    // first, so it never flips the (about-to-be-deleted) upload's status.
    deleteProducedBankStatements(tree);

    removePostOrder(root, rowRemover, new HashSet<>()); // children (referencing) before parents
    JPA.flush();

    for (Invoice inv : invoices) {
      detachUploadsFromInvoice(inv); // unlink any OTHER upload still pointing here (FK safety)
      autoCancelIfSafe(inv); // stock cancel of a draft/validated OCR invoice (guards re-checked)
      invoiceRepo.remove(inv); // stock guard: only CANCELED is removable
    }
    for (Move move : moves) {
      moveRepo.remove(move); // stock guard: only NEW/SIMULATED movable, checked before remove
    }

    // Quarantine LAST, once every reference is gone and flushed: the upload rows, the generated
    // invoices/moves and (above) their exact attachments. Only now can the reference scan prove a
    // candidate is unreferenced. Still inside the transaction body, so the synchronization
    // registered above is honoured: the blob is moved aside, then permanently deleted on commit or
    // restored on rollback. Nothing is ever unlinked physically inside the transaction.
    JPA.flush();
    quarantineUnreferenced(candidates, quarantined);

    LOG.info(
        "OCR upload delete: removed {} upload(s), {} candidate file(s), {} invoice(s), {} move(s).",
        tree.size(),
        candidates.size(),
        invoices.size(),
        moves.size());
  }

  /** Ids per query, keeping the JDBC parameter lists well inside any driver limit. */
  protected static final int DETACH_CHUNK = 500;

  /**
   * Remove the DMS attachments and {@link MetaAttachment} rows that <b>this tree's own records</b>
   * hold on <b>this tree's own candidate files</b> — nothing else.
   *
   * <p>Owners are strictly the records already scheduled for deletion: the uploads in {@code tree}
   * plus the <b>produced</b> invoices and moves (see {@link #producedInvoices} / {@link
   * #producedMoves}). Deriving owners from every non-null {@code upload.invoice} would be wrong: a
   * {@code duplicate} upload only <i>links</i> an invoice it did not create, and detaching that
   * invoice's attachment would vandalise a document that is not being deleted. Because this uses
   * the very sets {@link #fullyDeleteUploads} deletes, detach and delete can never disagree. It
   * also runs only after the protection gate has passed, so a refused delete detaches nothing.
   *
   * <p>Matching is on the exact triple — {@code (metaFile, relatedModel/objectName,
   * relatedId/objectId)} — mirroring the precision of {@code DMSFileRepository.save}. Queries are
   * batched per owner model and chunked, so the cost is a handful of queries rather than two per
   * owner.
   *
   * <p>The DMSFile's {@code metaFile} is nulled on the managed instance before removal, which makes
   * {@code DMSFileRepository.remove} skip its own count-and-delete branch entirely (it reads {@code
   * entity.getMetaFile()} straight off the instance). {@code save} is deliberately NOT called in
   * between: it would run the platform's extra attachment bookkeeping — parent-folder creation, and
   * an owner-blind "remove old attachment if file is moved" that deletes any MetaAttachment for
   * that file regardless of who owns it.
   */
  protected void detachTreeAttachments(
      Collection<AiOcrUpload> tree,
      Collection<Invoice> invoices,
      Collection<Move> moves,
      Set<MetaFile> candidates) {
    List<Long> fileIds = persistedIds(candidates, MetaFile::getId);
    if (fileIds.isEmpty()) {
      return;
    }
    Map<String, List<Long>> ownersByModel = new LinkedHashMap<>();
    putOwners(ownersByModel, AiOcrUpload.class, persistedIds(tree, AiOcrUpload::getId));
    putOwners(ownersByModel, Invoice.class, persistedIds(invoices, Invoice::getId));
    putOwners(ownersByModel, Move.class, persistedIds(moves, Move::getId));

    int attachments = 0;
    int dmsFiles = 0;
    for (Map.Entry<String, List<Long>> owner : ownersByModel.entrySet()) {
      for (List<Long> files : chunks(fileIds)) {
        for (List<Long> ids : chunks(owner.getValue())) {
          for (MetaAttachment a : findAttachments(owner.getKey(), ids, files)) {
            metaAttachmentRepo.remove(a);
            attachments++;
          }
          for (DMSFile d : findDmsFiles(owner.getKey(), ids, files)) {
            d.setMetaFile(null); // makes DMSFileRepository.remove skip its delete branch
            dmsFileRepo.remove(d);
            dmsFiles++;
          }
        }
      }
    }
    if (attachments > 0 || dmsFiles > 0) {
      LOG.info(
          "OCR upload delete: detached {} DMS file(s) and {} attachment(s) from this tree's own"
              + " records before removing them.",
          dmsFiles,
          attachments);
    }
  }

  protected List<MetaAttachment> findAttachments(
      String objectName, List<Long> objectIds, List<Long> fileIds) {
    return JPA.em()
        .createQuery(
            "SELECT a FROM MetaAttachment a WHERE a.metaFile.id IN :files"
                + " AND a.objectName = :name AND a.objectId IN :ids",
            MetaAttachment.class)
        .setParameter("files", fileIds)
        .setParameter("name", objectName)
        .setParameter("ids", objectIds)
        .getResultList();
  }

  protected List<DMSFile> findDmsFiles(
      String relatedModel, List<Long> relatedIds, List<Long> fileIds) {
    return JPA.em()
        .createQuery(
            "SELECT d FROM DMSFile d WHERE d.metaFile.id IN :files"
                + " AND d.relatedModel = :name AND d.relatedId IN :ids",
            DMSFile.class)
        .setParameter("files", fileIds)
        .setParameter("name", relatedModel)
        .setParameter("ids", relatedIds)
        .getResultList();
  }

  private static void putOwners(Map<String, List<Long>> target, Class<?> model, List<Long> ids) {
    if (!ids.isEmpty()) {
      target.put(model.getName(), ids);
    }
  }

  /** Distinct persisted ids of {@code items}; transient records cannot own an attachment. */
  private static <T> List<Long> persistedIds(
      Collection<T> items, java.util.function.Function<T, Long> idOf) {
    Set<Long> ids = new LinkedHashSet<>();
    if (items != null) {
      for (T item : items) {
        if (item == null) {
          continue;
        }
        Long id = idOf.apply(item); // proxy id — does NOT initialize the proxy
        if (id != null) {
          ids.add(id);
        }
      }
    }
    return new ArrayList<>(ids);
  }

  private static List<List<Long>> chunks(List<Long> ids) {
    List<List<Long>> out = new ArrayList<>();
    for (int from = 0; from < ids.size(); from += DETACH_CHUNK) {
      out.add(ids.subList(from, Math.min(from + DETACH_CHUNK, ids.size())));
    }
    return out;
  }

  /**
   * Delete every Bank Statement produced by any upload in {@code tree}, via the {@link
   * OcrBankStatementCreator} extension point (bg-localization). No-op when no backend is bound (the
   * lookup throws and is caught). Best-effort per upload so one failure never blocks the delete.
   */
  protected void deleteProducedBankStatements(Collection<AiOcrUpload> tree) {
    OcrBankStatementCreator creator;
    try {
      creator = Beans.get(OcrBankStatementCreator.class);
    } catch (RuntimeException e) {
      return; // no bank-statement backend on the classpath
    }
    for (AiOcrUpload u : tree) {
      try {
        creator.deleteForUpload(u);
      } catch (RuntimeException e) {
        LOG.warn(
            "Could not delete produced bank statement(s) for upload #{}: {}",
            u.getId(),
            e.getMessage());
      }
    }
  }

  /**
   * The accounting moves this tree PRODUCED and that still exist, deduped by id. A DUPLICATE upload
   * is skipped, exactly as {@link #producedInvoices} skips it: a duplicate only *links* a document
   * it did not create, so deleting the upload must never delete that document.
   *
   * <p>The symmetry is deliberate rather than decorative. Nothing enforces {@code status ==
   * DUPLICATE ⇒ move == null}: of the four places that set DUPLICATE, three never touch {@code
   * move} — {@code blockIfByteIdenticalDuplicate} even copies the prior upload's {@code invoice}
   * while leaving {@code move} untouched — and the two duplicate exits inside the invoice route
   * return <i>before</i> the {@code setMove(null)} further down that method. Today the combination
   * is unreachable only because all seven re-entry paths happen to clear {@code move} first (retry,
   * reprocess, ambiguous-classification, unmatched-supplier, …) and reprocess is gated on the
   * document having been deleted. That is an accident of seven independent call sites, not an
   * invariant: one new re-entry path that forgets a single {@code setMove(null)} would hand this
   * method a Move belonging to another document, and it would be deleted. Skipping DUPLICATE here
   * makes the guarantee structural.
   *
   * <p>The upload row still deletes cleanly: {@link #detachDocumentRefs} nulls the {@code move} FK
   * for every member of the tree regardless of status.
   *
   * <p>FK-safe: the move id is read from the proxy without initializing it, and a stale
   * (already-deleted) id is skipped, so a dangling {@code move} FK never triggers a load during
   * deletion.
   */
  protected Set<Move> producedMoves(Collection<AiOcrUpload> uploads) {
    Map<Long, Move> byId = new LinkedHashMap<>();
    Set<Move> result = new LinkedHashSet<>();
    for (AiOcrUpload u : uploads) {
      if (AiOcrUploadStatus.DUPLICATE.equals(u.getStatusSelect())) {
        continue; // duplicate: linked, not produced — leave the move alone
      }
      Move move = u.getMove();
      if (move == null) {
        continue;
      }
      Long id = move.getId(); // proxy id — does NOT initialize the proxy
      if (id == null) {
        result.add(move); // transient (never persisted)
      } else if (moveExists(id) && byId.putIfAbsent(id, move) == null) {
        result.add(move);
      }
    }
    return result;
  }

  /**
   * Refuse the delete up-front if any linked move is posted (not NEW/SIMULATED) — mirrors the stock
   * {@code MoveManagementRepository.remove} guard so accounting entries are never silently removed.
   * OCR-produced moves are created as NEW, so this only fires for a move a user has since posted.
   */
  public void assertMovesDeletable(Collection<Move> moves) {
    if (moves == null) {
      return;
    }
    for (Move move : moves) {
      Integer status = move.getStatusSelect();
      if (!Objects.equals(MoveRepository.STATUS_NEW, status)
          && !Objects.equals(MoveRepository.STATUS_SIMULATED, status)) {
        throw new PersistenceException(
            "Cannot delete the upload: its accounting move "
                + (move.getReference() != null ? move.getReference() : ("id=" + move.getId()))
                + " is posted (not new/simulated) and must not be removed.");
      }
    }
  }

  /**
   * The invoices this tree PRODUCED and that still exist, deduped by id. A DUPLICATE upload is
   * skipped — it only *links* an invoice it did not create, so deleting the upload must never
   * delete that invoice. FK-safe: the invoice id is read from the proxy without initializing it,
   * and a stale (already-deleted) id is skipped, so a dangling {@code invoice} FK never triggers a
   * load during deletion (this is what previously threw {@code EntityNotFoundException}).
   */
  protected Set<Invoice> producedInvoices(Collection<AiOcrUpload> uploads) {
    Map<Long, Invoice> byId = new LinkedHashMap<>();
    Set<Invoice> result = new LinkedHashSet<>();
    for (AiOcrUpload u : uploads) {
      if (AiOcrUploadStatus.DUPLICATE.equals(u.getStatusSelect())) {
        continue; // duplicate: linked, not produced — leave the invoice alone
      }
      Invoice inv = u.getInvoice();
      if (inv == null) {
        continue;
      }
      Long id = inv.getId(); // proxy id — does NOT initialize the proxy
      if (id == null) {
        result.add(inv); // transient (never persisted)
      } else if (invoiceExists(id) && byId.putIfAbsent(id, inv) == null) {
        result.add(inv);
      }
    }
    return result;
  }

  /** Existence check that never loads the entity (safe for a possibly-deleted, dangling FK). */
  protected boolean invoiceExists(Long id) {
    return id != null
        && JPA.em()
                .createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult()
            > 0;
  }

  protected boolean moveExists(Long id) {
    return id != null
        && JPA.em()
                .createQuery("SELECT COUNT(m) FROM Move m WHERE m.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult()
            > 0;
  }

  /**
   * Null the {@code invoice}/{@code move} FK on every upload in the tree via a bulk update (no
   * entity load, no audit dereference), then refresh the managed copies so their in-memory state
   * matches.
   */
  protected void detachDocumentRefs(Collection<AiOcrUpload> uploads) {
    List<Long> ids = new ArrayList<>();
    for (AiOcrUpload u : uploads) {
      if (u.getId() != null) {
        ids.add(u.getId());
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    JPA.em()
        .createQuery("UPDATE AiOcrUpload u SET u.invoice = null, u.move = null WHERE u.id IN :ids")
        .setParameter("ids", ids)
        .executeUpdate();
    for (AiOcrUpload u : uploads) {
      JPA.em().refresh(u); // sync the persistence context with the nulled FK columns
    }
  }

  /**
   * Unlink (FK-safe bulk update) any upload still pointing at this invoice, before it is removed.
   */
  protected void detachUploadsFromInvoice(Invoice invoice) {
    if (invoice == null || invoice.getId() == null) {
      return;
    }
    JPA.em()
        .createQuery("UPDATE AiOcrUpload u SET u.invoice = null WHERE u.invoice = :inv")
        .setParameter("inv", invoice)
        .executeUpdate();
  }

  protected List<AiOcrUpload> children(AiOcrUpload u) {
    return uploadRepo.all().filter("self.importArchive = :u").bind("u", u).fetch();
  }

  // ---------------------------------------------------------------------------------------------
  // Per-document protection, re-read inside the transaction
  // ---------------------------------------------------------------------------------------------

  /** One upload that must be kept, with the reason naming its own blocking document. */
  public static final class ProtectedUpload {
    private final Long uploadId;
    private final String uploadLabel;
    private final String reason;

    ProtectedUpload(Long uploadId, String uploadLabel, String reason) {
      this.uploadId = uploadId;
      this.uploadLabel = uploadLabel;
      this.reason = reason;
    }

    public Long getUploadId() {
      return uploadId;
    }

    public String getUploadLabel() {
      return uploadLabel;
    }

    public String getReason() {
      return reason;
    }

    /** "invoice.pdf (id=42): its invoice 000123 has been ventilated (tax compliance)" */
    public String describeRef() {
      return describe(uploadLabel) + " (id=" + uploadId + "): " + reason;
    }
  }

  /**
   * Evaluate protection for each upload <b>individually</b>, reading the documents FRESH from the
   * database.
   *
   * <p>Freshness matters: the in-memory {@code Invoice}/{@code Move} reachable from an upload may
   * be a managed copy loaded earlier in the request, and a ventilation committed by someone else in
   * the meantime must be honoured. So the status and the ventilation markers are read with a
   * projection query inside the caller's transaction rather than taken from the entity graph —
   * which also avoids initialising a proxy whose row may already be gone.
   *
   * <p>A {@code duplicate} upload is skipped exactly as {@link #producedInvoices} skips it: it only
   * <em>links</em> an invoice it did not create, so that invoice's status is not its business.
   *
   * @return one entry per protected upload, in tree order; empty when the whole set is deletable
   */
  protected List<ProtectedUpload> evaluateProtections(Collection<AiOcrUpload> uploads) {
    List<ProtectedUpload> protections = new ArrayList<>();
    if (uploads == null) {
      return protections;
    }
    for (AiOcrUpload u : uploads) {
      if (u == null || AiOcrUploadStatus.DUPLICATE.equals(u.getStatusSelect())) {
        continue;
      }
      Invoice inv = u.getInvoice();
      Long invoiceId = inv == null ? null : inv.getId(); // proxy id: does not initialise
      Move mv = u.getMove();
      Long moveId = mv == null ? null : mv.getId();

      boolean ventilated = invoiceId != null && isVentilatedInDb(invoiceId);
      boolean postedMove = moveId != null && isMoveProtectedInDb(moveId);
      String reason =
          protectionReason(
              ventilated, invoiceLabelInDb(invoiceId), postedMove, moveLabelInDb(moveId));
      if (reason != null) {
        protections.add(new ProtectedUpload(u.getId(), u.getFileName(), reason));
      }
    }
    return protections;
  }

  /**
   * Ventilation evidence read fresh from the database. Mirrors {@link #wasVentilated(Invoice)}
   * exactly — current status, either ventilation marker, or a posted move — but over committed
   * columns instead of a possibly stale entity. A row that no longer exists is not protected.
   */
  protected boolean isVentilatedInDb(Long invoiceId) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT i.statusSelect, i.ventilatedDateTime, vu.id, mv.id FROM Invoice i"
                    + " LEFT JOIN i.ventilatedByUser vu LEFT JOIN i.move mv WHERE i.id = :id",
                Object[].class)
            .setParameter("id", invoiceId)
            .getResultList();
    if (rows.isEmpty()) {
      return false;
    }
    Object[] r = rows.get(0);
    return Objects.equals(InvoiceRepository.STATUS_VENTILATED, r[0])
        || r[1] != null
        || r[2] != null
        || r[3] != null;
  }

  /** Move status read fresh; a row that no longer exists is not protected. */
  protected boolean isMoveProtectedInDb(Long moveId) {
    List<Integer> rows =
        JPA.em()
            .createQuery("SELECT m.statusSelect FROM Move m WHERE m.id = :id", Integer.class)
            .setParameter("id", moveId)
            .getResultList();
    return !rows.isEmpty() && isMoveProtected(rows.get(0));
  }

  protected String invoiceLabelInDb(Long invoiceId) {
    if (invoiceId == null) {
      return null;
    }
    List<String> rows =
        JPA.em()
            .createQuery("SELECT i.invoiceId FROM Invoice i WHERE i.id = :id", String.class)
            .setParameter("id", invoiceId)
            .getResultList();
    String label = rows.isEmpty() ? null : rows.get(0);
    return (label == null || label.trim().isEmpty()) ? ("id=" + invoiceId) : label;
  }

  protected String moveLabelInDb(Long moveId) {
    if (moveId == null) {
      return null;
    }
    List<String> rows =
        JPA.em()
            .createQuery("SELECT m.reference FROM Move m WHERE m.id = :id", String.class)
            .setParameter("id", moveId)
            .getResultList();
    String label = rows.isEmpty() ? null : rows.get(0);
    return (label == null || label.trim().isEmpty()) ? ("id=" + moveId) : label;
  }

  private void removePostOrder(AiOcrUpload u, Consumer<AiOcrUpload> remover, Set<Long> visited) {
    if (u == null) {
      return;
    }
    Long id = u.getId();
    if (id != null && !visited.add(id)) {
      return; // cycle-safe
    }
    for (AiOcrUpload child : children(u)) {
      removePostOrder(child, remover, visited);
    }
    remover.accept(u);
  }

  /**
   * Quarantine and drop every candidate file that nothing references any more.
   *
   * <p>Each candidate is <b>re-read by id</b> first. The instances in {@code candidates} were
   * captured from the upload entities at the start of the delete, and by the time this runs the
   * persistence context has moved on: attachments were detached, upload rows removed, {@code
   * detachDocumentRefs} issued a bulk UPDATE and refreshed the uploads, and the invoices/moves were
   * removed. Removing a stale instance then fails the optimistic-version check with {@code
   * StaleObjectStateException}. Re-reading also means a file some other path already reclaimed is
   * simply skipped instead of being removed twice.
   */
  private void quarantineUnreferenced(Set<MetaFile> candidates, List<QuarantinedFile> quarantined) {
    for (MetaFile candidate : candidates) {
      if (candidate == null || candidate.getId() == null) {
        continue; // never persisted — there is no row or blob to reclaim
      }
      MetaFile mf = metaFileRepo.find(candidate.getId());
      if (mf == null) {
        continue; // already gone
      }
      if (!isUnreferenced(mf)) {
        continue; // still referenced elsewhere — keep record + blob
      }
      quarantineService.quarantine(mf).ifPresent(quarantined::add);
      metaFileRepo.remove(mf);
    }
  }

  /**
   * True when NOTHING at all still references this MetaFile. The single source of truth for "this
   * blob is nobody's any more", so a shared file can never be removed by one of its holders.
   *
   * <p>Answered by {@link OcrMetaFileReferenceScanner#referencesOf} — <b>every</b> relation the JPA
   * metamodel knows about, in every activated module. The previous hand-written form asked only
   * about four upload fields plus attachments and DMS, and provably missed two live relations:
   * {@code BankStatement.bankStatementFile} and the deprecated {@code AiOcrUpload.stockReceiptFile}
   * column. That was survivable only while every consumer held its own private copy of the bytes;
   * with a shared MetaFile it would be data loss.
   *
   * <p><b>Fail-closed:</b> an inconclusive scan (a relation query failed, or a custom JSON field
   * may hold MetaFile ids outside any foreign key) answers {@code false} — never "unreferenced".
   */
  public boolean isUnreferenced(MetaFile mf) {
    if (mf == null || mf.getId() == null) {
      return false;
    }
    return referenceScanner.referencesOf(mf).isUnreferenced();
  }

  /**
   * Quarantine ONE MetaFile — and remove its row — if nothing references it any more.
   *
   * <p>For callers that drop a single file reference rather than a whole upload tree (the ZIP
   * reclaimed after a fully successful archive extraction). It reuses the same reference test and
   * the same {@link OcrFileTransactionSynchronization} contract as the delete path, so:
   *
   * <ul>
   *   <li>the blob is only <b>moved aside</b> into quarantine while the transaction is open — no
   *       physical deletion ever happens inside the transaction;
   *   <li>the permanent delete runs after COMMIT, and a ROLLBACK restores the blob;
   *   <li>a file still held by another upload, an attachment or a DMS entry is left completely
   *       alone.
   * </ul>
   *
   * <p><b>Must be called with an active transaction</b> (the synchronization is registered on it)
   * and <b>after</b> the caller's own reference has been nulled and flushed — otherwise the caller
   * itself still counts as a referrer and nothing is reclaimed.
   *
   * @return true when the file was quarantined for deletion
   */
  /**
   * Flush, then quarantine {@code file} if nothing references it any more — the one-file release
   * used by callers that have just nulled their own reference to it (a deleted Bank Statement, the
   * reclaimed source ZIP).
   *
   * <p>The flush is what makes the caller's just-nulled reference visible to the reference scan; a
   * caller that still holds the file simply gets {@code false} and the file is left completely
   * alone. Must be called with an active transaction — the post-commit synchronization is
   * registered on it, so the blob is only moved aside now and permanently deleted after commit (or
   * restored on rollback). Never throws: a file that cannot be released stays where it is.
   */
  public boolean releaseFileIfUnreferenced(MetaFile file) {
    if (file == null || file.getId() == null) {
      return false;
    }
    JPA.flush();
    return quarantineIfUnreferenced(file);
  }

  public boolean quarantineIfUnreferenced(MetaFile mf) {
    if (!isUnreferenced(mf)) {
      return false;
    }
    List<QuarantinedFile> quarantined = new ArrayList<>();
    JPA.em()
        .unwrap(Session.class)
        .getTransaction()
        .registerSynchronization(
            new OcrFileTransactionSynchronization(quarantined, quarantineService));
    quarantineService.quarantine(mf).ifPresent(quarantined::add);
    metaFileRepo.remove(mf);
    return true;
  }

  /**
   * Cancel a DRAFT/VALIDATED invoice through the stock {@link InvoiceService#cancel(Invoice)}
   * business service (NOT by mutating the status directly), so the stock delete guard (CANCELED
   * only) then accepts it. First re-checks the dependency guard, then delegates to the stock cancel
   * — which itself refuses any invoice that is not draft/validated (so a ventilated invoice can
   * never be cancelled here). A CANCELED invoice is a no-op. The stock accounting protections are
   * therefore never bypassed: only a genuinely safe draft/validated invoice is auto-cancelled.
   */
  public void autoCancelIfSafe(Invoice inv) {
    if (inv == null) {
      return;
    }
    Integer status = inv.getStatusSelect();
    if (Objects.equals(InvoiceRepository.STATUS_DRAFT, status)
        || Objects.equals(InvoiceRepository.STATUS_VALIDATED, status)) {
      assertSafeToAutoCancel(inv);
      try {
        invoiceService.cancel(inv);
      } catch (AxelorException e) {
        // Roll the whole delete back and surface the accounting reason.
        throw new PersistenceException(e.getMessage(), e);
      }
    }
  }
}
