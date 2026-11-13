package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Редът, който слага „Приспадни авансите“, и отказът без статия при плащане — без база.
 *
 * <p>Пълното осчетоводяване на фактура не върви на HSQLDB; тук се проверява само, че двете
 * състояния на авансовата питат {@code requirePaymentMoves} ПРЕДИ AOS (останалите зависимости са
 * {@code null} — стигне ли се до AOS, тестът пада с друго изключение).
 */
class BgVatAdvanceServiceTest {

  private static final LocalDate JUNE_5 = LocalDate.of(2026, 6, 5);

  @Test
  @DisplayName("Редът за приспадане носи полетата, авансовата, текста за печат и сметката 412")
  void редът_на_бутона() {
    Invoice advance =
        BgVatAdvancePlanTest.advance(
            "0000000123",
            JUNE_5,
            BgVatAdvancePlanTest.rate(BgVatAdvancePlanTest.VAT20, "1000", "200"));
    BgVatAdvancePlan.Deduction d = BgVatAdvancePlan.deductions(advance).get(0);
    Account a412 = BgVatAdvancePlanTest.account("412");

    InvoiceLine line = BgVatAdvanceService.line(d, "Advance deduction", null, a412, 7);

    assertTrue(line.getBgAdvanceDeduction());
    assertSame(advance, line.getBgAdvanceInvoice());
    assertTrue(BgVatAdvancePlan.isDeduction(line));
    assertEquals("Advance deduction", line.getProductName());
    assertSame(a412, line.getAccount());
    assertEquals(7, line.getSequence());
    assertEquals(0, new BigDecimal("-1000").compareTo(line.getExTaxTotal()));
    assertEquals(0, new BigDecimal("-1200").compareTo(line.getInTaxTotal()));
  }

  @Test
  @DisplayName(
      "Ред за приспадане при покупка: кредитният тип на аванса (колекторът рутира по него)")
  void редът_при_покупка() {
    Invoice advance =
        BgVatAdvancePlanTest.purchaseAdvance(
            "П-1", "2", BgVatAdvancePlanTest.rate(BgVatAdvancePlanTest.VAT20, "1000", "200"));
    BgVatAdvancePlan.Deduction d = BgVatAdvancePlan.deductions(advance).get(0);

    InvoiceLine line =
        BgVatAdvanceService.line(d, "Аванс", null, BgVatAdvancePlanTest.account("402"), 1);

    assertEquals("2", line.getBgVatCreditType());
    assertSame(advance, line.getBgAdvanceInvoice());
  }

  /** Услугата, която отказва веднага — без база и без AOS. */
  private static BgVatAdvanceService refusing() {
    return new BgVatAdvanceService(null, null, null, null, null, null, null, null) {
      @Override
      public void requirePaymentMoves(Invoice invoice) throws AxelorException {
        throw new AxelorException(
            invoice, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, "отказ без статия");
      }
    };
  }

  @Test
  @DisplayName("Авансовата и КИ към нея: отказът без статия при плащане е преди AOS")
  void отказът_е_преди_aos() {
    Invoice advance = new Invoice();

    BgVentilateAdvancePaymentState state =
        new BgVentilateAdvancePaymentState(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            refusing());
    state.init(advance);
    assertEquals(
        "отказ без статия", assertThrows(AxelorException.class, state::process).getMessage());

    BgVentilateAdvancePaymentRefundState refund =
        new BgVentilateAdvancePaymentRefundState(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            refusing());
    refund.init(advance);
    assertEquals(
        "отказ без статия", assertThrows(AxelorException.class, refund::process).getMessage());
  }
}
