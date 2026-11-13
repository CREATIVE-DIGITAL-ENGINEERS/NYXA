package com.axelor.apps.aiocr.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The invoice-form extension of this module replaces whole attribute values of axelor-account's
 * form: an {@code onChange} or {@code onClick} set here is the stock value with one action added.
 * If axelor-account changes its value, the extension would silently drop the new stock action — so
 * each one is compared with the stock file itself.
 */
class OcrInvoiceFormWiringTest {

  private static final String RECALC = "action-ai-ocr-invoice-onchange-recalc";

  private static Document stock;
  private static Document ours;
  private static final XPath XPATH = XPathFactory.newInstance().newXPath();

  @BeforeAll
  static void load() throws Exception {
    Path root = Paths.get("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("modules/axelor-open-suite"))) {
      root = root.getParent();
    }
    Assumptions.assumeTrue(root != null, "repository root not reachable from the test dir");
    Path suite = root.resolve("modules/axelor-open-suite");
    stock = parse(suite.resolve("axelor-account/src/main/resources/views/Invoice.xml"));
    ours = parse(suite.resolve("axelor-ai-ocr/src/main/resources/views/Invoice.xml"));
  }

  private static Document parse(Path file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(file.toFile());
  }

  private static String stockAttr(String element, String name, String attr) throws Exception {
    Element e =
        (Element)
            XPATH.evaluate(
                "//form[@name='invoice-form'][1]//" + element + "[@name='" + name + "']",
                stock,
                XPathConstants.NODE);
    assertNotNull(e, "axelor-account invoice-form has no " + element + " " + name);
    return e.getAttribute(attr);
  }

  private static String ourAttr(String target, String attr) throws Exception {
    Element e =
        (Element)
            XPATH.evaluate(
                "//form[@id='ai-ocr-invoice-form']/extend[@target=\""
                    + target
                    + "\"]/attribute[@name='"
                    + attr
                    + "']",
                ours,
                XPathConstants.NODE);
    assertNotNull(e, "no " + attr + " extension for " + target);
    return e.getAttribute("value");
  }

  @Test
  void eachRecalcIsAppendedToTheStockOnChange() throws Exception {
    for (String field : new String[] {"partner", "address", "currency", "paymentCondition"}) {
      assertEquals(
          stockAttr("field", field, "onChange") + "," + RECALC,
          ourAttr("//field[@name='" + field + "']", "onChange"),
          field);
    }
    assertEquals(
        stockAttr("panel-related", "invoiceLineListPanel", "onChange") + "," + RECALC,
        ourAttr("//panel-related[@name='invoiceLineListPanel']", "onChange"));
  }

  @Test
  void theReplacedBankDetailsFieldKeepsTheStockOnChange() throws Exception {
    Element ourField =
        (Element)
            XPATH.evaluate(
                "//form[@id='ai-ocr-invoice-form']/extend[@target=\"//field[@name='bankDetails']\"]"
                    + "/replace/field",
                ours,
                XPathConstants.NODE);
    assertEquals(
        stockAttr("field", "bankDetails", "onChange") + "," + RECALC,
        ourField.getAttribute("onChange"));
  }

  @Test
  void eachButtonRunsItsCheckFirstThenTheStockActions() throws Exception {
    String[][] buttons = {
      {"validateBtn", "action-ai-ocr-invoice-check-before-validate"},
      {"ventilateBtn", "action-ai-ocr-invoice-check-before-ventilate"},
      {"validateAndVentilateBtn", "action-ai-ocr-invoice-check-before-validate-and-ventilate"}
    };
    for (String[] button : buttons) {
      assertEquals(
          button[1] + "," + stockAttr("button", button[0], "onClick"),
          ourAttr("//button[@name='" + button[0] + "']", "onClick"),
          button[0]);
    }
  }

  @Test
  void theBankDetailsOverrideKeepsTheStockRefusalForSales() throws Exception {
    Element stockError =
        (Element)
            XPATH.evaluate(
                "//action-validate[@name='action-invoice-validate-check-bank-details']/error",
                stock,
                XPathConstants.NODE);
    Element ourError =
        (Element)
            XPATH.evaluate(
                "//action-validate[@name='action-invoice-validate-check-bank-details']/error",
                ours,
                XPathConstants.NODE);
    assertEquals(stockError.getAttribute("message"), ourError.getAttribute("message"));
    // The stock condition is "no bank details AND (supplier+transfer OR sale+DD/…)"; the override
    // keeps exactly the sale half as a refusal.
    assertEquals(
        "bankDetails == null && [3,4].contains(operationTypeSelect) && [2,10].contains(paymentMode?.typeSelect)",
        ourError.getAttribute("if"));
    assertEquals(
        true,
        stockError
            .getAttribute("if")
            .contains(
                "([3,4].contains(operationTypeSelect) && [2,10].contains(paymentMode?.typeSelect))"),
        "the stock sale condition is unchanged: " + stockError.getAttribute("if"));
  }
}
