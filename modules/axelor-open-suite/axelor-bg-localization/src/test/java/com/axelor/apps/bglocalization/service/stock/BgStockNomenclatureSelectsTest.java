package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.axelor.apps.bglocalization.service.saft.BgNraNomenclatures;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Селекциите на склада в {@code Selects.xml} са преписи на номенклатурите на SAF-T — и трябва да
 * останат точни: кодът е стойността, която отива във файла (SD.MG.10, MF.PS.6).
 *
 * <p>Сверката е срещу {@link BgNraNomenclatures} (файловете в {@code nomenclatures/nra}, пазени от
 * {@code BgNraNomenclatureIntegrityTest}), не срещу втори препис. Етикетът е „код – име“ с подрязан
 * краен интервал.
 */
class BgStockNomenclatureSelectsTest {

  private static final Path SELECTS = Paths.get("src/main/resources/views/Selects.xml");

  static Map<String, String> selection(String name) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    Document doc = f.newDocumentBuilder().parse(SELECTS.toFile());
    NodeList selections = doc.getElementsByTagNameNS("*", "selection");
    for (int i = 0; i < selections.getLength(); i++) {
      Element sel = (Element) selections.item(i);
      if (name.equals(sel.getAttribute("name"))) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList options = sel.getElementsByTagNameNS("*", "option");
        for (int j = 0; j < options.getLength(); j++) {
          Element o = (Element) options.item(j);
          out.put(o.getAttribute("value"), o.getTextContent());
        }
        return out;
      }
    }
    throw new AssertionError("липсва селекцията " + name + " в " + SELECTS);
  }

  /** Очакваната селекция от номенклатурата: стойност = код, етикет = „код – име“. */
  static Map<String, String> expected(Map<String, String> nomenclature) {
    Map<String, String> out = new LinkedHashMap<>();
    nomenclature.forEach((code, name) -> out.put(code, code + " – " + name.trim()));
    return out;
  }

  /** Разликите между селекцията и номенклатурата — празно значи пълно съвпадение и ред. */
  static List<String> differences(Map<String, String> expected, Map<String, String> actual) {
    List<String> out = new ArrayList<>();
    expected.forEach(
        (code, label) -> {
          if (!actual.containsKey(code)) {
            out.add("липсва код " + code);
          } else if (!label.equals(actual.get(code))) {
            out.add("код " + code + ": „" + actual.get(code) + "“ вместо „" + label + "“");
          }
        });
    actual.keySet().stream()
        .filter(code -> !expected.containsKey(code))
        .forEach(code -> out.add("излишен код " + code));
    if (out.isEmpty()
        && !new ArrayList<>(expected.keySet()).equals(new ArrayList<>(actual.keySet()))) {
      out.add("редът се различава от книгата");
    }
    return out;
  }

  @Test
  @DisplayName(
      "bg.saft.stock.movement.select = всичките 20 кода на „Stock movement“, в реда на книгата")
  void движенията_са_пълни() throws Exception {
    Map<String, String> nom = BgNraNomenclatures.movementTypes();
    assertEquals(20, nom.size(), "номенклатурата има 20 кода (книгата 1.0.2)");
    assertEquals(List.of(), differences(expected(nom), selection("bg.saft.stock.movement.select")));
  }

  @Test
  @DisplayName("bg.saft.inventory.type.select = петте вида на Nom_Inventory_Types")
  void видовете_са_пълни() throws Exception {
    Map<String, String> nom = BgNraNomenclatures.inventoryTypes();
    assertEquals(5, nom.size());
    assertEquals(List.of(), differences(expected(nom), selection("bg.saft.inventory.type.select")));
  }

  @Test
  @DisplayName("мутация: изпуснат, преименуван, излишен и разместен код се хващат")
  void проверката_хваща_мутациите() throws Exception {
    Map<String, String> good = expected(BgNraNomenclatures.movementTypes());

    Map<String, String> dropped = new LinkedHashMap<>(good);
    dropped.remove("65");
    assertNotEquals(List.of(), differences(good, dropped), "изпуснат код 65");

    Map<String, String> renamed = new LinkedHashMap<>(good);
    renamed.put("160", "160 – Брак");
    assertNotEquals(List.of(), differences(good, renamed), "друго име на 160");

    Map<String, String> extra = new LinkedHashMap<>(good);
    extra.put("999", "999 – Измислен");
    assertNotEquals(List.of(), differences(good, extra), "излишен код");

    Map<String, String> reordered = new LinkedHashMap<>();
    reordered.put("101", good.get("101"));
    good.forEach(reordered::putIfAbsent);
    assertNotEquals(List.of(), differences(good, reordered), "разместен ред");
  }
}
