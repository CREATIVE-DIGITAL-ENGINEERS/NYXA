package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Границата на импорта от Microinvest. Чист тест — без база и без контейнер.
 *
 * <p>Сценарият навсякъде е реалният: последен подаден от Microinvest период 202608, миграция на 15
 * септември.
 */
class MicroinvestVatPeriodCutoffTest {

  private static final String LAST_FILED = "202608";
  private static final LocalDate CUTOFF = LocalDate.of(2026, 8, 31);

  // Границата е ВКЛЮЧВАЩА и е последният ден на заявения месец.
  @Test
  void cutoffIsTheLastDayOfTheSelectedPeriod() {
    assertEquals(CUTOFF, MicroinvestVatPeriodCutoff.cutoffOf(LAST_FILED));
    // Февруари и високосната година — краят на месеца не се предполага, а се смята.
    assertEquals(LocalDate.of(2026, 2, 28), MicroinvestVatPeriodCutoff.cutoffOf("202602"));
    assertEquals(LocalDate.of(2024, 2, 29), MicroinvestVatPeriodCutoff.cutoffOf("202402"));
  }

  // ПРЕДИ и НА границата се внасят; СЛЕД нея — не.
  @Test
  void onOrBeforeCutoffIsImported_afterCutoffIsNot() {
    assertFalse(MicroinvestVatPeriodCutoff.isAfterCutoff(LocalDate.of(2026, 7, 15), CUTOFF));
    assertFalse(
        MicroinvestVatPeriodCutoff.isAfterCutoff(LocalDate.of(2026, 8, 31), CUTOFF),
        "31 август Е в последния подаден период и се внася");

    assertTrue(
        MicroinvestVatPeriodCutoff.isAfterCutoff(LocalDate.of(2026, 9, 1), CUTOFF),
        "1 септември е вече неподаденият месец — въвежда се в Nyxa");
    assertTrue(MicroinvestVatPeriodCutoff.isAfterCutoff(LocalDate.of(2026, 9, 14), CUTOFF));
    assertTrue(MicroinvestVatPeriodCutoff.isAfterCutoff(LocalDate.of(2026, 12, 31), CUTOFF));

    // Дата липсва — не се изхвърля тихо; това е друг дефект със своя проверка.
    assertFalse(MicroinvestVatPeriodCutoff.isAfterCutoff(null, CUTOFF));
  }

  // ⚠️ БЕЗ МЪЛЧАЛИВО ПОДРАЗБИРАНЕ: липсващ или невалиден период е отказ, не „внеси всичко“.
  @Test
  void missingOrInvalidPeriodIsRefused() {
    assertNotNull(MicroinvestVatPeriodCutoff.refusal(null));
    assertNotNull(MicroinvestVatPeriodCutoff.refusal("   "));
    assertNotNull(MicroinvestVatPeriodCutoff.refusal("2026-08"));
    assertNotNull(MicroinvestVatPeriodCutoff.refusal("202613"));
    assertNotNull(MicroinvestVatPeriodCutoff.refusal("202600"));

    assertNull(MicroinvestVatPeriodCutoff.refusal(LAST_FILED));
    assertNull(MicroinvestVatPeriodCutoff.cutoffOf("2026-08"), "невалиден период няма граница");

    assertTrue(MicroinvestVatPeriodCutoff.describe(LAST_FILED, CUTOFF).contains("31.08.2026"));
  }
}
