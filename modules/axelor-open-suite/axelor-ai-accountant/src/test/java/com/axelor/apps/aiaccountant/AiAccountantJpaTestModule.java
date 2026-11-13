package com.axelor.apps.aiaccountant;

import com.axelor.db.JpaModule;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.axelor.studio.bpm.service.WkfDisplayService;
import com.axelor.studio.bpm.service.execution.WkfInstanceService;
import com.google.inject.AbstractModule;
import org.mockito.Mockito;

/**
 * Guice module for the module's DB-backed tests: a real Hibernate schema on in-memory HSQLDB.
 *
 * <p>Mirrors axelor-core's own {@code JpaTestModule}, minus the parts a transcript test has no use
 * for ({@code AuthModule}, {@code AppModule}): nothing here logs in, and the stores take the user
 * and company ids as arguments rather than reading a session — which is exactly what makes them
 * testable, and is the same seam that keeps company scope out of the client's reach in production.
 *
 * <p>{@code Beans} is bound eagerly because the stores reach their repositories through it, and its
 * static handle is only set when Guice instantiates it.
 */
public class AiAccountantJpaTestModule extends AbstractModule {

  @Override
  protected void configure() {
    // No explicit .scan(): with no package filter JpaScanner takes every entity on the classpath,
    // which is what this unit needs. AiChatMessage has a required many-to-one to Company, Company
    // is
    // an axelor-base entity, and base entities point on into the message, studio, team and utils
    // addons — Hibernate refuses a mapping whose target is missing, so the unit has to be the whole
    // transitive closure. Naming it by hand meant chasing one AnnotationException at a time;
    // letting
    // the scanner do it is both correct and the same thing the running application does.
    install(new JpaModule("testUnit"));

    // axelor-studio registers a GlobalEntityListener that runs on every persist, update and delete
    // of every entity and asks Guice for these three. None of them has anything to do with a chat
    // transcript, but an unbound one turns every commit in this unit into a rollback. Mocked rather
    // than installed: pulling in the BPM stack to test a two-column insert would make the test
    // slower, more fragile, and no more truthful about what it is actually checking.
    bind(JpaSecurity.class).toInstance(Mockito.mock(JpaSecurity.class));
    bind(WkfInstanceService.class).toInstance(Mockito.mock(WkfInstanceService.class));
    bind(WkfDisplayService.class).toInstance(Mockito.mock(WkfDisplayService.class));

    bind(Beans.class).asEagerSingleton();
  }
}
