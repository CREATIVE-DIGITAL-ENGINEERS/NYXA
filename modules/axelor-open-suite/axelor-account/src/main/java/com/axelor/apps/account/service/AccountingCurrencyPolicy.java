package com.axelor.apps.account.service;

import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Year;

/** Extension point for installations with more than one historical book currency per company. */
public class AccountingCurrencyPolicy {
  public Currency bookCurrency(Move move) {
    return move.getCompanyCurrency() != null
        ? move.getCompanyCurrency()
        : move.getCompany().getCurrency();
  }

  public boolean isHistoricalBook(Move move) {
    return false;
  }

  /**
   * Both sides of a settlement are migrated historical books kept in the SAME book currency.
   *
   * <p>Such a settlement is exact in that currency: the debit and credit amounts are the amounts
   * that were booked, and the document currency carried on the Move is evidence of the original
   * document, not an input for a conversion. Asking for a rate on a migrated date would either
   * invent one or refuse the settlement outright.
   *
   * <p>Default installations have no historical books, so this is always false and every
   * reconciliation keeps Axelor's usual conversion and validation.
   */
  public boolean isExactHistoricalSettlement(Move debitMove, Move creditMove) {
    return debitMove != null
        && creditMove != null
        && isHistoricalBook(debitMove)
        && isHistoricalBook(creditMove)
        && java.util.Objects.equals(bookCurrency(debitMove), bookCurrency(creditMove));
  }

  public String currentMoveFilter(String moveAlias, boolean sql) {
    return "";
  }

  public void checkStockReport(AccountingReport report) {}

  public void checkYearClosing(Year year) {}
}
