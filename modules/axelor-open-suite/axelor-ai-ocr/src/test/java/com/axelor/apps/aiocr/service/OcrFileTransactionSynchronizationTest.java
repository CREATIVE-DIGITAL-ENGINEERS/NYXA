package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.transaction.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the transaction-outcome → filesystem bridge: commit permanently deletes quarantined blobs;
 * rollback restores them; failures are contained (never rethrown from afterCompletion).
 */
class OcrFileTransactionSynchronizationTest {

  private QuarantinedFile quarantine(Path dir, String name) throws IOException {
    Path original = dir.resolve("upload/" + name);
    Files.createDirectories(original.getParent());
    Path q = dir.resolve("q/" + name);
    Files.createDirectories(q.getParent());
    Files.writeString(q, "PDF"); // already moved into quarantine
    return new QuarantinedFile(original, q, 1L);
  }

  @Test
  void commitPermanentlyDeletesQuarantinedBlobs(@TempDir Path dir) throws IOException {
    QuarantinedFile qf = quarantine(dir, "a");
    OcrFileTransactionSynchronization sync =
        new OcrFileTransactionSynchronization(List.of(qf), new OcrFileQuarantineService());

    sync.afterCompletion(Status.STATUS_COMMITTED);

    assertFalse(Files.exists(qf.getQuarantine()), "quarantined blob deleted on commit");
    assertFalse(Files.exists(qf.getOriginal()), "not restored on commit");
  }

  @Test
  void rollbackRestoresOriginalFile(@TempDir Path dir) throws IOException {
    QuarantinedFile qf = quarantine(dir, "a");
    OcrFileTransactionSynchronization sync =
        new OcrFileTransactionSynchronization(List.of(qf), new OcrFileQuarantineService());

    sync.afterCompletion(Status.STATUS_ROLLEDBACK);

    assertTrue(Files.exists(qf.getOriginal()), "blob restored on rollback");
    assertFalse(Files.exists(qf.getQuarantine()), "removed from quarantine");
  }

  @Test
  void commitDeletionFailureLeavesFileInQuarantineAndDoesNotThrow(@TempDir Path dir)
      throws IOException {
    QuarantinedFile qf = quarantine(dir, "a");
    // A service whose permanent deletion always fails — the blob must stay for the sweeper.
    OcrFileQuarantineService failing =
        new OcrFileQuarantineService() {
          @Override
          public void permanentlyDelete(QuarantinedFile f) throws IOException {
            throw new IOException("simulated commit-delete failure");
          }
        };
    OcrFileTransactionSynchronization sync =
        new OcrFileTransactionSynchronization(List.of(qf), failing);

    assertDoesNotThrow(() -> sync.afterCompletion(Status.STATUS_COMMITTED));
    assertTrue(Files.exists(qf.getQuarantine()), "left in quarantine for retry sweep");
  }

  @Test
  void oneRestoreFailureDoesNotStopTheOthers(@TempDir Path dir) throws IOException {
    QuarantinedFile bad = quarantine(dir, "bad");
    QuarantinedFile good = quarantine(dir, "good");
    Files.delete(bad.getQuarantine()); // make restore of "bad" fail (source gone)
    OcrFileTransactionSynchronization sync =
        new OcrFileTransactionSynchronization(List.of(bad, good), new OcrFileQuarantineService());

    assertDoesNotThrow(() -> sync.afterCompletion(Status.STATUS_ROLLEDBACK));
    assertTrue(Files.exists(good.getOriginal()), "the good blob was still restored");
  }
}
