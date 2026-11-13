package com.axelor.apps.aiocr.service.record;

import static com.axelor.apps.aiocr.service.readiness.BgMessages.BG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The OCR refusal the user reads at Validate / Ventilate: Bulgarian, with both totals and what to
 * do — while the English diagnosis stays the exception's message for the tests and the logs.
 */
class OcrDocumentRefusalTest {

  private static OcrDocumentRefusal refusal(OcrDocumentRefusal.Kind kind) {
    return new OcrDocumentRefusal(
        new Invoice(),
        TraceBackRepository.CATEGORY_INCONSISTENCY,
        "The total printed on the document (175.84) and the total Axelor calculates (175.87) differ"
            + " by 0.03.",
        kind,
        new BigDecimal("175.84"),
        new BigDecimal("175.87"));
  }

  @Test
  void theMessageStaysTheEnglishDiagnosis() {
    assertTrue(refusal(OcrDocumentRefusal.Kind.MISMATCH).getMessage().startsWith("The total"));
  }

  @Test
  void aMismatchGivesBothTotalsAndWhatToCheck() {
    assertEquals(
        "Фактурата не съвпада с документа, от който е прочетена: общата сума по документа е"
            + " 175.84, а редовете дават 175.87. Разлика над 0,02 не е закръгляне. Сверете"
            + " количествата, цените и данъците на редовете с документа.",
        refusal(OcrDocumentRefusal.Kind.MISMATCH).userMessage(BG));
  }

  @Test
  void eachKindHasItsOwnText() {
    assertTrue(refusal(OcrDocumentRefusal.Kind.NO_VAT).userMessage(BG).contains("поле „Данъци“"));
    assertTrue(
        refusal(OcrDocumentRefusal.Kind.ROUNDING_ACCOUNT_MISSING)
            .userMessage(BG)
            .contains("„Сметка за дебитни разлики при равнение“"));
    assertTrue(
        refusal(OcrDocumentRefusal.Kind.ZERO_TAX_MISSING)
            .userMessage(BG)
            .contains("нулева ставка"));
    assertTrue(
        refusal(OcrDocumentRefusal.Kind.VAT_BREAKDOWN).userMessage(BG).contains("по ставки"));
  }

  @Test
  void anUnusableRoundingAccountIsNamed() {
    OcrDocumentRefusal refusal =
        new OcrDocumentRefusal(
            new Invoice(),
            TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
            "x",
            OcrDocumentRefusal.Kind.ROUNDING_ACCOUNT_UNUSABLE,
            new BigDecimal("175.88"),
            new BigDecimal("175.87"),
            "609");
    assertEquals(
        "Разликата между общата сума по документа (175.88) и редовете (175.87) е закръгляне, но"
            + " зададената за нея сметка 609 е групова или неактивна и по нея не може да има ред."
            + " Задайте листова разходна сметка (напр. 60909) в „Сметка за дебитни разлики при"
            + " равнение“ в счетоводната конфигурация на компанията.",
        refusal.userMessage(BG));
  }

  @Test
  void anUnknownTotalIsADashNotNull() {
    OcrDocumentRefusal refusal =
        new OcrDocumentRefusal(
            new Invoice(), 0, "x", OcrDocumentRefusal.Kind.MISMATCH, null, BigDecimal.ONE);
    assertTrue(refusal.userMessage(BG).contains("документа е —,"), refusal.userMessage(BG));
  }
}
