package com.axelor.apps.nyxasecurity.user;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceLoader;
import org.hibernate.integrator.spi.Integrator;
import org.junit.jupiter.api.Test;

/**
 * The self-edit guard is only active if Hibernate discovers the integrator. A missing or misspelled
 * META-INF/services entry would switch it off without any error, so pin the discovery here.
 */
class NyxaSecurityIntegratorRegistrationTest {

  @Test
  void integratorIsDiscoverableByServiceLoader() {
    boolean found = false;
    for (Integrator integrator : ServiceLoader.load(Integrator.class)) {
      found |= integrator instanceof NyxaSecurityIntegrator;
    }
    assertTrue(found, "NyxaSecurityIntegrator is not registered in META-INF/services");
  }
}
