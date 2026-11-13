package com.axelor.apps.bglocalization.service.bnb;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Заявката на СПБ-6А/6Б в BgNsiGodLedger — проверена по текста, защото без база не се изпълнява.
 *
 * <p>Всяко от тези парчета, ако изчезне, дава грешни числа без грешка: без {@code currencyAmount}
 * сумите са в отчетната валута, не в оригиналната; без валутата на статията USD и EUR се сливат;
 * без партньора салдото не се дели по знак; без закриващите статии салдото към 31.12 е нула.
 */
class BgBnbSpb6LedgerQueryGuardTest {

  private static final Path LEDGER =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/nsi/BgNsiGodLedger.java");

  private static String query() throws IOException {
    String src = Files.readString(LEDGER, StandardCharsets.UTF_8);
    int start = src.indexOf("private void collectPartnerCurrency(");
    assertTrue(start > 0, "collectPartnerCurrency липсва в BgNsiGodLedger");
    int end = src.indexOf("getResultList()", start);
    return src.substring(start, end).replaceAll("\"\\s*\\+\\s*\"", "");
  }

  @Test
  @DisplayName("Сумата във валута, валутата на статията и партньорът са в заявката")
  void валутата_и_партньорът() throws IOException {
    String q = query();
    assertTrue(q.contains("SUM(self.currencyAmount)"), q);
    assertTrue(q.contains("LEFT JOIN m.currency c"), q);
    assertTrue(q.contains("JOIN self.partner p"), q);
    assertTrue(q.contains("GROUP BY a.code, p.id, c.codeISO"), q);
  }

  @Test
  @DisplayName("Закриващите статии на годината на датата са вън — иначе салдото към 31.12 е нула")
  void закриването_е_вън() throws IOException {
    String q = query();
    assertTrue(q.contains("self.move.functionalOriginSelect <> :closure"), q);
    assertTrue(q.contains("OR self.date < :closedFrom OR self.date > :closedTo"), q);
    assertTrue(q.contains("SCOPE"), q);
  }
}
