package com.axelor.apps.aiocr.service.audit;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.AiOcrUploadAudit;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadAuditManagementRepository;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.auth.db.User;
import com.google.inject.Singleton;
import java.util.Collection;
import javax.inject.Inject;

/**
 * Keeps the permanent {@code AiOcrUploadAudit} summary row for each {@code AiOcrUpload} up to date.
 * The only writer of that entity — see {@code AiOcrUploadAuditManagementRepository}'s write guard
 * (bound in {@code AiOcrModule}).
 *
 * <p>Called from exactly three places, so the audit-write logic itself lives in exactly one place
 * (here) rather than being duplicated across callers: {@code AiOcrUploadManagementRepository}'s
 * {@code save()}/{@code remove()}, and the handful of raw bulk-UPDATE call sites that bypass JPA
 * entity lifecycle (claim / enqueue / markFailed / resetStaleProcessing).
 *
 * <h2>Atomic with the OCR mutation, on purpose</h2>
 *
 * Every method here shares the caller's ambient transaction and lets any persistence exception
 * propagate — there is no try/catch anywhere in this class. That is deliberate, not an oversight: a
 * caught exception cannot undo a flush failure Hibernate has already recorded against the session,
 * so swallowing one here would not actually protect the caller's OCR mutation — it would only hide
 * the real error, at best trading it for a confusing failure at the next unrelated flush or at
 * commit time. Propagating instead makes the audit write and the OCR mutation it is attached to a
 * single all-or-nothing unit: both commit together, or a persistence problem in either one rolls
 * back both and surfaces a clear error immediately, at the call site where it happened.
 */
@Singleton
public class AiOcrUploadAuditService {

  private final AiOcrUploadRepository uploadRepo;
  private final AiOcrUploadAuditManagementRepository auditRepo;

  @Inject
  public AiOcrUploadAuditService(
      AiOcrUploadRepository uploadRepo, AiOcrUploadAuditManagementRepository auditRepo) {
    this.uploadRepo = uploadRepo;
    this.auditRepo = auditRepo;
  }

  /**
   * Upsert the audit row for this upload from its current in-memory state, in the caller's own
   * transaction. Any persistence failure propagates — see the class javadoc.
   */
  public void sync(AiOcrUpload upload) {
    if (upload == null || upload.getId() == null) {
      return;
    }
    AiOcrUploadAudit audit = auditRepo.findActive(upload.getId());
    if (audit == null) {
      audit = new AiOcrUploadAudit();
      audit.setUploadId(upload.getId());
      audit.setActiveUploadId(upload.getId());
    }
    applySnapshot(audit, upload);
    audit.setStatusSelect(upload.getStatusSelect());
    auditRepo.saveInternal(audit);
  }

  /**
   * For the raw bulk-UPDATE call sites that bypass JPA entity lifecycle: re-syncs each id the bulk
   * update actually affected, in the caller's own transaction. Pass only ids the update touched
   * (e.g. guard on {@code executeUpdate() > 0}). Any persistence failure propagates and, since this
   * shares the caller's transaction, rolls back the bulk update alongside it — see the class
   * javadoc.
   */
  public void syncByIds(Collection<Long> uploadIds) {
    if (uploadIds == null || uploadIds.isEmpty()) {
      return;
    }
    for (Long id : uploadIds) {
      if (id == null) {
        continue;
      }
      AiOcrUpload upload = uploadRepo.find(id);
      if (upload != null) {
        sync(upload);
      }
    }
  }

  /**
   * Marks the audit row DELETED (audit-only terminal status) and releases its active-uniqueness
   * slot, in the caller's own transaction. Shares the same transaction as the upload row's own
   * removal — either both commit, or a rollback leaves both the upload and its prior audit status
   * intact (see the class javadoc; this method has no special behaviour beyond it — every method
   * here is equally atomic with its caller).
   */
  public void markDeleted(AiOcrUpload upload) {
    if (upload == null || upload.getId() == null) {
      return;
    }
    AiOcrUploadAudit audit = auditRepo.findActive(upload.getId());
    if (audit == null) {
      audit = new AiOcrUploadAudit();
      audit.setUploadId(upload.getId());
    }
    applySnapshot(audit, upload);
    audit.setStatusSelect(AiOcrUploadAuditStatus.DELETED);
    // Release the active-uniqueness slot so a future upload reusing this numeric id can get its
    // own active row without colliding with this now-terminal one (see the domain's own comment
    // on activeUploadId).
    audit.setActiveUploadId(null);
    auditRepo.saveInternal(audit);
  }

  /** Scalar snapshot copy, shared by {@link #sync} and {@link #markDeleted}. */
  private void applySnapshot(AiOcrUploadAudit audit, AiOcrUpload upload) {
    audit.setFileName(upload.getFileName());
    User uploader = upload.getUploadedBy();
    audit.setUploaderUserId(uploader == null ? null : uploader.getId());
    audit.setUploaderDisplayName(uploader == null ? null : uploader.getName());
    Company company = upload.getCompany();
    audit.setCompanyId(company == null ? null : company.getId());
    audit.setCompanyName(company == null ? null : company.getName());
    audit.setUploadedOn(upload.getCreatedOn());
    audit.setProcessedOn(upload.getProcessedOn());
    audit.setRetryCount(upload.getRetryCount());
  }
}
