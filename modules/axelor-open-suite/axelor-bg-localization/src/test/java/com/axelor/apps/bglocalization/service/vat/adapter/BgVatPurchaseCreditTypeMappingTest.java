package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Чистата функция кредитен тип → колони на дневника за покупки. Без база, без Guice. */
class BgVatPurchaseCreditTypeMappingTest {

  @Test
  void eachCreditTypeHasItsColumns() {
    assertSame(BgVatPurchaseTarget.FULL_CREDIT, BgVatPurchaseCreditTypeMapping.forCreditType("1"));
    assertSame(
        BgVatPurchaseTarget.PARTIAL_CREDIT, BgVatPurchaseCreditTypeMapping.forCreditType("2"));
    assertSame(BgVatPurchaseTarget.NO_CREDIT, BgVatPurchaseCreditTypeMapping.forCreditType("3"));
  }

  @Test
  void targetsCarryTheDiaryColumns() {
    assertEquals(
        BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, BgVatPurchaseTarget.FULL_CREDIT.getBaseColumn());
    assertEquals(
        BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, BgVatPurchaseTarget.FULL_CREDIT.getVatColumn());
    assertEquals(
        BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT,
        BgVatPurchaseTarget.PARTIAL_CREDIT.getBaseColumn());
    assertEquals(
        BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT,
        BgVatPurchaseTarget.PARTIAL_CREDIT.getVatColumn());
    // к.09 носи ДО + данък в една колона.
    assertEquals(
        BgVatPurchaseColumn.K09_BASE_NO_CREDIT, BgVatPurchaseTarget.NO_CREDIT.getBaseColumn());
    assertEquals(null, BgVatPurchaseTarget.NO_CREDIT.getVatColumn());
    assertTrue(BgVatPurchaseTarget.NO_CREDIT.isVatFoldedIntoBase());
    assertFalse(BgVatPurchaseTarget.FULL_CREDIT.isVatFoldedIntoBase());
  }

  @Test
  void valueIsTrimmed() {
    assertSame(
        BgVatPurchaseTarget.PARTIAL_CREDIT, BgVatPurchaseCreditTypeMapping.forCreditType(" 2 "));
  }

  @Test
  void blankDefaultsToFull_andSaysSo() {
    for (String blank : new String[] {null, "", "   "}) {
      assertSame(
          BgVatPurchaseTarget.FULL_CREDIT, BgVatPurchaseCreditTypeMapping.forCreditType(blank));
      assertTrue(BgVatPurchaseCreditTypeMapping.isDefaulted(blank));
    }
    assertFalse(BgVatPurchaseCreditTypeMapping.isDefaulted("1"));
  }

  /** Заварено мълчание: непознатият код е пълен кредит и НЕ се отчита като подразбиране. */
  @Test
  void unknownValueIsFullCredit_withoutDefaultFlag() {
    assertSame(BgVatPurchaseTarget.FULL_CREDIT, BgVatPurchaseCreditTypeMapping.forCreditType("9"));
    assertFalse(BgVatPurchaseCreditTypeMapping.isDefaulted("9"));
  }

  /** Нова опция в селекцията без колона трябва да падне тук, а не да мине мълчаливо като „1“. */
  @Test
  void coversExactlyTheSelectionValues() throws Exception {
    assertEquals(
        selectionValues("bg.vat.credit.type.select"), BgVatPurchaseCreditTypeMapping.creditTypes());
  }

  static Set<String> selectionValues(String selection) throws Exception {
    NodeList selections =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Paths.get("src/main/resources/views/Selects.xml").toFile())
            .getElementsByTagName("selection");
    for (int i = 0; i < selections.getLength(); i++) {
      Element element = (Element) selections.item(i);
      if (selection.equals(element.getAttribute("name"))) {
        Set<String> values = new LinkedHashSet<>();
        NodeList options = element.getElementsByTagName("option");
        for (int j = 0; j < options.getLength(); j++) {
          values.add(((Element) options.item(j)).getAttribute("value"));
        }
        return values;
      }
    }
    throw new AssertionError("selection " + selection + " not found in Selects.xml");
  }
}
