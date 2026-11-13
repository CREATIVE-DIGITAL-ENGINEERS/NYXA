package com.axelor.apps.bglocalization.service.bankstatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.bankstatement.BgPostbankStatementParser.BalanceCheck;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgBankMovement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgParsedStatement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline unit tests for the BG Postbank statement parser (no Guice, no file I/O). */
class BgPostbankStatementParserTest {

  private static final String[] HEADER = {
    "Вид операция",
    "Тип",
    "Наредител/Получател",
    "От/към сметка",
    "Канал",
    "Сума",
    "Валута на операцията",
    "Сума в EUR",
    "Сума в BGN (1.95583)",
    "Основание",
    "Дата на обработка",
    "Бордеро №",
    "Дата на бордеро",
    "Вальор",
    "Салдо след операцията"
  };

  private List<String[]> knownGrid() {
    List<String[]> g = new ArrayList<>();
    g.add(new String[] {"Сметка", "BG93BPBI88981032573701 (EUR)"});
    g.add(new String[] {"Банков клиент", "ТЕСТЕКС ООД"});
    g.add(HEADER);
    // Chronologically first: incoming 50 on 28.04, balance 1000.
    g.add(
        new String[] {
          "SCT", "Приход", "Клиент ЕООД", "BG11AAAA1", "Интернет",
          "50.00", "EUR", "50.00", "97.79", "плащане A",
          "28.04.2026 09:00:00", "111", "28/04/2026", "28.04.2026", "1000.00"
        });
    // Later: outgoing 100 on 30.04, balance 900.
    g.add(
        new String[] {
          "SCT", "Разход", "СИ ТИ ДИ ЕООД", "BG30UBBS2", "Интернет",
          "100.00", "EUR", "100.00", "195.58", "плащане B",
          "30.04.2026 13:39:56", "222", "30/04/2026", "30.04.2026", "900.00"
        });
    return g;
  }

  @Test
  void parsesKnownPostbankFormat() {
    BgParsedStatement st = new BgPostbankStatementParser().parse(knownGrid());
    assertTrue(st.recognized);
    assertEquals("BG93BPBI88981032573701", st.iban);
    assertEquals("EUR", st.currencyCode);
    assertEquals("ТЕСТЕКС ООД", st.accountHolder);
    assertEquals(2, st.movements.size());
    assertTrue(st.errors.isEmpty());

    BgBankMovement in = st.movements.get(0);
    assertFalse(in.debit); // Приход = incoming
    assertEquals(new BigDecimal("50.00"), in.amount);
    assertEquals(LocalDate.of(2026, 4, 28), in.operationDate);
    assertEquals("111", in.reference);
    assertEquals("плащане A", in.description);

    BgBankMovement out = st.movements.get(1);
    assertTrue(out.debit); // Разход = outgoing
    assertEquals(new BigDecimal("100.00"), out.amount);
    assertEquals(new BigDecimal("900.00"), out.runningBalance);
  }

  @Test
  void mapsColumnsByHeaderNotPosition() {
    // Reorder columns; mapping must still resolve by header name.
    List<String[]> g = new ArrayList<>();
    g.add(new String[] {"Сметка", "BG93BPBI88981032573701 (EUR)"});
    g.add(
        new String[] {
          "Тип", "Сума в EUR", "Основание", "Вальор", "Бордеро №", "Наредител/Получател"
        });
    g.add(new String[] {"Разход", "12.34", "такса", "01.05.2026", "999", "Доставчик"});
    BgParsedStatement st = new BgPostbankStatementParser().parse(g);
    assertTrue(st.recognized);
    assertEquals(1, st.movements.size());
    BgBankMovement m = st.movements.get(0);
    assertTrue(m.debit);
    assertEquals(new BigDecimal("12.34"), m.amount);
    assertEquals("такса", m.description);
    assertEquals("999", m.reference);
  }

  @Test
  void supportsBorderoAlias() {
    assertEquals("reference", BgPostbankStatementParser.canonicalHeader("Бордеро No"));
    assertEquals("reference", BgPostbankStatementParser.canonicalHeader("Бордеро №"));
    assertEquals("valueDate", BgPostbankStatementParser.canonicalHeader("Вальор"));
    assertEquals("amountEur", BgPostbankStatementParser.canonicalHeader("Сума в EUR"));
    assertEquals("amountBgn", BgPostbankStatementParser.canonicalHeader("Сума в BGN (1.95583)"));
    assertEquals("amountOrig", BgPostbankStatementParser.canonicalHeader("Сума"));
  }

  @Test
  void directionFromTip() {
    assertEquals(Boolean.TRUE, BgPostbankStatementParser.directionIsDebit("Разход"));
    assertEquals(Boolean.FALSE, BgPostbankStatementParser.directionIsDebit("Приход"));
    assertEquals(Boolean.TRUE, BgPostbankStatementParser.directionIsDebit("  разход  "));
    assertNull(BgPostbankStatementParser.directionIsDebit("нещо"));
    assertNull(BgPostbankStatementParser.directionIsDebit(null));
  }

  @Test
  void parsesBulgarianDates() {
    assertEquals(
        LocalDate.of(2026, 4, 30), BgPostbankStatementParser.parseBgDate("30.04.2026 13:39:56"));
    assertEquals(LocalDate.of(2026, 4, 30), BgPostbankStatementParser.parseBgDate("30/04/2026"));
    assertEquals(LocalDate.of(2026, 5, 1), BgPostbankStatementParser.parseBgDate("01.05.2026"));
    assertNull(BgPostbankStatementParser.parseBgDate("not a date"));
    assertNull(BgPostbankStatementParser.parseBgDate(""));
  }

  @Test
  void parsesBulgarianDecimals() {
    assertEquals(new BigDecimal("100.00"), BgPostbankStatementParser.parseBgDecimal("100.00"));
    assertEquals(new BigDecimal("29.59"), BgPostbankStatementParser.parseBgDecimal("29.59"));
    assertEquals(new BigDecimal("1234.56"), BgPostbankStatementParser.parseBgDecimal("1 234,56"));
    assertEquals(new BigDecimal("1234.56"), BgPostbankStatementParser.parseBgDecimal("1.234,56"));
    assertEquals(new BigDecimal("1234.56"), BgPostbankStatementParser.parseBgDecimal("1,234.56"));
    assertNull(BgPostbankStatementParser.parseBgDecimal(""));
    assertNull(BgPostbankStatementParser.parseBgDecimal("abc"));
  }

  @Test
  void validatesBalanceChain() {
    BgParsedStatement st = new BgPostbankStatementParser().parse(knownGrid());
    assertEquals(BalanceCheck.VALIDATED, BgPostbankStatementParser.validateBalances(st.movements));
  }

  @Test
  void validatesNewestFirstChainWithSameDayTransactions() {
    // Mirrors the real Postbank layout: newest-first, several Разход rows, running balance per row.
    // Real numbers from the file: 48734.61 - 204.77 = 48529.84, 48764.20 - 29.59 = 48734.61, ...
    List<BgBankMovement> ms = new ArrayList<>();
    ms.add(movement(true, "204.77", "48529.84")); // newest
    ms.add(movement(true, "29.59", "48734.61"));
    ms.add(movement(true, "397.01", "48764.20"));
    ms.add(movement(true, "70.34", "49161.21")); // oldest
    assertEquals(BalanceCheck.VALIDATED, BgPostbankStatementParser.validateBalances(ms));

    // Break one amount → the chain no longer reconciles.
    ms.get(1).amount = new BigDecimal("99.99");
    assertEquals(BalanceCheck.MISMATCH, BgPostbankStatementParser.validateBalances(ms));
  }

  private BgBankMovement movement(boolean debit, String amount, String runningBalance) {
    BgBankMovement m = new BgBankMovement();
    m.debit = debit;
    m.amount = new BigDecimal(amount);
    m.runningBalance = new BigDecimal(runningBalance);
    return m;
  }

  @Test
  void detectsBalanceMismatch() {
    BgParsedStatement st = new BgPostbankStatementParser().parse(knownGrid());
    // Corrupt the closing balance so opening + movements != closing.
    st.movements.get(1).runningBalance = new BigDecimal("777.00");
    assertEquals(BalanceCheck.MISMATCH, BgPostbankStatementParser.validateBalances(st.movements));
  }

  @Test
  void balanceNotAvailableWithoutRunningBalance() {
    // Two movements but no running balance on one → cannot validate the chain.
    BgBankMovement a = new BgBankMovement();
    a.debit = true;
    a.amount = new BigDecimal("10");
    a.runningBalance = new BigDecimal("100");
    BgBankMovement b = new BgBankMovement();
    b.debit = false;
    b.amount = new BigDecimal("5");
    b.runningBalance = null; // missing
    List<BgBankMovement> ms = new ArrayList<>();
    ms.add(a);
    ms.add(b);
    assertEquals(BalanceCheck.NOT_AVAILABLE, BgPostbankStatementParser.validateBalances(ms));
  }

  @Test
  void duplicateKeyPrimaryAndFallback() {
    BgBankMovement m = new BgBankMovement();
    m.debit = true;
    m.amount = new BigDecimal("100.00");
    m.valueDate = LocalDate.of(2026, 4, 30);
    m.operationDate = LocalDate.of(2026, 4, 30);
    m.reference = "222";
    m.description = "плащане";
    m.counterpartyAccount = "BG30UBBS2";

    String withRef = BgPostbankStatementParser.duplicateKey("BG93BPBI88981032573701", m);
    assertTrue(withRef.startsWith("REF|"));
    // Same movement → identical key (idempotent dedup).
    assertEquals(withRef, BgPostbankStatementParser.duplicateKey("BG93BPBI88981032573701", m));

    // Different signed amount → different key.
    BgBankMovement m2 = new BgBankMovement();
    m2.debit = false; // opposite direction changes the sign
    m2.amount = new BigDecimal("100.00");
    m2.valueDate = m.valueDate;
    m2.reference = m.reference;
    assertFalse(
        withRef.equals(BgPostbankStatementParser.duplicateKey("BG93BPBI88981032573701", m2)));

    // No Бордеро № → fallback key.
    m.reference = null;
    assertTrue(
        BgPostbankStatementParser.duplicateKey("BG93BPBI88981032573701", m)
            .startsWith("FALLBACK|"));
  }

  @Test
  void collectsMalformedRowsWithoutSkippingValidOnes() {
    List<String[]> g = new ArrayList<>();
    g.add(new String[] {"Сметка", "BG93BPBI88981032573701 (EUR)"});
    g.add(HEADER);
    // Bad Тип.
    g.add(
        new String[] {
          "SCT",
          "???",
          "X",
          "BG1",
          "Web",
          "10",
          "EUR",
          "10",
          "19",
          "op",
          "01.05.2026",
          "1",
          "01/05/2026",
          "01.05.2026",
          "10"
        });
    // Missing amount.
    g.add(
        new String[] {
          "SCT",
          "Разход",
          "Y",
          "BG2",
          "Web",
          "",
          "EUR",
          "",
          "",
          "op2",
          "02.05.2026",
          "2",
          "02/05/2026",
          "02.05.2026",
          "0"
        });
    // Valid.
    g.add(
        new String[] {
          "SCT",
          "Приход",
          "Z",
          "BG3",
          "Web",
          "5",
          "EUR",
          "5",
          "9.78",
          "op3",
          "03.05.2026",
          "3",
          "03/05/2026",
          "03.05.2026",
          "15"
        });
    BgParsedStatement st = new BgPostbankStatementParser().parse(g);
    assertEquals(1, st.movements.size()); // only the valid row
    assertEquals(2, st.errors.size()); // two malformed rows reported
    assertEquals(3, st.errors.get(0).excelRow); // bad-Тип row is grid index 2 → spreadsheet row 3
  }

  @Test
  void unrecognizedLayoutIsNotRecognized() {
    List<String[]> g = new ArrayList<>();
    g.add(new String[] {"foo", "bar", "baz"});
    g.add(new String[] {"1", "2", "3"});
    BgParsedStatement st = new BgPostbankStatementParser().parse(g);
    assertFalse(st.recognized);
    assertTrue(st.movements.isEmpty());
  }
}
