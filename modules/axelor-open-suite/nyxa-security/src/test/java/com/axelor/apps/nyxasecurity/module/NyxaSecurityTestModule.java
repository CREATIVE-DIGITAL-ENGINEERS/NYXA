package com.axelor.apps.nyxasecurity.module;

import com.axelor.app.AppModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;

/**
 * DB-backed test wiring for nyxa-security: base's repositories ({@code UserRepository} resolves to
 * {@code UserBaseRepository}, as at runtime) plus {@link NyxaSecurityModule}. The user self-edit
 * guard is a Hibernate integrator registered through {@code META-INF/services}, so it is active
 * here exactly as in production.
 */
public class NyxaSecurityTestModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new JpaModule("testUnit", true, true));
    install(new AuthModule());
    install(new AppModule());
    install(new BaseModule());
    install(new NyxaSecurityModule());
  }
}
