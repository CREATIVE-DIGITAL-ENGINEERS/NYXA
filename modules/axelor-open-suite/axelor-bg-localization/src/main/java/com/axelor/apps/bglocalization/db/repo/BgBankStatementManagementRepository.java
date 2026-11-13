package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.BankStatementLine;
import com.axelor.apps.bankpayment.db.repo.BankStatementLineRepository;
import com.axelor.apps.bankpayment.db.repo.BankStatementManagementRepository;
import com.axelor.apps.bankpayment.service.bankstatementline.BankStatementLineDeleteService;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tail override of the stock {@link BankStatementRepository} that wires the delete side of the
 * OCR-generated bank-statement lifecycle (mirrors {@code OcrInvoiceRepository}/{@code
 * OcrMoveRepository} for invoices/moves, but lives here because {@code BankStatement} is a
 * bank-payment type an ai-ocr repo cannot reference).
 *
 * <p>When a statement generated from an AI-OCR upload is deleted, the upload row + its source PDF
 * are KEPT and the upload flips to {@code deletedFromBankStatements} so the user can reprocess it
 * into a fresh statement (never an invoice/move). Deleting the upload itself goes the other way:
 * {@code OcrBankStatementCreatorImpl.deleteForUpload} clears the link first, so this override then
 * removes the statement without touching the (about-to-be-deleted) upload. Child lines are removed
 * first for FK safety, and the statement's private PDF copy is dropped (never the upload's
 * original).
 */
public class BgBankStatementManagementRepository extends BankStatementManagementRepository {

  private static final Logger LOG =
      LoggerFactory.getLogger(BgBankStatementManagementRepository.class);

  @Inject
  public BgBankStatementManagementRepository(
      BankStatementLineDeleteService bankStatementLineDeleteService) {
    super(bankStatementLineDeleteService);
  }

  @Override
  public void remove(BankStatement entity) {
    if (entity == null) {
      super.remove(entity);
      return;
    }
    // FK safety: remove the statement's lines before the statement row.
    deleteLines(entity);

    // Reverse lifecycle: a user-deleted generated statement keeps its upload, marked reprocessable.
    AiOcrUpload upload = entity.getAiOcrUpload();
    if (upload != null) {
      entity.setAiOcrUpload(null);
      upload.setStatusSelect(AiOcrUploadStatus.DELETED_FROM_BANK_STATEMENTS);
      Beans.get(AiOcrUploadRepository.class).save(upload);
    }

    MetaFile file = entity.getBankStatementFile();
    entity.setBankStatementFile(null); // drop THIS reference before asking whether any remains
    super.remove(entity);

    // Release the statement's PDF. It is normally the SAME MetaFile as the upload's originalFile
    // (shared, not copied), so removing the row outright would queue a delete that fails on the
    // upload's foreign key at commit — outside this try/catch — poisoning the whole delete. The
    // reference-aware release keeps the file while the upload (or anything else) still holds it,
    // and quarantines it post-commit only when nothing does. Either way the upload keeps its
    // source PDF in Upload Invoices for reprocess.
    if (file != null) {
      try {
        Beans.get(OcrUploadFileCleanupService.class).releaseFileIfUnreferenced(file);
      } catch (Exception e) {
        LOG.warn("Could not release the bank statement PDF #{}: {}", file.getId(), e.getMessage());
      }
    }
  }

  protected void deleteLines(BankStatement statement) {
    BankStatementLineRepository lineRepo = Beans.get(BankStatementLineRepository.class);
    for (BankStatementLine line :
        lineRepo.all().filter("self.bankStatement = :s").bind("s", statement).fetch()) {
      lineRepo.remove(line);
    }
  }
}
