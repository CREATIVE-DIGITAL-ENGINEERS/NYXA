package com.axelor.apps.nyxasecurity.user;

import com.axelor.apps.nyxasecurity.posting.ManualValidationGuard;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@link UserSelfEditListener} and {@link ManualValidationGuard} with Hibernate.
 * Discovered through {@code META-INF/services/org.hibernate.integrator.spi.Integrator}, so it needs
 * no change to the platform's JPA bootstrap. Axelor's single session {@code Interceptor} slot
 * ({@code AuditInterceptor}) stays untouched: event listeners are additive.
 */
public class NyxaSecurityIntegrator implements Integrator {

  private static final Logger LOG = LoggerFactory.getLogger(NyxaSecurityIntegrator.class);

  @Override
  public void integrate(
      Metadata metadata,
      SessionFactoryImplementor sessionFactory,
      SessionFactoryServiceRegistry serviceRegistry) {
    EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
    UserSelfEditListener listener = new UserSelfEditListener();
    registry.appendListeners(EventType.PRE_UPDATE, listener);
    registry.appendListeners(EventType.PRE_COLLECTION_UPDATE, listener);
    registry.appendListeners(EventType.PRE_COLLECTION_RECREATE, listener);
    registry.appendListeners(EventType.PRE_COLLECTION_REMOVE, listener);
    ManualValidationGuard validation = new ManualValidationGuard();
    registry.appendListeners(EventType.PRE_UPDATE, validation);
    registry.appendListeners(EventType.PRE_INSERT, validation);
    // Logged so a missing registration (e.g. the services file dropped from the jar) is visible.
    LOG.info("User self-edit guard registered.");
    LOG.info("Manual move validation guard registered.");
  }

  @Override
  public void disintegrate(
      SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
