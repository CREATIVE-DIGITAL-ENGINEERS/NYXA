package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgNsiGodLine;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodService;
import javax.persistence.PersistenceException;

/**
 * Редът на заключена справка за НСИ не се пише отвън — същото правило като в {@link
 * BgNsiGodReportManagementRepository}, за запис на ред поотделно (REST, редакция в мрежата).
 */
public class BgNsiGodLineManagementRepository extends BgNsiGodLineRepository {

  @Override
  public BgNsiGodLine save(BgNsiGodLine line) {
    refuseIfLocked(line);
    return super.save(line);
  }

  @Override
  public void remove(BgNsiGodLine line) {
    refuseIfLocked(line);
    super.remove(line);
  }

  private static void refuseIfLocked(BgNsiGodLine line) {
    if (BgNsiGodService.isInternal() || line.getReport() == null) {
      return;
    }
    Long reportId = line.getReport().getId();
    Object[] stored = reportId == null ? null : BgNsiGodReportManagementRepository.stored(reportId);
    if (stored != null
        && stored[0] != null
        && ((Number) stored[0]).intValue() == BgNsiGodService.LOCKED) {
      throw new PersistenceException(
          "Справката за НСИ е заключена. Отключете я, за да промените ред.");
    }
  }
}
