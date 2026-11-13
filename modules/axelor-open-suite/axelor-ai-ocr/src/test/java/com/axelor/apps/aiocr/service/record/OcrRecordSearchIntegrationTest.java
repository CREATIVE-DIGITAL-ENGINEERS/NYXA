package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** DB-backed proof of the search outcomes: not found, unique, ambiguous, bounded. */
class OcrRecordSearchIntegrationTest extends AiOcrTest {

  private OcrRecordSearchService svc() {
    return Beans.get(OcrRecordSearchService.class);
  }

  /**
   * The harness has no Shiro subject, so an explicit system context supplies the caller identity
   * the services demand — and it must name a company, because a scope naming none is refused rather
   * than treated as unrestricted.
   *
   * <p>The scope company is created here and handed to the body, so a test cannot accidentally file
   * its fixtures under one company while running scoped to another.
   */
  private void asSystem(String companyCode, Consumer<Company> body) {
    inTransaction(
        () -> {
          Company scope = company(companyCode);
          AiExecutionContext.runAsSystem(scope.getId(), "test", () -> body.accept(scope));
        });
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

  private Partner partner(String name, String eik) {
    Partner p = new Partner();
    p.setName(name);
    p.setFullName(name);
    p.setRegistrationCode(eik);
    p.setIsSupplier(true);
    return JPA.save(p);
  }

  private AiOcrUpload upload(String fileName, Company company, Invoice invoice, String status) {
    AiOcrUpload u = new AiOcrUpload();
    u.setFileName(fileName);
    u.setCompany(company);
    u.setInvoice(invoice);
    u.setStatusSelect(status);
    return JPA.save(u);
  }

  private Invoice invoice(Company company, Partner partner, String number) {
    Invoice i = new Invoice();
    i.setCompany(company);
    i.setPartner(partner);
    i.setSupplierInvoiceNb(number);
    i.setInvoiceDate(LocalDate.now().minusDays(1));
    i.setAiGenerated(Boolean.TRUE);
    i.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    i.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    return JPA.save(i);
  }

  @Test
  void searchByExactUploadIdReturnsSingle() {
    asSystem(
        "SRCH1",
        c -> {
          AiOcrUpload u = upload("alpha.pdf", c, null, AiOcrUploadStatus.COMPLETED);

          OcrRecordSearchService.SearchOutcome out =
              svc().search(new OcrRecordQuery().setUploadId(u.getId()));

          assertTrue(out.isUnique());
          assertEquals(u.getId(), out.single().getId());
          assertEquals(1L, out.getTotalMatches());
        });
  }

  @Test
  void searchByInvoiceIdAndNumberAndPartnerAndEik() {
    asSystem(
        "SRCH2",
        c -> {
          Partner p = partner("Demo EOOD", "831919536");
          Invoice inv = invoice(c, p, "INV-77");
          AiOcrUpload u = upload("beta.pdf", c, inv, AiOcrUploadStatus.NEEDS_REVIEW);

          assertEquals(
              u.getId(),
              svc().search(new OcrRecordQuery().setInvoiceId(inv.getId())).single().getId());
          assertEquals(
              u.getId(),
              svc().search(new OcrRecordQuery().setInvoiceNumber("inv-7")).single().getId());
          assertEquals(
              u.getId(),
              svc().search(new OcrRecordQuery().setPartnerName("demo")).single().getId());
          // BG prefix and spacing are normalised the same way the intake normalises them.
          assertEquals(
              u.getId(),
              svc().search(new OcrRecordQuery().setEik("BG 831 919 536")).single().getId());
        });
  }

  @Test
  void searchByStatusAndFileNameAndDateRange() {
    asSystem(
        "SRCH3",
        c -> {
          upload("gamma-report.pdf", c, null, AiOcrUploadStatus.FAILED);

          assertTrue(svc().search(new OcrRecordQuery().setFileName("gamma-report")).isUnique());
          assertTrue(
              svc()
                  .search(
                      new OcrRecordQuery()
                          .setStatusSelect(AiOcrUploadStatus.FAILED)
                          .setFileName("gamma-report"))
                  .isUnique());
          assertTrue(
              svc()
                  .search(
                      new OcrRecordQuery()
                          .setFileName("gamma-report")
                          .setCreatedFrom(LocalDate.now().minusDays(1))
                          .setCreatedTo(LocalDate.now()))
                  .isUnique());
          // A range that ends before today must not match.
          assertTrue(
              svc()
                  .search(
                      new OcrRecordQuery()
                          .setFileName("gamma-report")
                          .setCreatedTo(LocalDate.now().minusDays(5)))
                  .isNotFound());
        });
  }

  @Test
  void ambiguousSearchReturnsCandidatesAndNeverPicksOne() {
    asSystem(
        "SRCH4",
        c -> {
          upload("dup-invoice-a.pdf", c, null, AiOcrUploadStatus.COMPLETED);
          upload("dup-invoice-b.pdf", c, null, AiOcrUploadStatus.COMPLETED);
          upload("dup-invoice-c.pdf", c, null, AiOcrUploadStatus.COMPLETED);

          OcrRecordSearchService.SearchOutcome out =
              svc().search(new OcrRecordQuery().setFileName("dup-invoice"));

          assertFalse(out.isUnique());
          assertFalse(out.isNotFound());
          assertEquals(3, out.getRecords().size());
          assertEquals(3L, out.getTotalMatches());
          assertNull(out.single(), "an ambiguous search must not resolve to one record");
        });
  }

  @Test
  void resultsAreBoundedAndPageable() {
    asSystem(
        "SRCH5",
        c -> {
          for (int i = 0; i < 8; i++) {
            upload("bounded-" + i + ".pdf", c, null, AiOcrUploadStatus.COMPLETED);
          }

          OcrRecordSearchService.SearchOutcome first =
              svc().search(new OcrRecordQuery().setFileName("bounded-").setLimit(3));
          assertEquals(3, first.getRecords().size());
          assertEquals(8L, first.getTotalMatches());
          assertTrue(first.isTruncated());

          OcrRecordSearchService.SearchOutcome second =
              svc().search(new OcrRecordQuery().setFileName("bounded-").setLimit(3).setOffset(6));
          assertEquals(2, second.getRecords().size());
          assertFalse(second.isTruncated());
        });
  }

  @Test
  void notFoundIsAStructuredOutcomeNotAnError() {
    asSystem(
        "SRCH6",
        c -> {
          OcrRecordSearchService.SearchOutcome out =
              svc().search(new OcrRecordQuery().setFileName("no-such-file-anywhere"));
          assertTrue(out.isNotFound());
          assertEquals(0L, out.getTotalMatches());
        });
  }

  @Test
  void unfilteredSearchIsRefused() {
    // Refusing an empty query is what stops the model from dumping the whole table.
    inTransaction(
        () ->
            AiExecutionContext.runAsSystem(
                company("SRCH6C").getId(),
                "test",
                () -> {
                  assertThrows(
                      IllegalArgumentException.class, () -> svc().search(new OcrRecordQuery()));
                  assertThrows(IllegalArgumentException.class, () -> svc().search(null));
                }));
  }

  @Test
  void searchWithoutAnyCallerIdentityIsRefused() {
    // No Shiro subject and no system context: the search must not run at all.
    assertNull(com.axelor.auth.AuthUtils.getUser());
    assertThrows(
        IllegalStateException.class, () -> svc().search(new OcrRecordQuery().setUploadId(1L)));
  }

  @Test
  void systemContextIsConfinedToItsDeclaredCompany() {
    // A system caller names one company and cannot range beyond it, even though it has no user —
    // and a system caller that names NO company is refused rather than treated as unrestricted,
    // which is the whole point: "internal" is not a synonym for "everywhere".
    inTransaction(
        () -> {
          Company a = company("SRCH6A");
          Company b = company("SRCH6B");
          upload("scoped.pdf", a, null, AiOcrUploadStatus.COMPLETED);
          upload("scoped.pdf", b, null, AiOcrUploadStatus.COMPLETED);

          int seenByA =
              AiExecutionContext.callAsSystem(
                  a.getId(),
                  "test",
                  () ->
                      svc().search(new OcrRecordQuery().setFileName("scoped")).getRecords().size());
          assertEquals(1, seenByA, "a system caller must not see another company's records");

          assertThrows(
              AiCompanyScope.ScopeUnavailableException.class,
              () ->
                  AiExecutionContext.callAsSystem(
                      null, "test", () -> svc().search(new OcrRecordQuery().setFileName("scoped"))),
              "a system context with no company must be refused, not treated as global");
        });
  }

  @Test
  void companyFilterIsolatesRecords() {
    asSystem(
        "SRCH7A",
        a -> {
          Company b = company("SRCH7B");
          AiOcrUpload mine = upload("filtered.pdf", a, null, AiOcrUploadStatus.COMPLETED);
          AiOcrUpload theirs = upload("filtered.pdf", b, null, AiOcrUploadStatus.COMPLETED);

          // Two identically named documents in two companies; the scoped caller sees exactly one.
          // No parameter was needed to get this — isolation is not something a caller opts into.
          List<AiOcrUpload> found =
              svc().search(new OcrRecordQuery().setFileName("filtered")).getRecords();
          assertEquals(1, found.size());
          assertEquals(mine.getId(), found.get(0).getId());

          // And the other company's record is unreachable even by its exact id.
          assertTrue(
              svc().search(new OcrRecordQuery().setUploadId(theirs.getId())).isNotFound(),
              "knowing the id of another company's record must not be enough to read it");
        });
  }

  @Test
  void aCompanyLessRecordIsNotThisCompanysData() {
    asSystem(
        "SRCH9",
        c -> {
          AiOcrUpload orphan = upload("no-company.pdf", null, null, AiOcrUploadStatus.COMPLETED);

          assertTrue(
              svc().search(new OcrRecordQuery().setFileName("no-company")).isNotFound(),
              "a row with no company belongs to no company, so it is not visible here");
          assertTrue(svc().search(new OcrRecordQuery().setUploadId(orphan.getId())).isNotFound());
        });
  }

  @Test
  void carrierChildrenCanBeListedByImportArchive() {
    asSystem(
        "SRCH8",
        c -> {
          AiOcrUpload carrier = upload("carrier.pdf", c, null, AiOcrUploadStatus.ARCHIVE);
          carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          JPA.save(carrier);

          for (int i = 0; i < 2; i++) {
            AiOcrUpload child = upload("child-" + i + ".pdf", c, null, AiOcrUploadStatus.COMPLETED);
            child.setImportArchive(carrier);
            child.setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE);
            JPA.save(child);
          }

          assertEquals(
              2,
              svc()
                  .search(new OcrRecordQuery().setImportArchiveId(carrier.getId()))
                  .getRecords()
                  .size());
          assertEquals(
              2,
              svc()
                  .search(
                      new OcrRecordQuery()
                          .setImportArchiveId(carrier.getId())
                          .setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE))
                  .getRecords()
                  .size());
        });
  }

  // ── Near-miss suggestions ─────────────────────────────────────────────────
  // Only the wiring lives here: the SQL prefilter finds candidates and the cap is respected. All
  // the scoring, normalisation and threshold behaviour is in the pure OcrRecordFileNameSimilarity-
  // Test, which does not need a database to compare two strings.

  @Test
  void aMistypedFileNameStillSurfacesTheDocumentThroughSuggestions() {
    asSystem(
        "SRCH10",
        c -> {
          AiOcrUpload real = upload("Получател.pdf", c);
          upload("something-entirely-different.pdf", c);

          // What the exact search does today with the mistyped name.
          assertTrue(svc().search(new OcrRecordQuery().setFileName("Получетаел.pdf")).isNotFound());

          List<AiOcrUpload> near = svc().suggestByFileName("Получетаел.pdf", 5);
          assertFalse(near.isEmpty(), "a two-character typo must still surface the document");
          assertEquals(real.getId(), near.get(0).getId());
        });
  }

  @Test
  void suggestionsNeverCrossCompanies() {
    asSystem(
        "SRCH11A",
        mine -> {
          upload("Получател.pdf", company("SRCH11B"));

          assertTrue(
              svc().suggestByFileName("Получетаел.pdf", 5).isEmpty(),
              "a near-miss must not surface another company's document either");
        });
  }

  @Test
  void theSuggestionCountIsBounded() {
    asSystem(
        "SRCH12",
        c -> {
          for (int i = 0; i < 12; i++) {
            upload("bounded-invoice-" + i + ".pdf", c);
          }
          assertEquals(3, svc().suggestByFileName("bounded-invoice-0.pdf", 3).size());
        });
  }

  private AiOcrUpload upload(String fileName, Company company) {
    AiOcrUpload u = new AiOcrUpload();
    u.setFileName(fileName);
    u.setCompany(company);
    u.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    return JPA.save(u);
  }
}
