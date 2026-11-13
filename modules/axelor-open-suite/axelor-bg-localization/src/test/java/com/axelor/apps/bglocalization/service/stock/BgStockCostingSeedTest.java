package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Предложението на Nyxa за сметките (ИЗП) и журналът „Изписване на МЗ“ срещу BG_COA — четени от
 * истинските CSV файлове, не от паметта на автора.
 */
class BgStockCostingSeedTest {

  private static final Path COA =
      Paths.get("../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path CHART_CONFIG =
      Paths.get("../axelor-account/src/main/resources/l10n/chart-config.xml");

  private static List<String> lines(String file) throws IOException {
    return Files.readAllLines(COA.resolve(file), StandardCharsets.UTF_8);
  }

  /** Редовете на CSV с „;“ и кавички — достатъчно за BG_COA (без „;“ вътре в стойност). */
  private static List<Map<String, String>> rows(String file) throws IOException {
    List<String> all = lines(file);
    List<String> header = split(all.get(0));
    List<Map<String, String>> out = new ArrayList<>();
    for (String line : all.subList(1, all.size())) {
      if (line.trim().isEmpty()) {
        continue;
      }
      List<String> cells = split(line);
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
      }
      out.add(row);
    }
    return out;
  }

  private static List<String> split(String line) {
    return Arrays.stream(line.split(";", -1))
        .map(
            c ->
                c.startsWith("\"") && c.endsWith("\"") && c.length() >= 2
                    ? c.substring(1, c.length() - 1)
                    : c)
        .collect(Collectors.toList());
  }

  /** Кодът на сметката → importId на вида ѝ (account_accountType.csv). */
  private static Map<String, String> accountTypes() throws IOException {
    Map<String, String> out = new HashMap<>();
    for (Map<String, String> row : rows("account_account.csv")) {
      out.put(row.get("code"), row.get("accountType.importId"));
    }
    return out;
  }

  // ── таблицата по подразбиране ───────────────────────────────────────────

  @Test
  @DisplayName("таблицата е одобрената (442 и код 20), ред по ред")
  void таблицата_е_одобрената() {
    List<String> actual =
        BgStockCostingDefaults.ROWS.stream().map(Object::toString).collect(Collectors.toList());
    assertEquals(
        List.of(
            "30/30 → 702",
            "30/20 → 701",
            "30/10 → 706",
            "40/30 → 702",
            "40/20 → 701",
            "40/10 → 706",
            "70 → 60101",
            "120 → 60905",
            "120/лице → 442",
            "160 → 60905",
            "170 → 60905",
            "150 → 60906",
            "65 → 60909",
            "110 → 709",
            "60 → 709",
            "20 → 611"),
        actual);
  }

  @Test
  @DisplayName("всяка сметка от таблицата я има в BG_COA и не е групова (view)")
  void сметките_съществуват() throws IOException {
    Map<String, String> types = accountTypes();
    Set<String> problems = new TreeSet<>();
    for (BgStockCostingDefaults.Row row : BgStockCostingDefaults.ROWS) {
      String type = types.get(row.accountCode);
      if (type == null) {
        problems.add(row.accountCode + " липсва");
      } else if ("1".equals(type)) {
        problems.add(row.accountCode + " е групова (view) — не се осчетоводява по нея");
      }
    }
    assertTrue(problems.isEmpty(), problems.toString());
  }

  @Test
  @DisplayName("всеки код е от номенклатурата, осчетоводява се от склада и е в своята посока")
  void кодовете_и_посоките() {
    for (BgStockCostingDefaults.Row row : BgStockCostingDefaults.ROWS) {
      Set<BgStockMovementCodes.Direction> allowed = BgStockMovementCodes.allowed(row.movementCode);
      assertNotNull(allowed, row.toString());
      assertFalse(allowed.isEmpty(), row + ": код само по стойност не е за тази таблица");
      assertFalse(
          BgStockCostingRules.CODES_WITHOUT_POSTING.contains(row.movementCode), row.toString());
      assertTrue(
          row.inventoryType == null || Set.of("10", "20", "30").contains(row.inventoryType),
          row.toString());
    }
  }

  @Test
  @DisplayName(
      "таблицата минава собствените правила на политиката (без дубликати, лице само при 120)")
  void таблицата_минава_правилата() {
    List<com.axelor.apps.bglocalization.db.BgStockCostingAccountRule> rules = new ArrayList<>();
    for (BgStockCostingDefaults.Row row : BgStockCostingDefaults.ROWS) {
      com.axelor.apps.bglocalization.db.BgStockCostingAccountRule r =
          new com.axelor.apps.bglocalization.db.BgStockCostingAccountRule();
      r.setMovementCode(row.movementCode);
      r.setInventoryType(row.inventoryType);
      r.setWithResponsiblePartner(row.withResponsiblePartner);
      rules.add(r);
    }
    assertEquals(null, BgStockCostingRules.accountRulesRefusal(rules));
  }

  // ── журналът „Изписване на МЗ“ ──────────────────────────────────────────

  @Test
  @DisplayName("журналът STK е в BG_COA с importId 98, тип OTH и поредица 150")
  void журналът_е_в_bg_coa() throws IOException {
    List<Map<String, String>> journals = rows("account_journal.csv");
    List<Map<String, String>> stk =
        journals.stream()
            .filter(r -> BgStockCostingSetupService.JOURNAL_IMPORT_ID.equals(r.get("importId")))
            .collect(Collectors.toList());
    assertEquals(1, stk.size(), "един ред с importId 98");
    Map<String, String> row = stk.get(0);
    assertEquals(BgStockCostingSetupService.JOURNAL_CODE, row.get("code"));
    assertEquals(BgStockCostingSetupService.JOURNAL_NAME, row.get("name"));
    assertEquals(BgStockCostingSetupService.JOURNAL_TYPE_CODE, row.get("journalType.code"));
    assertEquals(BgStockCostingSetupService.SEQUENCE_IMPORT_ID, row.get("sequence_importId"));
    assertEquals(
        1, journals.stream().filter(r -> "STK".equals(r.get("code"))).count(), "кодът STK е един");
  }

  @Test
  @DisplayName("журналът приема видовете на всяка сметка от таблицата и на 30x")
  void журналът_приема_сметките() throws IOException {
    Map<String, String> row =
        rows("account_journal.csv").stream()
            .filter(r -> "STK".equals(r.get("code")))
            .findFirst()
            .orElseThrow();
    Set<String> valid = new HashSet<>(Arrays.asList(row.get("validAccountTypes").split("\\|")));
    Map<String, String> types = accountTypes();
    List<String> codes = new ArrayList<>(List.of("302", "303", "304"));
    BgStockCostingDefaults.ROWS.forEach(r -> codes.add(r.accountCode));
    for (String code : codes) {
      assertTrue(valid.contains(types.get(code)), code + " (вид " + types.get(code) + ")");
    }
    // Видовете в Java (заварените дружества) са същите като в CSV-то (новите).
    Map<String, String> technical = new HashMap<>();
    for (Map<String, String> t : rows("account_accountType.csv")) {
      technical.put(t.get("importId"), t.get("technicalTypeSelect"));
    }
    Set<String> fromCsv = valid.stream().map(technical::get).collect(Collectors.toSet());
    assertEquals(new HashSet<>(BgStockCostingSetupService.VALID_ACCOUNT_TYPES), fromCsv);
  }

  @Test
  @DisplayName("поредицата 150 е в BG_COA дума по дума като в Java, с версия от 2000")
  void поредицата_съвпада() throws IOException {
    BgStockTemplate.SequenceSpec spec = BgStockCostingSetupService.SEQUENCE;
    String row =
        String.format(
            "\"%s\";\"%s\";\"%s\";\"NUMBERS\";\"UPPERCASE\";\"1\";\"%d\";\"%s\";\"\";\"1\";0",
            spec.templateImportId,
            spec.code,
            spec.name,
            BgStockTemplate.SEQUENCE_PADDING,
            spec.prefix);
    assertTrue(lines("base_sequence.csv").contains(row), "липсва в BG_COA: " + row);
    assertTrue(lines("base_sequenceVersion.csv").contains("\"V150\";\"150\";\"2000-01-01\""));
    assertEquals(
        1,
        lines("base_sequence.csv").stream().filter(l -> l.startsWith("\"150\";")).count(),
        "importId 150 е един");
  }

  @Test
  @DisplayName("BG_COA връзва AccountConfig.bgStockIssueJournal към STK")
  void конфигурацията_сочи_журнала() throws IOException {
    Map<String, String> config = rows("account_accountConfig_journal.csv").get(0);
    assertEquals("STK", config.get("bgStockIssueJournal_code"));
    String chart = new String(Files.readAllBytes(CHART_CONFIG), StandardCharsets.UTF_8);
    assertTrue(
        chart.contains("<bind to=\"bgStockIssueJournal\" if=\"bgStockIssueJournal_code\""),
        "chart-config.xml връзва колоната");
  }
}
