package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictRules.Input;
import com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictRules.Verdict;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Правото към началото на неработоспособността — чистите правила, без база. */
class BgSickBenefitVerdictRulesTest {

  private static Input sick(int months, LocalDate asOf, LocalDate day) {
    Input in = new Input();
    in.absenceTypeCode = "sick_er";
    in.category = 2;
    in.incapacityStart = day;
    in.birthDate = LocalDate.of(1985, 4, 12);
    in.serviceDeclared = true;
    in.declaredMonths = months;
    in.declaredAsOf = asOf;
    return in;
  }

  @Test
  @DisplayName("стажът към D = декларираните + пълните месеци след датата им")
  void serviceAtADateIsDerivedFromTheAnchor() {
    assertEquals(
        5,
        BgSickBenefitVerdictRules.serviceMonthsAt(
            5, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 26)));
    assertEquals(
        3,
        BgSickBenefitVerdictRules.serviceMonthsAt(
            3, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 13)));
    assertEquals(
        12,
        BgSickBenefitVerdictRules.serviceMonthsAt(
            0, LocalDate.of(2025, 9, 30), LocalDate.of(2026, 10, 12)));
  }

  @Test
  @DisplayName("под прага към началото → БЕЗ право, със стажа и датата")
  void underTheThresholdAtTheStartHasNoRight() {
    Verdict v =
        BgSickBenefitVerdictRules.decide(
            sick(5, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 26)));
    assertEquals(BgSickBenefitVerdictRules.NO_RIGHT, v.verdict);
    assertEquals(5, v.serviceMonths.intValue());
    assertEquals(LocalDate.of(2026, 10, 26), v.asOf);
  }

  @Test
  @DisplayName("⚠️ начало ПРЕДИ датата на декларацията → отказ, назад стажът не се извежда")
  void aStartBeforeTheDeclarationIsRefused() {
    Verdict v =
        BgSickBenefitVerdictRules.decide(
            sick(6, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 26)));
    assertTrue(v.isRefused());
    assertTrue(v.refusal.contains("ПРЕДИ"), v.refusal);
  }

  @Test
  @DisplayName("⚠️ без дата на декларацията → отказ: число без дата е стаж „към неизвестно кога“")
  void aDeclarationWithoutADateIsRefused() {
    Verdict v = BgSickBenefitVerdictRules.decide(sick(6, null, LocalDate.of(2026, 10, 26)));
    assertTrue(v.isRefused());
    assertTrue(v.refusal.contains("КЪМ КОЯ ДАТА"), v.refusal);
  }

  @Test
  @DisplayName("⚠️ месец без удостоверено осигуряване → отказ, не „осигурен“")
  void anUnverifiedMonthIsRefused() {
    Input in = sick(0, LocalDate.of(2025, 9, 30), LocalDate.of(2026, 10, 12));
    in.unverifiedMonths = Collections.singletonList("2026-03 — няма потвърден фиш");
    Verdict v = BgSickBenefitVerdictRules.decide(in);
    assertTrue(v.isRefused());
    assertTrue(v.refusal.contains("2026-03"), v.refusal);
  }

  @Test
  @DisplayName("месеците за удостоверяване: след датата на декларацията и ПРЕДИ месеца на началото")
  void theMonthsToVerifyStopBeforeTheStartMonth() {
    assertEquals(
        Arrays.asList(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9)),
        BgSickBenefitVerdictRules.monthsToVerify(
            LocalDate.of(2026, 6, 30), LocalDate.of(2026, 10, 12)));
    assertEquals(
        Collections.emptyList(),
        BgSickBenefitVerdictRules.monthsToVerify(
            LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 12)));
  }

  @Test
  @DisplayName("бременност и раждане — прагът е 12 (чл. 48а), без изключението за възрастта")
  void maternityNeedsTwelveMonths() {
    Input in = sick(8, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 12));
    in.absenceTypeCode = "maternity_birth";
    in.category = BgSickBenefitVerdictRules.MATERNITY_CATEGORY;
    in.birthDate = LocalDate.of(2009, 1, 1);
    Verdict v = BgSickBenefitVerdictRules.decide(in);
    assertEquals(BgSickBenefitVerdictRules.NO_RIGHT, v.verdict, v.why);
  }

  @Test
  @DisplayName("трудова злополука и под 18 г. към началото — С право без стаж")
  void theExemptionsNeedNoService() {
    Input accident = sick(0, null, LocalDate.of(2026, 10, 12));
    accident.serviceDeclared = false;
    accident.absenceTypeCode = "work_accident_er";
    assertEquals(
        BgSickBenefitVerdictRules.HAS_RIGHT, BgSickBenefitVerdictRules.decide(accident).verdict);

    Input minor = sick(0, null, LocalDate.of(2026, 10, 12));
    minor.serviceDeclared = false;
    minor.birthDate = LocalDate.of(2009, 1, 1);
    assertEquals(
        BgSickBenefitVerdictRules.HAS_RIGHT, BgSickBenefitVerdictRules.decide(minor).verdict);
  }
}
