package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.moveline.MoveLineToolService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Окончателна фактура от доставчик: AOS приспада аванса през „дължимото“ ({@code getInvoiceDue}) —
 * плащането на приспаднатия с ред аванс изпада, О.Д. Дт 401 / Кт 402 за него не се прави.
 */
class BgMoveDueServiceTest {

  @Test
  @DisplayName("Плащането на приспаднатия аванс изпада от дължимото, другото остава")
  void приспаднатият_изпада() throws Exception {
    Move deductedPayment = new Move();
    Move otherPayment = new Move();
    MoveLine deducted = new MoveLine();
    deducted.setMove(deductedPayment);
    MoveLine other = new MoveLine();
    other.setMove(otherPayment);
    Invoice invoice = new Invoice();

    InvoiceService invoiceService = mock(InvoiceService.class);
    when(invoiceService.getMoveLinesFromAdvancePayments(invoice))
        .thenReturn(new ArrayList<>(List.of(deducted, other)));
    BgVatAdvanceService advanceService = mock(BgVatAdvanceService.class);
    when(advanceService.deductedAdvancePaymentMoves(invoice)).thenReturn(Set.of(deductedPayment));

    BgMoveDueService service =
        new BgMoveDueService(invoiceService, mock(MoveLineToolService.class), advanceService);

    assertEquals(List.of(other), service.getInvoiceDue(invoice, false));
  }
}
