package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * БАЗОВАТА ПОЛОВИНА НА ЕНДЖИНА ЗА НАЧИСЛЕНИЯ — изважда стойностите и вика чистата половина.
 *
 * <p><b>Разделението.</b> Аритметиката и правилата ѝ живеят в {@link BgEarningsResolver} и вървят
 * без среда; тук стои единствено четенето от каталозите и от дружеството. Двете не се смесват:
 * правило, написано до заявка към базата, спира да е проверимо без база — а точно това искахме да
 * избегнем, за да могат свидетелите на сметката да вървят на всяка машина.
 *
 * <p><b>Процентът за клас прослужено време — откъде идва.</b> Два източника с ясен ред:
 *
 * <ol>
 *   <li>{@code Company.bgSeniorityRatePct} — уговореното от работодателя, ако е попълнено;
 *   <li>{@code SENIORITY_MIN_PCT} от {@code BgPayrollParameter} за периода — законовият минимум
 *       (0,6000 по ПМС № 147/2007).
 * </ol>
 *
 * <p>⚠️ Вторият НЕ Е „резервна стойност“, а ПОД: уговорен процент ПОД минимума се вдига до него, не
 * се приема. Затова параметърът се чете ВИНАГИ, включително когато дружеството е попълнило свой
 * процент — без него подът не може да се наложи. И затова липсата му е ОТКАЗ, а не подразбиране:
 * 0,6 написано в кода е число, което никой отчет не може да защити пред проверка, а и се разминава
 * мълчаливо в деня, в който ПМС-то смени минимума.
 *
 * <p>⚠️ Четенето е по СЪЩИЯ механизъм като на всеки друг параметър в модула — {@code
 * BgPayrollParameterRepository} с филтър по код и по дата на валидност, същият, който {@code
 * BgInsurableIncomeService#parameterFor} ползва за тавана и за законовите прагове. Втори механизъм
 * за едно и също нещо е свободен да се размине с първия по датите — а разминаване по дати значи, че
 * две части на един фиш смятат по различни версии на закона.
 */
public class BgEarningsService {

  /** Кодът на законовия минимум за клас прослужено време в {@code BgPayrollParameter}. */
  public static final String PARAM_SENIORITY_MIN_PCT = "SENIORITY_MIN_PCT";

  /** Минималната работна заплата за страната — знаменателят на нощната добавка (НСОРЗ чл. 8). */
  public static final String PARAM_MIN_WAGE = "MIN_WAGE";

  /** Процентът от МРЗ за нощен час (0,15) — НСОРЗ чл. 8. */
  public static final String PARAM_NIGHT_PCT_OF_MIN_WAGE = "NIGHT_PCT_OF_MIN_WAGE";

  /** Абсолютният под за нощен час (0,51 EUR) — НСОРЗ чл. 8, втората половина на минимума. */
  public static final String PARAM_NIGHT_FLOOR_EUR = "NIGHT_FLOOR_EUR";

  /** Ставката за час на разположение (0,05 EUR) — НСОРЗ чл. 10. */
  public static final String PARAM_ON_CALL_EUR_PER_HOUR = "ON_CALL_EUR_PER_HOUR";

  /** Месечната сума за степен „доктор“ (7,67 EUR) — НСОРЗ чл. 11 ал. 1 т. 1. */
  public static final String PARAM_DEGREE_DOCTOR_EUR = "DEGREE_DOCTOR_EUR";

  /** Месечната сума за степен „доктор на науките“ (25,56 EUR) — НСОРЗ чл. 11 ал. 1 т. 2. */
  public static final String PARAM_DEGREE_DSC_EUR = "DEGREE_DSC_EUR";

  /**
   * ВСИЧКИТЕ ДЕСЕТ ПЕРА, КОИТО ТОЗИ ЕНДЖИН ПИШЕ САМ — и които затова НЕ се четат обратно от фиша.
   *
   * <p><b>⚠️ ЕНДЖИНЪТ НЕ ЧЕТЕ СОБСТВЕНИЯ СИ ИЗХОД. ТОВА Е ИНВАРИАНТ, НЕ ОПТИМИЗАЦИЯ.</b> Три от
   * десетте (основна, клас, научна степен) са с {@code bgIsPermanentCharacter = true} в речника,
   * тоест влизат в базата по НСОРЗ чл. 7 — но по ФОРМУЛА, от {@link
   * BgEarningsResolver#permanentPayBase}, а не по прочит на вече записания ред. Събрани и по двата
   * пътя, те щяха да се броят ДВА пъти при всяко второ {@code prepare()}.
   *
   * <p>⚠️ И обратното е капан: прочетени САМО от фиша, те щяха да са ПРОПОРЦИОНИРАНИ (класът по чл.
   * 12 ал. 8), а базата по чл. 7 е непроратираната месечна уговорка по договора.
   *
   * <p><b>⚠️ СПИСЪКЪТ Е ОТ ДЕСЕТ, А НЕ ОТ ТРИ, И РАЗЛИКАТА Е БЪДЕЩ ДЕФЕКТ.</b> Седемте часови пера
   * днес са с {@code bgIsPermanentCharacter = false} — тоест трите щяха да стигнат. Но флагът е
   * ДАННИ и се мени в CSV: отметнат някой ден за нощния труд (а речникът изрично записва, че
   * постоянният характер там Е СПОРЕН), перото щеше да влезе в базата, върху която само̀ то се
   * смята. Обратна връзка в сметка за пари, задействана от промяна в seed, която никой не свързва с
   * ведомостта. Инвариантът се пише тук, а не се уповава на стойност в данни.
   */
  private static final List<String> ENGINE_COMPUTED_LINES =
      Collections.unmodifiableList(
          Arrays.asList(
              BgEarningsResolver.LINE_BASE_SALARY,
              BgEarningsResolver.LINE_SENIORITY,
              BgEarningsResolver.LINE_ACADEMIC_DEGREE,
              BgEarningsResolver.LINE_NIGHT_WORK,
              BgEarningsResolver.LINE_ON_CALL,
              BgEarningsResolver.LINE_OVERTIME_WORKDAY,
              BgEarningsResolver.LINE_OVERTIME_REST_DAY,
              BgEarningsResolver.LINE_OVERTIME_HOLIDAY,
              BgEarningsResolver.LINE_OVERTIME_SIRV,
              BgEarningsResolver.LINE_HOLIDAY_WORK));

  /**
   * ПРОЦЕНТЪТ ЗА ВСЯКА НАВЪРШЕНА ГОДИНА за това дружество и за този период, или {@code null}.
   *
   * <p>{@code null} значи „законовият минимум не е зареден за датата“ и е ОТКАЗ нагоре по веригата
   * — НЕ нула на сто. Без параметъра подът не може да се наложи, тоест уговореният процент не може
   * да се приеме дори когато е попълнен: приемането му би било мълчаливо допускане, че е над
   * минимума.
   *
   * @param company дружеството, което ПЛАЩА по договора ({@code payCompany}), или {@code null}
   * @param date датата, по която се избира версията на параметъра ({@code parameterReferenceDate})
   */
  public BigDecimal seniorityRatePct(Company company, LocalDate date) {
    BigDecimal statutoryMin = statutoryMinPct(date);
    if (statutoryMin == null) {
      return null;
    }
    return BgEarningsResolver.effectiveSeniorityRatePct(
        company == null ? null : company.getBgSeniorityRatePct(), statutoryMin);
  }

  // ═══ ГЕНЕРАТОРЪТ ════════════════════════════════════════════════════════

  /**
   * ГЕНЕРИРА ДВЕТЕ ПЕРА С НАЛИЧЕН ИЗТОЧНИК върху фиш в чернова и връща какво е станало с всяко.
   *
   * <p><b>Върнатият списък е част от договора, не отпадък.</b> Той носи и отказите — а те са
   * единственото, което различава „перото не е нужно“ от „перото не можа да се сметне“. Изхвърли ли
   * го повикващият, вторият случай става неразличим от първия и фишът тихо остава без заплата.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНО.</b> Второ и трето извикване не дублират: перото се търси по СТАБИЛНИЯ
   * КОД на типа ({@code pl_osnovna}, {@code pl_tspo}) и намереното се ОБНОВЯВА на място. Кодът, а
   * не идентификаторът, защото при незапазен фиш перата още нямат идентификатор, а „кое перо е
   * основното“ трябва да е известно и тогава.
   *
   * <p><b>⚠️ АДИТИВНО.</b> Нищо не се трие и нищо чуждо не се пипа. Операторът въвежда на ръка
   * всичко без източник (нощен труд, извънреден, разположение) до WS-5 и тези редове трябва да
   * преживеят всяко {@code prepare()}. Генераторът докосва САМО двата реда със своите кодове.
   *
   * <p>⚠️ Следствие, което трябва да се знае: ръчно въведено перо {@code pl_osnovna} СЪЩО се
   * обновява — по код то Е перото на енджина. Това е предпочетено пред второ перо със същия код на
   * един фиш, защото два реда „Основно възнаграждение“ се събират в брутото и дават двойна заплата.
   *
   * <p><b>⚠️ КОРИГИРАЩ И ЗАЛИЧАВАЩ ФИШ СЕ ПРОПУСКАТ.</b> Техните замразени входове идват от
   * ОРИГИНАЛА, не от календара и договора наново — така ги прави {@code
   * BgPaySlipService#retroCopyOf} и по същата причина: коригира се ОНЗИ месец, а договорът и
   * календарът може да са се променили оттогава. Пресметнато наново перо би пренаписало историята
   * точно там, където целият ретро-механизъм съществува, за да не я пренаписва.
   *
   * @param slip фишът, върху който се пише. Само в статус ЧЕРНОВА
   * @param input входът по § 13.1 — ЕДИНСТВЕНИЯТ източник за котвата на стажа. Може да е {@code
   *     null}
   */
  public List<BgEarningsOutcome> generate(BgPaySlip slip, BgPayrollInput input) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return Collections.emptyList();
    }
    if (Boolean.TRUE.equals(slip.getBgIsCorrection())
        || Boolean.TRUE.equals(slip.getBgIsDeletion())) {
      return Collections.emptyList();
    }

    // ⚠️ ФАКТИТЕ СЕ ВАДЯТ ВЕДНЪЖ и се подават на двете стъпки. factsOf пита каталога с параметри
    // (МРЗ, процент за клас, размери на степента) — втори прочит тук щеше да удвои заявките на
    // ВСЕКИ
    // фиш в партидата, за да получи същия отговор.
    BgEarningsFacts facts = factsOf(slip, input);
    List<BgEarningsOutcome> outcomes = outcomes(slip, facts);
    for (BgEarningsOutcome outcome : outcomes) {
      if (outcome.isComputed()) {
        upsert(slip, outcome);
      } else if (outcome.isNotEntitled()) {
        // ⚠️ ОТРЕЧЕНОТО ПРАВО МАХА ЗАВАРЕН РЕД, А НЕ ГО ПРЕСКАЧА. Фишовете, подготвени преди тази
        // поправка, вече носят перо „клас прослужено време“ и на ДУК; прескочени, те щяха да го
        // запазят завинаги — поправката щеше да важи само за новите фишове, а точно старите са
        // сгрешените. Другите три изхода НЕ махат нищо: при тях заварен ред може да е ръчно
        // въведеното, което те очакват от оператора.
        drop(slip, outcome.lineCode);
      }
    }
    // ⚠️ СЛЕД СВОИТЕ ПЕРА, И РЕДЪТ НЕ Е СВОБОДЕН. Тук се пипа ЧУЖД ред — единственото изключение от
    // „адитивно“ по-горе, — и то се пипа по ПРАВИЛО, не по преценка: НСОРЗ чл. 15 ал. 2 нарича
    // постоянно онова допълнително възнаграждение, което зависи ЕДИНСТВЕНО от отработеното време.
    // Тоест ред, отметнат като постоянен, СЕ проратира от отсъствия — иначе фишът плаща дни, за
    // които не се дължи възнаграждение (КТ чл. 128 т. 2). Карвът обаче пише САМО bgAmount и чете
    // САМО bgEnteredAmount, тоест въведеното от оператора остава непокътнато и възстановимо.
    carveManualPermanentLines(slip, facts);
    return outcomes;
  }

  /**
   * КАРВЪТ НА 180/181 ВЪРХУ КОРИГИРАЩО КОПИЕ — ръчните постоянни пера от въведената сума, по дните
   * на ОНЗИ месец.
   *
   * <p><b>⚠️ ВРАТА, А НЕ РАЗХЛАБЕН ГЕЙТ.</b> {@link #generate} остава затворен за ретро-фиш:
   * основните начисления на копието са замразени и не се генерират наново. Тук минава САМО карвът
   * на ръчните постоянни пера — въведената сума е новото твърдение на оператора (изгубен бонус
   * 200), а дните, по които се карвира, са замразените на фиша ({@code BgFrozenPayrollInput}), не
   * днешните заявки за отпуск.
   *
   * <p>⚠️ Същият {@link #carveManualPermanentLines}, със същите три числа като в {@code prepare()}:
   * дни по чл. 177, неплатени дни за карва и норма. Втора формула тук щеше да карвира копието
   * различно от оригинала.
   *
   * @param frozen входът на копието — само от замразеното
   */
  public void carveOnCorrection(BgPaySlip slip, BgPayrollInput frozen) {
    if (slip == null
        || frozen == null
        || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())
        || !Boolean.TRUE.equals(slip.getBgIsCorrection())) {
      return;
    }
    carveManualPermanentLines(
        slip,
        BgEarningsFacts.builder()
            .art177LeaveDays(frozen.art177LeaveDays)
            .unpaidCarveDays(carveDaysOf(frozen))
            .workingDaysInPeriod(slip.getBgWorkingDaysInPeriod())
            .build());
  }

  /**
   * Има ли копието ръчно постоянно перо (180/181) — тоест чете ли карвът дните изобщо. Статично и
   * публично, за да питат и отказите за липсващи дни ({@code BgPaySlipService}) СЪЩИЯ предикат без
   * контейнер.
   */
  public static boolean hasManualPermanentLines(BgPaySlip slip) {
    return !manualPermanentLinesOf(slip).isEmpty();
  }

  /**
   * КАКВО БИ СТАНАЛО С ВСЕКИ ТИП ПЕРО — без да се пише нищо.
   *
   * <p><b>⚠️ ОБХОЖДА ЦЕЛИЯ РЕЧНИК, не двата кода, които умее.</b> Разликата не е педантичност:
   * списък от два кода мълчи за останалите двайсет и седем, а мълчанието изглежда точно като
   * „нямаше какво да се начисли“. Обходен целият речник, всеки тип получава ЯВЕН отговор —
   * сметнато, „източникът липсва (WS-5)“, „не е моя работа“ или отказ — и върнатият списък Е
   * докладът за фиша.
   *
   * <p>Оттам следва и че нов ред в {@code bg_pay_slip_line_type.csv} влиза в дисчпача сам, без
   * промяна тук; а ред с непозната база вдига отказ още при първото {@code prepare()}, а не при
   * първия фиш, който случайно го носи.
   */
  public List<BgEarningsOutcome> outcomes(BgPaySlip slip, BgPayrollInput input) {
    return outcomes(slip, factsOf(slip, input));
  }

  /**
   * Както горното, но с ВЕЧЕ ИЗВАДЕНИ факти.
   *
   * <p>⚠️ ПУБЛИЧНАТА ВРАТА ОТГОРЕ ОСТАВА и делегира — {@code outcomes(slip, input)} има заварени
   * повикващи и свидетели. Разширена на място, сигнатурата щеше да ги повали наведнъж, а те не се
   * интересуват от фактите.
   */
  public List<BgEarningsOutcome> outcomes(BgPaySlip slip, BgEarningsFacts facts) {
    List<BgEarningsOutcome> out = new ArrayList<>();
    for (BgPaySlipLineType type : lineTypes()) {
      out.add(BgEarningsResolver.resolve(type.getBgCalculationBase(), type.getCode(), facts));
    }
    return out;
  }

  /**
   * Петте числа, извадени ВЕДНЪЖ за целия обход.
   *
   * <p>⚠️ ОСНОВНАТА, ОТРАБОТЕНИТЕ И НОРМАТА СЕ ЧЕТАТ ОТ ФИША, не от договора наново — фишът вече ги
   * е замразил в {@code prepare()} и ТРЗ има право да ги надпише („числото е нечие решение“).
   * Прочетени пак от договора, ръчната корекция би се игнорирала мълчаливо. Котвата на стажа,
   * обратно, няма дом на фиша и идва от входа по § 13.1 — същия шев, по който минават и
   * осигуровките.
   */
  /**
   * ДНИТЕ, ЗА КОИТО ВЪЗНАГРАЖДЕНИЕ НЕ СЕ ДЪЛЖИ — сборът на двете дизюнктни подмножества.
   *
   * <pre>
   * карв = неплатен отпуск С кофа в Д1 (т. 16.3/16.5) + дни ИЗВЪН осигуряването (КСО чл. 10 ал. 2)
   * </pre>
   *
   * <p><b>⚠️ ТОВА Е ТОЧНО МНОЖЕСТВОТО, КОЕТО Д1 ВАДИ ОТ Т. 16.1</b>, и това е инвариантът, който
   * двата клона пазят: {@code BgD1RecordBuilder#fillDays} изважда т. 16.3, т. 16.4, т. 16.5 и дните
   * извън осигуряването. Заплатата сваля същите дни (без т. 16.4 — болничният без право на
   * обезщетение пътува като епизод, не като ден отпуск). Разминат ли се двете страни, фишът плаща
   * дни, които декларацията не обявява за осигурени, или обратното.
   *
   * <p>⚠️ {@code null} на едното НЕ обезсилва другото: двете идват от един прочит ({@code
   * BgPayrollInputBuilder#leaveDaysOf}) и са {@code null} заедно, но записано като „и двете или
   * нищо“, това би било трето правило, което може да се разсинхронизира с първите две. {@code
   * carvedDays} по-надолу и без това чете само положителните.
   */
  static BigDecimal carveDaysOf(BgPayrollInput input) {
    if (input == null) {
      return null;
    }
    BigDecimal inD1 = input.unpaidLeaveDaysInD1;
    BigDecimal outside = input.daysOutsideInsurance;
    if (inD1 == null) {
      return outside;
    }
    if (outside == null) {
      return inD1;
    }
    return inD1.add(outside);
  }

  protected BgEarningsFacts factsOf(BgPaySlip slip, BgPayrollInput input) {
    LocalDate referenceDate = referenceDateOf(slip, input);
    LocalDate periodTo = slip.getPeriod() == null ? null : slip.getPeriod().getToDate();
    LocalDate anchor = input == null ? null : input.recognisedServiceStartDate;

    Integer years =
        anchor == null || periodTo == null
            ? null
            : BgEarningsResolver.completedServiceYears(anchor, periodTo);

    Map<String, BigDecimal> agreed = agreedOvertimeUplift(slip, input);

    return BgEarningsFacts.builder()
        .baseSalary(slip.getBgBaseSalary())
        .workedDays(slip.getBgWorkedDays())
        // ⚠️ ОТ ВХОДА, НЕ ОТ ФИША, и това е обратното на трите числа около него. Дните по чл. 177
        // нямат дом на фиша НАРОЧНО: замразени там, те щяха да са четвърто поле, което ТРЗ може да
        // надпише, без перото по чл. 177 да разбере — а то ги чете от СЪЩИЯ вход. Разминат ли се
        // двете, карвът сваля едни пари, а перото връща други.
        .art177LeaveDays(input == null ? null : input.art177LeaveDays)
        // ⚠️ СГЪВАНЕТО ЖИВЕЕ ТУК, И ТОВА Е РЕШЕНИЕ, А НЕ ПРЕПИСВАНЕ. Входът носи ТРИ числа за
        // неплатеното отсъствие: всичкия неплатен отпуск (unpaidLeaveDays, за здравната вноска по
        // ЗЗО чл. 40 ал. 1 т. 1 б. „б“), частта му с КОФА в Д1 (unpaidLeaveDaysInD1 — т. 16.5 по
        // КТ чл. 160 ал. 3 и т. 16.3 по чл. 167а) и дните ИЗВЪН осигуряването (daysOutsideInsurance
        // — КСО чл. 10 ал. 2). Карвът получава сбора на ПОСЛЕДНИТЕ ДВЕ, тоест точно онези дни,
        // които Декларация обр. 1 вади от т. 16.1: фишът плаща толкова дни, колкото Д1 обявява в
        // осигуряване. Взето само първото, лицето щеше да плати и дните, зачетени за стаж; взето
        // само второто, петте вида без кофа оставаха платени цели срещу прекъснато осигуряване.
        // ⚠️ И ДВЕТЕ ЧИСЛА СА ДИЗЮНКТНИ: unpaidLeaveDaysInD1 иска НЕПРАЗЕН d1Code, а
        // daysOutsideInsurance — ПРАЗЕН. Един ден не може да влезе в двете, тоест сборът не е
        // двойно броене.
        // ⚠️ И СБОРЪТ Е ТУК, А НЕ ТРЕТИ ПАРАМЕТЪР НА carvedDays. Разликовият карв по чл. 177
        // (art177Inputs) вика енджина ДВА ПЪТИ и изисква всичко освен дните по чл. 177 да стои
        // НЕПОДВИЖНО между двете извиквания. Едно число, сметнато веднъж тук, е неподвижно по
        // конструкция; три изваждаеми, носени поотделно надолу, са трети шанс за разминаването,
        // което даде 227,28 срещу 227,27.
        .unpaidCarveDays(carveDaysOf(input))
        .leaveDaysRefusal(input == null ? null : input.leaveDaysRefusal)
        .workingDaysInPeriod(slip.getBgWorkingDaysInPeriod())
        .completedServiceYears(years)
        .seniorityRatePct(seniorityRatePct(companyOf(slip), referenceDate))
        .entitledToSeniorityBonus(seniorityEntitlement(slip))
        // ── часовете и двата признака: ОТ ВХОДА, не от фиша ──────────────────
        // ⚠️ ОТ ВХОДА, И ТОВА Е ОБРАТНОТО НА ПРАВИЛОТО ОТГОРЕ. Основната, отработените дни и
        // нормата се четат от ФИША, защото той вече ги е замразил и ТРЗ има право да ги надпише.
        // Часовете нямат такъв дом в момента на смятането: prepare() ги замразява СЛЕД
        // генераторите (инак замразява числото, което тепърва ще се чете). Прочетени от фиша тук,
        // те щяха да са онези от ПРЕДИШНОТО prepare() — тоест поправен отчет за време нямаше да
        // стигне до перата до второто натискане.
        .contractHoursPerDay(input == null ? null : input.contractHoursPerDay)
        .permanentAllowances(permanentAllowances(slip))
        .academicDegree(input == null ? null : input.academicDegree)
        .unregulatedWorkDay(input != null && input.unregulatedWorkDay)
        .workTimeMode(input == null ? null : input.workTimeMode)
        .nightHours(input == null ? null : input.nightHours)
        .onCallHours(input == null ? null : input.onCallHours)
        .holidayWorkHours(input == null ? null : input.holidayWorkHours)
        .overtimeWorkdayHours(input == null ? null : input.overtimeWorkdayHours)
        .overtimeRestDayHours(input == null ? null : input.overtimeRestDayHours)
        .overtimeHolidayHours(input == null ? null : input.overtimeHolidayHours)
        .overtimeSirvHours(input == null ? null : input.overtimeSirvHours)
        .agreedOvertimeWorkdayPct(agreed.get(BgPayrollInputBuilder.OVERTIME_WORKDAY))
        .agreedOvertimeRestDayPct(agreed.get(BgPayrollInputBuilder.OVERTIME_REST_DAY))
        .agreedOvertimeHolidayPct(agreed.get(BgPayrollInputBuilder.OVERTIME_HOLIDAY))
        .agreedOvertimeSirvPct(agreed.get(BgPayrollInputBuilder.OVERTIME_SIRV))
        // ── ставките от каталога, всичките по ЕДНА дата ─────────────────────
        .minWage(payrollParameter(PARAM_MIN_WAGE, referenceDate))
        .nightPctOfMinWage(payrollParameter(PARAM_NIGHT_PCT_OF_MIN_WAGE, referenceDate))
        .nightFloorEur(payrollParameter(PARAM_NIGHT_FLOOR_EUR, referenceDate))
        .onCallEurPerHour(payrollParameter(PARAM_ON_CALL_EUR_PER_HOUR, referenceDate))
        .degreeDoctorEur(payrollParameter(PARAM_DEGREE_DOCTOR_EUR, referenceDate))
        .degreeDscEur(payrollParameter(PARAM_DEGREE_DSC_EUR, referenceDate))
        .build();
  }

  // ═══ ВРАТАТА КЪМ ЧЛ. 177 ═══════════════════════════════════════════════

  /**
   * ТРИТЕ ЧИСЛА, КОИТО ПЕРОТО ПО ЧЛ. 177 ИСКА ОТ ЕНДЖИНА ЗА НАЧИСЛЕНИЯ.
   *
   * <p>Изчисляват се ЗАЕДНО, защото идват от един и същ обход на речника и от едно четене на
   * параметрите. Поискани поотделно, те щяха да са три четения на каталога по три дати — а
   * разминаване по дати значи, че карвът и базата му са смятани по различни версии на закона.
   */
  public static final class Art177Inputs {

    /**
     * {@code C} — ПАРИТЕ, КОИТО КАРВЪТ НАИСТИНА СВАЛИ ОТ ФИША, до стотинката.
     *
     * <p>⚠️ Не е формула, а РАЗЛИКА между двата изхода на едни и същи чисти функции — със и без
     * дните по чл. 177. Пресметнат наново по своя формула, той щеше да е свободен да се размине с
     * онова, което перата наистина показват, и инвариантът „нето нула“ щеше да се чупи с една
     * стотинка ту в едната, ту в другата посока.
     */
    public final BigDecimal carve;

    /**
     * {@code M} — НЕПРОРАТИРАНИЯТ месечен сбор на перата с постоянен характер за ТЕКУЩИЯ месец.
     *
     * <p>⚠️ НЕПРОРАТИРАН, и това е същественото. Всяко от тези пера плаща по {@code M / норма} на
     * КАЛЕНДАРНО-РАБОТЕН ден, а не на отработен: основната заплата е {@code база × отработени /
     * норма}, тоест дневната ѝ ставка е {@code база / норма} независимо колко дни са отработени.
     * Взет като сбора на ПОКАЗАНИТЕ суми, {@code M} щеше да падне в месец с болничен и отпускът
     * щеше да се плати по завишена надбавка.
     */
    public final BigDecimal currentMonthPermanentPay;

    /**
     * УГОВОРЕНОТО ПО ДОГОВОР — основно + ДТВ с постоянен характер, БЕЗ ръчните пера на фиша.
     *
     * <p>Знаменателят на резервния ред по чл. 177 ал. 2 / НСОРЗ чл. 18 ал. 1 изр. 2: нормата иска
     * „УГОВОРЕНИТЕ В ТРУДОВИЯ ДОГОВОР“ възнаграждения, а ръчно въведеното перо на един фиш не е
     * уговорка по договора — то е решение за ЕДИН месец.
     */
    public final BigDecimal contractualPermanentPay;

    /** Назована причина някое от трите пера да не може да се сметне; инак {@code null}. */
    public final String refusal;

    Art177Inputs(
        BigDecimal carve,
        BigDecimal currentMonthPermanentPay,
        BigDecimal contractualPermanentPay,
        String refusal) {
      this.carve = carve;
      this.currentMonthPermanentPay = currentMonthPermanentPay;
      this.contractualPermanentPay = contractualPermanentPay;
      this.refusal = refusal;
    }
  }

  /**
   * ЧИСЛАТА ЗА ЧЛ. 177 — карвът, месечният сбор и уговореното по договор.
   *
   * <p><b>⚠️ ЧЕТЕ СЕ ИЗХОДЪТ НА СЪЩИТЕ ЧИСТИ ФУНКЦИИ, А НЕ ВЕЧЕ ЗАПИСАНИТЕ ПЕРА.</b> Перата на фиша
   * вече са карвирани — от тях пълната месечна сума е невъзстановима. И обратното е капан:
   * собствена формула тук би била ВТОРИ отговор на въпроса „колко е основната за пълен месец“ и
   * свободна да се размине с първия.
   *
   * <p><b>⚠️ И ЗАЩО НЕ {@code permanentPayBase} ЗА {@code M}.</b> Базата по чл. 7 добавя класа
   * винаги, когато процентът и годините са налице, а {@link BgEarningsResolver#seniorityLine}
   * ОТКАЗВА при неустановено правоотношение ({@code entitled == null}) и не пише перо. Взет оттам,
   * {@code M} щеше да съдържа клас, който фишът не плаща — тоест надбавката щеше да излезе занижена
   * точно при заварен договор без вид осигурен.
   *
   * @param slip фишът; чете се за основната, отработените и нормата
   * @param input входът по § 13.1 — носи дните по чл. 177 и котвата на стажа
   */
  public Art177Inputs art177Inputs(BgPaySlip slip, BgPayrollInput input) {
    BgEarningsFacts facts = factsOf(slip, input);
    BigDecimal days = facts.art177LeaveDays;
    if (days == null || days.signum() <= 0) {
      return new Art177Inputs(BigDecimal.ZERO, null, null, null);
    }

    // ⚠️ НЕПЛАТЕНИЯТ ОТПУСК СТОИ НЕПОДВИЖЕН ОТ ДВЕТЕ СТРАНИ НА ВСЯКА РАЗЛИКА, И ТОВА Е УСЛОВИЕ ЗА
    // ИНВАРИАНТА, А НЕ ПЕДАНТИЧНОСТ. Карвът е онова, което дните по ЧЛ. 177 наистина свалиха от
    // ТОЗИ фиш — тоест разликата между два изхода, които се различават САМО по тях. Изпуснат от
    // двете извиквания, при основна 1000, норма 22, 5 дни по чл. 177 и 5 неплатени той щеше да
    // излезе 227,27 (= 1000 − 772,73), докато фишът наистина е загубил 227,28 (= 772,73 − 545,45);
    // перото по чл. 177 щеше да върне с една стотинка по-малко и инвариантът „нето нула“ да се
    // счупи точно в месеците, в които се ползват и двата вида отпуск.
    BigDecimal unpaid = facts.unpaidCarveDays;

    // ── основната ────────────────────────────────────────────────────────────
    BgEarningsOutcome fullBase =
        BgEarningsResolver.baseSalaryLine(
            facts.baseSalary, facts.workedDays, facts.workingDaysInPeriod, null, unpaid);
    if (!fullBase.isComputed()) {
      return new Art177Inputs(null, null, null, fullBase.detail);
    }
    BgEarningsOutcome carvedBase =
        BgEarningsResolver.baseSalaryLine(
            facts.baseSalary, facts.workedDays, facts.workingDaysInPeriod, days, unpaid);
    if (!carvedBase.isComputed()) {
      return new Art177Inputs(null, null, null, carvedBase.detail);
    }
    BigDecimal carve = fullBase.amount.subtract(carvedBase.amount);
    // ⚠️ ОСНОВНАТА ВЛИЗА В УГОВОРЕНОТО НЕПРОРАТИРАНА; M идва цял от monthlyPermanentPay отдолу.
    BigDecimal contractual =
        facts.baseSalary.setScale(BgEarningsResolver.SCALE, BgEarningsResolver.ROUNDING);

    // ── класът прослужено време ──────────────────────────────────────────────
    BgEarningsOutcome fullSeniority =
        BgEarningsResolver.seniorityLine(
            facts.entitledToSeniorityBonus,
            facts.baseSalary,
            facts.seniorityRatePct,
            facts.completedServiceYears,
            facts.workingDaysInPeriod,
            facts.workingDaysInPeriod,
            null);
    if (fullSeniority.isComputed()) {
      BgEarningsOutcome carvedSeniority =
          BgEarningsResolver.seniorityLine(
              facts.entitledToSeniorityBonus,
              facts.baseSalary,
              facts.seniorityRatePct,
              facts.completedServiceYears,
              facts.workedDays,
              facts.workingDaysInPeriod,
              days,
              unpaid);
      BgEarningsOutcome uncarvedSeniority =
          BgEarningsResolver.seniorityLine(
              facts.entitledToSeniorityBonus,
              facts.baseSalary,
              facts.seniorityRatePct,
              facts.completedServiceYears,
              facts.workedDays,
              facts.workingDaysInPeriod,
              null,
              unpaid);
      if (!carvedSeniority.isComputed() || !uncarvedSeniority.isComputed()) {
        return new Art177Inputs(null, null, null, carvedSeniority.detail);
      }
      carve = carve.add(uncarvedSeniority.amount.subtract(carvedSeniority.amount));
      // ⚠️ ПЪЛНИЯТ МЕСЕЦ, не начисленото: викнато с норма=норма, перото дава месечния си размер.
      contractual = contractual.add(fullSeniority.amount);
    }

    // ── научната степен ──────────────────────────────────────────────────────
    BgEarningsOutcome fullDegree =
        BgEarningsResolver.academicDegreeLine(
            facts.academicDegree, facts.degreeDoctorEur, facts.degreeDscEur, null, null, null);
    if (fullDegree.isComputed()) {
      // ⚠️ И ТУК ДВЕТЕ СТРАНИ НОСЯТ ЕДИН И СЪЩ НЕПЛАТЕН ОТПУСК. „Некарвираната“ страна е онази
      // БЕЗ дните по чл. 177, а не онази без никакъв карв: второто би приписало на чл. 177 и
      // парите, които неплатеният отпуск е свалил, тоест перото щеше да ги върне без основание.
      BgEarningsOutcome uncarvedDegree =
          BgEarningsResolver.academicDegreeLine(
              facts.academicDegree,
              facts.degreeDoctorEur,
              facts.degreeDscEur,
              null,
              unpaid,
              facts.workingDaysInPeriod);
      BgEarningsOutcome carvedDegree =
          BgEarningsResolver.academicDegreeLine(
              facts.academicDegree,
              facts.degreeDoctorEur,
              facts.degreeDscEur,
              days,
              unpaid,
              facts.workingDaysInPeriod);
      if (!carvedDegree.isComputed() || !uncarvedDegree.isComputed()) {
        return new Art177Inputs(null, null, null, carvedDegree.detail);
      }
      carve = carve.add(uncarvedDegree.amount.subtract(carvedDegree.amount));
      contractual = contractual.add(fullDegree.amount);
    }

    // ═══ РЪЧНИТЕ ПЕРА С ПОСТОЯНЕН ХАРАКТЕР — И В M, И В КАРВА ═══
    // ⚠️ НЕ САМО В M: влезли само в M, делът им за отпусковите дни би се приспаднал ВЪТРЕ в
    // надбавката вместо като по-малка сума на самото перо. За ЧЛ. 177 сметката пак би излязла вярна
    // (парите са едни и същи, местят се само границите между два реда) — но САМО защото има перо,
    // което да поеме приспадането. При НЕПЛАТЕН отпуск такова перо няма и ръчното перо би платило
    // пълния си месечен размер: при 100,00 €, норма 22 и 5 дни това са 22,73 € надплатени, тихо.
    // Затова редът се карвира ДИРЕКТНО, както основната и степента, а надбавката връща пълния дял.
    //
    // ⚠️ M ОБАЧЕ НЕ МЪРДА, И ТОВА Е УСЛОВИЕТО „НЕТО НУЛА“ ДА ОЦЕЛЕЕ. permanentAllowances чете
    // bgEnteredAmount — пълната месечна сума, — а не карвирания ред. Прочетено от реда, M щеше да
    // падне заедно с него, дневната ставка M/норма да падне и надбавката да излезе ЗАВИШЕНА точно в
    // месеца с отпуск. Същият довод, по който основната влиза в M непроратирана.
    carve = carve.add(manualPermanentCarve(slip, days, unpaid, facts.workingDaysInPeriod));

    return new Art177Inputs(carve, monthlyPermanentPay(facts, slip), contractual, null);
  }

  /**
   * ПЪЛНОТО МЕСЕЧНО ВЪЗНАГРАЖДЕНИЕ С ПОСТОЯНЕН ХАРАКТЕР — {@code M}: основната, класът прослужено
   * време и научната степен за ЦЕЛИЯ месец и ръчните постоянни пера по въведената им месечна сума.
   *
   * <p><b>ЕДИН ОТГОВОР, ДВА ЧИТАТЕЛЯ.</b> {@link #art177Inputs} (надбавката по чл. 177) и базата на
   * болничния по чл. 40 ал. 5 КСО ({@code BgSickLeaveService}) питат едно и също — „колко е
   * постоянното възнаграждение за месеца“. Написан на две места, отговорът е свободен да се
   * размине.
   *
   * <p>⚠️ Всяка съставка влиза САМО ако фишът я плаща: класът при отказ на {@link
   * BgEarningsResolver#seniorityLine} (неустановено правоотношение, липсваща котва на стажа) не
   * влиза — виж защо НЕ {@code permanentPayBase} в {@link #art177Inputs}.
   *
   * @return {@code null} без основна заплата — тогава повикващият отказва сам
   */
  public BigDecimal monthlyPermanentPay(BgPaySlip slip, BgPayrollInput input) {
    BgEarningsFacts facts = factsOf(slip, input);
    if (facts.baseSalary == null || facts.baseSalary.signum() <= 0) {
      return null;
    }
    return monthlyPermanentPay(facts, slip);
  }

  /**
   * ⚠️ НЕПРОРАТИРАН, и това е същественото — виж {@link Art177Inputs#currentMonthPermanentPay}.
   * Класът и степента се викат с норма=норма и без дни по чл. 177 и неплатени: така перото дава
   * месечния си размер. Ръчните пера влизат с {@code bgEnteredAmount}, не с карвирания ред.
   */
  protected BigDecimal monthlyPermanentPay(BgEarningsFacts facts, BgPaySlip slip) {
    BigDecimal monthly =
        facts.baseSalary.setScale(BgEarningsResolver.SCALE, BgEarningsResolver.ROUNDING);
    BgEarningsOutcome seniority =
        BgEarningsResolver.seniorityLine(
            facts.entitledToSeniorityBonus,
            facts.baseSalary,
            facts.seniorityRatePct,
            facts.completedServiceYears,
            facts.workingDaysInPeriod,
            facts.workingDaysInPeriod,
            null);
    if (seniority.isComputed()) {
      monthly = monthly.add(seniority.amount);
    }
    BgEarningsOutcome degree =
        BgEarningsResolver.academicDegreeLine(
            facts.academicDegree, facts.degreeDoctorEur, facts.degreeDscEur, null, null, null);
    if (degree.isComputed()) {
      monthly = monthly.add(degree.amount);
    }
    return monthly.add(permanentAllowances(slip));
  }

  /**
   * ДРУГИТЕ ДТВ С ПОСТОЯНЕН ХАРАКТЕР, вече вписани на фиша — третата съставка на базата по чл. 7.
   *
   * <p>⚠️ ЧЕТЕ СЕ ФЛАГЪТ, А НЕ СПИСЪК С КОДОВЕ. НСОРЗ чл. 15 ал. 2 определя постоянния характер по
   * КРИТЕРИЙ („изплаща се постоянно заедно с основното и зависи единствено от отработеното време“),
   * а критерият вече е приложен — веднъж, в речника, където се и спори за него. Списък с кодове тук
   * би бил втори прочит на същия въпрос и би замълчал точно при новото перо.
   *
   * <p>⚠️ САМО CREDIT: удръжка с постоянен характер не съществува, но флагът е булев и нищо не пази
   * речника от такъв ред. Събрана, тя щеше да ПОНИЖИ часовата ставка.
   */
  protected BigDecimal permanentAllowances(BgPaySlip slip) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgPaySlipLine line : manualPermanentLines(slip)) {
      sum = sum.add(enteredAmountOf(line));
    }
    return sum;
  }

  /**
   * РЪЧНИТЕ ПЕРА С ПОСТОЯНЕН ХАРАКТЕР — <b>ЕДИН предикат, три читателя</b>.
   *
   * <p>Викат го {@link #permanentAllowances} (базата по чл. 7 и {@code M} по чл. 177), {@link
   * #manualPermanentCarve} (изваждаемото на перото по чл. 177) и {@link #carveManualPermanentLines}
   * (самото писане на реда). Написан на три места, той е свободен да се разминe — а разминат,
   * карвът, който перото по чл. 177 връща, спира да съвпада с онова, което редовете наистина са
   * загубили, и „нето нула“ се чупи.
   *
   * <p><b>⚠️ ЧЕТЕ СЕ ФЛАГЪТ, А НЕ СПИСЪК С КОДОВЕ.</b> НСОРЗ чл. 15 ал. 2 определя постоянния
   * характер по КРИТЕРИЙ и критерият вече е приложен веднъж — в речника. Днес редовете са два
   * ({@code pl_postoyanen_bonus}, {@code pl_postoyanna_premia}); трети, отметнат утре в CSV, влиза
   * сам. Списък с кодове тук щеше да замълчи точно при него.
   *
   * <p><b>⚠️ САМО CREDIT:</b> удръжка с постоянен характер не съществува, но флагът е булев и нищо
   * не пази речника от такъв ред. Събрана, тя щеше да ПОНИЖИ часовата ставка.
   *
   * <p><b>⚠️ И САМО СОБСТВЕНИЯ ПЕРИОД.</b> Обходът филтрира по {@link
   * BgPaySlipResolver#isOwnPeriod}, както {@code art17BaseOf}. Единственото ретро перо ({@code
   * pl_korekcia_minal_period}) е с постоянен характер FALSE, но това е СТОЙНОСТ В СИЙДА, не код, и
   * не бива да е единственият пазач. Отметнат някой ден, ретро-делтата за минал месец щеше да влее
   * в {@code M} и в часовата ставка пари, които този месец не плаща: дневната ставка по чл. 177
   * щеше да порасне и надбавката да излезе занижена, а извънредният труд да поскъпне — всичко това
   * от една клетка в CSV, която никой не свързва с ведомостта.
   */
  protected List<BgPaySlipLine> manualPermanentLines(BgPaySlip slip) {
    return manualPermanentLinesOf(slip);
  }

  /** Предикатът на {@link #manualPermanentLines} — чист, без инстанция. */
  static List<BgPaySlipLine> manualPermanentLinesOf(BgPaySlip slip) {
    List<BgPaySlipLine> out = new ArrayList<>();
    if (slip == null || slip.getBgPaySlipLineList() == null) {
      return out;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (!Boolean.TRUE.equals(line.getBgIsPermanentCharacter())
          || !BgPaySlipResolver.CREDIT.equals(line.getBgSign())
          || !BgPaySlipResolver.isOwnPeriod(line)
          || (type != null && ENGINE_COMPUTED_LINES.contains(type.getCode()))) {
        continue;
      }
      out.add(line);
    }
    return out;
  }

  /**
   * ВЪВЕДЕНАТА МЕСЕЧНА СУМА на едно ръчно перо — {@code bgEnteredAmount}, а при празно {@code
   * bgAmount}.
   *
   * <p><b>⚠️ FALLBACK-ЪТ Е МИГРАЦИЯТА, И ТОЙ Е ТОЧЕН, А НЕ ПРИБЛИЗИТЕЛЕН.</b> Всички редове,
   * създадени преди това поле, са НЕкарвирани — тогава ръчните пера не се намаляват при отпуск.
   * Тоест техният {@code bgAmount} Е пълната месечна сума и прочитът е верен по КОНСТРУКЦИЯ, не по
   * допускане. Оттам и нулевият backfill: няма ред, за който двете да се различават.
   *
   * <p>⚠️ Генерираният getter за decimal връща {@code ZERO}, не {@code null} — затова празното се
   * проверява със {@code signum() == 0}, а не с {@code == null}. Нула като ВЪВЕДЕНА сума не
   * съществува смислено (перо за 0,00 не се въвежда), тоест двете състояния не се сблъскват.
   */
  static BigDecimal enteredAmountOf(BgPaySlipLine line) {
    BigDecimal entered = line.getBgEnteredAmount();
    return entered.signum() == 0 ? line.getBgAmount() : entered;
  }

  /**
   * КАРВИРА РЪЧНИТЕ ПОСТОЯННИ ПЕРА — <b>пише {@code bgAmount}, а НЕ го чете</b>.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНО ПО КОНСТРУКЦИЯ, И ТОВА Е ЦЯЛАТА ПОПРАВКА.</b> Сумата се смята от {@link
   * BgPaySlipLine#getBgEnteredAmount()} — съхранената пълна месечна сума, — а не от текущия ред.
   * Второ {@code prepare()} чете същия източник и записва същото число. Намалявано на място, перото
   * щеше да се намалява ПАК при всяко минаване: при 100,00 €, норма 22 и 5 дни отпуск първото
   * минаване дава 77,27, второто 59,71, третото 46,13 — и всяко от тях изглежда пресметнато.
   *
   * <p><b>⚠️ УЛАВЯНЕТО СТАВА ТУК, А НЕ НА ЕКРАНА.</b> Празен {@code bgEnteredAmount} значи „още не
   * е уловено“ и се пълни от текущия {@code bgAmount} ПРЕДИ карва. Оттам покритието е пълно:
   * формата, решетката на фиша, REST и вносът минават през едно и също място. Оставено на {@code
   * onChange} на изгледа, полето щеше да остане празно по всеки път ИЗВЪН формата — а празно, то
   * кара следващия обход да улови вече карвираната сума и да я карвира втори път.
   *
   * <p><b>⚠️ И ЗАТОВА РЕДАКТИРУЕМОТО ПОЛЕ Е {@code bgEnteredAmount}, А {@code bgAmount} Е
   * РЕЗУЛТАТ.</b> Смени ли операторът сумата след първото преизчисляване, редакцията трябва да се
   * УВАЖИ — а тя се различава от карва само по това КОЕ поле е пипнато. Виж изгледа: „Сума“ е
   * readonly точно за тези редове.
   *
   * <p>⚠️ Нула дни отпуск дава {@code сума × норма / норма}, тоест въведената сума непроменена —
   * фишът без отпуск излиза БИТ ПО БИТ както преди този клон.
   */
  protected void carveManualPermanentLines(BgPaySlip slip, BgEarningsFacts facts) {
    if (facts == null || facts.workingDaysInPeriod == null || facts.workingDaysInPeriod <= 0) {
      return;
    }
    for (BgPaySlipLine line : manualPermanentLines(slip)) {
      BigDecimal entered = enteredAmountOf(line);
      line.setBgEnteredAmount(entered);
      line.setBgAmount(
          BgEarningsResolver.monthlyFixedAfterLeaveCarve(
              entered, facts.art177LeaveDays, facts.unpaidCarveDays, facts.workingDaysInPeriod));
    }
  }

  /**
   * КОЛКО СВАЛИХА ДНИТЕ ПО ЧЛ. 177 ОТ РЪЧНИТЕ ПЕРА — изваждаемото, което перото по чл. 177 връща.
   *
   * <p><b>⚠️ ПО РЕД, А НЕ ВЪРХУ СБОРА, И РАЗЛИКАТА Е ЕДНА СТОТИНКА.</b> Всяко перо се закръгля
   * самостоятелно: две пера по 100,00 при норма 22 и 5 дни дават 77,27 + 77,27 = 154,54, докато
   * сборът 200,00 карвиран наведнъж дава 154,55. Перото по чл. 177 връща онова, което редовете
   * НАИСТИНА са загубили — сметнато върху сбора, то щеше да върне с една стотинка повече и „нето
   * нула“ да се счупи точно при два ръчни реда.
   *
   * <p><b>⚠️ НЕПЛАТЕНИЯТ ОТПУСК СТОИ НЕПОДВИЖЕН ОТ ДВЕТЕ СТРАНИ</b> — същият довод като при
   * основната и класа в {@link #art177Inputs}: карвът е разликата между два изхода, които се
   * различават САМО по дните по чл. 177.
   */
  protected BigDecimal manualPermanentCarve(
      BgPaySlip slip, BigDecimal art177Days, BigDecimal unpaidDays, Integer norm) {
    BigDecimal carve = BigDecimal.ZERO;
    if (norm == null || norm <= 0) {
      // ⚠️ НУЛА, А НЕ ИЗКЛЮЧЕНИЕ: без норма няма делител, а art177Inputs вече е отказал по-горе за
      // същата причина. Второ хвърляне тук би сменило назования отказ с NPE.
      return carve;
    }
    for (BgPaySlipLine line : manualPermanentLines(slip)) {
      BigDecimal entered = enteredAmountOf(line);
      BigDecimal uncarved =
          BgEarningsResolver.monthlyFixedAfterLeaveCarve(entered, null, unpaidDays, norm);
      BigDecimal carved =
          BgEarningsResolver.monthlyFixedAfterLeaveCarve(entered, art177Days, unpaidDays, norm);
      carve = carve.add(uncarved.subtract(carved));
    }
    return carve;
  }

  /**
   * МАХАНЕ НА ПЕРО, КОЕТО ЕНДЖИНЪТ ВЕЧЕ НЕ ПРИЗНАВА — само за {@code NOT_ENTITLED}.
   *
   * <p>⚠️ Редът се ТРИЕ, а не се занулява. Перо „клас прослужено време — 0,00“ върху фиш на
   * управител по ДУК твърди, че класът се дължи и този месец е нула лева; истината е, че не се
   * дължи изобщо. Връзката е {@code orphanRemoval = true}, тоест махнат от списъка, редът изчезва и
   * от базата — точно каквото се иска, и причината махането да е тук, а не SQL някъде.
   *
   * <p>⚠️ Вика се САМО от {@link #generate}, тоест само върху ЧЕРНОВА: потвърденият фиш не стига
   * дотук ({@code generate} излиза на статуса), а затворен месец се поправя с втори фиш, не с
   * редакция на първия.
   */
  protected void drop(BgPaySlip slip, String lineCode) {
    if (slip.getBgPaySlipLineList() == null) {
      return;
    }
    BgPaySlipLine line = lineWithCode(slip, lineCode);
    if (line != null) {
      slip.getBgPaySlipLineList().remove(line);
      line.setPaySlip(null);
    }
  }

  /**
   * Записва едно сметнато перо — ново или обновено.
   *
   * <p>Количеството и ставката се пишат ЗАЕДНО със сумата: те са документацията ѝ и без тях перото
   * е число без произход. Обновяването ги пренаписва и двете, инак на обновен ред щяха да останат
   * числата от предишното смятане и да обясняват друга сума.
   */
  protected void upsert(BgPaySlip slip, BgEarningsOutcome outcome) {
    BgPaySlipLine line = lineWithCode(slip, outcome.lineCode);
    if (line == null) {
      BgPaySlipLineType type = lineType(outcome.lineCode);
      if (type == null) {
        // Речникът няма този ред — App Install не е пускан. Мълчаливо прескачане тук би оставило
        // фиш без заплата и нито един отказ, затова изборът е ИЗКЛЮЧЕНИЕ, а не тихо нищо.
        throw new IllegalStateException(
            "типът перо „"
                + outcome.lineCode
                + "“ липсва в речника (bg_pay_slip_line_type.csv) — начисленията не могат да се"
                + " генерират. Пусни App Install.");
      }
      line = lineFrom(type, outcome.amount);
      addLine(slip, line);
    }
    line.setBgAmount(outcome.amount);
    line.setBgQuantity(outcome.quantity);
    line.setBgRate(outcome.rate);
  }

  /** Перото на този фиш с този код на тип, или {@code null}. Кодът е стабилният идентификатор. */
  protected BgPaySlipLine lineWithCode(BgPaySlip slip, String code) {
    if (slip.getBgPaySlipLineList() == null) {
      return null;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type != null && code.equals(type.getCode())) {
        return line;
      }
    }
    return null;
  }

  /**
   * ТРУДОВО ПРАВООТНОШЕНИЕ ЛИ Е ТОВА — въпросът, от който виси самото право на клас.
   *
   * <p>⚠️ ШЕВ, КОЙТО ДЕЛЕГИРА, А НЕ ВТОРИ ПРОЧИТ. Отговорът се вади от вида осигурен, валиден за
   * периода на фиша, и този темпорален филтър вече си има ЕДИН дом — {@code BgPaySlipService}.
   * Повторен тук, той щеше да е втори филтър над същата таблица: два реда за един фиш, единият за
   * данъчната ставка, другият за класа. Същият идиом като {@link #lineFrom}, който също минава
   * оттам.
   *
   * <p>⚠️ {@code null} се предава НАГОРЕ, а не се заменя: „никой не е питан“ е отказ, не отговор —
   * виж {@code BgEarningsFacts#entitledToSeniorityBonus}.
   */
  protected Boolean seniorityEntitlement(BgPaySlip slip) {
    return Beans.get(BgPaySlipService.class).seniorityBonusEntitlement(slip);
  }

  /** Дружеството, което ПЛАЩА по договора — не активното. Процентът за клас е негов. */
  protected Company companyOf(BgPaySlip slip) {
    return slip.getEmploymentContract() == null
        ? null
        : slip.getEmploymentContract().getPayCompany();
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
   * {@code SENIORITY_MIN_PCT} за датата, или {@code null}, ако редът липсва.
   *
   * <p>⚠️ Отделен метод, а не два реда вътре в {@link #seniorityRatePct}, точно за да може
   * правилото за пода да се провери БЕЗ база: тестът подменя този шев и проверява само реда на
   * двата източника.
   */
  protected BigDecimal statutoryMinPct(LocalDate date) {
    return payrollParameter(PARAM_SENIORITY_MIN_PCT, date);
  }

  /**
   * СТОЙНОСТТА НА ЕДИН ПАРАМЕТЪР за датата, или {@code null}, ако редът липсва.
   *
   * <p>⚠️ ЕДИН ЧЕТЕЦ ЗА ВСИЧКИТЕ СЕДЕМ, и това е същият довод, с който {@link #statutoryMinPct}
   * ползва {@code BgPayrollParameterRepository}, а не своя заявка: втори механизъм за едно и също
   * нещо е свободен да се размине с първия по ДАТИТЕ, а разминаване по дати значи, че две части на
   * един фиш смятат по различни версии на закона. Тук разминаването щеше да е между нощната ставка
   * и минималната работна заплата, върху която тя се смята.
   *
   * <p>⚠️ {@link #statutoryMinPct} остава като ОТДЕЛЕН метод, макар че вече само делегира: той е
   * шевът, който заварените свидетели подменят. Махнат, тестовете щяха да падат не защото правилото
   * се е сменило, а защото името е изчезнало.
   *
   * <p>{@code null} НЕ е нула и се предава нагоре: всяко от перата го обявява с името на своя
   * параметър и казва, че се зарежда през App Install.
   */
  protected BigDecimal payrollParameter(String code, LocalDate date) {
    if (date == null) {
      return null;
    }
    BgPayrollParameter row =
        Beans.get(BgPayrollParameterRepository.class)
            .all()
            .filter(
                "self.code = :c AND self.validFrom <= :d "
                    + "AND (self.validTo IS NULL OR self.validTo >= :d)")
            .bind("c", code)
            .bind("d", date)
            .fetchOne();
    return row == null ? null : row.getParamValue();
  }

  /**
   * УГОВОРЕНОТО УВЕЛИЧЕНИЕ по вид извънреден труд за периода — {@code ExtraHoursType.increase}.
   *
   * <p><b>⚠️ ЧЕТЕ СЕ, ЗА ДА СЕ ОТКАЖЕ, А НЕ ЗА ДА СЕ ПЛАТИ ПО НЕГО.</b> Виж {@link
   * BgEarningsResolver#overtimeUpliftPct}: полето е на ВИДА допълнителни часове, не на договора на
   * лицето, и има таван 100 — тоест то не може да бъде ставката, по която се плаща. Но може да
   * издаде, че работодателят е уговорил ПОВЕЧЕ от законовия минимум, а тогава сметката по минимума
   * е недоплащане. Оттам и единственият му изход: отказ с назована причина.
   *
   * <p>⚠️ MAX, а не сбор и не първият: един вид извънреден труд може да е представен от НЯКОЛКО
   * реда в речника с различни проценти. Сборът им не значи нищо, а първият зависи от подредбата.
   * По-високият е онзи, който би направил сметката по минимума недоплащане.
   *
   * <p>⚠️ ВТОРИ ПРОЧИТ НА СЪЩИТЕ РЕДОВЕ, И ТОВА Е ПРИЕТО СЪЗНАТЕЛНО. {@code
   * BgPayrollInputBuilder#overtimeHoursOf} чете ЧАСОВЕТЕ от тях; тук се чете ПРОЦЕНТЪТ. Двата
   * прочита не могат да се разминат по число, защото не четат едно и също число — филтрите обаче
   * трябва да останат едни и същи, затова статусът идва от {@code
   * BgPayrollInputBuilder.EXTRA_HOURS_VALIDATED}, а не от свое копие.
   */
  protected Map<String, BigDecimal> agreedOvertimeUplift(BgPaySlip slip, BgPayrollInput input) {
    Map<String, BigDecimal> agreed = new LinkedHashMap<>();
    if (input == null
        || input.employeeId == null
        || input.periodFrom == null
        || input.periodTo == null) {
      return agreed;
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT t.bgExtraHoursKind, MAX(t.increase) FROM ExtraHoursLine l"
                    + " JOIN l.extraHoursType t"
                    + " WHERE l.employee.id = :employee AND l.date BETWEEN :from AND :to"
                    + " AND l.extraHours.statusSelect = :validated"
                    + " AND t.bgExtraHoursKind IS NOT NULL"
                    + " GROUP BY t.bgExtraHoursKind",
                Object[].class)
            .setParameter("employee", input.employeeId)
            .setParameter("from", input.periodFrom)
            .setParameter("to", input.periodTo)
            .setParameter("validated", BgPayrollInputBuilder.EXTRA_HOURS_VALIDATED)
            .getResultList();
    for (Object[] row : rows) {
      if (row[0] instanceof String && row[1] instanceof BigDecimal) {
        agreed.put(((String) row[0]).trim(), (BigDecimal) row[1]);
      }
    }
    return agreed;
  }

  /**
   * Ново перо от речника — ПРЕЗ {@code BgPaySlipService#lineFrom}, за да остане ЕДНО мястото,
   * където речникът се чете и замразява по копиране. Второ копие на това правило е свободно да
   * пропусне флаг.
   */
  protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
    return Beans.get(BgPaySlipService.class).lineFrom(type, amount);
  }

  /** Закачането от двете страни на връзката — o2m с mappedBy не се пълни сам. */
  protected void addLine(BgPaySlip slip, BgPaySlipLine line) {
    if (slip.getBgPaySlipLineList() == null) {
      slip.setBgPaySlipLineList(new ArrayList<>());
    }
    line.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(line);
  }

  /** Редът на речника по стабилния си код, или {@code null}. */
  protected BgPaySlipLineType lineType(String code) {
    return Beans.get(BgPaySlipLineTypeRepository.class)
        .all()
        .filter("self.code = :c")
        .bind("c", code)
        .fetchOne();
  }

  /**
   * ВСИЧКИ ДЕЙСТВАЩИ редове на речника, по реда им на фиша.
   *
   * <p>⚠️ Само активните: закрит тип перо е бил валиден за минали периоди и остава видим там, но
   * нов фиш не бива да го носи. Подредбата е по {@code sequence}, за да е върнатият доклад в реда,
   * в който човек чете фиша.
   */
  protected List<BgPaySlipLineType> lineTypes() {
    return Beans.get(BgPaySlipLineTypeRepository.class)
        .all()
        .filter("self.active IS TRUE")
        .order("sequence")
        .fetch();
  }
}
