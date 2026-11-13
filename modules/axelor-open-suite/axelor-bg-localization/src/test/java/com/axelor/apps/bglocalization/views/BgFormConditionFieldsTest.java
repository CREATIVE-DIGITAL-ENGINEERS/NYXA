package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
 * ⚠️ ПОЛЕ, ЧЕТЕНО ОТ УСЛОВИЕ, ТРЯБВА ДА Е ВЪВ ФОРМАТА — инак условието е винаги лъжа.
 *
 * <p>Клиентът оценява {@code showIf}/{@code hideIf}/{@code readonlyIf}/{@code requiredIf} върху
 * полетата, които ФОРМАТА носи, а не върху целия запис: ако {@code statusSelect} не е във формата
 * на Д6, при „Генерирана“ „Печат (PDF)“ и „Закачи файловете към НАП“ липсват. Затова за всяка форма
 * тук: всяко поле на модела, което някое условие чете, е и {@code <field>} във формата (видимо или
 * скрито).
 */
class BgFormConditionFieldsTest {

  private static final Path VIEWS = Paths.get("src/main/resources/views");
  private static final Path DOMAINS = Paths.get("src/main/resources/domains");

  private static final List<String> CONDITIONS =
      List.of("showIf", "hideIf", "readonlyIf", "requiredIf");

  /** Идентификатор в израз; низовете в кавички се махат преди търсенето. */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  @Test
  @DisplayName("⚠️ Д6: бутоните четат statusSelect — полето е във формата")
  void theD6FormCarriesEveryFieldItsConditionsRead() throws Exception {
    assertFormCarriesItsConditionFields(
        "bg-d6-views.xml", "bg-d6-declaration-form", "BgD6Declaration.xml");
  }

  @Test
  @DisplayName("Д1: декларацията — всяко поле от условията е във формата")
  void theD1FormCarriesEveryFieldItsConditionsRead() throws Exception {
    assertFormCarriesItsConditionFields(
        "bg-d1-views.xml", "bg-d1-declaration-form", "BgD1Declaration.xml");
  }

  @Test
  @DisplayName("Д1: записът — всяко поле от условията е във формата")
  void theD1RecordFormCarriesEveryFieldItsConditionsRead() throws Exception {
    assertFormCarriesItsConditionFields("bg-d1-views.xml", "bg-d1-record-form", "BgD1Record.xml");
  }

  @Test
  @DisplayName("свидетелят вижда: statusSelect се чете от бутоните на Д6")
  void theWitnessSeesTheStatusCondition() throws Exception {
    Element form = form("bg-d6-views.xml", "bg-d6-declaration-form");
    assertTrue(
        conditionFields(form, domainFields("BgD6Declaration.xml")).contains("statusSelect"),
        "ако условията вече не четат statusSelect, свидетелят мери друго");
  }

  private static void assertFormCarriesItsConditionFields(
      String viewFile, String formName, String domainFile) throws Exception {
    Element form = form(viewFile, formName);
    Set<String> modelFields = domainFields(domainFile);
    Set<String> formFields = new LinkedHashSet<>();
    NodeList fields = form.getElementsByTagName("field");
    for (int i = 0; i < fields.getLength(); i++) {
      formFields.add(((Element) fields.item(i)).getAttribute("name"));
    }

    List<String> missing = new ArrayList<>();
    for (String read : conditionFields(form, modelFields)) {
      if (!formFields.contains(read)) {
        missing.add(read);
      }
    }
    assertEquals(
        Collections.emptyList(),
        missing,
        formName
            + ": условията четат полета, които формата НЕ носи — клиентът ги вижда празни и"
            + " условието е винаги лъжа. Добави ги като <field name=\"…\" hidden=\"true\"/>.");
  }

  /** Полетата на модела, които някое условие във формата чете. */
  private static Set<String> conditionFields(Element form, Set<String> modelFields) {
    Set<String> out = new LinkedHashSet<>();
    NodeList all = form.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element element = (Element) all.item(i);
      for (String attribute : CONDITIONS) {
        String expression = element.getAttribute(attribute);
        if (expression.isEmpty()) {
          continue;
        }
        Matcher m = IDENTIFIER.matcher(expression.replaceAll("'[^']*'|\"[^\"]*\"", ""));
        while (m.find()) {
          if (modelFields.contains(m.group())) {
            out.add(m.group());
          }
        }
      }
    }
    return out;
  }

  /** Имената на полетата на ентитито — всеки елемент с {@code name} в {@code <entity>}. */
  private static Set<String> domainFields(String domainFile) throws Exception {
    Document document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(DOMAINS.resolve(domainFile).toFile());
    NodeList entities = document.getElementsByTagName("entity");
    assertEquals(1, entities.getLength(), domainFile);
    Set<String> out = new LinkedHashSet<>();
    NodeList children = entities.item(0).getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element) {
        String name = ((Element) children.item(i)).getAttribute("name");
        if (!name.isEmpty()) {
          out.add(name);
        }
      }
    }
    return out;
  }

  private static Element form(String viewFile, String formName) throws Exception {
    Document document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(VIEWS.resolve(viewFile).toFile());
    NodeList forms = document.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element form = (Element) forms.item(i);
      if (formName.equals(form.getAttribute("name"))) {
        return form;
      }
    }
    assertNotNull(null, "формата " + formName + " липсва в " + viewFile);
    return null;
  }
}
