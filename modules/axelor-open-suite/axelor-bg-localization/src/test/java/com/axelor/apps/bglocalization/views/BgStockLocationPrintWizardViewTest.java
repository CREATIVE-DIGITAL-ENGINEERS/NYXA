package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
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
 * СЪВЕТНИКЪТ „ПЕЧАТ“ НА СКЛАДОВО МЯСТО ПРЕДЛАГА ШАБЛОН.
 *
 * <p>{@code stock-location-print-wizard-form} (axelor-stock) сам отваря празно „Шаблон за складово
 * място“, а „Печат“ е {@code readonlyIf="$stockLocationPrintTemplate == null"} — без шаблон
 * справката не излиза, докато потребителят не го намери сам. Разширението на bg-localization добавя
 * втори {@code onNew}, който попълва шаблона; първият, на axelor-stock, остава същият и пръв.
 */
class BgStockLocationPrintWizardViewTest {

  private static final String FORM = "stock-location-print-wizard-form";
  private static final String ACTION = "action-bg-stock-location-print-method-default-template";

  @Test
  @DisplayName("разширението пуска onNew на axelor-stock и после попълва шаблона")
  void theWizardGetsADefaultTemplate() throws Exception {
    Element stockForm =
        form(
            BgDomainColumns.modulesRoot()
                .resolve(
                    "axelor-open-suite/axelor-stock/src/main/resources/views/StockLocation.xml"),
            false);
    assertNotNull(stockForm, "няма " + FORM + " в axelor-stock");
    String stockOnNew = stockForm.getAttribute("onNew");
    assertEquals(
        "action-stock-location-print-wizard-attrs-set-print-type-and-type-select", stockOnNew);

    Element ours = form(Paths.get("src/main/resources/views/Stock-bg.xml"), true);
    assertNotNull(ours, "bg-localization не разширява " + FORM);
    assertEquals("com.axelor.utils.db.Wizard", ours.getAttribute("model"));
    assertTrue(!ours.getAttribute("id").isEmpty(), "разширението без id не се прилага");

    List<String> onNew = new ArrayList<>();
    NodeList attrs = ours.getElementsByTagName("attribute");
    for (int i = 0; i < attrs.getLength(); i++) {
      Element attr = (Element) attrs.item(i);
      if ("onNew".equals(attr.getAttribute("name"))) {
        onNew.add(attr.getAttribute("value"));
      }
    }
    assertEquals(List.of(stockOnNew + "," + ACTION), onNew);
  }

  @Test
  @DisplayName("действието вика BgStockLocationPrintController.defaultTemplate")
  void theActionCallsTheController() throws Exception {
    Document doc = parse(Paths.get("src/main/resources/views/Stock-bg.xml"));
    NodeList methods = doc.getElementsByTagName("action-method");
    Element call = null;
    for (int i = 0; i < methods.getLength(); i++) {
      Element m = (Element) methods.item(i);
      if (ACTION.equals(m.getAttribute("name"))) {
        call = (Element) m.getElementsByTagName("call").item(0);
      }
    }
    assertNotNull(call, "няма " + ACTION);
    Class<?> controller = Class.forName(call.getAttribute("class"));
    assertNotNull(
        controller.getMethod(
            call.getAttribute("method"), ActionRequest.class, ActionResponse.class));
  }

  private static Element form(Path file, boolean extension) throws Exception {
    NodeList forms = parse(file).getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element f = (Element) forms.item(i);
      if (FORM.equals(f.getAttribute("name"))
          && extension == "true".equals(f.getAttribute("extension"))) {
        return f;
      }
    }
    return null;
  }

  private static Document parse(Path file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(file.toFile());
  }
}
