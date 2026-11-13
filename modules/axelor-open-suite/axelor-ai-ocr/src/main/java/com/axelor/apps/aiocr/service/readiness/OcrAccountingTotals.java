package com.axelor.apps.aiocr.service.readiness;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The amounts Axelor actually accounts, taken from the invoice rather than recomputed.
 *
 * <h2>Why this exists</h2>
 *
 * There are two sets of figures on an OCR invoice and only one of them is money. The invoice's own
 * {@code exTaxTotal} / {@code taxTotal} / {@code inTaxTotal}, together with the per-rate {@code
 * invoiceLineTaxList}, are what validation stored and what posting reads. The other set — re-adding
 * the individually rounded per-line {@code inTaxTotal}s — is a reconstruction used to check the
 * extraction, and it can legitimately land a stotinka away.
 *
 * <p>That distinction was not expressed anywhere, so it had to be inferred, and it was inferred
 * wrongly: on invoice 583 the assistant told the accountant Axelor would post VAT 65.49 and gross
 * 392.99, because those were the numbers the reconstruction produced. Axelor posts 65.50 and
 * 393.00. Nothing in the posting path ever reads a per-line {@code inTaxTotal}.
 *
 * <h2>Verified against the posting path</h2>
 *
 * Every figure below was traced to the code that consumes it, not assumed:
 *
 * <ul>
 *   <li><b>expense debits</b> — {@code MoveLineCreateServiceImpl.createMoveLines} builds one line
 *       per invoice line from {@code invoiceLine.getCompanyExTaxTotal()};
 *   <li><b>VAT debits</b> — one line per {@code InvoiceLineTax} from {@code
 *       invoiceLineTax.getCompanyTaxTotal()}, which validation derived from {@code exTaxBase ×
 *       rate} on the <em>aggregated</em> base for that rate, not by adding per-line VAT;
 *   <li><b>the payable credit</b> — {@code addInvoiceTermMoveLines} totals to {@code
 *       invoice.getCompanyInTaxTotal()}, and forces it: the last term is given {@code
 *       companyInTaxTotal} minus everything before it;
 *   <li><b>the settlement amounts</b> — {@code InvoiceTermServiceImpl.computeInvoiceTerm} is {@code
 *       invoice.getInTaxTotal() × percentage / 100}, and {@code computeInvoiceTerms} gives the last
 *       term {@code inTaxTotal} minus the rest.
 * </ul>
 *
 * <h2>The one identity that must hold</h2>
 *
 * From the three bullets above, the move balances if and only if
 *
 * <pre>Σ line.companyExTaxTotal + Σ invoiceLineTax.companyTaxTotal == invoice.companyInTaxTotal
 * </pre>
 *
 * That is the only arithmetic that can make posting fail, and it is checked here so readiness can
 * refuse before a Move is built rather than after — see {@link #postingDelta()}. A difference
 * between the header and the re-added lines is <em>not</em> this identity and cannot break it.
 */
public final class OcrAccountingTotals {

  /** What these figures govern, stated rather than left to be worked out. */
  public static final String AUTHORITATIVE_FOR =
      "validation, ventilation/posting, invoice terms and settlement, and any statement to the user"
          + " of what will be accounted";

  private final BigDecimal exTaxTotal;
  private final BigDecimal taxTotal;
  private final BigDecimal inTaxTotal;
  private final BigDecimal companyExTaxTotal;
  private final BigDecimal companyTaxTotal;
  private final BigDecimal companyInTaxTotal;
  private final String currency;
  private final String companyCurrency;

  /** Sum of the per-rate VAT summary — the VAT that will actually be debited. */
  private final BigDecimal vatSummaryTaxTotal;

  /** Sum of the lines' company ex-tax totals — what will actually be debited to expense. */
  private final BigDecimal lineCompanyExTaxSum;

  private OcrAccountingTotals(
      BigDecimal exTaxTotal,
      BigDecimal taxTotal,
      BigDecimal inTaxTotal,
      BigDecimal companyExTaxTotal,
      BigDecimal companyTaxTotal,
      BigDecimal companyInTaxTotal,
      String currency,
      String companyCurrency,
      BigDecimal vatSummaryTaxTotal,
      BigDecimal lineCompanyExTaxSum) {
    this.exTaxTotal = exTaxTotal;
    this.taxTotal = taxTotal;
    this.inTaxTotal = inTaxTotal;
    this.companyExTaxTotal = companyExTaxTotal;
    this.companyTaxTotal = companyTaxTotal;
    this.companyInTaxTotal = companyInTaxTotal;
    this.currency = currency;
    this.companyCurrency = companyCurrency;
    this.vatSummaryTaxTotal = vatSummaryTaxTotal;
    this.lineCompanyExTaxSum = lineCompanyExTaxSum;
  }

  public static OcrAccountingTotals of(Invoice invoice) {
    BigDecimal vatSum = BigDecimal.ZERO;
    if (invoice.getInvoiceLineTaxList() != null) {
      for (InvoiceLineTax row : invoice.getInvoiceLineTaxList()) {
        vatSum = vatSum.add(nz(row.getCompanyTaxTotal()));
      }
    }
    BigDecimal lineSum = BigDecimal.ZERO;
    if (invoice.getInvoiceLineList() != null) {
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        lineSum = lineSum.add(nz(line.getCompanyExTaxTotal()));
      }
    }
    return new OcrAccountingTotals(
        nz(invoice.getExTaxTotal()),
        nz(invoice.getTaxTotal()),
        nz(invoice.getInTaxTotal()),
        nz(invoice.getCompanyExTaxTotal()),
        nz(invoice.getCompanyTaxTotal()),
        nz(invoice.getCompanyInTaxTotal()),
        invoice.getCurrency() == null ? null : invoice.getCurrency().getCode(),
        invoice.getCompany() == null || invoice.getCompany().getCurrency() == null
            ? null
            : invoice.getCompany().getCurrency().getCode(),
        vatSum,
        lineSum);
  }

  public BigDecimal getExTaxTotal() {
    return exTaxTotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getInTaxTotal() {
    return inTaxTotal;
  }

  public BigDecimal getCompanyInTaxTotal() {
    return companyInTaxTotal;
  }

  /**
   * {@code (Σ expense debits + Σ VAT debits) − payable credit}, in company currency.
   *
   * <p>Zero means the move Axelor is about to build balances. Non-zero is a genuine internal
   * inconsistency between the invoice's stored totals and its own accounting structures, and the
   * only case where the arithmetic is a blocker rather than a diagnostic.
   */
  public BigDecimal postingDelta() {
    return lineCompanyExTaxSum.add(vatSummaryTaxTotal).subtract(companyInTaxTotal);
  }

  public boolean postingBalances() {
    return postingDelta().signum() == 0;
  }

  /** What is inconsistent, in the words of the accounts involved; empty when it balances. */
  public List<String> postingDifferences() {
    List<String> out = new ArrayList<>();
    if (postingBalances()) {
      return out;
    }
    out.add(
        "The expense debits ("
            + lineCompanyExTaxSum
            + ") plus the VAT debits ("
            + vatSummaryTaxTotal
            + ") come to "
            + lineCompanyExTaxSum.add(vatSummaryTaxTotal)
            + ", but the payable credit is the invoice gross ("
            + companyInTaxTotal
            + "), a difference of "
            + postingDelta()
            + ".");
    return out;
  }

  /**
   * The projection handed to a caller.
   *
   * <p>{@code authoritativeFor} is part of the payload on purpose. A reader that has to decide
   * between two sets of numbers will sometimes decide wrongly; a reader that is told which set
   * governs cannot.
   */
  public Map<String, Object> toMap() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("authoritativeFor", AUTHORITATIVE_FOR);
    out.put("exTaxTotal", exTaxTotal);
    out.put("taxTotal", taxTotal);
    out.put("inTaxTotal", inTaxTotal);
    out.put("currency", currency);
    out.put("companyExTaxTotal", companyExTaxTotal);
    out.put("companyTaxTotal", companyTaxTotal);
    out.put("companyInTaxTotal", companyInTaxTotal);
    out.put("companyCurrency", companyCurrency);
    // What the move will be made of, so "what will be posted" is answerable without arithmetic.
    Map<String, Object> posting = new LinkedHashMap<>();
    posting.put("expenseDebit", lineCompanyExTaxSum);
    posting.put("vatDebit", vatSummaryTaxTotal);
    posting.put("payableCredit", companyInTaxTotal);
    posting.put("balances", postingBalances());
    posting.put("delta", postingDelta());
    out.put("posting", posting);
    return out;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
