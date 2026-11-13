package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgNraNomenclatures;
import com.axelor.apps.bglocalization.service.stock.BgStockMovementCodes;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Пазачи на причините за складова корекция — seed-ът, който дава кода по „Stock movement“ на
 * корекцията (SAF-T SD.MG.10) и ДДС сигнала ѝ.
 *
 * <p>Таблицата {@link #EXPECTED} е решението, не препис на файла: файлът се сверява срещу нея ред
 * по ред, а мутационният тест доказва, че сверката хваща разминаване.
 */
class BgStockCorrectionReasonSeedTest {

  private static final String FILE = "bg_stock_correction_reason.csv";

  /** Демото на axelor-stock заема importId 1 със същото търсене без префикс. */
  private static final int FOREIGN_IMPORT_IDS_UP_TO = 1;

  /** Име → (код по Stock movement, ДДС сигнал). Празен сигнал — по кода. */
  static final Map<String, String[]> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("Брак", new String[] {"160", "ART_79"});
    EXPECTED.put("Изтекъл срок на годност", new String[] {"170", "ART_79"});
    EXPECTED.put("Безвъзмездно предоставени", new String[] {"150", "ART_6_3_2"});
    EXPECTED.put("Получени отстъпки в натура", new String[] {"60", ""});
    EXPECTED.put("Предоставени отстъпки в натура", new String[] {"65", ""});
    EXPECTED.put("Други", new String[] {"180", ""});
    EXPECTED.put("Лично ползване", new String[] {"180", "ART_6_3_1"});
    EXPECTED.put("Работно и униформено облекло, ЛПС", new String[] {"180", "NONE"});
    EXPECTED.put("Мостри и рекламни стоки", new String[] {"150", "ART_6_3_2_SAMPLES"});
    EXPECTED.put("Липса с виновно лице", new String[] {"120", "ART_79"});
  }

  /** Единствената причина, която изисква виновно лице (ИЗП: Дт 498 аналитично по лицето). */
  private static final String REQUIRES_PARTNER = "Липса с виновно лице";

  private static final Set<String> SIGNALS =
      Set.of("", "NONE", "ART_6_3_1", "ART_6_3_2", "ART_6_3_2_SAMPLES", "ART_79");

  private static Map<String, String[]> seed() {
    List<String> header = BgSeed.quotedHeader(FILE);
    Map<String, String[]> out = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      out.put(
          BgSeed.col(header, row, "name"),
          new String[] {
            BgSeed.col(header, row, "bgStockMovementCode"),
            BgSeed.col(header, row, "bgVatSignalSelect")
          });
    }
    return out;
  }

  /** Разликите между seed-а и решението; празно — съвпадат. */
  static List<String> differences(Map<String, String[]> expected, Map<String, String[]> actual) {
    List<String> out = new ArrayList<>();
    expected.forEach(
        (name, v) -> {
          String[] a = actual.get(name);
          if (a == null) {
            out.add("липсва причината „" + name + "“");
          } else if (!v[0].equals(a[0]) || !v[1].equals(a[1])) {
            out.add(String.format("„%s“: %s/%s вместо %s/%s", name, a[0], a[1], v[0], v[1]));
          }
        });
    actual.keySet().stream()
        .filter(n -> !expected.containsKey(n))
        .forEach(n -> out.add("излишна причина „" + n + "“"));
    return out;
  }

  @Test
  @DisplayName("десетте причини с кода и сигнала от решението на S0")
  void seed_е_пълен() {
    assertEquals(List.of(), differences(EXPECTED, seed()));
  }

  @Test
  @DisplayName("мутация: друг код, друг сигнал, изпусната и излишна причина се хващат")
  void проверката_хваща_мутациите() {
    Map<String, String[]> wrongCode = new LinkedHashMap<>(EXPECTED);
    wrongCode.put("Брак", new String[] {"180", "ART_79"});
    assertNotEquals(List.of(), differences(EXPECTED, wrongCode));

    Map<String, String[]> wrongSignal = new LinkedHashMap<>(EXPECTED);
    wrongSignal.put("Работно и униформено облекло, ЛПС", new String[] {"180", ""});
    assertNotEquals(List.of(), differences(EXPECTED, wrongSignal));

    Map<String, String[]> dropped = new LinkedHashMap<>(EXPECTED);
    dropped.remove("Лично ползване");
    assertNotEquals(List.of(), differences(EXPECTED, dropped));

    Map<String, String[]> extra = new LinkedHashMap<>(EXPECTED);
    extra.put("Кражба", new String[] {"120", "ART_79"});
    assertNotEquals(List.of(), differences(EXPECTED, extra));
  }

  @Test
  @DisplayName("само „Липса с виновно лице“ изисква лице")
  void лицето_се_изисква_само_при_липса() {
    List<String> header = BgSeed.quotedHeader(FILE);
    for (String[] row : BgSeed.quotedRows(FILE)) {
      String name = BgSeed.col(header, row, "name");
      assertEquals(
          REQUIRES_PARTNER.equals(name) ? "true" : "false",
          BgSeed.col(header, row, "bgRequiresPartner"),
          name);
    }
  }

  @Test
  @DisplayName("кодовете са от „Stock movement“, а сигналите — от селекцията")
  void кодовете_са_от_номенклатурата() {
    Set<String> codes = BgNraNomenclatures.movementTypes().keySet();
    seed()
        .forEach(
            (name, v) -> {
              assertTrue(codes.contains(v[0]), name + ": код " + v[0] + " не е в номенклатурата");
              assertTrue(SIGNALS.contains(v[1]), name + ": непознат сигнал " + v[1]);
            });
  }

  @Test
  @DisplayName("всеки код е допустим в поне една посока — нито един не е само по стойност")
  void посоката_е_възможна() {
    seed()
        .forEach(
            (name, v) -> {
              assertNotNull(BgStockMovementCodes.allowed(v[0]), name);
              assertFalse(
                  BgStockMovementCodes.allowed(v[0]).isEmpty(),
                  name + ": код " + v[0] + " е само по стойност и не може да стои на корекция");
            });
  }

  @Test
  @DisplayName("номерата са свободни: извън заетия 1 на демото и без повторение")
  void номерата_не_се_бият_с_демото() {
    List<String> header = BgSeed.quotedHeader(FILE);
    Set<String> ids = new LinkedHashSet<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      String id = BgSeed.col(header, row, "importId");
      assertTrue(ids.add(id), "importId " + id + " се повтаря");
      assertTrue(
          Integer.parseInt(id) > FOREIGN_IMPORT_IDS_UP_TO,
          "importId " + id + " е зает от демото на axelor-stock („Stock error“)");
    }
  }
}
