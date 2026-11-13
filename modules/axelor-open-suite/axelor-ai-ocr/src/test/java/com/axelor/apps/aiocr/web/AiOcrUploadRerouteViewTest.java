package com.axelor.apps.aiocr.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.OcrRerouteDestination;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Structural proof, over the actual shipped {@code AiOcrUpload.xml} (not a copy), that the four
 * reroute buttons are wired the way the feature depends on:
 *
 * <ol>
 *   <li>they live in the Result section, before Extraction errors / Extraction information — not in
 *       the record header;
 *   <li>each one confirms before acting and hides itself for the destination the record already
 *       has;
 *   <li>each one reaches {@code BgOcrUploadController}, and all four controller methods delegate to
 *       the ONE {@code reroute(ActionRequest, ActionResponse, OcrRerouteDestination)} body — there
 *       is no second routing flow to drift from;
 *   <li>the view file itself is well-formed XML.
 * </ol>
 */
class AiOcrUploadRerouteViewTest {

  /** Button name → (action suffix, destination code it hides for). */
  private static final Map<String, String[]> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("rerouteToMoveBtn", new String[] {"reroute-move", "move"});
    EXPECTED.put(
        "rerouteToSupplierInvoiceBtn",
        new String[] {"reroute-supplier-invoice", "supplierInvoice"});
    EXPECTED.put(
        "rerouteToCustomerInvoiceBtn",
        new String[] {"reroute-customer-invoice", "customerInvoice"});
    EXPECTED.put(
        "rerouteToBankStatementBtn", new String[] {"reroute-bank-statement", "bankStatement"});
  }

  private static Document loadView() throws Exception {
    try (InputStream in =
        AiOcrUploadRerouteViewTest.class.getResourceAsStream("/views/AiOcrUpload.xml")) {
      assertNotNull(in, "AiOcrUpload.xml must be on the test classpath");
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      return factory.newDocumentBuilder().parse(in);
    }
  }

  private static Element elementByName(Document doc, String tag, String name) {
    NodeList nodes = doc.getElementsByTagName(tag);
    for (int i = 0; i < nodes.getLength(); i++) {
      Element element = (Element) nodes.item(i);
      if (name.equals(element.getAttribute("name"))) {
        return element;
      }
    }
    return null;
  }

  /**
   * The shipped view must still validate against the platform's own {@code object-views.xsd} — the
   * same schema the application validates views with at load time. This is what catches a bad
   * attribute or a misplaced element before a rebuild does.
   */
  @Test
  void theViewStillValidatesAgainstThePlatformSchema() throws Exception {
    javax.xml.validation.SchemaFactory factory =
        javax.xml.validation.SchemaFactory.newInstance(
            javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
    try (InputStream xsd =
        AiOcrUploadRerouteViewTest.class.getResourceAsStream("/object-views.xsd")) {
      assertNotNull(xsd, "the platform view schema must be on the test classpath");
      javax.xml.validation.Validator validator =
          factory.newSchema(new javax.xml.transform.stream.StreamSource(xsd)).newValidator();
      try (InputStream view =
          AiOcrUploadRerouteViewTest.class.getResourceAsStream("/views/AiOcrUpload.xml")) {
        assertNotNull(view, "AiOcrUpload.xml must be on the test classpath");
        validator.validate(new javax.xml.transform.stream.StreamSource(view));
      }
    }
  }

  @Test
  void theFourButtonsSitInTheResultSectionBeforeTheExtractionFields() throws Exception {
    Document doc = loadView();
    Element resultPanel = elementByName(doc, "panel", "resultPanel");
    assertNotNull(resultPanel, "the Result panel must still exist");
    Element reroutePanel = elementByName(doc, "panel", "reroutePanel");
    assertNotNull(reroutePanel, "the reroute panel must exist");
    assertTrue(
        reroutePanel.getParentNode() == resultPanel,
        "the reroute buttons must live inside the Result section, not in the header");

    List<String> order = new ArrayList<>();
    NodeList children = resultPanel.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element element = (Element) child;
      order.add(element.getTagName() + ":" + element.getAttribute("name"));
    }
    int panelAt = order.indexOf("panel:reroutePanel");
    int errorsAt = order.indexOf("field:extractionErrors");
    int infoAt = order.indexOf("field:extractionInformation");
    assertTrue(panelAt >= 0 && errorsAt > panelAt && infoAt > panelAt, "order was " + order);
  }

  @Test
  void eachButtonConfirmsHidesItselfForItsOwnDestinationAndCallsTheController() throws Exception {
    Document doc = loadView();
    for (Map.Entry<String, String[]> expected : EXPECTED.entrySet()) {
      Element button = elementByName(doc, "button", expected.getKey());
      assertNotNull(button, expected.getKey() + " must exist");
      String prompt = button.getAttribute("prompt");
      assertFalse(prompt.trim().isEmpty(), expected.getKey() + " must confirm before acting");
      assertEquals(
          "$rerouteCurrentDestination == '" + expected.getValue()[1] + "'",
          button.getAttribute("hideIf"),
          expected.getKey() + " must hide for the destination the record already has");
      assertTrue(
          button
              .getAttribute("onClick")
              .endsWith("action-ai-ocr-upload-method-" + expected.getValue()[0]),
          expected.getKey() + " must call its own reroute action");

      Element action =
          elementByName(
              doc, "action-method", "action-ai-ocr-upload-method-" + expected.getValue()[0]);
      assertNotNull(action, "the action for " + expected.getKey() + " must exist");
      Element call = (Element) action.getElementsByTagName("call").item(0);
      assertEquals("com.axelor.apps.aiocr.web.BgOcrUploadController", call.getAttribute("class"));
      assertFalse(call.getAttribute("method").trim().isEmpty());
    }
  }

  /**
   * All four public controller actions must funnel into the single common body, and that body is
   * the only place a destination is chosen — proven by calling each one with an empty request and
   * capturing which destination reached the common method.
   */
  @Test
  void allFourControllerActionsDelegateToTheOneCommonBody() throws Exception {
    Map<String, OcrRerouteDestination> seen = new HashMap<>();
    BgOcrUploadController controller =
        new BgOcrUploadController() {
          @Override
          protected void reroute(
              ActionRequest request, ActionResponse response, OcrRerouteDestination destination) {
            seen.put(destination.getCode(), destination);
          }
        };

    controller.rerouteToMove(new ActionRequest(), new ActionResponse());
    controller.rerouteToSupplierInvoice(new ActionRequest(), new ActionResponse());
    controller.rerouteToCustomerInvoice(new ActionRequest(), new ActionResponse());
    controller.rerouteToBankStatement(new ActionRequest(), new ActionResponse());

    assertEquals(4, seen.size(), "each action must reach the common body exactly once: " + seen);
    for (OcrRerouteDestination destination : OcrRerouteDestination.values()) {
      assertTrue(seen.containsKey(destination.getCode()), "missing " + destination);
    }
    // And there is exactly ONE such body (no per-destination overloads to drift apart).
    int bodies = 0;
    for (Method method : BgOcrUploadController.class.getDeclaredMethods()) {
      if ("reroute".equals(method.getName())) {
        bodies++;
      }
    }
    assertEquals(1, bodies, "there must be exactly one common reroute method");
  }
}
