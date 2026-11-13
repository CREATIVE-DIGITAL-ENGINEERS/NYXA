package com.axelor.apps.aiaccounting.posting;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@link AiDraftOnlyPostingGuard} with Hibernate. Discovered through {@code
 * META-INF/services/org.hibernate.integrator.spi.Integrator}, like nyxa-security's integrator;
 * event listeners are additive.
 */
public class AiAccountingIntegrator implements Integrator {

  private static final Logger LOG = LoggerFactory.getLogger(AiAccountingIntegrator.class);

  @Override
  public void integrate(
      Metadata metadata,
      SessionFactoryImplementor sessionFactory,
      SessionFactoryServiceRegistry serviceRegistry) {
    EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
    AiDraftOnlyPostingGuard guard = new AiDraftOnlyPostingGuard();
    registry.appendListeners(EventType.PRE_UPDATE, guard);
    registry.appendListeners(EventType.PRE_INSERT, guard);
    // Logged so a missing registration (e.g. the services file dropped from the jar) is visible.
    LOG.info("AI drafts-only posting guard registered.");
  }

  @Override
  public void disintegrate(
      SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
