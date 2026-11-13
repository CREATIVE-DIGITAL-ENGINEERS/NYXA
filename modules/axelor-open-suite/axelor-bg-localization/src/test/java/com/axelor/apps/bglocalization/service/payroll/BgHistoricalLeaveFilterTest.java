package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.d1.BgD1DaySource;
import com.axelor.apps.bglocalization.service.noi.BgApp9DeclarationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves, without a database, that a Microinvest-migrated historical {@code LeaveRequest}
 * (bgIsHistoricalImport=true) can never be returned by any of the four live queries this payroll
 * engine runs against {@code LeaveRequest} -- the exact set found by a repo-wide search for "FROM
 * LeaveRequest" in this module. Each query-building method exposes its JPQL text for this purpose;
 * a future edit that drops the shared {@link BgHistoricalLeaveFilter#EXCLUDE_HISTORICAL} fragment
 * from any one of them fails this test immediately.
 */
class BgHistoricalLeaveFilterTest {

  @Test
  @DisplayName("BgPayrollInputBuilder.leaveRows carries the historical-leave exclusion")
  void payrollInputBuilderExcludesHistorical() {
    assertTrue(
        BgPayrollInputBuilder.LEAVE_ROWS_JPQL.contains(BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL));
  }

  @Test
  @DisplayName("BgD1DaySource.leaveRows carries the historical-leave exclusion")
  void d1DaySourceExcludesHistorical() {
    assertTrue(BgD1DaySource.LEAVE_ROWS_JPQL.contains(BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL));
  }

  @Test
  @DisplayName("BgUnpaidLeaveHealthService.unpaidLeaveRules carries the historical-leave exclusion")
  void unpaidLeaveHealthServiceExcludesHistorical() {
    assertTrue(
        BgUnpaidLeaveHealthService.UNPAID_LEAVE_RULES_JPQL.contains(
            BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL));
  }

  @Test
  @DisplayName(
      "BgApp9DeclarationService.leaveIntervals carries the historical-leave exclusion, both branches")
  void app9DeclarationServiceExcludesHistorical() {
    assertTrue(
        BgApp9DeclarationService.leaveIntervalsJpql(true)
            .contains(BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL));
    assertTrue(
        BgApp9DeclarationService.leaveIntervalsJpql(false)
            .contains(BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL));
  }
}
