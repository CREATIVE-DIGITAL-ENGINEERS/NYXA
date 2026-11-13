package com.axelor.apps.bglocalization.service.bankstatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.bankstatement.dto.BgBankMovement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgParsedStatement;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests the PDF/OCR statement parser: it maps the Azure transaction table into the shared {@link
 * BgParsedStatement}/{@link BgBankMovement} model, resolves bare {@code dd.MM} dates against the
 * statement period (a December value date in a January statement is the previous year), splits
 * debit/credit by column in the account currency, and skips the "Общо / Total" summary row. A
 * second test parses the real stored Azure fixture when it is present (untracked test-data).
 */
class BgBankStatementOcrParserTest {

  private static final ObjectMapper M = new ObjectMapper();

  @Test
  void mapsColumnsResolvesYearsAndSkipsTotalRow() {
    String[][] meta = {
      {"IBAN", "BG 93 BPBI 8898 10 32573701"},
      {"Валута / Currency", "EUR"},
      {"месечно / monthly", "01.01.2026 - 31.01.2026"}
    };
    String[] header = {
      "Дата\nDate",
      "Вальор\nValue date",
      "Док/Реф. №\nDoc/Ref. No",
      "Основание\nReason",
      "Дебит\nDebit\nEUR",
      "Дебит\nDebit\nBGN",
      "Кредит\nCredit\nEUR",
      "Кредит\nCredit\nBGN",
      "Салдо\nBalance\nEUR",
      "Салдо\nBalance\nBGN"
    };
    String[][] txn = {
      header,
      {
        "05.01",
        "05.01",
        "4810389",
        "UNCRBGSFXXX BG 14 UNCR 7000 15 22083175 Линдстрьом ЕООД",
        "110.44",
        "216.00",
        "",
        "",
        "5,563.63",
        "10,881.51"
      },
      {
        "06.01",
        "31.12",
        "7393603\n104000000",
        "STSABGSF BG 03 STSA 9300 00 27280510 NAVIA PROPERTIES LTD ФАКТУРА НОМЕР 66",
        "",
        "",
        "24,367.69",
        "47,659.06",
        "42,899.64",
        "83,904.40"
      },
      {"Общо / Total", "", "", "", "34,593.63", "67,659.25", "55,451.49", "108,453.69", "", ""}
    };

    String json = analyzeResult(new String[][][] {meta, txn});
    BgParsedStatement st = new BgBankStatementOcrParser().parse(json);

    assertEquals("BG93BPBI88981032573701", st.iban);
    assertEquals("EUR", st.currencyCode);
    assertNotNull(st.periodFrom);
    assertEquals(2026, st.periodFrom.getYear());
    assertEquals(1, st.periodTo.getMonthValue());
    assertTrue(st.recognized);

    // Two movements; the Total row was skipped.
    assertEquals(2, st.movements.size());

    BgBankMovement debitRow = st.movements.get(0);
    assertTrue(debitRow.debit);
    assertEquals(0, new BigDecimal("110.44").compareTo(debitRow.amount));
    assertEquals("4810389", debitRow.reference); // first Doc/Ref line only
    assertEquals(2026, debitRow.operationDate.getYear());
    assertEquals("BG14UNCR70001522083175", debitRow.counterpartyAccount); // IBAN from Основание

    BgBankMovement creditRow = st.movements.get(1);
    assertFalse(creditRow.debit);
    assertEquals(0, new BigDecimal("24367.69").compareTo(creditRow.amount));
    // Value date 31.12 in a January-2026 statement → previous year.
    assertEquals(2025, creditRow.valueDate.getYear());
    assertEquals(12, creditRow.valueDate.getMonthValue());
  }

  @Test
  void unreadableOrEmptyJsonIsNotRecognized() {
    assertFalse(new BgBankStatementOcrParser().parse(null).recognized);
    assertFalse(new BgBankStatementOcrParser().parse("").recognized);
    assertFalse(new BgBankStatementOcrParser().parse("{not json").recognized);
  }

  @Test
  void pageRangeParsesOnlyTheRequestedStatement() {
    String[] header = {
      "Дата\nDate", "Вальор\nValue date", "Док/Реф. №", "Основание\nReason",
      "Дебит\nDebit\nEUR", "Дебит\nDebit\nBGN", "Кредит\nCredit\nEUR", "Кредит\nCredit\nBGN",
      "Салдо\nBalance\nEUR", "Салдо\nBalance\nBGN"
    };
    ObjectNode root = M.createObjectNode();
    StringBuilder content = new StringBuilder();
    ArrayNode tabs = root.putArray("tables");
    // Page 1 = account A, one debit movement.
    tabs.add(paged(metaGrid("BG 93 BPBI 8898 10 32573701", "01.01.2026 - 31.01.2026"), 1, content));
    tabs.add(
        paged(
            new String[][] {
              header, {"05.01", "05.01", "111", "d1", "10.00", "19.56", "", "", "100.00", "195.58"}
            },
            1,
            content));
    // Page 2 = account B, one credit movement.
    tabs.add(paged(metaGrid("BG 18 UBBS 8002 10 62502410", "01.02.2026 - 28.02.2026"), 2, content));
    tabs.add(
        paged(
            new String[][] {
              header, {"06.02", "06.02", "222", "c1", "", "", "20.00", "39.12", "120.00", "234.70"}
            },
            2,
            content));
    root.put("content", content.toString());
    String json = root.toString();

    BgParsedStatement p2 = new BgBankStatementOcrParser().parse(json, 2, 2);
    assertEquals("BG18UBBS80021062502410", p2.iban);
    assertEquals(1, p2.movements.size());
    assertFalse(p2.movements.get(0).debit); // the credit row only

    BgParsedStatement p1 = new BgBankStatementOcrParser().parse(json, 1, 1);
    assertEquals("BG93BPBI88981032573701", p1.iban);
    assertEquals(1, p1.movements.size());
    assertTrue(p1.movements.get(0).debit); // the debit row only
  }

  @Test
  void parsesTheRealPostbankFixtureWhenPresent() throws Exception {
    Path fixture = testData("fixtures/bankstatements.azure-full.json");
    if (fixture == null || !Files.exists(fixture)) {
      // ⚠️ Причината минава през BgSkip, а НЕ през assumeTrue: Gradle не записва съобщението на
      // assumeTrue в XML отчета — там остава празният <skipped/>. Пропуснат тест без причина е
      // неразличим от изключен тест.
      BgSkip.because(
          "липсва неверсионираният фикстур test-data/fixtures/bankstatements.azure-full.json"
              + " (реален извлек от Azure OCR) — разборът срещу истински документ НЕ е проверен."
              + " Останалите три теста на този клас текат и без него.");
    }

    String json = new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8);
    BgParsedStatement st = new BgBankStatementOcrParser().parse(json);

    assertEquals("BG93BPBI88981032573701", st.iban);
    assertEquals("EUR", st.currencyCode);
    assertEquals(2026, st.periodFrom.getYear());
    assertTrue(st.recognized, "the real 6-page statement must be recognized");
    assertTrue(st.movements.size() >= 20, "expected the movements from all 6 pages");
    // Every movement has a positive amount and a running balance (needed for validation).
    for (BgBankMovement m : st.movements) {
      assertTrue(m.amount != null && m.amount.signum() > 0);
      assertNotNull(m.operationDate);
    }
  }

  // --- helpers: build a minimal Azure analyzeResult JSON from string grids ---

  private static String analyzeResult(String[][][] tables) {
    ObjectNode root = M.createObjectNode();
    StringBuilder content = new StringBuilder();
    ArrayNode tabs = root.putArray("tables");
    for (String[][] grid : tables) {
      tabs.add(tableNode(grid, content));
    }
    root.put("content", content.toString());
    return root.toString();
  }

  private static ObjectNode tableNode(String[][] grid, StringBuilder content) {
    int rows = grid.length;
    int cols = rows == 0 ? 0 : grid[0].length;
    ObjectNode t = M.createObjectNode();
    t.put("rowCount", rows);
    t.put("columnCount", cols);
    ArrayNode cells = t.putArray("cells");
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        ObjectNode cell = M.createObjectNode();
        cell.put("rowIndex", r);
        cell.put("columnIndex", c);
        cell.put("content", grid[r][c]);
        cells.add(cell);
        content.append(grid[r][c]).append('\n');
      }
    }
    return t;
  }

  private static ObjectNode paged(String[][] grid, int page, StringBuilder content) {
    ObjectNode t = tableNode(grid, content);
    t.putArray("boundingRegions").addObject().put("pageNumber", page);
    return t;
  }

  private static String[][] metaGrid(String iban, String period) {
    return new String[][] {{"IBAN", iban}, {"месечно / monthly", period}};
  }

  private static Path testData(String relative) {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve("test-data").resolve(relative);
      if (Files.exists(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
