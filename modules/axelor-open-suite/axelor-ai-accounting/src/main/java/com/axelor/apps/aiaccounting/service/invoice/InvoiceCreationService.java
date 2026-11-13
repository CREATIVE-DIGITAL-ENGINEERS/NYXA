package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raises a new DRAFT invoice for a partner, with the lines it was given.
 *
 * <h2>Standard invoices only</h2>
 *
 * The operation type comes from the calling tool — purchase for the supplier side, sale for the
 * customer side — and there is no argument that can change it. Credit notes are not created here at
 * all: Axelor has no standalone credit note, only {@code InvoiceService.createRefund} against an
 * existing invoice, and no tool here calls it. Building a "refund" from nothing would produce a
 * document with no original, which is not a credit note in any sense the books recognise.
 *
 * <h2>What is reported rather than guessed</h2>
 *
 * The payment mode and payment condition are resolved from the partner and then from the company's
 * accounting configuration. When neither has one, the draft is still created — it is a valid draft,
 * and refusing would throw away the work — but the gap is reported as a warning, because the
 * alternative is silently choosing one, and which payment terms a customer gets is not a decision
 * to make on somebody's behalf.
 *
 * <p>A header field the caller asked for and that cannot be applied is the opposite case: that is a
 * request not carried out, so it refuses the whole creation before anything is saved, rather than
 * returning "created" for a draft that lacks it.
 */
@Singleton
public class InvoiceCreationService {

  private static final Logger LOG = LoggerFactory.getLogger(InvoiceCreationService.class);

  /** What was created, what it was given, and what it still needs. */
  public static final class Result {
    private final Invoice invoice;
    private final List<AiInvoiceFieldChange> changes = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    Result(Invoice invoice) {
      this.invoice = invoice;
    }

    public Invoice getInvoice() {
      return invoice;
    }

    public List<AiInvoiceFieldChange> getChanges() {
      return changes;
    }

    public List<String> getWarnings() {
      return warnings;
    }
  }

  /**
   * Creates the draft.
   *
   * @param side which direction of invoice this tool is for; fixes the operation type
   * @param partner the already-resolved and role-checked partner
   * @param header optional header overrides, applied after the defaults
   * @param lines optional initial lines
   */
  @Transactional(rollbackOn = Exception.class)
  public Result create(InvoiceSide side, Partner partner, JsonNode header, JsonNode lines) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No authenticated user; an invoice cannot be created without a caller identity.");
    }
    if (partner == null) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "An invoice needs a partner to be for.");
    }
    if (AuthUtils.getUser() != null
        && !Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_CREATE, Invoice.class)) {
      throw new InvoiceBlocked(
          AiBlockerCode.PERMISSION_DENIED, "You do not have permission to create invoices.");
    }
    Company company = scopeCompany();

    Invoice invoice = generateDraft(side, company, partner);
    List<AiInvoiceFieldChange> changes = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    // Country-specific header defaults (e.g. the BG NAP document type) for whichever fields are
    // still blank — before any caller-supplied header override, so an explicit value always wins.
    applyCreationDefaults(invoice);

    if (invoice.getPaymentMode() == null) {
      warnings.add(
          "No default payment mode is configured for this partner or for the company, so the"
              + " invoice has none. Set it before validating.");
    }
    if (invoice.getPaymentCondition() == null) {
      warnings.add(
          "No default payment condition is configured for this partner or for the company, so the"
              + " invoice has none. Set it before validating.");
    }

    // Header overrides come after the defaults, so an explicit value wins over a resolved one.
    if (header != null && header.isObject() && header.size() > 0) {
      InvoiceHeaderEditService.Result applied = headerEditor().apply(invoice, side, header);
      // A rejected field refuses the creation, before anything is saved. Creating the draft without
      // it and saying so in a warning would hand back "created" for an invoice that is not the one
      // asked for — and the next step would be validating it.
      applied.refuseIfAnyRejected("Nothing was created.");
      changes.addAll(applied.getChanges());
    }

    if (lines != null && lines.isArray()) {
      InvoiceLineEditService lineEditor = Beans.get(InvoiceLineEditService.class);
      for (JsonNode spec : lines) {
        InvoiceLineEditService.LineResult line = lineEditor.addLine(invoice, side, spec);
        changes.addAll(line.getChanges());
      }
    }

    Invoice saved = computeAndSave(invoice);
    AiWriteEffects.touched(Invoice.class.getName(), saved.getId());
    LOG.info(
        "AI Accountant: created draft {} {} for partner {}.",
        side.label(),
        saved.getId(),
        partner.getId());
    Result result = new Result(saved);
    result.changes.addAll(changes);
    result.warnings.addAll(warnings);
    return result;
  }

  /** The active company. A seam for tests. */
  protected Company scopeCompany() {
    return AiCompanyScope.requireCompany();
  }

  /** Axelor's own header assembly for a new, unsaved draft. A seam for tests. */
  protected Invoice generateDraft(InvoiceSide side, Company company, Partner partner) {
    try {
      return new AiManualInvoiceGenerator(side.standardType(), company, partner, null, null)
          .generate();
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.TOOL_FAILED,
          "Axelor could not build a draft " + side.label() + " for this partner: " + message(e),
          e);
    }
  }

  /** Country-specific defaults for blank header fields. A seam for tests. */
  protected void applyCreationDefaults(Invoice invoice) {
    Beans.get(AiInvoiceFieldRegistry.class).applyCreationDefaults(invoice);
  }

  /** The header editor. A seam for tests. */
  protected InvoiceHeaderEditService headerEditor() {
    return Beans.get(InvoiceHeaderEditService.class);
  }

  /**
   * The one write: Axelor's totals, then the save. Reached only when nothing was refused.
   *
   * <p>Saved through the repository rather than JPA directly, because InvoiceManagementRepository's
   * own save hook is what assigns the draft sequence number to a brand-new invoice.
   */
  protected Invoice computeAndSave(Invoice invoice) {
    // Axelor's own header computation from the lines. Never arithmetic repeated here.
    try {
      Beans.get(InvoiceService.class).compute(invoice);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.TOOL_FAILED,
          "Axelor could not compute the invoice totals: " + message(e),
          e);
    }
    Invoice saved = Beans.get(InvoiceRepository.class).save(invoice);
    JPA.flush();
    return saved;
  }

  private static String message(Exception e) {
    String text = e.getMessage();
    if (text == null || text.trim().isEmpty()) {
      return e.getClass().getSimpleName();
    }
    return text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
  }
}
