package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Т. 9 ОТ ЗАМРАЗЕНИТЕ ДАННИ НА ПОТВЪРДЕНИЯ ФИШ — правилото, с числа и без база.
 *
 * <p>Котвата е 0909043: лист за три работни дни през септември 2026, отразен с коригиращ фиш — 2
 * дни за сметка на осигурителя и 1 за ДОО, а т. 9 празна.
 */
class BgApp9Point9FromSlipTest {

  private static final LocalDate SEP = LocalDate.of(2026, 9, 1);
  private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

  private static BgApp9Point9FromSlip.MonthSource correction(int employer, int doo) {
    return new BgApp9Point9FromSlip.MonthSource(
        "FISH-2026-0015", true, employer, doo, new BigDecimal("8.00"), "1", null);
  }

  private static Map<LocalDate, BgApp9Point9FromSlip.MonthSource> only(
      LocalDate month, BgApp9Point9FromSlip.MonthSource source) {
    Map<LocalDate, BgApp9Point9FromSlip.MonthSource> out = new HashMap<>();
    out.put(month, source);
    return out;
  }

  private static List<BgSickLeavePeriodLine> lines(BgApp9Point9FromSlip.Plan plan) {
    List<BgSickLeavePeriodLine> out = new ArrayList<>();
    for (BgApp9Point9FromSlip.Slice s : plan.slices) {
      BgSickLeavePeriodLine line = new BgSickLeavePeriodLine();
      line.setPeriodYear(s.fromDay.getYear());
      line.setPeriodMonth(s.fromDay.getMonthValue());
      line.setFromDay(s.fromDay);
      line.setToDay(s.toDay);
      line.setWorkDays(s.workDays);
      line.setDailyHours(s.dailyHours);
      line.setWorkHours(s.workHours);
      line.setPayer(s.payer);
      out.add(line);
    }
    return out;
  }

  @Test
  @DisplayName("0909043 + потвърден коригиращ 09/2026 → т. 9 = 09/2026, 2 дни осигурител + 1 ДОО")
  void theConfirmedCorrectionGivesTwoEmployerDaysAndOneNoiDay() {
    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(
            LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30), only(SEP, correction(2, 1)));

    assertFalse(plan.isRefused(), String.valueOf(plan.refusals));
    assertEquals(2, plan.slices.size());
    BgApp9Point9FromSlip.Slice employer = plan.slices.get(0);
    BgApp9Point9FromSlip.Slice noi = plan.slices.get(1);
    assertEquals("EMPLOYER", employer.payer);
    assertEquals(2, employer.workDays);
    assertEquals("NOI", noi.payer);
    assertEquals(1, noi.workDays);
    assertEquals(LocalDate.of(2026, 9, 28), employer.fromDay);
    assertEquals(LocalDate.of(2026, 9, 30), noi.toDay);
    assertEquals(0, new BigDecimal("16.00").compareTo(employer.workHours), "подневно: 8 × 2");
    assertEquals(
        "09/2026 ← „FISH-2026-0015“ (коригиращ фиш, потвърден): осигурител 2, ДОО 1", plan.audit);

    // Точно каквото т. 9 на файла ще носи: ЕДИН месец, сборът 3, обхватът 28–30.
    List<BgApp9Point9.Month> months = BgApp9Point9.aggregate(lines(plan), null, "01");
    assertEquals(1, months.size());
    assertEquals(9, months.get(0).month);
    assertEquals(2026, months.get(0).year);
    assertEquals(3, months.get(0).days);
    assertEquals(28, months.get(0).fromDay);
    assertEquals(30, months.get(0).toDay);
    assertEquals(Integer.valueOf(8), months.get(0).legalWorkHours);
  }

  @Test
  @DisplayName("Без потвърден фиш за месеца → отказ, който назовава месеца; нищо за запис")
  void aMonthWithoutConfirmedSlipIsRefusedByName() {
    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(
            LocalDate.of(2026, 9, 28),
            LocalDate.of(2026, 9, 30),
            Collections.<LocalDate, BgApp9Point9FromSlip.MonthSource>emptyMap());

    assertTrue(plan.isRefused());
    assertEquals(1, plan.refusals.size());
    assertTrue(
        plan.refusals.get(0).startsWith("09/2026 — няма потвърден фиш"), plan.refusals.get(0));
    assertTrue(plan.slices.isEmpty());
    assertNull(plan.audit);
  }

  @Test
  @DisplayName("Лист през два месеца, октомври без потвърден фиш → отказ само за 10/2026, и нищо")
  void onlyTheMissingMonthIsNamedAndNothingIsHalfWritten() {
    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(
            LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2), only(SEP, correction(2, 0)));

    assertTrue(plan.isRefused());
    assertEquals(1, plan.refusals.size(), String.valueOf(plan.refusals));
    assertTrue(plan.refusals.get(0).startsWith("10/2026"), plan.refusals.get(0));
    assertTrue(plan.slices.isEmpty(), "половин т. 9 е по-лоша от празна");
  }

  @Test
  @DisplayName("Два месеца, два потвърдени фиша → по месец, границите са на листа в месеца")
  void twoMonthsTwoSlips() {
    Map<LocalDate, BgApp9Point9FromSlip.MonthSource> sources = only(SEP, correction(2, 0));
    sources.put(
        OCT,
        new BgApp9Point9FromSlip.MonthSource(
            "FISH-2026-0020", false, 0, 2, new BigDecimal("8.00"), "1", null));

    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2), sources);

    assertFalse(plan.isRefused(), String.valueOf(plan.refusals));
    List<BgApp9Point9.Month> months = BgApp9Point9.aggregate(lines(plan), null, "01");
    assertEquals(2, months.size());
    assertEquals(29, months.get(0).fromDay);
    assertEquals(30, months.get(0).toDay);
    assertEquals(2, months.get(0).days);
    assertEquals(1, months.get(1).fromDay);
    assertEquals(2, months.get(1).toDay);
    assertEquals(2, months.get(1).days);
    assertTrue(plan.audit.contains("10/2026 ← „FISH-2026-0020“ (фиш, потвърден)"), plan.audit);
  }

  @Test
  @DisplayName("Друг валидиран лист в месеца → отказ: дните на фиша са общи")
  void anotherSheetInTheMonthIsRefused() {
    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(
            LocalDate.of(2026, 9, 28),
            LocalDate.of(2026, 9, 30),
            only(
                SEP,
                new BgApp9Point9FromSlip.MonthSource(
                    "FISH-2026-0015", true, 2, 1, null, "1", Arrays.asList("Е202609 0909999"))));

    assertTrue(plan.isRefused());
    assertTrue(plan.refusals.get(0).contains("Е202609 0909999"), plan.refusals.get(0));
  }

  @Test
  @DisplayName("Фиш с нула болнични дни → отказ (сметнат е без листа)")
  void aSlipWithoutSickDaysIsRefused() {
    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(
            LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30), only(SEP, correction(0, 0)));

    assertTrue(plan.isRefused());
    assertTrue(plan.refusals.get(0).contains("няма болнични дни"), plan.refusals.get(0));
  }

  @Test
  @DisplayName("Повече дни на фиша от календарните дни на листа → отказ: не са от него")
  void moreSlipDaysThanTheSheetCoversIsRefused() {
    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(
            LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30), only(SEP, correction(2, 5)));

    assertTrue(plan.isRefused());
    assertTrue(plan.refusals.get(0).contains("7 болнични дни"), plan.refusals.get(0));
    assertTrue(plan.refusals.get(0).contains("3 календарни дни"), plan.refusals.get(0));
  }

  @Test
  @DisplayName("СИРВ (не подневно) → часовете остават празни, както при пресмятането")
  void summedWorkTimeLeavesHoursEmpty() {
    BgApp9Point9FromSlip.Plan plan =
        BgApp9Point9FromSlip.plan(
            LocalDate.of(2026, 9, 28),
            LocalDate.of(2026, 9, 30),
            only(
                SEP,
                new BgApp9Point9FromSlip.MonthSource(
                    "FISH-2026-0015", true, 2, 1, new BigDecimal("8.00"), "2", null)));

    assertFalse(plan.isRefused());
    assertNull(plan.slices.get(0).workHours);
    assertEquals(0, new BigDecimal("8.00").compareTo(plan.slices.get(0).dailyHours));
  }
}
