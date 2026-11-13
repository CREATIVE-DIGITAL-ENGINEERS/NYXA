package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.persistence.TypedQuery;

/**
 * ВЪЗНАГРАЖДЕНИЕТО ЗА ПЛАТЕН ОТПУСК СТАВА ПЕРО — КТ чл. 177, НСОРЗ чл. 17, 18 и 19.
 *
 * <p><b>ПЛАТЕНИЯТ ОТПУСК НЕ СЕ ПЛАЩА „КАТО ОТРАБОТЕН“.</b> {@code bgWorkedDays} тръгва като
 * работните дни на периода и се намалява САМО от болничния — без това перо дните платен отпуск
 * остават вътре и основната заплата се плаща за цял месец. Това е ВЯРНО при фиксирана заплата и
 * КРИВО в два случая, всеки от които нормата урежда изрично:
 *
 * <ul>
 *   <li><b>Занижава</b>, когато предходният месец е носил възнаграждение над основната с постоянен
 *       характер. Чл. 177 ал. 1 иска среднодневното от НАЧИСЛЕНОТО, не от основната.
 *   <li><b>Разминава се</b>, когато месецът на ползване има друг брой работни дни от базовия —
 *       коефициентът по НСОРЗ чл. 18 ал. 2 не се прилага.
 * </ul>
 *
 * <p><b>⚠️ ПЕРОТО НЕ ДОБАВЯ ПАРИ, А ГИ ПРЕМЕСТВА, И ТОВА Е ЦЕЛИЯТ МУ УСТРОЙСТВЕН ПРИНЦИП.</b>
 * Сумата му е <b>карв + надбавка</b>:
 *
 * <ul>
 *   <li><b>Карвът</b> е онова, което перата с постоянен характер за ТОЗИ месец са дали за дните
 *       отпуск — свалено от тях от енджина за начисления и върнато тук, до стотинката.
 *   <li><b>Надбавката</b> е разликата между дължимото по чл. 177 и същото това вече платено.
 * </ul>
 *
 * <p>Оттам следва ИНВАРИАНТЪТ: при непроменен състав на възнаграждението и напълно отработен базов
 * месец надбавката е ТОЧНО нула и фишът излиза бит по бит какъвто беше. Мръдне ли базовият месец,
 * мърда само надбавката. Без карва отпускът щеше да се плати ДВА ПЪТИ — веднъж през основната и
 * веднъж през това перо.
 *
 * <p><b>⚠️ ЗАЩО НЕ ОТРИЦАТЕЛЕН РЕД ЗА КАРВА.</b> {@code BgPaySlipLine.bgAmount} е ВИНАГИ
 * положително — знакът е на перото. А ред със знак DEBIT в група 1xx намалява САМО нетото: {@code
 * grossTotal}, {@code insuranceIncomeBase} и {@code taxableBase} събират единствено CREDIT. Тоест
 * лицето щеше да се осигурява и облага върху пари, които не е получило.
 *
 * <p><b>⚠️ КОИ ОТПУСКИ — ОТ РЕЧНИКА, НЕ ОТ {@code isPaid}.</b> Признакът е {@code
 * BgAbsenceType.bgPaidUnderArt177}. Платената кофа събира ОСЕМ вида по ТРИ режима на заплащане;
 * изведено от „платен“, среднодневното щеше да се приложи и върху чл. 161 (по КТД) и чл. 169–171
 * (кодексът мълчи).
 *
 * <p><b>⚠️ ЧЛ. 157 НОСИ ТРИ РЕЖИМА — И ТЕ СА ТРИ РЕДА В РЕЧНИКА.</b> Само средният ({@code
 * paid_157_art177} — ал. 1 т. 5а и т. 6 и ал. 2) влиза насам. Долният ({@code paid_157_special} —
 * ал. 1 т. 4, 5 и 7) изобщо не е платен от работодателя и минава по карва за неплатеното; горният
 * ({@code paid_157}) остава по КТД и извън чл. 177, докато инсталацията не отметне флага.
 *
 * <p><b>⚠️ КАКВО НЕ ПРАВИ: не брои дни и не строи календар.</b> Дните идват от {@code
 * BgPayrollInput.art177LeaveDays} — вече преброени, разбити по период, превърнати от часове и с
 * обявени откази. Работните дни идват от {@code BgWorkingDayService}. Второ броене тук би било
 * втори отговор на въпрос, който вече има един.
 */
public class BgArt177LeaveService {

  /** Стабилният код на перото по чл. 177 (PLT-165). */
  public static final String LINE_ART177_LEAVE = "pl_otpusk_177";

  /** Минималната работна заплата — числителят на пода по НСОРЗ чл. 19 ал. 2. */
  protected static final String PARAM_MIN_WAGE = "MIN_WAGE";

  /**
   * Докъде назад се търси базов месец. КТ чл. 177 ал. 1 не слага граница, но заявката трябва да има
   * такава: без нея един договор с десетилетна история чете всичките си фишове, за да намери онзи,
   * който почти винаги е ПЪРВИЯТ. Стигне ли се дъното без квалифициран месец, изходът е резервният
   * ред по ал. 2 — същият, който нормата дава при „няма такъв месец“.
   */
  protected static final int MAX_BASE_MONTHS_BACK = 36;

  /**
   * Смята и ЗАПИСВА перото по чл. 177, или връща обявена причина да няма перо.
   *
   * <p><b>⚠️ ВИКА СЕ СЛЕД {@code generateEarnings} И ПРЕДИ ОБЕЗЩЕТЕНИЯТА, И РЕДЪТ НЕ Е
   * СВОБОДЕН.</b> Карвът се чете като разлика между двата изхода на енджина за начисления — тоест
   * енджинът трябва вече да е минал със същия вход. А обезщетенията и вноските отдолу трябва да
   * видят това перо като вече записано: то е осигурителен доход и облагаемо.
   *
   * @param slip фишът, върху който се пише. Само чернова, която не е ретро
   * @param input входът по § 13.1 — носи дните по чл. 177. Може да е {@code null}
   */
  public BgArt177Outcome generate(BgPaySlip slip, BgPayrollInput input) {
    BgArt177Outcome outcome = compute(slip, input);
    if (!isEligibleDraft(slip)) {
      return outcome;
    }
    // ⚠️ ОТКАЗЪТ СЕ ЗАПИСВА НА ФИША И ПЕРОТО СЕ МАХА — ВЪВ ВСИЧКИ КЛОНОВЕ. Отказ, който само седи
    // в bgGeneratorGaps, не стига до „Потвърди“, а дните на отпуска вече са изрязани от
    // постоянните пера — тоест фишът би се потвърдил с неплатен отпуск или със заварено перо 165 от
    // предишно пресмятане. Тук е едното място, през което минава всеки изход — нов отказ отдолу не
    // може да го заобиколи.
    if (outcome.status == BgArt177Outcome.Status.REFUSED) {
      drop(slip);
      slip.setBgArt177Refusal(outcome.detail);
    } else {
      slip.setBgArt177Refusal(null);
    }
    return outcome;
  }

  /** Сметката по чл. 177 — без да пише отказа; виж {@link #generate}. */
  protected BgArt177Outcome compute(BgPaySlip slip, BgPayrollInput input) {
    if (!isEligibleDraft(slip)) {
      return BgArt177Outcome.notApplicable(
          "фишът не е чернова или е ретро-копие: коригиращият и заличаващият фиш носят ЗАМРАЗЕНИЯ"
              + " вход на оригинала и не се смятат наново.");
    }
    BigDecimal days = input == null ? null : input.art177LeaveDays;
    if (days == null || days.signum() <= 0) {
      // Нула дни отпуск по чл. 177 е НОРМАЛНОТО състояние на повечето фишове, не отказ.
      drop(slip);
      return BgArt177Outcome.notApplicable(
          "през периода няма ползван отпуск, заплащан по реда на чл. 177 КТ.");
    }

    Integer usageNorm =
        slip.getBgWorkingDaysInPeriod() == 0 ? null : slip.getBgWorkingDaysInPeriod();
    if (usageNorm == null) {
      return BgArt177Outcome.refused(
          "работните дни в периода не са определени, а месецът носи "
              + days
              + " дни отпуск по чл. 177. Те са знаменателят на коефициента по НСОРЗ чл. 18 ал. 2 и"
              + " на дневната ставка на самия фиш — без тях нито карвът, нито надбавката са"
              + " определени. Прегледай производствения календар за годината.");
    }

    BgEarningsService.Art177Inputs parts = earningsService().art177Inputs(slip, input);
    if (parts.refusal != null) {
      return BgArt177Outcome.refused(
          "дните отпуск по чл. 177 не могат да се изведат от перата с постоянен характер: "
              + parts.refusal);
    }

    BaseMonth base = findBaseMonth(slip);
    if (base != null && base.refusal != null) {
      return BgArt177Outcome.refused(base.refusal);
    }

    BgArt177Resolver.DailyRate rate =
        base == null
            ? contractualRate(slip, parts)
            : BgArt177Resolver.accruedDailyRate(
                base.accruedArt17Base, base.workedDays, base.workingDays, usageNorm);
    if (rate == null) {
      return BgArt177Outcome.refused(
          base == null
              ? "няма месец с поне 10 отработени дни при този работодател, а уговореното по договор"
                  + " не може да се установи (липсва основна заплата) или работните дни на"
                  + " календарната година не са заредени. Чл. 177 ал. 2 иска именно уговорените"
                  + " основно и допълнителни възнаграждения с постоянен характер, разделени на"
                  + " средномесечния брой работни дни за годината."
              : "базовият месец е намерен, но среднодневното не може да се изведе от него:"
                  + " начисленото по чл. 17 е нула или отработените му дни не са определени.");
    }

    BgArt177Resolver.DailyRate floored =
        BgArt177Resolver.withStatutoryFloor(rate, statutoryFloor(slip, input, usageNorm));

    BigDecimal uplift =
        BgArt177Resolver.upliftAmount(floored, parts.currentMonthPermanentPay, usageNorm, days);
    BigDecimal amount = parts.carve.add(uplift);

    // ⚠️ ОТРИЦАТЕЛНА СУМА НЕ СЕ ЗАПИСВА, А СЕ ОБЯВЯВА.
    //
    // По-богат текущ месец (ръчно постоянно перо, каквото базовият месец не е носил) дава
    // ОТРИЦАТЕЛНА надбавка, но и ръчното перо се карвира — затова сборът не пада под нула по тази
    // причина: карвът сваля точно M/норма × дни, а надбавката добавя (ставка − M/норма) × дни,
    // тоест сумата е СТАВКА × ДНИ, а тя е неотрицателна по устройство. Богат текущ месец дава
    // пълното право по чл. 177 (по-ниско от текущата ставка — и това е ВЯРНО: чл. 177 плаща по
    // БАЗОВИЯ месец, а не по текущия).
    //
    // ⚠️ И ВЪПРЕКИ ТОВА ПРОВЕРКАТА ОСТАВА, защото значи нещо ДРУГО: сборът е отрицателен само
    // ако карвът и надбавката са смятани върху РАЗЛИЧНИ числа — тоест ако инвариантът „M е
    // непроратираният сбор, карвът е онова, което редовете наистина загубиха“ е счупен някъде
    // нагоре. Махната, тя щеше да остави такова счупване да излезе като отрицателно перо на фиш.
    // Тих предпазител, който не бива да се задейства; задейства ли се, поправката е в кода, не в
    // данните.
    if (amount.signum() < 0) {
      drop(slip);
      return BgArt177Outcome.refused(
          "сборът карв + надбавка по чл. 177 излиза ОТРИЦАТЕЛЕН ("
              + amount
              + " €) при право "
              + BgArt177Resolver.entitlementAmount(floored, days)
              + " € за "
              + days
              + " дни. Това НЕ е състояние на данните: карвът и надбавката се смятат върху едно и"
              + " също M, тоест сборът им е дневната ставка по чл. 177 × дните и не може да е"
              + " отрицателен. Отрицателен сбор значи, че двете са смятани върху различни числа —"
              + " дефект в BgEarningsService#art177Inputs или в карва на перата, не в този фиш."
              + " Перото НЕ се записва: нула тук би платила отпуска по грешна ставка мълчаливо.");
    }

    upsert(slip, amount, days, floored, base);
    return BgArt177Outcome.computed(
        amount,
        days,
        parts.carve,
        uplift,
        BgArt177Resolver.entitlementAmount(floored, days),
        floored.basis);
  }

  // ═══ БАЗОВИЯТ МЕСЕЦ ═══════════════════════════════════════════════════════

  /** Какво носи базовият месец, или назована причина да не може да се прочете. */
  protected static final class BaseMonth {

    final BigDecimal accruedArt17Base;

    /**
     * ЧАСТТА ОТ {@link #accruedArt17Base} ПО Т. 5–7 — престой и подобните му, за ОБЯВАТА.
     *
     * <p>⚠️ Подмножество, не съседна кофа: тези пари СА вътре в базата и се броят там. Извадени
     * встрани, сборът щеше да падне. Полето съществува само за да може бележката да каже ЗАЩО
     * среднодневното е такова.
     */
    final BigDecimal nonPermanentPart;

    final BigDecimal workedDays;
    final Integer workingDays;
    final String reference;
    final String refusal;

    BaseMonth(
        BigDecimal accruedArt17Base,
        BigDecimal nonPermanentPart,
        BigDecimal workedDays,
        Integer workingDays,
        String reference,
        String refusal) {
      this.accruedArt17Base = accruedArt17Base;
      this.nonPermanentPart = nonPermanentPart;
      this.workedDays = workedDays;
      this.workingDays = workingDays;
      this.reference = reference;
      this.refusal = refusal;
    }
  }

  /**
   * ПОСЛЕДНИЯТ КАЛЕНДАРЕН МЕСЕЦ С ПОНЕ 10 ОТРАБОТЕНИ ДНИ, или {@code null} за резервния ред.
   *
   * <p><b>⚠️ „ОТРАБОТЕНИ“ НЕ Е {@code bgWorkedDays}.</b> Замразеното поле е работните дни на
   * периода МИНУС болничните — платеният и неплатеният отпуск остават вътре, защото те са време в
   * осигуряване и пълнят т. 16.1 на Д1. За чл. 18 ал. 1 обаче отпускът НЕ е отработен ден, тоест
   * отсъствията се свалят тук. Симетрично: чл. 17 ал. 1 не изброява възнаграждението за платен
   * отпуск сред седемте съставки, тоест то не е и в числителя.
   *
   * <p>⚠️ Отсъствията на базовия месец се ПРЕИЗЧИСЛЯВАТ със същия {@code leaveDaysOf}, а не се
   * четат от фиша — фишът няма къде да ги пази, а заявките за отпуск СА записът. Втора машинария за
   * броене на дни е свободна да се размине с онази, която е платила онзи месец.
   */
  protected BaseMonth findBaseMonth(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    LocalDate before = slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
    if (contract == null || before == null) {
      return null;
    }
    for (BgPaySlip candidate : earlierSlips(contract, before)) {
      Period period = candidate.getPeriod();
      if (period == null || period.getFromDate() == null || period.getToDate() == null) {
        continue;
      }
      // ⚠️ ПО ДОГОВОРА НА КАНДИДАТА, не на текущия фиш: базовият месец може да е по ПРЕДИШЕН
      // договор при същия работодател (чл. 177 ал. 1), а работният му ден — друг.
      EmploymentContract ofCandidate =
          candidate.getEmploymentContract() == null ? contract : candidate.getEmploymentContract();
      BigDecimal absent = absenceDaysOf(ofCandidate, period);
      if (absent == null) {
        return new BaseMonth(
            null,
            null,
            null,
            null,
            null,
            "отсъствията на базовия месец ("
                + period.getFromDate()
                + " – "
                + period.getToDate()
                + ") не могат да се преброят честно, а те са знаменателят на среднодневното по"
                + " НСОРЗ чл. 18 ал. 1: отработените дни са зачетените МИНУС отпуските. Провери"
                + " заявките за отпуск за онзи месец — заявка в часове без договорен работен ден или"
                + " заявка, която не покрива нито един работен ден, спира броенето.");
      }
      BigDecimal worked = BigDecimal.valueOf(candidate.getBgWorkedDays()).subtract(absent);
      if (!BgArt177Resolver.qualifiesAsBaseMonth(worked)) {
        continue;
      }
      Integer baseNorm = workingDaysOf(period.getFromDate(), period.getToDate());
      if (baseNorm == null) {
        return new BaseMonth(
            null,
            null,
            null,
            null,
            null,
            "производственият календар няма данни за базовия месец по чл. 177 ал. 1 ("
                + period.getFromDate()
                + " – "
                + period.getToDate()
                + "). Работните дни на този месец са ЧИСЛИТЕЛЯТ на коефициента по НСОРЗ чл. 18"
                + " ал. 2 — без календар той не се изчислява, а приет за равен на текущия месец,"
                + " коефициентът би станал единица МЪЛЧАЛИВО. Зареди неприсъствените дни за"
                + " годината.");
      }
      return new BaseMonth(
          art17BaseOf(candidate),
          art17NonPermanentPartOf(candidate),
          worked,
          baseNorm,
          candidate.getReference(),
          null);
    }
    return null;
  }

  /**
   * НАЧИСЛЕНОТО ПО ЧЛ. 17 БТВ НА ЕДИН ФИШ — перата с постоянен характер, знак CREDIT, собствен
   * период.
   *
   * <p><b>⚠️ ЧЕТЕ СЕ ЗАМРАЗЕНИЯТ ФЛАГ НА РЕДА, а не речникът днес.</b> {@code BgPaySlipLine} копира
   * {@code bgIsPermanentCharacter} при създаването си точно за да може миналият месец да се прочете
   * какъвто Е БИЛ. Прочетен през речника, един отметнат днес флаг би пренаписал базата на вече
   * изплатен отпуск.
   *
   * <p><b>⚠️ САМО СОБСТВЕНИЯТ ПЕРИОД.</b> Делта-редът от ретро-корекция носи пари за ДРУГ месец —
   * броен тук, той би вдигнал среднодневното на месец, през който тези пари не са били начислени.
   * Същият довод като при {@code BgPaySlipResolver#insuranceIncomeBase}.
   *
   * <p><b>⚠️ ЧЕТЕ СЕ {@code bgIsArt17Base}, А НЕ ПОСТОЯННИЯТ ХАРАКТЕР — И ТОВА БЕШЕ ГРЕШКАТА.</b>
   * Чл. 17 ал. 1 изброява СЕДЕМ съставки; постоянният характер е само т. 3. Прочетена през {@code
   * bgIsPermanentCharacter}, базата губеше останалите — и месец с престой даваше ЗАНИЖЕНО
   * среднодневно: при престой 210,00 € и 5 дни отпуск отпускът излизаше с 47,73 € по-малко.
   *
   * <p><b>Седемте точки и какво носи всяка ДНЕС:</b>
   *
   * <ul>
   *   <li><b>т. 1</b> основната за отработеното време — {@code pl_osnovna} ✓
   *   <li><b>т. 2</b> над основната „според прилаганите СИСТЕМИ за заплащане“ — {@code
   *       pl_rezultati} (190) ✓. ⚠️ И ТОЙ БЕШЕ ИЗПУСНАТ ПЪРВИЯ ПЪТ, по показателна причина:
   *       бележката му отрича ПОСТОЯННИЯ характер („зависи от резултат, не единствено от
   *       отработеното време“), а това отговаря на т. 3, не на т. 2. Отричането на едната точка се
   *       прочете като отричане на другата. Дискреционното перо е 185 и то си остава вън —
   *       бележката му го казва изрично.
   *   <li><b>т. 3</b> ДТВ с постоянен характер — {@code pl_tspo}, {@code pl_nauchna_stepen}, {@code
   *       pl_postoyanen_bonus}, {@code pl_postoyanna_premia} ✓
   *   <li><b>т. 4</b> вътрешно заместване по КТ чл. 259 — <b>НЯМА ПЕРО</b> в речника
   *   <li><b>т. 5</b> КТ чл. 266 ал. 1 (неизпълнена норма не по вина) — <b>НЯМА ПЕРО</b>
   *   <li><b>т. 6</b> КТ чл. 267 ал. 1 и 3 (престой) — {@code pl_prestoi_267} (170) ✓
   *   <li><b>т. 7</b> КТ чл. 268 ал. 2 и 3 (брак не по вина) — <b>НЯМА ПЕРО</b>
   * </ul>
   *
   * <p>Трите празни точки не са пропуск на този клон: перо за тях няма изобщо. Колоната ги чака —
   * появи ли се, то влиза в базата по ОБЯВЯВАНЕ, а не защото някой си е спомнил за този метод.
   *
   * <p><b>⚠️ И ЕДНА АЛИНЕЯ, КОЯТО ТОЗИ МЕТОД НЕ МОЖЕ ДА ИЗПЪЛНИ: ЧЛ. 17 АЛ. 2.</b> Тя иска в базата
   * да влезе и „съответната пропорционална част от допълнителните периодични или годишни
   * възнаграждения, изплатени СЛЕД ползването на платения отпуск, като за целта се ПРЕИЗЧИСЛЯВАТ
   * изплатените възнаграждения за платен отпуск“. Тоест декемврийски годишен бонус мени отпуска,
   * платен през септември — със задна дата. Днес това няма нито носител, нито изчисление никъде в
   * модула, и {@code bgIsArt17Base} НЕ го решава: колоната казва КОЕ перо е в базата, а ал. 2 е за
   * КОГА и за преизчисляване НАЗАД. Засяга {@code pl_rezultati} и {@code pl_godishno_vazn}.
   * Записано, за да не се сметне за уредено.
   *
   * <p><b>⚠️ И ЗАЩО НЕ СЕ ОТМЕТНА ПРОСТО ПОСТОЯННИЯТ ХАРАКТЕР ЗА ПРЕСТОЯ.</b> Същият флаг храни
   * базата по НСОРЗ ЧЛ. 7 — часовата ставка на нощния и извънредния труд ({@code
   * BgEarningsService#permanentAllowances} → {@code BgEarningsResolver#permanentPayBase}). Вдигнат
   * там, престоят щеше да поскъпи извънредния труд: пари, преместени в съвсем друго перо, за да се
   * поправи това. Двата въпроса са различни норми и затова са различни колони.
   *
   * <p><b>⚠️ ДИЗЮНКЦИЯТА Е МИГРАЦИЯТА, А НЕ ВТОРО ПРАВИЛО.</b> Всеки ред, създаден преди колоната,
   * я носи празна. Прочетена строго, базата на ВСЕКИ исторически базов месец ставаше нула — тоест
   * среднодневно нула и отпуск, платен по пода от МРЗ, за всяко лице наведнъж. Вторият дизюнкт дава
   * на заварените редове точно днешното им поведение и запазва историята: базов месец, смятан без
   * престой, пази числото си. Нула backfill.
   *
   * <p>⚠️ И е {@code Boolean.TRUE.equals}, не {@code !Boolean.FALSE.equals}: App Install е upsert и
   * при заварена таблица {@code false} може да се елидира до {@code NULL} — а {@code null} тук
   * трябва да значи „не е казано“, не „да“.
   */
  protected BigDecimal art17BaseOf(BgPaySlip slip) {
    BigDecimal sum = BigDecimal.ZERO;
    if (slip.getBgPaySlipLineList() == null) {
      return sum;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (inArt17Base(line)) {
        sum = sum.add(line.getBgAmount());
      }
    }
    return sum;
  }

  /**
   * В БАЗАТА ПО ЧЛ. 17 ЛИ Е ТОЗИ РЕД — един предикат, два читателя.
   *
   * <p>Вика го {@link #art17BaseOf} (числителят) и {@link #art17NonPermanentPartOf} (обявата).
   * Написан два пъти, той е свободен да се размине — а разминат, бележката щеше да обявява престой,
   * който базата не е броила.
   */
  static boolean inArt17Base(BgPaySlipLine line) {
    return (Boolean.TRUE.equals(line.getBgIsArt17Base())
            || Boolean.TRUE.equals(line.getBgIsPermanentCharacter()))
        && BgPaySlipResolver.CREDIT.equals(line.getBgSign())
        && BgPaySlipResolver.isOwnPeriod(line);
  }

  /**
   * ЧАСТТА ОТ БАЗАТА, КОЯТО НЕ Е „ПОСТОЯНЕН ХАРАКТЕР“ — т. 5–7, за ОБЯВАТА на фиша.
   *
   * <p><b>⚠️ ЧИСЛО ЗА ЧЕТЕНЕ, НЕ ЗА СМЯТАНЕ.</b> Базата вече е сметната цяла; това е разбивката ѝ.
   * Съществува, защото престоят в базовия месец мени числото на отпуска, а без нея нищо на фиша не
   * казва защо: два фиша с еднаква заплата и различно среднодневно изглеждат като дефект. Обявено,
   * числото става проверимо от човек, вместо да се доказва с дебъгер.
   */
  protected BigDecimal art17NonPermanentPartOf(BgPaySlip slip) {
    BigDecimal sum = BigDecimal.ZERO;
    if (slip.getBgPaySlipLineList() == null) {
      return sum;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (inArt17Base(line) && !Boolean.TRUE.equals(line.getBgIsPermanentCharacter())) {
        sum = sum.add(line.getBgAmount());
      }
    }
    return sum;
  }

  /**
   * ВСИЧКИ ОТСЪСТВИЯ НА ЕДИН ПЕРИОД в работни дни, или {@code null} при отказ на броенето.
   *
   * <p>Платен + неплатен + други: и трите излизат от отработените по чл. 18 ал. 1. Болничните НЕ —
   * те вече са свалени от {@code bgWorkedDays} от болничния енджин и второ изваждане би ги отчело
   * два пъти.
   *
   * <p><b>⚠️ „ДРУГИ“ НОСИ И НЕПРЕДСТАВЕНИЯ БОЛНИЧЕН.</b> Той не попада в никоя друга кофа ({@code
   * BgPayrollInputBuilder#leaveDaysOf}); невзет тук, би НАДУЛ знаменателя на среднодневното. Вади
   * се, и това е верният прочит на чл. 18 ал. 1: „среднодневното … се дели на ОТРАБОТЕНИТЕ дни“, а
   * ден без труд и без заплащане не е отработен ден.
   *
   * <p><b>⚠️ И ПОСОКАТА НА ЧИСЛОТО Е ДВЕ, НЕ ЕДНА.</b> При месец, който ОЩЕ квалифицира, по-малкият
   * знаменател вдига среднодневното (измерено: 57,14 → 66,17 при 22 → 19 отработени). Но {@link
   * #findBaseMonth} иска поне ДЕСЕТ отработени дни ({@code
   * BgArt177Resolver#QUALIFYING_WORKED_DAYS}, КТ чл. 177 ал. 1) — свалянето може да бутне кандидата
   * ПОД прага, той се прескача и за база става ПО-РАНЕН месец, чието среднодневно е свободно да е
   * по-ниско (измерено: 57,14 → 42,86). Изходът пак е законово верен — месец с 9 отработени дни НЕ
   * Е законна база, — но парите могат да отидат и НАДОЛУ. Двата случая имат свои свидетели в {@code
   * BgUnsubmittedSickNoteFreezeTest}.
   *
   * <p>⚠️ Правилото върви заедно с {@code freezeLeaveDays}: същото повишаване в „други“ е условието
   * инвариантът там да е верен по построение. Двете половини се движат ЗАЕДНО нарочно — разделени,
   * кофите и отсъствията пак щяха да си противоречат.
   */
  protected BigDecimal absenceDaysOf(EmploymentContract contract, Period period) {
    Employee employee = contract.getEmployee();
    if (employee == null) {
      return BigDecimal.ZERO;
    }
    BgPayrollInputBuilder.LeaveDays leave =
        inputBuilder()
            .leaveDaysOf(
                employee, period.getFromDate(), period.getToDate(), contract.getBgHoursPerDay());
    if (leave.isRefused()) {
      return null;
    }
    if (leave.paid == null) {
      // Нула валидирани заявки за онзи месец — „не знам“, което тук Е нула: ако лицето изобщо води
      // отпуските си в модула (а то ги води, инак този фиш нямаше да има дни по чл. 177), липсата
      // на заявка значи липса на отпуск.
      return BigDecimal.ZERO;
    }
    return leave.paid.add(leave.unpaid).add(leave.other);
  }

  // ═══ РЕЗЕРВНИЯТ РЕД И ПОДЪТ ═══════════════════════════════════════════════

  /**
   * СРЕДНОДНЕВНОТО ПО ЧЛ. 177 АЛ. 2 — уговореното по договор ÷ средномесечните работни дни.
   *
   * <p>⚠️ Годината е тази на ПОЛЗВАНЕТО („съответната календарна година“), а не на договора.
   */
  protected BgArt177Resolver.DailyRate contractualRate(
      BgPaySlip slip, BgEarningsService.Art177Inputs parts) {
    LocalDate on = slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
    if (on == null || parts.contractualPermanentPay == null) {
      return null;
    }
    Integer inYear =
        workingDaysOf(LocalDate.of(on.getYear(), 1, 1), LocalDate.of(on.getYear(), 12, 31));
    return BgArt177Resolver.contractualDailyRate(parts.contractualPermanentPay, inYear);
  }

  /**
   * ПОДЪТ ПО НСОРЗ ЧЛ. 19 АЛ. 2, или {@code null}, ако не се прилага.
   *
   * <p><b>⚠️ САМО ПРИ ПЪЛЕН РАБОТЕН ДЕН, и това е дословно в нормата.</b> Признакът е договореният
   * работен ден срещу законоустановения за длъжността: {@code bgHoursPerDay == bgLegalHoursPerDay}.
   * Наложен и при непълно работно време, подът би вдигнал отпуска на лице на половин щат до пълния
   * размер — тоест би му платил отпуска двойно.
   *
   * <p>⚠️ Липсваща МРЗ дава {@code null}, а не нулев под: нула би „минала“ проверката винаги и
   * подът щеше да изчезне тихо в деня, в който параметърът не е зареден.
   */
  protected BgArt177Resolver.DailyRate statutoryFloor(
      BgPaySlip slip, BgPayrollInput input, Integer usageNorm) {
    EmploymentContract contract = slip.getEmploymentContract();
    if (contract == null) {
      return null;
    }
    // ⚠️ signum(), НЕ == null. Генерираният getter за decimal връща ZERO, тоест „не е попълнено“
    // пристига като нула и сравнението с null е МЪРТВО УСЛОВИЕ. Непопълнени часове значи, че НЕ
    // Е ИЗВЕСТНО дали работният ден е пълен — а подът по чл. 19 ал. 2 виси точно на това, тоест
    // не се прилага. Наложен при непознат щат, той би вдигнал отпуска на половин щат до пълния.
    BigDecimal contracted = contract.getBgHoursPerDay();
    BigDecimal legal = contract.getBgLegalHoursPerDay();
    if (contracted.signum() <= 0 || legal.signum() <= 0 || contracted.compareTo(legal) != 0) {
      return null;
    }
    return BgArt177Resolver.statutoryMinimumDailyRate(
        minWage(referenceDateOf(slip, input)), usageNorm);
  }

  // ═══ ЗАПИСВАНЕТО ══════════════════════════════════════════════════════════

  /** Създава или обновява перото по стабилния си код — ИДЕМПОТЕНТНО, както енджина. */
  protected void upsert(
      BgPaySlip slip,
      BigDecimal amount,
      BigDecimal days,
      BgArt177Resolver.DailyRate rate,
      BaseMonth base) {
    BgPaySlipLine line = lineWithCode(slip);
    if (line == null) {
      BgPaySlipLineType type = lineType();
      if (type == null) {
        throw new IllegalStateException(
            "типът перо „"
                + LINE_ART177_LEAVE
                + "“ липсва в речника (bg_pay_slip_line_type.csv) — възнаграждението за платен"
                + " отпуск по чл. 177 не може да се начисли. Пусни App Install.");
      }
      line = paySlipService().lineFrom(type, amount);
      paySlipService().addLine(slip, line);
    }
    line.setBgAmount(amount);
    line.setBgQuantity(days);
    // ⚠️ Ставката е ДОКУМЕНТАЦИЯ, не източник — § 5.3. Тя се показва до сумата, за да се вижда
    // откъде идва, и затова слиза на четири знака: на два 50,4762 би станало 50,48 и умножено наум
    // би обяснило ДРУГА сума.
    line.setBgRate(
        rate.numerator.divide(
            rate.denominator, BgEarningsResolver.HOURLY_RATE_SCALE, BgEarningsResolver.ROUNDING));
    line.setNote(rate.basis + baseMonthNote(base));
  }

  /**
   * ОПАШКАТА НА БЕЛЕЖКАТА — кой месец е базата и какво НЕОБИЧАЙНО има в нея.
   *
   * <p><b>⚠️ ПРЕСТОЯТ СЕ ОБЯВЯВА, ЗАЩОТО ИНАЧЕ СРЕДНОДНЕВНОТО НЕ СЕ ОБЯСНЯВА ОТ ФИША.</b> Той влиза
   * в базата по чл. 17 ал. 1 т. 6, но живее на ДРУГ фиш — на базовия месец. Тоест две лица с
   * еднаква заплата получават различен отпуск и нищо на екрана не казва защо; проверката минава
   * през отваряне на миналия фиш и събиране на редове наум. Обявен, той е един ред текст.
   *
   * <p>⚠️ Пише се САМО когато е ненулев. Постоянно присъстващо „в т.ч. престой: 0,00 €“ е шум, а
   * шумът се спира да се чете — и точно тогава редът, който има значение, минава незабелязан.
   */
  protected String baseMonthNote(BaseMonth base) {
    if (base == null || base.reference == null) {
      return "";
    }
    String note = "; базов месец: " + base.reference;
    if (base.nonPermanentPart == null || base.nonPermanentPart.signum() <= 0) {
      return note;
    }
    return note
        + " (в т.ч. по чл. 17 ал. 1 т. 5–7, вкл. престой по чл. 267: "
        + base.nonPermanentPart
            .setScale(BgEarningsResolver.SCALE, BgEarningsResolver.ROUNDING)
            .toPlainString()
        + " €)";
  }

  /** Маха заварено перо, когато месецът вече няма отпуск по чл. 177 — както при NOT_ENTITLED. */
  protected void drop(BgPaySlip slip) {
    BgPaySlipLine line = lineWithCode(slip);
    if (line != null && slip.getBgPaySlipLineList() != null) {
      slip.getBgPaySlipLineList().remove(line);
    }
  }

  protected BgPaySlipLine lineWithCode(BgPaySlip slip) {
    if (slip.getBgPaySlipLineList() == null) {
      return null;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type != null && LINE_ART177_LEAVE.equals(type.getCode())) {
        return line;
      }
    }
    return null;
  }

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА (подменят се в тестовете) ════════════════════════

  /**
   * ФИШОВЕТЕ НА ЛИЦЕТО ПРИ СЪЩИЯ РАБОТОДАТЕЛ ПРЕДИ ТАЗИ ДАТА, от най-новия назад.
   *
   * <p><b>⚠️ ПО СЛУЖИТЕЛ И ДРУЖЕСТВО, НЕ ПО ДОГОВОР.</b> Чл. 177 ал. 1 търси месеца с 10 отработени
   * дни „при същия работодател“ — тоест и по ПРЕДИШЕН договор на лицето в същото дружество ({@code
   * payCompany}). Никога в друго дружество: то е друг работодател, а при липса на месец тук законът
   * дава ал. 2, не чужда база.
   *
   * <p><b>⚠️ САМО ПОТВЪРДЕНИ И ИЗПЛАТЕНИ — БЯЛ СПИСЪК.</b> Чл. 177 ал. 1 иска НАЧИСЛЕНО
   * възнаграждение. Ако се изключват само черновите, и ОТКАЗАН фиш би станал базов месец — с числа,
   * които никой не е начислил.
   *
   * <p>⚠️ Ретро-фишовете се ИЗКЛЮЧВАТ: коригиращият носи делта за друг месец, а заличаващият отрича
   * своя. Взети за база, те биха дали среднодневно на месец, който не е бил изживян.
   */
  protected List<BgPaySlip> earlierSlips(EmploymentContract contract, LocalDate before) {
    boolean sameEmployer = contract.getEmployee() != null && contract.getPayCompany() != null;
    TypedQuery<BgPaySlip> q =
        JPA.em()
            .createQuery(
                "SELECT s FROM BgPaySlip s WHERE "
                    + (sameEmployer
                        ? "s.employmentContract.employee = :employee"
                            + " AND s.employmentContract.payCompany = :company"
                        : "s.employmentContract = :contract")
                    + " AND s.period.fromDate < :before"
                    + " AND s.statusSelect IN (:confirmed, :paid)"
                    + " AND (s.bgIsCorrection IS NULL OR s.bgIsCorrection = FALSE)"
                    + " AND (s.bgIsDeletion IS NULL OR s.bgIsDeletion = FALSE)"
                    + " ORDER BY s.period.fromDate DESC, s.id DESC",
                BgPaySlip.class)
            .setParameter("before", before)
            .setParameter("confirmed", BgPaySlipResolver.CONFIRMED)
            .setParameter("paid", BgPaySlipResolver.PAID)
            .setMaxResults(MAX_BASE_MONTHS_BACK);
    if (sameEmployer) {
      q.setParameter("employee", contract.getEmployee())
          .setParameter("company", contract.getPayCompany());
    } else {
      q.setParameter("contract", contract);
    }
    return q.getResultList();
  }

  /** {@code MIN_WAGE} за датата, или {@code null}. Същият филтър като на всеки друг параметър. */
  protected BigDecimal minWage(LocalDate date) {
    if (date == null) {
      return null;
    }
    BgPayrollParameter row =
        Beans.get(BgPayrollParameterRepository.class)
            .all()
            .filter(
                "self.code = :c AND self.validFrom <= :d "
                    + "AND (self.validTo IS NULL OR self.validTo >= :d)")
            .bind("c", PARAM_MIN_WAGE)
            .bind("d", date)
            .fetchOne();
    return row == null ? null : row.getParamValue();
  }

  protected BgPaySlipLineType lineType() {
    List<BgPaySlipLineType> found =
        JPA.em()
            .createQuery(
                "SELECT t FROM BgPaySlipLineType t WHERE t.code = :code", BgPaySlipLineType.class)
            .setParameter("code", LINE_ART177_LEAVE)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  protected BgEarningsService earningsService() {
    return Beans.get(BgEarningsService.class);
  }

  protected BgPayrollInputBuilder inputBuilder() {
    return Beans.get(BgPayrollInputBuilder.class);
  }

  /**
   * РАБОТНИТЕ ДНИ В ИНТЕРВАЛ, или {@code null}, ако календарът не покрива годините му.
   *
   * <p><b>⚠️ ЧИСЛО ИЛИ {@code null}, А НЕ БРОЯЧ С ПРИЗНАК.</b> „Няма празници в този интервал“ и
   * „не знам какви са празниците“ дават един и същ брой, но са различни отговори — и разликата
   * решава дали коефициентът по чл. 18 ал. 2 е изчислен, или мълчаливо приет за единица. Слепени
   * тук веднъж, те не могат да се разделят по-нагоре.
   *
   * <p>⚠️ Един шев за ДВАТА въпроса (месец и година), защото и двата са един и същ въпрос към един
   * и същ календар. Два шева биха позволили тест да подмени единия и да остави другия да иска база.
   */
  protected Integer workingDaysOf(LocalDate from, LocalDate to) {
    BgWorkingDayService.Count count =
        workingDayService().count(from, (int) (to.toEpochDay() - from.toEpochDay()) + 1);
    return count.isReliable() && count.getWorkingDays() > 0 ? count.getWorkingDays() : null;
  }

  protected BgWorkingDayService workingDayService() {
    return Beans.get(BgWorkingDayService.class);
  }

  protected BgPaySlipService paySlipService() {
    return Beans.get(BgPaySlipService.class);
  }

  /** Датата за версията на параметрите: от входа, а при липса — от периода на фиша. */
  protected LocalDate referenceDateOf(BgPaySlip slip, BgPayrollInput input) {
    if (input != null && input.parameterReferenceDate != null) {
      return input.parameterReferenceDate;
    }
    return slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
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
