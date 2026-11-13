package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * „Генериране на ДДС дневници (NAP)“ е махнато, но менюто му остава като надгробие.
 *
 * <p>Axelor не трие ред от {@code meta_menu}, махнат от XML: в заварена база редът {@code
 * bg-compliance-vat-export} остава и сочи действие към модела BgVatReturnWizard, който вече го
 * няма. Надгробието го скрива при обновяване на модула, без SQL — но само ако е построено така, че
 * да сработи: СЪЩОТО name, БЕЗ id (иначе е нов ред, а заваренят остава видим), {@code
 * hidden="true"} и ЕДИН-единствен такъв ред (вторият без id се брои за повторение и се изхвърля).
 */
class BgVatLedgerMenuTombstoneTest {

  private static final String MENU = "bg-compliance-vat-export";
  private static final Path VIEWS = Paths.get("src/main/resources/views");

  @Test
  @DisplayName("Надгробието на „Генериране на ДДС дневници“ скрива заварения ред")
  void tombstoneHidesTheExistingRow() throws Exception {
    List<Element> found = new ArrayList<>();
    try (Stream<Path> files = Files.list(VIEWS)) {
      for (Path file :
          (Iterable<Path>) files.filter(f -> f.toString().endsWith(".xml"))::iterator) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (InputStream in = Files.newInputStream(file)) {
          NodeList all = factory.newDocumentBuilder().parse(in).getElementsByTagName("*");
          for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if ("menuitem".equals(e.getLocalName()) && MENU.equals(e.getAttribute("name"))) {
              found.add(e);
            }
          }
        }
      }
    }

    assertEquals(1, found.size(), MENU + " трябва да е точно един <menuitem> във views/");
    Element tombstone = found.get(0);
    assertEquals("true", tombstone.getAttribute("hidden"), MENU + ": без hidden=\"true\"");
    assertTrue(
        tombstone.getAttribute("id").isEmpty(),
        MENU + ": с id е НОВ ред в meta_menu, а заваренят остава видим");
    assertTrue(
        tombstone.getAttribute("action").isEmpty(),
        MENU + ": действието на махнатия екран го няма");
  }
}
