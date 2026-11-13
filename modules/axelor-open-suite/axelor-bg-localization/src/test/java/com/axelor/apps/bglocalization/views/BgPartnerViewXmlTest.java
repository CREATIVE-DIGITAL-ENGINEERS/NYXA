/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Пазачите на закачането: българският вид на партньорската форма се задейства от ЛОКАЛИЗАЦИЯТА, а
 * идент. № по ДДС е видим безусловно.
 *
 * <p>Това е структурен тест, защото и трите докачвания са в XML — жив тригер, ред в onLoad веригата
 * и ред в onNew веригата. Нищо от тях не се вижда в Java кода, а сгрешеният РЕД не гърми: той
 * просто дава грешния етикет на екрана и билдът остава зелен.
 */
class BgPartnerViewXmlTest {

  private static final Path BG_VIEW = Paths.get("src/main/resources/views/Partner-bg.xml");
  private static final Path BASE_VIEW =
      Paths.get("../axelor-base/src/main/resources/views/Partner.xml");

  private static final String LOCALIZATION_ATTRS = "action-bg-partner-method-localization-attrs";
  private static final String K3_TITLE = "action-bg-partner-attrs-registration-code-title";
  private static final String VAT_CHECK = "action-bg-partner-check-vat-number";
  private static final String CODE_WARN = "action-bg-partner-registration-code-onchange-warn";
  private static final String CODE_SAVE = "action-bg-partner-validate-registration-code";

  /** Гейтът по българска идентичност: локализация първа, адрес резервен. */
  private static final String BG_GATE =
      "(localization?.country?.alpha2Code == 'BG' || mainAddress?.country?.alpha2Code == 'BG')";

  /** Целта на разширението върху идент. № по ДДС. */
  private static final String TAX_NBR_TARGET =
      "//panel[@name='otherPartnerDetailsPanel']/field[@name='taxNbr']";

  /**
   * Пътят, по който Axelor намира полето Локализация в СЛЕТИЯ partner-form.
   *
   * <p>⚠️ Без панел нарочно: axelor-crm трие panel[@name='settingsPanel'] с {@code <replace/>} и
   * вгражда полето наново в безименен панел. Целта с панела се намира в базовия изглед и НЕ се
   * намира на живо — нула съвпадения, ред в лога и мълчаливо липсващ onChange.
   */
  private static final String LOCALIZATION_TARGET = "//field[@name='localization']";

  private static final Path CRM_VIEW =
      Paths.get("../axelor-crm/src/main/resources/views/Partner.xml");

  private static Document parse(Path path) throws Exception {
    assertTrue(Files.isRegularFile(path), "не намирам " + path.toAbsolutePath());
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // Без пространства от имена: тестът е структурен и XPath-ът остава четим.
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(new File(path.toString()));
  }

  private static String xpathString(Document doc, String expression) throws Exception {
    XPath xpath = XPathFactory.newInstance().newXPath();
    return (String) xpath.evaluate(expression, doc, XPathConstants.STRING);
  }

  private static Node xpathNode(Document doc, String expression) throws Exception {
    XPath xpath = XPathFactory.newInstance().newXPath();
    return (Node) xpath.evaluate(expression, doc, XPathConstants.NODE);
  }

  private static int count(Document doc, String expression) throws Exception {
    XPath xpath = XPathFactory.newInstance().newXPath();
    NodeList hits = (NodeList) xpath.evaluate(expression, doc, XPathConstants.NODESET);
    return hits.getLength();
  }

  /** Имената на стъпките в една {@code <action-group>}, по ред. */
  private static List<String> steps(Node group) {
    List<String> names = new ArrayList<>();
    NodeList children = group.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element && "action".equals(child.getNodeName())) {
        names.add(((Element) child).getAttribute("name"));
      }
    }
    return names;
  }

  @Test
  @DisplayName("⚠️ Полето Локализация носи жив тригер — иначе БГ видът пак чака адрес и запис")
  void localizationFieldCarriesTheTrigger() throws Exception {
    String onChange =
        xpathString(
            parse(BG_VIEW),
            "//form[@name='partner-form']/extend[@target=\""
                + LOCALIZATION_TARGET
                + "\"]"
                + "/attribute[@name='onChange']/@value");

    assertEquals(
        LOCALIZATION_ATTRS + "," + K3_TITLE,
        onChange.replace(" ", ""),
        "тригерът на локализацията липсва или стъпките са разменени — K3 е ПОСЛЕДНА, за да остане"
            + " „ЕГН/ЛНЧ“ последната дума за физическо лице");
  }

  @Test
  @DisplayName("⚠️ Идент. № по ДДС е видим по подразбиране в ИЗГЛЕДА — за новия запис")
  void taxNbrIsVisibleByDefault() throws Exception {
    String hidden =
        xpathString(
            parse(BG_VIEW),
            "//form[@name='partner-form']/extend[@target=\""
                + TAX_NBR_TARGET
                + "\"]"
                + "/attribute[@name='hidden']/@value");

    // Базовият изглед го обявява hidden="true" и нищо не го отваря на нов запис: веригата виси само
    // на onLoad. Тук се мени подразбирането на изгледа; setAttr в контролера покрива записания.
    assertEquals(
        "false", hidden, "без това чуждестранният доставчик няма поле за чуждия си ДДС номер");
  }

  @Test
  @DisplayName("⚠️ onLoad веригата: заварено → локализация → K3, точно в този ред")
  void onLoadGroupOrder() throws Exception {
    Node group =
        xpathNode(
            parse(BG_VIEW),
            "//action-group[@name='action-method-partner-siren-nic-onload']"
                + "[@id='bg-localization-partner-siren-nic-onload']");
    assertTrue(group != null, "форкът на onLoad групата изчезна");

    assertEquals(
        Arrays.asList(
            "action-bg-partner-method-hide-field-on-partner-type-select",
            LOCALIZATION_ATTRS,
            K3_TITLE),
        steps(group),
        "редът е поведение: нашата стъпка е СЛЕД заварената (за да я застъпи) и ПРЕДИ K3");
  }

  @Test
  @DisplayName("⚠️ onNew веригата пуска същото — стойност от action-record не поражда onChange")
  void onNewGroupRunsTheSameSteps() throws Exception {
    Node ourGroup =
        xpathNode(
            parse(BG_VIEW),
            "//action-group[@name='action-group-base-partner-onnew'][@id='bg-localization-partner-onnew']");
    assertTrue(
        ourGroup != null,
        "форкът на onNew групата липсва — нов партньор с БГ локализация по"
            + " подразбиране би се отворил с генерични етикети");

    List<String> ours = steps(ourGroup);
    assertEquals(
        Arrays.asList(LOCALIZATION_ATTRS, K3_TITLE),
        ours.subList(ours.size() - 2, ours.size()),
        "двете добавени стъпки са ПОСЛЕДНИ: localization се пълни от втората базова стъпка"
            + " (action-partner-record-set-default) и чак тогава е в контекста");
  }

  @Test
  @DisplayName(
      "⚠️ Форкът на onNew преписва заварените стъпки ДОСЛОВНО — пазач срещу дрейф при ъпгрейд")
  void onNewForkMirrorsUpstream() throws Exception {
    Node baseGroup =
        xpathNode(parse(BASE_VIEW), "//action-group[@name='action-group-base-partner-onnew']");
    assertTrue(
        baseGroup != null, "базовата група изчезна от axelor-base — форкът виси във въздуха");

    List<String> upstream = steps(baseGroup);
    List<String> ours =
        steps(
            xpathNode(
                parse(BG_VIEW),
                "//action-group[@name='action-group-base-partner-onnew']"
                    + "[@id='bg-localization-partner-onnew']"));

    assertEquals(
        upstream,
        ours.subList(0, upstream.size()),
        "axelor-base е добавил, махнал или разместил стъпка в onNew веригата, а форкът ни още носи"
            + " старата. Пренесете промяната ТУК — иначе тя изчезва мълчаливо за всички партньорски"
            + " форми.");
  }

  @Test
  @DisplayName("⚠️ Полето Локализация остава ЕДНО и след разширението на axelor-crm")
  void localizationFieldStaysUniqueUnderCrm() throws Exception {
    // Това е доказателството, към което сочи BgViewExtendTargetTest#PROVEN_ELSEWHERE. Генеричният
    // пазач не може да реши случая, защото сверява срещу ПРИСТИННИЯ базов изглед; тук се чете
    // самото чуждо разширение.
    Document base = parse(BASE_VIEW);
    Document crm = parse(CRM_VIEW);

    assertEquals(
        1,
        count(base, "//form[@name='partner-form']//field[@name='localization']"),
        "базовият partner-form носи точно едно поле Локализация");
    assertEquals(
        1,
        count(
            base,
            "//form[@name='partner-form']//panel[@name='settingsPanel']/field[@name='localization']"),
        "и то е в settingsPanel — панелът, който crm трие");

    assertEquals(
        1,
        count(
            crm,
            "//form[@name='partner-form']/extend[@target=\"//panel[@name='settingsPanel']\"]/replace"),
        "axelor-crm вече не трие settingsPanel — тогава полетата стават ДВЕ и целта без панел"
            + " става двусмислена. Преценете отново кое от двете трябва да носи onChange.");
    assertEquals(
        1,
        count(crm, "//form[@name='partner-form']/extend/insert//field[@name='localization']"),
        "axelor-crm вгражда точно едно поле Локализация обратно");
  }

  @Test
  @DisplayName("⚠️ Идент. № по ДДС се проверява и НА ЖИВО, не само при запис")
  void taxNbrCarriesTheLiveCheck() throws Exception {
    Document bg = parse(BG_VIEW);

    assertEquals(
        VAT_CHECK,
        xpathString(
                bg,
                "//form[@name='partner-form']/extend[@target=\""
                    + TAX_NBR_TARGET
                    + "\"]"
                    + "/attribute[@name='onChange']/@value")
            .replace(" ", ""),
        "без това потребителят въвежда сгрешен номер и не научава нищо, докато не натисне „Запази“");

    // Заглавието на разширението остава — живата проверка е ДОБАВКА, не замяна.
    assertEquals(
        "Идент. № по ДДС",
        xpathString(
            bg,
            "//form[@name='partner-form']/extend[@target=\""
                + TAX_NBR_TARGET
                + "\"]"
                + "/attribute[@name='title']/@value"));
  }

  @Test
  @DisplayName("⚠️ Задействането ПРИ ЗАПИС е непроменено — живото е добавка, не замяна")
  void saveTimeCheckIsUntouched() throws Exception {
    Document bg = parse(BG_VIEW);

    // partner-supplier-form: собствен onSave, защото базовият няма такъв.
    assertEquals(
        "action-bg-partner-validate-registration-code," + VAT_CHECK,
        xpathString(
                bg,
                "//form[@name='partner-supplier-form']/extend[@target='/']"
                    + "/attribute[@name='onSave']/@value")
            .replace(" ", ""),
        "екранът „Доставчици“ остава единственият без базова onSave верига — не я въвеждаме, но"
            + " нашите две проверки трябва да останат на нея");

    // partner-form и partner-customer-form минават през форка на базовата onSave група, където
    // проверката е ПОСЛЕДНА — след <action name="save"/>, защото не отменя нищо.
    List<String> steps =
        steps(
            xpathNode(
                bg,
                "//action-group[@name='action-partner-group-onsave']"
                    + "[@id='bg-localization-partner-group-onsave']"));
    assertEquals(
        VAT_CHECK, steps.get(steps.size() - 1), "проверката на ДДС остава последната стъпка");
    assertTrue(steps.contains("save"), "форкът все още съдържа самия запис");
  }

  @Test
  @DisplayName("⚠️ Едно разширение стига за трите форми — двете вграждат partner-form")
  void allThreePartnerFormsInheritTheExtension() throws Exception {
    Document base = parse(BASE_VIEW);

    // Разширяваме САМО partner-form. Ако някоя от другите две спре да вгражда panel-include и си
    // напише свой панел, нито onChange-ът на ДДС, нито останалите БГ преправки биха стигнали до
    // нея — мълчаливо, защото и двете форми ще продължат да се отварят.
    for (String form : Arrays.asList("partner-customer-form", "partner-supplier-form")) {
      assertEquals(
          1,
          count(base, "//form[@name='" + form + "']/panel-include[@view='partner-form']"),
          form + " вече не вгражда partner-form — БГ разширенията не стигат до него");
    }
  }

  @Test
  @DisplayName("⚠️ Дефолтната локализация наистина стига до новия партньор")
  void defaultPartnerLocalizationReachesTheNewPartner() throws Exception {
    Document base = parse(BASE_VIEW);

    // Това е връзката между настройката, която листенерът запълва, и БГ вида на формата:
    // AppBase.defaultPartnerLocalization → localization на новия партньор → onNew форкът на БГ
    // гейта.
    // Премахне ли axelor-base тази стъпка или смени ли израза, запълването на настройката става
    // безсмислено — тихо, защото формата ще продължи да се отваря.
    assertEquals(
        1,
        count(
            base,
            "//action-record[@name='action-partner-record-set-default']"
                + "/field[@name='localization']"
                + "[contains(@expr,'getDefaultPartnerLocalization')]"),
        "onNew веригата вече не копира AppBase.defaultPartnerLocalization в новия партньор");

    List<String> onNewSteps =
        steps(xpathNode(base, "//action-group[@name='action-group-base-partner-onnew']"));
    assertTrue(
        onNewSteps.indexOf("action-partner-record-set-default") >= 0,
        "стъпката, която задава подразбиранията, липсва от базовата onNew верига");
  }

  @Test
  @DisplayName("⚠️ Живата проверка на кода е ТОСТ, а не модален диалог")
  void registrationCodeLiveCheckIsAToast() throws Exception {
    Document bg = parse(BG_VIEW);

    // <alert> е модален диалог: спира редакцията и ActionValidate записва „pending“, тоест
    // прекъсва веригата. <notify> минава по канала на response.setNotify — същия, по който се
    // показва и проверката на идент. № по ДДС.
    assertEquals(
        0,
        count(bg, "//action-validate[@name='" + CODE_WARN + "']/alert"),
        "живата проверка пак прекъсва въвеждането с диалог");
    assertEquals(
        2,
        count(bg, "//action-validate[@name='" + CODE_WARN + "']/notify"),
        "очакват се две съобщения — за ЕИК/Булстат и за ЕГН/ЛНЧ");

    // Куката остава същата: заварената група първа, нашата след нея.
    assertEquals(
        "action-group-partner-registration-code-onChange," + CODE_WARN,
        xpathString(
                bg,
                "//form[@name='partner-form']/extend[@target=\"//panel"
                    + "[@name='otherPartnerDetailsPanel']/field[@name='registrationCode']\"]"
                    + "/attribute[@name='onChange']/@value")
            .replace(" ", ""));
  }

  @Test
  @DisplayName("⚠️ Тостът пали само за БГ партньор — чужд SIREN не е счупен ЕИК")
  void liveCheckIsGatedOnBulgarianIdentity() throws Exception {
    Document bg = parse(BG_VIEW);

    // Без този гейт чужда фирма получава „невалиден“ по БЪЛГАРСКАТА контролна цифра — измерено в
    // BgIdentifierValidatorTest#foreignCompanyNumbersLookInvalidToTheBulgarianRule.
    assertEquals(
        2,
        count(
            bg,
            "//action-validate[@name='"
                + CODE_WARN
                + "']/notify[contains(@if,\""
                + BG_GATE
                + "\")]"),
        "и двете съобщения трябва да носят гейта по локализация ИЛИ адрес");

    // Кое от двете се казва решава Java-та, не XPath-ът: иначе bgIdentifierType се забравя и
    // физическо лице с БУЛСТАТ чете съобщение за ЕГН.
    assertEquals(
        2,
        count(
            bg,
            "//action-validate[@name='"
                + CODE_WARN
                + "']/notify[contains(@if,'isCompanyIdentifier')]"));
  }

  @Test
  @DisplayName("⚠️ Блокът ПРИ ЗАПИС е непроменен — живото става меко, enforcement-ът не")
  void saveTimeEnforcementIsUntouched() throws Exception {
    Document bg = parse(BG_VIEW);
    String action = "//action-validate[@name='" + CODE_SAVE + "']";

    // Твърдият отказ: един <error>, гейтнат по alpha2Code на адреса, през isBlockingViolation.
    assertEquals(1, count(bg, action + "/error"), "твърдият блок за БГ фирма изчезна");
    assertEquals(
        1,
        count(
            bg,
            action
                + "/error[contains(@if,\"mainAddress?.country?.alpha2Code == 'BG'\")]"
                + "[contains(@if,'isBlockingViolation')]"),
        "гейтът по държава или присъдата на <error> са променени");

    // И меката половина на записа остава модален <alert> — нарочно: там потребителят вече е
    // натиснал „Запази“ и има смисъл да бъде попитан, за разлика от момента на въвеждане.
    assertEquals(
        1,
        count(bg, action + "/alert[contains(@if,'isWarningViolation')]"),
        "предупреждението при запис изчезна или смени присъдата си");
  }

  @Test
  @DisplayName("⚠️ Тригерът сочи към истински метод на истински клас")
  void actionMethodPointsAtTheController() throws Exception {
    Document bg = parse(BG_VIEW);

    assertEquals(
        "com.axelor.apps.bglocalization.web.BgPartnerViewController",
        xpathString(bg, "//action-method[@name='" + LOCALIZATION_ATTRS + "']/call/@class"));
    assertEquals(
        "applyLocalizationAttrs",
        xpathString(bg, "//action-method[@name='" + LOCALIZATION_ATTRS + "']/call/@method"));

    assertEquals(
        "com.axelor.apps.bglocalization.web.BgPartnerVatNumberController",
        xpathString(bg, "//action-method[@name='" + VAT_CHECK + "']/call/@class"));
    assertEquals(
        "check",
        xpathString(bg, "//action-method[@name='" + VAT_CHECK + "']/call/@method"),
        "методът се вика и от onChange, и от onSave — име с „OnSave“ би излъгало за едното");
  }
}
