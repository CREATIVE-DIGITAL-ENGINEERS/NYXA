package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationService;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.fixedasset.FixedAssetGenerationService;
import com.axelor.apps.account.service.invoice.AdvancePaymentRefundService;
import com.axelor.apps.account.service.invoice.InvoiceJournalService;
import com.axelor.apps.account.service.invoice.InvoicePfpValidateService;
import com.axelor.apps.account.service.invoice.InvoiceTermPfpToolService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateAdvancePaymentRefundState;
import com.axelor.apps.account.service.invoice.workflow.ventilate.WorkflowVentilationService;
import com.axelor.apps.account.service.move.MoveCreateFromInvoiceService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.user.UserService;
import javax.inject.Inject;

/**
 * Кредитно известие към авансова фактура: обратната ДДС статия (Дт 4532 / Кт 412) — КИ влиза в
 * дневника за продажби за периода на издаването си. Виж {@link BgVatAdvanceService#createVatMove}.
 */
public class BgVentilateAdvancePaymentRefundState extends VentilateAdvancePaymentRefundState {

  protected final BgVatAdvanceService advanceService;

  @Inject
  public BgVentilateAdvancePaymentRefundState(
      SequenceService sequenceService,
      MoveCreateFromInvoiceService moveCreateFromInvoiceService,
      AccountConfigService accountConfigService,
      AppAccountService appAccountService,
      InvoiceRepository invoiceRepo,
      WorkflowVentilationService workflowService,
      UserService userService,
      FixedAssetGenerationService fixedAssetGenerationService,
      InvoiceTermService invoiceTermService,
      AccountingSituationService accountingSituationService,
      InvoiceJournalService invoiceJournalService,
      InvoicePfpValidateService invoicePfpValidateService,
      InvoiceTermPfpToolService invoiceTermPfpToolService,
      AdvancePaymentRefundService advancePaymentRefundService,
      BgVatAdvanceService advanceService) {
    super(
        sequenceService,
        moveCreateFromInvoiceService,
        accountConfigService,
        appAccountService,
        invoiceRepo,
        workflowService,
        userService,
        fixedAssetGenerationService,
        invoiceTermService,
        accountingSituationService,
        invoiceJournalService,
        invoicePfpValidateService,
        invoiceTermPfpToolService,
        advancePaymentRefundService);
    this.advanceService = advanceService;
  }

  @Override
  public void process() throws AxelorException {
    advanceService.requirePaymentMoves(invoice);
    super.process();
    advanceService.createVatMove(invoice);
  }
}
