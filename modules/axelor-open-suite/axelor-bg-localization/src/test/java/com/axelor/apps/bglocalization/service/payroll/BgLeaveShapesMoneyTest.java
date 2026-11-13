package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТРИТЕ ОТПУСКОВИ ФОРМИ ДО ПАРИТЕ — от заявката за отпуск до сумата на вноската.
 *
 * <p><b>Защо този файл съществува отделно от {@code BgPayrollInputBuilderTest}.</b> Онзи доказва,
 * че трите форми се ПРЕБРОЯВАТ; този доказва, че преброяването МЕСТИ ПАРИ — и точно затова минава
 * през ДВА класа наведнъж: сглобителя и резолвера на здравната вноска. Разделени, двете твърдения
 * се четат като „входът стана по-богат“ и „резолверът приема дробни числа“ — а истинската находка е
 * трета и е между тях.
 *
 * <p><b>⚠️ ЗАЩО ТРИТЕ ФОРМИ НЕ БИВА ДА СЕ ОТКАЗВАТ ОТ ПРЕДПАЗЛИВОСТ.</b> Откаже ли {@code
 * BgPayrollInputBuilder.leaveDaysOf} при заявка през границата на периода, при заявка в часове или
 * при дробен сбор, отказът обезсилва ЦЯЛАТА тройка, тоест {@code unpaidLeaveDays} остава {@code
 * null}. А той има точно един консуматор — {@code BgUnpaidLeaveHealthService} — и при {@code null}
 * той не начислява НИЩО. Следствието: ЕДНА заявка в часове някъде през месеца маха здравната вноска
 * по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ от целия фиш. Не като нула на видно място, а като липсващо перо —
 * неразличимо от „не се дължи“.
 *
 * <p>Числата са същите като в {@code BgUnpaidLeaveHealthResolverTest}: база 310,10 (половината от
 * минималния осигурителен доход за самоосигуряващите се от 01.08.2026 г.), ЦЯЛАТА ставка 8,00 на
 * фонд ЗОВ, и декември 2026 г. с 21 работни дни.
 */
class BgLeaveShapesMoneyTest {

  /** {@code HEALTH_UNPAID_LEAVE_BASE} от 01.08.2026 г. */
  private static final BigDecimal HALF_SOL = new BigDecimal("310.1000");

  /** ЦЯЛАТА вноска за фонд ЗОВ — виж {@code RATE_IS_THE_WHOLE_CONTRIBUTION}. */
  private static final BigDecimal TOTAL_RATE = new BigDecimal("8.0000");

  /**
   * Декември 2026: 23 делнични дни, от които 24 и 25.12 са официални празници (четвъртък и петък) →
   * 21 РАБОТНИ дни. 26.12 е събота и не отнема нищо.
   */
  private static final int DECEMBER_WORKING_DAYS = 21;

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  private static final Set<LocalDate> HOLIDAYS =
      new HashSet<>(
          Arrays.asList(
              LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1)));

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  /** Сглобител с подменени шевове — календар и редове на заявките. Без база и без Guice. */
  private static final class Builder extends BgPayrollInputBuilder {

    private List<Object[]> leaves = Collections.emptyList();

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return leaves;
    }

    @Override
    protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
      Set<LocalDate> in = new HashSet<>();
      for (LocalDate day : HOLIDAYS) {
        if (!day.isBefore(from) && !day.isAfter(to)) {
          in.add(day);
        }
      }
      return in;
    }

    @Override
    protected BgWorkingDayService.Count workingDays(LocalDate from, LocalDate to) {
      return null;
    }

    @Override
    protected WorkedTime workedTime(Employee employee, LocalDate from, LocalDate to) {
      return WorkedTime.unknown();
    }

    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyMap();
    }

    @Override
    protected List<Long> compensationDocumentIds(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    @Override
    protected List<BgPayrollInput.SickLeaveEpisode> sickLeaveEpisodesOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    @Override
    protected List<Object[]> extraHoursRows(Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    @Override
    protected DeclaredHours declaredHoursOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return DeclaredHours.unknown();
    }
  }

  private static EmploymentContract contract(String hoursPerDay) {
    Employee employee = new Employee();
    employee.setId(7L);
    EmploymentContract contract = new EmploymentContract();
    contract.setId(42L);
    contract.setEmployee(employee);
    contract.setBgHoursPerDay(new BigDecimal(hoursPerDay));
    contract.setBgInsuredTypeCode("01");
    return contract;
  }

  /**
   * НЕПЛАТЕН отпуск по желание на лицето — категория 7, „unpaid_employee“ в речника.
   *
   * <p>⚠️ Кофата се решава от категорията и признака „платено“, не от името тук — виж {@code
   * BgPayrollInputBuilder#leaveBucketOf}.
   */
  private static Object[] unpaidLeave(String from, String to, String duration, int unit) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(duration),
      unit,
      7,
      Boolean.FALSE,
      12L,
      // ⚠️ Неплатеният отпуск НЕ е по чл. 177 — осмата колона на leaveRows.
      Boolean.FALSE,
      // ⚠️ ДЕВЕТАТА колона: кофата в Декларация обр. 1. „unpaid_own_le30“ (importId 12) носи 16.5
      // по КТ чл. 160 ал. 3, тоест Д1 вади тези дни от т. 16.1 и заплатата ги карвира.
      "16.5",
      // ⚠️ ДЕСЕТАТА колона: countsAsInsuranceService. До 30 работни дни неплатеният отпуск СЕ
      // зачита за осигурителен стаж (КСО чл. 9 ал. 2 т. 3) — тоест този вид НЕ е „извън
      // осигуряването“ и карвът му идва от кофата, не от новия подсбор.
      Boolean.TRUE,
      // ⚠️ ЕДИНАЙСЕТАТА колона: healthContributionRule. „unpaid_own_le30“ е отпускът ПО ЖЕЛАНИЕ на
      // лицето, тоест вноската по б. „б“ е изцяло негова — същото, което този файл вече твърди в
      // името на помощника contributionFor.
      BgUnpaidLeaveHealthResolver.RULE_UNPAID_EMPLOYEE
    };
  }

  /** Вноската за дните, които сглобителят е преброил. */
  private static BgUnpaidLeaveHealthResolver.Outcome contributionFor(BgPayrollInput input) {
    return BgUnpaidLeaveHealthResolver.resolve(
        BgUnpaidLeaveHealthResolver.RULE_UNPAID_EMPLOYEE,
        HALF_SOL,
        TOTAL_RATE,
        // ⚠️ ПОДМНОЖЕСТВОТО, не цялата кофа — точно каквото чете и BgUnpaidLeaveHealthService от
        // разцепването на чл. 157. За този вид двете са РАВНИ (той носи платец), тоест числата на
        // файла не мърдат; прочетено от unpaidLeaveDays, свидетелят щеше да мери СЪСЕДНО число.
        input.unpaidLeaveDaysUnderZzo,
        DECEMBER_WORKING_DAYS);
  }

  // ═══ ФОРМА 1 · ПРЕЗ ГРАНИЦАТА НА ПЕРИОДА ═════════════════════════════════

  @Test
  @DisplayName("⚠️ ПАРИ · заявка през границата ражда вноска за СВОИТЕ четири дни")
  void aStraddlingLeaveNowProducesAContributionForItsFourDays() {
    Builder builder = new Builder();
    // 28.12.2026–08.01.2027, 9 дни: 28–31.12 са четири работни дни, 01.01 е празник, 02–03 са
    // почивни, 04–08.01 са пет. В декемврийския фиш влизат ЧЕТИРИ.
    builder.leaves = Collections.singletonList(unpaidLeave("2026-12-28", "2027-01-08", "9", 1));

    BgPayrollInput input = builder.build(contract("8.00"), DEC_FROM, DEC_TO);
    assertNotNull(
        input.unpaidLeaveDays,
        "⚠️ null ТУК значи, че отказът „заявката пресича границата“ е обезсилил тройката и"
            + " вноската изчезва от фиша.");
    assertEquals(0, new BigDecimal("4").compareTo(input.unpaidLeaveDays), "28–31.12 = 4 дни");

    BgUnpaidLeaveHealthResolver.Outcome out = contributionFor(input);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.COMPUTED, out.status);
    // 310,10 × 8 % = 24,808 за 21 работни дни; за 4 дни → 4,7253… → 4,73
    assertEquals(
        0,
        new BigDecimal("4.73").compareTo(out.amount),
        "24,808 × 4 / 21 = 4,7253 → 4,73. Намерено: " + out.amount);
    assertEquals(BgUnpaidLeaveHealthResolver.Payer.INSURED_PERSON, out.payer);
  }

  // ═══ ФОРМА 2 · В ЧАСОВЕ ══════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПАРИ · четири часа неплатено при 8-часов ден дават 0,5 дни и 0,54 €")
  void fourHoursOfUnpaidLeaveProduceHalfADayAndHalfTheDailyContribution() {
    Builder builder = new Builder();
    builder.leaves = Collections.singletonList(unpaidLeave("2026-12-07", "2026-12-07", "4", 2));

    BgPayrollInput input = builder.build(contract("8.00"), DEC_FROM, DEC_TO);
    assertEquals(0, new BigDecimal("0.50").compareTo(input.unpaidLeaveDays), "4 ÷ 8 = 0,5");

    BgUnpaidLeaveHealthResolver.Outcome out = contributionFor(input);
    assertEquals(
        BgUnpaidLeaveHealthResolver.Status.COMPUTED,
        out.status,
        "⚠️ null на входа би дал NOT_APPLICABLE — тоест „не се дължи“.");
    assertEquals(
        0,
        new BigDecimal("0.59").compareTo(out.amount),
        "24,808 × 0,5 / 21 = 0,5907 → 0,59. Намерено: " + out.amount);
  }

  @Test
  @DisplayName("⚠️ ПАРИ · същите четири часа при ПОЛОВИН щат са цял ден и ДВОЙНАТА сума")
  void theSameFourHoursOnAHalfTimeContractAreAWholeDayAndTwiceTheMoney() {
    Builder builder = new Builder();
    builder.leaves = Collections.singletonList(unpaidLeave("2026-12-07", "2026-12-07", "4", 2));

    BgPayrollInput input = builder.build(contract("4.00"), DEC_FROM, DEC_TO);
    assertEquals(0, new BigDecimal("1.00").compareTo(input.unpaidLeaveDays), "4 ÷ 4 = 1 ден");

    BgUnpaidLeaveHealthResolver.Outcome out = contributionFor(input);
    assertEquals(
        0,
        new BigDecimal("1.18").compareTo(out.amount),
        "⚠️ ТОВА Е ЦЕНАТА НА ГРЕШНИЯ ДЕЛИТЕЛ, ИЗМЕРЕНА В ЕВРО. През законоустановените 8 часа"
            + " (bgLegalHoursPerDay) вместо договорените 4 сумата щеше да е 0,59 — половината"
            + " дължимо, при пълен щат неразличимо, защото там двете числа съвпадат. Намерено: "
            + out.amount);
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · часове без договорен ден спират тройката с НАЗОВАНА причина")
  void hoursWithoutAContractedDayRefuseLoudly() {
    Builder builder = new Builder();
    builder.leaves = Collections.singletonList(unpaidLeave("2026-12-07", "2026-12-07", "4", 2));

    BgPayrollInput input = builder.build(contract("0.00"), DEC_FROM, DEC_TO);
    assertNull(input.unpaidLeaveDays, "без делител часовете не стават дни");

    BgPayrollInputBuilder.LeaveDays days =
        builder.leaveDaysOf(contract("0.00").getEmployee(), DEC_FROM, DEC_TO, null);
    assertEquals(
        BgPayrollInputBuilder.REFUSALS.get("leaveHoursWithoutContractedDay"),
        days.refusal,
        "⚠️ Отказът ОСТАВА отказ и тук — но НАЗОВАН и поправим с едно попълване на формата, вместо"
            + " да е трите отказа, които поправяше този клон.");
  }

  // ═══ ФОРМА 3 · ДРОБЕН ДЕН ════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПАРИ · един час по КТ чл. 166 е 0,13 дни и 0,15 €, а не нула")
  void oneHourIsAnEighthOfADayAndFifteenCentsNotZero() {
    Builder builder = new Builder();
    builder.leaves = Collections.singletonList(unpaidLeave("2026-12-07", "2026-12-07", "1", 2));

    BgPayrollInput input = builder.build(contract("8.00"), DEC_FROM, DEC_TO);
    assertEquals(
        0,
        new BigDecimal("0.13").compareTo(input.unpaidLeaveDays),
        "1 ÷ 8 = 0,125 → 0,13 при две знака");

    BgUnpaidLeaveHealthResolver.Outcome out = contributionFor(input);
    assertEquals(
        0,
        new BigDecimal("0.15").compareTo(out.amount),
        "⚠️ Закръглен до ЦЯЛ ден на входа, този час щеше да стане 0 дни и 0,00 € — недекларирана"
            + " вноска с вид на „не се дължи“. Намерено: "
            + out.amount);
  }

  @Test
  @DisplayName("⚠️ Трите форми в ЕДИН месец се събират, вместо да се отказват поотделно")
  void allThreeShapesInOneMonthAddUpInsteadOfRefusing() {
    Builder builder = new Builder();
    builder.leaves =
        Arrays.asList(
            // straddle: 28–31.12 от 9-дневна заявка → 4 дни
            unpaidLeave("2026-12-28", "2027-01-08", "9", 1),
            // часове: 4 ч при 8-часов ден → 0,5 дни
            unpaidLeave("2026-12-07", "2026-12-07", "4", 2),
            // половин ден: носи се какъвто е
            unpaidLeave("2026-12-09", "2026-12-09", "0.5", 1));

    BgPayrollInput input = builder.build(contract("8.00"), DEC_FROM, DEC_TO);
    assertEquals(
        0,
        new BigDecimal("5.00").compareTo(input.unpaidLeaveDays),
        "4 + 0,5 + 0,5 = 5,00. Намерено: " + input.unpaidLeaveDays);

    BgUnpaidLeaveHealthResolver.Outcome out = contributionFor(input);
    assertEquals(
        0,
        new BigDecimal("5.91").compareTo(out.amount),
        "24,808 × 5 / 21 = 5,9067 → 5,91. ⚠️ Всяка от трите поотделно беше достатъчна да обезсили"
            + " ЦЕЛИЯ месец — тоест този фиш не получаваше 5,91, а НИЩО. Намерено: "
            + out.amount);
  }
}
