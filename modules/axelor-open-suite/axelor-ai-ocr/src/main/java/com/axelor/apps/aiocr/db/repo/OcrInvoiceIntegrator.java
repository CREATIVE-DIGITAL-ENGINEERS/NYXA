package com.axelor.apps.aiocr.db.repo;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@link OcrInvoiceImmutabilityListener} with Hibernate. Discovered through {@code
 * META-INF/services/org.hibernate.integrator.spi.Integrator} — the same mechanism the НАП and
 * security modules use: it adds listeners without changing the platform's JPA bootstrap and without
 * touching Axelor's single Interceptor.
 */
public class OcrInvoiceIntegrator implements Integrator {

  private static final Logger LOG = LoggerFactory.getLogger(OcrInvoiceIntegrator.class);

  @Override
  public void integrate(
      Metadata metadata,
      SessionFactoryImplementor sessionFactory,
      SessionFactoryServiceRegistry serviceRegistry) {
    EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
    OcrInvoiceImmutabilityListener listener = new OcrInvoiceImmutabilityListener();
    registry.appendListeners(EventType.PRE_UPDATE, listener);
    registry.appendListeners(EventType.PRE_INSERT, listener);
    LOG.info("OCR document-total immutability guard registered (Invoice PreInsert/PreUpdate).");
  }

  @Override
  public void disintegrate(
      SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
