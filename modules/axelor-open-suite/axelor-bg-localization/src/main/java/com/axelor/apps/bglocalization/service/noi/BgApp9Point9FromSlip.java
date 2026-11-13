package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.bglocalization.service.payroll.BgSickLeavePeriodSlice;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Т. 9 ЗА ЗАВАРЕН ЛИСТ — ИЗВЕДЕНА ОТ ЗАМРАЗЕНИТЕ ДАННИ НА ПОТВЪРДЕНИЯ ФИШ. Чиста функция, без база:
 * шевът към нея е {@link BgApp9Point9FromSlipService}.
 *
 * <p><b>Защо изобщо.</b> Заварените листове може да имат потвърден фиш — често коригиращо копие —
 * но празна т. 9 и затова да не могат да влязат в Прил. 9. Пресмятането наново не е изход:
 * потвърденият фиш е затворен.
 *
 * <p><b>⚠️ ДНИТЕ СА ЗАМРАЗЕНИТЕ НА ФИША, НЕ СМЕТНАТИ НАНОВО.</b> {@code bgSickDaysEmployer} и
 * {@code bgSickDaysDoo} са числата, по които е платено и декларирано. Ново разделяне от живите
 * данни (календар, речник, епизоди) може да даде друго число — и т. 9 пред НОИ щеше да се разминава
 * с платеното и с Д1.
 *
 * <p><b>⚠️ ГРАНИЦИТЕ СА НА ЛИСТА В МЕСЕЦА, ЗА ДВАТА РЕДА.</b> Фишът не пази в кои дни е платил
 * осигурителят и в кои ДОО — само колко. Т. 9 не дели по платец (виж {@link BgApp9Point9}): тя носи
 * първия и последния ден на листа в месеца и СБОРА на дните. Затова и двата реда носят границите на
 * листа в месеца; сборът и обхватът на т. 9 излизат точни, а разбивката по дни не се измисля.
 *
 * <p><b>⚠️ ОТКАЗ, А НЕ ПРЕДПОЛОЖЕНИЕ</b>, когато фишът не може да се отнесе еднозначно към листа:
 *
 * <ul>
 *   <li>месец без потвърден фиш (нито потвърдено коригиращо копие);
 *   <li>друг неотказан лист в същия месец (и в чернова) — дните на фиша са общи;
 *   <li>фиш с нула болнични дни — сметнат е без листа;
 *   <li>повече дни на фиша, отколкото календарни дни на листа в месеца — дните не са от него.
 * </ul>
 *
 * Един отказ спира ЦЯЛОТО действие: половин т. 9 е по-лоша от празна.
 */
public final class BgApp9Point9FromSlip {

  private BgApp9Point9FromSlip() {}

  /** Каквото шевът е прочел за ЕДИН месец на листа. */
  public static final class MonthSource {
    final String slipReference;
    final boolean correction;
    final int employerDays;
    final int dooDays;
    final BigDecimal hoursPerDay;
    final String workTimeMode;
    final List<String> otherSheets;

    /**
     * @param slipReference номерът на потвърдения фиш (FISH-…)
     * @param correction коригиращо копие ли е
     * @param employerDays замразеното {@code bgSickDaysEmployer}
     * @param dooDays замразеното {@code bgSickDaysDoo}
     * @param hoursPerDay замразеното {@code bgRegimeHoursPerDay}; нула/празно = няма норма
     * @param workTimeMode замразеното {@code bgRegimeWorkTimeMode}
     * @param otherSheets другите неотказани листове на договора в месеца — номерата им
     */
    public MonthSource(
        String slipReference,
        boolean correction,
        int employerDays,
        int dooDays,
        BigDecimal hoursPerDay,
        String workTimeMode,
        List<String> otherSheets) {
      this.slipReference = slipReference;
      this.correction = correction;
      this.employerDays = employerDays;
      this.dooDays = dooDays;
      this.hoursPerDay = hoursPerDay;
      this.workTimeMode = workTimeMode;
      this.otherSheets =
          otherSheets == null ? Collections.<String>emptyList() : new ArrayList<>(otherSheets);
    }
  }

  /** Един ред на т. 9 — лист × месец × платец. */
  public static final class Slice {
    public final LocalDate fromDay;
    public final LocalDate toDay;
    public final int workDays;
    public final String payer;
    public final BigDecimal dailyHours;
    public final BigDecimal workHours;

    Slice(
        LocalDate fromDay,
        LocalDate toDay,
        int workDays,
        String payer,
        BigDecimal dailyHours,
        BigDecimal workHours) {
      this.fromDay = fromDay;
      this.toDay = toDay;
      this.workDays = workDays;
      this.payer = payer;
      this.dailyHours = dailyHours;
      this.workHours = workHours;
    }

    @Override
    public String toString() {
      return String.format(
          "%02d/%d %s %d–%d · %d дни",
          fromDay.getMonthValue(),
          fromDay.getYear(),
          payer,
          fromDay.getDayOfMonth(),
          toDay.getDayOfMonth(),
          workDays);
    }
  }

  /** Резултатът: ИЛИ откази, ИЛИ редове с одитна следа. Никога и двете. */
  public static final class Plan {
    public final List<String> refusals;
    public final List<Slice> slices;
    public final String audit;

    Plan(List<String> refusals, List<Slice> slices, String audit) {
      this.refusals = Collections.unmodifiableList(refusals);
      this.slices = Collections.unmodifiableList(slices);
      this.audit = audit;
    }

    public boolean isRefused() {
      return !refusals.isEmpty();
    }

    static Plan refused(List<String> refusals) {
      return new Plan(refusals, new ArrayList<Slice>(), null);
    }
  }

  /** Календарните месеци, които листът засяга — първите им дни, хронологично. */
  public static List<LocalDate> monthsOf(LocalDate from, LocalDate to) {
    List<LocalDate> out = new ArrayList<>();
    if (from == null || to == null || to.isBefore(from)) {
      return out;
    }
    for (LocalDate m = from.withDayOfMonth(1); !m.isAfter(to); m = m.plusMonths(1)) {
      out.add(m);
    }
    return out;
  }

  /**
   * Редовете на т. 9 за листа {@code from–to} от потвърдените фишове по месеци.
   *
   * @param sources първият ден на месеца → прочетеното; липсващ ключ = няма потвърден фиш
   */
  public static Plan plan(LocalDate from, LocalDate to, Map<LocalDate, MonthSource> sources) {
    List<String> refusals = new ArrayList<>();
    List<Slice> slices = new ArrayList<>();
    StringBuilder audit = new StringBuilder();

    List<LocalDate> months = monthsOf(from, to);
    if (months.isEmpty()) {
      refusals.add("листът няма валиден период (от–до) — няма месеци, за които да се изведе т. 9.");
      return Plan.refused(refusals);
    }

    for (LocalDate month : months) {
      String mm = label(month);
      MonthSource source = sources == null ? null : sources.get(month);
      if (source == null) {
        refusals.add(
            mm
                + " — няма потвърден фиш (нито потвърдено коригиращо копие) за договора на листа."
                + " Потвърдете фиша за месеца и опитайте отново.");
        continue;
      }
      if (!source.otherSheets.isEmpty()) {
        refusals.add(
            mm
                + " — в месеца има и друг неотказан болничен лист ("
                + String.join(", ", source.otherSheets)
                + "). Дните на фиша „"
                + source.slipReference
                + "“ са общи за месеца и не могат да се отнесат еднозначно към този лист.");
        continue;
      }
      int total = source.employerDays + source.dooDays;
      if (total <= 0) {
        refusals.add(
            mm
                + " — потвърденият фиш „"
                + source.slipReference
                + "“ няма болнични дни (сметнат е без този лист). Отразете листа с коригиращ фиш"
                + " и го потвърдете.");
        continue;
      }
      LocalDate first = from.isAfter(month) ? from : month;
      LocalDate monthEnd = month.withDayOfMonth(month.lengthOfMonth());
      LocalDate last = to.isBefore(monthEnd) ? to : monthEnd;
      long calendarDays = ChronoUnit.DAYS.between(first, last) + 1;
      if (total > calendarDays) {
        refusals.add(
            mm
                + " — фишът „"
                + source.slipReference
                + "“ носи "
                + total
                + " болнични дни, а листът покрива само "
                + calendarDays
                + " календарни дни в месеца — дните на фиша не са от този лист.");
        continue;
      }

      BigDecimal daily =
          source.hoursPerDay != null && source.hoursPerDay.signum() > 0 ? source.hoursPerDay : null;
      boolean hourly =
          daily != null && BgSickLeaveService.WORK_TIME_MODE_DAILY.equals(source.workTimeMode);
      if (source.employerDays > 0) {
        slices.add(
            new Slice(
                first,
                last,
                source.employerDays,
                BgSickLeavePeriodSlice.PAYER_EMPLOYER,
                daily,
                hourly ? daily.multiply(BigDecimal.valueOf(source.employerDays)) : null));
      }
      if (source.dooDays > 0) {
        slices.add(
            new Slice(
                first,
                last,
                source.dooDays,
                BgSickLeavePeriodSlice.PAYER_NOI,
                daily,
                hourly ? daily.multiply(BigDecimal.valueOf(source.dooDays)) : null));
      }
      if (audit.length() > 0) {
        audit.append('\n');
      }
      audit
          .append(mm)
          .append(" ← „")
          .append(source.slipReference)
          .append("“ (")
          .append(source.correction ? "коригиращ фиш" : "фиш")
          .append(", потвърден): осигурител ")
          .append(source.employerDays)
          .append(", ДОО ")
          .append(source.dooDays);
    }

    if (!refusals.isEmpty()) {
      return Plan.refused(refusals);
    }
    return new Plan(refusals, slices, audit.toString());
  }

  static String label(LocalDate month) {
    return String.format("%02d/%d", month.getMonthValue(), month.getYear());
  }
}
