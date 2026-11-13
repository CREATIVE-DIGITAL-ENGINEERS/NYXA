package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgNsiGodReport;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodService;
import com.axelor.db.JPA;
import java.util.List;
import javax.persistence.FlushModeType;
import javax.persistence.PersistenceException;

/**
 * ЗАКЛЮЧЕНАТА СПРАВКА ЗА НСИ НЕ СЕ ПРОМЕНЯ ОТВЪН.
 *
 * <p>Изгледът прави полетата само за четене, но REST и формата с подредени редове минават покрай
 * него. Затова тук: при заключена справка — отказ за всеки запис, който не идва от {@link
 * BgNsiGodService} (изчисли, заключи, отключи). И състоянието не се сменя отвън — иначе „заключи“
 * би минал без проверката за грешки.
 *
 * <p>⚠️ Записаното състояние се чете с {@code FlushModeType.COMMIT}: при AUTO заявката първо би
 * записала промяната, която проверяваме, и би я прочела като „записана“.
 */
public class BgNsiGodReportManagementRepository extends BgNsiGodReportRepository {

  @Override
  public BgNsiGodReport save(BgNsiGodReport report) {
    if (report.getId() != null && !BgNsiGodService.isInternal()) {
      Object[] stored = stored(report.getId());
      if (stored != null) {
        int status = stored[0] == null ? BgNsiGodService.DRAFT : ((Number) stored[0]).intValue();
        if (status == BgNsiGodService.LOCKED) {
          throw new PersistenceException(
              "Справката за НСИ е заключена. Отключете я, за да я промените.");
        }
        if (report.getStatusSelect() != status) {
          throw new PersistenceException(
              "Състоянието на справката за НСИ се сменя само с бутоните „Заключи“ и „Отключи“.");
        }
        boolean computed = stored[1] != null;
        Long companyId = (Long) stored[2];
        Integer year = (Integer) stored[3];
        boolean moved =
            report.getCompany() == null
                || !report.getCompany().getId().equals(companyId)
                || !year.equals(report.getReportYear());
        if (computed && moved) {
          throw new PersistenceException(
              "Дружеството и годината на изчислена справка за НСИ не се сменят. Създайте нова"
                  + " справка.");
        }
      }
    }
    return super.save(report);
  }

  /** [statusSelect, computedOn, company.id, reportYear] от базата, без да се запише текущото. */
  static Object[] stored(Long id) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT r.statusSelect, r.computedOn, r.company.id, r.reportYear"
                    + " FROM BgNsiGodReport r WHERE r.id = :id",
                Object[].class)
            .setParameter("id", id)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0);
  }
}
