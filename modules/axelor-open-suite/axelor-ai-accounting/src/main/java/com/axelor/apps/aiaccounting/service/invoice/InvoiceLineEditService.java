package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AnalyticDistributionTemplate;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxEquiv;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AnalyticDistributionTemplateRepository;
import com.axelor.apps.account.db.repo.FixedAssetCategoryRepository;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.account.service.invoice.InvoiceLineAnalyticService;
import com.axelor.apps.account.service.invoice.InvoiceLineService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec.Type;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.UnitRepository;
import com.axelor.apps.base.service.tax.TaxService;
import com.axelor.db.JpaSecurity;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adds, changes and removes invoice lines through Axelor's own line services.
 *
 * <h2>The onChange chains the form runs, run here explicitly</h2>
 *
 * A line is not a row of numbers; picking a product fills the tax, the account, the unit and the
 * price, and changing any amount recomputes the line totals. In the UI those happen because the
 * form fires {@code onChange} actions. A service has no form, so it has to make the same calls
 * itself, and this class is where they are made:
 *
 * <ul>
 *   <li>a product set or changed → {@code InvoiceLineService.fillProductInformation}, whose
 *       <em>returned values</em> are applied here (it deliberately does not write most of them back
 *       onto the line — the controller does that through {@code response.setValues});
 *   <li>any amount, quantity, discount or tax change → {@code InvoiceLineService.compute}, which
 *       does write its results onto the line, so the totals are Axelor's own numbers rather than
 *       arithmetic repeated here;
 *   <li>the caller's own explicit values are applied <b>after</b> the product fill, so a price the
 *       user gave wins over the catalogue price rather than being silently overwritten by it.
 * </ul>
 *
 * <h2>What it will not do</h2>
 *
 * No line total is settable — {@code exTaxTotal}, {@code inTaxTotal}, the company-currency pair,
 * {@code taxRate}, {@code taxCode} and {@code priceDiscounted} are all outputs of {@code compute}.
 * Offering them would let a caller make a line's total disagree with its own quantity and price.
 */
@Singleton
public class InvoiceLineEditService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(InvoiceLineEditService.class);

  public static final String F_PRODUCT_ID = "productId";
  public static final String F_PRODUCT_CODE = "productCode";
  public static final String F_PRODUCT_NAME = "productName";
  public static final String F_DESCRIPTION = "description";
  public static final String F_QTY = "qty";
  public static final String F_PRICE = "price";
  public static final String F_IN_TAX_PRICE = "inTaxPrice";
  public static final String F_UNIT_ID = "unitId";
  public static final String F_DISCOUNT_AMOUNT = "discountAmount";
  public static final String F_DISCOUNT_TYPE_SELECT = "discountTypeSelect";
  public static final String F_ACCOUNT_ID = "accountId";
  public static final String F_ACCOUNT_CODE = "accountCode";
  public static final String F_FIXED_ASSETS = "fixedAssets";
  public static final String F_FIXED_ASSET_CATEGORY_ID = "fixedAssetCategoryId";
  public static final String F_TAX_CODE = "taxCode";
  public static final String F_CUT_OFF_START_DATE = "cutOffStartDate";
  public static final String F_CUT_OFF_END_DATE = "cutOffEndDate";
  public static final String F_COEFFICIENT = "coefficient";
  public static final String F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID = "analyticDistributionTemplateId";

  /** The core line fields, in the order they are documented. */
  public static final Set<String> CORE_FIELDS =
      new LinkedHashSet<>(
          java.util.Arrays.asList(
              F_PRODUCT_ID,
              F_PRODUCT_CODE,
              F_PRODUCT_NAME,
              F_DESCRIPTION,
              F_QTY,
              F_PRICE,
              F_IN_TAX_PRICE,
              F_UNIT_ID,
              F_DISCOUNT_AMOUNT,
              F_DISCOUNT_TYPE_SELECT,
              F_TAX_CODE,
              F_ACCOUNT_ID,
              F_ACCOUNT_CODE,
              F_FIXED_ASSETS,
              F_FIXED_ASSET_CATEGORY_ID,
              F_CUT_OFF_START_DATE,
              F_CUT_OFF_END_DATE,
              F_COEFFICIENT,
              F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID));

  /** Only a supplier invoice shows this; the customer form hides it. */
  public static final Set<String> SUPPLIER_ONLY_FIELDS =
      new LinkedHashSet<>(java.util.Arrays.asList(F_FIXED_ASSETS, F_FIXED_ASSET_CATEGORY_ID));

  /** What one line operation did. */
  public static final class LineResult {
    private final Long lineId;
    private final String operation;
    private final List<AiInvoiceFieldChange> changes = new ArrayList<>();

    LineResult(Long lineId, String operation) {
      this.lineId = lineId;
      this.operation = operation;
    }

    public Long getLineId() {
      return lineId;
    }

    public String getOperation() {
      return operation;
    }

    public List<AiInvoiceFieldChange> getChanges() {
      return changes;
    }
  }

  /**
   * Adds a line to the invoice.
   *
   * <p>Not saved: the line is attached to the invoice's own list and persisted with it by the
   * caller's transaction, so a failure later in the same call leaves nothing behind.
   */
  public LineResult addLine(Invoice invoice, InvoiceSide side, JsonNode spec) {
    requireObject(spec);
    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setTypeSelect(InvoiceLineRepository.TYPE_NORMAL);
    line.setQty(BigDecimal.ONE);
    LineResult result = new LineResult(null, "add");
    applySpec(invoice, side, line, spec, result);
    settleUnit(line);
    requireUsable(line);
    if (invoice.getInvoiceLineList() == null) {
      invoice.setInvoiceLineList(new ArrayList<>());
    }
    line.setSequence(invoice.getInvoiceLineList().size());
    invoice.getInvoiceLineList().add(line);
    return result;
  }

  /** Changes an existing line of this invoice. */
  public LineResult updateLine(Invoice invoice, InvoiceSide side, InvoiceLine line, JsonNode spec) {
    requireObject(spec);
    LineResult result = new LineResult(line.getId(), "update");
    applySpec(invoice, side, line, spec, result);
    settleUnit(line);
    requireUsable(line);
    return result;
  }

  /**
   * Settles the line's unit through the one shared rule: what the line already carries wins
   * (whether the caller stated it or the product filled it), else the product's own unit, else the
   * localization default.
   *
   * <p>On UPDATE as well as on add, deliberately: {@code unitId: null} is an accepted spec value,
   * so an update is a way to end up with a normal line carrying no unit — which is exactly what
   * must not reach the database.
   *
   * <p>Converted to the module's own blocker: this is an AI tool boundary and its failures are
   * reported to the caller as blockers, not thrown as {@code AxelorException}.
   */
  protected void settleUnit(InvoiceLine line) {
    try {
      unitDefaults().applyTo(line);
    } catch (AxelorException e) {
      throw new InvoiceBlocked(AiBlockerCode.MISSING_UNIT_CONFIG, e.getMessage());
    }
  }

  /** Seam: the production-path tests drive the real add/update with a stub here. */
  protected InvoiceLineUnitDefaultService unitDefaults() {
    return Beans.get(InvoiceLineUnitDefaultService.class);
  }

  /**
   * Seam for the same reason: the contributed-field registry is scanned from the classpath through
   * Guice, which a unit test has no injector for, and it has nothing to do with the rules being
   * asserted.
   */
  protected AiInvoiceFieldRegistry fieldRegistry() {
    return Beans.get(AiInvoiceFieldRegistry.class);
  }

  /**
   * Removes a line from the invoice.
   *
   * <p>Removed from the parent's list rather than deleted directly, so Axelor's orphan handling
   * does the delete inside the same transaction as everything else in the call.
   */
  public LineResult deleteLine(Invoice invoice, InvoiceLine line) {
    LineResult result = new LineResult(line.getId(), "delete");
    result.changes.add(new AiInvoiceFieldChange("line", describe(line), null));
    if (invoice.getInvoiceLineList() != null) {
      invoice.getInvoiceLineList().remove(line);
    }
    line.setInvoice(null);
    // Keep the remaining lines' order contiguous; the form does the same on a removal.
    resequence(invoice);
    return result;
  }

  /**
   * Every line field this side may set, described well enough to build a JSON schema from.
   *
   * <p>Generated for the same reason as the header's: one list, so the schema and the allowlist
   * cannot disagree, and a contributed field appears in both without anyone editing a schema.
   */
  public static List<com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec>
      fieldSpecs(InvoiceSide side) {
    List<com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec> specs =
        new ArrayList<>();
    boolean supplier = side == InvoiceSide.SUPPLIER;
    specs.add(
        spec(F_PRODUCT_ID, Type.INTEGER, "Product by id. Fills tax, account, unit and price."));
    specs.add(
        spec(
            F_PRODUCT_CODE,
            Type.STRING,
            "Product by its exact code. Nothing similar is substituted."));
    specs.add(
        spec(
            F_PRODUCT_NAME,
            Type.STRING,
            "The line's own name. Required when there is no product."));
    specs.add(spec(F_DESCRIPTION, Type.STRING, "Free-text description of the line."));
    specs.add(spec(F_QTY, Type.NUMBER, "Quantity.", false));
    specs.add(
        spec(
            F_PRICE,
            Type.NUMBER,
            "Unit price EXCLUDING tax. Use this when the invoice is not in-ATI. Never both prices.",
            false));
    specs.add(
        spec(
            F_IN_TAX_PRICE,
            Type.NUMBER,
            "Unit price INCLUDING tax. Use this when the invoice is in-ATI. Never both prices.",
            false));
    specs.add(spec(F_UNIT_ID, Type.INTEGER, "Unit of measure by id."));
    specs.add(
        spec(
            F_DISCOUNT_TYPE_SELECT,
            Type.INTEGER,
            "0 none, 1 percentage, 2 fixed amount — the price-list amount types.",
            false));
    specs.add(
        spec(F_DISCOUNT_AMOUNT, Type.NUMBER, "Discount rate or amount, per discount type.", false));
    specs.add(
        spec(
            F_TAX_CODE,
            Type.STRING,
            "Tax by its exact code — overrides what the product resolved. Clearable (line becomes"
                + " exempt): send null."));
    specs.add(spec(F_ACCOUNT_ID, Type.INTEGER, "Account by id, in this company.", false));
    specs.add(spec(F_ACCOUNT_CODE, Type.STRING, "Account by code, in this company.", false));
    if (supplier) {
      specs.add(
          spec(
              F_FIXED_ASSETS,
              Type.BOOLEAN,
              "Whether the line is a fixed asset. Supplier invoices only.",
              false));
      specs.add(
          spec(
              F_FIXED_ASSET_CATEGORY_ID,
              Type.INTEGER,
              "Fixed asset category by id, in this company. Only meaningful when fixedAssets is"
                  + " true."));
    }
    specs.add(
        spec(
            F_CUT_OFF_START_DATE,
            Type.STRING,
            "Cut-off period start date (yyyy-MM-dd). Only meaningful when the line's account"
                + " manages a cut-off period."));
    specs.add(spec(F_CUT_OFF_END_DATE, Type.STRING, "Cut-off period end date (yyyy-MM-dd)."));
    specs.add(
        spec(
            F_COEFFICIENT,
            Type.NUMBER,
            "Multiplier applied to the line's amounts, between 0 and 1. Only meaningful when the"
                + " company has invoice-coefficient enabled.",
            false));
    specs.add(
        spec(
            F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID,
            Type.INTEGER,
            "Analytic distribution template by id, in this company — builds the line's analytic"
                + " distribution from it. Only usable when the resolved account authorises analytic"
                + " distribution."));
    try {
      for (com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec contributed :
          Beans.get(AiInvoiceFieldRegistry.class).lineFields().values()) {
        if (supplier ? contributed.appliesToSupplier() : contributed.appliesToCustomer()) {
          specs.add(contributed);
        }
      }
    } catch (RuntimeException e) {
      // No injector or no registry — see the note on InvoiceHeaderEditService.fieldSpecs.
      LOG.debug("AI Accountant: contributed line fields unavailable: {}", e.getMessage());
    }
    return specs;
  }

  /** A line field null empties — the product, the unit, the free text. */
  private static com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec spec(
      String name, Type type, String description) {
    return spec(name, type, description, true);
  }

  /**
   * A line field, saying whether null empties it.
   *
   * <p>The numbers and the account do not: {@code requireDecimal}, {@code requireInt} and {@code
   * resolveAccount} all refuse null, because a line with no quantity or no price is not a line and
   * a line with no account cannot be posted. Declaring them clearable told the model it could send
   * a null the apply code was always going to refuse.
   */
  private static com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec spec(
      String name, Type type, String description, boolean clearable) {
    return new com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec(
        name,
        type,
        description,
        null,
        clearable,
        com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec.Applies.BOTH);
  }

  /** Every line field name this side may set, core and contributed. */
  public Set<String> writableFieldNames(InvoiceSide side) {
    Set<String> names = new LinkedHashSet<>();
    for (String field : CORE_FIELDS) {
      if (!SUPPLIER_ONLY_FIELDS.contains(field) || side == InvoiceSide.SUPPLIER) {
        names.add(field);
      }
    }
    AiInvoiceFieldRegistry registry = fieldRegistry();
    for (Map.Entry<String, com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec> e :
        registry.lineFields().entrySet()) {
      boolean applies =
          side == InvoiceSide.SUPPLIER
              ? e.getValue().appliesToSupplier()
              : e.getValue().appliesToCustomer();
      if (applies) {
        names.add(e.getKey());
      }
    }
    return names;
  }

  // ── the work ──────────────────────────────────────────────────────────────

  private void applySpec(
      Invoice invoice, InvoiceSide side, InvoiceLine line, JsonNode spec, LineResult result) {
    Set<String> allowed = writableFieldNames(side);
    Iterator<String> names = spec.fieldNames();
    List<String> unknown = new ArrayList<>();
    while (names.hasNext()) {
      String field = names.next();
      if (!allowed.contains(field)) {
        unknown.add(field);
      }
    }
    if (!unknown.isEmpty()) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "Not writable line field(s): "
              + String.join(", ", unknown)
              + ". Allowed: "
              + String.join(", ", allowed)
              + ".");
    }

    // 1. The product first, because everything it fills is a default the caller may then override.
    if (spec.has(F_PRODUCT_ID) || spec.has(F_PRODUCT_CODE)) {
      Product product = resolveProduct(spec);
      String before = line.getProduct() == null ? null : line.getProduct().getCode();
      line.setProduct(product);
      result.changes.add(
          new AiInvoiceFieldChange("product", before, product == null ? null : product.getCode()));
      fillFromProduct(invoice, line);
    }

    // 2. Then the caller's own values.
    if (spec.has(F_PRODUCT_NAME)) {
      String value = optString(spec.get(F_PRODUCT_NAME), 255);
      record(result, F_PRODUCT_NAME, line.getProductName(), value);
      line.setProductName(value);
    }
    if (spec.has(F_DESCRIPTION)) {
      String value = optString(spec.get(F_DESCRIPTION), 4000);
      record(result, F_DESCRIPTION, line.getDescription(), value);
      line.setDescription(value);
    }
    if (spec.has(F_QTY)) {
      BigDecimal value = requireDecimal(F_QTY, spec.get(F_QTY));
      record(result, F_QTY, text(line.getQty()), text(value));
      line.setQty(value);
    }
    if (spec.has(F_UNIT_ID)) {
      Unit unit = isNull(spec.get(F_UNIT_ID)) ? null : resolveUnit(spec.get(F_UNIT_ID));
      record(
          result,
          "unit",
          line.getUnit() == null ? null : line.getUnit().getName(),
          unit == null ? null : unit.getName());
      line.setUnit(unit);
    }
    if (spec.has(F_DISCOUNT_TYPE_SELECT)) {
      int value = requireInt(F_DISCOUNT_TYPE_SELECT, spec.get(F_DISCOUNT_TYPE_SELECT));
      record(result, F_DISCOUNT_TYPE_SELECT, text(line.getDiscountTypeSelect()), text(value));
      line.setDiscountTypeSelect(value);
    }
    if (spec.has(F_DISCOUNT_AMOUNT)) {
      BigDecimal value = requireDecimal(F_DISCOUNT_AMOUNT, spec.get(F_DISCOUNT_AMOUNT));
      record(result, F_DISCOUNT_AMOUNT, text(line.getDiscountAmount()), text(value));
      line.setDiscountAmount(value);
    }
    // The price the invoice is expressed in wins, and the other one is derived from it by compute.
    // Accepting both would leave two figures that disagree with no rule for which is right.
    boolean inAti = Boolean.TRUE.equals(invoice.getInAti());
    if (spec.has(F_PRICE) && spec.has(F_IN_TAX_PRICE)) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "Give either price or inTaxPrice, not both. This invoice is expressed "
              + (inAti ? "including tax, so send inTaxPrice." : "excluding tax, so send price."));
    }
    if (spec.has(F_PRICE)) {
      BigDecimal value = requireDecimal(F_PRICE, spec.get(F_PRICE));
      record(result, F_PRICE, text(line.getPrice()), text(value));
      line.setPrice(value);
      if (inAti) {
        // Told the excluding-tax figure on an including-tax invoice. Kept rather than refused —
        // it is unambiguous — but the derived figure is left to compute below.
        line.setInTaxPrice(null);
      }
    }
    if (spec.has(F_IN_TAX_PRICE)) {
      BigDecimal value = requireDecimal(F_IN_TAX_PRICE, spec.get(F_IN_TAX_PRICE));
      record(result, F_IN_TAX_PRICE, text(line.getInTaxPrice()), text(value));
      line.setInTaxPrice(value);
      if (!inAti) {
        line.setPrice(null);
      }
    }
    if (spec.has(F_ACCOUNT_ID) || spec.has(F_ACCOUNT_CODE)) {
      Account account = resolveAccount(spec);
      record(
          result,
          "account",
          line.getAccount() == null ? null : line.getAccount().getCode(),
          account.getCode());
      line.setAccount(account);
    }
    if (spec.has(F_FIXED_ASSETS)) {
      JsonNode node = spec.get(F_FIXED_ASSETS);
      boolean value = node.isBoolean() ? node.asBoolean() : Boolean.parseBoolean(node.asText(""));
      record(result, F_FIXED_ASSETS, text(line.getFixedAssets()), text(value));
      line.setFixedAssets(value);
    }
    if (spec.has(F_FIXED_ASSET_CATEGORY_ID)) {
      FixedAssetCategory category = resolveFixedAssetCategory(spec);
      record(
          result,
          "fixedAssetCategory",
          line.getFixedAssetCategory() == null ? null : line.getFixedAssetCategory().getName(),
          category == null ? null : category.getName());
      line.setFixedAssetCategory(category);
    }
    // Tax is an override on top of what the product resolved (fillFromProduct, step 1, already set
    // taxLineSet via fillPriceAndAccount) — same rule as every other explicit value here: the
    // caller's own choice wins over the catalogue default. Clearable, unlike account: a line with
    // no tax at all is a legitimate exempt supply.
    if (spec.has(F_TAX_CODE)) {
      Set<TaxLine> before = line.getTaxLineSet();
      Set<TaxLine> resolved = resolveTaxLineSet(invoice, spec);
      record(result, "tax", taxLabel(before), taxLabel(resolved));
      line.setTaxLineSet(resolved);
    }
    if (spec.has(F_CUT_OFF_START_DATE)) {
      LocalDate value = optLocalDate(F_CUT_OFF_START_DATE, spec.get(F_CUT_OFF_START_DATE));
      record(result, F_CUT_OFF_START_DATE, text(line.getCutOffStartDate()), text(value));
      line.setCutOffStartDate(value);
    }
    if (spec.has(F_CUT_OFF_END_DATE)) {
      LocalDate value = optLocalDate(F_CUT_OFF_END_DATE, spec.get(F_CUT_OFF_END_DATE));
      record(result, F_CUT_OFF_END_DATE, text(line.getCutOffEndDate()), text(value));
      line.setCutOffEndDate(value);
    }
    if (line.getCutOffStartDate() != null
        && line.getCutOffEndDate() != null
        && line.getCutOffStartDate().isAfter(line.getCutOffEndDate())) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "cutOffStartDate must not be after cutOffEndDate.");
    }
    if (spec.has(F_COEFFICIENT)) {
      BigDecimal value = requireDecimal(F_COEFFICIENT, spec.get(F_COEFFICIENT));
      if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
        throw new InvoiceBlocked(
            AiBlockerCode.BAD_ARGUMENTS, "'" + F_COEFFICIENT + "' must be between 0 and 1.");
      }
      record(result, F_COEFFICIENT, text(line.getCoefficient()), text(value));
      line.setCoefficient(value);
    }
    if (spec.has(F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID)) {
      AnalyticDistributionTemplate template = resolveAnalyticDistributionTemplate(spec);
      record(
          result,
          "analyticDistributionTemplate",
          line.getAnalyticDistributionTemplate() == null
              ? null
              : line.getAnalyticDistributionTemplate().getName(),
          template == null ? null : template.getName());
      line.setAnalyticDistributionTemplate(template);
      if (template != null) {
        // Axelor's own service, the same one the form's onChange calls — it reads the template and
        // the line's account/company and returns the distribution rows; nothing here re-derives the
        // split itself.
        try {
          line.setAnalyticMoveLineList(
              Beans.get(InvoiceLineAnalyticService.class)
                  .createAnalyticDistributionWithTemplate(line));
        } catch (Exception e) {
          throw new InvoiceBlocked(
              AiBlockerCode.TOOL_FAILED,
              "Axelor could not build the analytic distribution from this template: " + message(e),
              e);
        }
      }
    }

    // 3. Contributed line fields — the Bulgarian VAT-credit and income types on a BG install.
    AiInvoiceFieldRegistry registry = fieldRegistry();
    Iterator<String> all = spec.fieldNames();
    while (all.hasNext()) {
      String field = all.next();
      if (CORE_FIELDS.contains(field)) {
        continue;
      }
      AiInvoiceFieldChange change = registry.applyLineField(line, field, spec.get(field));
      if (change == null) {
        throw new InvoiceBlocked(
            AiBlockerCode.BAD_ARGUMENTS,
            "No module accepted line field '" + field + "', though one declared it.");
      }
      if (!AiInvoiceFieldChange.same(change.getOldValue(), change.getNewValue())) {
        result.changes.add(change);
      }
    }

    // 4. Axelor's own recompute, always. It writes the line totals, the tax rate and the tax code,
    // so those figures are never arithmetic repeated here.
    recompute(invoice, line);
  }

  /**
   * Applies what {@code fillProductInformation} worked out for this product.
   *
   * <p>The method returns its answers as a map because the form applies them through {@code
   * response.setValues}. There is no form here, so they are applied field by field, typed, and only
   * the ones a line actually carries. An {@code error} entry means Axelor could not resolve the
   * product's accounting configuration, and that is a refusal rather than something to work around.
   */
  protected void fillFromProduct(Invoice invoice, InvoiceLine line) {
    if (line.getProduct() == null) {
      return;
    }
    Map<String, Object> filled;
    try {
      filled = Beans.get(InvoiceLineService.class).fillProductInformation(invoice, line);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.TOOL_FAILED,
          "Axelor could not resolve this product's invoice settings: " + message(e),
          e);
    }
    if (filled == null) {
      return;
    }
    Object error = filled.get("error");
    if (error instanceof String && !((String) error).trim().isEmpty()) {
      throw new InvoiceBlocked(
          AiBlockerCode.MISSING_ACCOUNT_CONFIG,
          "This product cannot be used on this invoice: " + error);
    }
    apply(filled, "account", Account.class, line::setAccount);
    apply(filled, "taxEquiv", TaxEquiv.class, line::setTaxEquiv);
    apply(filled, "unit", Unit.class, line::setUnit);
    apply(filled, "price", BigDecimal.class, line::setPrice);
    apply(filled, "inTaxPrice", BigDecimal.class, line::setInTaxPrice);
    apply(filled, "discountAmount", BigDecimal.class, line::setDiscountAmount);
    apply(filled, "productName", String.class, line::setProductName);
    apply(filled, "productCode", String.class, line::setProductCode);
    apply(filled, "description", String.class, line::setDescription);
    Object discountType = filled.get("discountTypeSelect");
    if (discountType instanceof Number) {
      line.setDiscountTypeSelect(((Number) discountType).intValue());
    }
    // taxLineSet is the one value fillPriceAndAccount writes onto the line itself, so it is
    // deliberately not re-applied here.
  }

  /** Axelor's own line computation. Writes the totals onto the line. */
  protected void recompute(Invoice invoice, InvoiceLine line) {
    try {
      Beans.get(InvoiceLineService.class).compute(invoice, line);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.TOOL_FAILED, "Axelor could not compute this line: " + message(e), e);
    }
  }

  /** Renumbers the lines so their sequence stays contiguous after a removal. */
  protected void resequence(Invoice invoice) {
    if (invoice.getInvoiceLineList() == null) {
      return;
    }
    int index = 0;
    for (InvoiceLine remaining : invoice.getInvoiceLineList()) {
      remaining.setSequence(index++);
    }
  }

  // ── resolution and primitives ─────────────────────────────────────────────

  private Product resolveProduct(JsonNode spec) {
    // Both named: the schema says never both, and preferring the id here would silently ignore a
    // code the caller thought it was sending. Two references to one product mean the caller is not
    // sure which product it wants.
    if (!isNull(spec.get(F_PRODUCT_ID)) && !isNull(spec.get(F_PRODUCT_CODE))) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "Give either productId or productCode, not both.");
    }
    if (spec.has(F_PRODUCT_ID) && !isNull(spec.get(F_PRODUCT_ID))) {
      long id = requireLong(F_PRODUCT_ID, spec.get(F_PRODUCT_ID));
      Product product = Beans.get(ProductRepository.class).find(id);
      if (product == null) {
        throw new InvoiceBlocked(AiBlockerCode.RECORD_NOT_FOUND, "No product with id " + id + ".");
      }
      return product;
    }
    if (spec.has(F_PRODUCT_CODE) && !isNull(spec.get(F_PRODUCT_CODE))) {
      String code = spec.get(F_PRODUCT_CODE).asText("").trim();
      if (code.isEmpty()) {
        throw new InvoiceBlocked(AiBlockerCode.BAD_ARGUMENTS, "productCode must not be blank.");
      }
      List<Product> matches =
          Beans.get(ProductRepository.class)
              .all()
              .filter("LOWER(self.code) = :code AND COALESCE(self.dtype, 'Product') = 'Product'")
              .bind("code", code.toLowerCase(Locale.ROOT))
              .fetch(2);
      if (matches.isEmpty()) {
        throw new InvoiceBlocked(
            AiBlockerCode.RECORD_NOT_FOUND,
            "No product with code '" + code + "'. Nothing similar is substituted.");
      }
      if (matches.size() > 1) {
        throw new InvoiceBlocked(
            AiBlockerCode.AMBIGUOUS_CONTEXT,
            "More than one product has the code '" + code + "'; name the productId instead.");
      }
      return matches.get(0);
    }
    // Both present but null: the caller is clearing the product, which leaves a free-text line.
    return null;
  }

  private Account resolveAccount(JsonNode spec) {
    Long companyId = AiCompanyScope.requireCompanyId();
    if (!isNull(spec.get(F_ACCOUNT_ID)) && !isNull(spec.get(F_ACCOUNT_CODE))) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "Give either accountId or accountCode, not both.");
    }
    if (spec.has(F_ACCOUNT_ID) && !isNull(spec.get(F_ACCOUNT_ID))) {
      long id = requireLong(F_ACCOUNT_ID, spec.get(F_ACCOUNT_ID));
      Account account = Beans.get(AccountRepository.class).find(id);
      if (account == null
          || !AiCompanyScope.owns(account.getCompany())
          || !readable(Account.class, account.getId())) {
        throw new InvoiceBlocked(
            AiBlockerCode.RECORD_NOT_FOUND, "No account with id " + id + " in this company.");
      }
      return account;
    }
    String code = spec.get(F_ACCOUNT_CODE) == null ? null : spec.get(F_ACCOUNT_CODE).asText("");
    if (code == null || code.trim().isEmpty()) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "Give an accountId or a non-blank accountCode.");
    }
    Account account =
        Beans.get(AccountRepository.class)
            .all()
            .filter("LOWER(self.code) = :code AND self.company.id = :companyId")
            .bind("code", code.trim().toLowerCase(Locale.ROOT))
            .bind("companyId", companyId)
            .fetchOne();
    if (account == null || !readable(Account.class, account.getId())) {
      throw new InvoiceBlocked(
          AiBlockerCode.RECORD_NOT_FOUND,
          "No account with code '" + code.trim() + "' in this company.");
    }
    return account;
  }

  /**
   * {@code null} when {@code fixedAssetCategoryId} is explicitly null — clears the category. {@code
   * FixedAssetCategory} has no code, only a name, so id is the only reference this offers — unlike
   * account or product, a name is not a stable enough key for a caller to send back.
   */
  private FixedAssetCategory resolveFixedAssetCategory(JsonNode spec) {
    if (isNull(spec.get(F_FIXED_ASSET_CATEGORY_ID))) {
      return null; // explicitly null: clear
    }
    long id = requireLong(F_FIXED_ASSET_CATEGORY_ID, spec.get(F_FIXED_ASSET_CATEGORY_ID));
    FixedAssetCategory category = Beans.get(FixedAssetCategoryRepository.class).find(id);
    if (category == null
        || !AiCompanyScope.owns(category.getCompany())
        || !readable(FixedAssetCategory.class, category.getId())) {
      throw new InvoiceBlocked(
          AiBlockerCode.RECORD_NOT_FOUND,
          "No fixed asset category with id " + id + " in this company.");
    }
    return category;
  }

  /**
   * The tax's version covering the invoice's own date, resolved by CODE from a general
   * (non-country-scoped, not BG-restricted) lookup — {@code Tax} carries no company, so a caller's
   * read permission is the whole control, same as {@link #resolveUnit}. Code-only, matching {@code
   * OcrInvoiceLineEditService}'s tax field exactly (that path is intentionally restricted to a
   * closed Bulgarian purchase-code list; this one is not, because this module is country-agnostic —
   * but the two share one field name and shape in the tool schema, so they must share one input
   * type). {@code null} when the field is explicitly null: a cleared tax is a legitimate exempt
   * line, unlike a cleared account.
   */
  private Set<TaxLine> resolveTaxLineSet(Invoice invoice, JsonNode spec) {
    if (isNull(spec.get(F_TAX_CODE))) {
      return null; // explicitly null: clear — an exempt line
    }
    String code = spec.get(F_TAX_CODE).asText("").trim();
    if (code.isEmpty()) {
      throw new InvoiceBlocked(AiBlockerCode.BAD_ARGUMENTS, "taxCode must not be blank.");
    }
    Tax tax =
        Beans.get(TaxRepository.class)
            .all()
            .filter("LOWER(self.code) = :code")
            .bind("code", code.toLowerCase(Locale.ROOT))
            .fetchOne();
    if (tax == null || !readable(Tax.class, tax.getId())) {
      throw new InvoiceBlocked(AiBlockerCode.RECORD_NOT_FOUND, "No tax with code '" + code + "'.");
    }
    LocalDate date = invoice.getInvoiceDate();
    if (date == null) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "The invoice has no date, so no tax version can be selected. Set the invoice date"
              + " first.");
    }
    TaxLine taxLine;
    try {
      taxLine = Beans.get(TaxService.class).getTaxLine(tax, date);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.TOOL_FAILED,
          "Tax " + tax.getCode() + " could not be resolved for " + date + ": " + message(e),
          e);
    }
    if (taxLine == null) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "Tax " + tax.getCode() + " has no version covering " + date + ".");
    }
    Set<TaxLine> set = new HashSet<>();
    set.add(taxLine);
    return set;
  }

  /**
   * {@code null} when {@code analyticDistributionTemplateId} is explicitly null — clears the
   * template. {@code AnalyticDistributionTemplate} has no code, only a name, so id is the only
   * reference this offers, same reasoning as {@link #resolveFixedAssetCategory}.
   */
  private AnalyticDistributionTemplate resolveAnalyticDistributionTemplate(JsonNode spec) {
    if (isNull(spec.get(F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID))) {
      return null; // explicitly null: clear
    }
    long id =
        requireLong(
            F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID, spec.get(F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID));
    AnalyticDistributionTemplate template =
        Beans.get(AnalyticDistributionTemplateRepository.class).find(id);
    if (template == null
        || !AiCompanyScope.owns(template.getCompany())
        || !readable(AnalyticDistributionTemplate.class, template.getId())) {
      throw new InvoiceBlocked(
          AiBlockerCode.RECORD_NOT_FOUND,
          "No analytic distribution template with id " + id + " in this company.");
    }
    return template;
  }

  /**
   * A unit of measure, which is global master data — Axelor puts no company on {@code Unit}, so
   * there is no ownership to check and the caller's own read permission is the whole control.
   */
  private Unit resolveUnit(JsonNode value) {
    long id = requireLong(F_UNIT_ID, value);
    Unit unit = Beans.get(UnitRepository.class).find(id);
    if (unit == null || !readable(Unit.class, unit.getId())) {
      throw new InvoiceBlocked(AiBlockerCode.RECORD_NOT_FOUND, "No unit with id " + id + ".");
    }
    return unit;
  }

  /**
   * Whether the caller may read this record at all, under the same {@code JpaSecurity} rules the
   * form obeys.
   *
   * <p>Company ownership answers "is this the right record"; this answers "is this caller allowed
   * to see it", which the first does not imply. A false is turned into the caller's own <em>not
   * found</em> message, identical to the one a genuinely missing id produces, so a permission
   * boundary cannot be used to probe what exists. Mirrors {@code InvoiceHeaderEditService.readable}
   * — the header and the lines of one invoice must not answer this question differently.
   */
  private static boolean readable(Class<? extends Model> type, Long id) {
    return id != null && Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_READ, type, id);
  }

  /** The tax codes in a line's tax set, joined for an audit note — never null-unsafe. */
  private static String taxLabel(Set<TaxLine> taxLineSet) {
    if (taxLineSet == null || taxLineSet.isEmpty()) {
      return null;
    }
    return taxLineSet.stream()
        .map(tl -> tl.getTax() == null ? null : tl.getTax().getCode())
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.joining(","));
  }

  private static LocalDate optLocalDate(String field, JsonNode value) {
    if (isNull(value)) {
      return null;
    }
    try {
      return LocalDate.parse(value.asText("").trim());
    } catch (DateTimeParseException e) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' must be a date (yyyy-MM-dd).");
    }
  }

  /** A line has to be identifiable and priced before it is worth saving. */
  private void requireUsable(InvoiceLine line) {
    boolean named =
        (line.getProductName() != null && !line.getProductName().trim().isEmpty())
            || (line.getDescription() != null && !line.getDescription().trim().isEmpty());
    if (!named) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "A line needs a product, a product name or a description; none was given.");
    }
    if (line.getQty() == null) {
      throw new InvoiceBlocked(AiBlockerCode.BAD_ARGUMENTS, "A line needs a quantity.");
    }
  }

  private static <T> void apply(
      Map<String, Object> values,
      String key,
      Class<T> type,
      java.util.function.Consumer<T> setter) {
    Object value = values.get(key);
    if (type.isInstance(value)) {
      setter.accept(type.cast(value));
    }
  }

  private static void record(LineResult result, String field, String oldValue, String newValue) {
    if (!AiInvoiceFieldChange.same(oldValue, newValue)) {
      result.changes.add(new AiInvoiceFieldChange(field, oldValue, newValue));
    }
  }

  private static void requireObject(JsonNode spec) {
    if (spec == null || !spec.isObject() || spec.size() == 0) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "A line operation needs the line's fields.");
    }
  }

  private static boolean isNull(JsonNode value) {
    return value == null || value.isNull();
  }

  private static BigDecimal requireDecimal(String field, JsonNode value) {
    if (isNull(value)) {
      throw new InvoiceBlocked(AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' cannot be cleared.");
    }
    try {
      return new BigDecimal(value.isNumber() ? value.asText() : value.asText("").trim());
    } catch (NumberFormatException e) {
      throw new InvoiceBlocked(AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' must be a number.");
    }
  }

  private static int requireInt(String field, JsonNode value) {
    if (isNull(value)) {
      throw new InvoiceBlocked(AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' cannot be cleared.");
    }
    if (value.isNumber()) {
      return value.asInt();
    }
    try {
      return Integer.parseInt(value.asText("").trim());
    } catch (NumberFormatException e) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' must be a whole number.");
    }
  }

  private static long requireLong(String field, JsonNode value) {
    if (isNull(value)) {
      throw new InvoiceBlocked(AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' cannot be null.");
    }
    long id;
    if (value.isNumber()) {
      id = value.asLong();
    } else {
      try {
        id = Long.parseLong(value.asText("").trim());
      } catch (NumberFormatException e) {
        throw new InvoiceBlocked(
            AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' must be a numeric id.");
      }
    }
    if (id <= 0) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "'" + field + "' must be a positive id.");
    }
    return id;
  }

  private static String optString(JsonNode value, int maxLength) {
    if (isNull(value)) {
      return null;
    }
    String text = value.asText("").trim();
    if (text.isEmpty()) {
      return null;
    }
    if (text.length() > maxLength) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "longer than the " + maxLength + " characters this field holds.");
    }
    return text;
  }

  private static String describe(InvoiceLine line) {
    String name =
        line.getProductName() != null && !line.getProductName().trim().isEmpty()
            ? line.getProductName()
            : line.getDescription();
    return (name == null ? "(unnamed)" : name) + " × " + text(line.getQty());
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static String message(Exception e) {
    return e.getMessage() == null || e.getMessage().trim().isEmpty()
        ? e.getClass().getSimpleName()
        : e.getMessage().trim();
  }
}
