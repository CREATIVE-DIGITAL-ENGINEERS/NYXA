package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.common.Inflector;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.db.mapper.PropertyType;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppBgLocalization;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * НАСТРОЙКИТЕ НА МОДУЛА СА ДОСТИЖИМИ ОТ ЕКРАНА — от картата на приложението и от менюто.
 *
 * <p>Полето „ЕИК на разработчика на софтуера (Д1, поле 53)“ трябва да има път от екрана.
 * Единствената форма с полето е {@code app-bg-localization-config-form}; без връзка към нея
 * „Конфигуриране“ на картата „BG Localization“ казва „No configuration required“.
 *
 * <p><b>Правилото на картата, възпроизведено тук буквално</b> — axelor-studio 3.5.0, {@code
 * AppController#configure}, декомпилирано: от контекста на картата се чете полето {@code "app" +
 * Inflector.camelize(code)}; само ако е налице, се търси {@code MetaView} с {@code type='form'},
 * {@code model=com.axelor.studio.db.App<Camel>} и {@code name LIKE '%-config-form'}. Нужното звено
 * е обратната връзка {@code App.appBgLocalization} ({@code domains/App.xml}).
 */
class BgAppConfigReachabilityTest {

  private static final Path APP_YML = Paths.get("src/main/resources/apps/bg-localization.yml");
  private static final Path APP_VIEW = Paths.get("src/main/resources/views/AppBgLocalization.xml");
  private static final Path MENU = Paths.get("src/main/resources/views/Menu.xml");
  private static final Path D1_VIEW = Paths.get("src/main/resources/views/bg-d1-views.xml");

  private static final String ACTION = "action-app-bg-localization-view";
  private static final String CONFIG_FORM = "app-bg-localization-config-form";

  @Test
  @DisplayName(
      "„Конфигуриране“ на картата: App носи appBgLocalization — правилото на AppController")
  void theAppCardFindsItsConfiguration() throws Exception {
    String code = codeOf(APP_YML);
    assertEquals("bg-localization", code, "кодът на приложението от bg-localization.yml");
    String camel = Inflector.getInstance().camelize(code);
    String field = "app" + camel;
    assertEquals("appBgLocalization", field, "\"app\" + Inflector.camelize(code)");

    Property link = Mapper.of(App.class).getProperty(field);
    assertNotNull(
        link,
        "⚠️ App няма поле „"
            + field
            + "“ — AppController#configure получава null и казва „No configuration required“,"
            + " без изобщо да търси формата (domains/App.xml)");
    assertEquals(PropertyType.ONE_TO_ONE, link.getType());
    assertEquals(AppBgLocalization.class, link.getTarget());
    assertEquals("app", link.getMappedBy(), "обратна страна на AppBgLocalization.app");

    // Второто условие на същото правило: форма *-config-form за модела
    // com.axelor.studio.db.App<Camel>.
    assertEquals(AppBgLocalization.class.getName(), "com.axelor.studio.db.App" + camel);
    assertEquals(
        1.0,
        count(
            APP_VIEW,
            "//form[substring(@name, string-length(@name) - 11) = '-config-form'"
                + " and @model='"
                + AppBgLocalization.class.getName()
                + "']"),
        "точно една *-config-form за AppBgLocalization");
    assertEquals(
        1.0,
        count(
            APP_VIEW, "//form[@name='" + CONFIG_FORM + "']//field[@name='bgSoftwareDeveloperEik']"),
        "и в нея — ЕИК-ът на разработчика (Д1, поле 53)");
  }

  @Test
  @DisplayName(
      "менюто и действието със старото име отварят СЪЩАТА форма — една форма, една стойност")
  void theMenuAndTheLegacyActionOpenTheSameForm() throws Exception {
    assertEquals(
        1.0,
        count(
            APP_VIEW,
            "//action-view[@name='"
                + ACTION
                + "']/view[@type='form'][@name='"
                + CONFIG_FORM
                + "']"),
        "действието (старото име — съвместимост с ръчно създадено меню) отваря config-формата");
    assertEquals(
        AppBgLocalization.class.getName(),
        string(APP_VIEW, "//action-view[@name='" + ACTION + "']/@model"));
    assertTrue(
        string(APP_VIEW, "//action-view[@name='" + ACTION + "']/context[@name='_showRecord']/@expr")
            .contains("__repo__(AppBgLocalization)"),
        "отваря ЕДИНСТВЕНИЯ запис на настройките, не празна нова форма");
    assertEquals(
        0.0,
        count(APP_VIEW, "//form[@name='app-bg-localization-form']"),
        "старата форма не се връща — две места за една стойност");
    assertEquals(
        1.0,
        count(MENU, "//menuitem[@parent='nap-root'][@action='" + ACTION + "']"),
        "менюто в кода, под НАП");
  }

  @Test
  @DisplayName("Д1: селекторите за период показват само периодите на фирмата на декларацията")
  void theD1PeriodsAreScopedToTheCompany() throws Exception {
    for (String field : new String[] {"bgDataPeriod", "bgSubmissionPeriod"}) {
      assertEquals(
          "self.year.company = :company",
          string(
              D1_VIEW,
              "//form[@name='bg-d1-declaration-form']//field[@name='" + field + "']/@domain"),
          field + ": без домейн селекторът показва периодите на всички фирми");
    }
  }

  private static String codeOf(Path yml) throws Exception {
    for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
      if (line.startsWith("code:")) {
        return line.substring("code:".length()).trim();
      }
    }
    throw new AssertionError("няма code: в " + yml);
  }

  private static Document parse(Path xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(false);
    return f.newDocumentBuilder().parse(xml.toFile());
  }

  private static double count(Path xml, String xpath) throws Exception {
    return (Double)
        XPathFactory.newInstance()
            .newXPath()
            .evaluate("count(" + xpath + ")", parse(xml), XPathConstants.NUMBER);
  }

  private static String string(Path xml, String xpath) throws Exception {
    return XPathFactory.newInstance().newXPath().evaluate(xpath, parse(xml));
  }
}
