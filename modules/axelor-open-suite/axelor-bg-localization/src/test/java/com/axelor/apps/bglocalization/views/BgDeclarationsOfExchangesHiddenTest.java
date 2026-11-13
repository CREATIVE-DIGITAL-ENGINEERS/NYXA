package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * МЕНЮТО DEB/DES („Декларации за обмен“) Е СКРИТО (сверка 04.11.2026).
 *
 * <p>{@code supplychain-declarations-of-exchanges} на axelor-supplychain е преопределено в {@code
 * Menu-account-hide.xml} с {@code id} и {@code hidden="true"}. Без {@code id} редът се брои за
 * повторение и чуждото меню остава видимо — общият пазач {@link BgHiddenMenuTest} хваща това за
 * всички скривания; тук е самото решение: ТОВА меню е скрито. Данните (DeclarationOfExchanges) не
 * се пипат.
 */
class BgDeclarationsOfExchangesHiddenTest {

  private static final String MENU = "supplychain-declarations-of-exchanges";

  private static final Path HIDE_FILE = Paths.get("src/main/resources/views/Menu-account-hide.xml");

  private static final Path RETIRED =
      Paths.get("../nyxa-security/src/main/resources/nyxa-security/nx_retired.csv");

  private static final Path MENU_GRANTS =
      Paths.get("../nyxa-security/src/main/resources/nyxa-security/nx_menu.csv");

  @Test
  @DisplayName("„Декларации за обмен“ е скрито с id преопределяне")
  void менюто_е_скрито() throws Exception {
    List<Element> found = new ArrayList<>();
    Document doc;
    try (InputStream in = Files.newInputStream(HIDE_FILE)) {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      doc = f.newDocumentBuilder().parse(in);
    }
    NodeList all = doc.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      if ("menuitem".equals(e.getLocalName()) && MENU.equals(e.getAttribute("name"))) {
        found.add(e);
      }
    }
    assertEquals(1, found.size(), MENU + " трябва да е преопределено точно веднъж в " + HIDE_FILE);
    Element item = found.get(0);
    assertEquals("true", item.getAttribute("hidden"), MENU + " не е hidden=\"true\"");
    assertFalse(item.getAttribute("id").isEmpty(), MENU + " без id не се прилага");
    assertEquals(
        "account-root-periodic-processing",
        item.getAttribute("parent"),
        "преопределящият ред заменя чуждия изцяло — родителят трябва да е същият");
  }

  @Test
  @DisplayName("никоя роля не получава менюто, а заварените права са отнети")
  void правата_са_отнети() throws Exception {
    String grants = new String(Files.readAllBytes(MENU_GRANTS), StandardCharsets.UTF_8);
    assertFalse(grants.contains("\"" + MENU + "\""), MENU + " е още в " + MENU_GRANTS);

    String retired = new String(Files.readAllBytes(RETIRED), StandardCharsets.UTF_8);
    for (String role :
        List.of("accounting.read", "accounting.write", "all.read", "all.write", "client.admin")) {
      assertTrue(
          retired.contains("\"role-menu\";\"" + role + "\";\"" + MENU + "\""),
          role + " не е отнета от " + MENU + " в " + RETIRED);
    }
  }
}
