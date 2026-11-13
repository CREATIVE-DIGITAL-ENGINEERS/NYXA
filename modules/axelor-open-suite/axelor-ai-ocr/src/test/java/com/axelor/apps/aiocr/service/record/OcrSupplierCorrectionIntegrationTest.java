package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** DB-backed proof of the dedicated supplier-correction capability. */
class OcrSupplierCorrectionIntegrationTest extends AiOcrTest {

  private OcrSupplierCorrectionService svc() {
    return Beans.get(OcrSupplierCorrectionService.class);
  }

  /**
   * Explicit caller identity for the harness; see the search test for the rationale. The scope must
   * name a company — one is created here and handed to the body, so fixtures and scope cannot drift
   * apart.
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

  private Partner supplier(String name, String eik) {
    Partner p = new Partner();
    p.setName(name);
    p.setFullName(name);
    p.setRegistrationCode(eik);
    p.setTaxNbr("BG" + eik);
    p.setIsSupplier(true);
    return JPA.save(p);
  }

  /** Gives the partner an invoicing address, so the address refresh has something to resolve. */
  private Partner withInvoicingAddress(Partner p, String street) {
    Address a = new Address();
    a.setRoom(street);
    Address savedAddress = JPA.save(a);

    PartnerAddress pa = new PartnerAddress();
    pa.setAddress(savedAddress);
    pa.setPartner(p);
    pa.setIsInvoicingAddr(true);
    pa.setIsDefaultAddr(true);
    PartnerAddress savedPa = JPA.save(pa);

    List<PartnerAddress> list = new ArrayList<>();
    list.add(savedPa);
    p.setPartnerAddressList(list);
    return JPA.save(p);
  }

  private Invoice invoice(Company c, Partner p, int status) {
    Invoice i = new Invoice();
    i.setCompany(c);
    i.setPartner(p);
    i.setSupplierInvoiceNb("OLD-1");
    i.setInvoiceDate(LocalDate.now().minusDays(3));
    i.setAiGenerated(Boolean.TRUE);
    i.setStatusSelect(status);
    i.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    return JPA.save(i);
  }

  private AiOcrUpload upload(Company c, Invoice inv) {
    AiOcrUpload u = new AiOcrUpload();
    u.setFileName("sup.pdf");
    u.setCompany(c);
    u.setInvoice(inv);
    u.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    return JPA.save(u);
  }

  @Test
  void changesSupplierAndRefreshesDependentFields() {
    asSystem(
        "SUP1",
        c -> {
          Partner wrong = supplier("Wrong Ltd", "111111111");
          Partner right = withInvoicingAddress(supplier("Right Ltd", "831919536"), "Vitosha 1");
          Invoice inv = invoice(c, wrong, InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrSupplierCorrectionService.SupplierChangeResult r =
              svc().changeSupplier(u.getId(), right.getId());

          assertTrue(r.isChanged());
          assertEquals(wrong.getId(), r.getPreviousPartnerId());
          assertEquals(right.getId(), r.getNewPartnerId());

          Invoice reloaded = Beans.get(InvoiceRepository.class).find(inv.getId());
          assertEquals(right.getId(), reloaded.getPartner().getId());
          // Identity-derived fields must follow the new supplier.
          assertEquals("BG831919536", reloaded.getPartnerTaxNbr());
          assertNotNull(reloaded.getAddress(), "invoicing address resolved from the new partner");

          // The refresh report names what was re-derived, for the audit trail.
          assertTrue(r.getRefreshedFields().containsKey("address"));
          assertTrue(r.getRefreshedFields().containsKey("partnerTaxNbr"));
          assertTrue(r.getRefreshedFields().containsKey("bankDetails"));
          assertTrue(r.getRefreshedFields().containsKey("fiscalPosition"));
          // Payment mode/condition depend on account app configuration that a bare test database
          // does not have. The contract is that each is either refreshed or explicitly reported as
          // skipped — never silently left pointing at the previous supplier's setup.
          for (String field : new String[] {"paymentMode", "paymentCondition"}) {
            assertTrue(
                r.getRefreshedFields().containsKey(field)
                    || r.getSkipped().stream().anyMatch(s -> s.startsWith(field)),
                field + " must be refreshed or explicitly skipped");
          }
        });
  }

  @Test
  void previousSuppliersBankAccountIsNeverKept() {
    asSystem(
        "SUP2",
        c -> {
          Partner wrong = supplier("Wrong Ltd", "111111112");
          Partner right = supplier("Right Ltd", "831919537");
          Invoice inv = invoice(c, wrong, InvoiceRepository.STATUS_DRAFT);

          BankDetails wrongAccount = new BankDetails();
          wrongAccount.setIban("BG80BNBG96611020345678");
          wrongAccount.setPartner(wrong);
          BankDetails savedAccount = JPA.save(wrongAccount);
          inv.setBankDetails(savedAccount);
          JPA.save(inv);

          AiOcrUpload u = upload(c, inv);
          svc().changeSupplier(u.getId(), right.getId());

          Invoice reloaded = Beans.get(InvoiceRepository.class).find(inv.getId());
          // Paying the old supplier's IBAN is the worst possible outcome of a supplier correction.
          assertTrue(
              reloaded.getBankDetails() == null
                  || !savedAccount.getId().equals(reloaded.getBankDetails().getId()),
              "the previous supplier's bank account must not survive");
        });
  }

  @Test
  void sameSupplierIsANoOp() {
    asSystem(
        "SUP3",
        c -> {
          Partner p = supplier("Demo EOOD", "831919538");
          Invoice inv = invoice(c, p, InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);
          String notesBefore =
              Beans.get(InvoiceRepository.class).find(inv.getId()).getAiValidationNotes();

          OcrSupplierCorrectionService.SupplierChangeResult r =
              svc().changeSupplier(u.getId(), p.getId());

          assertFalse(r.isChanged());
          assertTrue(r.getRefreshedFields().isEmpty(), "no dependent field churn on a no-op");
          assertEquals(
              notesBefore,
              Beans.get(InvoiceRepository.class).find(inv.getId()).getAiValidationNotes(),
              "a no-op must not write an audit line");
        });
  }

  @Test
  void writesAnAuditTrailWithBeforeAndAfter() {
    asSystem(
        "SUP4",
        c -> {
          Partner wrong = supplier("Wrong Ltd", "111111113");
          Partner right = supplier("Right Ltd", "831919539");
          Invoice inv = invoice(c, wrong, InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          svc().changeSupplier(u.getId(), right.getId());

          String notes =
              Beans.get(InvoiceRepository.class).find(inv.getId()).getAiValidationNotes();
          assertNotNull(notes);
          assertTrue(notes.contains("Wrong Ltd"), "old supplier recorded");
          assertTrue(notes.contains("Right Ltd"), "new supplier recorded");
          assertTrue(notes.contains("refreshed"), "dependent field refresh recorded");
          assertTrue(notes.contains("system:test"), "caller recorded");
        });
  }

  @Test
  void refusesUnknownPartner() {
    asSystem(
        "SUP5",
        c -> {
          Invoice inv = invoice(c, supplier("Demo", "831919540"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrSupplierCorrectionService.SupplierChangeRefusedException e =
              assertThrows(
                  OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
                  () -> svc().changeSupplier(u.getId(), 987_654L));
          assertTrue(e.getMessage().contains("never created automatically"));
        });
  }

  @Test
  void refusesAPartnerThatIsNotASupplier() {
    asSystem(
        "SUP6",
        c -> {
          Invoice inv = invoice(c, supplier("Demo", "831919541"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          Partner customer = new Partner();
          customer.setName("Client Ltd");
          customer.setFullName("Client Ltd");
          customer.setIsSupplier(false);
          customer.setIsCustomer(true);
          Partner savedCustomer = JPA.save(customer);

          assertThrows(
              OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
              () -> svc().changeSupplier(u.getId(), savedCustomer.getId()));
        });
  }

  @Test
  void refusesNonDraftAndNonOcrInvoices() {
    asSystem(
        "SUP7",
        c -> {
          Partner target = supplier("Target Ltd", "831919542");

          Invoice validated =
              invoice(c, supplier("A Ltd", "111111114"), InvoiceRepository.STATUS_VALIDATED);
          AiOcrUpload u1 = upload(c, validated);
          assertTrue(
              assertThrows(
                      OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
                      () -> svc().changeSupplier(u1.getId(), target.getId()))
                  .getMessage()
                  .contains("Draft"));

          Invoice manual =
              invoice(c, supplier("B Ltd", "111111115"), InvoiceRepository.STATUS_DRAFT);
          manual.setAiGenerated(Boolean.FALSE);
          JPA.save(manual);
          AiOcrUpload u2 = upload(c, manual);
          assertTrue(
              assertThrows(
                      OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
                      () -> svc().changeSupplier(u2.getId(), target.getId()))
                  .getMessage()
                  .contains("not produced by OCR"));
        });
  }

  @Test
  void refusesAPartnerRestrictedToAnotherCompany() {
    asSystem(
        "SUP8A",
        mine -> {
          Company theirs = company("SUP8B");
          Invoice inv =
              invoice(mine, supplier("Demo", "831919543"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(mine, inv);

          Partner restricted = supplier("Foreign Ltd", "831919544");
          Set<Company> companies = new LinkedHashSet<>();
          companies.add(theirs);
          restricted.setCompanySet(companies);
          Partner savedRestricted = JPA.save(restricted);

          // The partner is now filtered out in SQL by the existence probe, so it reads as "no such
          // partner" rather than "exists but not for you" — which is also the non-leaky answer.
          // The later assertScope check on companySet remains as defence in depth for non-tool
          // callers that resolved the partner some other way.
          String message =
              assertThrows(
                      OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
                      () -> svc().changeSupplier(u.getId(), savedRestricted.getId()))
                  .getMessage();
          assertTrue(
              message.contains("No partner with id") || message.contains("not available"),
              "a partner outside the company must be refused: " + message);
        });
  }

  @Test
  void refusesARecordOutsideTheCallersCompanyScope() {
    asSystem(
        "SUP9A",
        mine -> {
          Company theirs = company("SUP9B");
          Invoice inv =
              invoice(theirs, supplier("Demo", "831919545"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(theirs, inv);
          Partner target = supplier("Target Ltd", "831919546");

          OcrSupplierCorrectionService scoped =
              new OcrSupplierCorrectionService(
                  Beans.get(AiOcrUploadRepository.class), Beans.get(PartnerRepository.class)) {
                @Override
                protected Long allowedCompanyId() {
                  return mine.getId();
                }
              };

          // Worded as not-found: a cross-company upload id must not be usable to establish that
          // the record exists.
          String message =
              assertThrows(
                      OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
                      () -> scoped.changeSupplier(u.getId(), target.getId()))
                  .getMessage();
          assertTrue(
              message.contains("No OCR record with id"),
              "a cross-company refusal must read as not-found: " + message);
        });
  }

  @Test
  void refusesMissingRecordOrMissingInvoice() {
    asSystem(
        "SUP10",
        c -> {
          Partner target = supplier("Target Ltd", "831919547");
          AiOcrUpload orphan = upload(c, null);

          assertThrows(
              OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
              () -> svc().changeSupplier(999_999L, target.getId()));
          assertThrows(
              OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
              () -> svc().changeSupplier(orphan.getId(), target.getId()));
          assertThrows(
              OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
              () -> svc().changeSupplier(null, target.getId()));
          assertThrows(
              OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
              () -> svc().changeSupplier(orphan.getId(), null));
        });
  }

  @Test
  void reportsResultingReviewStateAndNeverLeavesDraft() {
    asSystem(
        "SUP11",
        c -> {
          Partner wrong = supplier("Wrong Ltd", "111111116");
          Partner right = supplier("Right Ltd", "831919548");
          Invoice inv = invoice(c, wrong, InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrSupplierCorrectionService.SupplierChangeResult r =
              svc().changeSupplier(u.getId(), right.getId());

          assertNotNull(r.getOcrReviewStatus());
          assertNotNull(r.getUploadStatus());
          assertEquals(
              InvoiceRepository.STATUS_DRAFT,
              Beans.get(InvoiceRepository.class).find(inv.getId()).getStatusSelect().intValue(),
              "a correction never validates or ventilates");
        });
  }

  @Test
  void currencyIsNotTouchedWhenTheInvoiceHasLines() {
    asSystem(
        "SUP12",
        c -> {
          Partner wrong = supplier("Wrong Ltd", "111111117");
          Partner right = supplier("Right Ltd", "831919549");
          Invoice inv = invoice(c, wrong, InvoiceRepository.STATUS_DRAFT);

          InvoiceLine line = new InvoiceLine();
          line.setInvoice(inv);
          List<InvoiceLine> lines = new ArrayList<>();
          lines.add(line);
          inv.setInvoiceLineList(lines);
          JPA.save(inv);

          AiOcrUpload u = upload(c, inv);
          OcrSupplierCorrectionService.SupplierChangeResult r =
              svc().changeSupplier(u.getId(), right.getId());

          assertFalse(r.getRefreshedFields().containsKey("currency"));
          assertTrue(
              r.getSkipped().stream().anyMatch(s -> s.startsWith("currency")),
              "skipping currency under existing lines must be reported, not silent");
        });
  }

  @Test
  void refusesWhenThereIsNoAuthenticatedCallerAtAll() {
    // No Shiro subject and no system context: the change must not run at all.
    assertNull(com.axelor.auth.AuthUtils.getUser());
    assertFalse(AiExecutionContext.isAuthorisedCaller());
    OcrSupplierCorrectionService.SupplierChangeRefusedException e =
        assertThrows(
            OcrSupplierCorrectionService.SupplierChangeRefusedException.class,
            () -> svc().changeSupplier(1L, 1L));
    assertTrue(e.getMessage().contains("No authenticated user"));
  }
}
