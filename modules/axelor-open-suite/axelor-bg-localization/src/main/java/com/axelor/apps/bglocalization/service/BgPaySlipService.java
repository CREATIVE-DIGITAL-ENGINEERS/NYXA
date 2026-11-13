package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.AdjustHistory;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.AdjustHistoryRepository;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.AdjustHistoryService;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgGarnishmentScale;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipCapUsage;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgGarnishmentScaleRepository;
import com.axelor.apps.bglocalization.db.repo.BgInsuredTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.accounting.BgPaySlipMoveService;
import com.axelor.apps.bglocalization.service.d1.BgCompensationCalendar;
import com.axelor.apps.bglocalization.service.d1.BgCompensationParts;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import com.axelor.apps.bglocalization.service.d1.BgD1PeriodLockService;
import com.axelor.apps.bglocalization.service.payroll.BgArt177LeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgArt49SettlementService;
import com.axelor.apps.bglocalization.service.payroll.BgCompensationLineService;
import com.axelor.apps.bglocalization.service.payroll.BgContributionLineService;
import com.axelor.apps.bglocalization.service.payroll.BgEarningsService;
import com.axelor.apps.bglocalization.service.payroll.BgFrozenPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgGeneratedLines;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipLineOutcome;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipVerdict;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipVerdicts;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRegime;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollWorkTime;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveOutcome;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgUnpaidLeaveHealthService;
import com.axelor.apps.bglocalization.service.selfinsured.BgSelfInsuredRules;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Слоят с база за фиша: пълни го от източниците, сверява и замразява. Всяко ПРАВИЛО живее в {@link
 * BgPaySlipResolver}.
 *
 * <p><b>Границата на стъпката.</b> Автоматично се попълва само това, за което вече ИМА източник — и
 * границата се е местила два пъти, затова е записана по дати. {@link #prepare} днес пуска ПЕТ
 * генератора: работните дни през {@link BgWorkingDayService}, болничния по чл. 40 ал. 5 КСО,
 * основното възнаграждение и класа прослужено време, обезщетенията от потвърдени {@link
 * BgCompensation} (9xx), осигуровките през {@link BgContributionResolver} (5xx) и данъка по чл. 42
 * ЗДДФЛ (7xx).
 *
 * <p>⚠️ РЪЧНОТО ВЪВЕЖДАНЕ ОСТАВА ПРАВИЛО, НЕ ИЗКЛЮЧЕНИЕ. Нощният труд, извънредният,
 * разположението, научната степен, удръжките и плащанията НЯМАТ източник в модела и се въвеждат от
 * ТРЗ. Затова инвариантът стои непокътнат: фишът трябва да е верен и само с ръчни редове, а всеки
 * генератор докосва САМО своите редове. Ако някога това престане да е вярно, свързаността е
 * сбъркана.
 *
 * <p><b>⚠️ РЕДЪТ НА ПЕТТЕ ГЕНЕРАТОРА Е ПРАВИЛО, НЕ ПОДРЕДБА,</b> и всяка от четирите му стъпки има
 * свидетел в {@code BgPaySlipServiceTest}, който пита какво следващият генератор е ВИДЯЛ, а не
 * колко пъти е викан: болничен → начисления → обезщетения → осигуровки → данък. Виж javadoc-а на
 * всеки шев за цената на разменянето му.
 *
 * <p><b>Замразяване чрез копиране.</b> Перото носи СОБСТВЕНИ копия на името, знака, номера и трите
 * флага. Речникът се пита веднъж — при създаването на перото.
 */
public class BgPaySlipService {

  /** Кодът на данъчната ставка в BgPayrollParameter. */
  protected static final String PARAM_INCOME_TAX_RATE = "INCOME_TAX_RATE";

  /**
   * Кодът на данъчната ставка ЗА МОРСКО ЛИЦЕ — 1 на сто по чл. 42 ал. 4 ЗДДФЛ.
   *
   * <p>⚠️ ОТДЕЛЕН КОД, А НЕ ВТОРА ВЕРСИЯ НА {@link #PARAM_INCOME_TAX_RATE} по {@code validFrom}:
   * двете ставки важат ЕДНОВРЕМЕННО и се избират по ЛИЦЕТО, не по датата. Записано е и в {@code
   * views/Selects.xml} до самите две опции.
   *
   * <p>⚠️ Ставката е единствената разлика. Чл. 42 ал. 4 казва „месечната данъчна основа по ал. 3 се
   * умножи по данъчна ставка 10 на сто, а за доходите, придобити като морско лице — по данъчна
   * ставка 1 на сто“ — тоест ОСНОВАТА е същата и се смята по същия ред. Затова изборът е избор на
   * КОД НА ПАРАМЕТЪР и нищо повече; втора формула няма.
   *
   * <p>⚠️ ГОДИШНОТО Е ДРУГ МЕХАНИЗЪМ И Е ИЗВЪН ОБХВАТА ТУК. Чл. 25 ал. 3 ЗДДФЛ прави ГОДИШНАТА
   * данъчна основа на морското лице 10 на сто от тази по ал. 1 — намалена ОСНОВА, а не намалена
   * ставка. Фишът прави месечното авансово удържане по чл. 42 и само него.
   *
   * <p>Сверено: ЗДДФЛ чл. 42 ал. 4.
   */
  protected static final String PARAM_INCOME_TAX_RATE_SEAFARER = "INCOME_TAX_RATE_SEAFARER";

  /**
   * Кодът на МРЗ в BgPayrollParameter — основата на цялата скала по чл. 446 ГПК.
   *
   * <p>⚠️ Скалата НЕ носи суми, а КРАТНИ: „между една и две минимални заплати“. Сумата се чете тук
   * за периода, тоест смяна на МРЗ с ПМС мести и трите прага, без нито един ред да се пипа.
   */
  protected static final String PARAM_MIN_WAGE = "MIN_WAGE";

  /**
   * Кодът на МЕСЕЧНОТО данъчно облекчение за намалена работоспособност в BgPayrollParameter.
   *
   * <p>ЗДДФЛ чл. 18 ал. 2, ДОСЛОВНО: „Месечната данъчна основа по чл. 42, ал. 2 за доходи от
   * трудови правоотношения на лица с 50 и с над 50 на сто намалена работоспособност се намалява с
   * 660 лв., включително за месеца на настъпване на неработоспособността и за месеца на изтичане
   * срока на валидност на решението.“
   *
   * <p>⚠️ 660 лв. са 337,45 EUR по фиксирания курс 1,95583 — и числото живее в seed-а, НЕ тук.
   * Заковано в Java, то би трябвало да се пипа с комит при всяка промяна на нормата и не би могло
   * да смята минал период по стойността, валидна ТОГАВА.
   *
   * <p>⚠️ НЕ БЪРКАЙ С ГОДИШНОТО. Чл. 18 ал. 1 намалява СУМАТА ОТ ГОДИШНИТЕ данъчни основи със 7920
   * лв. — друг механизъм, друга основа и друг период. Фишът прави месечното авансово удържане и
   * само него.
   *
   * <p>Сверено срещу официалния ЗДДФЛ.
   */
  protected static final String PARAM_DISABILITY_RELIEF = "DISABILITY_RELIEF_MONTHLY";

  /**
   * МЕСЕЧНИЯТ РАЗМЕР НА АЛТЕРНАТИВНИЯ ДАНЪК ПО ЧЛ. 41А ЗДДФЛ.
   *
   * <p>⚠️ ОТДЕЛЕН КОД, А НЕ ТРЕТА ВЕРСИЯ НА {@link #PARAM_INCOME_TAX_RATE} — по същия довод като
   * при морското лице, но по-силен: това дори не е СТАВКА. Чл. 41а ал. 1 казва „в размер на 30 лв.
   * за всеки месец“, тоест параметърът носи ЛЕВОВЕ (евро), а INCOME_TAX_RATE носи проценти. Един
   * код за двете би дал число, което вторият читател умножава по основа.
   *
   * <p>⚠️ И БЕЗ КРАЙНА ДАТА, за разлика от годишните: чл. 41а не се преурежда от ЗБДОО всяка
   * година, а от изменение на самия ЗДДФЛ.
   */
  protected static final String PARAM_ALT_TAX_ART41A = "ALT_TAX_ART41A";

  /**
   * {@code Year.TYPE_PAYROLL} — заплатна година. Константата е в базовия Axelor, стойността е 2.
   */
  protected static final int YEAR_TYPE_PAYROLL = 2;

  /**
   * Кодът на перото, с което нетната делта влиза в текущия фиш.
   *
   * <p>⚠️ Един и същ низ живее на две места — тук и в {@code bg_pay_slip_line_type.csv}. Затова
   * съвпадението им е ЗАКОВАНО с тест ({@code BgPaySlipCorrectionTest}): разминаване иначе се
   * обажда чак когато делтата трябва да седне в текущия фиш, тоест СЛЕД като периодът вече е
   * местен.
   */
  protected static final String CORRECTION_LINE_CODE = "pl_korekcia_minal_period";

  // ── създаване и попълване ───────────────────────────────────────────────

  /**
   * Замразява входовете от договора и календара. Работи само в чернова.
   *
   * <p>Работните дни идват от производствения календар за периода. Оставаt редактируеми: ТРЗ вижда
   * случаи, които календарът не знае — но тогава потвърждаването не се блокира за непокрита година,
   * защото числото е нечие решение.
   *
   * <p><b>⚠️ И ОБХОДЪТ ОСТАВЯ ДОКЛАД, А НЕ САМО ПЕРА.</b> Шестте генератора връщат по една присъда
   * за всяко перо — сметнато, законна нула, не се дължи, няма източник, ОТКАЗ. Изхвърлени,
   * присъдите правят отказа неразличим отвън от нормалното състояние — перо няма и в двата случая,
   * — тоест „болничният не е начислен, защото стажът не е подаден“ изглежда точно като „нямаше
   * болничен“. Затова присъдите влизат в {@code bgGeneratorNotes}, отказите — и в {@code
   * bgGeneratorGaps}, а броят им — в {@code bgGeneratorRefusalCount}, откъдето {@code
   * BgPaySlipController} предупреждава оператора.
   *
   * <p>⚠️ ДОКЛАДЪТ НЕ МЕСТИ НИТО ЕДНО ЧИСЛО. Кои пера се създават и с каква сума не зависи от него
   * — {@link BgPaySlipVerdicts} само чете вече взетото решение (перо 195 = 133,33 и разбивът 2/1
   * остават същите).
   */
  public void prepare(BgPaySlip slip) {
    prepare(slip, true);
  }

  /**
   * @param writeDocuments дали генераторът на болничния записва редовете по т. 9 върху листовете —
   *     {@code false} само от {@link #preview}
   */
  protected void prepare(BgPaySlip slip, boolean writeDocuments) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return;
    }
    BgWorkingDayService.Count count = periodWorkingDays(slip);
    if (count != null && slip.getBgWorkingDaysInPeriod() == 0) {
      slip.setBgWorkingDaysInPeriod(count.getWorkingDays());
      if (slip.getBgWorkedDays() == 0) {
        slip.setBgWorkedDays(count.getWorkingDays());
      }
    }
    // ⚠️ ПРИСЪДИТЕ ОТ ПРЕДИШНИЯ ОБХОД СЕ ИЗТРИВАТ ТУК, ПРЕДИ ВСЕКИ ГЕНЕРАТОР И ПО ДВАТА ПЪТЯ.
    // Оставени, старият отказ щеше да стои под новите присъди — текст, който днешните данни вече
    // отричат, до число, което вече е вярно. Второ prepare() след поправка на договора е точно
    // случаят, в който това се чете.
    BgPaySlipVerdicts.reset(slip);
    // ⚠️ ЗАПЛАТАТА СЕ ИЗВЕЖДА ТУК, ПОД reset(), А НЕ ГОРЕ ПРИ РАБОТНИТЕ ДНИ — И РЕДЪТ НЕ Е
    // СВОБОДЕН. Копирането може да ОТКАЖЕ (анекс насред месеца), а присъда, записана преди
    // reset(), се изтрива от самия reset и fail-loud става тих по конструкция.
    applyBaseSalary(slip);
    // ⚠️ САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ ИЗЛИЗА ТУК, СЛЕД РАБОТНИТЕ ДНИ И ПРЕДИ ШЕСТТЕ ГЕНЕРАТОРА.
    // Виж prepareSelfInsured: пет от шестте нямат за него нито източник, нито смисъл, а шестият
    // (вноските) е целият фиш. Работните дни обаче се броят и за него — т. 16 позиции 1 и 2 на Д1
    // искат „общият брой РАБОТНИ дни в осигуряване“ и се броят по производствения календар точно
    // както при наетия, — затова изходът е ТУК, а не в началото на метода.
    if (BgSelfInsuredRules.isSelfInsured(slip)) {
      prepareSelfInsured(slip);
      BgPaySlipVerdicts.seal(slip);
      return;
    }
    // ⚠️ БОЛНИЧНИТЕ ДНИ — ПРЕДИ НАЧИСЛЕНИЯТА, И РЕДЪТ НЕ Е СВОБОДЕН. Те НАМАЛЯВАТ отработените
    // дни, а начисленията четат точно отработените дни от фиша. Разменени, основното
    // възнаграждение остава сметнато за цял месец: при лице, болно целия юли, това е 1805,00
    // вместо 0,00 — и фишът изглежда напълно нормален. Заковано е със свидетел в
    // BgSickLeaveServiceTest, а не оставено на подредбата тук.
    if (writeDocuments) {
      generateSickLeave(slip);
    } else {
      previewSickLeave(slip);
    }
    // ⚠️ ДНИТЕ ОТПУСК СЕ ЗАМРАЗЯВАТ ТУК, ПРЕДИ ВСЯКО НАЧИСЛЕНИЕ, И ПО СЪЩИЯ ДОВОД КАТО БОЛНИЧНИТЕ
    // ОТГОРЕ. Оттук нататък Декларация обр. 1 чете ТЯХ, а не смята наново от заявките за отпуск.
    freezeLeaveDays(slip);
    // ⚠️ СЛЕД замразяването, не преди: генераторът чете точно тези три числа от фиша.
    generateEarnings(slip);
    // ⚠️ СЛЕД НАЧИСЛЕНИЯТА, И РЕДЪТ НЕ Е СВОБОДЕН. Перото по чл. 177 се смята като „карв +
    // надбавка“, а карвът е РАЗЛИКАТА между двата изхода на енджина за начисления — със и без
    // дните отпуск. Пуснато преди него, то би чело енджин, който още не е минал със същия вход, и
    // би върнало пари, каквито перата не са свалили. ⚠️ И ПРЕДИ обезщетенията и вноските:
    // възнаграждението за платен отпуск Е осигурителен доход и Е облагаемо, тоест долните стъпки
    // трябва да го видят вече като перо.
    generateArt177Leave(slip);
    // ⚠️ СЛЕД начисленията: обезщетенията влизат в брутото (група 9xx) и част от тях са
    // осигурителен доход, тоест вноските отдолу трябва да ги ВИДЯТ вече като пера.
    generateCompensations(slip);
    // ⚠️ СЛЕД обезщетенията и ПРЕДИ вноските: превишението над тавана на еднократната помощ е
    // облагаемо, а при брак — и осигурително (виж generateSocialExpenseExcess).
    generateSocialExpenseExcess(slip);
    // ⚠️ ПОСЛЕДНО, И РЕДЪТ НЕ Е СВОБОДЕН. Вноските се смятат върху ПЪЛНИЯ осигурителен доход, а той
    // е сбор на ПЕРАТА (BgPaySlipResolver#insuranceIncomeBase). Пуснат по-рано, генераторът
    // осигурява непълен фиш — тихо, защото всички числа изглеждат пресметнати.
    generateContributions(slip);
    // ⚠️ И ДАНЪКЪТ СЛЕД ТЯХ: данъчната основа е облагаемите начисления МИНУС личните вноски, тоест
    // перата от 5xx DEBIT трябва вече да ги има. Разменени, данъкът се смята върху целия облагаем
    // доход и излиза завишен.
    generateIncomeTax(slip);
    // ⚠️ СЛЕД ДАНЪКА, И РЕДЪТ Е ПРАВИЛО, макар двете да не си четат числата. Изравняването по чл.
    // 49 е за МИНАЛАТА година и не зависи от нищо на този фиш — но перото му е в СЪЩАТА група 7xx,
    // а печатът и сверяването четат групата подредена. Пуснато преди данъка, то би сложило
    // годишното изравняване НАД месечния данък на януарския фиш — тоест би показало приключването
    // на 2026 г. преди удържането за януари 2027 г.
    generateAnnualSettlement(slip);
    // ⚠️ ЗАТВАРЯНЕТО НА ОБХОДА, а не украса: датата е онова, което различава „нула откази“ от
    // „генераторите не са пускани върху този фиш“. Броячът е Integer и getter-ът му връща 0 и за
    // NULL, тоест сам той не може да каже кое от двете е.
    BgPaySlipVerdicts.seal(slip);
  }

  /**
   * ИЗВЕЖДА ОСНОВНАТА ЗАПЛАТА ЗА ПЕРИОДА — от договора и допълнителните му споразумения.
   *
   * <p><b>⚠️ ТОВА Е ЕДИНСТВЕНОТО МЯСТО, ОТ КОЕТО ЗАПЛАТАТА ВЛИЗА ВЪВ ФИША.</b> Прочит БЕЗ дата
   * ({@code slip.setBgBaseSalary(contract.getBgBaseSalary())}) не стига: договорът носи едно поле,
   * което всеки анекс презаписва, тоест фиш за септември, направен след октомврийското увеличение,
   * би се сметнал с октомврийската заплата. Правилото е в {@link BgAmendmentSalaryRule}.
   *
   * <p><b>⚠️ КОГА СЕ ПРЕИЗЧИСЛЯВА — И ЗАЩО НЕ „САМО ПРИ НУЛА“.</b> Условие {@code signum() == 0}
   * значи „веднъж записана, заплатата не се пипа повече“. При датиран прочит то е тих грешен път:
   * избираш октомври (1200,00), виждаш, че си сбъркал периода, превключваш на септември — и фишът
   * остава с 1200,00, а септември се дължи по 1000,00. Затова решава ПРИЗНАКЪТ {@code
   * bgAutoFillBaseSalary}, а не стойността.
   *
   * <p><b>⚠️ И ПРАЗНОТО ПОЛЕ ВСЕ ПАК СЕ ПЪЛНИ.</b> Заварените чернови носят NULL в признака, а
   * генерираният getter за Boolean връща FALSE — тоест сами по себе си те никога не биха получили
   * заплата. Затова изходът е при признак изключен <b>И</b> вече попълнено число: така заварена
   * чернова със сума остава непокътната, а заварена чернова с нула получава изведената заплата.
   *
   * <p><b>⚠️ ПРИ ОТКАЗ ЧИСЛОТО СЕ ЗАНУЛЯВА, а не се оставя каквото е.</b> Изведена стойност, която
   * правилото вече отрича, е по-лоша от липсваща: тя изглежда пресметната. Занулена, тя спира и
   * {@link #confirm} — виж {@link #amendmentSalaryRefusal}.
   */
  protected void applyBaseSalary(BgPaySlip slip) {
    if (slip.getEmploymentContract() == null) {
      return;
    }
    boolean autoFill = Boolean.TRUE.equals(slip.getBgAutoFillBaseSalary());
    if (!autoFill && slip.getBgBaseSalary().signum() != 0) {
      return;
    }
    BgSalaryInEffect inEffect = salaryInEffect(slip);
    BgPaySlipVerdicts verdicts = new BgPaySlipVerdicts();
    if (inEffect.salaryUnknown()) {
      slip.setBgBaseSalary(BigDecimal.ZERO);
      verdicts.add(
          BgPaySlipVerdict.of(
              BgPaySlipVerdicts.STEP_BASE_SALARY,
              BgPaySlipVerdicts.CODE_BASE_SALARY,
              BgPaySlipVerdict.MARK_REFUSED,
              true,
              null,
              inEffect.detail));
    } else {
      slip.setBgBaseSalary(inEffect.salary);
      if (inEffect.detail != null) {
        verdicts.add(
            BgPaySlipVerdict.of(
                BgPaySlipVerdicts.STEP_BASE_SALARY,
                BgPaySlipVerdicts.CODE_BASE_SALARY,
                BgPaySlipVerdict.MARK_COMPUTED,
                false,
                inEffect.salary,
                inEffect.detail));
      }
    }
    slip.setBgAutoFillBaseSalary(true);
    verdicts.appendInto(slip);
  }

  /**
   * Прочитът на заплатата в сила — <b>шев</b>, за да може тест да го подмени без база.
   *
   * <p>Анексите идват от КОЛЕКЦИЯТА на договора, не от заявка: така правилото няма нужда нито от
   * репозиторий, нито от {@code Beans.get}, а заварените подменени сглобители в тестовете виждат
   * празен списък и връщат базата — тоест остават зелени без нито една промяна.
   */
  protected BgSalaryInEffect salaryInEffect(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    Period period = slip.getPeriod();
    return BgAmendmentSalaryRule.inEffect(
        contract.getBgContractAmendmentList(),
        contract.getBgBaseSalary(),
        period == null ? null : period.getFromDate(),
        period == null ? null : period.getToDate());
  }

  /**
   * ОТКАЗЪТ ПРИ ПОТВЪРЖДАВАНЕ — същото правило, попитано втори път.
   *
   * <p><b>⚠️ ПОПИТАНО НАНОВО, А НЕ ПРОЧЕТЕНО ОТ ЗАПИСАНА ПРИСЪДА.</b> Присъдата е на възрастта на
   * последното {@code prepare()}; договорът и анексите може да са мръднали след него. Заключване
   * върху остарял отговор е точно случаят, в който потвърждаването изглежда проверено.
   *
   * <p><b>⚠️ РЪЧНАТА СУМА НЕ СЕ ОТКАЗВА.</b> При изключен признак числото е нечие решение — същата
   * вратичка, с която операторът минава и покрай всеки друг отказ в този списък. Отказът важи само
   * докато фишът твърди, че заплатата е изведена.
   */
  protected String amendmentSalaryRefusal(BgPaySlip slip) {
    if (slip.getEmploymentContract() == null
        || !Boolean.TRUE.equals(slip.getBgAutoFillBaseSalary())) {
      return null;
    }
    BgSalaryInEffect inEffect = salaryInEffect(slip);
    return inEffect.salaryUnknown() ? inEffect.detail : null;
  }

  /**
   * ЗАМРАЗЯВА ДНИТЕ ОТПУСК ПО КОФА НА Т. 16 — <b>единственият източник за Декларация обр. 1</b>.
   *
   * <p><b>⚠️ ЗАЩО ИЗОБЩО.</b> Болничните дни се четат от фиша отдавна и доводът е записан в {@code
   * BgD1DaySource}: прочетени наново, те биха били ВТОРА СМЕТКА на едно число. Същото важи за
   * отпуските: „огледална“ заявка над същите заявки се държи само докато никой не пипне едната
   * половина. Разминат ли се правилата (часова заявка, заявка през границата на периода, дробен
   * ден), заявка 28.12–08.01 сваля 4 дни от декемврийската заплата и НУЛА от декемврийската т. 16.5
   * — декларация, която обявява дни с осигурителни вноски, за които вноски няма.
   *
   * <p><b>⚠️ НЕ ПИПА НИТО ЕДНО ЧИСЛО НА ПАРИТЕ.</b> Методът само ПИШЕ нови полета. Начисленията,
   * вноските и данъкът четат {@code BgPayrollInput.unpaidLeaveDaysInD1} и {@code art177LeaveDays} —
   * същите полета, от същия вход. Нула нови читателя по паричния път.
   *
   * <p><b>⚠️ ДАТАТА Е ДИСКРИМИНАТОР, НЕ ОДИТ.</b> Генерираният getter за decimal връща НУЛА, не
   * {@code null} — тоест „нула дни отпуск“ и „фиш отпреди замразяването“ са неразличими по числата.
   * Без датата Д1 щеше да прочете всеки заварен месец като месец без отпуск.
   *
   * <p>⚠️ Викa се и когато отпуск НЯМА: тогава замразява нули и вдига датата. Пропуснато при празен
   * вход, полето щеше да остане празно и Д1 да падне на огледалната заявка — тоест fallback-ът щеше
   * да се задейства на ЖИВИ фишове, а не само на историята.
   */
  protected void freezeLeaveDays(BgPaySlip slip) {
    freezeLeaveDays(slip, payrollInput(slip));
  }

  /**
   * Както горното, но с ВЕЧЕ СГЛОБЕН вход.
   *
   * <p>⚠️ ДВЕ ВРАТИ, ЗАЩОТО ЕДНОАРГОВАТА Е ШЕВЪТ. {@code prepare()} се вика и от тестове без Guice
   * и без Postgres; те подменят едноарговата, точно както подменят {@code generateSickLeave}.
   * Разширена на място, единствената сигнатура щеше да иска от всеки такъв тест да построи вход,
   * който изобщо не му трябва.
   */
  protected void freezeLeaveDays(BgPaySlip slip, BgPayrollInput input) {
    if (input == null) {
      // Без вход няма какво да се замрази — и НЕ се вдига дата: това е точно случаят, в който Д1
      // трябва да падне на огледалната заявка, а не да прочете нули.
      return;
    }
    Map<String, BigDecimal> byCode = input.leaveDaysByD1Code;
    slip.setBgLeaveDays161(frozenDays(byCode, BgD1DayBuckets.B_16_1));
    slip.setBgLeaveDays162(frozenDays(byCode, BgD1DayBuckets.B_16_2));
    slip.setBgLeaveDays163(frozenDays(byCode, BgD1DayBuckets.B_16_3));
    slip.setBgLeaveDays164(frozenDays(byCode, BgD1DayBuckets.B_16_4));
    slip.setBgLeaveDays165(frozenDays(byCode, BgD1DayBuckets.B_16_5));
    slip.setBgLeaveDays16A(frozenDays(byCode, BgD1DayBuckets.B_16_A));
    slip.setBgLeaveDaysOutside(
        input.daysOutsideInsurance == null ? BigDecimal.ZERO : input.daysOutsideInsurance);

    // ═══ FAIL-LOUD: КОФИТЕ И ОТСЪСТВИЯТА ТРЯБВА ДА КАЗВАТ ЕДНО И СЪЩО ═══
    // ⚠️ Сборът на кофите плюс дните извън осигуряването е ПОДМНОЖЕСТВО на преброените отсъствия:
    // не всяко отсъствие има кофа (болничните пътуват като епизоди и се замразяват другаде).
    // Затова проверката е ≤, а не равенство — равенство тук би паднало на всеки фиш с болничен.
    // Надхвърли ли сборът отсъствията, две половини на един обход си противоречат и числото не
    // бива да стигне до декларация: по-голямото изважда от т. 16.1 дни, които никой не е отсъствал.
    BigDecimal bucketed = BigDecimal.ZERO;
    for (String bucket : BgD1DayBuckets.BUCKETS) {
      bucketed = bucketed.add(frozenDays(byCode, bucket));
    }
    bucketed =
        bucketed.add(
            input.daysOutsideInsurance == null ? BigDecimal.ZERO : input.daysOutsideInsurance);
    BigDecimal absent = totalLeaveDaysOf(input);
    if (absent != null && bucketed.compareTo(absent) > 0) {
      throw new IllegalStateException(
          "дните по кофи на т. 16 ("
              + bucketed.toPlainString()
              + ") надхвърлят преброените дни отсъствие ("
              + absent.toPlainString()
              + ") за фиш "
              + slip.getReference()
              + ". Двете излизат от ЕДИН обход над едни и същи заявки — разминаване значи, че"
              + " едната половина е сметната по друго правило. Числото НЕ се замразява:"
              + " по-голямото изважда от т. 16.1 дни, които никой не е отсъствал, и декларацията"
              + " излиза с по-малко дни в осигуряване, отколкото лицето наистина има.");
    }
    slip.setBgLeaveDaysFrozenAt(LocalDateTime.now());
    // ⚠️ И ДНИТЕ НА КАРВА: коригиращото копие карвира 180/181 по тях, а кофите отгоре не ги
    // възстановяват — т. 16.1 носи и видове по чл. 177, и такива без него. Замразяват се само
    // когато
    // входът ги има: при отказ на отпуските те са null и датата не се вдига.
    if (input.art177LeaveDays != null && input.unpaidLeaveDaysInD1 != null) {
      slip.setBgArt177LeaveDays(input.art177LeaveDays);
      slip.setBgUnpaidLeaveDaysInD1(input.unpaidLeaveDaysInD1);
      slip.setBgCarveDaysFrozenAt(LocalDateTime.now());
    }
  }

  /** Дните на една кофа, или НУЛА — тук нулата е стойност, защото датата носи „замразено ли е“. */
  private static BigDecimal frozenDays(Map<String, BigDecimal> byCode, String bucket) {
    if (byCode == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal days = byCode.get(bucket);
    return days == null ? BigDecimal.ZERO : days;
  }

  /** Платен + неплатен + други — преброените дни отсъствие, или {@code null} при отказ. */
  private static BigDecimal totalLeaveDaysOf(BgPayrollInput input) {
    if (input.leaveDaysRefusal != null) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal part :
        Arrays.asList(input.paidLeaveDays, input.unpaidLeaveDays, input.otherAbsenceDays)) {
      if (part != null) {
        sum = sum.add(part);
      }
    }
    return sum;
  }

  /**
   * ФИШЪТ НА САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ — ЕДИН ГЕНЕРАТОР, И ПЕТ ОБЯВЕНИ ЛИПСИ.
   *
   * <p><b>⚠️ ИЗБРОЯВАНЕ, А НЕ ПРЕСКАЧАНЕ.</b> Изкушението беше да се пуснат същите шест генератора
   * и да се разчита, че всеки ще излезе празен сам. Пет от тях наистина биха излезли празни днес —
   * но по СЛУЧАЙНОСТ на входа, не по устройство: {@code BgEarningsService} обхожда ЦЕЛИЯ речник с
   * типове пера и връща отговор за всеки, тоест нов ред в {@code bg_pay_slip_line_type.csv} влиза в
   * диспача сам. Такъв ред би започнал да се начислява на самоосигуряващо се лице, без нито един
   * комит да го е поискал. Явното изброяване няма този проблем по устройство.
   *
   * <p><b>Защо всеки от петте не се вика — по НОРМА, не по липса на време:</b>
   *
   * <ul>
   *   <li><b>Начисления (група 1xx–4xx).</b> Няма възнаграждение. КСО чл. 6 ал. 8 определя базата
   *       като ИЗБОР между минимума и максимума по ЗБДОО, а не като начислена сума — оттам и класът
   *       прослужено време няма върху какво да се сметне.
   *   <li><b>Болничен по чл. 40 ал. 5 КСО.</b> Дословно: първите два работни дни се плащат „от
   *       ОСИГУРИТЕЛЯ“. Самоосигуряващият се няма осигурител — вноските му са „за сметка на
   *       осигурените лица“ (чл. 6 ал. 8), — тоест няма кой да плати тези дни. Обезщетението от ДОО
   *       е на НОИ и не е перо на фиша при никого.
   *   <li><b>Обезщетения (група 9xx).</b> Всичките двайсет и пет са по КТ, ЗДСл или ЗВО и искат
   *       прекратено ПРАВООТНОШЕНИЕ. Регистрацията се прекратява, а не се обезщетява.
   *   <li><b>Здравно при неплатен отпуск (ЗЗО чл. 40 ал. 1 т. 1 б. „б“).</b> Дословно „за лицата в
   *       неплатен отпуск“ — отпускът е институт на трудовото правоотношение (чл. 160 КТ).
   *       Самоосигуряващият се не излиза в отпуск, а ПРЕКЪСВА дейност, и тогава просто не дължи
   *       вноски за прекъснатия период.
   *   <li><b>Данък по чл. 42 ЗДДФЛ.</b> Дословно „авансовият данък за доходи от ТРУДОВИ
   *       ПРАВООТНОШЕНИЯ се определя от РАБОТОДАТЕЛЯ“. Самоосигуряващият се декларира и внася своя
   *       данък сам, с годишната данъчна декларация по чл. 50. ⚠️ ЕДНО ИЗКЛЮЧЕНИЕ ВЕЧЕ СЕ ВИЖДА и е
   *       записано, за да не се сметне за уредено: при вид осигурен 41 данъкът е АЛТЕРНАТИВЕН по
   *       чл. 41а ЗДДФЛ (фиксирана сума на месец) и се удържа от държавното предприятие по чл. 163
   *       ал. 1 от Закона за горите — друг данък, друг платец, друг ред. Той идва със своя код.
   * </ul>
   *
   * <p>⚠️ И ЕДНО ИЗВЕСТНО ОГРАНИЧЕНИЕ, ЗАПИСАНО: съдружникът и собственикът на ЕООД попълват т. 31
   * и 31а на Д1 за доходи по § 1 т. 26 б. „и“ ЗДДФЛ (заб. 1 към т. 31), тоест при код 12 облагаем
   * доход и данък МОГАТ да съществуват. Днес те влизат само като РЪЧНО перо — фишът трябва да е
   * верен и само с ръчни редове, както и при наетия.
   */
  protected void prepareSelfInsured(BgPaySlip slip) {
    generateContributions(slip);
  }

  /**
   * НАЧИСЛЕНИЯТА — стъпка 3 по § 6.3.1, закачени за {@link #prepare}.
   *
   * <p>⚠️ Наименуван шев, а не три реда вътре в {@code prepare()}: генераторът пипа базата (речника
   * с типове пера и каталога с параметри), а {@code prepare()} се вика и от тестове без Guice. Тук
   * подмяната е един метод.
   *
   * <p>⚠️ ГРАНИЦАТА НА СТЪПКАТА НЕ СЕ МЕСТИ. Автоматично се попълва само това, за което ИМА
   * източник — днес основното възнаграждение и класът прослужено време. Нощният труд, извънредният
   * и разположението остават РЪЧНИ и генераторът не ги докосва: той пише само двата реда със своите
   * кодове. Фишът трябва да е верен и само с ръчни редове.
   *
   * <p>⚠️ Входът минава през СЪЩИЯ шев като осигуровките ({@link #payrollInput}), а не чете
   * договора втори път: два прочита на един договор са свободни да се разминат, а разминаване тук
   * значи клас, сметнат по друг стаж от този, по който се смятат вноските.
   */
  protected void generateEarnings(BgPaySlip slip) {
    // ⚠️ ЕДИН ВХОД ЗА ДВЕТЕ, А НЕ ДВА ПРОЧИТА. Замразените часове трябва да са ТОЧНО онези, по
    // които са сметнати перата — второ извикване на payrollInput() е свободно да прочете отчет,
    // редактиран между двата реда, и фишът щеше да плаща по едно число и да декларира с друго.
    BgPayrollInput input = payrollInput(slip);
    freezeHours(slip, input);
    new BgPaySlipVerdicts()
        .addEarnings(BgPaySlipVerdicts.STEP_EARNINGS, earningsService().generate(slip, input))
        .appendInto(slip);
  }

  /**
   * ЗАМРАЗЯВА ОСЕМТЕ ЧАСОВИ ЧИСЛА И ЧЕТИРИТЕ ПРИЗНАКА ВЪРХУ ФИША.
   *
   * <p><b>Защо изобщо.</b> Прочетени от ЖИВИЯ отчет за време, часовете идват от две места с
   * различна съдба: този сглобител ги дава на ведомостта, {@code BgD1HourSource} — на т. 16.7 и т.
   * 16.8 на Декларация обр. 1. Валидиран отчет, редактиран СЛЕД потвърждаването на фиша, би местил
   * декларацията и оставил фиша на старото число. Замразени тук, двете четат едно.
   *
   * <p><b>⚠️ ПРИЗНАЦИТЕ НЕ СА УКРАСА.</b> Генерираният getter за decimal връща ZERO за NULL колона,
   * тоест без тях „нула нощни часа“ и „никой не е попълвал“ стават неразличими още при записа — и
   * добавката по НСОРЗ чл. 8 би отпаднала мълчаливо при всяко второ четене. Четирите отговарят на
   * ТРИТЕ ИЗТОЧНИКА на входа плюс втората причина за „не знам“ при празничните часове (непокрита от
   * производствения календар година).
   *
   * <p><b>⚠️ КОРИГИРАЩ И ЗАЛИЧАВАЩ ФИШ НЕ СЕ ЗАМРАЗЯВАТ ПАК.</b> Техните числа идват от ОРИГИНАЛА
   * през {@link #retroCopyOf} и по същия довод като работните дни: коригира се ОНЗИ месец, а
   * отчетът оттогава може да е променен. Презаписани тук с днешния отчет, те щяха да пренапишат
   * историята точно там, където целият ретро-механизъм съществува, за да не я пренаписва.
   */
  protected void freezeHours(BgPaySlip slip, BgPayrollInput input) {
    if (input == null
        || Boolean.TRUE.equals(slip.getBgIsCorrection())
        || Boolean.TRUE.equals(slip.getBgIsDeletion())) {
      return;
    }
    slip.setBgWorkedHoursKnown(input.workedHours != null);
    slip.setBgWorkedHours(input.workedHours);
    slip.setBgTimesheetDays(input.timesheetDays);

    slip.setBgDeclaredHoursKnown(input.nightHours != null || input.onCallHours != null);
    slip.setBgNightHours(input.nightHours);
    slip.setBgOnCallHours(input.onCallHours);

    slip.setBgHolidayHoursKnown(input.holidayWorkHours != null);
    slip.setBgHolidayWorkHours(input.holidayWorkHours);

    slip.setBgOvertimeHoursKnown(
        input.overtimeWorkdayHours != null
            || input.overtimeRestDayHours != null
            || input.overtimeHolidayHours != null
            || input.overtimeSirvHours != null);
    slip.setBgOvertimeWorkdayHours(input.overtimeWorkdayHours);
    slip.setBgOvertimeRestDayHours(input.overtimeRestDayHours);
    slip.setBgOvertimeHolidayHours(input.overtimeHolidayHours);
    slip.setBgOvertimeSirvHours(input.overtimeSirvHours);
  }

  /**
   * ШЕВЪТ КЪМ ГЕНЕРАТОРА НА НАЧИСЛЕНИЯ — за да може свидетел да го подмени, без да подменя
   * стъпката.
   *
   * <p>⚠️ ОТДЕЛЕН МЕТОД, А НЕ {@code Beans.get} НА МЯСТО, и това не е стил. Твърдението „присъдите
   * не се изхвърлят“ се проверява само ако тестът може да накара генератора да ОТКАЖЕ и после да
   * прочете фиша. Подмени ли се цялата стъпка ({@link #generateEarnings}), подменя се и записът на
   * присъдите — тоест тестът щеше да проверява собствената си фикстура. Същият идиом като {@code
   * BgSickLeaveService#writeSpecialHealthBase}.
   */
  protected BgEarningsService earningsService() {
    return Beans.get(BgEarningsService.class);
  }

  /**
   * ОБЕЗЩЕТЕНИЯТА КАТО ПЕРА (група 9xx) — закачени за {@link #prepare}.
   *
   * <p>⚠️ Наименуван шев, а не три реда вътре в {@code prepare()}: генераторът пипа базата (речника
   * с типове пера и документите на обезщетенията), а {@code prepare()} се вика и от тестове без
   * Guice. Тук подмяната е един метод — същият идиом като {@link #generateEarnings}.
   *
   * <p><b>⚠️ ВИКА СЕ СЛЕД {@link #generateEarnings} И ПРЕДИ ОСИГУРОВКИТЕ, И ТОВА Е ПРАВИЛО.</b>
   * Част от обезщетенията СА осигурителен доход (чл. 222 ал. 1 КТ, чл. 225 ал. 1 КТ, чл. 106 ал. 2
   * ЗДСл, чл. 58 ал. 3 ЗВО — четири от двайсет и петте), тоест вноските и данъкът долу трябва да ги
   * видят вече като пера. Пуснат СЛЕД тях, обезщетението остава неосигурено и необложено, а фишът
   * изглежда напълно нормален.
   *
   * <p>⚠️ ГРАНИЦАТА НА СТЪПКАТА: сумата НЕ се смята тук. Тя е замразена върху документа при
   * потвърждаването му и само се копира — виж {@link BgCompensationLineService}.
   *
   * <p>⚠️ Входът минава през СЪЩИЯ шев като начисленията и осигуровките ({@link #payrollInput}), а
   * не чете договора четвърти път: четири прочита на един договор са свободни да се разминат.
   */
  protected void generateCompensations(BgPaySlip slip) {
    new BgPaySlipVerdicts()
        .addLines(
            BgPaySlipVerdicts.STEP_COMPENSATIONS,
            compensationLineService().generate(slip, payrollInput(slip)))
        .appendInto(slip);
  }

  /**
   * ПРЕВИШЕНИЯТА НАД ТАВАНА НА ЕДНОКРАТНИТЕ ПОМОЩИ КАТО ПЕРА (221/223/225) — закачени за {@link
   * #prepare} и {@link #prepareCorrection}.
   *
   * <p>Операторът въвежда ЦЯЛАТА помощ на перото „в рамките“ (220/222/224). Тук то получава
   * освободената част до остатъка от тавана (ЗДДФЛ чл. 24 ал. 2 т. 11, 1227,10 € за календарна
   * година при един работодател — параметърът е в {@code bg_social_expense_cap.csv}), а
   * превишението сяда в генерирано перо-превишение: облагаемо (чл. 24 ал. 1 във връзка с чл. 205
   * ЗКПО), тоест в основата по чл. 42 ал. 2 и в т. 31 на Д1 по съществуващия път. Осигурителният
   * доход е флаг на перото и следва НЕВД: бракът (222/223) е, раждането и смъртта — не.
   *
   * <p><b>⚠️ ПРЕДИ ВНОСКИТЕ И ДАНЪКА, И РЕДЪТ Е ПРАВИЛО</b> — по довода при {@link
   * #generateCompensations}: превишението при брак е осигурителен доход, а при трите — облагаем.
   *
   * <p><b>⚠️ РЕДОВЕТЕ НА ГЕНЕРАТОРА СЕ ОБНОВЯВАТ НА МЯСТО</b> и се познават по {@code
   * bgIsGenerated}: второ „Преизчисли“ дава същия фиш, а нулево превишение маха реда си. Ръчен
   * ред-превишение не се осиновява — той се отказва по-рано ({@code
   * refuseManualDerivedSocialExpense}).
   *
   * <p>⚠️ Чистият изход — фиш без 2xx не пита Guice (по довода при {@link #socialExpenseTaxBase}).
   */
  protected void generateSocialExpenseExcess(BgPaySlip slip) {
    if (!hasSocialExpenseLines(slip)) {
      return;
    }
    Map<BgPaySlipLineType, BigDecimal> wanted = new LinkedHashMap<>();
    for (BgSocialExpenseService.Excess e : socialExpenseService().splitPersonalExcess(slip)) {
      BgPaySlipLineType excessType = excessTypeOf(e.within.getBgPaySlipLineType());
      if (excessType == null) {
        throw new BgCalculationRefusedException(
            "Към перото № "
                + e.within.getBgLineNumber()
                + " „"
                + e.within.getName()
                + "“ в речника няма перо-превишение (следващият номер, същият таван, „превишение над"
                + " капачката“) — превишението над тавана няма къде да влезе и щеше да остане"
                + " необложено. Редът идва от bg_pay_slip_line_type_social.csv.");
      }
      wanted.merge(excessType, e.excess, BigDecimal::add);
    }
    List<BgPaySlipLine> generated = new ArrayList<>();
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      if (Boolean.TRUE.equals(line.getBgIsGenerated())
          && BgSocialExpenseCapResolver.isDerivedSocialExpenseLine(line)) {
        generated.add(line);
      }
    }
    for (BgPaySlipLine line : generated) {
      BigDecimal amount = wanted.remove(line.getBgPaySlipLineType());
      if (amount == null || amount.signum() == 0) {
        slip.getBgPaySlipLineList().remove(line);
      } else {
        line.setBgAmount(amount);
      }
    }
    for (Map.Entry<BgPaySlipLineType, BigDecimal> e : wanted.entrySet()) {
      if (e.getValue().signum() > 0) {
        BgPaySlipLine line = lineFrom(e.getKey(), e.getValue());
        line.setBgIsGenerated(true);
        addLine(slip, line);
      }
    }
  }

  /**
   * ПЕРОТО-ПРЕВИШЕНИЕ на едно перо „в рамките“ — следващият номер, същият таван, {@code
   * bgIsCapExcess}. Шев: чете речника.
   */
  protected BgPaySlipLineType excessTypeOf(BgPaySlipLineType within) {
    if (within == null || within.getBgSocialExpenseCap() == null) {
      return null;
    }
    List<BgPaySlipLineType> found =
        JPA.em()
            .createQuery(
                "SELECT t FROM BgPaySlipLineType t WHERE t.number = :n"
                    + " AND t.bgSocialExpenseCap = :cap AND t.bgIsCapExcess = TRUE",
                BgPaySlipLineType.class)
            .setParameter("n", within.getNumber() + 1)
            .setParameter("cap", within.getBgSocialExpenseCap())
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /** Шевът към генератора на обезщетения — виж {@link #earningsService()} защо е отделен. */
  protected BgCompensationLineService compensationLineService() {
    return Beans.get(BgCompensationLineService.class);
  }

  /**
   * ОСИГУРОВКИТЕ КАТО ПЕРА (група 5xx) — закачени за {@link #prepare}.
   *
   * <p>⚠️ Наименуван шев, а не два реда вътре в {@code prepare()}: генераторът пипа речника с
   * типове пера, а {@code prepare()} се вика и от тестове без Guice. Тук подмяната е един метод —
   * същият идиом като {@link #generateEarnings}.
   *
   * <p><b>⚠️ РЕЗУЛТАТЪТ СЕ ПОДАВА, А НЕ СЕ ЧЕТЕ ОТ ГЕНЕРАТОРА.</b> Веригата на осигуровките е
   * {@link #contributions} и остава ТУК — вид осигурен, режим на работното време, МОД, под и таван,
   * избор на редове по ключа. Подадена, тя се смята ВЕДНЪЖ за фиша и перата са ТОЧНО това, което
   * пазачът при потвърждаване е видял. Прочетена наново вътре в генератора, тя щеше да е ВТОРО
   * извикване, свободно да се размине с първото — а разминаване тук значи фиш, чиито пера казват
   * едно, а пазачът му друго.
   *
   * <p><b>⚠️ ВИКА СЕ ПОСЛЕДЕН, И ТОВА Е ПРАВИЛО, НЕ ПОДРЕДБА.</b> Осигурителният доход е сбор на
   * ПЕРАТА, тоест всяко начисление — основна заплата, клас, болничен, обезщетение — трябва вече да
   * е ред. Пуснат по-рано, генераторът осигурява непълен фиш: вноските излизат по-малки,
   * декларацията с тях, а нищо на екрана не изглежда сбъркано. Заковано е със свидетел в {@code
   * BgPaySlipServiceTest}, а не оставено на подредбата тук.
   */
  protected void generateContributions(BgPaySlip slip) {
    List<BgPaySlipLineOutcome> outcomes =
        new ArrayList<>(contributionLineService().generate(slip, contributions(slip)));
    // ⚠️ ОБЕЗЩЕТЕНИЕТО ПО ВИД 27 СЕ ОСИГУРЯВА ОТДЕЛНО — със своя заявка и със свои пера, белязани
    // с документа (BgContributionLineService#generate с препратка). Виж compensationContributions.
    List<BgCompensationParts.Carried> carried = BgCompensationParts.carried(slip);
    for (BgCompensationParts.Carried c : carried) {
      outcomes.addAll(
          contributionLineService().generate(slip, compensationContributions(slip, c), c.getDoc()));
    }
    removeOrphanCompensationContributions(slip, carried);
    new BgPaySlipVerdicts()
        .addLines(BgPaySlipVerdicts.STEP_CONTRIBUTIONS, outcomes)
        .appendInto(slip);
    // ⚠️ НЕ ЗА КОПИЕТО: „само здравно“ чете повода от живите заявки за отпуск, а той не е
    // замразен. В копието перото остава копирано — виж prepareCorrection и healthOnlyRefusal.
    if (!BgPaySlipResolver.isRetro(slip)) {
      generateUnpaidLeaveHealth(slip);
    }
  }

  /** Шевът към генератора на осигуровки — виж {@link #earningsService()} защо е отделен. */
  protected BgContributionLineService contributionLineService() {
    return Beans.get(BgContributionLineService.class);
  }

  /**
   * ЗДРАВНАТА ВНОСКА ПРИ НЕПЛАТЕН ОТПУСК КАТО ПЕРО — ЗЗО чл. 40 ал. 1 т. 1 б. „б“.
   *
   * <p><b>⚠️ ТОВА Е ПРОИЗВОДСТВЕНИЯТ ЧИТАТЕЛ НА {@code BgUnpaidLeaveHealthResolver}.</b> Без него
   * лицето в неплатен отпуск получава фиш БЕЗ здравна вноска, а декларацията му — празни т. 27 и т.
   * 28. Перото се записва благодарение на база-дискриминатора.
   *
   * <p><b>⚠️ СЛЕД {@link #generateContributions}, И РЕДЪТ НЕ Е СВОБОДЕН.</b> Вноската е ВЪРХУ ДРУГА
   * база — половината минимален осигурителен доход, а не осигурителния доход на фиша — тоест тя НЕ
   * зависи от перата на 5xx. Но перото ѝ е ОТ ТЯХНАТА ГРУПА и от техния тип (516/517), и
   * идемпотентността на {@code upsert} се пази по ДВОЙКАТА код + база. Пуснат ПРЕДИ нормалния
   * генератор, той щеше да намери своето перо и да го обнови вярно — но нормалният после щеше да
   * търси „перо от тип 516 с база STANDARD“, да не намери нищо и да създаде второ. Двете биха
   * съществували и това е верният изход; редът обаче е закован тук, защото обратният ред работи по
   * случайност, не по устройство.
   *
   * <p>⚠️ Отказът НЕ спира фиша: той е СЪСТОЯНИЕ, което пътува. Виж javadoc-а на услугата защо два
   * различни повода за неплатен отпуск в един месец се отказват, вместо да се смесят.
   */
  protected void generateUnpaidLeaveHealth(BgPaySlip slip) {
    new BgPaySlipVerdicts()
        .addUnpaidLeaveHealth(
            BgPaySlipVerdicts.STEP_UNPAID_LEAVE_HEALTH,
            unpaidLeaveHealthService().generate(slip, payrollInput(slip)))
        .appendInto(slip);
  }

  /** Шевът към здравната вноска при неплатен отпуск — виж {@link #earningsService()}. */
  protected BgUnpaidLeaveHealthService unpaidLeaveHealthService() {
    return Beans.get(BgUnpaidLeaveHealthService.class);
  }

  /**
   * ВЪЗНАГРАЖДЕНИЕТО ЗА ПЛАТЕН ОТПУСК ПО КТ ЧЛ. 177 (перо 165) — закачено за {@link #prepare}.
   *
   * <p>⚠️ Наименуван шев, а не три реда вътре в {@code prepare()}: генераторът пипа базата (речника
   * с типове пера, фишовете на предходните месеци, заявките за отпуск и производствения календар),
   * а {@code prepare()} се вика и от тестове без Guice. Същият идиом като {@link
   * #generateEarnings}.
   *
   * <p><b>⚠️ ПЕРОТО ПРЕМЕСТВА ПАРИ, НЕ ДОБАВЯ.</b> Дните платен отпуск вече са извадени от перата с
   * постоянен характер от {@link #generateEarnings} и се връщат тук по среднодневното от ПРЕДХОДНИЯ
   * месец. При непроменено възнаграждение двете се компенсират до стотинката — фишът излиза бит по
   * бит какъвто беше. Виж {@code BgArt177LeaveService}.
   */
  protected void generateArt177Leave(BgPaySlip slip) {
    new BgPaySlipVerdicts()
        .addArt177Leave(
            BgPaySlipVerdicts.STEP_ART177_LEAVE,
            art177LeaveService().generate(slip, payrollInput(slip)))
        .appendInto(slip);
  }

  /** Шевът към енджина по чл. 177 — виж {@link #earningsService()}. */
  protected BgArt177LeaveService art177LeaveService() {
    return Beans.get(BgArt177LeaveService.class);
  }

  /**
   * ДАНЪКЪТ ПО ЧЛ. 42 ЗДДФЛ КАТО ПЕРО (група 7xx) — закачен за {@link #prepare}.
   *
   * <p>⚠️ Наименуван шев, а не два реда вътре в {@code prepare()}: генераторът пипа речника с
   * типове пера, а {@code prepare()} се вика и от тестове без Guice.
   *
   * <p><b>⚠️ ВИКА СЕ СЛЕД {@link #generateContributions}, И ТОВА Е ПРАВИЛО.</b> Данъчната основа е
   * облагаемите начисления МИНУС личните осигуровки, а те са перата от 5xx със знак DEBIT. Пуснат
   * преди тях, генераторът смята данък върху ЦЕЛИЯ облагаем доход — на златния фиш на Асен
   * разликата е между 0,00 и 9,47, тоест данък, удържан от лице, което е било болно целия месец.
   *
   * <p><b>⚠️ СТАВКАТА СЕ ПОДАВА, а не се чете от генератора</b> — по същия довод като при
   * осигуровките: {@link #incomeTaxRate} вече я чете за заглавната сума и второ четене е свободно
   * да се размине с първото. А разминаване тук значи перо и заглавна част, които не си съвпадат —
   * тоест {@code refuseTaxMismatch} блокира ВЕРЕН фиш.
   *
   * <p>⚠️ ТОВА ЗАТВАРЯ {@code refuseTaxMismatch}: дотук перо от 7xx не създаваше никой и всеки фиш
   * с данък трябваше да получи перото си на ръка.
   */
  protected void generateIncomeTax(BgPaySlip slip) {
    // ⚠️ РАЗКЛОНЕНИЕТО Е ПРЕДИ ГЕНЕРАТОРА, А НЕ ВЪТРЕ В НЕГО. Алтернативният данък по чл. 41а не
    // приема нито основа, нито ставка, нито облекчение по чл. 18 ал. 2 — прекаран през общия път,
    // той щеше да СМЯТА нещо, което нормата само назовава. Затова има своя врата.
    if (hasAlternativeTax(slip)) {
      recordTax(slip, incomeTaxService().generateAlternative(slip, alternativeTax(slip)));
      return;
    }
    // ⚠️ ГРАЖДАНСКИЯТ ДОГОВОР СЕ ОБЛАГА ПО ЧЛ. 43, НЕ ПО ЧЛ. 42 — И РАЗКЛОНЕНИЕТО Е ПАК ТУК, ПРЕД
    // ГЕНЕРАТОРА. Чл. 42 ал. 1 дословно урежда „авансовия данък за доходи от ТРУДОВИ
    // ПРАВООТНОШЕНИЯ“; код 14 е чл. 4 ал. 3 т. 5 и 6 КСО, тоест труд БЕЗ трудово правоотношение, и
    // неговият авансов данък е по чл. 43 — от облагаемия доход по ЧЛ. 29, тоест СЛЕД нормативните
    // разходи. При 2000,00 и 25 на сто разликата е 150,00 срещу 200,00.
    // ⚠️ САМО КОД 14. Наетият (01), ДУК-ът (10) и СОЛ остават по чл. 42 непокътнати — това е
    // разклонение, а не смяна на данъчния двигател.
    // ⚠️ И ОБЛЕКЧЕНИЕТО НЕ СЕ ПОДАВА, а не се подава като нула: чл. 18 ал. 2 се отнася за
    // „месечната данъчна основа ПО ЧЛ. 42, АЛ. 2 за доходи от ТРУДОВИ ПРАВООТНОШЕНИЯ“, тоест по чл.
    // 43 не се полага. Виж BgIncomeTaxService#generateBusiness за двете обявени липси на ал. 2 и 5.
    if (civilContractSlip(slip)) {
      recordTax(
          slip,
          incomeTaxService().generateBusiness(slip, incomeTaxRate(slip), businessExpense(slip)));
      return;
    }
    recordTax(slip, incomeTaxService().generate(slip, incomeTaxRate(slip), disabilityRelief(slip)));
  }

  /**
   * Присъдите на данъка — общи за трите му врати, за да не се разминат текстовете.
   *
   * <p>⚠️ И ТРИТЕ, а не само общата: разклонението е ПРЕД генератора, тоест фиш по чл. 41а минава
   * само през {@code generateAlternative}. Вързана една врата, отказите на другите две щяха да
   * останат точно толкова тихи, колкото бяха.
   */
  protected void recordTax(BgPaySlip slip, List<BgPaySlipLineOutcome> outcomes) {
    new BgPaySlipVerdicts().addLines(BgPaySlipVerdicts.STEP_INCOME_TAX, outcomes).appendInto(slip);
  }

  /** Шевът към генератора на данъка — виж {@link #earningsService()} защо е отделен. */
  protected BgIncomeTaxService incomeTaxService() {
    return Beans.get(BgIncomeTaxService.class);
  }

  /**
   * Шевът към социалните разходи — виж {@link #earningsService()} защо е отделен. Тестът на фиша с
   * капачките подменя САМО четенето от базата в услугата, а сметката ѝ остава истинската.
   */
  protected BgSocialExpenseService socialExpenseService() {
    return Beans.get(BgSocialExpenseService.class);
  }

  /**
   * ГОДИШНОТО ИЗРАВНЯВАНЕ ПО ЧЛ. 49 ЗДДФЛ — перо 710, само на януарски фиш.
   *
   * <p><b>⚠️ КОНСУМАЦИЯ, А НЕ СМЕТКА.</b> Числото е колона 19 от справката по чл. 73 ал. 6, вече
   * изчислена от {@code BgArt49Recalculation} и записана в {@code BgArt73Para6Annual}. Този шев
   * само я донася до фиша. Виж {@link BgArt49SettlementService} за трите състояния и защо
   * липсващата справка е ОТКАЗ, а липсващият ред в нея — законна нула.
   *
   * <p>⚠️ Наименуван шев по образеца на {@link #generateIncomeTax}: генераторът пипа базата (две
   * заявки — справката и речникът), а {@code prepare()} се тества без нея.
   */
  protected void generateAnnualSettlement(BgPaySlip slip) {
    new BgPaySlipVerdicts()
        .addLines(BgPaySlipVerdicts.STEP_ART49_SETTLEMENT, art49SettlementService().generate(slip))
        .appendInto(slip);
  }

  /** Шевът към генератора на изравняването — виж {@link #earningsService()} защо е отделен. */
  protected BgArt49SettlementService art49SettlementService() {
    return Beans.get(BgArt49SettlementService.class);
  }

  /**
   * БОЛНИЧНИЯТ ПО ЧЛ. 40 АЛ. 5 КСО — закачен за {@link #prepare}.
   *
   * <p>⚠️ Наименуван шев, а не три реда вътре в {@code prepare()}: генераторът пипа базата (речника
   * с отсъствия, речника с типове пера, каталога с параметри и таблицата със ставки), а {@code
   * prepare()} се вика и от тестове без Guice. Тук подмяната е един метод — същият идиом като
   * {@link #generateEarnings}.
   *
   * <p><b>⚠️ ВИКА СЕ ПРЕДИ {@link #generateEarnings}, И ТОВА Е ПРАВИЛО, НЕ ПОДРЕДБА.</b> Енджинът
   * НАМАЛЯВА отработените дни с дните болничен, а начисленията четат отработените дни от фиша.
   * Разменени, лице болно целия месец получава пълна основна заплата И болничен — двойно плащане,
   * което изглежда точно като верен фиш.
   *
   * <p>⚠️ ГРАНИЦАТА НА СТЪПКАТА: пише се САМО перото на болничния за сметка на осигурителя и двата
   * броя дни. Сумата, която НОИ плаща (Приложение 9, 80 или 90 на сто), НЕ е перо и НЕ се смята —
   * НОИ я плаща на лицето директно, а работодателят подава само данни.
   *
   * <p>⚠️ Входът минава през СЪЩИЯ шев като осигуровките и начисленията ({@link #payrollInput}), а
   * не чете договора трети път: три прочита на един договор са свободни да се разминат.
   */
  protected void generateSickLeave(BgPaySlip slip) {
    new BgPaySlipVerdicts()
        .addSickLeave(
            BgPaySlipVerdicts.STEP_SICK_LEAVE,
            sickLeaveService().generate(slip, payrollInput(slip)))
        .appendInto(slip);
  }

  /**
   * Болничният НА ЕКРАНА — същите дни и пера, без редовете по т. 9 върху листовете. Виж {@link
   * #preview}.
   */
  protected void previewSickLeave(BgPaySlip slip) {
    new BgPaySlipVerdicts()
        .addSickLeave(
            BgPaySlipVerdicts.STEP_SICK_LEAVE, sickLeaveService().preview(slip, payrollInput(slip)))
        .appendInto(slip);
  }

  /** Шевът към генератора на болничния — виж {@link #earningsService()} защо е отделен. */
  protected BgSickLeaveService sickLeaveService() {
    return Beans.get(BgSickLeaveService.class);
  }

  /**
   * Ново перо от речника — с КОПИРАНИ стойности.
   *
   * <p>Единственото място, където речникът се чете. След това перото живее само.
   *
   * <p><b>⚠️ ПРОПУСНАТ ФЛАГ ТУК Е ПРАЗНА ТОЧКА В ДЕКЛАРАЦИЯТА, НЕ КОЗМЕТИКА.</b> Понеже това е
   * ЕДИНСТВЕНОТО място, където речникът се чете, флаг, който не е копиран тук (например {@code
   * bgFormsGrossWage}), не се появява никъде другаде и генерираният {@code boolean} getter връща
   * подразбирането {@code false}. Тогава {@link BgPaySlipResolver#grossWageTotal} не намира нито
   * едно перо и <b>т. 29 на Декларация обр. 1 излиза празна за ВСЕКИ</b> — включително за наетия по
   * трудово правоотношение, комуто заб. към т. 29/30 на Приложение № 1 я ДЪЛЖИ.
   *
   * <p>⚠️ И такъв пропуск не се вижда: празната т. 29 е ВЕРНИЯТ изход за мнозинството видове
   * осигурен, а валидаторът вдига находка само за ПОПЪЛНЕНА точка при нетрудово правоотношение.
   * Тоест пропускът произвежда точно състоянието, което нито едно правило не проверява.
   */
  public BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    if (type != null) {
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
      // ⚠️ ЗАМРАЗЯВА СЕ ЗАЕДНО С ОСТАНАЛИТЕ: среднодневното по чл. 177 чете БАЗОВИЯ месец, тоест
      // пера отпреди седмици. Прочетен през речника днес, флагът би пренаписал базата на вече
      // изплатен отпуск.
      line.setBgIsArt17Base(type.getBgIsArt17Base());
      // ⚠️ СТОЙНОСТТА СЕ ПРЕНАСЯ, НЕ СЕ ВДИГА. „Формира БРЗ по НСОРЗ чл. 16 ал. 2“ е отговор ПЕРО
      // ПО ПЕРО и той вече живее в речника — true за 16 от 43-те реда. Перо 195 (болничен от
      // осигурител по чл. 40 ал. 5 КСО) и перо 175 (компенсация по чл. 138а КТ) са в група 1xx,
      // CREDIT са и са начислени, но НЕ са работна заплата; вдигнат безусловно, флагът щеше да ги
      // вкара в т. 29 — завишено число на верния ред.
      line.setBgFormsGrossWage(type.getBgFormsGrossWage());
    }
    line.setBgAmount(amount);
    return line;
  }

  /** Добавя перо към фиша от двете страни на връзката — o2m с mappedBy не се пълни сам. */
  public void addLine(BgPaySlip slip, BgPaySlipLine line) {
    if (slip.getBgPaySlipLineList() == null) {
      slip.setBgPaySlipLineList(new ArrayList<>());
    }
    line.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(line);
  }

  // ── единственият изчислителен път ──────────────────────────────────────

  /**
   * ПРЕСМЯТА ФИША — генераторите, после сборовете, после отпечатъкът. <b>Единственият път</b>, по
   * който формата смята: при нов фиш, при смяна на договор или период, при всяка промяна на перата
   * и на замразените входове, и от бутона „Преизчисли“.
   *
   * <p><b>⚠️ ЗАЩО Е ЕДИН.</b> Ако формата пуска {@link #prepare} само при нов фиш и смяна на
   * договор или период, а при промяна на перата — само {@link #recompute}, който сумира, перо 180 с
   * въведени 200,00 остава със сума 0,00 (карвът е в prepare), вноските и данъкът остават от
   * предишното пресмятане, и фишът се потвърждава без бонуса.
   *
   * <p><b>⚠️ ОТПЕЧАТЪКЪТ СЕ ПИШЕ ПОСЛЕДЕН</b>, след сборовете: той описва фиша ТОЧНО както е
   * пресметнат, и {@link #confirm} отказва, ако нещо се е променило оттогава.
   *
   * <p><b>⚠️ ТРАНЗАКЦИЯ, ЗАЩОТО ПЪТЯТ МОЖЕ ДА ПИШЕ.</b> Генераторът на болничния записва редовете
   * по месеци на листа ({@code BgSickLeaveService#writePeriodLines} → {@code saveSickLeave}), а
   * контролерите в модула нямат транзакция: запис извън нея пада с „no transaction is in progress“,
   * а {@code TraceBackService} после комитва полуготовото. Тук всичко или нищо. Записът на самия
   * фиш е на {@link #calculateAndSave} — в същата транзакция, тоест т. 9 и фишът се записват заедно
   * или изобщо. Пресмятането при onChange е {@link #preview} и не пише нищо.
   *
   * <p><b>⚠️ ДВЕ ИЗКЛЮЧЕНИЯ, ПОИМЕННО.</b> Внесеният исторически фиш не се пипа: числата му са от
   * Microinvest и генераторите нямат вход за тях. Коригиращото и заличаващото копие получават само
   * сборовете — производните им пера идват по своя път (виж {@link #retroCopyOf}).
   */
  @Transactional(rollbackOn = {Exception.class})
  public void calculate(BgPaySlip slip) {
    calculate(slip, true);
  }

  /**
   * ПРЕСМЯТА ФИША НА ЕКРАНА — onChange, нов фиш, смяна на договор или период. Същите генератори и
   * същите сборове като {@link #calculate}, с две разлики, и двете нарочни:
   *
   * <ul>
   *   <li><b>нищо извън фиша не се пише.</b> Редовете по т. 9 на болничните листове чакат записа на
   *       фиша ({@link #previewSickLeave}). Методът е БЕЗ транзакция: генератор, който все пак
   *       опита запис през репозиторий, пада с „no transaction is in progress“, вместо тихо да пише
   *       до запис на фиш, който може и да не дойде;
   *   <li><b>отпечатъкът се ИЗТРИВА, не се пише.</b> Отпечатъкът е обещанието на {@link #confirm},
   *       че фишът е пресметнат ТАКЪВ, КАКЪВТО Е ЗАПИСАН — заедно с т. 9. Пресмятане на екрана не
   *       може да го даде: т. 9 още я няма. Затова черновата, записана с „Запиши“ след onChange, се
   *       потвърждава чак след „Преизчисли“ — отказът вече праща точно там.
   * </ul>
   */
  public void preview(BgPaySlip slip) {
    calculate(slip, false);
  }

  /**
   * „ПРЕИЗЧИСЛИ“ — ПРЕСМЯТА И ЗАПИСВА ЧЕРНОВАТА, ЗАЕДНО С Т. 9, В ЕДНА ТРАНЗАКЦИЯ.
   *
   * <p>Фишът се сглобява от стойностите на екрана по същия път като стандартния запис на Axelor
   * ({@link JPA#edit}: оптимистичната проверка на версията, перата по идентификатор, новите —
   * нови), пресмята се по {@link #calculate} и се записва през репозитория на фиша. Провал където и
   * да е — в генератор, в записа на листа или в записа на фиша — връща ВСИЧКО: нито фишът, нито т.
   * 9 остават.
   *
   * <p><b>⚠️ ПАЗАЧИТЕ СА ПРЕДИ ПРЕСМЯТАНЕТО, И РЕДЪТ ИМ НЕ Е СВОБОДЕН.</b>
   *
   * <ol>
   *   <li><b>Статусът — ОТ БАЗАТА, ПРЕДИ {@link JPA#edit}.</b> Прочетен след редакцията, тоест от
   *       формата, той би позволил заявка със {@code statusSelect=DRAFT} да пресметне и презапише
   *       потвърден фиш.
   *   <li><b>Какво от формата влиза</b> — {@link BgPaySlipFormValues}: без статуса, отпечатъка,
   *       признаците на копието и (при редовен фиш) замразеното; препратките само се избират.
   *   <li><b>Дружеството — СЛЕД редакцията</b>, защото договорът е от формата: гейтът в контролера
   *       вижда записания фиш, не новия договор, а нов фиш изобщо няма записан.
   *   <li><b>Периодът</b> — отказите на потвърждаването, преди генераторите да пишат т. 9 по
   *       листовете.
   * </ol>
   *
   * <p>Записът минава и през пазачите на репозитория ({@code BgPaySlipManagementRepository#save}) —
   * те пазят стандартния запис и REST; тук са, за да не тръгне пресмятането изобщо.
   *
   * @param values стойностите на формата — контекстът на действието
   * @return записаният фиш
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgPaySlip calculateAndSave(Map<String, Object> values) {
    Long id = idOf(values);
    BgPaySlipWriteGuard.Stored stored = storedSlip(id);
    if (id != null && stored == null) {
      throw new BgCalculationRefusedException(
          "Фишът (id " + id + ") вече не съществува — затворете формата и го отворете наново.");
    }
    if (stored != null && !BgPaySlipResolver.DRAFT.equals(stored.status)) {
      // ⚠️ Бутонът е само на черновата, но заявката може да дойде и от остаряла или сглобена форма.
      throw new BgCalculationRefusedException(
          "Преизчислява се само чернова — фиш "
              + stored.reference
              + " е в статус "
              + stored.status
              + ". Нищо не е записано.");
    }
    String forged = BgPaySlipFormValues.forgedStatusRefusal(values);
    if (forged != null) {
      throw new BgCalculationRefusedException(forged);
    }
    boolean retro = stored != null && stored.retro;
    BgPaySlip slip = JPA.edit(BgPaySlip.class, BgPaySlipFormValues.accepted(values, retro));
    String noCompany = BgPaySlipWriteGuard.noCompanyRefusal(slip);
    if (noCompany != null) {
      throw new BgCalculationRefusedException(noCompany);
    }
    requireActiveCompany(BgPaySlipWriteGuard.companyOf(slip));
    String period = BgPaySlipWriteGuard.refusePeriod(slip, declaredToNap(slip.getPeriod()), retro);
    if (period != null) {
      throw new BgCalculationRefusedException(period);
    }
    calculate(slip, true);
    // ⚠️ JPA.edit дава управляван фиш или нов; JPA.save ги запазва в същата инстанция — новият
    // получава идентификатора си тук, във flush-а на записа.
    save(slip);
    return slip;
  }

  /** Идентификаторът от формата, или {@code null} за нов фиш. */
  static Long idOf(Map<String, Object> values) {
    Object id = values == null ? null : values.get("id");
    if (id == null) {
      return null;
    }
    long value = id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString());
    return value > 0 ? value : null;
  }

  /** Записаното в базата за фиша — шев към {@link BgPaySlipWriteGuard#stored}. */
  protected BgPaySlipWriteGuard.Stored storedSlip(Long id) {
    return BgPaySlipWriteGuard.stored(id);
  }

  /** Само активното дружество — шев към {@link CapabilityGate#requireActiveCompany}. */
  protected void requireActiveCompany(Company company) {
    CapabilityGate.requireActiveCompany(company);
  }

  private void calculate(BgPaySlip slip, boolean persisting) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return;
    }
    if (Boolean.TRUE.equals(slip.getBgIsHistoricalImport())) {
      return;
    }
    // ⚠️ РЪЧНО ПРЕВИШЕНИЕ НАД КАПАЧКАТА — ПРЕДИ ВСИЧКО, ЗАЩОТО ВСИЧКО ОТДОЛУ ЩЕШЕ ДА ГО БРОИ. Ред
    // 201/211/… е изход на резолвера; въведен на ръка, той е или второ броене на същите пари, или
    // основа по ЗКПО нула. Отказ с назован ред, не тихо триене — виж
    // BgSocialExpenseCapResolver#refuseManualDerivedSocialExpense.
    String manualExcess =
        BgSocialExpenseCapResolver.refuseManualDerivedSocialExpense(slip.getBgPaySlipLineList());
    if (manualExcess != null) {
      throw new BgCalculationRefusedException(manualExcess);
    }
    if (BgPaySlipResolver.isRetro(slip)) {
      // ⚠️ КОПИЕ БЕЗ РЕЖИМ НЕ СЕ СМЯТА — нито тихо от живите данни. Оригиналът е отпреди
      // замразяването; човекът трябва да натисне „Попълни от текущите данни“ и да провери.
      String missing = BgPayrollRegime.missingRefusal(slip, retroWhat(slip));
      if (missing != null) {
        throw new BgRegimeMissingException(missing);
      }
      // ⚠️ И ИЗПОЛЗВАНОТО ОТ ТАВАНИТЕ — по същия довод, и ПРЕДИ prepareCorrection: генераторът на
      // превишенията по помощите дели по ЗАМРАЗЕНОТО.
      readyRetroCapUsage(slip);
      // ⚠️ ЗАЛИЧАВАЩОТО КОПИЕ: НУЛИТЕ ИДВАТ ПО УСТРОЙСТВО, БЕЗ ГЕНЕРАТОРИ. Числата му са на
      // оригинала и идентифицират записа, тоест разликата перо по перо е нула; към лицето отива
      // минус цялото оригинално нето (BgPaySlipResolver#retroDelta), а не сметка на генератори.
      if (!Boolean.TRUE.equals(slip.getBgIsDeletion())) {
        String days = carveDaysRefusal(slip);
        if (days != null) {
          throw new BgRegimeMissingException(days);
        }
        String healthOnly = healthOnlyRefusal(slip);
        if (healthOnly != null) {
          throw new BgCalculationRefusedException(healthOnly);
        }
        prepareCorrection(slip);
      }
      freezeCapUsage(slip, persisting);
      recompute(slip);
      slip.setBgCalcFingerprint(persisting ? BgPaySlipFingerprint.of(slip) : null);
      return;
    }
    // ⚠️ РЕЖИМЪТ СЕ ЗАМРАЗЯВА ПЪРВИ: всичко отдолу — генераторите, сборовете, а после и
    // потвърждаването — чете вида осигурен, ТЕЛК, НКИД и останалите от ФИША, не от договора.
    freezeRegime(slip);
    prepare(slip, persisting);
    freezeCapUsage(slip, persisting);
    recompute(slip);
    slip.setBgCalcFingerprint(persisting ? BgPaySlipFingerprint.of(slip) : null);
  }

  /**
   * ПРОИЗВОДНИТЕ ПЕРА НА КОРИГИРАЩОТО КОПИЕ — от замразеното, по параметрите на ПЕРИОДА.
   *
   * <p><b>Какво се смята наново и какво не.</b> Основните начисления (заплата, клас, болничен по
   * чл. 40 ал. 5, отпуск по чл. 177, обезщетения) са ЗАМРАЗЕНИ: те са фактите на онзи месец или
   * новият факт, който операторът е нанесъл. Наново се смятат само ПРОИЗВОДНИТЕ — карвът на ръчните
   * постоянни пера 180/181 от въведената сума, здравната вноска 516 по ЗЗО чл. 40 ал. 1 т. 5 за
   * дните ДОО, вноските 5xx и данъкът 700. Копие с 5xx и 700 на ОРИГИНАЛА би носило перо 195 в
   * осигурителния доход и вноски без него.
   *
   * <p><b>⚠️ РЕДЪТ Е ТОЗИ НА {@link #prepare}, И НЕ Е СВОБОДЕН.</b> Карвът мени облагаемите и
   * осигурителните пера, вноските се смятат върху пълния осигурителен доход, а данъкът — след
   * личните вноски.
   *
   * <p><b>⚠️ „САМО ЗДРАВНО“ НЕ СЕ СМЯТА НАНОВО.</b> Поводът и платецът му (правилото на вида
   * неплатен отпуск) не са замразени никъде, тоест от замразеното то не може да се изведе. Остава
   * така, както е копирано, а {@link #healthOnlyRefusal} спира копието, ако дните, които го движат,
   * са се сменили.
   */
  protected void prepareCorrection(BgPaySlip copy) {
    BgPaySlipVerdicts.reset(copy);
    BgPayrollInput input = payrollInput(copy);
    earningsService().carveOnCorrection(copy, input);
    BgSickLeaveOutcome healthAbsence = sickLeaveService().healthAbsenceOnCorrection(copy, input);
    if (healthAbsence != null) {
      new BgPaySlipVerdicts()
          .addSickLeave(BgPaySlipVerdicts.STEP_SICK_LEAVE, Collections.singletonList(healthAbsence))
          .appendInto(copy);
    }
    // ⚠️ ПРЕВИШЕНИЕТО НА ПОМОЩТА — ПРОИЗВОДНО, КАТО ВНОСКИТЕ: по ЗАМРАЗЕНОТО използвано от тавана.
    generateSocialExpenseExcess(copy);
    generateContributions(copy);
    generateIncomeTax(copy);
    BgPaySlipVerdicts.seal(copy);
  }

  /**
   * ОТКАЗЪТ ЗА ЛИПСВАЩИ ДНИ НА КАРВА, или {@code null}.
   *
   * <p>Копие на оригинал отпреди замразяването на дните, чиито кофи по т. 16 НЕ са празни, не знае
   * колко от дните са били по чл. 177 и колко неплатени — а точно тях чете карвът на 180/181. Ако
   * копието носи такова перо, то не се смята по днешните заявки тихо: отказът назовава двете числа
   * и сочи „Попълни от текущите данни“.
   *
   * <p>⚠️ Без перо 180/181 дните не се четат от нищо, тоест няма какво да се откаже.
   */
  protected String carveDaysRefusal(BgPaySlip copy) {
    if (!carveDaysMissing(copy) || !BgEarningsService.hasManualPermanentLines(copy)) {
      return null;
    }
    return "Дните на карва на "
        + retroWhat(copy)
        + " не са замразени — оригиналът е отпреди замразяването им, а месецът има отсъствия."
        + " Липсват: дни платен отпуск по чл. 177, неплатени дни, които Д1 вади от т. 16.1. По тях"
        + " се карвират постоянните пера (180/181), а днешните заявки за отпуск не се четат тихо за"
        + " минал месец. Натиснете „Попълни от текущите данни“, проверете стойностите и пресметнете"
        + " отново.";
  }

  /** Липсват ли дните на карва — коригиращо копие, незамразени и НЕ доказано нулеви. */
  protected boolean carveDaysMissing(BgPaySlip copy) {
    return copy != null
        && Boolean.TRUE.equals(copy.getBgIsCorrection())
        && !BgFrozenPayrollInput.carveDaysFrozen(copy)
        && !BgFrozenPayrollInput.carveDaysAreZero(copy);
  }

  /**
   * ОТКАЗЪТ ЗА ОСТАРЯЛО „САМО ЗДРАВНО“, или {@code null}.
   *
   * <p>Вноската по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ остава в копието такава, каквато е копирана — от
   * замразеното тя не може да се изведе. Но ако дните, които я движат (неплатеният отпуск в т. 16.3
   * и т. 16.5 и дните извън осигуряването), са различни от оригинала, а перото е непипнато, сумата
   * е тихо остаряла. Тогава копието отказва с назована причина; перо, поправено на ръка (сума или
   * база), е решение на човека и минава.
   */
  protected String healthOnlyRefusal(BgPaySlip copy) {
    if (copy == null || !Boolean.TRUE.equals(copy.getBgIsCorrection())) {
      return null;
    }
    BgPaySlip original = copy.getBgCorrectionOf();
    if (original == null) {
      return null;
    }
    List<String> changed = new ArrayList<>();
    dayChange(changed, "т. 16.3", original.getBgLeaveDays163(), copy.getBgLeaveDays163());
    dayChange(changed, "т. 16.5", original.getBgLeaveDays165(), copy.getBgLeaveDays165());
    dayChange(
        changed,
        "извън осигуряването",
        original.getBgLeaveDaysOutside(),
        copy.getBgLeaveDaysOutside());
    if (changed.isEmpty()) {
      return null;
    }
    for (BgPaySlipLine line : safe(copy.getBgPaySlipLineList())) {
      if (!BgPaySlipResolver.isOwnPeriod(line)
          || !BgD1ContributionAggregator.BASE_HEALTH_ONLY.equals(
              BgD1ContributionAggregator.healthBaseOf(line))
          || !untouchedFrom(original, line)) {
        continue;
      }
      return "„Само здравно“ (ЗЗО чл. 40 ал. 1 т. 1 б. „б“) е преписано от оригинала ("
          + BgPaySlipFingerprint.amountText(line.getBgAmount())
          + "), а дните, които го движат, са сменени: "
          + String.join("; ", changed)
          + ". От замразеното то не се смята наново — поводът и платецът му не са замразени."
          + " Поправете перото на ръка (сума и база) и пресметнете отново.";
    }
    return null;
  }

  private static void dayChange(List<String> out, String label, BigDecimal was, BigDecimal is) {
    if (was.compareTo(is) != 0) {
      out.add(
          label
              + " "
              + BgPaySlipFingerprint.amountText(was)
              + " → "
              + BgPaySlipFingerprint.amountText(is));
    }
  }

  /** Перото на копието е същото като на оригинала — същият знак и база, същата сума и основа. */
  private static boolean untouchedFrom(BgPaySlip original, BgPaySlipLine line) {
    for (BgPaySlipLine o : safe(original.getBgPaySlipLineList())) {
      if (BgPaySlipResolver.isOwnPeriod(o)
          && line.getBgSign() != null
          && line.getBgSign().equals(o.getBgSign())
          && BgD1ContributionAggregator.healthBaseOf(line)
              .equals(BgD1ContributionAggregator.healthBaseOf(o))
          && line.getBgAmount().compareTo(o.getBgAmount()) == 0
          && line.getBgQuantity().compareTo(o.getBgQuantity()) == 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * ЗАМРАЗЯВА РЕЖИМА НА ОСИГУРЯВАНЕ на редовен фиш в чернова — от договора, служителя, фирмата или
   * регистрацията, такива каквито са СЕГА. Единственото място, където живите данни на режима се
   * четат за пресмятане.
   *
   * <p>⚠️ Фиш без носител (нито договор, нито регистрация) не получава режим: отказът „няма
   * носител“ вече съществува отдолу и е по-точен от „липсва режим“.
   */
  protected void freezeRegime(BgPaySlip slip) {
    BgPayrollRegime live = liveRegime(slip);
    if (live != null) {
      live.writeTo(slip, BgPayrollRegime.CALCULATED, LocalDateTime.now());
    }
  }

  /**
   * Режимът, прочетен ЖИВО, или {@code null} без носител. Шев.
   *
   * <p>⚠️ {@code new}, а не {@code Beans.get}: извличането чете само заредените договор, служител,
   * фирма и регистрация — без база и без контейнер, — тоест и тестовете без Guice минават през
   * истинския код.
   */
  protected BgPayrollRegime liveRegime(BgPaySlip slip) {
    LocalDate date = slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
    BgPayrollInputBuilder extractor = new BgPayrollInputBuilder();
    if (BgSelfInsuredRules.isSelfInsured(slip)) {
      return extractor.liveRegime(slip.getBgSelfInsuredRegistration(), date);
    }
    if (slip.getEmploymentContract() == null) {
      return null;
    }
    return extractor.liveRegime(slip.getEmploymentContract(), date);
  }

  /**
   * „ПОПЪЛНИ ОТ ТЕКУЩИТЕ ДАННИ“ — изричното действие на човек за копие на заварен фиш (отпреди
   * замразяването). Чете режима живо, записва го в копието и оставя следа в одита: поле по поле,
   * кой и кога.
   *
   * <p>⚠️ САМО ЗА КОПИЕ БЕЗ РЕЖИМ. Копие с наследен режим се поправя поле по поле — и всяка промяна
   * пак остава в одита, — а не се презаписва изцяло с днешния договор.
   */
  public void fillRegimeFromCurrent(BgPaySlip copy, User user) throws AxelorException {
    fillFromCurrent(copy, user, BgPaySlipRegimeAudit.FROM_CURRENT);
  }

  /**
   * ПОПЪЛВА ЛИПСВАЩОТО В КОПИЕТО ОТ ТЕКУЩИТЕ ДАННИ — режима и/или дните на карва.
   *
   * <p>⚠️ Изрично действие на човек: бутонът „Попълни от текущите данни“ или потвърденият план на
   * „Отрази пристигнал болничен“. Затова живото се чете ТУК, а следата казва и повода ({@code how})
   * — „от текущите данни“ или „при отразяване на болничен лист № …“.
   *
   * <p>⚠️ Попълва се само ЛИПСВАЩОТО. Наследен режим не се презаписва с днешния договор (поправя се
   * поле по поле, с одит), а доказано нулевите дни на карва (празни кофи) не се питат.
   */
  public void fillFromCurrent(BgPaySlip copy, User user, String how) throws AxelorException {
    if (!BgPaySlipResolver.isRetro(copy)
        || !BgPaySlipResolver.DRAFT.equals(copy.getStatusSelect())) {
      refuse("„Попълни от текущите данни“ е само за коригиращо или заличаващо копие в чернова.");
    }
    boolean regimeMissing = !BgPayrollRegime.isFrozen(copy);
    boolean daysMissing = carveDaysMissing(copy);
    // ⚠️ Чистият изход преди услугата: копие без перо от 2xx не пита Guice (тестовете без
    // контейнер).
    boolean capMissing =
        copy.getBgCapUsageFrozenAt() == null
            && hasSocialExpenseLines(copy)
            && socialExpenseService().missingOnRetro(copy) != null;
    if (!regimeMissing && !daysMissing && !capMissing) {
      refuse(
          "Режимът и дните на карва на копието вече са попълнени — променете полетата на режима"
              + " поотделно; всяка промяна се записва в одита.");
    }
    LocalDateTime now = LocalDateTime.now();
    if (regimeMissing) {
      BgPayrollRegime live = liveRegime(copy);
      if (live == null) {
        refuse("Копието няма нито договор, нито регистрация — няма откъде да се попълни режимът.");
      }
      live.writeTo(copy, BgPayrollRegime.FILLED, now);
      BgPaySlipRegimeAudit.recordFilled(copy, user, now, how);
    }
    if (daysMissing) {
      BgPayrollInput live = assembledInput(copy);
      boolean readable =
          live != null && live.art177LeaveDays != null && live.unpaidLeaveDaysInD1 != null;
      if (readable) {
        copy.setBgArt177LeaveDays(live.art177LeaveDays);
        copy.setBgUnpaidLeaveDaysInD1(live.unpaidLeaveDaysInD1);
        copy.setBgCarveDaysFrozenAt(now);
        BgPaySlipRegimeAudit.recordFilledDays(copy, user, now, how);
      } else if ((!regimeMissing && !capMissing)
          || BgEarningsService.hasManualPermanentLines(copy)) {
        // ⚠️ Отказ само когато дните ТРЯБВАТ (180/181 ги чете) или няма какво друго да се
        // попълни: същото условие като carveDaysRefusal. Без постоянни пера те не се четат от
        // нищо, и попълненият режим не бива да пада заради тях.
        refuse(
            "Дните на карва не могат да се прочетат от текущите заявки за отпуск"
                + (live == null || live.leaveDaysRefusal == null
                    ? "."
                    : ": " + live.leaveDaysRefusal));
      }
    }
    if (capMissing) {
      // ⚠️ СЛЕД режима и дните: делението на копието чете и двете, а редът тук е само входът му.
      String filled = socialExpenseService().fillMissing(copy, now);
      if (filled != null) {
        BgPaySlipRegimeAudit.recordFilledCapUsage(copy, user, now, how, filled);
      }
    }
  }

  /** Има ли фишът поне едно перо от 2xx. Чисто — номерът е копиран на перото. */
  private static boolean hasSocialExpenseLines(BgPaySlip slip) {
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      if (BgSocialExpenseCapResolver.isSocialExpenseLine(line)) {
        return true;
      }
    }
    return false;
  }

  /**
   * КАКВО ЩЕ СЕ ПОПЪЛНИ ОТ ТЕКУЩИТЕ ДАННИ при копие на {@code original} — по ред на поле, или
   * празен списък, ако нищо не липсва. За плана на „Отрази пристигнал болничен“ (3А): човекът вижда
   * стойностите ПРЕДИ да натисне OK.
   */
  public List<String> fillPreview(BgPaySlip original) {
    List<String> out = new ArrayList<>();
    if (original == null) {
      return out;
    }
    BgPayrollRegime live = BgPayrollRegime.isFrozen(original) ? null : liveRegime(original);
    if (live != null) {
      BgPaySlip preview = new BgPaySlip();
      preview.setBgSelfInsuredRegistration(original.getBgSelfInsuredRegistration());
      live.writeTo(preview, BgPayrollRegime.FILLED, null);
      for (BgPayrollRegime.Field f : BgPayrollRegime.fieldsFor(original)) {
        out.add(f.title + ": " + f.textOf(preview));
      }
    }
    if (!BgFrozenPayrollInput.carveDaysFrozen(original)
        && !BgFrozenPayrollInput.carveDaysAreZero(original)) {
      BgPayrollInput in = assembledInput(original);
      if (in != null && in.art177LeaveDays != null && in.unpaidLeaveDaysInD1 != null) {
        out.add(
            BgPaySlipRegimeAudit.ART177_DAYS
                + ": "
                + BgPaySlipRegimeAudit.days(in.art177LeaveDays));
        out.add(
            BgPaySlipRegimeAudit.UNPAID_D1_DAYS
                + ": "
                + BgPaySlipRegimeAudit.days(in.unpaidLeaveDaysInD1));
      }
    }
    return out;
  }

  private static String retroWhat(BgPaySlip slip) {
    return Boolean.TRUE.equals(slip.getBgIsDeletion())
        ? "заличаващото копие"
        : "коригиращото копие";
  }

  // ── сумите ──────────────────────────────────────────────────────────────

  /**
   * Преизчислява всички суми от перата. ⚠️ Само в чернова — потвърденият фиш е замразен.
   *
   * <p><b>⚠️ НЕ ВИКАЙ ОТВЪН — ПОЛЗВАЙ {@link #calculate}.</b> Този метод само СУМИРА: не пуска
   * генераторите, не карвира ръчните постоянни пера и не пише отпечатък. Остава public единствено
   * заради {@code BgSickNoteArrivalService}, който го вика върху коригиращото копие.
   */
  public void recompute(BgPaySlip slip) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return;
    }
    Totals totals = totalsOf(slip);
    slip.setBgGrossTotal(totals.gross);
    slip.setBgInsuranceIncome(totals.insuranceIncome);
    slip.setBgTaxBase(totals.taxBase);
    slip.setBgTaxAmount(totals.taxAmount);
    slip.setBgNetTotal(totals.net);
    slip.setBgEmployerCost(totals.employerCost);
    slip.setBgSocialExpenseTaxBase(totals.socialExpenseTaxBase);
  }

  /** Седемте заглавни суми, сметнати от перата — без да се пише нищо. */
  protected static final class Totals {
    final BigDecimal gross;
    final BigDecimal insuranceIncome;
    final BigDecimal taxBase;
    final BigDecimal taxAmount;
    final BigDecimal net;
    final BigDecimal employerCost;
    final BigDecimal socialExpenseTaxBase;

    Totals(
        BigDecimal gross,
        BigDecimal insuranceIncome,
        BigDecimal taxBase,
        BigDecimal taxAmount,
        BigDecimal net,
        BigDecimal employerCost,
        BigDecimal socialExpenseTaxBase) {
      this.gross = gross;
      this.insuranceIncome = insuranceIncome;
      this.taxBase = taxBase;
      this.taxAmount = taxAmount;
      this.net = net;
      this.employerCost = employerCost;
      this.socialExpenseTaxBase = socialExpenseTaxBase;
    }
  }

  /**
   * ЗАГЛАВНИТЕ СУМИ ОТ ПЕРАТА — ЕДНО място за двамата читатели: {@link #recompute} ги записва,
   * {@link #confirm} ги сверява със записаните. Две отделни сметки щяха да са свободни да се
   * разминат и сверяването щеше да спира верни фишове.
   */
  protected Totals totalsOf(BgPaySlip slip) {
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();
    BigDecimal gross = BgPaySlipResolver.grossTotal(lines);
    // ⚠️ НЕ сборът на перата направо: при координация по КСО чл. 6 ал. 11 осигурява се само
    // остатъкът до тавана, а перата носят пълния придобит доход. Виж insuranceIncomeHeader.
    BigDecimal insuranceIncome = insuranceIncomeHeader(slip);
    // ⚠️ ОБЛЕКЧЕНИЕТО ВЛИЗА ТУК, В ОСНОВАТА, А НЕ В СТАВКАТА. ЗДДФЛ чл. 42: ал. 2 дава основата
    // (облагаеми минус лични вноски), ал. 3 я НАМАЛЯВА с облекчението и чак ал. 4 умножава по
    // ставката. Приложено след ставката, облекчението би отнело една десета от себе си.
    // ⚠️ СЪЩОТО ЧИСЛО отива и на генератора през generateIncomeTax — инак перото pl_dod и
    // заглавната сума биха се разминали и refuseTaxMismatch щеше да блокира ВЕРЕН фиш.
    // ⚠️ И РАЗКЛОНЕНИЕТО ПО ЧЛ. 43 Е ТУК СЪЩО, ЗАЩОТО ЗАГЛАВНАТА ОСНОВА И ПЕРОТО СА ДВАМА ЧИТАТЕЛЯ
    // НА ЕДНО ПРАВИЛО. Разклонен само генераторът, перото щеше да носи 150,00, а заглавната част —
    // 200,00, и refuseTaxMismatch щеше да блокира ВЕРЕН фиш с необяснима разлика от 50,00.
    BigDecimal taxBase = taxBaseHeader(slip, lines);
    // ⚠️ ДВА ДАНЪКА, ЕДНА ЗАГЛАВНА СУМА. За събирача на диворастящи гъби и плодове дължимото НЕ е
    // процент върху основата, а едно число на месец (чл. 41а ал. 1) — виж alternativeTax.
    // Основата ОСТАВА сметната и записана: тя е факт за дохода и при двата данъка, а празна щеше да
    // скрие, че фишът изобщо е носил облагаеми начисления.
    BigDecimal taxAmount =
        hasAlternativeTax(slip)
            ? alternativeTax(slip)
            : BgPaySlipResolver.taxAmount(taxBase, incomeTaxRate(slip));
    // ⚠️ ШЕСТОТО ЧИСЛО НЕ Е ЧАСТ ОТ НЕТОТО И НЕ ВЛИЗА В НИТО ЕДИН ОТ ГОРНИТЕ ПЕТ СБОРА. Месечната
    // ЗКПО основа е задължение на ПРЕДПРИЯТИЕТО (данък върху разходите), не доход или удръжка на
    // лицето. Записва се тук, защото след затварянето на месеца входът ѝ вече не е наличен — виж
    // довода при полето в BgPaySlip.xml.
    return new Totals(
        gross,
        insuranceIncome,
        taxBase,
        taxAmount,
        BgPaySlipResolver.netTotal(lines),
        BgPaySlipResolver.employerCost(lines),
        socialExpenseTaxBase(slip));
  }

  /**
   * МЕСЕЧНАТА ЗКПО ОСНОВА за социалните разходи — наименуван шев, закачен за {@link #recompute}.
   *
   * <p><b>⚠️ ЧИСТИЯТ ИЗХОД Е ПРЕДИ ВСЯКА ЗАЯВКА, И ТОВА НЕ Е ОПТИМИЗАЦИЯ.</b> Фиш без нито едно
   * перо от 2xx получава нула, без да е поискано каквото и да е от Guice или от Hibernate: {@code
   * BgSocialExpenseCapResolver#isSocialExpenseLine} чете номера, КОПИРАН на самото перо. Оттам
   * следва, че {@code recompute()} остава извикваем от тестовете без контейнер, както беше — а те
   * са стотици. Написано с {@code Beans.get(...)} на първия ред, новият шев щеше да повали всеки от
   * тях с {@code IllegalStateException}, и то по причина, която няма нищо общо с това, което мерят.
   *
   * <p>⚠️ Шевът е СВОЙ метод, а не три реда вътре в {@code recompute()}, по същия довод като {@link
   * #generateEarnings}: услугата отдолу пипа базата, а подмяната ѝ в тест трябва да е един метод.
   */
  protected BigDecimal socialExpenseTaxBase(BgPaySlip slip) {
    if (slip == null || slip.getBgPaySlipLineList() == null) {
      return BigDecimal.ZERO;
    }
    boolean any = false;
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgSocialExpenseCapResolver.isSocialExpenseLine(line)) {
        any = true;
        break;
      }
    }
    if (!any) {
      return BigDecimal.ZERO;
    }
    return socialExpenseService().monthlyTaxBase(slip);
  }

  /**
   * ЗАМРАЗЯВА ИЗПОЛЗВАНОТО ОТ ТАВАНИТЕ на социалните разходи — наименуван шев, закачен за {@link
   * #calculate} СЛЕД генераторите (перата са окончателни) и ПРЕДИ сборовете.
   *
   * <p><b>⚠️ ЧИСТИЯТ ИЗХОД Е ПРЕДИ ВСЯКА ЗАЯВКА</b>, по довода при {@link #socialExpenseTaxBase}:
   * фиш без перо от 2xx само сваля заварените редове и слага датата — без Guice и без Hibernate.
   *
   * <p><b>⚠️ ДАТАТА СЕ СЛАГА И НА ФИШ БЕЗ 2xx.</b> Тя казва „пресметнат след замразяването“, а не
   * „има редове“: копие на такъв фиш, на което операторът ДОБАВИ помощ, иначе не би различило
   * „оригиналът не е ползвал тавана“ (нула, вярно) от „оригиналът е отпреди замразяването“
   * (неизвестно).
   *
   * <p><b>Ретро-копие:</b> наследеният ред е готов отпреди ({@link #readyRetroCapUsage}); тук се
   * смята наново само освободеното. Пресмятане на екрана ({@code persisting = false}) не пише нищо:
   * редовният фиш смята делението живо, а редовете се пишат с „Преизчисли“.
   */
  protected void freezeCapUsage(BgPaySlip slip, boolean persisting) {
    boolean retro = BgPaySlipResolver.isRetro(slip);
    if (!hasSocialExpenseLines(slip)) {
      if (persisting && !retro) {
        if (slip.getBgCapUsageList() != null) {
          slip.getBgCapUsageList().clear();
        }
        slip.setBgCapUsageFrozenAt(LocalDateTime.now());
      }
      return;
    }
    if (persisting) {
      socialExpenseService().freeze(slip, LocalDateTime.now());
    }
  }

  /**
   * ИЗПОЛЗВАНОТО НА РЕТРО-КОПИЕТО — ГОТОВО ЛИ Е ЗА ДЕЛЕНИЕТО. Вика се ПРЕДИ {@link
   * #prepareCorrection}: генераторът на превишенията по помощите чете замразеното.
   *
   * <p>Липсващ наследен ред при оригинал ОТПРЕДИ замразяването е ОТКАЗ — живото не се чете тихо за
   * минал месец (същото като режима). При оригинал СЛЕД замразяването, но без перо по тази капачка,
   * използваното преди онзи месец никога не е било нужно: не е „неизвестно“, а ненаписано — смята
   * се от потвърдените фишове без веригата на копието и се вижда в таблицата на формата.
   */
  protected void readyRetroCapUsage(BgPaySlip slip) {
    if (!BgPaySlipResolver.isRetro(slip) || !hasSocialExpenseLines(slip)) {
      return;
    }
    if (slip.getBgCapUsageFrozenAt() == null) {
      String missing = socialExpenseService().missingOnRetro(slip);
      if (missing != null) {
        throw new BgRegimeMissingException(missing);
      }
      return;
    }
    socialExpenseService().fillMissing(slip, null);
  }

  /**
   * ⚠️ ПАЗАЧЪТ НА ЗАМРАЗЕНОТО ИЗПОЛЗВАНО ОТ ТАВАНИТЕ. Причина за отказ, или {@code null} — виж
   * {@link BgSocialExpenseService#usageRefusal}. Чистият изход е същият като при {@link
   * #socialExpenseRefusal}.
   */
  protected String capUsageRefusal(BgPaySlip slip) {
    if (slip == null || Boolean.TRUE.equals(slip.getBgIsHistoricalImport())) {
      return null;
    }
    return hasSocialExpenseLines(slip) ? socialExpenseService().usageRefusal(slip) : null;
  }

  /**
   * ⚠️ ПАЗАЧЪТ НА МЕСЕЧНАТА ЗКПО ОСНОВА. Причина за отказ, или {@code null}.
   *
   * <p><b>Защо ПАЗАЧ, а не тиха нула.</b> {@link BgSocialExpenseService#monthlyTaxBase} НЕ брои
   * групите, за които резолверът е отказал — то би значело „нула за тази група“, а нулата е ВЯРНО
   * число за месец без такъв разход. Двете състояния са неразличими на фиша: и в двата случая
   * заглавната сума изглежда пресметната, защото останалите групи наистина са пресметнати.
   *
   * <p>Затова отказът е ТУК и НАЗОВАВА какво липсва. И той не блокира фиш без перо от 2xx: такъв не
   * стига до услугата изобщо (виж чистия изход в {@link #socialExpenseTaxBase}).
   *
   * <p>⚠️ ПОСЛЕДЕН по ред, СЛЕД {@code garnishmentRefusal}: този отказ е за задължение на
   * ПРЕДПРИЯТИЕТО, а всички преди него са за нетото на лицето. Изпреварвайки ги, той щеше да праща
   * оператора да зарежда капачки, докато фишът има по-близък проблем със самите пари.
   */
  protected String socialExpenseRefusal(BgPaySlip slip) {
    if (slip == null || slip.getBgPaySlipLineList() == null) {
      return null;
    }
    boolean any = false;
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgSocialExpenseCapResolver.isSocialExpenseLine(line)) {
        any = true;
        break;
      }
    }
    if (!any) {
      return null;
    }
    List<String> refusals = socialExpenseService().refusals(slip);
    if (refusals.isEmpty()) {
      return null;
    }
    return "Социалните разходи не могат да се разделят: "
        + String.join(" · ", refusals)
        + " Месечната данъчна основа по ЗКПО чл. 213/214 щеше да излезе ПО-МАЛКА от вярната.";
  }

  // ── непресметнатият фиш ───────────────────────────────────────────────

  /**
   * ⚠️ ФИШЪТ НЕ Е ПРЕСМЕТНАТ ТАКЪВ, КАКЪВТО СЕ ПОТВЪРЖДАВА. Причина за отказ, или {@code null}.
   *
   * <p>Три проверки, всяка със своя довод:
   *
   * <ul>
   *   <li><b>(а) отпечатък</b> — фишът е пресметнат по {@link #calculate} и нищо не е пипано
   *       оттогава. Празен отпечатък значи „не е пресмятан“, различен — какво точно е пипано.
   *   <li><b>(б) свежи сборове</b> — заглавните суми са ТОЧНО сборовете от {@link #totalsOf}, със
   *       същите функции като {@link #recompute}.
   *   <li><b>(в) базата на вноските</b> — нормалните 5xx стоят на ЕДНА база и тя не е под
   *       осигурителния доход от заглавието, освен ако е на тавана. Подът (МОД) само вдига базата,
   *       тоест по-ниска база значи вноски от друго пресмятане.
   * </ul>
   *
   * <p><b>⚠️ ИСТОРИЧЕСКИЯТ ВНОС СЕ ПРОПУСКА ИЗЦЯЛО.</b> Сумите му са от Microinvest („Общо
   * начисления“, „Сума за получаване“) и законно НЕ са сборове на перата, а пресметнат по този път
   * той никога не е бил. Пазачът тук би го обявил за дефект; контролерът така или иначе не пуска
   * преходи по него.
   *
   * <p><b>⚠️ КОРИГИРАЩОТО И ЗАЛИЧАВАЩОТО КОПИЕ — С (а), (б) И (в).</b> calculate() пуска
   * производните пера и пише отпечатък и на копието; заличаващото го получава още при раждането си,
   * защото генератори върху него не вървят по устройство.
   */
  protected String staleSlipRefusal(BgPaySlip slip) {
    if (Boolean.TRUE.equals(slip.getBgIsHistoricalImport())) {
      return null;
    }
    Totals fresh = totalsOf(slip);
    List<String> totals = totalsMismatch(slip, fresh);
    String base = contributionBaseMismatch(slip, fresh.insuranceIncome);

    // ⚠️ ЕДИН ТЕКСТ ЗА ФИША И ЗА КОПИЕТО, с различен подлог: „Фишът … пресмятан“, „Коригиращото
    // копие … пресмятано“.
    boolean retro = BgPaySlipResolver.isRetro(slip);
    String subject =
        !retro
            ? "Фишът"
            : Boolean.TRUE.equals(slip.getBgIsDeletion())
                ? "Заличаващото копие"
                : "Коригиращото копие";
    String ending = retro ? "о" : "";
    List<String> parts = new ArrayList<>();
    String stored = slip.getBgCalcFingerprint();
    if (stored == null || stored.trim().isEmpty()) {
      // ⚠️ „ОТ «ПРЕИЗЧИСЛИ»“, А НЕ „ОТ ПОСЛЕДНОТО ПРЕСМЯТАНЕ“: след onChange операторът е
      // видял пресметнати числа, а отпечатък няма — пресмятането на екрана не записва нищо.
      parts.add(
          subject
              + " не е пресмятан"
              + ending
              + " — липсва отпечатък от „Преизчисли“ (промяната по формата само показва"
              + " числата, без да ги записва)");
    } else {
      List<String> changes = BgPaySlipFingerprint.changes(stored, slip);
      if (!changes.isEmpty()) {
        parts.add(
            subject
                + " е променян"
                + ending
                + " след последното пресмятане: "
                + String.join("; ", changes));
      }
    }
    if (!totals.isEmpty()) {
      parts.add("Сумите в заглавието не съвпадат с перата: " + String.join("; ", totals));
    }
    if (base != null) {
      parts.add("Вноските не съответстват на осигурителния доход: " + base);
    }
    if (parts.isEmpty()) {
      return null;
    }
    return String.join(". ", parts)
        + ". Натиснете „Преизчисли“, прегледайте сумите и потвърдете отново.";
  }

  /** (б) Записаните заглавни суми срещу свежите — по едно изречение на разминаване. */
  protected List<String> totalsMismatch(BgPaySlip slip, Totals fresh) {
    List<String> out = new ArrayList<>();
    compareTotal(out, "бруто", slip.getBgGrossTotal(), fresh.gross);
    compareTotal(out, "осигурителен доход", slip.getBgInsuranceIncome(), fresh.insuranceIncome);
    compareTotal(out, "данъчна основа", slip.getBgTaxBase(), fresh.taxBase);
    compareTotal(out, "данък", slip.getBgTaxAmount(), fresh.taxAmount);
    compareTotal(out, "нето", slip.getBgNetTotal(), fresh.net);
    compareTotal(out, "разход за работодателя", slip.getBgEmployerCost(), fresh.employerCost);
    compareTotal(
        out,
        "ЗКПО основа за социалните разходи",
        slip.getBgSocialExpenseTaxBase(),
        fresh.socialExpenseTaxBase);
    return out;
  }

  private static void compareTotal(
      List<String> out, String label, BigDecimal stored, BigDecimal fresh) {
    BigDecimal a = stored == null ? BigDecimal.ZERO : stored;
    BigDecimal b = fresh == null ? BigDecimal.ZERO : fresh;
    if (a.compareTo(b) != 0) {
      out.add(
          label
              + " — в заглавието "
              + BgPaySlipFingerprint.amountText(a)
              + ", по перата "
              + BgPaySlipFingerprint.amountText(b));
    }
  }

  /**
   * (в) Базата на нормалните вноски срещу осигурителния доход. Причина или {@code null}.
   *
   * <p>Базата е {@code bgQuantity} на перото: генераторът я пише заедно със сумата и ставката (виж
   * {@code BgContributionLineService#upsert}). Перо без база (0,00) не казва нищо и не се брои.
   *
   * <p>⚠️ САМО НАДОЛУ И С ТАВАНА. Над дохода базата законно стои при МОД-пода, а под него — при
   * тавана. Всичко друго под дохода значи вноски, сметнати върху друг доход. Таванът се пита само
   * тогава, тоест обикновеният фиш не плаща заявка.
   */
  protected String contributionBaseMismatch(BgPaySlip slip, BigDecimal insuranceIncome) {
    // ⚠️ ВНОСКИТЕ ВЪРХУ ОБЕЗЩЕТЕНИЕ ПО ВИД 27 СА СВОЯ БАЗА — обезщетението под общия таван с вид
    // 01 (compensationInsurable), не осигурителният доход по вид 01. Сверяват се със СВОЯ документ.
    // Без таван или календар сверка няма — тогава вече отказва contributionRefusal, с причината.
    for (BgCompensationParts.Carried c : BgCompensationParts.carried(slip)) {
      BigDecimal due;
      try {
        due = compensationInsurable(slip, c);
      } catch (IllegalStateException unknown) {
        continue;
      }
      for (BgPaySlipLine line : c.getContributions()) {
        if (line.getBgQuantity().signum() != 0 && line.getBgQuantity().compareTo(due) != 0) {
          return "вноските върху обезщетението „"
              + compensationReference(c.getDoc())
              + "“ са върху "
              + BgPaySlipFingerprint.amountText(line.getBgQuantity())
              + ", а под общия таван с вид 01 се полагат върху "
              + BgPaySlipFingerprint.amountText(due)
              + " (обезщетението е "
              + BgPaySlipFingerprint.amountText(c.amount())
              + ")";
        }
      }
    }
    List<BigDecimal> bases = new ArrayList<>();
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      if (BgPaySlipResolver.groupOf(line) != BgPaySlipResolver.GROUP_CONTRIBUTIONS
          || BgCompensationParts.isPartLine(line)
          || !BgPaySlipResolver.isOwnPeriod(line)
          || !BgD1ContributionAggregator.BASE_STANDARD.equals(
              BgD1ContributionAggregator.healthBaseOf(line))
          || line.getBgQuantity().signum() == 0) {
        continue;
      }
      BigDecimal q = line.getBgQuantity();
      if (bases.stream().noneMatch(b -> b.compareTo(q) == 0)) {
        bases.add(q);
      }
    }
    if (bases.isEmpty()) {
      return null;
    }
    if (bases.size() > 1) {
      List<String> shown = new ArrayList<>();
      for (BigDecimal b : bases) {
        shown.add(BgPaySlipFingerprint.amountText(b));
      }
      return "вноските стоят на различни бази: " + String.join(", ", shown);
    }
    BigDecimal base = bases.get(0);
    BigDecimal income = insuranceIncome == null ? BigDecimal.ZERO : insuranceIncome;
    if (base.compareTo(income) >= 0) {
      return null;
    }
    BigDecimal cap = contributionCap(slip);
    if (cap != null && base.compareTo(cap) >= 0) {
      return null;
    }
    String uncharged = unchargedRetroLines(slip);
    return (uncharged == null ? "" : uncharged + ": ")
        + "вноските са върху "
        + BgPaySlipFingerprint.amountText(base)
        + ", а осигурителният доход е "
        + BgPaySlipFingerprint.amountText(income);
  }

  /** Таванът на осигурителния доход за периода на фиша, или {@code null}. Шев към базата. */
  protected BigDecimal contributionCap(BgPaySlip slip) {
    return payrollParameter(
        BgInsurableIncomeService.CODE_MAX_INSURABLE_INCOME,
        referenceDate(payrollInput(slip), slip));
  }

  /**
   * На ретро-копие: перата с осигурителен доход, които копието носи В ПОВЕЧЕ от оригинала — тоест
   * онези, които старите вноски не покриват („перо 195 … не е обложено“). {@code null}, ако няма
   * такива или фишът не е копие.
   */
  protected String unchargedRetroLines(BgPaySlip slip) {
    BgPaySlip original =
        Boolean.TRUE.equals(slip.getBgIsDeletion())
            ? slip.getBgDeletionOf()
            : slip.getBgCorrectionOf();
    if (original == null) {
      return null;
    }
    List<String> named = new ArrayList<>();
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      if (!BgPaySlipResolver.CREDIT.equals(line.getBgSign())
          || !Boolean.TRUE.equals(line.getBgIsInsuranceIncome())
          || !BgPaySlipResolver.isOwnPeriod(line)) {
        continue;
      }
      BigDecimal before = BigDecimal.ZERO;
      for (BgPaySlipLine o : safe(original.getBgPaySlipLineList())) {
        if (o.getBgLineNumber().intValue() == line.getBgLineNumber().intValue()
            && BgPaySlipResolver.CREDIT.equals(o.getBgSign())
            && BgPaySlipResolver.isOwnPeriod(o)) {
          before = before.add(o.getBgAmount());
        }
      }
      if (line.getBgAmount().compareTo(before) > 0) {
        named.add("перо " + line.getBgLineNumber() + " „" + line.getName() + "“");
      }
    }
    if (named.isEmpty()) {
      return null;
    }
    return String.join(", ", named) + (named.size() == 1 ? " не е обложено" : " не са обложени");
  }

  // ── преходи ─────────────────────────────────────────────────────────────

  /**
   * Потвърждаване: трите шумни отказа, после замразяване и PAID за обезщетенията.
   *
   * <p>Отказите казват КОЕ липсва, не „грешка“: непокрита календарна година, задействан предпазител
   * на осигуровките, липсващ МОД за периода.
   */
  @Transactional(rollbackOn = {Exception.class})
  public void confirm(BgPaySlip slip) throws AxelorException {
    refuse(BgPaySlipResolver.refuseTransition(slip.getStatusSelect(), BgPaySlipResolver.CONFIRMED));
    // ⚠️ ДВОЙНИЯТ НОСИТЕЛ — ПРЕДИ ВСИЧКО ОСТАНАЛО, ЗАЩОТО ВСИЧКО ОСТАНАЛО ЗАВИСИ ОТ НЕГО. Фиш с
    // договор И регистрация минава всеки следващ пазач: перата ги има, периодът е наред, вноските
    // се смятат — по единия от двата, а декларацията после ще тръгне по другия.
    // ⚠️ ТЕСНИЯТ ВЪПРОС, НЕ ШИРОКИЯТ, и това е решение. Случаят „нито един носител“ вече има кой
    // да го хване — contributions(...) го връща като INPUT_NOT_ASSEMBLED и contributionRefusal го
    // превръща в отказ по-надолу в същия този списък. Питан и тук, той щеше да ИЗПРЕВАРИ заварените
    // пазачи и да смени съобщението за фиш, чийто дефект е съвсем друг. Виж
    // BgSelfInsuredRules#refuseAmbiguousCarrier за измерването.
    refuse(BgSelfInsuredRules.refuseAmbiguousCarrier(slip));
    // ⚠️ ВТОРИ РЕДОВЕН ФИШ — същият отказ като при записа (BgPaySlipManagementRepository), и
    // ПРЕДИ проверките на съдържанието: заварен дубликат (празен, недовършен) трябва да чуе, че е
    // дубликат, а не че няма пера — поправката е да се откаже, не да се попълни.
    refuse(BgEffectivePaySlips.refuseSecondRegular(slip, otherRegular(slip)));
    // ⚠️ ПРЕДИ ПРАЗНИЯ ФИШ, И РЕДЪТ НЕ Е СВОБОДЕН. Анекс насред месеца зану̀лява основната заплата
    // (виж applyBaseSalary), тоест фишът най-често излиза и ПРАЗЕН — и питан втори, този отказ
    // никога не би се чул. Операторът щеше да получава „фишът няма нито едно перо“ за фиш, чиято
    // причина е съвсем друга и се поправя на съвсем друг екран. Същият довод като при МОД-а
    // преди осигуровките по-долу: причината изпреварва следствието.
    // ⚠️ И този отказ НЕ сравнява числа от фиша — той пита договора наново, тоест работи и върху
    // празен фиш. Точно затова има право да стои пред пазача, който иначе е първи.
    refuse(amendmentSalaryRefusal(slip));
    // ⚠️ ПЪРВА, преди всяка друга: останалите сравняват числа и минават празни при нули.
    refuse(BgPaySlipResolver.refuseEmptyPaySlip(slip.getBgPaySlipLineList()));
    // ⚠️ РЪЧНО ПРЕВИШЕНИЕ НАД КАПАЧКАТА — ВЕДНАГА СЛЕД ПРАЗНИЯ ФИШ. Редът изобщо не бива да
    // съществува, тоест всеки пазач по-долу би сравнил числа, в които той вече е преброен. Стара
    // чернова с такъв ред стига дотук, без да е минала през „Преизчисли“ — затова и тук, не само
    // там.
    // Внесената история не се пипа: числата ѝ са от Microinvest.
    if (!Boolean.TRUE.equals(slip.getBgIsHistoricalImport())) {
      refuse(
          BgSocialExpenseCapResolver.refuseManualDerivedSocialExpense(slip.getBgPaySlipLineList()));
    }
    refuse(payrollYearRefusal(slip));
    // ⚠️ ЗАКЛЮЧЕНИЯТ ПЕРИОД СЕ ПИТА ПРЕДИ КАЛЕНДАРА И МОД-А, и редът има значение. Отговорът
    // тук е „грешен ПЪТ“, а следващите два са „липсващи ДАННИ“ — при разменен ред операторът
    // получава „няма зареден производствен календар“ за фиш, който изобщо не бива да се
    // потвърждава по този начин, и тръгва да пълни календара вместо да отвори корекция.
    // ⚠️ СЛЕД payrollYearRefusal, а не преди: то е единственото, което гарантира, че периодът и
    // годината му съществуват, тоест съобщението тук може да назове периода по име.
    // ⚠️ ПЕРИОД В ЗАТВАРЯНЕ СПИРА И КОРЕКЦИЯТА, затова стои ПРЕДИ пазача на заключването —
    // той нея я пуска. Родният Axelor не пази този път: isClosedPeriod брои само
    // TEMPORARILY_CLOSED и CLOSED, важи за счетоводните записи, а този модул не го вика.
    refuse(BgPaySlipResolver.refuseClosingPeriod(slip.getPeriod()));
    refuse(BgPaySlipResolver.refuseLockedPeriod(slip, declaredToNap(slip.getPeriod())));
    // ⚠️ Признаците се проверяват ТУК, в списъка, а не по-късно при смятането на
    // делтата: отказ след замразяването разчита транзакцията да върне статуса, а екранът
    // дотогава вече е показал потвърден фиш.
    refuse(BgPaySlipResolver.refuseBothRetroFlags(slip));
    refuse(BgPaySlipResolver.refuseRetroWithoutOriginal(slip));
    // ⚠️ Едва СЛЕД като препратката е сигурна: пазачът сравнява числата на заличаващия фиш с тези
    // на
    // оригинала, тоест без оригинал нямаше да има с какво.
    refuse(BgPaySlipResolver.refuseAlteredDeletion(slip));
    refuse(calendarRefusal(slip));
    // ⚠️ КОПИЕ БЕЗ РЕЖИМ — СЛЕД ПРОВЕРКИТЕ НА ПЪТЯ, ПРЕДИ ВСЕКИ ПАЗАЧ, КОЙТО ЧЕТЕ РЕЖИМА. Отгоре са
    // „грешен път“ (заключен период, двата признака, липсваща препратка, пипнато заличаване) — те
    // казват повече и не четат режима. Отдолу декларацията за друг платец, МОД, вноските и данъкът
    // го четат; без замразен режим щяха да го прочетат тихо от днешния договор за минал месец.
    // ⚠️ И РЕДОВЕН ФИШ БЕЗ РЕЖИМ — със същия довод: пресметнат преди замразяването, той щеше да
    // бъде потвърден по днешния договор, а не по пресмятането, с което сумите са видени.
    if (BgPaySlipResolver.isRetro(slip)) {
      refuse(BgPayrollRegime.missingRefusal(slip, retroWhat(slip)));
      // ⚠️ И ДНИТЕ НА КАРВА, И „САМО ЗДРАВНО“ — същите откази като в calculate(): отпечатъкът
      // по-долу пази, че копието е пресметнато, а тези два — че е пресметнато от замразеното.
      refuse(carveDaysRefusal(slip));
      refuse(healthOnlyRefusal(slip));
    } else if (!Boolean.TRUE.equals(slip.getBgIsHistoricalImport())) {
      refuse(BgPayrollRegime.unfrozenRefusal(slip));
      // ⚠️ И РЕЖИМ, СМЕНЕН СЛЕД ПРЕСМЯТАНЕТО, НЕ СЕ ПОТВЪРЖДАВА ТИХО. Редовната чернова е за
      // текущия месец: смененият вид, НКПД или ТЕЛК е новото твърдение за него, а сумите са по
      // старото. Копието НЕ се сравнява — там режимът е замразен нарочно (виж driftRefusal).
      refuse(BgPayrollRegime.driftRefusal(slip, liveRegime(slip)));
    }
    // ⚠️ ОТКАЗАН РАЗБИВ НА БОЛНИЧНИЯ — преди вноските и данъка: те четат дните, които разбивът
    // не е записал. Казано отдолу, операторът щеше да види следствието, не причината.
    refuse(sickDaysRefusal(slip));
    // ⚠️ ОТКАЗАН ОТПУСК ПО ЧЛ. 177 — по същия довод: дните на отпуска вече са изрязани от
    // постоянните пера, а перо 165 го няма. Потвърден, фишът щеше да остави отпуска неплатен.
    refuse(art177Refusal(slip));
    // ⚠️ МОД ПРЕДИ ОСИГУРОВКИТЕ, и редът има значение. Блокиращият МОД спира И
    // двете вериги — базата не може да се съставе без него, — тоест при разменен ред операторът
    // получава СЛЕДСТВИЕТО („осигуровките не са изчислени докрай“) вместо ПРИЧИНАТА („няма МОД за
    // периода, а 2026 г. има две полугодия“). Първото го праща да гледа ставки, второто — реда по
    // Приложение № 1.
    // ⚠️ ПРЕДИ МОД-А, И РЕДЪТ Е ЧАСТ ОТ ПРАВИЛОТО. minIncomeRefusal ПРОПУСКА блокиращия МОД, когато
    // има деклариран чужд доход (базата не зависи от него) — тоест той чете отговора на въпроса
    // „координира ли се този месец“. Питан след него, противоречието щеше да се разреши СЛЕД като
    // някой вече е разчитал на едното от двете изявления.
    refuse(BgPaySlipResolver.refuseContradictoryOtherPayerDeclaration(slip));
    refuse(minIncomeRefusal(slip));
    refuse(contributionRefusal(slip));
    refuse(doubleCompensationRefusal(slip));
    // ⚠️ СЛЕД ДВОЙНОТО ПЛАЩАНЕ, а не преди. Двата пазача гледат едни и същи пера, но
    // отговарят на различни въпроси: „това обезщетение вече платено ли е“ е за ДОКУМЕНТА
    // и се поправя с махане на перото, а „по коя база се осигурява“ е за РЕЖИМА и днес
    // няма поправка на екрана. Обърнат редът, операторът получава дългото обяснение за
    // чл. 9 ал. 3 за фиш, чийто истински дефект е дубликат.
    refuse(art9Para3Refusal(slip));
    // ⚠️ НЕПРЕСМЕТНАТИЯТ ФИШ — ТОЧНО ПРЕДИ ДАНЪКА, И РЕДЪТ Е ПРАВИЛОТО. Пусната след него,
    // проверката на данъка щеше да спре фиш с ново перо 185 със съвета „поправете перото за
    // данъка“ — тоест операторът оправя 700 на ръка, данъкът се връзва, а вноските остават без
    // 185. Тук причината („променян след пресмятането“) изпреварва следствието („данъкът не
    // съвпада“).
    // ⚠️ ИЗПОЛЗВАНОТО ОТ ТАВАНИТЕ — ТОЧНО ПРЕДИ НЕПРЕСМЕТНАТИЯ ФИШ, по същия довод: потвърден
    // междувременно фиш на лицето мени и ЗКПО основата, и проверката отдолу би казала само „сумите
    // се разминават“. Тук се назовава ПРИЧИНАТА — кой таван и с колко.
    refuse(capUsageRefusal(slip));
    refuse(staleSlipRefusal(slip));
    refuse(BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()));
    // ⚠️ НЕСЕКВЕСТИРУЕМИЯТ ДОХОД Е ПАЗАЧ ПРИ ПОТВЪРЖДАВАНЕ, НЕ ГЕНЕРАТОР В prepare(). Перата от
    // 8xx са РЪЧНИ — запорът идва от изпълнителен лист, заемът от договор, издръжката от съдебно
    // решение, и нито едно от трите няма източник в модела. Тоест тук няма какво да се сглоби;
    // има какво да се СВЕРИ. Същият идиом като refuseTaxMismatch точно отгоре.
    // ⚠️ ПОСЛЕДЕН по ред: той чете НЕТОТО, тоест всички останали пера трябва вече да са на място.
    refuse(garnishmentRefusal(slip));
    // ⚠️ СОЦИАЛНИТЕ РАЗХОДИ СА ПАЗЕНИ ТУК, А НЕ ЗАПИСВАНИ МЪЛЧАЛИВО КАТО НУЛА. Ако капачката за
    // периода не е заредена, месечната ЗКПО основа излиза ПО-МАЛКА от вярната, и то с вид на
    // пресметната — останалите групи си имат числата. Същият довод като при refuseTaxMismatch и
    // garnishmentRefusal: тук няма какво да се сглоби, има какво да се СВЕРИ.
    refuse(socialExpenseRefusal(slip));

    // ⚠️ РАЗЛИКАТА СЯДА ПРЕДИ ЗАМРАЗЯВАНЕТО, И РЕДЪТ Е ЧАСТ ОТ ПРАВИЛОТО. Ако тя падне, фишът още
    // не е потвърден — тоест няма ретро-документ без пари зад себе си. Обратният ред дава точно
    // това: потвърдена корекция, дължима разлика и нищо, което да се обади.
    if (BgPaySlipResolver.isRetro(slip)) {
      settleRetroDelta(slip);
      // ⚠️ РЕТРО-ФИШЪТ ПОРАЖДА СВОЯТА СТАТИЯ ТУК, А НЕ В pay(), И ТОВА НЕ Е ИЗКЛЮЧЕНИЕ ОТ
      // ПРАВИЛОТО — ТО Е ЕДИНСТВЕНАТА МУ ВРАТА. Коригиращият и заличаващият фиш НЕ се изплащат
      // никога (refusePayRetro отказва на първия ред на pay()), тоест шев, окачен само на
      // изплащането, значи че корекцията не влиза в счетоводството НИКЪДЕ: тя храни коригираща
      // Д1/Д6 и оставя книгите с числата на грешния месец. Потвърждаването е състоянието, което
      // ретро-фишът наистина достига, и е моментът, в който делтата вече е слязла в текущия фиш.
      //
      // ⚠️ И ВЪТРЕ В СЪЩАТА ТРАНЗАКЦИЯ, ПРЕДИ ЗАТВАРЯНЕТО НА ПЕРИОДА. Статията се ражда в
      // STATUS_NEW и не се валидира, тоест състоянието на заплатния период не ѝ пречи; но ако
      // някога стане, редът тук е верният — периодът е още ADJUSTING.
      generatePayrollMove(slip);
    }

    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    save(slip);

    // ⚠️ А периодът се затваря ПОСЛЕДЕН — по същата причина, по която correct() го отваря последен:
    // всеки провал по пътя оставя месеца ADJUSTING, тоест видимо „под корекция“, вместо заключен с
    // недовършена корекция вътре.
    if (BgPaySlipResolver.isRetro(slip)) {
      closeAfterAdjustment(slip.getPeriod());
    }
  }

  /** Изплащане: иска дата и слага потвърдените обезщетения в PAID със същата дата. */
  @Transactional(rollbackOn = {Exception.class})
  public void pay(BgPaySlip slip) throws AxelorException {
    // ⚠️ ПЪРВО, преди дори прехода: „коригиращият фиш не се изплаща“ е отговор за целия документ, а
    // не за състоянието му. Питан по-късно, операторът първо получава „изплаща се само потвърден
    // фиш“ и отива да потвърждава нещо, което после пак ще му бъде отказано.
    refuse(BgPaySlipResolver.refusePayRetro(slip));
    refuse(BgPaySlipResolver.refuseTransition(slip.getStatusSelect(), BgPaySlipResolver.PAID));
    if (slip.getBgPaidDate() == null) {
      refuse("Изплащането иска дата на изплащане.");
    }
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      // ⚠️ САМО ПЕРОТО 9xx: вноските върху обезщетение по вид 27 носят СЪЩАТА препратка
      // (BgCompensationParts#isCompensationLine), а не са второ обезщетение.
      BgCompensation doc =
          BgCompensationParts.isCompensationLine(line) ? line.getBgCompensation() : null;
      if (doc == null) {
        continue;
      }
      doc.setBgPaidDate(slip.getBgPaidDate());
      doc.setStatusSelect(BgCompensationResolver.PAID);
      saveCompensation(doc);
    }
    // ⚠️ СТАТИЯТА СЕ СТРОИ ПРЕДИ СМЯНАТА НА СТАТУСА И ВЪТРЕ В СЪЩАТА ТРАНЗАКЦИЯ. Провалът ѝ връща
    // и изплащането — тоест няма изплатен фиш с половин статия зад себе си. Обратният ред дава
    // точно това: PAID върху фиш, чиято статия е паднала след последния save.
    generatePayrollMove(slip);
    slip.setStatusSelect(BgPaySlipResolver.PAID);
    save(slip);
  }

  /**
   * ШЕВЪТ КЪМ СЧЕТОВОДНАТА СТАТИЯ — наименуван, за да се подменя в тест като останалите генератори.
   *
   * <p><b>⚠️ ЧИСТИЯТ ИЗХОД Е ПРЕДИ ВСЯКО {@code Beans.get}, И ТОВА НЕ Е ОПТИМИЗАЦИЯ.</b> Същият
   * довод като при {@link #socialExpenseTaxBase}: стотиците тестове, които викат {@code pay()} без
   * контейнер, биха паднали с {@code IllegalStateException} по причина, която няма нищо общо с
   * това, което мерят. Тук вратата е ФИРМАТА — тя се чете от вече заредения обектен граф, без нито
   * една заявка, и е {@code null} точно в двата случая, в които статия и без това не се строи: фиш
   * без трудов договор (СОЛ) и фиш, сглобен в паметта от тест.
   *
   * <p>⚠️ ДВЕ ВРАТИ, И ТЕ НЕ СА ЕДНА И СЪЩА. За РЕДОВНИЯ фиш шевът виси на {@code pay()}: датата на
   * статията е датата на ИЗПЛАЩАНЕ, защото по нея се дължат вноските и данъкът. За РЕТРО-фиша
   * (коригиращ или заличаващ) виси на {@code confirm()} — той не се изплаща никога, тоест другата
   * врата за него не се отваря, а датата му е краят на СВОЯ период, тоест коригираният месец. Двете
   * извиквания са едно и също, защото периодо-осъзнатостта живее в {@code
   * BgPaySlipMoveService#dateOf}, а не в мястото на извикването.
   */
  protected void generatePayrollMove(BgPaySlip slip) throws AxelorException {
    if (slip == null
        || slip.getBgPayrollMove() != null
        || slip.getEmploymentContract() == null
        || slip.getEmploymentContract().getPayCompany() == null) {
      return;
    }
    Beans.get(BgPaySlipMoveService.class).generate(slip);
  }

  /** Отказ с причината, която фишът вече носи — вратата за заварените повикващи. */
  @Transactional(rollbackOn = {Exception.class})
  public void cancel(BgPaySlip slip) throws AxelorException {
    cancel(slip, slip.getBgCancelReason());
  }

  /**
   * Отказ: единственият изход от потвърден фиш — и единият от двата изхода на коригиращото копие.
   *
   * <p><b>⚠️ ПРИЧИНАТА СЕ ПОДАВА, А НЕ СЕ ЧЕТЕ ОТ ФИША.</b> Формата не може да я въведе: полето се
   * показва едва след отказа. Затова идва от входа на бутона и се записва в {@code bgCancelReason}
   * ТУК, след всички проверки, в същата транзакция с отказа и с връщането на периода в CLOSED.
   * Провал по средата — нито причината, нито статусът, нито периодът.
   */
  @Transactional(rollbackOn = {Exception.class})
  public void cancel(BgPaySlip slip, String reason) throws AxelorException {
    refuse(BgPaySlipResolver.refuseTransition(slip.getStatusSelect(), BgPaySlipResolver.CANCELLED));
    // ⚠️ ДЕКЛАРИРАНИЯТ ФИШ НЕ СЕ ОТКАЗВА, и това е ВТОРАТА врата на ключа. С пазач само на
    // потвърждаването месец, чиято Д1/Д6 е при НАП, не може да се редактира, но може да се
    // ИЗПРАЗНИ: отказаният фиш излиза от BgPaySlipResolver.DECLARABLE_STATUSES и следващата
    // декларация за месеца просто няма да го носи.
    // ⚠️ ПРЕДИ причината за отказ, а не след нея: „този фиш изобщо не се отказва“ е
    // отговор за пътя, а „отказът иска причина“ праща оператора да пише обяснение за действие,
    // което и с обяснение няма да стане.
    refuse(BgPaySlipResolver.refuseCancelInLockedPeriod(slip, declaredToNap(slip.getPeriod())));
    refuse(BgPaySlipResolver.refuseCancel(reason));
    slip.setBgCancelReason(reason.trim());
    slip.setStatusSelect(BgPaySlipResolver.CANCELLED);
    // ⚠️ Пазачът на периода в репозитория пита ЗАПИСВАНОТО: тук то вече е CANCELLED, тоест
    // отказът на чернова в заключен или затварящ се месец (почистването ѝ) минава по правилото.
    save(slip);

    // ⚠️ ОТКАЗЪТ НА КОРИГИРАЩО КОПИЕ ВРЪЩА ПЕРИОДА В CLOSED. Изходите от ADJUSTING са точно два —
    // потвърждаване и отказ — и ако вторият мълчи, месецът остава „под корекция“ завинаги, без нито
    // един документ, който да го затвори.
    if (BgPaySlipResolver.isRetro(slip)) {
      closeAfterAdjustment(slip.getPeriod());
    }
  }

  // ── трите шумни отказа ──────────────────────────────────────────────────

  /**
   * ⚠️ ПЕРИОДЪТ ТРЯБВА ДА Е ОТ ЗАПЛАТНА ГОДИНА. Връща причината за отказ, или {@code null}.
   *
   * <p>{@code Year.typeSelect} = 2 е „заплатна“ (TYPE_PAYROLL); 1 е фискална, 0 календарна, 3
   * производствена, 4 алокационна. Фиш върху фискален период смесва два календара: счетоводната
   * година може да не съвпада с календарната, а осигурителните параметри се избират по дата.
   *
   * <p>⚠️ Проверката е ОБЯВЕНА в домейна на BgPaySlip („това се проверява при потвърждаване“) и
   * важи при потвърждаване. Заварените потвърдени фишове върху фискален период НЕ се пипат.
   *
   * <p>Не е филтър на полето нарочно: филтър, който не показва нищо, изглежда като липсваща
   * настройка, а това съобщение казва какво липсва.
   */
  protected String payrollYearRefusal(BgPaySlip slip) {
    if (slip == null || slip.getPeriod() == null || slip.getPeriod().getYear() == null) {
      return "Фишът няма период или периодът не принадлежи на година — не може да се потвърди.";
    }
    Integer type = slip.getPeriod().getYear().getTypeSelect();
    if (type != null && type == YEAR_TYPE_PAYROLL) {
      return null;
    }
    return "Периодът „"
        + slip.getPeriod().getName()
        + "“ принадлежи на година „"
        + slip.getPeriod().getYear().getName()
        + "“, която НЕ е заплатна (тип "
        + type
        + " вместо "
        + YEAR_TYPE_PAYROLL
        + "). Фишът иска период от ЗАПЛАТНА година — създайте я от Заплати › Заплатни години и"
        + " генерирайте периодите ѝ.";
  }

  /**
   * (а) Периодът докосва година без производствен календар, а дните са ИЗВЕДЕНАТА стойност.
   *
   * <p>Ръчно надписаните дни минават: тогава числото е нечие решение, а не мълчалива аритметика.
   */
  protected String calendarRefusal(BgPaySlip slip) {
    BgWorkingDayService.Count count = periodWorkingDays(slip);
    if (count == null || count.isReliable()) {
      return null;
    }
    // ⚠️ „ВЪВЕДЕНО“ ЗНАЧИ ПОЛОЖИТЕЛНО, НЕ „НЕ Е null“. Генерираният getter за Integer връща 0, а
    // не null: непопълненото поле идваше тук като нула, нулата се различаваше от преброените дни
    // и отказът се ПРЕСКАЧАШЕ — тоест при незаредена година фишът минаваше мълчаливо, точно в
    // случая, за който проверката е писана. Същата болест като празния фиш: нулата изглежда като
    // нечие решение.
    int entered = slip.getBgWorkingDaysInPeriod();
    if (entered > 0 && entered != count.getWorkingDays()) {
      return null;
    }
    return "Периодът навлиза в "
        + count.uncoveredYearsAsText()
        + " г., за която НЯМА зареден производствен календар. Работните дни ("
        + count.getWorkingDays()
        + ") са преброени само по понеделник–петък, БЕЗ официалните празници. Допълнете"
        + " apps/init-data/en/bg_eventsPlanningLine.csv и fr/, заредете наново, или въведете дните"
        + " ръчно, ако ги знаете от друг източник.";
  }

  /**
   * Болничните дни НЕ са разделени при последното „Преизчисли“ — и защо.
   *
   * <p>⚠️ ЧЕТЕ СЕ ОТ ФИША, НЕ СЕ СМЯТА НАНОВО: по образеца на {@link #minIncomeRefusal} пазачът не
   * иска нито Guice, нито заявка. Отказът се пише от {@code BgSickLeaveService#applyDays} и се
   * чисти от същия метод при успешен разбив — тоест поправеният вход минава след „Преизчисли“, а не
   * тихо.
   *
   * <p>⚠️ И ОТКАЗАНОТО ПЕРО 195 — в същото поле, с началото {@code
   * BgSickLeaveService#SICK_PAY_REFUSAL_LEAD}: дните са разделени, но сумата им не може да се
   * сметне (напр. няма потвърден фиш за месеца на настъпване). Дните вече са извадени от
   * отработените, тоест потвърден без 195 фишът оставя двата дни неплатени.
   */
  protected String sickDaysRefusal(BgPaySlip slip) {
    String problem = slip.getBgSickDaysRefusal();
    if (problem == null || problem.trim().isEmpty()) {
      return null;
    }
    if (problem.startsWith(BgSickLeaveService.SICK_PAY_REFUSAL_LEAD)) {
      return problem.trim()
          + " Фишът не се потвърждава без него: дните за сметка на осигурителя вече са извадени от"
          + " отработените и щяха да останат неплатени.";
    }
    return "Болничните дни НЕ са разделени — "
        + problem.trim()
        + " Фишът не се потвърждава с неразделени болнични дни: Д1 щеше да обяви лицето за"
        + " работило. Поправете входа и натиснете „Преизчисли“.";
  }

  /**
   * Отпускът по чл. 177 КТ НЕ е сметнат при последното „Преизчисли“ — и защо.
   *
   * <p>⚠️ ЧЕТЕ СЕ ОТ ФИША, по образеца на {@link #sickDaysRefusal}: без Guice и без заявка. Пише го
   * и го чисти {@code BgArt177LeaveService#generate}.
   */
  protected String art177Refusal(BgPaySlip slip) {
    String problem = slip.getBgArt177Refusal();
    if (problem == null || problem.trim().isEmpty()) {
      return null;
    }
    return "Възнаграждението за платен отпуск по чл. 177 КТ НЕ е сметнато — "
        + problem.trim()
        + " Дните на отпуска вече са свалени от основната заплата, а перо 165 го няма — фишът не се"
        + " потвърждава с неплатен отпуск. Поправете входа и натиснете „Преизчисли“.";
  }

  /** (б) Резолверът на осигуровките е върнал сигнал, който не е чисто RESOLVED. */
  protected String contributionRefusal(BgPaySlip slip) {
    BgContributionResult result = contributions(slip);
    if (result != null && result.getStatus() == BgContributionResult.Status.RESOLVED) {
      // ⚠️ И ВНОСКИТЕ ВЪРХУ ОБЕЗЩЕТЕНИЕТО ПО ВИД 27 — те са своя заявка и свой отказ.
      for (BgCompensationParts.Carried c : BgCompensationParts.carried(slip)) {
        BgContributionResult own = compensationContributions(slip, c);
        if (own == null || own.getStatus() != BgContributionResult.Status.RESOLVED) {
          return "Осигуровките върху обезщетението „"
              + compensationReference(c.getDoc())
              + "“ (вид осигурен 27) не са изчислени докрай — "
              + (own == null ? "няма резултат" : own.getStatus())
              + (own == null || own.getDetail() == null ? "" : ": " + own.getDetail())
              + ". Фишът не се потвърждава, докато това не се уреди.";
        }
      }
      return null;
    }
    if (result == null) {
      return null;
    }
    return "Осигуровките не са изчислени докрай — "
        + result.getStatus()
        + (result.getDetail() == null ? "" : ": " + result.getDetail())
        + ". Фишът не се потвърждава, докато това не се уреди.";
  }

  /**
   * (в) Няма МОД за периода. ⚠️ ЛИПСВАЩ МОД НЕ ЗНАЧИ НУЛА.
   *
   * <p>Проверено в кода, а не предположено: {@code BgMinIncomeResult} има отделни състояния {@code
   * MOD_ROW_NOT_FOUND}, {@code NO_MOD_ROW_FOR_NKID} и {@code AMBIGUOUS_REQUIRES_OVERRIDE}, а сумата
   * при тях е {@code null}, не нула. Тоест разликата вече е моделирана и тук само се съобщава.
   *
   * <p><b>⚠️ ПРИ АКТИВНА КООРДИНАЦИЯ ТОЗИ ОТКАЗ НЕ СЕ ЗАДЕЙСТВА.</b> МОД подът е праг на ЛИЦЕТО за
   * месеца и при деклариран доход при друг платец той вече е покрит от другото правоотношение;
   * координираната база е остатъкът до тавана и НЕ зависи от него — виж {@code
   * BgInsurableIncomeService#resolve}. Иначе пазачът би спрял ВЕРЕН фиш по повод, от който нито
   * едно негово число не зависи — както работно-времевият гейт би блокирал фиша на морско лице
   * заради непопълнени часове.
   *
   * <p>⚠️ И НЕ Е „пропуска се и толкова“: {@link #contributions} също НЕ пита МОД при координация,
   * тоест пазачът и сметката четат ЕДНО И СЪЩО. Пропуснат само тук, той щеше да мълчи за фиш, чиято
   * база все пак зависи от МОД — а това е разминаване, не облекчение.
   */
  protected String minIncomeRefusal(BgPaySlip slip) {
    // ⚠️ СЛИП-ЛОКАЛНО, а НЕ през payrollInput: така пазачът не иска нито Guice, нито заявка, и
    // тестове на confirm(), които подменят minIncome, но не и payrollInput, не задействат
    // проверката ПРЕДИ подменения шев. Декларацията е на фиша и на договора; и двете са вече
    // заредени обекти.
    if (declaredOtherPayerIncome(slip) != null) {
      return null;
    }
    BgMinIncomeResult result = minIncome(slip);
    if (result == null || !result.isBlocking()) {
      return null;
    }
    return "Няма минимален осигурителен доход за периода — "
        + result.getStatus()
        + ". Липсващ МОД НЕ значи нула: 2026 г. има две полугодия (приложение № 1 до 31.07 и № 1А от"
        + " 01.08) и фишът трябва да знае кое от двете важи.";
  }

  /**
   * ОБЕЗЩЕТЕНИЕ, ЧИЯТО ОСИГУРИТЕЛНА БАЗА Е ПОСЛЕДНОТО БРУТНО, А НЕ САМАТА СУМА — КСО чл. 9 ал. 3 т.
   * 1 и т. 2.
   *
   * <p>Обхватът е ЗАТВОРЕН и живее в {@link BgArt9Para3Base}: чл. 213 ал. 1, чл. 213 ал. 2, чл. 214
   * и чл. 225 ал. 1 КТ. Всяко друго обезщетение минава оттук без дума — включително чл. 217 (т. 4)
   * и чл. 222 ал. 1 (т. 5), които СЪЩО се осигуряват, но върху себе си, тоест днешната им база е
   * вярна.
   *
   * <p><b>⚠️ ЗАЩО ОТКАЗ, А НЕ ПОПРАВЕНО ЧИСЛО.</b> Виж {@link
   * BgArt9Para3Base#refuseUnmodelledRegime} — накратко: перото носи {@code bgIsInsuranceIncome =
   * true}, тоест цялата изплатена сума влиза в {@link BgPaySlipResolver#insuranceIncomeBase} и се
   * осигурява по вид осигурен 01 с целия пакет ДОО. Да се извади само перото оттам би заменило
   * завишена вноска с НУЛЕВА и то мълчаливо, защото задължението по чл. 9 ал. 3 нищо друго не го
   * поражда.
   *
   * <p><b>⚠️ РЕТРО-ФИШЪТ СЕ ПРОПУСКА, по същия довод като {@link #doubleCompensationRefusal} и с
   * още един отгоре.</b> Коригиращото и заличаващото копие носят ПЕРАТА на оригинала, включително
   * препратката към документа — тоест пазачът би обявил всяка корекция за нов дефект. А корекцията
   * и заличаването са ЕДИНСТВЕНИЯТ изход за фиш, който вече е потвърден със завишената вноска:
   * спрян и той, грешката остава заключена в месеца си и никой не може да я извади.
   */
  protected String art9Para3Refusal(BgPaySlip slip) {
    if (BgPaySlipResolver.isRetro(slip)) {
      return null;
    }
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      // ⚠️ САМО ПЕРОТО 9xx: вноските върху обезщетение по вид 27 носят СЪЩАТА препратка
      // (BgCompensationParts#isCompensationLine), а не са второ обезщетение.
      BgCompensation doc =
          BgCompensationParts.isCompensationLine(line) ? line.getBgCompensation() : null;
      if (doc == null || doc.getBgCompensationType() == null) {
        continue;
      }
      BgCompensationType kind = doc.getBgCompensationType();
      String refusal =
          BgArt9Para3Base.refuseUnmodelledRegime(
              compensationReference(doc),
              kind.getCode(),
              kind.getLegalBasis(),
              line.getBgAmount(),
              doc.getBgBaseMonthGross(),
              doc.getBgQuantity());
      if (refusal != null) {
        return refusal;
      }
    }
    return null;
  }

  /** Номерът на документа за съобщенията, или обяснение защо го няма. */
  private static String compensationReference(BgCompensation doc) {
    String reference = doc.getReference();
    if (reference != null && !reference.trim().isEmpty()) {
      return reference.trim();
    }
    return doc.getId() == null ? "без номер" : "без номер (id " + doc.getId() + ")";
  }

  /** Обезщетение, вече платено с друг фиш, не се плаща втори път. */
  protected String doubleCompensationRefusal(BgPaySlip slip) {
    // ⚠️ РЕТРО-ФИШЪТ СЕ ПРОПУСКА, И ТОВА НЕ Е ДУПКА В ПАЗАЧА. Копието носи същите пера като
    // оригинала, включително препратката към обезщетението-документ — тоест този пазач би обявил
    // всяка корекция и всяко заличаване за второ плащане на същото обезщетение, и поправката щеше
    // да е невъзможна за всеки фиш с обезщетение на него. Пропускането е безопасно ТОЧНО ЗАЩОТО
    // ретро-фишът не се изплаща (refusePayRetro): документ, който не плаща, не може да плати втори
    // път. Двете правила се крепят взаимно и не бива да се разделят.
    if (BgPaySlipResolver.isRetro(slip)) {
      return null;
    }
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      // ⚠️ САМО ПЕРОТО 9xx: вноските върху обезщетение по вид 27 носят СЪЩАТА препратка
      // (BgCompensationParts#isCompensationLine), а не са второ обезщетение.
      BgCompensation doc =
          BgCompensationParts.isCompensationLine(line) ? line.getBgCompensation() : null;
      if (doc == null || doc.getId() == null) {
        continue;
      }
      String other = otherSlipCarrying(doc, slip);
      String refusal = BgPaySlipResolver.refuseDoubleCompensation(doc.getReference(), other);
      if (refusal != null) {
        return refusal;
      }
    }
    return null;
  }

  // ── ретро-потокът: correct() и voidRecord() ─────────────────────────────

  /**
   * ОТВАРЯ КОРЕКЦИЯ на потвърден фиш от заключен период и връща РЕДАКТИРУЕМОТО коригиращо копие.
   *
   * <p><b>Какво прави и какво нарочно НЕ прави.</b> Тук се създава копието с ПЪЛНИТЕ стари числа и
   * периодът се отваря временно (ADJUSTING). Операторът нанася промяната си върху копието и я
   * потвърждава — чак тогава {@link #confirm} смята нетната разлика, слага я в текущия фиш и връща
   * периода в CLOSED. Тоест сметките не се правят тук: тук се създава мястото, на което ще ги има.
   *
   * <p><b>⚠️ ПЕРИОДЪТ СЕ МЕСТИ ПОСЛЕДЕН, И ТОВА НЕ Е СТИЛ.</b> Спецификацията иска период никога да
   * не остава висящ в ADJUSTING. Транзакцията го гарантира при отказ на базата, но НЕ гарантира
   * нищо, ако някой ден извикащият лови изключението и продължава. Затова редът е обратен на
   * четимия: валидирай → построй → запиши копието → чак сега премести периода. Така всеки провал
   * ПРЕДИ последната стъпка оставя периода CLOSED по УСТРОЙСТВО, а не по семантика на транзакцията.
   *
   * <p><b>⚠️ И ОБРАТНАТА ПОСОКА е записана: след като correct() е минал, периодът СТОИ ADJUSTING,
   * докато операторът редактира.</b> Това не е висящо състояние, а видимият маркер „под корекция“ —
   * той пази срещу случайни редовни записи в същия месец. Изходите от него са два и двата минават
   * през услугата: потвърждаване на копието или отказ ({@link #cancel}).
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgPaySlip correct(BgPaySlip original) throws AxelorException {
    return openRetro(original, false);
  }

  /**
   * ЗАЛИЧАВА фиш, издаден ПО ГРЕШКА, и връща заличаващото копие. Симетричният близнак на {@link
   * #correct}.
   *
   * <p><b>Кога заличаване, а не корекция.</b> Корекцията казва „числата за онзи месец са други“;
   * заличаването казва „ТОЗИ ЗАПИС НЕ БИВА ДА СЪЩЕСТВУВА“ — грешно лице, грешен период, дубликат.
   * Механизмът по Н-13 е двуетапен: запис с код „заличаване“ за грешния запис, после — ако трябва —
   * нов с верни данни за верния период (това е нормалният поток, не част от заличаването).
   *
   * <p><b>⚠️ ОРИГИНАЛЪТ ОСТАВА.</b> Той не се трие и не се отказва: той е ДОКАЗАТЕЛСТВОТО какво е
   * било подадено и платено. „Заличен“ е за НАП, не за нас — в Nyxa това се вижда по заличаващия
   * фиш, който сочи към него.
   *
   * <p><b>⚠️ ЗАЛИЧАВАЩОТО КОПИЕ НОСИ ЧИСЛАТА НА ОРИГИНАЛА, И ТЕ НЕ СЕ РЕДАКТИРАТ.</b> Те не са
   * „новите верни числа“ — те ИДЕНТИФИЦИРАТ записа, който НАП трябва да заличи. Пазачът е {@code
   * refuseAlteredDeletion} и се задейства при потвърждаване.
   *
   * <p><b>⚠️ И ЗАТОВА ДЕЛТАТА Е ДРУГА:</b> при заличаване към лицето отива МИНУС ЦЯЛОТО оригинално
   * нето, а не „ново минус старо“ — второто тук дава нула. Виж {@link
   * BgPaySlipResolver#retroDelta}.
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgPaySlip voidRecord(BgPaySlip original) throws AxelorException {
    return openRetro(original, true);
  }

  /**
   * ОБЩИЯТ ПЪТ на двата потока — един, защото правилата им са едни.
   *
   * <p>Разликите между корекция и заличаване са точно три и всяка живее на СВОЕ място: кой флаг се
   * вдига (тук), как се смята делтата ({@link BgPaySlipResolver#retroDelta}) и как се казва редът в
   * текущия фиш ({@link BgPaySlipResolver#deltaLineName}). Всичко останало — валидациите, копието,
   * управлението на ADJUSTING, „не плаща сам“ — е буквално едно и също и второ копие би било
   * свободно да се размине с първото.
   */
  protected BgPaySlip openRetro(BgPaySlip original, boolean deletion) throws AxelorException {
    refuse(BgPaySlipResolver.refuseRetroSource(original, deletion));
    refuse(BgPaySlipResolver.refuseRetroPeriod(original.getPeriod(), deletion));
    // ⚠️ СРОКЪТ СЕ ПИТА СЛЕД СТАТУСА НА ПЕРИОДА, и редът има значение. За още ОТВОРЕН период
    // отговорът „откажи фиша и направи нов“ е действието, което операторът може да извърши; „срокът
    // е изтекъл“ там би било вярно и безполезно. Тоест първо се казва кой е верният път, и чак ако
    // той е този, се проверява дали още е отворен.
    refuse(BgPaySlipResolver.refuseRetroDeadline(original.getPeriod(), today(), deletion));

    BgPaySlip copy = retroCopyOf(original, deletion);
    // ⚠️ Копието е чернова в CLOSED период, но е РЕТРО — refuseLockedPeriod го пуска по
    // правилото, затварящ се период няма как да е (refuseRetroPeriod иска CLOSED).
    save(copy);
    openForAdjustment(original.getPeriod());
    return copy;
  }

  /**
   * РЕДАКТИРУЕМОТО КОПИЕ: същият договор, същият период, пълните стари числа, признак и препратка.
   *
   * <p>⚠️ Копие, а не празен фиш. Корекцията почти никога не сменя всичко — по-често е едно перо. С
   * празен фиш операторът трябва да пренапише целия месец на ръка, за да получи „пълните верни
   * числа“, и всяко непренаписано перо изчезва мълчаливо от коригиращата Д1/Д6.
   *
   * <p>⚠️ Препратката към обезщетението-документ СЕ КОПИРА. Оттам {@code doubleCompensationRefusal}
   * би обявил копието за второ плащане на същото обезщетение — затова той не се пита за ретро-фиш.
   * Това е безопасно ТОЧНО ЗАЩОТО ретро-фишът не се изплаща ({@code refusePayRetro}): документ,
   * който не плаща, не може да плати втори път. Двете промени вървят заедно и не бива да се
   * разделят.
   */
  protected BgPaySlip retroCopyOf(BgPaySlip original, boolean deletion) {
    BgPaySlip copy = new BgPaySlip();
    copy.setStatusSelect(BgPaySlipResolver.DRAFT);
    copy.setEmploymentContract(original.getEmploymentContract());
    copy.setPeriod(original.getPeriod());
    // ⚠️ ЕДИН флаг, не два: фиш, обявен и за коригиращ, и за заличаващ, иска две различни делти и
    // кодът трябва да предпочете едната наум. refuseBothRetroFlags го спира при потвърждаване.
    if (deletion) {
      copy.setBgIsDeletion(true);
      copy.setBgDeletionOf(original);
    } else {
      copy.setBgIsCorrection(true);
      copy.setBgCorrectionOf(original);
    }
    // Замразените входове идват от ОРИГИНАЛА, не от календара и договора наново: коригира се онзи
    // месец, а договорът и производственият календар може да са се променили оттогава.
    copy.setBgWorkingDaysInPeriod(original.getBgWorkingDaysInPeriod());
    copy.setBgWorkedDays(original.getBgWorkedDays());
    copy.setBgBaseSalary(original.getBgBaseSalary());
    copy.setBgInsuranceIncome(original.getBgInsuranceIncome());
    // ⚠️ И ЧАСОВЕТЕ, ПО СЪЩИЯ ДОВОД, ВКЛЮЧИТЕЛНО ЧЕТИРИТЕ ПРИЗНАКА. Пренесени без тях, коригиращият
    // фиш щеше да чете нулите на непопълнените колони като „измерени нула часа“ и т. 16.7 на
    // коригиращата Д1 щеше да излезе нула там, където оригиналът носи число.
    copy.setBgWorkedHours(original.getBgWorkedHours());
    copy.setBgWorkedHoursKnown(original.getBgWorkedHoursKnown());
    copy.setBgTimesheetDays(original.getBgTimesheetDays());
    copy.setBgNightHours(original.getBgNightHours());
    copy.setBgOnCallHours(original.getBgOnCallHours());
    copy.setBgDeclaredHoursKnown(original.getBgDeclaredHoursKnown());
    copy.setBgHolidayWorkHours(original.getBgHolidayWorkHours());
    copy.setBgHolidayHoursKnown(original.getBgHolidayHoursKnown());
    copy.setBgOvertimeWorkdayHours(original.getBgOvertimeWorkdayHours());
    copy.setBgOvertimeRestDayHours(original.getBgOvertimeRestDayHours());
    copy.setBgOvertimeHolidayHours(original.getBgOvertimeHolidayHours());
    copy.setBgOvertimeSirvHours(original.getBgOvertimeSirvHours());
    copy.setBgOvertimeHoursKnown(original.getBgOvertimeHoursKnown());
    // ═══ ЗАМРАЗЕНИТЕ ДНИ — И ТЕ ИДВАТ ОТ ОРИГИНАЛА, ПО СЪЩИЯ ДОВОД КАТО ЧАСОВЕТЕ ═══
    //
    // ⚠️ БЕЗ ТЯХ КОРИГИРАЩАТА Д1 СИ ПРОТИВОРЕЧИ ВЪТРЕ В ЕДИН ЗАПИС. BgD1DaySource чете т. 16.А и
    // т. 16.2 ЕДИНСТВЕНО от двата броя болнични дни — за тях няма огледален прочит, на който да
    // падне. Пропуснати, те даваха нула: за месец с 2 дни от осигурителя и 1 от ДОО коригиращата
    // декларация обявяваше т. 16.А = 0 и т. 16.2 = 0, докато т. 21 продължаваше да носи
    // осигурителния доход, включващ перото по чл. 40 ал. 5 КСО. НАП вижда вноски върху
    // възнаграждение за дни, които същият запис не декларира.
    //
    // ⚠️ И ВТОРИ ЧИТАТЕЛ, КОЙТО НЕ СЕ ВИЖДА ОТ ДЕКЛАРАЦИЯТА: contributions(...) подава
    // bgSickDaysEmployer на BgPayrollWorkTime#decide — числителят на коефициента по НЕВД чл. 1
    // ал. 3 е „отработените дни И дните по чл. 40 ал. 5“. Нула там значи по-нисък МОД праг при
    // потвърждаването на самото копие.
    copy.setBgSickDaysEmployer(original.getBgSickDaysEmployer());
    copy.setBgSickDaysDoo(original.getBgSickDaysDoo());
    // ⚠️ И КОЛКО ОТ ДНИТЕ ДОО СА БЕЗ ПРАВО НА ОБЕЗЩЕТЕНИЕ — присъдата по стажа КЪМ ОНЗИ МЕСЕЦ.
    // Прочетена
    // наново, тя щеше да следва днешния стаж: лице, навършило 6 месеца оттогава, щеше да премести
    // дните на коригиращата Д1 от т. 16.4 в т. 16.2.
    copy.setBgSickDaysNoBenefit(original.getBgSickDaysNoBenefit());
    // ⚠️ ШЕСТТЕ КОФИ И ИЗВАЖДАЕМОТО. Тук fallback ИМА — празната дата праща Д1 на живата
    // огледална заявка, — но той чете ДНЕШНИТЕ заявки за отпуск, а корекцията е за онзи месец.
    // Три случая се разминават дори при непокътнати заявки, защото живият път пази три отказа,
    // които замразеният няма: заявка в ЧАСОВЕ, заявка през ГРАНИЦАТА на периода и ДРОБЕН ден. За
    // тях копието обявяваше нула дни там, където оригиналът е декларирал числото си.
    copy.setBgLeaveDays161(original.getBgLeaveDays161());
    copy.setBgLeaveDays162(original.getBgLeaveDays162());
    copy.setBgLeaveDays163(original.getBgLeaveDays163());
    copy.setBgLeaveDays164(original.getBgLeaveDays164());
    copy.setBgLeaveDays165(original.getBgLeaveDays165());
    copy.setBgLeaveDays16A(original.getBgLeaveDays16A());
    copy.setBgLeaveDaysOutside(original.getBgLeaveDaysOutside());
    // ⚠️ И ДНИТЕ НА КАРВА — по същия довод: 180/181 на копието се карвират по ОНЗИ месец.
    copy.setBgArt177LeaveDays(original.getBgArt177LeaveDays());
    copy.setBgUnpaidLeaveDaysInD1(original.getBgUnpaidLeaveDaysInD1());
    // ═══ ДЕКЛАРИРАНИЯТ ДОХОД ПРИ ДРУГ ПЛАТЕЦ — ЗАМРАЗЕН ФАКТ ЗА ОНЗИ МЕСЕЦ ═══
    //
    // ⚠️ И ТОВА НЕ Е „ЗА ПЪЛНОТА“: recompute(copy) ПРЕЗАПИСВА bgInsuranceIncome през
    // insuranceIncomeHeader, тоест копираната стойност по-горе не оцелява сама. Без двете полета
    // otherPayerInsurableIncome пада на СТОЯЩАТА стойност на договора — точно пропадането, което
    // bgNoOtherPayerIncome съществува да спре. Договор със стояща 1800,00 и месец с вдигнат
    // признак: оригиналът носи осигурителен доход 1200,00, а копието — остатъка до тавана
    // 2300,00 − 1800,00 = 500,00. При ЗАЛИЧАВАНЕ това е видимо веднага: refuseAlteredDeletion
    // сравнява точно осигурителния доход и отказва копие, което операторът не е пипал.
    copy.setBgOtherPayerInsurableIncome(original.getBgOtherPayerInsurableIncome());
    copy.setBgNoOtherPayerIncome(original.getBgNoOtherPayerIncome());
    // ⚠️ РЕЖИМЪТ НА ОСИГУРЯВАНЕ СЕ НАСЛЕДЯВА, НЕ СЕ ЧЕТЕ НАНОВО: коригира се ОНЗИ месец, а
    // договорът може да е сменил вид осигурен, ТЕЛК или НКИД оттогава. Заварен оригинал (без режим)
    // дава копие без режим — то отказва пресмятане, докато човек не го попълни изрично.
    BgPayrollRegime.copy(original, copy);
    // ⚠️ РЪЧНА ПО УСТРОЙСТВО, А НЕ ПО СЛУЧАЙНОСТ. Некопиран, признакът би се чел като FALSE от
    // генерирания getter, а това е безвредно само докато bgBaseSalary е ненулево и
    // applyBaseSalary излиза на първия ред. Ретро-копието НИКОГА не бива да извежда заплатата
    // наново: тя се чете от договора и анексите му ПО ДАТА, а корекцията се прави месеци по-късно
    // и по ДНЕШНИТЕ анекси би дала друго число за онзи месец. Изрично false казва това, вместо да
    // го остави на стойността на едно съседно поле.
    copy.setBgAutoFillBaseSalary(false);
    // ⚠️ ДИСКРИМИНАТОРЪТ Е ПОСЛЕДЕН, И РЕДЪТ НЕ Е СТИЛ. Датата казва „на числата отгоре може да се
    // вярва“. Вдигната първа, всеки провал по средата на блока — или частично копиране при бъдещ
    // рефактор — оставя вдигната дата над непопълнени кофи, и Д1 чете нулите като ФАКТ. Последна,
    // тя прави непълното копие равно на копие без замразени дни, а не на тихо грешно.
    copy.setBgLeaveDaysFrozenAt(original.getBgLeaveDaysFrozenAt());
    copy.setBgCarveDaysFrozenAt(original.getBgCarveDaysFrozenAt());
    copy.setNote(
        (deletion ? "ЗАЛИЧАВАЩ" : "Коригиращ")
            + " фиш към "
            + (original.getReference() == null ? "оригинала" : original.getReference())
            + ". "
            + (deletion
                ? "Числата са тези на оригинала и НЕ се редактират — те ИДЕНТИФИЦИРАТ записа, който"
                    + " НАП трябва да заличи, и са данните на заличаващата Д1/Д6 за него. НЕ се"
                    + " изплаща: към лицето отива МИНУС цялото оригинално нето, в текущия отворен"
                    + " фиш. Оригиналът остава за одит и не се трие."
                : "Носи ПЪЛНИТЕ верни числа за периода и храни коригираща Д1/Д6 за него. НЕ се"
                    + " изплаща: към лицето отива само нетната разлика, в текущия отворен фиш."));
    for (BgPaySlipLine line : safe(original.getBgPaySlipLineList())) {
      addLine(copy, copyOf(line));
    }
    // ⚠️ ИЗПОЛЗВАНОТО ОТ ТАВАНИТЕ — ПРЕДИ recompute(copy), по довода на режима: коригира се ОНЗИ
    // месец. Прочетено живо, освобождаването на копието щеше да следва корекции на по-ранни месеци,
    // потвърдени оттогава. Датата идва с редовете — без нея копието не различава „оригиналът не е
    // ползвал тавана“ от „оригиналът е отпреди замразяването“.
    if (original.getBgCapUsageList() != null) {
      for (BgPaySlipCapUsage row : original.getBgCapUsageList()) {
        BgPaySlipCapUsage inherited = new BgPaySlipCapUsage();
        inherited.setBgSocialExpenseCap(row.getBgSocialExpenseCap());
        inherited.setBgUsedBefore(row.getBgUsedBefore());
        inherited.setBgExempt(row.getBgExempt());
        copy.addBgCapUsageListItem(inherited);
      }
    }
    copy.setBgCapUsageFrozenAt(original.getBgCapUsageFrozenAt());
    recompute(copy);
    // ⚠️ ЗАЛИЧАВАЩОТО КОПИЕ Е ПРЕСМЕТНАТО ОЩЕ ПРИ РАЖДАНЕТО СИ. Генератори върху него не
    // вървят
    // по устройство: числата му са тези на оригинала и ИДЕНТИФИЦИРАТ записа. Отпечатъкът (а) от
    // този миг пази срещу пипане, редом с refuseAlteredDeletion. Коригиращото копие го получава
    // едва от calculate() — докато не е пресметнато, то не се потвърждава.
    if (deletion) {
      copy.setBgCalcFingerprint(BgPaySlipFingerprint.of(copy));
    }
    return copy;
  }

  /** Перо по перо, БЕЗ да се пита речникът: копието трябва да е това, което оригиналът е бил. */
  protected BgPaySlipLine copyOf(BgPaySlipLine line) {
    BgPaySlipLine copy = new BgPaySlipLine();
    copy.setBgPaySlipLineType(line.getBgPaySlipLineType());
    copy.setBgLineNumber(line.getBgLineNumber());
    copy.setName(line.getName());
    copy.setBgSign(line.getBgSign());
    copy.setBgIsTaxable(line.getBgIsTaxable());
    copy.setBgIsInsuranceIncome(line.getBgIsInsuranceIncome());
    copy.setBgIsPermanentCharacter(line.getBgIsPermanentCharacter());
    // ⚠️ И ТОЗИ, по правилото на метода: „копието трябва да е това, което оригиналът е БИЛ“.
    // Пропуснат, коригиращият фиш би станал базов месец с ДРУГА база по чл. 17 от оригинала си —
    // тоест два фиша за един месец щяха да дават две различни среднодневни.
    copy.setBgIsArt17Base(line.getBgIsArt17Base());
    // ⚠️ И ТУК, ПО СОБСТВЕНОТО ПРАВИЛО НА МЕТОДА: „копието трябва да е това, което оригиналът е
    // БИЛ“. Пропуснат, флагът кара коригиращата Д1 за същия месец да заяви т. 29 = 0,00 там,
    // където оригиналната е заявила пълната брутна работна заплата. Двете декларации са за ЕДИН
    // месец и разминаването го вижда НАП, не ние.
    copy.setBgFormsGrossWage(line.getBgFormsGrossWage());
    copy.setBgQuantity(line.getBgQuantity());
    copy.setBgRate(line.getBgRate());
    copy.setBgAmount(line.getBgAmount());
    // ⚠️ И ВЪВЕДЕНАТА СУМА, по същото правило на метода: „копието трябва да е това, което
    // оригиналът
    // е БИЛ“. Пропусната, тя щеше да е празна на копието, а BgEarningsService#enteredAmountOf чете
    // празното като „улови го от bgAmount“ — тоест вече КАРВИРАНАТА сума щеше да мине за пълна
    // месечна и коригиращият фиш да карвира втори път върху нея. Ретро-фишът не се прекарва през
    // генератора (generate излиза на bgIsCorrection), тоест днес това не се задейства — но полето е
    // ИЗТОЧНИК, а източник, който копието губи, е дефект, чакащ първия, който махне онзи изход.
    copy.setBgEnteredAmount(line.getBgEnteredAmount());
    copy.setBgContributionFund(line.getBgContributionFund());
    // ⚠️ ДИСКРИМИНАТОРЪТ НА БАЗАТА СЕ КОПИРА. Фондът е един (НЗОК е един) и речникът му дава точка
    // 22.1; коя точка получава РЕДЪТ зависи от БАЗАТА и се решава в
    // BgD1ContributionAggregator#pointOf. Пропуснат, редът по ЗЗО чл. 40 ал. 1 т. 5 се чете на
    // копието като ОБИКНОВЕНА здравна вноска: т. 17 и т. 18 излизат ПРАЗНИ, а сумата отива в т.
    // 22.1 — тоест коригиращата декларация мести вноска между точки, без никой да е пипал перото.
    // BgRetroFrozenDaysTest иска т. 17/18 да съвпадат между оригинала и копието.
    copy.setBgHealthBaseKind(line.getBgHealthBaseKind());
    copy.setBgCompensation(line.getBgCompensation());
    copy.setBgCorrectsPeriod(line.getBgCorrectsPeriod());
    copy.setBgIsGenerated(line.getBgIsGenerated());
    copy.setNote(line.getNote());
    return copy;
  }

  /**
   * НЕТНАТА ДЕЛТА СЯДА В ТЕКУЩИЯ ФИШ — единственият път, по който парите от ретро-поток стигат до
   * лицето, в двете посоки.
   *
   * <p>Редът носи {@code bgCorrectsPeriod} = периода на оригинала. Оттам той е ИЗВЪН Д1/Д6 на
   * текущия месец (осигурителният доход и данъкът за онзи месец са в ретро-фиша) и ВЪТРЕ в текущото
   * нето, ведомостта и превода.
   *
   * <p>⚠️ НУЛЕВА ДЕЛТА НЕ СЛАГА РЕД, и това е решение. Корекция може да смени декларацията, без да
   * мени нетото — например разместване между две еднакво облагани пера. Тогава към лицето не се
   * дължи нищо и ред за нула би твърдял, че нещо се е случило. Записът на случилото се е самият
   * коригиращ фиш, който сочи оригинала.
   *
   * <p>⚠️ При ЗАЛИЧАВАНЕ нулева делта значи друго — че оригиналът е бил с нулево нето (цял месец
   * неплатен отпуск, например). И тогава ред не се слага: няма какво да се връща. Заличаващата
   * декларация обаче се ражда и в този случай, защото тя е за ЗАПИСА, не за парите.
   */
  protected void settleRetroDelta(BgPaySlip retro) throws AxelorException {
    boolean deletion = Boolean.TRUE.equals(retro.getBgIsDeletion());
    BgPaySlip original = deletion ? retro.getBgDeletionOf() : retro.getBgCorrectionOf();
    BigDecimal delta =
        BgPaySlipResolver.retroDelta(deletion, retro.getBgNetTotal(), original.getBgNetTotal());
    if (delta.signum() == 0) {
      return;
    }
    BgPaySlip current = currentSlipFor(retro);
    BgPaySlipLineType type = correctionLineType();
    refuse(correctionLineTypeRefusal(type));

    BgPaySlipLine line = lineFrom(type, delta.abs());
    line.setBgSign(BgPaySlipResolver.deltaSign(delta));
    line.setName(BgPaySlipResolver.deltaLineName(deletion, delta, retro.getPeriod()));
    line.setBgCorrectsPeriod(retro.getPeriod());
    line.setNote(
        (deletion ? "Сторно на заличен фиш за период " : "Нетна разлика от корекция на период ")
            + BgPaySlipResolver.periodLabel(retro.getPeriod())
            + " ("
            + (deletion ? "заличаващ" : "коригиращ")
            + " фиш "
            + (retro.getReference() == null ? "без номер" : retro.getReference())
            + "). ⚠️ Извън Д1/Д6 на ТОЗИ месец — вноските и данъкът за онзи месец минават по "
            + (deletion ? "заличаващата" : "коригиращата")
            + " декларация ЗА НЕГО.");
    addLine(current, line);
    recompute(current);
    save(current);
  }

  /** Липсващото перо в речника е ОТКАЗ, който сочи файла — не тиха липса на ред във фиша. */
  protected String correctionLineTypeRefusal(BgPaySlipLineType type) {
    if (type != null) {
      return null;
    }
    return "В речника с редове на фиша няма перо с код „"
        + CORRECTION_LINE_CODE
        + "“ и затова нетната разлика няма къде да влезе. Редът идва от"
        + " apps/init-data/en/bg_pay_slip_line_type.csv и fr/ и пристига в базата само през"
        + " uninstall → App Install — вдигането на версията на модула презарежда изгледи, не данни.";
  }

  /**
   * ТЕКУЩИЯТ ОТВОРЕН ФИШ НА ЛИЦЕТО — намерен или създаден.
   *
   * <p>⚠️ Създаването не е удобство: делтата трябва да има къде да седне и в най-честия случай —
   * корекция, открита преди текущият месец да е започнал да се пълни. Ако вместо това корекцията се
   * отказваше „няма текущ фиш“, операторът щеше да е принуден да създаде фиша на ръка и да се
   * върне, а между двете действия периодът стои ADJUSTING.
   */
  protected BgPaySlip currentSlipFor(BgPaySlip retro) throws AxelorException {
    Period current = currentOpenPeriod(retro);
    if (current == null) {
      refuse(
          "Няма ОТВОРЕН заплатен период, в който да влезе разликата от корекцията. Разликата се"
              + " плаща с текущата заплата, тоест иска период от заплатна година (Year.typeSelect ="
              + " 2) със статус OPENED, който съдържа днешната дата. Отворете го от Заплати ›"
              + " Заплатни години и повторете корекцията.");
    }
    BgPaySlip found = openDraftSlip(retro.getEmploymentContract(), current);
    if (found != null) {
      return found;
    }
    BgPaySlip fresh = new BgPaySlip();
    fresh.setStatusSelect(BgPaySlipResolver.DRAFT);
    fresh.setEmploymentContract(retro.getEmploymentContract());
    fresh.setPeriod(current);
    // ⚠️ ВТОРИ РЕДОВЕН ФИШ НЕ СЕ РАЖДА, когато текущият вече е потвърден: иначе черновата не се
    // намира и делтата получава свой фиш до потвърдения — два редовни за един месец. Отказът
    // назовава текущия; делтата влиза, след като той бъде откажен и направен наново като чернова.
    String second = BgEffectivePaySlips.refuseSecondRegular(fresh, otherRegular(fresh));
    if (second != null) {
      refuse(
          "Нетната разлика от корекцията няма къде да влезе: текущият месец вече има редовен фиш,"
              + " който не е чернова. "
              + second);
    }
    prepare(fresh);
    return fresh;
  }

  // ── управлението на ADJUSTING ───────────────────────────────────────────

  /** CLOSED → ADJUSTING, с вписване в родната история на корекциите. */
  protected void openForAdjustment(Period period) {
    logAdjustStart(period);
    period.setStatusSelect(PeriodRepository.STATUS_ADJUSTING);
    savePeriod(period);
  }

  /**
   * ADJUSTING → CLOSED, също вписано. Идемпотентно: период, който не е ADJUSTING, не се пипа.
   *
   * <p>⚠️ Идемпотентността НЕ е предпазна мярка. Този метод се вика и при потвърждаване, и при
   * отказ на коригиращото копие, а операторът може да откаже фиш, който вече е потвърден — тоест
   * втори път по същия период. Без проверката вторият път би вписал втори край на корекция, която
   * вече е приключила, и историята щеше да лъже.
   */
  protected void closeAfterAdjustment(Period period) {
    if (period == null || period.getStatusSelect() != PeriodRepository.STATUS_ADJUSTING) {
      return;
    }
    logAdjustEnd(period);
    period.setStatusSelect(PeriodRepository.STATUS_CLOSED);
    savePeriod(period);
  }

  // ── шевовете към базата (подменят се в тестовете) ───────────────────────

  /**
   * КЛЮЧ (Б) НА ЗАКЛЮЧВАНЕТО: има ли за периода ПОДАДЕНА и неотхвърлена Д1/Д6.
   *
   * <p>Правилото „А ИЛИ Б“ се смята в {@link BgPaySlipResolver#isPeriodLocked(Period, boolean)};
   * тук е само отговорът на въпроса към базата. Кои статуси на подаването се броят пише в {@code
   * BgD1PeriodLockRules} и се проверява без база.
   *
   * <p><b>⚠️ ЧИСТИЯТ ИЗХОД Е ПРЕДИ {@code Beans.get}, И ТОВА НЕ Е ОПТИМИЗАЦИЯ.</b> Същият довод
   * като при {@link #socialExpenseTaxBase} и {@link #generatePayrollMove}: стотиците тестове, които
   * викат {@code confirm()} и {@code cancel()} без контейнер, биха паднали с „Guice is not
   * initialized“ по причина, която няма нищо общо с това, което мерят. Вратата тук е {@code id}-то
   * на периода — сглобеният наум период го няма, а без ред в базата подаване към него не може да
   * сочи.
   *
   * <p>⚠️ Шевът е СВОЙ метод, а не три реда вътре в {@code confirm()}: тестът, който иска да мери
   * ключ (Б), го подменя с един ред, вместо да вдига НАП-регистър.
   */
  /**
   * Шев към {@link BgEffectivePaySlips#otherRegular} — без идентификатор на периода не пита базата,
   * по образеца на {@link #declaredToNap}.
   */
  protected BgPaySlip otherRegular(BgPaySlip slip) {
    return BgEffectivePaySlips.otherRegular(slip);
  }

  protected boolean declaredToNap(Period period) {
    if (period == null || period.getId() == null) {
      return false;
    }
    return Beans.get(BgD1PeriodLockService.class).isDeclaredToNap(period);
  }

  /** Работните дни в периода, заедно с покритието на календара. */
  protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
    if (slip == null || slip.getPeriod() == null || slip.getPeriod().getFromDate() == null) {
      return null;
    }
    LocalDate from = slip.getPeriod().getFromDate();
    LocalDate to = slip.getPeriod().getToDate() == null ? from : slip.getPeriod().getToDate();
    int days = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
    return Beans.get(BgWorkingDayService.class).count(from, days);
  }

  /**
   * Резултатът на резолвера за осигуровките.
   *
   * <p>Входът — вид осигурен, категория труд, кохорта по ДЗПО и ставка ТЗПБ — идва от {@link
   * BgPayrollInput} през сглобителя, а видът осигурен има дом на договора ({@code
   * EmploymentContract.bgInsuredTypeCode}). Резултатът захранва {@link #contributionRefusal}.
   *
   * <p><b>Веригата, стъпка по стъпка.</b> Всяка стъпка може да КАЖЕ „не мога“, и никоя не
   * подразбира:
   *
   * <ol>
   *   <li>вход от (договор, период) — без него няма какво да се пита;
   *   <li>вид осигурен — празният е ОТКАЗ на сглобителя, не подразбиране;
   *   <li>режим на работното време по НЕВД чл. 1 — отказът му спира и тази верига;
   *   <li>МОД за периода;
   *   <li>ОСИГУРИТЕЛНАТА БАЗА = min(max(бруто, МОД, законов праг), таван) — смята се от {@link
   *       BgInsurableIncomeService}, не тук;
   *   <li>заявка → {@link BgInsuranceCalculationService}, който зарежда редовете по ключа и вика
   *       {@link BgContributionResolver}.
   * </ol>
   *
   * <p>⚠️ ПАЗАЧИТЕ НА РЕЗОЛВЕРА НЕ СЕ ПРЕПИСВАТ ТУК. Дублиран фонд, липсващ задължителен фонд, вид
   * на ставката (MISSING / LEGAL_ZERO / STRUCTURAL_ZERO) и чл. 4в са негови и си остават негови —
   * второ копие на едно правило е свободно да се разминае с първото.
   *
   * <p>⚠️ {@code INPUT_NOT_ASSEMBLED}, А НЕ {@code NO_CONTRIBUTIONS_FOUND}, когато веригата спре
   * преди резолвера. Второто значи „каталогът е питан и мълчи“ и праща читателя да поправя seed-а;
   * първото значи „каталогът изобщо не е питан“ и го праща на договора. Една буква разлика в
   * съобщението, два различни файла за поправяне.
   */
  protected BgContributionResult contributions(BgPaySlip slip) {
    // ⚠️ ФИШ САМО С ОБЕЗЩЕТЕНИЕ ПО ВИД 27 НЯМА ВНОСКИ ПО ВИД 01 — дори МОД: правоотношението е
    // прекратено и осигурителен доход по него няма. Разрешеният празен резултат маха и отпадналите
    // стари пера. Вноските на обезщетението са своя заявка — compensationContributions.
    if (BgCompensationParts.onlyCompensation(slip)) {
      return BgContributionResult.resolved(Collections.emptyList());
    }
    // ⚠️ САМО ДВОЙНИЯТ НОСИТЕЛ СЕ ПИТА ПРЕДИ СГЛОБЯВАНЕТО. Широкият въпрос тук би ИЗПРЕВАРИЛ шева
    // payrollInput(...): тестове, които подават СОБСТВЕН вход през този шев и строят фиша без
    // договор, биха били отказани, преди да стигнат до гейта за работното време, който проверяват.
    //
    // Разграничението е: „и двете“ е свойство на ФИША и никой подменен шев не може да го поправи;
    // „нито едно“ е свойство на ВХОДА и се вижда точно там, където входът не може да се сглоби.
    String ambiguous = BgSelfInsuredRules.refuseAmbiguousCarrier(slip);
    if (ambiguous != null) {
      return BgContributionResult.failure(
          BgContributionResult.Status.INPUT_NOT_ASSEMBLED, ambiguous);
    }
    BgPayrollInput input = payrollInput(slip);
    if (input == null) {
      // ⚠️ ТЕКСТЪТ Е НА НОСИТЕЛЯ, КОГАТО ТОЙ Е ПРИЧИНАТА. Едно съобщение за двата случая —
      // „фишът няма трудов договор или период“ — би пратило човека да търси договор на фиш на
      // самоосигуряващо се лице.
      String carrier = BgSelfInsuredRules.refuseCarrier(slip);
      return BgContributionResult.failure(
          BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
          carrier != null
              ? carrier
              : "фишът няма период, тоест вход за осигуровки не може да се сглоби: без него нито"
                  + " темпоралните параметри, нито редовете със ставки могат да бъдат намерени");
    }
    if (input.insuredTypeCode == null || input.insuredTypeCode.trim().isEmpty()) {
      return BgContributionResult.failure(
          BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
          BgPayrollInputBuilder.REFUSALS.get("insuredTypeCode"));
    }

    // ⚠️ Режимът РЕШАВА кой ред по НЕВД чл. 1 важи, а двата реда дават РАЗЛИЧЕН праг за един и същ
    // месец. Отказът му затова спира и осигуровките: база, сметната по подразбран режим, е праг
    // наужким — точно тихият резултат, който отказът съществува да замени.
    // ⚠️ УСЛОВЕН, не безусловен. Отказът пази ЕДИН избор: ал. 4 (часове) или ал. 3 (дни). За вид
    // осигурен с bgZeroWorkTime този избор изобщо не се прави — прагът се дължи ЦЯЛ — и безусловен
    // гейт би блокирал фиша на МОРСКО ЛИЦЕ (код 92) заради непопълнено
    // EmploymentContract.bgHoursPerDay, вход, от който сметката му НЕ зависи.
    // Условието се чете от СЪЩИЯ източник, с който резолверът после смята: BgInsuredType.
    // ⚠️ И ВТОРО УСЛОВИЕ — КООРДИНАЦИЯТА. Виж coordinated(input): при активна
    // декларация базата е остатъкът до тавана и НЕ зависи нито от пропорцията, нито от МОД.
    boolean coordinated = coordinated(input);
    boolean prorates = !coordinated && proratesWithWorkTime(input, slip);
    // ⚠️ ДВУАРГОВИЯТ decide. НЕВД чл. 1 ал. 3 дава числителя като „отработените дни И ДНИТЕ ПО
    // ЧЛ. 40, АЛ. 5 от КСО“; подадени като обявена липса, тоест нула, вторите дават по-нисък
    // коефициент, по-нисък МОД праг, по-ниска осигурителна база. Занижаване, и то тихо — всички
    // числа изглеждат пресметнати.
    // ⚠️ ЧЕТЕ СЕ ОТ ФИША, А НЕ ОТ ЕПИЗОДИТЕ НА ВХОДА: разделянето е сметнато ВЕДНЪЖ от
    // BgSickLeaveResolver#split и е замразено в bgSickDaysEmployer — същият избор като в
    // BgD1DaySource, който го чете за т. 16.А. Изведено втори път тук, то е свободно да се размине
    // с първото, а коригиращият фиш за минал месец щеше да го сметне по ДНЕШНИТЕ правила.
    // ⚠️ РЕДЪТ Е ПРАВИЛО, НЕ ПОДРЕДБА: полето се пълни от generateSickLeave(...), а prepare(...)
    // го вика ПРЕДИ generateContributions(...) — виж довода там. Разменени, тук щеше да пристига
    // нула. Нулата и null се четат еднакво от вратата, тоест фиш БЕЗ болничен минава непроменен.
    BgPayrollWorkTime.Decision workTime =
        BgPayrollWorkTime.decide(input, slip.getBgSickDaysEmployer());
    if (!coordinated && workTime.blocks(prorates)) {
      return BgContributionResult.failure(
          BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
          "работното време не е определено — " + workTime.refusal);
    }

    BgInsurableIncomeResult income = insurableIncomeFor(slip, input, workTime, prorates);
    if (!income.isResolved()) {
      return BgContributionResult.failure(
          BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
          "осигурителната база не е определена — "
              + income.getStatus()
              + (income.getDetail() == null ? "" : ": " + income.getDetail()));
    }

    return calculate(
        new BgContributionRequest(
            income.getAmount(),
            input.insuredTypeCode,
            input.laborCategory,
            input.bornAfter1959,
            input.pensionChoice,
            input.solScope,
            referenceDate(input, slip),
            tzpbRate(slip, referenceDate(input, slip)),
            // ⚠️ STANDARD, не кодът на обезщетение. Фишът може да носи и обезщетения, но те имат
            // СВОЙ осигурителен режим на СВОЙ ред; смесването им със заплатата в една заявка би
            // вкарало и двата набора ставки наведнъж — двойно начисляване, което изглежда като
            // DUPLICATE_FUND_ROWS, но по друга причина. Обезщетението по вид 27 е СВОЯ заявка —
            // compensationContributions.
            null,
            // Учителството идва от ДОГОВОРА през сглобителя, не се извежда втори път тук.
            input.teacherScope));
  }

  /**
   * КАТЕГОРИЯТА, под която каталогът носи ставките на вид 27 — само трета.
   *
   * <p>⚠️ КСО чл. 9 ал. 3 т. 5 дава вноските за времето без работа „в размерите за фонд Пенсии“ —
   * не по категорията на труда, който лицето вече не полага. Каталогът ги носи под категория 3
   * (редове IC-070, IC-071, IC-081, IC-109 … IC-111), и образецът на Microinvest е точно тях: 8,22
   * / 6,58 и 2,80 / 2,20.
   */
  protected static final String COMPENSATION_27_LABOR_CATEGORY = "3";

  /**
   * ВНОСКИТЕ ВЪРХУ ЕДНО ОБЕЗЩЕТЕНИЕ ПО ВИД 27 — своя заявка към каталога.
   *
   * <p>⚠️ Вид осигурен 27 и основанието от каталога ({@code comp_kt_222_1}): {@link
   * BgInsuranceCalculationService#calculate} търси реда по ТОЧНО съвпадение на основанието, тоест
   * обикновените редове на вид 27 не се смесват с тези на чл. 106 ал. 2 ЗДСл. Фишът начислява
   * веднъж, върху цялото обезщетение — разделянето по месеци е на декларациите ({@code
   * BgCompensationParts}).
   *
   * <p><b>⚠️ БАЗАТА Е ПОД ОБЩИЯ ТАВАН С ВИД 01</b> (КСО чл. 6 ал. 11): сборът на частите, всяка =
   * min(частта; таванът за месеца − осигурителния доход по вид 01 на лицето в дружеството, но не
   * под 0) — {@link #compensationInsurable}. Заявката носи вече приложен таван ({@link
   * BgContributionRequest}), тоест той важи за всеки фонд на реда 27 (Пенсии, ДЗПО) наведнъж.
   *
   * <p>⚠️ ТЗПБ НЕ СЕ ДОЛЕПЯ: правоотношението е прекратено и работодател по смисъла на фонда няма.
   */
  protected BgContributionResult compensationContributions(
      BgPaySlip slip, BgCompensationParts.Carried carried) {
    BgPayrollInput input = payrollInput(slip);
    if (input == null) {
      return BgContributionResult.failure(
          BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
          "фишът няма период или договор — вноските върху обезщетението по вид 27 нямат вход");
    }
    BigDecimal insurable;
    try {
      insurable = compensationInsurable(slip, carried);
    } catch (IllegalStateException refused) {
      return BgContributionResult.failure(
          BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
          "обезщетение „" + compensationReference(carried.getDoc()) + "“: " + refused.getMessage());
    }
    BgCompensationType kind = carried.getDoc().getBgCompensationType();
    return calculate(
        new BgContributionRequest(
            insurable,
            BgCompensationParts.INSURED_TYPE,
            COMPENSATION_27_LABOR_CATEGORY,
            input.bornAfter1959,
            input.pensionChoice,
            input.solScope,
            referenceDate(input, slip),
            null,
            kind == null ? null : kind.getCode(),
            input.teacherScope));
  }

  /**
   * ОСИГУРИТЕЛНИЯТ ДОХОД НА ОБЕЗЩЕТЕНИЕТО ПОД ОБЩИЯ ТАВАН — същата сметка, по която Д1 и Д6 делят
   * вноските ({@link BgCompensationParts#insurableOf}).
   *
   * @throws IllegalStateException месец без таван или непокрит календар — отказ с причина
   */
  protected BigDecimal compensationInsurable(BgPaySlip slip, BgCompensationParts.Carried carried) {
    return BgCompensationParts.insurableOf(
        carried.amount(),
        carried.getDoc().getBgEventDate(),
        compensationCalendar(),
        compensationRoom(slip));
  }

  /** Шев: работните дни за частите на обезщетението. */
  protected BgCompensationParts.WorkingDays compensationCalendar() {
    return BgCompensationCalendar.fromDatabase();
  }

  /** Шев: мястото под общия таван с вид 01 за месеците на обезщетението. */
  protected BgCompensationParts.Room compensationRoom(BgPaySlip slip) {
    return BgCompensationCalendar.roomFor(slip);
  }

  /**
   * Вноските на вид 27, чийто документ вече НЕ е на фиша (отказан, свален) — махат се, щом са на
   * генератора. Инак те биха останали в счетоводството, без ред в Д1 и в Д6.
   */
  protected void removeOrphanCompensationContributions(
      BgPaySlip slip, List<BgCompensationParts.Carried> carried) {
    BgGeneratedLines.removeStale(
        slip,
        line -> {
          if (BgPaySlipResolver.groupOf(line) != BgPaySlipResolver.GROUP_CONTRIBUTIONS
              || line.getBgCompensation() == null) {
            return false;
          }
          for (BgCompensationParts.Carried c : carried) {
            if (c.getDoc() == line.getBgCompensation()
                || (c.getDoc().getId() != null
                    && c.getDoc().getId().equals(line.getBgCompensation().getId()))) {
              return false;
            }
          }
          return true;
        });
  }

  /**
   * Резултатът за МОД — по същата верига като {@link #contributions}.
   *
   * <p>⚠️ Подът зачита {@code BgInsuredType.bgFloorType}, но НЕ тук: изборът между МОД, МРЗ (кодове
   * 05, 06, 24) и минималния за СОЛ (12, 22, 92) живее в {@link BgInsurableIncomeService}, а този
   * метод връща само МОД частта — тя е и единственото, което {@link #minIncomeRefusal} пита.
   *
   * <p>⚠️ {@code NOT_APPLICABLE} НЕ Е ОТКАЗ и точно затова {@code isBlocking()} го изключва: за
   * кодове 05, 12, 14 и 24 МОД просто не важи, а законовият им праг идва по другия път. Отказ тук
   * би спрял всеки фиш на държавен служител.
   */
  protected BgMinIncomeResult minIncome(BgPaySlip slip) {
    BgPayrollInput input = payrollInput(slip);
    return input == null ? null : minIncomeFor(input, slip);
  }

  // ── новите шевове към базата (подменят се в тестовете) ──────────────────

  /**
   * Входът за двойката (трудов договор, период) — мостът от ФИША до {@link BgPayrollInput}.
   *
   * <p>Мост не се строеше: {@code BgPaySlip} носи и двете нужни неща като {@code required} връзки
   * ({@code employmentContract} и {@code period}), тоест домейн промяна не се наложи. Проверката за
   * {@code null} остава, защото {@code required} важи при запис, а услугата се вика и с непълен
   * обект от тестове и от контролери.
   */
  /**
   * ПУБЛИЧНАТА ВРАТА КЪМ СГЛОБЕНИЯ ВХОД — делегира, не повтаря.
   *
   * <p>⚠️ Шевът остава {@code protected} и заварените двайсетина свидетеля, които го подменят, не
   * се пипат. Вратата съществува за {@code BgSickNoteArrivalService}: пристигналият болничен иска
   * СЪЩИЯ вход, с който {@code prepare()} смята — втори сглобител би бил свободен да се размине с
   * него, а разминаване тук значи друг разбив на дните по чл. 40 ал. 5 КСО.
   */
  public BgPayrollInput payrollInputFor(BgPaySlip slip) {
    return assembledInput(slip);
  }

  /**
   * ВХОДЪТ НА ФИША — сглобен от договора и отчетите, а за коригиращо или заличаващо копие САМО от
   * замразеното.
   *
   * <p><b>⚠️ КОПИЕТО НЕ ЧЕТЕ ЖИВИТЕ ДАННИ ЗА ОНЗИ МЕСЕЦ.</b> Отчетите за време, заявките за отпуск
   * и болничните листове може да са пипани след оригинала; вноските и данъкът на копието се смятат
   * от дванайсетте часови полета, кофите от #241, дните на карва и режима на самия фиш — виж {@link
   * BgFrozenPayrollInput}. Изключението е {@link #payrollInputFor}: „Отрази пристигнал болничен“
   * иска НОВИЯ лист — точно новият факт, заради който копието съществува.
   */
  protected BgPayrollInput payrollInput(BgPaySlip slip) {
    if (BgPaySlipResolver.isRetro(slip)) {
      return frozenInput(slip);
    }
    return assembledInput(slip);
  }

  /** Входът на копието от замразеното — шев, за да може свидетел да подмени ЦЕЛИЯ вход. */
  protected BgPayrollInput frozenInput(BgPaySlip slip) {
    return BgFrozenPayrollInput.of(
        slip, BgPayrollRegime.of(slip), effectiveOtherPayerIncome(slip, null));
  }

  /** Входът, сглобен от договора, отчетите и заявките — за редовен фиш и за пристигналия лист. */
  protected BgPayrollInput assembledInput(BgPaySlip slip) {
    if (slip == null || slip.getPeriod() == null) {
      return null;
    }
    // ⚠️ РАЗКЛОНЕНИЕТО Е ПО НОСИТЕЛЯ, А НЕ ПО ВИДА ОСИГУРЕН. Кодът 12 може да се озове и на договор
    // — точно това пазачът BgInsuredTypeHomeFileTest отказва, — тоест изведен от кода, изборът на
    // сглобител щеше да зависи от данни, които сами по себе си може да са сгрешени. Носителят е
    // структурен: регистрация значи самоосигуряващо се лице, договор значи наето.
    // ⚠️ XOR-ът НЕ се проверява тук: тук се РАЗКЛОНЯВА, а отказът е на BgSelfInsuredRules
    // #refuseCarrier и се вика от contributions(...) и от confirm(...). Втора проверка на същото
    // място би дала второ съобщение за един дефект.
    if (BgSelfInsuredRules.isSelfInsured(slip)) {
      return inputBuilder()
          .buildSelfInsured(
              slip.getBgSelfInsuredRegistration(),
              slip.getPeriod().getFromDate(),
              slip.getPeriod().getToDate(),
              BgPayrollRegime.of(slip));
    }
    if (slip.getEmploymentContract() == null) {
      return null;
    }
    EmploymentContract contract = managedContract(slip);
    // ⚠️ ТРЕТИЯТ АРГУМЕНТ Е РАЗРЕШЕН ТУК, а не се чете вътре в сглобителя: той тръгва от двойката
    // (договор, период) и фиш не вижда, а разрешаването иска и двете страни.
    // ⚠️ РЕЖИМЪТ ОТ ФИША, ако е замразен — виж BgPayrollRegime. Живият прочит остава само за фиш
    // без режим: редовна чернова преди първото пресмятане или заварен потвърден фиш.
    return inputBuilder()
        .build(
            contract,
            slip.getPeriod().getFromDate(),
            slip.getPeriod().getToDate(),
            effectiveOtherPayerIncome(slip, contract),
            BgPayrollRegime.of(slip));
  }

  /**
   * САМИЯТ СГЛОБИТЕЛ — шев, за да може РАЗКЛОНЯВАНЕТО да се тества, а не само резултатът му.
   *
   * <p><b>⚠️ ЗАЩО ШЕВ, А НЕ {@code Beans.get} НА МЯСТО.</b> Разклонението по носителя (регистрация
   * срещу договор) е решение на {@link #payrollInput} и точно то трябва да се провери. Заварените
   * тестове подменят целия {@code payrollInput}, тоест разклонението в тях изобщо не се изпълнява —
   * подменена е самата функция, която го съдържа. Изнесен така, {@code Beans.get} е единственото
   * подменено нещо и разклонението тече ИСТИНСКО.
   *
   * <p>⚠️ Разширяването на видимостта НЕ поваля заварен override: никой не подменя този метод.
   * Обратният случай — нов шев вътре в вече подменян метод — е записан в {@code
   * BgPaySlipServiceTest} и там наложи промяна в стубовете.
   */
  protected BgPayrollInputBuilder inputBuilder() {
    return Beans.get(BgPayrollInputBuilder.class);
  }

  /**
   * ДЕКЛАРИРАНИЯТ ЧУЖД ОСИГУРИТЕЛЕН ДОХОД ЗА ТОЗИ МЕСЕЦ — ЕДНОТО място, където хибридът се
   * разрешава.
   *
   * <p><b>Ред: фиш → договор → липса.</b> Стойността на ФИША е МЕСЕЧЕН override и печели, когато е
   * попълнена; инак важи СТОЯЩАТА стойност на ДОГОВОРА; инак няма декларация и координацията не се
   * задейства.
   *
   * <p><b>⚠️ ЗАЩО ХИБРИД, А НЕ ЕДНО ОТ ДВЕТЕ.</b> Чуждият доход обикновено е ПОСТОЯНЕН — второто
   * правоотношение си стои месец след месец, — но се мени при отпуск, болничен или напускане при
   * другия платец. Само на договора: ТРЗ не може да поправи един месец. Само на фиша: трябва да го
   * въвежда дванайсет пъти в годината, а пропуснат месец мълчи — той изглежда точно като „няма чужд
   * доход“ и връща обратно двойния МОД праг, който целият този клон съществува да махне.
   *
   * <p><b>⚠️ „ПОПЪЛНЕНА“ ЗНАЧИ {@code signum() > 0}, НЕ {@code != null} — и оттам следва едно
   * ОГРАНИЧЕНИЕ, което се ОБЯВЯВА.</b> Генерираният getter за decimal връща {@code ZERO} за
   * непопълнена колона, тоест „не съм пипал полето“ и „въведох 0,00“ са НЕРАЗЛИЧИМИ на ЗАПИС.
   * Следствието: празно или 0,00 на фиша значи „ползвай договора“, а НЕ „този месец без
   * координация“. Тоест при попълнена стояща стойност ЕДИН месец не може да се занули от фиша —
   * зануляването става със зануляване на полето на ДОГОВОРА.
   *
   * <p><b>Пълният три-стейт е ОБЯВЕНА БЪДЕЩА ИТЕРАЦИЯ, не пропуск.</b> Той иска трети носител —
   * булев признак „този месец няма чужд доход“ на фиша, по образеца на {@code
   * BgVatReturn.bgAutoFillK71}, където признакът казва кое се смята и кое не. Записано тук и в
   * домейните на двете полета, за да не се сметне за уредено; днешното поведение е ЗНАЙНО, а не
   * подразбрано.
   *
   * <p>⚠️ Нула се връща като {@code null}, а не като нула: надолу {@code coordinationOf} и {@code
   * Declaration#isActive} питат същото нещо, и една стойност, минала за „декларирана нула“, би била
   * неразличима от декларация. Тоест трите състояния влизат в контракта като ДВЕ — стойност или
   * липса — и точно това е ограничението отгоре.
   */
  protected BigDecimal effectiveOtherPayerIncome(BgPaySlip slip, EmploymentContract contract) {
    // ⚠️ ДЕЛЕГИРА, А НЕ ПОВТАРЯ. Правилото слезе в BgPaySlipResolver, защото ВТОРИ читател го
    // поиска: т. 21 на Д1 трябва да знае дали bgInsuranceIncome носи КООРДИНИРАНАТА база или
    // просто сбора на перата, а строителят на записа е чиста функция и не може да вика услуга.
    // Този шев остава protected и извикваем — заварените пазачи и техните тестове не се пипат.
    BgPayrollRegime regime = BgPayrollRegime.of(slip);
    if (regime != null) {
      return BgPaySlipResolver.otherPayerInsurableIncome(slip, regime.otherPayerIncome);
    }
    return BgPaySlipResolver.otherPayerInsurableIncome(slip, contract);
  }

  /**
   * Договорът, ПРОЧЕТЕН НАНОВО по идентификатора си.
   *
   * <p>⚠️ Не е излишно. {@link #prepare} се вика от контролера върху обект, сглобен от КОНТЕКСТА НА
   * ЕКРАНА, а екранът на фиша носи от договора само препратката — не и {@code startDate}, нито
   * {@code bgPriorExperienceYears/Months/Days}. Без това четене котвата на стажа пристига празна и
   * класът прослужено време се отказва на всеки фиш, отворен от формата — отказ, чиято причина е
   * начинът на извикване, а не данните.
   *
   * <p>При {@link #confirm} обектът вече е управляван и {@code find} го връща от същия контекст,
   * тоест второ четене няма. Договор без идентификатор (тест, нов запис) се връща както е подаден.
   */
  protected EmploymentContract managedContract(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    if (contract.getId() == null) {
      return contract;
    }
    EmploymentContract found = Beans.get(EmploymentContractRepository.class).find(contract.getId());
    return found == null ? contract : found;
  }

  /**
   * МОД за входа. Отделен от {@link #minIncome}, за да го ползват и двете вериги без втори вход.
   */
  protected BgMinIncomeResult minIncomeFor(BgPayrollInput input, BgPaySlip slip) {
    return Beans.get(BgMinInsuranceIncomeService.class)
        .findMinInsuranceIncome(
            minIncomeOverrideOf(slip),
            input.nkidCode,
            minIncomeRowOf(slip),
            input.nkpdCode,
            input.insuredTypeCode,
            referenceDate(input, slip));
  }

  /**
   * РЪЧНИЯТ МОД НА ЛИЦЕТО — замразеният от режима, а живо само за фиш без режим.
   *
   * <p>⚠️ Той вдига пода на осигурителната база, тоест е вход на вноските, колкото и видът
   * осигурен. Прочетен от днешния служител, ръчен МОД, сменен през октомври, щеше да осигурява
   * мартенското копие по октомврийското число.
   */
  protected BigDecimal minIncomeOverrideOf(BgPaySlip slip) {
    BgPayrollRegime regime = BgPayrollRegime.of(slip);
    if (regime != null) {
      return regime.minIncomeOverride;
    }
    Employee employee = employeeOf(slip);
    return employee != null ? employee.getBgMinIncomeOverride() : null;
  }

  /** Ръчният МОД ред на фирмата (двусмислен КИД) — по довода на {@link #minIncomeOverrideOf}. */
  protected String minIncomeRowOf(BgPaySlip slip) {
    BgPayrollRegime regime = BgPayrollRegime.of(slip);
    if (regime != null) {
      return regime.minIncomeRow;
    }
    Company company = companyOf(slip);
    return company != null ? company.getBgMinIncomeRowCodeOverride() : null;
  }

  /**
   * Пропорционира ли се прагът с работното време — ОТ ВИДА ОСИГУРЕН, не от договора.
   *
   * <p>⚠️ Отговорът идва от {@code BgInsuredType.bgZeroWorkTime} през {@link
   * BgInsurableIncomeService#proratesWithWorkTime}, тоест от СЪЩИЯ ред, по който {@link
   * #insurableIncome} после ще смята. Затова е шев, а не изведено тук: второ извеждане би било
   * свободно да се размине с резолвера, а разминаването значи гейт, който вярва че се
   * пропорционира, срещу сметка, която не пропорционира.
   *
   * <p>⚠️ Датата трябва да е СЪЩАТА, с която {@link #insurableIncome} чете реда, инак двата прочита
   * могат да хванат различни периоди на един и същ вид осигурен. Днес съвпадат: сглобителят винаги
   * попълва {@code parameterReferenceDate} (началото на периода), а {@link #referenceDate} го
   * предпочита пред всичко друго. Фишът се подава само за да има резервна дата, ако някой ден вход
   * дойде без нея — тогава {@code null} би върнало „непознат вид“, тоест гейтът щеше да остане, а
   * не да падне тихо.
   */
  protected boolean proratesWithWorkTime(BgPayrollInput input, BgPaySlip slip) {
    return Beans.get(BgInsurableIncomeService.class)
        .proratesWithWorkTime(input.insuredTypeCode, referenceDate(input, slip));
  }

  /**
   * АКТИВНА ЛИ Е КООРДИНАЦИЯТА по КСО чл. 6 ал. 11 за този вход.
   *
   * <p>⚠️ ЕДИН въпрос, зададен на ЕДНО място, и точно същият, който {@link
   * BgIncomeCoordinationResolver.Declaration#isActive} задава надолу. Второ извеждане би било
   * свободно да се размине с правилото — а разминаване тук значи гейт, който вярва че координация
   * няма, срещу база, сметната като координирана.
   */
  protected boolean coordinated(BgPayrollInput input) {
    return input != null
        && BgInsurableIncomeService.coordinationOf(input.otherEmployerInsurableIncome).isActive();
  }

  /**
   * ОСИГУРИТЕЛНАТА БАЗА ЗА ТОЗИ ФИШ — цялата резолюция, на ЕДНО място.
   *
   * <p><b>⚠️ ИЗВАДЕНА ОТ {@link #contributions}, за да я ползва И заглавната сума.</b> Дотогава
   * веригата живееше вътре в {@code contributions} и {@link #recompute} нямаше как да стигне до нея
   * — затова заглавният „Осигурителен доход“ показваше сбора на ПЕРАТА. При координация двете са
   * РАЗЛИЧНИ числа: на фиша на Николай перата дават 1252,00, а вноските са сметнати върху 0,00.
   * Тоест екранът показваше доход, върху който НЕ е осигурявано — и точно това число щеше да отиде
   * в Д1.
   *
   * <p>⚠️ Викана два пъти (от осигуровките и от заглавната сума) тя дава ЕДИН отговор: няма
   * състояние, входовете са същите, а правилото е едно. Същият идиом като {@link #incomeTaxRate} и
   * {@link #disabilityRelief}, които {@code recompute} и генераторът също викат поотделно.
   */
  protected BgInsurableIncomeResult insurableIncomeFor(
      BgPaySlip slip, BgPayrollInput input, BgPayrollWorkTime.Decision workTime, boolean prorates) {
    // ⚠️ МОД НЕ СЕ ПИТА ПРИ КООРДИНАЦИЯ, а не се пита и после се подминава.
    // BgInsurableIncomeService
    // .resolve го игнорира изцяло, когато декларацията е активна — прагът е на ЛИЦЕТО за месеца и
    // вече е покрит от другото правоотношение. Заявка, чийто отговор не влиза в нито едно число, е
    // само още един начин веригата да падне по повод, от който не зависи.
    BgMinIncomeResult mod = coordinated(input) ? null : minIncomeFor(input, slip);
    BigDecimal gross = insuranceGrossFor(slip, input);

    // ⚠️ ГРАЖДАНСКИЯТ ДОГОВОР НАМАЛЯВА БРУТОТО, ПРЕДИ ТАВАНЪТ ДА ГО ПИПНЕ — КСО чл. 6 ал. 4.
    // Редът е самото правило: „възнаграждението, ВЪРХУ КОЕТО СЕ ДЪЛЖАТ вноски, се определя СЛЕД
    // намаляването му с разходите“, и чак то се сравнява с максималния осигурителен доход. Обратно
    // — таван, после приспадане — 2500,00 би станало 2111,64 и после 1583,73, вместо верните
    // 1875,00. Приспадане СЛЕД координацията по ал. 11 би било още по-невярно: тя реже вече
    // определената база до остатъка на ЛИЦЕТО, тоест работи върху число, което ал. 4 вече е
    // определила.
    if (BgCivilContractResolver.isCivilContract(input.insuredTypeCode)) {
      BgCivilContractResolver.Outcome civil = civilContractBase(gross, input, slip);
      if (!civil.isResolved()) {
        return BgInsurableIncomeResult.civilContractBlocked(civil.getDetail());
      }
      gross = civil.getBase();
    }

    return insurableIncome(gross, mod, input, workTime.factsFor(prorates));
  }

  /**
   * БАЗАТА НА ГРАЖДАНСКИЯ ДОГОВОР — приспадането и прагът, на ЕДНО място.
   *
   * <p>Шев, а не сметка: правилото е на {@link BgCivilContractResolver}, темпоралният прочит на МРЗ
   * — на {@link BgInsurableIncomeService#civilContractBase}, а тук стои само подаването на двата
   * входа от контракта и на СЪЩАТА дата, с която се четат таванът и видът осигурен.
   *
   * <p>⚠️ ДВАТА ВХОДА ИДВАТ ОТ {@code BgPayrollInput}, а не се четат втори път от договора. Втори
   * прочит е свободен да се размине с онзи, по който сглобителят е решавал — а разминаване тук
   * значи вноски върху една база и декларация върху друга.
   */
  protected BgCivilContractResolver.Outcome civilContractBase(
      BigDecimal gross, BgPayrollInput input, BgPaySlip slip) {
    return Beans.get(BgInsurableIncomeService.class)
        .civilContractBase(
            gross, input.expenseRate, input.insuredOnOtherGround, referenceDate(input, slip));
  }

  /**
   * БРУТОТО, ВЪРХУ КОЕТО СЕ НАЛАГАТ ПОДЪТ И ТАВАНЪТ — сборът на перата, или ИЗБРАНИЯТ доход.
   *
   * <p><b>⚠️ ЗА САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ БАЗАТА НЕ Е СБОР НА ПЕРА, И ТОВА НЕ Е ОПРОСТЯВАНЕ.</b>
   * КСО чл. 6 ал. 8 дословно: вноските „се дължат авансово: върху месечен осигурителен доход между
   * минималния и максималния месечен размер на дохода“. Тоест базата е ИЗБОР, обявен предварително,
   * а не следствие от начислени суми — самоосигуряващият се няма кой да му начисли възнаграждение.
   * Сборът на перата за него би бил НУЛА, а нула, вдигната до пода от {@code
   * BgInsurableIncomeService}, щеше да даде минимума на ВСЕКИ — правдоподобно число, което никой
   * контрол не хваща и което е вярно само за онези, които наистина са избрали минимума.
   *
   * <p><b>⚠️ РАЗКЛОНЯВА СЕ ПО НОСИТЕЛЯ, А СТОЙНОСТТА ИДВА ОТ ВХОДА.</b> Двете не са едно: носителят
   * е СТРУКТУРА на фиша и казва кой е питан, а входът е вече сглобеният контракт и е единственият
   * прочит на числото. Прочетена втори път от регистрацията тук, стойността щеше да е свободна да
   * се размине с онази, по която сглобителят е решавал — а разминаване тук значи вноски върху една
   * база и декларация върху друга.
   *
   * <p>⚠️ ПОДЪТ И ТАВАНЪТ СЕ НАЛАГАТ И ВЪРХУ ИЗБРАНОТО, по същия път като при заплатата. Изборът е
   * ограничен от закона („МЕЖДУ минималния и максималния“), а границите са темпорални и се сменят
   * по средата на 2026 г. — тоест едно и също избрано число е валидно за юли и под минимума за
   * август.
   */
  protected BigDecimal insuranceGrossFor(BgPaySlip slip, BgPayrollInput input) {
    if (BgSelfInsuredRules.isSelfInsured(slip)) {
      // ⚠️ И ТУК ИМА ВТОРО РАЗКЛОНЕНИЕ, ПО НОРМА. За кодове 41 и 96 базата НЕ е избор: чл. 4а1
      // ал. 1 я определя като половината от МРЗ, а чл. 4 ал. 9 — като минималния осигурителен
      // доход за самоосигуряващите се. Прочетен и там, изборът щеше да се размине със закона в
      // посока, която никой контрол не хваща — и двете числа са правдоподобни.
      // ⚠️ НУЛАТА ТУК НЕ Е ЛИПСА, А ПРЕДАВАНЕ НА ХОДА: BgInsurableIncomeService#applyFloor вдига
      // всяка база под законовия праг до него, тоест точно законовото число излиза от там. Второ
      // смятане тук би било второ мнение по един и същи въпрос.
      if (input == null) {
        return null;
      }
      return BgSelfInsuredRules.incomeIsChosen(input.insuredTypeCode)
          ? input.selfInsuredSelectedIncome
          : BigDecimal.ZERO;
    }
    return insuranceGross(slip);
  }

  /**
   * Декларираният чужд доход за този фиш, ЧЕТЕН САМО ОТ ЗАРЕДЕНИТЕ ОБЕКТИ — или {@code null}.
   *
   * <p>⚠️ Тънката врата към {@link #effectiveOtherPayerIncome} за пазачите: тя не иска нито Guice,
   * нито заявка, защото и фишът, и договорът му вече са в ръката. Оттам всеки пазач може да пита
   * „координация ли е това“ без да сглобява вход.
   *
   * <p>⚠️ Договорът се чете КАКТО Е ПОДАДЕН, без {@link #managedContract}: повторният прочит иска
   * база. За стойността на фиша това е без значение, а при стояща стойност на договора екранът,
   * сглобен от контекста на формата, може да я не носи — тогава заглавната сума пада на сбора на
   * перата. САМИТЕ ВНОСКИ не са засегнати: те минават през {@link #payrollInput}, който препрочита
   * договора. Записано, а не премълчано.
   */
  protected BigDecimal declaredOtherPayerIncome(BgPaySlip slip) {
    return slip == null ? null : effectiveOtherPayerIncome(slip, slip.getEmploymentContract());
  }

  /**
   * ГРАЖДАНСКИ ДОГОВОР ЛИ Е ТОЗИ ФИШ — четено САМО от заредените обекти, без заявка.
   *
   * <p>⚠️ ВТОРАТА ТЪНКА ВРАТА КЪМ ВЕРИГАТА, по същия довод като {@link #declaredOtherPayerIncome}:
   * {@link #insuranceIncomeHeader} се вика от ФОРМАТА при всяка промяна на перо, а сглобяването на
   * вход и прочитът на договора струват. Кодът обаче вече е в ръката — той стои на самия договор.
   *
   * <p>⚠️ И ЗАЩО ЗАГЛАВНАТА СУМА ИЗОБЩО ПИТА. За гражданския договор перата носят ПРИДОБИТИЯ доход
   * (2000,00 — лицето е получило каквото е получило), а вноските са върху намаления по чл. 6 ал. 4
   * (1500,00). Показан сборът на перата, екранът твърди осигурителен доход, върху който една
   * четвърт НЕ е осигурявана — точно разминаването, което координацията по ал. 11 вече веднъж
   * докара на фиша на Николай, и то с числото, което после отива в т. 21 на Д1.
   */
  protected boolean civilContractSlip(BgPaySlip slip) {
    // ⚠️ ВИДЪТ ОТ insuredTypeCodeOf, А НЕ ОТ ДОГОВОРА: замразеният печели. Чл. 42 или чл. 43 е
    // въпрос за ОНЗИ месец — договор, сменен на код 14 по-късно, не прави мартенското копие
    // граждански договор (и обратно).
    return slip != null
        && slip.getEmploymentContract() != null
        && BgCivilContractResolver.isCivilContract(insuredTypeCodeOf(slip));
  }

  /**
   * ЗАГЛАВНАТА ДАНЪЧНА ОСНОВА — по чл. 42 ал. 3, а за граждански договор по чл. 43 ал. 1.
   *
   * <p><b>⚠️ ЕДНО МЯСТО ЗА РАЗКЛОНЕНИЕТО, ПО СЪЩИЯ ДОВОД КАТО {@link #incomeTaxRate}.</b> През него
   * минават ДВАМА читатели: {@link #recompute} пълни {@code bgTaxBase} и оттам {@code bgTaxAmount},
   * а {@link #generateIncomeTax} подава същия избор на генератора. Разклонени поотделно, двата са
   * свободни да се разминат — а разминаване значи перо и заглавна част, които не си съвпадат, тоест
   * {@code BgPaySlipResolver#refuseTaxMismatch} блокира ВЕРЕН фиш.
   *
   * <p>⚠️ Питането е СЛИП-ЛОКАЛНО ({@link #civilContractSlip}) и не струва нищо — {@code recompute}
   * се вика от ФОРМАТА при всяка промяна на перо. Същият довод като при {@link
   * #insuranceIncomeHeader}.
   */
  protected BigDecimal taxBaseHeader(BgPaySlip slip, List<BgPaySlipLine> lines) {
    if (civilContractSlip(slip)) {
      return BgPaySlipResolver.businessTaxableBase(lines, businessExpense(slip));
    }
    return BgPaySlipResolver.taxableBase(lines, disabilityRelief(slip));
  }

  /**
   * НОРМАТИВНИТЕ РАЗХОДИ ПО ЧЛ. 29 АЛ. 1 ЗДДФЛ за този фиш, или {@code null} — <b>ОТКАЗ</b>.
   *
   * <p><b>⚠️ ПРИСПАДАТ СЕ ОТ ОБЛАГАЕМИТЕ НАЧИСЛЕНИЯ, А НЕ ОТ ОСИГУРИТЕЛНОТО БРУТО.</b> Чл. 29 ал. 1
   * реже „придобитият доход“, а придобитият доход за данъка са перата, обявени за облагаеми ({@code
   * BgPaySlipResolver#accruedTaxableTotal}) — същият сбор, от който тръгва и {@code
   * BgPaySlipResolver#taxBase}. Осигурителната страна тръгва от СВОЕТО бруто ({@link
   * #insuranceGrossFor}), защото КСО чл. 6 ал. 4 казва „възнаграждението“. На нормалния граждански
   * фиш двете съвпадат; там, където не съвпадат, всяка норма реже от своето си число, вместо едната
   * да приспадне процент, сметнат от чуждото.
   *
   * <p><b>⚠️ ФОРМУЛАТА Е ОБЩАТА</b> — {@code BgCivilContractResolver#expenseOf}, същата, през която
   * минава и приспадането по КСО чл. 6 ал. 4. Преписана тук, тя е свободна да се размине по
   * закръгляне, а разлика от една стотинка между двете основи на един фиш е точно правдоподобното
   * число, което никой контрол не хваща.
   *
   * <p><b>⚠️ СТАВКАТА Е ОТ КАТАЛОГА, НЕ ЗАКОВАНА.</b> {@code
   * EmploymentContract.bgIncomeType.expenseRate} — 25 на сто за извънтрудови по чл. 29 ал. 1 т. 3,
   * 40 за авторски, занаят, адвокат и нотариус по т. 2, 60 за непреработена земеделска продукция по
   * т. 1. Закована „25“ тук би обложила авторския договор с една четвърт повече, отколкото дължи, и
   * би поискала билд при всяка редакция на чл. 29 — а ДВ бр. 30/2026 промени т. 2 буква „г“ едва
   * тази година.
   *
   * <p>⚠️ Чете САМО заредените обекти — нито Guice, нито заявка, — по същия довод като {@link
   * #civilContractSlip}: {@code recompute} се вика от формата при всяка промяна на перо, а видът
   * доход виси направо на договора. ⚠️ Това Е полето, което {@code
   * BgPayrollInputBuilder#expenseRateOf} чете за осигуровките — ЕДНА колона, не два източника.
   * Връзката е m2o, а не разрешаване по дата, тоест двата прочита не могат да се разминат
   * темпорално.
   *
   * <p><b>⚠️ {@code null} Е ОТКАЗ, А НЕ НУЛА</b> — виж {@code
   * BgCivilContractResolver.Status#MISSING_EXPENSE_RATE}. Нула би значела „разходи не се
   * признават“, а данъкът щеше тихо да излезе ЗАВИШЕН.
   */
  protected BigDecimal businessExpense(BgPaySlip slip) {
    if (slip == null) {
      return null;
    }
    BigDecimal income = BgPaySlipResolver.accruedTaxableTotal(safe(slip.getBgPaySlipLineList()));
    BgPayrollRegime regime = BgPayrollRegime.of(slip);
    if (regime != null) {
      return BgCivilContractResolver.expenseOf(income, regime.expenseRate);
    }
    EmploymentContract contract = slip.getEmploymentContract();
    BgIncomeType incomeType = contract == null ? null : contract.getBgIncomeType();
    return BgCivilContractResolver.expenseOf(
        income, incomeType == null ? null : incomeType.getExpenseRate());
  }

  /**
   * ЗАГЛАВНИЯТ „ОСИГУРИТЕЛЕН ДОХОД“ — базата, ВЪРХУ КОЯТО НАИСТИНА Е ОСИГУРЯВАНО.
   *
   * <p><b>Без координация И БЕЗ ГРАЖДАНСКИ ДОГОВОР нищо не се мени и нищо не се пита:</b> сборът на
   * перата, обявени за осигурителен доход, точно както досега. Той е и един от трите агрегата на
   * декларацията.
   *
   * <p><b>⚠️ ПРИ ГРАЖДАНСКИ ДОГОВОР — СЪЩОТО РАЗМИНАВАНЕ, ПО ДРУГА ПРИЧИНА.</b> КСО чл. 6 ал. 4
   * реже базата с нормативните разходи, а перата носят придобития доход: 2000,00 срещу 1500,00.
   * Показан сборът, екранът твърди осигурителен доход, върху който една четвърт НЕ е осигурявана —
   * и числото от заглавната част е точно това, което влиза в т. 21 на Д1. Под прага по чл. 4 ал. 3
   * т. 5 разликата е още по-голяма: базата е НУЛА, а перата пак носят цялото възнаграждение.
   *
   * <p><b>⚠️ ПРИ КООРДИНАЦИЯ — базата, ползвана за вноските.</b> Двете се разминават по устройство:
   * перата носят ПРИДОБИТИЯ доход (той не се реже — лицето е получило каквото е получило), а
   * осигурява се само остатъкът до тавана. На фиша на Николай това е 1252,00 срещу 0,00. Показан
   * сборът, екранът твърди осигурителен доход, върху който НИТО ЕДНА вноска не е начислена — а
   * числото от заглавната част е точно това, което Д1 на ТОЗИ работодател ще носи.
   *
   * <p><b>⚠️ ЗАЩО ВЪПРОСЪТ Е ПЪРВИ, А ВЕРИГАТА — ВТОРА.</b> {@link #recompute} се вика от ФОРМАТА
   * при всяка промяна на перо. Питано безусловно, {@link #contributions} би струвало сглобяване на
   * вход и прочит на договора на всяко движение по екрана, а десет заварени теста, на които този
   * шев не е подменен, паднаха точно така. Слип-локалният въпрос ({@link
   * #declaredOtherPayerIncome}) не иска нищо, а веригата се пуска само за фишовете, за които има
   * значение.
   *
   * <p><b>⚠️ БАЗАТА СЕ ЧЕТЕ ОТ {@code BgContributionLine}, НЕ ОТ ПЕРАТА НА ФИША.</b> И това е
   * съществено: при изчерпан таван базата е НУЛА, всяка вноска се закръгля до 0,00, а нулева сума
   * НЕ прави перо ({@code BgContributionLineService#problem} — „ред с 0,00 би твърдял, че вноска се
   * дължи“). Тоест на фиша на Николай пера от 5xx ИЗОБЩО НЯМА и четене от тях би върнало пак
   * 1252,00 — поправката нямаше да работи точно за фиша, за който съществува. Вноската като ОБЕКТ
   * обаче я има и носи базата си.
   *
   * <p>⚠️ Първата вноска стига: резолверът смята всички редове на една заявка върху една база.
   *
   * <p>⚠️ При отказ или при нула вноски остава сборът на перата. Нула тук би изглеждала като
   * ЗАКОННАТА нула на Николай, а причината е съвсем друга; отказът се вижда от {@link
   * #contributionRefusal} при потвърждаване.
   */
  protected BigDecimal insuranceIncomeHeader(BgPaySlip slip) {
    BigDecimal fromLines =
        BgPaySlipResolver.insuranceIncomeBase(
            BgCompensationParts.withoutPartLines(safe(slip.getBgPaySlipLineList())));
    if (declaredOtherPayerIncome(slip) == null && !civilContractSlip(slip)) {
      return fromLines;
    }
    BgContributionResult result = contributions(slip);
    if (result == null || !result.isResolved() || result.getLines().isEmpty()) {
      return fromLines;
    }
    BigDecimal base = result.getLines().get(0).getBase();
    return base == null ? fromLines : base.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Осигурителната база: min(max(бруто, МОД, законов праг), таван). Правилото е на услугата.
   *
   * <p><b>⚠️ ИЛИ КООРДИНИРАНАТА БАЗА по КСО чл. 6 ал. 11, когато лицето е декларирало доход при
   * друг платец за същия месец.</b> Тогава горната формула НЕ се прилага: базата е остатъкът до
   * тавана и МОД подът по ТОВА правоотношение отпада — прагът е на ЛИЦЕТО за месеца, а не на всеки
   * договор поотделно. Кое от двете важи решава {@code BgInsurableIncomeService}, не този шев: тук
   * се подава само декларираното.
   *
   * <p>⚠️ Декларираното идва от ВХОДА ({@code BgPayrollInput.otherEmployerInsurableIncome}), а не
   * се чете втори път от фиша. Един прочит, едно число — по същия довод, по който ставката и
   * облекчението се ПОДАВАТ на генератора на данъка: втори прочит е свободен да се размине с
   * първия, а разминаване тук значи вноски върху една база и данък върху друга.
   */
  protected BgInsurableIncomeResult insurableIncome(
      BigDecimal gross, BgMinIncomeResult mod, BgPayrollInput input, BgWorkTimeFacts facts) {
    // ⚠️ ДАТАТА Е НА ПЕРИОДА, не днешната: копие за 07/2026 се осигурява до 2111,64, колкото и
    // късно да се пресмята. Изведена ТУК и подадена на шева, тя е видима за свидетеля.
    return insurableIncomeOn(gross, mod, input, facts, referenceDate(input, null));
  }

  /** Подът и таванът за ДАТАТА — шевът към базата (параметрите и видът осигурен). */
  protected BgInsurableIncomeResult insurableIncomeOn(
      BigDecimal gross,
      BgMinIncomeResult mod,
      BgPayrollInput input,
      BgWorkTimeFacts facts,
      LocalDate date) {
    return Beans.get(BgInsurableIncomeService.class)
        .resolve(
            gross,
            mod,
            input.insuredTypeCode,
            date,
            facts,
            BgInsurableIncomeService.coordinationOf(input.otherEmployerInsurableIncome));
  }

  /**
   * Ставката ТЗПБ по РАЗДЕЛА на икономическата дейност (две цифри), или {@code null}.
   *
   * <p>⚠️ {@code divisionCode}, не пълният НКИД код: {@code bg_tzpb_rate} е по раздел, а {@code
   * BgPayrollInput.nkidCode} носи пълния код — затова тук се чете от {@code BgNkid}, а не от входа.
   * {@code null} НЕ е нула: резолверът добавя ТЗПБ ред само когато има ставка, и точно затова
   * липсата ѝ не бива да се подразбира като „нула на сто“.
   */
  protected BigDecimal tzpbRate(BgPaySlip slip, LocalDate date) {
    BgPayrollRegime regime = BgPayrollRegime.of(slip);
    String division;
    if (regime != null) {
      division = regime.tzpbDivision;
    } else {
      Company company = companyOf(slip);
      BgNkid nkid = company != null ? company.getBgNkid() : null;
      division = nkid != null ? nkid.getDivisionCode() : null;
    }
    if (division == null || division.trim().isEmpty() || date == null) {
      return null;
    }
    return Beans.get(BgInsuranceCalculationService.class).findTzpbRate(division.trim(), date);
  }

  /** Зареждането на редовете по ключа и извикването на резолвера — една стъпка, един шев. */
  protected BgContributionResult calculate(BgContributionRequest request) {
    return Beans.get(BgInsuranceCalculationService.class).calculate(request);
  }

  // ── малките четци ───────────────────────────────────────────────────────

  /**
   * БРУТОТО ЗА ОСИГУРЯВАНЕ — перата, обявени за осигурителен доход, НЕ целият бруто.
   *
   * <p>⚠️ Различно нещо от {@code bgGrossTotal}. Резолверът на фиша вече прави разликата: {@code
   * insuranceIncomeBase} брои само перата с {@code bgIsInsuranceIncome}, а {@code grossTotal} брои
   * и обезщетенията. Подът и таванът се налагат ВЪРХУ първото — второто би осигурило суми, които не
   * са осигурителен доход.
   */
  protected BigDecimal insuranceGross(BgPaySlip slip) {
    // ⚠️ БЕЗ ОБЕЗЩЕТЕНИЕТО ПО ВИД 27: то се осигурява по СВОЙ режим, със своя заявка
    // (compensationContributions) — в базата на вид 01 би понесло целия пакет фондове.
    return BgPaySlipResolver.insuranceIncomeBase(
        BgCompensationParts.withoutPartLines(safe(slip.getBgPaySlipLineList())));
  }

  /**
   * Датата, по която се избират темпоралните редове: началото на периода.
   *
   * <p>Взима се от входа, а при липса — от периода на фиша. Двата източника са един и същ период;
   * вторият е за повикващите, които подават вход без период.
   */
  protected LocalDate referenceDate(BgPayrollInput input, BgPaySlip slip) {
    if (input != null && input.parameterReferenceDate != null) {
      return input.parameterReferenceDate;
    }
    if (slip != null && slip.getPeriod() != null) {
      return slip.getPeriod().getFromDate();
    }
    return null;
  }

  /**
   * ЛИЦЕТО ПО ДОГОВОРА НА ФИША, или {@code null}. Шев — подменя се в тестовете.
   *
   * <p>⚠️ Шев, защото {@link #disabilityRelief} чете решението за намалена работоспособност: то
   * живее на {@code Employee}, а тестовете на решението нямат нито Guice, нито база. Нищо не се
   * override-ва днес, тоест видимостта не поваля заварен override — виж обратния случай в {@code
   * BgPaySlipServiceTest}, където нов шев в {@code confirm()} наложи промяна в стубовете.
   */
  protected Employee employeeOf(BgPaySlip slip) {
    if (slip == null) {
      return null;
    }
    // ⚠️ И ДВАТА НОСИТЕЛЯ ВОДЯТ ДО ЕДНО И СЪЩО ЛИЦЕ, и това е самата причина регистрацията да е на
    // Employee, а не на Partner: решението за намалена работоспособност (чл. 18 ал. 2 ЗДДФЛ),
    // датата на раждане, изборът по ДЗПО и признакът „пенсионер“ живеят там и се четат по един и
    // същ начин за наетия и за самоосигуряващия се. Отделен читател за втория щеше да е втори
    // източник за всяко от четирите.
    if (slip.getBgSelfInsuredRegistration() != null) {
      return slip.getBgSelfInsuredRegistration().getEmployee();
    }
    EmploymentContract contract = slip.getEmploymentContract();
    return contract == null ? null : contract.getEmployee();
  }

  /** ⚠️ {@code payCompany} — дружеството, което ПЛАЩА по договора, не активното дружество. */
  private static Company companyOf(BgPaySlip slip) {
    EmploymentContract contract = slip == null ? null : slip.getEmploymentContract();
    return contract == null ? null : contract.getPayCompany();
  }

  /** Номерът на друг ПОТВЪРДЕН или ИЗПЛАТЕН фиш, който вече носи това обезщетение. */
  protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
    List<String> found =
        JPA.em()
            .createQuery(
                "SELECT s.reference FROM BgPaySlipLine l JOIN l.paySlip s"
                    + " WHERE l.bgCompensation.id = :doc AND s.statusSelect IN ('CONFIRMED','PAID')"
                    // ⚠️ Само перото 9xx — вноските на вид 27 носят същата препратка.
                    + " AND l.bgLineNumber BETWEEN 900 AND 999"
                    + " AND (:id IS NULL OR s.id <> :id)",
                String.class)
            .setParameter("doc", doc.getId())
            .setParameter("id", slip.getId())
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * ДАНЪЧНАТА СТАВКА ЗА ПЕРИОДА — 10 на сто, а за МОРСКО ЛИЦЕ 1 на сто (чл. 42 ал. 4 ЗДДФЛ).
   *
   * <p><b>⚠️ ЕДНО МЯСТО ЗА ДВАТА ЧИТАТЕЛЯ, И ТОВА Е ЦЯЛАТА ПРИЧИНА ИЗБОРЪТ ДА Е ТУК.</b> През този
   * метод минават И заглавната сума ({@link #recompute} пълни {@code bgTaxAmount}), И ставката,
   * подадена на {@link BgIncomeTaxService#generate}. Втори прочит на другото място би бил свободен
   * да се размине с този — а разминаване значи перо и заглавна част, които не си съвпадат, тоест
   * {@code BgPaySlipResolver#refuseTaxMismatch} блокира ВЕРЕН фиш. Точно това предвижда и {@code
   * BgIncomeTaxService}: ставката се ПОДАВА на генератора, за да остане решението едно.
   *
   * <p><b>⚠️ ПРИЗНАКЪТ Е ДАННИ, НЕ СПИСЪК В JAVA.</b> Кой вид осигурен е морско лице се чете от
   * {@code BgInsuredType.bgIsSeafarer} — днес вдигнат за един-единствен ред, код 92. Списък {@code
   * if ("92".equals(code))} тук би заковал номенклатурата в кода, а тя е темпорална (ДВ бр. 15/2024
   * премахна кодове 07, 21, 23 и 93). Същият довод стои в домейн-коментара на {@code
   * BgInsuredType}: „флаговете са ДАННИ (не hardcoded списъци в Java)“.
   *
   * <p><b>⚠️ ВИДЪТ ОСИГУРЕН СЕ ВЗИМА ВАЛИДЕН ЗА ДАТАТА НА ПЕРИОДА</b>, през {@link #insuredTypeFor}
   * — същият темпорален прочит, който {@code BgInsurableIncomeService} прави за долния праг. Взет
   * за днешната дата, коригиращ фиш за минал месец щеше да чете ДНЕШНАТА редакция на
   * номенклатурата.
   *
   * <p><b>⚠️ НЕРАЗРЕШИМ ИЛИ ПРАЗЕН ВИД ОСИГУРЕН СЕ ЧЕТЕ КАТО „НЕ Е МОРСКО ЛИЦЕ“</b> — тоест общите
   * 10 на сто. Същата конвенция като {@code BgInsurableIncomeService#floorCodeOf}: непознатият вид
   * не дава законов праг и пазенето срещу празния вид НЕ се преповтаря тук. Отказът за непопълнен
   * вид осигурен вече си има дом и назована причина — {@code BgPayrollInputBuilder.REFUSALS} го
   * обявява и веригата на осигуровките спира на него. Втори пазач тук би сменил само кое съобщение
   * вижда потребителят. А посоката на подразбирането е ВЯРНАТА: намалената ставка е ИЗКЛЮЧЕНИЕ,
   * което се обявява; сгрешено вдигнат флаг би удържал 1 на сто вместо 10 и разликата не изглежда
   * като грешка на нито един фиш.
   *
   * <p><b>⚠️ {@code null} Е ОТКАЗ, НЕ 10 НА СТО.</b> Липсващ {@code INCOME_TAX_RATE_SEAFARER} за
   * периода НЕ се замества мълчаливо с общата ставка: това би удържало 10 на сто от морско лице и
   * фишът щеше да изглежда напълно сметнат. Връща се {@code null} и {@code BgIncomeTaxService}
   * ОТКАЗВА с назована причина — фишът остава непотвърдим, а липсата се вижда.
   */
  protected BigDecimal incomeTaxRate(BgPaySlip slip) {
    LocalDate date =
        slip.getPeriod() == null || slip.getPeriod().getFromDate() == null
            ? LocalDate.now()
            : slip.getPeriod().getFromDate();
    BgInsuredType insuredType = insuredTypeFor(insuredTypeCodeOf(slip), date);
    String code = isSeafarer(insuredType) ? PARAM_INCOME_TAX_RATE_SEAFARER : PARAM_INCOME_TAX_RATE;
    return payrollParameter(code, date);
  }

  /**
   * МОРСКО ЛИ Е ЛИЦЕТО НА ТОЗИ ФИШ — публичната врата към прочита, който {@link #incomeTaxRate}
   * вече прави.
   *
   * <p><b>⚠️ ВРАТА, КОЯТО ДЕЛЕГИРА, А НЕ РАЗШИРЕН ШЕВ.</b> Въпросът дотрябва и на справката по чл.
   * 73 ал. 6: § 1 т. 54 от ДР прави морското лице СВОЙ код на дохода (103), различен от 101.
   * Отговорът обаче се вади през три частни стъпки — кодът на вида осигурен от фиша, редът, в сила
   * за периода, и флагът върху него. Вдигането на всяка от трите до публична би дало три нови шева
   * за поддържане и би отворило вътрешности, които никой не е поискал; преписан в справката,
   * прочитът би станал ВТОРИ темпорален филтър над същата таблица — точно това, срещу което
   * предупреждава {@link #insuredTypeFor}. Затова навън излиза ЕДИН отговор, а стъпките остават
   * където са.
   *
   * <p><b>⚠️ ДАТАТА Е НАЧАЛОТО НА ПЕРИОДА НА ФИША</b>, дословно както в {@link #incomeTaxRate}:
   * номенклатурата е темпорална и коригиращ фиш за минал период трябва да получи реда, валиден
   * ТОГАВА. Различна дата тук би дала фиш, обложен с 1 на сто, и справка, която го подава като код
   * 101.
   *
   * <p>⚠️ Липсващ ред за периода се чете като „не е морско лице“ — същият прочит като {@link
   * #isSeafarer}: намалената ставка е изключение, което се ОБЯВЯВА. Отказът при липсващ параметър е
   * работа на {@link #incomeTaxRate} и остава негов.
   */
  public boolean isSeafarerSlip(BgPaySlip slip) {
    if (slip == null) {
      return false;
    }
    LocalDate date =
        slip.getPeriod() == null || slip.getPeriod().getFromDate() == null
            ? LocalDate.now()
            : slip.getPeriod().getFromDate();
    return isSeafarer(insuredTypeFor(insuredTypeCodeOf(slip), date));
  }

  /**
   * СТАВКАТА ЗА ГОДИШНОТО ПРЕИЗЧИСЛЯВАНЕ ПО ЧЛ. 49 — публичната врата към {@link
   * #payrollParameter}.
   *
   * <p><b>⚠️ ВРАТА, КОЯТО ДЕЛЕГИРА, А НЕ ПРЕПИСАН ПРОЧИТ.</b> Колона 18 на справката по чл. 73 ал.
   * 6 иска „колона 17 х 10%“. Десетте на сто вече живеят в {@code BgPayrollParameter} под {@code
   * INCOME_TAX_RATE} и оттам ги чете месечният авансов данък по чл. 42. Втори прочит на същата
   * таблица в друг клас би бил ВТОРИ темпорален филтър над едни и същи редове — същият довод, с
   * който {@link #isSeafarerSlip} съществува. А заковани „10“ в кода на преизчисляването, двете
   * числа щяха да се разминат в деня, в който ставката се смени.
   *
   * <p><b>⚠️ ДАТАТА Е 31 ДЕКЕМВРИ НА ДАНЪЧНАТА ГОДИНА, И ТОВА Е ДАТАТА НА САМАТА НОРМА.</b> Чл. 49
   * ал. 1 върти цялото преизчисляване около „към 31 декември на данъчната година“. Прочетена с
   * {@code LocalDate.now()}, ставката за минала година щеше да дойде от днешния ред.
   *
   * <p><b>⚠️ И ТУК НЯМА МОРСКА СТАВКА.</b> Морското лице влиза в годишната основа СВИТО на 10 на
   * сто по чл. 25 ал. 3 (ред 8.2 на образеца), а не с намалена ставка: 10 на сто основа × 10 на сто
   * ставка дава същото 1 на сто, което чл. 42 ал. 4 удържа месечно. Подадена тук, ставката {@code
   * INCOME_TAX_RATE_SEAFARER} щеше да свие дохода му ВТОРИ път и да го обложи с 0,1 на сто.
   *
   * <p>⚠️ {@code null} значи „параметърът не е зареден за тази година“ и е ОТКАЗ, а не 10 на сто —
   * повикващият спира и назовава липсата.
   */
  public BigDecimal annualIncomeTaxRate(int taxYear) {
    return annualParameter(PARAM_INCOME_TAX_RATE, taxYear);
  }

  /**
   * ЕДИН СКАЛАРЕН ПАРАМЕТЪР, В СИЛА КЪМ 31 ДЕКЕМВРИ НА ДАНЪЧНАТА ГОДИНА.
   *
   * <p><b>⚠️ ОБЩАТА ВРАТА КЪМ {@link #payrollParameter}, А НЕ ВТОРИ ПРОЧИТ НА ТАБЛИЦАТА.</b>
   * Годишните облекчения по чл. 18, чл. 22в и чл. 22г искат същия темпорален филтър, който този
   * клас вече прави за ставките и за месечното облекчение. Преписан в подсистемата на справката,
   * той щеше да е ВТОРИ филтър над едни и същи редове — и един ден щеше да чете за друга дата.
   *
   * <p>⚠️ {@code null} значи „параметърът не е зареден за тази година“. Това е ОТКАЗ, а не нула:
   * незаредена сума на облекчение и „лицето няма право на облекчение“ дават еднакъв ефект върху
   * данъка и напълно различна поправка. Повикващият е този, който ги различава.
   */
  public BigDecimal annualParameter(String code, int taxYear) {
    return payrollParameter(code, LocalDate.of(taxYear, 12, 31));
  }

  /**
   * НАЧИСЛЯВА ЛИ СЕ КЛАС ПРОСЛУЖЕНО ВРЕМЕ НА ЛИЦЕТО ОТ ТОЗИ ФИШ — публичната врата към признака.
   *
   * <p><b>ОСНОВАНИЕТО е НСОРЗ чл. 12 ал. 1:</b> допълнителното трудово възнаграждение за придобит
   * трудов стаж и професионален опит се заплаща „на работниците и служителите“, в процент върху
   * основната работна заплата, „определена с ИНДИВИДУАЛНИЯ ТРУДОВ ДОГОВОР“. Чл. 2 ал. 2 стеснява
   * цялата наредба до работещите ПО ТРУДОВО ПРАВООТНОШЕНИЕ. Договорът за управление и контрол (вид
   * осигурен 10), гражданският договор (14), кооперативният член без ТПО (09) и самоосигуряващото
   * се лице са ИЗВЪН нормата.
   *
   * <p><b>⚠️ И СЛУЖЕБНИТЕ ПРАВООТНОШЕНИЯ СА ИЗВЪН</b> — кодове 05, 06, 17, 24 и 83 (държавна служба
   * и специални закони: Наредба Н-13 т. 12, НЗСДА чл. 19), както и 87, където трудово
   * правоотношение ИМА, но работодателят е ЧУЖД. Ако някому от тях се дължи допълнително
   * възнаграждение за прослужено време, то идва от друг акт и с друга ставка — тоест друго перо, не
   * това.
   *
   * <p><b>⚠️ СУБСИДИРАНАТА ЗАЕТОСТ (25, 72) Е ВЪТРЕ</b>, и разликата с горните е точно тази: там се
   * работи по трудов договор по КТ, а наредбата няма изключение за програмите за заетост. Думите
   * „не се осигуряват за безработица“ в наименованията им описват ОСИГУРИТЕЛНИЯ обхват, не правото
   * на клас — той е за придобит ТРУДОВ СТАЖ. Пълният списък по кодове живее в домейн-коментара на
   * {@code BgInsuredType} — на едно място, до данните.
   *
   * <p><b>⚠️ ВРАТА, КОЯТО ДЕЛЕГИРА, А НЕ РАЗШИРЕН ШЕВ</b> — дословно същият избор като {@link
   * #isSeafarerSlip}: отговорът се вади през три частни стъпки (кодът на вида осигурен от фиша,
   * редът, в сила за периода, и флагът върху него), а навън излиза ЕДИН отговор. Читателят му е
   * {@code BgEarningsService}; преписан там, прочитът щеше да стане ВТОРИ темпорален филтър над
   * същата таблица.
   *
   * <p><b>⚠️ ПРИЗНАКЪТ Е ДАННИ, НЕ СПИСЪК В JAVA</b> — {@code
   * BgInsuredType.bgEntitledToLengthOfServiceBonus}, по същия довод като {@code bgIsSeafarer}:
   * номенклатурата е темпорална и се сменя с редакция на Наредба Н-13, не с комит.
   *
   * <p><b>⚠️ И НЕ СЕ ИЗВЕЖДА ОТ „ИМА ЛИ ГВРС“</b>, макар т. 29/т. 30 на Д1 да четат трудовото
   * правоотношение точно оттам ({@code BgD1Emitter#gvrsEligibleCodes}). Двете множества се
   * разминават на ВИД ОСИГУРЕН 09 — „членове на кооперации, получаващи възнаграждение за работа БЕЗ
   * трудово правоотношение в кооперацията“ — който НОСИ ред за фонд ГВРС (публикуваната таблица на
   * НАП към 01.08.2026 г. изключва от ГВРС само 10, 14 и 27) и няма ТПО. Преизползван, онзи сигнал
   * щеше да начисли клас точно на него, тихо.
   *
   * <p><b>⚠️ {@code null} Е ОТКАЗ, НЕ „НЕ“.</b> Липсващ ред за периода и непопълнен вид осигурен са
   * СЪСТОЯНИЕ НА СРЕДАТА, не твърдение за лицето. Прочетени като „не“, те биха отнели класа на
   * всеки нает мълчаливо; прочетени като „да“ — биха го дали на управителя по ДУК също мълчаливо.
   * Затова тук излиза трета стойност и {@code BgEarningsResolver#seniorityLine} я ОБЯВЯВА. Това е
   * разликата с {@link #isSeafarerSlip}, който връща {@code boolean}: там подразбирането има вярна
   * посока (намалената ставка е изключение), тук — няма.
   *
   * <p><b>⚠️ ДАТАТА Е НАЧАЛОТО НА ПЕРИОДА НА ФИША</b>, дословно както в {@link #incomeTaxRate} и
   * {@link #isSeafarerSlip}: номенклатурата е темпорална и коригиращ фиш за минал период трябва да
   * получи реда, валиден ТОГАВА.
   */
  public Boolean seniorityBonusEntitlement(BgPaySlip slip) {
    if (slip == null) {
      return null;
    }
    LocalDate date =
        slip.getPeriod() == null || slip.getPeriod().getFromDate() == null
            ? LocalDate.now()
            : slip.getPeriod().getFromDate();
    BgInsuredType insuredType = insuredTypeFor(insuredTypeCodeOf(slip), date);
    // ⚠️ Генерираният getter за Boolean НИКОГА не връща null — връща FALSE и за непопълнена
    // колона, — затова „не е четено“ се различава ЕДИНСТВЕНО по липсващия РЕД, не по стойността.
    // Точно затова колоната се чете така, а не с `= false` в заявка: до App Install всичките ѝ
    // редове стоят NULL (Property#set излиза по `old == value`) и биха се прочели като „не“.
    return insuredType == null ? null : insuredType.getBgEntitledToLengthOfServiceBonus();
  }

  /**
   * ДАНЪКЪТ НА ТОЗИ ФИШ Е АЛТЕРНАТИВНИЯТ ПО ЧЛ. 41А ЗДДФЛ, А НЕ МЕСЕЧНИЯТ ПО ЧЛ. 42.
   *
   * <p><b>⚠️ ЕДИН ПРОЧИТ ЗА ДВАТА ЧИТАТЕЛЯ</b>, по същия довод като при {@link #incomeTaxRate}:
   * през това решение минават И заглавната сума ({@link #recompute}), И генераторът на перото.
   * Разминат ли се, {@code BgPaySlipResolver#refuseTaxMismatch} блокира ВЕРЕН фиш.
   *
   * <p><b>⚠️ ПРИЗНАКЪТ Е ВИДЪТ ОСИГУРЕН, ЧЕТЕН ОТ ФИША</b> — единственото място, което знае и двата
   * носителя (трудов договор и регистрация на самоосигуряващо се лице). Списъкът с кодове живее в
   * {@code BgSelfInsuredRules.ALT_TAX_CODES} заедно с нормата си.
   */
  protected boolean hasAlternativeTax(BgPaySlip slip) {
    return BgSelfInsuredRules.alternativeTaxApplies(insuredTypeCodeOf(slip));
  }

  /**
   * МЕСЕЧНИЯТ РАЗМЕР НА АЛТЕРНАТИВНИЯ ДАНЪК ПО ЧЛ. 41А ЗДДФЛ за периода на фиша, или {@code null}.
   *
   * <p><b>⚠️ ЕДНО ЧИСЛО НА МЕСЕЦ, БЕЗ ОСНОВА И БЕЗ СТАВКА.</b> Ал. 1: „облагат се ЕДНОКРАТНО с
   * алтернативен данък в размер на 30 лв. за ВСЕКИ МЕСЕЦ, през който се извършва дейността“. Един
   * фиш е един месец, тоест точно една такава сума — умножение тук би било второ броене на месеца.
   *
   * <p><b>⚠️ {@code null} Е ОТКАЗ, НЕ НУЛА и НЕ „общите 10 на сто“</b> — виж {@code
   * BgIncomeTaxService#applyAlternative}. Чл. 41а не е по-обща версия на чл. 42, а друг данък:
   * заместен с процент върху основа, той би дал число, което изглежда напълно сметнато.
   *
   * <p><b>⚠️ И ЕДНО, КОЕТО ТОЗИ МОДУЛ НЕ ТВЪРДИ.</b> Ал. 2 възлага удържането на държавните
   * предприятия по чл. 163 ал. 1 от Закона за горите „непосредствено преди издаването на
   * позволително“, а Наредба Н-13 чл. 1 т. 6 прави същите предприятия подател на Д1 и Д6 за тези
   * лица. Тоест фишът тук е ТЕХЕН. Дали позволително наистина е издадено за месеца, е факт извън
   * фиша — затова сумата се взима от нормата, а не се предполага от дейност.
   */
  protected BigDecimal alternativeTax(BgPaySlip slip) {
    LocalDate date =
        slip.getPeriod() == null || slip.getPeriod().getFromDate() == null
            ? LocalDate.now()
            : slip.getPeriod().getFromDate();
    return payrollParameter(PARAM_ALT_TAX_ART41A, date);
  }

  /**
   * МЕСЕЧНОТО ДАНЪЧНО ОБЛЕКЧЕНИЕ ЗА НАМАЛЕНА РАБОТОСПОСОБНОСТ за този фиш — {@code 0} или сумата.
   *
   * <p><b>═══ НОРМАТА, ДОСЛОВНО ═══</b> Сверено срещу официалния ЗДДФЛ.
   *
   * <p><b>Чл. 18 ал. 2:</b> „Месечната данъчна основа по чл. 42, ал. 2 за доходи от трудови
   * правоотношения на лица с 50 и с над 50 на сто намалена работоспособност се намалява с 660 лв.,
   * включително за месеца на настъпване на неработоспособността и за месеца на изтичане срока на
   * валидност на решението.“
   *
   * <p><b>Чл. 42 ал. 3:</b> „Месечната данъчна основа се намалява със: 1. данъчното облекчение за
   * намалена работоспособност по чл. 18, ал. 2; 2. данъчното облекчение по чл. 19, ал. 2, когато
   * сумите са удържани от работодателя при изплащане на дохода от трудово правоотношение.“
   *
   * <p><b>⚠️ РЕДЪТ Е ЧАСТ ОТ НОРМАТА, НЕ ПОДРЕДБА.</b> Ал. 2 дава основата (облагаем доход минус
   * личните осигуровки), ал. 3 я НАМАЛЯВА с облекчението, и чак ал. 4 умножава по ставката. Тоест
   * облекчението е ПРЕДИ ставката — и за 10-те на сто, и за 1-те на сто на морското лице. Приложено
   * след нея, то би отнело една десета от себе си.
   *
   * <p><b>⚠️ ОБЛЕКЧЕНИЕТО НЕ Е ПЕРО.</b> То не се показва като ред на фиша, защото не е нито
   * начисление, нито удръжка — то намаля БАЗАТА. Перо за него би влязло в нетото и би дало на
   * лицето 337,45 в повече.
   *
   * <p><b>⚠️ „ВКЛЮЧИТЕЛНО ЗА МЕСЕЦА НА ИЗТИЧАНЕ“ — оттам е и сравнението.</b> Датата е ПЪРВОТО
   * число на периода, тоест решение, валидно до който и да е ден от месеца, НЕ е {@code
   * isBefore(date)} и облекчението се полага за целия месец. Сравнено с края на периода, месецът на
   * изтичане щеше да изпадне — точно това, което алинеята изрично включва.
   *
   * <p><b>⚠️ ПРАЗНА ДАТА Е ПОЖИЗНЕНО РЕШЕНИЕ</b> (§ 10а ал. 3 ПЗР ЗДДФЛ), но САМО когато флагът е
   * вдигнат. Без вдигнат флаг празната дата не значи нищо — тя е просто непопълнено поле на човек
   * без решение. Затова флагът се пита ПЪРВИ и празната дата никога не е самостоятелно основание.
   *
   * <p><b>⚠️ ВДИГНАТ ФЛАГ + ЛИПСВАЩ ПАРАМЕТЪР = ОТКАЗ ({@code null}), не нула.</b> Същата семантика
   * като липсваща данъчна ставка: {@code BgIncomeTaxService} отказва и фишът остава непотвърдим.
   * Нула тук би удържала ПЪЛНИЯ данък от лице с решение на ТЕЛК и нищо на фиша не би изглеждало
   * сгрешено. ⚠️ СВАЛЕН флаг обаче е {@code ZERO}, не отказ: „това лице няма облекчение“ е отговор,
   * не липса.
   *
   * <p><b>⚠️ ЛИПСВАЩ СЛУЖИТЕЛ ИЛИ ДОГОВОР Е {@code ZERO}, НЕ ОТКАЗ.</b> Без установено решение няма
   * облекчение, а отказът за непопълнени данни по договора вече си има дом и назована причина в
   * {@code BgPayrollInputBuilder.REFUSALS}. Втори пазач тук би сменил само кое съобщение вижда
   * потребителят.
   *
   * <p><b>⚠️ ДВЕ ОБЯВЕНИ ЛИПСИ ОСТАВАТ, и двете ИЗВЪН обхвата днес:</b> облекчението по чл. 19 ал.
   * 2 (лични вноски за доброволно осигуряване) няма поле във входа; облекченията за деца по чл. 22в
   * и чл. 22г са ГОДИШНИ по устройство и не са месечни. Записани, за да се четат като решение, а не
   * като пропуск.
   */
  protected BigDecimal disabilityRelief(BgPaySlip slip) {
    LocalDate date =
        slip == null || slip.getPeriod() == null || slip.getPeriod().getFromDate() == null
            ? LocalDate.now()
            : slip.getPeriod().getFromDate();
    // ⚠️ ЗАМРАЗЕНОТО „ДА/НЕ“ ВЕЧЕ НОСИ ВАЛИДНОСТТА ЗА ПЕРИОДА — виж
    // BgPayrollInputBuilder#disabilityReliefOn. Решение, отметнато на служителя СЛЕД пресмятането,
    // не мени вече пресметнатия фиш; в копие се поправя в самия режим и остава в одита.
    BgPayrollRegime regime = BgPayrollRegime.of(slip);
    if (regime != null) {
      return regime.disabilityRelief
          ? payrollParameter(PARAM_DISABILITY_RELIEF, date)
          : BigDecimal.ZERO;
    }
    Employee employee = employeeOf(slip);
    if (employee == null || !employee.getBgDisabilityRelief()) {
      return BigDecimal.ZERO;
    }
    LocalDate validUntil = employee.getBgDisabilityValidUntil();
    if (validUntil != null && validUntil.isBefore(date)) {
      return BigDecimal.ZERO;
    }
    return payrollParameter(PARAM_DISABILITY_RELIEF, date);
  }

  /**
   * МОРСКО ЛИЦЕ ЛИ Е ТОЗИ ВИД ОСИГУРЕН — единственото място, където флагът се чете.
   *
   * <p>Неразрешеният вид ({@code null}) е „не“ — виж довода в {@link #incomeTaxRate}. Празната
   * колона в базата също е „не“: генерираният getter за {@code Boolean} връща {@code FALSE} и за
   * {@code NULL}.
   *
   * <p><b>⚠️ И ПРАЗНАТА КОЛОНА НЕ Е ПРЕХОДНО СЪСТОЯНИЕ.</b> Колоната минава през две състояния и
   * второто не е онова, което се очаква: след миграцията и преди App Install всичките 43 реда носят
   * {@code NULL}; СЛЕД App Install остават 42 с {@code NULL} и само код 92 получава {@code true} —
   * нито един ред не става {@code false}: след пълен App Install — {@code NULL|42}, {@code true|1},
   * а кодове 01 и 02 стоят непокътнати с {@code version = 1}.
   *
   * <p>⚠️ Причината НЕ е, че seed-ът мълчи: {@code bg_insured_type.csv} носи изричен {@code false}
   * и {@code BooleanAdapter} го превръща в {@code Boolean.FALSE}. Спира се на {@code
   * com.axelor.db.mapper.Property#set}, който чете старата стойност през ТОЗИ getter — връщащ
   * {@code Boolean.FALSE} и за непопълнено поле — и излиза по {@code if (old == value)}: един и същ
   * кеширан обект. Тоест точно null-safe четенето отдолу е причината колоната да остане празна, и
   * то същото я прави безопасна.
   *
   * <p>Оттам следва и правилото за всяка заявка по флага: {@code WHERE bg_is_seafarer}, НИКОГА
   * {@code = false}.
   */
  private static boolean isSeafarer(BgInsuredType insuredType) {
    return insuredType != null && insuredType.getBgIsSeafarer();
  }

  /**
   * ВИДЪТ ОСИГУРЕН НА ТОЗИ ФИШ — <b>ОТ КОЙТО И ДА Е ОТ ДВАТА НОСИТЕЛЯ</b>, или {@code null}.
   *
   * <p><b>⚠️ ВТОРИЯТ НОСИТЕЛ СЕ ЧЕТЕ, ЗАЩОТО ЛИПСАТА МУ Е ТИХА.</b> За фиш на самоосигуряващо се
   * лице договор няма; метод, който пита само трудовия договор, връща {@code null} и всеки читател
   * получава „вид осигурен неизвестен“. Това НЕ пада никъде: {@link #incomeTaxRate} чете {@code
   * null} като „не е морско лице“ и връща общите 10 на сто — верният отговор за код 12, но по
   * СЛУЧАЙНОСТ, а не по прочит. Появи ли се вид осигурен, чийто данък е друг — какъвто е точно код
   * 41 по чл. 41а ЗДДФЛ, — същата случайност става грешка.
   *
   * <p>⚠️ Редът на питане е този на {@code employeeOf}: договорът пръв, регистрацията втора. Фиш и
   * с двете е ОТКАЗАН по-рано ({@code BgSelfInsuredRules#refuseAmbiguousCarrier}), тоест тук няма
   * случай, в който редът да реши нещо.
   *
   * <p>Празен низ и {@code null} се четат ЕДНАКВО — същият прочит като {@code
   * BgPayrollInputBuilder#insuredTypeOf}: getter-ът за низ връща {@code null} за непопълнено поле,
   * но форма може да запише и празен низ след изчистване на селекцията, а двете не бива да дават
   * различен отговор на един въпрос.
   *
   * <p><b>⚠️ ПРОЧИТЪТ Е В {@code BgPaySlipResolver}, А ТУК ОСТАВА ДЕЛЕГАЦИЯ.</b> Постинг моделът на
   * заплатната статия го иска — сметката на данъка е 4541, 4542 или „няма“ точно по този код, — а
   * той е чиста функция и не може да види {@code private static} на услуга. Преместен, а не
   * преписан: два прочита на „кой е носителят на този фиш“ биха се разминали точно там, където
   * XOR-ът между договор и регистрация е спорен.
   */
  private static String insuredTypeCodeOf(BgPaySlip slip) {
    return BgPaySlipResolver.insuredTypeCodeOf(slip);
  }

  /**
   * РЕДЪТ НА ВИДА ОСИГУРЕН, В СИЛА ЗА ДАТАТА, или {@code null}. Шев към базата.
   *
   * <p>⚠️ СЪЩИЯТ ТЕМПОРАЛЕН ПРОЧИТ като {@code BgInsurableIncomeService#insuredTypeFor} — ПОВТОРЕН
   * нарочно, а не измислен наново: номенклатурата е темпорална и коригиращ фиш за минал период
   * ползва кодовете, валидни ТОГАВА. Два различни филтъра над една таблица биха дали два различни
   * реда за един и същи фиш — единият за долния праг, другият за данъчната ставка.
   *
   * <p>Празният код връща {@code null} и НЕ пита базата: заявка с празен код би обходила таблицата,
   * за да не намери нищо.
   */
  protected BgInsuredType insuredTypeFor(String insuredTypeCode, LocalDate date) {
    if (insuredTypeCode == null || insuredTypeCode.trim().isEmpty()) {
      return null;
    }
    return Beans.get(BgInsuredTypeRepository.class)
        .all()
        .filter(
            "self.code = :c AND self.validFrom <= :d "
                + "AND (self.validTo IS NULL OR self.validTo >= :d)")
        .bind("c", insuredTypeCode)
        .bind("d", date)
        .fetchOne();
  }

  /**
   * СТОЙНОСТТА НА ПАРАМЕТЪРА С ТОЗИ КОД, В СИЛА ЗА ДАТАТА, или {@code null}. Шев към базата.
   *
   * <p>⚠️ {@code null} значи „няма ред за периода“ и се предава нагоре КАТО ОТКАЗ — виж {@link
   * #incomeTaxRate}. Подразбираща се стойност тук би превърнала липсващ ред в мълчаливо число.
   */
  protected BigDecimal payrollParameter(String code, LocalDate date) {
    List<BigDecimal> found =
        JPA.em()
            .createQuery(
                "SELECT p.paramValue FROM BgPayrollParameter p WHERE p.code = :code"
                    + " AND p.validFrom <= :date AND (p.validTo IS NULL OR p.validTo >= :date)",
                BigDecimal.class)
            .setParameter("code", code)
            .setParameter("date", date)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  // ── несеквестируемият доход по чл. 446 ГПК ──────────────────────────────

  /**
   * ⚠️ ПАЗАЧЪТ НА НЕСЕКВЕСТИРУЕМИЯ ДОХОД. Причина за отказ, или {@code null}.
   *
   * <p><b>Защо ПАЗАЧ, а не генератор.</b> Перата от 8xx нямат източник в модела: запорът идва от
   * изпълнителен лист, заемът от договор, издръжката от съдебно решение. Няма какво да се сглоби —
   * има какво да се сВЕРИ, и точно затова това е валидация при потвърждаване, а не шеста стъпка в
   * {@code prepare()}.
   *
   * <p><b>⚠️ КЛАМПАТА СЕ СМЯТА, НО НЕ СЕ ЗАПИСВА, И ТОВА Е РЕШЕНИЕ.</b> Отказът НАЗОВАВА точното
   * число, което трябва да влезе в перо 821, вместо ведомостта сама да пренапише реда на оператора.
   * Причината е в устройството на нетото: {@code netTotal} вади ВСЯКА удръжка, тоест 820 и 821 са
   * АЛТЕРНАТИВИ, не слагаеми — записани и двете, те удържат два пъти. Автоматичен запис би трябвало
   * да нулира или изтрие 820, а с него изчезва и единственият запис за това КОЛКО е поискал
   * изпълнителният лист — числото, с което следващият месец се сверява остатъкът. Отказ, който
   * казва „сложи 821 = 310,10“, оставя и двете: и верния фиш, и следата.
   *
   * <p><b>⚠️ РЕДЪТ НА ЧЕТИРИТЕ ПРАВИЛА Е НА {@link BgGarnishmentResolver#assess}</b> и не се
   * преповтаря тук. Тук се ЧЕТАТ перата и се пита за вида на децата — двете неща, които чистият
   * резолвер по устройство не може да направи.
   *
   * <p><b>⚠️ НЕУСТАНОВИМИ ДЕЦА ПРИ ЗАПОР Е ОТКАЗ, НЕ НУЛА.</b> „Не се знае“, прочетено като „няма
   * деца“, вдига тавана от 1/4 на 1/3 и удържа ПОВЕЧЕ от родител, чийто картон просто не е
   * попълнен. Отказът се вижда; тихото „не“ не.
   *
   * <p>⚠️ БЕЗ ЗАПОР неустановимото НЕ блокира — там се чете КОНСЕРВАТИВНО, като „с деца“. Тази
   * редакция дава винаги ПО-НИСЪК таван (1/4 &lt; 1/3, 1/3 &lt; 1/2, остават 2,5 МРЗ вместо 2),
   * тоест при съмнение се удържа по-малко. Блокиране на всеки фиш със заем заради непопълнен картон
   * би спирало верните случаи заради данни, които никой не е поискал за тях.
   *
   * <p><b>═══ ДВЕТЕ НОРМИ, КОИТО ТОЗИ ПАЗАЧ ПРИЛАГА, ДОСЛОВНО ═══</b> Сверено срещу официалния ГПК
   * (justice.government.bg).
   *
   * <p><b>Чл. 446 ал. 4:</b> „Ограниченията по ал. 1 не се отнасят до задължения за издръжка. В
   * тези случаи присъдената сума за издръжка се удържа изцяло, а удръжките по ал. 1 за другите
   * задължения на осъдения и за задължения за издръжка за минало време се правят върху остатъка от
   * всичките му доходи.“ — оттам е и редът: перото 830 излиза цяло, а таванът се смята върху
   * остатъка.
   *
   * <p><b>⚠️ И ОТТАМ Е РАЗГРАНИЧЕНИЕТО МЕЖДУ ДВЕ ПЕРА: 830 и 831.</b> Същата алинея слага
   * „задълженията за издръжка за МИНАЛО ВРЕМЕ“ при „другите задължения“ — тоест просрочената
   * издръжка (перо 831) се удържа ВЪРХУ ОСТАТЪКА, по ал. 1, като запор, и НЕ излиза цяла. Затова
   * тук перата се четат ПООТДЕЛНО: 830 намалява основата, 831 се бори за място вътре в тавана.
   * Слети в едно число, просрочената щеше да мине под закрилата на ал. 4 и да свали лицето под МРЗ
   * — право, което алинеята дава само на присъдената текуща сума.
   *
   * <p>⚠️ РЕДЪТ ВЪТРЕ В ТАВАНА (просрочената издръжка преди запора) е на {@link
   * BgGarnishmentResolver#assess} и носи основанието си там — ЗЗД чл. 136 ал. 1 т. 5, цитирана с
   * disclaimer: точката е от caselaw източник и НЕ е сверена срещу официален ЗЗД в тази среда.
   *
   * <p><b>Чл. 447:</b> „Всеки отказ на длъжника от закрилата по чл. 444 и 446 е недействителен.“ —
   * оттам е и това, че доброволните удръжки се броят СРЕЩУ тавана, а не над него, и че „лицето
   * подписа съгласие“ никъде не е вход на тази сметка.
   *
   * <p>⚠️ ЧЛ. 446 АЛ. 5 Е ДРУГ ВЪПРОС И Е ИЗВЪН ОБХВАТА: „Върху вземания за издръжка не се допуска
   * принудително изпълнение. Върху стипендии се допуска принудително изпълнение само за задължения
   * за издръжка.“ Тя пази издръжката и стипендията КАТО ДОХОД на длъжника, а фишът тук плаща
   * трудово възнаграждение и не носи нито едното. Записана, за да се вижда, че е прочетена и
   * съзнателно оставена настрана, а не пропусната.
   *
   * <p>Останалото основание: КТ чл. 272 ал. 1 и ал. 2 (таванът върху СБОРА и шестте точки, сред
   * които е запорът). ⚠️ Текстът на КТ НЕ е сверен в тази среда — цитат от него тук няма нарочно.
   */
  protected String garnishmentRefusal(BgPaySlip slip) {
    List<BgPaySlipLine> lines = safe(slip.getBgPaySlipLineList());
    BigDecimal garnishment = BgPaySlipResolver.garnishmentWithheld(lines);
    BigDecimal pastDue = BgPaySlipResolver.pastDueMaintenanceWithheld(lines);
    BigDecimal consented = BgPaySlipResolver.consentDeductions(lines);
    // ⚠️ Само ТЕКУЩА издръжка (830) НЕ задейства пазача: тя е ИЗВЪН закрилата и няма таван, срещу
    // който да се мери. Иначе всеки фиш с издръжка щеше да иска заредена скала, за да се потвърди.
    //
    // ⚠️ ПРОСРОЧЕНАТА (831) ОБАЧЕ ГО ЗАДЕЙСТВА, и това е самото разграничение на ал. 4: тя се
    // удържа
    // „върху остатъка“, по ал. 1, тоест ИМА таван и може да го надхвърли сама, без нито един запор
    // на фиша. Пропусната тук, тя щеше да мине без нито една проверена граница.
    if (garnishment.signum() <= 0 && pastDue.signum() <= 0 && consented.signum() <= 0) {
      return null;
    }

    LocalDate date =
        slip.getPeriod() == null || slip.getPeriod().getFromDate() == null
            ? LocalDate.now()
            : slip.getPeriod().getFromDate();

    BgGarnishmentService.Status status = childrenStatus(slip, date);
    // ⚠️ ПРОСРОЧЕНАТА ИЗДРЪЖКА ПИТА ЗА ДЕЦАТА СЪС СЪЩАТА ТЕЖЕСТ като запора: и тя се удържа по ал.
    // 1
    // и признакът избира между двете скали. Питан само при запор, пазачът щеше да пуска фиш само с
    // перо 831 по мълчаливото „няма деца“ — тоест да удържа 1/3 там, където може да е 1/4.
    if ((garnishment.signum() > 0 || pastDue.signum() > 0)
        && status == BgGarnishmentService.Status.UNDETERMINABLE) {
      return "фишът носи удръжка по чл. 446 ал. 1 — запор (820/821) или издръжка за минало време"
          + " (831) — а не може да се установи дали лицето има деца на издръжка:"
          + " в картона има дете без дата на раждане или с отбелязано увреждане, но без срок на"
          + " ТЕЛК. ⚠️ Признакът избира МЕЖДУ ДВЕ СКАЛИ по чл. 446 ал. 1 (1/4 срещу 1/3 при доход до"
          + " две МРЗ), тоест прочетен като „няма деца“ той удържа ПОВЕЧЕ от родител, чиито данни"
          + " просто не са попълнени — а това не изглежда като грешка на нито един фиш. Попълнете"
          + " датата на раждане, съответно срока на ТЕЛК, по децата на служителя.";
    }
    // ⚠️ Неустановимото се чете КОНСЕРВАТИВНО — „с деца“ дава по-нисък таван. Виж javadoc-а.
    boolean withChildren = status != BgGarnishmentService.Status.WITHOUT_CHILDREN;

    BgGarnishmentOutcome outcome =
        BgGarnishmentResolver.assess(
            BgPaySlipResolver.netBeforeSeizure(lines),
            BgPaySlipResolver.maintenanceWithheld(lines),
            pastDue,
            garnishment,
            consented,
            payrollParameter(PARAM_MIN_WAGE, date),
            withChildren,
            garnishmentScale(date));

    if (outcome.hasRefusal()) {
      return outcome.refusal;
    }
    if (outcome.uncollected.signum() > 0) {
      return clampRefusal(outcome, pastDue, garnishment);
    }
    return null;
  }

  /**
   * ОТКАЗЪТ, КОЙТО НАЗОВАВА КЛАМПАТА — поотделно за перо 831 и за перо 821.
   *
   * <p><b>⚠️ ДВЕ ПЕРА, ДВА РАЗЛИЧНИ ХОДА, и затова съобщението не е едно изречение.</b> Свито до
   * сбора на несъбраното, то би пратило оператора да маха от запора сума, която идва от издръжката
   * за минало време — тоест да поправи вярното перо с грешното число.
   *
   * <p>⚠️ И ТУК КЛАМПАТА САМО СЕ НАЗОВАВА. Причината е същата като при 820/821: {@code netTotal}
   * вади ВСЯКА удръжка, тоест автоматичен запис би трябвало да пренапише реда на оператора, а с
   * него изчезва единственият запис колко е поискал изпълнителният лист.
   */
  protected String clampRefusal(
      BgGarnishmentOutcome outcome, BigDecimal pastDue, BigDecimal garnishment) {
    StringBuilder out = new StringBuilder();
    out.append("удръжките по чл. 446 ал. 1 надхвърлят несеквестируемия предел за този месец (")
        .append(outcome.limit.toPlainString())
        .append("). Удържимото се смята върху ")
        .append(outcome.base.toPlainString())
        .append(" — нетото МИНУС текущата издръжка (830), която по ал. 4 излиза първа и цяла.");

    if (outcome.uncollectedPastDueMaintenance.signum() > 0) {
      out.append(" ⚠️ Издръжката за МИНАЛО ВРЕМЕ (831) е ")
          .append(pastDue.toPlainString())
          .append(
              ", а по ал. 4 тя НЕ излиза цяла като текущата — прави се „върху остатъка“, по"
                  + " ал. 1, тоест има същия таван като запора. Този месец от нея може да се удържи ")
          .append(outcome.allowedPastDueMaintenance.toPlainString())
          .append(" — въведете перо 831 с тази стойност. ⚠️ Разликата от ")
          .append(outcome.uncollectedPastDueMaintenance.toPlainString())
          .append(" НЕ отпада — тя остава изискуема и се събира следващия месец.");
    }
    if (outcome.uncollectedGarnishment.signum() > 0) {
      out.append(" ⚠️ Запорът (")
          .append(garnishment.toPlainString())
          .append(
              ") не се побира в остатъка от предела. Въведете перо „Запор, ограничен по чл. 446"
                  + " ал. 1 ГПК“ (821) със стойност ")
          .append(outcome.allowedGarnishment.toPlainString())
          .append(" вместо перото 820. ⚠️ Разликата от ")
          .append(outcome.uncollectedGarnishment.toPlainString())
          .append(
              " НЕ отпада — тя остава изискуема и се събира следващия месец. ⚠️ Перото 820 НЕ се"
                  + " оставя заедно с 821: нетото вади всяка удръжка и двете заедно удържат два пъти.");
    }
    if (pastDue.signum() > 0 && garnishment.signum() > 0) {
      out.append(" ⚠️ РЕДЪТ В ПРЕДЕЛА: издръжката за минало време се удовлетворява ПРЕДИ запора")
          .append(
              " (привилегировано вземане — ЗЗД чл. 136 ал. 1 т. 5; ⚠️ точката е от caselaw"
                  + " източник и НЕ е сверена срещу официалния ЗЗД). Затова за запора остава ")
          .append(outcome.allowedGarnishment.toPlainString())
          .append(", а не целият предел.");
    }
    return out.toString();
  }

  /**
   * Видът на децата на лицето към датата. Шев — подменя се в тестовете.
   *
   * <p>⚠️ Правилото НЕ се преписва тук: то е на {@link BgGarnishmentService} и се вика оттам. Второ
   * копие би се разминало с първото при първата поправка, а разминаването избира ДРУГА скала.
   */
  protected BgGarnishmentService.Status childrenStatus(BgPaySlip slip, LocalDate date) {
    return Beans.get(BgGarnishmentService.class).childrenStatus(employeeOf(slip), date);
  }

  /**
   * РЕДОВЕТЕ НА СКАЛАТА ПО ЧЛ. 446, В СИЛА ЗА ДАТАТА. Шев към базата.
   *
   * <p>⚠️ Същият темпорален филтър като при вида осигурен и при параметрите — скалата е менена
   * (левове → кратни на МРЗ през 2017 г., нов под през 2025 г.) и коригиращ фиш за минал период
   * трябва да ползва редакцията, валидна ТОГАВА.
   */
  protected List<BgGarnishmentScale> garnishmentScale(LocalDate date) {
    return Beans.get(BgGarnishmentScaleRepository.class)
        .all()
        .filter("self.validFrom <= :d AND (self.validTo IS NULL OR self.validTo >= :d)")
        .bind("d", date)
        .order("bgThreshold")
        .fetch();
  }

  protected void save(BgPaySlip slip) {
    Beans.get(BgPaySlipRepository.class).save(slip);
  }

  protected void saveCompensation(BgCompensation doc) {
    Beans.get(com.axelor.apps.bglocalization.db.repo.BgCompensationRepository.class).save(doc);
  }

  private static List<BgPaySlipLine> safe(List<BgPaySlipLine> lines) {
    return lines == null ? new ArrayList<>() : lines;
  }

  // ── шевовете на корекцията към базата (подменят се в тестовете) ──────────

  /**
   * ТЕКУЩИЯТ ОТВОРЕН ЗАПЛАТЕН ПЕРИОД, който съдържа днешната дата.
   *
   * <p>⚠️ Заплатна година (typeSelect = 2), НЕ фискална: платежният период е отделен от счетоводния
   * и местенето му през ADJUSTING не пипа счетоводните книги. Осчетоводяването на корекцията в
   * затворен фискален период не е грижа на този метод.
   *
   * <p>⚠️ Дружеството е онова, което ПЛАЩА по договора ({@code payCompany}), не активното. {@code
   * Year.company} обаче може да е празно (полето не е required в базовия Axelor) — затова период
   * без дружество също се брои: иначе една незададена настройка изглежда като „няма отворен
   * период“.
   */
  protected Period currentOpenPeriod(BgPaySlip slip) {
    Company company = companyOf(slip);
    List<Period> found =
        JPA.em()
            .createQuery(
                "SELECT p FROM Period p WHERE p.year.typeSelect = :type"
                    + " AND p.statusSelect = :status"
                    + " AND p.fromDate <= :today AND p.toDate >= :today"
                    + " AND (p.year.company IS NULL OR :company IS NULL OR p.year.company = :company)"
                    + " ORDER BY p.fromDate",
                Period.class)
            .setParameter("type", YEAR_TYPE_PAYROLL)
            .setParameter("status", PeriodRepository.STATUS_OPENED)
            .setParameter("today", today())
            .setParameter("company", company)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * РЕДОВНАТА чернова на лицето за този период, ако вече я има.
   *
   * <p>⚠️ Коригиращите и заличаващите фишове се ИЗКЛЮЧВАТ. Иначе делтата от една корекция можеше да
   * седне в коригиращо копие за друг период — тоест в документ, който не плаща, и парите нямаше да
   * стигнат до никого.
   *
   * <p>⚠️ {@code IS NULL OR = FALSE}, а не само {@code = FALSE}: колоната е добавена на заварена
   * таблица и старите редове носят NULL, а SQL сравнението с NULL не е нито вярно, нито невярно —
   * тоест голото {@code = FALSE} би скрило всеки фиш отпреди миграцията.
   */
  protected BgPaySlip openDraftSlip(EmploymentContract contract, Period period) {
    if (contract == null || period == null) {
      return null;
    }
    List<BgPaySlip> found =
        JPA.em()
            .createQuery(
                "SELECT s FROM BgPaySlip s WHERE s.employmentContract = :contract"
                    + " AND s.period = :period AND s.statusSelect = :status"
                    + " AND (s.bgIsCorrection IS NULL OR s.bgIsCorrection = FALSE)"
                    + " AND (s.bgIsDeletion IS NULL OR s.bgIsDeletion = FALSE)"
                    + " ORDER BY s.id",
                BgPaySlip.class)
            .setParameter("contract", contract)
            .setParameter("period", period)
            .setParameter("status", BgPaySlipResolver.DRAFT)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /** Перото на делтата от речника, по стабилния си код. */
  protected BgPaySlipLineType correctionLineType() {
    List<BgPaySlipLineType> found =
        JPA.em()
            .createQuery(
                "SELECT t FROM BgPaySlipLineType t WHERE t.code = :code", BgPaySlipLineType.class)
            .setParameter("code", CORRECTION_LINE_CODE)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  protected void savePeriod(Period period) {
    Beans.get(PeriodRepository.class).save(period);
  }

  /** Начало на корекцията — в РОДНАТА история на Axelor, а не в наша таблица. */
  protected void logAdjustStart(Period period) {
    Beans.get(AdjustHistoryService.class).setStartDate(period);
  }

  /**
   * Край на корекцията, вписан в същата история.
   *
   * <p>⚠️ НЕ през {@code AdjustHistoryService#setEndDate}, и причината е в самия му код: той чете
   * {@code fetchOne()} и веднага вика {@code setEndDate} върху резултата, тоест хвърля {@code
   * NullPointerException}, когато отворен запис няма. А такъв може да няма: период, преместен в
   * ADJUSTING по друг път, е напълно възможен и не бива да поваля потвърждаването на фиш с
   * NullPointerException вместо със съобщение. Липсата на запис тук не е грешка — тя значи, че няма
   * какво да се затвори.
   */
  protected void logAdjustEnd(Period period) {
    if (period == null || period.getId() == null) {
      return;
    }
    AdjustHistory open =
        Beans.get(AdjustHistoryRepository.class)
            .all()
            .filter("self.period.id = :id AND self.endDate IS NULL")
            .bind("id", period.getId())
            .fetchOne();
    if (open == null) {
      return;
    }
    open.setEndDate(LocalDateTime.now());
    Beans.get(AdjustHistoryRepository.class).save(open);
  }

  /** Днешната дата — шев, за да могат срокът и „текущият период“ да се измерят от тест. */
  protected LocalDate today() {
    return LocalDate.now();
  }

  private static void refuse(String message) throws AxelorException {
    if (message != null && !message.isEmpty()) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, "%s", message);
    }
  }
}
