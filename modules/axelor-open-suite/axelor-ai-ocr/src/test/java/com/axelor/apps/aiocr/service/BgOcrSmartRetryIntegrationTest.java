package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.BgOcrIntakeService.RetryMode;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof of the smart-retry decision ({@link BgOcrIntakeService#decideRetryMode}) and its
 * atomic queuing guards ({@link BgOcrIntakeService#claimForReextract}/{@link
 * BgOcrIntakeService#claimForLocalReprocess}) — the single logic BOTH the per-record Retry button
 * and "Retry all eligible" call through {@link BgOcrIntakeService#retryOne}.
 *
 * <p>Kept at the decision/claim level, not the full async pipeline: the background executor is real
 * threads and a real Azure call is out of scope for an HSQLDB test, exactly as {@link
 * BgOcrRetryReprocessFieldPreservationIntegrationTest} and {@link
 * BgOcrReprocessLifecycleIntegrationTest} already reason for {@code retry()}/{@code reprocess()}.
 */
class BgOcrSmartRetryIntegrationTest extends AiOcrTest {

  private BgOcrIntakeService svc() {
    return Beans.get(
        BgOcrRetryReprocessFieldPreservationIntegrationTest.TestableIntakeService.class);
  }

  private AiOcrUploadRepository uploads() {
    return Beans.get(AiOcrUploadRepository.class);
  }

  private Company company(String code) {
    Company c =
        Beans.get(CompanyRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", code)
            .fetchOne();
    if (c == null) {
      c = new Company();
      c.setName(code);
      c.setCode(code);
      c = JPA.save(c);
    }
    return c;
  }

  /** A MetaFile whose bytes genuinely exist under the configured upload dir. */
  private MetaFile realFile(String name) throws IOException {
    String relName = name + "-" + System.nanoTime() + ".pdf";
    Path tmp = MetaFiles.getPath(relName);
    Files.createDirectories(tmp.getParent());
    Files.write(tmp, new byte[] {1, 2, 3});
    MetaFile mf = new MetaFile();
    mf.setFileName(relName);
    mf.setFilePath(relName);
    return JPA.save(mf);
  }

  private AiOcrUpload upload(Company c, String status, MetaFile originalFile) {
    AiOcrUpload up = new AiOcrUpload();
    up.setCompany(c);
    up.setStatusSelect(status);
    up.setOriginalFile(originalFile);
    return JPA.save(up);
  }

  @Test
  void noStoredResultChoosesReextraction() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-A");
            AiOcrUpload up = upload(c, AiOcrUploadStatus.FAILED, realFile("a"));
            assertEquals(RetryMode.REEXTRACT, svc().decideRetryMode(up));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void storedResultWithDownstreamFailureChoosesLocalReprocess() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-B");
            AiOcrUpload up = upload(c, AiOcrUploadStatus.FAILED, realFile("b"));
            up.setOcrResultJson("{\"analyzeResult\":{}}");
            up = JPA.save(up);
            assertEquals(RetryMode.REPROCESS, svc().decideRetryMode(up));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void missingSupplierWithStoredResultChoosesLocalReprocessNeverAzure() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-C");
            AiOcrUpload up = upload(c, AiOcrUploadStatus.NEEDS_REVIEW, realFile("c"));
            up.setOcrResultJson("{\"analyzeResult\":{}}");
            up.setExtractionErrors(
                "Supplier with EIK 831641791 was not found; no partner was created automatically.");
            up = JPA.save(up);
            // The message text is irrelevant to the decision — only the stored result matters, so a
            // missing supplier never triggers a fresh Azure call.
            assertEquals(RetryMode.REPROCESS, svc().decideRetryMode(up));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void queuedOrProcessingIsNotEligible() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-D");
            AiOcrUpload queued = upload(c, AiOcrUploadStatus.QUEUED, realFile("d1"));
            AiOcrUpload processing = upload(c, AiOcrUploadStatus.PROCESSING, realFile("d2"));
            assertEquals(RetryMode.NOT_ELIGIBLE, svc().decideRetryMode(queued));
            assertEquals(RetryMode.NOT_ELIGIBLE, svc().decideRetryMode(processing));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void cleanCompletedIsNotEligible() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-E");
            AiOcrUpload done = upload(c, AiOcrUploadStatus.COMPLETED, realFile("e"));
            assertEquals(RetryMode.NOT_ELIGIBLE, svc().decideRetryMode(done));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void missingOriginalFileIsNotEligible() {
    inTransaction(
        () -> {
          Company c = company("SR-F");
          AiOcrUpload up = upload(c, AiOcrUploadStatus.FAILED, null);
          assertEquals(RetryMode.NOT_ELIGIBLE, svc().decideRetryMode(up));
        });
  }

  @Test
  void concurrentReextractClaimsSucceedOnlyOnce() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-G");
            AiOcrUpload up = upload(c, AiOcrUploadStatus.FAILED, realFile("g"));
            long id = up.getId();
            assertTrue(svc().claimForReextract(id), "first claim wins the race");
            assertFalse(svc().claimForReextract(id), "second (racing) claim must lose");
            // The bulk UPDATE bypasses the persistence context, so the entity already cached by
            // JPA.save() above must be refreshed to see it, exactly like AiOcrUploadAuditService's
            // own reasoning for the production claim()/markFailed() bulk updates.
            AiOcrUpload after = uploads().find(id);
            JPA.em().refresh(after);
            assertEquals(AiOcrUploadStatus.QUEUED, after.getStatusSelect());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void concurrentLocalReprocessClaimsSucceedOnlyOnce() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-H");
            AiOcrUpload up = upload(c, AiOcrUploadStatus.NEEDS_REVIEW, realFile("h"));
            up.setOcrResultJson("{\"analyzeResult\":{}}");
            up = JPA.save(up);
            long id = up.getId();
            assertTrue(svc().claimForLocalReprocess(id), "first claim wins the race");
            assertFalse(svc().claimForLocalReprocess(id), "second (racing) claim must lose");
            AiOcrUpload after = uploads().find(id);
            JPA.em().refresh(after);
            assertEquals(AiOcrUploadStatus.PROCESSING, after.getStatusSelect());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void retryIsRestrictedToInternalUsers() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-I");
            AiOcrUpload up = upload(c, AiOcrUploadStatus.FAILED, realFile("i"));
            long id = up.getId();
            // The real (non-overridden) service: AuthUtils.getUser() is null in this harness, so
            // isInternalUser() is false exactly like an anonymous/non-internal caller.
            assertThrows(
                IllegalStateException.class,
                () -> Beans.get(BgOcrIntakeService.class).retryOne(id));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void decisionTalliesMatchForAMixedBatch() {
    inTransaction(
        () -> {
          try {
            Company c = company("SR-J");
            AiOcrUpload reextract = upload(c, AiOcrUploadStatus.FAILED, realFile("j1"));
            AiOcrUpload reprocess1 = upload(c, AiOcrUploadStatus.FAILED, realFile("j2"));
            reprocess1.setOcrResultJson("{\"analyzeResult\":{}}");
            reprocess1 = JPA.save(reprocess1);
            AiOcrUpload reprocess2 = upload(c, AiOcrUploadStatus.NEEDS_REVIEW, realFile("j3"));
            reprocess2.setOcrResultJson("{\"analyzeResult\":{}}");
            reprocess2 = JPA.save(reprocess2);
            AiOcrUpload notEligible1 = upload(c, AiOcrUploadStatus.QUEUED, realFile("j4"));
            AiOcrUpload notEligible2 = upload(c, AiOcrUploadStatus.COMPLETED, realFile("j5"));

            Map<RetryMode, Integer> tally = new EnumMap<>(RetryMode.class);
            for (RetryMode m : RetryMode.values()) {
              tally.put(m, 0);
            }
            for (AiOcrUpload u :
                new AiOcrUpload[] {reextract, reprocess1, reprocess2, notEligible1, notEligible2}) {
              RetryMode m = svc().decideRetryMode(u);
              tally.put(m, tally.get(m) + 1);
            }
            assertEquals(1, tally.get(RetryMode.REEXTRACT));
            assertEquals(2, tally.get(RetryMode.REPROCESS));
            assertEquals(2, tally.get(RetryMode.NOT_ELIGIBLE));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
