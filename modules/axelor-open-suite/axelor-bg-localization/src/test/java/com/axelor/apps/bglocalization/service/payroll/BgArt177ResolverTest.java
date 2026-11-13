package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВИДЕТЕЛИТЕ НА ЧИСТАТА АРИТМЕТИКА ПО ЧЛ. 177 — без база, без Guice, без Hibernate.
 *
 * <p>Всяко число тук е реконсилирано на ръка от дословния текст на нормата и е записано до
 * твърдението, за да може следващият читател да провери сметката, без да вярва на кода.
 */
class BgArt177ResolverTest {

  /** Основна 1000,00 + клас 6 % за 10 години = 60,00 → чл. 17 база 1060,00. */
  private static final BigDecimal ART17_BASE = new BigDecimal("1060.00");

  // ═══ ЧЛ. 18 АЛ. 1 ИЗР. 1 + АЛ. 2 ═══════════════════════════════════════

  @Test
  @DisplayName("Среднодневното е начисленото ÷ ОТРАБОТЕНИТЕ дни, а не ÷ работните")
  void theAverageDailyDividesByWorkedDays() {
    // Базов месец: норма 21, отработени 21. 1060,00 ÷ 21 = 50,476190… → 50,48 за един ден.
    BgArt177Resolver.DailyRate full =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("21"), 21, 21);
    assertEquals(new BigDecimal("50.48"), BgArt177Resolver.entitlementAmount(full, BigDecimal.ONE));

    // ⚠️ Същото начислено, но 20 ОТРАБОТЕНИ дни (един ден платен отпуск през базовия месец):
    // среднодневното РАСТЕ до 1060,00 ÷ 20 = 53,00. Точно това е разликата между „дни в
    // осигуряване“ (замразените bgWorkedDays) и „отработени дни“ (знаменателят на чл. 18 ал. 1).
    // Взето погрешно, среднодневното би излязло с 2,52 на ден по-ниско.
    BgArt177Resolver.DailyRate partial =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("20"), 21, 21);
    assertEquals(
        new BigDecimal("53.00"), BgArt177Resolver.entitlementAmount(partial, BigDecimal.ONE));
  }

  @Test
  @DisplayName("Коефициентът по ал. 2 е дни(БАЗА) / дни(ПОЛЗВАНЕ), а не обратното")
  void theCoefficientIsBaseOverUsage() {
    // Базов месец 20 работни дни, месец на ползване 23. Среднодневно 1060 ÷ 20 = 53,00.
    // Коефициент 20/23 → 53,00 × 20/23 × 5 дни = 1060 × 5 / 23 = 230,4347… → 230,43.
    BgArt177Resolver.DailyRate rate =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("20"), 20, 23);
    assertEquals(
        new BigDecimal("230.43"),
        BgArt177Resolver.entitlementAmount(rate, new BigDecimal("5")),
        "чл. 18 ал. 2: 1060 × 20 / (20 × 23) × 5");

    // ⚠️ БЕЗ коефициента същите пет дни биха дали 53,00 × 5 = 265,00. Разликата 34,57 е точно
    // онова, което ал. 2 мести — и точно онова, което днешният фиш губи.
    BgArt177Resolver.DailyRate uncorrected =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("20"), 23, 23);
    assertEquals(
        new BigDecimal("265.00"),
        BgArt177Resolver.entitlementAmount(uncorrected, new BigDecimal("5")));
  }

  @Test
  @DisplayName("Пълен базов месец и непроменено възнаграждение → надбавката е ТОЧНО нула")
  void theUpliftIsExactlyZeroWhenNothingChanged() {
    // Ноември: норма 21, отработени 21, начислено 1060,00. Декември: норма 22, 5 дни отпуск.
    BgArt177Resolver.DailyRate rate =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("21"), 21, 22);
    BigDecimal uplift = BgArt177Resolver.upliftAmount(rate, ART17_BASE, 22, new BigDecimal("5"));
    assertEquals(
        0,
        uplift.compareTo(BigDecimal.ZERO),
        "непроменен състав → перото връща само карва, тоест фишът е бит по бит какъвто беше");
  }

  @Test
  @DisplayName("Дробен ден отпуск (КТ чл. 166 — кърмене) минава без закръгляне на входа")
  void aFractionalLeaveDayIsCarried() {
    // Един час при осемчасов ден е 0,125 дни. 1060 × 0,125 / 21 = 6,3095… → 6,31.
    BgArt177Resolver.DailyRate rate =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("21"), 21, 21);
    assertEquals(
        new BigDecimal("6.31"), BgArt177Resolver.entitlementAmount(rate, new BigDecimal("0.125")));
  }

  // ═══ ЧЛ. 177 АЛ. 2 / ЧЛ. 18 АЛ. 1 ИЗР. 2 ═══════════════════════════════

  @Test
  @DisplayName("Резервният ред дели на СРЕДНОМЕСЕЧНИТЕ работни дни на годината")
  void theFallbackDividesByTheYearlyAverage() {
    // 249 работни дни за годината → средномесечно 20,75. 1060 × 5 / 20,75 = 255,4216… → 255,42.
    BgArt177Resolver.DailyRate rate = BgArt177Resolver.contractualDailyRate(ART17_BASE, 249);
    assertEquals(
        new BigDecimal("255.42"),
        BgArt177Resolver.entitlementAmount(rate, new BigDecimal("5")),
        "чл. 18 ал. 1 изр. 2: 1060 × 12 / 249 × 5");
  }

  @Test
  @DisplayName("Резервният ред НЕ носи коефициент — ал. 2 коригира само изречение първо")
  void theFallbackCarriesNoCoefficient() {
    // Дробта на резервния ред не зависи от работните дни на месеца на ползване: същият вход дава
    // същото число и за 20-дневен, и за 23-дневен месец. ⚠️ Ако някой добави коефициент тук,
    // числото ще започне да мърда и този свидетел ще падне.
    BgArt177Resolver.DailyRate rate = BgArt177Resolver.contractualDailyRate(ART17_BASE, 249);
    assertEquals(ART17_BASE.multiply(BigDecimal.valueOf(12)), rate.numerator);
    assertEquals(BigDecimal.valueOf(249), rate.denominator);
    assertTrue(rate.basis.contains("БЕЗ коефициент"));
  }

  @Test
  @DisplayName("Прагът по ал. 1 е „най-малко 10“, тоест точно 10 КВАЛИФИЦИРА")
  void tenWorkedDaysQualify() {
    assertTrue(BgArt177Resolver.qualifiesAsBaseMonth(new BigDecimal("10")));
    assertTrue(BgArt177Resolver.qualifiesAsBaseMonth(new BigDecimal("10.5")));
    assertFalse(BgArt177Resolver.qualifiesAsBaseMonth(new BigDecimal("9.99")));
    assertFalse(BgArt177Resolver.qualifiesAsBaseMonth(null));
  }

  // ═══ ЧЛ. 19 АЛ. 2 — ПОДЪТ ══════════════════════════════════════════════

  @Test
  @DisplayName("Подът по чл. 19 ал. 2 хапе, когато среднодневното падне под МРЗ-еквивалента")
  void theStatutoryFloorBitesBelowTheMinimumWage() {
    // МРЗ 620,20 при норма 22 → 28,19 на ден. Среднодневно от базов месец с начислено 400,00 за
    // 21 отработени при норма 21: 400 × 21 / (21 × 22) = 18,18 на ден — под пода.
    BgArt177Resolver.DailyRate low =
        BgArt177Resolver.accruedDailyRate(new BigDecimal("400.00"), new BigDecimal("21"), 21, 22);
    BgArt177Resolver.DailyRate floor =
        BgArt177Resolver.statutoryMinimumDailyRate(new BigDecimal("620.20"), 22);
    BgArt177Resolver.DailyRate applied = BgArt177Resolver.withStatutoryFloor(low, floor);
    assertSame(floor, applied, "подът е по-високият и заменя среднодневното");
    assertEquals(
        new BigDecimal("140.95"),
        BgArt177Resolver.entitlementAmount(applied, new BigDecimal("5")),
        "620,20 × 5 / 22");
  }

  @Test
  @DisplayName("Подът НЕ хапе при заплата над МРЗ — инвариантът остава цял")
  void theStatutoryFloorDoesNotBiteAboveTheMinimumWage() {
    BgArt177Resolver.DailyRate rate =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("21"), 21, 22);
    BgArt177Resolver.DailyRate floor =
        BgArt177Resolver.statutoryMinimumDailyRate(new BigDecimal("620.20"), 22);
    assertSame(rate, BgArt177Resolver.withStatutoryFloor(rate, floor));
  }

  @Test
  @DisplayName("Липсваща МРЗ НЕ е нулев под — подът просто не се прилага")
  void aMissingMinimumWageIsNotAZeroFloor() {
    assertNull(BgArt177Resolver.statutoryMinimumDailyRate(null, 22));
    BgArt177Resolver.DailyRate rate =
        BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("21"), 21, 22);
    assertSame(rate, BgArt177Resolver.withStatutoryFloor(rate, null));
  }

  // ═══ ЛИПСИТЕ ═══════════════════════════════════════════════════════════

  @Test
  @DisplayName("Нула отработени дни в базовия месец не дава дроб, а null")
  void zeroWorkedDaysGivesNoRate() {
    assertNull(BgArt177Resolver.accruedDailyRate(ART17_BASE, BigDecimal.ZERO, 21, 22));
    assertNull(BgArt177Resolver.accruedDailyRate(ART17_BASE, new BigDecimal("21"), null, 22));
    assertNull(BgArt177Resolver.accruedDailyRate(BigDecimal.ZERO, new BigDecimal("21"), 21, 22));
    assertNull(BgArt177Resolver.contractualDailyRate(ART17_BASE, 0));
  }
}
