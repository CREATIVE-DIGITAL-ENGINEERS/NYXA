package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceHeaderEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiocr.service.record.OcrRecordUpdateService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The contract each tool advertises to the model. These are the properties that keep the write path
 * safe, so they are pinned rather than left to review.
 *
 * <p>Retargeted when {@code edit_supplier_invoice} replaced the six granular invoice tools ({@code
 * update_ocr_record}, {@code change_ocr_invoice_supplier}, {@code replace_ocr_invoice_lines},
 * {@code edit_ocr_invoice_line}, {@code set_invoice_line_product}, {@code set_invoice_line_tax}).
 * Every property those tests protected still matters and is asserted here against the tool that now
 * carries it — the closed field vocabulary, the absence of any generic field/value escape hatch,
 * and the fields that must never be writable at all.
 */
class OcrRecordToolContractTest {

  @Test
  void readToolsAreReadAndTheEditToolIsWrite() {
    assertEquals(AiToolKind.READ, new SearchOcrRecordsTool().kind());
    assertEquals(AiToolKind.READ, new GetOcrRecordTool().kind());
    assertEquals(AiToolKind.READ, new CheckInvoiceAmountsTool().kind());
    assertEquals(AiToolKind.READ, new FindSupplierTool().kind());
    // Must stay WRITE so AiToolDispatcher refuses it unless writes are explicitly enabled.
    assertEquals(AiToolKind.WRITE, new EditSupplierInvoiceTool().kind());
    assertEquals(AiToolKind.WRITE, new ManageSupplierTool().kind());
    // Writes the OCR evidence rather than the invoice, but it is still a write and the dispatcher
    // must gate it exactly as it gates the other two.
    assertEquals(AiToolKind.WRITE, new CorrectOcrDocumentTotalTool().kind());
  }

  @Test
  void invoiceEditingIsConfirmedOnlySoNoUnattendedPathExists() {
    // Stronger than the guard the old update_ocr_record carried: originDate feeds the duplicate key
    // (InvoiceControlServiceImpl matches supplierInvoiceNb + partner + YEAR(originDate)) and a
    // purchase invoice's due date derives from it, so a person has to be present. The consolidated
    // tool has no unattended mode to guard at all.
    assertEquals(
        java.util.EnumSet.of(AiWriteMode.CONFIRMED), new EditSupplierInvoiceTool().writeModes());
    assertEquals(
        java.util.EnumSet.of(AiWriteMode.CONFIRMED), new ManageSupplierTool().writeModes());
    // Same reason again: it changes the figure the invoice is posted against.
    assertEquals(
        java.util.EnumSet.of(AiWriteMode.CONFIRMED),
        new CorrectOcrDocumentTotalTool().writeModes());
  }

  @Test
  void toolNamesAreStableSnakeCase() {
    assertEquals("search_ocr_records", new SearchOcrRecordsTool().name());
    assertEquals("get_ocr_record", new GetOcrRecordTool().name());
    assertEquals("edit_supplier_invoice", new EditSupplierInvoiceTool().name());
    assertEquals("find_supplier", new FindSupplierTool().name());
    assertEquals("manage_supplier", new ManageSupplierTool().name());
  }

  @Test
  void everySchemaForbidsAdditionalProperties() {
    // Without this the model could invent arguments; with it, unknown keys are rejected upstream.
    for (JsonNode schema :
        new JsonNode[] {
          new SearchOcrRecordsTool().parametersSchema(),
          new GetOcrRecordTool().parametersSchema(),
          new EditSupplierInvoiceTool().parametersSchema(),
          new ManageSupplierTool().parametersSchema(),
          new FindSupplierTool().parametersSchema()
        }) {
      assertFalse(schema.path("additionalProperties").asBoolean(true));
      assertEquals("object", schema.path("type").asText());
      assertTrue(schema.has("properties"));
    }
  }

  @Test
  void nestedObjectsInTheEditSchemaAlsoForbidAdditionalProperties() {
    // The escape hatch would otherwise just move one level down.
    JsonNode props = new EditSupplierInvoiceTool().parametersSchema().path("properties");
    assertFalse(props.path("header").path("additionalProperties").asBoolean(true));
    assertFalse(props.path("replaceLines").path("additionalProperties").asBoolean(true));
    assertFalse(
        props
            .path("replaceLines")
            .path("properties")
            .path("lines")
            .path("items")
            .path("additionalProperties")
            .asBoolean(true));
    assertFalse(
        props.path("lineOperations").path("items").path("additionalProperties").asBoolean(true));
  }

  @Test
  void headerExposesOneExplicitArgumentPerWritableFieldAndNothingElse() {
    JsonNode header =
        new EditSupplierInvoiceTool().parametersSchema().path("properties").path("header");
    JsonNode props = header.path("properties");

    // No generic escape hatch: a "field"/"value" pair would be arbitrary entity patching.
    assertFalse(props.has("field"));
    assertFalse(props.has("value"));
    assertFalse(props.has("values"));
    assertFalse(props.has("patch"));

    for (String field : OcrRecordUpdateService.WRITABLE_FIELDS) {
      assertTrue(props.has(field), "missing explicit argument for " + field);
      assertNotNull(props.path(field).path("description").asText(null));
    }
    // The header is no longer only this module's six fields. It is those six — which keep their
    // OCR-aware path, because each has consequences (due dates, tax versions, the review status)
    // that only OcrRecordUpdateService runs — plus the generic supplier-invoice header fields,
    // which were simply unreachable before: a payment mode or a journal could not be corrected from
    // the chat at all, on any invoice.
    //
    // What is still asserted is the part that matters: every argument is an explicit, named,
    // described field, and the set is exactly the union of the two allowlists. There is no generic
    // escape hatch (checked above) and nothing arrives by reflection.
    java.util.Set<String> expected = new java.util.LinkedHashSet<>();
    expected.addAll(OcrRecordUpdateService.WRITABLE_FIELDS);
    InvoiceHeaderEditService.fieldSpecs(InvoiceSide.SUPPLIER)
        .forEach(spec -> expected.add(spec.getName()));
    java.util.Set<String> declared = new java.util.LinkedHashSet<>();
    props.fieldNames().forEachRemaining(declared::add);
    assertEquals(
        expected, declared, "exactly the writable fields of both allowlists, nothing more");
    declared.forEach(
        field ->
            assertNotNull(
                props.path(field).path("description").asText(null),
                field + " needs a description"));
  }

  @Test
  void supplierIsChangedByIdAndDependentValuesAreNeverSupplied() {
    JsonNode props = new EditSupplierInvoiceTool().parametersSchema().path("properties");
    assertTrue(props.has("supplierId"));

    // Dependent fields are derived in Java from the chosen partner; the model must not supply them.
    for (String derived :
        new String[] {
          "address",
          "bankDetails",
          "paymentMode",
          "paymentCondition",
          "fiscalPosition",
          "partnerTaxNbr",
          "currency",
          "partnerName",
          "eik"
        }) {
      assertFalse(props.has(derived), derived + " must be derived, not supplied");
    }
  }

  @Test
  void headerTotalsAndStateAreNotWritable() {
    // Each of these would either bypass deterministic accounting logic or break the state machine.
    for (String forbidden :
        new String[] {
          "partnerId",
          "partner",
          "currency",
          "inTaxTotal",
          "exTaxTotal",
          "taxTotal",
          "statusSelect",
          "ocrReviewStatus",
          "ocrMissingFields",
          "aiValidationNotes",
          "company",
          "companyId"
        }) {
      assertFalse(
          OcrRecordUpdateService.WRITABLE_FIELDS.contains(forbidden),
          forbidden + " must not be writable");
    }
    // ...and none of them reachable as a top-level argument either.
    JsonNode props = new EditSupplierInvoiceTool().parametersSchema().path("properties");
    for (String forbidden :
        new String[] {"exTaxTotal", "taxTotal", "inTaxTotal", "statusSelect", "companyId"}) {
      assertFalse(props.has(forbidden), forbidden + " must not be an argument");
    }
  }

  @Test
  void theEditToolTakesOneRecordAndBatchesEverythingElse() {
    JsonNode schema = new EditSupplierInvoiceTool().parametersSchema();
    JsonNode props = schema.path("properties");
    // Both ways of naming the one record are offered, and NEITHER is individually required: the
    // rule is "exactly one of the two", which a JSON Schema cannot state in a form every consumer
    // honours. SupplierInvoiceTarget.of enforces it in Java and names both fields in the refusal —
    // the same pattern the codebase already uses for bankDetailsId/bankIban.
    assertTrue(props.has("uploadId"), "the OCR intake record");
    assertTrue(props.has("invoiceId"), "or any supplier invoice of this company");
    assertEquals(0, schema.path("required").size(), "exactly-one-of is checked in Java, not here");
    // The batching that makes one correction one approval.
    assertEquals("array", props.path("lineOperations").path("type").asText());
  }

  @Test
  void searchToolRequiresNothingButDocumentsEveryParameter() {
    JsonNode schema = new SearchOcrRecordsTool().parametersSchema();
    assertEquals(0, schema.path("required").size(), "any single criterion is enough");
    JsonNode props = schema.path("properties");
    props
        .fieldNames()
        .forEachRemaining(
            n ->
                assertNotNull(
                    props.path(n).path("description").asText(null), n + " needs a description"));
  }

  @Test
  void getRecordRequiresAnIdAndDefaultsRawOcrOff() {
    JsonNode schema = new GetOcrRecordTool().parametersSchema();
    assertEquals("uploadId", schema.path("required").get(0).asText());
    assertEquals("boolean", schema.path("properties").path("includeRawOcr").path("type").asText());
  }

  @Test
  void originDateIsStillWritableBecauseVentilationNeedsIt() {
    assertTrue(
        OcrRecordUpdateService.WRITABLE_FIELDS.contains(OcrRecordUpdateService.F_ORIGIN_DATE));
    assertTrue(
        new EditSupplierInvoiceTool()
            .parametersSchema()
            .path("properties")
            .path("header")
            .path("properties")
            .has(OcrRecordUpdateService.F_ORIGIN_DATE));
  }
}
