package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

/**
 * Thrown to abandon a whole financial year.
 *
 * <p>Unchecked on purpose: it has to escape {@code JPA.runInTransaction(Runnable)}, whose
 * functional interface declares no checked exception, so that the transaction rolls back.
 *
 * <p>⚠️ This is where the accounting importer deliberately departs from the payroll one. Payroll
 * commits each employee independently and records the failures — acceptable there, because a
 * missing employee is visible and fixable. An accounting year is not divisible that way: committing
 * 400 of 763 entries leaves books that balance nowhere and a trial balance nobody can reconcile.
 * One bad record therefore takes the whole year with it.
 */
public class MicroinvestAccountingAbortException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MicroinvestAccountingAbortException(String message) {
    super(message);
  }

  public MicroinvestAccountingAbortException(String message, Throwable cause) {
    super(message, cause);
  }
}
