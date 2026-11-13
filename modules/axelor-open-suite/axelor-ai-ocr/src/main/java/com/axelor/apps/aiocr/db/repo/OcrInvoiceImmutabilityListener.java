package com.axelor.apps.aiocr.db.repo;

import com.axelor.apps.account.db.Invoice;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces OCR document-total immutability at the Hibernate flush layer, so it holds on EVERY write
 * path — the form Save, the REST API, a service's {@code repository.save}, a direct {@code
 * JPA.save}, and a plain managed-entity change flushed at commit with no save call at all.
 *
 * <p>Outside a {@linkplain OcrInvoiceWriteGuard#isTrusted trusted} OCR write it reverts each
 * protected column to its stored value on update, and clears it on insert, by rewriting the
 * Hibernate state array that is about to be persisted. A save that merely round-trips the readonly
 * fields is a no-op (the values are unchanged); only a genuine, unauthorised change is undone, and
 * it is logged.
 *
 * <p>The two authorised flows — intake and {@link
 * com.axelor.apps.aiocr.service.record.OcrDocumentTotalCorrectionService} — run inside the trusted
 * marker and flush there, so their event fires while the marker is set and is left alone. JPQL bulk
 * update fires no event and is handled by {@link OcrInvoiceRequestGuard}.
 *
 * <p>Registered by {@link OcrInvoiceIntegrator}.
 */
public class OcrInvoiceImmutabilityListener
    implements PreUpdateEventListener, PreInsertEventListener {

  private static final long serialVersionUID = 1L;

  private static final Logger LOG = LoggerFactory.getLogger(OcrInvoiceImmutabilityListener.class);

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    if (!(event.getEntity() instanceof Invoice) || OcrInvoiceWriteGuard.isTrusted()) {
      return false;
    }
    Object[] oldState = event.getOldState();
    if (oldState == null) {
      // No loaded state to compare against. Clearing to null here would be wrong (it could wipe a
      // legitimately stored value), so refuse the whole update rather than guess.
      throw new IllegalStateException(
          "Cannot verify the OCR document-total fields of invoice "
              + ((Invoice) event.getEntity()).getId()
              + ": no previous state was loaded.");
    }
    String[] names = event.getPersister().getPropertyNames();
    boolean reverted =
        OcrInvoiceWriteGuard.revertProtectedForUpdate(names, event.getState(), oldState);
    if (reverted) {
      syncEntity((Invoice) event.getEntity(), names, event.getState());
      warn(((Invoice) event.getEntity()).getId());
    }
    return false;
  }

  @Override
  public boolean onPreInsert(PreInsertEvent event) {
    if (!(event.getEntity() instanceof Invoice) || OcrInvoiceWriteGuard.isTrusted()) {
      return false;
    }
    String[] names = event.getPersister().getPropertyNames();
    boolean cleared = OcrInvoiceWriteGuard.clearProtectedForInsert(names, event.getState());
    if (cleared) {
      syncEntity((Invoice) event.getEntity(), names, event.getState());
      warn(((Invoice) event.getEntity()).getId());
    }
    return false;
  }

  /**
   * Copies the (now-corrected) state array back onto the entity so its in-memory value matches what
   * is written, and a later flush in the same session cannot re-detect it as dirty.
   */
  private static void syncEntity(Invoice invoice, String[] names, Object[] state) {
    for (int i = 0; i < names.length; i++) {
      switch (names[i]) {
        case "ocrDocumentTotal":
          invoice.setOcrDocumentTotal(bd(state[i]));
          break;
        case "ocrDocumentNetTotal":
          invoice.setOcrDocumentNetTotal(bd(state[i]));
          break;
        case "ocrDocumentVatTotal":
          invoice.setOcrDocumentVatTotal(bd(state[i]));
          break;
        case "ocrDocumentBreakdownAvailable":
          invoice.setOcrDocumentBreakdownAvailable((Boolean) state[i]);
          break;
        case "ocrDocumentTotalCorrected":
          invoice.setOcrDocumentTotalCorrected(bd(state[i]));
          break;
        case "ocrDocumentNetCorrected":
          invoice.setOcrDocumentNetCorrected(bd(state[i]));
          break;
        case "ocrDocumentVatCorrected":
          invoice.setOcrDocumentVatCorrected(bd(state[i]));
          break;
        case "ocrDocumentTotalCorrectedBy":
          invoice.setOcrDocumentTotalCorrectedBy((com.axelor.auth.db.User) state[i]);
          break;
        case "ocrDocumentTotalCorrectedOn":
          invoice.setOcrDocumentTotalCorrectedOn((java.time.LocalDateTime) state[i]);
          break;
        case "ocrDocumentTotalCorrectionReason":
          invoice.setOcrDocumentTotalCorrectionReason((String) state[i]);
          break;
        default:
          break;
      }
    }
  }

  private static java.math.BigDecimal bd(Object v) {
    return (java.math.BigDecimal) v;
  }

  private static void warn(Long id) {
    LOG.warn(
        "Rejected an untrusted write to the protected OCR document-total fields on invoice {};"
            + " these change only through OcrDocumentTotalCorrectionService (or OCR intake).",
        id);
  }
}
