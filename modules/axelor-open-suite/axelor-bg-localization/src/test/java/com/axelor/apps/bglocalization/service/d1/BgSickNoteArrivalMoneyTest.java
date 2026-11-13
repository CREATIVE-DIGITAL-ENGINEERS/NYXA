package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveOutcome;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveServiceOnDeclaredStage;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРИСТИГНАЛИЯТ БОЛНИЧЕН ВЪРХУ РЕТРО-КОПИЕТО — дните, Д1 и капанът със зануляването.
 *
 * <p><b>Какво пази този файл.</b> {@code BgSickLeaveService#applyArrival} е втората врата към
 * същата сметка и трябва да направи ТРИ неща в ЕДНА стъпка: да намали отработените дни, да напише
 * разбива осигурител/ДОО и да ЗАНУЛИ {@code bgLeaveDaysOutside}. Пропуснатото трето е тихо грешно:
 * копието идва от {@code retroCopyOf} със замразените дни на непредставения болничен, а {@code
 * BgD1RecordBuilder#fillDays} вади outside ОТ ВЕЧЕ НАМАЛЕНИТЕ отработени — тоест същите три дни
 * излизат ДВА пъти и т. 16.1 пада от 19 на <b>16</b>.
 *
 * <p><b>⚠️ ПРИЕМНИЯТ КРИТЕРИЙ СА НАХОДКИТЕ ЗА ДНИ БЕЗ ПЕРА.</b> {@code D1_T16A_DAYS_LINE_MISMATCH}
 * и {@code D1_T162_DAYS_LINE_MISMATCH} палят, когато дните нямат своите пера. Копие, което носи дни
 * без перо 195 или без перо 516 с база {@code ART40_1_5}, е недовършено — и това вече го казва
 * пазачът, а не този тест.
 */
class BgSickNoteArrivalMoneyTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);
  private static final int NORM = 22;
  private static final BigDecimal SALARY = new BigDecimal("1200.00");

  // ═══ 1 · КАПАНЪТ ═════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ КАПАНЪТ · без зануляване на outside т. 16.1 пада от 19 на 16")
  void withoutZeroingTheOutsideDaysPointSixteenOneCollapses() {
    // Копието, каквото го оставя retroCopyOf: отработени вече намалени на 19, но outside още 3.
    BgPaySlip halfDone = retroCopy();
    halfDone.setBgWorkedDays(19);
    halfDone.setBgSickDaysEmployer(2);
    halfDone.setBgSickDaysDoo(1);
    halfDone.setBgLeaveDaysOutside(new BigDecimal("3.00"));

    assertEquals(
        "16",
        declarationOf(halfDone).getT161WorkedDays(),
        "⚠️ ТОВА Е ЧИСЛОТО, КОЕТО ЗАНУЛЯВАНЕТО СПИРА. fillDays смята отработени − outside, а"
            + " отработените ВЕЧЕ са намалени от applyDays — тоест същите три дни излизат два пъти."
            + " Ако този assert някога даде 19, значи fillDays е спрял да вади outside и"
            + " зануляването е станало излишно; ако даде друго, значи някой е пипнал разбива.");

    // Същото копие, но минало през вратата.
    BgPaySlip done = retroCopy();
    new Engine().applyArrival(done, arrivalInput());

    assertEquals(19, done.getBgWorkedDays(), "отработените падат 22 → 19");
    assertEquals(2, done.getBgSickDaysEmployer(), "два дни за сметка на осигурителя");
    assertEquals(1, done.getBgSickDaysDoo(), "един за ДОО");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(done.getBgLeaveDaysOutside()),
        "⚠️ И ТРЕТОТО — В СЪЩАТА СТЪПКА. Разделено на две извиквания, между тях има състояние, в"
            + " което декларацията е тихо грешна.");
    assertEquals("19", declarationOf(done).getT161WorkedDays(), "и т. 16.1 остава 19");
  }

  // ═══ 2 · Д1 НА КОПИЕТО ═══════════════════════════════════════════════════

  /**
   * ⚠️ КОДЪТ В Т. 1 НЕ СЕ МЕРИ ТУК, И ТОВА Е НАРОЧНО. Помощникът вика {@code
   * BgD1RecordBuilder#fillDays}, а буквата „К“ се пише в пълния {@code build(...)} — тоест assert
   * за т. 1 през тази врата би бил ВАКУУМНО зелен: щеше да минава, защото никой не я пълни.
   * Измерена е там, където се пише: {@code BgD1RecordTest} („К“ за коригиращ фиш, „З“ за
   * заличаващ). Тук се мерят ДНИТЕ.
   *
   * <p>А че двата записа вече са в РАЗЛИЧНИ декларации — редовната взима само фишове без корекция,
   * коригиращата само ретро-копията — е измерено срещу база в {@code BgD1KindQueryDbTest}.
   */
  @Test
  @DisplayName("⚠️ Д1 на копието · т. 16.А = 2, т. 16.2 = 1, Σ = 22 — и НУЛА находки за пера")
  void thecorrectingDeclarationCarriesTheSickDaysAndTheirLines() {
    BgPaySlip before = original();
    BgD1Record beforeD1 = declarationOf(before);
    assertEquals("19", beforeD1.getT161WorkedDays(), "оригинал: 22 − 3 извън осигуряването");
    assertEquals("0", beforeD1.getT162SickDays(), "оригинал: нула дни ДОО");
    assertEquals("0", beforeD1.getT16aEmployerSickDays(), "оригинал: нула дни осигурител");
    assertEquals("19", beforeD1.getT16Days(), "оригинал: Σ по заб. 1 е 19");

    BgPaySlip copy = retroCopy();
    List<BgSickLeaveOutcome> outcomes = new Engine().applyArrival(copy, arrivalInput());
    assertEquals(2, outcomes.size(), "двете присъди: перото по чл. 40 ал. 5 и здравната вноска");
    for (BgSickLeaveOutcome outcome : outcomes) {
      assertTrue(
          outcome.isComputed(),
          "⚠️ И ДВЕТЕ трябва да се сметнат — иначе находките за дни без пера палят. Присъда: "
              + outcome.lineCode
              + " → "
              + outcome.detail);
    }

    BgD1Record afterD1 = declarationOf(copy);
    assertEquals("19", afterD1.getT161WorkedDays(), "т. 16.1 остава 19");
    assertEquals("1", afterD1.getT162SickDays(), "⚠️ т. 16.2 вече НЕ е празна");
    assertEquals("2", afterD1.getT16aEmployerSickDays(), "⚠️ нито т. 16.А");
    assertEquals(
        "22",
        afterD1.getT16Days(),
        "⚠️ И СБОРЪТ СЕ ВДИГА 19 → 22: трите дни се връщат в осигурителния стаж, откъдето"
            + " непредставеният болничен ги беше извадил.");

    assertNoLineMismatch(copy);
  }

  @Test
  @DisplayName("⚠️ ПРИЕМНИЯТ КРИТЕРИЙ · копие с дни, но БЕЗ перата, пали находките")
  void daysWithoutTheirLinesTripTheGuardsFromThePreviousRound() {
    BgPaySlip naked = retroCopy();
    naked.setBgWorkedDays(19);
    naked.setBgSickDaysEmployer(2);
    naked.setBgSickDaysDoo(1);
    naked.setBgLeaveDaysOutside(BigDecimal.ZERO);

    List<String> codes = findingCodes(naked);
    assertTrue(
        codes.contains("D1_T16A_DAYS_LINE_MISMATCH"),
        "⚠️ Дни по чл. 40 ал. 5 без перо 195: " + codes);
    assertTrue(
        codes.contains("D1_T162_DAYS_LINE_MISMATCH"),
        "⚠️ Дни за ДОО без перо 516 с база ART40_1_5: " + codes);
  }

  // ═══ 3 · ОРИГИНАЛЪТ СЛЕД ОТМЯНАТА НА ЗАЯВКАТА ════════════════════════════

  /**
   * ⚠️ ЗАМРАЗЕНАТА ПОЛОВИНА НА ОРИГИНАЛА НЕ МЪРДА — измерено, не изведено.
   *
   * <p>Отмяната на заявката („Непредставен болничен“ → статус 5) маха реда от ВСИЧКИТЕ живи
   * прочита: и четирите заявки филтрират {@code statusSelect = 3}. Въпросът е дали това размърдва
   * вече подадената декларация на ОРИГИНАЛА. За т. 16 отговорът е НЕ и той е структурен: {@code
   * bgLeaveDaysFrozenAt} е вдигнат, тоест {@code BgD1DaySource} чете ЗАМРАЗЕНИТЕ полета на фиша и
   * изобщо не пита заявките за дни.
   */
  @Test
  @DisplayName(
      "⚠️ ОРИГИНАЛЪТ · т. 16 е БИТ ПО БИТ същата след отмяната — замразеното не се чете наново")
  void theOriginalsFrozenPointSixteenSurvivesTheCancellation() {
    BgPaySlip slip = original();

    BgD1Record before = declarationOf(slip, unsubmittedLeaveRow());
    BgD1Record after = declarationOf(slip); // заявката е отменена → изчезва от живия прочит

    assertEquals(before.getT161WorkedDays(), after.getT161WorkedDays(), "т. 16.1");
    assertEquals(before.getT162SickDays(), after.getT162SickDays(), "т. 16.2");
    assertEquals(before.getT163ChildCareDays(), after.getT163ChildCareDays(), "т. 16.3");
    assertEquals(before.getT164NoContributionDays(), after.getT164NoContributionDays(), "т. 16.4");
    assertEquals(before.getT165UnpaidLeaveDays(), after.getT165UnpaidLeaveDays(), "т. 16.5");
    assertEquals(before.getT16aEmployerSickDays(), after.getT16aEmployerSickDays(), "т. 16.А");
    assertEquals(before.getT16Days(), after.getT16Days(), "позиции 1 и 2 по заб. 1");
    assertEquals(
        "19",
        after.getT161WorkedDays(),
        "⚠️ И ЧИСЛОТО Е НАЗОВАНО: 22 отработени − 3 извън осигуряването. Идва от замразеното"
            + " bgLeaveDaysOutside, не от заявката — затова отмяната ѝ не го мести.");
  }

  /**
   * <b>⚠️ ГРАНИЦА — Т. 14/15 НА ОРИГИНАЛА ПАДАТ СЛЕД ОТМЯНАТА, И ТОВА Е ПРИЕТО.</b>
   *
   * <p>Прекъсванията по КСО чл. 10 ал. 2 са ИНТЕРВАЛИ, не броеве, и фишът няма къде да ги замрази —
   * обявена граница. Затова те се строят от ЖИВИТЕ заявки при всяко генериране, а отменената заявка
   * изчезва от тях: <b>един отрязък 07.12–09.12 → нула отрязъка</b>.
   *
   * <p><b>ПОДАДЕНИЯТ ФАЙЛ Е НЕИЗМЕНЕН — артефактът на подаването не се пипа от нищо тук.</b> Пътят
   * до НОВ файл е ЗАТВОРЕН с ДВЕ независими брави: в редовната декларация записът е без код дори за
   * ретро-копието ({@code BgD1Emitter#emit}), а повторното ѝ генериране за период с ПРИЕТО подаване
   * се отказва ({@code BgD1GenerationService#refuseRegularOverAccepted}). Смесен файл не се
   * произвежда — тоест находката {@code D1_MIXED_CODES} остава като пазач на регресия, а не като
   * единствената следа. Виж обърнатия ГРАНИЦА-свидетел в {@code BgD1GenerationOrderTest}.
   *
   * <p>Съдържателно новото състояние е и вярното: след пристигането дните се зачитат за
   * осигурителен стаж (т. 16.А и т. 16.2 на коригиращата декларация), тоест осигуряването през тях
   * не е било прекъснато.
   *
   * <p><b>Носителят за интервали е в списъка за бъдеща миграция</b> — заедно с m2o {@code
   * LeaveRequest → BgSickLeave} вместо днешната следа в {@code comments}.
   */
  @Test
  @DisplayName("⚠️ ГРАНИЦА · т. 14/15 на оригинала падат от 1 отрязък на 0 след отмяната")
  void theOriginalsInterruptionsMoveAndThatIsTheDeclaredBoundary() {
    BgPaySlip slip = original();

    assertEquals(
        1,
        interruptionsOf(slip, unsubmittedLeaveRow()).size(),
        "преди отмяната: един отрязък, 07.12–09.12");
    assertEquals(
        0,
        interruptionsOf(slip).size(),
        "⚠️ СЛЕД ОТМЯНАТА: нула. Интервалите не са замразени никъде и се строят от живите"
            + " заявки — отменената изчезва от тях. Ново състояние, което СЪДЪРЖАТЕЛНО е вярното"
            + " (дните вече се зачитат за стаж), но се разминава с подаденото за оригинала.");
  }

  private static Object[] unsubmittedLeaveRow() {
    return new Object[] {
      LocalDate.of(2026, 12, 7).atStartOfDay(),
      LocalDate.of(2026, 12, 9).atTime(23, 59, 59),
      new BigDecimal("3"),
      1,
      null,
      "Непредставен болничен",
      Boolean.FALSE
    };
  }

  private static List<LocalDate[]> interruptionsOf(BgPaySlip slip, Object[]... rows) {
    return new Source(java.util.Arrays.asList(rows)).read(slip, period()).interruptions;
  }

  /** Източник с подменен шев към базата — редовете на заявките се подават наготово. */
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

  // ═══ 4 · ПАЗАЧЪТ НА ВРАТАТА ══════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ applyArrival НЕ пипа редовен фиш — вратата е само за коригиращо копие")
  void thearrivalDoorRefusesAnythingButACorrectingDraft() {
    BgPaySlip regular = original();
    regular.setStatusSelect(BgPaySlipResolver.DRAFT);
    regular.setBgIsCorrection(false);
    int workedBefore = regular.getBgWorkedDays();

    assertTrue(
        new Engine().applyArrival(regular, arrivalInput()).isEmpty(),
        "редовната чернова минава по редовния път — generate(), не тази врата");
    assertEquals(workedBefore, regular.getBgWorkedDays(), "и нито едно число не е пипнато");
    assertEquals(
        0,
        new BigDecimal("3.00").compareTo(regular.getBgLeaveDaysOutside()),
        "⚠️ включително outside: зануляването НЕ бива да се случва извън вратата");

    BgPaySlip confirmed = retroCopy();
    confirmed.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    assertTrue(
        new Engine().applyArrival(confirmed, arrivalInput()).isEmpty(),
        "потвърденият фиш е замразен — и тази врата не го отваря");
  }

  @Test
  @DisplayName("⚠️ generate() и isEligibleDraft остават НЕРАЗХЛАБЕНИ")
  void theoriginalGateIsUnchanged() {
    BgPaySlip copy = retroCopy();
    assertTrue(
        new Engine().generate(copy, arrivalInput()).isEmpty(),
        "⚠️ generate() ВСЕ ОЩЕ пропуска коригиращия фиш. Това е вярно решение: епизодите може да са"
            + " коригирани в НОИ междувременно, а prepare() не бива да преизчислява историята"
            + " като страничен ефект. Пристигането е ИЗРИЧНО действие и има своя врата.");
    assertEquals(22, copy.getBgWorkedDays(), "и нищо не е пипнато по този път");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /** Потвърденият фиш за декември: 3 дни непредставен болничен, 22 отработени, outside 3. */
  private static BgPaySlip original() {
    BgPaySlip slip = base();
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setBgWorkedDays(NORM);
    slip.setBgLeaveDaysOutside(new BigDecimal("3.00"));
    line(slip, 100, "pl_osnovna", "1036.36");
    return slip;
  }

  /** Копието, както го оставя retroCopyOf: замразените числа на оригинала, статус ЧЕРНОВА. */
  private static BgPaySlip retroCopy() {
    BgPaySlip slip = base();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgIsCorrection(true);
    slip.setBgWorkedDays(NORM);
    slip.setBgLeaveDaysOutside(new BigDecimal("3.00"));
    line(slip, 100, "pl_osnovna", "1036.36");
    return slip;
  }

  private static BgPaySlip base() {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("BGPS-2026-12-001");
    slip.setBgWorkingDaysInPeriod(NORM);
    slip.setBgBaseSalary(SALARY);
    slip.setPeriod(period());
    slip.setBgLeaveDays161(BigDecimal.ZERO);
    slip.setBgLeaveDays162(BigDecimal.ZERO);
    slip.setBgLeaveDays163(BigDecimal.ZERO);
    slip.setBgLeaveDays164(BigDecimal.ZERO);
    slip.setBgLeaveDays165(BigDecimal.ZERO);
    slip.setBgLeaveDays16A(BigDecimal.ZERO);
    slip.setBgLeaveDaysFrozenAt(DEC_TO.atStartOfDay());
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

  private static Period period() {
    Period p = new Period();
    p.setName("12/2026");
    p.setFromDate(DEC_FROM);
    p.setToDate(DEC_TO);
    p.setStatusSelect(PeriodRepository.STATUS_CLOSED);
    return p;
  }

  /** Входът с пристигналия епизод — 07–09.12, нов, общо заболяване. */
  private static BgPayrollInput arrivalInput() {
    return BgPayrollInput.builder()
        .periodFrom(DEC_FROM)
        .periodTo(DEC_TO)
        .parameterReferenceDate(DEC_FROM)
        .workingDaysInPeriod(NORM)
        .workedDays(NORM)
        .baseSalary(SALARY)
        .insuranceServiceMonths(120)
        .insuredTypeCode("01")
        .laborCategory("3")
        .sickLeaveEpisode(
            new BgPayrollInput.SickLeaveEpisode(
                LocalDate.of(2026, 12, 7),
                LocalDate.of(2026, 12, 9),
                false,
                "БЛ-77/2026",
                "sick_er",
                1L))
        .build();
  }

  private static void line(BgPaySlip slip, int number, String code, String amount) {
    BgPaySlipLineType type = new BgPaySlipLineType();
    type.setNumber(number);
    type.setCode(code);
    type.setName(code);
    type.setBgSign(BgPaySlipResolver.CREDIT);
    type.setBgIsInsuranceIncome(true);
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    line.setBgLineNumber(number);
    line.setName(code);
    line.setBgSign(BgPaySlipResolver.CREDIT);
    line.setBgIsInsuranceIncome(true);
    line.setBgAmount(new BigDecimal(amount));
    line.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(line);
  }

  /**
   * Енджинът с подменени ЧЕТЦИ към базата — правилата вървят истински.
   *
   * <p>⚠️ Подменени са речникът на отсъствията, речникът с типове пера, календарът, параметърът за
   * здравната база и ставката, и записът на редовете по т. 9. {@code applyDays}, {@code
   * applyPeriodLines} и разбивът по чл. 40 ал. 5 КСО вървят непокътнати.
   */
  private static final class Engine extends BgSickLeaveServiceOnDeclaredStage {

    /**
     * Базата на 195 (чл. 40 ал. 5 КСО) — постоянното месечно възнаграждение. ⚠️ Фикстурата няма
     * клас, степен и постоянни пера, тоест то Е основната заплата; самото {@code M} се мери в
     * {@code BgPaySlipRetroGeneratorsTest#theSickPayBaseCarriesThePermanentAdditions}.
     */
    @Override
    protected java.math.BigDecimal monthlyPermanentPay(
        com.axelor.apps.bglocalization.db.BgPaySlip slip,
        com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
      return slip.getBgBaseSalary();
    }

    @Override
    protected BgAbsenceType absenceType(String code) {
      BgAbsenceType type = new BgAbsenceType();
      type.setCode(code);
      type.setPayer("employer");
      type.setCompensationPct(new BigDecimal("70"));
      type.setRequiresSickNote(true); // A4: вид на болничен лист, като в сийда
      type.setCountsAsInsuranceService(true);
      return type;
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      BgPaySlipLineType type = new BgPaySlipLineType();
      type.setCode(code);
      type.setNumber(195);
      type.setName(code);
      type.setBgSign(BgPaySlipResolver.CREDIT);
      type.setBgIsInsuranceIncome(true);
      type.setBgIsTaxable(false);
      return type;
    }

    @Override
    protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
      return Collections.emptySet();
    }

    /** ⚠️ Шевът минава през BgPaySlipService (Guice); тук се строи направо, със СЪЩИТЕ флагове. */
    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgAmount(amount);
      return line;
    }

    @Override
    protected BigDecimal healthAbsenceBase(LocalDate date) {
      return new BigDecimal("620.20");
    }

    @Override
    protected BigDecimal healthEmployerRatePct(BgPayrollInput input, LocalDate date) {
      return new BigDecimal("4.80");
    }

    /** ⚠️ Редовете по т. 9 искат ЗАПИСАН лист; тук твърдението е за дните и перата. */
    @Override
    protected void applyPeriodLines(
        BgPaySlip slip,
        BgPayrollInput input,
        com.axelor.apps.bglocalization.service.payroll.BgSickLeaveDays days) {}

    /** Перото 516 се пише през BgContributionLineService (Guice) — тук се вписва направо. */
    @Override
    protected void writeSpecialHealthBase(
        BgPaySlip slip, BigDecimal base, BigDecimal ratePercent, BigDecimal amount) {
      BgPaySlipLineType type = new BgPaySlipLineType();
      type.setNumber(516);
      type.setCode("pl_zov_rabotodatel");
      type.setName("ЗОВ работодател");
      type.setBgSign(BgPaySlipResolver.CREDIT);
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(516);
      line.setName(type.getName());
      line.setBgSign(BgPaySlipResolver.CREDIT);
      line.setBgAmount(amount);
      line.setBgQuantity(base);
      line.setBgRate(ratePercent);
      line.setBgHealthBaseKind(BgD1ContributionAggregator.BASE_ART40_1_5);
      BgContributionFund fund = new BgContributionFund();
      fund.setCode("HEALTH");
      fund.setBgD1Field(BgD1Point.T22_1_HEALTH);
      line.setBgContributionFund(fund);
      line.setPaySlip(slip);
      slip.getBgPaySlipLineList().add(line);
    }
  }

  // ═══ ДЕКЛАРАЦИЯТА ════════════════════════════════════════════════════════

  private static BgD1Record declarationOf(BgPaySlip slip, Object[]... leaveRows) {
    return build(slip, leaveRows).record;
  }

  private static List<String> findingCodes(BgPaySlip slip) {
    List<String> out = new ArrayList<>();
    for (BgD1FindingDto f : build(slip).findings) {
      out.add(f.getCode());
    }
    return out;
  }

  private static void assertNoLineMismatch(BgPaySlip slip) {
    List<String> codes = findingCodes(slip);
    assertFalse(
        codes.contains("D1_T16A_DAYS_LINE_MISMATCH"),
        "⚠️ ПРИЕМНИЯТ КРИТЕРИЙ: дни по чл. 40 ал. 5 БЕЗ перо 195. Находки: " + codes);
    assertFalse(
        codes.contains("D1_T162_DAYS_LINE_MISMATCH"),
        "⚠️ И вторият: дни за ДОО без перо 516 с база ART40_1_5. Находки: " + codes);
  }

  private static final class Built {
    private final BgD1Record record;
    private final List<BgD1FindingDto> findings;

    private Built(BgD1Record record, List<BgD1FindingDto> findings) {
      this.record = record;
      this.findings = findings;
    }
  }

  private static Built build(BgPaySlip slip, Object[]... leaveRows) {
    // ⚠️ Кофите на ОТПУСКИТЕ и изваждаемото идват от ЗАМРАЗЕНИТЕ полета (frozenAt е вдигнат), а
    // болничните дни — от двата броя. Живите редове влияят само на прекъсванията, и точно това
    // прави свидетелят за т. 14/15 честен.
    BgD1DaySource.Outcome live =
        new Source(java.util.Arrays.asList(leaveRows)).read(slip, period());
    List<BgD1DayBuckets.Entry> entries = new ArrayList<>(live.entries);
    Map<String, Integer> byBucket = BgD1DayBuckets.sum(entries);
    int outside = live.outsideInsuranceDays;
    BgD1Record record = new BgD1Record();
    BgInsuredType type = new BgInsuredType();
    type.setCode("01");
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1RecordBuilder.fillDays(
        record, slip, slip.getEmploymentContract(), type, byBucket, outside, null, findings, "01");
    return new Built(record, findings);
  }
}
