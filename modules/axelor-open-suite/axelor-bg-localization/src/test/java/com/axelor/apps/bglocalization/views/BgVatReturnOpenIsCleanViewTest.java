package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ДДС ДЕКЛАРАЦИЯТА СЕ ОТВАРЯ „ЧИСТА“, А „ПРЕГЛЕД НА ФАЙЛОВЕТЕ“ НЕ ЗАПИСВА.
 *
 * <p>onLoad сетва преходните {@code bgPeriodYear}/{@code bgPeriodMonth}
 * (action-bg-vat-return-load-period) и {@code bgPackageStale}/{@code bgPackageInfo}
 * (checkPackageStale). Всеки setValue от действие прави формата „променена“ (*), освен ако полето
 * не е с {@code x-dirty="false"} (axelor-front scope.ts → canDirty). Бутон, който започва със
 * {@code save}, на „променена“ форма праща запис със {@code _original} от отварянето, а {@code
 * JPA.verify} сверява версиите и броя на редовете в пакетите и логовете — които onLoad вече е
 * променил → OptimisticLockException.
 */
class BgVatReturnOpenIsCleanViewTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/BgVatReturn.xml");
  private static final Path CONTROLLER =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/web/BgVatReturnController.java");
  private static final String FORM = "bg-vat-return-form";

  private static Document view() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(VIEW.toFile());
  }

  private static Element form(Document doc) {
    NodeList forms = doc.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element form = (Element) forms.item(i);
      if (FORM.equals(form.getAttribute("name"))) {
        return form;
      }
    }
    throw new AssertionError("няма " + FORM);
  }

  private static Map<String, Element> named(Element root, String tag) {
    Map<String, Element> out = new LinkedHashMap<>();
    NodeList nodes = root.getElementsByTagName(tag);
    for (int i = 0; i < nodes.getLength(); i++) {
      Element e = (Element) nodes.item(i);
      out.putIfAbsent(e.getAttribute("name"), e);
    }
    return out;
  }

  /** Полетата, които onLoad сетва: от action-record-ите в onLoad + setValue в onLoad метода. */
  private static Set<String> fieldsSetOnLoad(Document doc) throws Exception {
    Set<String> out = new LinkedHashSet<>();
    Map<String, Element> records = named(doc.getDocumentElement(), "action-record");
    String source = new String(Files.readAllBytes(CONTROLLER), StandardCharsets.UTF_8);
    for (String action : form(doc).getAttribute("onLoad").split(",")) {
      Element record = records.get(action.trim());
      if (record != null) {
        out.addAll(named(record, "field").keySet());
        continue;
      }
      if ("action-bg-vat-return-method-check-stale".equals(action.trim())) {
        int start = source.indexOf("public void checkPackageStale(");
        assertTrue(start > 0, "checkPackageStale изчезна");
        int end = source.indexOf("\n  }\n", start);
        Matcher m =
            Pattern.compile("setValue\\(\\s*\"([A-Za-z0-9_]+)\"")
                .matcher(source.substring(start, end));
        while (m.find()) {
          out.add(m.group(1));
        }
      }
    }
    return out;
  }

  @Test
  @DisplayName("Отварянето не прави формата „променена“: всяко поле от onLoad е x-dirty=\"false\"")
  void everythingOnLoadSetsIsNotDirty() throws Exception {
    Document doc = view();
    Set<String> onLoad = fieldsSetOnLoad(doc);
    assertTrue(
        onLoad.containsAll(
            Arrays.asList("bgPeriodYear", "bgPeriodMonth", "bgPackageStale", "bgPackageInfo")),
        "onLoad: " + onLoad);
    Map<String, Element> fields = named(form(doc), "field");
    List<String> dirtying = new ArrayList<>();
    for (String name : onLoad) {
      Element field = fields.get(name);
      assertNotNull(field, name + " не е във формата");
      if (!"false".equals(field.getAttribute("x-dirty"))) {
        dirtying.add(name);
      }
    }
    assertEquals(List.of(), dirtying, "onLoad сетва тези полета и формата става „*“");
  }

  @Test
  @DisplayName("„Преглед на файловете“ не записва формата преди прегледа")
  void previewDoesNotSave() throws Exception {
    Map<String, Element> buttons = named(form(view()), "button");
    for (String name : Arrays.asList("previewFilesBtn", "previewFilesTabBtn")) {
      Element button = buttons.get(name);
      assertNotNull(button, name);
      List<String> steps = new ArrayList<>();
      for (String s : button.getAttribute("onClick").split(",")) {
        steps.add(s.trim());
      }
      assertFalse(steps.contains("save"), name + ": " + steps);
      assertEquals(List.of("action-bg-vat-return-method-open-preview"), steps, name);
    }
  }
}
