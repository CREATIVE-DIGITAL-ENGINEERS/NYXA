package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Полетата на AOS, скрити по прегледа от 20.10.2026, наистина ги няма на екрана.
 *
 * <p>Изгледът, който вижда човекът, е базовият с приложените разширения. Тестът прави същото като
 * {@code ComputedViewXmlProcessor#processExtend} за {@code <attribute>}: намира целта по XPath в
 * базовия изглед и ѝ слага атрибута. После проверява, че всяко поле е скрито — само или чрез скрит
 * панел над него. Еднозначността на целите пази {@code BgViewExtendTargetTest}.
 */
class BgAosHiddenFieldsTest {

  private static final Path SUITE = BgDomainColumns.modulesRoot().resolve("axelor-open-suite");

  private static final Path OURS = BgDomainColumns.moduleRoot().resolve("src/main/resources/views");

  /**
   * Базов файл, вид и име на изгледа, нашият файл с разширението и полетата, които трябва да ги
   * няма.
   */
  private static final List<Object[]> CASES =
      List.of(
          new Object[] {
            "axelor-account/src/main/resources/views/AccountConfig.xml",
            "form",
            "account-config-form",
            "AccountConfig-bg.xml",
            List.of(
                "dasContactPartner",
                "dasReportJournalType",
                "exportPath",
                "irrecoverableStandardRateTax",
                "expenseTaxAccount")
          },
          new Object[] {
            "axelor-human-resource/src/main/resources/views/ExpenseLine.xml",
            "grid",
            "expense-line-grid",
            "ExpenseLine-bg.xml",
            List.of("totalTax", "untaxedAmount")
          },
          new Object[] {
            "axelor-human-resource/src/main/resources/views/ExpenseLine.xml",
            "grid",
            "expense-line-orphan-grid",
            "ExpenseLine-bg.xml",
            List.of("totalTax", "untaxedAmount")
          },
          new Object[] {
            "axelor-human-resource/src/main/resources/views/ExpenseLine.xml",
            "form",
            "expense-line-form",
            "ExpenseLine-bg.xml",
            List.of("totalTax", "untaxedAmount")
          });

  @Test
  @DisplayName("Скритите по прегледа полета са скрити в крайния изглед")
  @SuppressWarnings("unchecked")
  void полетата_ги_няма() throws Exception {
    List<String> visible = new ArrayList<>();
    for (Object[] c : CASES) {
      Element base = view(SUITE.resolve((String) c[0]), (String) c[1], (String) c[2], false);
      Element extension = view(OURS.resolve((String) c[3]), (String) c[1], (String) c[2], true);
      applyAttributes(base, extension);
      for (String field : (List<String>) c[4]) {
        Element node = (Element) xpath(base, ".//field[@name='" + field + "']");
        assertTrue(node != null, c[2] + ": няма поле " + field + " в базовия изглед");
        if (!hidden(node)) {
          visible.add(c[2] + " → " + field);
        }
      }
    }
    assertEquals(List.of(), visible, "видими полета, които прегледът скрива");
  }

  /** Полето е скрито само или чрез скрит родител (панел). */
  private static boolean hidden(Element node) {
    for (Node n = node; n instanceof Element; n = n.getParentNode()) {
      if ("true".equals(((Element) n).getAttribute("hidden"))) {
        return true;
      }
    }
    return false;
  }

  /** {@code <extend target><attribute name value/></extend>} — само атрибутите, като AOP. */
  private static void applyAttributes(Element base, Element extension) throws Exception {
    NodeList extendList = extension.getElementsByTagName("extend");
    for (int i = 0; i < extendList.getLength(); i++) {
      Element extend = (Element) extendList.item(i);
      String target = extend.getAttribute("target").replaceFirst("^//", ".//");
      Element node = (Element) xpath(base, target);
      assertTrue(node != null, "целта " + extend.getAttribute("target") + " я няма в базата");
      NodeList attributes = extend.getElementsByTagName("attribute");
      for (int j = 0; j < attributes.getLength(); j++) {
        Element a = (Element) attributes.item(j);
        node.setAttribute(a.getAttribute("name"), a.getAttribute("value"));
      }
    }
  }

  private static Element view(Path file, String kind, String name, boolean extension)
      throws Exception {
    assertTrue(Files.isRegularFile(file), "не намирам " + file);
    Document doc;
    try (InputStream in = Files.newInputStream(file)) {
      // Без пространства от имена — така AOP оценява целите на <extend>.
      doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
    }
    NodeList views = doc.getElementsByTagName(kind);
    for (int i = 0; i < views.getLength(); i++) {
      Element v = (Element) views.item(i);
      if (name.equals(v.getAttribute("name"))
          && extension == "true".equals(v.getAttribute("extension"))) {
        return v;
      }
    }
    throw new AssertionError(
        file + ": няма " + kind + " " + name + (extension ? " (разширение)" : ""));
  }

  private static Node xpath(Element context, String expression) throws Exception {
    return (Node)
        XPathFactory.newInstance().newXPath().evaluate(expression, context, XPathConstants.NODE);
  }
}
