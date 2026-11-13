package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field-addressable readiness findings — {@code lines[2].account: missing} rather than a sentence
 * naming a count — so a caller (human or AI) can walk straight to the field that needs fixing
 * instead of re-parsing prose and guessing which line it was about.
 *
 * <h2>What this is not</h2>
 *
 * Not a second set of validation rules, and not a gate: nothing here is read by {@link
 * InvoiceReadinessService} or {@code OcrSupplierInvoiceReadinessService}, and adding an entry here
 * never blocks validate or ventilate — that stays exactly what it already was, and {@code
 * readyToValidate} in the tool results is always {@link InvoiceReadinessService}'s own answer,
 * never derived from this class. The {@code severity} on each entry says how seriously it should be
 * taken, not how it was computed, and is set to match an EXISTING rule wherever one exists rather
 * than invented here:
 *
 * <ul>
 *   <li>{@code account} is {@code blocker} — it is {@link InvoiceReadinessService.Signals
 *       #linesMissingAccount}, restated with a field path, and genuinely refuses validate.
 *   <li>{@code tax} is {@code warning} — legitimate for an exempt supply, exactly as {@link
 *       InvoiceReadinessService} already treats it.
 *   <li>{@code product}, {@code invoice.taxSummary}, {@code unit} and {@code vatCreditType} are all
 *       {@code warning} or {@code info}: worth surfacing, but none of them is an actual Axelor gate
 *       — {@code product} because neither {@code productName} nor {@code description} is {@code
 *       required} on the domain, {@code taxSummary} because Axelor's own compute/sync self-heals it
 *       on every write, and {@code unit}/{@code vatCreditType} because they have no safe default at
 *       all (see {@code BgNapInvoiceFieldContributor}) and an invoice is legitimate without them.
 *       {@code vatCreditType} is further emitted only on a <b>purchase</b>: VAT-credit right is a
 *       buyer's concept with no column on a sales invoice, so it never appears for a customer
 *       invoice (see {@link #isPurchase}).
 * </ul>
 */
public final class InvoiceStructuredBlockers {

  public static final String SEVERITY_BLOCKER = "blocker";
  public static final String SEVERITY_WARNING = "warning";
  public static final String SEVERITY_INFO = "info";

  private InvoiceStructuredBlockers() {}

  /**
   * One entry per finding, ordered by line then by field. Empty when nothing was found.
   *
   * <p>Production entry point: resolves the registry through Guice and lets a genuine wiring
   * failure (no injector, a misconfigured binding) propagate rather than being read as "nothing
   * contributed" — a caller told there is nothing to fix when the check could not even run would be
   * worse than a loud failure. A pure unit test that has no injector at all should call {@link
   * #build(Invoice, AiInvoiceFieldRegistry)} directly with its own registry instance instead of
   * going through this overload.
   */
  public static List<Map<String, Object>> build(Invoice invoice) {
    return build(invoice, Beans.get(AiInvoiceFieldRegistry.class));
  }

  /**
   * The real work, taking the registry explicitly — the test seam for a pure unit test (an {@code
   * AiInvoiceFieldRegistry} instance needs no Guice injection itself; only {@link Beans#get(Class)}
   * does). {@code registry} may be {@code null}, in which case the one registry-owned check ({@code
   * vatCreditType}) is skipped and every other finding is still real — used only by {@link
   * #build(Invoice)} if a caller explicitly wants that degraded behaviour, never as a substitute
   * for a working registry in production.
   */
  public static List<Map<String, Object>> build(Invoice invoice, AiInvoiceFieldRegistry registry) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (invoice == null) {
      return out;
    }
    boolean purchase = isPurchase(invoice);
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines != null) {
      for (int i = 0; i < lines.size(); i++) {
        lineBlockers(out, i, lines.get(i), registry, purchase);
      }
    }
    taxSummaryBlocker(out, invoice, lines);
    return out;
  }

  /**
   * Whether this is a purchase-side document. VAT-credit right (данъчен кредит) is a buyer's right
   * to deduct input VAT, so it exists only on a purchase; a sales invoice has no such diary column.
   * Gating the {@code vatCreditType} finding on this is what keeps a purchase-only concept out of
   * {@code get_customer_invoice}'s output — the same check the BG contributor makes for its
   * supplier-only defaults. An invoice whose operation type is not yet set is treated as not a
   * purchase, so the finding is withheld until the document's direction is actually known.
   */
  private static boolean isPurchase(Invoice invoice) {
    Integer type = invoice.getOperationTypeSelect();
    return type != null
        && (type == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
            || type == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND);
  }

  private static void lineBlockers(
      List<Map<String, Object>> out,
      int index,
      InvoiceLine line,
      AiInvoiceFieldRegistry registry,
      boolean purchase) {
    String prefix = "lines[" + index + "].";
    boolean named =
        line.getProduct() != null
            || notBlank(line.getProductName())
            || notBlank(line.getDescription());
    if (!named) {
      // WARNING, not blocker: InvoiceReadinessService has no rule against an unnamed line (neither
      // productName nor description is required="true" in the InvoiceLine domain), so Axelor's own
      // validate/ventilate do not actually refuse one. The edit tools themselves refuse to WRITE a
      // new line this bare (see InvoiceLineEditService.requireUsable), but a pre-existing one can
      // still be read this way — worth flagging, not a real gate.
      add(
          out,
          prefix + "product",
          "missing",
          SEVERITY_WARNING,
          "No product, product name or description.");
    }
    if (line.getAccount() == null) {
      // BLOCKER: this is InvoiceReadinessService.Signals.linesMissingAccount, restated with a field
      // path — a real validate blocker, not an invented one.
      add(out, prefix + "account", "missing", SEVERITY_BLOCKER, "No accounting account.");
    }
    if (line.getTaxLineSet() == null || line.getTaxLineSet().isEmpty()) {
      add(
          out,
          prefix + "tax",
          "missing",
          SEVERITY_WARNING,
          "No tax — legitimate for an exempt supply, a mistake otherwise.");
    }
    if (line.getUnit() == null) {
      add(out, prefix + "unit", "missing", SEVERITY_INFO, "No unit of measure.");
    }
    if (registry != null && purchase) {
      Object vatCreditType = registry.readLineFields(line).get("bgVatCreditType");
      if (vatCreditType == null || "".equals(vatCreditType)) {
        add(
            out,
            prefix + "vatCreditType",
            "missing",
            SEVERITY_INFO,
            "VAT-credit right not classified. Never guessed — set it when the deduction right is"
                + " actually known.");
      }
    }
  }

  /**
   * Sum of the tax rows' own base against the sum of the TAXED lines' net amounts. Only lines that
   * actually carry a tax are compared: a line with no tax at all (an exempt supply, чл.163а/167а,
   * or simply not classified yet) never produces an {@code InvoiceLineTax} row for {@code
   * TaxInvoiceLine.computeAndAddInvoiceLineTax} to add its base to, so including it in the sum
   * would be wrong — a perfectly valid invoice with one taxed line and one exempt line would have
   * its exempt line's net counted against a summary that was never supposed to contain it, and get
   * flagged as "inconsistent" for being exactly right. Once only taxed lines are summed, the two
   * totals are built from the very same lines and must agree to within one rounding step per taxed
   * line; a bigger gap means the summary is stale (a line changed after it was last built) rather
   * than newly wrong.
   *
   * <p>WARNING, not blocker: neither {@code InvoiceReadinessService} nor {@code
   * OcrSupplierInvoiceReadinessService} checks this, and for good reason — {@code
   * InvoiceService.compute()} (plain invoices) and {@code OcrInvoiceTaxSummaryService.sync()} (OCR
   * invoices) both regenerate the summary from the lines on every write, so a genuinely stale
   * summary should not normally survive to be read. When one is found anyway it is worth surfacing
   * loudly, but it is not an actual gate the way a missing account is.
   */
  private static void taxSummaryBlocker(
      List<Map<String, Object>> out, Invoice invoice, List<InvoiceLine> lines) {
    List<InvoiceLineTax> rows = invoice.getInvoiceLineTaxList();
    if (rows == null || rows.isEmpty() || lines == null || lines.isEmpty()) {
      return;
    }
    BigDecimal lineNet = BigDecimal.ZERO;
    int taxedLineCount = 0;
    for (InvoiceLine line : lines) {
      if (line.getTaxLineSet() == null || line.getTaxLineSet().isEmpty()) {
        continue; // exempt/not-yet-classified — no InvoiceLineTax row exists for it
      }
      taxedLineCount++;
      if (line.getExTaxTotal() != null) {
        lineNet = lineNet.add(line.getExTaxTotal());
      }
    }
    if (taxedLineCount == 0) {
      return; // nothing taxed on the invoice — the summary being empty is correct, not stale
    }
    BigDecimal rowBase = BigDecimal.ZERO;
    for (InvoiceLineTax row : rows) {
      if (row.getExTaxBase() != null) {
        rowBase = rowBase.add(row.getExTaxBase());
      }
    }
    BigDecimal allowance = new BigDecimal("0.01").multiply(BigDecimal.valueOf(taxedLineCount));
    if (lineNet.subtract(rowBase).abs().compareTo(allowance) > 0) {
      add(
          out,
          "invoice.taxSummary",
          "inconsistent",
          SEVERITY_WARNING,
          "The tax summary's base ("
              + rowBase
              + ") does not match the sum of the taxed lines' net amounts ("
              + lineNet
              + "). Re-sync the tax summary rather than editing it directly.");
    }
  }

  private static void add(
      List<Map<String, Object>> out, String field, String code, String severity, String message) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("field", field);
    entry.put("code", code);
    entry.put("severity", severity);
    entry.put("message", message);
    out.add(entry);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
