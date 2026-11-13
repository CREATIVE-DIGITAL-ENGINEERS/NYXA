package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ФАКТИТЕ, ОТ КОИТО ЗАВИСЯТ НАЧИСЛЕНИЯТА — извадени веднъж, подадени на дисчпача.
 *
 * <p><b>Защо обект, а не шест аргумента.</b> Дисчпачът минава през ЦЕЛИЯ речник с типове пера,
 * тоест едни и същи факти обикалят двайсет и девет пъти. Като аргументи те се пренареждат при
 * първата добавка и {@code (база, отработени, норма)} тихо става {@code (база, норма, отработени)}
 * — грешка, която компилаторът НЕ вижда, защото и трите са числа. Като полета с имена не се
 * пренареждат.
 *
 * <p><b>⚠️ И ЗАЩО BUILDER, А НЕ КОНСТРУКТОР.</b> Доводът отгоре е за ПОВИКВАНЕТО, а конструкторът е
 * същото повикване: с шест полета той е поносим, с двайсет и седем е точно капанът, срещу който
 * класът съществува. Осем часови числа и четири процента са дванайсет съседни {@code BigDecimal}
 * аргумента — разменени два от тях, компилаторът мълчи, а извънредният труд в почивен ден се плаща
 * по ставката на празничния. Builder-ът е същият идиом като на {@link BgPayrollInput} и по същата
 * причина.
 *
 * <p><b>⚠️ {@code null} ТУК ЗНАЧИ „НЕ ЗНАМ“, НЕ НУЛА.</b> Всяко поле е обвит тип точно затова:
 * {@code workedDays} нула значи цял месец отсъствие и е ЗАКОНЕН случай, а {@code null} значи, че
 * никой не е казал. Двете водят до различен изход — сума 0,00 срещу отказ — и класът съществува, за
 * да не се слеят по пътя.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата: {@code BgEarningsService} го сглобява, {@link
 * BgEarningsResolver} го чете.
 */
public final class BgEarningsFacts {

  /** Основната месечна заплата. Единица: ЕВРО на месец. {@code null} или нула — непопълнена. */
  public final BigDecimal baseSalary;

  /** Отработени дни. Единица: РАБОТНИ ДНИ. {@code null} — не са подадени; нула е законна. */
  public final Integer workedDays;

  /**
   * ДНИТЕ ПЛАТЕН ОТПУСК, КОИТО СЕ ЗАПЛАЩАТ ПО КТ ЧЛ. 177 — карвът. Единица: РАБОТНИ ДНИ. {@code
   * null} или нула значи „няма такъв отпуск“ и формулите остават БИТ ПО БИТ каквито бяха.
   *
   * <p><b>⚠️ ВТОРО ЧИСЛО, А НЕ ПОПРАВКА НА {@link #workedDays}.</b> Отработените дни са замразени
   * на фиша и хранят т. 16.1 на Декларация обр. 1 — а платеният отпуск Е време в осигуряване и
   * ТРЯБВА да остане там. Извадени от {@code workedDays}, същите дни щяха да изчезнат от
   * декларацията, тоест една поправка в заплащането щеше да сгреши декларирания стаж.
   *
   * <p><b>⚠️ ДРОБЕН Е ЗАКОНЕН</b> — КТ чл. 166 (кърмене) и чл. 157 ал. 1 т. 6 (предизвестие) дават
   * отпуск в ЧАСОВЕ; един час при осемчасов ден е 0,125 дни.
   *
   * <p>Кои пера го четат: САМО тези, чийто принос перото {@code pl_otpusk_177} после връща — виж
   * {@link BgEarningsResolver#resolve}. Часовата ставка НЕ го чете: тя дели на НОРМАТА, не на
   * отработените, тоест нощният и извънредният труд остават непокътнати.
   */
  public final BigDecimal art177LeaveDays;

  /**
   * ДНИТЕ НЕПЛАТЕН ОТПУСК, КОИТО СЕ КАРВИРАТ — вторият изваждаем. Единица: РАБОТНИ ДНИ. {@code
   * null} или нула значи „няма такъв отпуск“ и формулите остават БИТ ПО БИТ каквито бяха.
   *
   * <p><b>⚠️ НЕ Е {@code BgPayrollInput#unpaidLeaveDays}, А НЕГОВО ПОДМНОЖЕСТВО.</b> Карвират се
   * само видовете с КОФА в Декларация обр. 1 ({@code BgAbsenceType.d1Code} — т. 16.5 по КТ чл. 160
   * ал. 3 и т. 16.3 по чл. 167а), защото точно тях Д1 вади от т. 16.1. Неплатеният над 30 работни
   * дни, неприсъствието, самоотлъчката и непредставеният болничен НЯМАТ кофа: карвирани, те щяха да
   * дадат фиш за 17 дни срещу декларация за 22 дни в осигуряване. Стесняването е направено в {@code
   * BgPayrollInputBuilder#leaveDaysOf} и тук пристига готово.
   *
   * <p><b>⚠️ И ТУК, КАКТО ПРИ ЧЛ. 177, ЧИСЛОТО НЕ ПОПРАВЯ {@link #workedDays}.</b> Отработените са
   * замразени на фиша и Д1 ги чете; намалени, същите дни щяха да излязат от декларацията два пъти.
   *
   * <p>⚠️ РАЗЛИКАТА ОТ ЧЛ. 177 Е, ЧЕ ТОЗИ КАРВ НЕ СЕ ВРЪЩА. Няма перо, което да го плати обратно —
   * КТ чл. 128 т. 2 дължи възнаграждение „за извършената работа“.
   */
  public final BigDecimal unpaidCarveDays;

  /**
   * НАЗОВАНАТА ПРИЧИНА ДНИТЕ ОТСЪСТВИЕ ДА НЕ СА ПРЕБРОЕНИ, или {@code null}. Единица: няма (текст).
   *
   * <p><b>⚠️ ТОВА Е РАЗЛИКАТА МЕЖДУ „НУЛА ДНИ ОТПУСК“ И „НЕ СЕ ЗНАЕ“.</b> При отказ на {@code
   * BgPayrollInputBuilder#leaveDaysOf} и трите кофи пристигат {@code null} — точно както при
   * инсталация без нито една заявка за отпуск. Двете състояния искат ПРОТИВОПОЛОЖНО поведение:
   * второто смята заплатата за пълен месец и е вярно, първото НЕ бива да я смята изобщо. Полето е
   * единственото, което ги различава — виж {@link BgEarningsResolver#carveSourceRefusal}.
   */
  public final String leaveDaysRefusal;

  /**
   * Работни дни в периода — делителят. Единица: РАБОТНИ ДНИ. {@code null} или нула — неопределен.
   */
  public final Integer workingDaysInPeriod;

  /**
   * Навършени ПЪЛНИ години признат стаж към края на периода. Единица: ГОДИНИ.
   *
   * <p>⚠️ Идва от {@link BgEarningsResolver#completedServiceYears}, тоест от КОТВЕНАТА ДАТА — не от
   * {@code BgPayrollInput#recognisedServiceYears}. {@code null} значи, че котвата липсва; нула
   * значи „лицето е в първата си година“ и е изчислена стойност.
   */
  public final Integer completedServiceYears;

  /**
   * Процентът за всяка навършена година, с наложен вече законов под. Единица: ПРОЦЕНТ.
   *
   * <p>{@code null} значи, че {@code SENIORITY_MIN_PCT} не е зареден за периода — отказ, не нула.
   */
  public final BigDecimal seniorityRatePct;

  /**
   * НАЧИСЛЯВА ЛИ СЕ КЛАС ПРОСЛУЖЕНО ВРЕМЕ — единственият факт тук, който не е число.
   *
   * <p><b>Основанието е НСОРЗ чл. 12 ал. 1:</b> допълнителното възнаграждение за трудов стаж и
   * професионален опит се заплаща „на работниците и служителите“ в процент върху основната заплата,
   * „определена с ИНДИВИДУАЛНИЯ ТРУДОВ ДОГОВОР“. Договорът за управление и контрол, гражданският
   * договор и самоосигуряващото се лице са извън нормата и класът НЕ им се дължи — колкото и стаж
   * да имат и колкото и пълен да е останалият вход.
   *
   * <p><b>⚠️ ТРИТЕ СТОЙНОСТИ СА ТРИ РАЗЛИЧНИ ОТГОВОРА, И ТОВА Е ПРИЧИНАТА ПОЛЕТО ДА Е ОБВИТ
   * ТИП.</b> {@code TRUE} — нормата покрива това правоотношение и класът се смята. {@code FALSE} —
   * установено е, че перото не се дължи: ред не се създава, а заварен се маха. {@code null} — НИКОЙ
   * НЕ Е ПИТАН (речникът с видове осигурен не е четен, редът за периода липсва или видът осигурен
   * не е попълнен по договора) и това е ОТКАЗ, не отговор.
   *
   * <p>⚠️ Прочетено като „да“, непрочетеното би дало клас на ДУК, тихо. Прочетено като „не“, то би
   * отнело класа на всеки нает също така тихо. Затова третата стойност не се слива с никоя от
   * двете: {@link BgEarningsResolver#seniorityLine} я обявява.
   */
  public final Boolean entitledToSeniorityBonus;

  // ═══ ЧАСОВИЯТ ЕНДЖИН ════════════════════════════════════════════════════

  /**
   * ДОГОВОРЕНИТЕ ЧАСОВЕ НА ДЕН — ДЕЛИТЕЛЯТ на часовата ставка. Единица: ЧАСОВЕ на ден.
   *
   * <p><b>⚠️ ДОГОВОРЕНИТЕ, А НЕ ЗАКОНОВИТЕ ОСЕМ.</b> Часовата ставка по НСОРЗ чл. 7 дели месечната
   * база на месечната продължителност на работното време, а тя е онази ПО ДОГОВОРА: лице на половин
   * щат по КТ чл. 138 работи 4 часа на ден и часовата му ставка е ДВОЙНА спрямо сметнатата по осем.
   * Закован осмичен делител би занижил всяка негова добавка наполовина — правдоподобно и грешно,
   * точно като при чл. 220.
   *
   * <p>{@code null} или нула значи „договорът не го казва“ и е ОТКАЗ. Осем по подразбиране тук е
   * ВЯРНО за повечето и ГРЕШНО точно за онези, заради които полето съществува.
   */
  public final BigDecimal contractHoursPerDay;

  /**
   * ДРУГИТЕ ДТВ С ПОСТОЯНЕН ХАРАКТЕР, вече на фиша. Единица: ЕВРО за периода.
   *
   * <p>Трета съставка на базата по НСОРЗ чл. 7 — до основната заплата и класа, които {@link
   * BgEarningsResolver} смята сам. Тук влиза каквото ОПЕРАТОРЪТ вече е вписал с {@code
   * bgIsPermanentCharacter = true}: постоянният бонус (180), постоянната премия (181) и всяко
   * бъдещо перо, което речникът обяви за постоянно.
   *
   * <p>⚠️ НЕ включва перата, които енджинът смята САМ (основна, клас, научна степен) — те влизат в
   * базата по формула, а не по прочит. Събрани и по двата пътя, те щяха да се броят ДВА пъти и
   * всяка часова добавка щеше да излезе с една трета завишена.
   *
   * <p>{@code null} се чете като нула и това е единственото поле тук, за което е така: „няма други
   * постоянни добавки“ е нормалното състояние на фиша и не е повод за отказ.
   */
  public final BigDecimal permanentAllowances;

  /**
   * НАУЧНАТА СТЕПЕН по НСОРЗ чл. 11. Единица: код ({@code DOCTOR}, {@code DOCTOR_OF_SCIENCES}).
   *
   * <p>{@code null} или празно значи „никой не е питан“ — перото се обявява за липсващо, а не за
   * нула. Стойност извън речника е ОТКАЗ, не мълчаливо нула: непозната степен е или сгрешен речник,
   * или ново основание, и двете искат човек.
   */
  public final String academicDegree;

  /**
   * НЕНОРМИРАН РАБОТЕН ДЕН — КТ чл. 139а. Отрича заплащането на извънредния труд в РАБОТНИ дни (чл.
   * 263 ал. 1), но НЕ и в почивни дни и на официални празници (ал. 2).
   */
  public final boolean unregulatedWorkDay;

  /**
   * РЕЖИМЪТ НА ОТЧИТАНЕ — код от {@code bg.work.time.mode.select}. „2“ е СИРВ.
   *
   * <p>⚠️ Чете се САМО от перото за работа на официален празник и точно затова е тук, а не се
   * извежда от друго: при СИРВ празничният час вече е влязъл в нормата за периода и е платен
   * веднъж, тоест чл. 264 иска ДОПЪЛВАНЕ до удвоения размер, а не втори удвоен размер отгоре. Виж
   * {@link BgEarningsResolver#holidayWorkLine}.
   *
   * <p>{@code null} или празно е ОТКАЗ за това перо: без режима не се знае дали часът е платен
   * веднъж, или нула пъти, а разликата е сто на сто от него.
   */
  public final String workTimeMode;

  /** Нощни часове. Единица: ЧАСОВЕ. НСОРЗ чл. 8. {@code null} — недекларирани, не нула. */
  public final BigDecimal nightHours;

  /** Часове на разположение. Единица: ЧАСОВЕ. НСОРЗ чл. 10. {@code null} — недекларирани. */
  public final BigDecimal onCallHours;

  /**
   * ВСИЧКИ часове, отработени на официален празник — нормален труд И извънреден заедно.
   *
   * <p>Единица: ЧАСОВЕ. КТ чл. 264 заплаща двойно „НЕЗАВИСИМО дали представлява извънреден труд или
   * не“, тоест разделянето тук би било грешката. {@code null} — няма отчет ИЛИ производственият
   * календар не покрива годината; и двете са „не знам“.
   */
  public final BigDecimal holidayWorkHours;

  /** Извънреден труд в работен ден. Единица: ЧАСОВЕ. КТ чл. 262 ал. 1 т. 1. */
  public final BigDecimal overtimeWorkdayHours;

  /** Извънреден труд в почивен ден. Единица: ЧАСОВЕ. КТ чл. 262 ал. 1 т. 2. */
  public final BigDecimal overtimeRestDayHours;

  /**
   * Извънреден труд на официален празник. Единица: ЧАСОВЕ. КТ чл. 262 ал. 1 т. 3.
   *
   * <p>⚠️ ПОДМНОЖЕСТВО на {@link #holidayWorkHours}, а не втори набор. Двата броя идват от РАЗЛИЧНИ
   * таблици (отчетът за време срещу допълнителните часове) и точно затова се сверяват: повече
   * извънредни празнични, отколкото празнични изобщо, значи, че двата източника си противоречат.
   */
  public final BigDecimal overtimeHolidayHours;

  /** Извънреден труд при СИРВ. Единица: ЧАСОВЕ. КТ чл. 262 ал. 1 т. 4. */
  public final BigDecimal overtimeSirvHours;

  /**
   * УГОВОРЕНОТО увеличение за извънреден труд в работен ден. Единица: ПРОЦЕНТ. {@code null} — няма
   * уговорка, тоест важи законовият минимум.
   *
   * <p>⚠️ Виж {@link BgEarningsResolver#overtimeUpliftPct}: уговорка НАД минимума е ОТКАЗ, не
   * мълчаливо занижаване до минимума.
   */
  public final BigDecimal agreedOvertimeWorkdayPct;

  /** Уговореното увеличение за извънреден труд в почивен ден. Единица: ПРОЦЕНТ. */
  public final BigDecimal agreedOvertimeRestDayPct;

  /** Уговореното увеличение за извънреден труд на официален празник. Единица: ПРОЦЕНТ. */
  public final BigDecimal agreedOvertimeHolidayPct;

  /** Уговореното увеличение за извънреден труд при СИРВ. Единица: ПРОЦЕНТ. */
  public final BigDecimal agreedOvertimeSirvPct;

  // ═══ СТАВКИТЕ ОТ КАТАЛОГА (всяка {@code null} значи „не е заредена за периода“) ═══

  /** {@code MIN_WAGE} — минималната работна заплата за страната. Единица: ЕВРО на месец. */
  public final BigDecimal minWage;

  /** {@code NIGHT_PCT_OF_MIN_WAGE} — 0,15 по НСОРЗ чл. 8. Единица: ПРОЦЕНТ. */
  public final BigDecimal nightPctOfMinWage;

  /** {@code NIGHT_FLOOR_EUR} — 0,51 по НСОРЗ чл. 8. Единица: ЕВРО на час. */
  public final BigDecimal nightFloorEur;

  /** {@code ON_CALL_EUR_PER_HOUR} — 0,05 по НСОРЗ чл. 10. Единица: ЕВРО на час. */
  public final BigDecimal onCallEurPerHour;

  /** {@code DEGREE_DOCTOR_EUR} — 7,67 по НСОРЗ чл. 11 ал. 1 т. 1. Единица: ЕВРО на месец. */
  public final BigDecimal degreeDoctorEur;

  /** {@code DEGREE_DSC_EUR} — 25,56 по НСОРЗ чл. 11 ал. 1 т. 2. Единица: ЕВРО на месец. */
  public final BigDecimal degreeDscEur;

  private BgEarningsFacts(Builder b) {
    this.baseSalary = b.baseSalary;
    this.workedDays = b.workedDays;
    this.art177LeaveDays = b.art177LeaveDays;
    this.unpaidCarveDays = b.unpaidCarveDays;
    this.leaveDaysRefusal = b.leaveDaysRefusal;
    this.workingDaysInPeriod = b.workingDaysInPeriod;
    this.completedServiceYears = b.completedServiceYears;
    this.seniorityRatePct = b.seniorityRatePct;
    this.entitledToSeniorityBonus = b.entitledToSeniorityBonus;
    this.contractHoursPerDay = b.contractHoursPerDay;
    this.permanentAllowances = b.permanentAllowances;
    this.academicDegree = b.academicDegree;
    this.unregulatedWorkDay = b.unregulatedWorkDay;
    this.workTimeMode = b.workTimeMode;
    this.nightHours = b.nightHours;
    this.onCallHours = b.onCallHours;
    this.holidayWorkHours = b.holidayWorkHours;
    this.overtimeWorkdayHours = b.overtimeWorkdayHours;
    this.overtimeRestDayHours = b.overtimeRestDayHours;
    this.overtimeHolidayHours = b.overtimeHolidayHours;
    this.overtimeSirvHours = b.overtimeSirvHours;
    this.agreedOvertimeWorkdayPct = b.agreedOvertimeWorkdayPct;
    this.agreedOvertimeRestDayPct = b.agreedOvertimeRestDayPct;
    this.agreedOvertimeHolidayPct = b.agreedOvertimeHolidayPct;
    this.agreedOvertimeSirvPct = b.agreedOvertimeSirvPct;
    this.minWage = b.minWage;
    this.nightPctOfMinWage = b.nightPctOfMinWage;
    this.nightFloorEur = b.nightFloorEur;
    this.onCallEurPerHour = b.onCallEurPerHour;
    this.degreeDoctorEur = b.degreeDoctorEur;
    this.degreeDscEur = b.degreeDscEur;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Само присвоявания — нито едно правило. Правилата са в {@link BgEarningsResolver}. */
  public static final class Builder {

    private BigDecimal baseSalary;
    private Integer workedDays;
    private BigDecimal art177LeaveDays;
    private BigDecimal unpaidCarveDays;
    private String leaveDaysRefusal;
    private Integer workingDaysInPeriod;
    private Integer completedServiceYears;
    private BigDecimal seniorityRatePct;
    private Boolean entitledToSeniorityBonus;
    private BigDecimal contractHoursPerDay;
    private BigDecimal permanentAllowances;
    private String academicDegree;
    private boolean unregulatedWorkDay;
    private String workTimeMode;
    private BigDecimal nightHours;
    private BigDecimal onCallHours;
    private BigDecimal holidayWorkHours;
    private BigDecimal overtimeWorkdayHours;
    private BigDecimal overtimeRestDayHours;
    private BigDecimal overtimeHolidayHours;
    private BigDecimal overtimeSirvHours;
    private BigDecimal agreedOvertimeWorkdayPct;
    private BigDecimal agreedOvertimeRestDayPct;
    private BigDecimal agreedOvertimeHolidayPct;
    private BigDecimal agreedOvertimeSirvPct;
    private BigDecimal minWage;
    private BigDecimal nightPctOfMinWage;
    private BigDecimal nightFloorEur;
    private BigDecimal onCallEurPerHour;
    private BigDecimal degreeDoctorEur;
    private BigDecimal degreeDscEur;

    public Builder baseSalary(BigDecimal v) {
      this.baseSalary = v;
      return this;
    }

    public Builder workedDays(Integer v) {
      this.workedDays = v;
      return this;
    }

    public Builder art177LeaveDays(BigDecimal v) {
      this.art177LeaveDays = v;
      return this;
    }

    public Builder unpaidCarveDays(BigDecimal v) {
      this.unpaidCarveDays = v;
      return this;
    }

    public Builder leaveDaysRefusal(String v) {
      this.leaveDaysRefusal = v;
      return this;
    }

    public Builder workingDaysInPeriod(Integer v) {
      this.workingDaysInPeriod = v;
      return this;
    }

    public Builder completedServiceYears(Integer v) {
      this.completedServiceYears = v;
      return this;
    }

    public Builder seniorityRatePct(BigDecimal v) {
      this.seniorityRatePct = v;
      return this;
    }

    public Builder entitledToSeniorityBonus(Boolean v) {
      this.entitledToSeniorityBonus = v;
      return this;
    }

    public Builder contractHoursPerDay(BigDecimal v) {
      this.contractHoursPerDay = v;
      return this;
    }

    public Builder permanentAllowances(BigDecimal v) {
      this.permanentAllowances = v;
      return this;
    }

    public Builder academicDegree(String v) {
      this.academicDegree = v;
      return this;
    }

    public Builder unregulatedWorkDay(boolean v) {
      this.unregulatedWorkDay = v;
      return this;
    }

    public Builder workTimeMode(String v) {
      this.workTimeMode = v;
      return this;
    }

    public Builder nightHours(BigDecimal v) {
      this.nightHours = v;
      return this;
    }

    public Builder onCallHours(BigDecimal v) {
      this.onCallHours = v;
      return this;
    }

    public Builder holidayWorkHours(BigDecimal v) {
      this.holidayWorkHours = v;
      return this;
    }

    public Builder overtimeWorkdayHours(BigDecimal v) {
      this.overtimeWorkdayHours = v;
      return this;
    }

    public Builder overtimeRestDayHours(BigDecimal v) {
      this.overtimeRestDayHours = v;
      return this;
    }

    public Builder overtimeHolidayHours(BigDecimal v) {
      this.overtimeHolidayHours = v;
      return this;
    }

    public Builder overtimeSirvHours(BigDecimal v) {
      this.overtimeSirvHours = v;
      return this;
    }

    public Builder agreedOvertimeWorkdayPct(BigDecimal v) {
      this.agreedOvertimeWorkdayPct = v;
      return this;
    }

    public Builder agreedOvertimeRestDayPct(BigDecimal v) {
      this.agreedOvertimeRestDayPct = v;
      return this;
    }

    public Builder agreedOvertimeHolidayPct(BigDecimal v) {
      this.agreedOvertimeHolidayPct = v;
      return this;
    }

    public Builder agreedOvertimeSirvPct(BigDecimal v) {
      this.agreedOvertimeSirvPct = v;
      return this;
    }

    public Builder minWage(BigDecimal v) {
      this.minWage = v;
      return this;
    }

    public Builder nightPctOfMinWage(BigDecimal v) {
      this.nightPctOfMinWage = v;
      return this;
    }

    public Builder nightFloorEur(BigDecimal v) {
      this.nightFloorEur = v;
      return this;
    }

    public Builder onCallEurPerHour(BigDecimal v) {
      this.onCallEurPerHour = v;
      return this;
    }

    public Builder degreeDoctorEur(BigDecimal v) {
      this.degreeDoctorEur = v;
      return this;
    }

    public Builder degreeDscEur(BigDecimal v) {
      this.degreeDscEur = v;
      return this;
    }

    public BgEarningsFacts build() {
      return new BgEarningsFacts(this);
    }
  }
}
