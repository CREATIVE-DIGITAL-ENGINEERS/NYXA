package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * W4 — ПРОМЯНАТА НА ПЕРАТА И НА ЗАМРАЗЕНИТЕ ВХОДОВЕ ПУСКА calculate(), НЕ recompute().
 *
 * <p>{@code action-bg-payslip-recompute} само сумира: с него на onChange перо 180 с въведени 200,00
 * остава със сума 0,00, а вноските и данъкът — от предишното пресмятане. Свидетелят чете изгледа и
 * контролера, защото тъкмо връзката между тях е мястото на дефекта.
 */
class BgPaySlipCalculateViewTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/bg-payslip-views.xml");
  private static final Path CONTROLLER =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/web/BgPaySlipController.java");
  private static final String CALCULATE = "action-bg-payslip-calculate";
  private static final String RECALCULATE = "action-bg-payslip-recalculate";

  private static Document view() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(VIEW.toFile());
  }

  private static Map<String, Element> formFields(Document doc) {
    Map<String, Element> out = new LinkedHashMap<>();
    NodeList forms = doc.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element form = (Element) forms.item(i);
      if (!"bg-payslip-form".equals(form.getAttribute("name"))) {
        continue;
      }
      NodeList fields = form.getElementsByTagName("field");
      for (int j = 0; j < fields.getLength(); j++) {
        Element f = (Element) fields.item(j);
        out.putIfAbsent(f.getAttribute("name"), f);
      }
    }
    return out;
  }

  private static boolean callsCalculate(Element field) {
    return Arrays.asList(field.getAttribute("onChange").split(",")).contains(CALCULATE);
  }

  @Test
  @DisplayName("W4 · промяна на перата → action-bg-payslip-calculate")
  void theLineListCallsCalculate() throws Exception {
    Element lines = formFields(view()).get("bgPaySlipLineList");
    assertNotNull(lines, "перата изчезнаха от формата");
    assertEquals(
        CALCULATE,
        lines.getAttribute("onChange"),
        "⚠️ recompute() само сумира: перо 180 с въведени 200,00 остава 0,00 и фишът се потвърждава"
            + " без бонуса");
  }

  @Test
  @DisplayName("W4 · всеки замразен вход пресмята фиша наново")
  void everyFrozenInputCallsCalculate() throws Exception {
    Map<String, Element> fields = formFields(view());
    for (String name :
        Arrays.asList(
            "bgWorkingDaysInPeriod",
            "bgWorkedDays",
            "bgBaseSalary",
            "bgAutoFillBaseSalary",
            "bgNoOtherPayerIncome",
            "bgOtherPayerInsurableIncome")) {
      Element f = fields.get(name);
      assertNotNull(f, name + " изчезна от формата");
      assertTrue(
          callsCalculate(f), name + " не пресмята фиша: onChange=" + f.getAttribute("onChange"));
    }
    assertEquals(
        "action-bg-payslip-record-manual-base-salary," + CALCULATE,
        fields.get("bgBaseSalary").getAttribute("onChange"),
        "⚠️ РЕДЪТ Е ПРАВИЛОТО: признакът пада ПРЕДИ пресмятането, инак то презаписва ръчната"
            + " заплата");
  }

  private static String methodOf(Document doc, String action) {
    NodeList actions = doc.getElementsByTagName("action-method");
    String method = null;
    for (int i = 0; i < actions.getLength(); i++) {
      Element a = (Element) actions.item(i);
      assertFalse(
          "action-bg-payslip-recompute".equals(a.getAttribute("name")),
          "старият вход към recompute() е върнат");
      if (action.equals(a.getAttribute("name"))) {
        Element call = (Element) a.getElementsByTagName("call").item(0);
        assertEquals(
            "com.axelor.apps.bglocalization.web.BgPaySlipController", call.getAttribute("class"));
        method = call.getAttribute("method");
      }
    }
    return method;
  }

  @Test
  @DisplayName("W4 · действието вика контролера, контролерът — услугата; recompute няма вход")
  void theActionReachesTheService() throws Exception {
    Document doc = view();
    assertEquals("calculate", methodOf(doc, CALCULATE), "onChange липсва или вика друг метод");
    assertEquals("recalculate", methodOf(doc, RECALCULATE), "„Преизчисли“ вика друг метод");
    String source = new String(Files.readAllBytes(CONTROLLER), StandardCharsets.UTF_8);
    // ⚠️ ПРЕЗ Beans, инак @Transactional на calculateAndSave не се прихваща.
    assertTrue(
        source.contains("Beans.get(BgPaySlipService.class).preview(slip)"),
        "onChange пресмята на екрана, без запис");
    assertTrue(
        source.contains("Beans.get(BgPaySlipService.class).calculateAndSave(formValues(request))"),
        "„Преизчисли“ пресмята и записва в една транзакция");
    assertFalse(
        source.contains("Beans.get(BgPaySlipService.class).calculate(slip)"),
        "⚠️ onChange по записващия път: т. 9 без записан фиш");
    assertFalse(source.contains(".recompute(slip)"), "контролерът не сумира сам");
  }

  @Test
  @DisplayName("W4 · бутонът „Преизчисли“ е на черновата, а сумите са само за четене")
  void theButtonAndTheTotals() throws Exception {
    Document doc = view();
    NodeList buttons = doc.getElementsByTagName("button");
    Element button = null;
    for (int i = 0; i < buttons.getLength(); i++) {
      Element b = (Element) buttons.item(i);
      if ("calculateBtn".equals(b.getAttribute("name"))) {
        button = b;
      }
    }
    assertNotNull(button, "бутонът „Преизчисли“ липсва");
    assertEquals("Преизчисли", button.getAttribute("title"));
    // ⚠️ БЕЗ „save,“ отпред: записът е ВЪТРЕ в действието, в транзакцията на пресмятането.
    assertEquals(RECALCULATE, button.getAttribute("onClick"));
    assertEquals("statusSelect == 'DRAFT'", button.getAttribute("showIf"));

    Map<String, Element> fields = formFields(doc);
    for (String total :
        Arrays.asList(
            "bgGrossTotal",
            "bgInsuranceIncome",
            "bgTaxBase",
            "bgTaxAmount",
            "bgNetTotal",
            "bgEmployerCost",
            "bgSocialExpenseTaxBase")) {
      assertEquals(
          "true", fields.get(total).getAttribute("readonly"), total + " се редактира на ръка");
    }
    assertNotNull(
        fields.get("bgCalcFingerprint"),
        "отпечатъкът трябва да е на формата, инак записът не го носи и всеки фиш е „непресметнат“");
  }
}
