package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.stock.BgStockMoveDates.PeriodState;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Датата на документа (SD.MG.6): отказ в бъдещето и извън отворен период, иначе предупреждение. */
class BgStockMoveDatesTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

  @Test
  @DisplayName("дата в бъдещето — отказ")
  void бъдещето_се_отказва() {
    String refusal = BgStockMoveDates.refusal(TODAY.plusDays(1), TODAY, PeriodState.OPEN);
    assertNotNull(refusal);
    assertTrue(refusal.contains("08.10.2026") && refusal.contains("бъдещето"), refusal);
    assertNull(BgStockMoveDates.refusal(TODAY, TODAY, PeriodState.OPEN), "днес е допустимо");
  }

  @Test
  @DisplayName("липсващ фискален период — отказ")
  void липсващ_период() {
    String refusal = BgStockMoveDates.refusal(TODAY, TODAY, PeriodState.MISSING);
    assertNotNull(refusal);
    assertTrue(refusal.contains("няма фискален период"), refusal);
  }

  @Test
  @DisplayName("затворен фискален период — отказ")
  void затворен_период() {
    String refusal = BgStockMoveDates.refusal(TODAY.minusMonths(3), TODAY, PeriodState.CLOSED);
    assertNotNull(refusal);
    assertTrue(refusal.contains("затворен"), refusal);
  }

  @Test
  @DisplayName("период в ход на затваряне (временно затворен / в затваряне) — отказ")
  void период_в_затваряне() {
    String refusal = BgStockMoveDates.refusal(TODAY.minusMonths(1), TODAY, PeriodState.CLOSING);
    assertNotNull(refusal);
    assertTrue(refusal.contains("затваряне") && refusal.contains("07.09.2026"), refusal);
  }

  @Test
  @DisplayName("дата назад в отворен период — допустима")
  void назад_в_отворен_период() {
    assertNull(BgStockMoveDates.refusal(TODAY.minusMonths(1), TODAY, PeriodState.OPEN));
  }

  @Test
  @DisplayName("WARNING: документът е в друг месец от реализирането")
  void предупреждение_за_друг_месец() {
    String warning = BgStockMoveDates.monthWarning(LocalDate.of(2026, 9, 30), TODAY);
    assertNotNull(warning);
    assertTrue(warning.contains("30.09.2026") && warning.contains("07.10.2026"), warning);
    assertNull(BgStockMoveDates.monthWarning(LocalDate.of(2026, 10, 1), TODAY), "същият месец");
    assertNotNull(
        BgStockMoveDates.monthWarning(LocalDate.of(2025, 10, 7), TODAY),
        "същият месец, друга година");
    assertNull(BgStockMoveDates.monthWarning(null, TODAY));
    assertNull(BgStockMoveDates.monthWarning(TODAY, null), "нереализирано");
  }
}
