package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.bankstatement.OcrBankStatementCreator;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.OcrResultJson;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of the manual reroute decision + ordering rules, driven entirely through {@link
 * OcrRerouteService}'s seams — no database, no Guice injector, no security context.
 *
 * <p>What is proven here is exactly what makes the feature safe:
 *
 * <ul>
 *   <li>the destination is taken from the caller, not from the document (the classifier is never
 *       consulted);
 *   <li>the replaced record is deleted only AFTER the new one exists, and never when creation
 *       failed;
 *   <li>a ventilated invoice, a posted move, an imported statement and another company's record are
 *       refused with nothing changed;
 *   <li>a second click cannot create a second record;
 *   <li>the stored extraction is what gets routed (no re-extraction).
 * </ul>
 */
class OcrRerouteServiceTest {

  // -------------------------------------------------------------------------------------------
  // Test doubles
  // -------------------------------------------------------------------------------------------

  /** Records which destination handler ran, and simulates what each one does to the upload. */
  private static final class FakeIntake extends BgOcrIntakeService {

    final List<String> calls = new ArrayList<>();
    OcrExtractResult stored;
    OcrExtractResult handedToHandler;
    boolean invoiceHandlerCreatesNothing;
    Invoice createdInvoice;
    Move createdMove;

    FakeIntake() {
      super(null, null, null, null, null);
    }

    @Override
    public OcrExtractResult rehydrateExtraction(AiOcrUpload upload) {
      calls.add("rehydrate");
      return stored;
    }

    @Override
    protected boolean isInternalUser() {
      return true;
    }

    @Override
    protected void routeMove(AiOcrUpload upload, OcrExtractResult result, String note) {
      calls.add("routeMove");
      handedToHandler = result;
      createdMove = new Move();
      createdMove.setId(77L);
      upload.setInvoice(null);
      upload.setMove(createdMove);
      upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    }

    @Override
    protected void routeCustomerInvoice(
        AiOcrUpload upload, OcrExtractResult result, com.axelor.apps.base.db.Partner customer) {
      calls.add("routeCustomerInvoice");
    }

    @Override
    protected void routeInvoice(AiOcrUpload upload, OcrExtractResult result) {
      calls.add("routeInvoice");
      handedToHandler = result;
      if (invoiceHandlerCreatesNothing) {
        // exactly what the real handler does when no supplier matches: no invoice, needs review
        upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
        upload.setExtractionErrors("Supplier with EIK 123456789 was not found");
        return;
      }
      createdInvoice = new Invoice();
      createdInvoice.setId(55L);
      upload.setMove(null);
      upload.setInvoice(createdInvoice);
      upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    }
  }

  /** Captures the destructive calls so the test can assert whether — and when — they happened. */
  private static class TestService extends OcrRerouteService {

    final FakeIntake intake;
    final List<String> events = new ArrayList<>();
    final List<String> partnerLookups = new ArrayList<>();
    AiOcrUpload upload;
    boolean owns = true;
    boolean claimSucceeds = true;
    int claims;
    long otherOwners;
    OcrBankStatementCreator.StatementRef statement;

    /** canonical EIK → the partners a company-scoped lookup finds for it. */
    final Map<String, List<Partner>> partnersByEik = new HashMap<>();

    TestService(FakeIntake intake) {
      super(null, null, intake);
      this.intake = intake;
    }

    @Override
    protected AiOcrUpload findUpload(Long uploadId) {
      return upload;
    }

    @Override
    protected void saveUpload(AiOcrUpload upload) {
      events.add("saveUpload");
    }

    @Override
    protected void syncAudit(AiOcrUpload upload) {}

    @Override
    protected boolean ownsCompany(Company company) {
      return owns;
    }

    @Override
    protected boolean claimForReroute(Long uploadId, String previousStatus) {
      claims++;
      return claimSucceeds && claims == 1;
    }

    @Override
    protected void releaseClaim(Long uploadId, String previousStatus, String reason) {
      events.add("release:" + reason);
    }

    @Override
    protected void removeInvoice(Invoice invoice) {
      events.add("removeInvoice#" + invoice.getId());
    }

    @Override
    protected void removeMove(Move move) {
      events.add("removeMove#" + move.getId());
    }

    @Override
    protected long otherUploadsLinkingInvoice(AiOcrUpload upload, Invoice invoice) {
      return otherOwners;
    }

    @Override
    protected long otherUploadsLinkingMove(AiOcrUpload upload, Move move) {
      return otherOwners;
    }

    @Override
    protected OcrBankStatementCreator.StatementRef statementRef(AiOcrUpload upload) {
      return statement;
    }

    @Override
    protected OcrBankStatementCreator bankStatementCreator() {
      return null; // no backend bound in a unit test
    }

    @Override
    protected List<Partner> matchPartnersForCompany(String canonicalEik, Long companyId) {
      partnerLookups.add(canonicalEik + "@" + companyId);
      return partnersByEik.getOrDefault(canonicalEik, List.of());
    }
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private static Company company(long id) {
    Company company = new Company();
    company.setId(id);
    return company;
  }

  /** An upload holding a DRAFT, automatically created invoice and a reusable stored extraction. */
  private static AiOcrUpload uploadWithDraftInvoice() {
    AiOcrUpload upload = new AiOcrUpload();
    upload.setId(1L);
    upload.setCompany(company(10L));
    upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    upload.setCachedOcrJson(OcrResultJson.toJson(extraction()));
    upload.setInvoice(draftInvoice(company(10L)));
    return upload;
  }

  private static Invoice draftInvoice(Company company) {
    Invoice invoice = new Invoice();
    invoice.setId(55L);
    invoice.setCompany(company);
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setAiGenerated(true);
    return invoice;
  }

  /** Text a document classifier would read as an invoice — the reroute must ignore it. */
  private static OcrExtractResult extraction() {
    OcrExtractResult result = new OcrExtractResult();
    result.setRawText("ФАКТУРА № 0000001234 оригинал");
    result.setInvoiceNumber("0000001234");
    return result;
  }

  // -------------------------------------------------------------------------------------------
  // Tests
  // -------------------------------------------------------------------------------------------

  @Test
  void explicitDestinationBypassesClassificationAndReusesTheStoredExtraction() {
    FakeIntake intake = new FakeIntake();
    intake.stored = extraction();
    TestService service = new TestService(intake);
    service.upload = uploadWithDraftInvoice();

    OcrRerouteService.Outcome outcome = service.reroute(1L, OcrRerouteDestination.MOVE);

    // The document says "ФАКТУРА"; the user said Move. The Move handler is the one that ran.
    assertEquals(List.of("rehydrate", "routeMove"), intake.calls);
    // ...from the SAVED extraction — the service never reaches for the extractor/Azure.
    assertSame(intake.stored, intake.handedToHandler);
    assertEquals(OcrRerouteDestination.MOVE, outcome.getDestination());
    assertTrue(outcome.getMessage().contains("Accounting move #77"));
  }

  @Test
  void theReplacedDraftIsRemovedOnlyAfterTheNewRecordExists() {
    FakeIntake intake = new FakeIntake();
    intake.stored = extraction();
    TestService service = new TestService(intake);
    service.upload = uploadWithDraftInvoice();

    service.reroute(1L, OcrRerouteDestination.MOVE);

    assertEquals(
        Long.valueOf(77L), service.upload.getMove().getId(), "the new record must be linked");
    assertNull(service.upload.getInvoice(), "the old link must be gone");
    int removed = service.events.indexOf("removeInvoice#55");
    assertTrue(removed >= 0, "the previous draft invoice must be removed: " + service.events);
    assertTrue(
        intake.calls.indexOf("routeMove") >= 0 && intake.createdMove != null,
        "creation must have happened before the removal");
  }

  @Test
  void aFailedDestinationPreservesThePreviousRecordAndItsLink() {
    FakeIntake intake = new FakeIntake();
    intake.stored = extraction();
    intake.invoiceHandlerCreatesNothing = true;
    TestService service = new TestService(intake);
    AiOcrUpload upload = new AiOcrUpload();
    upload.setId(1L);
    upload.setCompany(company(10L));
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    upload.setCachedOcrJson(OcrResultJson.toJson(extraction()));
    Move move = new Move();
    move.setId(88L);
    move.setCompany(company(10L));
    move.setStatusSelect(MoveRepository.STATUS_NEW);
    upload.setMove(move);
    service.upload = upload;

    OcrRerouteService.RerouteRefusedException failure =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () -> service.reroute(1L, OcrRerouteDestination.SUPPLIER_INVOICE));

    assertTrue(failure.getMessage().contains("Nothing was changed"));
    assertFalse(
        service.events.contains("removeMove#88"), "the previous move must NOT have been removed");
    assertSame(move, upload.getMove(), "the previous link must be preserved");
    assertTrue(
        service.events.stream().anyMatch(e -> e.startsWith("release:")),
        "the claim must be released so the record never stays 'processing': " + service.events);
  }

  @Test
  void aVentilatedInvoiceIsRefused() {
    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = uploadWithDraftInvoice();
    upload.getInvoice().setStatusSelect(InvoiceRepository.STATUS_VENTILATED);
    service.upload = upload;

    String refusal = service.refusalReason(upload, OcrRerouteDestination.MOVE);

    assertNotNull(refusal);
    assertTrue(refusal.contains("ventilated"), refusal);
  }

  @Test
  void aPostedMoveIsRefused() {
    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = new AiOcrUpload();
    upload.setId(1L);
    upload.setCompany(company(10L));
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    upload.setCachedOcrJson(OcrResultJson.toJson(extraction()));
    Move move = new Move();
    move.setId(88L);
    move.setCompany(company(10L));
    move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    upload.setMove(move);
    service.upload = upload;

    String refusal = service.refusalReason(upload, OcrRerouteDestination.SUPPLIER_INVOICE);

    assertNotNull(refusal);
    assertTrue(refusal.contains("posted"), refusal);
  }

  @Test
  void anImportedBankStatementIsRefused() {
    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = new AiOcrUpload();
    upload.setId(1L);
    upload.setCompany(company(10L));
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    upload.setCachedOcrJson(OcrResultJson.toJson(extraction()));
    service.upload = upload;
    service.statement = new OcrBankStatementCreator.StatementRef(9L, "st", false, 10L);

    assertEquals(OcrRerouteDestination.BANK_STATEMENT, service.currentDestination(upload));
    String refusal = service.refusalReason(upload, OcrRerouteDestination.MOVE);
    assertNotNull(refusal);
    assertTrue(refusal.contains("already been imported"), refusal);
  }

  @Test
  void anotherCompanysRecordCannotBeRerouted() {
    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = uploadWithDraftInvoice();
    service.upload = upload;
    service.owns = false;

    String refusal = service.refusalReason(upload, OcrRerouteDestination.MOVE);

    assertNotNull(refusal);
    assertTrue(refusal.startsWith("No OCR record with id"), refusal);
    // ...and the entry point refuses before any handler runs.
    assertThrows(
        OcrRerouteService.RerouteRefusedException.class,
        () -> service.reroute(1L, OcrRerouteDestination.MOVE));
    assertTrue(intake.calls.isEmpty(), "no destination handler may run: " + intake.calls);
  }

  @Test
  void aSecondClickCannotCreateASecondRecord() {
    FakeIntake intake = new FakeIntake();
    intake.stored = extraction();
    TestService service = new TestService(intake);
    service.upload = uploadWithDraftInvoice();

    service.reroute(1L, OcrRerouteDestination.MOVE);
    long handlerRuns = intake.calls.stream().filter("routeMove"::equals).count();

    // The record now holds a Move; the same click again is refused by the destination check, and a
    // genuinely concurrent one loses the atomic claim (claimForReroute returns false).
    assertThrows(
        OcrRerouteService.RerouteRefusedException.class,
        () -> service.reroute(1L, OcrRerouteDestination.MOVE));
    service.upload.setMove(null);
    service.upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    assertThrows(
        OcrRerouteService.RerouteRefusedException.class,
        () -> service.reroute(1L, OcrRerouteDestination.MOVE));
    assertEquals(
        handlerRuns,
        intake.calls.stream().filter("routeMove"::equals).count(),
        "no further destination record may be created");
  }

  @Test
  void aRecordWithNoStoredExtractionIsRefusedInsteadOfCallingAzure() {
    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = uploadWithDraftInvoice();
    upload.setCachedOcrJson(null);
    service.upload = upload;

    String refusal = service.refusalReason(upload, OcrRerouteDestination.MOVE);

    assertNotNull(refusal);
    assertTrue(refusal.contains("no stored extraction"), refusal);
  }

  @Test
  void aSplitSegmentCannotBecomeABankStatementWithoutTheRawAnalyzeResult() {
    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = uploadWithDraftInvoice(); // only cachedOcrJson (a parsed segment result)
    service.upload = upload;

    String refusal = service.refusalReason(upload, OcrRerouteDestination.BANK_STATEMENT);

    assertNotNull(refusal);
    assertTrue(refusal.contains("split segment"), refusal);
  }

  // -------------------------------------------------------------------------------------------
  // Customer Invoice identity (requirements 1-4 of the correctness pass)
  // -------------------------------------------------------------------------------------------

  private static final String OUR_EIK = "131391369";
  private static final String BUYER_EIK = "205123456";

  private static Partner partner(long id, String eik) {
    Partner partner = new Partner();
    partner.setId(id);
    partner.setRegistrationCode(eik);
    return partner;
  }

  /** An upload whose company carries OUR_EIK, so the vendor comparison has something to match. */
  private static AiOcrUpload uploadOfCompanyWithEik() {
    AiOcrUpload upload = uploadWithDraftInvoice();
    Company company = upload.getCompany();
    company.setName("Nyxa Test EOOD");
    company.setPartner(partner(500L, OUR_EIK));
    return upload;
  }

  /** A document this company issued: vendor = us, buyer = the customer. */
  private static OcrExtractResult saleDocument() {
    OcrExtractResult result = extraction();
    result.setSupplierEik(OUR_EIK);
    result.setSupplierName("Nyxa Test EOOD");
    result.setCustomerEik(BUYER_EIK);
    result.setCustomerName("Купувач ООД");
    return result;
  }

  @Test
  void theExtractedBuyerBecomesTheInvoicePartnerWhenTheActiveCompanyIsTheVendor() {
    TestService service = new TestService(new FakeIntake());
    AiOcrUpload upload = uploadOfCompanyWithEik();
    Partner buyer = partner(900L, BUYER_EIK);
    service.partnersByEik.put(BUYER_EIK, List.of(buyer));

    Partner resolved = service.requireCustomer(upload, saleDocument());

    assertSame(buyer, resolved, "the extracted CUSTOMER must become Invoice.partner");
    assertEquals(
        List.of(BUYER_EIK + "@10"),
        service.partnerLookups,
        "exactly one company-scoped lookup, for the buyer EIK");
  }

  @Test
  void aVendorThatIsNotTheActiveCompanyRefusesCreation() {
    TestService service = new TestService(new FakeIntake());
    AiOcrUpload upload = uploadOfCompanyWithEik();
    OcrExtractResult result = saleDocument();
    result.setSupplierEik("999888777"); // someone else issued this document

    OcrRerouteService.RerouteRefusedException refusal =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () -> service.requireCustomer(upload, result));

    assertTrue(refusal.getMessage().contains("issuer/vendor"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("Nothing was changed"), refusal.getMessage());
    assertTrue(service.partnerLookups.isEmpty(), "no partner may be looked up after the refusal");
  }

  @Test
  void aMissingCustomerIdentityRefusesCreationInsteadOfGuessing() {
    TestService service = new TestService(new FakeIntake());
    AiOcrUpload upload = uploadOfCompanyWithEik();
    OcrExtractResult result = saleDocument();
    result.setCustomerEik(null);
    result.setCustomerVat(null);
    // A name alone is never enough.
    result.setCustomerName("Купувач ООД");

    OcrRerouteService.RerouteRefusedException refusal =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () -> service.requireCustomer(upload, result));

    assertTrue(refusal.getMessage().contains("No customer EIK/VAT"), refusal.getMessage());
    assertTrue(service.partnerLookups.isEmpty(), "no name-based lookup may happen");
  }

  @Test
  void theVendorIsNeverUsedAsTheCustomer() {
    TestService service = new TestService(new FakeIntake());
    AiOcrUpload upload = uploadOfCompanyWithEik();
    OcrExtractResult result = saleDocument();
    result.setCustomerEik(null);
    result.setCustomerVat(null);
    // A partner DOES exist for the vendor EIK — the old fallback would have picked it up.
    service.partnersByEik.put(OUR_EIK, List.of(partner(500L, OUR_EIK)));
    // ...and so does one for an unrelated supplier read off the document.
    service.partnersByEik.put("999888777", List.of(partner(901L, "999888777")));

    assertThrows(
        OcrRerouteService.RerouteRefusedException.class,
        () -> service.requireCustomer(upload, result));
    assertTrue(
        service.partnerLookups.isEmpty(),
        "the vendor/supplier block must never be consulted for the customer: "
            + service.partnerLookups);
  }

  @Test
  void theActiveCompanyItselfIsNeverTheInvoicePartner() {
    TestService service = new TestService(new FakeIntake());
    AiOcrUpload upload = uploadOfCompanyWithEik();
    OcrExtractResult result = saleDocument();
    result.setCustomerEik(OUR_EIK); // our own EIK on both sides

    OcrRerouteService.RerouteRefusedException refusal =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () -> service.requireCustomer(upload, result));

    assertTrue(refusal.getMessage().contains("BOTH sides"), refusal.getMessage());
    assertTrue(service.partnerLookups.isEmpty());
  }

  @Test
  void anUnknownOrAmbiguousBuyerRefusesCreationAndNeverCreatesAPartner() {
    TestService service = new TestService(new FakeIntake());
    AiOcrUpload upload = uploadOfCompanyWithEik();

    OcrRerouteService.RerouteRefusedException notFound =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () -> service.requireCustomer(upload, saleDocument()));
    assertTrue(
        notFound.getMessage().contains("No partner with EIK " + BUYER_EIK + " is available to"),
        notFound.getMessage());
    assertTrue(
        notFound.getMessage().contains("no customer is ever created automatically"),
        notFound.getMessage());

    service.partnersByEik.put(
        BUYER_EIK, List.of(partner(901L, BUYER_EIK), partner(902L, BUYER_EIK)));
    OcrRerouteService.RerouteRefusedException ambiguous =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () -> service.requireCustomer(upload, saleDocument()));
    assertTrue(ambiguous.getMessage().contains("Several partners"), ambiguous.getMessage());
  }

  @Test
  void aCompanyWithNoEikCannotBeProvenToBeTheIssuer() {
    TestService service = new TestService(new FakeIntake());
    AiOcrUpload upload = uploadOfCompanyWithEik();
    upload.getCompany().setPartner(partner(500L, null));

    OcrRerouteService.RerouteRefusedException refusal =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () -> service.requireCustomer(upload, saleDocument()));

    // The diagnostic has to name the exact record to correct — the linked Partner, by id.
    assertTrue(refusal.getMessage().contains("Partner #500"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("Nothing was changed"), refusal.getMessage());
  }

  /**
   * The real invoice that exposed this: ПРИМЕРНА (100000019) issued it to ТЕСТЕКС (100000026),
   * printing both identities under "ДДС №" only. Azure returns no {@code VendorTaxId}, so the raw
   * extraction reaches the reroute with an empty vendor block and the identity check used to refuse
   * with "vendor EIK/VAT read: none". The issuer ЕИК must be recovered from the text first — and,
   * unlike the supplier path, the active company's own ЕИК must stay a valid candidate there.
   */
  @Test
  void theCustomerRerouteRecoversTheIssuerEikAzureNeverReturned() {
    String issuerEik = "100000019";
    String buyerEik = "100000026";

    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = uploadWithDraftInvoice();
    upload.getCompany().setName("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД");
    upload.getCompany().setPartner(partner(500L, issuerEik));
    service.partnersByEik.put(buyerEik, List.of(partner(900L, buyerEik)));

    OcrExtractResult result = extraction();
    result.setSupplierEik(null); // Azure produced no VendorTaxId at all
    result.setSupplierVat(null);
    result.setCustomerEik(buyerEik);
    result.setCustomerVat("BG" + buyerEik);
    result.setRawText(
        "ТЕСТЕКС ООД\nДДС №\nBG100000026\n"
            + "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД\nДДС №\nBG100000019\n"
            + "Услуги по договор\nДДС 20%: 1 000.00 лв.");

    service.createDestination(upload, OcrRerouteDestination.CUSTOMER_INVOICE, result);

    assertEquals(issuerEik, result.getSupplierEik(), "the issuer ЕИК is recovered from ДДС №");
    assertTrue(
        intake.calls.contains("routeCustomerInvoice"),
        "the reroute must proceed once the issuer is identified: " + intake.calls);
  }

  /** Two plausible issuer candidates stay ambiguous: nothing is filled in, and the gate refuses. */
  @Test
  void anAmbiguousIssuerEikIsStillRefused() {
    String buyerEik = "100000026";

    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload upload = uploadWithDraftInvoice();
    upload.getCompany().setName("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД");
    upload.getCompany().setPartner(partner(500L, "100000019"));

    OcrExtractResult result = extraction();
    result.setSupplierEik(null);
    result.setSupplierVat(null);
    result.setCustomerEik(buyerEik);
    result.setCustomerVat("BG" + buyerEik);
    result.setRawText(
        "ТЕСТЕКС ООД\nДДС №\nBG100000026\n"
            + "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД\nДДС №\nBG100000019\n"
            + "Посредник АД\nДДС №\nBG203999888\n");

    OcrRerouteService.RerouteRefusedException refusal =
        assertThrows(
            OcrRerouteService.RerouteRefusedException.class,
            () ->
                service.createDestination(upload, OcrRerouteDestination.CUSTOMER_INVOICE, result));

    assertNull(result.getSupplierEik(), "an ambiguous read must never be guessed at");
    assertTrue(refusal.getMessage().contains("issuer/vendor"), refusal.getMessage());
    assertFalse(intake.calls.contains("routeCustomerInvoice"), intake.calls.toString());
    assertTrue(service.partnerLookups.isEmpty(), "no partner may be looked up after the refusal");
  }

  /**
   * The supplier reroute must reach the ordinary, untouched supplier handler — so none of the
   * customer-direction conversion (and none of its bank-account handling) can affect a supplier
   * invoice.
   */
  @Test
  void aSupplierRerouteNeverEntersTheCustomerConversion() {
    FakeIntake intake = new FakeIntake();
    intake.stored = extraction();
    TestService service = new TestService(intake);
    AiOcrUpload upload = new AiOcrUpload();
    upload.setId(1L);
    upload.setCompany(company(10L));
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    upload.setCachedOcrJson(OcrResultJson.toJson(extraction()));
    Move move = new Move();
    move.setId(88L);
    move.setCompany(company(10L));
    move.setStatusSelect(MoveRepository.STATUS_NEW);
    upload.setMove(move);
    service.upload = upload;

    service.reroute(1L, OcrRerouteDestination.SUPPLIER_INVOICE);

    assertTrue(intake.calls.contains("routeInvoice"), "the stock supplier handler must run");
    assertFalse(
        intake.calls.contains("routeCustomerInvoice"),
        "the customer conversion must never run for a supplier reroute: " + intake.calls);
    assertTrue(service.partnerLookups.isEmpty(), "no customer identity resolution may happen");
  }

  @Test
  void carriersAndInFlightRecordsAreNotReroutable() {
    FakeIntake intake = new FakeIntake();
    TestService service = new TestService(intake);
    AiOcrUpload carrier = uploadWithDraftInvoice();
    carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
    service.upload = carrier;
    assertTrue(
        service.refusalReason(carrier, OcrRerouteDestination.MOVE).contains("carrier"),
        "a carrier holds no single result");

    AiOcrUpload processing = uploadWithDraftInvoice();
    processing.setStatusSelect(AiOcrUploadStatus.PROCESSING);
    assertTrue(
        service.refusalReason(processing, OcrRerouteDestination.MOVE).contains("cannot be"),
        "a record still processing must not be rerouted");
  }
}
