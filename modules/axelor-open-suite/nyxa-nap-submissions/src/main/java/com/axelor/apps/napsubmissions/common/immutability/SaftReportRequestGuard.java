package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.event.Observes;
import com.axelor.events.PreRequest;
import com.axelor.events.RequestEvent;
import com.axelor.events.qualifiers.EntityType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Named;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * SAF-T ОТЧЕТЪТ ОТ КЛИЕНТА (G6b): нов — само за активното дружество; масово обновяване — без
 * статуса, файла, дружеството, месеца и вида.
 *
 * <p>Масовото обновяване е JPQL и минава покрай Hibernate слушателя ({@link
 * SaftReportStatusListener}), затова се отказва още в заявката. Дружеството на НОВ отчет се сверява
 * тук, преди всеки запис: правото за създаване в каталога няма условие по запис.
 */
public class SaftReportRequestGuard {

  public void onSave(
      @Observes @Named(RequestEvent.SAVE) @EntityType(SaftReport.class) PreRequest event) {
    User user = AuthUtils.getUser();
    if (user == null || AuthUtils.isAdmin(user)) {
      return;
    }
    Long active = user.getActiveCompany() == null ? null : user.getActiveCompany().getId();
    for (Map<String, Object> record : NapArtifactRequestGuard.records(event.getRequest())) {
      if (NapArtifactRequestGuard.idOf(record) != null) {
        continue;
      }
      Long company = NapArtifactRequestGuard.idOf(record.get(SaftReportStatusGuard.COMPANY));
      if (active == null || !Objects.equals(active, company)) {
        throw new UnauthorizedException("Нов SAF-T отчет се създава само за активното дружество.");
      }
    }
  }

  public void onMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(SaftReport.class) PreRequest event) {
    List<String> fixed = new ArrayList<>(SaftReportStatusGuard.WORKFLOW_FIELDS);
    fixed.add(SaftReportStatusGuard.COMPANY);
    fixed.addAll(SaftReportStatusGuard.FIXED_AFTER_GENERATION);
    Map<String, Object> data = NapArtifactRequestGuard.massUpdateValues(event.getRequest());
    for (String key : data.keySet()) {
      String field = NapArtifactRequestGuard.fieldOf(key);
      if (fixed.contains(field)) {
        throw new RegulatoryImmutabilityException(
            "Полето „" + field + "“ на SAF-T отчета не се променя с масово обновяване.");
      }
    }
  }
}
