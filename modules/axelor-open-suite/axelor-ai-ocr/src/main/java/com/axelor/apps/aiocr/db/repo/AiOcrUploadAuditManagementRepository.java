package com.axelor.apps.aiocr.db.repo;

import com.axelor.apps.aiocr.db.AiOcrUploadAudit;
import javax.persistence.PersistenceException;

/**
 * Write guard for the permanent {@code AiOcrUploadAudit} trail: {@link AiOcrUploadAuditService}
 * (via {@link #saveInternal}) is the only sanctioned writer, and rows are never deleted at all.
 *
 * <p>This exists because Axelor's Permission model is additive: the module's existing unconditioned
 * wildcard {@code perm.aiocr.all} (package {@code com.axelor.apps.aiocr.db.*}, role {@code Admin})
 * already covers this new entity, and a narrower Permission row cannot reduce what that wildcard
 * grants. Without this guard a technical Admin-role direct API call could still write to an entity
 * whose UI (grid/form) exposes no save/delete action at all.
 */
public class AiOcrUploadAuditManagementRepository extends AiOcrUploadAuditRepository {

  private static final ThreadLocal<Boolean> INTERNAL_WRITE = ThreadLocal.withInitial(() -> false);

  /** The only sanctioned way to persist an audit row. */
  public AiOcrUploadAudit saveInternal(AiOcrUploadAudit entity) {
    INTERNAL_WRITE.set(true);
    try {
      return save(entity);
    } finally {
      INTERNAL_WRITE.remove();
    }
  }

  @Override
  public AiOcrUploadAudit save(AiOcrUploadAudit entity) {
    if (!Boolean.TRUE.equals(INTERNAL_WRITE.get())) {
      throw new PersistenceException(
          "AiOcrUploadAudit is a system-managed audit trail; direct writes are not allowed.");
    }
    return super.save(entity);
  }

  @Override
  public void remove(AiOcrUploadAudit entity) {
    // No retention/deletion policy exists yet (see the plan) — audit rows are permanent once
    // written, so removal is refused unconditionally, including internally.
    throw new PersistenceException("AiOcrUploadAudit rows cannot be deleted.");
  }

  /**
   * The active (non-terminal) audit row for this upload id, or {@code null}. Looks up {@code
   * activeUploadId} — a column the database itself enforces as unique (nullable, so any number of
   * historical DELETED rows can hold {@code null} there) — never {@code uploadId}, so the match is
   * always deterministic by construction rather than "most recent of possibly several". A recycled
   * upload id (after a sequence reset) is safe for the same reason: once the prior row for that id
   * is marked DELETED, its {@code activeUploadId} is cleared, so a fresh active row for the reused
   * id never conflicts.
   */
  public AiOcrUploadAudit findActive(Long uploadId) {
    if (uploadId == null) {
      return null;
    }
    return all().filter("self.activeUploadId = :uploadId").bind("uploadId", uploadId).fetchOne();
  }
}
