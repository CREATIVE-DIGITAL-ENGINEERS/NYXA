package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import java.time.LocalDate;

/**
 * ДЪЛЖИ ЛИ СЕ ПРИЛОЖЕНИЕ № 9 ЗА ТОЗИ ЛИСТ, И ОТ КОЯ ДАТА ТЕЧЕ СРОКЪТ — едно място, чиста функция.
 *
 * <p><b>Правна основа</b> (НПОПДОО, ред. ДВ бр. 30/2024):
 *
 * <ul>
 *   <li><b>чл. 8 ал. 1</b> — удостоверението се подава „за всеки болничен лист“; при повече от едно
 *       правоотношение при същия осигурител — отделно удостоверение за всяко. В модела всеки лист
 *       на договор е свой {@code BgSickLeave}, тоест „за всяко правоотношение“ е „за всеки ред“.
 *       Оттам се дължи и за лист ИЗЦЯЛО в първите два работни дни за сметка на осигурителя, и за
 *       всяко ПРОДЪЛЖЕНИЕ (със свой срок от своето представяне), и за вид 18 (бременност и
 *       раждане), и за видове 27/28 (без право на обезщетение — правото решава НОИ, не
 *       осигурителят);
 *   <li><b>чл. 8 ал. 2</b> — обжалван от осигурителя лист (чл. 112 ЗЗдр): след приключване на
 *       обжалването; дотогава срокът не тече;
 *   <li><b>чл. 12 т. 1</b> — срокът тече от ПРЕДСТАВЯНЕТО при осигурителя. Важи и за код 10
 *       (управител/прокурист по договор за управление): осигурител е дружеството;
 *   <li><b>чл. 12 т. 2</b> — ИЗДАВАНЕ: виж {@link BgApp9Deadline.Kind#ISSUE}.
 * </ul>
 *
 * <p><b>⚠️ ИЗДАВАНЕ (чл. 12 т. 2) — чака носител за болничен на самоосигуряващ се; отделна
 * задача.</b> Днес правилото избира САМО {@link BgApp9Deadline.Kind#PRESENTATION} (и приключването
 * на обжалването): {@code BgSickLeave} виси задължително на трудов договор, а самоосигуряващият се
 * собственик/съдружник няма договор, а регистрация, и пазачът {@code BgInsuredTypeHomeFileTest}
 * държи кодовете му далеч от договора. Тоест неговият лист днес не може да бъде въведен, и няма от
 * какво да се избере ИЗДАВАНЕ. Функцията за срока вече го смята.
 *
 * <p><b>Редът на правилата е част от правилото.</b> Изключващите (не е документ, не е представен,
 * история, изключено напомняне, преди границата) идват ПРЕДИ видимите групи, за да не наводнят
 * заварените листове групите „няма дата“ и „извън обхвата“.
 */
public final class BgApp9Obligation {

  /** Видът „Непредставен болничен“ (bg_absence_type.csv) — срокът тръгва чак при представянето. */
  public static final String UNPRESENTED_CODE = "sicknote_unsubmitted";

  /** Присъдата — стойностите на {@code bg.app9.obligation.select}. */
  public enum Group {
    /** Дължи се; срокът тече. */
    OWES(true),
    /** чл. 8 ал. 2 — обжалван, срокът не тече. */
    APPEALED(true),
    /** Дължи се, но т. 25 липсва — срокът няма от какво да тече. */
    NO_ANCHOR_DATE(true),
    /** Вид осигурен без т. 25 (12, 13, 96) — подава лицето, не осигурителят. */
    OUT_OF_SCOPE_SELF(true),
    /**
     * Удостоверението е ПОДАДЕНО в НОИ — задължението е изпълнено, срокът не тече. Видимо, за да
     * остане листът в „Прил. 9 за подаване“ като свършен, а не да изчезне.
     */
    SUBMITTED(true),
    /** Вид 21 „Непредставен“ — не се дължи още. */
    UNPRESENTED(false),
    /** Видът не изисква болничен лист. */
    NOT_A_SICK_NOTE(false),
    /** Черновата не е документ. */
    NOT_VALIDATED(false),
    /** Внесен от миграция. */
    HISTORICAL(false),
    /** Дружеството няма граница — напомнянето е изключено. */
    TRACKING_OFF(false),
    /** Представен (или започнал) преди границата на дружеството. */
    BEFORE_CUTOFF(false);

    /** Показва ли се листът в „Прил. 9 за подаване“. */
    public final boolean listed;

    Group(boolean listed) {
      this.listed = listed;
    }
  }

  /** Фактите за един лист — събира ги {@code BgApp9DeadlineService}, без нито едно правило. */
  public static final class Facts {
    String statusSelect;
    String absenceTypeCode;
    boolean requiresSickNote;
    String insuredTypeCode;
    LocalDate receivedDate;
    LocalDate fromDate;
    boolean appealed;
    LocalDate appealClosedDate;
    boolean historicalImport;
    LocalDate trackFrom;
    boolean submitted;

    public Facts status(String value) {
      statusSelect = value;
      return this;
    }

    public Facts absenceType(String code, boolean sickNote) {
      absenceTypeCode = code;
      requiresSickNote = sickNote;
      return this;
    }

    public Facts insuredType(String code) {
      insuredTypeCode = code;
      return this;
    }

    public Facts received(LocalDate value) {
      receivedDate = value;
      return this;
    }

    public Facts from(LocalDate value) {
      fromDate = value;
      return this;
    }

    public Facts appeal(boolean value, LocalDate closedOn) {
      appealed = value;
      appealClosedDate = closedOn;
      return this;
    }

    public Facts historical(boolean value) {
      historicalImport = value;
      return this;
    }

    public Facts trackFrom(LocalDate value) {
      trackFrom = value;
      return this;
    }

    /** Удостоверението на листа е подадено в НОИ (със или без входящ №). */
    public Facts submitted(boolean value) {
      submitted = value;
      return this;
    }
  }

  /** Присъдата: групата, а при {@link Group#OWES} — видът на срока и датата, от която тече. */
  public static final class Verdict {
    public final Group group;
    public final BgApp9Deadline.Kind kind;
    public final LocalDate anchor;

    /** Правилото, което реши — с цитата. За екрана и за свидетелите. */
    public final String rule;

    Verdict(Group group, BgApp9Deadline.Kind kind, LocalDate anchor, String rule) {
      this.group = group;
      this.kind = kind;
      this.anchor = anchor;
      this.rule = rule;
    }

    public boolean owes() {
      return group == Group.OWES;
    }

    static Verdict of(Group group, String rule) {
      return new Verdict(group, null, null, rule);
    }
  }

  private BgApp9Obligation() {}

  public static Facts facts() {
    return new Facts();
  }

  /** ПРИСЪДАТА — правилата в реда, в който се прилагат. */
  public static Verdict decide(Facts f) {
    if (!BgPayrollInputBuilder.SICK_LEAVE_VALIDATED.equals(f.statusSelect)) {
      return Verdict.of(Group.NOT_VALIDATED, "черновата е намерение, не документ");
    }
    if (!f.requiresSickNote) {
      return Verdict.of(Group.NOT_A_SICK_NOTE, "видът отсъствие не изисква болничен лист");
    }
    if (UNPRESENTED_CODE.equals(f.absenceTypeCode)) {
      return Verdict.of(
          Group.UNPRESENTED,
          "непредставен лист — срокът по чл. 12 т. 1 тръгва чак когато бъде представен");
    }
    if (f.historicalImport) {
      return Verdict.of(Group.HISTORICAL, "внесен при миграция — история, не задача");
    }
    if (f.trackFrom == null) {
      return Verdict.of(
          Group.TRACKING_OFF, "дружеството няма граница „Следи сроковете за Прил. 9 от дата“");
    }
    LocalDate cutoffDay = f.receivedDate != null ? f.receivedDate : f.fromDate;
    if (cutoffDay == null || cutoffDay.isBefore(f.trackFrom)) {
      return Verdict.of(
          Group.BEFORE_CUTOFF, "преди границата на дружеството (" + f.trackFrom + ")");
    }
    if (f.insuredTypeCode != null
        && BgApp9MandatoryPoints.WITHOUT_EMPLOYER_REGISTRY.contains(f.insuredTypeCode.trim())) {
      return Verdict.of(
          Group.OUT_OF_SCOPE_SELF,
          "вид осигурен "
              + f.insuredTypeCode.trim()
              + " — т. 25 не се попълва, удостоверението подава лицето");
    }
    // ПОДАДЕНОТО ПРЕДИ ОБЖАЛВАНЕТО И ДАТАТА: подаден лист не „дължи“ нищо, каквато и да е
    // котвата — след отметката „подадено в НОИ“ групата не е „Дължи се — срокът тече“.
    // След изключващите правила — заварен или изключен лист остава в своята група.
    if (f.submitted) {
      return Verdict.of(
          Group.SUBMITTED, "удостоверението е подадено в НОИ — задължението по чл. 8 е изпълнено");
    }
    if (f.appealed) {
      if (f.appealClosedDate == null) {
        return Verdict.of(
            Group.APPEALED, "чл. 8 ал. 2 НПОПДОО — обжалван от осигурителя, срокът не тече");
      }
      return new Verdict(
          Group.OWES,
          BgApp9Deadline.Kind.APPEAL_CLOSED,
          f.appealClosedDate,
          "чл. 8 ал. 2 НПОПДОО — срокът тече от месеца на приключване на обжалването");
    }
    if (f.receivedDate == null) {
      return Verdict.of(
          Group.NO_ANCHOR_DATE,
          "чл. 12 т. 1 НПОПДОО иска датата на представяне (т. 25), а тя липсва");
    }
    return new Verdict(
        Group.OWES,
        BgApp9Deadline.Kind.PRESENTATION,
        f.receivedDate,
        "чл. 8 ал. 1 и чл. 12 т. 1 НПОПДОО — до 10-о число на месеца след представянето");
  }
}
