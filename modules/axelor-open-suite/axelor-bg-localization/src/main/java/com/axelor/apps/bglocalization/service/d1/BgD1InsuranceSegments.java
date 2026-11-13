package com.axelor.apps.bglocalization.service.d1;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Т. 14 / Т. 15 — <b>отрязъците, В КОИТО лицето Е било осигурено през месеца</b>. Чиста аритметика
 * върху дати: без база, без Guice.
 *
 * <p><b>КОЕ ПРЕКЪСВА ОСИГУРЯВАНЕТО — КСО ЧЛ. 10 АЛ. 2, ДОСЛОВНО:</b> „Осигуряването се прекъсва
 * през периодите, които <b>не се зачитат за осигурителен стаж</b>, независимо че дейността по чл. 4
 * или чл. 4а, ал. 1 <b>не е прекратена</b>.“
 *
 * <p>Това е разпоредбата, която прави позиции 2–5 на т. 14/15 ОПРЕДЕЛИМИ, а до този клон те стояха
 * като „няма носител в модела“. Носителят е имало през цялото време и е на едно място: {@code
 * BgAbsenceType.countsAsInsuranceService}. Пет вида отсъствие в речника носят {@code false} —
 * неплатеният отпуск над 30 работни дни (по желание и по производствена необходимост),
 * неприсъствието, самоотлъчката и непредставеният болничен — и точно те са периодите по ал. 2.
 *
 * <p><b>⚠️ И ЗАЩО НЕ ВСЯКО ОТСЪСТВИЕ.</b> Болничният, платеният отпуск, отпускът за дете и
 * неплатеният отпуск ДО 30 работни дни СЕ ЗАЧИТАТ за осигурителен стаж (чл. 9 ал. 2), тоест НЕ
 * прекъсват осигуряването — те си имат кофи в т. 16 и се броят там. Прочетено като „отсъствието
 * прекъсва осигуряването“, правилото щеше да нареже месеца на всеки болничен и да произведе до пет
 * двойки за напълно обикновен фиш.
 *
 * <p><b>⚠️ ОТРЯЗЪЦИ, А НЕ „ДВОЙКИ ОТСЪСТВИЯ“, и това е самата подредба на декларацията.</b> Т. 15 е
 * ПОСЛЕДНИЯТ ден В ОСИГУРЯВАНЕ преди прекъсването, а т. 14 на СЛЕДВАЩАТА позиция е денят на
 * ВЪЗОБНОВЯВАНЕТО. Тоест двойките описват границите на осигуряването, не интервалите на отсъствието
 * — и границите се получават като допълнение на прекъсванията, не като самите тях. Написано като
 * „т. 14 = началото на отсъствието“, полето би сочило точно ден, в който лицето НЕ е осигурено.
 *
 * <p><b>⚠️ КАЛЕНДАРНИ ДНИ, не работни.</b> Указанието казва „първият КАЛЕНДАРЕН ден в осигуряване“
 * и „последният КАЛЕНДАРЕН ден“. Тоест производственият календар тук няма работа: прекъсване от
 * петък до понеделник дава т. 15 = четвъртък и т. 14 = вторник, независимо че събота и неделя не са
 * работни.
 */
public final class BgD1InsuranceSegments {

  /** Полета 9–18 на файла са ПЕТ двойки. Броят е ЗАКОВАН от формата, не е отворен списък. */
  public static final int MAX_PAIRS = 5;

  private BgD1InsuranceSegments() {}

  /** Един отрязък в осигуряване — включително двата края. */
  public static final class Segment {

    private final LocalDate from;
    private final LocalDate to;

    Segment(LocalDate from, LocalDate to) {
      this.from = from;
      this.to = to;
    }

    public LocalDate getFrom() {
      return from;
    }

    public LocalDate getTo() {
      return to;
    }

    @Override
    public String toString() {
      return from + "–" + to;
    }
  }

  /** Какво излезе: отрязъците и дали формата може да ги носи всичките. */
  public static final class Outcome {

    private final List<Segment> segments;
    private final int total;

    Outcome(List<Segment> segments, int total) {
      this.segments = segments;
      this.total = total;
    }

    /** Отрязъците, ОРЯЗАНИ до петте позиции на формата. */
    public List<Segment> getSegments() {
      return segments;
    }

    /**
     * Колко отрязъка е имало НАИСТИНА — може да е повече от петте, които форматът носи.
     *
     * <p>⚠️ Пази се отделно, защото орязването е ЗАГУБА НА ДАННИ и трябва да се обяви. Броят на
     * върнатите отрязъци не може да го каже: пет върнати значат и „точно пет“, и „седем, от които
     * две изчезнаха“.
     */
    public int getTotal() {
      return total;
    }

    /** Изгубени ли са отрязъци заради петте позиции на формата. */
    public boolean isTruncated() {
      return total > segments.size();
    }
  }

  /**
   * Отрязъците в осигуряване за един месец.
   *
   * @param periodFrom първи ден на периода на данните
   * @param periodTo последен ден на периода на данните
   * @param insuredFrom денят, от който осигуряването възниква (началото на договора), или {@code
   *     null}. Извън периода се пренебрегва — тогава лицето е осигурено от началото на месеца
   * @param insuredTo денят на ДЕЙСТВИТЕЛНОТО прекратяване, или {@code null}. ⚠️ НЕ уговореният край
   *     на срочен договор: той се попълва при подписването и може да стои в бъдещето
   * @param interruptions интервалите, които НЕ се зачитат за осигурителен стаж (КСО чл. 10 ал. 2),
   *     всеки като {@code {от, до}}; редът и припокриването им не значат нищо — нормализират се тук
   */
  public static Outcome of(
      LocalDate periodFrom,
      LocalDate periodTo,
      LocalDate insuredFrom,
      LocalDate insuredTo,
      List<LocalDate[]> interruptions) {

    if (periodFrom == null || periodTo == null || periodTo.isBefore(periodFrom)) {
      return new Outcome(Collections.emptyList(), 0);
    }

    LocalDate start = inPeriod(insuredFrom, periodFrom, periodTo) ? insuredFrom : periodFrom;
    LocalDate end = inPeriod(insuredTo, periodFrom, periodTo) ? insuredTo : periodTo;
    if (end.isBefore(start)) {
      // Прекратен ПРЕДИ да е започнал в рамките на месеца — противоречие в данните на договора.
      // Тук то не се лекува: отрязък няма, а противоречието се вижда от липсата.
      return new Outcome(Collections.emptyList(), 0);
    }

    List<LocalDate[]> breaks = normalize(interruptions, start, end);

    List<Segment> all = new ArrayList<>();
    LocalDate cursor = start;
    for (LocalDate[] gap : breaks) {
      if (gap[0].isAfter(cursor)) {
        all.add(new Segment(cursor, gap[0].minusDays(1)));
      }
      LocalDate after = gap[1].plusDays(1);
      cursor = after.isAfter(cursor) ? after : cursor;
      if (cursor.isAfter(end)) {
        break;
      }
    }
    if (!cursor.isAfter(end)) {
      all.add(new Segment(cursor, end));
    }

    // ⚠️ ОРЯЗВА СЕ НАКРАЯ, а не докато се строят: изгубеният брой трябва да е известен, за да се
    // обяви. Спряно на петия отрязък, броенето не може да каже колко са били.
    List<Segment> kept = all.size() <= MAX_PAIRS ? all : new ArrayList<>(all.subList(0, MAX_PAIRS));
    return new Outcome(Collections.unmodifiableList(kept), all.size());
  }

  /**
   * Прекъсванията, орязани до отрязъка, подредени и СЛЕТИ.
   *
   * <p><b>⚠️ СЛИВАНЕТО НЕ Е ОПТИМИЗАЦИЯ.</b> Две припокриващи се заявки (или две ДОПИРАЩИ СЕ —
   * неплатен отпуск до 15-о и самоотлъчка от 16-о) са ЕДНО прекъсване на осигуряването. Броени
   * поотделно, те дават празен отрязък между себе си и с това ИЗМИСЛЯТ двойка т. 14/15, в която
   * лицето е осигурено нула дни. При седем такива заявки записът щеше да изразходва петте позиции
   * на формата с нищо.
   */
  static List<LocalDate[]> normalize(
      List<LocalDate[]> interruptions, LocalDate start, LocalDate end) {
    List<LocalDate[]> clipped = new ArrayList<>();
    if (interruptions != null) {
      for (LocalDate[] gap : interruptions) {
        if (gap == null || gap.length < 2 || gap[0] == null || gap[1] == null) {
          continue;
        }
        LocalDate from = gap[0].isBefore(start) ? start : gap[0];
        LocalDate to = gap[1].isAfter(end) ? end : gap[1];
        if (!to.isBefore(from)) {
          clipped.add(new LocalDate[] {from, to});
        }
      }
    }
    clipped.sort((a, b) -> a[0].compareTo(b[0]));

    List<LocalDate[]> merged = new ArrayList<>();
    for (LocalDate[] gap : clipped) {
      if (merged.isEmpty()) {
        merged.add(gap);
        continue;
      }
      LocalDate[] last = merged.get(merged.size() - 1);
      // Допиращи се (краят + 1 ден = началото) също се сливат — виж javadoc-а.
      if (!gap[0].isAfter(last[1].plusDays(1))) {
        if (gap[1].isAfter(last[1])) {
          last[1] = gap[1];
        }
      } else {
        merged.add(gap);
      }
    }
    return merged;
  }

  private static boolean inPeriod(LocalDate date, LocalDate from, LocalDate to) {
    return date != null && !date.isBefore(from) && !date.isAfter(to);
  }
}
