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
 * ПРИЧИНАТА ЗА ОТКАЗ ИМА КЪДЕ ДА СЕ НАПИШЕ, ПРЕДИ ОТКАЗА.
 *
 * <p>Отказът иска причината преди себе си, затова полето не може да стои само в „Забележки“, с
 * {@code showIf="statusSelect == 'CANCELLED'"} и само за четене. Свидетелят чете изгледа, домейна и
 * контролера, защото дефектът е във връзката между трите.
 */
class BgPaySlipCancelViewTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/bg-payslip-views.xml");
  private static final Path DOMAIN = Paths.get("src/main/resources/domains/BgPaySlip.xml");
  private static final Path CONTROLLER =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/web/BgPaySlipController.java");

  private static Element form() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    Document doc = factory.newDocumentBuilder().parse(VIEW.toFile());
    NodeList forms = doc.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element f = (Element) forms.item(i);
      if ("bg-payslip-form".equals(f.getAttribute("name"))) {
        return f;
      }
    }
    throw new AssertionError("формулярът bg-payslip-form изчезна");
  }

  private static Element panel(Element form, String name) {
    NodeList panels = form.getElementsByTagName("panel");
    for (int i = 0; i < panels.getLength(); i++) {
      Element p = (Element) panels.item(i);
      if (name.equals(p.getAttribute("name"))) {
        return p;
      }
    }
    return null;
  }

  @Test
  @DisplayName("Панелът „Отказ“ носи входа и бутона заедно, преди отказа")
  void theReasonAndTheButtonStandTogether() throws Exception {
    Element form = form();
    Element cancel = panel(form, "cancelPanel");
    assertNotNull(cancel, "панелът „Отказ“ липсва");
    assertEquals("id && statusSelect != 'CANCELLED'", cancel.getAttribute("showIf"));

    Element input = (Element) cancel.getElementsByTagName("field").item(0);
    assertEquals("bgCancelReasonInput", input.getAttribute("name"));
    assertFalse("true".equals(input.getAttribute("readonly")), "входът се редактира");
    assertTrue(input.getAttribute("showIf").isEmpty(), "входът не чака отказа, за да се покаже");

    Element button = (Element) cancel.getElementsByTagName("button").item(0);
    assertEquals("cancelBtn", button.getAttribute("name"));
    assertEquals(
        "action-bg-payslip-cancel",
        button.getAttribute("onClick"),
        "⚠️ БЕЗ save ОТПРЕД: записът презарежда фиша и губи входа, който не се пази");

    NodeList buttons = form.getElementsByTagName("button");
    int cancels = 0;
    for (int i = 0; i < buttons.getLength(); i++) {
      if ("cancelBtn".equals(((Element) buttons.item(i)).getAttribute("name"))) {
        cancels++;
      }
    }
    assertEquals(1, cancels, "един бутон „Откажи“ — до причината, а не в страничната лента");
  }

  @Test
  @DisplayName("Входът не се пази, а контролерът го подава на услугата")
  void theInputIsTransientAndReachesTheService() throws Exception {
    String domain = new String(Files.readAllBytes(DOMAIN), StandardCharsets.UTF_8);
    assertTrue(
        domain.contains(
            "<string name=\"bgCancelReasonInput\" title=\"Причина за отказа\" large=\"true\""
                + " transient=\"true\"/>"),
        "входът е transient: написана и записана с „Запиши“, причината щеше да стои върху"
            + " неотказан фиш");

    String controller = new String(Files.readAllBytes(CONTROLLER), StandardCharsets.UTF_8);
    assertTrue(controller.contains("service.cancel(slip, context.getBgCancelReasonInput())"));
    assertFalse(
        controller.contains("setBgCancelReason(context"),
        "полето, което се показва САМО след отказа, вече не е източник");
  }
}
