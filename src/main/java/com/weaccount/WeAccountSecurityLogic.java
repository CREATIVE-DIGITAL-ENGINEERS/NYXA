package com.weaccount;

import com.axelor.auth.pac4j.AuthPac4jInfo;
import com.axelor.auth.pac4j.AxelorSecurityLogic;
import com.axelor.auth.pac4j.ClientListService;
import com.axelor.auth.pac4j.ErrorHandler;
import com.axelor.inject.Beans;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.pac4j.core.client.Client;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.util.HttpActionHelper;

/**
 * Stops a session evicted by {@link SingleSessionEnforcer} from silently signing itself back in.
 *
 * <p>Eviction on its own is not enough: the evicted browser is redirected to Keycloak, whose SSO
 * cookie signs it straight back in, and that login evicts the other browser in turn. Two open tabs
 * then trade the session back and forth every few seconds. Keycloak's Session Limits authenticator
 * does not fix this either, because the realm federates to Entra and Entra's session will sign
 * Keycloak back in.
 *
 * <p>So the eviction has to be terminal: a request carrying an evicted session is sent to a page
 * outside the application rather than to the identity provider. Getting back in requires a
 * deliberate click, which is what breaks the loop — an automatic redirect is what sustains it.
 *
 * <p>XHR requests are left alone and still get the usual 401, so the front-end's http-watch turns
 * them into a full navigation; that navigation is what lands here and gets the terminal page.
 */
@Singleton
public class WeAccountSecurityLogic extends AxelorSecurityLogic {

  @Inject
  public WeAccountSecurityLogic(
      ErrorHandler errorHandler, Config config, ClientListService clientListService) {
    super(errorHandler, config, clientListService);
  }

  @Override
  protected HttpAction redirectToIdentityProvider(
      WebContext context, SessionStore sessionStore, List<Client> currentClients) {

    final HttpServletRequest request = currentRequest();

    if (request != null && !AuthPac4jInfo.isXHR(request)) {
      final HttpSession session = request.getSession(false);

      final Object evicted = session == null ? null : evictedFlag(session);

      if (evicted != null) {
        // Only now, once the notice is actually being served, is the marker no longer needed.
        session.invalidate();
        final String page =
            evicted instanceof String
                ? (String) evicted
                : SingleSessionEnforcer.SIGNED_IN_ELSEWHERE_PAGE;
        return HttpActionHelper.buildRedirectUrlAction(context, request.getContextPath() + page);
      }
    }

    return super.redirectToIdentityProvider(context, sessionStore, currentClients);
  }

  private HttpServletRequest currentRequest() {
    try {
      return Beans.get(HttpServletRequest.class);
    } catch (RuntimeException e) {
      // No request scope; fall through to the normal provider redirect.
      return null;
    }
  }

  private Object evictedFlag(HttpSession session) {
    try {
      return session.getAttribute(SingleSessionEnforcer.EVICTED_ATTRIBUTE);
    } catch (IllegalStateException e) {
      // Session already invalidated by something else.
      return null;
    }
  }
}
