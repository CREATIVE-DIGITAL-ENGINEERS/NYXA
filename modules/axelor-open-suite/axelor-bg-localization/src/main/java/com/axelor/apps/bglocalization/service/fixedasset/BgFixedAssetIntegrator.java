package com.axelor.apps.bglocalization.service.fixedasset;

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
 * Проверява (само четене) схемата на активите — {@link BgFixedAssetSchemaCheck} — и регистрира
 * {@link BgFixedAssetListener} в Hibernate. Открива се през {@code
 * META-INF/services/org.hibernate.integrator.spi.Integrator} — като {@code BgPartnerIntegrator}:
 * без промяна в стартирането на JPA и без да пипа единствения Interceptor на Axelor.
 */
public class BgFixedAssetIntegrator implements Integrator {

  private static final Logger LOG = LoggerFactory.getLogger(BgFixedAssetIntegrator.class);

  @Override
  public void integrate(
      Metadata metadata,
      SessionFactoryImplementor sessionFactory,
      SessionFactoryServiceRegistry serviceRegistry) {
    // Само четене: пуснат ли е файлът на инвентарния номер. Не е ли — приложението не тръгва
    // (провал тук спира създаването на Hibernate, тоест и на JPA), с името на файла в съобщението.
    String refusal = schemaRefusal(serviceRegistry);
    if (refusal != null) {
      LOG.error(refusal);
      throw new IllegalStateException(refusal);
    }
    EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
    BgFixedAssetListener listener = new BgFixedAssetListener();
    registry.appendListeners(EventType.PRE_INSERT, listener);
    registry.appendListeners(EventType.PRE_UPDATE, listener);
    registry.appendListeners(EventType.PRE_DELETE, listener);
    // В лога, за да се вижда липсваща регистрация (напр. изпаднал services файл).
    LOG.info("Fixed asset identity guard registered (inventory number, account, category, split).");
  }

  private static String schemaRefusal(SessionFactoryServiceRegistry serviceRegistry) {
    JdbcConnectionAccess access =
        serviceRegistry.getService(JdbcServices.class).getBootstrapJdbcConnectionAccess();
    Connection connection = null;
    try {
      connection = access.obtainConnection();
      return BgFixedAssetSchemaCheck.refusal(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("Проверката на схемата на активите не мина.", e);
    } finally {
      if (connection != null) {
        try {
          access.releaseConnection(connection);
        } catch (SQLException e) {
          LOG.warn("Връзката след проверката на активите не се освободи.", e);
        }
      }
    }
  }

  @Override
  public void disintegrate(
      SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
