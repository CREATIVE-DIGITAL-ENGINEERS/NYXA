package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
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
import com.axelor.apps.aiocr.service.record.SupplierInvoiceVentilationService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: post a validated OCR supplier invoice through Axelor's ventilation lifecycle.
 *
 * <p>The highest-consequence tool in this module: it draws the supplier-invoice number from the
 * company's sequence and creates the accounting move. It takes no accounting decision of its own —
 * it runs the Ventilate action's own controls and then calls {@code InvoiceService.ventilate},
 * exactly as {@code InvoiceController.ventilate} does. See {@link
 * SupplierInvoiceVentilationService} for the full trace and for what is refused rather than clicked
 * through.
 *
 * <p>There is deliberately no {@code reason} argument. Who posted it, when, which move came out and
 * against which journal are all facts the server knows, and it writes them to the invoice's audit
 * note itself; a free-text justification supplied by the caller would add nothing a reviewer could
 * rely on.
 */
@Singleton
public class VentilateSupplierInvoiceTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "ventilate_supplier_invoice";
  }

  @Override
  public String description() {
    return "Posts (ventilates) an already VALIDATED OCR supplier invoice through Axelor's real "
        + "lifecycle (InvoiceService.ventilate): assigns the invoice number from the company "
        + "sequence and creates the accounting Move and its lines. This puts the document in the "
        + "books and cannot be undone from the chat. Only for an invoice the user asked to post; a "
        + "draft must be validated first, which is a separate TOOL with its own guards — when one "
        + "message authorises both, call validate and ventilate in the SAME response rather than "
        + "waiting a round trip between them. Refused with the reason and nothing posted when a "
        + "readiness blocker remains, the amounts disagree, the partner account is unusable, the "
        + "VAT summary or payment terms are stale, or Axelor would have asked the user a question.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    SupplierInvoiceTarget.describeIds(props, "posted");
    props
        .putObject("acknowledgeDuplicate")
        .put("type", "boolean")
        .put(
            "description",
            "Answers Axelor's duplicate-number question and nothing else. Only after the user has"
                + " been shown the duplicate and said to post anyway — never on a first attempt,"
                + " never to get past another refusal.");
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

  /** Never unattended: this is the entry into the books. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** After validation (30), because the invoice has to be validated before it can be posted. */
  @Override
  public int writeOrder() {
    return 40;
  }

  /**
   * Whichever record the call names — {@code Upload#} on the OCR path, {@code Invoice#} otherwise.
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
          ? Beans.get(SupplierInvoiceVentilationService.class).scopedStatus(target.uploadId())
          : Beans.get(InvoiceLifecycleService.class)
              .scopedStatus(target.invoiceId(), InvoiceSide.SUPPLIER);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * After a lost answer: is the invoice in the books?
   *
   * <h2>The case this whole mechanism was built for</h2>
   *
   * Ventilation takes the invoice number from the company sequence and creates the accounting move.
   * Repeating one that already ran is the worst outcome this system can produce; reporting one that
   * did run as unknown sends an accountant to check a posting that is plainly there. Before this,
   * those were the only two options.
   *
   * <p>They are unnecessary, because the effect is a single lifecycle field and the status before
   * the call is known — this tool refuses anything that is not VALIDATED. Still VALIDATED means the
   * transaction rolled back and nothing was posted, which is provable and the only case a retry may
   * be built on. VENTILATED means it landed: reported as the ordinary success it is, with the move
   * read from the invoice's own link rather than searched for.
   *
   * <p>Anything else is unknown. A ventilation does not produce a CANCELED invoice, so if one is
   * there, something outside this call put it there and interpreting that is not this method's job.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    Integer before = probe == null ? null : (Integer) probe.toolState();
    if (before == null || before != InvoiceRepository.STATUS_VALIDATED) {
      return AiWriteOutcome.unknown();
    }
    SupplierInvoiceTarget target = SupplierInvoiceTarget.ofOrNull(args);
    if (target == null) {
      return AiWriteOutcome.unknown();
    }
    Integer now =
        target.isOcr()
            ? Beans.get(SupplierInvoiceVentilationService.class).scopedStatus(target.uploadId())
            : Beans.get(InvoiceLifecycleService.class)
                .scopedStatus(target.invoiceId(), InvoiceSide.SUPPLIER);
    if (now == null) {
      return AiWriteOutcome.unknown();
    }
    if (now == InvoiceRepository.STATUS_VALIDATED) {
      return AiWriteOutcome.notWritten();
    }
    if (now != InvoiceRepository.STATUS_VENTILATED) {
      return AiWriteOutcome.unknown();
    }
    Map<String, Object> result =
        target.isOcr()
            ? Beans.get(SupplierInvoiceVentilationService.class).confirmedResult(target.uploadId())
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
    if (!target.isOcr()) {
      try {
        Invoice invoice =
            Beans.get(InvoiceLifecycleService.class)
                .ventilate(target.invoiceId(), InvoiceSide.SUPPLIER);
        Map<String, Object> out =
            Beans.get(InvoiceViewService.class)
                .writeSummary(invoice, java.util.Collections.singletonList("posted"));
        out.put("posted", true);
        out.put(
            "nextStep",
            "In the books as "
                + invoice.getInvoiceId()
                + ". A correction now needs a credit note, raised in Axelor itself.");
        return out;
      } catch (InvoiceBlocked e) {
        throw e.asToolException();
      } catch (IllegalArgumentException | IllegalStateException e) {
        throw new AiToolException(e.getMessage());
      }
    }
    try {
      return Beans.get(SupplierInvoiceVentilationService.class)
          .ventilate(target.uploadId(), acknowledgeDuplicate);
    } catch (OcrBlocked e) {
      // A named blocker: its code and, where one exists, the tool that clears it travel with the
      // refusal, so recovery does not depend on the message being read a particular way.
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }
}
