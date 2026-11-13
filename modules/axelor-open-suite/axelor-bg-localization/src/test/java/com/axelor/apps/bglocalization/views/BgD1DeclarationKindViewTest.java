package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ВИДЪТ НА ДЕКЛАРАЦИЯТА НА ЕКРАНА — задължителен за НОВ запис, и само там.
 *
 * <p><b>⚠️ ЗАЩО НЕ В ДОМЕЙНА.</b> {@code required="true"} в домейна се превежда в {@code @NotNull}
 * върху генерирания клас. Бийн-валидацията в ПРОДУКЦИЯ е включена ({@code hibernate-validator} е
 * зависимост на платформата, {@code libs.gradle:99}; REST слоят лови {@code
 * ConstraintViolationException}), тоест всеки запис на ЗАВАРЕНА декларация — смяна на статуса,
 * закачане на входящ номер — щеше да пада заради колона, която онзи ред няма как да е имал. Същото
 * важи и за бутона „Генерирай“: неговият {@code onClick} започва със {@code save}, тоест действието
 * изобщо не би тръгнало.
 *
 * <p><b>⚠️ И ЗАЩО ТОЗИ ТЕСТ Е НУЖЕН.</b> Интеграционните тестове не могат да отговорят: {@code
 * src/test/resources/META-INF/persistence.xml:17} изключва бийн-валидацията изрично. Тоест зелен
 * запис там НЕ е свидетелство за продукцията — свидетелят е {@code BgD1DeclarationKindTest} (над
 * валидатора) и този файл (над атрибутите на изгледа).
 *
 * <p>⚠️ Изгледът е ЛЮБЕЗНОСТ към човека пред екрана, а не пазач — REST и импортът минават покрай
 * него. Затова тук се пази точно каквото изгледът може да обещае: че новото се ражда с вид.
 */
class BgD1DeclarationKindViewTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/bg-d1-views.xml");
  private static final Path DOMAIN = Paths.get("src/main/resources/domains/BgD1Declaration.xml");

  @Test
  @DisplayName("⚠️ Видът е задължителен САМО за нов запис — requiredIf=\"id == null\"")
  void theKindIsRequiredOnlyForANewRecord() throws Exception {
    Element field = fieldOf("bg-d1-declaration-form", "bgDeclarationKind");

    assertNotNull(field, "полето го няма във формата — декларирана функционалност без изглед");
    assertEquals(
        "id == null",
        field.getAttribute("requiredIf"),
        "⚠️ Условието пита ТОЧНО новия запис. Без него нов запис може да се запише без вид и"
            + " изборът на фишове да зависи от прочита „липса ⇒ редовна“ там, където човекът е"
            + " искал коригираща.");
    assertFalse(
        field.hasAttribute("required"),
        "⚠️ И НЕ Е БЕЗУСЛОВНО задължително: безусловното върху формата спира редакцията на всяка"
            + " заварена декларация, не само създаването.");
  }

  @Test
  @DisplayName("⚠️ В ДОМЕЙНА полето НЕ е required — инак @NotNull вали заварените редове")
  void theDomainDoesNotMakeTheKindRequired() throws Exception {
    String domain = new String(Files.readAllBytes(DOMAIN), StandardCharsets.UTF_8);
    int at = domain.indexOf("name=\"bgDeclarationKind\"");
    assertTrue(at > 0, "полето изчезна от домейна");
    String declaration = domain.substring(domain.lastIndexOf('<', at), domain.indexOf("/>", at));

    assertFalse(
        declaration.contains("required=\"true\""),
        "⚠️ Върнато в домейна, required прави @NotNull и ВСЕКИ запис на заварен ред пада — а това"
            + " се вижда САМО в продукция: тестовата персистентност изключва бийн-валидацията"
            + " (persistence.xml:17). Декларацията: "
            + declaration);
    assertTrue(
        declaration.contains("default=\"REGULAR\""),
        "⚠️ Но подразбирането ОСТАВА: то е инициализатор на полето, тоест всеки НОВ обект се ражда"
            + " редовен без никой да му задава вид. Точно то замества махнатото required: "
            + declaration);
  }

  @Test
  @DisplayName("⚠️ Бутонът „Генерирай“ наистина започва със save — затова required щеше да го спре")
  void theGenerateButtonSavesFirst() throws Exception {
    Element button = buttonOf("bg-d1-declaration-form", "generateBtn");

    assertNotNull(button, "бутонът изчезна — свидетелят сочи грешно място");
    assertTrue(
        button.getAttribute("onClick").startsWith("save"),
        "⚠️ Ето защо въпросът „спира ли записът“ е същият като „спира ли действието“: действието"
            + " МИНАВА през записа. onClick: "
            + button.getAttribute("onClick"));
  }

  private static Element fieldOf(String formName, String fieldName) throws Exception {
    return elementOf(formName, "field", fieldName);
  }

  private static Element buttonOf(String formName, String buttonName) throws Exception {
    return elementOf(formName, "button", buttonName);
  }

  private static Element elementOf(String formName, String tag, String name) throws Exception {
    Document document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(VIEW.toFile());
    NodeList forms = document.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element form = (Element) forms.item(i);
      if (!formName.equals(form.getAttribute("name"))) {
        continue;
      }
      NodeList nodes = form.getElementsByTagName(tag);
      for (int j = 0; j < nodes.getLength(); j++) {
        Element element = (Element) nodes.item(j);
        if (name.equals(element.getAttribute("name"))) {
          return element;
        }
      }
    }
    return null;
  }
}
