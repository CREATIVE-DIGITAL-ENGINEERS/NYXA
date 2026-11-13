package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import org.junit.jupiter.api.Test;

/**
 * The company-direction gate in front of automatic supplier-invoice creation: a document is only
 * booked as a purchase of the upload's company when that company is identifiable on the document as
 * the BUYER.
 *
 * <p>Modelled on the real invoice that exposed the gap — vendor ПРИМЕРНА (100000019) to customer
 * ТЕСТЕКС (100000026), printing both identities under "ДДС №" only, with no "ЕИК" label anywhere
 * and no {@code VendorTaxId} from Azure. Filed under a third company with no ЕИК of its own, it
 * used to become that company's supplier invoice.
 */
class BgOcrCompanyDirectionGuardTest {

  private static final String VENDOR_EIK = "100000019";
  private static final String CUSTOMER_EIK = "100000026";

  /** The document text as Azure returns it: identities only under "ДДС №", never "ЕИК". */
  private static final String DDS_ONLY_TEXT =
      "ТЕСТЕКС ООД\nДДС №\nBG100000026\n"
          + "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД\nДДС №\nBG100000019\n"
          + "Услуги по договор\nДДС 20%: 1 000.00 лв.";

  /**
   * Only {@code uploadRepo} is reachable from the gate; the real refusal path then runs for real.
   */
  private static class Intake extends BgOcrIntakeService {
    Intake() {
      super(null, mock(AiOcrUploadRepository.class), null, null, null);
    }
  }

  private final Intake svc = new Intake();

  private static Company company(String name, String eik) {
    Company c = new Company();
    c.setName(name);
    Partner p = new Partner();
    p.setRegistrationCode(eik);
    c.setPartner(p);
    return c;
  }

  private static AiOcrUpload upload(Company company) {
    AiOcrUpload u = new AiOcrUpload();
    u.setCompany(company);
    return u;
  }

  /** Azure's output for this document: a customer identity, but no vendor one. */
  private static OcrExtractResult extracted() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierName("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД");
    r.setCustomerName("ТЕСТЕКС ООД");
    r.setCustomerVat("BG" + CUSTOMER_EIK);
    r.setCustomerEik(CUSTOMER_EIK);
    r.setRawText(DDS_ONLY_TEXT);
    return r;
  }

  @Test
  void companyIsTheBuyerSoTheRecoveredSupplierInvoiceIsAllowed() {
    // (1) + (5) together: the "ДДС №" fallback recovers the vendor ЕИК Azure never returned, and
    // the gate then confirms this company as the buyer.
    Company us = company("ТЕСТЕКС ООД", CUSTOMER_EIK);
    AiOcrUpload u = upload(us);
    OcrExtractResult r = extracted();

    svc.enrichSupplierEik(r, u);
    assertEquals(VENDOR_EIK, r.getSupplierEik(), "vendor ЕИК recovered from the ДДС № label");

    assertTrue(svc.checkDirectionOrReview(u, r));
    assertNotEquals(
        AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect(), "an allowed document is not flagged");
    assertNull(u.getExtractionErrors());
  }

  @Test
  void companyIsTheVendorSoNoSupplierInvoiceIsCreated() {
    // The sale this company issued. Refused as a supplier invoice; the message keeps pointing at
    // the outgoing/customer-invoice route rather than inventing a purchase.
    Company us = company("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", VENDOR_EIK);
    AiOcrUpload u = upload(us);
    OcrExtractResult r = extracted();
    r.setSupplierVat("BG" + VENDOR_EIK); // Azure did read the vendor here
    r.setSupplierEik(VENDOR_EIK);

    assertFalse(svc.checkDirectionOrReview(u, r));
    assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect());
    assertTrue(u.getExtractionErrors().contains("SELLER"), u.getExtractionErrors());
  }

  /**
   * The outgoing counterpart of {@link #companyIsTheBuyerSoTheRecoveredSupplierInvoiceIsAllowed}:
   * on the customer reroute this company IS the issuer, so its own ЕИК must remain a valid
   * candidate — excluding it (as the supplier path must) deletes the only number the reroute's
   * identity check can match, and the document gets refused with "vendor EIK/VAT read: none".
   */
  @Test
  void theCustomerRerouteRecoveryKeepsOurOwnEikAsAnIssuerCandidate() {
    OcrExtractResult r = extracted(); // no supplier ЕИК/ДДС: Azure returned no VendorTaxId

    svc.recoverIssuerEikForCustomerReroute(r);

    assertEquals(VENDOR_EIK, r.getSupplierEik(), "our own ЕИК is a valid issuer on a sale");
  }

  /** Two issuer candidates besides the buyer: ambiguous, so nothing is filled in. */
  @Test
  void theCustomerRerouteRecoveryRefusesAnAmbiguousIssuer() {
    OcrExtractResult r = extracted();
    r.setRawText(DDS_ONLY_TEXT + "\nПосредник АД\nДДС №\nBG203999888\n");

    svc.recoverIssuerEikForCustomerReroute(r);

    assertNull(r.getSupplierEik(), "two candidates must never be guessed between");
  }

  /** The supplier path is untouched: the active company's own ЕИК is still never its supplier. */
  @Test
  void theSupplierRecoveryStillExcludesOurOwnEik() {
    Company us = company("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", VENDOR_EIK);
    AiOcrUpload u = upload(us);
    OcrExtractResult r = extracted();

    svc.enrichSupplierEik(r, u);

    assertNull(r.getSupplierEik(), "our own ЕИК may never be recovered as a supplier");
  }

  @Test
  void companyIsNeitherPartySoNoInvoiceIsCreated() {
    // Record 26's real shape once the company HAS an ЕИК: a document between two other parties.
    Company us = company("Nyxa BG", "131391369");
    AiOcrUpload u = upload(us);
    OcrExtractResult r = extracted();
    r.setSupplierEik(VENDOR_EIK);

    assertFalse(svc.checkDirectionOrReview(u, r));
    assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect());
    assertTrue(u.getExtractionErrors().contains("Nyxa BG"), u.getExtractionErrors());
  }

  @Test
  void companyWithNoLinkedPartnerIsNeverAssumedToBeAParty() {
    // The live shape of Company #1: Company.partner is null, while a separate supplier Partner row
    // carries the company's own ЕИК. Fail closed instead of defaulting to "we are the buyer", and
    // say which record to correct.
    Company us = new Company();
    us.setName("Nyxa BG");
    AiOcrUpload u = upload(us);
    OcrExtractResult r = extracted();
    r.setSupplierEik(VENDOR_EIK);

    assertFalse(svc.checkDirectionOrReview(u, r));
    assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect());
    assertTrue(u.getExtractionErrors().contains("has no linked Partner"), u.getExtractionErrors());
    assertTrue(u.getExtractionErrors().contains("Nyxa BG"), u.getExtractionErrors());
  }

  @Test
  void companyWhoseLinkedPartnerHasNoEikIsNeverAssumedToBeAParty() {
    // Linked, but the Partner itself carries no ЕИК/ДДС — a different repair, in a different menu.
    Company us = company("Nyxa BG", null);
    AiOcrUpload u = upload(us);
    OcrExtractResult r = extracted();
    r.setSupplierEik(VENDOR_EIK);

    assertFalse(svc.checkDirectionOrReview(u, r));
    assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect());
    assertTrue(u.getExtractionErrors().contains("carries no usable ЕИК"), u.getExtractionErrors());
    assertTrue(u.getExtractionErrors().contains("Referential"), u.getExtractionErrors());
  }

  @Test
  void placeholderCompanyIdentifierIsNotAnIdentity() {
    // "N/A" typed into the registration field is not an ЕИК — it must not satisfy the gate.
    Company us = company("Nyxa BG", "N/A");
    AiOcrUpload u = upload(us);
    OcrExtractResult r = extracted();
    r.setSupplierEik(VENDOR_EIK);

    assertFalse(svc.checkDirectionOrReview(u, r));
    assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect());
  }
}
