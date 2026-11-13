package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The customer-invoice reroute converts a draft that {@code buildDraft} produced in its PURCHASE
 * shape. These tests pin the two halves of that conversion that would silently produce wrong
 * accounting if they regressed:
 *
 * <ul>
 *   <li>{@code swapCounterpartyToCustomerSide} — the buyer, not this company, is what the draft
 *       builder sees as the counterparty (identities, address, bank account);
 *   <li>{@code applySaleDirection} — operation type, journal, payment mode, line accounts and line
 *       VAT are all moved to the sale side, and the purchase-side values are never left behind.
 * </ul>
 *
 * <p>Plain unit tests: the stock services the conversion calls are unavailable here (no injector),
 * which is itself part of what is being proven — every one of those calls is optional and its
 * failure must CLEAR the purchase-side value rather than keep it.
 */
class BgOcrCustomerInvoiceDirectionTest {

  private static final String OUR_EIK = "131391369";
  private static final String BUYER_EIK = "205123456";

  /**
   * Only the VAT summary needs suppressing (it must reach a DB-backed service), plus the stock
   * company-bank-details resolution, which is the seam these tests drive: {@code
   * companyBankDetails} is whatever STOCK returns, and {@code null} when stock cannot safely
   * choose.
   */
  private static class Intake extends BgOcrIntakeService {
    BankDetails stockCompanyAccount;
    int companyResolutions;

    Intake() {
      super(null, null, null, null, null);
    }

    @Override
    protected void syncTaxSummary(Invoice invoice, List<String> reviewNotes) {
      // needs the DB-backed tax-summary service; out of scope for a unit test
    }

    @Override
    protected BankDetails resolveCompanyBankDetails(Invoice invoice) {
      companyResolutions++;
      return stockCompanyAccount;
    }
  }

  private static BankDetails account(long id, String iban, Partner owner) {
    BankDetails bankDetails = new BankDetails();
    bankDetails.setId(id);
    bankDetails.setIban(iban);
    bankDetails.setActive(true);
    bankDetails.setIsDefault(true);
    bankDetails.setPartner(owner);
    return bankDetails;
  }

  private static Partner partner(long id, String eik, String name) {
    Partner partner = new Partner();
    partner.setId(id);
    partner.setRegistrationCode(eik);
    partner.setName(name);
    return partner;
  }

  /** What Azure read off a sale invoice THIS company issued: vendor = us, customer = the buyer. */
  private static OcrExtractResult saleDocument() {
    OcrExtractResult result = new OcrExtractResult();
    result.setInvoiceNumber("0000001234");
    result.setSupplierName("Nyxa Test EOOD");
    result.setSupplierEik(OUR_EIK);
    result.setSupplierVat("BG" + OUR_EIK);
    result.setSupplierAddress("гр. София, ул. Наша 1");
    result.setSupplierAddrCity("София");
    result.setSupplierAddrStreet("Наша");
    result.setSupplierAddrNumber("1");
    result.setSupplierAddrZip("1000");
    result.setIban("BG80BNBG96611020345678");
    result.setSwift("BNBGBGSF");
    result.setCustomerName("Купувач ООД");
    result.setCustomerEik(BUYER_EIK);
    result.setCustomerVat("BG" + BUYER_EIK);
    result.setCustomerAddress("гр. Пловдив, ул. Тяхна 5");
    return result;
  }

  @Test
  void theBuyerReplacesThisCompanyOnTheCounterpartySideOfTheExtraction() {
    OcrExtractResult result = saleDocument();

    new Intake().swapCounterpartyToCustomerSide(result, partner(1L, OUR_EIK, "Nyxa Test EOOD"));

    // What buildDraft reads as "the party this invoice is with" is now the BUYER.
    assertEquals(BUYER_EIK, result.getSupplierEik());
    assertEquals("BG" + BUYER_EIK, result.getSupplierVat());
    assertEquals("Купувач ООД", result.getSupplierName());
    assertEquals("гр. Пловдив, ул. Тяхна 5", result.getSupplierAddress());
    // Our own structured address must not survive as the invoice address.
    assertNull(result.getSupplierAddrCity());
    assertNull(result.getSupplierAddrStreet());
    assertNull(result.getSupplierAddrNumber());
    assertNull(result.getSupplierAddrZip());
    // The IBAN printed on a sale invoice is OURS — it must never be attached to the buyer.
    assertNull(result.getIban());
    assertNull(result.getSwift());
    // ...and this company is now the other side, so the identity cross-check stays coherent.
    assertEquals(OUR_EIK, result.getCustomerEik());
  }

  @Test
  void applySaleDirectionMovesEveryPurchaseValueToTheSaleSide() {
    Invoice draft = new Invoice();
    Company company = new Company();
    company.setId(10L);
    draft.setCompany(company);
    draft.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    draft.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    draft.setSupplierInvoiceNb("0000001234");
    draft.setInTaxTotal(new BigDecimal("120.00"));
    Journal purchaseJournal = new Journal();
    purchaseJournal.setCode("PCH");
    draft.setJournal(purchaseJournal);
    PaymentMode outMode = new PaymentMode();
    outMode.setCode("OUT_WT");
    draft.setPaymentMode(outMode);

    InvoiceLine line = new InvoiceLine();
    Account purchaseAccount = new Account();
    purchaseAccount.setCode("602");
    line.setAccount(purchaseAccount);
    line.setTaxCode(BgOcrVatMapping.BG_STD_20);
    // A rate with no mapped sale code: the purchase tax must still be removed, never kept.
    line.setTaxRate(new BigDecimal("7"));
    draft.setInvoiceLineList(new ArrayList<>(List.of(line)));

    Partner buyer = partner(900L, BUYER_EIK, "Купувач ООД");
    buyer.setTaxNbr("BG" + BUYER_EIK);

    new Intake().applySaleDirection(draft, saleDocument(), buyer);

    assertEquals(
        InvoiceRepository.OPERATION_TYPE_CLIENT_SALE,
        draft.getOperationTypeSelect().intValue(),
        "the draft must become a client sale");
    assertNull(draft.getSupplierInvoiceNb(), "a sale carries no supplier invoice number");
    assertEquals(
        "0000001234", draft.getExternalReference(), "the document number is kept for traceability");
    assertEquals("BG" + BUYER_EIK, draft.getPartnerTaxNbr(), "the buyer's VAT, not the vendor's");
    assertNull(
        draft.getJournal(),
        "the PCH purchase journal must be dropped when no sales journal resolves");
    assertNull(draft.getPaymentMode(), "an outgoing payment mode must not stay on a sale");
    assertNull(
        draft.getBankDetails(), "the document's (our own) IBAN must not stay on the invoice");
    assertNull(
        draft.getCompanyBankDetails(),
        "unresolved company bank details must stay empty, never a guessed default");
    assertNull(line.getAccount(), "a purchase expense account must never survive onto a sale line");
    assertNull(line.getTaxCode(), "an input-VAT code must never survive onto a sale line");
    assertTrue(
        line.getTaxLineSet() == null || line.getTaxLineSet().isEmpty(),
        "the purchase tax line must be removed too");
    assertEquals(
        BgOcrIntakeService.OCR_REVIEW_TO_REVIEW,
        draft.getOcrReviewStatus(),
        "a rerouted sale invoice is always left to review");
    assertTrue(
        draft.getAiValidationNotes().contains("sales journal"),
        "the reviewer must be told what could not be resolved: " + draft.getAiValidationNotes());
  }

  // -------------------------------------------------------------------------------------------
  // Bank accounts. Stock semantics: Invoice.bankDetails is the PARTNER's account (its form domain
  // is built from the partner's own active accounts), Invoice.companyBankDetails is the company's.
  // A rerouted sale therefore leaves bankDetails empty and resolves companyBankDetails via stock.
  // -------------------------------------------------------------------------------------------

  /**
   * A draft as buildDraft leaves it: purchase-shaped, with an OCR-matched account already on it.
   */
  private static Invoice purchaseShapedDraft(Company company) {
    Invoice draft = new Invoice();
    draft.setCompany(company);
    draft.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    draft.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    draft.setInTaxTotal(new BigDecimal("120.00"));
    draft.setInvoiceLineList(new ArrayList<>());
    return draft;
  }

  @Test
  void theBuyersOwnBankAccountIsNeverPutOnTheSalesInvoice() {
    Company company = new Company();
    company.setId(10L);
    Partner buyer = partner(900L, BUYER_EIK, "Купувач ООД");
    BankDetails buyersAccount = account(700L, "BG18RZBB91550123456789", buyer);
    buyer.setBankDetailsList(new ArrayList<>(List.of(buyersAccount)));

    Invoice draft = purchaseShapedDraft(company);
    draft.setPartner(buyer);
    // Whatever the purchase-side pass left here (the document's IBAN is the ISSUER's) must go.
    draft.setBankDetails(account(701L, "BG80BNBG96611020345678", null));

    Intake intake = new Intake();
    intake.stockCompanyAccount = account(800L, "BG11UNCR70001234567890", null);
    intake.applySaleDirection(draft, saleDocument(), buyer);

    assertNull(
        draft.getBankDetails(),
        "neither the document IBAN nor the buyer's default account may end up here");
    assertNotSame(buyersAccount, draft.getBankDetails());
    assertNotSame(buyersAccount, draft.getCompanyBankDetails());
  }

  @Test
  void theStockResolvedCompanyAccountIsAccepted() {
    Company company = new Company();
    company.setId(10L);
    Partner buyer = partner(900L, BUYER_EIK, "Купувач ООД");
    Invoice draft = purchaseShapedDraft(company);
    draft.setPartner(buyer);
    // enrichHeader's blind company default, which must be replaced by the stock resolution.
    draft.setCompanyBankDetails(account(801L, "BG55STSA93000012345678", null));

    Intake intake = new Intake();
    BankDetails stockChoice = account(800L, "BG11UNCR70001234567890", null);
    intake.stockCompanyAccount = stockChoice;
    intake.applySaleDirection(draft, saleDocument(), buyer);

    assertEquals(1, intake.companyResolutions, "the stock resolver must be asked exactly once");
    assertSame(
        stockChoice,
        draft.getCompanyBankDetails(),
        "the account stock resolves for this company + payment mode is the one that is used");
    assertTrue(
        !draft.getAiValidationNotes().contains("No company bank account"),
        "no missing-account note when stock resolved one");
  }

  @Test
  void unresolvedCompanyBankDetailsStaysEmptyAndTheInvoiceStaysToReview() {
    Company company = new Company();
    company.setId(10L);
    Partner buyer = partner(900L, BUYER_EIK, "Купувач ООД");
    Invoice draft = purchaseShapedDraft(company);
    draft.setPartner(buyer);
    draft.setCompanyBankDetails(account(801L, "BG55STSA93000012345678", null));

    Intake intake = new Intake();
    intake.stockCompanyAccount = null; // stock cannot safely choose
    intake.applySaleDirection(draft, saleDocument(), buyer);

    assertNull(draft.getCompanyBankDetails(), "never fall back to a guessed company default");
    assertNull(draft.getBankDetails());
    assertEquals(BgOcrIntakeService.OCR_REVIEW_TO_REVIEW, draft.getOcrReviewStatus());
    assertTrue(
        draft.getAiValidationNotes().contains("No company bank account"),
        "the reviewer must be told: " + draft.getAiValidationNotes());
  }

  @Test
  void saleVatCodesMirrorThePurchaseOnesAndNeverOverlap() {
    assertEquals("BG_SRS_20", BgOcrVatMapping.saleTaxCodeForRate(new BigDecimal("20")));
    assertEquals("BG_LRS_9", BgOcrVatMapping.saleTaxCodeForRate(new BigDecimal("9")));
    assertEquals("BG_ZRS", BgOcrVatMapping.saleTaxCodeForRate(BigDecimal.ZERO));
    assertNull(BgOcrVatMapping.saleTaxCodeForRate(new BigDecimal("7")));
    assertNull(BgOcrVatMapping.saleTaxCodeForRate(null));

    for (String saleCode : BgOcrVatMapping.saleTaxCodes()) {
      assertTrue(
          !BgOcrVatMapping.purchaseTaxCodes().contains(saleCode),
          "a sale code must never be a purchase code: " + saleCode);
    }
    assertNotEquals(
        BgOcrVatMapping.taxCodeForRate(new BigDecimal("20")),
        BgOcrVatMapping.saleTaxCodeForRate(new BigDecimal("20")));
  }
}
