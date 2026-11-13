package com.axelor.apps.aiocr.service.audit;

/**
 * Single source of truth for {@code AiOcrUploadAudit.statusSelect} (selection
 * ai.ocr.upload.audit.status.select) codes, mirroring how {@code AiOcrUpload}'s own codes live in
 * {@code service.ocr.AiOcrUploadStatus}.
 */
public final class AiOcrUploadAuditStatus {

  /**
   * Audit-only terminal state: the {@code AiOcrUpload} row itself was permanently deleted. Distinct
   * from {@code AiOcrUploadStatus.DELETED_FROM_*}, which mean only the destination was removed
   * while the upload row is still alive and reprocessable.
   */
  public static final String DELETED = "deleted";

  private AiOcrUploadAuditStatus() {}
}
