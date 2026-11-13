package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationService;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.fixedasset.FixedAssetGenerationService;
import com.axelor.apps.account.service.invoice.InvoiceJournalService;
import com.axelor.apps.account.service.invoice.InvoicePfpValidateService;
import com.axelor.apps.account.service.invoice.InvoiceTermPfpToolService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateAdvancePaymentState;
import com.axelor.apps.account.service.invoice.workflow.ventilate.WorkflowVentilationService;
import com.axelor.apps.account.service.move.MoveCreateFromInvoiceService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.user.UserService;
import javax.inject.Inject;

/**
 * Авансовата фактура на клиент: AOS я номерира и я оставя „валидирана“ без статия; тук получава и
 * ДДС статията си (Дт 412 / Кт 4532), иначе ДДС-ът ѝ не стига до дневника за продажби (ЗДДС чл. 25
 * ал. 7, чл. 124 ал. 2). Виж {@link BgVatAdvanceService#createVatMove}.
 */
public class BgVentilateAdvancePaymentState extends VentilateAdvancePaymentState {

  protected final BgVatAdvanceService advanceService;

  @Inject
  public BgVentilateAdvancePaymentState(
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
        invoiceTermPfpToolService);
    this.advanceService = advanceService;
  }

  @Override
  public void process() throws AxelorException {
    advanceService.requirePaymentMoves(invoice);
    super.process();
    advanceService.createVatMove(invoice);
  }
}
