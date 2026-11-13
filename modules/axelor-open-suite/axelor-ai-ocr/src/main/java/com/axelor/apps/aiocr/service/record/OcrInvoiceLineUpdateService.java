package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.account.service.AccountManagementAccountService;
import com.axelor.apps.account.service.TaxAccountService;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.tax.TaxService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Corrects the product/account/tax of one line on an OCR-generated DRAFT supplier invoice.
 *
 * <p>Scope, and why it is drawn here: these three are the fields whose absence silently makes an
 * otherwise "complete" invoice impossible to ventilate. A line with no product gets no expense
 * account; a line whose VAT rate the mapping does not know gets no tax at all. Neither shows up in
 * {@code ocrReviewStatus}, so both used to pass review and fail at ventilation.
 *
 * <p><b>Amounts are never touched.</b> {@code exTaxTotal} / {@code inTaxTotal} come straight from
 * what the supplier printed ({@code BgOcrIntakeService.buildInvoiceLine}), and there is no stored
 * per-line tax amount — {@code InvoiceLineTax} is generated from {@code taxLineSet} + {@code
 * exTaxTotal} at Validate. So attaching a tax line needs no amount recomputation, and recomputing
 * one would replace the supplier's figures with derived ones. That would be the single most
 * damaging thing this class could do, so it does not do it.
 *
 * <p><b>Persistence note.</b> Saves with {@link JPA#save} rather than {@code InvoiceRepository},
 * for the same audited reason as {@link OcrRecordUpdateService}: the Invoice repository chain
 * (Budget → Supplychain → Management) recomputes line amounts and pack totals on save. The line
 * belongs to {@code invoiceLineList}, which cascades, so saving the invoice persists it.
 *
 * <p>Never calls {@code InvoiceLineService.fillProductInformation} / {@code fillPriceAndAccount}:
 * those reset the tax fields and then re-derive the price from the supplier price list, overwriting
 * the invoiced amount with a catalogue one.
 *
 * <p><b>Transaction boundary.</b> The three public operations are {@code @Transactional} so each is
 * atomic on its own — one of them assigns both a product and the account derived from it, and half
 * of that is not a state worth persisting. They previously had no annotation at all, which meant
 * they depended on a caller having opened a transaction: true for the invoice form, but not for the
 * OCR intake thread that calls {@code applyExactProductCode} unattended. Inside a compound AI
 * change the annotation simply joins the caller's transaction (guice-persist does not commit a
 * joined one), so the caller keeps its all-or-nothing guarantee.
 */
@Singleton
public class OcrInvoiceLineUpdateService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrInvoiceLineUpdateService.class);

  /** Rounding slack when comparing a configured tax rate with the rate OCR read. */
  private static final BigDecimal RATE_EPSILON = new BigDecimal("0.01");

  private final AiOcrUploadRepository uploadRepo;

  @Inject
  public OcrInvoiceLineUpdateService(AiOcrUploadRepository uploadRepo) {
    this.uploadRepo = uploadRepo;
  }

  /** What changed, for the caller's audit trail and for the model's benefit. */
  public static final class LineChange {
    private final Map<String, Object> details = new LinkedHashMap<>();
    private final StringBuilder audit = new StringBuilder();

    void record(String field, Object from, Object to, String note) {
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("from", from);
      one.put("to", to);
      details.put(field, one);
      if (audit.length() > 0) {
        audit.append("; ");
      }
      audit.append(field).append(": ").append(from).append(" → ").append(to);
      if (note != null) {
        audit.append(" (").append(note).append(')');
      }
    }

    public Map<String, Object> getDetails() {
      return details;
    }

    public String getAudit() {
      return audit.toString();
    }

    public boolean isEmpty() {
      return details.isEmpty();
    }
  }

  /**
   * Sets the product on a line and resolves its purchase account from that product.
   *
   * @param productId an existing, non-archived Product
   */
  @Transactional(rollbackOn = Exception.class)
  public LineChange setLineProduct(long uploadId, long lineId, long productId) {
    Context ctx = load(uploadId, lineId);

    Product product = Beans.get(ProductRepository.class).find(productId);
    if (product == null) {
      throw new IllegalArgumentException("No product with id " + productId + ".");
    }
    if (Boolean.TRUE.equals(product.getArchived())) {
      throw new IllegalArgumentException(
          "Product " + describe(product) + " is archived and cannot be used.");
    }
    return applyProduct(ctx, product, null);
  }

  /**
   * Sets the product from the code printed on the document. Deterministic: applies only when
   * exactly one product carries that code, and refuses otherwise rather than choosing.
   */
  @Transactional(rollbackOn = Exception.class)
  public LineChange applyExactProductCode(long uploadId, long lineId, String productCode) {
    Context ctx = load(uploadId, lineId);
    if (productCode == null || productCode.trim().isEmpty()) {
      throw new IllegalArgumentException("A product code is required.");
    }
    String code = productCode.trim();
    List<Product> matches =
        Beans.get(ProductRepository.class)
            .all()
            .filter("self.code = :c AND (self.archived IS NULL OR self.archived = FALSE)")
            .bind("c", code)
            .fetch(2);
    if (matches.isEmpty()) {
      throw new IllegalArgumentException("No product has the code '" + code + "'.");
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "Several products share the code '" + code + "'; this must be resolved by a person.");
    }
    return applyProduct(ctx, matches.get(0), "matched product code " + code);
  }

  /**
   * Sets a line's tax from a BG purchase tax code.
   *
   * <p>The code must be one of the mapped purchase codes — an arbitrary or output-VAT code is
   * refused, so the model cannot put a sales tax on a purchase line.
   */
  @Transactional(rollbackOn = Exception.class)
  public LineChange setLineTax(long uploadId, long lineId, String taxCode) {
    Context ctx = load(uploadId, lineId);
    if (taxCode == null || !BgOcrVatMapping.purchaseTaxCodes().contains(taxCode.trim())) {
      throw new IllegalArgumentException(
          "'"
              + taxCode
              + "' is not a Bulgarian purchase tax code. Allowed: "
              + String.join(", ", BgOcrVatMapping.purchaseTaxCodes())
              + ".");
    }
    String code = taxCode.trim();
    Invoice invoice = ctx.invoice;
    InvoiceLine line = ctx.line;

    if (invoice.getInvoiceDate() == null) {
      throw new IllegalArgumentException(
          "The invoice has no date, so no tax version can be selected. Set the invoice date first.");
    }
    Tax tax =
        Beans.get(TaxRepository.class).all().filter("self.code = :c").bind("c", code).fetchOne();
    if (tax == null) {
      throw new IllegalArgumentException(
          "Tax " + code + " is not configured in this database (l10n_bgr chart of accounts).");
    }
    // Read the date off the SAVED invoice, not the extraction: the AI pre-pass may have corrected
    // it, and picking a version for the wrong date silently yields nothing.
    TaxLine taxLine;
    try {
      taxLine = Beans.get(TaxService.class).getTaxLine(tax, invoice.getInvoiceDate());
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Tax " + code + " could not be resolved for " + invoice.getInvoiceDate() + ".");
    }
    if (taxLine == null) {
      throw new IllegalArgumentException(
          "Tax " + code + " has no version covering " + invoice.getInvoiceDate() + ".");
    }

    LineChange change = new LineChange();
    String previous = line.getTaxCode();
    HashSet<TaxLine> set = new HashSet<>();
    set.add(taxLine);
    line.setTaxLineSet(set);

    TaxAccountService taxAccountService = Beans.get(TaxAccountService.class);
    line.setTaxCode(taxAccountService.computeTaxCode(set));
    BigDecimal configuredRate = taxAccountService.getTotalTaxRateInPercentage(set);
    line.setTaxRate(configuredRate);
    change.record("taxCode", previous, code, "rate " + configuredRate + "%");

    // A configured rate that disagrees with the printed one is a real red flag, not something to
    // paper over: report it rather than silently accepting either number.
    BigDecimal printed = ctx.printedRate;
    if (printed != null
        && configuredRate != null
        && printed.subtract(configuredRate).abs().compareTo(RATE_EPSILON) > 0) {
      change.record(
          "taxRateMismatch",
          printed,
          configuredRate,
          "the document shows a different rate — check this line");
    }

    persist(ctx, change);
    return change;
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private LineChange applyProduct(Context ctx, Product product, String note) {
    InvoiceLine line = ctx.line;
    LineChange change = new LineChange();

    Product previous = line.getProduct();
    line.setProduct(product);
    change.record("product", describe(previous), describe(product), note);

    Company company = ctx.invoice.getCompany();
    Account account = null;
    try {
      account =
          Beans.get(AccountManagementAccountService.class)
              .getProductAccount(product, company, ctx.invoice.getFiscalPosition(), true, false);
    } catch (Exception e) {
      LOG.info(
          "No purchase account resolvable for product {}: {}", product.getId(), e.getMessage());
    }
    if (account == null) {
      throw new IllegalArgumentException(
          "Product "
              + describe(product)
              + " has no purchase account configured for this company, so the line would still be"
              + " unventilatable. Pick a product that does, or configure its account management.");
    }
    // A misconfigured AccountManagement can hand back another company's account; that would post
    // to the wrong books, so verify rather than trust.
    if (account.getCompany() != null
        && company != null
        && !account.getCompany().getId().equals(company.getId())) {
      throw new IllegalArgumentException(
          "The account resolved for "
              + describe(product)
              + " belongs to another company; refusing to use it.");
    }
    String previousAccount = line.getAccount() == null ? null : line.getAccount().getCode();
    line.setAccount(account);
    change.record("account", previousAccount, account.getCode(), null);

    if (line.getUnit() == null && product.getUnit() != null) {
      line.setUnit(product.getUnit());
    }
    persist(ctx, change);
    return change;
  }

  /** Everything the operations need, already validated. */
  private static final class Context {
    AiOcrUpload upload;
    Invoice invoice;
    InvoiceLine line;
    BigDecimal printedRate;
  }

  /** Resolves and authorises the target through the shared {@link OcrDraftInvoiceGuard}. */
  private Context load(long uploadId, long lineId) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolve(uploadId);
    Context ctx = new Context();
    ctx.upload = target.getUpload();
    ctx.invoice = target.getInvoice();
    ctx.line = target.requireLine(lineId);
    ctx.printedRate = ctx.line.getTaxRate();
    return ctx;
  }

  /** Saves, refreshes the derived review state, and appends the audit note. */
  private void persist(Context ctx, LineChange change) {
    if (change.isEmpty()) {
      return;
    }
    Invoice invoice = ctx.invoice;

    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    invoice.setOcrReviewStatus(missing.isEmpty() ? "complete" : "toReview");
    invoice.setOcrMissingFields(missing.isEmpty() ? null : String.join(", ", missing));
    String note =
        "Line correction by "
            + AiExecutionContext.callerDescription()
            + " on line "
            + ctx.line.getId()
            + " — "
            + change.getAudit();
    String existing = invoice.getAiValidationNotes();
    invoice.setAiValidationNotes(
        existing == null || existing.trim().isEmpty() ? note : existing + "\n\n" + note);

    // JPA.save, never invoiceRepo.save: see the class javadoc.
    JPA.save(invoice);
    ctx.upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(ctx.upload);
    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    AiWriteEffects.touched(AiOcrUpload.class.getName(), ctx.upload.getId());
  }

  private static String describe(Product product) {
    if (product == null) {
      return null;
    }
    return product.getCode() == null ? product.getName() : product.getCode();
  }
}
