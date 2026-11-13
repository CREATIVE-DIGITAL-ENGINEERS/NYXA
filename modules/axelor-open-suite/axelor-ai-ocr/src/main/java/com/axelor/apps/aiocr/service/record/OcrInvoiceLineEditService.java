package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AnalyticDistributionTemplate;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AnalyticDistributionTemplateRepository;
import com.axelor.apps.account.db.repo.FixedAssetCategoryRepository;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.account.service.AccountManagementAccountService;
import com.axelor.apps.account.service.invoice.InvoiceLineAnalyticService;
import com.axelor.apps.account.service.invoice.InvoiceLineService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.readiness.OcrAmountReconciliation;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.UnitRepository;
import com.axelor.apps.base.service.CurrencyScaleService;
import com.axelor.apps.base.service.tax.TaxService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds, changes, removes and replaces the lines of an OCR-generated DRAFT supplier invoice.
 *
 * <p>This exists because OCR sometimes reconstructs the line section as nonsense while the header
 * totals come out right. Invoice 576 is the reference case: a fuel receipt whose two "line items"
 * were a VAT sub-total fragment and the card-payment row, adding up to 50.87 against a printed
 * ex-tax total of 19.97. Nothing in the pipeline could repair that, because there was no way to
 * delete a line or write a correct one.
 *
 * <h2>What the caller supplies, and what Java decides</h2>
 *
 * A caller — an AI tool, a controller, a future UI action — supplies only <em>intended</em> values:
 * description, an optional product, quantity, unit price, ex-tax amount, and a tax code from a
 * closed list. Everything that Axelor is supposed to calculate is calculated here, using Axelor's
 * own services:
 *
 * <ul>
 *   <li><b>tax line</b> — {@link TaxService#getTaxLine(Tax, LocalDate)} against the date on the
 *       saved invoice, so the correct date-versioned {@code TaxLine} is picked;
 *   <li><b>taxRate / taxCode</b> — {@link TaxService#getTotalTaxRateInPercentage} and {@link
 *       TaxService#computeTaxCode}, never from the caller, so they cannot disagree with the tax
 *       actually attached;
 *   <li><b>inTaxTotal</b> — {@code exTaxTotal + exTaxTotal × rate}, the same arithmetic and the
 *       same currency scale as {@code InvoiceLineGenerator.computeTotal};
 *   <li><b>unit price</b> — derived from amount ÷ quantity when not given, and <em>verified</em>
 *       against the amount when it is;
 *   <li><b>product</b> — when the caller names none, {@link BgOcrIntakeService#resolveLineProduct}:
 *       the same deterministic tiers intake uses, ending at the company's own generic OCR purchase
 *       product (see {@link #applyDeterministicProduct});
 *   <li><b>account</b> — {@link AccountManagementAccountService#getProductAccount} for the product
 *       and this company, checked to actually belong to this company;
 *   <li><b>company amounts</b> — {@link InvoiceLineService#getCompanyExTaxTotal}, which converts at
 *       the invoice's origin date for a purchase.
 * </ul>
 *
 * <p>So the model can say "this line is 41.328 litres of E Gas LPG, 19.97 net, 20% VAT" and cannot
 * say "and therefore the VAT is 3.50".
 *
 * <h2>Header totals are never written</h2>
 *
 * The invoice's own {@code exTaxTotal} / {@code taxTotal} / {@code inTaxTotal} are what the
 * supplier printed, and they are the only independent check on whether a rewritten line section is
 * right. If this service also "fixed" them to match the new lines, a wrong replacement would
 * silently reconcile with itself. So they are left alone, the difference is reported in the result,
 * and {@link OcrSupplierInvoiceReadinessService} raises it — as a ventilate blocker when it is a
 * real disagreement, and as a warning when it is only rounding.
 *
 * <p>That distinction matters on invoice 576 itself. Its printed VAT is 4.00 while 19.97 at 20%
 * gives 3.99, so a correct line section still leaves the header one stotinka away from what Axelor
 * will post. {@link OcrAmountReconciliation} names that case rather than smoothing it into a match,
 * and the audit note says which figure will be posted.
 *
 * <h2>Persistence</h2>
 *
 * Saves with {@link JPA#save}, never {@code InvoiceRepository.save}: the Invoice repository chain
 * (Budget → Supplychain → Management) recomputes line amounts and pack totals on save, which would
 * overwrite the amounts just written. {@code invoiceLineList} is {@code cascade=ALL,
 * orphanRemoval=true}, so adding to and removing from the collection is enough to insert and delete
 * lines. See {@link OcrRecordUpdateService} for the full audit of that decision.
 *
 * <p>Never calls {@code InvoiceLineService.fillProductInformation} / {@code fillPriceAndAccount}:
 * those reset the tax fields and re-derive the price from the supplier price list, replacing the
 * invoiced amount with a catalogue one.
 */
@Singleton
public class OcrInvoiceLineEditService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrInvoiceLineEditService.class);

  /** Absolute floor on the qty × price vs amount check. */
  private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.02");

  /**
   * Extra slack per unit when checking qty × unitPrice against the amount: a unit price is printed
   * rounded, so 41.328 litres at a two-decimal price cannot land within a flat 2 cents of the
   * printed net value. Half a cent per unit is the honest allowance.
   */
  private static final BigDecimal TOLERANCE_PER_UNIT = new BigDecimal("0.005");

  /** Hard ceiling on a replacement, so a runaway suggestion cannot generate a thousand lines. */
  public static final int MAX_LINES_PER_CALL = 40;

  private final OcrRecordViewService viewService;

  @Inject
  public OcrInvoiceLineEditService(OcrRecordViewService viewService) {
    this.viewService = viewService;
  }

  // ---------------------------------------------------------------------------
  // Input
  // ---------------------------------------------------------------------------

  /**
   * One intended invoice line, as a caller means it.
   *
   * <p>Everything is optional except a description, because a caller correcting one field of an
   * existing line should not have to restate the rest. {@code null} means "leave as it is" on an
   * update and "derive it" on an insert — it never means "clear it".
   */
  public static final class LineSpec {
    private String description;
    private Long productId;
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal exTaxTotal;
    private String taxCode;
    private Long unitId;
    private Integer discountTypeSelect;
    private BigDecimal discountAmount;
    private Long accountId;
    private String accountCode;
    private Boolean fixedAssets;
    private Long fixedAssetCategoryId;
    private LocalDate cutOffStartDate;
    private LocalDate cutOffEndDate;
    private BigDecimal coefficient;
    private Long analyticDistributionTemplateId;
    private final Map<String, JsonNode> contributedFields = new LinkedHashMap<>();

    public LineSpec setDescription(String description) {
      this.description = description;
      return this;
    }

    public LineSpec setProductId(Long productId) {
      this.productId = productId;
      return this;
    }

    public LineSpec setQty(BigDecimal qty) {
      this.qty = qty;
      return this;
    }

    public LineSpec setUnitPrice(BigDecimal unitPrice) {
      this.unitPrice = unitPrice;
      return this;
    }

    public LineSpec setExTaxTotal(BigDecimal exTaxTotal) {
      this.exTaxTotal = exTaxTotal;
      return this;
    }

    public LineSpec setTaxCode(String taxCode) {
      this.taxCode = taxCode;
      return this;
    }

    /** Like every other field here, {@code null} means "not given", never "clear". */
    public LineSpec setUnitId(Long unitId) {
      this.unitId = unitId;
      return this;
    }

    public LineSpec setDiscountTypeSelect(Integer discountTypeSelect) {
      this.discountTypeSelect = discountTypeSelect;
      return this;
    }

    public LineSpec setDiscountAmount(BigDecimal discountAmount) {
      this.discountAmount = discountAmount;
      return this;
    }

    /** Either id or code, never both. */
    public LineSpec setAccountId(Long accountId) {
      this.accountId = accountId;
      return this;
    }

    public LineSpec setAccountCode(String accountCode) {
      this.accountCode = accountCode;
      return this;
    }

    public LineSpec setFixedAssets(Boolean fixedAssets) {
      this.fixedAssets = fixedAssets;
      return this;
    }

    public LineSpec setFixedAssetCategoryId(Long fixedAssetCategoryId) {
      this.fixedAssetCategoryId = fixedAssetCategoryId;
      return this;
    }

    public LineSpec setCutOffStartDate(LocalDate cutOffStartDate) {
      this.cutOffStartDate = cutOffStartDate;
      return this;
    }

    public LineSpec setCutOffEndDate(LocalDate cutOffEndDate) {
      this.cutOffEndDate = cutOffEndDate;
      return this;
    }

    public LineSpec setCoefficient(BigDecimal coefficient) {
      this.coefficient = coefficient;
      return this;
    }

    public LineSpec setAnalyticDistributionTemplateId(Long analyticDistributionTemplateId) {
      this.analyticDistributionTemplateId = analyticDistributionTemplateId;
      return this;
    }

    /** A field owned by an {@code AiInvoiceFieldContributor} (BG VAT-credit type, income type). */
    public LineSpec putContributedField(String field, JsonNode value) {
      contributedFields.put(field, value);
      return this;
    }

    public String getDescription() {
      return description;
    }

    public Long getProductId() {
      return productId;
    }

    public BigDecimal getQty() {
      return qty;
    }

    public BigDecimal getUnitPrice() {
      return unitPrice;
    }

    public BigDecimal getExTaxTotal() {
      return exTaxTotal;
    }

    public String getTaxCode() {
      return taxCode;
    }

    public Long getUnitId() {
      return unitId;
    }

    public Integer getDiscountTypeSelect() {
      return discountTypeSelect;
    }

    public BigDecimal getDiscountAmount() {
      return discountAmount;
    }

    public Long getAccountId() {
      return accountId;
    }

    public String getAccountCode() {
      return accountCode;
    }

    public Boolean getFixedAssets() {
      return fixedAssets;
    }

    public Long getFixedAssetCategoryId() {
      return fixedAssetCategoryId;
    }

    public LocalDate getCutOffStartDate() {
      return cutOffStartDate;
    }

    public LocalDate getCutOffEndDate() {
      return cutOffEndDate;
    }

    public BigDecimal getCoefficient() {
      return coefficient;
    }

    public Long getAnalyticDistributionTemplateId() {
      return analyticDistributionTemplateId;
    }

    public Map<String, JsonNode> getContributedFields() {
      return contributedFields;
    }
  }

  /** What happened, in enough detail to audit and to answer the caller. */
  public static final class LineEditResult {
    private final List<String> audit = new ArrayList<>();
    private final List<Map<String, Object>> removed = new ArrayList<>();
    private final List<Map<String, Object>> written = new ArrayList<>();
    private Long uploadId;
    private Long invoiceId;
    private String ocrReviewStatus;
    private List<String> missingReviewFields = new ArrayList<>();
    private Map<String, Object> amountsReconcile = new LinkedHashMap<>();
    private List<String> validateBlockers = new ArrayList<>();
    private List<String> ventilateBlockers = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private boolean usable;
    private List<Map<String, Object>> lines = new ArrayList<>();

    public List<String> getAudit() {
      return audit;
    }

    public List<Map<String, Object>> getRemoved() {
      return removed;
    }

    public List<Map<String, Object>> getWritten() {
      return written;
    }

    public Long getUploadId() {
      return uploadId;
    }

    public Long getInvoiceId() {
      return invoiceId;
    }

    public String getOcrReviewStatus() {
      return ocrReviewStatus;
    }

    public List<String> getMissingReviewFields() {
      return missingReviewFields;
    }

    public Map<String, Object> getAmountsReconcile() {
      return amountsReconcile;
    }

    public List<String> getValidateBlockers() {
      return validateBlockers;
    }

    public List<String> getVentilateBlockers() {
      return ventilateBlockers;
    }

    /**
     * Things that are true and worth saying but do not block anything — chiefly a rounding
     * difference between the document's VAT and the VAT its lines produce.
     */
    public List<String> getWarnings() {
      return warnings;
    }

    public boolean isUsable() {
      return usable;
    }

    public List<Map<String, Object>> getLines() {
      return lines;
    }
  }

  // ---------------------------------------------------------------------------
  // Operations
  // ---------------------------------------------------------------------------

  /** Appends one line to the invoice. */
  @Transactional(rollbackOn = Exception.class)
  public LineEditResult addLine(long uploadId, LineSpec spec, String reason) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolve(uploadId);
    Invoice invoice = target.getInvoice();
    LineEditResult result = new LineEditResult();

    InvoiceLine line = new InvoiceLine();
    apply(invoice, line, spec, true);
    if (invoice.getInvoiceLineList() == null) {
      invoice.setInvoiceLineList(new ArrayList<>());
    }
    invoice.addInvoiceLineListItem(line);
    result.audit.add("added line " + describe(line));
    return finish(target, result, reason);
  }

  /** Changes the supplied fields of one existing line, leaving the rest alone. */
  @Transactional(rollbackOn = Exception.class)
  public LineEditResult updateLine(long uploadId, long lineId, LineSpec spec, String reason) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolve(uploadId);
    Invoice invoice = target.getInvoice();
    InvoiceLine line = target.requireLine(lineId);
    LineEditResult result = new LineEditResult();

    String before = describe(line);
    apply(invoice, line, spec, false);
    result.audit.add("line " + lineId + ": " + before + " → " + describe(line));
    return finish(target, result, reason);
  }

  /** Removes one line. Refuses to leave the invoice with none. */
  @Transactional(rollbackOn = Exception.class)
  public LineEditResult deleteLine(long uploadId, long lineId, String reason) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolve(uploadId);
    Invoice invoice = target.getInvoice();
    InvoiceLine line = target.requireLine(lineId);
    LineEditResult result = new LineEditResult();

    if (invoice.getInvoiceLineList().size() <= 1) {
      throw new IllegalArgumentException(
          "Removing line "
              + lineId
              + " would leave the invoice with no lines at all, which cannot be ventilated. Replace"
              + " the lines instead of deleting the last one.");
    }
    result.removed.add(snapshot(line));
    result.audit.add("removed line " + lineId + " (" + describe(line) + ")");
    invoice.removeInvoiceLineListItem(line);
    resequence(invoice);
    return finish(target, result, reason);
  }

  /**
   * Replaces a set of lines with a new set, in one operation.
   *
   * <p>This is the capability that matters for a mis-reconstructed line section: deleting two junk
   * lines and inserting one correct one has to be a single atomic change, or the invoice passes
   * through a state that is worse than where it started.
   *
   * @param replaceLineIds lines to remove; {@code null} or empty means "all of them"
   * @param newLines the lines to write; must not be empty
   */
  @Transactional(rollbackOn = Exception.class)
  public LineEditResult replaceLines(
      long uploadId, List<Long> replaceLineIds, List<LineSpec> newLines, String reason) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolve(uploadId);
    Invoice invoice = target.getInvoice();
    LineEditResult result = new LineEditResult();

    if (newLines == null || newLines.isEmpty()) {
      throw new IllegalArgumentException(
          "A replacement needs at least one new line; use the delete operation to remove a line.");
    }
    if (newLines.size() > MAX_LINES_PER_CALL) {
      throw new IllegalArgumentException(
          "At most " + MAX_LINES_PER_CALL + " lines may be written in one call.");
    }

    List<InvoiceLine> existing =
        invoice.getInvoiceLineList() == null
            ? new ArrayList<>()
            : new ArrayList<>(invoice.getInvoiceLineList());
    List<InvoiceLine> doomed = new ArrayList<>();
    if (replaceLineIds == null || replaceLineIds.isEmpty()) {
      doomed.addAll(existing);
    } else {
      // requireLine so an id from another invoice is refused rather than quietly ignored.
      Set<Long> wanted = new LinkedHashSet<>(replaceLineIds);
      for (Long id : wanted) {
        if (id == null) {
          throw new IllegalArgumentException("A line id to replace was null.");
        }
        doomed.add(target.requireLine(id));
      }
    }

    // Build and validate every replacement BEFORE removing anything. A rejected line must leave the
    // invoice exactly as it was, and relying on transaction rollback for that is not enough: when a
    // caller has already opened a transaction this one joins it, so an in-memory collection emptied
    // before the failure would stay emptied. Order, not rollback, is what makes this atomic.
    List<InvoiceLine> replacements = new ArrayList<>();
    for (LineSpec spec : newLines) {
      InvoiceLine line = new InvoiceLine();
      apply(invoice, line, spec, true);
      replacements.add(line);
    }

    for (InvoiceLine line : doomed) {
      result.removed.add(snapshot(line));
      result.audit.add("removed line " + line.getId() + " (" + describe(line) + ")");
      invoice.removeInvoiceLineListItem(line);
    }
    if (invoice.getInvoiceLineList() == null) {
      invoice.setInvoiceLineList(new ArrayList<>());
    }
    for (InvoiceLine line : replacements) {
      invoice.addInvoiceLineListItem(line);
      result.audit.add("added line " + describe(line));
    }
    resequence(invoice);
    warnOnCollapsedDetail(result, doomed, newLines);
    return finish(target, result, reason);
  }

  /**
   * Notices when a replacement threw away the document's line detail to make the totals agree.
   *
   * <h2>The behaviour this exists to surface</h2>
   *
   * Faced with {@code amountsReconcile.verdict=MISMATCH} — extracted lines that do not sum to the
   * printed base — one replacement satisfies every check in a single move: delete all of them and
   * write one line called "As per supplier invoice 0900092690" for the printed amount. The totals
   * then reconcile exactly, validation passes and the invoice posts. It is also the destruction of
   * every position the supplier actually billed, which is the part of the document an accountant
   * needs most: expense analysis, per-line VAT treatment and any later dispute all rest on it.
   *
   * <h2>Why a warning and not a refusal</h2>
   *
   * The collapse is sometimes right. A receipt with no line detail genuinely has one position, and
   * a dozen identical OCR duplicates genuinely reduce to one real line. A refusal would have to
   * decide which case it is looking at, get it wrong, and block a correct repair.
   *
   * <p>So this fires only on the shape that can be neither: several <em>distinct</em> descriptions
   * replaced by exactly one line whose own description refers to the document rather than to
   * anything on it. Duplicates collapse to one distinct description and are therefore silent; a
   * genuinely single-position invoice has one line to begin with.
   *
   * <p>The warning reaches the model in the tool result and the accountant in the record's notes,
   * which is the point: the loss becomes visible instead of reading as a clean repair.
   */
  protected void warnOnCollapsedDetail(
      LineEditResult result, List<InvoiceLine> doomed, List<LineSpec> newLines) {
    if (newLines == null || newLines.size() != 1 || doomed == null || doomed.size() < 2) {
      return;
    }
    Set<String> distinct = new LinkedHashSet<>();
    for (InvoiceLine line : doomed) {
      String text = line.getProductName() == null ? line.getDescription() : line.getProductName();
      if (text != null && !text.trim().isEmpty()) {
        distinct.add(text.trim().toLowerCase(Locale.ROOT));
      }
    }
    if (distinct.size() < 2) {
      return; // duplicates of one position: collapsing them is the repair, not a loss
    }
    if (!isGenericSummary(newLines.get(0).getDescription())) {
      return; // a real description means the detail was rebuilt, not discarded
    }
    result.warnings.add(
        "Line detail was replaced by a single summary line: "
            + distinct.size()
            + " distinct positions from the document were removed. If the document does list those"
            + " positions, rebuild them from the OCR evidence instead — a summary line makes the"
            + " totals agree but loses the per-line expense and VAT detail.");
  }

  /**
   * A description that refers to the document instead of naming what was bought.
   *
   * <p>Kept to the forms actually observed plus their direct Bulgarian equivalents. It is a
   * heuristic feeding a warning, so a miss costs nothing and a false hit costs one extra sentence.
   */
  protected boolean isGenericSummary(String description) {
    if (description == null || description.trim().isEmpty()) {
      return true;
    }
    String text = description.trim().toLowerCase(Locale.ROOT);
    return text.startsWith("as per ")
        || text.startsWith("per supplier invoice")
        || text.startsWith("supplier invoice")
        || text.startsWith("invoice total")
        || text.startsWith("total per")
        || text.startsWith("по фактура")
        || text.startsWith("съгласно фактура")
        || text.startsWith("стоки по фактура")
        || text.startsWith("услуги по фактура")
        || text.startsWith("обща сума");
  }

  /**
   * Re-resolves each line's tax to the version covering the invoice's current date.
   *
   * <p>{@code TaxLine} is date-versioned, so moving the invoice or origin date can leave a line
   * pointing at the wrong version — a silent error, because the tax code still looks right. The
   * {@code Tax} itself is never changed here: only which of its versions applies.
   *
   * @return one note per line whose version actually moved; empty when nothing changed
   */
  public List<String> refreshLineTaxVersions(Invoice invoice) {
    List<String> notes = new ArrayList<>();
    LocalDate date = documentDate(invoice);
    if (date == null || invoice.getInvoiceLineList() == null) {
      return notes;
    }
    TaxService taxService = Beans.get(TaxService.class);
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      Set<TaxLine> current = line.getTaxLineSet();
      if (current == null || current.isEmpty()) {
        continue;
      }
      // One tax per OCR line by construction; a multi-tax line is left for a person.
      if (current.size() > 1) {
        continue;
      }
      TaxLine held = current.iterator().next();
      Tax tax = held.getTax();
      if (tax == null) {
        continue;
      }
      TaxLine applicable;
      try {
        applicable = taxService.getTaxLine(tax, date);
      } catch (Exception e) {
        continue;
      }
      if (applicable == null
          || applicable.getId() == null
          || applicable.getId().equals(held.getId())) {
        continue;
      }
      Set<TaxLine> replacement = new HashSet<>();
      replacement.add(applicable);
      line.setTaxLineSet(replacement);
      line.setTaxRate(taxService.getTotalTaxRateInPercentage(replacement));
      line.setTaxCode(taxService.computeTaxCode(replacement));
      notes.add(
          "line "
              + line.getId()
              + ": tax "
              + tax.getCode()
              + " moved to the version valid on "
              + date);
    }
    return notes;
  }

  // ---------------------------------------------------------------------------
  // Writing one line
  // ---------------------------------------------------------------------------

  /**
   * Writes a spec onto a line, deriving and validating everything derivable.
   *
   * @param isNew true for an insert, where a description and an amount are required
   */
  protected void apply(Invoice invoice, InvoiceLine line, LineSpec spec, boolean isNew) {
    if (spec == null) {
      throw new IllegalArgumentException("No line values were supplied.");
    }
    line.setInvoice(invoice);

    // ── description ─────────────────────────────────────────────────────────
    if (spec.getDescription() != null && !spec.getDescription().trim().isEmpty()) {
      String description = spec.getDescription().trim();
      if (description.length() > 500) {
        throw new IllegalArgumentException("The line description is longer than 500 characters.");
      }
      line.setDescription(description);
      line.setProductName(description);
    } else if (isNew) {
      throw new IllegalArgumentException(
          "A new line needs a description — what was actually bought.");
    }

    // ── product + account ───────────────────────────────────────────────────
    if (spec.getProductId() != null) {
      applyProduct(invoice, line, spec.getProductId());
    } else if (line.getProduct() == null) {
      applyDeterministicProduct(invoice, line);
    }

    // ── explicit overrides on top of what the product resolved ───────────────
    // Applied AFTER the product, same rule as the plain-invoice editor: a caller's own value wins
    // over the catalogue default rather than being silently replaced by it.
    if (spec.getUnitId() != null) {
      line.setUnit(resolveUnit(spec.getUnitId()));
    }
    if (spec.getAccountId() != null || spec.getAccountCode() != null) {
      line.setAccount(resolveAccountOverride(invoice, spec));
    }
    if (spec.getDiscountTypeSelect() != null) {
      if (spec.getDiscountTypeSelect() < 0 || spec.getDiscountTypeSelect() > 2) {
        throw new IllegalArgumentException(
            "discountTypeSelect must be 0 (none), 1 (percentage) or 2 (fixed amount), not "
                + spec.getDiscountTypeSelect()
                + ".");
      }
      line.setDiscountTypeSelect(spec.getDiscountTypeSelect());
    }
    if (spec.getDiscountAmount() != null) {
      line.setDiscountAmount(spec.getDiscountAmount());
    }
    if (spec.getFixedAssets() != null) {
      line.setFixedAssets(spec.getFixedAssets());
    }
    if (spec.getFixedAssetCategoryId() != null) {
      line.setFixedAssetCategory(
          resolveFixedAssetCategory(invoice, spec.getFixedAssetCategoryId()));
    }
    if (spec.getCutOffStartDate() != null) {
      line.setCutOffStartDate(spec.getCutOffStartDate());
    }
    if (spec.getCutOffEndDate() != null) {
      line.setCutOffEndDate(spec.getCutOffEndDate());
    }
    if (line.getCutOffStartDate() != null
        && line.getCutOffEndDate() != null
        && line.getCutOffStartDate().isAfter(line.getCutOffEndDate())) {
      throw new IllegalArgumentException("cutOffStartDate must not be after cutOffEndDate.");
    }
    if (spec.getCoefficient() != null) {
      if (spec.getCoefficient().signum() < 0
          || spec.getCoefficient().compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("coefficient must be between 0 and 1.");
      }
      line.setCoefficient(spec.getCoefficient());
    }
    if (spec.getAnalyticDistributionTemplateId() != null) {
      AnalyticDistributionTemplate template =
          resolveAnalyticDistributionTemplate(invoice, spec.getAnalyticDistributionTemplateId());
      line.setAnalyticDistributionTemplate(template);
      try {
        line.setAnalyticMoveLineList(
            Beans.get(InvoiceLineAnalyticService.class)
                .createAnalyticDistributionWithTemplate(line));
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Axelor could not build the analytic distribution from this template: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      }
    }
    for (Map.Entry<String, JsonNode> field : spec.getContributedFields().entrySet()) {
      AiInvoiceFieldChange change =
          Beans.get(AiInvoiceFieldRegistry.class)
              .applyLineField(line, field.getKey(), field.getValue());
      if (change == null) {
        throw new IllegalArgumentException(
            "No module accepted line field '" + field.getKey() + "'.");
      }
    }

    // ── tax, before amounts: the rate is what turns the net into the gross ───
    BigDecimal taxRateFraction = applyTax(invoice, line, spec.getTaxCode());

    // ── quantity / unit price / amount ──────────────────────────────────────
    // A freshly constructed InvoiceLine is not blank: the generated entity initialises qty, price,
    // exTaxTotal and inTaxTotal to BigDecimal.ZERO. So "fall back to what the line already has" is
    // only meaningful for an update — on an insert those zeros would masquerade as supplied values
    // and produce a zero-amount line instead of a refusal.
    BigDecimal qty = spec.getQty();
    if (qty == null) {
      // Not stated: keep the line's own quantity on an update, otherwise one. A quantity the caller
      // *did* state as zero is still an error — the distinction is whether they asked about it.
      BigDecimal existing = isNew ? null : line.getQty();
      qty = existing != null && existing.signum() > 0 ? existing : BigDecimal.ONE;
    }
    if (qty.signum() <= 0) {
      throw new IllegalArgumentException("Quantity must be greater than zero (was " + qty + ").");
    }

    BigDecimal exTax = spec.getExTaxTotal();
    if (exTax == null && !isNew) {
      exTax = line.getExTaxTotal();
    }
    BigDecimal unitPrice = spec.getUnitPrice();
    if (exTax == null && unitPrice != null) {
      exTax = qty.multiply(unitPrice);
    }
    if (exTax == null) {
      throw new IllegalArgumentException(
          "A line needs an amount: give exTaxTotal, or a unitPrice to multiply by the quantity.");
    }
    // A negative amount is legitimate and must stay supported: a discount, a rebate or a returned-
    // deposit row is printed as a negative net on the document, and refusing it would force the
    // caller to either drop the row or fold it into another line — both of which change what the
    // invoice says. The quantity still has to be positive (checked above), so the sign lives on the
    // amount alone, and everything downstream follows it: the derived unit price, the gross
    // (exTax + exTax × rate) and the reconciliation sums are all sign-correct by arithmetic.

    CurrencyScaleService scaleService = Beans.get(CurrencyScaleService.class);
    exTax = scaleService.getScaledValue(invoice, exTax);

    if (unitPrice == null) {
      // Derived, at the field's own scale, so qty × price reproduces the amount as closely as the
      // schema allows. The amount stays authoritative either way.
      unitPrice = exTax.divide(qty, 10, RoundingMode.HALF_UP);
    } else {
      // Supplied: check it, and refuse rather than silently prefer one of two disagreeing numbers.
      // This is the check that catches a gross unit price passed as a net one.
      BigDecimal expected = qty.multiply(unitPrice);
      BigDecimal tolerance = AMOUNT_TOLERANCE.max(qty.multiply(TOLERANCE_PER_UNIT));
      if (expected.subtract(exTax).abs().compareTo(tolerance) > 0) {
        throw new IllegalArgumentException(
            "The line does not add up: "
                + qty
                + " × "
                + unitPrice
                + " = "
                + scaleService.getScaledValue(invoice, expected)
                + ", but exTaxTotal is "
                + exTax
                + ". Give consistent values — if "
                + unitPrice
                + " is the price including VAT, pass the net unit price or omit it and let it be"
                + " derived from the amount.");
      }
    }

    line.setQty(qty);
    line.setPrice(unitPrice);
    line.setPriceDiscounted(unitPrice);
    line.setExTaxTotal(exTax);

    // Same arithmetic and scale as InvoiceLineGenerator.computeTotal, so a line written here and a
    // line written by the suite agree to the last decimal.
    BigDecimal inTax =
        scaleService.getScaledValue(invoice, exTax.add(exTax.multiply(taxRateFraction)));
    line.setInTaxTotal(inTax);
    line.setInTaxPrice(inTax.divide(qty, 10, RoundingMode.HALF_UP));
    if (line.getCoefficient() == null) {
      line.setCoefficient(BigDecimal.ONE);
    }

    // Company-currency amounts, converted at the date Axelor itself uses for a purchase (origin
    // date). Best-effort: a missing rate must not block the line correction.
    try {
      InvoiceLineService invoiceLineService = Beans.get(InvoiceLineService.class);
      line.setCompanyExTaxTotal(invoiceLineService.getCompanyExTaxTotal(exTax, invoice));
      line.setCompanyInTaxTotal(invoiceLineService.getCompanyExTaxTotal(inTax, invoice));
    } catch (Exception e) {
      LOG.info(
          "Could not convert line amounts to the company currency for invoice {}: {}",
          invoice.getId(),
          e.getMessage());
    }

    // The unit, LAST, through the one shared rule: каквото редът вече носи (изрично подадено от
    // повикващия по-горе, или взето от продукта) печели; инак единицата на продукта; инак
    // настроената по подразбиране от локализацията.
    //
    // ⚠️ И ЗА UPDATE, не само за нов ред: `spec.getUnitId()` може да дойде и като изрично нулиране,
    // а обикновен ред без мерна единица е точно това, което този клас е тук да не произвежда.
    //
    // Преобразувано в unchecked: това е инструментен път към модела, който не обявява
    // AxelorException
    // и чиито грешки се връщат като текст. IllegalStateException, не IllegalArgumentException —
    // липсващата настройка не е грешка на подадения аргумент.
    settleUnit(line);
  }

  /**
   * The line's unit, through the one shared rule. Called as the LAST step of {@link #apply}, after
   * product resolution and after the caller's own {@code unitId} override, so the precedence is
   * explicit → product → localization default.
   */
  protected void settleUnit(InvoiceLine line) {
    try {
      unitDefaults().applyTo(line);
    } catch (AxelorException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  /** Seam: the production-path tests drive the real {@link #apply} with a stub here. */
  protected InvoiceLineUnitDefaultService unitDefaults() {
    return Beans.get(InvoiceLineUnitDefaultService.class);
  }

  /** Attaches a product and the purchase account that follows from it. */
  protected void applyProduct(Invoice invoice, InvoiceLine line, long productId) {
    Product product = Beans.get(ProductRepository.class).find(productId);
    if (product == null) {
      throw new IllegalArgumentException("No product with id " + productId + ".");
    }
    if (Boolean.TRUE.equals(product.getArchived())) {
      throw new IllegalArgumentException(
          "Product " + label(product) + " is archived and cannot be used.");
    }
    Company company = invoice.getCompany();
    Account account = null;
    try {
      account =
          Beans.get(AccountManagementAccountService.class)
              .getProductAccount(product, company, invoice.getFiscalPosition(), true, false);
    } catch (Exception e) {
      LOG.info("No purchase account resolvable for product {}: {}", productId, e.getMessage());
    }
    if (account == null) {
      throw new IllegalArgumentException(
          "Product "
              + label(product)
              + " has no purchase account configured for this company, so the line would still be"
              + " unventilatable. Pick a product that has one, or configure its account management.");
    }
    // A misconfigured AccountManagement can hand back another company's account; that would post to
    // the wrong books, so verify rather than trust.
    if (account.getCompany() != null
        && company != null
        && !account.getCompany().getId().equals(company.getId())) {
      throw new IllegalArgumentException(
          "The account resolved for "
              + label(product)
              + " belongs to another company; refusing to use it.");
    }
    line.setProduct(product);
    line.setAccount(account);
    if (product.getUnit() != null) {
      line.setUnit(product.getUnit());
    }
  }

  /**
   * A unit override. Global master data — Axelor puts no company on {@code Unit}, so there is no
   * ownership to check, same as {@code InvoiceLineEditService.resolveUnit}.
   */
  protected Unit resolveUnit(long unitId) {
    Unit unit = Beans.get(UnitRepository.class).find(unitId);
    if (unit == null) {
      throw new IllegalArgumentException("No unit with id " + unitId + ".");
    }
    return unit;
  }

  /**
   * An account override, in the active company. Kept separate from the account {@link
   * #applyProduct} / {@link #applyDeterministicProduct} resolve from the product, so a caller can
   * correct a wrongly-mapped account without having to change the product too.
   */
  protected Account resolveAccountOverride(Invoice invoice, LineSpec spec) {
    if (spec.getAccountId() != null && spec.getAccountCode() != null) {
      throw new IllegalArgumentException("Give either accountId or accountCode, not both.");
    }
    Company company = invoice.getCompany();
    Long companyId = company != null ? company.getId() : AiCompanyScope.requireCompanyId();
    Account account;
    if (spec.getAccountId() != null) {
      account = Beans.get(AccountRepository.class).find(spec.getAccountId());
      if (account == null) {
        throw new IllegalArgumentException("No account with id " + spec.getAccountId() + ".");
      }
    } else {
      String code = spec.getAccountCode().trim();
      if (code.isEmpty()) {
        throw new IllegalArgumentException("accountCode must not be blank.");
      }
      account =
          Beans.get(AccountRepository.class)
              .all()
              .filter("LOWER(self.code) = :code AND self.company.id = :companyId")
              .bind("code", code.toLowerCase(Locale.ROOT))
              .bind("companyId", companyId)
              .fetchOne();
      if (account == null) {
        throw new IllegalArgumentException("No account with code '" + code + "' in this company.");
      }
    }
    if (account.getCompany() != null
        && companyId != null
        && !account.getCompany().getId().equals(companyId)) {
      throw new IllegalArgumentException(
          "Account " + account.getCode() + " belongs to another company; refusing to use it.");
    }
    return account;
  }

  /**
   * A fixed asset category, in the active company. {@code FixedAssetCategory} has no code, only a
   * name, so id is the only reference accepted — same reasoning as {@code
   * InvoiceLineEditService.resolveFixedAssetCategory}.
   */
  protected FixedAssetCategory resolveFixedAssetCategory(Invoice invoice, long categoryId) {
    FixedAssetCategory category = Beans.get(FixedAssetCategoryRepository.class).find(categoryId);
    if (category == null) {
      throw new IllegalArgumentException("No fixed asset category with id " + categoryId + ".");
    }
    Company company = invoice.getCompany();
    if (category.getCompany() != null
        && company != null
        && !category.getCompany().getId().equals(company.getId())) {
      throw new IllegalArgumentException(
          "Fixed asset category "
              + category.getName()
              + " belongs to another company; refusing to use it.");
    }
    return category;
  }

  /**
   * An analytic distribution template, in the active company. Same id-only reasoning as {@link
   * #resolveFixedAssetCategory}.
   */
  protected AnalyticDistributionTemplate resolveAnalyticDistributionTemplate(
      Invoice invoice, long templateId) {
    AnalyticDistributionTemplate template =
        Beans.get(AnalyticDistributionTemplateRepository.class).find(templateId);
    if (template == null) {
      throw new IllegalArgumentException(
          "No analytic distribution template with id " + templateId + ".");
    }
    Company company = invoice.getCompany();
    if (template.getCompany() != null
        && company != null
        && !template.getCompany().getId().equals(company.getId())) {
      throw new IllegalArgumentException(
          "Analytic distribution template "
              + template.getName()
              + " belongs to another company; refusing to use it.");
    }
    return template;
  }

  /**
   * The product a line gets when the caller named none — the intake's own resolution, reused.
   *
   * <p>Without this, a line written from the chat came out with no product and therefore no expense
   * account, so a correction that fixed the amounts left the invoice unventilatable and the only
   * way forward was to create a catalogue Product for a one-off description like "Услуги по
   * договор". Intake never required that: {@link BgOcrIntakeService#resolveLineProduct} falls back
   * to the company's own {@code aiOcrGenericPurchaseProduct} (the OCR-GEN product), which carries
   * the purchase account mapping. Calling that method rather than re-deriving the tiers here is the
   * point — a line written by the assistant and a line written by intake resolve identically.
   *
   * <p>Two deliberate differences from {@link #applyProduct}:
   *
   * <ul>
   *   <li><b>the OCR description survives.</b> It is already on {@code description} / {@code
   *       productName} by the time this runs, and it is not overwritten with the generic product's
   *       name — an accountant reading the line needs to see what was bought, not "OCR Generic
   *       Purchase". Only a line with no description at all borrows the product's name.
   *   <li><b>it never throws.</b> An explicit {@code productId} that yields no account is a caller
   *       error worth refusing; a fallback that yields none is a company-configuration gap, exactly
   *       as during intake, and the readiness check reports it as a ventilate blocker. Failing here
   *       would throw away a correction whose amounts are right.
   * </ul>
   */
  protected void applyDeterministicProduct(Invoice invoice, InvoiceLine line) {
    Company company = invoice.getCompany();
    if (company == null) {
      return;
    }
    Product product;
    try {
      product =
          Beans.get(BgOcrIntakeService.class)
              .resolveLineProduct(null, line.getDescription(), company);
    } catch (RuntimeException e) {
      LOG.info("Could not resolve a fallback product for invoice {}: {}", invoice.getId(), e);
      return;
    }
    if (product == null || Boolean.TRUE.equals(product.getArchived())) {
      return; // description-only, same as intake; readiness reports the missing account
    }
    Account account = null;
    try {
      account =
          Beans.get(AccountManagementAccountService.class)
              .getProductAccount(product, company, invoice.getFiscalPosition(), true, false);
    } catch (Exception e) {
      LOG.info(
          "No purchase account resolvable for fallback product {}: {}",
          product.getId(),
          e.getMessage());
    }
    // Same company check as applyProduct: a misconfigured AccountManagement can return another
    // company's account, and posting there would be worse than posting nowhere.
    if (account != null
        && account.getCompany() != null
        && !account.getCompany().getId().equals(company.getId())) {
      LOG.warn(
          "Ignoring account {} for product {}: it belongs to company {}, not {}.",
          account.getCode(),
          product.getId(),
          account.getCompany().getId(),
          company.getId());
      account = null;
    }
    line.setProduct(product);
    if (account != null) {
      line.setAccount(account);
    }
    if ((line.getProductName() == null || line.getProductName().trim().isEmpty())
        && product.getName() != null) {
      line.setProductName(product.getName());
    }
    if (line.getUnit() == null && product.getUnit() != null) {
      line.setUnit(product.getUnit());
    }
  }

  /**
   * Resolves the tax from a closed allowlist of BG purchase codes and returns its rate as a
   * fraction.
   *
   * @param taxCode null to keep whatever the line already has
   * @return the rate as a fraction (0.20 for 20%), zero when the line carries no tax
   */
  protected BigDecimal applyTax(Invoice invoice, InvoiceLine line, String taxCode) {
    TaxService taxService = Beans.get(TaxService.class);
    if (taxCode != null && !taxCode.trim().isEmpty()) {
      String code = taxCode.trim();
      if (!BgOcrVatMapping.purchaseTaxCodes().contains(code)) {
        throw new IllegalArgumentException(
            "'"
                + code
                + "' is not a Bulgarian purchase tax code. Allowed: "
                + String.join(", ", BgOcrVatMapping.purchaseTaxCodes())
                + ". An output-VAT code must never go on a purchase line.");
      }
      LocalDate date = documentDate(invoice);
      if (date == null) {
        throw new IllegalArgumentException(
            "The invoice has no date, so no tax version can be selected. Set the invoice date and"
                + " the supplier origin date first.");
      }
      Tax tax =
          Beans.get(TaxRepository.class).all().filter("self.code = :c").bind("c", code).fetchOne();
      if (tax == null) {
        throw new IllegalArgumentException(
            "Tax " + code + " is not configured in this database (l10n_bgr chart of accounts).");
      }
      TaxLine taxLine;
      try {
        taxLine = taxService.getTaxLine(tax, date);
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Tax " + code + " could not be resolved for " + date + ".");
      }
      if (taxLine == null) {
        throw new IllegalArgumentException(
            "Tax " + code + " has no version covering " + date + ".");
      }
      Set<TaxLine> set = new HashSet<>();
      set.add(taxLine);
      line.setTaxLineSet(set);
      line.setTaxRate(taxService.getTotalTaxRateInPercentage(set));
      line.setTaxCode(taxService.computeTaxCode(set));
    }
    Set<TaxLine> effective = line.getTaxLineSet();
    if (effective == null || effective.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return taxService.getTotalTaxRate(effective);
  }

  // ---------------------------------------------------------------------------
  // After the edit
  // ---------------------------------------------------------------------------

  /**
   * Persists, then re-derives every downstream value through the paths that already own it.
   *
   * <p>Nothing here decides that the invoice is now fine: the review status comes from {@code
   * missingReviewFields}, the blockers from the readiness service, and the arithmetic verdict from
   * the same reconciliation the record projection uses. A caller that wanted to declare success
   * cannot, because the answer is computed from the saved record.
   */
  protected LineEditResult finish(
      OcrDraftInvoiceGuard.Target target, LineEditResult result, String reason) {
    Invoice invoice = target.getInvoice();
    AiOcrUpload upload = target.getUpload();
    result.uploadId = upload.getId();
    result.invoiceId = invoice.getId();

    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    boolean complete = missing.isEmpty();
    invoice.setOcrReviewStatus(
        complete
            ? BgOcrIntakeService.OCR_REVIEW_COMPLETE
            : BgOcrIntakeService.OCR_REVIEW_TO_REVIEW);
    invoice.setOcrMissingFields(BgOcrIntakeService.missingFieldsLabel(missing));

    // Rebuild the VAT summary from the lines that were just written. Editing a line changes the
    // base a rate applies to, so the summary computed for the previous lines is stale — and on a
    // draft that never had one, this is what stops Validate asking "There is no taxes on this
    // invoice". Header totals are untouched; see OcrInvoiceTaxSummaryService.
    OcrInvoiceTaxSummaryService.Result taxes =
        Beans.get(OcrInvoiceTaxSummaryService.class).sync(invoice);
    // The lines just changed, so the draft is finished again here — the same shared, idempotent
    // entry point the OCR intake uses, so an edited draft is as final as a freshly created one.
    // false: the summary was rebuilt one line above, and rebuilding it twice in a transaction is
    // exactly what duplicated the Tax rows on invoice #15.
    Beans.get(OcrRoundingLineService.class).prepareDraft(invoice, false);

    // Before/after for each line, plus who authorised it and on what evidence. A reviewer opening
    // this invoice in six months should be able to see that its lines were rewritten, by whom,
    // whether a human approved it, and why — without reading a log.
    StringBuilder note =
        new StringBuilder("Line correction by ")
            .append(AiExecutionContext.callerDescription())
            .append(':');
    for (String entry : result.audit) {
      note.append("\n- ").append(entry);
    }
    if (reason != null && !reason.trim().isEmpty()) {
      note.append("\n  Evidence: ").append(reason.trim());
    }
    // Say which of the three it is. "Do not add up" for a one-stotinka rounding difference reads as
    // a failure and sends a reviewer looking for a mistake that is not there; saying nothing at all
    // for it hides that the posted VAT will differ from the printed one.
    OcrAmountReconciliation amounts = OcrAmountReconciliation.of(invoice);
    for (String difference : amounts.differences()) {
      note.append("\n  NOTE: ").append(difference);
    }
    if (taxes.isFailed()) {
      note.append("\n  NOTE: the VAT summary could not be rebuilt (")
          .append(taxes.getFailure())
          .append("). Axelor rebuilds it at validation and will fail the same way.");
    } else if (taxes.isChanged()) {
      note.append("\n  VAT summary: ").append(String.join("; ", taxes.getRows()));
    }
    String existing = invoice.getAiValidationNotes();
    invoice.setAiValidationNotes(
        existing == null || existing.trim().isEmpty() ? note.toString() : existing + "\n\n" + note);

    // JPA.save, never invoiceRepo.save: see the class javadoc.
    JPA.save(invoice);
    JPA.flush();

    upload.setProcessedOn(LocalDateTime.now());
    JPA.save(upload);
    Beans.get(BgOcrIntakeService.class).syncUploadReview(invoice.getId(), complete);

    OcrInvoiceReadiness readiness =
        Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());
    result.ocrReviewStatus = invoice.getOcrReviewStatus();
    result.missingReviewFields = missing;
    result.amountsReconcile = viewService.amountsReconcile(invoice);
    result.validateBlockers = readiness.getValidateBlockers();
    result.ventilateBlockers = readiness.getVentilateBlockers();
    result.warnings = readiness.getWarnings();
    result.usable =
        missing.isEmpty()
            && readiness.getValidateBlockers().isEmpty()
            && readiness.getVentilateBlockers().isEmpty();
    result.lines = viewService.lines(invoice);
    for (Map<String, Object> line : result.lines) {
      result.written.add(line);
    }

    // So a chat turn can tell the client which open form is now stale. No-op outside a chat turn,
    // and discarded when the transaction rolls back — see AiWriteEffects.
    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    AiWriteEffects.touched(AiOcrUpload.class.getName(), upload.getId());

    LOG.info(
        "OCR record {}: invoice {} lines edited by {} — {}",
        upload.getId(),
        invoice.getId(),
        AiExecutionContext.callerDescription(),
        String.join("; ", result.audit));
    return result;
  }

  /** Keeps {@code sequence} in step with position, the way the grid expects. */
  protected void resequence(Invoice invoice) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null) {
      return;
    }
    for (int i = 0; i < lines.size(); i++) {
      lines.get(i).setSequence(i);
    }
  }

  /**
   * The date Axelor derives tax versions and conversions from. Origin date first: on a purchase
   * invoice that is the supplier's document date and the one the suite itself reads.
   */
  protected static LocalDate documentDate(Invoice invoice) {
    return invoice.getOriginDate() != null ? invoice.getOriginDate() : invoice.getInvoiceDate();
  }

  private static Map<String, Object> snapshot(InvoiceLine line) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("lineId", line.getId());
    m.put("description", line.getDescription());
    m.put("productName", line.getProductName());
    m.put("qty", line.getQty());
    m.put("unitPrice", line.getPrice());
    m.put("exTaxTotal", line.getExTaxTotal());
    m.put("inTaxTotal", line.getInTaxTotal());
    m.put("taxCode", line.getTaxCode());
    return m;
  }

  private static String describe(InvoiceLine line) {
    return "'"
        + (line.getProductName() == null ? line.getDescription() : line.getProductName())
        + "' qty "
        + line.getQty()
        + " × "
        + line.getPrice()
        + " = "
        + line.getExTaxTotal()
        + " net, "
        + (line.getTaxCode() == null ? "no tax" : line.getTaxCode())
        + ", account "
        + (line.getAccount() == null ? "none" : line.getAccount().getCode());
  }

  private static String label(Product product) {
    return product.getCode() == null ? product.getName() : product.getCode();
  }

  /**
   * Field documentation, shared so a tool schema and any UI describe the same contract.
   *
   * <p>Deliberately terse. This map is emitted <em>twice</em> inside {@code
   * edit_supplier_invoice}'s schema — once for {@code replaceLines.lines} and once for {@code
   * lineOperations} — and that schema is sent on every model request of every conversation, so a
   * sentence here is paid for about as often as anything in this codebase can be. The reasoning
   * that used to live in these strings ("omit productId and the generic OCR product is applied,
   * never ask the user to create one for a one-off description", "amounts must add up") is not
   * lost: it is in the supplier-invoice domain guide, stated once per turn instead of twice per
   * request, and it is enforced in Java regardless of what the model read.
   */
  public static Map<String, String> lineFieldDocs() {
    Map<String, String> docs = new LinkedHashMap<>();
    docs.put("description", "What was bought, as printed. Required for a new line.");
    docs.put(
        "productId",
        "Id of an EXISTING product from find_product. Omit for a one-off description: the company's"
            + " generic OCR purchase product and its account are applied automatically.");
    docs.put("qty", "Quantity. Defaults to 1.");
    docs.put(
        "unitPrice",
        "NET unit price. Omit to derive it from exTaxTotal ÷ qty; if given, qty × unitPrice must"
            + " equal exTaxTotal.");
    docs.put(
        "exTaxTotal",
        "The line's net amount as printed — the authoritative figure. Gross is calculated from it"
            + " and the tax rate. May be negative for a discount row.");
    docs.put(
        "taxCode",
        "Purchase VAT code: " + String.join(", ", BgOcrVatMapping.purchaseTaxCodes()) + ".");
    docs.put("unitId", "Unit of measure by id. Omit to keep what the product resolved.");
    docs.put(
        "discountTypeSelect",
        "0 none, 1 percentage, 2 fixed amount — the price-list amount types.");
    docs.put("discountAmount", "Discount rate or amount, per discountTypeSelect.");
    docs.put(
        "accountId",
        "Accounting account by id, in this company — overrides what the product resolved. Give"
            + " either this or accountCode, not both.");
    docs.put("accountCode", "Accounting account by code, in this company.");
    docs.put("fixedAssets", "Whether the line is a fixed asset.");
    docs.put(
        "fixedAssetCategoryId",
        "Fixed asset category by id, in this company. Only meaningful when fixedAssets is true.");
    docs.put(
        "cutOffStartDate",
        "Cut-off period start date (yyyy-MM-dd). Only meaningful when the line's account manages a"
            + " cut-off period.");
    docs.put("cutOffEndDate", "Cut-off period end date (yyyy-MM-dd).");
    docs.put(
        "coefficient",
        "Multiplier applied to the line's amounts, between 0 and 1. Only meaningful when the"
            + " company has invoice-coefficient enabled.");
    docs.put(
        "analyticDistributionTemplateId",
        "Analytic distribution template by id, in this company — builds the line's analytic"
            + " distribution from it. Only usable when the resolved account authorises analytic"
            + " distribution.");
    return docs;
  }

  /**
   * The contributed line fields (Bulgarian VAT-credit type, ZДДФЛ income type) that apply to a
   * supplier line — the same {@link AiInvoiceFieldRegistry} the plain-invoice editor schema draws
   * from, so an OCR-anchored invoice and a hand-entered one offer identical field names and never
   * drift apart.
   *
   * <p>Production entry point: resolves the registry through Guice and lets a genuine wiring
   * failure propagate — a tool that silently advertised fewer fields than are actually installed
   * would be a worse failure mode than one that fails loudly at startup. A pure unit test with no
   * injector at all should call {@link #contributedLineFieldSpecs(AiInvoiceFieldRegistry)} directly
   * with its own registry instance instead of going through this overload.
   */
  public static List<AiInvoiceFieldSpec> contributedLineFieldSpecs() {
    return contributedLineFieldSpecs(Beans.get(AiInvoiceFieldRegistry.class));
  }

  /**
   * The real work, taking the registry explicitly — the test seam ({@code AiInvoiceFieldRegistry}
   * needs no Guice injection itself; only {@link Beans#get(Class)} does).
   */
  public static List<AiInvoiceFieldSpec> contributedLineFieldSpecs(
      AiInvoiceFieldRegistry registry) {
    List<AiInvoiceFieldSpec> specs = new ArrayList<>();
    for (AiInvoiceFieldSpec spec : registry.lineFields().values()) {
      if (spec.appliesToSupplier()) {
        specs.add(spec);
      }
    }
    return specs;
  }

  /** The operations {@code edit_ocr_invoice_line} accepts, shared with its schema. */
  public static final List<String> OPERATIONS = Arrays.asList("add", "update", "delete");
}
