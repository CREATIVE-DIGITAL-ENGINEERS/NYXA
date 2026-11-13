package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.repo.BgInsuredTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Resolves the insurable income base: {@code min(max(gross, МОД floor, statutory floor), cap)}.
 *
 * <p><b>There are two floors, not one.</b> МОД covers the insured types that have an Appendix 1
 * row; for the rest — self-insured persons, state and judicial servants — the floor is a flat
 * statutory amount that does not depend on economic activity or qualification group and therefore
 * cannot live in the МОД table. Which one applies is data, not code: {@code
 * BgInsuredType.bgFloorType} names a {@code BgPayrollParameter} code ({@code MIN_WAGE} for КСО art.
 * 6 par. 5, which makes contributions for the persons under art. 4 par. 1 items 2, 3, 4 and 10 due
 * on no less than the minimum monthly wage — ДВ бр. 74/2002 as amended to 2012, <b>not</b> a 2026
 * provision; {@code MIN_INCOME_SOL} for the self-insured) or {@code NONE}. Do not confuse it with
 * art. 6 par. 5a, which sets the 80:20 split for state and judicial servants from 01.08.2026 and is
 * not a floor at all. Types that have МОД carry {@code NONE}, so the extra {@code max} is a no-op
 * for them and the two mechanisms never need to know about each other.
 *
 * <p>Both the ceiling and the statutory floor are read from {@code BgPayrollParameter} for the
 * reference date — no constant in code, so the 01.08.2026 rise (cap 2111.64 → 2300, self-insured
 * minimum 550.66 → 620.20 EUR) is a data change. A missing parameter blocks with a clear reason
 * rather than silently dropping the bound: dropping a floor lowers the base, which is the direction
 * that does not announce itself.
 *
 * <p><b>Proration follows the relationship, not the floor.</b> Both floors are prorated per НЕВД
 * art. 1 par. 3 and par. 4 — see {@link #prorate} — but only for relationships that are measured in
 * working time. At four contracted hours a day the income is owed against half the floor. A
 * self-insured person has no working time to be part of, and neither does a management contract, so
 * for them a monthly floor is owed whole. The question is asked once, of the insured type, and the
 * answer governs both floors — see {@link #measuredInWorkTime}.
 *
 * <p>Proration touches floors and nothing else: the gross is what was actually paid, and the cap is
 * a statutory ceiling, not a quantity earned over the month.
 *
 * <p>The arithmetic ({@link #compute}) is pure and unit tested by passing the bounds as input,
 * mirroring how the resolvers take their rows; {@link #resolve} only fetches them and delegates.
 */
public class BgInsurableIncomeService {

  /**
   * Кодът на параметъра с максималния месечен осигурителен доход по ЗБДОО.
   *
   * <p>⚠️ ПУБЛИЧЕН, защото ВТОРИ читател го поиска: {@code BgD1Emitter} чете същата стойност за
   * тавана на т. 34 (НПОПДОО чл. 32 ал. 1). Литерал, преписан там, би бил второ копие на код, който
   * сменя стойността си два пъти в една година (чл. 9 т. 1 и т. 2 ЗБДОО за 2026 г.) — и двете копия
   * биха изглеждали верни, докато някой не преименува реда в речника.
   */
  public static final String CODE_MAX_INSURABLE_INCOME = "MAX_INSURABLE_INCOME";

  /**
   * Кодът на параметъра с минималната работна заплата за страната.
   *
   * <p>⚠️ ЕДИН НИЗ, ДВА РАЗЛИЧНИ ЧИТАТЕЛЯ, И ТЕ НЕ ПИТАТ ЕДНО И СЪЩО. Тук той е ПОД: {@code
   * BgInsuredType.bgFloorType} го назовава по име за кодовете по чл. 6 ал. 5 и {@link #floorCodeOf}
   * го чете като всяка друга стойност от речника. За гражданския договор (код 14) същият параметър
   * е ВРАТА — чл. 4 ал. 3 т. 5 пита дали изобщо възниква осигуряване, а не до колко се вдига
   * базата. Затова {@link #civilContractBase} го чете ИЗРИЧНО по константа, вместо да мине през
   * {@code bgFloorType}: вписан там, той щеше да вдига база 450,00 до 620,20.
   */
  public static final String CODE_MIN_WAGE = "MIN_WAGE";

  /** {@code BgInsuredType.bgFloorType} value meaning "no statutory floor for this insured type". */
  static final String FLOOR_NONE = "NONE";

  /**
   * Working scale for the proration coefficient. Ten digits keeps 40/168 and 10/21 far away from
   * the second decimal of the result; the rounding that matters happens once, on the money.
   */
  static final int COEFFICIENT_SCALE = 10;

  /**
   * Fetches the bounds for the date and computes the base for a full month.
   *
   * @param gross the gross remuneration
   * @param mod the МОД resolution; its floor applies only when it resolved
   * @param insuredTypeCode the NAP insured-type code. It selects two things: which statutory floor
   *     applies ({@code bgFloorType}) and whether the floors shrink with part working time ({@code
   *     bgZeroWorkTime}). An unknown code yields no statutory floor and prorating behaviour.
   * @param date the reference date selecting the temporal parameter rows
   */
  public BgInsurableIncomeResult resolve(
      BigDecimal gross, BgMinIncomeResult mod, String insuredTypeCode, LocalDate date) {
    return resolve(gross, mod, insuredTypeCode, date, BgWorkTimeFacts.fullMonth());
  }

  /**
   * As {@link #resolve(BigDecimal, BgMinIncomeResult, String, LocalDate)}, prorating both floors.
   */
  public BgInsurableIncomeResult resolve(
      BigDecimal gross,
      BgMinIncomeResult mod,
      String insuredTypeCode,
      LocalDate date,
      BgWorkTimeFacts facts) {
    return resolve(
        gross,
        mod,
        insuredTypeCode,
        date,
        facts,
        BgIncomeCoordinationResolver.Declaration.absent());
  }

  /**
   * Както горното, но СРЕЩУ ДЕКЛАРИРАН ЧУЖД ОСИГУРИТЕЛЕН ДОХОД — КСО чл. 6 ал. 11.
   *
   * <p><b>⚠️ ДВА ПЪТЯ, НЕ ЕДИН С ПОПРАВКА.</b> При активна декларация базата излиза от {@link
   * BgIncomeCoordinationResolver}, а нормалният път (МОД под, законов праг, пропорция, единичен
   * таван) НЕ се минава изобщо. Обратното — да се смята нормалната база и после да се „поправи“ —
   * би приложило пода ДВА пъти: веднъж тук и веднъж при другия платец, тоест лице с два договора би
   * се осигурило върху два МОД-а, а сборът пак щеше да изглежда под тавана.
   *
   * <p><b>⚠️ МОД ПОДЪТ НЕ СЕ ПРИЛАГА ПРИ АКТИВНА КООРДИНАЦИЯ, и това е самото правило.</b> Ал. 11
   * дължи вноски върху СБОРА от осигурителните доходи, но не повече от максималния — тоест прагът е
   * на ЛИЦЕТО за месеца, а не на всяко правоотношение поотделно. Приложен и тук, той би вдигнал
   * базата на второто правоотношение до цял МОД върху доход, чийто праг вече е покрит от първото.
   * Златното число е доходът 300,00 при деклариран 1000,00: базата остава 300,00, НЕ се вдига до
   * МОД. По същия довод НЕ се прилага и законовият праг ({@code bgFloorType}) — питането е същото
   * („дължи ли се месечен минимум по ТОВА правоотношение“) и отговорът е същият, точно както {@link
   * #compute} третира двата пода еднакво навсякъде другаде.
   *
   * <p><b>⚠️ И МОД ИЗОБЩО НЕ СЕ ПИТА, а не се пита и после се подминава.</b> Блокиращ МОД (липсващ
   * НКПД, ненамерен ред по Приложение № 1) при активна координация не може да мести базата — тя не
   * зависи от него. Отказ по повод, от който резултатът не зависи, спира верен фиш — както би спрял
   * фиш на морско лице заради непопълнени часове, от които сметката му не зависи. Затова тук МОД се
   * чете само по нормалния път.
   *
   * @param declared декларираният чужд доход; {@link
   *     BgIncomeCoordinationResolver.Declaration#absent()} значи „нормалният единичен път“
   */
  public BgInsurableIncomeResult resolve(
      BigDecimal gross,
      BgMinIncomeResult mod,
      String insuredTypeCode,
      LocalDate date,
      BgWorkTimeFacts facts,
      BgIncomeCoordinationResolver.Declaration declared) {

    BgPayrollParameter cap = parameterFor(CODE_MAX_INSURABLE_INCOME, date);
    BigDecimal capValue = cap != null ? cap.getParamValue() : null;
    LocalDate capValidFrom = cap != null ? cap.getValidFrom() : null;

    BgInsuredType insuredType = insuredTypeFor(insuredTypeCode, date);
    boolean prorates = measuredInWorkTime(insuredType);

    boolean coordinating = declared != null && declared.isActive();

    // ⚠️ ПРАГЪТ НЕ СЕ ЧЕТЕ ПРИ АКТИВНА КООРДИНАЦИЯ, тоест и не може да откаже заради липсващ
    // параметър. Долният праг не се ПРИЛАГА (виж javadoc-а отгоре) — а отказ заради стойност, която
    // няма да се ползва, спира верен фиш по повод, от който числото му не зависи.
    String floorCode = coordinating ? null : floorCodeOf(insuredType);
    BigDecimal floorValue = null;
    if (floorCode != null) {
      BgPayrollParameter floor = parameterFor(floorCode, date);
      if (floor == null) {
        // The insured type declares a floor and the parameter for the period is missing. Computing
        // without it would return a smaller base and a clean RESOLVED — exactly the failure this
        // floor exists to prevent.
        return BgInsurableIncomeResult.missingFloor(
            "липсва параметър "
                + floorCode
                + " за периода (вид осигурен: "
                + insuredTypeCode
                + ")");
      }
      floorValue = floor.getParamValue();
    }

    return compute(
        gross,
        mod,
        floorValue,
        prorates,
        capValue,
        capValidFrom,
        facts,
        ownLevel(),
        isSeafarer(insuredType),
        declared);
  }

  /**
   * Морско лице ли е този вид осигурен — от {@code BgInsuredType.bgIsSeafarer}.
   *
   * <p>⚠️ ФЛАГ, НЕ СПИСЪК С КОДОВЕ, и не втори прочит: същото поле, по което {@code
   * BgPaySlipService#incomeTaxRate} избира ставката от 1 на сто по чл. 42 ал. 4 ЗДДФЛ. Непознат вид
   * се чете като „не е морско лице“ — там отказът идва по друг път ({@code INSURED_TYPE_NOT_FOUND}
   * на МОД търсенето), а втори пазач тук само би сменил кое съобщение вижда операторът.
   */
  private static boolean isSeafarer(BgInsuredType insuredType) {
    return insuredType != null && Boolean.TRUE.equals(insuredType.getBgIsSeafarer());
  }

  /**
   * Нивото по КСО чл. 6 ал. 11 на ТОВА правоотношение.
   *
   * <p><b>⚠️ ДНЕС Е ЕДНО ЧИСЛО, И ТОВА Е ОБХВАТЪТ НА КЛОНА, НЕ ПРОПУСК.</b> Връща се т. 1 („доходи
   * от дейности по чл. 4 ал. 1 и 10“), защото подслучаят, който се моделира, е ВТОРИ РАБОТОДАТЕЛ:
   * трудово правоотношение срещу трудово правоотношение, тоест РАВЕН приоритет от двете страни.
   *
   * <p><b>⚠️ КАКВО СЛЕДВА ОТТУК, ЗА ДА НЕ СЕ СМЕТНЕ ЗА УРЕДЕНО.</b> {@code BgInsuredType} НЕ носи
   * колона с нивото по ал. 11 — проверено в домейна. Тоест докато такава колона не се появи, ниво,
   * различно от т. 1, не може да се ПРОЧЕТЕ, а извеждането му от списък с кодове би било точно
   * онова, което {@code bgFloorType} и {@code bgIsSeafarer} вече отказаха да бъдат: правило,
   * заковано в код вместо в данни.
   *
   * <p>Следствието се казва тук изрично: пазачът {@link
   * BgIncomeCoordinationResolver.Status#DECLARED_LOWER_PRIORITY} НЕ се задейства от този път — и
   * двете страни са на т. 1, тоест равни. Той пази ДРУГИТЕ повикващи на чистото правило: случая с
   * два договора при един работодател и морското лице, които са отделни парчета. Свидетелят му е
   * файловата половина, не тази.
   */
  private static int ownLevel() {
    return BgIncomeCoordinationResolver.LEVEL_ACTIVITY;
  }

  /**
   * Декларацията, сглобена от контрактното поле — ЕДНОТО място, където полето става правило.
   *
   * <p>⚠️ Наименувана врата, а не {@code Declaration.ofSecondEmployer(...)} на всяко място на
   * извикването: така подразбирането „сумата на фиша е доход от ВТОРИ РАБОТОДАТЕЛ, тоест т. 1 по
   * ал. 11“ се чете и се сменя на едно място. Полето носи сума БЕЗ произход — виж {@code
   * BgPaySlip.bgOtherPayerInsurableIncome} — и това подразбиране е съзнателно решение.
   */
  public static BgIncomeCoordinationResolver.Declaration coordinationOf(
      BigDecimal otherPayerInsurableIncome) {
    return otherPayerInsurableIncome == null
        ? BgIncomeCoordinationResolver.Declaration.absent()
        : BgIncomeCoordinationResolver.Declaration.ofSecondEmployer(otherPayerInsurableIncome);
  }

  /**
   * БАЗАТА НА ГРАЖДАНСКИЯ ДОГОВОР — приспадането по КСО чл. 6 ал. 4 и прагът по чл. 4 ал. 3 т. 5.
   *
   * <p>Прочита на {@code MIN_WAGE} за периода и делегиране към чистия {@link
   * BgCivilContractResolver}. Само това: правилото е негово, а тук е единственият прочит на
   * параметъра, точно както {@link #resolve} е единственият прочит на тавана и на законовия праг.
   *
   * <p><b>⚠️ ТОВА Е БАЗА ПРЕДИ ТАВАНА, А НЕ ГОТОВА БАЗА.</b> Върнатото число влиза в {@link
   * #resolve} като БРУТО и оттам минава през същия таван, през който минава и заплатата — виж
   * {@code BgPaySlipService#insuranceGrossFor}. Обратният ред (таван, после приспадане) би отрязал
   * 2500,00 до 2111,64 и чак тогава би махнал 25 на сто — 1583,73 вместо верните 1875,00.
   *
   * <p><b>⚠️ ПАРАМЕТЪРЪТ СЕ ЧЕТЕ БЕЗУСЛОВНО, А СЕ ПОЛЗВА УСЛОВНО.</b> Отказът за липсващ {@code
   * MIN_WAGE} се вдига от резолвера и САМО когато лицето не е осигурено на друго основание — тоест
   * само когато отговорът зависи от него. Прочит без ползване не струва нищо; отказ без ползване би
   * спрял верен фиш.
   *
   * @param gross сборът на перата, обявени за осигурителен доход
   * @param expenseRate ставката по чл. 29 ал. 1 ЗДДФЛ от {@code EmploymentContract.bgIncomeType}
   * @param insuredOnOtherGround {@code EmploymentContract.bgInsuredOnOtherGround}
   */
  public BgCivilContractResolver.Outcome civilContractBase(
      BigDecimal gross, BigDecimal expenseRate, boolean insuredOnOtherGround, LocalDate date) {
    BgPayrollParameter minWage = parameterFor(CODE_MIN_WAGE, date);
    return BgCivilContractResolver.resolve(
        gross, expenseRate, insuredOnOtherGround, minWage == null ? null : minWage.getParamValue());
  }

  /**
   * Пропорционира ли се прагът за този вид осигурен с работното време?
   *
   * <p><b>Защо е публичен.</b> Отговорът трябва на ПОВИКВАЩИТЕ, преди да са стигнали до {@link
   * #resolve}: работно-времевият отказ по НЕВД чл. 1 ({@code BgPayrollWorkTime.decide}) няма смисъл
   * за вид, при който прагът се дължи ЦЯЛ — той пази избор между ал. 3 и ал. 4, който при {@code
   * bgZeroWorkTime} изобщо не се прави. Безусловен, гейтът би спрял фиша на морско лице (код 92)
   * заради непопълнено {@code EmploymentContract.bgHoursPerDay} — вход, от който резултатът му НЕ
   * зависи.
   *
   * <p>⚠️ СЪЩИЯТ ПРОЧИТ, не втори. Вътре е {@link #measuredInWorkTime} върху {@link
   * #insuredTypeFor} — буквално двете стъпки, които {@link #resolve} прави за своя {@code
   * prorates}. Отделно копие на правилото в повикващия би било свободно да се размине с това, а
   * разминаването се мери в пари: гейт, който смята че се пропорционира, срещу резолвер, който не
   * пропорционира.
   *
   * <p>⚠️ Непознатият вид се чете като „пропорционира се“ — същата отрицателна форма като в {@link
   * #measuredInWorkTime}. Тоест при съмнение гейтът ОСТАВА, вместо да падне тихо.
   */
  public boolean proratesWithWorkTime(String insuredTypeCode, LocalDate date) {
    return measuredInWorkTime(insuredTypeFor(insuredTypeCode, date));
  }

  /** The {@code BgInsuredType} row in force at the date, or null. */
  private BgInsuredType insuredTypeFor(String insuredTypeCode, LocalDate date) {
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

  /** The {@code BgPayrollParameter} row in force at the date, or null. */
  private BgPayrollParameter parameterFor(String code, LocalDate date) {
    return Beans.get(BgPayrollParameterRepository.class)
        .all()
        .filter(
            "self.code = :c AND self.validFrom <= :d "
                + "AND (self.validTo IS NULL OR self.validTo >= :d)")
        .bind("c", code)
        .bind("d", date)
        .fetchOne();
  }

  /**
   * The parameter code naming this insured type's statutory floor, or null when there is none.
   *
   * <p>Blank is read as {@code NONE}, and this is the only place that decision is taken. The column
   * was added to a populated table and there is no migration mechanism yet, so rows seeded before
   * it existed carry no value; treating blank as "no floor" keeps them behaving exactly as they did
   * rather than blocking every calculation until the seed is reloaded. An unknown insured type also
   * yields no floor — the МОД lookup already blocks on that with {@code INSURED_TYPE_NOT_FOUND},
   * and duplicating the guard here would only change which message the user sees.
   */
  private String floorCodeOf(BgInsuredType insuredType) {
    if (insuredType == null) {
      return null;
    }
    String floorType = insuredType.getBgFloorType();
    if (floorType == null || floorType.trim().isEmpty() || FLOOR_NONE.equals(floorType.trim())) {
      return null;
    }
    return floorType.trim();
  }

  /**
   * Pure base computation for a full month — the coefficient is 1, so nothing shrinks.
   *
   * <p>A thin delegate over the prorating overload. Note it is the <em>facts</em> that make it a
   * full month, not a switch: passing {@code proratesWithWorkTime = true} here is still correct and
   * still leaves the floors whole.
   */
  public static BgInsurableIncomeResult compute(
      BigDecimal gross,
      BgMinIncomeResult mod,
      BigDecimal statutoryFloor,
      boolean proratesWithWorkTime,
      BigDecimal cap,
      LocalDate capValidFrom) {
    return compute(
        gross,
        mod,
        statutoryFloor,
        proratesWithWorkTime,
        cap,
        capValidFrom,
        BgWorkTimeFacts.fullMonth());
  }

  /**
   * Pure base computation. {@code min(max(gross, prorated МОД, prorated statutory floor), cap)}. A
   * null cap blocks, and so do unusable work-time facts and a blocking МОД status. A null {@code
   * statutoryFloor} means this insured type has none — not that one was expected and lost; {@link
   * #resolve} blocks in that case before reaching here.
   *
   * <p><b>Two МОД outcomes that must not be collapsed.</b> {@code NOT_APPLICABLE} means the insured
   * type has no МОД at all — a correct answer, and the remaining bounds stand on their own. Every
   * other non-resolved status ({@code NKPD_MISSING}, {@code NKID_NOT_FOUND}, {@code
   * NO_MOD_ROW_FOR_NKID} and the rest) means the floor exists but could not be found. Treating the
   * second like the first drops the floor and still reports RESOLVED — a lower base, silently.
   * {@link BgMinIncomeResult#isBlocking()} draws exactly this line.
   *
   * <p><b>{@code proratesWithWorkTime} is the caller's decision, not this method's.</b> It is a
   * property of the <em>relationship</em>, not of either floor, so it governs both: an employment
   * relationship at four contracted hours a day owes against half the floor, while a management
   * contract or a self-insured person has no working time for a floor to be part of and owes the
   * whole monthly figure. {@link #resolve} reads it from {@code BgInsuredType.bgZeroWorkTime} — see
   * {@link #measuredInWorkTime} — and passes it explicitly so the pure arithmetic never has to
   * guess.
   */
  public static BgInsurableIncomeResult compute(
      BigDecimal gross,
      BgMinIncomeResult mod,
      BigDecimal statutoryFloor,
      boolean proratesWithWorkTime,
      BigDecimal cap,
      LocalDate capValidFrom,
      BgWorkTimeFacts facts) {
    return compute(
        gross,
        mod,
        statutoryFloor,
        proratesWithWorkTime,
        cap,
        capValidFrom,
        facts,
        BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
        false,
        BgIncomeCoordinationResolver.Declaration.absent());
  }

  /**
   * Чистата база СРЕЩУ ДЕКЛАРИРАН ЧУЖД ОСИГУРИТЕЛЕН ДОХОД — КСО чл. 6 ал. 11.
   *
   * <p><b>⚠️ ЕДНАТА ИСТИНА ЗА КООРДИНИРАНАТА БАЗА Е ТУК.</b> И вноските, и данъчната основа стъпват
   * на ЕДНО число: вноските се смятат върху него, перата от 5xx го носят, а данъчната основа е
   * облагаемите начисления МИНУС точно тези пера ({@code BgPaySlipResolver#taxBase}). Тоест няма
   * втора сметка, която да се размине с първата, и {@code refuseTaxMismatch} не може да се спъне в
   * разлика, каквато не съществува. Сметната два пъти — веднъж за вноските и веднъж за данъка, —
   * координацията щеше да е точно онзи тих провал, срещу който целият модул е построен.
   *
   * <p><b>⚠️ КООРДИНАЦИЯТА ИЗМЕСТВА ПОДОВЕТЕ, А НЕ СЕ ДОБАВЯ КЪМ ТЯХ.</b> При активна декларация
   * нито МОД, нито законовият праг, нито пропорцията по НЕВД чл. 1 участват — базата е остатъкът до
   * тавана и нищо друго. Виж {@link #resolve(BigDecimal, BgMinIncomeResult, String, LocalDate,
   * BgWorkTimeFacts, BgIncomeCoordinationResolver.Declaration)} за защо.
   *
   * <p><b>⚠️ ОБЛАГАЕМИЯТ ДОХОД ОСТАВА ПЪЛНИЯТ ПРИДОБИТ И НЕ СЕ ПИПА ОТТУК.</b> Координацията реже
   * ОСИГУРИТЕЛНАТА база, не дохода: лицето е получило каквото е получило. Оттам данъчната основа
   * (облагаем − лични вноски) се ВДИГА сама, защото вноските паднаха — в примера от 1079,47 на
   * 1252,00. Това не е страничен ефект, а самото правило на чл. 42 ал. 2 ЗДДФЛ, приложено върху
   * вярната осигурителна база.
   *
   * @param ownLevel нивото по ал. 11 на ТОВА правоотношение — константите {@code
   *     BgIncomeCoordinationResolver.LEVEL_*}
   * @param ownIsSeafarer морско лице ли е ТОВА правоотношение — при активна декларация е ОТКАЗ
   * @param declared декларираният чужд доход, или {@link
   *     BgIncomeCoordinationResolver.Declaration#absent()}
   */
  public static BgInsurableIncomeResult compute(
      BigDecimal gross,
      BgMinIncomeResult mod,
      BigDecimal statutoryFloor,
      boolean proratesWithWorkTime,
      BigDecimal cap,
      LocalDate capValidFrom,
      BgWorkTimeFacts facts,
      int ownLevel,
      boolean ownIsSeafarer,
      BgIncomeCoordinationResolver.Declaration declared) {

    if (declared != null && declared.isActive()) {
      BgIncomeCoordinationResolver.Outcome outcome =
          BgIncomeCoordinationResolver.coordinate(gross, ownLevel, ownIsSeafarer, declared, cap);
      switch (outcome.getStatus()) {
        case COORDINATED:
          return BgInsurableIncomeResult.coordinated(
              outcome.getBase(), cap, capValidFrom, outcome.getDeclared());
        case SEAFARER_MULTI_SOURCE:
          return BgInsurableIncomeResult.seafarerMultiSource(
              outcome.getDetail(), outcome.getDeclared());
        case DECLARED_LOWER_PRIORITY:
          return BgInsurableIncomeResult.declaredLowerPriority(
              outcome.getDetail(), outcome.getDeclared());
        case MISSING_CAP:
          return BgInsurableIncomeResult.missingCap(outcome.getDetail());
        default:
          // NOT_DECLARED не може да стигне дотук — питането isActive() е същото. Стигне ли, това е
          // разминаване между двата прочита и трябва да се ВИДИ, вместо да върне база.
          return BgInsurableIncomeResult.missingCap(
              "координацията върна " + outcome.getStatus() + " при активна декларация");
      }
    }

    if (cap == null) {
      return BgInsurableIncomeResult.missingCap("липсва параметър MAX_INSURABLE_INCOME за периода");
    }
    if (mod != null && mod.isBlocking()) {
      String detail = mod.getDetail();
      return BgInsurableIncomeResult.modBlocked(
          "МОД не е определен: "
              + mod.getStatus()
              + (detail != null && !detail.isEmpty() ? " (" + detail + ")" : ""));
    }
    BigDecimal floor = gross != null ? gross : BigDecimal.ZERO;
    // Both floors take the same treatment, because the question is the same for both: does this
    // relationship have working time to be part of? If it does not, a monthly floor is owed whole.
    if (mod != null && mod.isResolved() && mod.getAmount() != null) {
      Applied applied = applyFloor(mod.getAmount(), proratesWithWorkTime, facts);
      if (applied.problem != null) {
        return BgInsurableIncomeResult.invalidWorkTime(applied.problem);
      }
      floor = floor.max(applied.amount);
    }
    if (statutoryFloor != null) {
      Applied applied = applyFloor(statutoryFloor, proratesWithWorkTime, facts);
      if (applied.problem != null) {
        return BgInsurableIncomeResult.invalidWorkTime(applied.problem);
      }
      floor = floor.max(applied.amount);
    }
    // The cap is applied last and is never prorated: it is a monthly statutory ceiling, not a
    // quantity earned over the month.
    BigDecimal base = floor.min(cap);
    return BgInsurableIncomeResult.resolved(base, cap, capValidFrom);
  }

  /**
   * Is this relationship measured in working time at all?
   *
   * <p>Read from {@code BgInsuredType.bgZeroWorkTime} — the flag that already says "report zero in
   * Declaration 1 item 16, positions 3 and 4". A relationship that reports no working days or hours
   * has no working time for a floor to be part of. It is true for management contracts (code 10),
   * for every self-insured code (12, 13, 22) and for the other hour-free grounds; it is false for
   * employment relationships, including state servants, the judiciary and the military.
   *
   * <p>There is no separate column for proration: this one already draws exactly the line — "СОЛ
   * няма часове, както ДУК" and "чл. 4 ал. 1 т. 4 се пропорционира". A second field would be a
   * second source for one fact, free to drift from this one.
   *
   * <p>The negative form matters for safety: the flag is false by default, so a type seeded before
   * anything cared keeps prorating, which is the behaviour it had.
   */
  private static boolean measuredInWorkTime(BgInsuredType insuredType) {
    return insuredType == null || !Boolean.TRUE.equals(insuredType.getBgZeroWorkTime());
  }

  /** A floor as it applies this month — prorated when the relationship is measured in work time. */
  private static Applied applyFloor(
      BigDecimal monthly, boolean proratesWithWorkTime, BgWorkTimeFacts facts) {
    if (!proratesWithWorkTime) {
      return new Applied(monthly, null);
    }
    Proration proration = prorate(monthly, facts);
    return new Applied(proration.amount, proration.problem);
  }

  /** Either the amount to compare against, or a reason it cannot be produced. */
  private static final class Applied {
    final BigDecimal amount;
    final String problem;

    Applied(BigDecimal amount, String problem) {
      this.amount = amount;
      this.problem = problem;
    }
  }

  /**
   * Prorates a monthly floor — МОД or the statutory one — per НЕВД art. 1.
   *
   * <p>Par. 4 (hours) is tried first and par. 3 (days) second, because the hourly rule covers both
   * situations at once whenever the worked hours are counted correctly — a part-time contract in a
   * part month needs one calculation, not two. Which rule applies is decided by which facts the
   * caller supplied, not by which one gives a smaller number.
   *
   * <p>Both denominators are monthly totals — statutory hours for the month, working days in the
   * month. Never a daily norm: at 40 contracted hours a month over 21 working days, 40/168 = 0.2381
   * and the daily 2/8 = 0.2500, roughly 5% apart on the base.
   *
   * <p>The days (hours) under art. 40 par. 5 КСО count towards the numerator — the base is worked
   * <em>plus</em> those, not worked alone.
   *
   * <p><b>⚠️ ТАВАНЪТ 1 Е НАШЕ ЧЕТЕНЕ — И ВЕЧЕ СЕ ЗНАЕ СРЕЩУ КАКВО Е СВЕРЕНО.</b> Коефициентът се
   * ограничава до 1: прагът е праг, не цел, която расте над пълен месец, тоест извънреден труд не
   * вдига законов МИНИМУМ.
   *
   * <p>Сверено дословно срещу НЕВД чл. 1 ал. 3 и ал. 4 (обн. ДВ бр. 263 от 30.12.1999 г., ред.
   * 2024): НИТО ЕДНАТА алинея не съдържа таван. Ал. 3 дава „раздели на броя на работните дни …
   * умножи по броя на отработените дни“; ал. 4 — „раздели на броя на законоустановените работни
   * часове за месеца … умножи по броя на отработените часове“. Нищо повече.
   *
   * <p><b>Затова четенето ОСТАВА четене, а не става цитат.</b> Сверката не смени какво е — то си е
   * извод, — а смени две други неща: че вече е сверено, и срещу какво. Нормата МЪЛЧИ по въпроса, а
   * мълчанието на нормата не е разрешение да се мълчи и в кода: неограничен коефициент би вдигал
   * законовия минимум с всеки извънреден час, и то тихо.
   *
   * <p>Стои заковано на ЕДНО място — {@code
   * BgInsurableIncomeServiceTest.coefficientIsCappedAtOneSoOvertimeDoesNotRaiseTheFloor} — за да се
   * смени с един ред, ако някога се появи основание за друго четене.
   */
  static Proration prorate(BigDecimal monthlyAmount, BgWorkTimeFacts facts) {
    BigDecimal denominator;
    BigDecimal numerator;
    String unit;

    if (facts == null || (!facts.hasHourData() && !facts.hasDayData())) {
      return Proration.of(monthlyAmount);
    }
    if (facts.hasHourData()) {
      denominator = facts.getStatutoryMonthlyHours();
      numerator = nz(facts.getWorkedHours()).add(nz(facts.getArt40Par5Hours()));
      unit = "часове";
    } else {
      denominator = facts.getWorkingDaysInMonth();
      numerator = nz(facts.getWorkedDays()).add(nz(facts.getArt40Par5Days()));
      unit = "дни";
    }

    // Never divide by zero and never fall back to "treat as a full month" — both would move the
    // base with nothing on screen to say why.
    if (denominator == null || denominator.signum() <= 0) {
      return Proration.blocked(
          "невалиден знаменател за пропорция по НЕВД: законоустановени "
              + unit
              + " = "
              + (denominator != null ? denominator.toPlainString() : "(няма)")
              + " (трябва да е > 0)");
    }
    if (numerator.signum() < 0) {
      return Proration.blocked(
          "отрицателен числител за пропорция по НЕВД: отработени + чл. 40 ал. 5 КСО "
              + unit
              + " = "
              + numerator.toPlainString());
    }

    BigDecimal coefficient = numerator.divide(denominator, COEFFICIENT_SCALE, RoundingMode.HALF_UP);
    if (coefficient.compareTo(BigDecimal.ONE) > 0) {
      coefficient = BigDecimal.ONE;
    }
    return Proration.of(monthlyAmount.multiply(coefficient).setScale(2, RoundingMode.HALF_UP));
  }

  private static BigDecimal nz(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  /** Either a prorated amount or a reason why one cannot be produced. */
  static final class Proration {

    final BigDecimal amount;
    final String problem;

    private Proration(BigDecimal amount, String problem) {
      this.amount = amount;
      this.problem = problem;
    }

    static Proration of(BigDecimal amount) {
      return new Proration(amount, null);
    }

    static Proration blocked(String problem) {
      return new Proration(null, problem);
    }
  }
}
