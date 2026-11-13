package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceBlocked;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLifecycleService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.SupplierInvoiceValidationService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: run Axelor's Validate on an OCR draft supplier invoice.
 *
 * <p>Takes no accounting decision of its own. It performs the same controls the Validate button
 * performs and then calls {@code InvoiceService.validate}, exactly as {@code
 * InvoiceController.validate} does — see {@link SupplierInvoiceValidationService} for the full
 * trace of that chain and of what is refused rather than clicked through.
 *
 * <p>Chat-only and never unattended. Validation resolves the partner payable account, assigns the
 * journal and freezes the figures the accounting move will later be built from; an automatic pass
 * doing that with nobody watching is exactly what must not happen.
 */
@Singleton
public class ValidateSupplierInvoiceTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "validate_supplier_invoice";
  }

  @Override
  public String description() {
    return "Validates a DRAFT supplier invoice through Axelor's real lifecycle "
        + "(InvoiceService.validate): recomputes the VAT summary and header totals from the lines, "
        + "creates the invoice terms and due dates, resolves the payable account, assigns the "
        + "journal, and moves Draft → Validated. Address it by uploadId (an OCR intake record) or "
        + "by invoiceId (any supplier invoice of this company, including one entered by hand) — "
        + "give one, not both. Refused with the reason and nothing changed when the record is not "
        + "usable, a readiness blocker remains, the amounts do not reconcile, or Axelor would have "
        + "asked the user a question. Does NOT post: no accounting move, and the invoice can still "
        + "go back to draft.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    SupplierInvoiceTarget.describeIds(props, "validated");
    props
        .putObject("acknowledgeDuplicate")
        .put("type", "boolean")
        .put(
            "description",
            "Answers Axelor's duplicate-number question and nothing else. Only after the user has"
                + " been shown the duplicate and said to validate anyway — never on a first"
                + " attempt, never to get past another refusal.");
    props
        .putObject("reason")
        .put("type", "string")
        .put(
            "description",
            "Why it is being validated — recorded on the invoice's audit note. One short sentence.");
    // Neither id is individually required: exactly one of the two is, which is what the oneOf below
    // says. SupplierInvoiceTarget.of still enforces it in Java and names both fields in the
    // refusal, for the consumers that do not validate.
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

  /** Never unattended: a validated document is an accounting commitment, not a data correction. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Last (30): validation freezes the figures, so every correction has to have landed first. */
  @Override
  public int writeOrder() {
    return 30;
  }

  /**
   * Whichever record the call names, for the repeat guard and the workflow.
   *
   * <p>{@code Upload#946} on the OCR path — including when the caller gave the invoice id of an OCR
   * invoice, because that is the same document and must fingerprint as one — and {@code
   * Invoice#606} for an invoice with no upload behind it.
   */
  @Override
  public String targetKey(JsonNode args) {
    SupplierInvoiceTarget target = SupplierInvoiceTarget.ofOrNull(args);
    return target == null ? null : target.targetKey();
  }

  /** The invoice's status before the call — the baseline the postcondition is read against. */
  @Override
  public Object beforeExecute(JsonNode args) {
    try {
      SupplierInvoiceTarget target = SupplierInvoiceTarget.ofOrNull(args);
      if (target == null) {
        return null;
      }
      return target.isOcr()
          ? Beans.get(SupplierInvoiceValidationService.class).scopedStatus(target.uploadId())
          : Beans.get(InvoiceLifecycleService.class)
              .scopedStatus(target.invoiceId(), InvoiceSide.SUPPLIER);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * After a lost answer: was the invoice validated?
   *
   * <h2>One field settles it</h2>
   *
   * Validation has one lifecycle effect — DRAFT becomes VALIDATED — and this tool refuses anything
   * that is not a draft, so the status before the call was DRAFT and the reading afterwards is
   * unambiguous. Still DRAFT means the transaction rolled back and nothing was validated, which is
   * provable and retryable. VALIDATED means it landed, and is reported as the ordinary success it
   * is, in the same shape, rebuilt by re-reading the record.
   *
   * <p>Anything else — VENTILATED, CANCELED, an unreadable record — is unknown. A validation does
   * not produce those, so something outside this call did, and this is not the place to interpret
   * it.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    Integer before = probe == null ? null : (Integer) probe.toolState();
    if (before == null || before != InvoiceRepository.STATUS_DRAFT) {
      return AiWriteOutcome.unknown();
    }
    SupplierInvoiceTarget target = SupplierInvoiceTarget.ofOrNull(args);
    if (target == null) {
      return AiWriteOutcome.unknown();
    }
    Integer now =
        target.isOcr()
            ? Beans.get(SupplierInvoiceValidationService.class).scopedStatus(target.uploadId())
            : Beans.get(InvoiceLifecycleService.class)
                .scopedStatus(target.invoiceId(), InvoiceSide.SUPPLIER);
    if (now == null) {
      return AiWriteOutcome.unknown();
    }
    if (now == InvoiceRepository.STATUS_DRAFT) {
      return AiWriteOutcome.notWritten();
    }
    if (now != InvoiceRepository.STATUS_VALIDATED) {
      return AiWriteOutcome.unknown();
    }
    Map<String, Object> result =
        target.isOcr()
            ? Beans.get(SupplierInvoiceValidationService.class).confirmedResult(target.uploadId())
            : Beans.get(InvoiceLifecycleService.class)
                .confirmedResult(target.invoiceId(), InvoiceSide.SUPPLIER);
    return result == null ? AiWriteOutcome.unknown() : AiWriteOutcome.completed(result);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    SupplierInvoiceTarget target = SupplierInvoiceTarget.of(args);
    boolean acknowledgeDuplicate =
        args.hasNonNull("acknowledgeDuplicate")
            && args.get("acknowledgeDuplicate").asBoolean(false);
    String reason = AiToolArgs.optText(args, "reason");
    if (!target.isOcr()) {
      // An invoice with no upload behind it: no extraction to reconcile against, so the generic
      // lifecycle service is the whole of the work. acknowledgeDuplicate has nothing to answer here
      // — the duplicate question is Axelor's own, raised through the OCR pre-validation controls.
      try {
        Invoice invoice =
            Beans.get(InvoiceLifecycleService.class)
                .validate(target.invoiceId(), InvoiceSide.SUPPLIER);
        Map<String, Object> out =
            Beans.get(InvoiceViewService.class)
                .writeSummary(invoice, java.util.Collections.singletonList("validated"));
        out.put(
            "nextStep",
            "Validated, not posted. ventilate_supplier_invoice is what puts it in the books.");
        if (reason != null) {
          out.put("reason", reason);
        }
        return out;
      } catch (InvoiceBlocked e) {
        throw e.asToolException();
      } catch (IllegalArgumentException | IllegalStateException e) {
        throw new AiToolException(e.getMessage());
      }
    }
    try {
      return Beans.get(SupplierInvoiceValidationService.class)
          .validate(target.uploadId(), acknowledgeDuplicate, reason);
    } catch (OcrBlocked e) {
      // A named blocker: its code and, where one exists, the tool that clears it travel with the
      // refusal, so recovery does not depend on the message being read a particular way.
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }
}
