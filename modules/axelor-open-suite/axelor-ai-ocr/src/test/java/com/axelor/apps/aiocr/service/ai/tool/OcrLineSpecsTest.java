package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.record.OcrInvoiceLineEditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The JSON ↔ {@link OcrInvoiceLineEditService.LineSpec} mapping — the actual tool-argument plumbing
 * behind {@code edit_supplier_invoice} — for the fields it gained parity with the plain-invoice
 * editor for: unit, account, discount, fixedAssets, and the Bulgarian VAT-credit type, which is not
 * a field this module knows about at all and must be routed to the contributor registry rather than
 * dropped. Pure — no Guice, no JPA: {@link OcrLineSpecs#fromJson} touches nothing but the JSON.
 */
class OcrLineSpecsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void unitAccountDiscountAndFixedAssetsAreAllMappedOntoTheSpec() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("description", "Гориво");
    node.put("exTaxTotal", "19.97");
    node.put("taxCode", "BG_STD_20");
    node.put("unitId", 7);
    node.put("discountTypeSelect", 1);
    node.put("discountAmount", "5");
    node.put("accountId", 42);
    node.put("fixedAssets", true);

    OcrInvoiceLineEditService.LineSpec spec = OcrLineSpecs.fromJson(node);

    assertEquals(7L, spec.getUnitId());
    assertEquals(1, spec.getDiscountTypeSelect());
    assertEquals(0, new BigDecimal("5").compareTo(spec.getDiscountAmount()));
    assertEquals(42L, spec.getAccountId());
    assertNull(spec.getAccountCode(), "id and code are mutually exclusive on the wire too");
    assertEquals(Boolean.TRUE, spec.getFixedAssets());
  }

  @Test
  void accountCodeIsMappedWhenGivenInsteadOfId() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("description", "Такса");
    node.put("exTaxTotal", "10.00");
    node.put("accountCode", "602");

    OcrInvoiceLineEditService.LineSpec spec = OcrLineSpecs.fromJson(node);

    assertEquals("602", spec.getAccountCode());
    assertNull(spec.getAccountId());
  }

  @Test
  void theBulgarianVatCreditTypeIsRoutedToTheContributorRegistryNotDropped() {
    // bgVatCreditType is not one of this module's own fields — axelor-ai-ocr does not know what it
    // means, only axelor-bg-localization's AiInvoiceFieldContributor does. The mapping's job is
    // only to not lose it: it must survive into contributedFields for
    // OcrInvoiceLineEditService.apply
    // to hand to the registry, exactly like the plain-invoice editor already does.
    ObjectNode node = MAPPER.createObjectNode();
    node.put("description", "Наем");
    node.put("exTaxTotal", "100.00");
    node.put("bgVatCreditType", "1");
    // The two fields lineOperations puts alongside a line's own fields must never be mistaken for
    // contributed fields (there is no module that "owns" operation/lineId).
    node.put("operation", "update");
    node.put("lineId", 55);

    OcrInvoiceLineEditService.LineSpec spec = OcrLineSpecs.fromJson(node);

    assertTrue(spec.getContributedFields().containsKey("bgVatCreditType"));
    JsonNode value = spec.getContributedFields().get("bgVatCreditType");
    assertEquals("1", value.asText());
    assertFalse(spec.getContributedFields().containsKey("operation"));
    assertFalse(spec.getContributedFields().containsKey("lineId"));
  }

  @Test
  void anAbsentFieldIsNeverMappedAsAnExplicitClear() {
    // The class-wide contract: null/absent means "not given", never "clear" — unitId, accountId and
    // accountCode included, same as every field this mapper already covered.
    ObjectNode node = MAPPER.createObjectNode();
    node.put("description", "Гориво");
    node.put("exTaxTotal", "19.97");

    OcrInvoiceLineEditService.LineSpec spec = OcrLineSpecs.fromJson(node);

    assertNull(spec.getUnitId());
    assertNull(spec.getAccountId());
    assertNull(spec.getAccountCode());
    assertNull(spec.getFixedAssets());
    assertTrue(spec.getContributedFields().isEmpty());
  }

  @Test
  void anExplicitNullOnAContributedFieldReachesTheContributorAsAClear() {
    // The schema declares bgVatCreditType clearable, so a sent null is an instruction — dropping it
    // here would turn "remove the credit type" into a silent no-op that still reports success.
    ObjectNode node = MAPPER.createObjectNode();
    node.put("description", "Наем");
    node.put("exTaxTotal", "100.00");
    node.putNull("bgVatCreditType");

    OcrInvoiceLineEditService.LineSpec spec = OcrLineSpecs.fromJson(node);

    assertTrue(spec.getContributedFields().containsKey("bgVatCreditType"));
    assertTrue(spec.getContributedFields().get("bgVatCreditType").isNull());
  }
}
