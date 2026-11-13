package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Всяко разширение на изглед носи непразен {@code id}.
 *
 * <p><b>Най-старият известен тих провал в модула.</b> {@code extension="true"} без {@code id} се
 * изхвърля ЦЯЛО при зареждане: разширението просто не се прилага, приложението тръгва нормално и
 * екранът изглежда като преди. Плащали сме го с дефекти.
 *
 * <p><b>Защо не го хваща {@link BgViewSchemaTest}.</b> Проверено, не предположено: в {@code
 * object-views.xsd} атрибутът {@code id} е НЕЗАДЪЛЖИТЕЛЕН, тоест разширение без него е напълно
 * валиден XML. Изхвърлянето е поведение на {@code ViewLoader}, а не на схемата — схемата няма как
 * да се обади. Двата теста покриват различни дупки и затова са два.
 *
 * <p>⚠️ Тестът НЕ проверява дали {@code id} е УНИКАЛЕН между модулите — за това би трябвало да види
 * и чуждите jar-ове. Проверява само, че го има: това е условието, при което разширението изобщо
 * стига до база.
 */
class BgViewExtensionIdTest {

  @Test
  @DisplayName("Всеки <form|grid ... extension=\"true\"> има непразен id")
  void everyExtensionCarriesAnId() throws Exception {
    Path viewsDir = Paths.get("src/main/resources/views");
    assertTrue(Files.isDirectory(viewsDir), "не намирам " + viewsDir.toAbsolutePath());

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);

    List<Path> files;
    try (Stream<Path> walk = Files.list(viewsDir)) {
      files = walk.filter(p -> p.toString().endsWith(".xml")).sorted().collect(Collectors.toList());
    }

    List<String> offenders = new ArrayList<>();
    int extensions = 0;

    for (Path p : files) {
      Document doc = factory.newDocumentBuilder().parse(p.toFile());
      List<String> lines = Files.readAllLines(p);
      NodeList all = doc.getElementsByTagName("*");
      for (int i = 0; i < all.getLength(); i++) {
        Node node = all.item(i);
        if (node.getNodeType() != Node.ELEMENT_NODE) {
          continue;
        }
        Element el = (Element) node;
        if (!"true".equals(el.getAttribute("extension"))) {
          continue;
        }
        extensions++;
        String id = el.getAttribute("id");
        if (id == null || id.trim().isEmpty()) {
          offenders.add(
              p.getFileName()
                  + " · <"
                  + el.getLocalName()
                  + " name=\""
                  + el.getAttribute("name")
                  + "\"> на ред "
                  + lineOf(lines, el.getAttribute("name")));
        }
      }
    }

    assertTrue(extensions > 0, "нито едно разширение — сгрешена ли е папката?");

    if (!offenders.isEmpty()) {
      fail(
          "разширения без id ("
              + offenders.size()
              + " от "
              + extensions
              + "). ⚠️ Такова разширение се изхвърля ЦЯЛО при зареждане, без ред в лога:\n  "
              + String.join("\n  ", offenders));
    }
  }

  /** Приблизителен ред за съобщението — DOM не пази позиция, а името е достатъчно рядко. */
  private static int lineOf(List<String> lines, String name) {
    for (int i = 0; i < lines.size(); i++) {
      if (name != null && !name.isEmpty() && lines.get(i).contains("\"" + name + "\"")) {
        return i + 1;
      }
    }
    return 0;
  }
}
