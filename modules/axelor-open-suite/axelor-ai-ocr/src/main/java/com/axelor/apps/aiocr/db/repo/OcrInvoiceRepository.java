package com.axelor.apps.aiocr.db.repo;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.budget.db.repo.BudgetInvoiceRepository;
import com.axelor.apps.businessproject.service.app.AppBusinessProjectService;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import java.util.List;

/**
 * Extends the tail of the Invoice repository binding chain ({@code InvoiceRepository → … →
 * BudgetInvoiceRepository}) to govern deleting a supplier invoice from the <b>Suppl. Invoices</b>
 * module (and every other Invoice delete path — REST, bulk, cascade). Rules applied to EVERY
 * invoice delete:
 *
 * <ul>
 *   <li><b>Ventilation hard stop</b> — an invoice that was ever ventilated can never be deleted
 *       (applies to all invoices, strengthening the stock protection).
 *   <li><b>Only this module is affected</b> — any linked {@link
 *       com.axelor.apps.aiocr.db.AiOcrUpload} is merely unlinked ({@code invoice = null}); the
 *       upload/files are NOT deleted here.
 * </ul>
 *
 * <p>For an <b>OCR-generated</b> draft/validated invoice (only) it additionally auto-cancels via
 * the stock {@code InvoiceService.cancel} business service (guarded against accounting
 * dependencies) so it can be discarded in one step. NON-OCR invoices keep the stock behaviour
 * unchanged: they are only removable once CANCELED (the guard in {@code super.remove}). Stock
 * accounting protections are never weakened.
 */
public class OcrInvoiceRepository extends BudgetInvoiceRepository {

  @Inject
  public OcrInvoiceRepository(AppBusinessProjectService appBusinessProjectService) {
    super(appBusinessProjectService);
  }

  // No save() override: OCR document-total immutability is enforced at the Hibernate flush layer
  // (OcrInvoiceImmutabilityListener), which catches every write path — repository.save, JPA.save,
  // REST, and a plain managed-entity change flushed with no save call. A repository guard would
  // miss
  // all but the first.

  @Override
  public void remove(Invoice entity) {
    if (entity == null) {
      super.remove(entity);
      return;
    }
    OcrUploadFileCleanupService svc = Beans.get(OcrUploadFileCleanupService.class);
    svc.assertNotVentilated(List.of(entity)); // never delete a ventilated invoice (any path)
    svc.unlinkUploads(entity); // keep the uploads; just clear the FK
    if (Boolean.TRUE.equals(entity.getAiGenerated())) {
      // OCR draft/validated: cancel safely via stock so a discarded OCR invoice deletes in one
      // step.
      svc.autoCancelIfSafe(entity);
    }
    super.remove(entity); // stock canceled-only guard + supplychain/budget cleanup
  }
}
