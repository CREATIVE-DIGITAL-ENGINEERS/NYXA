package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.FiscalPosition;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.TaxNumber;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.FiscalPositionRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.JournalRepository;
import com.axelor.apps.account.db.repo.PaymentConditionRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.db.repo.TaxNumberRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PriceList;
import com.axelor.apps.base.db.PrintingSettings;
import com.axelor.apps.base.db.TradingName;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.apps.base.db.repo.CurrencyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.PriceListRepository;
import com.axelor.apps.base.db.repo.PrintingSettingsRepository;
import com.axelor.apps.base.db.repo.TradingNameRepository;
import com.axelor.apps.base.service.PartnerPriceListService;
import com.axelor.db.JpaSecurity;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.message.db.Template;
import com.axelor.message.db.repo.TemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies explicit, whitelisted header changes to a DRAFT invoice.
 *
 * <h2>Not entity patching</h2>
 *
 * Only the fields named below may change, each has its own typed validation, and nothing is written
 * through reflection or a dynamic property name. A field the caller sends that is not on the list
 * is <b>rejected and reported</b>, never ignored — a silently dropped field is a correction the
 * user believes was made.
 *
 * <h2>Omitted, null, and present: three different instructions</h2>
 *
 * A field the caller did not mention is left exactly as it was. A field sent as JSON {@code null}
 * is a request to clear it, honoured only where the form allows the field to be empty and refused
 * with a reason where it does not. Anything else is a new value. Collapsing the first two — which
 * is what a {@code Map<String,String>} of raw values does, since an absent key and an empty value
 * look alike — makes clearing a field impossible and turns every partial edit into a risk.
 *
 * <h2>References resolve by id or by stable code, never by printed name</h2>
 *
 * Each reference takes two alternative arguments: {@code …Id} for the scoped database id and {@code
 * …Code} for the entity's own code. The name is deliberately not accepted for {@code paymentMode}
 * and {@code paymentCondition} because theirs are {@code translatable} — matching on them would
 * resolve differently depending on the user's locale, and silently pick the wrong record in one
 * language while working in another. An id or code that does not resolve is refused by name;
 * nothing here falls back to a nearest match.
 *
 * <h2>What is not here, and why</h2>
 *
 * <ul>
 *   <li><b>Totals</b> — derived from the lines by Axelor. No setter is offered, so a caller cannot
 *       make the header disagree with what it is made of.
 *   <li><b>statusSelect and the lifecycle timestamps</b> — moved only by {@code
 *       InvoiceLifecycleService}, through Axelor's own transitions.
 *   <li><b>operationTypeSelect</b> — fixed when the invoice was created. There is no writable path
 *       to it anywhere in this package, by design.
 *   <li><b>partner</b> — a change with consequences for the address, the bank details and the
 *       payment defaults, so it has its own service ({@code InvoicePartnerReassignService}) and its
 *       own arguments rather than hiding among the header fields.
 * </ul>
 */
@Singleton
public class InvoiceHeaderEditService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(InvoiceHeaderEditService.class);

  public static final String F_INVOICE_DATE = "invoiceDate";
  public static final String F_DUE_DATE = "dueDate";
  public static final String F_ORIGIN_DATE = "originDate";
  public static final String F_SUPPLIER_INVOICE_NB = "supplierInvoiceNb";
  public static final String F_INTERNAL_REFERENCE = "internalReference";
  public static final String F_EXTERNAL_REFERENCE = "externalReference";
  public static final String F_PAYMENT_MODE_ID = "paymentModeId";
  public static final String F_PAYMENT_MODE_CODE = "paymentModeCode";
  public static final String F_PAYMENT_CONDITION_ID = "paymentConditionId";
  public static final String F_PAYMENT_CONDITION_CODE = "paymentConditionCode";
  public static final String F_JOURNAL_ID = "journalId";
  public static final String F_JOURNAL_CODE = "journalCode";
  public static final String F_PARTNER_ACCOUNT_ID = "partnerAccountId";
  public static final String F_PARTNER_ACCOUNT_CODE = "partnerAccountCode";
  public static final String F_FISCAL_POSITION_ID = "fiscalPositionId";
  public static final String F_FISCAL_POSITION_CODE = "fiscalPositionCode";
  public static final String F_CURRENCY_ID = "currencyId";
  public static final String F_CURRENCY_CODE = "currencyCode";
  public static final String F_BANK_DETAILS_ID = "bankDetailsId";
  public static final String F_BANK_IBAN = "bankIban";
  // ── the convenience tier: printing, correspondence, references ────────────
  public static final String F_CONTACT_PARTNER_ID = "contactPartnerId";
  public static final String F_THIRD_PARTY_PAYER_PARTNER_ID = "thirdPartyPayerPartnerId";
  public static final String F_PRICE_LIST_ID = "priceListId";
  public static final String F_COMPANY_BANK_DETAILS_ID = "companyBankDetailsId";
  public static final String F_TRADING_NAME_ID = "tradingNameId";
  public static final String F_ADDRESS_ID = "addressId";
  public static final String F_DELIVERY_ADDRESS_ID = "deliveryAddressId";
  public static final String F_NOTE = "note";
  public static final String F_SPECIFIC_NOTES = "specificNotes";
  public static final String F_INVOICES_COPY_SELECT = "invoicesCopySelect";
  public static final String F_HIDE_DISCOUNT = "hideDiscount";
  public static final String F_GROUP_PRODUCTS_ON_PRINTINGS = "groupProductsOnPrintings";
  public static final String F_PRINTING_SETTINGS_ID = "printingSettingsId";
  public static final String F_PARTNER_TAX_NBR = "partnerTaxNbr";
  public static final String F_COMPANY_TAX_NUMBER_ID = "companyTaxNumberId";
  public static final String F_INVOICE_AUTOMATIC_MAIL = "invoiceAutomaticMail";
  public static final String F_INVOICE_MESSAGE_TEMPLATE_ID = "invoiceMessageTemplateId";
  public static final String F_INVOICE_AUTOMATIC_MAIL_ON_VALIDATE =
      "invoiceAutomaticMailOnValidate";
  public static final String F_INVOICE_MESSAGE_TEMPLATE_ON_VALIDATE_ID =
      "invoiceMessageTemplateOnValidateId";
  public static final String F_DISPLAY_STOCK_MOVE_ON_INVOICE_PRINTING =
      "displayStockMoveOnInvoicePrinting";

  /** The core header fields, in the order they are documented. */
  public static final Set<String> CORE_FIELDS =
      new LinkedHashSet<>(
          java.util.Arrays.asList(
              F_INVOICE_DATE,
              F_DUE_DATE,
              F_ORIGIN_DATE,
              F_SUPPLIER_INVOICE_NB,
              F_INTERNAL_REFERENCE,
              F_EXTERNAL_REFERENCE,
              F_PAYMENT_MODE_ID,
              F_PAYMENT_MODE_CODE,
              F_PAYMENT_CONDITION_ID,
              F_PAYMENT_CONDITION_CODE,
              F_JOURNAL_ID,
              F_JOURNAL_CODE,
              F_PARTNER_ACCOUNT_ID,
              F_PARTNER_ACCOUNT_CODE,
              F_FISCAL_POSITION_ID,
              F_FISCAL_POSITION_CODE,
              F_CURRENCY_ID,
              F_CURRENCY_CODE,
              F_BANK_DETAILS_ID,
              F_BANK_IBAN,
              F_CONTACT_PARTNER_ID,
              F_THIRD_PARTY_PAYER_PARTNER_ID,
              F_PRICE_LIST_ID,
              F_COMPANY_BANK_DETAILS_ID,
              F_TRADING_NAME_ID,
              F_ADDRESS_ID,
              F_DELIVERY_ADDRESS_ID,
              F_NOTE,
              F_SPECIFIC_NOTES,
              F_INVOICES_COPY_SELECT,
              F_HIDE_DISCOUNT,
              F_GROUP_PRODUCTS_ON_PRINTINGS,
              F_PRINTING_SETTINGS_ID,
              F_PARTNER_TAX_NBR,
              F_COMPANY_TAX_NUMBER_ID,
              F_INVOICE_AUTOMATIC_MAIL,
              F_INVOICE_MESSAGE_TEMPLATE_ID,
              F_INVOICE_AUTOMATIC_MAIL_ON_VALIDATE,
              F_INVOICE_MESSAGE_TEMPLATE_ON_VALIDATE_ID,
              F_DISPLAY_STOCK_MOVE_ON_INVOICE_PRINTING));

  /** Fields only a supplier invoice has. The form hides these for the customer side. */
  public static final Set<String> SUPPLIER_ONLY_FIELDS =
      new LinkedHashSet<>(java.util.Arrays.asList(F_ORIGIN_DATE, F_SUPPLIER_INVOICE_NB));

  /**
   * Fields only a customer invoice has, read off the effective form.
   *
   * <ul>
   *   <li>{@code partnerTaxNbr} — {@code hideIf="operationTypeSelect != 3 && != 4"};
   *   <li>{@code deliveryAddress} and {@code displayStockMoveOnInvoicePrinting} — the delivery and
   *       printing blocks that carry them are shown only for the customer types;
   *   <li>the on-validate mail pair — the automatic-mail panel is hidden outright for supplier
   *       invoices ({@code hideIf="... operationTypeSelect <= 2"}), so those two fields exist on
   *       the entity but no supplier-invoice form ever offers them.
   * </ul>
   */
  public static final Set<String> CUSTOMER_ONLY_FIELDS =
      new LinkedHashSet<>(
          java.util.Arrays.asList(
              F_PARTNER_TAX_NBR,
              F_DELIVERY_ADDRESS_ID,
              F_DISPLAY_STOCK_MOVE_ON_INVOICE_PRINTING,
              F_INVOICE_AUTOMATIC_MAIL_ON_VALIDATE,
              F_INVOICE_MESSAGE_TEMPLATE_ON_VALIDATE_ID));

  /** One rejected field and why, so the caller can explain itself rather than fail opaquely. */
  public static final class Rejection {
    private final String field;
    private final String reason;

    Rejection(String field, String reason) {
      this.field = field;
      this.reason = reason;
    }

    public String getField() {
      return field;
    }

    public String getReason() {
      return reason;
    }
  }

  /** Everything that happened. Never partially reported. */
  public static final class Result {
    private final List<AiInvoiceFieldChange> changes = new ArrayList<>();
    private final List<Rejection> rejected = new ArrayList<>();
    private boolean linesNeedRecompute;

    public List<AiInvoiceFieldChange> getChanges() {
      return changes;
    }

    public List<Rejection> getRejected() {
      return rejected;
    }

    /** True when a change here means the lines have to be recomputed by the caller. */
    public boolean isLinesNeedRecompute() {
      return linesNeedRecompute;
    }

    public boolean isChanged() {
      return !changes.isEmpty();
    }

    /**
     * Refuses the whole request when any field it named was rejected.
     *
     * <p>{@link #apply} works field by field, so by the time a rejection is known the fields before
     * it may already be on the invoice in memory. Every caller runs inside a transaction that rolls
     * back on exception and calls this before saving, so the throw takes all of them back with it:
     * a request is applied whole or not at all, never reported as done with some of its fields
     * silently missing.
     *
     * @param consequence what the refusal means for the record, e.g. "Nothing was changed."
     */
    public void refuseIfAnyRejected(String consequence) {
      if (rejected.isEmpty()) {
        return;
      }
      List<String> parts = new ArrayList<>();
      for (Rejection rejection : rejected) {
        parts.add(rejection.getField() + ": " + rejection.getReason());
      }
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "Refused — "
              + (rejected.size() == 1
                  ? "a requested header field could not be applied ("
                  : rejected.size() + " requested header fields could not be applied (")
              + String.join("; ", parts)
              + "). "
              + consequence
              + " Correct or drop those fields and send the whole request again.");
    }
  }

  /**
   * Applies every header field the caller named.
   *
   * <p>Does not save and does not recompute: the caller owns the transaction and decides when the
   * totals are rebuilt, because a header change and a line change in one call must recompute once.
   * Rejections are collected rather than thrown so all of them can be reported at once; the caller
   * must then call {@link Result#refuseIfAnyRejected} before it saves anything.
   *
   * @param header the raw JSON object of header fields, or null when the caller sent none
   */
  public Result apply(Invoice invoice, InvoiceSide side, JsonNode header) {
    Result result = new Result();
    if (header == null || header.isNull() || !header.isObject() || header.size() == 0) {
      return result;
    }
    AiInvoiceFieldRegistry contributed = fieldRegistry();
    Set<String> contributedNames = contributed.headerFieldNames(side == InvoiceSide.SUPPLIER);

    Iterator<String> names = header.fieldNames();
    while (names.hasNext()) {
      String field = names.next();
      JsonNode value = header.get(field);
      try {
        String alternative = alternativeAlsoGiven(header, field);
        if (alternative != null) {
          // Both halves of an id-or-code pair. The loop below would apply each in turn and let
          // whichever came last in the JSON win, which is a reference chosen by key order rather
          // than by the caller. Refused for both names, so the answer says what the problem is.
          result.rejected.add(
              new Rejection(
                  field,
                  "give either "
                      + field
                      + " or "
                      + alternative
                      + ", not both; they name the same reference two ways."));
          continue;
        }
        if (CORE_FIELDS.contains(field)) {
          if (SUPPLIER_ONLY_FIELDS.contains(field) && side != InvoiceSide.SUPPLIER) {
            result.rejected.add(
                new Rejection(
                    field,
                    "only a supplier invoice has this field; the customer invoice form does not"
                        + " show it."));
            continue;
          }
          if (CUSTOMER_ONLY_FIELDS.contains(field) && side != InvoiceSide.CUSTOMER) {
            result.rejected.add(
                new Rejection(
                    field,
                    "only a customer invoice has this field; the supplier invoice form does not"
                        + " show it."));
            continue;
          }
          applyCore(invoice, side, field, value, result);
        } else if (contributedNames.contains(field)) {
          AiInvoiceFieldChange change = contributed.applyHeaderField(invoice, field, value);
          if (change == null) {
            result.rejected.add(
                new Rejection(field, "no module accepted this field, though one declared it."));
          } else if (!AiInvoiceFieldChange.same(change.getOldValue(), change.getNewValue())) {
            result.changes.add(change);
          }
        } else if (contributed.headerFields().containsKey(field)) {
          result.rejected.add(
              new Rejection(field, "this field does not apply to a " + side.label() + "."));
        } else {
          result.rejected.add(
              new Rejection(
                  field,
                  "not a writable header field; allowed: "
                      + String.join(", ", writableFieldNames(side))));
        }
      } catch (IllegalArgumentException e) {
        result.rejected.add(new Rejection(field, e.getMessage()));
      }
    }
    return result;
  }

  /**
   * The other half of this field's id-or-code pair, when the caller sent that too.
   *
   * <p>Mirrors what the schema declares ({@code allOf} of {@code not: {required: [id, code]}}), so
   * a call that ignores the schema is refused rather than resolved by JSON key order. Null when the
   * field is not one half of a pair, or when only this half was given — which is the ordinary case.
   *
   * <p>Answers for <em>either</em> half, so both names are rejected and neither is applied. Testing
   * only the {@code …Id} one would leave whichever came first in the JSON already written.
   */
  private static String alternativeAlsoGiven(JsonNode header, String field) {
    String alternative = null;
    if (field.endsWith("Id")) {
      alternative =
          F_BANK_DETAILS_ID.equals(field)
              ? F_BANK_IBAN
              : field.substring(0, field.length() - 2) + "Code";
    } else if (field.endsWith("Code")) {
      alternative = field.substring(0, field.length() - 4) + "Id";
    } else if (F_BANK_IBAN.equals(field)) {
      alternative = F_BANK_DETAILS_ID;
    }
    return alternative != null && header.has(alternative) ? alternative : null;
  }

  /**
   * Every header field this side may edit, described well enough to build a JSON schema from.
   *
   * <h2>Why the schema is generated from this rather than written beside it</h2>
   *
   * The allowlist and the schema have to agree: a field in the schema but not the allowlist is a
   * call the model will make and be refused for, and a field in the allowlist but not the schema is
   * a capability nobody knows about. Two hand-maintained lists agree until the day somebody edits
   * one, so there is one list and the schema is derived from it — including whatever a localisation
   * contributes, which no hand-written schema could have known about.
   *
   * <h2>Static, and injector-free for the core fields</h2>
   *
   * Tool schemas are built by {@code parametersSchema()}, which a unit test calls on a bare
   * instance with no Guice around it. So this needs no instance state and no injector to answer for
   * the fields it owns; only the contributed ones need the registry, and when that cannot be
   * reached the core list is returned rather than an exception. A schema is a description — failing
   * to build one because a module registry is not up would make every tool unusable to protect a
   * few optional fields.
   */
  public static List<AiInvoiceFieldSpec> fieldSpecs(InvoiceSide side) {
    List<AiInvoiceFieldSpec> specs = new ArrayList<>();
    boolean supplier = side == InvoiceSide.SUPPLIER;
    specs.add(date(F_INVOICE_DATE, "The invoice's own date. Not in the future.", false));
    specs.add(
        date(
            F_DUE_DATE,
            "Only settable when the payment condition is a free one; otherwise it follows from the"
                + " condition and is refused here.",
            false));
    if (supplier) {
      specs.add(
          date(
              F_ORIGIN_DATE,
              "The date on the supplier's own document. Supplier invoices only.",
              false));
      specs.add(
          string(
              F_SUPPLIER_INVOICE_NB,
              "The number the supplier put on their document. Supplier invoices only. Send null to"
                  + " clear.",
              true));
    }
    specs.add(
        string(F_INTERNAL_REFERENCE, "Free-text internal reference. Send null to clear.", true));
    specs.add(
        string(F_EXTERNAL_REFERENCE, "Free-text external reference. Send null to clear.", true));
    specs.add(id(F_PAYMENT_MODE_ID, "Payment mode by id. Cannot be cleared.", false));
    specs.add(
        string(
            F_PAYMENT_MODE_CODE,
            "Payment mode by its code — not its printed label, which is translated.",
            false));
    specs.add(id(F_PAYMENT_CONDITION_ID, "Payment condition by id. Cannot be cleared.", false));
    specs.add(
        string(
            F_PAYMENT_CONDITION_CODE,
            "Payment condition by its code — not its printed name, which is translated.",
            false));
    specs.add(
        id(
            F_JOURNAL_ID,
            "Journal by id, in this company. Send null to clear and let validation assign it."));
    specs.add(string(F_JOURNAL_CODE, "Journal by code, in this company.", true));
    specs.add(id(F_PARTNER_ACCOUNT_ID, "Partner account by id, in this company. Null clears it."));
    specs.add(string(F_PARTNER_ACCOUNT_CODE, "Partner account by code, in this company.", true));
    specs.add(id(F_FISCAL_POSITION_ID, "Fiscal position by id. Null clears it."));
    specs.add(string(F_FISCAL_POSITION_CODE, "Fiscal position by code.", true));
    specs.add(
        id(
            F_CURRENCY_ID,
            "Currency by id. Only while the invoice has no lines. Cannot be cleared.",
            false));
    specs.add(
        string(F_CURRENCY_CODE, "Currency by ISO code, e.g. BGN. Only while no lines.", false));
    specs.add(
        id(
            F_BANK_DETAILS_ID,
            "Bank account by id; must belong to this invoice's partner. Null clears it."));
    specs.add(
        string(F_BANK_IBAN, "Bank account by IBAN; must belong to this invoice's partner.", true));
    specs.add(id(F_CONTACT_PARTNER_ID, "Contact person at the partner, by id. Null clears it."));
    specs.add(
        id(
            F_THIRD_PARTY_PAYER_PARTNER_ID,
            "Third-party payer, by id. Not on a credit note. Null clears it."));
    specs.add(
        id(
            F_PRICE_LIST_ID,
            "Price list by id. Only while the invoice has no lines. Null clears it."));
    specs.add(
        id(
            F_COMPANY_BANK_DETAILS_ID,
            "The COMPANY's own bank account by id — where this invoice is paid to or from. Null"
                + " clears it."));
    specs.add(id(F_TRADING_NAME_ID, "Trading name by id, of this company. Null clears it."));
    specs.add(id(F_ADDRESS_ID, "Invoicing address by id. Null clears it."));
    specs.add(string(F_NOTE, "Free-text note on the invoice.", true));
    specs.add(string(F_SPECIFIC_NOTES, "Notes printed on the document.", true));
    specs.add(integer(F_INVOICES_COPY_SELECT, "How many copies to print (1-10)."));
    specs.add(bool(F_HIDE_DISCOUNT, "Hide the discount column on the printed invoice."));
    specs.add(bool(F_GROUP_PRODUCTS_ON_PRINTINGS, "Group products on the printed invoice."));
    specs.add(id(F_PRINTING_SETTINGS_ID, "Printing settings by id. Null clears it."));
    specs.add(id(F_COMPANY_TAX_NUMBER_ID, "The company's tax number by id. Null clears it."));
    specs.add(bool(F_INVOICE_AUTOMATIC_MAIL, "Send the invoice by e-mail automatically."));
    specs.add(
        id(
            F_INVOICE_MESSAGE_TEMPLATE_ID,
            "Message template for that e-mail, by id. Null clears it."));
    if (!supplier) {
      specs.add(
          string(
              F_PARTNER_TAX_NBR,
              "The customer's tax number as printed on this invoice. Customer invoices only.",
              true));
      specs.add(
          id(
              F_DELIVERY_ADDRESS_ID,
              "Delivery address by id. Customer invoices only. Null clears it."));
      specs.add(
          bool(
              F_DISPLAY_STOCK_MOVE_ON_INVOICE_PRINTING,
              "Show the stock move on the printed invoice. Customer invoices only."));
      specs.add(
          bool(
              F_INVOICE_AUTOMATIC_MAIL_ON_VALIDATE,
              "Send the e-mail on validation rather than on posting. Customer invoices only."));
      specs.add(
          id(
              F_INVOICE_MESSAGE_TEMPLATE_ON_VALIDATE_ID,
              "Template for the on-validation e-mail, by id. Customer invoices only. Null clears"
                  + " it."));
    }
    try {
      for (AiInvoiceFieldSpec contributed :
          Beans.get(AiInvoiceFieldRegistry.class).headerFields().values()) {
        if (supplier ? contributed.appliesToSupplier() : contributed.appliesToCustomer()) {
          specs.add(contributed);
        }
      }
    } catch (RuntimeException e) {
      // No injector (a unit test building a schema) or no registry. The core fields are still a
      // correct, usable description; a contributed field simply is not offered.
      LOG.debug("AI Accountant: contributed header fields unavailable: {}", e.getMessage());
    }
    return specs;
  }

  private static AiInvoiceFieldSpec date(String name, String description, boolean clearable) {
    return new AiInvoiceFieldSpec(
        name,
        AiInvoiceFieldSpec.Type.STRING,
        description + " ISO date, yyyy-MM-dd.",
        null,
        clearable,
        AiInvoiceFieldSpec.Applies.BOTH);
  }

  private static AiInvoiceFieldSpec string(String name, String description, boolean clearable) {
    return new AiInvoiceFieldSpec(
        name,
        AiInvoiceFieldSpec.Type.STRING,
        description,
        null,
        clearable,
        AiInvoiceFieldSpec.Applies.BOTH);
  }

  /** A reference by id, which null clears — the ordinary case for an optional reference. */
  private static AiInvoiceFieldSpec id(String name, String description) {
    return id(name, description, true);
  }

  /**
   * A reference by id, saying whether null clears it.
   *
   * <p>Three of them cannot be cleared — the payment mode, the payment condition and the currency —
   * because {@code applyCore} refuses null on each: validation and posting need all three, and an
   * invoice with no currency has amounts that mean nothing. Declaring them clearable made the
   * schema promise a clear the apply code was always going to refuse.
   */
  private static AiInvoiceFieldSpec id(String name, String description, boolean clearable) {
    return new AiInvoiceFieldSpec(
        name,
        AiInvoiceFieldSpec.Type.INTEGER,
        description,
        null,
        clearable,
        AiInvoiceFieldSpec.Applies.BOTH);
  }

  private static AiInvoiceFieldSpec integer(String name, String description) {
    return new AiInvoiceFieldSpec(
        name,
        AiInvoiceFieldSpec.Type.INTEGER,
        description,
        null,
        false,
        AiInvoiceFieldSpec.Applies.BOTH);
  }

  private static AiInvoiceFieldSpec bool(String name, String description) {
    return new AiInvoiceFieldSpec(
        name,
        AiInvoiceFieldSpec.Type.BOOLEAN,
        description,
        null,
        false,
        AiInvoiceFieldSpec.Applies.BOTH);
  }

  /** Every header field name this side may edit, core and contributed. */
  public Set<String> writableFieldNames(InvoiceSide side) {
    Set<String> names = new LinkedHashSet<>();
    for (String field : CORE_FIELDS) {
      if (SUPPLIER_ONLY_FIELDS.contains(field) && side != InvoiceSide.SUPPLIER) {
        continue;
      }
      if (CUSTOMER_ONLY_FIELDS.contains(field) && side != InvoiceSide.CUSTOMER) {
        continue;
      }
      names.add(field);
    }
    names.addAll(fieldRegistry().headerFieldNames(side == InvoiceSide.SUPPLIER));
    return names;
  }

  /** The contributed-field registry. A seam, as in {@code InvoiceLineEditService}. */
  protected AiInvoiceFieldRegistry fieldRegistry() {
    return Beans.get(AiInvoiceFieldRegistry.class);
  }

  private void applyCore(
      Invoice invoice, InvoiceSide side, String field, JsonNode value, Result result) {
    switch (field) {
      case F_INVOICE_DATE:
        {
          LocalDate date = requireDate(field, value, true);
          if (invoice.getVentilatedDateTime() != null || invoice.getVentilatedByUser() != null) {
            throw new IllegalArgumentException(
                "the invoice has been posted, so its date is fixed.");
          }
          record(result, field, text(invoice.getInvoiceDate()), text(date));
          invoice.setInvoiceDate(date);
          break;
        }
      case F_ORIGIN_DATE:
        {
          // Stock refuses to post a purchase whose origin date is ahead of today; a sale has no
          // such rule, so neither has this.
          LocalDate date = requireDate(field, value, side == InvoiceSide.SUPPLIER);
          record(result, field, text(invoice.getOriginDate()), text(date));
          invoice.setOriginDate(date);
          break;
        }
      case F_DUE_DATE:
        {
          // A due date is meant to be ahead of today: that is what a payment term is.
          LocalDate date = requireDate(field, value, false);
          PaymentCondition condition = invoice.getPaymentCondition();
          // Exactly the form's own rule (readonlyIf="!paymentCondition.isFree"): with a scheduled
          // condition the due date is derived from it and the invoice date, so accepting one here
          // would be writing a value the next recompute silently replaces.
          if (condition == null || !Boolean.TRUE.equals(condition.getIsFree())) {
            throw new IllegalArgumentException(
                "the due date follows from the payment condition and cannot be set directly unless"
                    + " that condition is a free one. Change the payment condition instead.");
          }
          record(result, field, text(invoice.getDueDate()), text(date));
          invoice.setDueDate(date);
          break;
        }
      case F_SUPPLIER_INVOICE_NB:
        {
          String number = optString(field, value, 255);
          record(result, field, invoice.getSupplierInvoiceNb(), number);
          invoice.setSupplierInvoiceNb(number);
          break;
        }
      case F_INTERNAL_REFERENCE:
        {
          String reference = optString(field, value, 255);
          record(result, field, invoice.getInternalReference(), reference);
          invoice.setInternalReference(reference);
          break;
        }
      case F_EXTERNAL_REFERENCE:
        {
          String reference = optString(field, value, 255);
          record(result, field, invoice.getExternalReference(), reference);
          invoice.setExternalReference(reference);
          break;
        }
      case F_PAYMENT_MODE_ID:
      case F_PAYMENT_MODE_CODE:
        {
          if (isNull(value)) {
            throw new IllegalArgumentException(
                "the payment mode cannot be cleared; validation and posting both need one.");
          }
          PaymentMode mode = resolvePaymentMode(field, value);
          record(
              result,
              "paymentMode",
              invoice.getPaymentMode() == null ? null : invoice.getPaymentMode().getCode(),
              mode.getCode());
          invoice.setPaymentMode(mode);
          break;
        }
      case F_PAYMENT_CONDITION_ID:
      case F_PAYMENT_CONDITION_CODE:
        {
          if (isNull(value)) {
            throw new IllegalArgumentException(
                "the payment condition cannot be cleared; validation needs one.");
          }
          PaymentCondition condition = resolvePaymentCondition(field, value);
          record(
              result,
              "paymentCondition",
              invoice.getPaymentCondition() == null
                  ? null
                  : invoice.getPaymentCondition().getCode(),
              condition.getCode());
          invoice.setPaymentCondition(condition);
          break;
        }
      case F_JOURNAL_ID:
      case F_JOURNAL_CODE:
        {
          Journal journal = isNull(value) ? null : resolveJournal(field, value);
          record(
              result,
              "journal",
              invoice.getJournal() == null ? null : invoice.getJournal().getCode(),
              journal == null ? null : journal.getCode());
          invoice.setJournal(journal);
          break;
        }
      case F_PARTNER_ACCOUNT_ID:
      case F_PARTNER_ACCOUNT_CODE:
        {
          Account account = isNull(value) ? null : resolveAccount(field, value);
          record(
              result,
              "partnerAccount",
              invoice.getPartnerAccount() == null ? null : invoice.getPartnerAccount().getCode(),
              account == null ? null : account.getCode());
          invoice.setPartnerAccount(account);
          break;
        }
      case F_FISCAL_POSITION_ID:
      case F_FISCAL_POSITION_CODE:
        {
          FiscalPosition position =
              isNull(value) ? null : resolveFiscalPosition(invoice, field, value);
          record(
              result,
              "fiscalPosition",
              invoice.getFiscalPosition() == null ? null : invoice.getFiscalPosition().getCode(),
              position == null ? null : position.getCode());
          invoice.setFiscalPosition(position);
          // The lines' taxes and accounts follow from the fiscal position, so the caller has to
          // rerun Axelor's own line update. Flagged rather than done here: one recompute per call.
          result.linesNeedRecompute = true;
          break;
        }
      case F_CURRENCY_ID:
      case F_CURRENCY_CODE:
        {
          if (isNull(value)) {
            throw new IllegalArgumentException("the currency cannot be cleared.");
          }
          if (invoice.getInvoiceLineList() != null && !invoice.getInvoiceLineList().isEmpty()) {
            // The form locks the currency once a line exists, because every line's amounts are
            // expressed in it. Changing it here would leave the figures meaning something else.
            throw new IllegalArgumentException(
                "the currency can only be changed while the invoice has no lines.");
          }
          Currency currency = resolveCurrency(field, value);
          record(
              result,
              "currency",
              invoice.getCurrency() == null ? null : invoice.getCurrency().getCode(),
              currency.getCode());
          invoice.setCurrency(currency);
          break;
        }
      case F_BANK_DETAILS_ID:
      case F_BANK_IBAN:
        {
          BankDetails bankDetails =
              isNull(value) ? null : resolveBankDetails(invoice, field, value);
          record(
              result,
              "bankDetails",
              invoice.getBankDetails() == null ? null : invoice.getBankDetails().getIban(),
              bankDetails == null ? null : bankDetails.getIban());
          invoice.setBankDetails(bankDetails);
          break;
        }
      case F_CONTACT_PARTNER_ID:
        {
          Partner contact = isNull(value) ? null : contactPartner(invoice, field, value);
          record(
              result,
              "contactPartner",
              invoice.getContactPartner() == null
                  ? null
                  : invoice.getContactPartner().getFullName(),
              contact == null ? null : contact.getFullName());
          invoice.setContactPartner(contact);
          break;
        }
      case F_THIRD_PARTY_PAYER_PARTNER_ID:
        {
          // The form shows this for a purchase and a sale, not for either credit note.
          Integer type = invoice.getOperationTypeSelect();
          if (!isNull(value)
              && type != null
              && (type == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND
                  || type == InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND)) {
            throw new IllegalArgumentException(
                "a credit note carries no third-party payer; the form does not offer it.");
          }
          Partner payer = isNull(value) ? null : thirdPartyPayerPartner(field, value);
          record(
              result,
              "thirdPartyPayerPartner",
              invoice.getThirdPartyPayerPartner() == null
                  ? null
                  : invoice.getThirdPartyPayerPartner().getFullName(),
              payer == null ? null : payer.getFullName());
          invoice.setThirdPartyPayerPartner(payer);
          break;
        }
      case F_PRICE_LIST_ID:
        {
          if (!isNull(value)
              && invoice.getInvoiceLineList() != null
              && !invoice.getInvoiceLineList().isEmpty()) {
            // Same rule as the currency, and for the same reason: the lines' prices came from it.
            throw new IllegalArgumentException(
                "the price list can only be changed while the invoice has no lines.");
          }
          PriceList priceList = isNull(value) ? null : resolvePriceList(invoice, field, value);
          record(
              result,
              "priceList",
              invoice.getPriceList() == null ? null : invoice.getPriceList().getTitle(),
              priceList == null ? null : priceList.getTitle());
          invoice.setPriceList(priceList);
          break;
        }
      case F_COMPANY_BANK_DETAILS_ID:
        {
          BankDetails companyBank =
              isNull(value) ? null : companyBankDetails(field, value, invoice);
          record(
              result,
              "companyBankDetails",
              invoice.getCompanyBankDetails() == null
                  ? null
                  : invoice.getCompanyBankDetails().getIban(),
              companyBank == null ? null : companyBank.getIban());
          invoice.setCompanyBankDetails(companyBank);
          break;
        }
      case F_TRADING_NAME_ID:
        {
          TradingName tradingName = isNull(value) ? null : tradingName(field, value, invoice);
          record(
              result,
              "tradingName",
              invoice.getTradingName() == null ? null : invoice.getTradingName().getName(),
              tradingName == null ? null : tradingName.getName());
          invoice.setTradingName(tradingName);
          break;
        }
      case F_ADDRESS_ID:
        {
          Address address =
              isNull(value) ? null : resolvePartnerAddress(invoice, field, value, true);
          record(
              result,
              "address",
              invoice.getAddress() == null ? null : invoice.getAddress().getFullName(),
              address == null ? null : address.getFullName());
          invoice.setAddress(address);
          // The printed form of the address is derived from it, the same way the form's own
          // onChange derives it — a stale string beside a changed address is a wrong document.
          invoice.setAddressStr(
              address == null
                  ? null
                  : Beans.get(com.axelor.apps.base.service.address.AddressService.class)
                      .computeAddressStr(address));
          break;
        }
      case F_DELIVERY_ADDRESS_ID:
        {
          Address delivery =
              isNull(value) ? null : resolvePartnerAddress(invoice, field, value, false);
          record(
              result,
              "deliveryAddress",
              invoice.getDeliveryAddress() == null
                  ? null
                  : invoice.getDeliveryAddress().getFullName(),
              delivery == null ? null : delivery.getFullName());
          invoice.setDeliveryAddress(delivery);
          invoice.setDeliveryAddressStr(
              delivery == null
                  ? null
                  : Beans.get(com.axelor.apps.base.service.address.AddressService.class)
                      .computeAddressStr(delivery));
          break;
        }
      case F_NOTE:
        {
          String note = optString(field, value, 4000);
          record(result, field, invoice.getNote(), note);
          invoice.setNote(note);
          break;
        }
      case F_SPECIFIC_NOTES:
        {
          String notes = optString(field, value, 4000);
          record(result, field, invoice.getSpecificNotes(), notes);
          invoice.setSpecificNotes(notes);
          break;
        }
      case F_PARTNER_TAX_NBR:
        {
          String taxNbr = optString(field, value, 255);
          record(result, field, invoice.getPartnerTaxNbr(), taxNbr);
          invoice.setPartnerTaxNbr(taxNbr);
          break;
        }
      case F_INVOICES_COPY_SELECT:
        {
          int copies = requireInt(field, value);
          if (copies < 1 || copies > 10) {
            throw new IllegalArgumentException("must be between 1 and 10.");
          }
          record(result, field, text(invoice.getInvoicesCopySelect()), text(copies));
          invoice.setInvoicesCopySelect(copies);
          break;
        }
      case F_HIDE_DISCOUNT:
        {
          boolean hide = requireBool(field, value);
          record(result, field, text(invoice.getHideDiscount()), text(hide));
          invoice.setHideDiscount(hide);
          break;
        }
      case F_GROUP_PRODUCTS_ON_PRINTINGS:
        {
          boolean group = requireBool(field, value);
          record(result, field, text(invoice.getGroupProductsOnPrintings()), text(group));
          invoice.setGroupProductsOnPrintings(group);
          break;
        }
      case F_DISPLAY_STOCK_MOVE_ON_INVOICE_PRINTING:
        {
          boolean display = requireBool(field, value);
          record(
              result, field, text(invoice.getDisplayStockMoveOnInvoicePrinting()), text(display));
          invoice.setDisplayStockMoveOnInvoicePrinting(display);
          break;
        }
      case F_PRINTING_SETTINGS_ID:
        {
          PrintingSettings settings = isNull(value) ? null : resolvePrintingSettings(field, value);
          record(
              result,
              "printingSettings",
              invoice.getPrintingSettings() == null
                  ? null
                  : invoice.getPrintingSettings().getName(),
              settings == null ? null : settings.getName());
          invoice.setPrintingSettings(settings);
          break;
        }
      case F_COMPANY_TAX_NUMBER_ID:
        {
          TaxNumber taxNumber = isNull(value) ? null : companyTaxNumber(field, value, invoice);
          record(
              result,
              "companyTaxNumber",
              invoice.getCompanyTaxNumber() == null
                  ? null
                  : invoice.getCompanyTaxNumber().getTaxNbr(),
              taxNumber == null ? null : taxNumber.getTaxNbr());
          invoice.setCompanyTaxNumber(taxNumber);
          break;
        }
      case F_INVOICE_AUTOMATIC_MAIL:
        {
          boolean automatic = requireBool(field, value);
          record(result, field, text(invoice.getInvoiceAutomaticMail()), text(automatic));
          invoice.setInvoiceAutomaticMail(automatic);
          break;
        }
      case F_INVOICE_AUTOMATIC_MAIL_ON_VALIDATE:
        {
          boolean automatic = requireBool(field, value);
          record(result, field, text(invoice.getInvoiceAutomaticMailOnValidate()), text(automatic));
          invoice.setInvoiceAutomaticMailOnValidate(automatic);
          break;
        }
      case F_INVOICE_MESSAGE_TEMPLATE_ID:
        {
          Template template = isNull(value) ? null : template(field, value);
          record(
              result,
              "invoiceMessageTemplate",
              invoice.getInvoiceMessageTemplate() == null
                  ? null
                  : invoice.getInvoiceMessageTemplate().getName(),
              template == null ? null : template.getName());
          invoice.setInvoiceMessageTemplate(template);
          break;
        }
      case F_INVOICE_MESSAGE_TEMPLATE_ON_VALIDATE_ID:
        {
          Template template = isNull(value) ? null : template(field, value);
          record(
              result,
              "invoiceMessageTemplateOnValidate",
              invoice.getInvoiceMessageTemplateOnValidate() == null
                  ? null
                  : invoice.getInvoiceMessageTemplateOnValidate().getName(),
              template == null ? null : template.getName());
          invoice.setInvoiceMessageTemplateOnValidate(template);
          break;
        }
      default:
        throw new IllegalArgumentException("not a writable header field.");
    }
  }

  // ── convenience-tier resolution ───────────────────────────────────────────

  private Partner partner(String field, JsonNode value) {
    long id = requireId(field, value);
    Partner partner = Beans.get(PartnerRepository.class).find(id);
    if (partner == null
        || !AiCompanyScope.partnerInScope(partner.getCompanySet())
        || !readable(Partner.class, partner.getId())) {
      throw new IllegalArgumentException("no partner with id " + id + " in this company.");
    }
    return partner;
  }

  /**
   * The invoice partner's own contact, and nobody else's.
   *
   * <p>Exactly the form's rule ({@code action-set-partner-domain}): the list offered is {@code
   * partner.contactPartnerSet}, and when that set is empty the field offers nothing at all. Any
   * other contact on the invoice names a person who has no relationship with the counterparty.
   */
  private Partner contactPartner(Invoice invoice, String field, JsonNode value) {
    Partner counterparty = invoice.getPartner();
    if (counterparty == null || counterparty.getId() == null) {
      throw new IllegalArgumentException(
          "the invoice has no partner yet, so a contact cannot be checked against one.");
    }
    Partner contact = partner(field, value);
    Set<Partner> contacts = counterparty.getContactPartnerSet();
    boolean linked =
        contacts != null
            && contacts.stream().anyMatch(c -> c != null && contact.getId().equals(c.getId()))
            && Boolean.TRUE.equals(contact.getIsContact());
    if (!linked) {
      throw new IllegalArgumentException(
          "that partner is not one of this invoice's partner's contacts, so the form would not"
              + " offer it.");
    }
    return contact;
  }

  /**
   * The form offers only partners flagged as third-party payers ({@code self.isThirdPartyPayer}).
   */
  private Partner thirdPartyPayerPartner(String field, JsonNode value) {
    Partner payer = partner(field, value);
    if (!Boolean.TRUE.equals(payer.getIsThirdPartyPayer())) {
      throw new IllegalArgumentException(
          "that partner is not marked as a third-party payer, so the form would not offer it.");
    }
    return payer;
  }

  /**
   * An address the invoice's partner actually holds, for the role the field is.
   *
   * <p>The form's own domain ({@code action-invoice-attrs-domain-address} and its delivery twin)
   * offers only the partner's {@code partnerAddressList} entries flagged for that role. Address has
   * no company of its own, so this relation is the only thing standing between the tool and any
   * address row in the database — including another company's customers' addresses.
   */
  private Address resolvePartnerAddress(
      Invoice invoice, String field, JsonNode value, boolean invoicing) {
    Partner counterparty = invoice.getPartner();
    if (counterparty == null || counterparty.getId() == null) {
      throw new IllegalArgumentException(
          "the invoice has no partner yet, so an address cannot be checked against one.");
    }
    long id = requireId(field, value);
    Address address =
        Beans.get(AddressRepository.class)
            .all()
            .filter(
                "self.id = :addressId AND EXISTS (SELECT 1 FROM PartnerAddress pa"
                    + " WHERE pa.address = self AND pa.partner.id = :partnerId AND pa."
                    + (invoicing ? "isInvoicingAddr" : "isDeliveryAddr")
                    + " IS TRUE)")
            .bind("addressId", id)
            .bind("partnerId", counterparty.getId())
            .fetchOne();
    if (address == null || !readable(Address.class, address.getId())) {
      throw new IllegalArgumentException(
          "this invoice's partner has no "
              + (invoicing ? "invoicing" : "delivery")
              + " address with id "
              + id
              + ".");
    }
    return address;
  }

  /**
   * A price list the partner is actually entitled to, for this invoice's direction.
   *
   * <p>Delegates the entitlement question to the same service the form's {@code onSelect} calls, so
   * an exclusive list belonging to another partner, an inactive one, or one outside its application
   * dates is refused here for the same reason the form would not show it.
   */
  private PriceList resolvePriceList(Invoice invoice, String field, JsonNode value) {
    Partner counterparty = invoice.getPartner();
    if (counterparty == null || counterparty.getId() == null) {
      throw new IllegalArgumentException(
          "the invoice has no partner yet, so a price list cannot be checked against one.");
    }
    long id = requireId(field, value);
    int type =
        Beans.get(com.axelor.apps.account.service.invoice.InvoiceService.class)
            .getPurchaseTypeOrSaleType(invoice);
    String domain = Beans.get(PartnerPriceListService.class).getPriceListDomain(counterparty, type);
    PriceList priceList = null;
    // An empty id list is how the service says "none apply"; it is also not valid JPQL, so it is
    // answered here rather than handed to the query.
    if (domain != null && !domain.replace(" ", "").contains("IN()")) {
      priceList =
          Beans.get(PriceListRepository.class)
              .all()
              .filter("(" + domain + ") AND self.id = :priceListId")
              .bind("priceListId", id)
              .fetchOne();
    }
    if (priceList == null || !readable(PriceList.class, priceList.getId())) {
      throw new IllegalArgumentException(
          "no price list with id " + id + " applies to this invoice's partner.");
    }
    return priceList;
  }

  /**
   * Printing settings carry no company and the stock form offers no domain for them, so the only
   * control available is the caller's own read permission — which is applied rather than skipped.
   */
  private PrintingSettings resolvePrintingSettings(String field, JsonNode value) {
    long id = requireId(field, value);
    PrintingSettings settings = Beans.get(PrintingSettingsRepository.class).find(id);
    if (settings == null || !readable(PrintingSettings.class, settings.getId())) {
      throw new IllegalArgumentException("no printing settings with id " + id + ".");
    }
    return settings;
  }

  /** The COMPANY's own bank account, which is a different thing from the partner's. */
  private BankDetails companyBankDetails(String field, JsonNode value, Invoice invoice) {
    long id = requireId(field, value);
    BankDetails bankDetails = Beans.get(BankDetailsRepository.class).find(id);
    if (bankDetails == null || !readable(BankDetails.class, bankDetails.getId())) {
      throw new IllegalArgumentException("no bank account with id " + id + ".");
    }
    Company company = invoice.getCompany();
    if (company == null
        || bankDetails.getCompany() == null
        || bankDetails.getCompany().getId() == null
        || !bankDetails.getCompany().getId().equals(company.getId())) {
      throw new IllegalArgumentException(
          "that bank account does not belong to this invoice's company. The company's own account"
              + " is what this field holds; the partner's is bankDetailsId.");
    }
    return bankDetails;
  }

  private TradingName tradingName(String field, JsonNode value, Invoice invoice) {
    long id = requireId(field, value);
    TradingName tradingName = Beans.get(TradingNameRepository.class).find(id);
    if (tradingName == null || !readable(TradingName.class, tradingName.getId())) {
      throw new IllegalArgumentException("no trading name with id " + id + ".");
    }
    Company company = invoice.getCompany();
    if (company == null
        || company.getTradingNameList() == null
        || company.getTradingNameList().stream()
            .noneMatch(candidate -> candidate.getId().equals(tradingName.getId()))) {
      throw new IllegalArgumentException(
          "that trading name is not one of this invoice's company's, so the form would not offer"
              + " it.");
    }
    return tradingName;
  }

  private TaxNumber companyTaxNumber(String field, JsonNode value, Invoice invoice) {
    long id = requireId(field, value);
    TaxNumber taxNumber = Beans.get(TaxNumberRepository.class).find(id);
    if (taxNumber == null || !readable(TaxNumber.class, taxNumber.getId())) {
      throw new IllegalArgumentException("no tax number with id " + id + ".");
    }
    Company company = invoice.getCompany();
    if (company == null
        || taxNumber.getCompany() == null
        || taxNumber.getCompany().getId() == null
        || !taxNumber.getCompany().getId().equals(company.getId())) {
      throw new IllegalArgumentException("that tax number belongs to another company.");
    }
    return taxNumber;
  }

  /**
   * A message template written for the Invoice model, which is the form's own domain ({@code
   * self.metaModel.name = 'Invoice'}). A template bound to another model would render against
   * fields the invoice does not have.
   */
  private Template template(String field, JsonNode value) {
    long id = requireId(field, value);
    Template template = Beans.get(TemplateRepository.class).find(id);
    if (template == null || !readable(Template.class, template.getId())) {
      throw new IllegalArgumentException("no message template with id " + id + ".");
    }
    if (template.getMetaModel() == null || !"Invoice".equals(template.getMetaModel().getName())) {
      throw new IllegalArgumentException(
          "that message template is not written for invoices, so the form would not offer it.");
    }
    return template;
  }

  /**
   * Whether the caller may read this record at all, under the same {@code JpaSecurity} rules the
   * form obeys.
   *
   * <p>Company ownership and the relation to the invoice answer "is this the right record"; this
   * answers "is this caller allowed to see it", which is a different question and is not implied by
   * the first. Callers turn a false into their own <em>not found</em> message, identical to the one
   * a genuinely missing id produces, so a permission boundary cannot be used to probe what exists.
   */
  private static boolean readable(Class<? extends Model> type, Long id) {
    return id != null && Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_READ, type, id);
  }

  private static int requireInt(String field, JsonNode value) {
    if (isNull(value)) {
      throw new IllegalArgumentException("this field cannot be cleared.");
    }
    if (value.isNumber()) {
      return value.asInt();
    }
    try {
      return Integer.parseInt(value.asText("").trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("must be a whole number.");
    }
  }

  private static boolean requireBool(String field, JsonNode value) {
    if (isNull(value)) {
      throw new IllegalArgumentException("this field cannot be cleared; send true or false.");
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    String text = value.asText("").trim().toLowerCase(Locale.ROOT);
    if ("true".equals(text)) {
      return true;
    }
    if ("false".equals(text)) {
      return false;
    }
    throw new IllegalArgumentException("must be true or false.");
  }

  // ── reference resolution ───────────────────────────────────────────────────

  private PaymentMode resolvePaymentMode(String field, JsonNode value) {
    if (F_PAYMENT_MODE_ID.equals(field)) {
      long id = requireId(field, value);
      PaymentMode mode = Beans.get(PaymentModeRepository.class).find(id);
      if (mode == null || !readable(PaymentMode.class, mode.getId())) {
        throw new IllegalArgumentException("no payment mode with id " + id + ".");
      }
      return mode;
    }
    String code = requireCode(field, value);
    PaymentMode mode =
        Beans.get(PaymentModeRepository.class)
            .all()
            .filter("LOWER(self.code) = :code")
            .bind("code", code)
            .fetchOne();
    if (mode != null && !readable(PaymentMode.class, mode.getId())) {
      mode = null;
    }
    if (mode == null) {
      throw new IllegalArgumentException(
          "no payment mode with code '" + code + "'. The code is not the printed label.");
    }
    return mode;
  }

  private PaymentCondition resolvePaymentCondition(String field, JsonNode value) {
    if (F_PAYMENT_CONDITION_ID.equals(field)) {
      long id = requireId(field, value);
      PaymentCondition condition = Beans.get(PaymentConditionRepository.class).find(id);
      if (condition == null || !readable(PaymentCondition.class, condition.getId())) {
        throw new IllegalArgumentException("no payment condition with id " + id + ".");
      }
      return condition;
    }
    String code = requireCode(field, value);
    PaymentCondition condition =
        Beans.get(PaymentConditionRepository.class)
            .all()
            .filter("LOWER(self.code) = :code")
            .bind("code", code)
            .fetchOne();
    if (condition != null && !readable(PaymentCondition.class, condition.getId())) {
      condition = null;
    }
    if (condition == null) {
      throw new IllegalArgumentException(
          "no payment condition with code '"
              + code
              + "'. Its printed name is translated and is deliberately not accepted here.");
    }
    return condition;
  }

  private Journal resolveJournal(String field, JsonNode value) {
    Long companyId = AiCompanyScope.requireCompanyId();
    if (F_JOURNAL_ID.equals(field)) {
      long id = requireId(field, value);
      Journal journal = Beans.get(JournalRepository.class).find(id);
      if (journal == null
          || !AiCompanyScope.owns(journal.getCompany())
          || !readable(Journal.class, journal.getId())) {
        throw new IllegalArgumentException("no journal with id " + id + " in this company.");
      }
      return journal;
    }
    String code = requireCode(field, value);
    Journal journal =
        Beans.get(JournalRepository.class)
            .all()
            .filter("LOWER(self.code) = :code AND self.company.id = :companyId")
            .bind("code", code)
            .bind("companyId", companyId)
            .fetchOne();
    if (journal != null && !readable(Journal.class, journal.getId())) {
      journal = null;
    }
    if (journal == null) {
      throw new IllegalArgumentException("no journal with code '" + code + "' in this company.");
    }
    return journal;
  }

  private Account resolveAccount(String field, JsonNode value) {
    Long companyId = AiCompanyScope.requireCompanyId();
    if (F_PARTNER_ACCOUNT_ID.equals(field)) {
      long id = requireId(field, value);
      Account account = Beans.get(AccountRepository.class).find(id);
      if (account == null
          || !AiCompanyScope.owns(account.getCompany())
          || !readable(Account.class, account.getId())) {
        throw new IllegalArgumentException("no account with id " + id + " in this company.");
      }
      return account;
    }
    String code = requireCode(field, value);
    Account account =
        Beans.get(AccountRepository.class)
            .all()
            .filter("LOWER(self.code) = :code AND self.company.id = :companyId")
            .bind("code", code)
            .bind("companyId", companyId)
            .fetchOne();
    if (account != null && !readable(Account.class, account.getId())) {
      account = null;
    }
    if (account == null) {
      throw new IllegalArgumentException("no account with code '" + code + "' in this company.");
    }
    return account;
  }

  /**
   * A fiscal position the invoice's own tax number allows.
   *
   * <p>{@code InvoiceDomainService.getFiscalPositionDomain} is the form's rule: once a company tax
   * number is set, only the positions in that number's {@code taxNumberSet} are offered, and with
   * no tax number there is no restriction. Applied here so the tool cannot set a position the form
   * would refuse — the position drives every line's tax equivalence, so a wrong one is a wrong VAT
   * treatment on the whole document.
   */
  private FiscalPosition resolveFiscalPosition(Invoice invoice, String field, JsonNode value) {
    FiscalPosition position;
    if (F_FISCAL_POSITION_ID.equals(field)) {
      long id = requireId(field, value);
      position = Beans.get(FiscalPositionRepository.class).find(id);
      if (position == null || !readable(FiscalPosition.class, position.getId())) {
        throw new IllegalArgumentException("no fiscal position with id " + id + ".");
      }
    } else {
      String code = requireCode(field, value);
      position =
          Beans.get(FiscalPositionRepository.class)
              .all()
              .filter("LOWER(self.code) = :code")
              .bind("code", code)
              .fetchOne();
      if (position != null && !readable(FiscalPosition.class, position.getId())) {
        position = null;
      }
      if (position == null) {
        throw new IllegalArgumentException("no fiscal position with code '" + code + "'.");
      }
    }
    TaxNumber companyTaxNumber = invoice.getCompanyTaxNumber();
    if (companyTaxNumber != null && companyTaxNumber.getId() != null) {
      Set<TaxNumber> allowed = position.getTaxNumberSet();
      boolean offered =
          allowed != null
              && allowed.stream()
                  .anyMatch(n -> n != null && companyTaxNumber.getId().equals(n.getId()));
      if (!offered) {
        throw new IllegalArgumentException(
            "that fiscal position does not apply to this invoice's company tax number, so the form"
                + " would not offer it.");
      }
    }
    return position;
  }

  private Currency resolveCurrency(String field, JsonNode value) {
    if (F_CURRENCY_ID.equals(field)) {
      long id = requireId(field, value);
      Currency currency = Beans.get(CurrencyRepository.class).find(id);
      if (currency == null || !readable(Currency.class, currency.getId())) {
        throw new IllegalArgumentException("no currency with id " + id + ".");
      }
      return currency;
    }
    String code = requireCode(field, value);
    Currency currency =
        Beans.get(CurrencyRepository.class)
            .all()
            .filter("LOWER(self.code) = :code")
            .bind("code", code)
            .fetchOne();
    if (currency != null && !readable(Currency.class, currency.getId())) {
      currency = null;
    }
    if (currency == null) {
      throw new IllegalArgumentException("no currency with code '" + code + "'.");
    }
    return currency;
  }

  /**
   * The invoice's own partner's bank account, and nobody else's.
   *
   * <p>This is where money is paid to or from, so the ownership check is not a formality: an IBAN
   * that belongs to another partner is refused even when it exists and is perfectly valid.
   */
  private BankDetails resolveBankDetails(Invoice invoice, String field, JsonNode value) {
    if (invoice.getPartner() == null || invoice.getPartner().getId() == null) {
      throw new IllegalArgumentException(
          "the invoice has no partner yet, so a bank account cannot be checked against one.");
    }
    long partnerId = invoice.getPartner().getId();
    BankDetails bankDetails;
    if (F_BANK_DETAILS_ID.equals(field)) {
      long id = requireId(field, value);
      bankDetails = Beans.get(BankDetailsRepository.class).find(id);
      if (bankDetails == null || !readable(BankDetails.class, bankDetails.getId())) {
        throw new IllegalArgumentException("no bank account with id " + id + ".");
      }
    } else {
      String iban = requireCode(field, value).replace(" ", "");
      List<BankDetails> matches =
          Beans.get(BankDetailsRepository.class)
              .all()
              .filter("LOWER(REPLACE(self.iban, ' ', '')) = :iban AND self.partner.id = :partnerId")
              .bind("iban", iban)
              .bind("partnerId", partnerId)
              .fetch(2);
      if (matches.isEmpty()) {
        throw new IllegalArgumentException(
            "this partner has no bank account with IBAN '" + iban + "'.");
      }
      if (matches.size() > 1) {
        throw new IllegalArgumentException(
            "this partner has more than one bank account with IBAN '"
                + iban
                + "'; name the bankDetailsId instead.");
      }
      bankDetails = matches.get(0);
      if (!readable(BankDetails.class, bankDetails.getId())) {
        throw new IllegalArgumentException(
            "this partner has no bank account with IBAN '" + iban + "'.");
      }
    }
    if (bankDetails.getPartner() == null
        || bankDetails.getPartner().getId() == null
        || bankDetails.getPartner().getId() != partnerId) {
      throw new IllegalArgumentException(
          "that bank account belongs to another partner, so it cannot be used on this invoice.");
    }
    if (!Boolean.TRUE.equals(bankDetails.getActive())) {
      throw new IllegalArgumentException("that bank account is not active.");
    }
    return bankDetails;
  }

  // ── primitives ────────────────────────────────────────────────────────────

  private static void record(Result result, String field, String oldValue, String newValue) {
    if (!AiInvoiceFieldChange.same(oldValue, newValue)) {
      result.changes.add(new AiInvoiceFieldChange(field, oldValue, newValue));
    }
  }

  private static boolean isNull(JsonNode value) {
    return value == null || value.isNull();
  }

  /**
   * An ISO date, with no calendar rule of this service's own invention.
   *
   * <p>Only {@code rejectFuture} is applied, and only where stock Axelor already applies it: {@code
   * VentilateState} refuses to post an invoice whose {@code invoiceDate} is after today, and a
   * purchase whose {@code originDate} is. Nothing in stock forbids a future {@code dueDate} — a
   * payment term that falls due next month is the ordinary case — so nothing here does either.
   */
  private static LocalDate requireDate(String field, JsonNode value, boolean rejectFuture) {
    if (isNull(value)) {
      throw new IllegalArgumentException("this date cannot be cleared.");
    }
    String text = value.asText("").trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("this date cannot be blank.");
    }
    LocalDate date;
    try {
      date = LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("'" + text + "' is not an ISO date (yyyy-MM-dd).");
    }
    if (rejectFuture && date.isAfter(LocalDate.now())) {
      throw new IllegalArgumentException(
          "'" + text + "' is in the future; posting refuses an invoice dated ahead of today.");
    }
    return date;
  }

  /** A string, or null when the caller asked to clear it. */
  private static String optString(String field, JsonNode value, int maxLength) {
    if (isNull(value)) {
      return null;
    }
    String text = value.asText("").trim();
    if (text.isEmpty()) {
      return null;
    }
    if (text.length() > maxLength) {
      throw new IllegalArgumentException(
          "longer than the " + maxLength + " characters this field holds.");
    }
    return text;
  }

  private static long requireId(String field, JsonNode value) {
    long id;
    if (value.isNumber()) {
      id = value.asLong();
    } else {
      try {
        id = Long.parseLong(value.asText("").trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("must be a numeric id.");
      }
    }
    if (id <= 0) {
      throw new IllegalArgumentException("must be a positive id.");
    }
    return id;
  }

  private static String requireCode(String field, JsonNode value) {
    String text = value.asText("").trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("must not be blank.");
    }
    return text.toLowerCase(Locale.ROOT);
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /** Raised when the header cannot be applied at all. */
  public static class HeaderRefusedException extends InvoiceBlocked {
    private static final long serialVersionUID = 1L;

    public HeaderRefusedException(String message) {
      super(AiBlockerCode.BAD_ARGUMENTS, message);
    }
  }
}
