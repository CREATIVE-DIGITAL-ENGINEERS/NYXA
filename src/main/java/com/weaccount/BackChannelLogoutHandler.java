package com.weaccount;

import java.util.concurrent.TimeUnit;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.logout.handler.DefaultLogoutHandler;
import org.pac4j.core.store.GuavaStore;

/**
 * Ends the app session when Keycloak ends the SSO session (OIDC back-channel logout).
 *
 * <p>Keycloak POSTs a signed {@code logout_token} to the callback URL with {@code
 * ?logoutendpoint=true}; pac4j's {@code OidcExtractor} validates it (signature, events claim, no
 * nonce, a {@code sid}) and calls {@link #destroySessionBack} with that {@code sid}. At login,
 * pac4j recorded which servlet session belongs to the {@code sid}; invalidating it also ends the
 * Shiro subject, because Axelor's Shiro sessions are the servlet container's.
 *
 * <p>Why not pac4j's handler as is: its {@code sid} → session map forgets entries 30 minutes after
 * login, while {@code session.timeout} here is hours, so a back-channel logout arriving later would
 * find nothing and silently do nothing. The map is in memory and local to this JVM, the same limit
 * as {@link SingleSessionEnforcer}: fine for one container per tenant, not for a scaled-out one.
 */
public class BackChannelLogoutHandler extends DefaultLogoutHandler {

  /** Longer than any session lives in practice; entries of ended sessions just age out. */
  static final int STORE_HOURS = 24;

  /** Upper bound on remembered sessions; a tenant has a few dozen users. */
  static final int STORE_SIZE = 10_000;

  public BackChannelLogoutHandler() {
    super(new GuavaStore<>(STORE_SIZE, STORE_HOURS, TimeUnit.HOURS));
  }

  @Override
  public void destroySessionBack(WebContext context, SessionStore sessionStore, String key) {
    logger.info("Back-channel logout from Keycloak for sid {}", key);
    super.destroySessionBack(context, sessionStore, key);
  }
}
