package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ЗДРАВНАТА ВНОСКА ПРИ НЕПЛАТЕН ОТПУСК — ЗЗО чл. 40 ал. 1 т. 1 б. „б“. Чиста аритметика: без база,
 * без Guice, без Hibernate.
 *
 * <p><b>ДОСЛОВНИЯТ ТЕКСТ, ЧЕТЕН ОТ ПЪРВИЧЕН ИЗТОЧНИК</b> (ЗЗО, редакция ДВ бр. 68 от 2026 г., в
 * сила от 01.08.2026 г.): „за лицата в неплатен отпуск, които не подлежат на осигуряване на друго
 * основание, вноската се определя върху половината от минималния месечен размер на осигурителния
 * доход за самоосигуряващите се лица, определен със Закона за бюджета на държавното обществено
 * осигуряване; вноската е изцяло за сметка на осигуреното лице — когато неплатеният отпуск е по
 * негово желание, и за сметка на работодателя или ведомството — когато неплатеният отпуск е за
 * отглеждане на дете по реда на чл. 167а от Кодекса на труда или поради производствена необходимост
 * и престой; вноската се внася чрез съответното предприятие, организация или ведомство до 25-о
 * число на месеца, следващ този, за който се отнася“.
 *
 * <p><b>⚠️ ТРИ НЕЩА ОТ ТОЗИ ТЕКСТ, КОИТО ПРЕРАЗКАЗЪТ ГУБИ.</b>
 *
 * <ol>
 *   <li><b>Чл. 167а, а НЕ чл. 165.</b> Нормата изброява поводите за сметка на работодателя
 *       дословно: отглеждане на дете <em>по реда на чл. 167а</em>, производствена необходимост и
 *       престой. Отпускът по чл. 165 КТ е ПЛАТЕН и изобщо не е в тази хипотеза — четен като повод,
 *       той би прехвърлил върху работодателя вноска, която законът не му възлага.
 *   <li><b>„Изцяло за сметка на“ — един платец, ЦЯЛАТА вноска.</b> Виж {@link
 *       #RATE_IS_THE_WHOLE_CONTRIBUTION}. Това е и разликата с ЗЗО чл. 40 ал. 1 т. 5 (дните ДОО),
 *       където законът казва друго: „вноските са за сметка на работодателя … и са РАВНИ НА
 *       ДЪЛЖИМАТА ОТ НЕГО ЧАСТ от вноската“, тоест 4,8 на сто, а не 8. Двата ръба изглеждат еднакво
 *       и се смятат различно.
 *   <li><b>„които не подлежат на осигуряване на друго основание“</b> — условие на самата хипотеза,
 *       а не подразбиране. Речникът на отсъствията вече го носи като стойност {@code other_basis}
 *       на {@code healthContributionRule}; тук тя дава {@link Status#NOT_APPLICABLE}, не сума.
 * </ol>
 *
 * <p><b>Защо това е РАЗЛИЧЕН ръб от ЗЗО чл. 40 ал. 5.</b> Досега бележката на параметъра {@code
 * HEALTH_UNPAID_LEAVE_BASE} обявяваше препратката за СПОРНА между ал. 1 т. 1 б. „б“ и ал. 5, с
 * довода че „двете хипотези ползват една и съща база, затова сумата излиза еднаква и грешният цитат
 * не се вижда от числото“. Първичният текст решава спора и базите НЕ са една и съща: б. „б“ казва
 * „върху ПОЛОВИНАТА“ — определена сума, — а ал. 5 казва „върху осигурителен доход НЕ ПО-МАЛЪК ОТ
 * половината“, тоест ПОД, придружен с годишно изравняване, и то за лицата, които изобщо не подлежат
 * на осигуряване по ал. 1, 2 и 3. Оттам следва и кой минава през фиша: б. „б“ изрично се внася
 * „чрез съответното предприятие“, ал. 5 — от самото лице.
 *
 * <p><b>⚠️ КАКВО ТОЗИ КЛАС ОЩЕ НЕ ПРАВИ.</b> Сумата се СМЯТА и се ВРЪЩА, но НЕ се записва като перо
 * — точно както при здравната вноска за дните ДОО ({@link BgSickLeaveResolver#healthAbsenceLine}).
 * И двете чакат едно и също нещо и то не е в този клас: собствен ред в речника на фондовете, тоест
 * ред и в речника с типове пера. Виж доклада на клона и javadoc-а на {@code
 * BgSickLeaveService#healthAbsence}.
 */
public final class BgUnpaidLeaveHealthResolver {

  /** Скала и закръгляне — § 5.3, същото правило като навсякъде в модула. */
  public static final int SCALE = BgEarningsResolver.SCALE;

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * Стойността на {@code BgAbsenceType.healthContributionRule} за неплатен отпуск ПО ЖЕЛАНИЕ НА
   * ЛИЦЕТО — речникът {@code bg.health.contribution.rule.select}.
   */
  public static final String RULE_UNPAID_EMPLOYEE = "unpaid_employee";

  /**
   * Стойността за неплатен отпуск ЗА СМЕТКА НА РАБОТОДАТЕЛЯ — отглеждане на дете по чл. 167а КТ,
   * производствена необходимост и престой.
   */
  public static final String RULE_UNPAID_EMPLOYER = "unpaid_employer";

  /** Лицето е осигурено на друго основание — хипотезата на б. „б“ изобщо не се открива. */
  public static final String RULE_OTHER_BASIS = "other_basis";

  /**
   * <b>⚠️ РЕШЕНИЕ № 1 — НАИМЕНУВАН ШЕВ: КОЯ Е СТАВКАТА.</b> ЦЯЛАТА здравноосигурителна вноска, а не
   * частта на единия платец.
   *
   * <p>Основанието е дословно: „вноската е ИЗЦЯЛО за сметка на осигуреното лице … и за сметка на
   * работодателя …“. Тоест платецът се сменя, а вноската не се дели — при 8 на сто общо това е 8 на
   * сто, независимо кой я носи. Ставката пак НЕ се пише в кода: тя е сборът на двете ставки на фонд
   * {@code HEALTH} от {@code bg_insurance_contribution} за вида осигурен, категорията и периода —
   * виж {@code BgInsuranceCalculationService#findHealthTotalRate}.
   *
   * <p>⚠️ Сборът е поискан именно защото ЧАСТИТЕ се менят, а сборът не: за вид 01 през 2026 г.
   * траншът е 4,8 + 3,2, а за видове 05 и 24 от 01.08.2026 г. — 6,4 + 1,6. И двете дават 8,0. Число
   * в кода би минало неусетно през този транш и би паднало при следващия, който смени САМИЯ сбор.
   *
   * <p>⚠️ Обратната проверка: при четене „частта на платеца“ неплатеният отпуск по желание би дал
   * 3,2 на сто върху 310,10 = 9,92 вместо 24,81 — тоест лицето би внесло по-малко от една трета от
   * дължимото, а числото на фиша би изглеждало пресметнато.
   */
  public static final boolean RATE_IS_THE_WHOLE_CONTRIBUTION = true;

  /**
   * <b>⚠️ РЕШЕНИЕ № 2 — НАИМЕНУВАН ШЕВ: ПРОРАТИРА ЛИ СЕ ВНОСКАТА ПРИ ЧАСТИЧЕН МЕСЕЦ.</b> ДА, по
   * работните дни в неплатен отпуск спрямо работните дни в периода.
   *
   * <p><b>Защо това е решение, а не прочит.</b> Нормата дава база „половината от МИНИМАЛНИЯ МЕСЕЧЕН
   * размер“ и мълчи за месеца, в който неплатеният отпуск е само няколко дни. Двете четения
   * съществуват: „цялата месечна база при поне един ден отпуск“ и „базата, съответна на дните“.
   *
   * <p>Избрано е второто, по два довода. Първо, самата хипотеза иска лицето да НЕ подлежи на
   * осигуряване на друго основание — а през отработените дни от същия месец то подлежи, тоест
   * пълната месечна база би обложила и период, който вече е обложен по т. 1. Второ, делителят е
   * РАБОТНИ ДНИ В ПЕРИОДА, същият, който ползват начисленията ({@code BgEarningsResolver}),
   * обезщетенията ({@code BgCompensationResolver}) и здравната вноска за дните ДОО ({@code
   * BgSickLeaveResolver}) — три различни делителя за едно понятие са три различни числа на един
   * фиш.
   *
   * <p>⚠️ При ЦЯЛ месец неплатен отпуск двете четения СЪВПАДАТ и разликата не се вижда от числото:
   * 21 от 21 работни дни дават пълните 24,81. Затова шевът е наименуван, а не подразбиращ се —
   * приемният случай T12 („цял месец неплатен отпуск“) не може да го провери.
   */
  public static final boolean CONTRIBUTION_FOLLOWS_UNPAID_DAYS = true;

  private BgUnpaidLeaveHealthResolver() {}

  /** Кой носи вноската — или никой, когато хипотезата на б. „б“ не се открива. */
  public enum Payer {
    /** Неплатен отпуск по желание на лицето — вноската е изцяло негова. */
    INSURED_PERSON,
    /** Отглеждане на дете по чл. 167а КТ, производствена необходимост или престой. */
    EMPLOYER
  }

  /** Трите изхода. Виж {@link Outcome}. */
  public enum Status {
    /** Сметнато е; {@link Outcome#amount} и {@link Outcome#payer} са попълнени. */
    COMPUTED,
    /**
     * Хипотезата не се открива — отсъствието не е неплатен отпуск по б. „б“. Нормално състояние.
     */
    NOT_APPLICABLE,
    /** Трябваше да се сметне и не можа — {@link Outcome#detail} казва какво липсва. */
    REFUSED
  }

  /**
   * Един отговор — сума и платец, ИЛИ обявена причина да няма сума.
   *
   * <p>Огледален на {@link BgSickLeaveOutcome} и по същия довод: „не се дължи“ и „не можах да
   * сметна“ са два различни факта, а свити до нула стават един. Носи и ПЛАТЕЦА, защото при този ръб
   * той е половината отговор: 24,81 за сметка на лицето и 24,81 за сметка на работодателя са две
   * различни числа на фиша, макар да са едно и също число.
   */
  public static final class Outcome {

    public final Status status;

    /** Закръглена по § 5.3 сума — САМО при {@link Status#COMPUTED}, инак {@code null}. */
    public final BigDecimal amount;

    /** Кой я носи — САМО при {@link Status#COMPUTED}, инак {@code null}. */
    public final Payer payer;

    /** Работните дни в неплатен отпуск, за които е сметната — документира сумата. */
    public final BigDecimal quantity;

    /** ЦЯЛО ИЗРЕЧЕНИЕ за човек. Празно е само при {@link Status#COMPUTED}. */
    public final String detail;

    private Outcome(
        Status status, BigDecimal amount, Payer payer, BigDecimal quantity, String detail) {
      this.status = status;
      this.amount = amount;
      this.payer = payer;
      this.quantity = quantity;
      this.detail = detail;
    }

    static Outcome computed(BigDecimal amount, Payer payer, BigDecimal unpaidDays) {
      return new Outcome(Status.COMPUTED, amount, payer, unpaidDays, null);
    }

    static Outcome notApplicable(String detail) {
      return new Outcome(Status.NOT_APPLICABLE, null, null, null, detail);
    }

    /**
     * ⚠️ ПУБЛИЧЕН, за да може и УСЛУГАТА да откаже със своя причина.
     *
     * <p>Резолверът отказва при липсваща база и липсваща ставка — входове, за които той отговаря.
     * Услугата обаче среща свой случай, който резолверът не може да види: ДВА различни повода за
     * неплатен отпуск в един месец, тоест два платеца за едно число дни. Отказът е ЕДИН вид изход и
     * не бива да има два носача според това кой го е вдигнал.
     */
    public static Outcome refused(String detail) {
      return new Outcome(Status.REFUSED, null, null, null, detail);
    }

    public boolean isComputed() {
      return status == Status.COMPUTED;
    }

    @Override
    public String toString() {
      return status
          + "["
          + (amount == null ? "" : amount + " · " + payer)
          + (detail == null ? "" : detail)
          + "]";
    }
  }

  // ═══ ЧИСТА АРИТМЕТИКА ═══════════════════════════════════════════════════

  /**
   * ПЛАТЕЦЪТ по правилото от речника на отсъствията ({@code BgAbsenceType.healthContributionRule}).
   *
   * <p>⚠️ Правилото се ЧЕТЕ от речника, а не се извежда от кода на отсъствието. Списък с кодове тук
   * ({@code unpaid_own_le30}, {@code unpaid_child_u8_167a}, …) би остарял точно както остаряват
   * всички ръчно поддържани списъци: новият вид неплатен отпуск влиза в речника, не в този клас.
   *
   * @param healthContributionRule стойност на {@code bg.health.contribution.rule.select}
   * @return платецът, или {@code null}, когато хипотезата на б. „б“ не се открива
   */
  public static Payer payerFor(String healthContributionRule) {
    if (healthContributionRule == null) {
      return null;
    }
    String rule = healthContributionRule.trim();
    if (RULE_UNPAID_EMPLOYEE.equals(rule)) {
      return Payer.INSURED_PERSON;
    }
    if (RULE_UNPAID_EMPLOYER.equals(rule)) {
      return Payer.EMPLOYER;
    }
    return null;
  }

  /**
   * ВНОСКАТА = база × ставка × дни / (100 × норма) — ЗЗО чл. 40 ал. 1 т. 1 б. „б“.
   *
   * <p>Едно умножение и едно деление, тоест ЕДНО закръгляне — § 5.3.
   *
   * @param halfSolBase {@code HEALTH_UNPAID_LEAVE_BASE} за периода — половината от минималния
   *     месечен осигурителен доход за самоосигуряващите се. Единица: ЕВРО на месец
   * @param totalRatePct ЦЯЛАТА ставка на фонд ЗОВ (работодател + лице). Единица: ПРОЦЕНТ
   * @param unpaidDays работни дни в неплатен отпуск. Единица: РАБОТНИ ДНИ. ⚠️ ДРОБНИ СА ЗАКОННИ —
   *     час отпуск по КТ чл. 166 е 0,125 дни, виж {@code BgPayrollInput#paidLeaveDays}
   * @param workingDaysInPeriod работни дни в периода — делителят. Трябва да е &gt; 0
   */
  public static BigDecimal amount(
      BigDecimal halfSolBase,
      BigDecimal totalRatePct,
      BigDecimal unpaidDays,
      int workingDaysInPeriod) {
    return halfSolBase
        .multiply(totalRatePct)
        .multiply(unpaidDays)
        .divide(
            HUNDRED.multiply(BigDecimal.valueOf(workingDaysInPeriod)),
            SCALE,
            BgEarningsResolver.ROUNDING);
  }

  /**
   * ВНОСКАТА ПРИ НЕПЛАТЕН ОТПУСК — сума и платец, ИЛИ обявена причина да няма сума.
   *
   * <p>⚠️ Отказва при липсваща база и при липсваща ставка, вместо да сметне нула: нула на фиша
   * изглежда като „не се дължи“, а тук тя значи „дължи се и не е декларирана“. Същото правило като
   * при {@link BgSickLeaveResolver#healthAbsenceLine}.
   *
   * @param healthContributionRule правилото от речника на отсъствията
   * @param halfSolBase {@code HEALTH_UNPAID_LEAVE_BASE} за периода
   * @param totalRatePct сборът на двете ставки на фонд ЗОВ за периода
   * @param unpaidDays работни дни в неплатен отпуск
   * @param workingDaysInPeriod работни дни в периода
   */
  public static Outcome resolve(
      String healthContributionRule,
      BigDecimal halfSolBase,
      BigDecimal totalRatePct,
      BigDecimal unpaidDays,
      Integer workingDaysInPeriod) {

    Payer payer = payerFor(healthContributionRule);
    if (payer == null) {
      return Outcome.notApplicable(
          "отсъствието не е неплатен отпуск по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ — правилото в речника на"
              + " отсъствията е „"
              + (healthContributionRule == null ? "непопълнено" : healthContributionRule.trim())
              + "“. Здравна вноска върху половината минимален осигурителен доход не се дължи на това"
              + " основание.");
    }
    if (unpaidDays == null || unpaidDays.signum() <= 0) {
      return Outcome.notApplicable(
          "нула работни дни в неплатен отпуск този период — вноската по ЗЗО чл. 40 ал. 1 т. 1 б. „б“"
              + " не се дължи.");
    }
    if (workingDaysInPeriod == null || workingDaysInPeriod <= 0) {
      return Outcome.refused(
          "работните дни в периода не са установени — те са делителят на вноската. Нула тук НЕ се"
              + " чете като „месец без работни дни“: генерираният getter връща нула и за непопълнено"
              + " поле.");
    }
    if (unpaidDays.compareTo(BigDecimal.valueOf(workingDaysInPeriod)) > 0) {
      return Outcome.refused(
          "дните в неплатен отпуск ("
              + unpaidDays.toPlainString()
              + ") надхвърлят работните дни в периода ("
              + workingDaysInPeriod
              + ") — вноската би излязла над месечната си база. Сверѝ входа, не сметката.");
    }
    if (halfSolBase == null || halfSolBase.signum() <= 0) {
      return Outcome.refused(
          "параметърът HEALTH_UNPAID_LEAVE_BASE не е зареден за периода — базата по ЗЗО чл. 40 ал. 1"
              + " т. 1 б. „б“ е ПОЛОВИНАТА от минималния месечен осигурителен доход за"
              + " самоосигуряващите се лица (275,33 до 31.07.2026 г., 310,10 от 01.08.2026 г.)."
              + " Зареди го през App Install. БЕЗ него вноската НЕ е"
              + " нула — тя е недекларирана.");
    }
    if (totalRatePct == null || totalRatePct.signum() <= 0) {
      return Outcome.refused(
          "ставката на фонд ЗОВ не е намерена за този вид осигурен, категория труд и период в"
              + " bg_insurance_contribution.csv. Тук се иска ЦЯЛАТА вноска, а не частта на единия"
              + " платец — виж RATE_IS_THE_WHOLE_CONTRIBUTION.");
    }

    return Outcome.computed(
        amount(halfSolBase, totalRatePct, unpaidDays, workingDaysInPeriod), payer, unpaidDays);
  }
}
