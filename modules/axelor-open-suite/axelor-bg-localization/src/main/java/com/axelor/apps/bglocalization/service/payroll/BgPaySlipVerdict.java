package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ЕДНА ПРИСЪДА НА ЕДИН ГЕНЕРАТОР, ПРИВЕДЕНА КЪМ ЕДИН ФОРМАТ — това, което операторът чете.
 *
 * <p><b>⚠️ ЗАЩО ТОЗИ КЛАС ИЗОБЩО СЪЩЕСТВУВА.</b> Четирите генератора на фиша смятаха присъди и ги
 * ИЗХВЪРЛЯХА: {@code BgPaySlipService} викаше {@code generate()} на редове 270, 293, 317 и 411 и не
 * присвояваше върнатия {@code List<Outcome>} на нищо. Правилата отказваха точно както трябва —
 * „болничният не е начислен, защото осигурителният стаж не е подаден“ — и никой не научаваше за
 * отказа. Отвън това е неразличимо от „нямаше какво да се начисли“: перо няма и в двата случая.
 * Тоест дефектът НЕ Е в сметките, а в мълчанието им, и цената му е тихо недоплащане.
 *
 * <p><b>⚠️ И ЗАЩО ЕДИН КЛАС ЗА ЧЕТИРИ РЕЧНИКА НА СЪСТОЯНИЯ.</b> {@link BgEarningsOutcome} има пет
 * изхода, {@link BgPaySlipLineOutcome} — четири, {@link BgSickLeaveOutcome} — четири, а {@code
 * BgUnpaidLeaveHealthResolver.Outcome} — три, и това е вярно: всеки носи въпроси, които са само
 * негови. Сливането им в общ речник щеше да е загуба. Тук обаче въпросът е ЕДИН и е на човека пред
 * екрана: „защо това перо го няма и кой трябва да поправи нещо“. Затова четирите се привеждат към
 * ЕДИН РЕД ТЕКСТ, а не към едно състояние — състоянието остава при своя генератор, преводът е само
 * на изхода.
 *
 * <p><b>⚠️ ОТКАЗЪТ Е ЕДИНСТВЕНИЯТ С УДИВИТЕЛНА.</b> При шейсет и няколко присъди на фиш окото търси
 * отказите, а не чете подред. Същият избор като {@code BgApp9Outcome#marker}.
 *
 * <p><b>⚠️ ТУК НЕ СЕ СМЯТА НИЩО.</b> Класът не пипа суми, не закръгля и не решава дали перо да има
 * — той само ПРЕВЕЖДА вече взето решение в изречение. Парите на фиша не минават оттук.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата.
 */
public final class BgPaySlipVerdict {

  /** Маркерът на отказа — единственият, който иска действие СЕГА. */
  public static final String MARK_REFUSED = "[! ОТКАЗ]";

  /** Записано перо. */
  public static final String MARK_COMPUTED = "[начислено]";

  /** Записано перо, чийто източник спори с речника — виж {@code BgPaySlipLineOutcome#DIVERGENT}. */
  public static final String MARK_DIVERGENT = "[~ РАЗМИНАВАНЕ]";

  /** Нула по сметка или по закон. Ред няма и никой не поправя нищо. */
  public static final String MARK_LEGAL_ZERO = "[законна нула]";

  /** Правото го няма — НСОРЗ чл. 12 ал. 1 при неТПО. Различно от „дължи се, но е нула“. */
  public static final String MARK_NOT_ENTITLED = "[не се дължи]";

  /** Перо на друг генератор — ръчно въведено или производно. Нормално състояние. */
  public static final String MARK_OTHER_ENGINE = "[друг генератор]";

  /** Правилото е известно, входът му няма източник в модела. Адресат: WS-5, не операторът. */
  public static final String MARK_SOURCE_MISSING = "[няма източник · WS-5]";

  /** Сметнато е и отговорът е нула дни за сметка на осигурителя. */
  public static final String MARK_NO_EMPLOYER_DAYS = "[няма дни за осигурителя]";

  /** Хипотезата не се отнася за този фиш — и това е отговор, не липса. */
  public static final String MARK_NOT_APPLICABLE = "[не се отнася]";

  /** Кой генератор е дал присъдата — „Начисления“, „Болничен“… Никога празно. */
  public final String step;

  /** Стабилният код на типа перо, или името на източника, когато тип още не е намерен. */
  public final String lineCode;

  /** Маркерът пред реда. Един от константите по-горе. */
  public final String marker;

  /**
   * Иска ли тази присъда някой да поправи нещо СЕГА.
   *
   * <p>⚠️ Признак, а не сравнение с {@link #marker} по низ: маркерът е ТЕКСТ за човек и има право
   * да се промени, без това да мести кое е отказ.
   */
  public final boolean refusal;

  /** Сумата — само при записано перо. {@code null} инак, и това е нарочно. */
  public final BigDecimal amount;

  /** ЦЯЛО ИЗРЕЧЕНИЕ за човек: какво липсва и къде се поправя. Празно при сметнато перо. */
  public final String detail;

  private BgPaySlipVerdict(
      String step,
      String lineCode,
      String marker,
      boolean refusal,
      BigDecimal amount,
      String detail) {
    this.step = step;
    this.lineCode = lineCode;
    this.marker = marker;
    this.refusal = refusal;
    this.amount = amount;
    this.detail = detail;
  }

  /** Присъда, сглобена на ръка — за случаите без {@code Outcome} и за свидетелите. */
  public static BgPaySlipVerdict of(
      String step,
      String lineCode,
      String marker,
      boolean refusal,
      BigDecimal amount,
      String detail) {
    return new BgPaySlipVerdict(step, lineCode, marker, refusal, amount, detail);
  }

  // ═══ ЧЕТИРИТЕ ПРЕВОДА ═══════════════════════════════════════════════════
  // ⚠️ Претоварени по ТИП, а не по име: списъците се изтриват до List при компилация, но единичният
  // Outcome не се изтрива. Един метод с instanceof по средата би сработил грешно точно когато някой
  // добави пети речник на състояния.

  /** НАЧИСЛЕНИЯТА (група 1xx) — пет изхода. */
  public static BgPaySlipVerdict of(String step, BgEarningsOutcome outcome) {
    switch (outcome.status) {
      case COMPUTED:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_COMPUTED, false, outcome.amount, null);
      case SOURCE_MISSING:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_SOURCE_MISSING, false, null, outcome.detail);
      case NOT_THIS_ENGINE:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_OTHER_ENGINE, false, null, outcome.detail);
      case NOT_ENTITLED:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_NOT_ENTITLED, false, null, outcome.detail);
      default:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_REFUSED, true, null, outcome.detail);
    }
  }

  /** ОБЕЗЩЕТЕНИЯТА (9xx), ОСИГУРОВКИТЕ (5xx) и ДАНЪКЪТ (7xx) — общият им речник. */
  public static BgPaySlipVerdict of(String step, BgPaySlipLineOutcome outcome) {
    switch (outcome.status) {
      case COMPUTED:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_COMPUTED, false, outcome.amount, null);
      case DIVERGENT:
        // ⚠️ НЕ Е ОТКАЗ, и това е нарочно: перото СЕ ЗАПИСВА по речника, разминаването се обявява.
        // Прочетено като отказ, то би пратило човека да поправя фиш, който е верен.
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_DIVERGENT, false, outcome.amount, outcome.detail);
      case LEGAL_ZERO:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_LEGAL_ZERO, false, null, outcome.detail);
      default:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_REFUSED, true, null, outcome.detail);
    }
  }

  /**
   * ВЪЗНАГРАЖДЕНИЕТО ЗА ПЛАТЕН ОТПУСК по КТ чл. 177 — три изхода.
   *
   * <p>⚠️ {@code NOT_APPLICABLE} се превежда като „друг генератор“, а НЕ като „законна нула“: месец
   * без отпуск по чл. 177 няма нула лева отпуск — той няма отпуск. Обявено като нула, перото щеше
   * да твърди, че правото съществува и този месец е излязло нищо.
   */
  public static BgPaySlipVerdict of(String step, BgArt177Outcome outcome) {
    switch (outcome.status) {
      case COMPUTED:
        return new BgPaySlipVerdict(
            step,
            BgArt177LeaveService.LINE_ART177_LEAVE,
            MARK_COMPUTED,
            false,
            outcome.amount,
            null);
      case NOT_APPLICABLE:
        return new BgPaySlipVerdict(
            step,
            BgArt177LeaveService.LINE_ART177_LEAVE,
            MARK_OTHER_ENGINE,
            false,
            null,
            outcome.detail);
      default:
        return new BgPaySlipVerdict(
            step, BgArt177LeaveService.LINE_ART177_LEAVE, MARK_REFUSED, true, null, outcome.detail);
    }
  }

  /** БОЛНИЧНИЯТ по чл. 40 ал. 5 КСО — четири изхода. */
  public static BgPaySlipVerdict of(String step, BgSickLeaveOutcome outcome) {
    switch (outcome.status) {
      case COMPUTED:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_COMPUTED, false, outcome.amount, null);
      case NO_EMPLOYER_DAYS:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_NO_EMPLOYER_DAYS, false, null, outcome.detail);
      case SOURCE_MISSING:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_SOURCE_MISSING, false, null, outcome.detail);
      default:
        return new BgPaySlipVerdict(
            step, outcome.lineCode, MARK_REFUSED, true, null, outcome.detail);
    }
  }

  /**
   * ЗДРАВНАТА ВНОСКА ПРИ НЕПЛАТЕН ОТПУСК — ЗЗО чл. 40 ал. 1 т. 1 б. „б“.
   *
   * <p>⚠️ Кодът на перото се ПОДАВА, а не се вади от отговора: този резолвер не носи код — той знае
   * една хипотеза и типът ѝ се намира по двойката фонд + знак чак в записа.
   */
  public static BgPaySlipVerdict of(
      String step, String lineCode, BgUnpaidLeaveHealthResolver.Outcome outcome) {
    switch (outcome.status) {
      case COMPUTED:
        return new BgPaySlipVerdict(step, lineCode, MARK_COMPUTED, false, outcome.amount, null);
      case NOT_APPLICABLE:
        return new BgPaySlipVerdict(
            step, lineCode, MARK_NOT_APPLICABLE, false, null, outcome.detail);
      default:
        return new BgPaySlipVerdict(step, lineCode, MARK_REFUSED, true, null, outcome.detail);
    }
  }

  /**
   * РЕДЪТ, КОЙТО ВЛИЗА В ПОЛЕТО — това е форматът, който операторът чете.
   *
   * <p>Води с маркера, не със стъпката: при шейсет реда окото търси отказите.
   */
  public String asNote() {
    StringBuilder sb = new StringBuilder();
    sb.append(marker).append(' ').append(step).append(" · ").append(lineCode);
    if (amount != null) {
      sb.append(" · ").append(amount);
    }
    if (detail != null && !detail.isEmpty()) {
      sb.append(" — ").append(detail);
    }
    return sb.toString();
  }

  /**
   * РЕДЪТ В СПИСЪКА „КАКВО ЛИПСВА“ — само за отказите.
   *
   * <p>⚠️ БЕЗ маркера: в този списък всеки ред е отказ, тоест маркерът не различава нищо и само
   * измества причината надясно.
   */
  public String asGap() {
    StringBuilder sb = new StringBuilder();
    sb.append("• ").append(step).append(" · ").append(lineCode);
    if (detail != null && !detail.isEmpty()) {
      sb.append(" — ").append(detail);
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return asNote();
  }
}
