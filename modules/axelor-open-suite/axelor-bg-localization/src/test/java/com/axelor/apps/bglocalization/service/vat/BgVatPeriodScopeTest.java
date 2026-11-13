package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Общото правило за периода — текстът на заявките; подборът е в IT. */
class BgVatPeriodScopeTest {

  @Test
  @DisplayName("Статиите: без неявно съединяване — изричният период е в подзаявки по Invoice")
  void без_неявно_съединяване() {
    String moves = BgVatPeriodScope.MOVES;
    assertFalse(moves.contains("self.invoice.bgVatReportingPeriod"), moves);
    assertTrue(moves.contains("self.invoice IS NULL OR self.invoice.id NOT IN ("), moves);
    assertTrue(moves.contains("OR self.invoice.id IN ("), moves);
    // И петте заявки на колектора го носят дословно (OUTSIDE_PERIOD е по датата — извън периода).
    for (String filter :
        new String[] {
          BgVatDocumentCollector.POSTED_MOVES,
          BgVatDocumentCollector.UNPOSTED_MOVES,
          BgVatDocumentCollector.OTHER_COMPANY_MOVES
        }) {
      assertTrue(filter.contains(moves), filter);
      assertFalse(filter.contains("self.date >= :from AND self.date <= :to AND COALESCE"), filter);
    }
  }

  @Test
  @DisplayName(
      "Фактурите: празното — по датата, изричното — по него; заявките на авансите го носят")
  void фактурите() {
    String scope = BgVatPeriodScope.invoices("self.move.date");
    assertTrue(scope.contains("COALESCE(self.bgVatReportingPeriod, '') = ''"));
    assertTrue(scope.contains("self.move.date >= :from"));
    assertTrue(BgVatAdvanceService.PERIOD_FINALS.contains(scope));
    String byDate = BgVatPeriodScope.invoices("self.invoiceDate");
    assertTrue(BgVatAdvanceService.PERIOD_PURCHASE_ADVANCES.contains(byDate));
    assertTrue(BgVatAdvanceService.PERIOD_ADVANCES.contains(byDate));
    assertTrue(BgVatAdvanceService.PERIOD_WITHOUT_VAT_MOVE.contains(byDate));
  }

  @Test
  @DisplayName("Параметрите: датите и същото като ГГГГММ")
  void параметрите() {
    Map<String, Object> p =
        BgVatPeriodScope.parameters(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    assertEquals(LocalDate.of(2026, 6, 1), p.get("from"));
    assertEquals(LocalDate.of(2026, 6, 30), p.get("to"));
    assertEquals("202606", p.get("periodFrom"));
    assertEquals("202606", p.get("periodTo"));
  }
}
