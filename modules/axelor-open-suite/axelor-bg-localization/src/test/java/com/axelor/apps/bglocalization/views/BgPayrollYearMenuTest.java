package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Прекият път към заплатните години сочи КЪДЕТО ТРЯБВА, и целта още е за typeSelect = 2.
 *
 * <p><b>Защо изобщо.</b> Менюто ни е обвивка около чуждо действие — {@code hr.conf.payroll.year} от
 * {@code axelor-human-resource}. Обвивката не носи domain: тя наследява този на действието. Тоест
 * ако някой ден базовият Axelor смени domain-а (или самото действие изчезне), нашето меню ще
 * продължи да изглежда наред и ще води до чужд списък — година от друг тип или празен екран. Това е
 * тих провал по чужда вина, и точно затова проверката е у нас.
 *
 * <p>Тестът чете действието от JAR-а на HR модула в classpath-а, тоест от същата версия, която ще
 * върви и в производство.
 */
class BgPayrollYearMenuTest {

  private static final String MENU = "nyxa-payroll-year";
  private static final String ACTION = "hr.conf.payroll.year";

  private static Document parse(InputStream in) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    return f.newDocumentBuilder().parse(in);
  }

  @Test
  @DisplayName("Менюто сочи hr.conf.payroll.year и носи условието на hr-root")
  void ourMenuPointsAtTheHrAction() throws Exception {
    Path menuFile = Paths.get("src/main/resources/views/Menu-payroll.xml");
    assertTrue(Files.isRegularFile(menuFile), "не намирам " + menuFile);

    Document doc;
    try (InputStream in = Files.newInputStream(menuFile)) {
      doc = parse(in);
    }
    Element item = null;
    NodeList items = doc.getElementsByTagName("*");
    for (int i = 0; i < items.getLength(); i++) {
      Element e = (Element) items.item(i);
      if ("menuitem".equals(e.getLocalName()) && MENU.equals(e.getAttribute("name"))) {
        item = e;
      }
    }
    assertNotNull(item, "менюто " + MENU + " липсва");
    assertEquals(ACTION, item.getAttribute("action"), "прекият път сочи друго действие");

    // ⚠️ Без това условие менюто се вижда, докато целта му е скрита: hr-root носи същото.
    assertEquals(
        "__config__.app.isApp('employee')",
        item.getAttribute("if"),
        "прекият път трябва да носи условието на hr-root");
  }

  @Test
  @DisplayName("hr.conf.payroll.year още носи domain self.typeSelect = 2")
  void theHrActionStillFiltersPayrollYears() throws Exception {
    String domain = null;
    for (File jar : jarsOnClasspath()) {
      try (JarFile zip = new JarFile(jar)) {
        Enumeration<JarEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.getName().startsWith("views/") || !entry.getName().endsWith(".xml")) {
            continue;
          }
          Document doc;
          try (InputStream in = zip.getInputStream(entry)) {
            doc = parse(in);
          }
          NodeList all = doc.getElementsByTagName("*");
          for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if ("action-view".equals(e.getLocalName()) && ACTION.equals(e.getAttribute("name"))) {
              NodeList domains = e.getElementsByTagName("*");
              for (int j = 0; j < domains.getLength(); j++) {
                Element d = (Element) domains.item(j);
                if ("domain".equals(d.getLocalName())) {
                  domain = d.getTextContent().trim();
                }
              }
            }
          }
        }
      }
    }
    if (domain == null) {
      fail(
          "не намирам действието "
              + ACTION
              + " в нито един JAR от classpath-а. ⚠️ Или е преименувано, или модулът"
              + " axelor-human-resource го е махнал — прекият път ни води в никъде.");
    }
    assertEquals(
        "self.typeSelect = 2",
        domain,
        "⚠️ базовият Axelor е сменил domain-а на "
            + ACTION
            + ". Прекият път ни вече не води до ЗАПЛАТНИ години (typeSelect = 2). Проверете какво"
            + " показва действието и дали Nyxa трябва да сочи него.");
  }

  private static List<File> jarsOnClasspath() {
    List<File> jars = new ArrayList<>();
    for (String part : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (part.endsWith(".jar") && part.contains("human-resource")) {
        jars.add(new File(part));
      }
    }
    return jars;
  }
}
