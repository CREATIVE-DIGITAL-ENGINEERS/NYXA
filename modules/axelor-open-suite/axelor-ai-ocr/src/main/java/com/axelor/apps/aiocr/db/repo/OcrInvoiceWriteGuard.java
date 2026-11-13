package com.axelor.apps.aiocr.db.repo;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The authority on which Invoice columns are OCR-protected, and the thread-local marker that lets
 * the two authorised flows write them.
 *
 * <h2>Why the repository was not enough</h2>
 *
 * An earlier version guarded {@code OcrInvoiceRepository.save}. But Hibernate persists a managed
 * entity's changes on <b>flush</b>, with no {@code repository.save} call at all: load an invoice,
 * change {@code ocrDocumentTotalCorrected}, let the transaction commit — the column is written and
 * the repository guard never runs. {@code JPA.save} (an {@code em.merge}) and the REST {@code
 * Resource.save} flush the same way. So enforcement has to sit at the flush layer, in {@link
 * OcrInvoiceImmutabilityListener} (a Hibernate {@code PreInsert}/{@code PreUpdate} listener), which
 * sees every one of those paths.
 *
 * <p>JPQL bulk {@code UPDATE} does not load rows and fires no event, so the listener cannot see it;
 * the reachable bulk path — the mass-update REST endpoint — is refused earlier by {@link
 * OcrInvoiceRequestGuard}. A hand-written {@code em.createQuery("update Invoice
 * …").executeUpdate()} in server code bypasses both and is a developer-only path with no user route
 * to it.
 *
 * <h2>The marker</h2>
 *
 * The protected columns may change only inside {@link #runTrusted}: the OCR intake (which first
 * writes the extracted figures) and {@link
 * com.axelor.apps.aiocr.service.record.OcrDocumentTotalCorrectionService} (the correction + audit).
 * Both must <b>flush inside the scope</b> — the marker is thread-local and the dirty flush would
 * otherwise happen at commit, after the scope has closed, and the listener would revert the very
 * write they intended.
 */
public final class OcrInvoiceWriteGuard {

  /** The OCR document-total / correction / audit columns, immutable outside a trusted write. */
  public static final Set<String> PROTECTED_FIELDS =
      java.util.Collections.unmodifiableSet(
          new LinkedHashSet<>(
              Arrays.asList(
                  "ocrDocumentTotal",
                  "ocrDocumentNetTotal",
                  "ocrDocumentVatTotal",
                  "ocrDocumentBreakdownAvailable",
                  "ocrDocumentTotalCorrected",
                  "ocrDocumentNetCorrected",
                  "ocrDocumentVatCorrected",
                  "ocrDocumentTotalCorrectedBy",
                  "ocrDocumentTotalCorrectedOn",
                  "ocrDocumentTotalCorrectionReason")));

  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  private OcrInvoiceWriteGuard() {}

  /** True while a trusted OCR write is in progress on this thread. */
  public static boolean isTrusted() {
    return DEPTH.get() > 0;
  }

  /**
   * Runs {@code action} as a trusted OCR write. The caller MUST flush inside {@code action} (e.g.
   * {@code JPA.em().flush()} after the save) so the Hibernate event fires while the marker is set.
   */
  public static void runTrusted(Runnable action) {
    enter();
    try {
      action.run();
    } finally {
      exit();
    }
  }

  /** As {@link #runTrusted(Runnable)}, for a write that returns a value. */
  public static <T> T callTrusted(Supplier<T> action) {
    enter();
    try {
      return action.get();
    } finally {
      exit();
    }
  }

  private static void enter() {
    DEPTH.set(DEPTH.get() + 1);
  }

  private static void exit() {
    int next = DEPTH.get() - 1;
    if (next <= 0) {
      DEPTH.remove();
    } else {
      DEPTH.set(next);
    }
  }

  /**
   * Clears every protected column in an insert's state array (an untrusted creation can carry none
   * of them). Pure, operating on the Hibernate state array, so it is unit-testable without
   * persistence.
   *
   * @return true when at least one value was cleared, for logging
   */
  public static boolean clearProtectedForInsert(String[] propertyNames, Object[] state) {
    boolean changed = false;
    for (int i = 0; i < propertyNames.length; i++) {
      if (PROTECTED_FIELDS.contains(propertyNames[i]) && state[i] != null) {
        state[i] = null;
        changed = true;
      }
    }
    return changed;
  }

  /**
   * Reverts every protected column in an update's state array to its stored (old) value, so an
   * untrusted update cannot change it. Pure, for unit testing.
   *
   * @return true when at least one value was reverted, for logging
   */
  public static boolean revertProtectedForUpdate(
      String[] propertyNames, Object[] state, Object[] oldState) {
    boolean changed = false;
    for (int i = 0; i < propertyNames.length; i++) {
      if (PROTECTED_FIELDS.contains(propertyNames[i]) && !Objects.equals(state[i], oldState[i])) {
        state[i] = oldState[i];
        changed = true;
      }
    }
    return changed;
  }
}
