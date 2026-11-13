package com.axelor.apps.bglocalization.service.partner;

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
 * Регистрира {@link BgPartnerCompanyListener} и {@link BgJustInserted} в Hibernate и проверява
 * (само четене) схемата на base_partner — {@link BgPartnerSchemaCheck}. Открива се през {@code
 * META-INF/services/org.hibernate.integrator.spi.Integrator} — като {@code NyxaSecurityIntegrator}
 * и {@code NapSubmissionsIntegrator}: без промяна в стартирането на JPA и без да пипа единствения
 * Interceptor на Axelor; слушателите се добавят.
 */
public class BgPartnerIntegrator implements Integrator {

  private static final Logger LOG = LoggerFactory.getLogger(BgPartnerIntegrator.class);

  @Override
  public void integrate(
      Metadata metadata,
      SessionFactoryImplementor sessionFactory,
      SessionFactoryServiceRegistry serviceRegistry) {
    // Само четене: пуснат ли е файлът за уникалността на номера. Не е ли — приложението не тръгва
    // (провал тук спира създаването на Hibernate, тоест и на JPA), с името на файла в съобщението.
    String refusal = schemaRefusal(serviceRegistry);
    if (refusal != null) {
      LOG.error(refusal);
      throw new IllegalStateException(refusal);
    }
    EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
    BgPartnerCompanyListener listener = new BgPartnerCompanyListener();
    registry.appendListeners(EventType.PRE_INSERT, listener);
    registry.appendListeners(EventType.PRE_UPDATE, listener);
    // „Вмъкнат в тази транзакция“ — за afterCreate на дружеството и системния партньор на нов
    // потребител: по пътя на екрана записът е вмъкнат преди репозиторито (виж BgJustInserted).
    registry.appendListeners(EventType.POST_INSERT, new BgJustInserted());
    // В лога, за да се вижда липсваща регистрация (напр. изпаднал services файл).
    LOG.info("Partner company guard registered (Partner.company: set on insert, fixed after).");
  }

  private static String schemaRefusal(SessionFactoryServiceRegistry serviceRegistry) {
    JdbcConnectionAccess access =
        serviceRegistry.getService(JdbcServices.class).getBootstrapJdbcConnectionAccess();
    Connection connection = null;
    try {
      connection = access.obtainConnection();
      return BgPartnerSchemaCheck.refusal(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("Проверката на схемата на base_partner не мина.", e);
    } finally {
      if (connection != null) {
        try {
          access.releaseConnection(connection);
        } catch (SQLException e) {
          LOG.warn("Връзката след проверката на base_partner не се освободи.", e);
        }
      }
    }
  }

  @Override
  public void disintegrate(
      SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
