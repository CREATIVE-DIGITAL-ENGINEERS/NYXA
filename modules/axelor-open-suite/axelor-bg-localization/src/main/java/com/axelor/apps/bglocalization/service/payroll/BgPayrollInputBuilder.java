package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.BgAmendmentSalaryRule;
import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.bglocalization.service.BgContributionRequest;
import com.axelor.apps.bglocalization.service.BgSalaryInEffect;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import com.axelor.apps.bglocalization.service.selfinsured.BgSelfInsuredRules;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.TimesheetRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ПРОИЗВОДСТВЕНИЯТ СГЛОБИТЕЛ: от (трудов договор, период) до {@link BgPayrollInput}.
 *
 * <p><b>Защо съществува.</b> Контрактът е защитен от {@code BgPayrollInputContractTest}, но без
 * сглобител НИКОЙ в главния код не го строи. Тогава {@code BgWorkTimeFacts.byHours()} и {@code
 * byDays()} нямат нито един викащ извън {@code src/test}, тоест пропорцията по НЕВД чл. 1 ал. 3 и
 * ал. 4 е недостижима от работещото приложение — при непълно работно време МОД прагът се сравнява
 * цял. Контракт без сглобител е подпис под договор, който никой не изпълнява.
 *
 * <p><b>Какво този клас НЕ прави.</b> Нито едно изчисление по същество. Той ЧЕТЕ и ПРЕПИСВА; всяко
 * правило е на WS-3. ТРИТЕ изключения са изрични и са преобразувания, не правила: датата на
 * признатия стаж (начало на договора, изместено назад — самият контракт го описва така),
 * учителският обхват (булева → код, точно както го прави {@code BgContributionPreviewController}) и
 * обхватът по чл. 4а ал. 2 (пак булева → код, виж {@link #solScopeOf}).
 *
 * <p><b>⚠️ „ЕДИНИЦА: НЯМА“ Е ВАЛИДНО ОБЯВЯВАНЕ; МЪЛЧАНИЕТО НЕ Е.</b> Същото важи и за източника.
 * Поле, за което източник в модела НЯМА, се попълва с {@link #absent(String)} — обявена липса, — а
 * не се оставя на подразбиращата се стойност на строителя. Подразбиращата се стойност е точно това,
 * което не може да каже „не знам“: пропуснат {@code .nightHours(...)} и обявен {@code
 * .nightHours(absent("nightHours"))} дават ЕДНА И СЪЩА стойност и РАЗЛИЧНО твърдение.
 *
 * <p>Списъкът с обявените липси е {@link #DECLARED_ABSENT} и се сверява в ДВЕТЕ посоки от {@code
 * BgPayrollAssemblyTest}: нов запис без ред в кода е червено, ред в кода без запис е червено (пада
 * още при извикването, вижте {@link #requireDeclared}), запис без причина е червено, а поле на
 * контракта, което сглобителят е подминал МЪЛЧАЛИВО — нито попълнено, нито обявено — също е
 * червено.
 *
 * <p><b>⚠️ И ТРЕТО СЪСТОЯНИЕ: ОТКАЗЪТ.</b> {@link #REFUSALS} е за поле, чийто източник СЪЩЕСТВУВА и
 * е ПРАЗЕН — различно от обявената липса, при която източник изобщо няма. Двете дават един и същ
 * {@code null} и искат различна поправка: липсата чака решение на архитект, отказът чака попълване
 * от оператор. Слети в едно, второто мълчи. Днес отказът е един — видът осигурен.
 *
 * <p><b>Шевовете към базата са protected</b>, по образеца на {@code BgPaySlipService}: така целият
 * сглобител се тества без Postgres, без Guice и без Hibernate.
 */
public class BgPayrollInputBuilder {

  /**
   * ⚠️ ПОЛЕТАТА С ОБЯВЕНА ЛИПСА, всяко с ПРИЧИНА. Списъкът е ПЪЛЕН.
   *
   * <p>Причината е зависимост или решение, не обяснение: „няма entity“, „няма съпоставяне“, „чака
   * решение на човек“. Изключение без причина е криене на грешка — образецът е {@code
   * BgDefaultedGetterTest.EXEMPT}.
   */
  public static final Map<String, String> DECLARED_ABSENT = new LinkedHashMap<>();

  static {
    DECLARED_ABSENT.put(
        "manualLines",
        "ръчните пера са BgPaySlipLine и мапват 1:1 върху ManualLine, но живеят на ФИША, а не на"
            + " двойката (договор, период), от която този сглобител тръгва. ⚠️ И ПОСОКАТА Е"
            + " ОБРАТНАТА: фишът се СГЛОБЯВА от този вход, тоест четенето на неговите пера обратно"
            + " към входа му затваря кръг — за един и същи период съществуват фиш-чернова и фиш"
            + " отказан, и кой от двата е източникът е решение, не прочит. Навързването им е"
            + " навързване на фиша — следваща задача");
    DECLARED_ABSENT.put(
        "insurableIncomeOverride",
        "ръчното надписване на ОСИГУРИТЕЛНИЯ ДОХОД няма поле. Employee.bgMinIncomeOverride и"
            + " entity-то BgMinIncomeOverride надписват РЕДА по Приложение № 1 (минималния праг за"
            + " длъжността), а не самия доход — различно нещо със сходно име, и точно затова"
            + " заемането му тук би било мълчалива смяна на смисъла. ⚠️ ОТЛОЖЕНО НАРОЧНО В WS-5:"
            + " това е рядко админ надписване, което ЗАОБИКАЛЯ цялото изчисление, тоест иска и"
            + " задължителна причина, и следа кой го е направил — тоест решение на архитект за"
            + " обхвата му, а не още една колона");
    DECLARED_ABSENT.put(
        "insurableIncomeOverrideReason",
        "върви с insurableIncomeOverride и няма как да го изпревари: причина без надписване е"
            + " обяснение за нещо, което не се е случило");
  }

  /**
   * ⚠️ ОТКАЗИТЕ — трето състояние до „ето стойност“ и „обявена липса“, всеки с ПРИЧИНА.
   *
   * <p><b>Отказът и обявената липса НЕ са едно и също, макар и двете да дават {@code null}.</b>
   * Обявената липса казва „ИЗТОЧНИК В МОДЕЛА НЯМА“ — тя е за архитекта. Отказът казва „източникът
   * СЪЩЕСТВУВА и е ПРАЗЕН“ — той е за оператора и се поправя с едно попълване на формата. Слети в
   * едно, вторият случай се чете като първия и никой не отива да попълни полето.
   *
   * <p>Списъкът е по образеца на {@code BgPayrollWorkTime.REFUSALS} и се сверява в ДВЕТЕ посоки от
   * {@code BgPayrollAssemblyTest}: код без запис пада още при извикването ({@link #refuse}), запис
   * без ред в кода е червено, запис без причина е червено.
   */
  public static final Map<String, String> REFUSALS = new LinkedHashMap<>();

  static {
    REFUSALS.put(
        "insuredTypeCode",
        "видът осигурен не е попълнен по договора (EmploymentContract.bgInsuredTypeCode). Без него"
            + " BgContributionResolver НЯМА КАКВО ДА БЪДЕ ПОПИТАН: кодът е първият ключ в речника на"
            + " ставките и без него наборът редове е празен, а не подразбиращ се."
            + " ⚠️ 01 НЕ Е ПОДРАЗБИРАНЕ. Той е най-честият вид осигурен и точно затова е най-опасният"
            + " по подразбиране: за учителя (08), държавния служител (05), допълнителния договор (04)"
            + " и морското лице (92) той избира ДРУГ набор ставки, тоест грешна вноска, която никъде"
            + " не изглежда като грешка. Поправка: попълнете вида осигурен на формата на договора.");
    REFUSALS.put(
        "extraHoursTypeUnclassified",
        "валидиран ред с допълнителни часове сочи вид (ExtraHoursType), който НЯМА класификация"
            + " към някой от четирите вида извънреден труд. Не се знае в коя кофа е трябвало да"
            + " влезе, а всяка от четирите носи различно увеличение по чл. 262 ал. 1 КТ. Попълнете"
            + " ExtraHoursType.bgExtraHoursKind за посочения вид");
    REFUSALS.put(
        "leaveReasonUnmapped",
        "валидирана заявка за отпуск сочи вид (LeaveReason), който НЯМА съпоставяне към"
            + " BgAbsenceType. Не се знае в коя кофа е трябвало да влезе — платен, неплатен или"
            + " друго — а трите се третират различно в осигурителния доход. Попълнете"
            + " LeaveReason.bgAbsenceType за посочения вид отпуск");
    REFUSALS.put(
        "absenceTypeInsuranceFlagsConflict",
        "съпоставеният вид отсъствие (BgAbsenceType) си ПРОТИВОРЕЧИ: кофата в Декларация обр. 1"
            + " (d1Code) и признакът „зачита се за осигурителен стаж“ (countsAsInsuranceService)"
            + " сочат в различни посоки. Двете трябва да се съгласяват, защото по тях се местят"
            + " ЗАЕДНО заплатата и т. 16.1 — виж BgD1DayBuckets.outsideInsurance."
            + " ⚠️ И НЕПОПЪЛНЕНАТА ОТМЕТКА Е ПРОТИВОРЕЧИЕ, не подразбиране: вид без кофа и без"
            + " отметка би се платил ЦЯЛ, а Д1 щеше да го обяви като ден в осигуряване — точно"
            + " тихото плащане, което този отказ съществува да спре."
            + " Поправка: или попълнете кофата (16.1–16.5, 16.А), или махнете отметката"
            + " „зачита се за осигурителен стаж“ на формата на вида отсъствие");
    REFUSALS.put(
        "leaveHoursWithoutContractedDay",
        "валидирана заявка за отпуск се мери в ЧАСОВЕ (LeaveReason.unitSelect = 2), а договорът НЕ"
            + " казва колко часа е денят (EmploymentContract.bgHoursPerDay). Без делител часовете"
            + " не стават дни, а събрани направо с дните дават сбор от несравними числа."
            + " ⚠️ 8 НЕ Е ПОДРАЗБИРАНЕ: делителят е ДОГОВОРЕНИЯТ ден на ЛИЦЕТО, не"
            + " законоустановеният за длъжността (bgLegalHoursPerDay). При половин щат (4 ч)"
            + " четири часа отпуск са ЦЯЛ ден отсъствие; през осемте те стават половин."
            + " Поправка: попълнете договорените часове на ден на формата на договора");
    REFUSALS.put(
        "unsubmittedSickNoteOverlapsSickLeave",
        "за едни и същи дни има ДВА записа, които се изключват взаимно: валидирана заявка за"
            + " отпуск от вид ИЗВЪН осигуряването без своя кофа (непредставен болничен) и"
            + " ВАЛИДИРАН болничен лист. Двата описват едно и също отсъствие в два взаимно"
            + " изключващи се режима — „болничният НЕ е представен“ и „ето го болничния“."
            + " ⚠️ И РАЗЛИКАТА Е В ПАРИТЕ, не в подредбата: болничният енджин сваля дните от"
            + " отработените (BgSickLeaveService#applyDays), а заявката сваля СЪЩИТЕ дни втори път"
            + " през карва (BgD1DayBuckets#outsideInsurance) — тоест основната заплата излиза"
            + " занижена с цели дни, а фишът изглежда напълно пресметнат. При 3 дни, основна"
            + " 1200,00 и норма 22 това са 872,73 вместо 1036,36."
            + " Поправка: пристигналият болничен ЗАМЕСТВА заявката — изтрийте или откажете заявката"
            + " за отпуск „Непредставен болничен“ за тези дни и оставете само болничния лист."
            + " Обратната поправка е същата с разменени роли: ако листът е въведен по грешка,"
            + " откажете него (статус „Отказан“), а не заявката");
    REFUSALS.put(
        "leaveOutsideWorkingDays",
        "валидирана заявка за отпуск с положителна продължителност НЕ ПОКРИВА нито един работен"
            + " ден по производствения календар — тоест заявката и календарът твърдят различни"
            + " неща за едни и същи дати. Разпределянето по периоди дели на работните дни на"
            + " заявката; при нула такива делителят е нула и частта в този месец е неизразима."
            + " Проверете датите на заявката и покритието на календара за годината");
  }

  /**
   * Обхватът, когато няма упражнен избор по чл. 4а ал. 2 КСО.
   *
   * <p>⚠️ ТУК ANY Е СТОЙНОСТ НА ЗАЯВКАТА, за разлика от учителския обхват, където {@code
   * BgContributionRequest} изрично казва „ANY е стойност на реда, не на заявката“. Разликата е в
   * това какво пита филтърът: {@code bgSolScope = 'ANY' OR bgSolScope = :scope} — тоест ANY на
   * заявката значи „вземи САМО безусловните редове“, а не „вземи всички“. Точно това изключва трите
   * реда IC-131, IC-137 и IC-144 при неупражнен избор.
   */
  public static final String SOL_SCOPE_ANY = "ANY";

  /** Упражненият избор по чл. 4а ал. 2 КСО — само морското лице може да го има. */
  public static final String SOL_SCOPE_ART4A_AL2 = "ART4A_AL2";

  /**
   * Валидираният болничен епизод — единственото състояние, което влиза във ведомостта.
   *
   * <p>Низ, а не число: {@code bg.sick.leave.status.select} е низова селекция, точно както {@code
   * bg.compensation.doc.status.select}. Константата е тук, а не преписана в заявката, за да има
   * ЕДНО място, което се сверява срещу селекцията.
   */
  public static final String SICK_LEAVE_VALIDATED = "VALIDATED";

  /** Валидираната заявка за отпуск — {@code hrs.leave.request.status.select}, стойност 3. */
  public static final int LEAVE_VALIDATED = 3;

  /** {@code hr.leave.reason.unit.select}: 1 = дни, 2 = часове. */
  public static final int LEAVE_UNIT_DAYS = 1;

  /**
   * ТОЧНОСТТА НА ДНИТЕ ОТПУСК. Единица: знаци след десетичната запетая.
   *
   * <p>⚠️ ДВА, А НЕ НУЛА. Кофите не са {@code Integer} и дробният сбор не е ОТКАЗ — дробният ден е
   * задължителният образ на КТ чл. 166 (кърмене, 1 час дневно) и КТ чл. 157 ал. 1 т. 6 (1 час
   * дневно при предизвестие) в дни: един час при осемчасов ден Е 0,125 дни. Закръглен на входа, той
   * става 0 и правото се трие тихо.
   *
   * <p>⚠️ И ДВА, А НЕ ПОВЕЧЕ: същият {@link BgEarningsResolver#SCALE}, с който се мерят парите на
   * този фиш. Дните са числител на дялове, чийто знаменател е {@link #workingDaysInPeriodOf} —
   * трета точност тук би дала трето число за един и същи месец.
   */
  public static final int LEAVE_DAYS_SCALE = BgEarningsResolver.SCALE;

  // ⚠️ Категориите са от bg.absence.category.select и се четат ОТ ТАМ, не се измислят тук.
  /**
   * Категория „болнични“ в речника с отсъствия. ⚠️ {@code public}, защото {@code
   * BgSickNoteArrivalService} пита по нея кои заявки листът замества — а преписан там като литерал,
   * номерът щеше да е второ мнение за същата колона.
   */
  public static final int ABSENCE_CATEGORY_SICK = 2;

  static final int ABSENCE_CATEGORY_LEAVE = 1;
  static final int ABSENCE_CATEGORY_DISCIPLINARY = 5;
  static final int ABSENCE_CATEGORY_OTHER = 6;
  static final int ABSENCE_CATEGORY_UNPAID = 7;

  /** Валидираните допълнителни часове — {@code hrs.leave.request.status.select}, стойност 3. */
  public static final int EXTRA_HOURS_VALIDATED = 3;

  // ⚠️ Стойностите са от bg.extra.hours.kind.select и се четат ОТ ТАМ.
  static final String OVERTIME_WORKDAY = "OVERTIME_WORKDAY";
  static final String OVERTIME_REST_DAY = "OVERTIME_REST_DAY";
  static final String OVERTIME_HOLIDAY = "OVERTIME_HOLIDAY";
  static final String OVERTIME_SIRV = "OVERTIME_SIRV";

  static final String BUCKET_PAID = "paid";
  static final String BUCKET_UNPAID = "unpaid";
  static final String BUCKET_OTHER = "other";

  // ═══ ВХОДНАТА ТОЧКА ═══════════════════════════════════════════════════════

  /**
   * Сглобява входа за един договор и един период.
   *
   * @param contract трудовият договор; {@code null} дава вход само с периода
   * @param period стандартният {@code Period}; иска се началната му дата
   */
  public BgPayrollInput build(EmploymentContract contract, Period period) {
    return build(contract, period, null);
  }

  /**
   * Както горното, но с ДЕКЛАРИРАНИЯ осигурителен доход при друг платец за същия месец.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗШИРЕН ШЕВ.</b> Двуаргументният вход отгоре ОСТАВА и делегира с
   * {@code null}. Разширен на място, той щеше да повали всеки повикващ и всеки заварен тест, който
   * го подменя — а промяната тук е ДОБАВЯНЕ на факт, не смяна на съществуващ.
   *
   * <p><b>⚠️ {@code null} ОТ ДВУАРГУМЕНТНИЯ ВХОД НЕ Е ПОДРАЗБИРАНЕ, А ЛИПСА НА ПОВОД.</b>
   * Декларацията е МЕСЕЧЕН факт и живее на ФИША ({@code BgPaySlip.bgOtherPayerInsurableIncome}) —
   * повикващ без фиш (диагностичният екран „Преглед на осигуровки“, който сглобява вход за
   * произволен договор) няма откъде да я вземе и няма месец, за който да я твърди. Тоест за него
   * „няма декларация“ е ВЕРНИЯТ отговор, не подразбран.
   *
   * @param otherPayerInsurableIncome декларираният чужд осигурителен доход в ЕВРО, или {@code null}
   */
  public BgPayrollInput build(
      EmploymentContract contract, Period period, BigDecimal otherPayerInsurableIncome) {
    LocalDate from = period != null ? period.getFromDate() : null;
    LocalDate to = period != null ? period.getToDate() : null;
    return build(contract, from, to, otherPayerInsurableIncome);
  }

  /**
   * Както горното, но с гол интервал — за повикващите, които нямат {@code Period}.
   *
   * <p>⚠️ Празният край се чете като „периодът е един ден“, а не като „до безкрай“: интервал без
   * край би дал знаменател, който расте, тоест по-малък коефициент и по-нисък праг — посоката,
   * която не се обявява сама.
   */
  public BgPayrollInput build(EmploymentContract contract, LocalDate from, LocalDate to) {
    return build(contract, from, to, null);
  }

  /**
   * Както горното, но с декларирания чужд осигурителен доход — виж {@link
   * #build(EmploymentContract, Period, BigDecimal)}.
   */
  public BgPayrollInput build(
      EmploymentContract contract,
      LocalDate from,
      LocalDate to,
      BigDecimal otherPayerInsurableIncome) {
    return build(contract, from, to, otherPayerInsurableIncome, null);
  }

  /**
   * Както горното, но РЕЖИМЪТ НА ОСИГУРЯВАНЕ идва от фиша, а не от договора и служителя.
   *
   * <p><b>⚠️ ЕДИН ПЪТ ЗА ЖИВОТО И ЗАМРАЗЕНОТО.</b> Полетата на режима се пълнят ВИНАГИ от {@link
   * BgPayrollRegime}: подаден — замразеният от фиша; {@code null} — извлечен живо с {@link
   * #liveRegime(EmploymentContract, LocalDate)}, със СЪЩИТЕ помощници като живия вход. Тоест
   * замразеното се тълкува точно както живото, а живото се чете само там, където фиш още няма
   * режим.
   *
   * @param regime замразеният режим на фиша, или {@code null} — тогава се чете живо
   */
  public BgPayrollInput build(
      EmploymentContract contract,
      LocalDate from,
      LocalDate to,
      BigDecimal otherPayerInsurableIncome,
      BgPayrollRegime regime) {
    Employee employee = contract != null ? contract.getEmployee() : null;
    LocalDate end = to != null ? to : from;
    BgPayrollRegime r = regime != null ? regime : liveRegime(contract, from);

    BgPayrollInput.Builder b = BgPayrollInput.builder();

    // ── кой и по кой договор ────────────────────────────────────────────────
    b.employmentContractId(contract != null ? contract.getId() : null)
        .employeeId(employee != null ? employee.getId() : null)
        .insuredTypeCode(insuredTypeOfCode(r.insuredTypeCode).code)
        // ⚠️ Категорията труд е на ЛИЦЕТО, не на договора. Javadoc-ът на контракта казва
        // „Източник: договорът“, а поле bgLaborCategory на EmploymentContract НЯМА — проверено в
        // домейна. Документ срещу код: вярва се на кода и разминаването е записано в доклада на
        // клона.
        .laborCategory(r.laborCategory)
        .birthDate(employee != null ? employee.getBirthDate() : null)
        .bornAfter1959(r.bornAfter1959)
        .pensionChoice(r.pensionChoice)
        .pensioner(employee != null && Boolean.TRUE.equals(employee.getBgIsPensioner()))
        .solScope(r.solScope)
        .teacherScope(r.teacherScope)
        .nkpdCode(r.nkpdCode)
        .nkidCode(r.nkidCode)
        .baseSalary(baseSalaryOf(contract, from, end))
        .contractHoursPerDay(r.hoursPerDay)
        .contractDaysPerWeek(r.daysPerWeek)
        .workTimeMode(r.workTimeMode)
        // ⚠️ ДВЕТЕ ПОЛЕТА НА ЧАСОВИЯ ЕНДЖИН — препис, не тълкуване. Дали степента се плаща и дали
        // извънредният труд отпада, е правило и живее в BgEarningsResolver; тук минава само
        // декларираното на договора. Степента е низ и НЕ се превежда в сума — размерите ѝ са
        // данни с валидност по дата (DEGREE_DOCTOR_EUR / DEGREE_DSC_EUR).
        .academicDegree(contract != null ? contract.getBgAcademicDegree() : null)
        .unregulatedWorkDay(
            contract != null && Boolean.TRUE.equals(contract.getBgIsUnregulatedWorkDay()))
        // ── гражданският договор: приспадането и прагът ────────────────────
        // ⚠️ ПРЕПИСВАТ СЕ, НЕ СЕ ТЪЛКУВАТ. Дали кодът изобщо ползва тези два входа е правило и
        // живее в BgCivilContractResolver: за всеки вид осигурен освен 14 те стоят непрочетени.
        // Гейт тук („попълвай ги само при код 14“) би бил второ копие на същото питане и щеше да
        // мълчи точно когато сгреши — с null ставка приспадането изчезва, а базата се вдига.
        .expenseRate(r.expenseRate)
        .insuredOnOtherGround(r.insuredOnOtherGround);

    // ⚠️ Нулата тук е СТОЙНОСТ, не липса: „нула признати години стаж“ е верният отговор за човек
    // без предходен стаж, а генерираният getter за Integer връща точно 0 и за непопълнено поле.
    // Двете са неразличими по модела и това е записано в доклада — сглобителят препраща числото
    // такова, каквото е, вместо да гадае кое от двете значи.
    b.recognisedServiceYears(contract != null ? contract.getBgPriorExperienceYears() : null)
        .recognisedServiceMonths(contract != null ? contract.getBgPriorExperienceMonths() : null)
        .recognisedServiceDays(contract != null ? contract.getBgPriorExperienceDays() : null)
        .recognisedServiceStartDate(recognisedServiceStartDateOf(contract))
        .insuranceServiceMonths(insuranceServiceMonthsOf(employee));

    // ── периодът ───────────────────────────────────────────────────────────
    // ⚠️ Датата за версията на параметрите е ОТДЕЛНА от началото на периода и СЪВПАДА с него по
    // подразбиране. Съвпадението е решение на този сглобител, не свойство на контракта: коригиращ
    // фиш за юли, издаден през септември, иска ЮЛСКИ параметри, и точно затова полето е отделно.
    b.periodFrom(from).periodTo(end).parameterReferenceDate(from);

    BgWorkingDayService.Count count = workingDays(from, end);
    b.workingDaysInPeriod(workingDaysInPeriodOf(count));

    // ── отработено ─────────────────────────────────────────────────────────
    // ⚠️ ДВА ПРОЧИТА НА ЕДИН И СЪЩ ОТЧЕТ, и това е нарочно. workedTime дава СБОРА (часове и дни),
    // а часовете на официален празник искат разбивка ПО ДАТИ — сборът вече я е загубил. Шевовете
    // са два, за да остане workedTime подменяем такъв, какъвто заварените тестове го подменят.
    Map<LocalDate, BigDecimal> workedByDate = workedHoursByDate(employee, from, end);
    WorkedTime worked = workedTime(employee, from, end);
    // Четирите вида извънреден труд излизат ЗАЕДНО, по същия довод като кофите на отпуските:
    // един некласифициран вид не се знае в коя от четирите е трябвало да влезе.
    OvertimeHours overtime = overtimeHoursOf(employee, from, end);
    // Нощният труд и разположението идват от ДЕКЛАРАЦИЯ, не от отчет — виж BgDeclaredWorkHours.
    DeclaredHours declared = declaredHoursOf(contract, from, end);

    b.workedDays(worked.days)
        .workedHours(worked.hours)
        // ⚠️ НУЛА ДНИ С ОТЧЕТ НЕ Е „НЯМА ОТЧЕТ“. Празната карта дава размер нула, а нула дни с
        // отчет е и отговорът на човек, който е бил в платен отпуск цял месец — а точно този
        // случай числото съществува, за да различи (т. 16.7 иска часовете на платения отпуск като
        // ОСТАТЪК от отработените дни минус дните с отчет). Слети, липсата на отчет би станала
        // твърдение „целият месец е платен отпуск“.
        .timesheetDays(workedByDate.isEmpty() ? null : workedByDate.size())
        .nightHours(declared.nightHours)
        .overtimeWorkdayHours(overtime.workday)
        .overtimeRestDayHours(overtime.restDay)
        .overtimeHolidayHours(overtime.holiday)
        .overtimeSirvHours(overtime.sirv)
        .onCallHours(declared.onCallHours)
        .holidayWorkHours(holidayWorkHoursOf(workedByDate, count, from, end));

    // ── отсъствия ──────────────────────────────────────────────────────────
    // Едно четене на отпуските, три кофи. Кофите излизат ЗАЕДНО, защото един несъпоставен вид
    // отпуск обезсилва и трите: не се знае в коя от тях е трябвало да влезе.
    // ⚠️ ДЕЛИТЕЛЯТ ЗА ЧАСОВИТЕ ЗАЯВКИ ИДВА ОТ СЪЩИЯ ПРОЧИТ като contractHoursPerDay по-горе, а не
    // от втори: два прочита на едно поле са свободни да се разминат, а разминаване тук значи ден
    // отпуск, сметнат по друг работен ден от този, по който се мери работното време.
    // ⚠️ ЕПИЗОДИТЕ СЕ ЧЕТАТ ТУК, ПРЕДИ ОТСЪСТВИЯТА, И РЕДЪТ НЕ Е СВОБОДЕН. Броенето на отпуските
    // трябва да ги ВИДИ, за да откаже при съжителство на „непредставен болничен“ с валидиран
    // болничен лист за същите дни — виж петарната врата на leaveDaysOf. Прочетени по-долу, те
    // биха пристигнали СЛЕД като дните вече са преброени, и кръстосаната проверка би искала втори
    // прочит на същата заявка.
    List<BgPayrollInput.SickLeaveEpisode> episodes = sickLeaveEpisodesOf(contract, from, end);
    LeaveDays leave = leaveDaysOf(employee, from, end, contractHoursPerDayOf(contract), episodes);
    b.paidLeaveDays(leave.paid)
        .art177LeaveDays(leave.paidUnderArt177)
        .unpaidLeaveDays(leave.unpaid)
        .unpaidLeaveDaysInD1(leave.unpaidInD1)
        .unpaidLeaveDaysUnderZzo(leave.unpaidUnderZzo)
        .daysOutsideInsurance(leave.outsideInsurance)
        .leaveDaysByD1Code(leave.byD1Code)
        .otherAbsenceDays(leave.other)
        // ⚠️ ПРИЧИНАТА СЕ ПРЕНАСЯ, А НЕ СЕ ИЗХВЪРЛЯ. Без нея отказът на речника на отсъствията
        // стига до фиша като ЧЕТИРИ празни полета, неразличими от „няма отпуски“. С карва за
        // неплатения отпуск това не е само липсваща диагностика: прочетено като нула дни, то
        // плаща ПЪЛЕН месец на човек, който може да е бил в неплатен отпуск.
        .leaveDaysRefusal(leave.refusal);

    // ── суми, които идват готови ────────────────────────────────────────────
    for (Long id : compensationDocumentIds(contract, from, end)) {
      b.compensationDocumentId(id);
    }
    // ⚠️ ПРЕПИСВА СЕ, НЕ СЕ ТЪЛКУВА. Дали сумата активира координация по КСО чл. 6 ал. 11 е правило
    // на {@code BgIncomeCoordinationResolver}, а този сглобител само пренася декларираното.
    // Източникът е месечното поле BgPaySlip.bgOtherPayerInsurableIncome, затова полето не е в
    // DECLARED_ABSENT.
    b.otherEmployerInsurableIncome(otherPayerInsurableIncome)
        .insurableIncomeOverride(absent("insurableIncomeOverride"))
        .insurableIncomeOverrideReason(absent("insurableIncomeOverrideReason"));

    // ⚠️ ДВЕТЕ ПОЛЕТА НА САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ СЕ ЗАНУЛЯВАТ ИЗРИЧНО, а не се премълчават.
    // Пропуснат setter и подаден null дават ЕДНА И СЪЩА стойност и РАЗЛИЧНО твърдение — точно
    // правилото, с което този сглобител е построен. Тук твърдението е: наетият по трудово
    // правоотношение НЯМА регистрация на самоосигуряващо се лице и НЯМА избран осигурителен доход
    // по чл. 6 ал. 8 КСО; базата му е сборът на перата. Огледалното твърдение стои в
    // buildSelfInsured, където employmentContractId се занулява по същия начин.
    b.selfInsuredRegistrationId(null).selfInsuredSelectedIncome(null);

    // ── болничните епизоди ─────────────────────────────────────────────────
    // ⚠️ ЕПИЗОДИ, А НЕ БРОЙ ДНИ, и точно затова тук стои цикъл, а не число. По чл. 40 ал. 5 КСО
    // първите два работни дни от ВСЕКИ болничен се плащат от осигурителя — тоест пет дни болничен
    // струват на работодателя два дни като ЕДИН епизод и четири като ДВА. Сборът не различава
    // двата случая.
    for (BgPayrollInput.SickLeaveEpisode episode : episodes) {
      b.sickLeaveEpisode(episode);
    }

    // ⚠️ ЛИПСАТА, КОЯТО НЕ МИНАВА ПРЕЗ setter. Строителят на контракта дава само „добави един“
    // ({@code manualLine}), тоест липсата на цял списък се изразява с НУЛА добавяния — а нула
    // добавяния изглежда точно като недоглеждане. Затова тя се обявява тук изрично: редът пада,
    // ако полето не е в DECLARED_ABSENT, и DECLARED_ABSENT пада, ако полето вече се пълни.
    declareAbsent("manualLines");

    return b.build();
  }

  // ═══ ВТОРАТА ВРАТА: САМООСИГУРЯВАЩО СЕ ЛИЦЕ ═══════════════════════════════

  /**
   * Сглобява входа за една РЕГИСТРАЦИЯ на самоосигуряващо се лице и един период.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗШИРЕН ШЕВ — същият избор като при {@link
   * #build(EmploymentContract, Period, BigDecimal)}.</b> Четирите входа с {@code
   * EmploymentContract} остават непокътнати и нито един заварен повикващ или тест, който подменя
   * {@code protected} шев, не се пипа. Разширен на място, входът щеше да поиска промяна на всеки от
   * тях — а промяната тук е ДРУГ ИЗТОЧНИК, не друг факт за същия източник.
   *
   * <p><b>⚠️ КАКВО ТОЗИ ВХОД НАРОЧНО НЕ НОСИ, И ЗАЩО ТОВА НЕ Е ПРЕМЪЛЧАВАНЕ.</b> Празни остават
   * четири групи полета, всяка по НОРМАТИВНА причина, не по липса на време:
   *
   * <ul>
   *   <li><b>НКПД, НКИД, квалификационна група.</b> Указанието към т. 12.1/12.2/12.3 на Д1 ги иска
   *       само за 18-те кода, които прилагат МОД, а нито един самоосигуряващ се код не е сред тях
   *       ({@code BgInsuredType.bgAppliesMod = false} за 12, 13, 22 и 41 — измерено в seed-а).
   *       Самоосигуряващият се няма длъжност и няма осигурител, чиято икономическа дейност да се
   *       впише.
   *   <li><b>Работно време — часове на ден, дни в седмица, режим.</b> Указанието към т. 16 позиция
   *       3 изброява дословно кодовете, които попълват НУЛА, и 12, 13, 22 и 41 са сред тях ({@code
   *       bgZeroWorkTime = true}). Тоест не че часовете липсват — те НЕ СЕ ПОПЪЛВАТ.
   *   <li><b>Отработено, отсъствия, болнични епизоди.</b> Всичките идват от отчети за време и
   *       заявки за отпуск, които висят на трудово правоотношение. Самоосигуряващият се няма
   *       работодател, който да ги одобрява.
   *   <li><b>Основна заплата и стаж.</b> Няма възнаграждение — КСО чл. 6 ал. 8 определя базата като
   *       ИЗБОР, а не като начислена сума. Оттам и класът прослужено време няма смисъл.
   * </ul>
   *
   * <p>⚠️ РАБОТНИТЕ ДНИ В ПЕРИОДА СЕ ЧЕТАТ И ТУК, макар т. 16 позиция 3 да е нула: позиции 1 и 2 на
   * т. 16 искат „общият брой РАБОТНИ дни в осигуряване“, а те се броят по производствения календар
   * точно както при наетия. Един и същ шев, един и същ отговор.
   *
   * @param registration регистрацията; {@code null} дава вход само с периода
   * @param period стандартният {@code Period}; иска се началната му дата
   */
  public BgPayrollInput buildSelfInsured(BgSelfInsuredRegistration registration, Period period) {
    LocalDate from = period != null ? period.getFromDate() : null;
    LocalDate to = period != null ? period.getToDate() : null;
    return buildSelfInsured(registration, from, to);
  }

  /** Както горното, но с гол интервал — за повикващите, които нямат {@code Period}. */
  public BgPayrollInput buildSelfInsured(
      BgSelfInsuredRegistration registration, LocalDate from, LocalDate to) {
    return buildSelfInsured(registration, from, to, null);
  }

  /**
   * Както горното, но режимът идва от фиша — същият довод като при {@link
   * #build(EmploymentContract, LocalDate, LocalDate, BigDecimal, BgPayrollRegime)}.
   */
  public BgPayrollInput buildSelfInsured(
      BgSelfInsuredRegistration registration,
      LocalDate from,
      LocalDate to,
      BgPayrollRegime regime) {
    Employee employee = registration != null ? registration.getEmployee() : null;
    LocalDate end = to != null ? to : from;
    BgPayrollRegime r = regime != null ? regime : liveRegime(registration, from);

    BgPayrollInput.Builder b = BgPayrollInput.builder();

    // ── кой ─────────────────────────────────────────────────────────────────
    // ⚠️ ОГЛЕДАЛНОТО ЗАНУЛЯВАНЕ. Виж бележката в build(...): пропуснат setter и подаден null дават
    // една и съща стойност и различно твърдение. Тук твърдението е, че трудов договор НЯМА — КСО
    // чл. 6 ал. 8: вноските са „за сметка на осигурените лица“, тоест осигурител не съществува.
    b.employmentContractId(null)
        .selfInsuredRegistrationId(registration != null ? registration.getId() : null)
        .employeeId(employee != null ? employee.getId() : null)
        .insuredTypeCode(insuredTypeOfCode(r.insuredTypeCode).code)
        // ⚠️ ТРЕТА КАТЕГОРИЯ, ПОДАДЕНА ЯВНО, а не прочетена от лицето. Виж
        // BgSelfInsuredRules.LABOR_CATEGORY: ако лицето има И трудов договор при I категория,
        // неговата категория е вярна за ОНОВА правоотношение и невярна за това.
        .laborCategory(BgSelfInsuredRules.LABOR_CATEGORY)
        .birthDate(employee != null ? employee.getBirthDate() : null)
        .bornAfter1959(r.bornAfter1959)
        .pensionChoice(r.pensionChoice)
        .pensioner(employee != null && Boolean.TRUE.equals(employee.getBgIsPensioner()))
        .solScope(r.solScope)
        // ⚠️ NON_TEACHER, не ANY — стойността ANY е само на РЕДА в каталога, не на заявката. Същият
        // довод, който BgContributionRequest вече записва.
        .teacherScope(BgContributionRequest.NON_TEACHER);

    // ── периодът ────────────────────────────────────────────────────────────
    b.periodFrom(from).periodTo(end).parameterReferenceDate(from);
    b.workingDaysInPeriod(workingDaysInPeriodOf(workingDays(from, end)));

    // ── базата ──────────────────────────────────────────────────────────────
    // ⚠️ ПРЕПИСВА СЕ, НЕ СЕ ТЪЛКУВА — както навсякъде в този клас. Дали числото е в границите по
    // ЗБДОО е правило и живее в BgInsurableIncomeService, който налага и пода (MIN_INCOME_SOL), и
    // тавана (MAX_INSURABLE_INCOME) по СЪЩИЯ път, по който минава и заплатата. Проверено тук, то
    // щеше да е второ мнение по един и същ въпрос — и щеше да чете параметрите по друга дата.
    b.selfInsuredSelectedIncome(r.selfInsuredIncome);

    // ⚠️ ДВАТА ВХОДА НА ГРАЖДАНСКИЯ ДОГОВОР СЕ ЗАНУЛЯВАТ ИЗРИЧНО, по същия довод като полетата на
    // СОЛ отгоре: пропуснат setter и подадена нула дават ЕДНА стойност и РАЗЛИЧНО твърдение. Тук
    // твърдението е, че самоосигуряващото се лице НЕ е по чл. 4 ал. 3 т. 5 и 6 — базата му е ИЗБОР
    // по чл. 6 ал. 8, а не възнаграждение, от което се вадят разходи, и праг от една МРЗ за него
    // няма. Кодовете дори не се засичат: СОЛ носи 12, 13, 22, 41, 96, а гражданският — 14.
    b.expenseRate(null).insuredOnOtherGround(false);

    return b.build();
  }

  /**
   * ВИДЪТ ОСИГУРЕН от регистрацията, или ОТКАЗ с назована причина.
   *
   * <p>⚠️ СЪЩИЯТ ОТКАЗ КАТО ПРИ ДОГОВОРА, и нарочно същият текст: празният вид осигурен оставя
   * {@code BgContributionResolver} без първи ключ и наборът редове е ПРАЗЕН, а не подразбиращ се.
   * Втори отказ със свое име щеше да е второ съобщение за един и същ дефект.
   *
   * <p>⚠️ НЕ СЕ ВАЛИДИРА СРЕЩУ ЧЕТИРИТЕ СОЛ КОДА тук. Че кодът е от стеснената номенклатура, е
   * работа на селекцията {@code bg.sol.insured.type.select}; че списъкът ѝ съвпада с пазача на
   * договора, е работа на {@code BgSelfInsuredRegistrationFileTest}. Сглобителят ЧЕТЕ и ПРЕПИСВА.
   */
  protected InsuredType selfInsuredTypeOf(BgSelfInsuredRegistration registration) {
    String code = registration != null ? registration.getBgInsuredTypeCode() : null;
    if (code == null || code.trim().isEmpty()) {
      return InsuredType.refused(refuse("insuredTypeCode"));
    }
    return InsuredType.of(code.trim());
  }

  /**
   * ОБХВАТЪТ от регистрацията — ALL_RISKS или PENSION_ONLY.
   *
   * <p>⚠️ ПРАЗНОТО СЕ ЧЕТЕ КАТО PENSION_ONLY, И ТОВА Е САМОТО ПРАВИЛО, не подразбиране за удобство.
   * КСО чл. 4 ал. 4 казва „по свой избор МОГАТ да се осигуряват И за общо заболяване и майчинство“
   * — тоест ОЗМ е разширение, което се УПРАЖНЯВА. Неупражнено, то не се предполага. Обратната
   * посока би начислила 3,5 на сто на всяко лице, което не е избрало.
   *
   * <p>⚠️ И ТОВА Е ПРЕОБРАЗУВАНЕ, НЕ ПРАВИЛО: правилото живее в НОРМАТА и в данните — редът IC-080
   * за ОЗМ носи обхват ALL_RISKS и затова излиза от подразбиращия се набор. Валидността на самата
   * стойност е на {@code BgSelfInsuredRules#refuseRegistration}, който отказва всичко извън двете.
   */
  protected String selfInsuredScopeOf(BgSelfInsuredRegistration registration) {
    String scope = registration != null ? registration.getBgSolScope() : null;
    if (scope == null || scope.trim().isEmpty()) {
      return BgSelfInsuredRules.SCOPE_PENSION_ONLY;
    }
    return scope.trim();
  }

  // ═══ ПРЕОБРАЗУВАНИЯТА (без правила) ═══════════════════════════════════════

  /**
   * TEACHER или NON_TEACHER от учителската длъжност по договора.
   *
   * <p>Без договор отговорът е NON_TEACHER, а не {@code null}: това не е подразбиране, а ЛИПСА на
   * учителска длъжност — същият довод, с който {@code BgContributionPreviewController} вече го
   * решава, и същият, който {@code BgContributionRequest} записва („ANY е стойност на реда, не на
   * заявката“).
   */
  protected String teacherScopeOf(EmploymentContract contract) {
    return contract != null && Boolean.TRUE.equals(contract.getBgIsTeacherPosition())
        ? BgContributionRequest.TEACHER
        : BgContributionRequest.NON_TEACHER;
  }

  /**
   * ВИДЪТ ОСИГУРЕН от договора, или ОТКАЗ с назована причина.
   *
   * <p>Празен низ и {@code null} се четат ЕДНАКВО: и двете значат „не е попълнено“. Генерираният
   * getter за {@code String} връща {@code null} за непопълнено поле, но форма може да запише и
   * празен низ след изчистване на селекцията — двете не бива да дават различен отговор на един
   * въпрос.
   *
   * <p>⚠️ НЕ СЕ ВАЛИДИРА СРЕЩУ 43-ТЕ КОДА тук. Че кодът е от номенклатурата, е работа на
   * селекцията; че е СЪГЛАСУВАН с изводимите сигнали и че НЕ е самоосигуряващ, е работа на пазача
   * {@code BgInsuredTypeHomeFileTest}. Сглобителят ЧЕТЕ и ПРЕПИСВА — правило тук би било правило на
   * грешното място.
   */
  protected InsuredType insuredTypeOf(EmploymentContract contract) {
    return insuredTypeOfCode(contract != null ? contract.getBgInsuredTypeCode() : null);
  }

  /**
   * ВИДЪТ ОСИГУРЕН по кода — живия от договора или замразения от фиша, — или ОТКАЗ с назована
   * причина. Едно място, за да е един и същ отказът и в двата случая.
   */
  protected InsuredType insuredTypeOfCode(String code) {
    if (code == null || code.trim().isEmpty()) {
      return InsuredType.refused(refuse("insuredTypeCode"));
    }
    return InsuredType.of(code.trim());
  }

  /**
   * ART4A_AL2 при упражнен избор на морското лице, инак ANY.
   *
   * <p>Преобразуване (булева → код), не правило — точно както {@link #teacherScopeOf}. Правилото
   * („без изричен избор безработица не се дължи“) живее в НОРМАТА и в данните: чл. 4а ал. 2 КСО
   * казва „по свой избор и желание“, а трите реда IC-131, IC-137 и IC-144 носят обхват ART4A_AL2 и
   * затова излизат от подразбиращия се набор.
   *
   * <p>⚠️ ANY, А НЕ {@code null}. Празният обхват би минал през филтъра като {@code bgSolScope =
   * 'ANY' OR bgSolScope = null}, тоест втората страна не хваща нищо и резултатът СЛУЧАЙНО съвпада —
   * съвпадение, което ще се счупи при първата промяна на заявката. ANY е ТВЪРДЕНИЕ: „няма упражнен
   * избор, вземи само безусловните редове“.
   *
   * <p>⚠️ ВИДЪТ ОСИГУРЕН НЕ СЕ ПРОВЕРЯВА ТУК. Вдигнат флаг при код, различен от 92, е РАЗМИНАВАНЕ В
   * ДАННИТЕ и се лови от пазача — не се поправя мълчаливо тук. Мълчаливата поправка би скрила точно
   * записа, който трябва да се види.
   */
  protected String solScopeOf(EmploymentContract contract) {
    return contract != null && Boolean.TRUE.equals(contract.getBgSeafarerUnemploymentChoice())
        ? SOL_SCOPE_ART4A_AL2
        : SOL_SCOPE_ANY;
  }

  protected String nkpdCodeOf(EmploymentContract contract) {
    if (contract == null || contract.getBgNkpd() == null) {
      return null;
    }
    return contract.getBgNkpd().getCode();
  }

  /**
   * РАЗХОДНАТА СТАВКА ПО ЧЛ. 29 ЗДДФЛ — от вида доход на договора, или {@code null}.
   *
   * <p>⚠️ {@code null} ПРИ ЛИПСВАЩ ВИД ДОХОД, А НЕ НУЛА, и разликата е цялото твърдение. Нула би
   * значела „разходи не се признават“ — верен отговор за някои шаблони на чл. 29, — и приспадането
   * по КСО чл. 6 ал. 4 щеше да изчезне мълчаливо: база 2000,00 вместо 1500,00. Липсата е ОТКАЗ и се
   * вдига от {@code BgCivilContractResolver}, там където правилото знае за кой код важи.
   *
   * <p>⚠️ Обратно, ЗАВЕДЕНАТА нула стига дотам такава, каквато е — генерираният getter за decimal
   * връща ZERO и за непопълнена колона, тоест „0 на сто“ и „непопълнено на реда от каталога“ са
   * неразличими. Разграничението, което този модул може да направи, е на нивото на ВРЪЗКАТА (има ли
   * изобщо вид доход), не на стойността ѝ — и точно то е направено тук.
   */
  protected BigDecimal expenseRateOf(EmploymentContract contract) {
    if (contract == null || contract.getBgIncomeType() == null) {
      return null;
    }
    return contract.getBgIncomeType().getExpenseRate();
  }

  /** НКИД на дружеството, което плаща по договора — {@code payCompany}, не активното дружество. */
  /**
   * РЕЖИМЪТ НА ОСИГУРЯВАНЕ, ПРОЧЕТЕН ЖИВО от договора, служителя и фирмата — за замразяване.
   *
   * <p>⚠️ Извиква се САМО на три места, и нито едно не СМЯТА с него: при пресмятане на редовен фиш
   * в чернова (BgPaySlipService#freezeRegime), при изричното „Попълни от текущите данни“ в копие на
   * заварен фиш и при потвърждаване на редовен фиш — за сравнение със замразеното
   * (BgPayrollRegime#driftRefusal). Помощниците са СЪЩИТЕ, с които се пълни живият вход — тоест
   * замразеното е точно онова, което живото пресмятане би прочело.
   *
   * @param date датата, към която се преценява валидността на решението на ТЕЛК (началото на
   *     периода)
   */
  public BgPayrollRegime liveRegime(EmploymentContract contract, LocalDate date) {
    Employee employee = contract != null ? contract.getEmployee() : null;
    Company company = contract != null ? contract.getPayCompany() : null;
    BgNkid nkid = company != null ? company.getBgNkid() : null;
    BigDecimal standing =
        contract == null ? BigDecimal.ZERO : contract.getBgOtherPayerInsurableIncome();
    return new BgPayrollRegime(
        contract != null ? contract.getBgInsuredTypeCode() : null,
        employee != null ? employee.getBgLaborCategory() : null,
        employee != null && Boolean.TRUE.equals(employee.getBgBornAfter1959()),
        employee != null ? employee.getBgPensionChoice() : null,
        solScopeOf(contract),
        teacherScopeOf(contract),
        nkpdCodeOf(contract),
        nkidCodeOf(contract),
        nkid != null ? nkid.getDivisionCode() : null,
        contract != null ? contract.getBgWorkTimeMode() : null,
        contractHoursPerDayOf(contract),
        contractDaysPerWeekOf(contract),
        expenseRateOf(contract),
        contract != null && Boolean.TRUE.equals(contract.getBgInsuredOnOtherGround()),
        standing.signum() > 0 ? standing : null,
        null,
        disabilityReliefOn(employee, date),
        employee != null ? employee.getBgDisabilityValidUntil() : null,
        company != null ? company.getBgMinIncomeRowCodeOverride() : null,
        employee != null ? employee.getBgMinIncomeOverride() : null);
  }

  /** Същото за самоосигуряващото се лице — от регистрацията. */
  public BgPayrollRegime liveRegime(BgSelfInsuredRegistration registration, LocalDate date) {
    Employee employee = registration != null ? registration.getEmployee() : null;
    return new BgPayrollRegime(
        registration != null ? registration.getBgInsuredTypeCode() : null,
        BgSelfInsuredRules.LABOR_CATEGORY,
        employee != null && Boolean.TRUE.equals(employee.getBgBornAfter1959()),
        employee != null ? employee.getBgPensionChoice() : null,
        selfInsuredScopeOf(registration),
        BgContributionRequest.NON_TEACHER,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        registration != null ? registration.getBgSelectedIncome() : null,
        disabilityReliefOn(employee, date),
        employee != null ? employee.getBgDisabilityValidUntil() : null,
        // Фирма няма: BgPaySlipService#companyOf чете платеца по ДОГОВОР, а фишът на СОЛ е без
        // договор — тоест и живото подава null.
        null,
        employee != null ? employee.getBgMinIncomeOverride() : null);
  }

  /**
   * Има ли облекчение по ТЕЛК за периода — решението е отметнато и не е изтекло преди началото му.
   * Същото условие като BgPaySlipService#disabilityRelief, тоест замразеното „да“ значи точно
   * онова, което вчерашното пресмятане щеше да приложи.
   */
  static boolean disabilityReliefOn(Employee employee, LocalDate date) {
    if (employee == null || !Boolean.TRUE.equals(employee.getBgDisabilityRelief())) {
      return false;
    }
    LocalDate validUntil = employee.getBgDisabilityValidUntil();
    return validUntil == null || date == null || !validUntil.isBefore(date);
  }

  protected String nkidCodeOf(EmploymentContract contract) {
    Company company = contract != null ? contract.getPayCompany() : null;
    BgNkid nkid = company != null ? company.getBgNkid() : null;
    return nkid != null ? nkid.getCode() : null;
  }

  /**
   * Основното възнаграждение по договора, или обявена липса.
   *
   * <p>⚠️ {@code signum() == 0}, НЕ {@code == null}. Генерираният getter за decimal връща {@code
   * BigDecimal.ZERO}, тоест „още не е попълнено“ пристига като „е нула“ и проверка за null тук би
   * била МЪРТЪВ КОД. Точно този капан вече ухапа копирането на заплатата върху фиша.
   */
  protected BigDecimal baseSalaryOf(EmploymentContract contract) {
    return baseSalaryOf(contract, null, null);
  }

  /**
   * Същото, но за КОНКРЕТЕН период — заплатата, която допълнителните споразумения дават за него.
   *
   * <p><b>⚠️ ДОБАВЕН ПОДПИС, А НЕ РАЗШИРЕН ЗАВАРЕНИЯТ.</b> {@code baseSalaryOf(contract)} остава
   * дума по дума и делегира с празни дати — тоест всеки подменен сглобител в тестовете и всяко
   * бъдещо преопределяне продължават да работят. Разширен на място, шевът щеше да повали
   * override-ите мълчаливо.
   *
   * <p><b>⚠️ И ТУК ПРОЧИТЪТ Е ЕДИН.</b> Входът и фишът трябва да казват едно и също число: оставен
   * пряк, {@code BgPayrollInput.baseSalary} щеше да носи базата по договора, докато перото се смята
   * по анекса. Днес полето няма производствен читател, но договорът на входа се сверява от {@code
   * BgPayrollInputContractTest} и се чете от хората — разминаване в него е дефект, който чака свой
   * пръв консуматор.
   *
   * <p>Празни дати значат „периодът не е известен“ и отговорът е базата по договора — виж {@link
   * BgAmendmentSalaryRule#inEffect}.
   */
  protected BigDecimal baseSalaryOf(EmploymentContract contract, LocalDate from, LocalDate to) {
    if (contract == null) {
      return null;
    }
    BgSalaryInEffect inEffect =
        BgAmendmentSalaryRule.inEffect(
            contract.getBgContractAmendmentList(), contract.getBgBaseSalary(), from, to);
    // ⚠️ ОТКАЗЪТ ПРИСТИГА КАТО ЛИПСА, а не като нула, и това е същото решение като при непопълнена
    // заплата отдолу: входът носи „договорът не го казва“, а какво следва от това се решава от
    // читателя. Отказът СЕ ЧУВА на фиша (BgPaySlipService#applyBaseSalary) — тук той не бива да се
    // преразказва като второ съобщение от втори източник.
    BigDecimal salary = inEffect.salaryUnknown() ? null : inEffect.salary;
    return salary == null || salary.signum() == 0 ? null : salary;
  }

  /** Договорените часове на ден. Нулата пак значи непопълнено — виж {@link #baseSalaryOf}. */
  protected BigDecimal contractHoursPerDayOf(EmploymentContract contract) {
    if (contract == null) {
      return null;
    }
    BigDecimal hours = contract.getBgHoursPerDay();
    return hours.signum() == 0 ? null : hours;
  }

  /**
   * Договорените дни на седмица. Нулата пак значи непопълнено — виж {@link #baseSalaryOf}.
   *
   * <p>⚠️ {@code null} НЕ се чете като нула дни: нула дни на седмица не е договор. Тук {@code null}
   * значи „договорът не го казва“, а какво следва от това е решение на {@link BgPayrollWorkTime} —
   * обявено допускане за пълна седмица, не мълчание.
   */
  protected BigDecimal contractDaysPerWeekOf(EmploymentContract contract) {
    if (contract == null) {
      return null;
    }
    BigDecimal days = contract.getBgDaysPerWeek();
    return days.signum() == 0 ? null : days;
  }

  /**
   * ПРИЗНАТИЯТ ОСИГУРИТЕЛЕН СТАЖ НА ЛИЦЕТО в месеци — прочетен само когато е ДЕКЛАРИРАН.
   *
   * <p><b>⚠️ ФЛАГЪТ, А НЕ СТОЙНОСТТА, КАЗВА ДАЛИ ИМА ОТГОВОР.</b> Генерираният getter за {@code
   * Integer} връща 0 и за непопълнена колона, тоест „нула месеца стаж“ и „никой не е попълвал“
   * пристигат като ЕДНО И СЪЩО число. Тук — за разлика от {@code baseSalaryOf} — нулата НЕ бива да
   * се чете като липса: нула месеца е ВЕРНИЯТ отговор за човек на първата му работа, а по чл. 40
   * ал. 1 КСО тъкмо той няма право на парично обезщетение. Тоест прочетена като липса, нулата би
   * превърнала „няма право“ в „не знам“, а прочетена като стойност, непопълненото би превърнало „не
   * знам“ в „няма право“. Двете се различават само от флага.
   *
   * <p>⚠️ И ЗАТОВА ТУК НЯМА {@code signum() == 0}. Образецът на {@code baseSalaryOf} е верен там,
   * където нулата не е законна стойност (заплата нула не е договор) и НЕ е верен тук.
   *
   * <p>⚠️ Стажът се чете от ЛИЦЕТО, не от договора. {@code bgPriorExperienceMonths} на договора е
   * признатият ТРУДОВ стаж за този договор и е друго нещо: неплатеният отпуск над 30 дни не е нито
   * трудов, нито осигурителен стаж, а платеният е трудов. Изведен от него, осигурителният стаж би
   * бил догадка с вид на данни.
   */
  protected Integer insuranceServiceMonthsOf(Employee employee) {
    if (employee == null || !Boolean.TRUE.equals(employee.getBgInsuranceServiceDeclared())) {
      return null;
    }
    return employee.getBgInsuranceServiceMonths();
  }

  /**
   * Началото на признатия стаж: началото на договора, изместено НАЗАД с признатия предходен стаж.
   *
   * <p>Точно това описва самият контракт за полето. Котвата печели пред сбора, защото от нея се
   * извежда и денят на навършването, а от „13 години към 30.11.2026“ — не.
   */
  protected LocalDate recognisedServiceStartDateOf(EmploymentContract contract) {
    if (contract == null || contract.getStartDate() == null) {
      return null;
    }
    return contract
        .getStartDate()
        .minusYears(contract.getBgPriorExperienceYears())
        .minusMonths(contract.getBgPriorExperienceMonths())
        .minusDays(contract.getBgPriorExperienceDays());
  }

  /**
   * Работните дни в периода — само когато числото е НАДЕЖДНО.
   *
   * <p>⚠️ Непокрита календарна година дава число, преброено само по понеделник–петък, БЕЗ
   * официалните празници, тоест ЗАВИШЕН знаменател. Завишен знаменател значи по-малък коефициент,
   * по-нисък праг и по-ниска осигурителна база — посоката, която не се обявява сама. Затова
   * ненадеждното число не се предава като стойност: {@code null} кара {@link BgPayrollWorkTime} да
   * не пропорционира изобщо, което е предишното поведение, а не тихо по-малка сума.
   */
  protected Integer workingDaysInPeriodOf(BgWorkingDayService.Count count) {
    if (count == null || !count.isReliable()) {
      return null;
    }
    return count.getWorkingDays();
  }

  /**
   * ЧАСОВЕТЕ, ПАДНАЛИ НА ОФИЦИАЛЕН ПРАЗНИК — само часовете, без нито едно правило върху тях.
   *
   * <p><b>Какво този метод НЕ прави.</b> Не разделя нормален труд от извънреден. Работата на
   * официален празник по чл. 264 КТ се заплаща с увеличение, а дали часът е и ИЗВЪНРЕДЕН зависи от
   * това дали денят е бил работен по графика на лицето — правило, което иска графика и живее на
   * WS-3. Тук се дава само числителят: колко часа са отчетени на дати, които календарът обявява за
   * официални празници.
   *
   * <p><b>⚠️ ТРИТЕ СЪСТОЯНИЯ, И ДВЕТЕ ПРИЧИНИ ЗА „НЕ ЗНАМ“.</b>
   *
   * <ul>
   *   <li><b>Няма отчет изобщо</b> → {@code null}. Същият довод като {@link WorkedTime#unknown()}:
   *       празната заявка дава сбор нула, а нула часа на празник е и отговорът на човек, който е
   *       почивал. Слети, липсата на отчет би станала твърдение.
   *   <li><b>Календарът не покрива годината</b> → {@code null}, И ТОВА Е ГЛАВНИЯТ КАПАН ТУК.
   *       Непокрита година няма нито един ред в таблицата с празници, тоест списъкът излиза ПРАЗЕН
   *       и сборът става 0,00 — „не е работено на празник“ — при това с пълен отчет зад себе си.
   *       Това е ЗАНИЖЕНО число, представено като измерено: увеличението по чл. 264 КТ просто няма
   *       да се начисли и нищо няма да изглежда като грешка. Същият довод, с който {@link
   *       #workingDaysInPeriodOf} маха знаменателя.
   *   <li><b>Отчет има, календарът покрива, на празниците не е работено</b> → {@code ZERO}. ТОВА Е
   *       СТОЙНОСТ, не липса: и трите въпроса имат отговор.
   * </ul>
   */
  protected BigDecimal holidayWorkHoursOf(
      Map<LocalDate, BigDecimal> workedByDate,
      BgWorkingDayService.Count count,
      LocalDate from,
      LocalDate to) {
    if (workedByDate == null || workedByDate.isEmpty()) {
      return null;
    }
    if (count == null || !count.isReliable()) {
      return null;
    }
    Set<LocalDate> holidays = officialHolidays(from, to);
    BigDecimal sum = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> day : workedByDate.entrySet()) {
      if (day.getKey() != null && day.getValue() != null && holidays.contains(day.getKey())) {
        sum = sum.add(day.getValue());
      }
    }
    return sum;
  }

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА (подменят се в тестовете) ════════════════════════

  /** Работните дни в интервала, заедно с покритието на календара. */
  protected BgWorkingDayService.Count workingDays(LocalDate from, LocalDate to) {
    if (from == null) {
      return null;
    }
    LocalDate end = to != null ? to : from;
    int calendarDays = (int) (end.toEpochDay() - from.toEpochDay()) + 1;
    return Beans.get(BgWorkingDayService.class).count(from, calendarDays);
  }

  /** Официалните празници в интервала. Отделен шев, за да се подменя без база. */
  protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      return Collections.emptySet();
    }
    return Beans.get(BgWorkingDayService.class).officialHolidaysIn(from, to);
  }

  /**
   * ОТРАБОТЕНИТЕ ЧАСОВЕ ПО ДАТИ, от ВАЛИДИРАНИ отчети — суровата разбивка, преди всякакъв сбор.
   *
   * <p>Същите две ограничения като {@link #workedTime}, по същите причини: иска се валидиран отчет
   * (черновата е нечие намерение, не вход за ведомост) и се чете {@code hoursDuration}, а не {@code
   * duration} (второто е в единицата на предпочитанието и сборът му е сбор от несравними числа).
   *
   * <p>⚠️ Един ден с ДВА реда се сумира в ЕДИН запис — иначе часовете на празника щяха да се броят
   * само веднъж, вместо толкова пъти, колкото са редовете.
   */
  protected Map<LocalDate, BigDecimal> workedHoursByDate(
      Employee employee, LocalDate from, LocalDate to) {
    if (employee == null || from == null || to == null) {
      return Collections.emptyMap();
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT l.date, l.hoursDuration FROM TimesheetLine l"
                    + " WHERE l.employee = :employee AND l.date BETWEEN :from AND :to"
                    + " AND l.timesheet.statusSelect = :validated",
                Object[].class)
            .setParameter("employee", employee)
            .setParameter("from", from)
            .setParameter("to", to)
            .setParameter("validated", TimesheetRepository.STATUS_VALIDATED)
            .getResultList();
    return hoursByDate(rows);
  }

  /** Сумира редовете по дата. Публично статична, за да се сверява без сглобител. */
  public static Map<LocalDate, BigDecimal> hoursByDate(List<Object[]> rows) {
    Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
    if (rows == null) {
      return out;
    }
    for (Object[] row : rows) {
      if (!(row[0] instanceof LocalDate)) {
        continue;
      }
      LocalDate date = (LocalDate) row[0];
      BigDecimal hours = row[1] instanceof BigDecimal ? (BigDecimal) row[1] : BigDecimal.ZERO;
      BigDecimal seen = out.get(date);
      out.put(date, seen == null ? hours : seen.add(hours));
    }
    return out;
  }

  /**
   * Отработеното от отчетите за време — часове и дни, всяко със своето „не знам“.
   *
   * <p>⚠️ ИСКА СЕ ВАЛИДИРАН ОТЧЕТ. Черновата не е вход за ведомост: тя е нечие намерение. Ред без
   * {@code timesheet} се изпуска от вътрешното съединение по същата причина — няма кой да го е
   * валидирал.
   *
   * <p>⚠️ ЧЕТЕ СЕ {@code hoursDuration}, а НЕ {@code duration}. Второто е в единицата на
   * предпочитанието за отчитане (часове ИЛИ дни), тоест сборът му е сбор от несравними числа;
   * първото е винаги в часове — проверено в {@code TimesheetLineServiceImpl.computeHoursDuration}.
   */
  protected WorkedTime workedTime(Employee employee, LocalDate from, LocalDate to) {
    if (employee == null || from == null || to == null) {
      return WorkedTime.unknown();
    }
    return WorkedTime.of(workedHoursByDate(employee, from, to));
  }

  /**
   * Идентификаторите на ПОТВЪРДЕНИТЕ обезщетения, чийто период на изплащане пада в този период.
   *
   * <p>⚠️ {@code bgPayoutPeriod} е гола дата, а не препратка към период на ведомост: {@code
   * BgPayrollPeriod} в модула не съществува и това е ЗАПИСАН ДЪЛГ в домейна на {@code
   * BgCompensation}, не решение. Оттам и филтърът е по интервал, а не по равенство.
   */
  protected List<Long> compensationDocumentIds(
      EmploymentContract contract, LocalDate from, LocalDate to) {
    if (contract == null || contract.getId() == null || from == null || to == null) {
      return Collections.emptyList();
    }
    return JPA.em()
        .createQuery(
            "SELECT c.id FROM BgCompensation c WHERE c.employmentContract.id = :contract"
                + " AND c.statusSelect = :confirmed"
                + " AND c.bgPayoutPeriod BETWEEN :from AND :to"
                + " ORDER BY c.id",
            Long.class)
        .setParameter("contract", contract.getId())
        .setParameter("confirmed", BgCompensationResolver.CONFIRMED)
        .setParameter("from", from)
        .setParameter("to", to)
        .getResultList();
  }

  /**
   * БОЛНИЧНИТЕ ЕПИЗОДИ за този договор, ЗАСЯГАЩИ този период — от {@code BgSickLeave}.
   *
   * <p><b>⚠️ ПРИПОКРИВАНЕ, А НЕ ПОПАДАНЕ.</b> Филтърът пита кои епизоди ЗАСЯГАТ периода, а не кои
   * ЗАПОЧВАТ в него. Разликата не е педантизъм: първите два работни дни по чл. 40 ал. 5 КСО се
   * броят от НАЧАЛОТО НА ЕПИЗОДА, а то може да е в предходния месец. Болничен от 29 юни до 5 юли
   * има своите два дни в ЮНИ — тоест в юлския фиш работодателят не дължи нищо по него. Взет само
   * ако започва в периода, той щеше да изчезне от юли и ДОО щеше да плати от първия ден. {@code
   * BgSickLeaveService#holidaysFor} прави същото допускане от другата страна и разчита епизодът да
   * му е подаден цял.
   *
   * <p><b>⚠️ САМО ВАЛИДИРАНИ.</b> Същият довод като при отчета за време: черновата е нечие
   * намерение, не документ. Отказаният епизод също отпада — иначе поправката чрез отказ и нов запис
   * би платила два пъти.
   *
   * <p><b>⚠️ ПЛАТЕЦЪТ И ПРОЦЕНТЪТ НЕ СЕ ЧЕТАТ ТУК.</b> Контрактът носи само кода на вида, а {@code
   * BgSickLeaveService#episodesOf} го разрешава срещу речника. Прочетени и тук, те щяха да са трето
   * копие на {@code BgAbsenceType} — и щяха да се разминат с него при първата промяна.
   *
   * <p>⚠️ Празният списък е ПРАЗЕН СПИСЪК, не {@code null}: „никой не е въвел болничен“ и „няма
   * болничен“ съвпадат тук, за разлика от отчета за време. Причината е, че болничният е ДОКУМЕНТ:
   * той не съществува неотразен, докато отработеният час съществува неотчетен.
   *
   * <p><b>⚠️ И ИД-ТО НА ЗАПИСА ВЛИЗА В ПРОЕКЦИЯТА, НЕ САМО НОМЕРЪТ.</b> Разбивът по т. 9 на
   * Приложение № 9 се записва ОБРАТНО върху листа ({@code BgSickLeaveService#applyPeriodLines}), а
   * номерът не е ключ — домейнът го оставя без {@code unique}, защото един лист поражда епизод за
   * всеки договор на лицето. Върнатият обратно по номер, редът щеше да се закачи на произволен от
   * няколко законни записа.
   *
   * <p><b>⚠️ И ЦЯЛАТА ВЕРИГА НАЗАД, ПО ВРЪЗКАТА (A2).</b> Припокриването стига, докато
   * продължението и началото му засягат един и същ период. Не стига при продължение от 1-во число:
   * първият лист свършва в предходния месец, не засяга този и не влизаше в списъка — продължението
   * излизаше първо, откриваше нов период и работодателят плащаше първите два дни ВТОРИ път. Затова
   * за всеки лист с {@code bgContinuationOf} заявката тегли предходния, неговия предходен и т.н. —
   * само ВАЛИДИРАНИ и само по СЪЩИЯ договор. Скъсана верига (липсваща връзка, отказан лист, чужд
   * договор) не се закърпва тук: продължението остава първо в периода си и резолверът отказва
   * ({@code BgSickLeaveResolver#split}). Дните на издърпаните листове извън периода не влизат в
   * сметката — резолверът реже по периода; те носят само НАЧАЛОТО.
   */
  protected List<BgPayrollInput.SickLeaveEpisode> sickLeaveEpisodesOf(
      EmploymentContract contract, LocalDate from, LocalDate to) {
    if (contract == null || contract.getId() == null || from == null || to == null) {
      return Collections.emptyList();
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT s.fromDate, s.toDate, s.bgIsContinuation, s.bgDocumentNumber,"
                    + " t.code, s.id, p.id"
                    + " FROM BgSickLeave s JOIN s.bgAbsenceType t LEFT JOIN s.bgContinuationOf p"
                    + " WHERE s.employmentContract.id = :contract"
                    + " AND s.statusSelect = :validated"
                    + " AND s.fromDate <= :to AND s.toDate >= :from"
                    + " ORDER BY s.fromDate, s.id",
                Object[].class)
            .setParameter("contract", contract.getId())
            .setParameter("validated", SICK_LEAVE_VALIDATED)
            .setParameter("from", from)
            .setParameter("to", to)
            .getResultList();

    Map<Long, Object[]> byId = new LinkedHashMap<>();
    for (Object[] row : rows) {
      byId.put((Long) row[5], row);
    }
    // ⚠️ ВЕРИГАТА НАЗАД — с пазач срещу цикъл: връзката е данни и нищо в схемата не забранява
    // A → B → A. Обходът спира на вече видян лист, на лист по друг договор и на невалидиран.
    List<Long> pending = new ArrayList<>();
    for (Object[] row : rows) {
      if (row[6] != null) {
        pending.add((Long) row[6]);
      }
    }
    Set<Long> visited = new HashSet<>(byId.keySet());
    while (!pending.isEmpty()) {
      Long id = pending.remove(0);
      if (!visited.add(id)) {
        continue;
      }
      Object[] previous = chainLinkOf(id, contract.getId());
      if (previous == null) {
        continue;
      }
      byId.put(id, previous);
      if (previous[6] != null) {
        pending.add((Long) previous[6]);
      }
    }

    List<Object[]> all = new ArrayList<>(byId.values());
    all.sort(
        (a, b) -> {
          int byStart = ((LocalDate) a[0]).compareTo((LocalDate) b[0]);
          return byStart != 0 ? byStart : ((Long) a[5]).compareTo((Long) b[5]);
        });
    List<BgPayrollInput.SickLeaveEpisode> out = new ArrayList<>();
    for (Object[] row : all) {
      out.add(
          new BgPayrollInput.SickLeaveEpisode(
              (LocalDate) row[0],
              (LocalDate) row[1],
              // Продължение е и листът с връзка, и заварен лист само с отметка.
              Boolean.TRUE.equals(row[2]) || row[6] != null,
              (String) row[3],
              (String) row[4],
              (Long) row[5]));
    }
    return out;
  }

  /**
   * Един лист от веригата назад — в проекцията на {@link #sickLeaveEpisodesOf}, или {@code null},
   * ако не е ВАЛИДИРАН или е по ДРУГ договор (тогава веригата е скъсана и резолверът отказва).
   */
  protected Object[] chainLinkOf(Long sickLeaveId, Long contractId) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT s.fromDate, s.toDate, s.bgIsContinuation, s.bgDocumentNumber,"
                    + " t.code, s.id, p.id"
                    + " FROM BgSickLeave s JOIN s.bgAbsenceType t LEFT JOIN s.bgContinuationOf p"
                    + " WHERE s.id = :id AND s.employmentContract.id = :contract"
                    + " AND s.statusSelect = :validated",
                Object[].class)
            .setParameter("id", sickLeaveId)
            .setParameter("contract", contractId)
            .setParameter("validated", SICK_LEAVE_VALIDATED)
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * ИЗВЪНРЕДНИЯТ ТРУД ЗА ПЕРИОДА, разпределен по четирите вида.
   *
   * <p>Чете ВАЛИДИРАНИ редове с допълнителни часове и ги разпределя през класификацията {@code
   * ExtraHoursType.bgExtraHoursKind}.
   *
   * <p>⚠️ Нула валидирани реда → четирите са {@code null}, не нула. Инсталация, която не води
   * извънредния труд в модула, не е инсталация без извънреден труд — преброена като нула, тя отнема
   * увеличението по чл. 262 ал. 1 КТ на всекиго и не изглежда като грешка.
   *
   * <p>⚠️ Некласифициран вид → ОТКАЗ за ЧЕТИРИТЕ. Виж {@link LeaveDays} за същия довод.
   *
   * <p>⚠️ Редовете НЕ се филтрират по припокриване, а по ДАТА НА РЕДА: {@code ExtraHoursLine.date}
   * е един ден, не интервал — тоест тук няма пресичане на граница, което да иска отказ.
   */
  protected OvertimeHours overtimeHoursOf(Employee employee, LocalDate from, LocalDate to) {
    if (employee == null || from == null || to == null) {
      return OvertimeHours.unknown();
    }
    List<Object[]> rows = extraHoursRows(employee, from, to);
    if (rows.isEmpty()) {
      return OvertimeHours.unknown();
    }

    Map<String, BigDecimal> kinds = new LinkedHashMap<>();
    for (String kind :
        new String[] {OVERTIME_WORKDAY, OVERTIME_REST_DAY, OVERTIME_HOLIDAY, OVERTIME_SIRV}) {
      kinds.put(kind, BigDecimal.ZERO);
    }

    for (Object[] row : rows) {
      BigDecimal qty = row[0] instanceof BigDecimal ? (BigDecimal) row[0] : BigDecimal.ZERO;
      String kind = (String) row[1];
      if (kind == null || kind.trim().isEmpty() || !kinds.containsKey(kind.trim())) {
        return OvertimeHours.refused(refuse("extraHoursTypeUnclassified"));
      }
      kinds.put(kind.trim(), kinds.get(kind.trim()).add(qty));
    }
    return new OvertimeHours(
        kinds.get(OVERTIME_WORKDAY),
        kinds.get(OVERTIME_REST_DAY),
        kinds.get(OVERTIME_HOLIDAY),
        kinds.get(OVERTIME_SIRV),
        null);
  }

  /** Редовете на ВАЛИДИРАНИТЕ допълнителни часове в периода, с класификацията на вида си. */
  protected List<Object[]> extraHoursRows(Employee employee, LocalDate from, LocalDate to) {
    return JPA.em()
        .createQuery(
            "SELECT l.qty, t.bgExtraHoursKind FROM ExtraHoursLine l"
                + " LEFT JOIN l.extraHoursType t"
                + " WHERE l.employee = :employee AND l.date BETWEEN :from AND :to"
                + " AND l.extraHours.statusSelect = :validated"
                + " ORDER BY l.date, l.id",
            Object[].class)
        .setParameter("employee", employee)
        .setParameter("from", from)
        .setParameter("to", to)
        .setParameter("validated", EXTRA_HOURS_VALIDATED)
        .getResultList();
  }

  /**
   * ДЕКЛАРИРАНИТЕ ЧАСОВЕ за (договор, период) — нощен труд и разположение.
   *
   * <p>⚠️ ТЪРСИ СЕ ТОЧНО ТОЗИ ПЕРИОД, не припокриване. Декларацията е твърдение за ЦЕЛИЯ период;
   * взета от съседен, тя щеше да отнесе чужди часове насам. Липсата ѝ е {@code null}, а не нула —
   * недекларираният нощен труд не е липсващ нощен труд, а неизмерен.
   */
  protected DeclaredHours declaredHoursOf(
      EmploymentContract contract, LocalDate from, LocalDate to) {
    if (contract == null || contract.getId() == null || from == null || to == null) {
      return DeclaredHours.unknown();
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT d.bgNightHours, d.bgOnCallHours FROM BgDeclaredWorkHours d"
                    + " WHERE d.employmentContract.id = :contract"
                    + " AND d.periodFrom = :from AND d.periodTo = :to",
                Object[].class)
            .setParameter("contract", contract.getId())
            .setParameter("from", from)
            .setParameter("to", to)
            .getResultList();
    return declaredHoursFrom(rows);
  }

  /**
   * Превръща редовете на декларацията в двете числа.
   *
   * <p>⚠️ {@code null} на колоната се чете като НУЛА, защото редът вече е обявил периода за
   * деклариран: празната клетка при съществуваща декларация значи „няма такива часове“, а не „не
   * знам“. Точно затова разликата живее в СЪЩЕСТВУВАНЕТО на реда, а не в стойността — генерираният
   * getter така или иначе не би върнал {@code null}.
   */
  static DeclaredHours declaredHoursFrom(List<Object[]> rows) {
    if (rows == null || rows.isEmpty()) {
      return DeclaredHours.unknown();
    }
    BigDecimal night = BigDecimal.ZERO;
    BigDecimal onCall = BigDecimal.ZERO;
    for (Object[] row : rows) {
      if (row[0] instanceof BigDecimal) {
        night = night.add((BigDecimal) row[0]);
      }
      if (row[1] instanceof BigDecimal) {
        onCall = onCall.add((BigDecimal) row[1]);
      }
    }
    return new DeclaredHours(night, onCall);
  }

  /**
   * ОТПУСКИТЕ ЗА ПЕРИОДА, разпределени по кофи — трите състояния наведнъж.
   *
   * <p>Чете ВАЛИДИРАНИ заявки, засягащи периода, и ги разпределя през съпоставянето {@code
   * LeaveReason.bgAbsenceType}. Всичко, което не може да се преброи ЧЕСТНО, е ОТКАЗ с назована
   * причина — виж {@link #REFUSALS}.
   *
   * <p><b>⚠️ ТРИ ФОРМИ СЕ ОБРАБОТВАТ, А НЕ СЕ ОТКАЗВАТ.</b> Заявка, пресичаща границата на периода,
   * заявка в ЧАСОВЕ и дробен сбор дни не са отказ — понеже отказът обезсилва ЦЯЛАТА тройка, ЕДНА
   * такава заявка някъде през месеца би оставила {@code unpaidLeaveDays} празно. А то има точно
   * един консуматор ({@code BgUnpaidLeaveHealthService}) и той при {@code null} не начислява НИЩО —
   * тоест здравната вноска при неплатен отпуск по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ би изчезнала от
   * фиша. Три отказа с добра мотивировка биха събрали недекларирана вноска.
   *
   * <ul>
   *   <li><b>ПРЕСИЧАЩА ГРАНИЦАТА</b> → разпределя се по периоди, виж {@link #leavePartInPeriod}.
   *   <li><b>В ЧАСОВЕ</b> → превръща се в дни през договорения ден, виж {@link #leaveHoursAsDays}.
   *   <li><b>ДРОБЕН ДЕН</b> → носи се какъвто е: кофите вече са {@code BigDecimal}.
   * </ul>
   *
   * <p><b>⚠️ ДРОБНИЯТ ДЕН НЕ Е АНОМАЛИЯ, А ЗАДЪЛЖИТЕЛНИЯТ ОБРАЗ НА ДВА ЗАКОНА В ДНИ.</b> КТ чл. 166
   * дава отпуск за кърмене по 1 (или 2, или 3) ЧАСА дневно, платен от работодателя (ал. 4); КТ чл.
   * 157 ал. 1 т. 6 дава по 1 час дневно при предизвестие, платен „в размера по чл. 177“ (ал. 3 т.
   * 2). Един час при осемчасов ден Е 0,125 дни — число, което {@code Integer} не може да изрази.
   * Закръглено на входа, правото се трие тихо; затова закръглението слиза при консуматора, който
   * ИСКА цял ден (Декларация обр. 1, т. 16), а входът носи точността.
   *
   * <p><b>⚠️ И ДВАТА ОСТАВАЩИ ОТКАЗА СА НАЗОВАНИ, не тихи.</b> Липсващ делител за часовете ({@code
   * leaveHoursWithoutContractedDay}) и заявка, която не покрива нито един работен ден ({@code
   * leaveOutsideWorkingDays}).
   *
   * <p><b>⚠️ ЕДИН КАЛЕНДАР, А НЕ ВТОРИ.</b> Работните дни се броят с {@link
   * BgSickLeaveResolver#workingDaysBetween} върху {@link #officialHolidays} — точно машинарията, с
   * която болничният енджин вече реже епизод по границата на периода. Базовият {@code
   * LeaveRequestComputeDurationService} също умее да реже по период, но чете {@code WeeklyPlanning}
   * и {@code EventsPlanning} на служителя — ВТОРИ календар. Числителят (дните отпуск) и
   * знаменателят ({@link #workingDaysInPeriodOf}, на който те после се делят) щяха да идват от два
   * източника, свободни да се разминат; а разминаване тук значи дял на месеца, по-голям от едно.
   *
   * <p>⚠️ Несъпоставеният вид отпуск ОСТАВА отказ: не се знае в коя кофа е трябвало да влезе.
   * Преброен в „други“, платеният отпуск щеше да излезе от осигурителния доход тихо.
   *
   * <p>⚠️ И ЕДНО „НЕ ЗНАМ“, КОЕТО НЕ Е ОТКАЗ: нула валидирани заявки. Тогава трите са {@code null},
   * защото празната заявка дава сбор нула — същото число, което дава и „не е ползван отпуск“.
   * Инсталация, която просто не води отпуските си в модула, щеше да отчете нула дни отсъствие за
   * всекиго, тоест ЗАВИШЕНО отработено време. Същият довод като {@link WorkedTime#unknown()}.
   */
  protected LeaveDays leaveDaysOf(Employee employee, LocalDate from, LocalDate to) {
    return leaveDaysOf(employee, from, to, null);
  }

  /**
   * Както горното, но с ДЕЛИТЕЛЯ за заявките в часове.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗШИРЕН ШЕВ</b> — по същия образец като {@link
   * #build(EmploymentContract, Period, BigDecimal)}. Триаргументният вход остава и делегира с
   * {@code null}; разширен на място, той щеше да повали всеки заварен тест, който го вика.
   *
   * <p>⚠️ {@code null} тук НЕ е подразбиране, а ЛИПСА НА ДЕЛИТЕЛ: заявка в дни минава и без него,
   * заявка в часове получава назования отказ {@code leaveHoursWithoutContractedDay}. Осем часа по
   * подразбиране биха били верни за повечето и грешни точно за онези, заради които полето
   * съществува.
   *
   * @param hoursPerDay ДОГОВОРЕНИТЕ часове на ден на ЛИЦЕТО ({@code
   *     EmploymentContract.bgHoursPerDay}), или {@code null}. НЕ е {@code bgLegalHoursPerDay}
   */
  protected LeaveDays leaveDaysOf(
      Employee employee, LocalDate from, LocalDate to, BigDecimal hoursPerDay) {
    return leaveDaysOf(
        employee, from, to, hoursPerDay, Collections.<BgPayrollInput.SickLeaveEpisode>emptyList());
  }

  /**
   * Както горното, но СВЕРЕНО и срещу валидираните болнични листове за същия период.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗШИРЕН ШЕВ</b> — трети път по същия образец. Четириаргументният
   * вход остава и делегира с ПРАЗЕН списък; разширен на място, той щеше да повали {@code
   * BgArt177LeaveService#absenceDaysOf} и десетината свидетеля, които подменят шева.
   *
   * <p><b>Какво добавя списъкът и защо е точно тук.</b> Единственият вид, който може да СЪЖИТЕЛСТВА
   * с болничен лист за същия ден, е „непредставен болничен“ — и двата описват едно и също
   * отсъствие, но в два взаимно изключващи се режима. Съжителството НЕ е безобидно: болничният
   * енджин вече е свалил дните от отработените ({@code BgSickLeaveService#applyDays}), а заявката
   * ги сваля ВТОРИ ПЪТ през карва. Резултатът е занижена основна заплата с вид на пресметната.
   *
   * <p><b>⚠️ ЗАЩО ОТКАЗ, А НЕ „предпочети листа“.</b> Мълчаливото предпочитание е избор между два
   * записа, които ЧОВЕК е въвел и които твърдят различни неща — а верният от двата зависи от факт
   * (дошъл ли е листът), който кодът не знае по-добре от оператора. Същият довод, с който {@code
   * absenceTypeInsuranceFlagsConflict} спира противоречивия речник вместо да гадае кофа.
   *
   * <p>⚠️ Празният списък НЕ значи „няма болнични“ — значи „не е питано“. Точно затова
   * четириаргументната врата подава празен: базовият месец по чл. 177 се брои с нея и там
   * кръстосаната проверка не е поискана.
   *
   * @param episodes валидираните болнични епизоди за същия период, или празен списък
   */
  protected LeaveDays leaveDaysOf(
      Employee employee,
      LocalDate from,
      LocalDate to,
      BigDecimal hoursPerDay,
      List<BgPayrollInput.SickLeaveEpisode> episodes) {
    if (employee == null || from == null || to == null) {
      return LeaveDays.unknown();
    }
    List<Object[]> rows = leaveRows(employee, from, to);
    if (rows.isEmpty()) {
      return LeaveDays.unknown();
    }
    // ⚠️ ЕДНО ЧЕТЕНЕ НА ПРАЗНИЦИТЕ ЗА ЦЕЛИЯ ОБХОД, а не по заявка. Втори прочит е свободен да
    // върне друг списък (календарът се редактира от формата), а тогава total и inside на една и
    // съща заявка биха дошли от два различни календара.
    Set<LocalDate> holidays = holidaysSpanning(rows, from, to);

    Map<String, BigDecimal> buckets = new LinkedHashMap<>();
    buckets.put(BUCKET_PAID, BigDecimal.ZERO);
    buckets.put(BUCKET_UNPAID, BigDecimal.ZERO);
    buckets.put(BUCKET_OTHER, BigDecimal.ZERO);
    // ⚠️ ПОДКОФА НА ПЛАТЕНИЯ, А НЕ ЧЕТВЪРТА КОФА. Дните по чл. 177 СА платен отпуск и трябва да
    // останат в paid — те са осигурителен стаж и пълнят т. 16.1 на Д1 точно както другите платени.
    // Извадени в отделна кофа, сборът на трите щеше да падне и същите дни щяха да изчезнат от
    // всеки читател на paid. Разграничението е за ЗАПЛАЩАНЕТО, не за броенето.
    BigDecimal under177 = BigDecimal.ZERO;
    // ⚠️ И ВТОРА ПОДКОФА, ПО СЪЩИЯ ОБРАЗЕЦ — този път на НЕПЛАТЕНИЯ. Дните с попълнен d1Code са
    // онези, които Декларация обр. 1 вади от т. 16.1 (т. 16.5 по КТ чл. 160 ал. 3 и т. 16.3 по
    // чл. 167а). Те остават в unpaid и се броят там — здравната вноска по ЗЗО чл. 40 ал. 1 т. 1
    // б. „б“ се дължи за ВСЕКИ ден неплатен отпуск. Разграничението е за ЗАПЛАЩАНЕТО.
    BigDecimal unpaidInD1 = BigDecimal.ZERO;
    // ⚠️ И ВТОРА ПОДКОФА НА НЕПЛАТЕНИЯ, ПО ДРУГ ПРИЗНАК — дните, за които се дължи здравната
    // вноска по ЗЗО чл. 40 ал. 1 т. 1 б. „б“. До разцепването на КТ чл. 157 това беше ЦЯЛАТА
    // неплатена кофа и затова число нямаше: петте вида от категория 7 всички носят платец.
    // paid_157_special е първият вид в тази кофа БЕЗ платец по б. „б“ — той не е неплатен отпуск, а
    // отпуск, заплащан от ДРУГ (КТ чл. 157 ал. 3 т. 3). Виж BgPayrollInput#unpaidLeaveDaysUnderZzo.
    BigDecimal unpaidUnderZzo = BigDecimal.ZERO;
    // ⚠️ И ТРЕТИ ПОДСБОР — но той НЕ Е подкофа на ЕДНА от трите, и това е разликата. Дните извън
    // осигуряването се разпръсват ПО ТРИТЕ кофи: самоотлъчката и неприсъствието са „други“,
    // неплатеният над 30 дни е „неплатен“, а непредставеният болничен (категория 2, без своя кофа)
    // се ПОВИШАВА в „други“ долу. Затова числото се брои УСПОРЕДНО на кофите — то отговаря на друг
    // въпрос („дължи ли се заплащане“), а не на техния („как се заплаща“).
    // ⚠️ ТО Е ПОДМНОЖЕСТВО НА СБОРА ИМ, и точно това прави инварианта на
    // BgPaySlipService#freezeLeaveDays верен по построение: ден, който тежи тук и в нито една
    // кофа, би накарал пазача да хвърли при всеки фиш с такава заявка.
    BigDecimal outsideInsurance = BigDecimal.ZERO;
    // ⚠️ ДНИТЕ ПО КОФА НА Т. 16 — СЪЩИЯТ ОБХОД, КОЙТО СМЯТА И ПАРИТЕ. Декларация обр. 1 не ги
    // смята НАНОВО със „огледална“ заявка над същите заявки за отпуск — огледалото се пази само
    // докато някой не пипне едната половина. Разминат ли се отказите (часове, граница на периода,
    // дробен ден), заявка 28.12–08.01 сваля 4 дни от декемврийската заплата и НУЛА от
    // декемврийската т. 16.5.
    Map<String, BigDecimal> byD1Code = new LinkedHashMap<>();
    // ⚠️ ГРАНИЦИТЕ на заявките, повишени в „други“ заради липсваща кофа — единственият вход на
    // кръстосаната проверка срещу болничните листове. Списък, а не брояч: отказът назовава записи.
    List<LocalDate[]> unbucketedOutside = new ArrayList<>();

    for (Object[] row : rows) {
      // ⚠️ fromDateT/toDateT са ДАТА С ЧАС. Отпускът се брои в дни, тоест часът е шум — но
      // сравнението с границите на периода трябва да стане ВЪРХУ ДАТИ, инак „до 31.03 00:00“ би
      // излязло след „31.03“ и цял месец отпуски щеше да се обяви за пресичащ границата.
      LocalDate leaveFrom = row[0] == null ? null : ((LocalDateTime) row[0]).toLocalDate();
      LocalDate leaveTo = row[1] == null ? null : ((LocalDateTime) row[1]).toLocalDate();
      BigDecimal duration = row[2] instanceof BigDecimal ? (BigDecimal) row[2] : BigDecimal.ZERO;
      Integer unit = (Integer) row[3];
      Integer category = (Integer) row[4];
      Boolean paid = (Boolean) row[5];
      Long absenceTypeId = (Long) row[6];
      Boolean paidUnderArt177 = (Boolean) row[7];
      String d1Code = (String) row[8];
      Boolean countsAsInsuranceService = (Boolean) row[9];
      // ⚠️ ЕДИНАЙСЕТАТА колона: правилото по ЗЗО чл. 40 ал. 1 т. 1 б. „б“. Чете се ЗАЕДНО с
      // дните, а не отделно от тях — виж BgPayrollInput#unpaidLeaveDaysUnderZzo защо вноската не
      // се дължи за всеки ден от неплатената кофа.
      String healthContributionRule = (String) row[10];

      if (absenceTypeId == null) {
        return LeaveDays.refused(refuse("leaveReasonUnmapped"));
      }
      // ⚠️ ПРОТИВОРЕЧИВИЯТ РЕЧНИК СПИРА ФИША, И ТОВА Е ОБРАЗЕЦЪТ НА leaveReasonUnmapped ОТГОРЕ.
      // Кофа и признак, които сочат в различни посоки, не могат да се прочетат нито като „плати“,
      // нито като „не плащай“ — а мълчаливият избор между двете е разликата между пълна заплата и
      // карвната. Проверката е ПРЕДИ разпределението по кофи, за да хване и вида, който излиза
      // през continue-то долу (непредставеният болничен е категория 2).
      if (BgD1DayBuckets.insuranceFlagsConflict(d1Code, countsAsInsuranceService)) {
        return LeaveDays.refused(refuse("absenceTypeInsuranceFlagsConflict"));
      }
      String bucket = leaveBucketOf(category, Boolean.TRUE.equals(paid));
      // ⚠️ ЕДИН ПРЕДИКАТ, ДВА ЧИТАТЕЛЯ: същият метод чете и BgD1DaySource, когато вади същите дни
      // от т. 16.1. Написан тук наново, той е свободен да се размине с онзи — а разминат, фишът
      // плаща 17 дни срещу декларация за 22 дни в осигуряване.
      boolean outside = BgD1DayBuckets.outsideInsurance(d1Code, countsAsInsuranceService);
      if (bucket == null && !outside) {
        // Болничен/злополука/майчинство, стигнал дотук като заявка за отпуск: НЕ се брои, защото
        // пътува като епизод. Пропускането е нарочно и не е отказ.
        // ⚠️ И ИСТИНСКИЯТ БОЛНИЧЕН ИЗЛИЗА ТУК — той се зачита за осигурителен стаж
        // (countsAsInsuranceService = true), тоест outside е false. Разграничителят между него и
        // непредставения болничен е признакът, а НЕ категорията: и двата са категория 2.
        continue;
      }
      // ⚠️ ИЗВЪН ОСИГУРЯВАНЕТО БЕЗ СВОЯ КОФА → „ДРУГИ“. ТОВА Е САМАТА ПОПРАВКА, И ТЯ Е В
      // БРОЕНЕТО, НЕ В ПАЗАЧА.
      //
      // Петте вида в BgD1DayBuckets.UNBUCKETED се разпръскваха по кофите НЕРАВНОМЕРНО: неплатеният
      // над 30 дни е категория 7 → „неплатен“, самоотлъчката и неприсъствието са категория 5 →
      // „други“, а непредставеният болничен е категория 2 → leaveBucketOf връща null и той не
      // попадаше в НИТО ЕДНА. Оттам той влизаше в outsideInsurance и в НИЩО друго — тоест беше
      // единственият вид, който тежи отляво на инварианта и липсва отдясно.
      //
      // ⚠️ И ТОВА НЕ БЕШЕ КОЗМЕТИКА: BgPaySlipService#freezeLeaveDays сверява
      // „Σкофи + outside ≤ преброени дни отсъствие (платен + неплатен + други)“ и ХВЪРЛЯ при
      // надвишаване. Три дни непредставен болничен даваха 3 срещу 0 — тоест prepare() падаше с
      // IllegalStateException на ВСЕКИ фиш с такава заявка, а сценарият, заради който видът изобщо
      // съществува („болничният още не е дошъл“), беше непостижим по нормалния път. Поправката
      // затваря дупката ПО ПОСТРОЕНИЕ: след този ред всеки ден от outsideInsurance е и в някоя от
      // трите кофи, тоест лявата страна вече не може да надвиши дясната заради липсваща кофа.
      // Пазачът остава непокътнат — разхлабен вместо това, той щеше да замълчи и за истинските
      // разминавания, заради които съществува.
      //
      // ⚠️ ЗАЩО „ДРУГИ“, А НЕ ЧЕТВЪРТА КОФА. Трите кофи описват КАК се заплаща отсъствието;
      // за непредставения болничен отговорът е „не се заплаща“ — точно отговорът на самоотлъчката
      // и неприсъствието, които вече са там. Четвърта кофа би поискала ново поле на контракта, нов
      // читател във freezeLeaveDays и нов ред в BgArt177LeaveService — три места вместо нула.
      //
      // ⚠️ И ЕДНА ПОСЛЕДИЦА В ПАРИТЕ, ОБЯВЕНА, А НЕ ПРЕМЪЛЧАНА: BgArt177LeaveService
      // #absenceDaysOf сумира платен + неплатен + други за базовия месец по КТ чл. 177 ал. 1, тоест
      // непредставеният болничен СЕ ВАДИ от отработените дни на базовия месец. Това е верният
      // прочит на НСОРЗ чл. 18 ал. 1 („среднодневното … се дели на ОТРАБОТЕНИТЕ дни“): ден без труд
      // и без заплащане не е отработен ден. Премълчан, той би НАДУЛ знаменателя и оттам ЗАНИЖИЛ
      // среднодневното — тоест би платил по-малко за платения отпуск.
      if (bucket == null) {
        bucket = BUCKET_OTHER;
        // ⚠️ И СЪЩИТЕ ДНИ СЕ ЗАПОМНЯТ ЗА КРЪСТОСАНАТА ПРОВЕРКА С БОЛНИЧНИТЕ ЛИСТОВЕ. Повишеният
        // ред е точно видът, който може да СЪЖИТЕЛСТВА с валидиран болничен за същия ден —
        // „непредставен болничен“ и „ето го болничния“ за едно и също отсъствие. Запомнят се
        // ГРАНИЦИТЕ на заявката, а не броят дни: отказът трябва да НАЗОВЕ записа, а брой дни не
        // сочи към нищо, което операторът може да отвори.
        unbucketedOutside.add(new LocalDate[] {leaveFrom, leaveTo});
      }

      // ⚠️ РЕЗАНЕТО Е ПЪРВО, ПРЕВРЪЩАНЕТО — ВТОРО, И РЕДЪТ НЕ Е СВОБОДЕН. duration е в СВОЯТА
      // единица (дни или часове) и за ЦЯЛАТА заявка; частта в този месец е дял от нея, тоест
      // делението по периоди се прави в същата единица. Разменени, часовете щяха да се превърнат в
      // дни за цялата заявка и чак после да се режат — същият резултат само когато делението е
      // точно, и различен при всяко закръгляне.
      BigDecimal part = leavePartInPeriod(leaveFrom, leaveTo, duration, from, to, holidays);
      if (part == null) {
        return LeaveDays.refused(refuse("leaveOutsideWorkingDays"));
      }
      if (unit == null || unit != LEAVE_UNIT_DAYS) {
        part = leaveHoursAsDays(part, hoursPerDay);
        if (part == null) {
          return LeaveDays.refused(refuse("leaveHoursWithoutContractedDay"));
        }
      }
      // ⚠️ КОФАТА НЕ МОЖЕ ДА Е null ТУК, И ТОВА Е ИНВАРИАНТЪТ, А НЕ УДОБСТВО. Всеки ред,
      // стигнал дотук, или има кофа по категория, или е бил повишен в „други“ малко по-горе, защото
      // е извън осигуряването. Оттам „Σкофи ≥ outside“ важи по построение — точно проверката, която
      // BgPaySlipService#freezeLeaveDays прави, включително за непредставения болничен.
      // Трите кофи описват КАК се заплаща отсъствието; карвът описва ДАЛИ изобщо се дължи
      // заплащане, а второто не е подмножество на първото — затова outsideInsurance остава ОТДЕЛЕН
      // подсбор, а не четвърта кофа.
      buckets.put(bucket, buckets.get(bucket).add(part));
      if (outside) {
        outsideInsurance = outsideInsurance.add(part);
      } else if (d1Code != null && !d1Code.trim().isEmpty()) {
        // ⚠️ СУРОВО, НЕЗАКРЪГЛЕНО. Т. 16 се попълва в ЦЕЛИ дни (Numeric ≤2 в файловия формат), но
        // закръглянето е ФОРМАТНА грижа и живее в Д1 — тук се пази това, което фишът наистина е
        // платил. Закръглено още тук, дробният ден щеше да се закръгли ДВА пъти при две заявки в
        // един месец: 0,5 + 0,5 щеше да стане 1 + 1 вместо 1.
        // ⚠️ И САМО КОГАТО НЕ Е ИЗВЪН ОСИГУРЯВАНЕТО: outside и кофа са ВЗАИМНОИЗКЛЮЧВАЩИ се по
        // конструкцията на outsideInsurance (той иска ПРАЗНА кофа). Броени и на двете места, дните
        // щяха да излязат два пъти от т. 16.1.
        byD1Code.merge(d1Code.trim(), part, BigDecimal::add);
      }
      // ⚠️ ФЛАГЪТ СЕ ПИТА САМО ВЪТРЕ В ПЛАТЕНАТА КОФА. Вид, отметнат по чл. 177, но попаднал в
      // „неплатен“ или „други“, е сгрешен запис в речника, а не второ основание за плащане —
      // събран тук, той би платил по чл. 177 дни, които изобщо не се заплащат.
      if (BUCKET_PAID.equals(bucket) && Boolean.TRUE.equals(paidUnderArt177)) {
        under177 = under177.add(part);
      }
      // ⚠️ И КОФАТА СЕ ПИТА САМО ВЪТРЕ В НЕПЛАТЕНАТА. Платен отпуск с кофа 16.1 не е „неплатен,
      // зачетен за стаж“ — събран тук, той би СВАЛИЛ от заплатата дни, които тя дължи.
      // ⚠️ И празният код е СТОЙНОСТ, а не липса: точно той бележи петте вида, които не се зачитат
      // за осигурителен стаж (BgD1DayBuckets.UNBUCKETED) — те си имат СВОЙ подсбор по-горе.
      // „Непопълнена клетка“ вече НЕ се слива с „нарочно извън т. 16“: празна кофа без признака
      // countsAsInsuranceService = false е ПРОТИВОРЕЧИЕ и излиза като назован отказ преди тук.
      if (BUCKET_UNPAID.equals(bucket) && d1Code != null && !d1Code.trim().isEmpty()) {
        unpaidInD1 = unpaidInD1.add(part);
      }
      // ⚠️ И ВТОРО ПОДМНОЖЕСТВО НА СЪЩАТА КОФА, по ДРУГ признак. Карвът пита „дължи ли
      // работодателят заплащане“; б. „б“ пита „НЕПЛАТЕН ОТПУСК ли е“ — и от разцепването на чл. 157
      // двете не са един въпрос. Платецът се чете от речника (не от списък с кодове тук), а
      // отсъствието на платец е СТОЙНОСТ: значи, че хипотезата на б. „б“ изобщо не се открива.
      if (BUCKET_UNPAID.equals(bucket)
          && BgUnpaidLeaveHealthResolver.payerFor(healthContributionRule) != null) {
        unpaidUnderZzo = unpaidUnderZzo.add(part);
      }
    }

    // ⚠️ КРЪСТОСАНАТА ПРОВЕРКА Е СЛЕД ЦЕЛИЯ ОБХОД, А НЕ ВЪТРЕ В НЕГО. Вътре тя щеше да се задейства
    // при ПЪРВАТА засечена двойка и да премълчи останалите; отказът трябва да изброи всички, инак
    // операторът поправя една заявка, натиска пак и получава същото съобщение за следващата.
    String clash = sickNoteArrivalClash(unbucketedOutside, episodes);
    if (clash != null) {
      return LeaveDays.refused(clash);
    }

    Map<String, BigDecimal> scaledByD1Code = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> e : byD1Code.entrySet()) {
      scaledByD1Code.put(e.getKey(), scaled(e.getValue()));
    }
    return new LeaveDays(
        scaled(buckets.get(BUCKET_PAID)),
        scaled(buckets.get(BUCKET_UNPAID)),
        scaled(buckets.get(BUCKET_OTHER)),
        scaled(under177),
        scaled(unpaidInD1),
        scaled(unpaidUnderZzo),
        scaled(outsideInsurance),
        scaledByD1Code,
        null);
  }

  /**
   * ЧАСТТА ОТ ЗАЯВКАТА, КОЯТО ПАДА В ТОЗИ ПЕРИОД — в СЪЩАТА единица, в която е {@code duration}.
   *
   * <p><b>ПРАВИЛОТО.</b> Продължителността се разпределя по РАБОТНИТЕ ДНИ на заявката:
   *
   * <pre>част = duration × (работни дни в периода) ÷ (работни дни на цялата заявка)</pre>
   *
   * <p><b>⚠️ ЗАЯВКА, КОЯТО НЕ ПРЕСИЧА ГРАНИЦАТА, СЕ ВРЪЩА НЕПОКЪТНАТА.</b> При {@code inside ==
   * total} се връща самото {@code duration}, без нито едно деление — тоест за всеки заварен случай
   * числото е бит по бит днешното. Делението е и излишно (x × n ÷ n), и вредно: то би внесло
   * закръгляне в месец, който няма какво да дели.
   *
   * <p><b>⚠️ ЗАЩО ПО РАБОТНИ, А НЕ ПО КАЛЕНДАРНИ ДНИ.</b> Отпускът се мери в РАБОТНИ дни — КТ чл.
   * 155 ал. 4 („не по-малко от 20 работни дни“). По календарни дни отпуск 28.12–03.01 щеше да даде
   * на януари дял за 1, 2 и 3 януари — празник и уикенд, тоест дни, които не са отпуск.
   *
   * <p><b>⚠️ ЗАЩО СПЛИТЪТ Е ЗАДЪЛЖИТЕЛЕН, А НЕ УДОБСТВО.</b> НСОРЗ чл. 18 ал. 2 коригира
   * среднодневното с коефициент от „броя на работните дни на месеца, приет за база, и броя на
   * работните дни на СЪОТВЕТНИЯ МЕСЕЦ, ПРЕЗ КОЙТО СЕ ПОЛЗВА ОТПУСКЪТ“ — два месеца значи два
   * коефициента. И КТ чл. 177 ал. 1 взема базата от „последния календарен месец, ПРЕДХОЖДАЩ
   * ползването“ — отпуск през граница има ДВЕ бази. Свити в едно число, и двете са неизразими.
   *
   * @param holidays официалните празници, засягащи заявката. {@code null} се чете като „няма“
   * @return частта в периода, или {@code null}, когато заявката с положителна продължителност няма
   *     нито един работен ден — делителят е нула и частта е неизразима
   */
  static BigDecimal leavePartInPeriod(
      LocalDate leaveFrom,
      LocalDate leaveTo,
      BigDecimal duration,
      LocalDate from,
      LocalDate to,
      Set<LocalDate> holidays) {
    if (duration == null || duration.signum() == 0) {
      return BigDecimal.ZERO;
    }
    if (leaveFrom == null || leaveTo == null) {
      // Заявка без дати не може да се реже — но и не пресича нищо. Носи се цяла, както досега.
      return duration;
    }
    int total = BgSickLeaveResolver.workingDaysBetween(leaveFrom, leaveTo, holidays);
    if (total <= 0) {
      return null;
    }
    LocalDate sliceFrom = leaveFrom.isBefore(from) ? from : leaveFrom;
    LocalDate sliceTo = leaveTo.isAfter(to) ? to : leaveTo;
    int inside = BgSickLeaveResolver.workingDaysBetween(sliceFrom, sliceTo, holidays);
    if (inside == total) {
      return duration;
    }
    if (inside <= 0) {
      return BigDecimal.ZERO;
    }
    return duration
        .multiply(BigDecimal.valueOf(inside))
        .divide(BigDecimal.valueOf(total), LEAVE_DAYS_SCALE, BgEarningsResolver.ROUNDING);
  }

  /**
   * ЧАСОВЕТЕ КАТО ДНИ — {@code часове ÷ договорени часове на ден}.
   *
   * <p><b>⚠️ ТОВА НЕ Е ЕКЗОТИКА, А ТРИ РЕАЛНИ БЪЛГАРСКИ ПРАВА.</b> {@code LeaveReason.unitSelect =
   * 2} съществува в модела, защото КТ чл. 166 ал. 1–2 дава отпуск за кърмене по 1, 2 или 3 ЧАСА
   * дневно (платен от работодателя по ал. 4), а КТ чл. 157 ал. 1 т. 6 и ал. 2 — по 1 час дневно при
   * предизвестие и за прегледи при бременност, платени „в размера по чл. 177“ (ал. 3 т. 2).
   * Отказан, часовият отпуск изхвърляше и трите кофи на месеца.
   *
   * <p>Самото превръщане е операцията, която НСОРЗ чл. 18 ал. 3 предписва при СИРВ: „отработените
   * часове през месеца … се разделят на дневната продължителност на работното време“.
   *
   * <p><b>⚠️ ДЕЛИТЕЛЯТ Е ДОГОВОРЕНИЯТ ДЕН НА ЛИЦЕТО, НЕ ЗАКОНОУСТАНОВЕНИЯТ ЗА ДЛЪЖНОСТТА.</b>
   * {@code bgHoursPerDay}, не {@code bgLegalHoursPerDay}. При половин щат по КТ чл. 138 (4 часа)
   * четири часа отпуск са ЦЯЛ ден отсъствие; през законоустановените 8 те стават 0,5 — половината
   * отсъствие изчезва, а двете числа съвпадат при пълен щат, тоест грешката не се вижда никъде,
   * освен при точно онези лица, заради които полето съществува.
   *
   * @return дните, или {@code null} при липсващ или неположителен делител
   */
  static BigDecimal leaveHoursAsDays(BigDecimal hours, BigDecimal hoursPerDay) {
    if (hours == null) {
      return null;
    }
    if (hours.signum() == 0) {
      // Нула часа са нула дни при всеки делител — включително при липсващ. Отказ тук би поискал
      // попълнено поле, от което отговорът не зависи.
      return BigDecimal.ZERO;
    }
    if (hoursPerDay == null || hoursPerDay.signum() <= 0) {
      return null;
    }
    return hours.divide(hoursPerDay, LEAVE_DAYS_SCALE, BgEarningsResolver.ROUNDING);
  }

  /** Сборът на една кофа, приведен към точността на входа. */
  static BigDecimal scaled(BigDecimal days) {
    return days == null ? null : days.setScale(LEAVE_DAYS_SCALE, BgEarningsResolver.ROUNDING);
  }

  /**
   * Празниците, покриващи И периода, И всяка заявка, която излиза извън него.
   *
   * <p>⚠️ Интервалът на ЗАЯВКИТЕ, а не на периода, и разликата е точно straddle случаят: отпуск
   * 28.12–08.01 иска и януарските празници, за да се сметне {@code total}. Прочетени само за
   * периода, 1 януари щеше да липсва от списъка и заявката щеше да носи един работен ден повече,
   * тоест декември щеше да получи по-малък дял.
   */
  protected Set<LocalDate> holidaysSpanning(List<Object[]> rows, LocalDate from, LocalDate to) {
    LocalDate first = from;
    LocalDate last = to;
    for (Object[] row : rows) {
      if (row[0] instanceof LocalDateTime) {
        LocalDate d = ((LocalDateTime) row[0]).toLocalDate();
        if (d.isBefore(first)) {
          first = d;
        }
      }
      if (row[1] instanceof LocalDateTime) {
        LocalDate d = ((LocalDateTime) row[1]).toLocalDate();
        if (d.isAfter(last)) {
          last = d;
        }
      }
    }
    return officialHolidays(first, last);
  }

  /**
   * Редовете на ВАЛИДИРАНИТЕ заявки за отпуск, засягащи периода.
   *
   * <p>⚠️ {@code fromDateT}/{@code toDateT} са ДАТА С ЧАС в базовия модул; тук се вземат само
   * датите, защото отпускът се брои в дни. ⚠️ Филтърът е ПРИПОКРИВАНЕ, за да СТИГНЕ до сглобителя и
   * заявката, пресичаща границата — тя трябва да предизвика ОТКАЗ, а не да бъде премълчана от
   * заявката към базата.
   */
  /** Exposed (not just inlined) so a test can assert the historical-leave filter is present. */
  public static final String LEAVE_ROWS_JPQL =
      "SELECT l.fromDateT, l.toDateT, l.duration, r.unitSelect, t.category, t.isPaid, t.id,"
          + " t.bgPaidUnderArt177, t.d1Code, t.countsAsInsuranceService,"
          + " t.healthContributionRule"
          + " FROM LeaveRequest l"
          + " JOIN l.leaveReason r"
          + " LEFT JOIN r.bgAbsenceType t"
          + " WHERE l.employee = :employee AND l.statusSelect = :validated"
          + " AND l.fromDateT <= :toEnd AND l.toDateT >= :fromStart"
          + BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL
          + " ORDER BY l.fromDateT, l.id";

  protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
    return JPA.em()
        .createQuery(LEAVE_ROWS_JPQL, Object[].class)
        .setParameter("employee", employee)
        .setParameter("validated", LEAVE_VALIDATED)
        .setParameter("fromStart", from.atStartOfDay())
        .setParameter("toEnd", to.atTime(23, 59, 59))
        .getResultList();
  }

  // ═══ ОТРАБОТЕНОТО КАТО ТРИ СЪСТОЯНИЯ ═════════════════════════════════════

  /**
   * Отработеното време: часове, дни, и „не знам“ за всяко поотделно.
   *
   * <p><b>⚠️ НУЛА ОТЧЕТЕНИ РЕДА И НУЛА ОТРАБОТЕНИ ЧАСА НЕ СА ЕДНО И СЪЩО.</b> Празната заявка дава
   * сбор нула — същото число, което дава и цял месец отсъствие. Ако това число тръгне като числител
   * на пропорцията, коефициентът става 0, прагът пада до нула и осигурителната база се смъква до
   * брутото. Тоест ЛИПСАТА на отчет би понижила базата тихо — и то за всеки, който просто не води
   * отчети за време. Затова липсата на редове е {@code null}, а не нула.
   *
   * <p><b>⚠️ И ВТОРА РАЗЛИКА: отчетени дни с нула часа.</b> Ред съществува, но {@code
   * hoursDuration} е непопълнено, тоест getter-ът дава {@code ZERO}. Сборът е нула при налични
   * редове — това е ДАННА ГРЕШКА, не отработена нула, и часовият числител не е използваем. Часовете
   * стават „не знам“ и остават ДНИТЕ, тоест сглобителят пада от НЕВД чл. 1 ал. 4 към ал. 3, вместо
   * да смята с нула.
   */
  public static final class WorkedTime {

    /** Отработени часове, или {@code null} за „не знам“. Единица: ЧАСОВЕ. */
    public final BigDecimal hours;

    /** Отработени дни, или {@code null} за „не знам“. Единица: РАБОТНИ ДНИ. */
    public final Integer days;

    WorkedTime(BigDecimal hours, Integer days) {
      this.hours = hours;
      this.days = days;
    }

    /** Няма отчет — нито часове, нито дни. НЕ е нула. */
    public static WorkedTime unknown() {
      return new WorkedTime(null, null);
    }

    /**
     * Часове и дни, изведени от РАЗБИВКАТА ПО ДАТИ.
     *
     * <p>Празната разбивка е {@link #unknown()}, не нула — виж довода в описанието на класа.
     */
    public static WorkedTime of(Map<LocalDate, BigDecimal> byDate) {
      if (byDate == null || byDate.isEmpty()) {
        return unknown();
      }
      BigDecimal hours = BigDecimal.ZERO;
      for (BigDecimal value : byDate.values()) {
        if (value != null) {
          hours = hours.add(value);
        }
      }
      return new WorkedTime(hours.signum() > 0 ? hours : null, byDate.size());
    }

    /** Часове и дни, изведени от редовете. */
    public static WorkedTime of(List<Object[]> rows) {
      if (rows == null || rows.isEmpty()) {
        return unknown();
      }
      TreeSet<LocalDate> dates = new TreeSet<>();
      BigDecimal hours = BigDecimal.ZERO;
      for (Object[] row : rows) {
        if (row[0] instanceof LocalDate) {
          dates.add((LocalDate) row[0]);
        }
        if (row[1] instanceof BigDecimal) {
          hours = hours.add((BigDecimal) row[1]);
        }
      }
      // ⚠️ signum(), не сравнение с null: сборът е конструиран от ZERO и никога не е null.
      return new WorkedTime(hours.signum() > 0 ? hours : null, dates.size());
    }
  }

  // ═══ ИЗВЪНРЕДНИЯТ ТРУД КАТО ЧЕТИРИ КОФИ И ЕДИН ОТКАЗ ═════════════════════

  /**
   * ЧЕТИРИТЕ ВИДА ИЗВЪНРЕДЕН ТРУД, или ОТКАЗ за всичките.
   *
   * <p>Четирите излизат ЗАЕДНО по същия довод като {@link LeaveDays}: един некласифициран вид
   * обезсилва и четирите, защото не се знае в коя от тях е трябвало да влезе, а всяка носи различно
   * увеличение по чл. 262 ал. 1 КТ.
   */
  public static final class OvertimeHours {

    public final BigDecimal workday;
    public final BigDecimal restDay;
    public final BigDecimal holiday;
    public final BigDecimal sirv;
    public final String refusal;

    OvertimeHours(
        BigDecimal workday,
        BigDecimal restDay,
        BigDecimal holiday,
        BigDecimal sirv,
        String refusal) {
      this.workday = workday;
      this.restDay = restDay;
      this.holiday = holiday;
      this.sirv = sirv;
      this.refusal = refusal;
    }

    /** Няма нито един валидиран ред — четирите са „не знам“, не нула. */
    public static OvertimeHours unknown() {
      return new OvertimeHours(null, null, null, null, null);
    }

    public static OvertimeHours refused(String reason) {
      return new OvertimeHours(null, null, null, null, reason);
    }

    public boolean isRefused() {
      return refusal != null;
    }
  }

  /**
   * НОЩНИЯТ ТРУД И ВРЕМЕТО НА РАЗПОЛОЖЕНИЕ — двете, които идват от ДЕКЛАРАЦИЯ.
   *
   * <p>⚠️ САМИЯТ РЕД Е ДЕКЛАРАЦИЯТА. Няма ред за периода → и двете са {@code null}. Има ред → и
   * двете са декларирани, тоест нулата е ОТГОВОР, а не празнота. Виж {@code BgDeclaredWorkHours}.
   */
  public static final class DeclaredHours {

    public final BigDecimal nightHours;
    public final BigDecimal onCallHours;

    DeclaredHours(BigDecimal nightHours, BigDecimal onCallHours) {
      this.nightHours = nightHours;
      this.onCallHours = onCallHours;
    }

    /** Няма декларация за периода. */
    public static DeclaredHours unknown() {
      return new DeclaredHours(null, null);
    }
  }

  // ═══ ОТПУСКИТЕ КАТО ТРИ КОФИ И ЕДИН ОТКАЗ ════════════════════════════════

  /**
   * ОТПУСКИТЕ НА ПЕРИОДА, разпределени по трите кофи на контракта — или ОТКАЗ за всичките.
   *
   * <p><b>⚠️ ЗАЩО ТРИТЕ ИЗЛИЗАТ ЗАЕДНО.</b> Един несъпоставен вид отпуск обезсилва и трите кофи, а
   * не само своята: не се знае в коя е ТРЯБВАЛО да влезе. Върнати поотделно, две от тях щяха да
   * изглеждат пълни и верни, а третата — празна, тоест грешката щеше да се представи като данни.
   */
  public static final class LeaveDays {

    /**
     * Платен отпуск в РАБОТНИ ДНИ, или {@code null}. Дробен е ЗАКОНЕН — виж {@link
     * BgPayrollInputBuilder#LEAVE_DAYS_SCALE}.
     */
    public final BigDecimal paid;

    /** Неплатен отпуск в РАБОТНИ ДНИ, или {@code null}. Дробен е законен. */
    public final BigDecimal unpaid;

    /** Други отсъствия в РАБОТНИ ДНИ, или {@code null}. Дробен е законен. */
    public final BigDecimal other;

    /**
     * ПОДМНОЖЕСТВОТО НА {@link #paid}, което се заплаща в размера по КТ чл. 177 — в РАБОТНИ ДНИ,
     * или {@code null}.
     *
     * <p><b>⚠️ ПОДМНОЖЕСТВО, А НЕ ЧЕТВЪРТА КОФА.</b> Тези дни СА вътре в {@link #paid} и се броят
     * там: те са осигурителен стаж и пълнят т. 16.1 на Д1 наравно с останалите платени. Извадени
     * встрани, те щяха да изчезнат от всеки читател на {@code paid}, а той има консуматори, които
     * не знаят нищо за чл. 177.
     *
     * <p><b>⚠️ И НЕ СЕ ИЗВЕЖДА ОТ „платен“.</b> Категория 1 с {@code isPaid} събира ОСЕМ вида,
     * заплащани по различни режими: чл. 177 ал. 1 (годишният), чл. 159 ал. 2 („се заплаща съгласно
     * чл. 177“), чл. 161 ал. 1 (по КТД), чл. 169–171 (кодексът мълчи), чл. 166 ал. 4 (кърменето) и
     * двата платени реда по чл. 157 — ал. 3 т. 1 (по КТД) и ал. 3 т. 2 с ал. 2 („в размера по чл.
     * 177“). Признакът е ДАННИ — {@code BgAbsenceType.bgPaidUnderArt177}.
     */
    public final BigDecimal paidUnderArt177;

    /**
     * ПОДМНОЖЕСТВОТО НА {@link #unpaid}, което Декларация обр. 1 ВАДИ от т. 16.1 — в РАБОТНИ ДНИ,
     * или {@code null}.
     *
     * <p><b>⚠️ ПОДМНОЖЕСТВО, А НЕ ПЕТА КОФА</b> — по същия образец като {@link #paidUnderArt177}.
     * Тези дни СА вътре в {@link #unpaid} и се броят там: здравната вноска при неплатен отпуск (ЗЗО
     * чл. 40 ал. 1 т. 1 б. „б“) се дължи за всеки ден неплатено отсъствие, независимо дали се
     * зачита за осигурителен стаж. Извадени встрани, те щяха да изчезнат от нейния читател.
     *
     * <p><b>⚠️ РАЗГРАНИЧИТЕЛЯТ Е {@code BgAbsenceType.d1Code}, А НЕ КАТЕГОРИЯТА ИЛИ {@code
     * countsAsInsuranceService}.</b> И трите се разцепват еднакво ДНЕС, но само d1Code е колоната,
     * от която Д1 наистина строи кофите си ({@code BgD1DayBuckets}) — а инвариантът, който пазим, е
     * точно „каквото Д1 вади от т. 16.1, това и заплатата сваля“. Прочетен от втора колона, той
     * щеше да се държи, докато двете не се разминат, и да замълчи точно тогава.
     */
    public final BigDecimal unpaidInD1;

    /**
     * ПОДМНОЖЕСТВОТО НА {@link #unpaid}, върху което се дължи здравната вноска по <b>ЗЗО чл. 40 ал.
     * 1 т. 1 б. „б“</b> — в РАБОТНИ ДНИ, или {@code null}.
     *
     * <p><b>⚠️ ВТОРО ПОДМНОЖЕСТВО НА СЪЩАТА КОФА, И ТО ПО ДРУГ ПРИЗНАК.</b> {@link #unpaidInD1}
     * отговаря на „дължи ли работодателят заплащане за деня“ (карвът); това поле отговаря на
     * „НЕПЛАТЕН ОТПУСК ли е денят“ — дословната хипотеза на б. „б“. До разцепването на КТ чл. 157
     * двата въпроса имаха един отговор и второ число не беше нужно. Разграничителят е {@code
     * BgAbsenceType.healthContributionRule} през {@code BgUnpaidLeaveHealthResolver#payerFor}.
     */
    public final BigDecimal unpaidUnderZzo;

    /**
     * ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО — в РАБОТНИ ДНИ, или {@code null}. <b>Подмножество на СБОРА на
     * трите кофи, но не на определена една</b> — и точно това го различава от {@link
     * #paidUnderArt177} и {@link #unpaidInD1}, всяко от които живее в ЕДНА кофа.
     *
     * <p>Предикатът е {@code BgD1DayBuckets#outsideInsurance} — <b>КСО чл. 10 ал. 2</b>: периодът
     * не се зачита за осигурителен стаж и няма кофа в т. 16. Днес това са петте вида в {@code
     * BgD1DayBuckets.UNBUCKETED}, но списъкът не е закован тук — чете се речникът.
     *
     * <p><b>⚠️ ЗАЩО ПОРЕДОМ С КОФИТЕ, А НЕ ВЪТРЕ В НЯКОЯ.</b> Петте се разпръсват по трите кофи —
     * самоотлъчката и неприсъствието са „други“ (категория 5), неплатеният над 30 работни дни е
     * „неплатен“ (категория 7), а непредставеният болничен е категория 2, няма своя кофа и се
     * ПОВИШАВА в „други“. Кофите казват КАК се заплаща отсъствието; това число казва ДАЛИ изобщо се
     * дължи заплащане, а второто не е подмножество на първото.
     *
     * <p><b>⚠️ И ЗАЩО ВСЕКИ НЕГОВ ДЕН Е И В НЯКОЯ КОФА.</b> Инвариантът на {@code
     * BgPaySlipService#freezeLeaveDays} е „Σкофи + това число НЕ надхвърля преброените дни
     * отсъствие“. Ден, който тежи тук и в нито една кофа, го чупи — непредставеният болничен без
     * кофа би накарал пазача да хвърли {@code IllegalStateException} на ВСЕКИ фиш с такава заявка,
     * тоест сценарият „болничният още не е дошъл“ би бил непостижим по нормалния път. Повишаването
     * в „други“ не е удобство — то е условието инвариантът да е верен по построение.
     *
     * <p><b>⚠️ ПОСЛЕДИЦАТА В ПАРИТЕ Е ОБЯВЕНА, А НЕ ПРЕМЪЛЧАНА.</b> {@code
     * BgArt177LeaveService#absenceDaysOf} сумира {@code paid + unpaid + other} за базовия месец по
     * КТ чл. 177 ал. 1 — тоест непредставеният болничен СЕ ВАДИ от отработените дни там. Това е
     * верният прочит на НСОРЗ чл. 18 ал. 1 („среднодневното … се дели на ОТРАБОТЕНИТЕ дни“): ден
     * без труд и без заплащане не е отработен ден.
     *
     * <p>⚠️ Посоката на числото е ДВЕ, не една: при месец, който още квалифицира, среднодневното се
     * ВДИГА (57,14 → 66,17), но паднал под прага от 10 отработени дни, месецът се ПРЕСКАЧА и
     * по-ранният може да даде по-ниско (57,14 → 42,86). Виж {@code
     * BgArt177LeaveService#absenceDaysOf}.
     */
    public final BigDecimal outsideInsurance;

    /**
     * ДНИТЕ ПО КОФА НА Т. 16 — {@code d1Code → работни дни}, сурови и НЕЗАКРЪГЛЕНИ.
     *
     * <p><b>⚠️ ТОВА Е ЕДИНСТВЕНИЯТ ИЗТОЧНИК НА ДНИТЕ ЗА ДЕКЛАРАЦИЯ ОБР. 1.</b> Д1 не ги смята
     * НАНОВО с „огледална“ заявка над същите заявки за отпуск. Огледалото се държи само докато
     * никой не пипне едната половина — разминат ли се отказите (часове, граница на периода, дробен
     * ден), фишът и декларацията му казват различни неща за едни и същи дни.
     *
     * <p>⚠️ СТОЙНОСТТА Е ДРОБНА НАРОЧНО. Т. 16 се попълва в ЦЕЛИ дни, но закръглянето е ФОРМАТНА
     * грижа и живее в Д1: закръглено тук, две заявки по половин ден в един месец щяха да дадат 1 +
     * 1 вместо 1.
     *
     * <p>⚠️ Дните ИЗВЪН осигуряването НЕ са тук — те нямат кофа по определение ({@code
     * BgD1DayBuckets#outsideInsurance} иска ПРАЗЕН код) и пътуват в {@link #outsideInsurance}.
     * Броени и на двете места, те щяха да излязат от т. 16.1 ДВА пъти.
     */
    public final Map<String, BigDecimal> byD1Code;

    /** Причината, когато числата са {@code null} заради отказ; инак {@code null}. */
    public final String refusal;

    LeaveDays(
        BigDecimal paid,
        BigDecimal unpaid,
        BigDecimal other,
        BigDecimal paidUnderArt177,
        BigDecimal unpaidInD1,
        BigDecimal unpaidUnderZzo,
        BigDecimal outsideInsurance,
        Map<String, BigDecimal> byD1Code,
        String refusal) {
      this.paid = paid;
      this.unpaid = unpaid;
      this.other = other;
      this.paidUnderArt177 = paidUnderArt177;
      this.unpaidInD1 = unpaidInD1;
      this.unpaidUnderZzo = unpaidUnderZzo;
      this.outsideInsurance = outsideInsurance;
      this.byD1Code =
          byD1Code == null
              ? Collections.emptyMap()
              : Collections.unmodifiableMap(new LinkedHashMap<>(byD1Code));
      this.refusal = refusal;
    }

    /** Няма нито една валидирана заявка — шестте са „не знам“, не нула. */
    public static LeaveDays unknown() {
      return new LeaveDays(null, null, null, null, null, null, null, null, null);
    }

    /** Отказ с назована причина: източникът съществува, но не може да бъде прочетен. */
    public static LeaveDays refused(String reason) {
      return new LeaveDays(null, null, null, null, null, null, null, null, reason);
    }

    public boolean isRefused() {
      return refusal != null;
    }
  }

  /**
   * Кофата, в която попада един вид отсъствие — или {@code null}, ако въобще не е отпуск.
   *
   * <p><b>⚠️ ТОВА Е ПРОЧИТ НА НАШИЯ РЕЧНИК, НЕ ОПЕРАЦИОНЕН ИЗБОР.</b> Кое е „платен отпуск“ в
   * инсталацията на клиента се решава от съпоставянето {@code LeaveReason.bgAbsenceType} — то е
   * конфигурируемо и чака човек. КОЯ КОФА обаче отговаря на един ред от {@code BgAbsenceType} се
   * чете от самия ред: категорията и признакът „платено“ са НАШИ полета, с наш смисъл, seed-нати от
   * този модул. Затова разпределението живее в кода, а не в трета конфигурация.
   *
   * <p><b>⚠️ БОЛНИЧНИТЕ, ЗЛОПОЛУКИТЕ И МАЙЧИНСТВОТО НЕ ВЛИЗАТ В НИТО ЕДНА КОФА.</b> Те пътуват като
   * {@link BgPayrollInput#sickLeaveEpisodes} и се смятат по чл. 40 ал. 5 КСО. Преброени и тук, те
   * биха се отчели ДВА ПЪТИ — веднъж като епизод и веднъж като „други отсъствия“ — и то по начин,
   * който изглежда точно като по-дълго отсъствие.
   */
  static String leaveBucketOf(Integer category, boolean paid) {
    if (category == null) {
      return null;
    }
    switch (category) {
      case ABSENCE_CATEGORY_LEAVE:
        return paid ? BUCKET_PAID : BUCKET_UNPAID;
      case ABSENCE_CATEGORY_UNPAID:
        return BUCKET_UNPAID;
      case ABSENCE_CATEGORY_DISCIPLINARY:
      case ABSENCE_CATEGORY_OTHER:
        return BUCKET_OTHER;
      default:
        // Болнични (2), злополука (3), майчинство (4) — пътуват като епизоди, не като дни.
        return null;
    }
  }

  /**
   * ДВАТА ЗАПИСА ЗА ЕДИН И СЪЩ ДЕН — отказ с ИМЕНАТА им, или {@code null}.
   *
   * <p><b>Какво пита.</b> Дали заявка за отпуск, повишена в „други“ заради липсваща кофа
   * (единственият такъв вид днес е „непредставен болничен“), се застъпва по КАЛЕНДАРНИ дати с
   * валидиран болничен лист за същия период. Двата записа твърдят различни неща за едно и също
   * отсъствие: първият казва „болничният НЕ е представен“, вторият — „ето го“.
   *
   * <p><b>⚠️ ЗАЩО КАЛЕНДАРНО ЗАСТЪПВАНЕ, А НЕ „същите РАБОТНИ дни“.</b> Проверката не е за брой, а
   * за ПРОТИВОРЕЧИЕ: два документа за един и същ отрязък от календара. Стеснена до работните дни,
   * тя щеше да мълчи за заявка събота–неделя вътре в болничния — тоест за запис, който пак трябва
   * да бъде махнат, преди някой да повярва на фиша. И щеше да иска производствения календар в чиста
   * функция.
   *
   * <p><b>⚠️ ЧИСТА ФУНКЦИЯ, БЕЗ БАЗА И БЕЗ Guice</b> — по образеца на {@link #leaveBucketOf}: така
   * се проверява със свидетел, а не с инсталация.
   *
   * <p>⚠️ Изброяват се ВСИЧКИ засечени двойки, не първата. Операторът има нужда от пълния списък,
   * инак поправя една заявка и получава същото съобщение за следващата.
   *
   * @param unbucketedOutside границите на повишените заявки — {@code {от, до}} на всяка
   * @param episodes валидираните болнични епизоди за същия период
   */
  static String sickNoteArrivalClash(
      List<LocalDate[]> unbucketedOutside, List<BgPayrollInput.SickLeaveEpisode> episodes) {
    if (unbucketedOutside == null
        || unbucketedOutside.isEmpty()
        || episodes == null
        || episodes.isEmpty()) {
      return null;
    }
    List<String> pairs = new ArrayList<>();
    for (LocalDate[] span : unbucketedOutside) {
      if (span == null || span[0] == null || span[1] == null) {
        continue;
      }
      for (BgPayrollInput.SickLeaveEpisode episode : episodes) {
        if (episode == null || episode.fromDate == null || episode.toDate == null) {
          continue;
        }
        if (span[1].isBefore(episode.fromDate) || episode.toDate.isBefore(span[0])) {
          continue;
        }
        pairs.add(
            "заявка за отпуск "
                + day(span[0])
                + " – "
                + day(span[1])
                + " ↔ болничен лист "
                + (episode.documentNumber == null || episode.documentNumber.trim().isEmpty()
                    ? "без номер"
                    : "№ " + episode.documentNumber.trim())
                + " ("
                + day(episode.fromDate)
                + " – "
                + day(episode.toDate)
                + ")");
      }
    }
    if (pairs.isEmpty()) {
      return null;
    }
    return refuse("unsubmittedSickNoteOverlapsSickLeave")
        + ". Засечени двойки ("
        + pairs.size()
        + "): "
        + String.join("; ", pairs);
  }

  /** Датата, както операторът я вижда на формата. Отделен метод, за да е ЕДИН форматът. */
  private static String day(LocalDate date) {
    return String.format(
        "%02d.%02d.%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
  }

  // ═══ ВИДЪТ ОСИГУРЕН КАТО ДВЕ СЪСТОЯНИЯ ════════════════════════════════════

  /**
   * Видът осигурен: КОД или ОТКАЗ. Трето състояние няма.
   *
   * <p><b>Защо носач, а не гол {@code String}.</b> Гол {@code null} на мястото на кода не може да
   * каже ЗАЩО го няма, а причините са две с различна поправка: „източник в модела няма“ (мястото ѝ
   * е {@link #DECLARED_ABSENT}) и „източникът е празен“ (поправя се от оператора). Носачът прави
   * втората ЧЕТИМА, вместо да я слее с първата.
   *
   * <p><b>⚠️ Защо отказът НЕ хвърля.</b> Изключението би било единственото поведение и за
   * диагностичния екран „Преглед на осигуровки“, който сглобява вход за произволен договор — тоест
   * заварен договор без вид осигурен би счупил екрана, вместо да покаже, че полето трябва да се
   * попълни. Отказът е СЪСТОЯНИЕ, което пътува, по образеца на {@code BgPayrollWorkTime.Decision}:
   * {@code BgPayrollInput.insuredTypeCode} остава {@code null} и веригата надолу няма какво да
   * сметне, а причината е назована и достъпна.
   */
  public static final class InsuredType {

    /** Кодът по номенклатурата на НАП, или {@code null} при отказ. */
    public final String code;

    /** Причината за отказа, или {@code null} когато има код. */
    public final String refusal;

    private InsuredType(String code, String refusal) {
      this.code = code;
      this.refusal = refusal;
    }

    static InsuredType of(String code) {
      return new InsuredType(code, null);
    }

    static InsuredType refused(String reason) {
      return new InsuredType(null, reason);
    }

    public boolean isRefused() {
      return refusal != null;
    }
  }

  /**
   * ОТКАЗ с обявена причина — текстът идва от {@link #REFUSALS}, не от мястото на извикването.
   *
   * <p>Същият механизъм като {@link #absent} и като {@code BgPayrollWorkTime.refuse}: един отказ
   * има един текст, той се сверява в двете посоки от тест, вместо да се преписва.
   */
  static String refuse(String code) {
    String reason = REFUSALS.get(code);
    if (reason == null) {
      throw new IllegalStateException(
          "отказът „"
              + code
              + "“ не е в BgPayrollInputBuilder.REFUSALS. Отказ без ПРИЧИНА е точно толкова тих,"
              + " колкото и мълчаливото подразбиране, което той съществува да замени.");
    }
    return reason;
  }

  // ═══ ОБЯВЯВАНЕТО НА ЛИПСА ═════════════════════════════════════════════════

  /**
   * ОБЯВЕНА ЛИПСА за поле, което има setter: връща {@code null} и казва, че това е решение.
   *
   * <p>Генеричният връщан тип е нарочен — така обявяването се чете на мястото на стойността ({@code
   * .nightHours(absent("nightHours"))}) и не иска нито каст, нито отделен ред.
   */
  static <T> T absent(String field) {
    requireDeclared(field);
    return null;
  }

  /**
   * ОБЯВЕНА ЛИПСА за поле БЕЗ setter, който приема липсата — списъците на контракта.
   *
   * <p>Отделен вход, а не коментар: носачът на твърдението е поле, а не изречение. Изречението не
   * може да падне.
   */
  static void declareAbsent(String... fields) {
    for (String field : fields) {
      requireDeclared(field);
    }
  }

  /**
   * ⚠️ Едната посока на сверката, и то по време на изпълнение: обявяване на липса за поле, което не
   * е в {@link #DECLARED_ABSENT}, ПАДА веднага, вместо да мине тихо.
   *
   * <p>Другата посока — запис в списъка без свой ред в кода, и поле на контракта, подминато без
   * нито едно от двете — се сверява от {@code BgPayrollAssemblyTest} върху сорса.
   */
  private static void requireDeclared(String field) {
    if (!DECLARED_ABSENT.containsKey(field)) {
      throw new IllegalStateException(
          "полето „"
              + field
              + "“ се обявява за липсващо, но не е в BgPayrollInputBuilder.DECLARED_ABSENT."
              + " Обявена липса без ПРИЧИНА е криене на грешка: впишете полето с причина, или го"
              + " попълнете от източник.");
    }
  }
}
