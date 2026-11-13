package com.axelor.apps.bglocalization.service.payroll;

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
 * Проверява (само четене) схемата на фиша и на обезщетението — {@link BgTrzSchemaCheck} — и
 * регистрира в Hibernate двата слушателя на ТРЗ броячите. Открива се през {@code
 * META-INF/services/org.hibernate.integrator.spi.Integrator} — като {@code
 * NapSubmissionsIntegrator} и {@code BgPartnerIntegrator}: без промяна в стартирането на JPA;
 * слушателите се ДОБАВЯТ след стандартните.
 *
 * <ul>
 *   <li>{@link BgTrzCompanySequenceListener} — ново дружество → двете ТРЗ редици ({@code PERSIST},
 *       каскадата при flush {@code PERSIST_ONFLUSH}, {@code MERGE});
 *   <li>{@link BgTrzNumberingCompanyGuard} — {@code company} на фиша и на обезщетението не се сменя
 *       след номерирането ({@code PRE_UPDATE}).
 * </ul>
 *
 * <p>⚠️ ПРЕДПАЗИТЕЛЯТ Е ПЪРВИ. Провал тук спира създаването на Hibernate, тоест и на JPA — много
 * преди {@code StartupEvent}, на който върви {@link BgTrzSequenceBackfill}. Стъпката при старта
 * затова винаги идва СЛЕД проверката: на непригодна схема до нея не се стига.
 */
public class BgTrzIntegrator implements Integrator {

  private static final Logger LOG = LoggerFactory.getLogger(BgTrzIntegrator.class);

  @Override
  public void integrate(
      Metadata metadata,
      SessionFactoryImplementor sessionFactory,
      SessionFactoryServiceRegistry serviceRegistry) {
    JdbcConnectionAccess access =
        serviceRegistry.getService(JdbcServices.class).getBootstrapJdbcConnectionAccess();
    Connection connection = null;
    try {
      connection = access.obtainConnection();
      requireSchema(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("Проверката на схемата на фиша/обезщетението не мина.", e);
    } finally {
      if (connection != null) {
        try {
          access.releaseConnection(connection);
        } catch (SQLException e) {
          LOG.warn("Връзката след проверката на схемата не се освободи.", e);
        }
      }
    }
    EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
    BgTrzCompanySequenceListener sequences = new BgTrzCompanySequenceListener();
    registry.appendListeners(EventType.PERSIST, sequences);
    registry.appendListeners(EventType.PERSIST_ONFLUSH, sequences);
    registry.appendListeners(EventType.MERGE, sequences);
    registry.appendListeners(EventType.PRE_UPDATE, new BgTrzNumberingCompanyGuard());
    // В лога, за да се вижда липсваща регистрация (напр. изпаднал services файл).
    LOG.info(
        "Payroll numbering registered: per-company sequences for every new Company,"
            + " BgPaySlip/BgCompensation.company fixed after numbering.");
  }

  /** ПРЕДПАЗИТЕЛЯТ: схемата не е готова → приложението НЕ тръгва, с името на миграцията. */
  static void requireSchema(Connection connection) throws SQLException {
    String refusal = BgTrzSchemaCheck.refusal(connection);
    if (refusal != null) {
      LOG.error(refusal);
      throw new IllegalStateException(refusal);
    }
  }

  @Override
  public void disintegrate(
      SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
