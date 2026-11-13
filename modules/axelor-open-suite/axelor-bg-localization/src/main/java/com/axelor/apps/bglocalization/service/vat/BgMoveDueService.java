package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.move.MoveDueService;
import com.axelor.apps.account.service.moveline.MoveLineToolService;
import com.axelor.apps.base.AxelorException;
import java.util.List;
import javax.inject.Inject;

/**
 * Авансът, приспаднат с ред в окончателната фактура от доставчик, не се приспада втори път като
 * плащане — огледалото на {@link BgMoveExcessPaymentService} за покупките.
 *
 * <p>При покупка AOS не търси „надплатеното“, а „дължимото“ ({@code getInvoiceDue}) — вкл.
 * плащанията на закачените авансови фактури (Дт 402) — и прави О.Д. Дт 401 / Кт 402. Когато авансът
 * е приспаднат с реда (Кт 402 в статията на окончателната), О.Д. би намалила задължението и 402
 * втори път. Тук плащанията на приспаднатите с ред авансови изпадат; 402 се лентира от {@link
 * BgVatAdvanceService#reconcile}.
 */
public class BgMoveDueService extends MoveDueService {

  protected final BgVatAdvanceService advanceService;

  @Inject
  public BgMoveDueService(
      InvoiceService invoiceService,
      MoveLineToolService moveLineToolService,
      BgVatAdvanceService advanceService) {
    super(invoiceService, moveLineToolService);
    this.advanceService = advanceService;
  }

  @Override
  public List<MoveLine> getInvoiceDue(Invoice invoice, boolean useOthersInvoiceDue)
      throws AxelorException {
    return BgMoveExcessPaymentService.withoutDeducted(
        super.getInvoiceDue(invoice, useOthersInvoiceDue),
        advanceService.deductedAdvancePaymentMoves(invoice));
  }
}
