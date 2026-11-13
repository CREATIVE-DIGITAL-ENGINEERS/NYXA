package com.axelor.apps.aiocr.service;

import java.io.IOException;
import java.util.List;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the JPA transaction outcome to the physical filesystem for an OCR-invoice delete. Holds
 * the (shared, mutable) list of blobs quarantined during the transaction and, once the transaction
 * completes:
 *
 * <ul>
 *   <li><b>commit</b> → permanently delete each quarantined blob. A failure is logged and the blob
 *       is LEFT in quarantine (reclaimed later by the sweeper) — never rethrown (the transaction is
 *       already done).
 *   <li><b>rollback / unknown</b> → restore each blob to its original path. Restore failures are
 *       logged at ERROR (not swallowed silently): the DB row is back but the blob is stranded in
 *       quarantine, so it is surfaced loudly for the sweeper / manual recovery.
 * </ul>
 *
 * The list is shared with the caller by reference so blobs moved before a later failure are still
 * restored.
 */
public class OcrFileTransactionSynchronization implements Synchronization {

  private static final Logger LOG =
      LoggerFactory.getLogger(OcrFileTransactionSynchronization.class);

  private final List<QuarantinedFile> quarantined;
  private final OcrFileQuarantineService quarantineService;

  public OcrFileTransactionSynchronization(
      List<QuarantinedFile> quarantined, OcrFileQuarantineService quarantineService) {
    this.quarantined = quarantined;
    this.quarantineService = quarantineService;
  }

  @Override
  public void beforeCompletion() {
    // no-op: all filesystem effects happen after the outcome is known.
  }

  @Override
  public void afterCompletion(int status) {
    if (status == Status.STATUS_COMMITTED) {
      commit();
    } else {
      rollback();
    }
  }

  private void commit() {
    for (QuarantinedFile qf : quarantined) {
      try {
        quarantineService.permanentlyDelete(qf);
      } catch (IOException | RuntimeException e) {
        // Not fatal: the blob stays in quarantine and the sweeper reclaims it later.
        LOG.warn(
            "OCR delete committed but permanent deletion of quarantined blob failed; left in"
                + " quarantine for the sweeper: {}",
            qf,
            e);
      }
    }
  }

  private void rollback() {
    for (QuarantinedFile qf : quarantined) {
      try {
        quarantineService.restore(qf);
      } catch (IOException | RuntimeException e) {
        // Do NOT swallow: the DB row was rolled back but the blob is stranded in quarantine.
        LOG.error(
            "OCR delete rolled back but restoring the quarantined blob FAILED; the file is stranded"
                + " in quarantine and must be recovered: {}",
            qf,
            e);
      }
    }
  }
}
