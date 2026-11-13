package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.db.repo.BgAbsenceTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.BgEffectivePaySlips;
import com.axelor.apps.bglocalization.service.BgInsuranceCalculationService;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * БАЗОВАТА ПОЛОВИНА НА ЕНДЖИНА ЗА БОЛНИЧНИ — изважда стойностите и вика чистата половина.
 *
 * <p><b>Разделението.</b> Аритметиката на дните и на сумите живее в {@link BgSickLeaveResolver} и
 * върви без среда; тук стои единствено четенето от речника на отсъствията, от производствения
 * календар, от каталога с параметри и от таблицата със ставки. Двете не се смесват: правило,
 * написано до заявка към базата, спира да е проверимо без база.
 *
 * <p><b>Какво този клас произвежда — четири неща, и само две от тях са пера.</b>
 *
 * <ol>
 *   <li><b>ДНИТЕ</b> на фиша: {@code bgSickDaysEmployer} и {@code bgSickDaysDoo}, плюс намалените
 *       отработени дни. Виж {@link #applyDays}.
 *   <li><b>РАЗБИВЪТ ПО Т. 9</b> на Приложение № 9 — същите дни, записани върху САМИТЕ болнични
 *       листове, по месец и по платец. Виж {@link #applyPeriodLines}. ⚠️ НЕ мени нито едно число на
 *       фиша: агрегатите горе остават входът на всичко, което се смята.
 *   <li><b>ПЕРОТО</b> {@code pl_bolnichen_rabotodatel} (195) със сумата по чл. 40 ал. 5 КСО.
 *   <li><b>ЗДРАВНАТА ВНОСКА</b> за дните ВНР по ЗЗО чл. 40 ал. 1 т. 5 — сметната и ВЪРНАТА, но ⚠️
 *       <b>НЕ записана като перо</b>. Виж {@link #healthAbsence}.
 * </ol>
 *
 * <p><b>⚠️ РЕДЪТ СПРЯМО НАЧИСЛЕНИЯТА НЕ Е СВОБОДЕН.</b> {@link #applyDays} трябва да мине ПРЕДИ
 * {@code BgEarningsService}, защото болничните дни НАМАЛЯВАТ отработените, а начисленията четат
 * точно отработените дни от фиша. Пуснат след тях, основното възнаграждение остава сметнато за цял
 * месец — при лице, болно целия юли, това е 1805,00 вместо 0,00 и фишът изглежда напълно нормален.
 * Редът е закован със свидетел — {@code BgPaySlipServiceTest.prepareRunsSickLeaveBeforeEarnings} —
 * а не оставен на подредбата в {@code BgPaySlipService#prepare}. Свидетелят пита какво начисленията
 * са ВИДЕЛИ, а не колко пъти са викани: брояч сам по себе си е 1 и при двете подредби.
 *
 * <p><b>⚠️ ДНЕС ЕНДЖИНЪТ Е СПЯЩ В ПРОИЗВОДСТВО, И ТОВА Е ОБЯВЕНО, НЕ СКРИТО.</b> {@code
 * BgPayrollInputBuilder.DECLARED_ABSENT} обявява и {@code sickLeaveEpisodes}, и {@code
 * insuranceServiceMonths} за без източник в модела: болничният лист не е документ ({@code
 * BgSickLeave} не съществува), а стажът идва от осигурителната история, която WS-5 ще донесе. Тоест
 * списъкът с епизоди е ВИНАГИ празен и всяко {@code prepare()} минава без да пипне нищо. Разликата
 * между „няма болничен“ и „никой не е питан“ е записана в самото обяснение, което резолверът връща
 * — инак спящият енджин щеше да изглежда като работещ и връщащ нула.
 */
public class BgSickLeaveService {

  /** Кодът на параметъра с базата на здравната вноска при отсъствие — ЗЗО чл. 40 ал. 1 т. 5. */
  public static final String PARAM_HEALTH_ABSENCE_BASE = "HEALTH_ABSENCE_BASE";

  /**
   * ПОДНЕВНО ОТЧИТАНЕ — стойност „1“ на {@code bg.work.time.mode.select} (чл. 142 ал. 1 КТ).
   *
   * <p>Единственият режим, в който колоната „Работни часове“ на т. 9 се пълни от {@code норма ×
   * дни}. Другата стойност е „2“ (СИРВ) и при нея указанието иска часовете ПО ГРАФИК — виж {@link
   * #workHoursFor}. Стои наименувана и се чете на едно място, защото селекцията е в домейна и
   * стойностите ѝ са заковани от {@code BgWorkTimeRegimeTest}.
   */
  public static final String WORK_TIME_MODE_DAILY = "1";

  /**
   * Началото на отказа на ПЕРОТО в {@code bgSickDaysRefusal} — по него {@code
   * BgPaySlipService#sickDaysRefusal} различава „перото не е сметнато“ от „дните не са разделени“.
   * Пише се и се чете само чрез тази константа.
   */
  public static final String SICK_PAY_REFUSAL_LEAD =
      "Перо 195 (болничен за сметка на осигурителя) НЕ е сметнато — ";

  // ═══ ГЕНЕРАТОРЪТ ════════════════════════════════════════════════════════

  /**
   * ЦЕЛИЯТ ЕНДЖИН ВЪРХУ ЕДИН ФИШ В ЧЕРНОВА: дните, перото и здравната вноска.
   *
   * <p><b>Върнатият списък е част от договора, не отпадък.</b> Той носи и отказите — а те са
   * единственото, което различава „болничен не се дължи“ от „болничният не можа да се сметне“.
   * Изхвърли ли го повикващият, вторият случай става неразличим от първия и лицето тихо остава без
   * обезщетение, което му се дължи.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНО.</b> Второ и трето извикване не дублират и не натрупват: перото се търси
   * по СТАБИЛНИЯ КОД на типа и намереното се ОБНОВЯВА на място, а двата броя дни се ПРИСВОЯВАТ, не
   * се добавят. Кодът, а не идентификаторът, защото при незапазен фиш перата още нямат
   * идентификатор.
   *
   * <p><b>⚠️ АДИТИВНО.</b> Нищо не се трие и нищо чуждо не се пипа — генераторът докосва САМО реда
   * със своя код. Ръчно въведените пера трябва да преживеят всяко {@code prepare()}.
   *
   * <p><b>⚠️ КОРИГИРАЩ И ЗАЛИЧАВАЩ ФИШ СЕ ПРОПУСКАТ</b>, по същия довод като при начисленията:
   * замразените им входове идват от ОРИГИНАЛА, не от календара и договора наново. Пресметнат наново
   * болничен би пренаписал историята точно там, където ретро-механизмът съществува, за да не я
   * пренаписва — а при болничния разликата е особено остра, защото епизодите може да са били
   * коригирани в НОИ междувременно.
   *
   * @param slip фишът, върху който се пише. Само в статус ЧЕРНОВА
   * @param input входът по § 13.1 — ЕДИНСТВЕНИЯТ източник за епизодите и за стажа. Може да е {@code
   *     null}
   */
  public List<BgSickLeaveOutcome> generate(BgPaySlip slip, BgPayrollInput input) {
    if (!isEligibleDraft(slip)) {
      return Collections.emptyList();
    }
    return applyTo(slip, input);
  }

  /**
   * СЪЩАТА СМЕТКА НА ЕКРАНА — БЕЗ НИТО ЕДИН ЗАПИС ВЪРХУ ЛИСТА.
   *
   * <p>Дните, перата 195 и 516 и намалените отработени дни излизат дума по дума като от {@link
   * #generate} — разликата е само в {@link #applyPeriodLines}, който тук не върви. Пресмятането при
   * onChange е върху фиш, който операторът още не е записал и може да откаже: записани оттук,
   * редовете по т. 9 биха останали без записан фиш зад тях и след „Откажи промените“. Т. 9 се пише
   * само по пътя, който записва и фиша — {@code BgPaySlipService#calculateAndSave}, в една
   * транзакция.
   */
  public List<BgSickLeaveOutcome> preview(BgPaySlip slip, BgPayrollInput input) {
    if (!isEligibleDraft(slip)) {
      return Collections.emptyList();
    }
    return applyTo(slip, input, false);
  }

  /**
   * ПРИСТИГНАЛИЯТ БОЛНИЧЕН ВЪРХУ РЕТРО-КОПИЕ — втората врата към същата сметка.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗХЛАБЕН ГЕЙТ.</b> {@link #isEligibleDraft} и {@link #generate}
   * остават дума по дума каквито са. Тяхното „коригиращият фиш се пропуска“ е ВЯРНО решение и пази
   * историята от преизчисление по днешните правила: епизодите може да са коригирани в НОИ
   * междувременно. Пристигането обаче е точно обратният случай — там преизчислението Е искането, и
   * то е ИЗРИЧНО действие на човек, а не страничен ефект на {@code prepare()}. Затова вход е нов
   * метод с нов пазач, а не {@code ||} в стария.
   *
   * <p><b>⚠️ ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО СЕ ЗАНУЛЯВАТ В СЪЩАТА СТЪПКА, И ТОВА НЕ Е ПОДРЕДБА.</b>
   * Копието идва от {@code retroCopyOf} със замразените числа на оригинала, тоест носи дните на
   * непредставения болничен в {@code bgLeaveDaysOutside}. Оставени, те се вадят ВТОРИ ПЪТ от
   * остатъка на т. 16.1: {@code BgD1RecordBuilder#fillDays} смята {@code отработени − … − outside},
   * а отработените вече са намалени от {@link #applyDays}. Измерено: 19 − 3 = <b>16</b> вместо 19.
   * Двете половини се движат заедно нарочно — разделени, между тях има състояние, в което
   * декларацията е тихо грешна.
   *
   * @param slip ретро-копието — ЧЕРНОВА и коригиращо; всичко друго се отказва
   * @param input входът за СЪЩИЯ период, вече сглобен
   */
  public List<BgSickLeaveOutcome> applyArrival(BgPaySlip slip, BgPayrollInput input) {
    if (slip == null
        || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())
        || !Boolean.TRUE.equals(slip.getBgIsCorrection())) {
      return Collections.emptyList();
    }
    slip.setBgLeaveDaysOutside(BigDecimal.ZERO);
    return applyTo(slip, input);
  }

  /**
   * ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ДОО ВЪРХУ КОРИГИРАЩО КОПИЕ — перо 516 по ЗЗО чл. 40 ал. 1 т. 5, ОТ
   * ЗАМРАЗЕНИТЕ ДНИ.
   *
   * <p><b>⚠️ ТРЕТА ВРАТА, И ТЯ НЕ ЧЕТЕ ЕПИЗОДИ.</b> Разбивът на дните е сметнат ВЕДНЪЖ и стои на
   * фиша ({@code bgSickDaysEmployer}, {@code bgSickDaysDoo}) — от оригинала или от току-що
   * отразения лист. Тук само се смята вноската върху тях: базата {@code HEALTH_ABSENCE_BASE} и
   * ставката на осигурителя са за ПЕРИОДА на копието, по замразения вид осигурен и категория.
   * Перото по чл. 40 ал. 5 (195) и дните НЕ се пипат.
   *
   * @param frozen входът на копието — само от замразеното
   */
  public BgSickLeaveOutcome healthAbsenceOnCorrection(BgPaySlip slip, BgPayrollInput frozen) {
    if (slip == null
        || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())
        || !Boolean.TRUE.equals(slip.getBgIsCorrection())) {
      return null;
    }
    int employerDays = slip.getBgSickDaysEmployer();
    int dooDays = slip.getBgSickDaysDoo();
    BgSickLeaveDays days =
        BgSickLeaveDays.of(
            employerDays,
            dooDays,
            slip.getBgSickDaysNoBenefit(),
            Collections.singletonList(
                "замразените дни на копието: "
                    + employerDays
                    + " за сметка на осигурителя, "
                    + dooDays
                    + " за сметка на ДОО"),
            Collections.<BgSickLeavePeriodSlice>emptyList(),
            0,
            0);
    if (dooDays == 0) {
      // ⚠️ Без дни за ДОО вноска по т. 5 няма — и параметрите не се питат: копие без болничен не
      // бива да зависи от каталога, за да се пресметне.
      return BgSickLeaveResolver.healthAbsenceLine(null, days);
    }
    BgSickLeaveFacts facts = factsOf(slip, frozen);
    BgSickLeaveOutcome outcome = BgSickLeaveResolver.healthAbsenceLine(facts, days);
    if (outcome.isComputed()) {
      writeHealthAbsenceLine(slip, facts, days, outcome);
    }
    return outcome;
  }

  /** Сърцевината, споделена от двете врати. */
  protected List<BgSickLeaveOutcome> applyTo(BgPaySlip slip, BgPayrollInput input) {
    return applyTo(slip, input, true);
  }

  /**
   * @param writeDocuments дали редовете по т. 9 се записват върху листовете — {@code false} само от
   *     {@link #preview}
   */
  protected List<BgSickLeaveOutcome> applyTo(
      BgPaySlip slip, BgPayrollInput input, boolean writeDocuments) {
    Map<Long, BgSickBenefitVerdictRules.Verdict> fresh = new LinkedHashMap<>();
    BgSickLeaveDays days = applyDays(slip, input, fresh);
    if (writeDocuments) {
      applyPeriodLines(slip, input, days);
      // ПРИСЪДИТЕ, СМЕТНАТИ СЕГА, СЕ ЗАМРАЗЯВАТ — само при запис и само при разделени дни.
      // Прегледът (onChange) не пише нищо; отказан разбив оставя всичко, както е било.
      if (days.isResolved()) {
        freezeVerdicts(fresh);
      }
    }
    BgSickLeaveFacts facts = factsOf(slip, input);

    List<BgSickLeaveOutcome> outcomes = new ArrayList<>();
    BgSickLeaveOutcome sickPay =
        BgSickLeaveResolver.sickPayLine(facts, days, onsetBases(slip, input, days));
    outcomes.add(sickPay);
    if (sickPay.isComputed()) {
      upsert(slip, sickPay);
    } else {
      // ⚠️ НЕСМЕТНАТО ПЕРО НЕ ОСТАВА ОТ ПРЕДИШНО ПРЕСМЯТАНЕ. Отказ, който само не пише, оставя
      // заварената сума от предното „Преизчисли“ до отказа и фишът изглежда начислен. Същото при
      // нула дни на осигурителя — перо без дни зад себе си.
      dropSickPay(slip);
    }
    if (days.isResolved() && sickPay.status == BgSickLeaveOutcome.Status.REFUSED) {
      // ⚠️ ОТКАЗАНОТО ПЕРО СПИРА „ПОТВЪРДИ“ — по довода на A8 при чл. 177. Дните на осигурителя
      // вече са извадени от отработените, тоест потвърден без 195 фишът оставя двата дни
      // неплатени. Полето е същото като при отказан разбив: и двете значат „болничният не е сметнат
      // докрай“, а нова колона за второто би казала същото втори път.
      slip.setBgSickDaysRefusal(SICK_PAY_REFUSAL_LEAD + sickPay.detail);
    }
    BgSickLeaveOutcome healthAbsence = BgSickLeaveResolver.healthAbsenceLine(facts, days);
    outcomes.add(healthAbsence);
    if (healthAbsence.isComputed()) {
      // ⚠️ СЕ ЗАПИСВА — с база-дискриминатор, не с договорка. Виж
      // BgContributionLineService#upsertSpecialHealthBase: перото е от тип 516 (фонд HEALTH,
      // осигурител), но носи bgHealthBaseKind = ART40_1_5, тоест не може да се слее с нормалното.
      // Без него сумата липсва на всеки фиш с болничен и разходът за работодателя е занижен.
      writeHealthAbsenceLine(slip, facts, days, healthAbsence);
    }
    return outcomes;
  }

  /**
   * ЗАПИСВА здравната вноска за дните ВНР като перо със СВОЯ база.
   *
   * <p><b>⚠️ БАЗАТА СЕ СМЯТА ОТ РЕЗОЛВЕРА, НЕ ОТ СУМАТА.</b> {@code healthAbsenceLine} връща в
   * {@code quantity} ДНИТЕ (21) — вярната документация за човека, който гледа фиша, и закована от
   * свидетел. Декларацията обаче иска БАЗАТА (502,78), защото т. 18 е „размерът на вноската ВЪРХУ
   * СУМАТА в т. 17“. Обратната сметка {@code сума × 100 / ставка} дава 502,7833… — друго число.
   * Затова базата идва от {@link BgSickLeaveResolver#healthAbsenceProratedBase}, тоест по същия
   * път, по който е сметната сумата.
   *
   * <p>⚠️ Платецът е ВИНАГИ работодателят: ЗЗО чл. 40 ал. 1 т. 5 казва „вноските са за сметка на
   * работодателя или ведомството“. Оттам {@code employerBorne = true} е константа тук, а не избор.
   */
  protected void writeHealthAbsenceLine(
      BgPaySlip slip,
      BgSickLeaveFacts facts,
      BgSickLeaveDays days,
      BgSickLeaveOutcome healthAbsence) {

    BigDecimal base =
        BgSickLeaveResolver.healthAbsenceProratedBase(
            facts.healthAbsenceBase, days.dooDays, facts.workingDaysInPeriod);
    if (base == null) {
      // Резолверът вече е отказал при липсващи вход — дотук се стига само при сметнат резултат.
      return;
    }
    writeSpecialHealthBase(slip, base, facts.healthEmployerRatePct, healthAbsence.amount);
  }

  /**
   * ШЕВЪТ КЪМ ГЕНЕРАТОРА НА 5xx — единственото място тук, което пипа Guice.
   *
   * <p>⚠️ ОТДЕЛЕН ПРОТЕКТЕД МЕТОД, А НЕ {@code Beans.get} НА МЯСТО, и това не е стил. Двайсет и пет
   * заварени свидетеля строят този енджин като подклас и подменят ВСЕКИ негов достъп до базата —
   * речника, календара, параметрите. {@code Beans.get} по средата на {@code generate()} ги поваля
   * наведнъж с „Guice is not initialized“, и то не защото твърдят нещо грешно, а защото новият
   * достъп няма къде да се подмени. Шевът им дава къде.
   */
  protected void writeSpecialHealthBase(
      BgPaySlip slip, BigDecimal base, BigDecimal ratePercent, BigDecimal amount) {
    Beans.get(BgContributionLineService.class)
        .upsertSpecialHealthBase(
            slip, BgD1ContributionAggregator.BASE_ART40_1_5, true, base, ratePercent, amount);
  }

  /**
   * ДНИТЕ — сметнати, записани на фиша, и извадени от отработените. ⚠️ ВИКА СЕ ПРЕДИ НАЧИСЛЕНИЯТА.
   *
   * <p><b>⚠️ ОТРАБОТЕНИТЕ ДНИ СЕ НАМАЛЯВАТ С {@code min}, НЕ СЕ ПРИСВОЯВАТ.</b> Три свойства
   * наведнъж, и всяко от тях е нужно:
   *
   * <ul>
   *   <li><b>идемпотентно</b> — второ {@code prepare()} не намалява втори път;
   *   <li><b>само надолу</b> — енджинът не може да ИЗМИСЛИ отработени дни, тоест не може да вдигне
   *       заплата;
   *   <li><b>уважава по-ниска ръчна стойност</b> — ако ТРЗ вече е приспаднал и друго отсъствие
   *       (платен отпуск, неплатен), болничният не го връща обратно.
   * </ul>
   *
   * <p>Присвояване вместо {@code min} щеше да изтрие точно тази ръчна корекция, и то мълчаливо:
   * фишът пак щеше да изглежда пресметнат, само с повече отработени дни, отколкото месецът има.
   *
   * <p>⚠️ При ОТКАЗАНО разделяне не се пипа НИЩО — нито дните, нито отработените. Записана нула при
   * отказ е най-лошият изход: тя изглежда като изчислена и махва основанието за отказа от екрана.
   */
  protected BgSickLeaveDays applyDays(BgPaySlip slip, BgPayrollInput input) {
    return applyDays(slip, input, new LinkedHashMap<>());
  }

  /**
   * @param fresh тук се събират присъдите, сметнати при това пресмятане — виж {@link #applyTo}.
   */
  protected BgSickLeaveDays applyDays(
      BgPaySlip slip, BgPayrollInput input, Map<Long, BgSickBenefitVerdictRules.Verdict> fresh) {
    BgSickLeaveDays days = daysOf(slip, input, fresh);
    if (!days.isResolved()) {
      // ⚠️ ОТКАЗЪТ СЕ ЗАПИСВА НА ФИША — дните НЕ се пипат, но „Потвърди“ вижда, че не са
      // разделени (BgPaySlipService#sickDaysRefusal). Само в присъдите той не би спрял нищо.
      slip.setBgSickDaysRefusal(days.problem);
      return days;
    }
    slip.setBgSickDaysRefusal(null);
    slip.setBgSickDaysEmployer(days.employerDays);
    slip.setBgSickDaysDoo(days.dooDays);
    slip.setBgSickDaysNoBenefit(days.withoutBenefitDays);

    int workingDays = slip.getBgWorkingDaysInPeriod();
    if (workingDays > 0) {
      int available = Math.max(0, workingDays - days.totalDays());
      if (slip.getBgWorkedDays() > available) {
        slip.setBgWorkedDays(available);
      }
    }
    return days;
  }

  /**
   * ЗАПИСВА РАЗБИВА ПО Т. 9 НА ПРИЛОЖЕНИЕ № 9 върху самите болнични листове.
   *
   * <p><b>Защо върху ЛИСТА, а не върху фиша.</b> Върху фиша разбивът е само два цели агрегата —
   * {@code bgSickDaysEmployer} и {@code bgSickDaysDoo}. Агрегат по (договор × месец) губи
   * необратимо кой лист е дал кои дни: два болнични в един месец се сливат в едно число, а
   * обратният път до документа не съществува. Приложение № 9 обаче се подава ЗА ВСЕКИ БОЛНИЧЕН ЛИСТ
   * поотделно (чл. 8 ал. 1 НПОПДОО), тоест точно разрезът, който агрегатът заличава. ⚠️ Агрегатите
   * ОСТАВАТ и не се пипат: те са входът на здравната вноска и на намалените отработени дни, и
   * изведени наново от редовете щяха да са втора сметка на едно и също.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНО ПРЕЗ ОБХВАТ, НЕ ПРЕЗ ИЗХОДА.</b> Триенето тръгва от ЕПИЗОДИТЕ на входа и
   * от МЕСЕЦИТЕ на периода, а не от редовете, които току-що са пресметнати. Разликата е случаят, в
   * който лист е бил отказан или преместен: той изчезва от изхода, тоест по изхода старите му
   * редове не биха се изтрили никога и месецът щеше да носи дни от два различни прочита.
   *
   * <p><b>⚠️ ПРИ ОТКАЗАНО РАЗДЕЛЯНЕ НЕ СЕ ПИПА НИЩО</b> — същият довод като в {@link #applyDays}:
   * изтрити при отказ, редовете щяха да оставят листа празен и празнотата щеше да изглежда като
   * пресметнат нула.
   *
   * <p>⚠️ И срез БЕЗ лист зад себе си се пропуска мълчаливо — виж {@code
   * BgSickLeavePeriodSlice#isPersistable}. Той не е грешка: така пристигат епизодите в свидетелите.
   */
  protected void applyPeriodLines(BgPaySlip slip, BgPayrollInput input, BgSickLeaveDays days) {
    if (days == null || !days.isResolved()) {
      return;
    }
    // ⚠️ САМО ЛИСТОВЕТЕ, КОИТО ЗАСЯГАТ ПЕРИОДА (A2). Сглобителят тегли и веригата НАЗАД — листове
    // от
    // предходни месеци, които носят само началото на периода. Те нямат дни в този фиш и не бива да
    // се презаписват от него: запис през repository-то на заварен лист е чужда промяна.
    LocalDate periodFrom = slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
    LocalDate periodTo = slip.getPeriod() == null ? null : slip.getPeriod().getToDate();
    Set<Long> scope = new LinkedHashSet<>();
    for (BgSickLeaveEpisode e : episodesOf(input)) {
      boolean touchesPeriod =
          periodFrom == null
              || periodTo == null
              || (e.fromDate != null
                  && e.toDate != null
                  && !e.fromDate.isAfter(periodTo)
                  && !e.toDate.isBefore(periodFrom));
      if (e.sickLeaveId != null && touchesPeriod) {
        scope.add(e.sickLeaveId);
      }
    }
    if (scope.isEmpty()) {
      return;
    }
    List<LocalDate> months = monthsOf(slip);
    if (months.isEmpty()) {
      return;
    }

    Map<Long, List<BgSickLeavePeriodSlice>> byDocument = new LinkedHashMap<>();
    for (BgSickLeavePeriodSlice line : days.periodLines) {
      if (line.isPersistable()) {
        byDocument.computeIfAbsent(line.sickLeaveId, k -> new ArrayList<>()).add(line);
      }
    }

    BigDecimal dailyHours = dailyHoursFor(input);
    for (Long id : scope) {
      writePeriodLines(
          id,
          months,
          byDocument.containsKey(id)
              ? byDocument.get(id)
              : Collections.<BgSickLeavePeriodSlice>emptyList(),
          input,
          dailyHours);
    }
  }

  /**
   * ДНЕВНОТО РАБОТНО ВРЕМЕ за колоната „Дневно работно време по правоотношението“.
   *
   * <p>⚠️ Указанието към т. 9 дава и заместваща стойност — „за лицата, които нямат определено
   * работно време в часове, се попълва «8 часа»“ — и тя НЕ се прилага тук. Осемте часа са правило
   * на ОБРАЗЕЦА при празно поле; записани като факт, стават неразличими от действително уговорени
   * осем и никой повече не може да каже кое от двете е било. Празното остава празно.
   */
  protected BigDecimal dailyHoursFor(BgPayrollInput input) {
    return input == null ? null : input.contractHoursPerDay;
  }

  /**
   * ЧАСОВЕТЕ за един ред — {@code дневна норма × работни дни}, но САМО при подневно отчитане.
   *
   * <p><b>⚠️ ПРИ СИРВ ОСТАВАТ ПРАЗНИ, И ТОВА Е ОТКАЗ, А НЕ ПРОПУСК.</b> Указанието към т. 9 е
   * дословно: часове се попълват „само когато лицето работи при сумирано изчисляване на работното
   * време и/или на смени по часов график … СЪОБРАЗНО ГРАФИКА на лицето за съответния период“.
   * График на смените модулът НЯМА. Тоест точно в режима, за който колоната съществува, верният
   * източник липсва — а {@code норма × дни} там е правдоподобно грешно число, което би се подало
   * пред НОИ като истинско. При подневно отчитане същото произведение е точното число и се пише.
   *
   * <p>⚠️ Празната дневна норма също дава празни часове: генерираният getter връща нула, а нула
   * часа болничен е сума, която образецът показва като законна.
   */
  protected BigDecimal workHoursFor(BgPayrollInput input, BigDecimal dailyHours, int workDays) {
    if (dailyHours == null || dailyHours.signum() <= 0) {
      return null;
    }
    if (input == null || !WORK_TIME_MODE_DAILY.equals(input.workTimeMode)) {
      return null;
    }
    return dailyHours.multiply(BigDecimal.valueOf(workDays));
  }

  /**
   * Месеците, които този фиш пресмята — по един на календарен месец, засегнат от периода.
   *
   * <p>Почти винаги един. Списък, а не единично число, защото периодът е {@code Period} и нищо в
   * схемата не го задължава да е точно месец: сведен до „месеца на началото“, период през граница
   * щеше да трие редовете на един месец и да вписва на два.
   */
  protected List<LocalDate> monthsOf(BgPaySlip slip) {
    List<LocalDate> out = new ArrayList<>();
    if (slip == null || slip.getPeriod() == null) {
      return out;
    }
    LocalDate from = slip.getPeriod().getFromDate();
    LocalDate to = slip.getPeriod().getToDate();
    if (from == null || to == null || to.isBefore(from)) {
      return out;
    }
    for (LocalDate m = from.withDayOfMonth(1); !m.isAfter(to); m = m.plusMonths(1)) {
      out.add(m);
    }
    return out;
  }

  /**
   * ШЕВЪТ КЪМ БАЗАТА: презаписва редовете на ЕДИН лист за пресметнатите месеци.
   *
   * <p>Триене и вписване в един ход, защото ключът (лист, година, месец, платец) няма уникално
   * ограничение — виж {@code BgSickLeavePeriodLine}. Обновяване на място би искало да намери реда
   * по същия ключ, тоест същата заявка, само че два пъти.
   */
  protected void writePeriodLines(
      Long sickLeaveId,
      List<LocalDate> months,
      List<BgSickLeavePeriodSlice> slices,
      BgPayrollInput input,
      BigDecimal dailyHours) {

    BgSickLeave sickLeave = sickLeaveById(sickLeaveId);
    if (sickLeave == null) {
      return;
    }
    if (sickLeave.getBgPeriodLineList() == null) {
      sickLeave.setBgPeriodLineList(new ArrayList<BgSickLeavePeriodLine>());
    }
    // ⚠️ КОЛЕКЦИЯТА СЕ МУТИРА, НЕ СЕ ПОДМЕНЯ. Връзката е с orphanRemoval, а подменен списък прави
    // стария „вече нереферериран“ и Hibernate хвърля, вместо да изтрие сираците. Затова махнатите
    // редове излизат от СЪЩИЯ списък, а новите влизат в него.
    List<BgSickLeavePeriodLine> lines = sickLeave.getBgPeriodLineList();

    Set<String> replaced = new LinkedHashSet<>();
    for (LocalDate month : months) {
      replaced.add(month.getYear() + "-" + month.getMonthValue());
    }
    lines.removeIf(line -> replaced.contains(line.getPeriodYear() + "-" + line.getPeriodMonth()));

    for (BgSickLeavePeriodSlice slice : slices) {
      BgSickLeavePeriodLine line = new BgSickLeavePeriodLine();
      // ⚠️ Годината и месецът се ИЗВЕЖДАТ от котвата (fromDay), не се подават отделно — виж
      // домейна. Въведени независимо, редът можеше да сочи месец, различен от границите си.
      line.setPeriodYear(slice.year());
      line.setPeriodMonth(slice.month());
      line.setFromDay(slice.fromDay);
      line.setToDay(slice.toDay);
      line.setWorkDays(slice.workDays);
      line.setDailyHours(dailyHours);
      line.setWorkHours(workHoursFor(input, dailyHours, slice.workDays));
      line.setPayer(slice.payer);
      // addBgPeriodLineListItem, а не lines.add: то закача и обратната връзка, а sickLeave е
      // required — ред без нея не може да се запише.
      sickLeave.addBgPeriodLineListItem(line);
    }
    saveSickLeave(sickLeave);
  }

  /** Самият запис на листа. Отделен шев, за да върви целият енджин без Postgres в свидетелите. */
  protected BgSickLeave sickLeaveById(Long id) {
    return id == null ? null : Beans.get(BgSickLeaveRepository.class).find(id);
  }

  /** Записва листа с обновените му редове. Отделен шев по същата причина. */
  protected void saveSickLeave(BgSickLeave sickLeave) {
    Beans.get(BgSickLeaveRepository.class).save(sickLeave);
  }

  /**
   * РАЗДЕЛЯНЕТО НА ДНИТЕ за този фиш — епизодите, съпоставени с речника, подадени на резолвера.
   *
   * <p>⚠️ Речникът се чете ВЕДНЪЖ за целия списък и през {@link #absenceType}, тоест един вид,
   * срещнат в три епизода, не пали три заявки. Липсващият ред НЕ се подразбира: епизодът тръгва към
   * резолвера с {@code payer = null} и той отказва с името на речника — виж {@code
   * BgSickLeaveEpisode#payer}.
   */
  protected BgSickLeaveDays daysOf(BgPaySlip slip, BgPayrollInput input) {
    return daysOf(slip, input, new LinkedHashMap<>());
  }

  /**
   * @param fresh присъдите, сметнати сега (не взети замразени), по идентификатор на листа — за
   *     замразяване от повикващия, ако записва
   */
  protected BgSickLeaveDays daysOf(
      BgPaySlip slip, BgPayrollInput input, Map<Long, BgSickBenefitVerdictRules.Verdict> fresh) {
    LocalDate from = slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
    LocalDate to = slip.getPeriod() == null ? null : slip.getPeriod().getToDate();
    String typeRefusal = absenceTypeRefusal(input);
    if (typeRefusal != null) {
      return BgSickLeaveDays.blocked(typeRefusal);
    }
    List<BgSickLeaveEpisode> episodes = episodesOf(input);

    return BgSickLeaveResolver.split(
        episodes,
        episodes.isEmpty() ? Collections.<LocalDate>emptySet() : holidaysFor(episodes, from, to),
        from,
        to,
        input == null ? null : input.insuranceServiceMonths,
        input == null ? null : input.birthDate,
        first -> benefitVerdictOf(first, fresh));
  }

  /**
   * ПРИСЪДАТА ЗА ПЪРВИЯ ЛИСТ НА ПЕРИОДА — замразената, или сметната сега към началото на
   * неработоспособността. ЕДИНСТВЕНИЯТ източник на правото по пътя на фиша.
   *
   * <p>⚠️ НЯМА РЕЗЕРВЕН ПЪТ. Епизод без лист зад себе си или лист, който не се намира, е ОТКАЗ — не
   * „старото правило по подадения стаж“: то е точно мерилото по днешния стаж, което присъдата маха.
   * Сглобителят дава на всеки епизод идентификатора на листа му, тоест в производство отказът е
   * дефект в данните, не нормален случай.
   *
   * <p>⚠️ НЕ ЗАПИСВА. Сметнатата присъда отива в {@code fresh}; замразява я {@link #applyTo} — само
   * когато пресмятането пише.
   */
  protected BgSickBenefitVerdictRules.Verdict benefitVerdictOf(
      BgSickLeaveEpisode first, Map<Long, BgSickBenefitVerdictRules.Verdict> fresh) {
    if (first == null || first.sickLeaveId == null) {
      return BgSickBenefitVerdictRules.Verdict.refused(
          "епизодът няма болничен лист зад себе си — правото на обезщетение се определя от листа"
              + " (замразената присъда) и без него не може да се определи.");
    }
    BgSickLeave sheet = sickLeaveById(first.sickLeaveId);
    if (sheet == null) {
      return BgSickBenefitVerdictRules.Verdict.refused(
          "болничният лист (id "
              + first.sickLeaveId
              + ") не е намерен — правото на обезщетение се чете от него и без него не може да се"
              + " определи.");
    }
    BgSickBenefitVerdictService verdicts = benefitVerdicts();
    BgSickBenefitVerdictRules.Verdict frozen = verdicts.frozenOf(sheet);
    if (frozen != null) {
      return frozen;
    }
    BgSickBenefitVerdictRules.Verdict computed = verdicts.compute(sheet);
    if (!computed.isRefused()) {
      fresh.put(first.sickLeaveId, computed);
    }
    return computed;
  }

  /** Замразява сметнатите сега присъди върху листовете им. */
  protected void freezeVerdicts(Map<Long, BgSickBenefitVerdictRules.Verdict> fresh) {
    for (Map.Entry<Long, BgSickBenefitVerdictRules.Verdict> e : fresh.entrySet()) {
      BgSickLeave sheet = sickLeaveById(e.getKey());
      if (sheet == null || sheet.getBgBenefitVerdict() != null) {
        continue;
      }
      benefitVerdicts().freeze(sheet, e.getValue(), null);
      saveSickLeave(sheet);
    }
  }

  /** Шевът към услугата за присъдата — подменим в свидетелите. */
  protected BgSickBenefitVerdictService benefitVerdicts() {
    return Beans.get(BgSickBenefitVerdictService.class);
  }

  /**
   * ЛИСТ С ВИД, КОЙТО НЕ Е ВИД НА БОЛНИЧЕН ЛИСТ (A4) — отказът за първия такъв, или {@code null}.
   *
   * <p><b>⚠️ ТУК, А НЕ В ЗАЯВКАТА НА СГЛОБИТЕЛЯ.</b> {@code
   * BgPayrollInputBuilder#sickLeaveEpisodesOf} нарочно не чете атрибути на речника („платецът и
   * процентът не се четат тук“) — контрактът носи само кода и той се разрешава срещу речника
   * ЕДИНСТВЕНО тук. Третият признак (допуска ли се видът на лист) се чете на същото място, по
   * същата причина: копие на речника в заявката би се разминало с него. Ефектът е същият — епизодът
   * не стига до сметката.
   *
   * <p>⚠️ ОТКАЗ ЗА ЦЕЛИЯ РАЗБИВ, не прескачане на листа. Прескочен, листът щеше да изчезне тихо от
   * дните и фишът щеше да плати пълна заплата за дни, в които човекът не е работил. Правилото е
   * {@link BgSickLeaveTypeRules}; при запис то отказва още на входа ({@code
   * BgSickLeaveManagementRepository}), тук пази заварените записи и пътищата покрай repository-то.
   */
  protected String absenceTypeRefusal(BgPayrollInput input) {
    if (input == null || input.sickLeaveEpisodes == null) {
      return null;
    }
    for (BgPayrollInput.SickLeaveEpisode e : input.sickLeaveEpisodes) {
      if (e == null) {
        continue;
      }
      String refusal = BgSickLeaveTypeRules.refusal(absenceType(e.absenceTypeCode));
      if (refusal != null) {
        return "болничен лист "
            + (e.documentNumber == null || e.documentNumber.trim().isEmpty()
                ? "без номер"
                : "№ " + e.documentNumber.trim())
            + " ("
            + e.fromDate
            + " – "
            + e.toDate
            + "): "
            + refusal;
      }
    }
    return null;
  }

  /** Епизодите от контракта, всеки с прикачени платец и процент от речника. */
  protected List<BgSickLeaveEpisode> episodesOf(BgPayrollInput input) {
    List<BgSickLeaveEpisode> out = new ArrayList<>();
    if (input == null || input.sickLeaveEpisodes == null) {
      return out;
    }
    for (BgPayrollInput.SickLeaveEpisode e : input.sickLeaveEpisodes) {
      if (e == null) {
        continue;
      }
      BgAbsenceType type = absenceType(e.absenceTypeCode);
      out.add(
          new BgSickLeaveEpisode(
              e.fromDate,
              e.toDate,
              e.continuation,
              e.absenceTypeCode,
              type == null ? null : type.getPayer(),
              // ⚠️ Процентът се взима САМО когато редът е намерен. Взет безусловно, генерираният
              // getter щеше да върне нула за липсващ ред и „вид без обезщетение“ щеше да е
              // неразличимо от „вид, който го няма в речника“.
              type == null ? null : type.getCompensationPct(),
              e.documentNumber,
              // ⚠️ ИДЕНТИФИКАТОРЪТ НА ЛИСТА СЕ ПРЕНАСЯ. Без него applyPeriodLines излиза с празен
              // обхват и т. 9 на Приложение № 9 не се пише от НИКОЙ път. Пренесен, редовете се
              // пишат при всяко пресмятане (calculate, в неговата транзакция) и при отразяване на
              // пристигнал лист (apply, в неговата).
              e.sickLeaveId,
              // ⚠️ РИСКЪТ, ПО КОЙТО СЕ СМЯТА ПРАВОТО — само при намерен ред, по същия довод като
              // процента: липсващият вид вече отказва по платеца.
              type == null ? null : type.getCategory()));
    }
    return out;
  }

  /**
   * Празниците, засягащи ВСИЧКИ епизоди — не само периода на фиша.
   *
   * <p>⚠️ Интервалът е обединението на епизодите И периода, защото първите два работни дни се броят
   * от НАЧАЛОТО НА ЕПИЗОДА, а то може да е в предходния месец. Взети само празниците на периода,
   * епизод, започнал на 30 юни, щеше да брои 30 юни за работен ден дори когато е обявен за празник
   * — и в юли щеше да падне грешният от двата дни.
   */
  protected Set<LocalDate> holidaysFor(
      List<BgSickLeaveEpisode> episodes, LocalDate periodFrom, LocalDate periodTo) {
    LocalDate from = periodFrom;
    LocalDate to = periodTo;
    for (BgSickLeaveEpisode e : episodes) {
      if (e.fromDate != null && (from == null || e.fromDate.isBefore(from))) {
        from = e.fromDate;
      }
      if (e.toDate != null && (to == null || e.toDate.isAfter(to))) {
        to = e.toDate;
      }
    }
    if (from == null || to == null) {
      return Collections.emptySet();
    }
    return holidaysIn(from, to);
  }

  /**
   * Числата за сумите, извадени ВЕДНЪЖ.
   *
   * <p>⚠️ ОСНОВНАТА И НОРМАТА СЕ ЧЕТАТ ОТ ФИША, не от договора наново — фишът вече ги е замразил в
   * {@code prepare()} и ТРЗ има право да ги надпише („числото е нечие решение“). Прочетени пак от
   * договора, ръчната корекция би се игнорирала мълчаливо. Същият шев като при начисленията.
   *
   * <p>⚠️ ПРОЦЕНТЪТ идва от ПЪРВИЯ епизод с платец осигурителя, а не от кой да е: перото плаща
   * точно неговите първи дни. Списък без такъв епизод дава {@code null} — но тогава дните са нула и
   * резолверът не стига до процента.
   */
  protected BgSickLeaveFacts factsOf(BgPaySlip slip, BgPayrollInput input) {
    LocalDate referenceDate = referenceDateOf(slip, input);
    BigDecimal baseSalary = slip.getBgBaseSalary();

    return new BgSickLeaveFacts(
        baseSalary,
        // Решение № 1: базата и уговореното са едно и също число днес. Подава се ДВА пъти нарочно —
        // виж BgSickLeaveResolver#DAILY_BASE_IS_CONTRACTED_SALARY.
        baseSalary,
        slip.getBgWorkingDaysInPeriod(),
        employerPaidPct(input),
        healthAbsenceBase(referenceDate),
        healthEmployerRatePct(input, referenceDate));
  }

  /**
   * БАЗИТЕ ЗА ПЕРО 195 — по една за всеки МЕСЕЦ НА НАСТЪПВАНЕ, чл. 40 ал. 5 КСО: ПОСТОЯННОТО
   * МЕСЕЧНО ВЪЗНАГРАЖДЕНИЕ, делено на работните дни НА ОНЗИ МЕСЕЦ.
   *
   * <p><b>⚠️ МЕСЕЦЪТ НА НАСТЪПВАНЕ, НЕ МЕСЕЦЪТ НА ФИША (A5).</b> Алинеята плаща по „среднодневното
   * брутно възнаграждение за месеца, в който е настъпила неработоспособността, но не по-малко от
   * среднодневното уговорено“. Взети от ТОЗИ фиш, лист от сряда 30.09 би платил 01.10 по
   * октомврийската база (октомври 2026 — 22 работни дни) вместо по септемврийската (20). Затова:
   *
   * <ul>
   *   <li>настъпване в месеца на фиша — числата на самия фиш;
   *   <li>настъпване в предходен месец — числата на ПОТВЪРДЕНИЯ фиш за онзи месец ({@link
   *       #onsetSlip}): постоянното му месечно възнаграждение и неговите работни дни;
   *   <li>няма потвърден фиш за онзи месец — ОТКАЗ, който назовава месеца. Базата на текущия фиш на
   *       мястото на липсващата е точно дефектът.
   * </ul>
   *
   * <p><b>⚠️ ОСНОВАТА НЕ Е САМО ОСНОВНАТА ЗАПЛАТА.</b> Основата за дните за сметка на работодателя
   * е „основната заплата + всички допълнителни плащания с постоянен характер за месеца (стаж,
   * професионален опит)“. При 2000,00 и клас 5 % (100,00), септември 2026 (20 работни дни) и 2 дни
   * болничен перото е 2100,00 / 20 × 70 % × 2 = 147,00, а не 140,00.
   *
   * <p>Сборът идва от {@link BgEarningsService#monthlyPermanentPay} — същото {@code M}, на което
   * стъпва и надбавката по чл. 177, НЕПРОРАТИРАНО: болничните дни свалят отработените, тоест взет
   * от показаните пера, класът щеше да падне точно в месеца на болничния.
   *
   * <p>⚠️ Четецът се пита САМО при дни за сметка на работодателя: без тях перо 195 няма, а
   * генераторите на начисленията искат договор, параметри и Guice, които фиш без болничен не бива
   * да изисква. Уговореното (подът по ал. 5) е същото число — решение № 1 остава в сила за пода.
   */
  protected Map<YearMonth, BgSickLeaveOnsetBase> onsetBases(
      BgPaySlip slip, BgPayrollInput input, BgSickLeaveDays days) {
    Map<YearMonth, BgSickLeaveOnsetBase> out = new LinkedHashMap<>();
    if (days == null || !days.isResolved() || days.employerDays == 0) {
      return out;
    }
    for (YearMonth month : days.employerDaysByOnset.keySet()) {
      out.put(
          month, isOwnMonth(slip, month) ? baseOf(month, slip, input) : earlierBase(slip, month));
    }
    return out;
  }

  /** Месецът е в периода на фиша — базата е на самия фиш. */
  protected boolean isOwnMonth(BgPaySlip slip, YearMonth month) {
    LocalDate from = slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
    LocalDate to = slip.getPeriod() == null ? null : slip.getPeriod().getToDate();
    return from != null
        && to != null
        && !month.isBefore(YearMonth.from(from))
        && !month.isAfter(YearMonth.from(to));
  }

  /**
   * Базата от ЕДИН фиш — числител и знаменател от него. Уговореното (подът по ал. 5) е същото число
   * — решение № 1 остава в сила за пода.
   */
  protected BgSickLeaveOnsetBase baseOf(YearMonth month, BgPaySlip slip, BgPayrollInput input) {
    // ⚠️ signum, не null: генерираният getter връща нула, никога null (BgDefaultedGetterTest).
    // Без основна заплата базата остава нула и резолверът отказва с името на липсата.
    BigDecimal monthly = slip.getBgBaseSalary();
    if (monthly.signum() > 0) {
      BigDecimal permanent = monthlyPermanentPay(slip, input);
      if (permanent != null) {
        monthly = permanent;
      }
    }
    return BgSickLeaveOnsetBase.of(month, monthly, monthly, slip.getBgWorkingDaysInPeriod());
  }

  /**
   * Базата за ПРЕДХОДЕН месец на настъпване — от потвърдения фиш за него, или шумен отказ.
   *
   * <p>⚠️ ВХОДЪТ Е НА ОНЗИ ФИШ ({@link #inputOf}), не на текущия: класът прослужено време и датата
   * на параметрите са за неговия месец. С текущия вход сборът щеше да е по днешните данни.
   */
  protected BgSickLeaveOnsetBase earlierBase(BgPaySlip slip, YearMonth month) {
    BgPaySlip onset = onsetSlip(slip, month);
    if (onset == null) {
      BgPaySlip draft = unconfirmedSlip(slip, month);
      return BgSickLeaveOnsetBase.refused(
          month,
          "неработоспособността е настъпила през "
              + BgSickLeaveResolver.monthLabel(month)
              + ", а базата по чл. 40 ал. 5 КСО е среднодневното брутно и уговореното"
              + " възнаграждение ЗА МЕСЕЦА НА НАСТЪПВАНЕ. Липсва ПОТВЪРДЕНИЯТ фиш за "
              + BgSickLeaveResolver.monthLabel(month)
              + " по този трудов договор"
              + (draft == null
                  ? ""
                  : " (има фиш "
                      + draft.getReference()
                      + " в статус "
                      + draft.getStatusSelect()
                      + ")")
              + ". Потвърдете фиша за "
              + BgSickLeaveResolver.monthLabel(month)
              + " и натиснете „Преизчисли“ тук. Базата на ТОЗИ фиш НЕ се взима на негово място:"
              + " тя е за друг месец.");
    }
    if (onset.getBgBaseSalary().signum() <= 0) {
      return BgSickLeaveOnsetBase.refused(
          month,
          "потвърденият фиш "
              + onset.getReference()
              + " за "
              + BgSickLeaveResolver.monthLabel(month)
              + " (месеца на настъпване) няма основна заплата — базата по чл. 40 ал. 5 КСО е от"
              + " него и без нея не съществува.");
    }
    return baseOf(month, onset, inputOf(onset));
  }

  /**
   * ДЕЙСТВАЩИЯТ фиш по СЪЩИЯ договор за месеца на настъпване, или {@code null} — през {@link
   * BgEffectivePaySlips#effectiveForContract}, същото правило като за Д1 и Д6.
   *
   * <p>⚠️ КОРИГИРАН МЕСЕЦ НА НАСТЪПВАНЕ → БАЗАТА Е ОТ КОРЕКЦИЯТА, не от „потвърдения РЕДОВЕН“
   * оригинал, чиито числа корекцията е заменила. Коригиращото копие е за СЪЩИЯ месец и носи пълните
   * верни числа; делтата за друг месец е перото 620 в ТЕКУЩИЯ фиш и тук не участва. Заличен месец
   * няма действащ фиш — отказът по-горе го назовава като липсващ.
   */
  protected BgPaySlip onsetSlip(BgPaySlip slip, YearMonth month) {
    return BgEffectivePaySlips.effectiveForContract(slip.getEmploymentContract(), month.atDay(1));
  }

  /** Редовен фиш за месеца в друг статус (чернова) — само за да го назове отказът. */
  protected BgPaySlip unconfirmedSlip(BgPaySlip slip, YearMonth month) {
    return slipFor(slip, month, Collections.singletonList(BgPaySlipResolver.DRAFT));
  }

  private BgPaySlip slipFor(BgPaySlip slip, YearMonth month, List<String> statuses) {
    if (slip.getEmploymentContract() == null) {
      return null;
    }
    List<BgPaySlip> found =
        JPA.em()
            .createQuery(
                "SELECT s FROM BgPaySlip s WHERE s.employmentContract = :contract"
                    + " AND s.period.fromDate <= :day AND s.period.toDate >= :day"
                    + " AND s.statusSelect IN (:statuses)"
                    + " AND (s.bgIsCorrection IS NULL OR s.bgIsCorrection = FALSE)"
                    + " AND (s.bgIsDeletion IS NULL OR s.bgIsDeletion = FALSE)"
                    + " ORDER BY s.id DESC",
                BgPaySlip.class)
            .setParameter("contract", slip.getEmploymentContract())
            .setParameter("day", month.atDay(1))
            .setParameter("statuses", statuses)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /** Входът на ДРУГ фиш — същата врата, през която смята {@code prepare()}. */
  protected BgPayrollInput inputOf(BgPaySlip slip) {
    return Beans.get(BgPaySlipService.class).payrollInputFor(slip);
  }

  /**
   * Маха заварено перо 195, когато това пресмятане не го смята — по образеца на {@code
   * BgArt177LeaveService#drop}.
   */
  protected void dropSickPay(BgPaySlip slip) {
    BgPaySlipLine line = lineWithCode(slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    if (line != null) {
      slip.getBgPaySlipLineList().remove(line);
    }
  }

  /** Шевът към {@link BgEarningsService#monthlyPermanentPay} — отделен, за да се подменя. */
  protected BigDecimal monthlyPermanentPay(BgPaySlip slip, BgPayrollInput input) {
    return Beans.get(BgEarningsService.class).monthlyPermanentPay(slip, input);
  }

  /** Процентът на обезщетението от ПЪРВИЯ епизод, платим от осигурителя, или {@code null}. */
  protected BigDecimal employerPaidPct(BgPayrollInput input) {
    for (BgSickLeaveEpisode e : episodesOf(input)) {
      if (e.employerPaysOpeningDays()) {
        return e.compensationPct;
      }
    }
    return null;
  }

  /**
   * ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ВНР — сметната и върната, но ⚠️ НЕ записана като перо.
   *
   * <p><b>Защо не перо, и защо това е граница, а не пропуск.</b> Групата 5xx на речника е ОГЛЕДАЛНА
   * на {@code bg_contribution_fund}: по един ред за всеки фонд и за всеки платец, точно 20, и
   * {@code BgPaySlipLineTypeCatalogueTest} пази съответствието едно към едно. Двайсет и първи ред
   * „здравна вноска за дните ВНР“ би скъсал точно този инвариант, а вписването ѝ в {@code
   * pl_vn_health_osig} (516) би слепило две вноски върху РАЗЛИЧНИ бази в едно число — 5,27 върху
   * 109,87 и 24,13 върху 502,78 — и нито едната не би могла да се провери от фиша.
   *
   * <p>⚠️ {@code BgContributionLineService} записва групата 5xx на всеки фиш, а генераторът намира
   * типа перо по ДВОЙКАТА фонд + знак, тоест двайсет и първи ред би му бил недостижим, а вписване в
   * {@code pl_vn_health_osig} (516) би слепило 5,27 върху 109,87 и 24,13 върху 502,78 в едно число
   * — при това в перо, чиито количество и ставка вече ДОКУМЕНТИРАТ първата база. Затова числото пак
   * не се записва тук.
   *
   * <p>⚠️ КАКВО ИСКА ЗАТВАРЯНЕТО: собствен ред в речника с фондовете (или изрично второ перо за
   * фонд ЗОВ с обявена различна база), а не промяна в този метод. Дотогава числото се ВРЪЩА и
   * {@code BgSickLeaveGoldenTest} пази колко е то.
   */
  public BgSickLeaveOutcome healthAbsence(BgPaySlip slip, BgPayrollInput input) {
    if (slip == null) {
      return BgSickLeaveResolver.healthAbsenceLine(null, null);
    }
    return BgSickLeaveResolver.healthAbsenceLine(factsOf(slip, input), daysOf(slip, input));
  }

  /** Чернова, която НЕ е коригираща и НЕ е заличаваща — единственото, върху което се пише. */
  protected boolean isEligibleDraft(BgPaySlip slip) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return false;
    }
    return !Boolean.TRUE.equals(slip.getBgIsCorrection())
        && !Boolean.TRUE.equals(slip.getBgIsDeletion());
  }

  /**
   * Записва сметнатото перо — ново или обновено.
   *
   * <p>Количеството и ставката се пишат ЗАЕДНО със сумата: те са документацията ѝ и без тях перото
   * е число без произход. Обновяването ги пренаписва и двете, инак на обновен ред щяха да останат
   * числата от предишното смятане и да обясняват друга сума. Същият идиом като {@code
   * BgEarningsService#upsert}.
   */
  protected void upsert(BgPaySlip slip, BgSickLeaveOutcome outcome) {
    BgPaySlipLine line = lineWithCode(slip, outcome.lineCode);
    if (line == null) {
      BgPaySlipLineType type = lineType(outcome.lineCode);
      if (type == null) {
        // Речникът няма този ред — App Install не е пускан след добавянето му. Мълчаливо
        // прескачане тук би оставило болничния несметнат и нито един отказ.
        throw new IllegalStateException(
            "типът перо „"
                + outcome.lineCode
                + "“ липсва в речника (bg_pay_slip_line_type.csv) — болничният по чл. 40 ал. 5 КСО не"
                + " може да се начисли. Пусни App Install.");
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

  /** Датата за версията на параметрите: от входа, а при липса — от периода на фиша. */
  protected LocalDate referenceDateOf(BgPaySlip slip, BgPayrollInput input) {
    if (input != null && input.parameterReferenceDate != null) {
      return input.parameterReferenceDate;
    }
    return slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
  }

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА (подменят се в тестовете) ════════════════════════

  /**
   * {@code HEALTH_ABSENCE_BASE} за датата, или {@code null}, ако редът липсва.
   *
   * <p>⚠️ Четенето е по СЪЩИЯ механизъм като на всеки друг параметър в модула — филтър по код и по
   * валидност на датата, същият, който {@code BgEarningsService#statutoryMinPct} и {@code
   * BgInsurableIncomeService#parameterFor} ползват. Втори механизъм за едно и също нещо е свободен
   * да се размине с първия по датите, а 2026 г. има ДВЕ осигурителни половини: 550,66 до 31.07 и
   * 620,20 от 01.08.
   */
  protected BigDecimal healthAbsenceBase(LocalDate date) {
    if (date == null) {
      return null;
    }
    BgPayrollParameter row =
        Beans.get(BgPayrollParameterRepository.class)
            .all()
            .filter(
                "self.code = :c AND self.validFrom <= :d "
                    + "AND (self.validTo IS NULL OR self.validTo >= :d)")
            .bind("c", PARAM_HEALTH_ABSENCE_BASE)
            .bind("d", date)
            .fetchOne();
    return row == null ? null : row.getParamValue();
  }

  /**
   * Ставката на фонд ЗОВ за платеца РАБОТОДАТЕЛ, или {@code null}.
   *
   * <p>⚠️ Минава през {@code BgInsuranceCalculationService}, а не през своя заявка: там живеят
   * ВСИЧКИ прочити на ставки и оттам ставката за болничния е същият ред, по който после ще се
   * сметнат и останалите вноски. Виж {@code BgInsuranceCalculationService#findHealthEmployerRate}.
   */
  protected BigDecimal healthEmployerRatePct(BgPayrollInput input, LocalDate date) {
    if (input == null) {
      return null;
    }
    return Beans.get(BgInsuranceCalculationService.class)
        .findHealthEmployerRate(input.insuredTypeCode, input.laborCategory, date);
  }

  /**
   * Официалните празници в интервала — през {@code BgWorkingDayService}, не през своя заявка.
   *
   * <p>„Работен ден“ трябва да значи ЕДНО И СЪЩО нещо на целия фиш: работните дни в периода, дните
   * на обезщетенията и първите два дни на болничния се броят по един и същи календар. Втора заявка
   * към {@code EventsPlanningLine} е свободна да се размине с тази.
   */
  protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
    return new LinkedHashSet<>(Beans.get(BgWorkingDayService.class).officialHolidaysIn(from, to));
  }

  /** Редът на речника с отсъствията по стабилния си код, или {@code null}. */
  protected BgAbsenceType absenceType(String code) {
    if (code == null || code.trim().isEmpty()) {
      return null;
    }
    return Beans.get(BgAbsenceTypeRepository.class)
        .all()
        .filter("self.code = :c")
        .bind("c", code.trim())
        .fetchOne();
  }

  /** Редът на речника с перата по стабилния си код, или {@code null}. */
  protected BgPaySlipLineType lineType(String code) {
    return Beans.get(BgPaySlipLineTypeRepository.class)
        .all()
        .filter("self.code = :c")
        .bind("c", code)
        .fetchOne();
  }

  /**
   * Ново перо от речника — ПРЕЗ {@code BgPaySlipService#lineFrom}, за да остане ЕДНО мястото,
   * където речникът се чете и замразява по копиране. Второ копие на това правило е свободно да
   * пропусне флаг — а тук флаговете СА правилото: осигурителен доход да, данък не.
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
}
