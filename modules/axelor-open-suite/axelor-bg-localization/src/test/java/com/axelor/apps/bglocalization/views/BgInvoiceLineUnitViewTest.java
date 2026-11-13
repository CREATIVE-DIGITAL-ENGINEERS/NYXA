/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * Пазачите на мерната единица по подразбиране върху ред от фактура (и при доставчик, и при клиент —
 * двете минават през един и същ {@code invoice-form} и един и същ {@code invoice-line-form}).
 *
 * <p>Структурен тест, защото и трите решения живеят в XML и нито едно от тях не гърми при счупване:
 *
 * <ul>
 *   <li><b>Редът в onNew.</b> {@code <attribute>} ЗАМЕСТВА стойността, не я добавя. Изпаднала
 *       оттам, стоковата група просто спира да се изпълнява — новият ред идва без проект, без вид
 *       операция, без аналитични оси и без скали, а билдът остава зелен.
 *   <li><b>Разрешаването на единицата.</b> Сочи се ВРЪЗКА от настройката на модула. Върнато по име,
 *       търсенето тихо спира да връща нещо при преименуване и тихо избира един от два реда при
 *       дубликат — и в двата случая без нито един признак.
 *   <li><b>Задължителността.</b> Стоковото {@code typeSelect == 0} НЕ важи за току-що отворения ред
 *       (клиентът борави с undefined, а в JavaScript {@code undefined == 0} е false), тоест точно в
 *       момента, в който правилото има значение, то отсъства.
 * </ul>
 */
class BgInvoiceLineUnitViewTest {

  private static final Path BG_VIEW = Paths.get("src/main/resources/views/InvoiceLine-bg.xml");
  private static final Path BASE_VIEW =
      Paths.get("../axelor-account/src/main/resources/views/InvoiceLine.xml");
  private static final Path APP_DOMAIN =
      Paths.get("src/main/resources/domains/AppBgLocalization.xml");
  private static final Path APP_VIEW = Paths.get("src/main/resources/views/AppBgLocalization.xml");
  private static final Path UNIT_DOMAIN =
      Paths.get("../axelor-base/src/main/resources/domains/Unit.xml");

  private static final Path BUDGET_VIEW =
      Paths.get("../axelor-budget/src/main/resources/views/InvoiceLine.xml");

  private static final String STOCK_GROUP = "action-account-invoice-line-onnew-group";
  private static final String BUDGET_GROUP = "action-group-budget-invoice-line-onnew";
  private static final String DEFAULT_UNIT =
      "action-bg-localization-invoice-line-record-default-unit";

  /** Формите, в които човек РЪЧНО отваря и записва ред: обикновената и порталната. */
  private static final List<String> LINE_FORMS =
      List.of("invoice-line-form", "invoice-line-client-form");

  /**
   * Заварената onNew стойност на всяка от двете форми — тоест това, върху което нашият {@code
   * <attribute>} пише. За {@code invoice-line-form} това НЕ е стоковата група: axelor-budget вече я
   * е подменил със своята.
   */
  private static final java.util.Map<String, String> INHERITED_ON_NEW =
      java.util.Map.of(
          "invoice-line-form", BUDGET_GROUP,
          "invoice-line-client-form", STOCK_GROUP);

  private static Document parse(Path path) throws Exception {
    assertTrue(Files.isRegularFile(path), "не намирам " + path.toAbsolutePath());
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // Без пространства от имена: тестът е структурен и XPath-ът остава четим.
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(new File(path.toString()));
  }

  private static String xpathString(Path path, String expression) throws Exception {
    XPath xpath = XPathFactory.newInstance().newXPath();
    return (String) xpath.evaluate(expression, parse(path), XPathConstants.STRING);
  }

  private static int count(Path path, String expression) throws Exception {
    XPath xpath = XPathFactory.newInstance().newXPath();
    return ((org.w3c.dom.NodeList) xpath.evaluate(expression, parse(path), XPathConstants.NODESET))
        .getLength();
  }

  // ------------------------------------------------------------------ веригата onNew

  @Test
  @DisplayName(
      "⚠️ Заварената onNew верига се пази цяла и остава ПЪРВА — нашето действие е последно")
  void inheritedOnNewChainSurvivesAndRunsFirst() throws Exception {
    for (String form : LINE_FORMS) {
      assertEquals(
          STOCK_GROUP,
          xpathString(BASE_VIEW, "//form[@name='" + form + "']/@onNew").replace(" ", ""),
          "стоковата onNew на " + form + " вече не е само " + STOCK_GROUP);

      assertEquals(
          INHERITED_ON_NEW.get(form) + "," + DEFAULT_UNIT,
          xpathString(
                  BG_VIEW,
                  "//form[@name='"
                      + form
                      + "']/extend[@target='/']/attribute[@name='onNew']/@value")
              .replace(" ", ""),
          "редът в onNew на "
              + form
              + " е грешен: заварената верига пълни реда, нашето действие само допълва празната"
              + " единица и затова върви СЛЕД нея");
    }
  }

  @Test
  @DisplayName(
      "⚠️ Бюджетната група е заварената върху invoice-line-form и ПАК започва със стоковата")
  void budgetGroupIsWhatWeInheritAndStillWrapsTheStockGroup() throws Exception {
    assertEquals(
        BUDGET_GROUP,
        xpathString(
            BUDGET_VIEW,
            "//form[@name='invoice-line-form']/extend[@target='/']/attribute[@name='onNew']/@value"),
        "axelor-budget вече не подменя onNew — нашата стойност сочи неговата група и трябва да се"
            + " върне към стоковата");

    assertEquals(
        STOCK_GROUP,
        xpathString(BUDGET_VIEW, "//action-group[@name='" + BUDGET_GROUP + "']/action[1]/@name"),
        "бюджетната група вече не започва със стоковата — стоковото поведение при нов ред вече не е"
            + " гарантирано от нея и веригата ни трябва да го изброи само");
  }

  /**
   * Пишем ПОСЛЕДНИ, защото сме най-надолу по зависимости (bg-localization → ai-ocr → budget). Трети
   * модул, който стъпи върху същия атрибут, или ще изяде нашата стъпка, или ние неговата — и в
   * двата случая мълчаливо.
   */
  @Test
  @DisplayName("⚠️ Никой трети модул не пише върху onNew на същите форми")
  void noThirdModuleWritesTheSameAttribute() throws Exception {
    java.util.List<String> writers = new java.util.ArrayList<>();
    try (java.util.stream.Stream<Path> modules = Files.list(Paths.get(".."))) {
      for (Path module :
          modules.filter(Files::isDirectory).collect(java.util.stream.Collectors.toList())) {
        Path views = module.resolve("src/main/resources/views");
        if (!Files.isDirectory(views)) continue;
        try (java.util.stream.Stream<Path> files = Files.list(views)) {
          for (Path file :
              files
                  .filter(p -> p.toString().endsWith(".xml"))
                  .collect(java.util.stream.Collectors.toList())) {
            String text =
                new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            if (!text.contains("invoice-line-form")) continue;
            for (String form : LINE_FORMS) {
              if (count(
                      file,
                      "//form[@name='" + form + "'][@extension='true']//attribute[@name='onNew']")
                  > 0) {
                writers.add(module.getFileName() + " → " + form);
              }
            }
          }
        }
      }
    }

    assertEquals(
        List.of(
            "axelor-bg-localization → invoice-line-client-form",
            "axelor-bg-localization → invoice-line-form",
            "axelor-budget → invoice-line-form"),
        writers.stream().sorted().collect(java.util.stream.Collectors.toList()),
        "появил се е нов модул, който подменя onNew на ред от фактура — сверете реда на модулите и"
            + " заварената стойност в INHERITED_ON_NEW, инак едната верига мълчаливо изчезва");
  }

  // ------------------------------------------------------- разрешаването на единицата

  @Test
  @DisplayName("⚠️ Единицата се сочи по ВРЪЗКА от настройката — никакво търсене по име или ред")
  void defaultUnitComesFromConfiguredReference() throws Exception {
    String expr =
        xpathString(
            BG_VIEW, "//action-record[@name='" + DEFAULT_UNIT + "']/field[@name='unit']/@expr");

    assertTrue(
        expr.contains("AppBgLocalization") && expr.contains("BgDefaultInvoiceLineUnit"),
        "изразът трябва да чете настройката на модула, а намерих: " + expr);

    for (String forbidden :
        List.of("findByName", "Брой", "importId", "order(", "labelToPrinting")) {
      assertFalse(
          expr.contains(forbidden),
          "изразът пак разчита на „"
              + forbidden
              + "“ — Unit няма нито код, нито уникален ключ, а name е translatable и редактируемо: "
              + expr);
    }
  }

  @Test
  @DisplayName("Вече попълнена единица не се пипа: стойност се подава само при unit == null")
  void defaultAppliesOnlyToAnEmptyUnit() throws Exception {
    assertEquals(
        "unit==null",
        xpathString(
                BG_VIEW, "//action-record[@name='" + DEFAULT_UNIT + "']/field[@name='unit']/@if")
            .replace(" ", ""),
        "без това условие onNew действието би прегазвало и вече избрана единица");

    assertEquals(
        1,
        count(BG_VIEW, "//action-record[@name='" + DEFAULT_UNIT + "']/field"),
        "действието пипа само мерната единица — всяко друго поле тук е извън обхвата");
  }

  @Test
  @DisplayName("⚠️ Предпоставката: Unit НЯМА траен идентификатор — само преводимо име")
  void unitStillHasNoStableIdentifier() throws Exception {
    assertEquals(
        "true",
        xpathString(UNIT_DOMAIN, "//entity[@name='Unit']/string[@name='name']/@translatable"),
        "ако Unit.name вече не е translatable, предпоставката за цялото решение се е променила");

    assertEquals(
        0,
        count(UNIT_DOMAIN, "//entity[@name='Unit']/*[@unique='true']"),
        "Unit е получил уникално поле — то е по-добрият идентификатор от настройка и решението"
            + " трябва да се преразгледа");
  }

  // ------------------------------------------------------------- забраната за празно

  @Test
  @DisplayName("⚠️ Задължителността хваща и НОВИЯ ред (undefined), не само записания (0)")
  void unitIsRequiredOnNormalLinesIncludingBrandNewOnes() throws Exception {
    for (String form : LINE_FORMS) {
      assertEquals(
          "typeSelect == 0",
          xpathString(BASE_VIEW, "//form[@name='" + form + "']//field[@name='unit']/@requiredIf"),
          "стоковото условие върху " + form + " се е променило — преразгледай заместването");

      assertEquals(
          "!typeSelect",
          xpathString(
              BG_VIEW,
              "//form[@name='"
                  + form
                  + "']/extend[@target=\"//field[@name='unit']\"]/attribute[@name='requiredIf']/@value"),
          "без null-безопасното условие празната единица минава точно при новия ред: изразът се"
              + " смята в JavaScript, където undefined == 0 е false");
    }
  }

  // ------------------------------------------------------------------ настройката

  @Test
  @DisplayName("Настройката съществува като връзка към Unit и се вижда в конфигурационния екран")
  void configurationFieldExistsAndIsVisible() throws Exception {
    assertEquals(
        "com.axelor.apps.base.db.Unit",
        xpathString(
            APP_DOMAIN,
            "//entity[@name='AppBgLocalization']/many-to-one[@name='bgDefaultInvoiceLineUnit']/@ref"),
        "настройката липсва или вече не сочи към Unit");

    assertEquals(
        1,
        count(
            APP_VIEW,
            "//form[@name='app-bg-localization-config-form']//field[@name='bgDefaultInvoiceLineUnit']"),
        "полето трябва да е видимо в екрана за настройки — инак няма как да се зададе"
            + " след празна инсталация");
  }
}
