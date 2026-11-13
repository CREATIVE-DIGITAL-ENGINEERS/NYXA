package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.ICalendarEventRepository;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.apps.hr.db.repo.LeaveRequestRepository;
import com.axelor.apps.hr.service.leave.LeaveLineService;
import com.axelor.apps.hr.service.leave.LeaveRequestCancelServiceImpl;
import com.axelor.apps.hr.service.leave.LeaveRequestCheckService;
import com.axelor.apps.hr.service.leave.LeaveRequestManagementService;
import com.axelor.apps.hr.service.leavereason.LeaveReasonService;
import com.google.inject.Inject;

/**
 * Secondary guard for a Microinvest-migrated historical {@code LeaveRequest} -- see {@link
 * BgHistoricalLeaveGuardRefuseService} for why: a cancel on an already-{@code VALIDATED} historical
 * row would call {@code manageCancelLeaves} and credit {@code LeaveLine} days this import never
 * debited.
 */
public class BgHistoricalLeaveGuardCancelService extends LeaveRequestCancelServiceImpl {

  @Inject
  public BgHistoricalLeaveGuardCancelService(
      LeaveRequestManagementService leaveRequestManagementService,
      LeaveLineService leaveLineService,
      ICalendarEventRepository iCalendarEventRepository,
      LeaveReasonService leaveReasonService,
      LeaveRequestRepository leaveRequestRepository,
      LeaveRequestCheckService leaveRequestCheckService) {
    super(
        leaveRequestManagementService,
        leaveLineService,
        iCalendarEventRepository,
        leaveReasonService,
        leaveRequestRepository,
        leaveRequestCheckService);
  }

  @Override
  public void cancel(LeaveRequest leaveRequest) throws AxelorException {
    if (Boolean.TRUE.equals(leaveRequest.getBgIsHistoricalImport())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "%s",
          "Исторически внесен отпуск (миграция) — анулирането е изключено, за да не се завиши"
              + " баланс, който вносът никога не е намалявал.");
    }
    super.cancel(leaveRequest);
  }
}
