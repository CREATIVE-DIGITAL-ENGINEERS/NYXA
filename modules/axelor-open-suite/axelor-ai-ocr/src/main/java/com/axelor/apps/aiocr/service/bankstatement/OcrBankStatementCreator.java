package com.axelor.apps.aiocr.service.bankstatement;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import java.util.List;

/**
 * Extension point that turns an OCR'd PDF <b>bank statement</b> into reviewable Bank Statement
 * records, so the generic {@code axelor-ai-ocr} intake can route them without depending on {@code
 * axelor-bank-payment}. The real implementation lives in {@code axelor-bg-localization} (which owns
 * the BG Bank Statement fields, the shared movement→line persist and the upload link) and is bound
 * over the default no-op in its Guice module.
 *
 * <p>A single uploaded PDF may hold <b>several</b> statements. The intake uses the existing
 * carrier/child model: it asks {@link #detectStatementRanges} how many statements the PDF holds; a
 * multi-statement PDF becomes a carrier that spawns one child {@link AiOcrUpload} per statement
 * (each child holding only that statement's page slice), and each child creates exactly one
 * BankStatement via {@link #createForOwner}. A single-statement PDF stays one upload that creates
 * one statement. Deleting the carrier/child cascades through {@link #deleteForUpload}.
 */
public interface OcrBankStatementCreator {

  /**
   * The per-statement page ranges (1-based, inclusive) detected in the OCR result — one entry per
   * statement. Size ≤ 1 means a single statement (no split). Empty when nothing could be read.
   */
  List<int[]> detectStatementRanges(String analyzeResultJson);

  /**
   * Create exactly ONE reviewable Bank Statement for {@code owner} (a single-statement upload, or a
   * per-statement split child) from the pages {@code [fromPage..toPage]} of {@code
   * analyzeResultJson}, attaching {@code owner}'s own file and linking the statement to {@code
   * owner}. <b>Idempotent</b>: if a statement already links to {@code owner} it is returned as-is
   * (never recreated / overwritten), so ordinary retries never destroy a record or manual edits.
   * Returns the statement id, or {@code null} when it could not be created.
   */
  Long createForOwner(AiOcrUpload owner, String analyzeResultJson, int fromPage, int toPage);

  /**
   * Delete the Bank Statement(s) linked to exactly {@code upload} (a single-statement upload or one
   * split child) — used by the upload's delete cascade and by a manual reroute AWAY from Bank
   * Statement (which removes the replaced statement only after the new destination succeeded);
   * never as a retry idempotency strategy. Returns the number deleted.
   */
  int deleteForUpload(AiOcrUpload upload);

  /**
   * What (if anything) this upload currently holds on the Bank Statement side, so a caller outside
   * {@code axelor-bank-payment} can name it and decide whether it may be replaced. Returns {@code
   * null} when the upload has no statement.
   */
  StatementRef findForUpload(AiOcrUpload upload);

  /**
   * A read-only snapshot of the statement linked to an upload: enough to identify it, say whether
   * it is still an untouched received draft (so a reroute may replace it) and check it belongs to
   * the upload's company — without leaking the bank-payment types into {@code axelor-ai-ocr}.
   */
  final class StatementRef {
    private final Long id;
    private final String name;
    private final boolean replaceable;
    private final Long companyId;

    public StatementRef(Long id, String name, boolean replaceable, Long companyId) {
      this.id = id;
      this.name = name;
      this.replaceable = replaceable;
      this.companyId = companyId;
    }

    public Long getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    /** True only while the statement is still "received" — never once it has been imported. */
    public boolean isReplaceable() {
      return replaceable;
    }

    public Long getCompanyId() {
      return companyId;
    }
  }

  /** Default no-op: no bank-statement backend is present. */
  class NoOp implements OcrBankStatementCreator {
    @Override
    public List<int[]> detectStatementRanges(String analyzeResultJson) {
      return java.util.Collections.emptyList();
    }

    @Override
    public Long createForOwner(AiOcrUpload owner, String analyzeResultJson, int from, int to) {
      return null;
    }

    @Override
    public int deleteForUpload(AiOcrUpload upload) {
      return 0;
    }

    @Override
    public StatementRef findForUpload(AiOcrUpload upload) {
      return null;
    }
  }
}
