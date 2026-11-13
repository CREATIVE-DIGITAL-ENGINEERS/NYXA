package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.service.invoice.generator.InvoiceGenerator;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import java.util.ArrayList;
import java.util.List;

/**
 * The smallest possible concrete {@link InvoiceGenerator}: a plain invoice with no source document.
 *
 * <h2>Why subclass rather than build an Invoice by hand</h2>
 *
 * {@code InvoiceGenerator.createInvoiceHeader()} already is the "build an invoice for this company,
 * partner and operation type" logic, and it is the logic Axelor's own order-to-invoice conversions
 * use. It resolves the payment condition and payment mode through {@code InvoiceToolService} (the
 * partner's own first, the company's configuration second), the invoicing address through {@code
 * PartnerService}, the currency from the partner, the in-ATI flag from the accounting
 * configuration, the company bank details and the partner's default account — and sets the initial
 * DRAFT status. Assembling that by hand here would be a second implementation of a dozen defaulting
 * rules, wrong in some detail from the first day and drifting from then on.
 *
 * <p>The class is abstract only because {@code generate()} is: every existing subclass has a source
 * document to read (a sale order, a purchase order, a contract) and builds its lines from it. This
 * one has none — the lines come from what the user said — so {@code generate()} is the header and
 * nothing else, and the caller adds lines afterwards through {@link InvoiceLineEditService}.
 *
 * <h2>Deliberately not set here</h2>
 *
 * The journal. No Axelor creation path sets one — not the form's {@code onNew} chain, not {@code
 * InvoiceGenerator}, not the OCR intake — because validation assigns it from the company's
 * configuration, by operation type and by the sign of the total. Setting one at creation would
 * pre-empt a choice Axelor makes better later, and would be wrong the moment the total went
 * negative.
 */
public class AiManualInvoiceGenerator extends InvoiceGenerator {

  /**
   * @param operationType fixed by the calling tool — never taken from the model
   * @param company the active company, from the AI company scope
   * @param partner the customer or supplier this invoice is for
   * @param internalReference optional, may be null
   * @param externalReference optional, may be null
   */
  public AiManualInvoiceGenerator(
      int operationType,
      Company company,
      Partner partner,
      String internalReference,
      String externalReference)
      throws AxelorException {
    // Nulls are not omissions: each one tells createInvoiceHeader to resolve that value the way the
    // form would — payment condition and mode from the partner then the company, the address from
    // PartnerService, the currency from the partner, inAti from the accounting configuration.
    super(
        operationType,
        company,
        null,
        null,
        null,
        partner,
        null,
        null,
        null,
        internalReference,
        externalReference,
        null,
        null,
        null,
        null,
        null);
  }

  @Override
  public Invoice generate() throws AxelorException {
    Invoice invoice = createInvoiceHeader();
    // An empty list rather than none: populate initialises the collections and computes the
    // (currently zero) totals, so the record is internally consistent from the moment it is saved.
    List<InvoiceLine> lines = new ArrayList<>();
    populate(invoice, lines);
    return invoice;
  }
}
