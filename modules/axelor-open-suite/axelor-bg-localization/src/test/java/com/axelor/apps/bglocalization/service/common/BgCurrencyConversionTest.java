package com.axelor.apps.bglocalization.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Каноничният конвертор лев → евро по ЗВЕРБ.
 *
 * <p>Чисти функции, тъй че без Guice и без база. Числата тук са ЕТАЛОНИ: всеки левов праг в
 * локализацията минава оттук, и разминаване с една стотинка се появява в данъчна декларация.
 */
class BgCurrencyConversionTest {

  @Test
  @DisplayName("прагът по чл. 50/51 ЗКПО: 700 лв дават 357,90 EUR")
  void прагът_по_чл_50() {
    // ЗКПО чл. 50 ал. 1 т. 2 и чл. 51 ал. 1 т. 1 б. „г“ бб) стоят в ЛЕВОВЕ, изписани с думи —
    // „седемстотин лева“ — и Законът за въвеждане на еврото НЕ ги е превалутирал поименно (§ 36
    // изменя ЗКПО с тринайсет точки и нито една не пипа тези два члена). Затова важи общото
    // правило на чл. 11 ал. 2 и сумата се ИЗЧИСЛЯВА.
    assertEquals(
        new BigDecimal("357.90"),
        BgCurrencyConversion.toEur(new BigDecimal("700")),
        "700 ÷ 1.95583 = 357.9043168… → по чл. 13 ал. 1 третият знак е 4, тоест надолу → 357.90");
  }

  @Test
  @DisplayName("⚠️ същото число по чл. 45 ал. 1 е ДРУГО — режимът не е козметика")
  void двата_режима_се_разминават() {
    BigDecimal седемстотин = new BigDecimal("700");
    BigDecimal общо = BgCurrencyConversion.toEur(седемстотин);
    BigDecimal заплати = BgCurrencyConversion.toEurForRemunerations(седемстотин);

    assertEquals(new BigDecimal("357.90"), общо, "чл. 13 ал. 1: третият знак 4 → надолу");
    assertEquals(
        new BigDecimal("357.91"),
        заплати,
        "чл. 45 ал. 1: третият знак 4 е ПО-ГОЛЯМ ОТ НУЛА → вторият се увеличава с една единица");
    assertNotEquals(
        общо,
        заплати,
        "и точно затова двата режима стоят в ЕДНА утилита: разлика от стотинка, скрита в два файла,"
            + " изглежда като грешка в закръглянето вместо като избор на правило");
  }

  @Test
  @DisplayName("прецедентът от заплатите: 660 лв дават 337,45 EUR по общото правило")
  void прецедентът_от_фиша() {
    // BgPaySlipService документира „660 лв. са 337,45 EUR по фиксирания курс 1,95583“. Проверката е
    // двустранна: потвърждава конвертора И че payroll вече ползва чл. 13, а не чл. 45.
    assertEquals(
        new BigDecimal("337.45"),
        BgCurrencyConversion.toEur(new BigDecimal("660")),
        "660 ÷ 1.95583 = 337.4526… → третият знак 2 → надолу");
    assertEquals(
        new BigDecimal("337.46"),
        BgCurrencyConversion.toEurForRemunerations(new BigDecimal("660")),
        "по чл. 45 ал. 1 същата сума дава стотинка повече — дали минималният осигурителен доход е"
            + " „възнаграждение“ по смисъла на ал. 1 е преценка на payroll частта");
  }

  @Test
  @DisplayName("⚠️ дели се на ПЪЛНИЯ курс, не се умножава по обърнат")
  void без_инверсен_курс() {
    // Регламент (ЕО) № 1103/97 чл. 4 § 2 забранява инверсния курс, а ЗВЕРБ чл. 12 ал. 2 забранява
    // курсът да се закръглява или съкращава. Разликата е измерима, а не теоретична.
    BigDecimal сума = new BigDecimal("1000000");
    BigDecimal вярно = BgCurrencyConversion.toEur(сума);
    BigDecimal инверсен =
        сума.multiply(
                BigDecimal.ONE.divide(
                    BgCurrencyConversion.OFFICIAL_RATE, 5, java.math.RoundingMode.HALF_UP))
            .setScale(2, java.math.RoundingMode.HALF_UP);
    assertNotEquals(
        вярно, инверсен, "обърнатият курс дава друго число — оттам и забраната в регламента");
    assertEquals(new BigDecimal("511291.88"), вярно, "1 000 000 ÷ 1.95583 = 511291.8814…");
  }

  @Test
  @DisplayName("чл. 45 ал. 1 не е CEILING — гледа се ТРЕТИЯТ знак, не всеки остатък")
  void чл_45_не_е_ceiling() {
    // 0.0001 EUR остатък: третият знак е НУЛА, тъй че увеличение НЕ се прави, докато CEILING би
    // вдигнал. Буквалният прочит е причината правилото да е написано през третата цифра.
    BigDecimal сума = new BigDecimal("1.95583").multiply(new BigDecimal("2.0001"));
    assertEquals(
        new BigDecimal("2.00"),
        BgCurrencyConversion.toEurForRemunerations(сума),
        "2.0001 → третият знак е 0 → вторият остава непроменен");
  }

  @Test
  @DisplayName("липсващата сума се обажда, вместо да мине за нула")
  void null_не_е_нула() {
    // Нулата не е безопасно подразбиране за конвертор, който храни ПРАГОВЕ: праг нула значи „всичко
    // минава“, тоест мълчалив отказ от проверката.
    assertThrows(IllegalArgumentException.class, () -> BgCurrencyConversion.toEur(null));
    assertThrows(
        IllegalArgumentException.class, () -> BgCurrencyConversion.toEurForRemunerations(null));
  }

  @Test
  @DisplayName("курсът е с шест цифри и пет знака, както го описва чл. 12 ал. 1")
  void формата_на_курса() {
    // ЗВЕРБ чл. 5 казва, че самото число идва от Регламент на Съвета, и това е Регламент (ЕС)
    // 2025/1409 на Съвета от 8 юли 2025 г., чийто член 1 вмъква в Регламент (ЕО) № 2866/98 реда
    // „= 1,95583 Bulgarian lev“ (в сила от 01.01.2026 г.). Тъй че стойността не се проверява тук —
    // тя е неотменимо фиксирана с акт на Съвета. Проверява се ФОРМАТА, която чл. 12 ал. 1 описва:
    // „изразен с шест цифри с всичките пет знака след десетичната запетая“.
    assertEquals(5, BgCurrencyConversion.OFFICIAL_RATE.scale(), "пет знака след запетаята");
    assertEquals(
        6,
        BgCurrencyConversion.OFFICIAL_RATE.precision(),
        "шест значещи цифри — формата по чл. 12 ал. 1");
  }
}
