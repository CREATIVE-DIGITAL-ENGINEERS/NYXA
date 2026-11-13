package com.axelor.apps.bglocalization.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.InvoiceLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Видът данъчен кредит на автоматичния ред за закръгляване. Чист тест — без Guice и без JPA.
 *
 * <p>Стотинката влиза в дневника за покупки през {@code
 * BgVatDocumentCollector.accumulatePurchaseColumns} като всеки друг ред, тъй че видът ѝ данъчен
 * кредит решава в коя колона попада.
 */
class BgOcrRoundingLineCreditTypeTest {

  private final BgOcrRoundingLineCreditType creditType = new BgOcrRoundingLineCreditType();

  private static InvoiceLine lineWith(String bgVatCreditType) {
    InvoiceLine line = new InvoiceLine();
    line.setBgVatCreditType(bgVatCreditType);
    return line;
  }

  @Test
  void aUnanimousCreditTypeIsCopiedOntoTheCorrection() {
    InvoiceLine rounding = new InvoiceLine();

    String refusal =
        creditType.applyFromBucket(rounding, Arrays.asList(lineWith("2"), lineWith("2")));

    assertNull(refusal);
    assertEquals("2", rounding.getBgVatCreditType(), "частичен кредит се наследява");
  }

  @Test
  void anUnsetBucketGetsTheTreatmentTheDiaryWouldApplyAnyway() {
    // accumulatePurchaseColumns приема празна стойност за ПЪЛЕН кредит и предупреждава; записано
    // явно, редът влиза в същата колона без предупреждение.
    InvoiceLine rounding = new InvoiceLine();

    String refusal =
        creditType.applyFromBucket(rounding, Arrays.asList(lineWith(null), lineWith("  ")));

    assertNull(refusal);
    assertEquals("1", rounding.getBgVatCreditType());
  }

  @Test
  void conflictingCreditTypesAreRefusedRatherThanGuessed() {
    InvoiceLine rounding = new InvoiceLine();

    String refusal =
        creditType.applyFromBucket(rounding, Arrays.asList(lineWith("1"), lineWith("3")));

    assertNotNull(refusal, "различни видове кредит нямат еднозначен отговор");
    assertNull(rounding.getBgVatCreditType(), "нищо не се записва при отказ");
  }

  @Test
  void anEmptyBucketStillProducesAnExplicitValue() {
    InvoiceLine rounding = new InvoiceLine();
    List<InvoiceLine> none = new ArrayList<>();

    assertNull(creditType.applyFromBucket(rounding, none));
    assertEquals("1", rounding.getBgVatCreditType());
  }
}
