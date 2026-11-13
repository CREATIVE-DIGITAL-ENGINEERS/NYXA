package com.axelor.apps.bglocalization.service.bankstatement;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.bankstatement.OcrBankStatementCreator;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.repo.BankStatementRepository;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgParsedStatement;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * axelor-bg-localization implementation of the {@link OcrBankStatementCreator} extension point.
 * Uses the carrier/child model: {@link #detectStatementRanges} reports the per-statement page
 * ranges (a multi-statement PDF then becomes a carrier that spawns one child per statement in
 * ai-ocr), and {@link #createForOwner} creates exactly ONE {@link BankStatement} for a given owner
 * upload (a single-statement upload, or one split child) — attaching only that owner's own file,
 * linking the statement to the owner, and persisting through the <b>shared</b> {@link
 * BgBankStatementImportService#persistMovements} step (real lines, dedup, running-balance
 * validation, BG status). Incomplete OCR or a balance mismatch still creates the statement, marked
 * "manual correction required" — never a Supplier Invoice or a Move.
 *
 * <p><b>Idempotent by existence:</b> if a statement already links to the owner it is returned
 * unchanged (never recreated), so ordinary retries never destroy a record or manual edits. {@link
 * #deleteForUpload} is used only by the upload's delete cascade.
 */
@Singleton
public class OcrBankStatementCreatorImpl implements OcrBankStatementCreator {

  private static final Logger LOG = LoggerFactory.getLogger(OcrBankStatementCreatorImpl.class);

  protected final BankStatementRepository bankStatementRepo;

  @Inject
  public OcrBankStatementCreatorImpl(BankStatementRepository bankStatementRepo) {
    this.bankStatementRepo = bankStatementRepo;
  }

  @Override
  public List<int[]> detectStatementRanges(String analyzeResultJson) {
    return new BgBankStatementOcrSegmenter().segment(analyzeResultJson);
  }

  @Override
  @Transactional
  public Long createForOwner(
      AiOcrUpload owner, String analyzeResultJson, int fromPage, int toPage) {
    if (owner == null) {
      return null;
    }
    // Idempotent: never recreate/overwrite a statement that already belongs to this owner.
    BankStatement existing =
        bankStatementRepo.all().filter("self.aiOcrUpload = :u").bind("u", owner).fetchOne();
    if (existing != null) {
      return existing.getId();
    }

    BgParsedStatement parsed =
        new BgBankStatementOcrParser().parse(analyzeResultJson, fromPage, toPage);

    BankStatement statement = new BankStatement();
    String fileName = owner.getFileName();
    statement.setName(
        I18n.get("Bank statement (OCR)")
            + (fileName == null || fileName.isBlank() ? "" : " — " + fileName));
    statement.setStatusSelect(BankStatementRepository.STATUS_RECEIVED);
    if (parsed.periodFrom != null) {
      statement.setFromDate(parsed.periodFrom);
    }
    if (parsed.periodTo != null) {
      statement.setToDate(parsed.periodTo);
    }
    statement.setAiOcrUpload(owner);
    // The upload's company, carried onto the statement. Intake runs on a pool thread with no user
    // and often no AI scope, so the repository's active-company default cannot resolve one here —
    // and the upload already knows whose document this is.
    statement.setCompany(owner.getCompany());

    // Attach an INDEPENDENT copy of the owner's OWN file: the whole PDF for a single-statement
    // upload, or exactly this statement's slice for a split child (never the carrier's full PDF).
    attachOwnerFile(statement, owner);

    statement = bankStatementRepo.save(statement);
    Beans.get(BgBankStatementImportService.class).persistMovements(statement, parsed);
    return statement.getId();
  }

  /**
   * The statement linked to this upload, if any. "Replaceable" means it is still in the RECEIVED
   * state the OCR path creates it in — an IMPORTED (or otherwise advanced) statement has become
   * accounting data and a manual reroute must refuse to replace it.
   */
  @Override
  public StatementRef findForUpload(AiOcrUpload upload) {
    if (upload == null) {
      return null;
    }
    BankStatement statement =
        bankStatementRepo.all().filter("self.aiOcrUpload = :u").bind("u", upload).fetchOne();
    if (statement == null) {
      return null;
    }
    boolean replaceable =
        Integer.valueOf(BankStatementRepository.STATUS_RECEIVED)
            .equals(statement.getStatusSelect());
    Long companyId = statement.getCompany() == null ? null : statement.getCompany().getId();
    return new StatementRef(statement.getId(), statement.getName(), replaceable, companyId);
  }

  @Override
  @Transactional
  public int deleteForUpload(AiOcrUpload upload) {
    if (upload == null) {
      return 0;
    }
    List<BankStatement> statements =
        bankStatementRepo.all().filter("self.aiOcrUpload = :u").bind("u", upload).fetch();
    for (BankStatement st : statements) {
      // Clear the link BEFORE removing so the repository override does not flip the upload status
      // (the upload is being reprocessed or deleted, not losing a statement to a manual delete).
      MetaFile file = st.getBankStatementFile();
      st.setAiOcrUpload(null);
      st.setBankStatementFile(null); // drop THIS reference before asking whether any remains
      bankStatementRepo.remove(st); // override removes the child lines
      releaseStatementFile(file);
    }
    return statements.size();
  }

  /**
   * Release the statement's file now that the statement no longer points at it.
   *
   * <p>The file is normally <b>shared with the upload</b> (see {@link #attachOwnerFile}), so it
   * must NOT simply be removed: {@code MetaFileRepository.remove} would queue a delete that fails
   * on the upload's foreign key only at commit — outside any try/catch here — poisoning the whole
   * delete transaction. Instead the reference-aware release keeps the file whenever anything still
   * holds it, and quarantines it (post-commit, rollback-safe) only when nothing does.
   */
  protected void releaseStatementFile(MetaFile file) {
    if (file == null) {
      return;
    }
    try {
      Beans.get(OcrUploadFileCleanupService.class).releaseFileIfUnreferenced(file);
    } catch (Exception e) {
      LOG.warn(
          "Could not release the bank statement file #{} after deleting the statement: {}",
          file.getId(),
          e.getMessage());
    }
  }

  /**
   * Point the statement at the owner upload's own file.
   *
   * <p><b>The same {@link MetaFile} is shared, not copied.</b> {@code bankStatementFile} is a plain
   * foreign key, so it creates no {@code DMSFile}/{@code MetaAttachment} and cannot clash with the
   * upload's own reference — both records simply hold the one file whose bytes they both need (the
   * whole PDF for a single-statement upload, or exactly this statement's slice for a split child).
   *
   * <p>This used to copy the blob because the platform's reference count does not know about {@code
   * AiOcrUpload.originalFile}. {@code OcrMetaFiles} now counts every mapped relation — including
   * {@code BankStatement.bankStatementFile} — and retains a file a business record still holds, so
   * the copy is no longer needed. Both delete paths were updated to match.
   */
  protected void attachOwnerFile(BankStatement statement, AiOcrUpload owner) {
    MetaFile source = owner.getOriginalFile();
    if (source == null) {
      return;
    }
    Path srcPath = MetaFiles.getPath(source);
    if (srcPath == null || !Files.exists(srcPath)) {
      return; // nothing to show — never point the statement at a missing blob
    }
    statement.setBankStatementFile(source);
  }
}
