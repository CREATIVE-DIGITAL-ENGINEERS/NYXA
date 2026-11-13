package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.utils.ThrowConsumer;
import com.axelor.utils.helpers.ModelHelper;
import com.google.inject.Singleton;
import java.util.List;

/**
 * Mass "Cancel selected invoices" action for the supplier invoice/refund grids (Tools menu).
 * Mirrors the stock mass validate/ventilate mechanism: each invoice is cancelled in its own
 * transaction via {@link ModelHelper#apply} so one failure never rolls back the others.
 * Already-cancelled invoices are skipped. Reuses the standard {@link InvoiceService#cancel}
 * workflow (no accounting shortcuts).
 */
@Singleton
public class BgInvoiceMassController {

  public void cancelSelected(ActionRequest request, ActionResponse response) {
    try {
      @SuppressWarnings("unchecked")
      List<Number> ids = (List<Number>) request.getContext().get("_ids");
      if (ids == null || ids.isEmpty()) {
        response.setError(I18n.get("Please select at least one invoice to cancel."));
        return;
      }
      // G10: the ids come from the client and Axelor only checked READ. Cancelling is a
      // write, and the write permission's company condition also confines it to the active company.
      // All ids are checked before any is cancelled, so a refusal cancels nothing.
      for (Number id : ids) {
        CapabilityGate.requireWrite(Invoice.class, id.longValue());
      }

      InvoiceService invoiceService = Beans.get(InvoiceService.class);
      int[] cancelled = {0};
      int[] skipped = {0};

      int errors =
          ModelHelper.apply(
              Invoice.class,
              ids,
              new ThrowConsumer<Invoice, Exception>() {
                @Override
                public void accept(Invoice invoice) throws Exception {
                  if (invoice.getStatusSelect() == InvoiceRepository.STATUS_CANCELED) {
                    skipped[0]++;
                    return; // already cancelled — nothing to do
                  }
                  invoiceService.cancel(invoice);
                  cancelled[0]++;
                }
              });

      response.setInfo(
          I18n.get(
              String.format(
                  "Cancelled %d invoice(s). Already cancelled: %d. Failed: %d.",
                  cancelled[0], skipped[0], errors)));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    } finally {
      response.setReload(true);
    }
  }
}
