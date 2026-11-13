package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.apps.bglocalization.service.BgInsuranceCalculationService;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.apps.hr.db.Employee;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ЗДРАВНАТА ВНОСКА ПРИ НЕПЛАТЕН ОТПУСК СТАВА ПЕРО — ЗЗО чл. 40 ал. 1 т. 1 б. „б“.
 *
 * <p><b>⚠️ ДОТУК {@link BgUnpaidLeaveHealthResolver} НЕ ИМАШЕ НИТО ЕДИН ПРОИЗВОДСТВЕН ЧИТАТЕЛ.</b>
 * Сметката му беше цяла, сверена срещу дословния текст и покрита със свидетели — и се извикваше
 * САМО от тестове. Тоест числото съществуваше и не влизаше в нито един фиш: лицето в неплатен
 * отпуск получаваше фиш БЕЗ здравна вноска, а декларацията му — празни т. 27 и т. 28.
 *
 * <p><b>Защо чак сега.</b> Границата беше същата като при здравното за дните ВНР: перото няма къде
 * да се запише, защото фонд ЗОВ има ЕДИН ред в речника и вписването на втора вноска в него би
 * слепило две числа върху различни бази. Базата-дискриминатор на перото сваля точно този блокер —
 * виж {@code BgContributionLineService#upsertSpecialHealthBase}.
 *
 * <p><b>⚠️ ТРИТЕ ВХОДА СЕ ЧЕТАТ ОТ ТРИ РАЗЛИЧНИ МЕСТА, И НИТО ЕДНО ОТ ТЯХ НЕ Е ТОЗИ КЛАС.</b>
 *
 * <ul>
 *   <li><b>Правилото за платеца</b> — от {@code BgAbsenceType.healthContributionRule}. Списък с
 *       кодове тук би остарял при първия нов вид неплатен отпуск.
 *   <li><b>Базата</b> — {@code HEALTH_UNPAID_LEAVE_BASE} за датата, по същия механизъм като всеки
 *       друг параметър. ⚠️ 275,33 до 31.07.2026 и 310,10 от 01.08 — тоест ЕДИН фиш може да падне в
 *       различна половина от друг, и втори механизъм за четене е свободен да се размине с първия.
 *   <li><b>Ставката</b> — ЦЯЛАТА вноска на фонд ЗОВ, от {@code
 *       BgInsuranceCalculationService#findHealthTotalRate}. ⚠️ НЕ частта на платеца: б. „б“ казва
 *       „вноската е ИЗЦЯЛО за сметка на …“. Прочит „частта на платеца“ би дал 3,2 на сто върху
 *       310,10 = 9,92 вместо 24,81 — по-малко от една трета от дължимото, с вид на пресметнато.
 * </ul>
 *
 * <p><b>⚠️ КАКВО НЕ ПРАВИ: не брои дни.</b> Дните в неплатен отпуск идват от {@code
 * BgPayrollInput.unpaidLeaveDays} — вече преброени и с обявени откази от {@code
 * BgPayrollInputBuilder}. Второ броене тук би било втори отговор на въпрос, който вече има един, и
 * при това по-лош: сглобителят ОТКАЗВА при заявка в часове, при заявка, пресичаща границата на
 * периода, и при половин ден, а този клас не би знал за нищо от това.
 */
public class BgUnpaidLeaveHealthService {

  /** Кодът на параметъра с половината минимален осигурителен доход за СОЛ. */
  protected static final String PARAM_BASE = "HEALTH_UNPAID_LEAVE_BASE";

  /** Състояние „валидирана“ на заявката за отпуск. Огледално на BgPayrollInputBuilder. */
  protected static final int LEAVE_VALIDATED = 3;

  /**
   * Смята и ЗАПИСВА вноската, като САМА намира правилото за платеца от речника.
   *
   * <p><b>⚠️ ПРАВИЛОТО НЕ Е НА ВХОДА, И ТОВА Е ДУПКА, КОЯТО СЕ ЗАТВАРЯ ТУК.</b> {@code
   * BgPayrollInput} свива неплатените отпуски в ЕДНО число ({@code unpaidLeaveDays}) и губи по кой
   * ред са ползвани — а точно редът решава КОЙ ПЛАЩА вноската: лицето при отпуск по негово желание,
   * работодателят при чл. 167а КТ, производствена необходимост и престой. Без него сумата е
   * определена, а платецът — не; а 24,81 за сметка на лицето и 24,81 за сметка на работодателя са
   * две различни неща на фиша.
   *
   * <p><b>⚠️ ДВА РАЗЛИЧНИ ПОВОДА В ЕДИН МЕСЕЦ СЕ ОТКАЗВАТ, не се смесват.</b> Лице, ползвало и
   * неплатен по свое желание, и неплатен по чл. 167а, има ДВЕ вноски с ДВА платеца — а дните са в
   * едно число и не могат да се разделят. Избран един от двата, платецът е верен за част от сумата
   * и грешен за останалата. Отказът назовава и двата повода.
   */
  public BgUnpaidLeaveHealthResolver.Outcome generate(BgPaySlip slip, BgPayrollInput input) {
    if (slip == null || slip.getEmploymentContract() == null) {
      return BgUnpaidLeaveHealthResolver.resolve(null, null, null, null, null);
    }
    LocalDate from = slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
    LocalDate to = slip.getPeriod() == null ? null : slip.getPeriod().getToDate();
    Employee employee = slip.getEmploymentContract().getEmployee();
    if (from == null || to == null || employee == null) {
      return BgUnpaidLeaveHealthResolver.resolve(null, null, null, null, null);
    }

    Set<String> rules = new LinkedHashSet<>();
    for (Object row : unpaidLeaveRules(employee, from, to)) {
      if (row != null && BgUnpaidLeaveHealthResolver.payerFor(String.valueOf(row)) != null) {
        rules.add(String.valueOf(row));
      }
    }
    if (rules.isEmpty()) {
      // Хипотезата на б. „б“ не се открива — нормално състояние, не отказ.
      return BgUnpaidLeaveHealthResolver.resolve(null, null, null, null, null);
    }
    if (rules.size() > 1) {
      return BgUnpaidLeaveHealthResolver.Outcome.refused(
          "лицето е ползвало неплатен отпуск по ПОВЕЧЕ ОТ ЕДНО основание този месец ("
              + String.join(", ", rules)
              + "), а ЗЗО чл. 40 ал. 1 т. 1 б. „б“ дава вноската на РАЗЛИЧЕН платец според повода:"
              + " на лицето при отпуск по негово желание, на работодателя при чл. 167а КТ,"
              + " производствена необходимост и престой. Дните идват в едно число"
              + " (BgPayrollInput.unpaidLeaveDays) и не могат да се разделят между двамата. Избран"
              + " един платец, той е верен за част от сумата и грешен за останалата — впиши вноската"
              + " на ръка.");
    }
    return generate(slip, input, rules.iterator().next());
  }

  /**
   * Правилата за здравна вноска на ползваните неплатени отпуски през периода.
   *
   * <p>⚠️ Същият филтър като {@code BgPayrollInputBuilder#leaveRows} и {@code BgD1DaySource} —
   * валидирани заявки, припокриващи периода. Трети различен филтър над едни и същи заявки би дал
   * трети отговор за един и същи фиш.
   */
  /** Exposed (not just inlined) so a test can assert the historical-leave filter is present. */
  public static final String UNPAID_LEAVE_RULES_JPQL =
      "SELECT DISTINCT t.healthContributionRule"
          + " FROM LeaveRequest l"
          + " JOIN l.leaveReason r"
          + " JOIN r.bgAbsenceType t"
          + " WHERE l.employee = :employee AND l.statusSelect = :validated"
          + " AND l.fromDateT <= :toEnd AND l.toDateT >= :fromStart"
          + BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL;

  protected List<Object> unpaidLeaveRules(Employee employee, LocalDate from, LocalDate to) {
    return JPA.em()
        .createQuery(UNPAID_LEAVE_RULES_JPQL, Object.class)
        .setParameter("employee", employee)
        .setParameter("validated", LEAVE_VALIDATED)
        .setParameter("fromStart", from.atStartOfDay())
        .setParameter("toEnd", to.atTime(23, 59, 59))
        .getResultList();
  }

  /**
   * Смята и ЗАПИСВА вноската, или връща обявена причина да няма перо.
   *
   * <p>⚠️ ВРЪЩА {@link BgUnpaidLeaveHealthResolver.Outcome}, а не {@code void}: отказът е част от
   * изхода. „Не се дължи“ и „дължи се и не можах да я сметна“ са два различни факта, а свити до
   * липсващо перо стават един — и вторият е недекларирана вноска.
   *
   * @param slip фишът, върху който се пише. Само чернова, която не е ретро
   * @param input входът по § 13.1 — носи дните в неплатен отпуск. Може да е {@code null}
   * @param healthContributionRule правилото от речника на отсъствията за ползвания отпуск
   */
  public BgUnpaidLeaveHealthResolver.Outcome generate(
      BgPaySlip slip, BgPayrollInput input, String healthContributionRule) {

    if (slip == null) {
      return BgUnpaidLeaveHealthResolver.resolve(healthContributionRule, null, null, null, null);
    }

    // ⚠️ unpaidLeaveDaysUnderZzo, А НЕ unpaidLeaveDays, И ТОВА Е ДОСЛОВНОСТТА НА Б. „Б“. Нормата
    // казва „за лицата в НЕПЛАТЕН ОТПУСК, които не подлежат на осигуряване на друго основание“ —
    // тоест пита за ИНСТИТУТА, не за това дали работодателят е платил. Отпускът по КТ чл. 157 ал. 1
    // т. 4, 5 и 7 се карвира от заплатата (работодателят не му дължи размера — ал. 3 т. 3), но НЕ е
    // неплатен отпуск и денят му се зачита за осигурителен стаж. За всичките заварени видове двете
    // числа са РАВНИ. Виж BgPayrollInput#unpaidLeaveDaysUnderZzo.
    BigDecimal unpaidDays = input == null ? null : input.unpaidLeaveDaysUnderZzo;
    Integer workingDays =
        slip.getBgWorkingDaysInPeriod() == 0 ? null : slip.getBgWorkingDaysInPeriod();
    LocalDate on = referenceDateOf(slip, input);

    BgUnpaidLeaveHealthResolver.Outcome outcome =
        BgUnpaidLeaveHealthResolver.resolve(
            healthContributionRule,
            baseFor(on),
            totalHealthRate(input, on),
            unpaidDays,
            workingDays);

    if (!outcome.isComputed()) {
      return outcome;
    }

    // ⚠️ ПЛАТЕЦЪТ ИДВА ОТ РЕЗОЛВЕРА, а не се извежда от базата: б. „б“ дава вноската на ЛИЦЕТО при
    // отпуск по негово желание и на РАБОТОДАТЕЛЯ при чл. 167а КТ, производствена необходимост и
    // престой. Извод от базата би бил верен в единия случай и грешен в другия.
    boolean employerBorne = outcome.payer == BgUnpaidLeaveHealthResolver.Payer.EMPLOYER;

    writeHealthLine(
        slip,
        employerBorne,
        proratedBase(baseFor(on), unpaidDays, workingDays),
        totalHealthRate(input, on),
        outcome.amount);

    return outcome;
  }

  /**
   * ЗАПИСЪТ НА ПЕРОТО — НАИМЕНУВАН ШЕВ, а не три реда вътре в {@link #generate}.
   *
   * <p><b>⚠️ ДОБАВЕН ЗАРАДИ СВИДЕТЕЛ, И ТОВА Е ЛЕГИТИМНА ПРИЧИНА.</b> {@code Beans.get} иска Guice,
   * тоест сметката по-горе не можеше да се провери с ЧИСЛО от тест без инжектор — а точно числото е
   * онова, което разцепването на КТ чл. 157 мени. Точно същият шев и по същия довод вече съществува
   * от другата страна на здравната вноска: {@code BgSickLeaveService#writeSpecialHealthBase}.
   *
   * <p>⚠️ НОВ метод, а не разширен съществуващ — заварен наследник не се поваля от добавка.
   */
  protected void writeHealthLine(
      BgPaySlip slip,
      boolean employerBorne,
      BigDecimal proratedBase,
      BigDecimal ratePercent,
      BigDecimal amount) {
    Beans.get(BgContributionLineService.class)
        .upsertSpecialHealthBase(
            slip,
            BgD1ContributionAggregator.BASE_HEALTH_ONLY,
            employerBorne,
            proratedBase,
            ratePercent,
            amount);
  }

  /**
   * БАЗАТА за т. 27 — половината минимален доход, ПРОРАТИРАНА по дните.
   *
   * <p>⚠️ Същото решение като при сумата ({@code
   * BgUnpaidLeaveHealthResolver#CONTRIBUTION_FOLLOWS_UNPAID_DAYS}) и по същия довод: хипотезата
   * иска лицето да НЕ подлежи на осигуряване на друго основание, а през отработените дни от същия
   * месец то подлежи. Пълната месечна база в т. 27 би декларирала доход за период, който вече е
   * деклариран в т. 21.
   */
  protected BigDecimal proratedBase(
      BigDecimal halfSolBase, BigDecimal unpaidDays, Integer workingDays) {
    if (halfSolBase == null || unpaidDays == null || workingDays == null || workingDays <= 0) {
      return null;
    }
    return halfSolBase
        .multiply(unpaidDays)
        .divide(
            BigDecimal.valueOf(workingDays),
            BgUnpaidLeaveHealthResolver.SCALE,
            BgEarningsResolver.ROUNDING);
  }

  /** Датата за версията на параметрите: от входа, а при липса — от периода на фиша. */
  protected LocalDate referenceDateOf(BgPaySlip slip, BgPayrollInput input) {
    if (input != null && input.parameterReferenceDate != null) {
      return input.parameterReferenceDate;
    }
    return slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
  }

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА (подменят се в тестовете) ════════════════════════

  /**
   * {@code HEALTH_UNPAID_LEAVE_BASE} за датата, или {@code null}, ако редът липсва.
   *
   * <p>⚠️ Същият филтър като {@code BgSickLeaveService#healthAbsenceBase} — нарочно. Два различни
   * филтъра над една таблица дават два различни реда за един и същи фиш, а 2026 г. има ДВЕ
   * осигурителни половини.
   */
  protected BigDecimal baseFor(LocalDate date) {
    if (date == null) {
      return null;
    }
    BgPayrollParameter row =
        Beans.get(BgPayrollParameterRepository.class)
            .all()
            .filter(
                "self.code = :c AND self.validFrom <= :d "
                    + "AND (self.validTo IS NULL OR self.validTo >= :d)")
            .bind("c", PARAM_BASE)
            .bind("d", date)
            .fetchOne();
    return row == null ? null : row.getParamValue();
  }

  /**
   * ЦЯЛАТА ставка на фонд ЗОВ за вида осигурен, категорията и периода на този фиш.
   *
   * <p>⚠️ ВИДЪТ ОСИГУРЕН И КАТЕГОРИЯТА ИДВАТ ОТ ВХОДА, не от договора направо: сглобителят вече ги
   * е решил и е обявил отказите си (заварен договор без вид осигурен например). Втори прочит от
   * договора е свободен да се размине с първия — и точно това разминаване прави ставка, различна от
   * онази, по която е сметнат останалият фиш.
   */
  protected BigDecimal totalHealthRate(BgPayrollInput input, LocalDate date) {
    if (input == null || date == null) {
      return null;
    }
    return Beans.get(BgInsuranceCalculationService.class)
        .findHealthTotalRate(input.insuredTypeCode, input.laborCategory, date);
  }

  /** Чернова, която НЕ е коригираща и НЕ е заличаваща — единственото, върху което се пише. */
  protected boolean isEligibleDraft(BgPaySlip slip) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return false;
    }
    return !Boolean.TRUE.equals(slip.getBgIsCorrection())
        && !Boolean.TRUE.equals(slip.getBgIsDeletion());
  }
}
