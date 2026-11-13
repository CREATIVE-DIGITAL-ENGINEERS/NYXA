package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛОВАТА ПОЛОВИНА на енджина за начисления — аритметиката, § 6.3.1 стъпка 3.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate: класът под теста не докосва базата. Падне ли
 * нещо тук, паднала е СМЕТКАТА, а не средата — и точно затова тези твърдения нямат право да се
 * пропускат.
 *
 * <p><b>Всеки свидетел е чупен преднамерено, преди да го оставя зелен</b> — иначе не е доказано, че
 * мери онова, което казва, че мери. Какво е било счупено и как е паднало стои до всеки тест.
 */
class BgEarningsResolverTest {

  private static final BigDecimal BASE_2000 = new BigDecimal("2000.00");
  private static final BigDecimal PCT_MIN = new BigDecimal("0.6000");

  // ── основно възнаграждение ──────────────────────────────────────────────

  /**
   * FAIL-FIRST: делителят беше сменен на {@code norm + 1} → 954,55 ≠ 1000,00, тестът падна. Тоест
   * твърдението наистина мери пропорцията, а не просто връща подадената база.
   */
  @Test
  @DisplayName("основна · пълен месец дава ТОЧНО базата, не 999,99")
  void fullMonthIsExactlyTheBase() {
    assertEquals(
        new BigDecimal("1000.00"),
        BgEarningsResolver.baseSalaryAmount(new BigDecimal("1000.00"), 21, 21));
  }

  /** FAIL-FIRST: умножението по отработените дни беше махнато → 1000,00 ≠ 500,00, тестът падна. */
  @Test
  @DisplayName("основна · половин месец дава половин база")
  void halfMonthIsHalfTheBase() {
    assertEquals(
        new BigDecimal("500.00"),
        BgEarningsResolver.baseSalaryAmount(new BigDecimal("1000.00"), 10, 20));
  }

  /**
   * ⚠️ Единственото закръгляне е ПОСЛЕДНО: 1000 × 7 / 21 = 333,3333… → 333,33. Верига от закръглени
   * междинни числа (1000/21 = 47,62 × 7 = 333,34) дава ДРУГА стотинка — затова тук стои и точното
   * очаквано число, и грешното до него в коментара.
   *
   * <p>FAIL-FIRST: сметката беше пренаписана като {@code база.divide(норма,2).multiply(отраб)} →
   * 333,34 ≠ 333,33, тестът падна.
   */
  @Test
  @DisplayName("основна · закръглянето е ЕДНО и последно (§ 5.3)")
  void roundingHappensOnceAtTheEnd() {
    assertEquals(
        new BigDecimal("333.33"),
        BgEarningsResolver.baseSalaryAmount(new BigDecimal("1000.00"), 7, 21));
  }

  // ── клас прослужено време ───────────────────────────────────────────────

  /**
   * СВИДЕТЕЛ 1 — числото от заданието: база 2000, 10 навършени години, 0,6% → 120,00.
   *
   * <p>FAIL-FIRST: делението на 100 беше махнато → 12000,00 ≠ 120,00, тестът падна.
   */
  @Test
  @DisplayName("ТСПО · база 2000 × 10 години × 0,6% = 120,00")
  void seniorityOfTenYears() {
    assertEquals(
        new BigDecimal("120.00"),
        BgEarningsResolver.seniorityAmount(BASE_2000, PCT_MIN, 10, 21, 21));
  }

  /**
   * СВИДЕТЕЛ 2 — ⚠️ БАЗАТА Е ОСНОВНАТА, НЕ БРУТОТО. Най-честата грешка при това перо.
   *
   * <p>Доказва се така: една и съща основна дава една и съща сума, независимо колко е брутото. Ако
   * някой ден формулата вземе брутото, тук ще има за какво да се хване — по-високото бруто ще даде
   * по-висок клас и двете твърдения ще се разминат.
   *
   * <p>FAIL-FIRST: {@link BgEarningsResolver#seniorityAmount} беше извикан с брутото вместо с
   * основната (3200 вместо 2000) → 192,00 ≠ 120,00, тестът падна.
   */
  @Test
  @DisplayName("ТСПО · ползва ОСНОВНАТА, не брутото — по-високо бруто НЕ мени класа")
  void seniorityIgnoresGross() {
    BigDecimal gross = new BigDecimal("3200.00"); // основна 2000 + добавки 1200
    assertTrue(gross.compareTo(BASE_2000) > 0, "фикстурата иска бруто НАД основната");

    BigDecimal fromBase = BgEarningsResolver.seniorityAmount(BASE_2000, PCT_MIN, 10, 21, 21);
    BigDecimal fromGross = BgEarningsResolver.seniorityAmount(gross, PCT_MIN, 10, 21, 21);

    assertEquals(new BigDecimal("120.00"), fromBase);
    assertNotEquals(
        fromBase,
        fromGross,
        "ако двете съвпаднат, фикстурата не различава двете бази и свидетелят не доказва нищо");
  }

  /**
   * СВИДЕТЕЛ 3 — ⚠️ РЕШЕНИЕ № 1: класът следва СЪЩАТА пропорция като основната.
   *
   * <p>Основанието е ИЗРИЧНО: НСОРЗ чл. 12 ал. 8 — перото „се заплаща за ДЕЙСТВИТЕЛНО ОТРАБОТЕНО
   * ВРЕМЕ в рамките на съответната месечна продължителност на работното време“. Тоест пропорцията е
   * изискване на наредбата, не наш избор.
   *
   * <p>⚠️ Спецификацията § 6.3.1 сама по себе си води до ГРЕШНИЯ отговор: пропорцията там е
   * записана до основната и не е записана до класа. Затова свидетелят стои — четенето само на §
   * 6.3.1 е точно пътят към „класът се дължи цял“. Обръщането на {@link
   * BgEarningsResolver#SENIORITY_FOLLOWS_WORKED_TIME} на {@code false} поваля ТОЧНО този тест и
   * оставя другите зелени — тоест правилото е проверимо на едно място.
   *
   * <p>FAIL-FIRST: константата беше обърната на {@code false} → 120,00 ≠ 60,00, тестът падна.
   */
  @Test
  @DisplayName("ТСПО · РЕШЕНИЕ № 1 — половин месец дава половин клас (както основната)")
  void seniorityFollowsTheSameProrationAsTheBase() {
    assertTrue(
        BgEarningsResolver.SENIORITY_FOLLOWS_WORKED_TIME,
        "този свидетел описва решението „класът се проратира“; смени ли се константата, смени и него");

    BigDecimal full = BgEarningsResolver.seniorityAmount(BASE_2000, PCT_MIN, 10, 20, 20);
    BigDecimal half = BgEarningsResolver.seniorityAmount(BASE_2000, PCT_MIN, 10, 10, 20);

    assertEquals(new BigDecimal("120.00"), full);
    assertEquals(new BigDecimal("60.00"), half);
  }

  /**
   * Обратната проверка на същото решение: цял неотработен месец не носи клас.
   *
   * <p>При правилото „цял клас“ лице с нула отработени дни щеше да получи заплата 0,00 и клас
   * 120,00.
   *
   * <p>FAIL-FIRST: константата беше обърната на {@code false} → 120,00 ≠ 0,00, тестът падна.
   */
  @Test
  @DisplayName("ТСПО · нула отработени дни не носят клас")
  void seniorityIsZeroWhenNothingWasWorked() {
    assertEquals(
        new BigDecimal("0.00"), BgEarningsResolver.seniorityAmount(BASE_2000, PCT_MIN, 10, 0, 21));
  }

  // ── навършените години: броят се от ДАТАТА ──────────────────────────────

  /**
   * СВИДЕТЕЛ 4 — ⚠️ БРОИ СЕ ОТ ДАТАТА, НЕ ОТ {@code recognisedServiceYears}.
   *
   * <p>Договор с отдавнашно начало и НУЛА признат предходен стаж пак трупа години. Ако енджинът
   * ползваше сбора, тук щеше да излезе нула — затова методът изобщо няма параметър за сбор.
   *
   * <p>FAIL-FIRST: методът беше пренаписан да връща {@code 0} („както би върнал предходният стаж“)
   * → 0 ≠ 10, тестът падна.
   */
  @Test
  @DisplayName("ТСПО · годините се броят от КОТВАТА — 0 предходен стаж пак трупа")
  void yearsAreCountedFromTheAnchorNotFromThePriorSum() {
    LocalDate anchor = LocalDate.of(2016, 3, 1); // начало на договора, БЕЗ предходен стаж
    LocalDate periodTo = LocalDate.of(2026, 3, 31);

    assertEquals(10, BgEarningsResolver.completedServiceYears(anchor, periodTo));
  }

  /**
   * ⚠️ РЕШЕНИЕ № 2: мери се към ПОСЛЕДНИЯ ден на периода, тоест годината, навършена през месеца,
   * вдига класа ЗА СЪЩИЯ месец. Правилото „от месеца СЛЕД навършването“ би дало 9 за същия период.
   *
   * <p>ПОЛИТИКАТА: класът се вдига в МЕСЕЦА НА НАВЪРШВАНЕ. НСОРЗ чл. 12 ал. 10 фиксира само
   * интервала (не по-малък от една година), но не деня на влизане в сила — тоест изборът е на
   * работодателя и е направен. § 6.3.1 не се произнася.
   *
   * <p>FAIL-FIRST: аргументът беше сменен на първия ден на периода (2026-03-01) → 9 ≠ 10, тестът
   * падна.
   */
  @Test
  @DisplayName("ТСПО · РЕШЕНИЕ № 2 — годишнина ПРЕЗ периода вдига класа за СЪЩИЯ период")
  void anAnniversaryInsideThePeriodCountsForThatPeriod() {
    LocalDate anchor = LocalDate.of(2016, 3, 15);

    assertEquals(9, BgEarningsResolver.completedServiceYears(anchor, LocalDate.of(2026, 3, 14)));
    assertEquals(10, BgEarningsResolver.completedServiceYears(anchor, LocalDate.of(2026, 3, 15)));
    assertEquals(10, BgEarningsResolver.completedServiceYears(anchor, LocalDate.of(2026, 3, 31)));
  }

  /** Котва в бъдещето е нула навършени години, не отрицателно число. */
  @Test
  @DisplayName("ТСПО · котва СЛЕД периода дава 0, не отрицателно")
  void aFutureAnchorYieldsZeroYears() {
    assertEquals(
        0,
        BgEarningsResolver.completedServiceYears(
            LocalDate.of(2027, 1, 1), LocalDate.of(2026, 3, 31)));
  }

  // ── процентът: законовият под ───────────────────────────────────────────

  /**
   * СВИДЕТЕЛ 5а — уговореният процент НАДДЕЛЯВА над минимума, когато е по-висок.
   *
   * <p>FAIL-FIRST: методът беше пренаписан да връща винаги минимума → 0,6000 ≠ 1,0000, тестът
   * падна.
   */
  @Test
  @DisplayName("ТСПО · процентът на дружеството надделява, когато е НАД минимума")
  void theCompanyRateWinsWhenHigher() {
    assertEquals(
        new BigDecimal("1.00"),
        BgEarningsResolver.effectiveSeniorityRatePct(new BigDecimal("1.00"), PCT_MIN));
  }

  /**
   * СВИДЕТЕЛ 5б — под минимума се ВДИГА до минимума. ПМС № 147/2007 дава ДОЛНА граница, не стойност
   * по подразбиране.
   *
   * <p>FAIL-FIRST: методът беше пренаписан да връща подадения процент без сравнение → 0,40 ≠
   * 0,6000, тестът падна.
   */
  @Test
  @DisplayName("ТСПО · процент ПОД минимума се вдига до минимума")
  void aRateBelowTheStatutoryMinimumIsLiftedToIt() {
    assertEquals(
        PCT_MIN, BgEarningsResolver.effectiveSeniorityRatePct(new BigDecimal("0.40"), PCT_MIN));
  }

  /**
   * Непопълненият процент пристига като нула от генерирания getter — чете се като „не е уговорен“.
   */
  @Test
  @DisplayName("ТСПО · непопълнен процент (нула) пада на минимума, не на нула на сто")
  void anUnsetCompanyRateFallsBackToTheMinimum() {
    assertEquals(PCT_MIN, BgEarningsResolver.effectiveSeniorityRatePct(BigDecimal.ZERO, PCT_MIN));
    assertEquals(PCT_MIN, BgEarningsResolver.effectiveSeniorityRatePct(null, PCT_MIN));
  }

  // ── „0 не е решение“ ────────────────────────────────────────────────────

  /**
   * FAIL-FIRST: отказът беше заменен с {@code BigDecimal.ZERO} → статусът стана COMPUTED и тестът
   * падна на {@code isRefusal()}. Тоест твърдението различава отказ от сметната нула.
   */
  @Test
  @DisplayName("0 не е решение · непопълнена основна заплата е ОТКАЗ, който сочи къде се поправя")
  void anUnsetBaseSalaryIsARefusalNotZero() {
    BgEarningsOutcome out = BgEarningsResolver.baseSalaryLine(BigDecimal.ZERO, 21, 21);

    assertTrue(out.isRefusal(), "непопълнена заплата не бива да дава перо");
    assertNull(out.amount, "отказ не носи сума — иначе някой ще я събере с истинските");
    assertTrue(out.detail.contains("основната заплата"), out.detail);
    assertTrue(out.detail.contains("договор"), out.detail);
  }

  @Test
  @DisplayName("0 не е решение · липсваща норма е ОТКАЗ (делител няма, а не е нула)")
  void anUnsetWorkingDayCountIsARefusal() {
    BgEarningsOutcome out = BgEarningsResolver.baseSalaryLine(BASE_2000, 21, 0);

    assertTrue(out.isRefusal());
    assertTrue(out.detail.contains("работните дни"), out.detail);
    assertTrue(out.detail.contains("календар"), out.detail);
  }

  @Test
  @DisplayName("0 не е решение · неподадени отработени дни са ОТКАЗ, а нула отработени са ЗАКОННИ")
  void nullWorkedDaysRefuseButZeroWorkedDaysCompute() {
    assertTrue(BgEarningsResolver.baseSalaryLine(BASE_2000, null, 21).isRefusal());

    BgEarningsOutcome zero = BgEarningsResolver.baseSalaryLine(BASE_2000, 0, 21);
    assertTrue(zero.isComputed(), "цял месец отсъствие е законен случай, не отказ");
    assertEquals(new BigDecimal("0.00"), zero.amount);
  }

  /**
   * FAIL-FIRST: {@code null} процент беше подменен с 0,6 по подразбиране → статусът стана COMPUTED
   * и тестът падна. Точно това е капанът: подразбиране върху ЛИПСВАЩ параметър дава правдоподобно и
   * непроверимо число.
   */
  @Test
  @DisplayName("0 не е решение · липсващ SENIORITY_MIN_PCT е ОТКАЗ, който сочи seed-а")
  void aMissingSeniorityParameterIsARefusal() {
    BgEarningsOutcome out =
        BgEarningsResolver.seniorityLine(Boolean.TRUE, BASE_2000, null, 10, 21, 21);

    assertTrue(out.isRefusal());
    assertTrue(out.detail.contains("SENIORITY_MIN_PCT"), out.detail);
    assertTrue(out.detail.contains("App Install"), out.detail);
  }

  @Test
  @DisplayName("0 не е решение · липсваща котва на стажа е ОТКАЗ, а нула години са ЗАКОННИ")
  void aMissingAnchorRefusesButZeroYearsCompute() {
    BgEarningsOutcome missing =
        BgEarningsResolver.seniorityLine(Boolean.TRUE, BASE_2000, PCT_MIN, null, 21, 21);
    assertTrue(missing.isRefusal());
    assertTrue(missing.detail.contains("bgPriorExperience"), missing.detail);

    BgEarningsOutcome firstYear =
        BgEarningsResolver.seniorityLine(Boolean.TRUE, BASE_2000, PCT_MIN, 0, 21, 21);
    assertTrue(firstYear.isComputed(), "първата година на стажа е ИЗЧИСЛЕНА нула, не липса");
    assertEquals(new BigDecimal("0.00"), firstYear.amount);
  }

  /** Количеството и ставката са ДОКУМЕНТАЦИЯ на сумата — без тях перото е число без произход. */
  @Test
  @DisplayName("сметнатото перо носи и числата, които обясняват сумата")
  void aComputedLineCarriesItsOwnExplanation() {
    BgEarningsOutcome base = BgEarningsResolver.baseSalaryLine(BASE_2000, 10, 20);
    assertEquals(BgEarningsResolver.LINE_BASE_SALARY, base.lineCode);
    assertEquals(new BigDecimal("1000.00"), base.amount);
    assertEquals(0, base.quantity.compareTo(BigDecimal.TEN), "количеството са отработените дни");
    assertEquals(0, base.rate.compareTo(BASE_2000), "ставката е месечната основна");

    BgEarningsOutcome seniority =
        BgEarningsResolver.seniorityLine(Boolean.TRUE, BASE_2000, PCT_MIN, 10, 20, 20);
    assertEquals(BgEarningsResolver.LINE_SENIORITY, seniority.lineCode);
    assertNotNull(seniority.amount);
    assertEquals(0, seniority.quantity.compareTo(BigDecimal.TEN), "количеството са годините");
    assertEquals(0, seniority.rate.compareTo(PCT_MIN), "ставката е процентът за година");
  }
}
