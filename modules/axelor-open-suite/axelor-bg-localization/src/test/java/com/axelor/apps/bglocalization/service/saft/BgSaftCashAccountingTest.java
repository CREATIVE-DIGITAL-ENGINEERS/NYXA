package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Периодът на касовата отчетност: „от“ и „до“ са включително (ЗДДС чл. 151б ал. 1 и ал. 9); празно
 * „от“ = извън режима. Видовете 11/12/13 и 91/93.
 */
class BgSaftCashAccountingTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 6, 15);

  @Test
  @DisplayName("Празни полета — дружеството не е по режима; получените се подават")
  void извън_режима() {
    assertSame(BgSaftCashAccounting.NONE, BgSaftCashAccounting.of(null, null));
    assertNull(BgSaftCashAccounting.NONE.receivedRefusal(LocalDate.of(2026, 4, 5)));
    assertNull(BgSaftCashAccounting.NONE.receivedRefusal(null));
  }

  @Test
  @DisplayName("„От“ и „до“ са включително; извън тях — подава се")
  void границите() {
    BgSaftCashAccounting cash = BgSaftCashAccounting.of(FROM, TO);
    assertNull(cash.receivedRefusal(FROM.minusDays(1)));
    assertNotNull(cash.receivedRefusal(FROM));
    assertNotNull(cash.receivedRefusal(TO));
    assertNull(cash.receivedRefusal(TO.plusDays(1)));
    String refusal = cash.receivedRefusal(FROM);
    assertTrue(refusal.contains("дружеството е по касова отчетност към 2026-03-01"), refusal);
    assertTrue(refusal.contains(BgSaftNapPending.OWN_CASH_ACCOUNTING), refusal);
  }

  @Test
  @DisplayName("Без „до“ режимът продължава")
  void без_край() {
    assertNotNull(BgSaftCashAccounting.of(FROM, null).receivedRefusal(LocalDate.of(2030, 1, 1)));
  }

  @Test
  @DisplayName("„До“ без „от“, „до“ преди „от“, документ без дата при дружество по режима — отказ")
  void нееднозначно() {
    assertTrue(BgSaftCashAccounting.of(null, TO).receivedRefusal(FROM).contains("без „от“"));
    assertTrue(BgSaftCashAccounting.of(TO, FROM).receivedRefusal(FROM).contains("е преди „от“"));
    assertTrue(BgSaftCashAccounting.of(FROM, null).receivedRefusal(null).contains("без дата"));
  }

  @Test
  @DisplayName("Видовете: 11/12/13 — фактура/известие; 91/93 — получен протокол; 92/94 — не")
  void видовете() {
    for (String type : new String[] {"11", "12", "13", " 11 "}) {
      assertTrue(BgSaftCashAccounting.isInvoiceType(type), type);
    }
    assertTrue(BgSaftCashAccounting.isReceivedProtocolType("91"));
    assertTrue(BgSaftCashAccounting.isReceivedProtocolType("93"));
    for (String type : new String[] {"92", "94", "01", null}) {
      assertTrue(!BgSaftCashAccounting.isReceivedProtocolType(type), String.valueOf(type));
      assertTrue(!BgSaftCashAccounting.isInvoiceType(type), String.valueOf(type));
    }
  }
}
