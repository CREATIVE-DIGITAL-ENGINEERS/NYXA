package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgNraNomenclatures;
import com.axelor.apps.bglocalization.service.stock.BgSaftInventoryTypes;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Пазачи на БГ колоните на склада в шаблона BG_COA: видът на запаса на семейството ({@code
 * base_productFamily.csv}) и складовата сметка 30x на реда AccountManagement на семейството ({@code
 * account_accountManagement.csv}).
 *
 * <p>Двата източника — шаблонът за нови дружества и {@link BgSaftInventoryTypes} за заварените при
 * стартиране — трябва да казват едно и също. Тестът ги сверява, иначе нова и заварена база биха
 * подали различен вид или сметка.
 *
 * <p>⚠️ Без bind в {@code chart-config.xml} колоната се пренебрегва мълчаливо: свойството е от този
 * модул, а CSVBinder праща непознатото в {@code handleDummyBind}. Затова и bind-ът се проверява.
 */
class BgStockFamilySeedTest {

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path CHART_CONFIG =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/chart-config.xml");

  /** {@code AccountManagement.typeSelect} = 1 — ред за продукт или семейство. */
  private static final String PRODUCT_ROW = "1";

  @Test
  @DisplayName("видът на семействата в BG_COA = видът при стартиране, и е от Nom_Inventory_Types")
  void видът_съвпада() throws IOException {
    Set<String> types = BgNraNomenclatures.inventoryTypes().keySet();
    Map<String, String> seed = new LinkedHashMap<>();
    for (Map<String, String> row : data("base_productFamily.csv")) {
      String type = row.get("bgSaftInventoryType");
      assertTrue(type.isEmpty() || types.contains(type), row.get("code") + ": вид " + type);
      if (!type.isEmpty()) {
        seed.put(row.get("code"), type);
      }
    }
    assertEquals(BgSaftInventoryTypes.FAMILY_TYPES, seed);
  }

  @Test
  @DisplayName("услугите, ДМА, ресторантът и хотелът не са стоково-материален запас")
  void не_са_смз() throws IOException {
    for (Map<String, String> row : data("base_productFamily.csv")) {
      if (Set.of("SERV", "FIXA", "REST9", "HOTEL9").contains(row.get("code"))) {
        assertEquals("", row.get("bgSaftInventoryType"), row.get("code"));
      }
    }
  }

  @Test
  @DisplayName("складовата сметка на семействата = сметката при стартиране и съществува в BG_COA")
  void сметката_съвпада() throws IOException {
    Map<String, String> familyCode = new LinkedHashMap<>();
    for (Map<String, String> row : data("base_productFamily.csv")) {
      familyCode.put(row.get("importId"), row.get("code"));
    }
    Set<String> accounts =
        data("account_account.csv").stream().map(r -> r.get("code")).collect(Collectors.toSet());

    Map<String, String> seed = new LinkedHashMap<>();
    for (Map<String, String> row : data("account_accountManagement.csv")) {
      String stock = row.get("bgStockAccount_code");
      if (stock.isEmpty()) {
        continue;
      }
      assertEquals(PRODUCT_ROW, row.get("typeSelect"), "сметка само на ред за семейство");
      assertTrue(accounts.contains(stock), "сметка " + stock + " я няма в account_account.csv");
      assertTrue(stock.startsWith("30"), "складовата сметка е от група 30: " + stock);
      seed.put(familyCode.get(row.get("productFamily_importId")), stock);
    }
    assertEquals(BgSaftInventoryTypes.FAMILY_STOCK_ACCOUNTS, seed);
  }

  @Test
  @DisplayName("покупната сметка на PROD остава 60101 — складовата 303 е отделно поле")
  void покупката_не_е_пипната() throws IOException {
    for (Map<String, String> row : data("account_accountManagement.csv")) {
      if ("3".equals(row.get("productFamily_importId"))) {
        assertEquals("60101", row.get("purchaseAccount_code"));
        assertEquals("303", row.get("bgStockAccount_code"));
      }
    }
  }

  @Test
  @DisplayName("chart-config.xml свързва двете колони, иначе вносът ги пренебрегва мълчаливо")
  void bind_ът_го_има() throws IOException {
    String config = new String(Files.readAllBytes(CHART_CONFIG), StandardCharsets.UTF_8);
    assertTrue(
        input(config, "base_productFamily.csv")
            .contains("<bind column=\"bgSaftInventoryType\" to=\"bgSaftInventoryType\""));
    String am = input(config, "account_accountManagement.csv");
    assertTrue(
        am.contains(
            "search=\"self.code = :bgStockAccount_code and self.company.id = :_companyId\""),
        "сметката се търси по код И дружество");
    assertTrue(am.contains("to=\"bgStockAccount\""));
  }

  private static String input(String config, String file) {
    int start = config.indexOf("<input file=\"" + file + "\"");
    assertTrue(start >= 0, file);
    int end = config.indexOf("</input>", start);
    assertTrue(end > start, file);
    return config.substring(start, end);
  }

  private static List<Map<String, String>> data(String file) throws IOException {
    Path path = COA.resolve(file);
    assertTrue(Files.isRegularFile(path), "не намирам " + path);
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    assertFalse(lines.isEmpty(), file);
    List<String> header = split(lines.get(0));
    List<Map<String, String>> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().isEmpty()) {
        continue;
      }
      List<String> cells = split(lines.get(i));
      assertEquals(header.size(), cells.size(), file + " ред " + (i + 1));
      Map<String, String> row = new LinkedHashMap<>();
      for (int c = 0; c < header.size(); c++) {
        row.put(header.get(c), cells.get(c));
      }
      out.add(row);
    }
    return out;
  }

  /** Цепи по „;“, зачитайки кавичките. */
  private static List<String> split(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (c == ';' && !quoted) {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString());
    return cells;
  }
}
