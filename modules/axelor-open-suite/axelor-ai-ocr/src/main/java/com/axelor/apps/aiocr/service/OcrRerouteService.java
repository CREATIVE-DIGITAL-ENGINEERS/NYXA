package com.axelor.apps.aiocr.service;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.audit.AiOcrUploadAuditService;
import com.axelor.apps.aiocr.service.bankstatement.OcrBankStatementCreator;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.supplier.SupplierEik;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual rerouting of ONE {@link AiOcrUpload} (or one split segment) to an EXPLICITLY chosen
 * destination, for when the automatic classification produced the wrong kind of record.
 *
 * <p>This is the single entry point behind all four view buttons ({@link #reroute}); the
 * destination is a typed {@link OcrRerouteDestination}, never free text and never re-derived from
 * an error message. Everything else is reuse:
 *
 * <ul>
 *   <li>the extraction comes from {@link BgOcrIntakeService#rehydrateExtraction} — the same stored
 *       JSON smart retry reuses, so a wrong classification never costs a second Azure call;
 *   <li>the record is created by the SAME destination handlers ordinary intake uses ({@code
 *       routeInvoice}, {@code routeMove}, {@code routeCustomerInvoice}, {@code
 *       OcrBankStatementCreator#createForOwner}) — no parallel pipeline, and the document
 *       classifier is simply not consulted;
 *   <li>the replaced record is removed through the existing guarded repository paths ({@code
 *       OcrInvoiceRepository} / {@code OcrMoveRepository} / {@code deleteForUpload}), which keep
 *       every stock protection;
 *   <li>double clicks are stopped by the same atomic status claim the retry/process buttons use.
 * </ul>
 *
 * <p><b>Order of operations is the safety property.</b> The new record is created FIRST and the old
 * one removed only afterwards, both inside one transaction: if creation fails (or produces nothing
 * — an unmatched supplier, a duplicate, a missing journal) the transaction rolls back and the
 * upload keeps the record and the link it had, with the reason written to {@code extractionErrors}
 * by a separate, committed release step.
 */
@Singleton
public class OcrRerouteService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrRerouteService.class);

  /**
   * Statuses a record may be rerouted from. A queued/processing/extracting row is mid-flight, and a
   * carrier or a {@code deletedFrom*} row has no result of its own to replace.
   */
  private static final List<String> REROUTABLE_STATUSES =
      Arrays.asList(
          AiOcrUploadStatus.COMPLETED,
          AiOcrUploadStatus.NEEDS_REVIEW,
          AiOcrUploadStatus.FAILED,
          AiOcrUploadStatus.COMPLETED_WITH_ISSUES);

  protected final AiOcrUploadRepository uploadRepo;
  protected final PartnerRepository partnerRepo;
  protected final BgOcrIntakeService intake;

  @Inject
  public OcrRerouteService(
      AiOcrUploadRepository uploadRepo, PartnerRepository partnerRepo, BgOcrIntakeService intake) {
    this.uploadRepo = uploadRepo;
    this.partnerRepo = partnerRepo;
    this.intake = intake;
  }

  /** A reroute that cannot be performed, with a message meant for the user. Nothing was changed. */
  public static class RerouteRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RerouteRefusedException(String message) {
      super(message);
    }
  }

  /** What a completed reroute produced, for the controller's notification. */
  public static final class Outcome {
    private final OcrRerouteDestination destination;
    private final String message;

    Outcome(OcrRerouteDestination destination, String message) {
      this.destination = destination;
      this.message = message;
    }

    public OcrRerouteDestination getDestination() {
      return destination;
    }

    public String getMessage() {
      return message;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------------------------

  /**
   * Reroute exactly this upload to {@code destination}. Never touches sibling segments of the same
   * source file: every read and write below is keyed on this one id.
   *
   * @throws RerouteRefusedException when the operation is not allowed or could not complete; in
   *     that case nothing was created, nothing was deleted and the upload keeps its previous record
   *     and status
   */
  public Outcome reroute(Long uploadId, OcrRerouteDestination destination) {
    if (destination == null) {
      throw new RerouteRefusedException("No destination was supplied for the reroute.");
    }
    if (!isInternalUser()) {
      throw new RerouteRefusedException("Rerouting is restricted to internal users.");
    }
    AiOcrUpload upload = findUpload(uploadId);
    String refusal = refusalReason(upload, destination);
    if (refusal != null) {
      throw new RerouteRefusedException(refusal);
    }

    OcrRerouteDestination previousDestination = currentDestination(upload);
    String previousStatus = upload.getStatusSelect();
    // Atomic claim, exactly like the process/retry buttons: only the caller that flips the row out
    // of its current status proceeds, so a double click (or two users) cannot both create a record.
    if (!claimForReroute(uploadId, previousStatus)) {
      throw new RerouteRefusedException(
          "This record is already being processed or was just rerouted by someone else. Refresh to"
              + " see its current state.");
    }
    try {
      String message = applyReroute(uploadId, destination, previousDestination, previousStatus);
      return new Outcome(destination, message);
    } catch (RerouteRefusedException e) {
      releaseClaim(uploadId, previousStatus, e.getMessage());
      throw e;
    } catch (Exception e) {
      LOG.warn("Reroute of upload #{} to {} failed: {}", uploadId, destination, e.toString());
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty()
              ? e.getClass().getSimpleName()
              : e.getMessage();
      String text =
          "Reroute to " + destination.getLabel() + " failed and nothing was changed: " + reason;
      releaseClaim(uploadId, previousStatus, text);
      throw new RerouteRefusedException(text);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Current destination + eligibility
  // ---------------------------------------------------------------------------------------------

  /** The destination this upload currently holds a record for, or {@code null} when it has none. */
  public OcrRerouteDestination currentDestination(AiOcrUpload upload) {
    if (upload == null) {
      return null;
    }
    Invoice invoice = upload.getInvoice();
    if (invoice != null) {
      return InvoiceRepository.OPERATION_TYPE_CLIENT_SALE == nz(invoice.getOperationTypeSelect())
              || InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND
                  == nz(invoice.getOperationTypeSelect())
          ? OcrRerouteDestination.CUSTOMER_INVOICE
          : OcrRerouteDestination.SUPPLIER_INVOICE;
    }
    if (upload.getMove() != null) {
      return OcrRerouteDestination.MOVE;
    }
    return statementRef(upload) == null ? null : OcrRerouteDestination.BANK_STATEMENT;
  }

  /**
   * Why this upload cannot be rerouted to {@code destination}, or {@code null} when it can. Applied
   * before anything is claimed AND again inside the write transaction, so a record that changed in
   * between is still refused rather than half-rerouted.
   */
  public String refusalReason(AiOcrUpload upload, OcrRerouteDestination destination) {
    if (upload == null) {
      return "No OCR record was found for this action.";
    }
    // Company scope, server-side. The controller checks it too, but a controller check is a
    // convenience, not the control: this is the one that also holds for any other caller.
    Company company = upload.getCompany();
    if (company == null) {
      return "This OCR record has no company, so it belongs to no one and cannot be rerouted.";
    }
    if (!ownsCompany(company)) {
      return "No OCR record with id " + upload.getId() + ".";
    }
    if (upload.getCarrierKind() != null || upload.getArchiveFile() != null) {
      return "This record is an archive/split carrier, not a single document. Open the individual"
          + " document you want to reroute and use its own buttons.";
    }
    if (!REROUTABLE_STATUSES.contains(upload.getStatusSelect())) {
      return "A record with status \""
          + upload.getStatusSelect()
          + "\" cannot be rerouted. Only completed, needs-review or failed records can.";
    }
    OcrRerouteDestination current = currentDestination(upload);
    if (current == destination) {
      return "This record is already routed to " + destination.getLabel() + ".";
    }
    if (!intake.hasUsableStoredExtraction(upload)) {
      return "This record has no stored extraction to build a "
          + destination.getLabel()
          + " from. Run Retry first so the document is read again, then reroute.";
    }
    if (destination == OcrRerouteDestination.BANK_STATEMENT && rawAnalyzeResult(upload) == null) {
      // Documented limitation: the bank-statement parser consumes Azure's raw analyzeResult, which
      // only a plain upload / a carrier keeps. A split child keeps its per-segment OcrExtractResult
      // instead, and that shape carries no page geometry to parse a statement from. Refused rather
      // than silently re-extracting: that decision belongs to the user, through Retry.
      return "This record is a split segment and keeps only its parsed extraction, which a bank"
          + " statement cannot be built from. Re-run the whole document (Retry on the original"
          + " upload) if it is really a bank statement.";
    }
    return replacementRefusal(upload, current);
  }

  /** Why the record this upload currently holds may not be replaced, or {@code null}. */
  protected String replacementRefusal(AiOcrUpload upload, OcrRerouteDestination current) {
    if (current == null) {
      return null; // nothing to replace (a failed/needs-review record with no result)
    }
    if (AiOcrUploadStatus.DUPLICATE.equals(upload.getStatusSelect())) {
      return "This record is a duplicate and only points at an invoice created by another upload;"
          + " it cannot be rerouted.";
    }
    switch (current) {
      case SUPPLIER_INVOICE:
      case CUSTOMER_INVOICE:
        return invoiceReplacementRefusal(upload);
      case MOVE:
        return moveReplacementRefusal(upload);
      case BANK_STATEMENT:
        return statementReplacementRefusal(upload);
      default:
        return null;
    }
  }

  protected String invoiceReplacementRefusal(AiOcrUpload upload) {
    Invoice invoice = upload.getInvoice();
    if (invoice == null) {
      return null;
    }
    if (!sameCompany(upload, invoice.getCompany())) {
      return "The invoice linked to this record belongs to another company; it cannot be replaced"
          + " from here.";
    }
    if (OcrUploadFileCleanupService.wasVentilated(invoice)) {
      return "Invoice "
          + invoiceLabel(invoice)
          + " has been ventilated and can never be replaced (tax compliance). Nothing was changed.";
    }
    if (InvoiceRepository.STATUS_DRAFT != nz(invoice.getStatusSelect())) {
      return "Invoice "
          + invoiceLabel(invoice)
          + " is no longer a draft (status "
          + invoice.getStatusSelect()
          + "), so it cannot be replaced automatically. Cancel/delete it in Accounting first.";
    }
    if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
      return "Invoice "
          + invoiceLabel(invoice)
          + " was not created automatically from this document, so rerouting will not delete it."
          + " Handle it in Accounting first.";
    }
    long otherOwners = otherUploadsLinkingInvoice(upload, invoice);
    if (otherOwners > 0) {
      return "Invoice "
          + invoiceLabel(invoice)
          + " is also linked to "
          + otherOwners
          + " other OCR record(s), so it is not this record's to replace.";
    }
    return null;
  }

  protected String moveReplacementRefusal(AiOcrUpload upload) {
    Move move = upload.getMove();
    if (move == null) {
      return null;
    }
    if (!sameCompany(upload, move.getCompany())) {
      return "The accounting move linked to this record belongs to another company; it cannot be"
          + " replaced from here.";
    }
    if (OcrUploadFileCleanupService.isMoveProtected(move.getStatusSelect())) {
      return "Accounting move "
          + moveLabel(move)
          + " is posted (not new/simulated) and cannot be replaced. Nothing was changed.";
    }
    long otherOwners = otherUploadsLinkingMove(upload, move);
    if (otherOwners > 0) {
      return "Accounting move "
          + moveLabel(move)
          + " is also linked to "
          + otherOwners
          + " other OCR record(s), so it is not this record's to replace.";
    }
    return null;
  }

  protected String statementReplacementRefusal(AiOcrUpload upload) {
    OcrBankStatementCreator.StatementRef ref = statementRef(upload);
    if (ref == null) {
      return null;
    }
    if (ref.getCompanyId() != null
        && upload.getCompany() != null
        && !ref.getCompanyId().equals(upload.getCompany().getId())) {
      return "The bank statement linked to this record belongs to another company; it cannot be"
          + " replaced from here.";
    }
    if (!ref.isReplaceable()) {
      return "Bank statement #"
          + ref.getId()
          + " has already been imported and cannot be replaced. Nothing was changed.";
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Claim / release (atomic, committed on their own)
  // ---------------------------------------------------------------------------------------------

  /**
   * Guarded status flip {@code <previous> → processing}, the same shape as {@code
   * BgOcrIntakeService#claim}: exactly one concurrent caller can win it, so two clicks can never
   * both reach the creation step.
   */
  @Transactional
  protected boolean claimForReroute(Long uploadId, String previousStatus) {
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :processing, s.lockedOn = :now "
                    + "WHERE s.id = :id AND s.statusSelect = :previous")
            .setParameter("processing", AiOcrUploadStatus.PROCESSING)
            .setParameter("now", LocalDateTime.now())
            .setParameter("id", uploadId)
            .setParameter("previous", previousStatus)
            .executeUpdate();
    if (updated == 1) {
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(uploadId));
    }
    return updated == 1;
  }

  /**
   * Put the row back exactly where it was and record why, in its own committed transaction — so a
   * failed reroute never leaves the record stuck in {@code processing}. Guarded on {@code
   * processing} so it cannot overwrite a state someone else has since set.
   */
  @Transactional
  protected void releaseClaim(Long uploadId, String previousStatus, String reason) {
    // Drop whatever the rolled-back attempt left in the persistence context first: the entities it
    // touched are stale (and possibly unflushable), and this write must not drag any of them along.
    // The statement below is a bulk UPDATE for the same reason markFailed is one.
    JPA.em().clear();
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :previous, s.lockedOn = null, "
                    + "s.extractionErrors = :reason WHERE s.id = :id AND s.statusSelect = :processing")
            .setParameter("previous", previousStatus)
            .setParameter("reason", reason)
            .setParameter("id", uploadId)
            .setParameter("processing", AiOcrUploadStatus.PROCESSING)
            .executeUpdate();
    if (updated == 1) {
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(uploadId));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The write
  // ---------------------------------------------------------------------------------------------

  /**
   * Create the requested record and, only once it exists, remove the replaced one — in ONE
   * transaction, so a failure anywhere leaves the upload exactly as it was.
   *
   * @return the user-facing success message
   */
  @Transactional
  protected String applyReroute(
      Long uploadId,
      OcrRerouteDestination destination,
      OcrRerouteDestination previousDestination,
      String previousStatus) {
    AiOcrUpload upload = findUpload(uploadId);
    if (upload == null) {
      throw new RerouteRefusedException("The OCR record no longer exists.");
    }
    // Re-check under the claim: the record (or its target) may have moved on since the button was
    // rendered. The status itself is now 'processing', so it is checked against what we claimed.
    if (!REROUTABLE_STATUSES.contains(previousStatus)) {
      throw new RerouteRefusedException("This record can no longer be rerouted.");
    }
    String refusal = replacementRefusal(upload, previousDestination);
    if (refusal != null) {
      throw new RerouteRefusedException(refusal);
    }

    Invoice oldInvoice = upload.getInvoice();
    Move oldMove = upload.getMove();
    boolean hadStatement = previousDestination == OcrRerouteDestination.BANK_STATEMENT;

    // The saved extraction, chosen by the SAME rule smart retry uses. No Azure call is made here
    // for any destination.
    OcrExtractResult result = intake.rehydrateExtraction(upload);

    String created = createDestination(upload, destination, result);

    // Only now that the new record exists: drop the replaced one, through the existing guarded
    // delete paths (they keep every stock protection and refuse anything unsafe, which would roll
    // this whole transaction back).
    String removed = removePrevious(upload, previousDestination, oldInvoice, oldMove, hadStatement);

    recordAudit(upload, previousDestination, destination, removed);
    upload.setProcessedOn(LocalDateTime.now());
    upload.setLockedOn(null);
    saveUpload(upload);
    syncAudit(upload);

    return "Rerouted from "
        + label(previousDestination)
        + " to "
        + destination.getLabel()
        + ". "
        + created
        + (removed == null ? "" : " " + removed);
  }

  /**
   * Run the destination's OWN existing handler — the document classifier is never consulted, which
   * is the whole point of an explicit reroute. Throws {@link RerouteRefusedException} when the
   * handler produced no record (an unmatched supplier, a duplicate, a missing backend): that is a
   * failure, not a success, and it must roll the transaction back with the old record intact.
   */
  protected String createDestination(
      AiOcrUpload upload, OcrRerouteDestination destination, OcrExtractResult result) {
    try {
      switch (destination) {
        case MOVE:
          intake.routeMove(
              upload,
              result,
              "Manually rerouted to a draft accounting move — review and classify.");
          break;
        case SUPPLIER_INVOICE:
          intake.routeInvoice(upload, result);
          break;
        case CUSTOMER_INVOICE:
          // Azure fills the vendor identity only from VendorTaxId, which a BG invoice printing
          // just "ЕИК …" / "ДДС № BG…" leaves empty — recover it from the text first, or the
          // identity check below has nothing to compare and refuses a document that names its
          // issuer perfectly well. Buyer-only exclusion, exactly-one-candidate, no name matching.
          intake.recoverIssuerEikForCustomerReroute(result);
          // Identity is proven BEFORE anything is built: this company must be the document's
          // issuer, and the buyer must resolve to exactly one partner of this company.
          intake.routeCustomerInvoice(upload, result, requireCustomer(upload, result));
          break;
        case BANK_STATEMENT:
          createBankStatement(upload, result);
          break;
        default:
          throw new RerouteRefusedException("Unsupported destination.");
      }
    } catch (RerouteRefusedException e) {
      throw e;
    } catch (Exception e) {
      throw new RerouteRefusedException(
          "The "
              + destination.getLabel()
              + " could not be created, so nothing was changed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
    // An invoice handler can legitimately finish WITHOUT creating anything (supplier not matched,
    // semantic duplicate). Reading the result of the handler — never its message text — is what
    // tells the two apart.
    if (destination == OcrRerouteDestination.SUPPLIER_INVOICE
        || destination == OcrRerouteDestination.CUSTOMER_INVOICE) {
      if (upload.getInvoice() == null || upload.getInvoice().getId() == null) {
        throw new RerouteRefusedException(
            "No "
                + destination.getLabel()
                + " could be created from this document"
                + reasonSuffix(upload.getExtractionErrors())
                + " Nothing was changed.");
      }
      if (AiOcrUploadStatus.DUPLICATE.equals(upload.getStatusSelect())) {
        throw new RerouteRefusedException(
            "This document matches an existing invoice"
                + reasonSuffix(upload.getExtractionErrors())
                + " Nothing was changed.");
      }
      return "Invoice #" + upload.getInvoice().getId() + " created.";
    }
    if (destination == OcrRerouteDestination.MOVE) {
      if (upload.getMove() == null || upload.getMove().getId() == null) {
        throw new RerouteRefusedException(
            "No accounting move could be created. Nothing was changed.");
      }
      return "Accounting move #" + upload.getMove().getId() + " created.";
    }
    OcrBankStatementCreator.StatementRef ref = statementRef(upload);
    return ref == null ? "Bank statement created." : "Bank statement #" + ref.getId() + " created.";
  }

  /** Create the bank statement from the raw analyzeResult this record stores (never from Azure). */
  protected void createBankStatement(AiOcrUpload upload, OcrExtractResult result) {
    OcrBankStatementCreator creator = bankStatementCreator();
    if (creator == null) {
      throw new RerouteRefusedException(
          "No Bank Statement backend is available in this installation.");
    }
    String rawJson = rawAnalyzeResult(upload);
    if (rawJson == null) {
      throw new RerouteRefusedException(
          "This record has no raw extraction to build a bank statement from.");
    }
    Long statementId = creator.createForOwner(upload, rawJson, 1, Integer.MAX_VALUE);
    if (statementId == null) {
      throw new RerouteRefusedException(
          "No bank statement could be read from this document. Nothing was changed.");
    }
    upload.setInvoice(null);
    upload.setMove(null);
    upload.setExtractionErrors("Rerouted to Bank Statement #" + statementId + " (review).");
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    saveUpload(upload);
  }

  /**
   * Remove the record the upload used to hold, now that the new one exists. Nothing is force
   * deleted: each path is the module's existing guarded repository delete, which still refuses a
   * ventilated invoice or a posted move.
   *
   * @return a short note for the user, or {@code null} when there was nothing to remove
   */
  protected String removePrevious(
      AiOcrUpload upload,
      OcrRerouteDestination previousDestination,
      Invoice oldInvoice,
      Move oldMove,
      boolean hadStatement) {
    if (previousDestination == null) {
      return null;
    }
    switch (previousDestination) {
      case SUPPLIER_INVOICE:
      case CUSTOMER_INVOICE:
        if (oldInvoice == null || oldInvoice.getId() == null) {
          return null;
        }
        Long invoiceId = oldInvoice.getId();
        removeInvoice(oldInvoice);
        return "The previous draft invoice #" + invoiceId + " was removed.";
      case MOVE:
        if (oldMove == null || oldMove.getId() == null) {
          return null;
        }
        Long moveId = oldMove.getId();
        removeMove(oldMove);
        return "The previous draft move #" + moveId + " was removed.";
      case BANK_STATEMENT:
        if (!hadStatement) {
          return null;
        }
        OcrBankStatementCreator creator = bankStatementCreator();
        int deleted = creator == null ? 0 : creator.deleteForUpload(upload);
        return deleted == 0
            ? null
            : "The previous bank statement" + (deleted > 1 ? "s were" : " was") + " removed.";
      default:
        return null;
    }
  }

  /**
   * The reroute audit trail, written where the record already keeps its structured processing
   * information rather than in new domain fields: the previous type, the requested type, the
   * timestamp and the user, appended to {@code extractionInformation}. The {@code statusSelect} /
   * {@code invoice} / {@code move} changes are additionally tracked by the entity's own {@code
   * <track>} history, and {@code AiOcrUploadAudit} keeps the (permanent) status snapshot.
   */
  protected void recordAudit(
      AiOcrUpload upload,
      OcrRerouteDestination previousDestination,
      OcrRerouteDestination destination,
      String removed) {
    String line =
        "Rerouted "
            + label(previousDestination)
            + " → "
            + destination.getLabel()
            + " on "
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            + " by "
            + currentUserCode()
            + "."
            + (removed == null ? "" : " " + removed);
    String existing = upload.getExtractionInformation();
    upload.setExtractionInformation(
        existing == null || existing.trim().isEmpty() ? line : existing + "\n" + line);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * The BUYER of a rerouted customer invoice, or a refusal.
   *
   * <p>Thin wrapper over {@link BgOcrIntakeService#resolveCustomerForSale}, which is the single
   * implementation of "who may this company invoice" — the automatic outgoing intake runs the very
   * same checks, so the button and the pipeline can never disagree. All this adds is the reroute's
   * own failure shape: a refusal becomes {@link RerouteRefusedException}, which rolls the
   * transaction back with the previous record intact.
   */
  protected Partner requireCustomer(AiOcrUpload upload, OcrExtractResult result) {
    BgOcrIntakeService.CustomerResolution resolution =
        intake.resolveCustomerForSale(
            upload == null ? null : upload.getCompany(), result, this::matchPartnersForCompany);
    if (!resolution.isResolved()) {
      throw new RerouteRefusedException(resolution.refusal());
    }
    return resolution.customer();
  }

  /**
   * The raw Azure {@code analyzeResult} this record stores, which is the only shape the bank
   * statement parser can read. {@code null} for a split child (it keeps a parsed per-segment result
   * instead).
   */
  protected String rawAnalyzeResult(AiOcrUpload upload) {
    if (upload == null) {
      return null;
    }
    String raw = upload.getOcrResultJson();
    if (raw == null || raw.trim().isEmpty()) {
      raw = upload.getSplitSourceJson();
    }
    return raw == null || raw.trim().isEmpty() ? null : raw;
  }

  /** The bank-statement backend, or {@code null} when this installation has none bound. */
  protected OcrBankStatementCreator bankStatementCreator() {
    try {
      return Beans.get(OcrBankStatementCreator.class);
    } catch (RuntimeException e) {
      return null;
    }
  }

  protected OcrBankStatementCreator.StatementRef statementRef(AiOcrUpload upload) {
    OcrBankStatementCreator creator = bankStatementCreator();
    if (creator == null) {
      return null;
    }
    try {
      return creator.findForUpload(upload);
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Seams. Every call that needs the database, the security context or another module's repository
  // goes through one of these, so the decision logic above is unit-testable without any of them.
  // ---------------------------------------------------------------------------------------------

  /** Same internal-user rule the Retry/Reprocess actions use. */
  protected boolean isInternalUser() {
    return intake.isInternalUser();
  }

  /**
   * The acting user's code for the audit line. Never fails the reroute: this also runs from
   * contexts with no bound security subject.
   */
  protected String currentUserCode() {
    try {
      User user = AuthUtils.getUser();
      return user == null || user.getCode() == null ? "system" : user.getCode();
    } catch (RuntimeException e) {
      return "system";
    }
  }

  /** The active-company scope used by every other upload action. */
  protected boolean ownsCompany(Company company) {
    return AiCompanyScope.owns(company);
  }

  protected AiOcrUpload findUpload(Long uploadId) {
    return uploadRepo.find(uploadId);
  }

  protected void saveUpload(AiOcrUpload upload) {
    uploadRepo.save(upload);
  }

  protected void syncAudit(AiOcrUpload upload) {
    Beans.get(AiOcrUploadAuditService.class).sync(upload);
  }

  /** Guarded delete path of the Invoice repository chain ({@code OcrInvoiceRepository}). */
  protected void removeInvoice(Invoice invoice) {
    Beans.get(InvoiceRepository.class).remove(invoice);
  }

  /** Guarded delete path of the Move repository chain ({@code OcrMoveRepository}). */
  protected void removeMove(Move move) {
    Beans.get(MoveRepository.class).remove(move);
  }

  /** Partners of ONE company matching a canonical ЕИК — the company-scoped lookup, never global. */
  protected List<Partner> matchPartnersForCompany(String canonicalEik, Long companyId) {
    return SupplierEik.matchForCompany(partnerRepo, canonicalEik, companyId).fetch();
  }

  protected long otherUploadsLinkingInvoice(AiOcrUpload upload, Invoice invoice) {
    return uploadRepo
        .all()
        .filter("self.invoice = :inv AND self.id != :self")
        .bind("inv", invoice)
        .bind("self", upload.getId())
        .count();
  }

  protected long otherUploadsLinkingMove(AiOcrUpload upload, Move move) {
    return uploadRepo
        .all()
        .filter("self.move = :mv AND self.id != :self")
        .bind("mv", move)
        .bind("self", upload.getId())
        .count();
  }

  protected boolean sameCompany(AiOcrUpload upload, Company other) {
    Company own = upload == null ? null : upload.getCompany();
    if (own == null || own.getId() == null || other == null || other.getId() == null) {
      return false;
    }
    return own.getId().equals(other.getId());
  }

  private static String label(OcrRerouteDestination destination) {
    return destination == null ? "no record" : destination.getLabel();
  }

  private static String reasonSuffix(String reason) {
    return reason == null || reason.trim().isEmpty() ? "." : ": " + reason.trim();
  }

  private static String invoiceLabel(Invoice invoice) {
    if (invoice.getInvoiceId() != null && !invoice.getInvoiceId().trim().isEmpty()) {
      return invoice.getInvoiceId();
    }
    return "#" + invoice.getId();
  }

  private static String moveLabel(Move move) {
    if (move.getReference() != null && !move.getReference().trim().isEmpty()) {
      return move.getReference();
    }
    return "#" + move.getId();
  }

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }
}
