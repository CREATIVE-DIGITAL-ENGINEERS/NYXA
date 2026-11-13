package com.axelor.apps.aiocr.web;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.OcrRerouteDestination;
import com.axelor.apps.aiocr.service.OcrRerouteService;
import com.axelor.apps.aiocr.service.ingest.AiOcrArchiveExtractService;
import com.axelor.apps.aiocr.service.ingest.AiOcrSupportedTypes;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.process.AiOcrProcessingExecutor;
import com.axelor.apps.aiocr.service.storage.OcrOrphanFileCleanupService;
import com.axelor.apps.aiocr.service.storage.OcrOrphanInventoryService;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.Result;
import com.axelor.apps.base.ResponseMessageType;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JpaSecurity;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.util.List;
import java.util.Map;

/**
 * Wizard/record controller for the AI-OCR upload surface. Delegates the actual work (extraction →
 * draft invoice) to {@link BgOcrIntakeService}; controllers hold no transaction of their own.
 *
 * <h2>Why these actions check the company themselves</h2>
 *
 * A grid or a form open goes through Axelor's {@code Resource} layer, which applies the {@code
 * Permission.condition} filter for us. An <em>action</em> call does not: the client posts a context
 * containing a record id and the method acts on it. So an id from another company would otherwise
 * be actionable by anyone who can reach the action, simply by knowing the number.
 *
 * <p>{@link #requireOwnUpload} closes that, and it deliberately does two things rather than one:
 *
 * <ul>
 *   <li>{@code JpaSecurity.check} — the platform's own answer, which now carries the company
 *       condition from the module's permission data. This is the native control;
 *   <li>an explicit active-company assertion — which holds even on a database where the permission
 *       rows have not been re-imported yet, and which pins the rule in code where it can be read.
 * </ul>
 */
@Singleton
public class BgOcrUploadController {

  /**
   * Validate a file chosen in the "Document" section at upload time. Only PDF/PNG/JPG/TIFF are OCR
   * documents; anything else is cleared with a helpful message so the user isn't left with a record
   * that will only fail later during OCR:
   *
   * <ul>
   *   <li>a ZIP → use the "Archive" section (it extracts the contained documents);
   *   <li>a spreadsheet → upload bank statements in Accounting → Bank Statements;
   *   <li>anything else → unsupported.
   * </ul>
   */
  public void onOriginalFileChange(ActionRequest request, ActionResponse response) {
    AiOcrUpload upload = request.getContext().asType(AiOcrUpload.class);
    MetaFile file = upload.getOriginalFile();
    if (file == null) {
      response.setValue("fileName", null);
      return;
    }
    if (upload.getArchiveFile() != null) {
      // One record, one input. The view already makes this field read-only while an archive is
      // present, so this is the belt to that braces (a REST call, or a stale form).
      response.setValue("originalFile", null);
      response.setValue("fileName", null);
      response.setInfo(
          I18n.get(
              "This record already holds a ZIP archive — remove it with the × button first, or"
                  + " upload the document on a new record."));
      return;
    }
    String name = file.getFileName();
    String mime = file.getFileType();
    if (AiOcrSupportedTypes.isSupportedFileName(name)
        || AiOcrSupportedTypes.isSupportedMime(mime)) {
      response.setValue("fileName", name); // keep the previous set-filename behaviour
      // Persist the record now that the bytes have landed, so a user who navigates away after the
      // (slow) upload does not lose it. Only on the ACCEPTED path: a rejected file must leave
      // nothing behind, and an XML "onChange=...,save" chain cannot make that distinction —
      // ActionGroup sets pending="save" for any result carrying info. `values` are hoisted ahead of
      // the pending action by the client, so fileName is applied before the save runs.
      response.setNotify(I18n.get("Document uploaded. Press \"Process\" to run OCR on it."));
      response.setPending("save");
      return;
    }
    // Unsupported in the Document section — clear it and guide the user.
    response.setValue("originalFile", null);
    response.setValue("fileName", null);
    String ext = AiOcrSupportedTypes.extensionOf(name);
    String lowerMime = mime == null ? "" : mime.toLowerCase();
    if (AiOcrSupportedTypes.isZip(name) || lowerMime.contains("zip")) {
      response.setInfo(
          I18n.get(
              "This is a ZIP archive. Upload it in the \"Archive\" section below — it will extract"
                  + " and process each document inside."));
    } else if (ext.startsWith("xls")
        || "csv".equals(ext)
        || "ods".equals(ext)
        || lowerMime.contains("spreadsheet")
        || lowerMime.contains("excel")) {
      response.setInfo(
          I18n.get(
              "Spreadsheets are not OCR documents. Please upload bank statements in"
                  + " Accounting → Bank Statements."));
    } else {
      response.setInfo(
          I18n.get(
              "Unsupported file type. Only PDF, PNG, JPG and TIFF documents are accepted here."));
    }
  }

  /**
   * Mirror of {@link #onOriginalFileChange} for the "Archive" section — the field had no {@code
   * onChange} at all, which is how one record ended up holding two files and showing both the
   * <i>Process</i> and <i>Extract archive</i> buttons, and how a PDF dropped in here reached
   * extraction and failed the carrier with "Unreadable archive".
   *
   * <p>Two refusals, each clearing the just-picked value and explaining why:
   *
   * <ul>
   *   <li>the record already holds a document — one record, one input;
   *   <li>the file is not a ZIP; when it <em>is</em> an OCR document, point at the Document panel
   *       (the inverse of the message the document handler shows for a ZIP).
   * </ul>
   */
  public void onArchiveFileChange(ActionRequest request, ActionResponse response) {
    AiOcrUpload upload = request.getContext().asType(AiOcrUpload.class);
    MetaFile file = upload.getArchiveFile();
    if (file == null) {
      return;
    }
    if (upload.getOriginalFile() != null) {
      response.setValue("archiveFile", null);
      response.setInfo(
          I18n.get(
              "This record already holds a document — remove it with the × button first, or upload"
                  + " the archive on a new record."));
      return;
    }
    String name = file.getFileName();
    String mime = file.getFileType();
    if (!AiOcrSupportedTypes.isZipUpload(name, mime)) {
      response.setValue("archiveFile", null);
      if (AiOcrSupportedTypes.isSupportedFileName(name)
          || AiOcrSupportedTypes.isSupportedMime(mime)) {
        response.setInfo(
            I18n.get(
                "This is a single document, not an archive. Upload it in the \"Document\" section"
                    + " above."));
      } else {
        response.setInfo(
            I18n.get("Only a .zip archive can be uploaded here (it is extracted file by file)."));
      }
      return;
    }
    String current = upload.getFileName();
    if (current == null || current.trim().isEmpty()) {
      response.setValue("fileName", name);
    }
    // Accepted → persist now (see the note in onOriginalFileChange).
    response.setNotify(
        I18n.get("Archive uploaded. Press \"Extract archive\" to expand and process it."));
    response.setPending("save");
  }

  /**
   * Queue a saved upload for processing in the BACKGROUND (extract → gate → build DRAFT supplier
   * invoice, or FAIL/DUP). The heavy work — lossless normalization, the Azure OCR call (which fans
   * a large multi-page PDF out into many sequential submit+poll cycles), PDF splitting and
   * per-child processing — must never run on the HTTP request thread: for a big document that froze
   * the upload screen for minutes. The record is already {@code queued} (domain default; the button
   * is only shown for queued rows) and {@code save} persisted it, so we just wake the DB-backed
   * dispatcher, which claims it and runs the identical {@link BgOcrIntakeService#process} on the
   * OCR worker pool. The request returns immediately; status/progress/recovery are unchanged (use
   * Refresh to follow).
   */
  public void process(ActionRequest request, ActionResponse response) {
    try {
      AiOcrUpload upload = requireOwnUpload(request);
      if (upload.getArchiveFile() != null && upload.getOriginalFile() != null) {
        // A legacy row holding both files (the repository guard is insert-only by design). The
        // archive is the meaningful input, so send the user to Extract archive rather than OCR the
        // document half of a record that should never have existed.
        response.setInfo(
            I18n.get(
                "This record holds both a document and a ZIP archive. Use \"Extract archive\" — it"
                    + " expands the archive and processes each document inside."));
        return;
      }
      BgOcrIntakeService intake = Beans.get(BgOcrIntakeService.class);
      // A manual upload is stored as 'uploaded', which the dispatcher never drains — so processing
      // starts here, on the click, and nowhere else. No-op for a row that is already queued.
      intake.enqueue(upload.getId());
      // Claim the row synchronously (QUEUED -> PROCESSING, real start time) so the reload hides the
      // green Process button and a repeated click is a no-op; only the worker that wins the claim
      // hands the heavy OCR work to the background. Nothing long-running runs on this request.
      boolean claimed = intake.claim(upload.getId());
      if (claimed) {
        Beans.get(AiOcrProcessingExecutor.class).submitProcess(upload.getId());
      }
      response.setReload(true);
      response.setNotify(
          I18n.get(
              "Processing in the background. You can leave this page; use Refresh to see progress."));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * The persisted upload this action may act on.
   *
   * <p>Reads the row from the database rather than trusting {@code context.asType()}: that object
   * is built from what the client posted, so its {@code company} is the client's claim, not a fact.
   *
   * @throws IllegalArgumentException worded as "not found", so an id from another company is
   *     indistinguishable from an id that was never issued — <em>except</em> for the two cases that
   *     are the caller's own configuration rather than someone else's data (no resolvable scope,
   *     and a row belonging to no company at all), which say what is actually wrong. "No OCR record
   *     with id 1" for a record the user had just created was the original bug report.
   */
  protected AiOcrUpload requireOwnUpload(ActionRequest request) {
    Long id = request.getContext().asType(AiOcrUpload.class).getId();
    if (id == null) {
      throw new IllegalArgumentException(I18n.get("No OCR record was supplied."));
    }
    // Resolve the caller's scope BEFORE reading any row: an unresolved scope must never be able to
    // probe whether an id exists, and a configuration problem must not be reported as a missing
    // record.
    if (AiCompanyScope.companyIdOrNull() == null) {
      throw new IllegalArgumentException(
          I18n.get(
              "No active company is selected for your user, so no OCR record can be opened. Choose"
                  + " one in Preferences → Active company and try again."));
    }
    AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(id);
    if (upload == null) {
      throw new IllegalArgumentException(String.format(I18n.get("No OCR record with id %s."), id));
    }
    if (upload.getCompany() == null) {
      // A company-less row belongs to nobody, so saying so leaks nothing. These exist only where a
      // record was created while no active company was selected or
      // through a monitored folder configured without one.
      throw new IllegalArgumentException(
          String.format(
              I18n.get(
                  "OCR record %s has no company, so it belongs to no one and cannot be processed."
                      + " It was created while no active company was selected — select one in"
                      + " Preferences → Active company and upload the file again."),
              id));
    }
    if (!AiCompanyScope.owns(upload.getCompany())) {
      throw new IllegalArgumentException(String.format(I18n.get("No OCR record with id %s."), id));
    }
    // The platform's own check, which now carries the company condition through the module's
    // permission data. Admins bypass it, exactly as everywhere else in Axelor.
    Beans.get(JpaSecurity.class).check(JpaSecurity.CAN_WRITE, AiOcrUpload.class, id);
    return upload;
  }

  /**
   * Smart retry for one upload (internal action): re-extracts with Azure only when no usable OCR
   * result is stored yet, otherwise reruns local parsing/routing from the stored result (including
   * a missing-supplier {@code needsReview} row, once the supplier has been created manually — this
   * never creates one itself). See {@link BgOcrIntakeService#decideRetryMode}.
   */
  public void retry(ActionRequest request, ActionResponse response) {
    try {
      AiOcrUpload upload = requireOwnUpload(request);
      BgOcrIntakeService.RetryMode mode =
          Beans.get(BgOcrIntakeService.class).retryOne(upload.getId());
      response.setReload(true);
      switch (mode) {
        case REEXTRACT:
          response.setNotify(
              I18n.get(
                  "Queued for a fresh extraction. You can leave this page; use Refresh to see"
                      + " progress."));
          break;
        case REPROCESS:
          response.setNotify(
              I18n.get(
                  "Reprocessing the saved extraction in the background — no new extraction is"
                      + " needed. Use Refresh to see progress."));
          break;
        default:
          response.setInfo(
              I18n.get(
                  "This record is no longer eligible for retry — it may already be queued,"
                      + " processing, or someone else just retried it. Refresh to see its current"
                      + " status."));
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Form {@code onLoad}: publish the record's CURRENT destination into two "$"-prefixed dummy
   * context values, so the reroute panel can hide the button for the destination the record already
   * has — including Bank Statement, which is linked from the statement side and therefore has no
   * field on this record to test.
   *
   * <p>Read-only and deliberately silent: an unsaved record, a record outside the caller's scope or
   * any lookup problem simply leaves the values empty, which shows all four buttons and lets the
   * server-side rules in {@link OcrRerouteService} give the real answer. Never an error on form
   * load.
   */
  public void currentDestination(ActionRequest request, ActionResponse response) {
    try {
      Long id = request.getContext().asType(AiOcrUpload.class).getId();
      if (id == null) {
        return;
      }
      AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(id);
      if (upload == null
          || upload.getCompany() == null
          || !AiCompanyScope.owns(upload.getCompany())) {
        return;
      }
      OcrRerouteDestination current = Beans.get(OcrRerouteService.class).currentDestination(upload);
      response.setValue("$rerouteCurrentDestination", current == null ? null : current.getCode());
      response.setValue(
          "$rerouteCurrentDestinationLabel",
          current == null ? I18n.get("No record yet") : I18n.get(current.getLabel()));
    } catch (Exception e) {
      // A form load must never fail because of a UI hint.
    }
  }

  /**
   * Reroute this upload to a draft accounting Move. One of the four thin wrappers around the single
   * {@link #reroute(ActionRequest, ActionResponse, OcrRerouteDestination)} entry point — the
   * destination is the only thing that differs between them.
   */
  public void rerouteToMove(ActionRequest request, ActionResponse response) {
    reroute(request, response, OcrRerouteDestination.MOVE);
  }

  /** Reroute this upload to a draft supplier (purchase) invoice. */
  public void rerouteToSupplierInvoice(ActionRequest request, ActionResponse response) {
    reroute(request, response, OcrRerouteDestination.SUPPLIER_INVOICE);
  }

  /** Reroute this upload to a draft customer (sale) invoice. */
  public void rerouteToCustomerInvoice(ActionRequest request, ActionResponse response) {
    reroute(request, response, OcrRerouteDestination.CUSTOMER_INVOICE);
  }

  /** Reroute this upload to a reviewable bank statement. */
  public void rerouteToBankStatement(ActionRequest request, ActionResponse response) {
    reroute(request, response, OcrRerouteDestination.BANK_STATEMENT);
  }

  /**
   * The ONE reroute action body: scope-check the record exactly like every other per-record action
   * ({@link #requireOwnUpload}), then hand the typed destination to {@link OcrRerouteService},
   * which re-checks the company server-side and owns all the safety rules. A refusal is a normal
   * answer — shown as a plain message, not a traceback — and by contract means nothing was changed.
   */
  protected void reroute(
      ActionRequest request, ActionResponse response, OcrRerouteDestination destination) {
    try {
      AiOcrUpload upload = requireOwnUpload(request);
      OcrRerouteService.Outcome outcome =
          Beans.get(OcrRerouteService.class).reroute(upload.getId(), destination);
      response.setReload(true);
      response.setNotify(I18n.get(outcome.getMessage()));
    } catch (OcrRerouteService.RerouteRefusedException e) {
      response.setReload(true);
      response.setInfo(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Toolbar action: queue the same smart retry ({@link BgOcrIntakeService#retryOne}) for every
   * currently eligible upload — {@code failed} or {@code needsReview}, with its original file still
   * in storage — scoped to the caller's active company. One record's failure to queue never stops
   * the rest; never creates a supplier and never calls AI Accountant.
   */
  public void retryAllEligible(ActionRequest request, ActionResponse response) {
    try {
      Long companyId = AiCompanyScope.requireCompanyId();
      List<AiOcrUpload> candidates =
          Beans.get(AiOcrUploadRepository.class)
              .all()
              .filter(
                  "self.statusSelect IN (:failed, :needsReview) AND self.originalFile IS NOT NULL"
                      + " AND "
                      + AiCompanyScope.companyFilter("self.company"))
              .bind("failed", AiOcrUploadStatus.FAILED)
              .bind("needsReview", AiOcrUploadStatus.NEEDS_REVIEW)
              .bind(AiCompanyScope.COMPANY_PARAM, companyId)
              .fetch();
      BgOcrIntakeService intake = Beans.get(BgOcrIntakeService.class);
      int reextract = 0;
      int reprocess = 0;
      int skipped = 0;
      int failedToQueue = 0;
      for (AiOcrUpload candidate : candidates) {
        try {
          BgOcrIntakeService.RetryMode mode = intake.retryOne(candidate.getId());
          switch (mode) {
            case REEXTRACT:
              reextract++;
              break;
            case REPROCESS:
              reprocess++;
              break;
            default:
              skipped++;
          }
        } catch (Exception e) {
          failedToQueue++;
        }
      }
      response.setReload(true);
      response.setNotify(
          String.format(
              I18n.get(
                  "%d queued for re-extraction, %d queued for local reprocessing, %d skipped/not"
                      + " eligible, %d failed to queue."),
              reextract,
              reprocess,
              skipped,
              failedToQueue));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Reprocess an upload whose produced invoice was deleted from Suppl. Invoices: requeue the still
   * stored original document and run OCR again into a fresh draft. Only valid for the {@code
   * deletedFromSupplInvoices} status (enforced in the service).
   */
  public void reprocess(ActionRequest request, ActionResponse response) {
    try {
      AiOcrUpload upload = requireOwnUpload(request);
      BgOcrIntakeService intake = Beans.get(BgOcrIntakeService.class);
      intake.reprocess(upload.getId()); // guarded requeue -> QUEUED (own tx)
      // Re-OCR of a large PDF must not block the request thread. Claim the just-requeued row
      // synchronously (button hides, re-click no-op) and run the pipeline in the background.
      if (intake.claim(upload.getId())) {
        Beans.get(AiOcrProcessingExecutor.class).submitProcess(upload.getId());
      }
      response.setReload(true);
      response.setNotify(
          I18n.get(
              "Reprocessing in the background. You can leave this page; use Refresh to see"
                  + " progress."));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Expand the ZIP uploaded in the "Archive" section. Extraction and OCR run entirely in the
   * background (bounded pools + a DB-backed dispatcher), so this only kicks the work off and
   * returns immediately. The carrier row stays as the per-ZIP progress record (use Refresh to
   * update it).
   */
  public void extractArchive(ActionRequest request, ActionResponse response) {
    try {
      AiOcrUpload upload = requireOwnUpload(request);
      // A manually uploaded ZIP is stored as 'uploaded' for the same reason a document is, so it
      // has to be armed here too. No-op for an already-queued carrier (every pre-existing row).
      Beans.get(BgOcrIntakeService.class).enqueue(upload.getId());
      // Claim synchronously (status -> extracting, real start time stamped now) so the reload hides
      // the green "Extract archive" button and a repeated click is a no-op; only the background
      // then
      // runs the heavy extraction. This also fixes the start time equalling the finish time, which
      // happened when the start was stamped on the background thread right before a near-instant
      // extraction of small files.
      //
      // The boolean is the double-click protection (the UPDATE is guarded by extractionStartedOn IS
      // NULL); ignoring it would let a re-click launch a SECOND background extraction of the
      // same ZIP. Nothing is submitted unless this call won the claim.
      boolean claimed = Beans.get(AiOcrArchiveExtractService.class).claimExtraction(upload.getId());
      if (!claimed) {
        response.setReload(true);
        response.setNotify(
            I18n.get(
                "Extraction has already been started for this archive; use Refresh to see"
                    + " progress."));
        return;
      }
      Beans.get(AiOcrProcessingExecutor.class).submitExtraction(upload.getId());
      response.setReload(true);
      response.setNotify(
          I18n.get(
              "Extraction started in the background. You can leave this page; use Refresh to see"
                  + " progress."));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Refresh the carrier form. This is a FAST, READ-ONLY reload: it must never wait for the OCR
   * call, take a long-running processing lock, or save the (possibly stale) carrier entity. The
   * background pipeline already commits the carrier's status/progress independently — the OCR-page
   * percentage after each Azure chunk, and the child rollup after each child finishes — so a plain
   * reload shows the latest committed state immediately. Persisting here would block the UI for
   * minutes on the row lock held by the long OCR transaction and raise optimistic-lock ("unexpected
   * row count … id=?/version=?") errors.
   */
  public void refreshProgress(ActionRequest request, ActionResponse response) {
    response.setReload(true);
  }

  /**
   * Temporary, read-only storage diagnostic (see {@link OcrUploadStorageDiagnosticService}). The
   * button is admin-only in the view ({@code showIf}), but that is a UI hint, not a control — the
   * authorization is re-checked here, the only place that actually matters.
   */
  public void storageCheck(ActionRequest request, ActionResponse response) {
    if (!isAdmin()) {
      response.setError(I18n.get("You are not allowed to run the storage check."));
      return;
    }
    try {
      Result result = Beans.get(OcrUploadStorageDiagnosticService.class).scan();
      response.setInfo(formatStorageCheck(result));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Temporary, one-time, admin-only cleanup of the orphan {@code MetaFile} rows already proven by
   * {@link #storageCheck}. See {@link OcrOrphanFileCleanupService} for the safety contract: a fresh
   * scan is always run (the earlier Storage check is never trusted), every candidate is re-checked
   * immediately before deletion, and deletion goes through the same guarded production path used by
   * normal upload deletion. Meant to be removed once the proven orphans are cleaned up.
   *
   * <p>Same admin-only pattern as {@link #storageCheck}: the view's {@code showIf} is a UI hint
   * only, re-checked here.
   */
  public void cleanOrphanFiles(ActionRequest request, ActionResponse response) {
    if (!isAdmin()) {
      response.setError(I18n.get("You are not allowed to run the orphan file cleanup."));
      return;
    }
    try {
      OcrOrphanFileCleanupService.Result result =
          Beans.get(OcrOrphanFileCleanupService.class).cleanup();
      response.setInfo(formatCleanup(result));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Temporary, STRICTLY READ-ONLY listing of the orphan candidates {@link #cleanOrphanFiles} would
   * act on — see {@link OcrOrphanInventoryService}. Lets an admin inspect exactly what a cleanup
   * run would touch, on a hosted environment with no Azure/File Share access, before approving the
   * destructive action. Never deletes, moves or writes anything.
   */
  public void orphanInventory(ActionRequest request, ActionResponse response) {
    if (!isAdmin()) {
      response.setError(I18n.get("You are not allowed to view the orphan inventory."));
      return;
    }
    try {
      OcrOrphanInventoryService.Inventory inventory =
          Beans.get(OcrOrphanInventoryService.class).list();
      response.setInfo(formatInventory(inventory));
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Download exactly one proven-orphan MetaFile by id, entered by the admin in the "Download orphan
   * file" wizard. Accepts only an id from the browser, never a path, and never a posted entity: the
   * only value ever read is the dummy {@code $orphanMetaFileId} context key (see {@link
   * #parseOrphanMetaFileId(Map)}) — {@code request.getContext().asType(MetaFile.class)} (or
   * anything that would treat the posted context as a real entity) is never called here. {@link
   * OcrOrphanInventoryService#authorizeDownload(Long)} re-proves — right now, not from the earlier
   * inventory listing — that the file is still unreferenced and that its resolved path is a regular
   * file under the configured upload root; only then is the request handed to the platform's own
   * authenticated MetaFile download endpoint (the same one {@code binary-link} fields use), exactly
   * as {@code BgVatReturnController#downloadPackageFile} already does elsewhere in this app. This
   * method never reads or streams bytes itself, and never saves/removes anything.
   */
  public void downloadOrphanFile(ActionRequest request, ActionResponse response) {
    if (!isAdmin()) {
      response.setError(I18n.get("You are not allowed to download orphan files."));
      return;
    }
    Long metaFileId;
    try {
      metaFileId = parseOrphanMetaFileId(request.getContext());
    } catch (IllegalArgumentException e) {
      response.setError(I18n.get(e.getMessage()));
      return;
    }
    try {
      OcrOrphanInventoryService.DownloadAuthorization auth =
          Beans.get(OcrOrphanInventoryService.class).authorizeDownload(metaFileId);
      if (!auth.isAllowed()) {
        response.setError(I18n.get(auth.getReason()));
        return;
      }
      MetaFile metaFile = auth.getMetaFile();
      response.setView(
          ActionView.define(metaFile.getFileName())
              .add(
                  "html",
                  "ws/rest/com.axelor.meta.db.MetaFile/"
                      + metaFile.getId()
                      + "/content/download?v="
                      + metaFile.getVersion())
              .param("download", "true")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e, ResponseMessageType.ERROR);
    }
  }

  /**
   * Strictly parses the dummy {@code $orphanMetaFileId} wizard value — the "$" prefix marks it as a
   * context-only value with no corresponding MetaFile property (axelor-front strips every
   * "$"-prefixed key before any request body is ever serialized, so it can never round-trip as
   * posted record data either). Deliberately reads nothing else from {@code context}: an {@code id}
   * or any other key that might be present (e.g. from a stray posted entity) is never consulted, so
   * this is the one and only value the download action ever trusts from the browser.
   *
   * @throws IllegalArgumentException with a user-facing message when the value is missing, blank or
   *     not a whole number
   */
  protected Long parseOrphanMetaFileId(Map<String, Object> context) {
    Object raw = context == null ? null : context.get("$orphanMetaFileId");
    if (raw instanceof Number) {
      return ((Number) raw).longValue();
    }
    String s = raw == null ? "" : raw.toString().trim();
    if (s.isEmpty()) {
      throw new IllegalArgumentException(I18n.get("Enter a MetaFile id to download."));
    }
    try {
      return Long.valueOf(s);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(I18n.get("The MetaFile id must be a whole number."));
    }
  }

  /** Seam so a test can drive {@link #storageCheck} without a security context. */
  protected boolean isAdmin() {
    return AuthUtils.isAdmin(AuthUtils.getUser());
  }

  /** Plain-text report: counts and bytes only, no file name/path/id ever appears here. */
  protected String formatStorageCheck(Result r) {
    StringBuilder sb = new StringBuilder();
    sb.append(I18n.get("Scan state")).append(": ").append(r.getState());
    if (r.getReason() != null) {
      sb.append(" (").append(r.getReason()).append(")");
    }
    sb.append("\n\n");
    sb.append(I18n.get("Upload storage"))
        .append(": ")
        .append(countAndBytes(r.getUploadFileCount(), r.getUploadBytes()))
        .append("\n");
    sb.append(I18n.get("Filesystem free space"))
        .append(": ")
        .append(bytesOrUnknown(r.getFreeBytes()))
        .append("\n");
    sb.append(I18n.get("Configured minimum free-space reserve"))
        .append(": ")
        .append(human(r.getMinFreeReserveBytes()))
        .append("\n");
    sb.append(I18n.get("Archive extraction currently permitted"))
        .append(": ")
        .append(boolOrUnknown(r.getExtractionPermitted()))
        .append("\n");
    sb.append(I18n.get("Quarantine"))
        .append(": ")
        .append(countAndBytes(r.getQuarantineFileCount(), r.getQuarantineBytes()))
        .append("\n");
    sb.append(I18n.get("OCR temp area"))
        .append(": ")
        .append(I18n.get("not resolvable"))
        .append(" (")
        .append(r.getTempAreaUnavailableReason())
        .append(")\n");
    sb.append(I18n.get("Proven unreferenced MetaFile rows"))
        .append(": ")
        .append(countAndBytes(r.getUnreferencedCount(), r.getUnreferencedBytes()))
        .append("\n");
    sb.append(I18n.get("Physical blobs with no MetaFile row"))
        .append(": ")
        .append(countAndBytes(r.getStrayBlobCount(), r.getStrayBlobBytes()))
        .append("\n");
    sb.append(I18n.get("MetaFile rows whose blob is missing"))
        .append(": ")
        .append(countAndBytes(r.getMissingBlobCount(), r.getMissingBlobBytes()));
    return sb.toString();
  }

  /** Plain-text report: counts and bytes only, no file name/path/id ever appears here. */
  protected String formatCleanup(OcrOrphanFileCleanupService.Result r) {
    if (r.isAborted()) {
      return I18n.get("Cleanup aborted") + ": " + r.getReason();
    }
    StringBuilder sb = new StringBuilder();
    sb.append(I18n.get("Candidate rows")).append(": ").append(r.getCandidateCount()).append("\n");
    sb.append(I18n.get("Deleted MetaFile rows"))
        .append(": ")
        .append(r.getDeletedCount())
        .append("\n");
    sb.append(I18n.get("Physical blobs reclaimed"))
        .append(": ")
        .append(r.getBlobsReclaimedCount())
        .append("\n");
    sb.append(I18n.get("Reclaimed physical bytes"))
        .append(": ")
        .append(human(r.getBytesReclaimed()))
        .append("\n");
    sb.append(I18n.get("Skipped (referenced or unproven)"))
        .append(": ")
        .append(r.getSkippedCount())
        .append("\n");
    sb.append(I18n.get("Failures")).append(": ").append(r.getFailureCount()).append("\n\n");
    sb.append(I18n.get("Run Storage check again to confirm."));
    return sb.toString();
  }

  /**
   * Compact, one-block-per-candidate text report: id, file name, content type, created on/by,
   * recorded vs. actual size, blob existence and downloadability, and the RELATIVE filePath (never
   * an absolute server path, never database credentials, never a file outside this candidate set).
   */
  protected String formatInventory(OcrOrphanInventoryService.Inventory inventory) {
    if (!inventory.isAvailable()) {
      return I18n.get("Orphan inventory unavailable") + ": " + inventory.getReason();
    }
    List<OcrOrphanInventoryService.Candidate> candidates = inventory.getCandidates();
    StringBuilder sb = new StringBuilder();
    sb.append(I18n.get("Proven orphan candidates"))
        .append(": ")
        .append(candidates.size())
        .append("\n\n");
    for (OcrOrphanInventoryService.Candidate c : candidates) {
      sb.append("#")
          .append(c.getId())
          .append("  ")
          .append(c.getFileName() == null ? I18n.get("(no name)") : c.getFileName())
          .append("  [")
          .append(c.getFileType() == null ? I18n.get("unknown type") : c.getFileType())
          .append("]\n");
      sb.append("    ")
          .append(I18n.get("created"))
          .append(": ")
          .append(c.getCreatedOn() == null ? I18n.get("unknown") : c.getCreatedOn())
          .append(c.getCreatedBy() == null ? "" : " (" + c.getCreatedBy() + ")")
          .append("\n");
      sb.append("    ")
          .append(I18n.get("recorded size"))
          .append(": ")
          .append(c.getRecordedSize() == null ? I18n.get("unknown") : human(c.getRecordedSize()))
          .append("   ")
          .append(I18n.get("actual size"))
          .append(": ")
          .append(c.getActualSize() == null ? I18n.get("n/a") : human(c.getActualSize()))
          .append("\n");
      sb.append("    ")
          .append(I18n.get("blob exists"))
          .append(": ")
          .append(c.isBlobExists() ? I18n.get("Yes") : I18n.get("No"))
          .append("   ")
          .append(I18n.get("downloadable"))
          .append(": ")
          .append(c.isDownloadable() ? I18n.get("Yes") : I18n.get("No"))
          .append("\n");
      sb.append("    ")
          .append(I18n.get("path"))
          .append(": ")
          .append(c.getFilePath())
          .append("\n\n");
    }
    return sb.toString();
  }

  private static String countAndBytes(Long count, Long bytes) {
    if (count == null || bytes == null) {
      return I18n.get("Unknown");
    }
    return count + " (" + human(bytes) + ")";
  }

  private static String bytesOrUnknown(Long bytes) {
    return bytes == null ? I18n.get("Unknown") : human(bytes);
  }

  private static String boolOrUnknown(Boolean b) {
    return b == null ? I18n.get("Unknown") : (b ? I18n.get("Yes") : I18n.get("No"));
  }

  private static String human(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    String[] units = {"KB", "MB", "GB", "TB"};
    double value = bytes / 1024.0;
    int i = 0;
    while (value >= 1024 && i < units.length - 1) {
      value /= 1024.0;
      i++;
    }
    return String.format("%.1f %s (%d B)", value, units[i], bytes);
  }
}
