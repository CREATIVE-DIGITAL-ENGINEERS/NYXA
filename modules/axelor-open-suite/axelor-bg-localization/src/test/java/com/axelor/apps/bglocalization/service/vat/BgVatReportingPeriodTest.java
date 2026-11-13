package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.bglocalization.service.vat.BgVatReportingPeriod.Kind;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Правилата на „Период на отразяване по ЗДДС“ — без база. Примерът от плана: фактура от май,
 * установена в юли; май е подаден на 14.06.
 */
class BgVatReportingPeriodTest {

  private static final Predicate<String> NONE_FILED = p -> false;

  private static Predicate<String> filed(String... periods) {
    Set<String> set = Set.of(periods);
    return set::contains;
  }

  private static Invoice invoice(int operation, LocalDate date, LocalDate origin) {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(operation);
    invoice.setInvoiceDate(date);
    invoice.setOriginDate(origin);
    return invoice;
  }

  private static Invoice sale(LocalDate date) {
    return invoice(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE, date, null);
  }

  private static Invoice purchase(LocalDate date, LocalDate origin) {
    return invoice(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE, date, origin);
  }

  private static final LocalDate MAY_28 = LocalDate.of(2026, 5, 28);

  @Test
  @DisplayName("ГГГГММ, плюс месеци през годината, срокът 14-о, етикетът ММ.ГГГГ")
  void основите() {
    assertEquals("202605", BgVatReportingPeriod.of(MAY_28));
    assertNull(BgVatReportingPeriod.of(null));
    assertEquals("202701", BgVatReportingPeriod.plus("202612", 1));
    assertEquals("202705", BgVatReportingPeriod.plus("202605", 12));
    assertEquals(LocalDate.of(2026, 6, 14), BgVatReportingPeriod.deadline("202605"));
    assertEquals(LocalDate.of(2027, 1, 14), BgVatReportingPeriod.deadline("202612"));
    assertEquals("05.2026", BgVatReportingPeriod.label("202605"));
    assertTrue(BgVatReportingPeriod.isValid("202612"));
    assertFalse(BgVatReportingPeriod.isValid("202613"));
    assertFalse(BgVatReportingPeriod.isValid("2026-05"));
    assertFalse(BgVatReportingPeriod.isValid(null));
  }

  @Test
  @DisplayName("Празното е месецът на статията; изричното печели; КИ не наследява (copy=false)")
  void ефективният() {
    Invoice invoice = sale(MAY_28);
    assertNull(BgVatReportingPeriod.explicit(invoice));
    assertEquals("202605", BgVatReportingPeriod.effective(invoice));
    invoice.setBgVatReportingPeriod(" ");
    assertEquals("202605", BgVatReportingPeriod.effective(invoice));
    invoice.setBgVatReportingPeriod("202607");
    assertEquals("202607", BgVatReportingPeriod.effective(invoice));
    assertEquals("202605", BgVatReportingPeriod.own(invoice));
  }

  @Test
  @DisplayName("Продажба: неподаден свой период — само той (чл. 124 ал. 2)")
  void продажба_неподаден() {
    assertEquals(
        List.of("202605"),
        BgVatReportingPeriod.allowed(
            Kind.FIXED, "202605", null, LocalDate.of(2026, 7, 5), NONE_FILED));
  }

  @Test
  @DisplayName("Продажба: подаден, срокът не е изтекъл — своят, подава се отново (чл. 126 ал. 2)")
  void продажба_подаден_в_срока() {
    assertEquals(
        List.of("202605"),
        BgVatReportingPeriod.allowed(
            Kind.FIXED, "202605", null, LocalDate.of(2026, 6, 14), filed("202605")));
  }

  @Test
  @DisplayName("Продажба: подаден, срокът изтекъл — периодът на установяване (чл. 126 ал. 3 т. 1)")
  void продажба_след_срока() {
    assertEquals(
        List.of("202607"),
        BgVatReportingPeriod.allowed(
            Kind.FIXED, "202605", null, LocalDate.of(2026, 7, 5), filed("202605")));
    // 15.06 — денят след срока: установено е в юни.
    assertEquals(
        List.of("202606"),
        BgVatReportingPeriod.allowed(
            Kind.FIXED, "202605", null, LocalDate.of(2026, 6, 15), filed("202605")));
    // Периодът на установяване е подаден — няма позволен.
    assertEquals(
        List.of(),
        BgVatReportingPeriod.allowed(
            Kind.FIXED, "202605", null, LocalDate.of(2026, 7, 5), filed("202605", "202607")));
  }

  @Test
  @DisplayName("Покупка: всеки неподаден до 12-ия период след издаването (чл. 72 ал. 1, 124 ал. 4)")
  void покупка_дванадесет() {
    List<String> allowed =
        BgVatReportingPeriod.allowed(
            Kind.PURCHASE, "202605", "202605", LocalDate.of(2026, 7, 5), filed("202606"));
    assertEquals("202605", allowed.get(0));
    assertFalse(allowed.contains("202606"), "подаденият не се предлага");
    assertTrue(allowed.contains("202607"));
    assertTrue(allowed.contains("202705"), "12-ият период минава");
    assertFalse(allowed.contains("202706"), "13-ият — не");
    assertEquals(12, allowed.size());
  }

  @Test
  @DisplayName("Покупка: 12-те се броят от датата на доставчика, не от въвеждането (Р6)")
  void покупка_от_доставчика() {
    Invoice late = purchase(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 4, 30));
    assertEquals("202604", BgVatReportingPeriod.issued(late));
    assertEquals("202607", BgVatReportingPeriod.own(late));
    List<String> allowed =
        BgVatReportingPeriod.allowed(
            Kind.PURCHASE,
            BgVatReportingPeriod.own(late),
            BgVatReportingPeriod.issued(late),
            LocalDate.of(2026, 7, 5),
            NONE_FILED);
    assertTrue(allowed.contains("202704"));
    assertFalse(allowed.contains("202705"));
  }

  @Test
  @DisplayName("Покупка: подаден свой период след срока — само по-късните")
  void покупка_подаден() {
    List<String> allowed =
        BgVatReportingPeriod.allowed(
            Kind.PURCHASE, "202605", "202605", LocalDate.of(2026, 7, 5), filed("202605"));
    assertFalse(allowed.contains("202605"));
    assertEquals("202606", allowed.get(0));
  }

  @Test
  @DisplayName("Отказите: формат, назад, подаден, след 12-ия, получено КИ, протокол, Microinvest")
  void отказите() {
    LocalDate july5 = LocalDate.of(2026, 7, 5);
    Invoice sale = sale(MAY_28);
    assertTrue(
        BgVatReportingPeriod.refusal(sale, "2026-07", july5, NONE_FILED, false, false)
            .contains("ГГГГММ"));
    assertTrue(
        BgVatReportingPeriod.refusal(sale, "202604", july5, NONE_FILED, false, false)
            .contains("чл. 126 ал. 3 т. 2"));
    assertTrue(
        BgVatReportingPeriod.refusal(sale, "202607", july5, filed("202607"), false, false)
            .contains("подаден"));
    assertTrue(
        BgVatReportingPeriod.refusal(sale, "202607", july5, NONE_FILED, true, false)
            .contains("протокола"));
    assertTrue(
        BgVatReportingPeriod.refusal(sale, "202607", july5, NONE_FILED, false, true)
            .contains("Microinvest"));
    // Неподаден май — продажбата е в своя период.
    assertTrue(
        BgVatReportingPeriod.refusal(sale, "202607", july5, NONE_FILED, false, false)
            .contains("чл. 124 ал. 2"));
    // Май подаден, срокът изтекъл — юли да, юни (не е установена тогава) — не.
    assertNull(BgVatReportingPeriod.refusal(sale, "202607", july5, filed("202605"), false, false));
    assertTrue(
        BgVatReportingPeriod.refusal(sale, "202606", july5, filed("202605"), false, false)
            .contains("07.2026"));

    Invoice purchase = purchase(MAY_28, MAY_28);
    assertNull(BgVatReportingPeriod.refusal(purchase, "202705", july5, NONE_FILED, false, false));
    assertTrue(
        BgVatReportingPeriod.refusal(purchase, "202706", july5, NONE_FILED, false, false)
            .contains("05.2027"));

    // Получено кредитно известие — без отлагане (чл. 124 ал. 5).
    Invoice refund = invoice(InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND, MAY_28, MAY_28);
    assertEquals(Kind.FIXED, BgVatReportingPeriod.kind(refund));
    assertTrue(
        BgVatReportingPeriod.refusal(refund, "202607", july5, NONE_FILED, false, false)
            .contains("чл. 124 ал. 5"));
  }
}
