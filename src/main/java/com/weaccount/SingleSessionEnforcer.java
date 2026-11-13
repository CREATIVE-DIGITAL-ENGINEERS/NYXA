package com.weaccount;

import static org.apache.shiro.subject.support.DefaultSubjectContext.AUTHENTICATED_SESSION_KEY;
import static org.apache.shiro.subject.support.DefaultSubjectContext.PRINCIPALS_SESSION_KEY;

import com.axelor.apps.nyxasecurity.user.UserAccessRevoked;
import com.axelor.event.Observes;
import com.axelor.events.PostLogin;
import com.axelor.inject.Beans;
import com.axelor.web.AppSessionListener;
import java.util.Objects;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.apache.shiro.subject.PrincipalCollection;
import org.pac4j.core.util.Pac4jConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces one open session per user: logging in from a second browser or machine invalidates the
 * previous session.
 *
 * <p>This happens in the application. Axelor never re-validates the OIDC token after the callback,
 * so terminating a session in Keycloak alone left the old browser's servlet session working until
 * {@code session.timeout} elapsed. {@link BackChannelLogoutHandler} now lets Keycloak end it, but
 * only where the client has a back-channel logout URL Keycloak can reach (not for local dev), so
 * this enforcer stays as the safety net.
 *
 * <p>Conversely, the application alone is not enough either: once the old session is invalidated,
 * that browser is redirected to Keycloak, and if its SSO cookie is still valid Keycloak signs it
 * straight back in — the two browsers then evict each other in a loop. Keycloak must therefore run
 * the <em>Session Limits</em> authenticator in the browser flow, set to 1 session per user with
 * "Terminate oldest session", so that the old browser's SSO session dies at the same time.
 *
 * <p>Two constraints to keep in mind. The session registry in {@link AppSessionListener} is a
 * static set local to one JVM, which holds for the current single-container deployment but not for
 * a scaled-out one. And sessions are matched on user code alone, which is unambiguous only because
 * this deployment is single-tenant.
 */
public class SingleSessionEnforcer {

  private static final Logger logger = LoggerFactory.getLogger(SingleSessionEnforcer.class);

  /**
   * Marks a session as evicted rather than destroying it outright, so that the next request from
   * that browser can be recognised and sent to a terminal notice instead of back to the identity
   * provider. Its value is the notice page to send it to. See {@link WeAccountSecurityLogic}.
   */
  public static final String EVICTED_ATTRIBUTE = "com.weaccount.sessionEvicted";

  /** Notice for a session ended because the same account signed in elsewhere. */
  public static final String SIGNED_IN_ELSEWHERE_PAGE = "/public/signed-out-elsewhere.html";

  /** Notice for a session ended because an administrator revoked the user's access. */
  public static final String ACCESS_REVOKED_PAGE = "/public/access-revoked.html";

  void onLoginSuccess(@Observes @Named(PostLogin.SUCCESS) PostLogin event) {
    final String login = event.getUser().getCode();
    final int evicted = evict(login, currentSession(), SIGNED_IN_ELSEWHERE_PAGE);
    if (evicted > 0) {
      logger.info("Evicted previous session of user {} on new login", login);
    }
  }

  /**
   * A revoked user's open sessions end at once: their next click shows why, instead of every
   * request failing with "Unauthorized". The identity provider refuses their next sign-in.
   */
  void onAccessRevoked(@Observes UserAccessRevoked event) {
    final String login = event.getUserCode();
    final int evicted = evict(login, null, ACCESS_REVOKED_PAGE);
    logger.info("Ended {} open session(s) of user {} after access was revoked", evicted, login);
  }

  /** Strips every authenticated session of {@code login} except {@code keep}; returns how many. */
  private int evict(String login, HttpSession keep, String noticePage) {
    int evicted = 0;

    for (HttpSession session : AppSessionListener.getSessions()) {
      if (session == null || session == keep) {
        continue;
      }

      try {
        if (session.getAttribute(AUTHENTICATED_SESSION_KEY) != Boolean.TRUE
            || !Objects.equals(login, loginOf(session))) {
          continue;
        }

        // Deliberately not invalidate(): the session is kept, stripped of its credentials and
        // flagged, so the evicted browser can be shown why it was signed out instead of being
        // bounced straight back to Keycloak and silently re-authenticated.
        //
        // Shiro and pac4j each keep their own copy of the authenticated state, and BOTH have to
        // go. Dropping only Shiro's leaves pac4j holding a valid profile: the security logic then
        // considers the request authenticated and never redirects, while AxelorUserAuthorizer
        // resolves no user and denies it — every request 403s and the notice below is never shown.
        session.removeAttribute(PRINCIPALS_SESSION_KEY);
        session.removeAttribute(AUTHENTICATED_SESSION_KEY);
        session.removeAttribute(Pac4jConstants.USER_PROFILES);
        session.setAttribute(EVICTED_ATTRIBUTE, noticePage);
        evicted++;
      } catch (IllegalStateException e) {
        // Session was already invalidated; nothing to evict.
      }
    }

    return evicted;
  }

  /**
   * The session being created is identified by object identity, not by id or login: {@code
   * AxelorSessionManager.changeSessionId()} has just changed its id, and Shiro binds the principals
   * to it only after {@link PostLogin} has fired.
   */
  private HttpSession currentSession() {
    try {
      return Beans.get(HttpServletRequest.class).getSession(false);
    } catch (RuntimeException e) {
      // No request scope, which shouldn't happen on a browser login.
      logger.debug("No current request while enforcing single session", e);
      return null;
    }
  }

  private String loginOf(HttpSession session) {
    final Object principals = session.getAttribute(PRINCIPALS_SESSION_KEY);

    if (principals instanceof PrincipalCollection) {
      final Object primary = ((PrincipalCollection) principals).getPrimaryPrincipal();
      return primary == null ? null : primary.toString();
    }

    return principals == null ? null : principals.toString();
  }
}
