package com.axelor.apps.bglocalization.service.zddfl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ШЕСТТЕ ШАБЛОНА НА ЗДДФЛ — С ЧИСЛА.
 *
 * <p>Всеки свидетел тръгва от 1000 лв. бруто, защото при това бруто резултатите се четат наум и
 * разликата между шаблоните е видима без сметка: 75 при услуга, 90 при наем, 50 при дивидент, 70
 * при застраховка, нула при продажба и нула при рента.
 *
 * <p><b>⚠️ Ставките тук са ИЗПИСАНИ, а в {@link BgIncomeTaxSeedWitnessTest} се ЧЕТАТ ОТ SEED-А.</b>
 * Двата теста питат различни неща и затова са два: този пита „вярна ли е ФОРМУЛАТА при 25 на сто“,
 * другият — „носи ли код 3073 наистина 25 на сто“. Слети в един, повреден seed щеше да смени и
 * очакването, и резултата, и тестът щеше да остане зелен.
 */
class BgIncomeTaxResolverTest {

  private static final BigDecimal THOUSAND = new BigDecimal("1000.00");
  private static final BigDecimal TEN = new BigDecimal("10.00");
  private static final BigDecimal ZERO = new BigDecimal("0.00");

  // ═══ ШАБЛОН A · ЧЛ. 29 ══════════════════════════════════════════════════════

  @Test
  @DisplayName("A · код 3073 (консултантска услуга): 1000 → разходи 250, основа 750, авансов 75")
  void consultingServiceAtTwentyFivePercent() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_BUSINESS, "25.00", "0.00", "10.00", true, ZERO, false));

    assertTrue(r.isResolved(), "изчислението не биваше да отказва: " + r.refusal);
    assertEquals(new BigDecimal("250.00"), r.expenseAmount, "разходи по чл. 29, ал. 1, т. 3");
    assertEquals(new BigDecimal("750.00"), r.taxableBase, "основа по чл. 43, ал. 1");
    assertEquals(new BigDecimal("75.00"), r.taxAmount, "авансов данък по чл. 43, ал. 3");
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, r.taxKind);
  }

  @Test
  @DisplayName(
      "A · осигуровките ВЛИЗАТ в основата — чл. 43, ал. 1 иска разликата, не облагаемия доход")
  void businessSubtractsInsuranceFromTheBase() {
    // Същите 1000 при 25 на сто, но с 50 лв. удържани ЗОВ: основата пада от 750 на 700, данъкът —
    // от 75 на 70. ⚠️ Този свидетел е причината A и B да НЕ споделят код.
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_BUSINESS,
                "25.00",
                "0.00",
                "10.00",
                true,
                new BigDecimal("50.00"),
                false));

    assertEquals(new BigDecimal("250.00"), r.expenseAmount);
    assertEquals(new BigDecimal("700.00"), r.taxableBase, "1000 − 250 разходи − 50 ЗОВ");
    assertEquals(new BigDecimal("70.00"), r.taxAmount);
  }

  @Test
  @DisplayName(
      "A · СОЛ-получател, декларирал по чл. 43, ал. 5: данък НЕ се удържа, но редът се записва")
  void selfInsuredRecipientIsRecordedWithoutWithholding() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_BUSINESS, "25.00", "0.00", "10.00", true, ZERO, true));

    assertTrue(r.isResolved(), "редът СЕ ЗАПИСВА — отказ тук би изгубил доход от справката");
    assertEquals(new BigDecimal("750.00"), r.taxableBase, "основата се показва за сведение");
    assertEquals(ZERO, r.taxAmount, "чл. 43, ал. 6 — данъкът се внася от лицето");
    assertEquals(
        BgIncomeTaxResolver.TAX_NONE, r.taxKind, "нулата трябва да е РАЗЛИЧИМА от чл. 33 и чл. 13");
    assertNotNull(r.note, "нулевият данък иска обяснение, иначе изглежда като пропуск");
    assertTrue(r.note.contains("чл. 43"), "обяснението назовава нормата: " + r.note);
  }

  // ═══ ШАБЛОН B · ЧЛ. 31 ══════════════════════════════════════════════════════

  @Test
  @DisplayName("B · код 401 (наем недвижимо): 1000 → разходи 100, основа 900, авансов 90")
  void rentAtTenPercent() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(BgIncomeTaxResolver.PATTERN_RENT, "10.00", "0.00", "10.00", true, ZERO, false));

    assertEquals(new BigDecimal("100.00"), r.expenseAmount, "10 на сто по чл. 31, ал. 1");
    assertEquals(new BigDecimal("900.00"), r.taxableBase);
    assertEquals(new BigDecimal("90.00"), r.taxAmount, "чл. 44, ал. 4");
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, r.taxKind);
  }

  @Test
  @DisplayName("B · осигуровките НЕ влизат — чл. 44, ал. 4 умножава облагаемия доход по чл. 31")
  void rentIgnoresInsuranceWhenThePayerWithholds() {
    // ⚠️ Огледалото на businessSubtractsInsuranceFromTheBase: същите 50 лв. ЗОВ, но тук основата
    // ОСТАВА 900 и данъкът остава 90. Изравнени „за последователност“, двата случая биха дали
    // грешен данък на едната страна при всяко плащане с ненулева колона 12.
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_RENT,
                "10.00",
                "0.00",
                "10.00",
                true,
                new BigDecimal("50.00"),
                false));

    assertEquals(new BigDecimal("900.00"), r.taxableBase, "чл. 44, ал. 4 не приспада ЗОВ");
    assertEquals(new BigDecimal("90.00"), r.taxAmount);
  }

  @Test
  @DisplayName("B · СОЛ-наемодател НЕ е изключение — чл. 44 няма алинея като чл. 43, ал. 5")
  void rentHasNoSelfInsuredException() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(BgIncomeTaxResolver.PATTERN_RENT, "10.00", "0.00", "10.00", true, ZERO, true));

    assertEquals(new BigDecimal("90.00"), r.taxAmount, "флагът за СОЛ действа САМО при шаблон A");
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, r.taxKind);
  }

  // ═══ ШАБЛОН C · ЧЛ. 37 и ЧЛ. 38 ═════════════════════════════════════════════

  @Test
  @DisplayName("C · код 814 (дивидент): 1000 → окончателен 50 при 5 на сто, БЕЗ разходи")
  void dividendAtFivePercent() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(BgIncomeTaxResolver.PATTERN_FINAL, "0.00", "5.00", "0.00", true, ZERO, false));

    assertEquals(ZERO, r.expenseAmount, "глава шеста облага брутната сума — разходи няма");
    assertEquals(THOUSAND, r.taxableBase, "основата е брутото");
    assertEquals(new BigDecimal("50.00"), r.taxAmount, "чл. 46, ал. 3");
    assertEquals(BgIncomeTaxResolver.TAX_FINAL, r.taxKind);
  }

  @Test
  @DisplayName("C · код 8171 (застраховка „Живот“ 15+ г.): 1000 → окончателен 70 при 7 на сто")
  void lifeInsuranceAtSevenPercent() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(BgIncomeTaxResolver.PATTERN_FINAL, "0.00", "7.00", "0.00", true, ZERO, false));

    assertEquals(new BigDecimal("70.00"), r.taxAmount, "чл. 46, ал. 2");
    assertEquals(BgIncomeTaxResolver.TAX_FINAL, r.taxKind);
  }

  @Test
  @DisplayName("C · ставката идва от КОДА: 817 и 8171 са по един член, но с различен данък")
  void theRateComesFromTheCodeNotFromTheArticle() {
    // Двата кода са по чл. 38, ал. 8. 817 носи 10 на сто (чл. 46, ал. 1), 8171 — 7 на сто
    // (чл. 46, ал. 2). Логика, водена от ЧЛЕНА, би дала едно и също число на двата.
    BgIncomeTaxResult ten =
        BgIncomeTaxResolver.resolve(
            request(BgIncomeTaxResolver.PATTERN_FINAL, "0.00", "10.00", "0.00", true, ZERO, false));
    BgIncomeTaxResult seven =
        BgIncomeTaxResolver.resolve(
            request(BgIncomeTaxResolver.PATTERN_FINAL, "0.00", "7.00", "0.00", true, ZERO, false));

    assertEquals(new BigDecimal("100.00"), ten.taxAmount);
    assertEquals(new BigDecimal("70.00"), seven.taxAmount);
  }

  // ═══ ШАБЛОН D · ЧЛ. 33 ══════════════════════════════════════════════════════

  @Test
  @DisplayName("D · код 508 (продажба на акции): данък 0, но брутото се ЗАПИСВА")
  void transferRecordsGrossAndNoTax() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_TRANSFER, "0.00", "0.00", "0.00", false, ZERO, false));

    assertTrue(r.isResolved(), "редът СЕ ЗАПИСВА — платецът дължи ред в справката, не данък");
    assertEquals(ZERO, r.taxAmount, "чл. 33 не се облага авансово от платеца");
    assertEquals(BgIncomeTaxResolver.TAX_NONE, r.taxKind);
    assertEquals(ZERO, r.taxableBase, "основата иска цена на придобиване, която платецът няма");
    assertEquals(
        ZERO, r.expenseAmount, "10-те на сто по чл. 33 падат върху ПЕЧАЛБАТА, не върху бруто");
    assertNotNull(r.note);
    assertTrue(r.note.contains("чл. 33"), "нулата е обяснена: " + r.note);
  }

  @Test
  @DisplayName(
      "D · нулевата основа НЕ е „бруто × 0,9“ — 10-те на сто по чл. 33 не са разход от бруто")
  void transferDoesNotApplyTenPercentToGross() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_TRANSFER, "0.00", "0.00", "0.00", false, ZERO, false));

    assertFalse(
        new BigDecimal("900.00").equals(r.taxableBase),
        "900 тук би било правдоподобно число, което не е основата на нито една сделка");
  }

  // ═══ ШАБЛОН E · ЧЛ. 35 ══════════════════════════════════════════════════════

  @Test
  @DisplayName("E · други източници (чл. 35): разходи 0, основа = бруто, авансов 100 при 10 на сто")
  void otherSourcesTaxTheGrossAmount() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(BgIncomeTaxResolver.PATTERN_OTHER, "0.00", "0.00", "10.00", true, ZERO, false));

    assertEquals(ZERO, r.expenseAmount, "чл. 35: „облагаемият доход е БРУТНАТА СУМА“");
    assertEquals(THOUSAND, r.taxableBase);
    assertEquals(new BigDecimal("100.00"), r.taxAmount, "чл. 44а, ал. 1");
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, r.taxKind);
  }

  @Test
  @DisplayName(
      "E · осигуровките НЕ се приспадат — чл. 44а, ал. 1 умножава облагаемия доход по чл. 35")
  void otherSourcesIgnoreInsurance() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_OTHER,
                "0.00",
                "0.00",
                "10.00",
                true,
                new BigDecimal("50.00"),
                false));

    assertEquals(THOUSAND, r.taxableBase);
    assertEquals(new BigDecimal("100.00"), r.taxAmount);
  }

  // ═══ ШАБЛОН F · ЧЛ. 13 ══════════════════════════════════════════════════════

  @Test
  @DisplayName("F · код 907 (рента от земеделска земя): нула данък, само ред")
  void exemptIncomeIsRecordedWithZeroTax() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_EXEMPT, "0.00", "0.00", "0.00", false, ZERO, false));

    assertTrue(r.isResolved());
    assertEquals(ZERO, r.taxAmount);
    assertEquals(ZERO, r.taxableBase);
    assertEquals(BgIncomeTaxResolver.TAX_NONE, r.taxKind);
    assertNotNull(r.note);
    assertTrue(r.note.contains("чл. 13"), "нулата е обяснена: " + r.note);
  }

  // ═══ ТРИТЕ НУЛИ СЕ РАЗЛИЧАВАТ ═══════════════════════════════════════════════

  @Test
  @DisplayName("Трите нулеви данъка носят РАЗЛИЧНИ обяснения — иначе са неразличими в справката")
  void theThreeZeroesAreDistinguishable() {
    String transfer =
        BgIncomeTaxResolver.resolve(
                request(
                    BgIncomeTaxResolver.PATTERN_TRANSFER,
                    "0.00",
                    "0.00",
                    "0.00",
                    false,
                    ZERO,
                    false))
            .note;
    String exempt =
        BgIncomeTaxResolver.resolve(
                request(
                    BgIncomeTaxResolver.PATTERN_EXEMPT, "0.00", "0.00", "0.00", false, ZERO, false))
            .note;
    String selfInsured =
        BgIncomeTaxResolver.resolve(
                request(
                    BgIncomeTaxResolver.PATTERN_BUSINESS,
                    "25.00",
                    "0.00",
                    "10.00",
                    true,
                    ZERO,
                    true))
            .note;

    assertNotNull(transfer);
    assertNotNull(exempt);
    assertNotNull(selfInsured);
    assertFalse(transfer.equals(exempt), "чл. 33 и чл. 13 не са една и съща нула");
    assertFalse(exempt.equals(selfInsured), "чл. 13 и чл. 43, ал. 6 не са една и съща нула");
    assertFalse(transfer.equals(selfInsured), "чл. 33 и чл. 43, ал. 6 не са една и съща нула");
  }

  // ═══ ОТКАЗИТЕ ═══════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Отказите назовават липсващото, вместо да върнат нула")
  class Refusals {

    @Test
    @DisplayName("Трудов доход не се въвежда тук — той идва от фишовете по чл. 73, ал. 6")
    void employmentIncomeIsRefused() {
      BgIncomeTaxResult r =
          BgIncomeTaxResolver.resolve(
              request(
                  BgIncomeTaxResolver.PATTERN_EMPLOYMENT,
                  "0.00",
                  "0.00",
                  "10.00",
                  true,
                  ZERO,
                  false));

      assertFalse(r.isResolved());
      assertTrue(r.refusal.contains("ал. 6"), r.refusal);
    }

    @Test
    @DisplayName("Липсваща ставка на окончателния данък е ОТКАЗ, а не данък нула")
    void missingFinalRateIsRefused() {
      BgIncomeTaxResult r =
          BgIncomeTaxResolver.resolve(
              request(
                  BgIncomeTaxResolver.PATTERN_FINAL, "0.00", "0.00", "0.00", true, ZERO, false));

      assertFalse(r.isResolved(), "данък нула тук би минал за законен резултат");
      assertTrue(r.refusal.contains("чл. 46"), r.refusal);
    }

    @Test
    @DisplayName("Липсващи нормативни разходи при чл. 29 са ОТКАЗ — иначе данъкът е завишен")
    void missingExpenseRateIsRefused() {
      BgIncomeTaxResult r =
          BgIncomeTaxResolver.resolve(
              request(
                  BgIncomeTaxResolver.PATTERN_BUSINESS,
                  "0.00",
                  "0.00",
                  "10.00",
                  true,
                  ZERO,
                  false));

      assertFalse(r.isResolved(), "без разходи основата става бруто и лицето надплаща");
      assertTrue(r.refusal.contains("чл. 29"), r.refusal);
    }

    @Test
    @DisplayName("Отрицателно бруто е отказ — заличаване се подава с код корекция „8“")
    void negativeGrossIsRefused() {
      BgIncomeTaxResult r =
          BgIncomeTaxResolver.resolve(
              new BgIncomeTaxRequest(
                  BgIncomeTaxResolver.PATTERN_BUSINESS,
                  new BigDecimal("25.00"),
                  ZERO,
                  TEN,
                  true,
                  new BigDecimal("-100.00"),
                  ZERO,
                  false));

      assertFalse(r.isResolved());
      assertTrue(r.refusal.contains("8"), r.refusal);
    }

    @Test
    @DisplayName("Непознат шаблон се назовава, вместо да се смята по подразбиране")
    void unknownPatternIsRefused() {
      BgIncomeTaxResult r =
          BgIncomeTaxResolver.resolve(request("Z", "0.00", "0.00", "10.00", true, ZERO, false));

      assertFalse(r.isResolved());
      assertTrue(r.refusal.contains("Z"), r.refusal);
    }
  }

  // ═══ ГРАНИЧНИТЕ СМЕТКИ ══════════════════════════════════════════════════════

  @Test
  @DisplayName("Осигуровки над облагаемия доход дават нулева основа, не отрицателна")
  void insuranceAboveTheIncomeClampsTheBaseToZero() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            request(
                BgIncomeTaxResolver.PATTERN_BUSINESS,
                "25.00",
                "0.00",
                "10.00",
                true,
                new BigDecimal("900.00"),
                false));

    assertEquals(ZERO, r.taxableBase, "750 − 900 не е „данък за връщане“, а нулева основа");
    assertEquals(ZERO, r.taxAmount);
  }

  @Test
  @DisplayName("Закръглението е до стотинка, HALF_UP: 33,33 при 25 на сто дава разходи 8,33")
  void roundingIsHalfUpToTheStotinka() {
    BgIncomeTaxResult r =
        BgIncomeTaxResolver.resolve(
            new BgIncomeTaxRequest(
                BgIncomeTaxResolver.PATTERN_BUSINESS,
                new BigDecimal("25.00"),
                ZERO,
                TEN,
                true,
                new BigDecimal("33.33"),
                ZERO,
                false));

    assertEquals(new BigDecimal("8.33"), r.expenseAmount, "33,33 × 0,25 = 8,3325 → 8,33");
    assertEquals(new BigDecimal("25.00"), r.taxableBase);
    assertEquals(new BigDecimal("2.50"), r.taxAmount);
  }

  // ═══ СРЕДАТА ════════════════════════════════════════════════════════════════

  private static BgIncomeTaxRequest request(
      String pattern,
      String expenseRate,
      String finalRate,
      String advanceRate,
      boolean withheld,
      BigDecimal insurance,
      boolean selfInsuredDeclared) {
    return new BgIncomeTaxRequest(
        pattern,
        new BigDecimal(expenseRate),
        new BigDecimal(finalRate),
        new BigDecimal(advanceRate),
        withheld,
        THOUSAND,
        insurance,
        selfInsuredDeclared);
  }
}
