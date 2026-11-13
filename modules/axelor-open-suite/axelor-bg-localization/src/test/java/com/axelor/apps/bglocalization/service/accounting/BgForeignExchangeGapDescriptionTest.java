package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import org.junit.jupiter.api.Test;

/**
 * Редовете на статията за курсова разлика носят описание и произход — BG_COA изисква описание на
 * всеки ред, а AOS ги оставяше празни.
 */
class BgForeignExchangeGapDescriptionTest {

  @Test
  void supplierInvoiceUsesTheSupplierNumber() {
    Move move = new Move();
    Invoice invoice = new Invoice();
    invoice.setInvoiceId("P-0000000007");
    invoice.setSupplierInvoiceNb("US-0008");
    move.setInvoice(invoice);
    assertEquals(
        "Курсова разлика по фактура № US-0008", BgForeignExchangeGapServiceImpl.description(move));
    assertEquals("P-0000000007", BgForeignExchangeGapServiceImpl.origin(move));
  }

  @Test
  void customerInvoiceUsesItsOwnNumber() {
    Move move = new Move();
    Invoice invoice = new Invoice();
    invoice.setInvoiceId("0000000012");
    move.setInvoice(invoice);
    assertEquals(
        "Курсова разлика по фактура № 0000000012",
        BgForeignExchangeGapServiceImpl.description(move));
  }

  @Test
  void withoutAnInvoiceTheDescriptionIsStillThere() {
    Move move = new Move();
    assertEquals("Курсова разлика", BgForeignExchangeGapServiceImpl.description(move));
    assertNull(BgForeignExchangeGapServiceImpl.origin(move));
  }
}
