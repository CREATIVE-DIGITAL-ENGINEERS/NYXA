package com.axelor.apps.aiocr.db.repo;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.budget.db.repo.MoveBudgetManagementRepository;
import com.axelor.inject.Beans;

/**
 * Extends the tail of the Move repository binding chain ({@code MoveRepository → … →
 * MoveBudgetManagementRepository}) so deleting an OCR-produced Move directly from the Moves module
 * first clears every {@link com.axelor.apps.aiocr.db.AiOcrUpload#getMove()} reference (otherwise
 * the stock {@code checkMoveBeforeRemove} refuses it: "linked to another piece named Uploaded
 * invoice").
 *
 * <p>The upload row and its source file are preserved and flagged {@code deletedFromMoves} so the
 * client can reprocess. All stock protections are kept: {@code super.remove} still refuses DAYBOOK
 * / ACCOUNTED moves. Unlink + delete run in the caller's single transaction, so a refused/failed
 * delete rolls the unlink back.
 */
public class OcrMoveRepository extends MoveBudgetManagementRepository {

  @Override
  public void remove(Move entity) {
    if (entity != null) {
      Beans.get(OcrUploadFileCleanupService.class).unlinkMoveUploads(entity);
    }
    super.remove(entity); // stock guard: only NEW/SIMULATED removable; checkMoveBeforeRemove
  }
}
