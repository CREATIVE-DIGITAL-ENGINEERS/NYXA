package com.axelor.apps.aiocr.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceLineService;
import com.axelor.apps.account.service.invoice.InvoiceTermFilterService;
import com.axelor.apps.account.service.invoice.InvoiceTermPfpService;
import com.axelor.apps.account.service.invoice.InvoiceTermPfpToolService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.account.service.invoice.factory.CancelFactory;
import com.axelor.apps.account.service.invoice.factory.ValidateFactory;
import com.axelor.apps.account.service.invoice.factory.VentilateFactory;
import com.axelor.apps.account.service.invoice.print.InvoicePrintService;
import com.axelor.apps.account.service.invoice.print.InvoiceProductStatementService;
import com.axelor.apps.account.service.move.MoveToolService;
import com.axelor.apps.aiocr.service.record.OcrDocumentRefusal;
import com.axelor.apps.aiocr.service.record.OcrRoundingLineService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.businessproject.service.InvoiceServiceProjectImpl;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.apps.supplychain.service.IntercoService;
import com.axelor.message.service.TemplateMessageService;
import com.google.inject.Inject;

/**
 * The one place a supplier invoice's total is checked against the document it came from.
 *
 * <h2>Why here and not in a controller or an OCR-only service</h2>
 *
 * Validate and Ventilate are reachable from four directions — the stock form buttons, the AI
 * accountant's tools ({@code SupplierInvoiceValidationService} / {@code
 * SupplierInvoiceVentilationService}), batches, and anything programmatic — and every one of them
 * ends at {@code InvoiceService.validate} / {@code InvoiceService.ventilate}. Putting the check on
 * a controller or on the OCR tool path would leave the form open; putting it here closes all of
 * them at once, server-side, with no tool contract to republish.
 *
 * <h2>Which link of the chain</h2>
 *
 * The production chain is {@code InvoiceService} → {@code InvoiceServiceImpl} (account) → {@code
 * InvoiceServiceSupplychainImpl} (supplychain) → {@code InvoiceServiceProjectImpl}
 * (business-project) → here. {@code InvoiceServiceProjectImpl} is the TAIL — nothing else binds it,
 * while {@code InvoiceServiceSupplychainImpl} is already bound by {@code BusinessProjectModule}, so
 * binding that key again would be a duplicate Guice binding (boot failure) and would also drop the
 * project layer's {@code cancel} override on the floor. Extending the tail keeps every layer below
 * intact — project analytics, interco invoices, timetables, budgets and the rest run exactly as
 * they did.
 *
 * <h2>Order</h2>
 *
 * The reconciliation runs BEFORE {@code super.validate}, not inside it. Validation's own {@code
 * compute} is what produces the payment terms, and {@code InvoiceGenerator.computeInvoice} only
 * generates them when the list is empty — so a correction line added after that point would leave
 * the terms sitting on the pre-correction total. Correcting first means the terms are derived from
 * the final figure.
 *
 * <p>For ventilation the gate is {@code checkPreconditions}, which {@code ventilateProcess} runs
 * first of all — before the invoice number is taken and before the move is built. Refusing there
 * costs nothing and leaves the invoice exactly as it was.
 */
public class OcrInvoiceServiceImpl extends InvoiceServiceProjectImpl {

  protected OcrRoundingLineService ocrRoundingLineService;

  @Inject
  public OcrInvoiceServiceImpl(
      ValidateFactory validateFactory,
      VentilateFactory ventilateFactory,
      CancelFactory cancelFactory,
      InvoiceRepository invoiceRepo,
      AppAccountService appAccountService,
      PartnerService partnerService,
      InvoiceLineService invoiceLineService,
      AccountConfigService accountConfigService,
      MoveToolService moveToolService,
      InvoiceTermService invoiceTermService,
      InvoiceTermPfpService invoiceTermPfpService,
      AppBaseService appBaseService,
      InvoiceProductStatementService invoiceProductStatementService,
      TemplateMessageService templateMessageService,
      InvoiceTermFilterService invoiceTermFilterService,
      InvoicePrintService invoicePrintService,
      InvoiceTermPfpToolService invoiceTermPfpToolService,
      InvoiceLineRepository invoiceLineRepo,
      IntercoService intercoService,
      StockMoveRepository stockMoveRepository,
      OcrRoundingLineService ocrRoundingLineService) {
    super(
        validateFactory,
        ventilateFactory,
        cancelFactory,
        invoiceRepo,
        appAccountService,
        partnerService,
        invoiceLineService,
        accountConfigService,
        moveToolService,
        invoiceTermService,
        invoiceTermPfpService,
        appBaseService,
        invoiceProductStatementService,
        templateMessageService,
        invoiceTermFilterService,
        invoicePrintService,
        invoiceTermPfpToolService,
        invoiceLineRepo,
        intercoService,
        stockMoveRepository);
    this.ocrRoundingLineService = ocrRoundingLineService;
  }

  @Override
  public void validate(Invoice invoice) throws AxelorException {
    // Either books the 0.01/0.02 correction on 609, or throws — in which case nothing is written
    // and the invoice stays Draft and editable, with a message naming both totals. The message the
    // user reads is theirs-language; the English diagnosis stays on the traceback as the cause.
    try {
      ocrRoundingLineService.reconcileBeforeValidation(invoice);
    } catch (OcrDocumentRefusal refusal) {
      throw refusal.forUser(invoice);
    }
    super.validate(invoice);
  }

  @Override
  public void checkPreconditions(Invoice invoice) throws AxelorException {
    super.checkPreconditions(invoice);
    try {
      ocrRoundingLineService.checkBeforeVentilation(invoice);
    } catch (OcrDocumentRefusal refusal) {
      throw refusal.forUser(invoice);
    }
  }
}
