package com.weaccount;

import com.axelor.apps.base.service.pac4j.BaseAuthPac4jUserService;
import com.axelor.auth.db.User;
import com.axelor.auth.pac4j.AuthPac4jProfileService;
import com.google.inject.persist.Transactional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.pac4j.core.profile.CommonProfile;

/**
 * Keycloak is the source of truth for a user's roles: at every login they are <b>replaced</b> by
 * the token's, whatever those are.
 *
 * <p>The platform replaces them only when at least one of the token's roles exists as a local
 * {@code Role}; when none does, it leaves the previous roles in place. A user whose roles were all
 * taken away in Keycloak (or renamed to roles this tenant doesn't have) would then keep acting with
 * the old ones. Here the roles are cleared in that case too. Role names the tenant has no local
 * role for ({@code restricted-access}, {@code client.owner}) are dropped, as before.
 *
 * <p>Extends Open Suite's {@link BaseAuthPac4jUserService} (which BaseModule binds in place of the
 * platform's service) and is bound on top of it, so its own login behaviour still applies.
 */
@Singleton
public class WeAccountUserService extends BaseAuthPac4jUserService {

  @Inject private AuthPac4jProfileService profileService;

  @Override
  @Transactional
  protected void updateUser(User user, CommonProfile profile) {
    super.updateUser(user, profile);
    if (profileService.getRoles(profile).isEmpty()) {
      user.clearRoles();
    }
  }
}
