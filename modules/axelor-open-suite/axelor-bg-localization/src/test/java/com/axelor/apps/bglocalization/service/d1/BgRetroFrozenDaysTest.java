package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОРИГИРАЩОТО КОПИЕ НОСИ ЗАМРАЗЕНИТЕ ДНИ — коригиращата Д1 казва същото, което и оригиналната.
 *
 * <p><b>ЗАЩО.</b> {@code BgPaySlipService#retroCopyOf} копира перата и седемнайсет замразени входа;
 * ако не копира и заглавните броячи на дните, резултатът е противоречие ВЪТРЕ в един запис: за
 * месец с 2 дни болничен от осигурителя и 1 от ДОО коригиращата Д1 излиза с <b>т. 16.А = 0 и т.
 * 16.2 = 0</b>, докато т. 21 продължава да носи осигурителния доход, включващ перото по чл. 40 ал.
 * 5 КСО — вноски върху дни, които същият запис не декларира.
 *
 * <p><b>⚠️ И ДВЕТЕ ПОЛОВИНИ НА ЗАГУБАТА НЕ СА ЕДНАКВИ.</b>
 *
 * <ul>
 *   <li><b>Болничните нямат fallback.</b> {@link BgD1DaySource} чете т. 16.А и т. 16.2 ЕДИНСТВЕНО
 *       от {@code bgSickDaysEmployer} и {@code bgSickDaysDoo} — няма огледален прочит, на който да
 *       паднат. Загубата е БЕЗУСЛОВНА.
 *   <li><b>Отпуските имат fallback, но той чете ДНЕШНИТЕ заявки.</b> Празният {@code
 *       bgLeaveDaysFrozenAt} праща Д1 на живата заявка. Дори при НЕПОКЪТНАТИ заявки три случая се
 *       разминават, защото живият път пази три отказа, които замразеният няма: заявка в ЧАСОВЕ,
 *       заявка през ГРАНИЦАТА на периода и ДРОБЕН ден. Променят ли се заявките — а корекцията се
 *       прави месеци по-късно — се разминава всичко.
 * </ul>
 *
 * <p><b>⚠️ ИСТИНСКИЯТ ПЪТ, НЕ ПОДРЕДЕНИ ПОЛЕТА.</b> Копието идва от {@code service.correct(...)} →
 * {@code retroCopyOf} → {@code recompute}; подменени са само четците към базата, точно както в
 * {@code BgPaySlipCorrectionTest}. Тоест ако утре някой махне ред от копирането, тестът пада.
 *
 * <p><b>⚠️ ОБЯВЕНА ГРАНИЦА: Т. 14/15 НЕ СЕ ЗАМРАЗЯВАТ.</b> Прекъсванията по КСО чл. 10 ал. 2 са
 * ИНТЕРВАЛИ, не броеве, и фишът няма къде да ги пази — те се строят от живите заявки за ДВАТА фиша.
 * Тук се проверяват само като регресия при непокътнати заявки; носител за интервали няма.
 */
class BgRetroFrozenDaysTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Декември 2026 по фикстурата: норма 22 работни дни. */
  private static final int NORM = 22;

  private static final BigDecimal SALARY = new BigDecimal("1200.00");

  // ═══ 1 · БОЛНИЧНИЯТ — БЕЗУСЛОВНАТА ЗАГУБА ════════════════════════════════

  @Test
  @DisplayName("⚠️ Болничен 2+1 · копието носи т. 16.А = 2 и т. 16.2 = 1, не НУЛА")
  void theSickDayCountersSurviveTheCorrection() {
    BgPaySlip original = confirmedSlip(19);
    original.setBgSickDaysEmployer(2);
    original.setBgSickDaysDoo(1);
    addLine(original, lineType(100, "pl_osnovna", true), "1036.36");
    addLine(original, lineType(195, "pl_bolnichen_rabotodatel", true), "76.36");
    addHealthAbsenceLine(original, "28.19", "4.80", "1.35");
    recomputed(original);

    BgPaySlip copy = correct(original);

    assertEquals(2, copy.getBgSickDaysEmployer(), "дните на осигурителя идват от оригинала");
    assertEquals(1, copy.getBgSickDaysDoo(), "и дните на ДОО");

    BgD1Record before = declarationOf(original);
    BgD1Record after = declarationOf(copy);

    assertEquals("2", before.getT16aEmployerSickDays(), "котвата: оригиналът декларира двата дни");
    assertEquals(
        "2",
        after.getT16aEmployerSickDays(),
        "⚠️ ТУК НЕ БИВА ДА ИЗЛИЗА „0“. BgD1DaySource чете т. 16.А само от bgSickDaysEmployer и"
            + " няма огледален прочит, на който да падне — пропуснато, полето нулира точката"
            + " тихо.");
    assertEquals("1", after.getT162SickDays(), "същото за т. 16.2 — и тя не е нула");
    assertSameDays(before, after);
    assertEquals(
        "22",
        after.getT16Days(),
        "⚠️ И СБОРЪТ ПО ЗАБ. 1: 19 + 1 + 2. При 19 три дни осигурителен стаж изчезват от"
            + " коригиращата декларация, докато т. 21 ги плаща.");

    assertSpecialHealthBase(before, after, "28.19");
    assertZeroDelta(original, copy);
  }

  // ═══ 2 · ТРИТЕ ОТКАЗА, КОИТО ЖИВИЯТ ПЪТ ПАЗИ ═════════════════════════════

  @Test
  @DisplayName("⚠️ Заявка в ЧАСОВЕ · замразеното 0,50 дава т. 16.5 = 1; живият прочит дава 0")
  void anHourlyRequestKeepsItsFrozenDay() {
    Object[] hourly = hours("2026-12-07", "2026-12-07", "4", "16.5");
    assertFrozenBucketSurvives(new BigDecimal("0.50"), "16.5", "1", "21", hourly);
  }

  @Test
  @DisplayName("⚠️ Straddle 28.12–08.01 · замразените 4 дни остават т. 16.5 = 4, т. 16.1 = 18")
  void astraddlingRequestKeepsItsFrozenDays() {
    Object[] straddle = row("2026-12-28", "2027-01-08", "8", "16.5");
    assertFrozenBucketSurvives(new BigDecimal("4.00"), "16.5", "4", "18", straddle);
  }

  @Test
  @DisplayName("⚠️ Дробен ден 0,50 · замразеното се закръгля HALF_UP до 1; живият прочит го губи")
  void afractionalDayKeepsItsFrozenDay() {
    Object[] fractional = row("2026-12-07", "2026-12-07", "0.5", "16.5");
    assertFrozenBucketSurvives(new BigDecimal("0.50"), "16.5", "1", "21", fractional);
  }

  /**
   * Общият свидетел на трите: замразената кофа оцелява, а живият прочит на СЪЩАТА заявка я губи.
   *
   * <p>⚠️ Контролата в края е задължителната половина: без нея тестът не доказва, че разликата идва
   * от замразяването — можеше да идва от фикстурата.
   */
  private void assertFrozenBucketSurvives(
      BigDecimal frozenDays,
      String bucket,
      String expected165,
      String expected161,
      Object[] leave) {

    BgPaySlip original = confirmedSlip(NORM);
    original.setBgLeaveDays165(frozenDays);
    addLine(original, lineType(100, "pl_osnovna", true), "1036.36");
    recomputed(original);

    BgPaySlip copy = correct(original);
    BgD1Record before = declarationOf(original, leave);
    BgD1Record after = declarationOf(copy, leave);

    assertEquals(expected165, before.getT165UnpaidLeaveDays(), "котвата: оригиналът е замразен");
    assertEquals(
        expected165,
        after.getT165UnpaidLeaveDays(),
        "⚠️ КОПИЕТО ЧЕТЕ СЪЩОТО. Изгубен bgLeaveDaysFrozenAt праща Д1 на ЖИВАТА заявка, а тя пази"
            + " три отказа, които замразеният път няма (часове, граница на периода, дробен ден) —"
            + " тоест точката би излязла НУЛА при непокътната заявка.");
    assertEquals(expected161, after.getT161WorkedDays(), "и остатъкът на т. 16.1 следва кофата");
    assertSameDays(before, after);
    assertZeroDelta(original, copy);

    // ⚠️ КОНТРОЛАТА: същата заявка, прочетена БЕЗ замразяване, дава друго число.
    BgPaySlip unfrozen = confirmedSlip(NORM);
    BgD1Record live = declarationOf(unfrozen, leave);
    assertEquals(
        "0",
        live.getT165UnpaidLeaveDays(),
        "⚠️ Без дискриминатора живият прочит НЕ брои тази заявка — точно състоянието, в което"
            + " копието се раждаше досега. Ако това стане различно от нула, свидетелят вече не"
            + " мери загубата и трябва да се препише.");
  }

  // ═══ 3 · ПЛАТЕНИЯТ ОТПУСК ════════════════════════════════════════════════

  @Test
  @DisplayName("Платен отпуск 5 дни · т. 16.1 остава 22 и на двата фиша")
  void paidLeaveIsCarriedOverToo() {
    BgPaySlip original = confirmedSlip(NORM);
    original.setBgLeaveDays161(new BigDecimal("5.00"));
    addLine(original, lineType(100, "pl_osnovna", true), "1200.00");
    recomputed(original);

    BgPaySlip copy = correct(original);
    Object[] leave = row("2026-12-07", "2026-12-11", "5", "16.1");

    assertEquals(
        0,
        new BigDecimal("5.00").compareTo(copy.getBgLeaveDays161()),
        "⚠️ КОПИРА СЕ, макар т. 16.1 да се ПРЕЗАПИСВА от остатъка в fillDays и числото да не мени"
            + " декларацията днес. Шестте кофи пътуват заедно: половин копие е по-лошо от никакво,"
            + " защото дискриминаторът би обявил и липсващите за факт.");
    assertSameDays(declarationOf(original, leave), declarationOf(copy, leave));
    assertZeroDelta(original, copy);
  }

  // ═══ 4 · ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО · С ПОДМЯНА НА ЗАЯВКИТЕ ═══════════════

  @Test
  @DisplayName("⚠️ Неплатен над 30 / самоотлъчка / НЕПРЕДСТАВЕН · заявката МАХНАТА между прочитите")
  void outsideInsuranceDaysSurviveEvenWhenTheRequestIsGone() {
    BgPaySlip original = confirmedSlip(NORM);
    original.setBgLeaveDaysOutside(new BigDecimal("3.00"));
    addLine(original, lineType(100, "pl_osnovna", true), "1036.36");
    recomputed(original);

    BgPaySlip copy = correct(original);

    // Оригиналът се чете, докато заявката още е налице.
    BgD1Record before = declarationOf(original, row("2026-12-07", "2026-12-09", "3", null));
    // ⚠️ КОПИЕТО СЕ ЧЕТЕ БЕЗ НЕЯ — точно случаят, за който ретро-потокът съществува: корекцията се
    // прави месеци по-късно, а заявките дотогава се редактират, изтриват и пренасочват. Без
    // замразеното число копието щеше да преброи наново ДНЕШНОТО състояние.
    BgD1Record after = declarationOf(copy);

    assertEquals("19", before.getT161WorkedDays(), "котвата: 22 − 3 извън осигуряването");
    assertEquals(
        "19",
        after.getT161WorkedDays(),
        "⚠️ КОПИЕТО ПАЗИ ЧИСЛОТО НА ОНЗИ МЕСЕЦ. Без bgLeaveDaysOutside и дискриминатора то щеше да"
            + " прочете нула заявки → т. 16.1 = 22, тоест да обяви три дни в осигуряване, за които"
            + " вноски няма и никога не е имало.");
    assertEquals(
        0,
        new BigDecimal("3.00").compareTo(copy.getBgLeaveDaysOutside()),
        "изваждаемото е замразено на копието");
    assertSameDays(before, after);
    assertZeroDelta(original, copy);
  }

  // ═══ 5 · ДЕКЛАРИРАНИЯТ ДОХОД ПРИ ДРУГ ПЛАТЕЦ ═════════════════════════════

  @Test
  @DisplayName("⚠️ стояща 1800, вдигнат признак, бруто 1200 → копието носи 1200,00, не 500,00")
  void theNoOtherPayerFlagSurvivesAndKeepsTheInsuranceIncome() {
    BgPaySlip original = confirmedSlip(NORM);
    // Договорът носи СТОЯЩА декларация 1800,00 — лицето обикновено има втори работодател.
    original.getEmploymentContract().setBgOtherPayerInsurableIncome(new BigDecimal("1800.00"));
    // Но ТОЗИ месец другият платец не е осигурявал и операторът го е обявил.
    original.setBgNoOtherPayerIncome(true);
    addLine(original, lineType(100, "pl_osnovna", true), "1200.00");
    recomputed(original);

    assertEquals(
        0,
        new BigDecimal("1200.00").compareTo(original.getBgInsuranceIncome()),
        "котвата: с вдигнат признак координацията по КСО чл. 6 ал. 11 НЕ се прилага");

    BgPaySlip copy = correct(original);

    assertTrue(
        copy.getBgNoOtherPayerIncome(),
        "⚠️ ПРИЗНАКЪТ Е ЗАМРАЗЕН ФАКТ ЗА ОНЗИ МЕСЕЦ. Пропуснат, копието чете FALSE от генерирания"
            + " getter и otherPayerInsurableIncome пада на СТОЯЩАТА стойност на договора — точно"
            + " пропадането, заради което полето изобщо съществува.");
    assertEquals(
        0,
        new BigDecimal("1200.00").compareTo(copy.getBgInsuranceIncome()),
        "⚠️ И ТОВА Е ЧИСЛОТО. Без признака recompute(copy) презаписва заглавната сума през"
            + " координирана база: 2300,00 таван − 1800,00 стояща = 500,00. Разлика от 700,00 в"
            + " осигурителния"
            + " доход на коригиращата декларация, без никой да е пипал копието.");
    assertZeroDelta(original, copy);
  }

  @Test
  @DisplayName("⚠️ и ЗАЛИЧАВАЩОТО копие не се отказва като „разминато с оригинала“")
  void thedeletionCopyNoLongerTripsTheAlteredGuard() {
    BgPaySlip original = confirmedSlip(NORM);
    original.getEmploymentContract().setBgOtherPayerInsurableIncome(new BigDecimal("1800.00"));
    original.setBgNoOtherPayerIncome(true);
    addLine(original, lineType(100, "pl_osnovna", true), "1200.00");
    recomputed(original);

    BgPaySlip copy = service().voidRecordFor(original);

    assertNull(
        BgPaySlipResolver.refuseAlteredDeletion(copy),
        "⚠️ ТУК НЕ БИВА ДА ИЗЛИЗА ОТКАЗ „Заличаващият фиш се е разминал с оригинала по"
            + " осигурителен доход: 500,00 срещу 1200,00“ за копие, което операторът НЕ е пипал."
            + " Пазачът е верен, стига двете полета да се копират.");
  }

  // ═══ 6 · Т. 14/15 — ОБЯВЕНАТА ГРАНИЦА ════════════════════════════════════

  @Test
  @DisplayName("⚠️ ГРАНИЦА · т. 14/15 съвпадат само докато заявките са непокътнати")
  void theInterruptionsAreLiveForBothSlipsAndThatIsDeclared() {
    BgPaySlip original = confirmedSlip(NORM);
    original.setBgLeaveDaysOutside(new BigDecimal("3.00"));
    addLine(original, lineType(100, "pl_osnovna", true), "1036.36");
    recomputed(original);
    BgPaySlip copy = correct(original);

    Object[] leave = row("2026-12-07", "2026-12-09", "3", null);
    assertEquals(
        1,
        read(original, leave).interruptions.size(),
        "регресия: при налична заявка оригиналът вижда едно прекъсване");
    assertEquals(
        1,
        read(copy, leave).interruptions.size(),
        "и копието — СЪЩОТО, защото чете СЪЩАТА жива заявка");

    assertTrue(
        read(copy).interruptions.isEmpty(),
        "⚠️ ОБЯВЕНА ГРАНИЦА. Махне ли се заявката, копието губи т. 14/15 —"
            + " прекъсванията са ИНТЕРВАЛИ, а фишът замразява само БРОЕВЕ и няма къде да ги пази."
            + " Дните му обаче оцеляват (виж свидетеля с махнатата заявка), тоест т. 16 и т. 14/15"
            + " могат да се разминат. Носител за интервали няма.");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /** Потвърден фиш със замразени нули — основата, върху която всеки свидетел слага своето. */
  private static BgPaySlip confirmedSlip(int workedDays) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setReference("BGPS-2026-12-001");
    slip.setPeriod(period(PeriodRepository.STATUS_CLOSED));
    slip.setBgWorkingDaysInPeriod(NORM);
    slip.setBgWorkedDays(workedDays);
    slip.setBgBaseSalary(SALARY);
    slip.setBgLeaveDays161(BigDecimal.ZERO);
    slip.setBgLeaveDays162(BigDecimal.ZERO);
    slip.setBgLeaveDays163(BigDecimal.ZERO);
    slip.setBgLeaveDays164(BigDecimal.ZERO);
    slip.setBgLeaveDays165(BigDecimal.ZERO);
    slip.setBgLeaveDays16A(BigDecimal.ZERO);
    slip.setBgLeaveDaysOutside(BigDecimal.ZERO);
    slip.setBgLeaveDaysFrozenAt(LocalDate.of(2026, 12, 31).atStartOfDay());
    slip.setBgPaySlipLineList(new ArrayList<BgPaySlipLine>());

    Employee employee = new Employee();
    employee.setId(7L);
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setBgHoursPerDay(new BigDecimal("8.00"));
    contract.setBgInsuredTypeCode("01");
    slip.setEmploymentContract(contract);
    return slip;
  }

  private static Period period(int status) {
    Period p = new Period();
    p.setName("12/2026");
    p.setFromDate(DEC_FROM);
    p.setToDate(DEC_TO);
    p.setStatusSelect(status);
    return p;
  }

  private static BgPaySlipLineType lineType(int number, String code, boolean insurable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setCode(code);
    t.setName(code);
    t.setBgSign(BgPaySlipResolver.CREDIT);
    t.setBgIsInsuranceIncome(insurable);
    t.setBgIsTaxable(number == 100);
    t.setBgIsPermanentCharacter(false);
    return t;
  }

  private static void addLine(BgPaySlip slip, BgPaySlipLineType type, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    line.setBgLineNumber(type.getNumber());
    line.setName(type.getName());
    line.setBgSign(type.getBgSign());
    line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
    line.setBgIsTaxable(type.getBgIsTaxable());
    line.setBgAmount(new BigDecimal(amount));
    line.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(line);
  }

  /** Перото на здравната вноска по ЗЗО чл. 40 ал. 1 т. 5 — разпознава се по базата, не по номер. */
  private static void addHealthAbsenceLine(
      BgPaySlip slip, String base, String ratePct, String amount) {
    BgPaySlipLineType type = lineType(516, "pl_zov_rabotodatel", false);
    addLine(slip, type, amount);
    BgPaySlipLine line = slip.getBgPaySlipLineList().get(slip.getBgPaySlipLineList().size() - 1);
    // ⚠️ ФОНДЪТ Е ЗАДЪЛЖИТЕЛЕН: pointOf чете точката от BgContributionFund.bgD1Field и чак после
    // дискриминаторът я ПРЕНАСОЧВА от т. 22.1 към т. 18. Без фонда редът няма точка и не стига до
    // специалните бази — тоест фикстурата щеше да мери липса, а не число.
    BgContributionFund health = new BgContributionFund();
    health.setCode("HEALTH");
    health.setBgD1Field(BgD1Point.T22_1_HEALTH);
    line.setBgContributionFund(health);
    line.setBgHealthBaseKind(BgD1ContributionAggregator.BASE_ART40_1_5);
    line.setBgQuantity(new BigDecimal(base));
    line.setBgRate(new BigDecimal(ratePct));
  }

  private static void recomputed(BgPaySlip slip) {
    // ⚠️ Сумите на ОРИГИНАЛА се смятат по същия път, по който recompute(copy) смята тези на
    // копието. Въведени на ръка, делтата щеше да е разлика между два различни начина на смятане.
    String status = slip.getStatusSelect();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    service().recompute(slip);
    slip.setStatusSelect(status);
  }

  private static BgPaySlip correct(BgPaySlip original) {
    try {
      return service().correct(original);
    } catch (Exception e) {
      throw new IllegalStateException("correct() не мина: " + e.getMessage(), e);
    }
  }

  /** ⚠️ Нулева делта значи, че корекция БЕЗ промяна не мести нито стотинка към лицето. */
  private static void assertZeroDelta(BgPaySlip original, BgPaySlip copy) {
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(
            BgPaySlipResolver.retroDelta(false, copy.getBgNetTotal(), original.getBgNetTotal())),
        "⚠️ ДЕЛТАТА ТРЯБВА ДА Е НУЛА. Копие без нито една промяна, което мести пари, значи че"
            + " recompute смята копието по друг път от оригинала — и разликата би влязла в текущия"
            + " фиш като доплащане или удръжка без основание. Оригинал "
            + original.getBgNetTotal()
            + " срещу копие "
            + copy.getBgNetTotal());
  }

  /** Всичките точки за ДНИ на двете декларации, наведнъж. */
  private static void assertSameDays(BgD1Record before, BgD1Record after) {
    assertEquals(before.getT161WorkedDays(), after.getT161WorkedDays(), "т. 16.1");
    assertEquals(before.getT162SickDays(), after.getT162SickDays(), "т. 16.2");
    assertEquals(before.getT163ChildCareDays(), after.getT163ChildCareDays(), "т. 16.3");
    assertEquals(before.getT164NoContributionDays(), after.getT164NoContributionDays(), "т. 16.4");
    assertEquals(before.getT165UnpaidLeaveDays(), after.getT165UnpaidLeaveDays(), "т. 16.5");
    assertEquals(before.getT16aEmployerSickDays(), after.getT16aEmployerSickDays(), "т. 16.А");
    assertEquals(before.getT16Days(), after.getT16Days(), "позиции 1 и 2 по заб. 1");
  }

  /** Т. 17 и т. 18 идват от ПЕРАТА, които се копират — регресионен пазач, не поправка. */
  private static void assertSpecialHealthBase(
      BgD1Record before, BgD1Record after, String expectedBase) {
    assertEquals(
        new BigDecimal(expectedBase),
        before.getT17Art4015Income(),
        "котвата: оригиналът носи базата по ЗЗО чл. 40 ал. 1 т. 5");
    assertEquals(
        before.getT17Art4015Income(),
        after.getT17Art4015Income(),
        "⚠️ Т. 17 се чете от bgQuantity на перото с база ART40_1_5, а перата се копират — тоест тук"
            + " нищо не е било счупено и този assert пази да не се счупи.");
    assertEquals(before.getT18HealthRateOnT17(), after.getT18HealthRateOnT17(), "т. 18 — ставката");
  }

  // ═══ ДЕКЛАРАЦИЯТА ════════════════════════════════════════════════════════

  private static BgD1Record declarationOf(BgPaySlip slip, Object[]... leaveRows) {
    BgD1DaySource.Outcome out = read(slip, leaveRows);
    Map<String, Integer> byBucket = BgD1DayBuckets.sum(out.entries);
    BgD1Record record = new BgD1Record();
    BgInsuredType type = new BgInsuredType();
    type.setCode("01");
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1RecordBuilder.fillDays(
        record,
        slip,
        slip.getEmploymentContract(),
        type,
        byBucket,
        out.outsideInsuranceDays,
        null,
        findings,
        "01");
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();
    Map<String, BigDecimal> bases = BgD1ContributionAggregator.specialBasesByPoint(lines);
    record.setT17Art4015Income(bases.get(BgD1Point.T17_ART40_1_5_INCOME));
    record.setT18HealthRateOnT17(
        BgD1ContributionAggregator.ratesByPoint(lines, BgPaySlipResolver.CREDIT)
            .get(BgD1Point.T18_HEALTH_ON_T17));
    for (BgD1FindingDto f : findings) {
      assertTrue(
          !"D1_T161_NEGATIVE_REMAINDER".equals(f.getCode()),
          "отрицателен остатък в т. 16.1: " + f.getMessage());
    }
    return record;
  }

  private static BgD1DaySource.Outcome read(BgPaySlip slip, Object[]... rows) {
    return new Source(Arrays.asList(rows)).read(slip, period(PeriodRepository.STATUS_CLOSED));
  }

  /** Ред на {@code BgD1DaySource#leaveRows} — седем колони, заявка в ДНИ. */
  private static Object[] row(String from, String to, String days, String d1Code) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(days),
      1,
      d1Code,
      d1Code == null ? "Непредставен болничен" : "Неплатен до 30 дни",
      Boolean.valueOf(d1Code != null)
    };
  }

  /** Същото, но заявката е в ЧАСОВЕ ({@code unitSelect = 2}). */
  private static Object[] hours(String from, String to, String qty, String d1Code) {
    Object[] r = row(from, to, qty, d1Code);
    r[3] = 2;
    return r;
  }

  private static final class Source extends BgD1DaySource {
    private final List<Object[]> rows;

    private Source(List<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return rows;
    }
  }

  // ═══ УСЛУГАТА БЕЗ БАЗА ═══════════════════════════════════════════════════

  private static Service service() {
    return new Service();
  }

  /**
   * ⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ КЪМ БАЗАТА. {@code retroCopyOf} и {@code recompute} вървят
   * ИСТИНСКИ — тоест махне ли някой ред от копирането, свидетелите падат. Същият състав шевове като
   * в {@code BgPaySlipCorrectionTest}.
   */
  private static final class Service extends BgPaySlipService {

    BgPaySlip voidRecordFor(BgPaySlip original) {
      try {
        return voidRecord(original);
      } catch (Exception e) {
        throw new IllegalStateException("voidRecord() не мина: " + e.getMessage(), e);
      }
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.resolved(
          Collections.<com.axelor.apps.bglocalization.service.BgContributionLine>emptyList());
    }

    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("неутрално за този тест");
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }

    @Override
    protected void save(BgPaySlip slip) {}

    @Override
    protected void savePeriod(Period period) {}

    @Override
    protected void logAdjustStart(Period period) {}

    @Override
    protected void logAdjustEnd(Period period) {}

    @Override
    protected LocalDate today() {
      return LocalDate.of(2027, 1, 20);
    }
  }
}
