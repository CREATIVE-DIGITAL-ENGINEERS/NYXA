package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СРОКЪТ ПО ПРИЛОЖЕНИЕ № 9 — свидетели с дати, по ИСТИНСКИЯ календар.
 *
 * <p>Празниците се четат от seed-а на производствения календар ({@code bg_eventsPlanningLine.csv})
 * — същите редове, които App Install зарежда в {@code EventsPlanningLine} и които {@code
 * BgWorkingDayService} чете. Преписани в теста, те щяха да са второ мнение за празниците.
 *
 * <p>Вариант Б: 10-о число в почивен ден се мести на първия следващ работен ден.
 */
class BgApp9DeadlineTest {

  private static final Path SEED =
      Paths.get("src/main/resources/apps/init-data/en/bg_eventsPlanningLine.csv");

  private static Set<LocalDate> holidays;
  private static Set<Integer> covered;

  @BeforeAll
  static void loadCalendar() throws IOException {
    holidays = new HashSet<>();
    covered = new HashSet<>();
    List<String> lines = Files.readAllLines(SEED, StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      if (line.trim().isEmpty()) {
        continue;
      }
      String[] cells = line.split(";");
      covered.add(Integer.valueOf(cells[1].trim()));
      holidays.add(LocalDate.parse(cells[2].trim()));
    }
    assertTrue(
        covered.contains(2026) && covered.contains(2027) && !covered.contains(2028),
        "свидетелите са писани за календар 2026–2027 без 2028; seed-ът носи " + covered);
  }

  private static BgApp9Deadline.Result presented(LocalDate day) {
    return BgApp9Deadline.of(BgApp9Deadline.Kind.PRESENTATION, day, holidays, covered);
  }

  // ═══ СРОКЪТ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName("01.10.2026 → 10.11.2026: делничен, без преместване")
  void aWeekdayTenthStays() {
    BgApp9Deadline.Result r = presented(LocalDate.of(2026, 10, 1));
    assertEquals(LocalDate.of(2026, 11, 10), r.dueDate);
    assertNull(r.warning);
  }

  @Test
  @DisplayName("15.09.2026 → 12.10.2026: 10.10 е събота, вариант Б мести на понеделник")
  void aSaturdayTenthMovesToMonday() {
    BgApp9Deadline.Result r = presented(LocalDate.of(2026, 9, 15));
    assertEquals(LocalDate.of(2026, 10, 10), r.legalDate, "законовата дата си е 10-о");
    assertEquals(LocalDate.of(2026, 10, 12), r.dueDate);
  }

  @Test
  @DisplayName("20.03.2026 → 14.04.2026: Велики петък → Велики понеделник, после вторник")
  void theEasterRunMovesToTuesday() {
    assertEquals(LocalDate.of(2026, 4, 14), presented(LocalDate.of(2026, 3, 20)).dueDate);
  }

  @Test
  @DisplayName("15.12.2026 → 11.01.2027: 10.01.2027 е неделя, през границата на годината")
  void aSundayTenthAcrossTheYearMovesToMonday() {
    BgApp9Deadline.Result r = presented(LocalDate.of(2026, 12, 15));
    assertEquals(LocalDate.of(2027, 1, 11), r.dueDate);
    assertNull(r.warning, "2026 и 2027 са заредени");
  }

  @Test
  @DisplayName("31.01.2026 → 10.02.2026: 31-о число не прелива месеца")
  void theThirtyFirstDoesNotOverflow() {
    assertEquals(LocalDate.of(2026, 2, 10), presented(LocalDate.of(2026, 1, 31)).dueDate);
  }

  @Test
  @DisplayName("⚠️ 15.12.2027 → „ненадежден срок (2028 не е заредена)“, не тихо 10-о")
  void anUnloadedYearIsVisiblyUnreliable() {
    BgApp9Deadline.Result r = presented(LocalDate.of(2027, 12, 15));
    assertEquals("ненадежден срок (2028 не е заредена)", r.warning);
    assertEquals(LocalDate.of(2028, 1, 10), r.dueDate, "датата се показва — но с предупреждението");
  }

  @Test
  @DisplayName("ИЗДАВАНЕ (чл. 12 т. 2): издаден 20.09.2026 → 12.10.2026, и тук вариант Б")
  void theIssueKindRunsFromTheIssueDate() {
    BgApp9Deadline.Result r =
        BgApp9Deadline.of(BgApp9Deadline.Kind.ISSUE, LocalDate.of(2026, 9, 20), holidays, covered);
    assertEquals(LocalDate.of(2026, 10, 12), r.dueDate);
    assertEquals(BgApp9Deadline.Kind.ISSUE, r.kind);
  }

  // ═══ ЛЕНТИТЕ — спрямо действителния срок ═════════════════════════════════

  @Test
  @DisplayName(
      "Лентите спрямо 10.11.2026: 04.11 в срок · 05.11 до 3 работни дни · 10.11 днес · 11.11 просрочено")
  void theBandsAroundTheTenthOfNovember() {
    BgApp9Deadline.Result r = presented(LocalDate.of(2026, 10, 1));
    assertEquals(LocalDate.of(2026, 11, 5), r.dueSoonFrom, "06, 09 и 10 ноември — три работни дни");

    assertEquals(BgApp9Deadline.Band.ON_TIME, band(r, LocalDate.of(2026, 11, 4)));
    assertEquals(BgApp9Deadline.Band.DUE_SOON, band(r, LocalDate.of(2026, 11, 5)));
    assertEquals(
        BgApp9Deadline.Band.DUE_SOON,
        band(r, LocalDate.of(2026, 11, 8)),
        "неделя: след нея остават 09 и 10");
    assertEquals(BgApp9Deadline.Band.DUE_TODAY, band(r, LocalDate.of(2026, 11, 10)));
    assertEquals(BgApp9Deadline.Band.OVERDUE, band(r, LocalDate.of(2026, 11, 11)));
  }

  @Test
  @DisplayName("Лентата се мери от ПРЕМЕСТЕНИЯ срок: 12.10.2026, а не от съботата 10.10")
  void theBandFollowsTheMovedDeadline() {
    BgApp9Deadline.Result r = presented(LocalDate.of(2026, 9, 15));
    assertEquals(LocalDate.of(2026, 10, 7), r.dueSoonFrom, "08, 09 и 12 октомври");
    assertEquals(BgApp9Deadline.Band.DUE_SOON, band(r, LocalDate.of(2026, 10, 10)));
    assertEquals(BgApp9Deadline.Band.DUE_TODAY, band(r, LocalDate.of(2026, 10, 12)));
  }

  @Test
  @DisplayName("Празниците разтягат лентата: срок 14.04.2026 — „до 3 работни дни“ още от 07.04")
  void holidaysStretchTheBandBackwards() {
    BgApp9Deadline.Result r = presented(LocalDate.of(2026, 3, 20));
    assertEquals(
        LocalDate.of(2026, 4, 7), r.dueSoonFrom, "08, 09 и 14 април — 10–13 април са почивни");
    assertEquals(BgApp9Deadline.Band.ON_TIME, band(r, LocalDate.of(2026, 4, 6)), "06.04 вижда 4");
  }

  private static BgApp9Deadline.Band band(BgApp9Deadline.Result r, LocalDate today) {
    return BgApp9Deadline.band(today, r.dueDate, r.dueSoonFrom);
  }
}
