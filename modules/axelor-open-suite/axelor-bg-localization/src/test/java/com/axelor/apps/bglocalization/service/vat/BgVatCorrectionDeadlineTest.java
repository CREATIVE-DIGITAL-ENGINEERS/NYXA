package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * т. 7: „Създай корекция“ след срока по ЗДДС чл. 125 ал. 5 — предупреждение с чл. 126 ал. 3, в
 * двете посоки на срока. Срокът се мести на първия работен ден след събота, неделя и празник по
 * календара. Юни 2026: 14.07.2026 е вторник — без преместване.
 */
class BgVatCorrectionDeadlineTest {

  /** Без календар на дружеството — само събота и неделя. */
  private static final Predicate<LocalDate> NONE = day -> false;

  private static Predicate<LocalDate> holidays(LocalDate... days) {
    return Set.of(days)::contains;
  }

  private static final String JUNE_TEXT =
      "Срокът за подаване за 06.2026 изтече на 14.07.2026. Повторно подаване по чл. 126 ал. 2 ЗДДС"
          + " е само до срока. След него неотразен документ се включва в текущия период (чл. 126"
          + " ал. 3 т. 1), а неправилно отразен се поправя с писмено уведомление до НАП (т. 2).";

  private static String warning(String status, String period, LocalDate today) {
    return BgVatCorrectionDeadline.warning(status, period, today, NONE);
  }

  @Test
  @DisplayName("преди срока и точно на 14-о (вторник) — без предупреждение")
  void преди_срока() {
    for (String status : new String[] {"submitted", "accepted"}) {
      assertNull(warning(status, "202606", LocalDate.of(2026, 7, 1)));
      assertNull(warning(status, "202606", LocalDate.of(2026, 7, 13)));
      assertNull(
          warning(status, "202606", LocalDate.of(2026, 7, 14)), "14-о е последният ден — в срок");
    }
  }

  @Test
  @DisplayName("на 15-о и по-късно — предупреждението с периода и датата на срока")
  void след_срока() {
    for (String status : new String[] {"submitted", "accepted"}) {
      assertEquals(JUNE_TEXT, warning(status, "202606", LocalDate.of(2026, 7, 15)));
      assertEquals(JUNE_TEXT, warning(status, "202606", LocalDate.of(2026, 11, 3)));
    }
  }

  @Test
  @DisplayName("14-о в събота (14.11.2026) — срокът е понеделник 16-о; 17-о — с предупреждение")
  void събота() {
    assertEquals(
        LocalDate.of(2026, 11, 16), BgVatReportingPeriod.deadline("202610", NONE), "без календар");
    assertNull(warning("submitted", "202610", LocalDate.of(2026, 11, 16)));
    String warning = warning("submitted", "202610", LocalDate.of(2026, 11, 17));
    assertTrue(warning.startsWith("Срокът за подаване за 10.2026 изтече на 16.11.2026."), warning);
  }

  @Test
  @DisplayName("14-о в неделя (14.06.2026) — срокът е понеделник 15-о")
  void неделя() {
    assertNull(warning("accepted", "202605", LocalDate.of(2026, 6, 15)));
    assertTrue(
        warning("accepted", "202605", LocalDate.of(2026, 6, 16)).contains("изтече на 15.06.2026."));
  }

  @Test
  @DisplayName("14-о на празник по календара (понеделник 14.09.2026) — срокът е 15-о")
  void празник() {
    Predicate<LocalDate> calendar = holidays(LocalDate.of(2026, 9, 14));
    assertEquals(LocalDate.of(2026, 9, 15), BgVatReportingPeriod.deadline("202608", calendar));
    assertNull(
        BgVatCorrectionDeadline.warning(
            "submitted", "202608", LocalDate.of(2026, 9, 15), calendar));
    String warning =
        BgVatCorrectionDeadline.warning("submitted", "202608", LocalDate.of(2026, 9, 16), calendar);
    assertTrue(warning.startsWith("Срокът за подаване за 08.2026 изтече на 15.09.2026."), warning);
    // Същият ден без календар — 14-о е работен и срокът изтича тогава.
    assertTrue(
        warning("submitted", "202608", LocalDate.of(2026, 9, 15))
            .contains("изтече на 14.09.2026."));
  }

  @Test
  @DisplayName("празник в петък 14.08.2026, после събота и неделя — срокът е понеделник 17-о")
  void празник_и_почивни() {
    Predicate<LocalDate> calendar = holidays(LocalDate.of(2026, 8, 14));
    assertEquals(LocalDate.of(2026, 8, 17), BgVatReportingPeriod.deadline("202607", calendar));
  }

  @Test
  @DisplayName("отхвърлен оригинал след срока — без предупреждение (отхвърленото не е подаване)")
  void отхвърлен_оригинал() {
    assertNull(warning("rejected", "202606", LocalDate.of(2026, 7, 15)));
    assertNull(warning("rejected", "202606", LocalDate.of(2026, 11, 3)));
  }

  @Test
  @DisplayName("декември → януари: срокът е 14.01 на следващата година (четвъртък)")
  void декември_януари() {
    assertNull(warning("accepted", "202612", LocalDate.of(2027, 1, 14)));
    String warning = warning("accepted", "202612", LocalDate.of(2027, 1, 15));
    assertTrue(warning.startsWith("Срокът за подаване за 12.2026 изтече на 14.01.2027."), warning);
  }

  @Test
  @DisplayName("без валиден период — без предупреждение")
  void без_период() {
    assertNull(warning("submitted", null, LocalDate.of(2026, 11, 3)));
    assertNull(warning("submitted", "2026", LocalDate.of(2026, 11, 3)));
  }
}
