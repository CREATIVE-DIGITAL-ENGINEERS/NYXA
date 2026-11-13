package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.InvoiceLine;
import java.util.List;

/**
 * Extension point that gives the automatic rounding line the same VAT-credit treatment as the lines
 * it belongs to.
 *
 * <h2>Why an extension point and not a field assignment</h2>
 *
 * {@code InvoiceLine.bgVatCreditType} is declared by {@code axelor-bg-localization}, and Axelor
 * generates a SEPARATE merged entity per module: {@code axelor-ai-ocr}'s copy of {@code
 * InvoiceLine} carries {@code ocrRoundingAdjustment} but has no {@code bgVatCreditType} at all,
 * because bg-localization sits DOWNSTREAM of this module. Referring to it from here would not
 * compile, and inverting the dependency would put Bulgarian tax law into the generic OCR layer.
 *
 * <p>So the rounding line's amount, account and tax bucket are decided here — that is arithmetic —
 * while the credit type, which is ЗДДС, is decided by the module that owns it. Same shape as {@code
 * OcrBankStatementCreator}: declared here, bound by bg-localization, looked up defensively so
 * ai-ocr still runs without it.
 */
public interface OcrRoundingLineCreditType {

  /**
   * Copies the VAT-credit treatment of {@code bucketLines} onto {@code roundingLine}.
   *
   * <p>The rounding cent is part of the same taxable base as the lines it was derived from, so it
   * must be declared in the same purchase-diary column. Implementations must NOT guess: when the
   * bucket's lines disagree about their credit type there is no single right answer for the
   * correction either.
   *
   * @param roundingLine the marked 609 line, already carrying its amount and tax
   * @param bucketLines every ordinary line of the bucket the correction was assigned to
   * @return {@code null} when the credit type was set; otherwise why it could not be, in one
   *     sentence, for the caller to surface as a review reason
   */
  String applyFromBucket(InvoiceLine roundingLine, List<InvoiceLine> bucketLines);
}
