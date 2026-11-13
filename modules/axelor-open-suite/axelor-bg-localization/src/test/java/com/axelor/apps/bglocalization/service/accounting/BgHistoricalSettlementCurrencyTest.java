package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.service.AccountingCurrencyPolicy;
import com.axelor.apps.base.db.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one decision that keeps a migrated settlement from asking for an exchange rate.
 *
 * <p>FY2016's 11 mixed-currency settlements failed in {@code
 * ReconcileInvoiceTermComputationServiceImpl.updatePayment}: with no Invoice on either side it fell
 * through to {@code getAmountCurrencyConvertedAtDate(otherMove.currency, move.currency, …,
 * move.date)}, and the two Moves carried different DOCUMENT currencies (one EUR invoice, one BGN
 * payment) although both were booked in BGN — so Axelor asked for a EUR→BGN rate on a 2016 date.
 * {@code isExactHistoricalSettlement} is what now sends that case to the booked amount instead.
 *
 * <p>Database-free: Move and Currency are plain entities here.
 */
class BgHistoricalSettlementCurrencyTest {

  private static Currency currency(String code) {
    Currency currency = new Currency();
    currency.setCodeISO(code);
    currency.setName(code);
    return currency;
  }

  private static final Currency BGN = currency("BGN");
  private static final Currency EUR = currency("EUR");

  /** A Move as the Microinvest importer writes it: BGN book, document currency as issued. */
  private static Move historical(Currency documentCurrency) {
    Move move = new Move();
    move.setBgMigratedHistorical(true);
    move.setBgBookCurrency(BGN);
    move.setCompanyCurrency(EUR);
    move.setCurrency(documentCurrency);
    return move;
  }

  /** An ordinary operational Move: no historical book at all. */
  private static Move operational(Currency documentCurrency) {
    Move move = new Move();
    move.setCompanyCurrency(EUR);
    move.setCurrency(documentCurrency);
    return move;
  }

  private final BgAccountingCurrencyPolicy policy = new BgAccountingCurrencyPolicy();

  @Test
  @DisplayName("two migrated BGN moves settle exactly, even with different document currencies")
  void mixedDocumentCurrencyHistoricalSettlementIsExact() {
    assertTrue(policy.isExactHistoricalSettlement(historical(EUR), historical(BGN)));
    assertTrue(policy.isExactHistoricalSettlement(historical(BGN), historical(EUR)));
    assertTrue(policy.isExactHistoricalSettlement(historical(BGN), historical(BGN)));
  }

  @Test
  @DisplayName("an ordinary reconciliation keeps Axelor's currency conversion")
  void operationalSettlementIsNotExempt() {
    assertFalse(policy.isExactHistoricalSettlement(operational(EUR), operational(BGN)));
    assertFalse(policy.isExactHistoricalSettlement(operational(EUR), operational(EUR)));
    // Mixing one migrated side with an operational one is not a historical settlement either —
    // ReconcileServiceImpl.confirmReconcile refuses that pair outright.
    assertFalse(policy.isExactHistoricalSettlement(historical(BGN), operational(BGN)));
  }

  @Test
  @DisplayName("different book currencies are not an exact settlement")
  void differentBookCurrenciesAreNotExempt() {
    Move eurBook = historical(EUR);
    eurBook.setBgBookCurrency(EUR);
    assertFalse(policy.isExactHistoricalSettlement(historical(BGN), eurBook));
  }

  @Test
  @DisplayName("stock Axelor has no historical books, so nothing is ever exempt")
  void stockPolicyNeverExempts() {
    AccountingCurrencyPolicy stock = new AccountingCurrencyPolicy();
    assertFalse(stock.isExactHistoricalSettlement(historical(EUR), historical(BGN)));
    assertFalse(stock.isExactHistoricalSettlement(operational(EUR), operational(BGN)));
    assertFalse(stock.isExactHistoricalSettlement(null, null));
  }
}
