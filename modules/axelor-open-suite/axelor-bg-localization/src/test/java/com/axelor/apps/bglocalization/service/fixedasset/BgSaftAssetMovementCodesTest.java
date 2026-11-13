package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОДЪТ ПО НАП НА ПРИЧИНАТА ЗА ОТПИСВАНЕ (SAF-T A1): селекцията е номенклатурата
 * AssetMovementTypes, а seed-ът дава код на всяка БГ причина, която е счетоводно извеждане.
 */
class BgSaftAssetMovementCodesTest {

  @Test
  @DisplayName("Селекцията bg.saft.asset.movement.select = кодовете на asset_movement_types.csv")
  void селекцията_е_номенклатурата() throws Exception {
    List<String> nomenclature = new ArrayList<>();
    for (String line : lines("/nomenclatures/nra/asset_movement_types.csv")) {
      String code = line.substring(0, line.indexOf(','));
      if (!"code".equals(code)) {
        nomenclature.add(code);
      }
    }
    assertEquals(13, nomenclature.size(), "13 кода по книгата 1.0.3");
    assertEquals(nomenclature, BgFixedAssetKindsTest.selection("bg.saft.asset.movement.select"));
  }

  @Test
  @DisplayName(
      "Seed-ът: продажба 20, брак 50, липса 90, дарение 120; само-ДАП и консервацията — без код;"
          + " en и fr са еднакви")
  void seedът() throws Exception {
    Map<String, String> en = codes("/apps/init-data/en/bg_asset_disposal_reason.csv");
    Map<String, String> fr = codes("/apps/init-data/fr/bg_asset_disposal_reason.csv");
    assertEquals(en, fr);
    assertEquals("20", en.get("BG_SALE"));
    assertEquals("50", en.get("BG_SCRAP"));
    assertEquals("90", en.get("BG_SHORT"));
    assertEquals("90", en.get("BG_SHORT_REC"));
    assertEquals("120", en.get("BG_DONAT"));
    assertEquals("20", en.get("BG_SCRAPSALE"), "продажба за скрап — да се потвърди");
    assertEquals("50", en.get("BG_FORCE"), "непреодолима сила — да се потвърди");
    assertEquals("", en.get("BG_TAXEND"), "само от ДАП — не е счетоводно извеждане");
    assertEquals("", en.get("BG_CONSRV"), "консервация — не е извеждане");
    List<String> allowed = BgFixedAssetKindsTest.selection("bg.saft.asset.movement.select");
    for (Map.Entry<String, String> e : en.entrySet()) {
      assertTrue(
          e.getValue().isEmpty() || allowed.contains(e.getValue()),
          e.getKey() + " → " + e.getValue() + " не е в номенклатурата");
    }
  }

  /** code → bgNapMovementCode от seed-а (точка и запетая, всичко в кавички). */
  private static Map<String, String> codes(String resource) throws Exception {
    List<String> lines = lines(resource);
    List<String> header = cells(lines.get(0));
    int code = header.indexOf("code");
    int nap = header.indexOf("bgNapMovementCode");
    assertTrue(code >= 0 && nap >= 0, resource + ": " + header);
    Map<String, String> out = new LinkedHashMap<>();
    for (String line : lines.subList(1, lines.size())) {
      List<String> row = cells(line);
      out.put(row.get(code), row.get(nap));
    }
    return out;
  }

  private static List<String> cells(String line) {
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

  private static List<String> lines(String resource) throws Exception {
    try (InputStream in = BgSaftAssetMovementCodesTest.class.getResourceAsStream(resource)) {
      assertTrue(in != null, "липсва " + resource);
      List<String> out = new ArrayList<>();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        if (!line.trim().isEmpty()) {
          out.add(line);
        }
      }
      return out;
    }
  }
}
