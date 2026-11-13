package com.axelor.apps.aiocr.db.repo;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.audit.AiOcrUploadAuditService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import java.util.List;
import javax.persistence.FlushModeType;
import javax.persistence.PersistenceException;

/**
 * Deleting an upload from the <b>Upload Invoices</b> module is a FULL deletion: the upload (and its
 * ZIP-carrier children via {@code importArchive}), their unreferenced {@link
 * com.axelor.meta.db.MetaFile} rows + physical blobs (rollback-safe quarantine), AND the produced
 * supplier invoice — so a document disappears from both Upload Invoices and Suppl. Invoices in one
 * action. A VENTILATED invoice (ever) blocks the delete (tax compliance).
 *
 * <p>The orchestration lives in {@link OcrUploadFileCleanupService}; {@code super.remove} (the
 * plain carrier-safe row delete) is passed in as the row remover, so children are removed bottom-up
 * without re-entering this override. Removing the carrier drops the dedup marker, so the same ZIP
 * can be re-imported afterwards.
 *
 * <h2>Why {@code save} decides the company and the status itself</h2>
 *
 * The form used to default {@code company} from {@code __user__.activeCompany} in an {@code
 * action-record}. On a database where that is unset the record saved with {@code company = null} —
 * and the grid domain and every action guard filter on {@code self.company = :_activeCompany}, so
 * the row was created <em>unreachable</em>: invisible in the list and refused by its own buttons
 * with "No OCR record with id 1". A client-supplied company is a claim, not a fact, so it is now
 * ignored entirely and derived here instead.
 *
 * <p>Two rules are applied, to <b>a user's own upload only</b> (see {@link #isManualUpload} — an
 * insert, or an existing pristine draft of theirs, which is what a record created through the web
 * layer already is by the time this repository sees it):
 *
 * <ul>
 *   <li><b>company</b> — strictly {@code AuthUtils.getUser().getActiveCompany()}; null refuses the
 *       save. Deliberately NOT {@code AiCompanyScope}'s fallback ladder: that can resolve a company
 *       which is not the user's active one, and the row would then save and be invisible in the
 *       list. Refusing with a message that says what to configure beats an orphan row.
 *   <li><b>statusSelect</b> — forced to {@code uploaded}, a state the background dispatcher does
 *       not drain, so nothing is OCR'd until the user presses Process / Extract archive.
 * </ul>
 *
 * <p>The same reasoning applies to the rest of the entity: the form offers two file pickers and a
 * name, while every other column is written by the pipeline — yet an action or REST call posts a
 * whole record. {@link #clearServerOwnedFields} therefore blanks all of them on a manual insert, so
 * they are corrected rather than merely ignored. This is also why the predicate must never be built
 * from client-writable fields such as {@code importArchive} or {@code sourcePath}.
 */
public class AiOcrUploadManagementRepository extends AiOcrUploadRepository {

  @Override
  public AiOcrUpload save(AiOcrUpload entity) {
    if (entity == null) {
      return super.save(entity);
    }
    User caller = AuthUtils.getUser();
    if (isManualUpload(
        entity.getId(),
        caller != null,
        AiExecutionContext.isSystem(),
        hasCarrierMarkers(entity),
        hasStarted(entity),
        isOwnedByCaller(entity, caller))) {
      // Only the guards are wrapped, never super.save: an unrelated constraint violation must keep
      // its own message instead of being relabelled as a configuration problem.
      try {
        applyManualUploadRules(entity);
      } catch (AxelorException e) {
        TraceBackService.traceExceptionFromSaveMethod(e);
        throw new PersistenceException(e.getMessage(), e);
      }
    }
    if (caller != null && !AiExecutionContext.isSystem() && !AuthUtils.isAdmin(caller)) {
      requireOwnFiles(entity, caller);
    }
    inheritUploaderFromCarrier(entity);
    fillBlankFileName(entity);
    AiOcrUpload saved = super.save(entity);
    // Keeps the permanent Upload Audit trail (Accounting -> Upload Audit) up to date, atomically
    // with this save: a persistence failure in the sync rolls both back together instead of being
    // swallowed (see AiOcrUploadAuditService's own class javadoc, including the few call sites
    // that don't funnel through here).
    Beans.get(AiOcrUploadAuditService.class).sync(saved);
    return saved;
  }

  /**
   * A record that belongs to a carrier is owned by whoever uploaded that carrier — always, at every
   * nesting depth, on every creation path.
   *
   * <p>{@code uploadedBy} is what the <b>Upload Invoices</b> list scopes on, and it is the only
   * ownership marker that can span the pipeline: {@code createdBy} is stamped by Axelor's auditing
   * from the current subject, and the extract/split workers run with no authenticated subject, so a
   * pipeline-created row has none. The extractor and the splitter already copy the carrier's
   * uploader onto each child they create ({@code AiOcrArchiveExtractService.newChild}, {@code
   * AiOcrPdfSplitService.upsertSplitChild}) — including duplicate, failed and unsupported entries,
   * which go through the same factory. This makes that an <em>invariant</em> rather than a
   * convention held up by three call sites:
   *
   * <ul>
   *   <li>it closes the depth problem at the root. Ownership is re-anchored to the parent on every
   *       save, so a grandchild is owned by its carrier, which is owned by ITS carrier, up to the
   *       manual upload — no fixed-depth ancestor walk in the view domain, and no depth at which
   *       the list silently loses records;
   *   <li>it makes a client-posted {@code uploadedBy} unusable on any row the pipeline owns. Such a
   *       row is excluded from {@link #isManualUpload} by its carrier markers, so {@link
   *       #applyManualUploadRules} never runs on it and would not correct the field; here it is
   *       overwritten with the parent's uploader whatever the caller sent. On a manual upload the
   *       field is already derived server-side from {@code AuthUtils.getUser()};
   *   <li>system-created records keep their intended visibility. A folder-monitor import has no
   *       carrier and no uploader, so it is untouched here and stays out of the client list exactly
   *       as before — and so do its own split children, because the splitter copies that empty
   *       uploader onto them.
   * </ul>
   *
   * <p><b>It only ever writes an uploader, never erases one.</b> A carrier with no uploader of its
   * own has no ownership to hand down, and blanking the child's would destroy information rather
   * than propagate it — it would also un-own a row that legitimately has an uploader and drop it
   * out of its owner's list. {@code uploadedBy} is a structural field on a retry/reprocess (see
   * {@code BgOcrRetryReprocessFieldPreservationIntegrationTest}), and this runs on every save, so
   * an overwrite here would silently clear it on any save of such a row. The anti-adoption property
   * is unaffected where it matters: a descendant of somebody else's manual upload has a carrier
   * WITH an uploader, and that uploader wins over anything a caller posts.
   */
  protected void inheritUploaderFromCarrier(AiOcrUpload entity) {
    AiOcrUpload carrier = entity.getImportArchive();
    if (carrier == null) {
      return; // a root record: its uploader is its own (manual) or absent (folder monitor)
    }
    User owner = carrier.getUploadedBy();
    if (owner == null) {
      return; // nothing to hand down; never blank what the row already has
    }
    if (!sameUser(entity.getUploadedBy(), owner)) {
      entity.setUploadedBy(owner);
    }
  }

  /** Identity by id, null-safe; two unsaved users are only "the same" when they are one object. */
  static boolean sameUser(User a, User b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null || a.getId() == null || b.getId() == null) {
      return false;
    }
    return a.getId().equals(b.getId());
  }

  @Override
  public void remove(AiOcrUpload entity) {
    if (entity == null) {
      super.remove(entity);
      return;
    }
    Beans.get(OcrUploadFileCleanupService.class)
        .fullyDeleteUploads(
            entity,
            upload -> {
              // Shares this transaction with the row's own removal (bottom-up, once per tree
              // node): a failure here rolls back the removal too, leaving both the upload and its
              // prior audit status intact — see AiOcrUploadAuditService's class javadoc.
              Beans.get(AiOcrUploadAuditService.class).markDeleted(upload);
              super.remove(upload);
            });
  }

  // ---------------------------------------------------------------------------
  // The predicate — pure, so it can be unit-tested without Guice/Shiro/JPA
  // ---------------------------------------------------------------------------

  /**
   * Whether this save is a user's own upload, and therefore has to be given its company, its
   * uploader and the {@code uploaded} status.
   *
   * <h3>Why an id alone cannot answer it</h3>
   *
   * This used to be {@code id == null}, on the assumption that the repository sees a new record
   * before it is persisted. It does not. {@code Resource.save} calls {@code JPA.manage(bean)} —
   * which persists <em>and flushes</em> (auto-flush is on) — and only then calls {@code
   * repository.save}. The id is generated by a sequence at persist time, so <b>every</b> record
   * created through the web layer already carries one here. The guard therefore never fired from
   * the UI: the row was inserted with {@code company = null} and the domain-default status, which
   * the grid hides ({@code self.company = :_activeCompany}) and the dispatcher drains.
   *
   * <h3>The two branches</h3>
   *
   * <ul>
   *   <li><b>{@code id == null}</b> — a genuine insert (a direct repository call, an import, a REST
   *       path that does not go through {@code JPA.manage}). Exactly the old rule, deliberately
   *       trusting <em>no</em> field on the row: {@code importArchive} / {@code extractionKey} /
   *       {@code sourcePath} / {@code processedOn} are ordinary writable columns, so a crafted POST
   *       must not be able to dodge the company and status assignment by setting one. The
   *       legitimate {@code new AiOcrUpload()} sites declare their trust with {@link
   *       AiExecutionContext#runAsSystem} instead.
   *   <li><b>an existing row</b> — in scope only while it is still a pristine draft of the caller's
   *       own: no carrier provenance, nothing started, and belonging to nobody else. Those three
   *       conditions are what keep the pipeline's ~34 update call sites out (they all run either as
   *       system, on an unauthenticated worker thread, or on a row that has already run), keep the
   *       archive/split/folder-monitor children untouched, and make adopting somebody else's record
   *       impossible. On an existing row the provenance markers <em>are</em> trustworthy for
   *       exclusion: skipping the rules can only ever leave a row alone, never move it to another
   *       company.
   * </ul>
   */
  static boolean isManualUpload(
      Long id,
      boolean hasAuthenticatedCaller,
      boolean systemContext,
      boolean carrierMarkers,
      boolean started,
      boolean ownedByCaller) {
    if (!hasAuthenticatedCaller || systemContext) {
      return false;
    }
    if (id == null) {
      return true; // a real insert: nothing on the row is trusted, everything is corrected
    }
    return !carrierMarkers && !started && ownedByCaller;
  }

  /**
   * Provenance written by the pipeline when it creates a child or a carrier segment. Read only for
   * an already-persisted row (see {@link #isManualUpload}), where it identifies a record the
   * extract/split/folder-monitor paths own and this guard must not rewrite.
   */
  protected boolean hasCarrierMarkers(AiOcrUpload entity) {
    return entity.getImportArchive() != null
        || entity.getExtractionKey() != null
        || entity.getSourcePath() != null
        || entity.getCarrierKind() != null
        || entity.getSegmentType() != null;
  }

  /**
   * Whether anything has happened to this upload yet. A started or finished row keeps its status
   * and its results: the repair below is for a draft that has never run, never for a record the
   * pipeline is working on or has completed.
   */
  protected boolean hasStarted(AiOcrUpload entity) {
    String status = entity.getStatusSelect();
    boolean pristine =
        status == null
            || AiOcrUploadStatus.UPLOADED.equals(status)
            || AiOcrUploadStatus.QUEUED.equals(status);
    return !pristine
        || entity.getLockedOn() != null
        || entity.getExtractionStartedOn() != null
        || entity.getExtractionFinishedOn() != null
        || entity.getProcessedOn() != null
        || entity.getInvoice() != null
        || entity.getMove() != null;
  }

  /**
   * Whether this row is the caller's to repair: filed under no company or under their active one,
   * and uploaded/created by nobody else. A record belonging to another company or another user is
   * left exactly as it is — repairing it would be adoption, and stamping the caller's company on it
   * would move somebody else's document.
   */
  protected boolean isOwnedByCaller(AiOcrUpload entity, User caller) {
    if (caller == null) {
      return false;
    }
    Company company = entity.getCompany();
    Company active = caller.getActiveCompany();
    if (company != null
        && (active == null || active.getId() == null || !active.getId().equals(company.getId()))) {
      return false;
    }
    return isSameOrAbsent(entity.getUploadedBy(), caller)
        && isSameOrAbsent(entity.getCreatedBy(), caller);
  }

  protected static boolean isSameOrAbsent(User user, User caller) {
    return user == null || user.getId() == null || user.getId().equals(caller.getId());
  }

  /**
   * An upload holds exactly one input: a document OR a ZIP archive. Holding both makes it doubly
   * runnable (OCR'd as a document <em>and</em> offered for extraction), which is what produced the
   * two-file record in the original report.
   */
  static boolean holdsTwoFiles(boolean hasDocument, boolean hasArchive) {
    return hasDocument && hasArchive;
  }

  // ---------------------------------------------------------------------------

  /**
   * G7: a file a user attaches must be one they uploaded. Otherwise any user can point their upload
   * at another company's MetaFile id and have it OCR'd onto their own invoice. Only a file that is
   * new or changed on this save is checked, so a reviewer can still edit someone else's upload
   * without touching its files.
   */
  protected void requireOwnFiles(AiOcrUpload entity, User caller) {
    Long[] stored = storedFileIds(entity.getId());
    checkOwnFile(entity.getOriginalFile(), stored[0], caller);
    checkOwnFile(entity.getArchiveFile(), stored[1], caller);
  }

  /** Whether this file reference must be ownership-checked: set, and not what was stored. */
  static boolean isNewFileReference(Long fileId, Long storedFileId) {
    return fileId != null && !fileId.equals(storedFileId);
  }

  private static void checkOwnFile(MetaFile file, Long storedFileId, User caller) {
    if (file == null || !isNewFileReference(file.getId(), storedFileId)) {
      return;
    }
    User owner = file.getCreatedBy();
    if (owner == null || !owner.getId().equals(caller.getId())) {
      throw new PersistenceException("You can only attach files you uploaded yourself.");
    }
  }

  /** The stored originalFile / archiveFile ids, read without flushing the pending changes. */
  private static Long[] storedFileIds(Long uploadId) {
    if (uploadId == null) {
      return new Long[] {null, null};
    }
    List<?> rows =
        JPA.em()
            .createQuery(
                "SELECT o.id, a.id FROM AiOcrUpload u LEFT JOIN u.originalFile o"
                    + " LEFT JOIN u.archiveFile a WHERE u.id = :id")
            .setParameter("id", uploadId)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (rows.isEmpty()) {
      return new Long[] {null, null};
    }
    Object[] row = (Object[]) rows.get(0);
    return new Long[] {(Long) row[0], (Long) row[1]};
  }

  protected void applyManualUploadRules(AiOcrUpload entity) throws AxelorException {
    if (holdsTwoFiles(entity.getOriginalFile() != null, entity.getArchiveFile() != null)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(
              "An upload holds either one document or one .zip archive, never both. Remove one of"
                  + " the two files with the × button, or use a separate record."));
    }
    entity.setCompany(requireActiveCompany());
    entity.setStatusSelect(AiOcrUploadStatus.UPLOADED);
    // Provenance, like the company: who uploaded it is a fact about the caller, not a claim.
    entity.setUploadedBy(AuthUtils.getUser());
    clearServerOwnedFields(entity);
  }

  /**
   * Blanks every field the pipeline owns, on a user's own upload only.
   *
   * <p>The form exposes two file pickers and a name; everything else on this entity is written by
   * the OCR pipeline. But an action or REST call posts a whole record, so any of these columns can
   * be set on creation — and several of them are load-bearing rather than cosmetic:
   *
   * <ul>
   *   <li><b>provenance / carrier links</b> ({@code importArchive}, {@code extractionKey}, {@code
   *       sourcePath}, {@code carrierKind}, {@code segmentType}) graft a row into somebody else's
   *       carrier or make it claim to be a split child. {@code (importArchive, extractionKey)} is a
   *       unique index the idempotent re-extraction depends on, so a squatted pair also breaks
   *       crash recovery for that ZIP;
   *   <li><b>generated links</b> ({@code invoice}, {@code move}) would attach an accounting
   *       document the upload never produced, and the delete path treats them as its own output;
   *   <li><b>dedup keys</b> ({@code fileHash}, {@code docKey}) decide what a byte-identical upload
   *       considers already-imported — a planted hash silently suppresses a later real import;
   *   <li><b>lifecycle / extraction state</b> ({@code lockedOn}, {@code extractionAttemptId},
   *       {@code extractionStartedOn}/{@code FinishedOn}, {@code progressPercent}, {@code
   *       retryCount}) drive the claims, the stale-processing reset and both buttons' {@code
   *       showIf} — a posted {@code extractionStartedOn} alone hides <i>Extract archive</i> for
   *       good;
   *   <li><b>result / audit</b> ({@code confidence}, {@code engineUsed}, {@code processedOn},
   *       {@code blobUrl}, the three OCR JSON columns, the error/information notes, the size
   *       counters) are evidence of a run that has not happened yet, and {@code cachedOcrJson} /
   *       {@code splitSourceJson} are re-parsed by {@code process()} instead of calling Azure — so
   *       a crafted payload would be read back as if it were Azure's own answer;
   *   <li><b>attachments</b> ({@code stockReceiptFileSet}, {@code posReceiptFile}) are slices the
   *       splitter produces and attaches itself.
   * </ul>
   *
   * <p>Nothing here touches the pipeline's own creations: {@code AiOcrArchiveExtractService} and
   * {@code AiOcrPdfSplitService} both insert inside {@link AiExecutionContext#runAsSystem}, so
   * {@link #isManualUpload} is false for them and they keep setting these fields exactly as before
   * — and on an existing row their provenance markers exclude it outright. A record that has
   * started or finished is out of scope as well, so it keeps everything the pipeline wrote onto it.
   */
  protected void clearServerOwnedFields(AiOcrUpload entity) {
    // Provenance and carrier membership.
    entity.setImportArchive(null);
    entity.setExtractionKey(null);
    entity.setSourcePath(null);
    entity.setCarrierKind(null);
    entity.setSegmentType(null);
    // Documents the pipeline produces.
    entity.setInvoice(null);
    entity.setMove(null);
    // Deduplication keys.
    entity.setFileHash(null);
    entity.setDocKey(null);
    // Lifecycle and extraction state.
    entity.setLockedOn(null);
    entity.setRetryCount(0);
    entity.setProgressPercent(null);
    entity.setExtractionAttemptId(null);
    entity.setExtractionStartedOn(null);
    entity.setExtractionFinishedOn(null);
    // Extraction result and audit trail.
    entity.setProcessedOn(null);
    entity.setConfidence(null);
    entity.setEngineUsed(null);
    entity.setBlobUrl(null);
    entity.setExtractionErrors(null);
    entity.setExtractionInformation(null);
    entity.setRawExtractedJson(null);
    entity.setOcrResultJson(null);
    entity.setSplitSourceJson(null);
    entity.setCachedOcrJson(null);
    entity.setOriginalSizeBytes(null);
    entity.setOptimizedSizeBytes(null);
    // Slices the splitter attaches to a child.
    entity.setPosReceiptFile(null);
    entity.clearStockReceiptFileSet();
  }

  /**
   * The one company a manual upload may be filed under. No sole-company fallback: the grid domain
   * and the action guards both test the user's <em>active</em> company, so anything else would save
   * a row the uploader cannot see.
   */
  protected Company requireActiveCompany() throws AxelorException {
    User user = AuthUtils.getUser();
    Company active = user == null ? null : user.getActiveCompany();
    if (active != null) {
      return active;
    }
    // "Preferences → Active company" is a dead end on an empty database, so say which of the two
    // situations this is.
    if (companyCount() == 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(
              "No company is configured yet, so this upload cannot be filed anywhere. Create one in"
                  + " Application config → Users/Companies → Companies, then select it in"
                  + " Preferences → Active company."));
    }
    throw new AxelorException(
        TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
        I18n.get(
            "No active company is selected for your user, so this upload cannot be filed. Choose"
                + " one in Preferences → Active company and try again."));
  }

  protected long companyCount() {
    try {
      return Beans.get(CompanyRepository.class).all().count();
    } catch (RuntimeException e) {
      // No injector / no persistence here: treat as "cannot tell", and use the generic message.
      return -1;
    }
  }

  /** Keeps the list readable whatever creation path produced the row. */
  protected void fillBlankFileName(AiOcrUpload entity) {
    String name = entity.getFileName();
    if (name != null && !name.trim().isEmpty()) {
      return;
    }
    if (entity.getOriginalFile() != null) {
      entity.setFileName(entity.getOriginalFile().getFileName());
    } else if (entity.getArchiveFile() != null) {
      entity.setFileName(entity.getArchiveFile().getFileName());
    }
  }
}
