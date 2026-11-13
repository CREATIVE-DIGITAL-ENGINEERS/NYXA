package com.axelor.apps.bglocalization.service.noi;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * СРОКЪТ ЗА ПРИЛОЖЕНИЕ № 9 — ЕДИНСТВЕНОТО МЯСТО, КЪДЕТО СЕ СМЯТА. Чиста функция: без база, без
 * Guice, без днешна дата.
 *
 * <p><b>Правна основа</b> (НПОПДОО, ред. ДВ бр. 30/2024):
 *
 * <ul>
 *   <li><b>чл. 12 т. 1</b> — осигурителят подава удостоверението „до 10-о число на месеца, следващ
 *       месеца, в който лицето е представило документите при осигурителя“ ({@link
 *       Kind#PRESENTATION}, от {@code BgSickLeave.bgEmployerReceivedDate});
 *   <li><b>чл. 12 т. 2</b> — за съдружниците и собствениците на ЕООД, осигурени чрез дружеството:
 *       до 10-о число на месеца, следващ месеца на ИЗДАВАНЕ на болничния лист ({@link Kind#ISSUE},
 *       от {@code BgSickLeave.bgIssueDate});
 *   <li><b>чл. 8 ал. 2</b> — болничен, обжалван от осигурителя по чл. 112 от Закона за здравето:
 *       удостоверението се подава след приключване на обжалването ({@link Kind#APPEAL_CLOSED}, от
 *       датата на приключването).
 * </ul>
 *
 * <p><b>⚠️ НЕПРИСЪСТВЕН ДЕН — ВАРИАНТ Б.</b> Ако 10-о число е събота, неделя или официален празник,
 * срокът се мести на ПЪРВИЯ СЛЕДВАЩ РАБОТЕН ДЕН по производствения календар ({@code
 * BgWorkingDayService} — единственият източник на празниците). НПОПДОО няма собствено правило.
 * Годината, която календарът не познава, НЕ дава тихо 10-о число, а видимо „ненадежден срок“ —
 * {@link Result#warning}.
 *
 * <p><b>⚠️ ЛЕНТИТЕ СЕ СМЯТАТ СПРЯМО ДЕЙСТВИТЕЛНИЯ (ПРЕМЕСТЕНИЯ) СРОК</b> и пак тук: {@link
 * Result#dueSoonFrom} е денят, от който до срока остават {@value #DUE_SOON_WORKING_DAYS} или
 * по-малко работни дни, броени СЛЕД днешния ден до деня на срока включително. Гридът и броячът само
 * сравняват днешната дата с двете дати — второ броене на работни дни няма.
 */
public final class BgApp9Deadline {

  /** „До 3 работни дни“ — лентата преди срока. */
  public static final int DUE_SOON_WORKING_DAYS = 3;

  /**
   * Колко календарни дни около законовата дата трябват на изчислението. Напред — за преместването
   * (най-дългата поредица неприсъствени дни у нас е Великден + изместен празник, под седмица),
   * назад — за трите работни дни. С резерв.
   */
  static final int CALENDAR_WINDOW_DAYS = 21;

  /** ОТ КОЯ ДАТА тече срокът — изборът е на {@code BgApp9Obligation}, тук само се смята. */
  public enum Kind {
    /** НПОПДОО чл. 12 т. 1 — от представянето на документите при осигурителя. */
    PRESENTATION,
    /**
     * НПОПДОО чл. 12 т. 2 — от издаването на болничния лист (съдружници/собственици на ЕООД,
     * осигурени чрез дружеството).
     */
    ISSUE,
    /** НПОПДОО чл. 8 ал. 2 — от приключването на обжалването по чл. 112 ЗЗдр. */
    APPEAL_CLOSED
  }

  /** Къде е днешният ден спрямо срока. */
  public enum Band {
    OVERDUE,
    DUE_TODAY,
    DUE_SOON,
    ON_TIME
  }

  private BgApp9Deadline() {}

  /** Резултатът: действителният срок, началото на лентата „до 3 работни дни“ и надеждността. */
  public static final class Result {
    public final Kind kind;
    public final LocalDate anchor;
    public final LocalDate legalDate;
    public final LocalDate dueDate;
    public final LocalDate dueSoonFrom;

    /** {@code null} = надежден; иначе „ненадежден срок (2028 не е заредена)“. */
    public final String warning;

    Result(
        Kind kind,
        LocalDate anchor,
        LocalDate legalDate,
        LocalDate dueDate,
        LocalDate dueSoonFrom,
        String warning) {
      this.kind = kind;
      this.anchor = anchor;
      this.legalDate = legalDate;
      this.dueDate = dueDate;
      this.dueSoonFrom = dueSoonFrom;
      this.warning = warning;
    }

    public boolean isReliable() {
      return warning == null;
    }
  }

  /** 10-о число на месеца, СЛЕДВАЩ месеца на котвата. Безопасно и за 31-о число. */
  public static LocalDate legalDate(LocalDate anchor) {
    return anchor.plusMonths(1).withDayOfMonth(10);
  }

  /** Първият ден, чиито празници трябват на {@link #of}. */
  public static LocalDate calendarFrom(LocalDate anchor) {
    return legalDate(anchor).minusDays(CALENDAR_WINDOW_DAYS);
  }

  /** Последният ден, чиито празници трябват на {@link #of}. */
  public static LocalDate calendarTo(LocalDate anchor) {
    return legalDate(anchor).plusDays(CALENDAR_WINDOW_DAYS);
  }

  /**
   * СРОКЪТ.
   *
   * @param kind от коя дата тече — решено от {@code BgApp9Obligation}
   * @param anchor самата дата (представяне, издаване или приключване на обжалването)
   * @param holidays официалните празници поне в [{@link #calendarFrom}, {@link #calendarTo}]
   * @param coveredYears годините, за които календарът е ЗАРЕДЕН; ден от друга година прави срока
   *     ненадежден, защото празниците му са неизвестни, а не липсващи
   */
  public static Result of(
      Kind kind, LocalDate anchor, Set<LocalDate> holidays, Set<Integer> coveredYears) {
    if (anchor == null) {
      throw new IllegalArgumentException(
          "Срокът по Прил. 9 няма от какво да тече: датата е празна.");
    }
    Set<LocalDate> nonWorking = holidays == null ? Collections.<LocalDate>emptySet() : holidays;
    LocalDate legal = legalDate(anchor);

    // ВАРИАНТ Б: напред до първия работен ден.
    LocalDate due = legal;
    while (!isWorkingDay(due, nonWorking)) {
      due = due.plusDays(1);
    }

    // Лентата: четвъртият работен ден назад от срока, броен ВКЛЮЧИТЕЛНО — от него до срока остават
    // точно три работни дни СЛЕД него. Денят преди него вече вижда четири.
    LocalDate soon = due;
    int working = 0;
    for (LocalDate d = due; ; d = d.minusDays(1)) {
      if (isWorkingDay(d, nonWorking)) {
        working++;
        if (working == DUE_SOON_WORKING_DAYS + 1) {
          soon = d;
          break;
        }
      }
    }

    LocalDate firstExamined = soon.isBefore(legal) ? soon : legal;
    return new Result(kind, anchor, legal, due, soon, warningFor(firstExamined, due, coveredYears));
  }

  /**
   * ЛЕНТАТА за днешния ден — само сравнения с двете дати на {@link Result}.
   *
   * <p>⚠️ „Днес е срокът“ е СВОЯ лента, отделна от „просрочено“: в деня на срока подаването още е
   * навреме. А петък преди срок, паднал в събота (възможно само при ненадежден календар), е „до 3
   * работни дни“, не „днес“.
   */
  public static Band band(LocalDate today, LocalDate dueDate, LocalDate dueSoonFrom) {
    if (today.isAfter(dueDate)) {
      return Band.OVERDUE;
    }
    if (today.isEqual(dueDate)) {
      return Band.DUE_TODAY;
    }
    if (dueSoonFrom != null && !today.isBefore(dueSoonFrom)) {
      return Band.DUE_SOON;
    }
    return Band.ON_TIME;
  }

  /** Работен ден = делничен и не официален празник. Отработени съботи календарът не носи. */
  static boolean isWorkingDay(LocalDate day, Set<LocalDate> holidays) {
    DayOfWeek dow = day.getDayOfWeek();
    return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
  }

  /** „ненадежден срок (2028 не е заредена)“ — или {@code null}, ако всички прегледани години са. */
  static String warningFor(LocalDate from, LocalDate to, Set<Integer> coveredYears) {
    Set<Integer> covered = coveredYears == null ? Collections.<Integer>emptySet() : coveredYears;
    Set<Integer> missing = new TreeSet<>();
    for (int y = from.getYear(); y <= to.getYear(); y++) {
      if (!covered.contains(y)) {
        missing.add(y);
      }
    }
    if (missing.isEmpty()) {
      return null;
    }
    List<String> years = new ArrayList<>();
    for (Integer y : missing) {
      years.add(String.valueOf(y));
    }
    return missing.size() == 1
        ? "ненадежден срок (" + years.get(0) + " не е заредена)"
        : "ненадежден срок (" + String.join(" и ", years) + " не са заредени)";
  }
}
