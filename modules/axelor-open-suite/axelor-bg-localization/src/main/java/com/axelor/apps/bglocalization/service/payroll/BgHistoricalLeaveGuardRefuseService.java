package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.apps.hr.db.repo.LeaveRequestRepository;
import com.axelor.apps.hr.service.leave.LeaveLineService;
import com.axelor.apps.hr.service.leave.LeaveRequestCheckService;
import com.axelor.apps.hr.service.leave.LeaveRequestManagementService;
import com.axelor.apps.hr.service.leave.LeaveRequestRefuseServiceImpl;
import com.axelor.apps.hr.service.leavereason.LeaveReasonService;
import com.google.inject.Inject;

/**
 * Secondary guard for a Microinvest-migrated historical {@code LeaveRequest} (belt-and-braces: the
 * load-bearing protection is {@link BgHistoricalLeaveFilter}, which keeps historical rows out of
 * every live calculation regardless of {@code statusSelect}). Without this, a refuse on an
 * already-{@code VALIDATED} historical row would call {@code manageRefuseLeaves} and credit {@code
 * LeaveLine} days that this import never debited in the first place -- the balance was never
 * touched at import time (see {@code MicroinvestPilotImportService}), so "giving it back" would
 * create a balance discrepancy out of nothing.
 */
public class BgHistoricalLeaveGuardRefuseService extends LeaveRequestRefuseServiceImpl {

  @Inject
  public BgHistoricalLeaveGuardRefuseService(
      LeaveRequestManagementService leaveRequestManagementService,
      LeaveLineService leaveLineService,
      LeaveReasonService leaveReasonService,
      LeaveRequestRepository leaveRequestRepository,
      AppBaseService appBaseService,
      LeaveRequestCheckService leaveRequestCheckService) {
    super(
        leaveRequestManagementService,
        leaveLineService,
        leaveReasonService,
        leaveRequestRepository,
        appBaseService,
        leaveRequestCheckService);
  }

  @Override
  public void refuse(LeaveRequest leaveRequest, String groundForRefusal) throws AxelorException {
    if (Boolean.TRUE.equals(leaveRequest.getBgIsHistoricalImport())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "%s",
          "Исторически внесен отпуск (миграция) — отказът е изключен, за да не се завиши баланс,"
              + " който вносът никога не е намалявал.");
    }
    super.refuse(leaveRequest, groundForRefusal);
  }
}
