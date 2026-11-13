package com.axelor.apps.aiocr.service.readiness;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arithmetic verdict on one OCR draft invoice: the document's own totals, the totals implied by
 * its lines, and exactly how the two differ.
 *
 * <h2>Why this exists</h2>
 *
 * Invoice 576 is the reference case. The receipt prints net 19.97, VAT 4.00, gross 23.97. The
 * single correct line is 19.97 net at 20%, and Axelor's own arithmetic (the same used by {@code
 * InvoiceLineGenerator.computeTotal}) makes that 23.96 gross — so the line-derived VAT is 3.99, one
 * stotinka below the printed 4.00. The old check compared only the line ex-tax sum against the
 * header base, found 19.97 = 19.97, and reported the invoice as reconciling. The VAT was never
 * compared at all, so a real difference between what the supplier declared and what will actually
 * be posted was invisible.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>Four comparisons, not one</b>: the header's own identity ({@code base + VAT = gross}),
 *       the line ex-tax sum against the header base, the line-derived VAT against the header VAT,
 *       and the line gross sum against the header gross.
 *   <li><b>"Reconciles" means equal.</b> Every boolean here is exact. A difference inside a
 *       tolerance is still a difference, and calling it a match is how #576 was reported as fine.
 *   <li><b>A rounding-sized difference is named, not hidden.</b> Per-line VAT is rounded to the
 *       currency's scale, so a document with N lines can legitimately differ from the sum of its
 *       lines by up to one rounding step per line. That case gets its own verdict, {@link
 *       Verdict#ROUNDING}, and a sentence saying which figure will actually be posted. It is not
 *       promoted to a match and not demoted to a mismatch.
 *   <li><b>Nothing here writes anything.</b> The header totals are what the supplier printed and
 *       are the only independent check on a rewritten line section; "fixing" them to agree with the
 *       lines would make any wrong replacement reconcile with itself.
 * </ul>
 *
 * <h2>Why this class does not keep the supplier's VAT</h2>
 *
 * {@code Invoice.taxTotal} is {@code readonly} and {@code invoiceLineTaxList} — which is what
 * ventilation actually posts — is regenerated from {@code taxLineSet} × {@code exTaxTotal} when the
 * invoice is validated. Storing a supplier figure in the header alone would therefore be a lie with
 * a delay in it, which is why this class reports the difference instead and says which figure will
 * be posted.
 *
 * <p>There <em>is</em> one narrow exception, and it lives elsewhere: a row on a tax flagged {@code
 * manageByAmount} survives that regeneration when its {@code taxTotal} differs from its {@code
 * percentageTaxTotal}. {@link OcrVatRoundingReconciler} uses it to close the cent between a
 * supplier's per-line VAT rounding and Axelor's per-rate rounding, and only when the line gross
 * amounts already reproduce the printed invoice total exactly. That is a rounding correction on the
 * posting side, not a licence to keep any declared VAT — the comparisons here stay untouched and
 * keep reporting the header-versus-lines difference as they always did.
 *
 * <p>Pure: no JPA, no Guice, no I/O beyond reading the entities handed to it.
 */
public final class OcrAmountReconciliation {

  /** Smallest representable difference in a two-decimal currency — one rounding step. */
  public static final BigDecimal ROUNDING_STEP = new BigDecimal("0.01");

  /** How a pair of figures compares. */
  public enum Verdict {
    /** One of the two figures is absent, so nothing was compared. */
    NOT_CHECKED,
    /** Equal to the last decimal. */
    EXACT,
    /** Different, but by no more than the rounding that per-line VAT legitimately produces. */
    ROUNDING,
    /** Different by more than rounding can explain. Something is wrong. */
    MISMATCH
  }

  /** One pair of figures and the difference between them. */
  public static final class Comparison {
    private final String what;
    private final BigDecimal document;
    private final BigDecimal calculated;
    private final BigDecimal delta;
    private final BigDecimal allowance;
    private final Verdict verdict;

    private Comparison(
        String what,
        BigDecimal document,
        BigDecimal calculated,
        BigDecimal delta,
        BigDecimal allowance,
        Verdict verdict) {
      this.what = what;
      this.document = document;
      this.calculated = calculated;
      this.delta = delta;
      this.allowance = allowance;
      this.verdict = verdict;
    }

    static Comparison notChecked(String what) {
      return new Comparison(what, null, null, null, null, Verdict.NOT_CHECKED);
    }

    static Comparison of(
        String what, BigDecimal document, BigDecimal calculated, BigDecimal allowance) {
      if (document == null || calculated == null) {
        return notChecked(what);
      }
      BigDecimal delta = calculated.subtract(document);
      Verdict verdict;
      if (delta.signum() == 0) {
        verdict = Verdict.EXACT;
      } else if (delta.abs().compareTo(allowance) <= 0) {
        verdict = Verdict.ROUNDING;
      } else {
        verdict = Verdict.MISMATCH;
      }
      return new Comparison(what, document, calculated, delta, allowance, verdict);
    }

    /** What is being compared, in words, for a message. */
    public String getWhat() {
      return what;
    }

    /** The figure printed on the supplier's document (as stored on the invoice header). */
    public BigDecimal getDocument() {
      return document;
    }

    /** The figure the lines imply, i.e. what Axelor will post. */
    public BigDecimal getCalculated() {
      return calculated;
    }

    /** Signed {@code calculated - document}; null when not checked. */
    public BigDecimal getDelta() {
      return delta;
    }

    /** The largest difference rounding alone could account for. */
    public BigDecimal getAllowance() {
      return allowance;
    }

    public Verdict getVerdict() {
      return verdict;
    }

    /** Exactly equal. Never true for a difference of any size — that is the whole point. */
    public boolean isExact() {
      return verdict == Verdict.EXACT;
    }

    public boolean isRounding() {
      return verdict == Verdict.ROUNDING;
    }

    public boolean isMismatch() {
      return verdict == Verdict.MISMATCH;
    }

    /** Null when there is nothing to say; a plain sentence otherwise. */
    public String describe() {
      switch (verdict) {
        case ROUNDING:
          return what
              + ": the document says "
              + document
              + ", the lines give "
              + calculated
              + " — a rounding difference of "
              + delta.abs()
              + ". This is normal supplier rounding and it changes nothing that is accounted: the"
              + " invoice total ("
              + document
              + ") is what is posted and settled. See accountingTotals.";
        case MISMATCH:
          return what
              + ": the document says "
              + document
              + ", the lines give "
              + calculated
              + " — a difference of "
              + delta.abs()
              + ", which rounding cannot explain.";
        default:
          return null;
      }
    }
  }

  private final Comparison header;
  private final Comparison exTax;
  private final Comparison tax;
  private final Comparison inTax;
  private final BigDecimal headerExTaxTotal;
  private final BigDecimal headerTaxTotal;
  private final BigDecimal headerInTaxTotal;
  private final BigDecimal lineSumExTax;
  private final BigDecimal lineSumInTax;
  private final BigDecimal lineDerivedTax;
  private final int lineCount;

  private OcrAmountReconciliation(
      Comparison header,
      Comparison exTax,
      Comparison tax,
      Comparison inTax,
      BigDecimal headerExTaxTotal,
      BigDecimal headerTaxTotal,
      BigDecimal headerInTaxTotal,
      BigDecimal lineSumExTax,
      BigDecimal lineSumInTax,
      BigDecimal lineDerivedTax,
      int lineCount) {
    this.header = header;
    this.exTax = exTax;
    this.tax = tax;
    this.inTax = inTax;
    this.headerExTaxTotal = headerExTaxTotal;
    this.headerTaxTotal = headerTaxTotal;
    this.headerInTaxTotal = headerInTaxTotal;
    this.lineSumExTax = lineSumExTax;
    this.lineSumInTax = lineSumInTax;
    this.lineDerivedTax = lineDerivedTax;
    this.lineCount = lineCount;
  }

  /**
   * Reads the invoice and its lines and works out every comparison.
   *
   * <p>The line-derived VAT is {@code sum(inTaxTotal) - sum(exTaxTotal)} rather than a fresh {@code
   * exTaxTotal × rate}: {@code inTaxTotal} is the value the line actually carries, so this reports
   * what is really stored rather than recomputing and possibly disagreeing with it.
   */
  public static OcrAmountReconciliation of(Invoice invoice) {
    BigDecimal base = invoice.getExTaxTotal();
    BigDecimal vat = invoice.getTaxTotal();
    BigDecimal gross = invoice.getInTaxTotal();

    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    int count = lines == null ? 0 : lines.size();

    // A header is one printed set of figures, so one rounding step is all it may be out by.
    Comparison headerCheck =
        (base == null || vat == null || gross == null)
            ? Comparison.notChecked("Header total")
            : Comparison.of("Header total", gross, base.add(vat), ROUNDING_STEP);

    if (count == 0) {
      return new OcrAmountReconciliation(
          headerCheck,
          Comparison.notChecked("Ex-tax total"),
          Comparison.notChecked("VAT"),
          Comparison.notChecked("Gross total"),
          base,
          vat,
          gross,
          null,
          null,
          null,
          0);
    }

    BigDecimal sumExTax = BigDecimal.ZERO;
    BigDecimal sumInTax = BigDecimal.ZERO;
    for (InvoiceLine line : lines) {
      sumExTax = sumExTax.add(nz(line.getExTaxTotal()));
      sumInTax = sumInTax.add(nz(line.getInTaxTotal()));
    }
    BigDecimal derivedTax = sumInTax.subtract(sumExTax);

    // Each line's VAT is rounded to the currency scale independently, so N lines can accumulate up
    // to N rounding steps against a single printed figure.
    BigDecimal allowance = ROUNDING_STEP.multiply(BigDecimal.valueOf(Math.max(1, count)));

    return new OcrAmountReconciliation(
        headerCheck,
        Comparison.of("Ex-tax total", base, sumExTax, allowance),
        Comparison.of("VAT", vat, derivedTax, allowance),
        Comparison.of("Gross total", gross, sumInTax, allowance),
        base,
        vat,
        gross,
        sumExTax,
        sumInTax,
        derivedTax,
        count);
  }

  /** {@code base + VAT} against the printed gross — the document's internal consistency. */
  public Comparison getHeader() {
    return header;
  }

  /** Line ex-tax sum against the printed net total. */
  public Comparison getExTax() {
    return exTax;
  }

  /** Line-derived VAT against the printed VAT. This is the check #576 was missing. */
  public Comparison getTax() {
    return tax;
  }

  /** Line gross sum against the printed gross total. */
  public Comparison getInTax() {
    return inTax;
  }

  public BigDecimal getLineSumExTax() {
    return lineSumExTax;
  }

  public BigDecimal getLineSumInTax() {
    return lineSumInTax;
  }

  public BigDecimal getLineDerivedTax() {
    return lineDerivedTax;
  }

  public int getLineCount() {
    return lineCount;
  }

  private List<Comparison> all() {
    List<Comparison> out = new ArrayList<>();
    out.add(header);
    out.add(exTax);
    out.add(tax);
    out.add(inTax);
    return out;
  }

  /** The worst verdict among the four comparisons; {@code NOT_CHECKED} when none was made. */
  public Verdict verdict() {
    Verdict worst = Verdict.NOT_CHECKED;
    for (Comparison c : all()) {
      switch (c.getVerdict()) {
        case MISMATCH:
          return Verdict.MISMATCH;
        case ROUNDING:
          worst = Verdict.ROUNDING;
          break;
        case EXACT:
          if (worst == Verdict.NOT_CHECKED) {
            worst = Verdict.EXACT;
          }
          break;
        default:
          break;
      }
    }
    return worst;
  }

  /**
   * True only when every comparison that could be made was exact.
   *
   * <p>Deliberately not "within tolerance": a caller asking whether the amounts reconcile is asking
   * whether it can stop looking, and a rounding difference is something a person still has to be
   * told about.
   */
  public boolean isExact() {
    return verdict() == Verdict.EXACT;
  }

  /** A difference exists but every one of them is rounding-sized. */
  public boolean isRoundingOnly() {
    return verdict() == Verdict.ROUNDING;
  }

  /** At least one difference is too large to be rounding. */
  public boolean isMismatch() {
    return verdict() == Verdict.MISMATCH;
  }

  /** One sentence per difference; empty when everything is exact or unchecked. */
  public List<String> differences() {
    List<String> out = new ArrayList<>();
    for (Comparison c : all()) {
      String note = c.describe();
      if (note != null) {
        out.add(note);
      }
    }
    return out;
  }

  /**
   * Wire form.
   *
   * <p>{@code headerReconciles} and {@code linesReconcile} keep their names and their place because
   * tools, prompts and the record projection already speak in them — but they now mean "equal", so
   * an invoice that used to read {@code linesReconcile=true} while being a stotinka out reads
   * {@code false} with {@code verdict=ROUNDING} and a sentence explaining it.
   */
  public Map<String, Object> toMap() {
    Map<String, Object> out = new LinkedHashMap<>();
    // Stated first, and stated every time, because this is the field that was being inferred — and
    // inferred wrongly. This structure diagnoses the extraction; it never supplies an amount that
    // is validated, posted, settled or quoted to the user as what will be accounted.
    out.put("purpose", "DIAGNOSTIC_ONLY");
    out.put("authoritativeSource", "accountingTotals (the invoice's own stored totals)");
    out.put("accountingImpact", isMismatch() ? "BLOCKING" : "NONE");
    out.put("verdict", verdict().name());
    out.put("reconciles", isExact());
    out.put("headerExTaxTotal", headerExTaxTotal);
    out.put("headerTaxTotal", headerTaxTotal);
    out.put("headerInTaxTotal", headerInTaxTotal);
    out.put("headerReconciles", header.isExact());
    out.put("headerDelta", header.getDelta());
    out.put("lineCount", lineCount);
    out.put("lineSumExTax", lineSumExTax);
    out.put("lineSumInTax", lineSumInTax);
    out.put("lineDerivedTax", lineDerivedTax);
    out.put("linesReconcile", checkedFlag(exTax));
    out.put("exTaxDelta", exTax.getDelta());
    out.put("taxReconciles", checkedFlag(tax));
    out.put("taxDelta", tax.getDelta());
    out.put("inTaxReconciles", checkedFlag(inTax));
    out.put("inTaxDelta", inTax.getDelta());
    out.put("roundingAllowance", exTax.getAllowance());
    out.put("differences", differences());
    if (isRoundingOnly()) {
      Map<String, Object> rounding = new LinkedHashMap<>();
      rounding.put("taxDocument", headerTaxTotal);
      rounding.put("taxCalculated", lineDerivedTax);
      rounding.put("taxDelta", tax.getDelta());
      rounding.put(
          "explanation",
          "The difference is one rounding step or less per line, and it has NO accounting effect."
              + " taxCalculated is this reconstruction re-adding the individually rounded per-line"
              + " amounts; nothing in the posting path reads those. Axelor derives the VAT once per"
              + " rate from the aggregated base (invoiceLineTax = exTaxBase × rate) and posts the"
              + " invoice's own totals. Report the difference as a reconstruction difference; state"
              + " the amounts from accountingTotals, never taxCalculated, as what will be posted;"
              + " and do not call the invoice exactly reconciled either.");
      out.put("roundingDifference", rounding);
    }
    return out;
  }

  /** {@code null} when the comparison could not be made, so "unchecked" never reads as "fine". */
  private static Boolean checkedFlag(Comparison c) {
    return c.getVerdict() == Verdict.NOT_CHECKED ? null : Boolean.valueOf(c.isExact());
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
