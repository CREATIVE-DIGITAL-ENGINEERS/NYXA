package com.axelor.apps.bglocalization.service.payroll;

/**
 * Single JPQL fragment excluding historically-imported {@code LeaveRequest} rows (Microinvest
 * migration, {@code LeaveRequest.bgIsHistoricalImport}) from every live query this payroll engine
 * runs against {@code LeaveRequest}. Applied verbatim to all four such queries in this codebase --
 * {@link BgPayrollInputBuilder#leaveRows}, {@code BgD1DaySource#leaveRows}, {@link
 * BgUnpaidLeaveHealthService#unpaidLeaveRules}, and {@code BgApp9DeclarationService#leaveIntervals}
 * -- so a historical row can never feed a live payslip, Д1 day bucket, ЗОВ-payer resolution, or
 * Приложение №9 lookback, regardless of its {@code statusSelect}.
 *
 * <p>One shared constant instead of four hand-typed copies is deliberate: it is what makes the
 * guarantee testable without a JPA/Hibernate test environment (this module doesn't run one) --
 * {@code BgHistoricalLeaveFilterTest} asserts the exact same instance appears in all four
 * query-building methods' JPQL text, so a future edit that drops the filter from any one of them
 * fails immediately.
 */
public final class BgHistoricalLeaveFilter {

  public static final String EXCLUDE_HISTORICAL =
      " AND (l.bgIsHistoricalImport IS NULL OR l.bgIsHistoricalImport = FALSE)";

  private BgHistoricalLeaveFilter() {}
}
