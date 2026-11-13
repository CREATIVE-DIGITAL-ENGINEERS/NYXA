package com.axelor.apps.aiocr.db.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The pure pieces of the flush-layer guard: the marker, and the state-array revert/clear that
 * {@link OcrInvoiceImmutabilityListener} applies to a Hibernate insert/update. The real persistence
 * behaviour is covered by {@code OcrInvoiceProtectedFieldPersistenceTest}.
 */
class OcrInvoiceWriteGuardTest {

  // A stand-in for the persister's property names: a few ordinary columns plus the protected ones.
  private static final String[] NAMES = {
    "partner",
    "ocrDocumentTotal",
    "exTaxTotal",
    "ocrDocumentTotalCorrected",
    "ocrDocumentNetCorrected",
    "ocrDocumentVatCorrected",
    "ocrDocumentTotalCorrectionReason"
  };

  private static Object[] state(Object... values) {
    return values;
  }

  @Test
  void anUntrustedUpdateIsRevertedFieldByFieldToTheStoredValues() {
    Object[] old =
        state("P", bd("80.75"), bd("72.27"), bd("86.73"), bd("72.27"), bd("14.46"), "from receipt");
    Object[] now = state("P2", bd("999"), bd("99.99"), bd("999"), bd("999"), bd("999"), "tampered");

    boolean changed = OcrInvoiceWriteGuard.revertProtectedForUpdate(NAMES, now, old);

    assertTrue(changed);
    // Ordinary columns are left as-is; only the protected ones snap back.
    assertEquals("P2", now[0]);
    assertEquals(0, ((BigDecimal) now[1]).compareTo(bd("80.75"))); // extracted total immutable
    assertEquals(0, ((BigDecimal) now[2]).compareTo(bd("99.99"))); // exTaxTotal untouched
    assertEquals(0, ((BigDecimal) now[3]).compareTo(bd("86.73"))); // correction immutable
    assertEquals(0, ((BigDecimal) now[4]).compareTo(bd("72.27")));
    assertEquals(0, ((BigDecimal) now[5]).compareTo(bd("14.46")));
    assertEquals("from receipt", now[6]);
  }

  @Test
  void anUntrustedUpdateThatTouchesNoProtectedFieldIsLeftAlone() {
    Object[] old = state("P", bd("80.75"), bd("72.27"), bd("86.73"), bd("72.27"), bd("14.46"), "n");
    Object[] now =
        state("P2", bd("80.75"), bd("55.00"), bd("86.73"), bd("72.27"), bd("14.46"), "n");

    boolean changed = OcrInvoiceWriteGuard.revertProtectedForUpdate(NAMES, now, old);

    assertFalse(changed); // only exTaxTotal moved, which is not protected
    assertEquals(0, ((BigDecimal) now[2]).compareTo(bd("55.00")));
  }

  @Test
  void anUntrustedInsertCannotCarryAnyProtectedValue() {
    Object[] now =
        state("P", bd("80.75"), bd("72.27"), bd("86.73"), bd("72.27"), bd("14.46"), "forged");

    boolean changed = OcrInvoiceWriteGuard.clearProtectedForInsert(NAMES, now);

    assertTrue(changed);
    assertEquals("P", now[0]); // partner kept
    assertNull(now[1]); // ocrDocumentTotal cleared
    assertEquals(0, ((BigDecimal) now[2]).compareTo(bd("72.27"))); // exTaxTotal kept
    assertNull(now[3]);
    assertNull(now[4]);
    assertNull(now[5]);
    assertNull(now[6]);
  }

  @Test
  void theTrustedMarkerIsOffByDefaultAndNests() {
    assertFalse(OcrInvoiceWriteGuard.isTrusted());
    OcrInvoiceWriteGuard.runTrusted(
        () -> {
          assertTrue(OcrInvoiceWriteGuard.isTrusted());
          String inner =
              OcrInvoiceWriteGuard.callTrusted(
                  () -> {
                    assertTrue(OcrInvoiceWriteGuard.isTrusted());
                    return "ok";
                  });
          assertEquals("ok", inner);
          assertTrue(OcrInvoiceWriteGuard.isTrusted());
        });
    assertFalse(OcrInvoiceWriteGuard.isTrusted());
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
