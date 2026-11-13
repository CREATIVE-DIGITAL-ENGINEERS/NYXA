package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.ProvisionException;
import com.google.inject.servlet.RequestScoped;
import com.google.inject.servlet.ServletScopes;
import org.junit.jupiter.api.Test;

/**
 * Системният партньор на нов потребител се създава и извън HTTP заявка: първите потребители на нова
 * база идват при старта, а веригата на услугата стига до {@code @RequestScoped} услуга (без обхват
 * инсталацията на приложенията пада с OutOfScopeException).
 */
class BgUserRepositoryRequestScopeTest {

  @RequestScoped
  static class PerRequest {}

  static class Broken {
    Broken() {
      throw new IllegalStateException("не е обхватът");
    }
  }

  private final Injector injector =
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              bindScope(RequestScoped.class, ServletScopes.REQUEST);
            }
          });

  @Test
  void outsideARequestTheWorkRunsInItsOwnRequestScope() {
    assertThrows(ProvisionException.class, () -> injector.getInstance(PerRequest.class));

    assertNotNull(BgUserRepository.inRequestScope(() -> injector.getInstance(PerRequest.class)));
  }

  @Test
  void anyOtherProvisioningFailureIsNotRetried() {
    assertThrows(
        ProvisionException.class,
        () -> BgUserRepository.inRequestScope(() -> injector.getInstance(Broken.class)));
  }
}
