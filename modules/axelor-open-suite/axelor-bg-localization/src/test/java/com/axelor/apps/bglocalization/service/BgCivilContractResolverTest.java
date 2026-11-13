package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГРАЖДАНСКИЯТ ДОГОВОР — приспадането по КСО чл. 6 ал. 4 и прагът по чл. 4 ал. 3 т. 5 и 6.
 *
 * <p><b>Двете стъпки, които вид осигурен 10 няма.</b> Управителят по чл. 4 ал. 1 т. 7 се осигурява
 * върху ПЪЛНОТО си възнаграждение и се осигурява ВИНАГИ. Лицето по чл. 4 ал. 3 т. 5 и 6 — върху
 * възнаграждението СЛЕД нормативните разходи, и по т. 5 само ако то достига една минимална работна
 * заплата.
 *
 * <p><b>Числата са ДЕЙСТВИТЕЛНИТЕ за 2026 г.</b> МРЗ 620,20 евро ({@code MIN_WAGE}, ПМС № 243 от
 * 13.11.2025 г., ДВ бр. 98/2025); разходна ставка 25 на сто — чл. 29 ал. 1 т. 3 ЗДДФЛ,
 * „възнаграждения по извънтрудови правоотношения“, кодове 307x от каталога.
 */
class BgCivilContractResolverTest {

  /** Минималната работна заплата за 2026 г. — прагът по чл. 4 ал. 3 т. 5. */
  private static final BigDecimal MRZ_2026 = new BigDecimal("620.2000");

  /** Чл. 29 ал. 1 т. 3 — услуги и извънтрудови правоотношения. */
  private static final BigDecimal RATE_25 = new BigDecimal("25.00");

  /** Чл. 29 ал. 1 т. 2 — авторски и лицензионни възнаграждения, занаят, адвокат и нотариус. */
  private static final BigDecimal RATE_40 = new BigDecimal("40.00");

  private static final boolean INSURED_ELSEWHERE = true;
  private static final boolean NOT_INSURED_ELSEWHERE = false;

  // ═══ кодът ═══

  @Test
  @DisplayName("код · само 14 е граждански договор; 10 и празното НЕ са")
  void onlyCodeFourteenIsACivilContract() {
    assertTrue(BgCivilContractResolver.isCivilContract("14"));
    assertTrue(
        BgCivilContractResolver.isCivilContract(" 14 "), "заобикалящите интервали не решават");

    assertFalse(
        BgCivilContractResolver.isCivilContract("10"),
        "⚠️ ДУК минава по ЧЛ. 4 АЛ. 1 Т. 7 и се осигурява върху ПЪЛНОТО възнаграждение — чл. 6"
            + " ал. 3. Приспадане по ал. 4 при него би свалило базата му с една четвърт, тоест би"
            + " занижило и вноските, и бъдещата му пенсия.");
    assertFalse(BgCivilContractResolver.isCivilContract("01"), "наетият по трудов договор");
    assertFalse(BgCivilContractResolver.isCivilContract(null));
    assertFalse(BgCivilContractResolver.isCivilContract("   "));
  }

  // ═══ приспадането по чл. 6 ал. 4 ═══

  @Test
  @DisplayName("основа · ГД услуга 2000,00 при 25 на сто разходи дава 1500,00")
  void theServiceContractBaseIsTheRemunerationLessTwentyFivePercent() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("2000.00"), RATE_25, NOT_INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(BgCivilContractResolver.Status.INSURED, outcome.getStatus());
    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(outcome.getBase()),
        "КСО чл. 6 ал. 4: възнаграждението, върху което се дължат вноски, се определя СЛЕД"
            + " намаляването му с разходите за дейността. ⚠️ 2000,00 тук е числото на ДУК —"
            + " пълното възнаграждение по чл. 6 ал. 3 — и е точно грешката, която прави"
            + " гражданския договор да изглежда като управленски.");
    assertEquals(
        0,
        new BigDecimal("500.00").compareTo(outcome.getExpense()),
        "приспаднатите разходи се носят нататък: същото число, което данъчната страна пише в"
            + " колона 13 на справката по чл. 73 ал. 1.");
  }

  @Test
  @DisplayName("основа · авторско възнаграждение 2000,00 при 40 на сто дава 1200,00")
  void theRoyaltyContractUsesTheFortyPercentRate() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("2000.00"), RATE_40, NOT_INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(
        0,
        new BigDecimal("1200.00").compareTo(outcome.getBase()),
        "⚠️ СТАВКАТА НЕ Е ЕДНА ЗА ВСИЧКИ ГРАЖДАНСКИ ДОГОВОРИ, и точно затова идва от каталога, а"
            + " не от константа. Чл. 29 ал. 1 т. 2 б. „б“ дава 40 на сто за авторските и"
            + " лицензионните възнаграждения; т. 3 — 25 на сто за останалите извънтрудови"
            + " правоотношения. Закована 25-ица тук би дала 1500,00 — завишена основа за автора.");
  }

  @Test
  @DisplayName("основа · закръглянето е на разхода, до евроцент, половина нагоре")
  void theExpenseIsRoundedToTheCent() {
    // 1333,33 × 25 % = 333,3325 → 333,33; основата е 1000,00.
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("1333.33"), RATE_25, INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(0, new BigDecimal("333.33").compareTo(outcome.getExpense()));
    assertEquals(
        0,
        new BigDecimal("1000.00").compareTo(outcome.getBase()),
        "спецификация § 5.3: междинните с пълна точност, всяко перо — до евроцент. Същата"
            + " аритметика като BgIncomeTaxResolver#percentOf, за да не се разминат"
            + " осигурителната и данъчната основа за едно и също плащане.");
  }

  // ═══ прагът · т. 5 срещу т. 6 — НАХОДКАТА НА ГД ═══

  @Test
  @DisplayName("т. 5 · малък ГД 600,00 → 450,00 под МРЗ и без друго основание: БЕЗ вноски")
  void belowTheMinimumWageAndNotInsuredElsewhereNoContributionsArise() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("600.00"), RATE_25, NOT_INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(
        BgCivilContractResolver.Status.BELOW_THRESHOLD,
        outcome.getStatus(),
        "КСО чл. 4 ал. 3 т. 5 ДОСЛОВНО: задължително осигурени са лицата без трудово"
            + " правоотношение, които получават месечно възнаграждение „равно или над една"
            + " минимална работна заплата, СЛЕД НАМАЛЯВАНЕТО МУ С РАЗХОДИТЕ ЗА ДЕЙНОСТТА, ако не"
            + " са осигурени на друго основание през съответния месец“. 600 − 25 % = 450,00 <"
            + " 620,20 → лицето изобщо не попада в кръга на осигурените по това основание.");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(outcome.getBase()),
        "⚠️ 450,00 ТУК Е ПЪРВАТА ОЧАКВАНА ГРЕШКА: прагът, прочетен като „смятай върху каквото"
            + " има“, вместо като врата.");
    assertTrue(
        outcome.isResolved(),
        "⚠️ И ТОВА Е ЗАКОННА НУЛА, НЕ ОТКАЗ. Обявена като проблем, тя би спряла потвърждаването"
            + " на напълно верен фиш — а на този фиш просто няма осигурителни вноски.");
    assertEquals(
        0,
        new BigDecimal("150.00").compareTo(outcome.getExpense()),
        "разходите се смятат и под прага: данъкът по ЗДДФЛ се дължи и тук, а той стъпва на тях."
            + " ⚠️ Данъкът обаче НЕ минава оттук — извънтрудовите доходи влизат в регистъра"
            + " BgIndividualIncomePayment и в справката по чл. 73 ал. 1, не в Д1.");
  }

  @Test
  @DisplayName("т. 6 · СЪЩИЯТ малък ГД, но осигурен другаде: 450,00, независимо от размера")
  void insuredElsewhereMeansContributionsRegardlessOfTheAmount() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("600.00"), RATE_25, INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(BgCivilContractResolver.Status.INSURED, outcome.getStatus());
    assertEquals(
        0,
        new BigDecimal("450.00").compareTo(outcome.getBase()),
        "КСО чл. 4 ал. 3 т. 6 ДОСЛОВНО: лицата без трудово правоотношение, „които са осигурени на"
            + " друго основание през съответния месец, НЕЗАВИСИМО ОТ РАЗМЕРА на полученото"
            + " възнаграждение“. ⚠️ ЕДНО И СЪЩО ВЪЗНАГРАЖДЕНИЕ, ДВА ПРОТИВОПОЛОЖНИ ОТГОВОРА, и"
            + " разликата е ЕДИН признак — точно затова той е вход, а не извод. Нула тук би"
            + " лишила от осигуряване лице, което законът осигурява безусловно, и Д1 нямаше да се"
            + " подаде.");
  }

  @Test
  @DisplayName("т. 5 · РАВНОТО е ВЪТРЕ — 620,20 точно на МРЗ се осигурява")
  void exactlyOneMinimumWageIsInsured() {
    // Възнаграждение 826,94 при 25 на сто разходи → 620,20 точно, тоест ръбът на прага.
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("826.94"), RATE_25, NOT_INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(
        0,
        new BigDecimal("620.20").compareTo(outcome.getBase()),
        "826,94 − 206,74 (25 на сто, закръглено нагоре от 206,735) = 620,20.");
    assertEquals(
        BgCivilContractResolver.Status.INSURED,
        outcome.getStatus(),
        "нормата казва „РАВНО ИЛИ над една минимална работна заплата“ — тоест сравнението е >=,"
            + " не >. Строго по-голямо тук би оставило без осигуряване точно лицето на ръба.");
  }

  @Test
  @DisplayName("т. 5 · един цент под МРЗ вече е извън кръга")
  void oneCentBelowTheMinimumWageFallsOutside() {
    // Възнаграждение 826,92 → разходи 206,73 → 620,19, тоест един цент под прага.
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("826.92"), RATE_25, NOT_INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(
        0,
        new BigDecimal("206.73").compareTo(outcome.getExpense()),
        "826,92 × 25 на сто = 206,73 точно, тоест намаленото е 620,19 — един цент под прага.");
    assertEquals(
        BgCivilContractResolver.Status.BELOW_THRESHOLD,
        outcome.getStatus(),
        "⚠️ ОБРАТНАТА ПОЛОВИНА НА СВИДЕТЕЛЯ ЗА РЪБА, и без нея той щеше да мине и при сравнение"
            + " „винаги вътре“. Един цент е достатъчен: прагът е числов, не приблизителен.");
    assertEquals(0, BigDecimal.ZERO.compareTo(outcome.getBase()));
  }

  @Test
  @DisplayName("праг · сравнява се НАМАЛЕНОТО, не брутото — 700,00 бруто е над МРЗ, 525,00 не е")
  void theThresholdIsMeasuredAfterTheDeductionNotBefore() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("700.00"), RATE_25, NOT_INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(
        BgCivilContractResolver.Status.BELOW_THRESHOLD,
        outcome.getStatus(),
        "⚠️ ТОЧНО ТОЗИ СВИДЕТЕЛ ЗАБРАНЯВА УДОБНАТА ГРЕШКА „бруто ≥ МРЗ, значи се осигурява“."
            + " 700,00 бруто е над 620,20 и всяко око би казало „вътре“; нормата обаче мери"
            + " СЛЕД намалението — 525,00, — тоест лицето е ИЗВЪН кръга. Сравнено бруто, системата"
            + " би начислила вноски, които не се дължат, и то на лицето, което най-трудно ще ги"
            + " оспори.");
  }

  @Test
  @DisplayName("праг · ВРАТА, НЕ ПОД — 450,00 не се вдига до 620,20")
  void theThresholdIsAGateNotAFloor() {
    BgCivilContractResolver.Outcome insured =
        BgCivilContractResolver.resolve(
            new BigDecimal("600.00"), RATE_25, INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(
        0,
        new BigDecimal("450.00").compareTo(insured.getBase()),
        "⚠️ 620,20 ТУК Е ВТОРАТА ОЧАКВАНА ГРЕШКА и е по-скъпата от двете, защото изглежда"
            + " законосъобразна. Изр. 1 на чл. 6 ал. 3, което дължи вноски „върху не по-малко от"
            + " минималния осигурителен доход“, изброява САМО работниците и служителите и лицата"
            + " по чл. 4 ал. 1 т. 7 и 8 — ал. 3 т. 5 и 6 ГИ НЯМА в него. Затова и"
            + " bgFloorType на код 14 стои на NONE, а bgAppliesMod — на false: МОД по НКПД за"
            + " гражданския договор няма, а МРЗ е праг за ВЪЗНИКВАНЕ, не за размер.");
  }

  // ═══ отказите ═══

  @Test
  @DisplayName("отказ · без вид доход по ЗДДФЛ базата НЕ се смята непонижена")
  void aMissingExpenseRateRefusesInsteadOfSkippingTheDeduction() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("2000.00"), null, NOT_INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(BgCivilContractResolver.Status.MISSING_EXPENSE_RATE, outcome.getStatus());
    assertFalse(outcome.isResolved());
    assertNull(
        outcome.getBase(),
        "⚠️ ПОСОКАТА РЕШАВА. Сметната без ставката, базата излиза 2000,00 вместо 1500,00 —"
            + " вноски, завишени с една трета, при чист зелен резултат на екрана. Отказът с"
            + " назована причина е единственият от двата изхода, който се вижда.");
    assertNotNull(outcome.getDetail(), "отказ без причина праща човека да гадае");
    assertTrue(
        outcome.getDetail().contains("чл. 29"),
        "причината назовава РАЗПОРЕДБАТА, не полето: " + outcome.getDetail());
  }

  @Test
  @DisplayName("отказ · без МРЗ не може да се каже нито че се дължи, нито че не се дължи")
  void aMissingMinimumWageRefusesWhenTheThresholdMatters() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(
            new BigDecimal("600.00"), RATE_25, NOT_INSURED_ELSEWHERE, null);

    assertEquals(BgCivilContractResolver.Status.MISSING_MIN_WAGE, outcome.getStatus());
    assertNull(outcome.getBase());
    assertTrue(
        outcome.getDetail().contains(BgInsurableIncomeService.CODE_MIN_WAGE),
        "причината назовава параметъра, за да се знае кой ред от речника липсва: "
            + outcome.getDetail());
  }

  @Test
  @DisplayName("отказ · липсващата МРЗ НЕ спира лицето по т. 6 — прагът не участва в числата му")
  void aMissingMinimumWageDoesNotBlockThePersonInsuredElsewhere() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(new BigDecimal("600.00"), RATE_25, INSURED_ELSEWHERE, null);

    assertEquals(
        BgCivilContractResolver.Status.INSURED,
        outcome.getStatus(),
        "⚠️ ОТКАЗ ПО ПОВОД, ОТ КОЙТО РЕЗУЛТАТЪТ НЕ ЗАВИСИ, е дефект: фишът пада заради вход,"
            + " от който сметката му не зависи. По т. 6 лицето се осигурява"
            + " „независимо от размера“, тоест МРЗ не влиза в нито едно от числата му.");
    assertEquals(0, new BigDecimal("450.00").compareTo(outcome.getBase()));
  }

  @Test
  @DisplayName("граница · нула възнаграждение дава нула основа, не отказ")
  void zeroRemunerationIsZeroBase() {
    BgCivilContractResolver.Outcome outcome =
        BgCivilContractResolver.resolve(null, RATE_25, INSURED_ELSEWHERE, MRZ_2026);

    assertEquals(BgCivilContractResolver.Status.INSURED, outcome.getStatus());
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(outcome.getBase()),
        "месец без изплатено възнаграждение по гражданския договор е нула база, а не липсващо"
            + " число: КСО чл. 7 ал. 6 дължи вноските „до 25-о число на месеца, следващ месеца на"
            + " ИЗПЛАЩАНЕ на възнаграждението“.");
  }
}
