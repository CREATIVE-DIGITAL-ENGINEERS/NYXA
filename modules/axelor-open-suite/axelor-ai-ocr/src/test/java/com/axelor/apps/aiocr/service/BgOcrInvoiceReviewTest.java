package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The OCR "To review" completeness rule: partner + address + currency + total — what booking the
 * invoice needs. Bank details and the payment condition are needed to pay it, so they are warnings
 * ({@code reviewWarnings}) and never lock Validate — a missing bank account alone must not lock it.
 * A missing payment condition still stops Ventilate — that is said by {@code
 * SupplierInvoiceButtonCheck} when Ventilate is pressed.
 */
class BgOcrInvoiceReviewTest {

  @Test
  void emptyInvoiceMissesEveryReviewField() {
    List<String> missing = BgOcrIntakeService.missingReviewFields(new Invoice());
    assertEquals(List.of("supplier (partner)", "address", "currency", "total"), missing);
    assertEquals(
        List.of("bank details", "payment condition"),
        BgOcrIntakeService.reviewWarnings(new Invoice()));
  }

  @Test
  void fullyFilledInvoiceIsComplete() {
    Invoice invoice = new Invoice();
    invoice.setPartner(new Partner());
    invoice.setAddress(new Address());
    invoice.setBankDetails(new BankDetails());
    invoice.setCurrency(new Currency());
    invoice.setPaymentCondition(new PaymentCondition());
    invoice.setInTaxTotal(new BigDecimal("240.33"));
    assertTrue(BgOcrIntakeService.missingReviewFields(invoice).isEmpty());
  }

  @Test
  void zeroOrNegativeTotalCountsAsMissing() {
    Invoice invoice = new Invoice();
    invoice.setPartner(new Partner());
    invoice.setAddress(new Address());
    invoice.setBankDetails(new BankDetails());
    invoice.setCurrency(new Currency());
    invoice.setPaymentCondition(new PaymentCondition());
    invoice.setInTaxTotal(BigDecimal.ZERO);
    assertEquals(List.of("total"), BgOcrIntakeService.missingReviewFields(invoice));
  }

  @Test
  void missingBankDetailsIsAWarningNotALock() {
    Invoice invoice = new Invoice();
    invoice.setPartner(new Partner());
    invoice.setAddress(new Address());
    invoice.setCurrency(new Currency());
    invoice.setPaymentCondition(new PaymentCondition());
    invoice.setInTaxTotal(new BigDecimal("10"));
    assertTrue(BgOcrIntakeService.missingReviewFields(invoice).isEmpty());
    assertEquals(List.of("bank details"), BgOcrIntakeService.reviewWarnings(invoice));
  }

  @Test
  void missingPaymentConditionIsAWarningNotALock() {
    Invoice invoice = new Invoice();
    invoice.setPartner(new Partner());
    invoice.setAddress(new Address());
    invoice.setBankDetails(new BankDetails());
    invoice.setCurrency(new Currency());
    invoice.setInTaxTotal(new BigDecimal("10"));
    assertTrue(BgOcrIntakeService.missingReviewFields(invoice).isEmpty());
    assertEquals(List.of("payment condition"), BgOcrIntakeService.reviewWarnings(invoice));
  }

  @Test
  void eachBookingFieldLocksOnItsOwn() {
    Invoice noPartner = complete(PaymentModeRepository.TYPE_TRANSFER);
    noPartner.setPartner(null);
    assertEquals(List.of("supplier (partner)"), BgOcrIntakeService.missingReviewFields(noPartner));
    Invoice noAddress = complete(PaymentModeRepository.TYPE_TRANSFER);
    noAddress.setAddress(null);
    assertEquals(List.of("address"), BgOcrIntakeService.missingReviewFields(noAddress));
    Invoice noCurrency = complete(PaymentModeRepository.TYPE_TRANSFER);
    noCurrency.setCurrency(null);
    assertEquals(List.of("currency"), BgOcrIntakeService.missingReviewFields(noCurrency));
    Invoice noTotal = complete(PaymentModeRepository.TYPE_TRANSFER);
    noTotal.setInTaxTotal(null);
    assertEquals(List.of("total"), BgOcrIntakeService.missingReviewFields(noTotal));
  }

  @Test
  void cardPaymentDoesNotRequireBankDetails() {
    // A receipt-style invoice paid "Плащане с кредитна карта" (bank-card OUT mode) needs no bank
    // account: missing bank details must NOT appear, so it can be Completed / Validate-able.
    Invoice invoice = complete(PaymentModeRepository.TYPE_BANK_CARD);
    invoice.setBankDetails(null);
    assertFalse(BgOcrIntakeService.paymentRequiresBankDetails(invoice));
    assertTrue(BgOcrIntakeService.missingReviewFields(invoice).isEmpty());
    assertTrue(BgOcrIntakeService.reviewWarnings(invoice).isEmpty());
  }

  @Test
  void cashAndOtherDoNotRequireBankDetails() {
    for (int type : new int[] {PaymentModeRepository.TYPE_CASH, PaymentModeRepository.TYPE_OTHER}) {
      Invoice invoice = complete(type);
      invoice.setBankDetails(null);
      assertFalse(BgOcrIntakeService.paymentRequiresBankDetails(invoice));
      assertTrue(BgOcrIntakeService.missingReviewFields(invoice).isEmpty());
      assertTrue(BgOcrIntakeService.reviewWarnings(invoice).isEmpty());
    }
  }

  @Test
  void transferAndDirectDebitWarnAboutBankDetails() {
    for (int type :
        new int[] {PaymentModeRepository.TYPE_TRANSFER, PaymentModeRepository.TYPE_DD}) {
      Invoice invoice = complete(type);
      invoice.setBankDetails(null);
      assertTrue(BgOcrIntakeService.paymentRequiresBankDetails(invoice));
      assertTrue(BgOcrIntakeService.missingReviewFields(invoice).isEmpty());
      assertEquals(List.of("bank details"), BgOcrIntakeService.reviewWarnings(invoice));
    }
  }

  private static Invoice complete(int paymentType) {
    Invoice invoice = new Invoice();
    invoice.setPartner(new Partner());
    invoice.setAddress(new Address());
    invoice.setBankDetails(new BankDetails());
    invoice.setCurrency(new Currency());
    invoice.setPaymentCondition(new PaymentCondition());
    invoice.setInTaxTotal(new BigDecimal("10"));
    PaymentMode mode = new PaymentMode();
    mode.setTypeSelect(paymentType);
    invoice.setPaymentMode(mode);
    return invoice;
  }
}
