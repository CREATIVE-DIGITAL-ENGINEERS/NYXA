package com.weaccount;

import com.axelor.app.AxelorModule;
import com.axelor.apps.base.service.pac4j.BaseAuthPac4jUserService;
import com.axelor.auth.pac4j.AxelorLogoutLogic;
import com.axelor.auth.pac4j.AxelorSecurityLogic;
import com.axelor.web.service.InfoService;

/**
 * Application-level bindings for the nyxa module, discovered by {@code AppModule}.
 *
 * <p>{@link AxelorLogoutLogic} is bound here rather than in the Shiro module because the filter
 * that consumes it lives in a {@code PrivateModule}; that child injector has no binding of its own
 * for the key, so it inherits this one.
 *
 * <p>Observers are only active once bound, hence the explicit {@link SingleSessionEnforcer} and
 * {@link UserLanguageOnLogin} bindings.
 */
public class WeAccountModule extends AxelorModule {

  @Override
  protected void configure() {
    bind(InfoService.class).to(WeAccountInfoService.class);
    bind(AxelorLogoutLogic.class).to(WeAccountLogoutLogic.class);
    bind(AxelorSecurityLogic.class).to(WeAccountSecurityLogic.class);
    // Login replaces a user's roles with the token's, also when none of them exists locally.
    // Chained: BaseModule already binds AuthPac4jUserService to BaseAuthPac4jUserService, and a
    // second binding of AuthPac4jUserService fails the injector (BindingAlreadySet).
    bind(BaseAuthPac4jUserService.class).to(WeAccountUserService.class);
    bind(SingleSessionEnforcer.class);
    bind(UserLanguageOnLogin.class);
  }
}
