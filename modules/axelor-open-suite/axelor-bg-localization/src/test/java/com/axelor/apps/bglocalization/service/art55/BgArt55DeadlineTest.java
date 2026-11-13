package com.axelor.apps.bglocalization.service.art55;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СРОКЪТ И ГРАНИЦИТЕ НА ТРИМЕСЕЧИЕТО — чл. 56 ал. 1 във връзка с чл. 65 ал. 12.
 *
 * <p>Чл. 56 ал. 1: декларацията се подава „в сроковете за внасяне на дължимите данъци“. Чл. 65 ал.
 * 12: „до края на месеца, следващ тримесечието на придобиване на дохода“. Указанията към образец
 * 4001 повтарят същото.
 */
class BgArt55DeadlineTest {

  @Test
  @DisplayName(
      "СВИДЕТЕЛ · срокът е краят на месеца, следващ тримесечието — и за Q4 това е 31 януари")
  void deadlineIsEndOfMonthFollowingTheQuarter() {
    assertEquals(
        LocalDate.parse("2025-04-30"),
        BgArt55DeclarationService.dueDate(2025, 1),
        "април има 30 дни — срокът е последният ДЕН на месеца, не 30-о число по правило");
    assertEquals(LocalDate.parse("2025-07-31"), BgArt55DeclarationService.dueDate(2025, 2));
    assertEquals(LocalDate.parse("2025-10-31"), BgArt55DeclarationService.dueDate(2025, 3));
    assertEquals(
        LocalDate.parse("2026-01-31"),
        BgArt55DeclarationService.dueDate(2025, 4),
        "четвъртото тримесечие прелива в СЛЕДВАЩАТА година — и съвпада с датата по чл. 65 ал. 13");
  }

  @Test
  @DisplayName("границите на тримесечието, по които се чете регистърът")
  void quarterBoundsCoverTheWholeQuarter() {
    assertEquals(LocalDate.parse("2025-01-01"), BgArt55DeclarationService.firstDayOf(2025, 1));
    assertEquals(LocalDate.parse("2025-03-31"), BgArt55DeclarationService.lastDayOf(2025, 1));
    assertEquals(LocalDate.parse("2025-04-01"), BgArt55DeclarationService.firstDayOf(2025, 2));
    assertEquals(
        LocalDate.parse("2025-06-30"),
        BgArt55DeclarationService.lastDayOf(2025, 2),
        "юни има 30 дни — изчислено, а не заковано");
    assertEquals(LocalDate.parse("2025-12-31"), BgArt55DeclarationService.lastDayOf(2025, 4));
  }

  @Test
  @DisplayName("2024 е високосна — 29 февруари е в първото тримесечие и не мести срока")
  void leapYearDoesNotMoveTheDeadline() {
    assertEquals(1, BgArt55Aggregator.quarterOf(LocalDate.parse("2024-02-29")));
    assertEquals(LocalDate.parse("2024-04-30"), BgArt55DeclarationService.dueDate(2024, 1));
  }
}
