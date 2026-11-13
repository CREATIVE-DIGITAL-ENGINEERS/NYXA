package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.repo.PaymentModeRepository;
import org.junit.jupiter.api.Test;

/** Unit tests for detecting the BG payment method from OCR text. */
class BgOcrPaymentMethodTest {

  @Test
  void detectsBankTransferFromRealInvoiceLine() {
    // Exact wording from the sample invoice (BG131391369_0003478613_20260601).
    assertEquals(
        PaymentModeRepository.TYPE_TRANSFER,
        BgOcrPaymentMethod.detectPaymentType("Начин на плащане: по банков път"));
  }

  @Test
  void detectsCash() {
    assertEquals(
        PaymentModeRepository.TYPE_CASH,
        BgOcrPaymentMethod.detectPaymentType("Начин на плащане: в брой"));
  }

  @Test
  void detectsCard() {
    assertEquals(
        PaymentModeRepository.TYPE_BANK_CARD,
        BgOcrPaymentMethod.detectPaymentType("Плащане с карта"));
  }

  @Test
  void detectsCardFromDebitCreditWording() {
    // Fixture P5 (manyinvoices_plusreciepts): a normal invoice with "Плащане Дебитна/Кредитна
    // карта" must resolve to a bank-card payment even though it is not receipt-style.
    assertEquals(
        PaymentModeRepository.TYPE_BANK_CARD,
        BgOcrPaymentMethod.detectPaymentType("Плащане\nДебитна/Кредитна карта"));
    assertEquals(
        PaymentModeRepository.TYPE_BANK_CARD,
        BgOcrPaymentMethod.detectPaymentType("Начин на плащане: С карта"));
  }

  @Test
  void arbitraryCardMentionsDoNotClassifyAsCard() {
    // A loyalty-card number or the plural "карти" on a fiscal-receipt line is NOT card-payment
    // wording — a bare "карта" must never trigger card detection.
    assertNull(BgOcrPaymentMethod.detectPaymentType("#Номер на карта : 0001236495"));
    assertNull(BgOcrPaymentMethod.detectPaymentType("кредитни/дебитни карти 24.91"));
  }

  @Test
  void detectsCheque() {
    assertEquals(
        PaymentModeRepository.TYPE_CHEQUE, BgOcrPaymentMethod.detectPaymentType("Плащане с чек"));
  }

  @Test
  void unknownOrMissingTextReturnsNull() {
    assertNull(BgOcrPaymentMethod.detectPaymentType(null));
    assertNull(BgOcrPaymentMethod.detectPaymentType("no payment hint here"));
    assertNull(BgOcrPaymentMethod.detectPaymentType(""));
  }

  @Test
  void labelsAreHumanReadable() {
    assertEquals("bank transfer", BgOcrPaymentMethod.label(PaymentModeRepository.TYPE_TRANSFER));
    assertEquals("cash", BgOcrPaymentMethod.label(PaymentModeRepository.TYPE_CASH));
    assertEquals("unknown", BgOcrPaymentMethod.label(null));
  }
}
