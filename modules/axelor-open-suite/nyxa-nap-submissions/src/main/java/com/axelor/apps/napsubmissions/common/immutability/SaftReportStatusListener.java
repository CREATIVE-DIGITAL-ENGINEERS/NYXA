package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.SaftReport;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * Hibernate пазачът на {@link SaftReportStatusGuard}: по старото състояние, по всеки път — форма,
 * {@code /ws/rest} (въпреки flush-а на {@code Resource.save} преди {@code repository.save}),
 * услуги, admin. Регистриран от {@code NapSubmissionsIntegrator}.
 */
public class SaftReportStatusListener implements PreInsertEventListener, PreUpdateEventListener {

  private static final long serialVersionUID = 1L;

  @Override
  public boolean onPreInsert(PreInsertEvent event) {
    if (event.getEntity() instanceof SaftReport) {
      SaftReportStatusGuard.checkInsert(
          event.getPersister().getPropertyNames(),
          event.getState(),
          SaftReportStatusGuard.allowed());
    }
    return false;
  }

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    if (!(event.getEntity() instanceof SaftReport)) {
      return false;
    }
    if (event.getOldState() == null) {
      // Без старо състояние сравнение няма; пропуск би бил тиха врата — отказ.
      throw new RegulatoryImmutabilityException(
          "Cannot verify an update of SaftReport #"
              + ((SaftReport) event.getEntity()).getId()
              + ": no previous state was loaded");
    }
    SaftReportStatusGuard.checkUpdate(
        event.getPersister().getPropertyNames(),
        event.getOldState(),
        event.getState(),
        SaftReportStatusGuard.allowed());
    return false;
  }
}
