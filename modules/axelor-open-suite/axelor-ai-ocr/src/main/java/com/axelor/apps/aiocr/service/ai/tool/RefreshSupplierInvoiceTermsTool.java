package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceBlocked;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLifecycleService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.SupplierInvoiceTermsRefreshService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: regenerate the payment terms of a VALIDATED OCR supplier invoice.
 *
 * <p>Repairs invoices validated while their payment condition had no lines — the case that leaves a
 * validated document with zero invoice terms and therefore no supplier credit line to post against.
 * See {@link SupplierInvoiceTermsRefreshService} for the trace and for why this is Axelor's own
 * lifecycle rather than a repair of our own.
 *
 * <p>The schema is one id and nothing else, deliberately. Dates come from the payment condition,
 * amounts from the invoice gross, and both are Axelor's to compute; a caller-supplied date or
 * amount here would be a settlement figure invented in a chat. There is no {@code reason} either —
 * who ran it, when, and what came out are all recorded on the invoice by the server.
 */
@Singleton
public class RefreshSupplierInvoiceTermsTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "refresh_supplier_invoice_terms";
  }

  @Override
  public String description() {
    return "Regenerates the payment terms of an already VALIDATED OCR supplier invoice using "
        + "Axelor's own InvoiceTermService, exactly as re-selecting the payment condition on the "
        + "form does. Use when readiness says a validated invoice has no payment terms: without "
        + "them posting builds an entry with no supplier credit line and fails. Changes no amount, "
        + "no date, no status and not the payment condition; the terms come from the invoice's own "
        + "gross total. Refused, with nothing changed, if the invoice is not validated or its "
        + "payment condition is still not correctly configured. NEVER tell the user to validate a "
        + "validated invoice again — this is the supported repair.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    SupplierInvoiceTarget.describeIds(props, "repaired");
    // Exactly one of the two ids, declared here and enforced in Java by SupplierInvoiceTarget.of.
    schema.putArray("required");
    SupplierInvoiceTarget.declareExactlyOneId(schema);
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.SUPPLIER_INVOICE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /**
   * Never unattended.
   *
   * <p>It touches a validated accounting document and creates what the payable side of a posting is
   * built from. Deterministic though it is, that belongs to a person's request, not to a background
   * pass.
   */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /**
   * Between validation (30) and posting (40).
   *
   * <p>Its target is validated but not yet posted, and "оправи я и я осчетоводи" has to run it
   * before {@code ventilate_supplier_invoice} for the posting to have a credit line at all.
   */
  @Override
  public int writeOrder() {
    return 35;
  }

  /**
   * Whichever record the call names — {@code Upload#} on the OCR path, {@code Invoice#} otherwise.
   */
  @Override
  public String targetKey(JsonNode args) {
    SupplierInvoiceTarget target = SupplierInvoiceTarget.ofOrNull(args);
    return target == null ? null : target.targetKey();
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    SupplierInvoiceTarget target = SupplierInvoiceTarget.of(args);
    if (!target.isOcr()) {
      try {
        Invoice invoice =
            Beans.get(InvoiceLifecycleService.class)
                .refreshTerms(target.invoiceId(), InvoiceSide.SUPPLIER);
        Map<String, Object> out =
            Beans.get(InvoiceViewService.class)
                .writeSummary(invoice, java.util.Collections.singletonList("termsRefreshed"));
        out.put(
            "termCount",
            invoice.getInvoiceTermList() == null ? 0 : invoice.getInvoiceTermList().size());
        return out;
      } catch (InvoiceBlocked e) {
        throw e.asToolException();
      } catch (IllegalArgumentException | IllegalStateException e) {
        throw new AiToolException(e.getMessage());
      }
    }
    try {
      return Beans.get(SupplierInvoiceTermsRefreshService.class).refresh(target.uploadId());
    } catch (OcrBlocked e) {
      // The refusal already says what kind it is; relaying only its sentence is what forced the
      // workflow to work that out again from the words.
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }
}
