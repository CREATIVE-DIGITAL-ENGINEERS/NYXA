package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.service.invoice.generator.tax.TaxInvoiceLine;
import com.axelor.apps.aiocr.service.readiness.OcrVatRoundingReconciler;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds an OCR draft's VAT summary — {@code Invoice.invoiceLineTaxList}, the rows the form shows
 * in its "Tax" panel and the rows ventilation actually posts.
 *
 * <h2>Why it exists</h2>
 *
 * A supplier invoice built by OCR intake, or corrected by {@code edit_supplier_invoice}, has
 * correct lines with a correct {@code taxLineSet} but no {@code invoiceLineTaxList} unless it is
 * built here. Clicking Validate would then raise Axelor's own alert — <i>"There is no taxes on this
 * invoice. Do you want to proceed?"</i> — which is {@code
 * action-validate-invoice-ckeck-invoice-line-tax-list} in {@code Invoice.xml}, an {@code <alert>}
 * on {@code invoiceLineTaxList == null || invoiceLineTaxList.isEmpty()} evaluated on the saved
 * record just before the validation call.
 *
 * <p>Continuing past it works because {@code InvoiceServiceImpl.validateProcess} calls {@code
 * compute(invoice)} first, and that is what fills the panel in. So the warning is not about the
 * taxes being wrong; it is about the draft not yet carrying the summary that a draft prepared in
 * the form always carries.
 *
 * <p>A hand-made invoice gets it for free: every edit of {@code invoiceLineList} fires {@code
 * action-invoice-group-invoice-line-list-on-change} → {@code action-invoice-method-compute} →
 * {@code InvoiceService.compute}, whose result map includes {@code invoiceLineTaxList}, and the
 * next save persists it. Nothing in the OCR pipeline goes through the form, so nothing ever
 * computed it.
 *
 * <h2>Why not simply call {@code InvoiceService.compute}</h2>
 *
 * Because {@code compute} also runs {@code InvoiceGenerator.computeInvoice}, which overwrites
 * {@code exTaxTotal} with the line sum and {@code taxTotal}/{@code inTaxTotal} with the figures
 * derived from the tax rows. Those three header fields are what the <em>supplier printed</em>, and
 * they are the only independent check on whether a rewritten line section is right — see {@link
 * com.axelor.apps.aiocr.service.readiness.OcrAmountReconciliation}. Overwriting them would make a
 * wrong line section reconcile with itself, and would erase exactly the 0.01 rounding differences
 * that have to stay visible.
 *
 * <p>So this class runs the <em>same generator</em> {@code compute} runs — {@link TaxInvoiceLine},
 * unchanged, with its own VAT-system resolution, its own imputed-account lookup via {@code
 * TaxAccountService} and its own per-rate aggregation — and stops there. No {@code Tax}, {@code
 * TaxLine} or account is invented here; the rows are assembled strictly from each line's existing
 * {@code taxLineSet}, and a line with no tax contributes nothing.
 *
 * <h2>Idempotent, and harmless at validation</h2>
 *
 * {@code TaxInvoiceLine.creates()} clears the list and rebuilds it, so running this twice yields
 * the same rows. At validation Axelor rebuilds them again: {@code
 * InvoiceGenerator.initInvoiceLineTaxList} keeps only rows that carry a manual amount override
 * ({@code taxTotal != percentageTaxTotal} on a tax flagged {@code manageByAmount}) and regenerates
 * the rest, so nothing is duplicated.
 *
 * <p>One row deliberately <em>does</em> carry such an override: see {@link
 * com.axelor.apps.aiocr.service.readiness.OcrVatRoundingReconciler}, run below once the rows exist.
 * It closes the cent between a supplier's per-line VAT rounding and Axelor's per-rate rounding, and
 * the override is exactly what makes that correction survive validation. Running this method again
 * is still idempotent: {@code creates()} preserves the override, and the reconciler then re-derives
 * a delta of zero.
 *
 * <p>Failure is never fatal. The generator needs the company's VAT account configuration, and a
 * company missing it would throw here exactly as it would throw at validation; that is a real
 * problem to report, not a reason to lose a correction that is otherwise valid. On failure the
 * previous rows are put back and the reason is returned for the caller to record.
 */
@Singleton
public class OcrInvoiceTaxSummaryService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrInvoiceTaxSummaryService.class);

  /** What the rebuild produced, or why it could not. */
  public static final class Result {
    private int rowCount;
    private boolean changed;
    private String failure;
    private final List<String> rows = new ArrayList<>();
    private OcrVatRoundingReconciler.Outcome roundingAdjustment;

    /** Number of {@code InvoiceLineTax} rows the invoice now carries. */
    public int getRowCount() {
      return rowCount;
    }

    /** True when the rebuilt summary differs from the one that was there before. */
    public boolean isChanged() {
      return changed;
    }

    /** Null when the summary was rebuilt; the reason otherwise. */
    public String getFailure() {
      return failure;
    }

    /** One readable line per rate, e.g. {@code "BG_SRP_20 20.00%: base 19.97, VAT 3.99"}. */
    public List<String> getRows() {
      return rows;
    }

    /**
     * What the per-line VAT rounding reconciler did, or why it declined. Never null after a
     * successful {@code sync}.
     */
    public OcrVatRoundingReconciler.Outcome getRoundingAdjustment() {
      return roundingAdjustment;
    }

    public boolean isFailed() {
      return failure != null;
    }
  }

  /**
   * Rebuilds {@code invoiceLineTaxList} from the invoice's lines. Header totals are not touched.
   *
   * <p>Does not save: the caller owns persistence, and {@code invoiceLineTaxList} is {@code
   * cascade=ALL, orphanRemoval=true}, so whichever save the caller already performs writes the new
   * rows and deletes the replaced ones.
   */
  public Result sync(Invoice invoice) {
    Result result = new Result();
    if (invoice == null) {
      return result;
    }
    if (invoice.getInvoiceLineTaxList() == null) {
      invoice.setInvoiceLineTaxList(new ArrayList<>());
    }
    List<InvoiceLineTax> previous = new ArrayList<>(invoice.getInvoiceLineTaxList());
    List<String> before = describe(previous);

    List<InvoiceLine> lines =
        invoice.getInvoiceLineList() == null ? new ArrayList<>() : invoice.getInvoiceLineList();
    try {
      // creates() clears invoice.invoiceLineTaxList itself and returns the replacement; populate()
      // in InvoiceGenerator adds them back, and so do we. Nothing else in it touches the header.
      List<InvoiceLineTax> generated = new TaxInvoiceLine(invoice, lines).creates();
      invoice.getInvoiceLineTaxList().clear();
      for (InvoiceLineTax row : generated) {
        invoice.addInvoiceLineTaxListItem(row);
      }
      // Only now that the rows exist can the supplier's per-line VAT rounding be reconciled against
      // Axelor's per-rate rounding. Inside sync() rather than at its five call sites, so every
      // path that rebuilds the summary gets it, and so it re-runs on the rebuilt rows.
      result.roundingAdjustment = OcrVatRoundingReconciler.apply(invoice);
      result.rowCount = invoice.getInvoiceLineTaxList().size();
      result.rows.addAll(describe(invoice.getInvoiceLineTaxList()));
      result.changed = !before.equals(result.rows);
    } catch (Exception e) {
      // Put back exactly what was there. The rows are the same managed instances, so re-adding them
      // before the flush means orphan removal never sees them leave the collection.
      invoice.getInvoiceLineTaxList().clear();
      for (InvoiceLineTax row : previous) {
        invoice.addInvoiceLineTaxListItem(row);
      }
      result.rowCount = previous.size();
      result.rows.addAll(before);
      result.failure =
          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim();
      LOG.warn(
          "Could not build the VAT summary for invoice {}: {}", invoice.getId(), result.failure);
    }
    return result;
  }

  /** Stable, comparable description of a set of rows — also what goes into the audit note. */
  protected List<String> describe(List<InvoiceLineTax> rows) {
    List<String> out = new ArrayList<>();
    if (rows == null) {
      return out;
    }
    for (InvoiceLineTax row : rows) {
      TaxLine taxLine = row.getTaxLine();
      String code =
          taxLine == null || taxLine.getTax() == null ? "(no tax)" : taxLine.getTax().getCode();
      String rate = taxLine == null || taxLine.getValue() == null ? "?" : taxLine.getValue() + "%";
      out.add(
          code
              + " "
              + rate
              + ": base "
              + row.getExTaxBase()
              + ", VAT "
              + row.getTaxTotal()
              + (row.getImputedAccount() == null
                  ? ""
                  : ", account " + row.getImputedAccount().getCode()));
    }
    return out;
  }
}
