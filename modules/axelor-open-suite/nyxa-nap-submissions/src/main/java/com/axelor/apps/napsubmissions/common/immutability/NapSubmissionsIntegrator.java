package com.axelor.apps.napsubmissions.common.immutability;

import java.sql.Connection;
import java.sql.SQLException;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Регистрира {@link NapArtifactImmutabilityListener} и {@link NapSubmissionImmutabilityListener} в
 * Hibernate. Открива се през {@code META-INF/services/org.hibernate.integrator.spi.Integrator} —
 * като {@code NyxaSecurityIntegrator} (G12): без промяна в JPA стартирането на платформата и без да
 * пипа единствения Interceptor на Axelor; слушателите се добавят.
 *
 * <p>Преди слушателите — проверка на схемата, само четене ({@link SaftReportSchemaCheck}): без
 * колоните на годишния SAF-T и SAF-T при поискване приложението не тръгва.
 */
public class NapSubmissionsIntegrator implements Integrator {

  private static final Logger LOG = LoggerFactory.getLogger(NapSubmissionsIntegrator.class);

  @Override
  public void integrate(
      Metadata metadata,
      SessionFactoryImplementor sessionFactory,
      SessionFactoryServiceRegistry serviceRegistry) {
    // Само четене: пуснат ли е файлът с колоните на T1. Не е ли — приложението не тръгва (провал
    // тук спира създаването на Hibernate, тоест и на JPA), с името на файла в съобщението.
    String refusal = schemaRefusal(serviceRegistry);
    if (refusal != null) {
      LOG.error(refusal);
      throw new IllegalStateException(refusal);
    }
    EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
    registry.appendListeners(EventType.PRE_UPDATE, new NapArtifactImmutabilityListener());
    // Подаденото подаване и файловете му: промяна, нов файл и триене — по старото състояние.
    NapSubmissionImmutabilityListener submissions = new NapSubmissionImmutabilityListener();
    registry.appendListeners(EventType.PRE_UPDATE, submissions);
    registry.appendListeners(EventType.PRE_INSERT, submissions);
    registry.appendListeners(EventType.PRE_DELETE, submissions);
    // SAF-T отчетът (G6b): статусът и генерираният файл — само от работния поток.
    SaftReportStatusListener saftReports = new SaftReportStatusListener();
    registry.appendListeners(EventType.PRE_INSERT, saftReports);
    registry.appendListeners(EventType.PRE_UPDATE, saftReports);
    // В лога, за да се вижда липсваща регистрация (напр. изпаднал services файл).
    LOG.info(
        "Regulatory file immutability guard registered (NapArtifact, NapSubmissionArtifact,"
            + " NapSubmission, NapSubmissionFile, SaftReport status).");
  }

  private static String schemaRefusal(SessionFactoryServiceRegistry serviceRegistry) {
    JdbcConnectionAccess access =
        serviceRegistry.getService(JdbcServices.class).getBootstrapJdbcConnectionAccess();
    Connection connection = null;
    try {
      connection = access.obtainConnection();
      return SaftReportSchemaCheck.refusal(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("Проверката на схемата на SAF-T отчета не мина.", e);
    } finally {
      if (connection != null) {
        try {
          access.releaseConnection(connection);
        } catch (SQLException e) {
          LOG.warn("Връзката след проверката на SAF-T отчета не се освободи.", e);
        }
      }
    }
  }

  @Override
  public void disintegrate(
      SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
