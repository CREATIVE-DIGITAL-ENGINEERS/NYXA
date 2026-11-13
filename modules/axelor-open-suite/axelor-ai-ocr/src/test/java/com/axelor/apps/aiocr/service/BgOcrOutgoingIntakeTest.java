package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Automatic intake of an OUTGOING invoice — the sale this company issued, which used to stop at
 * "Needs review: use the Reroute to Customer Invoice action".
 *
 * <p>Modelled on the real record 29: active company ПРИМЕРНА (100000019) sold to ТЕСТЕКС
 * (100000026); the document prints both identities under "ДДС №" only, so Azure returns no {@code
 * VendorTaxId} at all and the vendor block reaches routing empty.
 *
 * <p>What is proven here is the gate itself — that {@link
 * BgOcrIntakeService#routeCustomerInvoiceIfOutgoing} takes over exactly for outgoing documents,
 * refuses fail-closed for everything it cannot establish, and never builds a second invoice. The
 * draft construction below it is the manual reroute's own, already-covered code.
 */
class BgOcrOutgoingIntakeTest {

  private static final String OUR_EIK = "100000019";
  private static final String BUYER_EIK = "100000026";

  /** Identities under "ДДС №" only — no "ЕИК" label anywhere, which is what defeats Azure. */
  private static final String DDS_ONLY_TEXT =
      "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД\nДДС №\nBG100000019\n"
          + "Получател: ТЕСТЕКС ООД\nДДС №\nBG100000026\n"
          + "Услуги по договор\nДДС 20%: 1 000.00 лв.";

  /**
   * Records what the gate decided without building anything: {@code routeCustomerInvoice} is the
   * manual reroute's production method and is covered by its own tests.
   */
  private static class Intake extends BgOcrIntakeService {

    final List<String> calls = new ArrayList<>();
    final Map<String, List<Partner>> partnersByEik = new HashMap<>();
    final List<String> lookups = new ArrayList<>();
    Partner routedCustomer;
    String routedNote;

    Intake() {
      super(null, mock(AiOcrUploadRepository.class), null, null, null);
    }

    @Override
    protected List<Partner> matchPartnersForCompany(String canonicalEik, Long companyId) {
      lookups.add(canonicalEik + "@" + companyId);
      return partnersByEik.getOrDefault(canonicalEik, Collections.emptyList());
    }

    @Override
    protected void routeCustomerInvoice(
        AiOcrUpload upload, OcrExtractResult result, Partner customer, String note) {
      calls.add("routeCustomerInvoice");
      routedCustomer = customer;
      routedNote = note;
    }
  }

  private static Partner partner(long id, String eik) {
    Partner p = new Partner();
    p.setId(id);
    p.setRegistrationCode(eik);
    return p;
  }

  private static Company company(long id, String name, String eik) {
    Company c = new Company();
    c.setId(id);
    c.setName(name);
    c.setPartner(partner(500L + id, eik));
    return c;
  }

  private static AiOcrUpload upload(Company company) {
    AiOcrUpload u = new AiOcrUpload();
    u.setId(29L);
    u.setCompany(company);
    return u;
  }

  /** Azure's output for record 29: a customer identity, and nothing at all on the vendor side. */
  private static OcrExtractResult saleDocument() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierName("ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД");
    r.setCustomerName("ТЕСТЕКС ООД");
    r.setCustomerVat("BG" + BUYER_EIK);
    r.setCustomerEik(BUYER_EIK);
    r.setRawText(DDS_ONLY_TEXT);
    return r;
  }

  @Test
  void theRealRecord29IsBookedAsACustomerInvoiceWithoutAManualReroute() throws Exception {
    Intake svc = new Intake();
    Company us = company(4L, "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", OUR_EIK);
    AiOcrUpload u = upload(us);
    Partner buyer = partner(73L, BUYER_EIK);
    svc.partnersByEik.put(BUYER_EIK, Arrays.asList(buyer));
    OcrExtractResult r = saleDocument();

    assertTrue(svc.routeCustomerInvoiceIfOutgoing(u, r), "the outgoing document is handled here");

    assertEquals(OUR_EIK, r.getSupplierEik(), "the issuer ЕИК is recovered from ДДС №");
    assertEquals(Arrays.asList("routeCustomerInvoice"), svc.calls);
    assertSame(buyer, svc.routedCustomer, "the extracted BUYER becomes the invoice partner");
    assertEquals(
        Arrays.asList(BUYER_EIK + "@4"), svc.lookups, "one company-scoped lookup, for the buyer");
    assertTrue(svc.routedNote.contains("review it before validating"), svc.routedNote);
  }

  @Test
  void anIncomingInvoiceStillGoesDownTheSupplierPath() throws Exception {
    // The same document filed under the BUYER: this company is the customer, so the gate declines
    // and routeInvoice continues into the untouched supplier flow.
    Intake svc = new Intake();
    AiOcrUpload u = upload(company(2L, "ТЕСТЕКС - ООД", BUYER_EIK));
    OcrExtractResult r = saleDocument();

    assertFalse(
        svc.routeCustomerInvoiceIfOutgoing(u, r), "an incoming invoice is not handled here");

    assertTrue(svc.calls.isEmpty(), svc.calls.toString());
    assertTrue(svc.lookups.isEmpty(), "no buyer resolution may run for a purchase");
    assertNotEquals(
        AiOcrUploadStatus.NEEDS_REVIEW,
        u.getStatusSelect(),
        "the gate must not flag anything on the supplier path");
    assertNull(u.getExtractionErrors(), "and must write no reason of its own");
  }

  @Test
  void anAmbiguousIssuerIsLeftToTheExistingDirectionGate() throws Exception {
    // A third identity on the page makes the issuer unreadable: nothing is guessed, the gate
    // declines, and the existing direction check flags the record with its own reason.
    Intake svc = new Intake();
    AiOcrUpload u = upload(company(4L, "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", OUR_EIK));
    OcrExtractResult r = saleDocument();
    r.setRawText(DDS_ONLY_TEXT + "\nПосредник АД\nДДС №\nBG203999888\n");

    assertFalse(svc.routeCustomerInvoiceIfOutgoing(u, r));

    assertNull(r.getSupplierEik(), "two candidates must never be guessed between");
    assertTrue(svc.calls.isEmpty(), svc.calls.toString());
    assertFalse(svc.checkDirectionOrReview(u, r), "the existing gate then stops routing");
    assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect());
  }

  @Test
  void aBuyerThatIsNotAvailableToThisCompanyStopsAtNeedsReview() throws Exception {
    // The issuer IS us, so the gate owns the document — but the buyer resolves to no partner of
    // this company. Fail-closed: Needs review with the reason, no Partner and no Invoice created.
    Intake svc = new Intake();
    AiOcrUpload u = upload(company(4L, "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", OUR_EIK));
    OcrExtractResult r = saleDocument();

    assertTrue(svc.routeCustomerInvoiceIfOutgoing(u, r), "the refusal is handled here, not below");

    assertTrue(svc.calls.isEmpty(), "no invoice may be built: " + svc.calls);
    assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, u.getStatusSelect());
    assertTrue(u.getExtractionErrors().contains("No partner with EIK"), u.getExtractionErrors());
    assertNull(u.getInvoice());
  }

  @Test
  void aReprocessNeverCreatesASecondCustomerInvoice() throws Exception {
    Intake svc = new Intake();
    Company us = company(4L, "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД", OUR_EIK);
    AiOcrUpload u = upload(us);
    svc.partnersByEik.put(BUYER_EIK, Arrays.asList(partner(73L, BUYER_EIK)));
    Invoice already = new Invoice();
    already.setId(4242L);
    u.setInvoice(already);

    assertTrue(svc.routeCustomerInvoiceIfOutgoing(u, saleDocument()));

    assertTrue(svc.calls.isEmpty(), "the existing draft is kept: " + svc.calls);
    assertSame(already, u.getInvoice());
  }
}
