package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceEditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * On the invoiceId path the line schema's {@code unitPrice} used to reach {@code
 * InvoiceLineEditService} unchanged, which accepts only {@code price}/{@code inTaxPrice}, so every
 * unit-price edit of a hand-entered invoice failed part-way through the request. It is now mapped
 * by the invoice's own tax mode, and {@code exTaxTotal} is refused before the invoice is even read.
 */
class EditSupplierInvoicePlainLinesTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A fixed tax mode and no contributed fields; records whether the invoice was read at all. */
  private static final class Tool extends EditSupplierInvoiceTool {
    private final boolean inAti;
    private boolean invoiceRead;

    Tool(boolean inAti) {
      this.inAti = inAti;
    }

    @Override
    protected boolean invoiceInAti(long invoiceId) {
      invoiceRead = true;
      return inAti;
    }

    @Override
    protected Set<String> contributedLineFieldNames() {
      return Collections.emptySet();
    }
  }

  private static JsonNode update(String field, double value) {
    ArrayNode ops = MAPPER.createArrayNode();
    ops.addObject().put("operation", "update").put("lineId", 7).put(field, value);
    return ops;
  }

  @Test
  void unitPriceBecomesTheExcludingTaxPriceOnANormalInvoice() throws Exception {
    List<InvoiceEditService.LineOperation> ops =
        new Tool(false).plainLineOperations(606L, update("unitPrice", 12.5));

    JsonNode spec = ops.get(0).getSpec();
    assertEquals(12.5, spec.path("price").asDouble());
    assertFalse(spec.has("unitPrice"));
    assertFalse(spec.has("inTaxPrice"));
  }

  @Test
  void unitPriceBecomesTheIncludingTaxPriceOnAnInAtiInvoice() throws Exception {
    List<InvoiceEditService.LineOperation> ops =
        new Tool(true).plainLineOperations(606L, update("unitPrice", 15.0));

    JsonNode spec = ops.get(0).getSpec();
    assertEquals(15.0, spec.path("inTaxPrice").asDouble());
    assertFalse(spec.has("price"));
  }

  @Test
  void exTaxTotalIsRefusedBeforeTheInvoiceIsRead() {
    Tool tool = new Tool(false);

    AiToolException ex =
        assertThrows(
            AiToolException.class,
            () -> tool.plainLineOperations(606L, update("exTaxTotal", 100.0)));

    assertEquals(AiBlockerCode.BAD_ARGUMENTS.wire(), ex.details().get("blocker"));
    assertTrue(ex.getMessage().contains("exTaxTotal"), ex.getMessage());
    assertFalse(tool.invoiceRead, "nothing may be read or changed before the refusal");
  }
}
