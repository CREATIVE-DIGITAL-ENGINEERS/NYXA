package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.service.AccountingCurrencyPolicy;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.db.Query;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * One company, explicit fiscal-year books, and an operational EUR balance starting at changeover.
 */
public class BgAccountingCurrencyPolicy extends AccountingCurrencyPolicy {
  @Override
  public com.axelor.apps.base.db.Currency bookCurrency(Move move) {
    return move.getBgBookCurrency() != null ? move.getBgBookCurrency() : super.bookCurrency(move);
  }

  @Override
  public boolean isHistoricalBook(Move move) {
    return Boolean.TRUE.equals(move.getBgMigratedHistorical())
        && move.getBgBookCurrency() != null
        && "BGN".equals(move.getBgBookCurrency().getCodeISO());
  }

  public static final LocalDate TRANSITION = LocalDate.of(2026, 1, 1);
  public static final BigDecimal BGN_PER_EUR = new BigDecimal("1.95583");

  public static String expectedCurrency(LocalDate date) {
    if (date == null) {
      throw new IllegalArgumentException("Липсва счетоводна дата.");
    }
    return date.isBefore(TRANSITION) ? "BGN" : "EUR";
  }

  public static String reportCurrency(LocalDate from, LocalDate to) {
    if (from == null || to == null || from.isAfter(to)) {
      throw new IllegalArgumentException("Задайте валиден период на справката.");
    }
    return expectedCurrency(to);
  }

  /** Convert aggregate components, never individual stored debit/credit rows. */
  public static BigDecimal component(BigDecimal bgn, BigDecimal eur, String outputCurrency) {
    if ("BGN".equals(outputCurrency)) {
      if (eur.signum() != 0) {
        throw new IllegalArgumentException("EUR component in a BGN report");
      }
      return bgn;
    }
    return bgn.divide(BGN_PER_EUR, 2, RoundingMode.HALF_UP).add(eur);
  }

  public static Year fiscalYear(Company company, LocalDate date) {
    List<Year> years =
        Query.of(Year.class)
            .filter(
                "self.company = :company AND self.typeSelect = :type"
                    + " AND self.fromDate <= :date AND self.toDate >= :date")
            .bind("company", company)
            .bind("type", YearRepository.TYPE_FISCAL)
            .bind("date", date)
            .fetch(2);
    if (years.size() != 1) {
      throw new IllegalArgumentException("Няма една еднозначна счетоводна година за " + date);
    }
    Year year = years.get(0);
    if (!expectedCurrency(year.getFromDate()).equals(expectedCurrency(year.getToDate()))) {
      throw new IllegalArgumentException("Счетоводната година пресича смяната BGN/EUR.");
    }
    return year;
  }

  public static String bookCurrency(Year year) {
    String expected = expectedCurrency(year.getFromDate());
    if (year.getBgBookCurrency() == null
        || !expected.equals(year.getBgBookCurrency().getCodeISO())) {
      throw new IllegalArgumentException(
          "Задайте счетоводна валута "
              + expected
              + " за година "
              + year.getCode()
              + ". Оперативната валута на компанията остава EUR.");
    }
    return expected;
  }

  /**
   * Predicate for current balances only. Historical reports use the period-aware report service.
   */
  @Override
  public String currentMoveFilter(String alias, boolean sql) {
    if (sql) {
      return " AND ("
          + alias
          + ".bg_book_currency IS NULL OR "
          + alias
          + ".bg_book_currency = (SELECT c.currency FROM base_company c WHERE c.id = "
          + alias
          + ".company)) ";
    }
    return " AND ("
        + alias
        + ".bgBookCurrency IS NULL OR "
        + alias
        + ".bgBookCurrency = "
        + alias
        + ".company.currency) ";
  }

  public static boolean hasHistoricalBooks(Company company) {
    String filter =
        "self.bgBookCurrency IS NOT NULL AND self.bgBookCurrency <> self.company.currency";
    if (company == null) {
      return Query.of(Move.class).filter(filter).count() > 0;
    }
    return Query.of(Move.class)
            .filter(filter + " AND self.company = :company")
            .bind("company", company)
            .count()
        > 0;
  }

  @Override
  public void checkStockReport(AccountingReport report) {
    if (hasHistoricalBooks(report.getCompany())) {
      throw new IllegalStateException(
          "Тази стандартна справка не поддържа BGN/EUR книги в една"
              + " компания. Използвайте „Справки по счетоводна валута“: Оборотна ведомост, Главна книга"
              + " или Салда по партньори.");
    }
  }

  @Override
  public void checkYearClosing(Year year) {
    if (hasHistoricalBooks(year.getCompany()) && year.getFromDate().isBefore(TRANSITION)) {
      throw new IllegalStateException(
          "Историческа BGN година: приключването/пренасянето чрез"
              + " стандартната услуга е спряно. Историята е неизменима; периодната справка преобразува"
              + " натрупаното BGN салдо към EUR без дублиращ начален Move за 2026 г.");
    }
  }
}
