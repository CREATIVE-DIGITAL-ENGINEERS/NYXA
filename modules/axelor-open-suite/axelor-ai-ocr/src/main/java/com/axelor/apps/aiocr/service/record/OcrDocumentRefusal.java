package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.base.AxelorException;
import com.axelor.i18n.I18n;
import java.math.BigDecimal;
import java.util.function.Function;

/**
 * A refusal of {@link OcrRoundingLineService}: the invoice does not match the document it was read
 * from, or the correction cannot be booked.
 *
 * <p>{@link #getMessage()} is the full English diagnosis, as before — the tests and the logs read
 * it. What the user is shown is {@link #userMessage}: what kind of problem it is, the two totals,
 * and where to fix it, in the user's language. {@code OcrInvoiceServiceImpl} swaps one for the
 * other at the edge, so Validate and Ventilate never show the English text.
 */
public class OcrDocumentRefusal extends AxelorException {

  /** What the user has to do about it — one message each. */
  public enum Kind {
    /** The lines do not add up to the printed total, beyond rounding. */
    MISMATCH,
    /** The lines carry no VAT although the document has VAT. */
    NO_VAT,
    /** A rounding difference, but the company has no rounding account set. */
    ROUNDING_ACCOUNT_MISSING,
    /** A rounding difference, but the account set for it is a group (view) or inactive account. */
    ROUNDING_ACCOUNT_UNUSABLE,
    /** A rounding difference, but the zero-rate purchase tax is missing or not valid. */
    ZERO_TAX_MISSING,
    /** The VAT breakdown by rate cannot be built or is mixed. */
    VAT_BREAKDOWN
  }

  public static final String MISMATCH_TEXT =
      /*$$(*/ "The invoice does not match the document it was read from: the document's total is %s, the invoice lines give %s. A difference of more than 0.02 is not rounding. Check the quantities, prices and taxes of the invoice lines against the document." /*)*/;

  public static final String NO_VAT_TEXT =
      /*$$(*/ "The invoice lines carry no VAT, but the document's total of %s includes VAT (the lines give %s). Open each line and set its tax (field Taxes)." /*)*/;

  public static final String ROUNDING_ACCOUNT_TEXT =
      /*$$(*/ "The difference between the document's total (%s) and the lines (%s) is rounding, but no account is set for it. Fill in the Cashier Debit Regulation account in the company's Accounting configuration (a posting expense account, e.g. 60909)." /*)*/;

  public static final String ROUNDING_ACCOUNT_UNUSABLE_TEXT =
      /*$$(*/ "The difference between the document's total (%s) and the lines (%s) is rounding, but the account set for it, %s, is a group or inactive account and cannot carry a line. Set a posting expense account (e.g. 60909) as the Cashier Debit Regulation account in the company's Accounting configuration." /*)*/;

  public static final String ZERO_TAX_TEXT =
      /*$$(*/ "The difference between the document's total (%s) and the lines (%s) is rounding, but the zero-rate purchase tax is missing or not valid for the invoice date. Check the taxes in the Accounting configuration." /*)*/;

  public static final String VAT_BREAKDOWN_TEXT =
      /*$$(*/ "The VAT of the invoice cannot be broken down by rate (document total %s, lines %s). Check that every line has the right tax and that the lines do not mix VAT treatments." /*)*/;

  private final Kind kind;
  private final BigDecimal documentTotal;
  private final BigDecimal invoiceTotal;
  private final String account;

  public OcrDocumentRefusal(
      Invoice invoice,
      int category,
      String message,
      Kind kind,
      BigDecimal documentTotal,
      BigDecimal invoiceTotal) {
    this(invoice, category, message, kind, documentTotal, invoiceTotal, null);
  }

  /** As above, naming the account the refusal is about (the rounding account). */
  public OcrDocumentRefusal(
      Invoice invoice,
      int category,
      String message,
      Kind kind,
      BigDecimal documentTotal,
      BigDecimal invoiceTotal,
      String account) {
    super(invoice, category, message);
    this.kind = kind;
    this.documentTotal = documentTotal;
    this.invoiceTotal = invoiceTotal;
    this.account = account;
  }

  public Kind getKind() {
    return kind;
  }

  /** The refusal as the user reads it, in their language. */
  public String userMessage() {
    return userMessage(I18n::get);
  }

  /** As {@link #userMessage()}, with the translation passed in (tests have no database). */
  public String userMessage(Function<String, String> translate) {
    String text;
    switch (kind) {
      case NO_VAT:
        text = NO_VAT_TEXT;
        break;
      case ROUNDING_ACCOUNT_MISSING:
        text = ROUNDING_ACCOUNT_TEXT;
        break;
      case ROUNDING_ACCOUNT_UNUSABLE:
        text = ROUNDING_ACCOUNT_UNUSABLE_TEXT;
        break;
      case ZERO_TAX_MISSING:
        text = ZERO_TAX_TEXT;
        break;
      case VAT_BREAKDOWN:
        text = VAT_BREAKDOWN_TEXT;
        break;
      default:
        text = MISMATCH_TEXT;
    }
    // A text with two placeholders ignores the third argument.
    return String.format(
        translate.apply(text),
        amount(documentTotal),
        amount(invoiceTotal),
        account == null ? "—" : account);
  }

  /**
   * The same refusal for the user: their-language message, the English diagnosis kept as the cause
   * (it is what the traceback records).
   */
  public AxelorException forUser(Invoice invoice) {
    return new AxelorException(this, invoice, getCategory(), userMessage());
  }

  private static String amount(BigDecimal value) {
    return value == null ? "—" : value.toPlainString();
  }
}
