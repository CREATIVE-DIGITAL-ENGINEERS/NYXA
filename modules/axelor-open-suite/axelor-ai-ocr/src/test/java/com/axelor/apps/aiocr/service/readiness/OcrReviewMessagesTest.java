package com.axelor.apps.aiocr.service.readiness;

import static com.axelor.apps.aiocr.service.readiness.BgMessages.BG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the invoice form says about an OCR draft's review flag: every missing key becomes a
 * Bulgarian sentence naming the field and where it is, and a locked Validate always has a reason
 * next to it.
 */
class OcrReviewMessagesTest {

  @Test
  void aLockedValidateNamesEveryMissingFieldInBulgarian() {
    List<String> missing = BgOcrIntakeService.missingReviewFields(new Invoice());

    assertEquals(
        "„Потвърди“ е заключен, докато в OCR черновата липсва: "
            + "доставчик (полето „Партньор“ в горната част на фактурата); "
            + "адрес на доставчика (полето „Адрес“ под партньора); "
            + "валута (полето „Валута“ в горната част на фактурата); "
            + "обща сума над нула (въведете редовете на фактурата с цените им — сумата се смята от"
            + " тях). Бутонът се отключва веднага щом ги попълните.",
        OcrReviewMessages.lockHint(missing, BG));
  }

  @Test
  void nothingMissingMeansNoHintAndNoMissingText() {
    assertNull(OcrReviewMessages.lockHint(List.of(), BG));
    assertNull(OcrReviewMessages.missingText(List.of(), BG));
    assertNull(OcrReviewMessages.warningsText(List.of(), BG));
  }

  @Test
  void paymentWarningsSayTheyDoNotBlockValidation() {
    assertEquals(
        "Няма банкова сметка — ще трябва при плащане (полето „Банкова сметка“). Не спира"
            + " потвърждаването.\n"
            + "Няма условие за плащане — ще трябва при осчетоводяване (полето „Условие за"
            + " плащане“). Не спира потвърждаването.",
        OcrReviewMessages.warningsText(BgOcrIntakeService.reviewWarnings(new Invoice()), BG));
  }

  @Test
  void anUnknownKeyIsShownRatherThanDropped() {
    assertEquals("something new", OcrReviewMessages.describe("something new", BG));
  }
}
