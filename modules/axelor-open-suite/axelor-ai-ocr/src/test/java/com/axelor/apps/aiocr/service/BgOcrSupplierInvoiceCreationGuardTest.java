package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ocr.CompanyLegalIdentity;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import org.junit.jupiter.api.Test;

/**
 * The company/direction validation in front of {@code createDraftForSupplier} — the path {@code
 * edit_supplier_invoice createIfMissing} takes, which used to reach draft creation without passing
 * through intake's own gate.
 *
 * <p>Modelled on the record that exposed it: vendor ПРИМЕРНА (100000019) → buyer ТЕСТЕКС
 * (100000026), filed under a company whose {@code Company.partner} was null while a SEPARATE
 * supplier Partner row carried the company's own ЕИК and was visible under Purchases → Suppliers.
 * The assistant resolved that row as the supplier and a purchase invoice was created from the
 * company to itself.
 */
class BgOcrSupplierInvoiceCreationGuardTest {

  private static final String OUR_EIK = "100000019";
  private static final String BUYER_EIK = "100000026";

  private static class Intake extends BgOcrIntakeService {
    Intake() {
      super(null, mock(AiOcrUploadRepository.class), null, null, null);
    }
  }

  private final Intake svc = new Intake();

  private static Partner partner(long id, String name, String eik, String vat) {
    Partner p = new Partner();
    p.setId(id);
    p.setName(name);
    p.setFullName(name);
    p.setRegistrationCode(eik);
    p.setTaxNbr(vat);
    return p;
  }

  private static Company company(long id, String name, Partner linked) {
    Company c = new Company();
    c.setId(id);
    c.setName(name);
    c.setPartner(linked);
    return c;
  }

  private static AiOcrUpload upload(Company company) {
    AiOcrUpload u = new AiOcrUpload();
    u.setCompany(company);
    return u;
  }

  /** Vendor = us, buyer = ТЕСТЕКС: the outgoing document. */
  private static OcrExtractResult saleDocument() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierName("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД");
    r.setSupplierEik(OUR_EIK);
    r.setCustomerName("ТЕСТЕКС ООД");
    r.setCustomerEik(BUYER_EIK);
    r.setCustomerVat("BG" + BUYER_EIK);
    return r;
  }

  /** An ordinary purchase: a third-party vendor invoicing us. */
  private static OcrExtractResult purchaseDocument() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierName("Доставчик ООД");
    r.setSupplierEik("831641791");
    r.setCustomerName("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД");
    r.setCustomerEik(OUR_EIK);
    return r;
  }

  // ── the linked Partner is the identity, not a same-named supplier ──────────

  @Test
  void identityComesFromTheLinkedPartnerNotFromASameNamedSupplier() {
    // Exactly the live shape: Company.partner is null, and a SEPARATE supplier Partner carries the
    // company's own name and ЕИК. That row must not become the company's identity.
    Partner lookalikeSupplier =
        partner(77L, "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", OUR_EIK, "BG" + OUR_EIK);
    Company unlinked = company(1L, "Nyxa BG", null);

    assertFalse(CompanyLegalIdentity.of(unlinked).isUsable());
    assertFalse(CompanyLegalIdentity.of(unlinked).isOwnPartner(lookalikeSupplier));
    assertFalse(CompanyLegalIdentity.of(unlinked).isSameParty(OUR_EIK, "BG" + OUR_EIK));

    // Linked properly, the identity is read and the same ЕИК now names the company.
    Company linked = company(1L, "Nyxa BG", partner(90L, "Nyxa BG", OUR_EIK, "BG" + OUR_EIK));
    CompanyLegalIdentity identity = CompanyLegalIdentity.of(linked);
    assertTrue(identity.isUsable());
    assertTrue(identity.isSameParty("BG " + OUR_EIK, null), "canonical comparison, spaces and BG");
    assertEquals("Nyxa BG (Company #1)", identity.describeCompany());
  }

  @Test
  void aMissingLinkedPartnerIdentityGivesTheImprovedDiagnostic() {
    OcrBlocked noPartner =
        assertThrows(
            OcrBlocked.class,
            () ->
                svc.guardSupplierInvoiceCreation(
                    upload(company(1L, "Nyxa BG", null)),
                    purchaseDocument(),
                    partner(77L, "Доставчик ООД", "831641791", null)));
    assertEquals(AiBlockerCode.MISSING_COMPANY_IDENTITY.wire(), noPartner.getCode());
    assertTrue(noPartner.getMessage().contains("Nyxa BG (Company #1)"), noPartner.getMessage());
    assertTrue(noPartner.getMessage().contains("has no linked Partner"), noPartner.getMessage());
    assertTrue(noPartner.getMessage().contains("Users/Companies"), noPartner.getMessage());

    // Linked, but the Partner itself carries nothing usable — a different repair, named by id.
    OcrBlocked blankPartner =
        assertThrows(
            OcrBlocked.class,
            () ->
                svc.guardSupplierInvoiceCreation(
                    upload(company(1L, "Nyxa BG", partner(90L, "Nyxa BG", null, null))),
                    purchaseDocument(),
                    partner(77L, "Доставчик ООД", "831641791", null)));
    assertEquals(AiBlockerCode.MISSING_COMPANY_IDENTITY.wire(), blankPartner.getCode());
    assertTrue(blankPartner.getMessage().contains("Partner #90"), blankPartner.getMessage());
    assertTrue(blankPartner.getMessage().contains("Referential"), blankPartner.getMessage());
  }

  // ── direction ─────────────────────────────────────────────────────────────

  @Test
  void companyVendorAndExtractedBuyerResolveAsACustomerInvoice() {
    // Company 100000019 is the VENDOR and 100000026 the buyer → outgoing. Creation is refused and
    // the refusal names the reroute action rather than leaving the caller to guess.
    Company us = company(1L, "Nyxa BG", partner(90L, "Nyxa BG", OUR_EIK, "BG" + OUR_EIK));
    OcrBlocked refusal =
        assertThrows(
            OcrBlocked.class,
            () ->
                svc.guardSupplierInvoiceCreation(
                    upload(us), saleDocument(), partner(73L, "ТЕСТЕКС ООД", BUYER_EIK, null)));

    assertEquals(AiBlockerCode.NOT_A_SUPPLIER_INVOICE.wire(), refusal.getCode());
    assertTrue(refusal.getMessage().contains("SELLER"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("Reroute to Customer Invoice"), refusal.getMessage());
  }

  @Test
  void theActiveCompanysOwnEikPassedAsSupplierIsRefused() {
    Company us = company(1L, "Nyxa BG", partner(90L, "Nyxa BG", OUR_EIK, "BG" + OUR_EIK));

    // (a) the lookalike supplier row — a DIFFERENT Partner carrying our ЕИК.
    OcrBlocked byIdentifier =
        assertThrows(
            OcrBlocked.class,
            () ->
                svc.guardSupplierInvoiceCreation(
                    upload(us),
                    saleDocument(),
                    partner(77L, "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", OUR_EIK, "BG" + OUR_EIK)));
    assertEquals(AiBlockerCode.NOT_A_SUPPLIER_INVOICE.wire(), byIdentifier.getCode());
    assertTrue(
        byIdentifier.getMessage().contains("carries the ЕИК/ДДС of"), byIdentifier.getMessage());
    assertTrue(
        byIdentifier.getMessage().contains("Reroute to Customer Invoice"),
        byIdentifier.getMessage());

    // (b) the company's own linked Partner, named directly.
    OcrBlocked byRecord =
        assertThrows(
            OcrBlocked.class,
            () -> svc.guardSupplierInvoiceCreation(upload(us), saleDocument(), us.getPartner()));
    assertEquals(AiBlockerCode.NOT_A_SUPPLIER_INVOICE.wire(), byRecord.getCode());
    assertTrue(byRecord.getMessage().contains("Partner #90"), byRecord.getMessage());
  }

  @Test
  void aSupplierThatIsNotTheExtractedVendorIsRefused() {
    Company us = company(1L, "Nyxa BG", partner(90L, "Nyxa BG", OUR_EIK, "BG" + OUR_EIK));
    OcrBlocked refusal =
        assertThrows(
            OcrBlocked.class,
            () ->
                svc.guardSupplierInvoiceCreation(
                    upload(us),
                    purchaseDocument(), // document's vendor is 831641791
                    partner(88L, "Друг Доставчик ООД", "999888777", null)));

    assertEquals(AiBlockerCode.NOT_A_SUPPLIER_INVOICE.wire(), refusal.getCode());
    assertTrue(refusal.getMessage().contains("831641791"), refusal.getMessage());
  }

  // ── the legitimate case still works ───────────────────────────────────────

  @Test
  void aLegitimateSupplierInvoiceIsStillCreated() {
    Company us = company(1L, "Nyxa BG", partner(90L, "Nyxa BG", OUR_EIK, "BG" + OUR_EIK));
    assertDoesNotThrow(
        () ->
            svc.guardSupplierInvoiceCreation(
                upload(us),
                purchaseDocument(),
                partner(50L, "Доставчик ООД", "831641791", "BG831641791")));
  }

  @Test
  void anInvoiceThatAlreadyExistsNeverReachesTheGuard() {
    // Editing is untouched: the creation path is the ONLY caller of the guard, and it bows out on
    // an upload that already has an invoice before any identity or direction is looked at. So a
    // supplier invoice created legitimately stays editable exactly as before — including one whose
    // supplier would not pass the creation checks today.
    AiOcrUploadRepository repo = mock(AiOcrUploadRepository.class);
    Invoice existing = new Invoice();
    existing.setId(7L);
    AiOcrUpload u = upload(company(1L, "Nyxa BG", null)); // identity missing — and irrelevant here
    u.setId(27L);
    u.setInvoice(existing);
    when(repo.find(27L)).thenReturn(u);

    BgOcrIntakeService intake = new BgOcrIntakeService(null, repo, null, null, null);
    IllegalStateException refusal =
        assertThrows(
            IllegalStateException.class,
            () ->
                intake.createDraftForSupplier(
                    27L, partner(50L, "Доставчик ООД", "831641791", null)));

    // IllegalStateException, not OcrBlocked (an IllegalArgumentException): this is the structural
    // "nothing to create" refusal, not a guard verdict — the guard was never reached.
    assertTrue(refusal.getMessage().contains("already has invoice 7"), refusal.getMessage());
  }

  @Test
  void anOrdinaryPurchaseWithNoExtractedCustomerStillPasses() {
    // The common Bulgarian case: Azure returns no recipient at all. Nothing contradicts "we are the
    // buyer", so creation must NOT be blocked — the guard adds no new failure to ordinary intake.
    Company us = company(1L, "Nyxa BG", partner(90L, "Nyxa BG", OUR_EIK, "BG" + OUR_EIK));
    OcrExtractResult r = purchaseDocument();
    r.setCustomerEik(null);
    r.setCustomerVat(null);
    r.setCustomerName(null);

    assertDoesNotThrow(
        () ->
            svc.guardSupplierInvoiceCreation(
                upload(us), r, partner(50L, "Доставчик ООД", "831641791", null)));
  }
}
