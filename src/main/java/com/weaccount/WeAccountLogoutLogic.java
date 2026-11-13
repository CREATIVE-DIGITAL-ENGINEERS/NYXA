package com.weaccount;

import com.axelor.app.AppSettings;
import com.axelor.auth.pac4j.AuthPac4jInfo;
import com.axelor.auth.pac4j.AxelorLogoutLogic;
import com.axelor.common.StringUtils;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.http.adapter.HttpActionAdapter;

/**
 * Makes logout reach Keycloak instead of only dropping the local session.
 *
 * <p>{@code auth.logout.central} is documented in the sample configuration but read by the Axelor
 * Enterprise edition only: {@code AxelorLogoutFilter} never sets pac4j's {@code centralLogout}, so
 * {@code DefaultLogoutLogic} resolves it to {@code false} and the browser keeps its Keycloak SSO
 * cookie. Signing out and returning would then sign the user straight back in.
 *
 * <p>Rather than rebind the filter — which lives inside a Shiro {@code PrivateModule} — this
 * overrides the logout logic the filter delegates to, and forces the flag. pac4j then redirects to
 * the OP's {@code end_session_endpoint} (taken from the discovery document) with the id token as
 * hint and the logout URL as {@code post_logout_redirect_uri}.
 *
 * <p>The logout URL also comes from here: {@value #REDIRECT_URL}, set per environment with {@code
 * AXELOR_CONFIG_AUTH_LOGOUT_REDIRECT_URL}. It replaces the platform's {@code
 * auth.logout.default-url}, whose hyphen {@code AXELOR_CONFIG_*} environment variables can't reach,
 * so every container would send the URL baked into the image. When it is unset, the platform's
 * resolution applies (that key, else {@code application.base-url}). {@code AxelorLogoutLogic}
 * ignores the URL handed to it and asks {@link AuthPac4jInfo#getLogoutUrl()}, hence the wrapper.
 */
@Singleton
public class WeAccountLogoutLogic extends AxelorLogoutLogic {

  static final String REDIRECT_URL = "auth.logout.redirect.url";

  private final boolean centralLogout;

  @Inject
  public WeAccountLogoutLogic(AuthPac4jInfo info) {
    super(new RedirectUrlInfo(info));
    this.centralLogout = AppSettings.get().getBoolean("auth.logout.central", true);
  }

  /** {@link AuthPac4jInfo} whose logout URL is {@value #REDIRECT_URL} when that is set. */
  static class RedirectUrlInfo extends AuthPac4jInfo {

    private final AuthPac4jInfo delegate;

    RedirectUrlInfo(AuthPac4jInfo delegate) {
      this.delegate = delegate;
    }

    @Override
    public String getLogoutUrl() {
      // A blank value counts as unset (AppSettings), so an empty env var falls back to the
      // platform.
      String url = AppSettings.get().get(REDIRECT_URL, null);
      return StringUtils.isBlank(url) ? delegate.getLogoutUrl() : url.trim();
    }

    @Override
    public String getBaseUrl() {
      return delegate.getBaseUrl();
    }
  }

  @Override
  public Object perform(
      WebContext context,
      SessionStore sessionStore,
      Config config,
      HttpActionAdapter httpActionAdapter,
      String defaultUrl,
      String inputLogoutUrlPattern,
      Boolean inputLocalLogout,
      Boolean inputDestroySession,
      Boolean inputCentralLogout) {

    return super.perform(
        context,
        sessionStore,
        config,
        httpActionAdapter,
        defaultUrl,
        inputLogoutUrlPattern,
        inputLocalLogout,
        inputDestroySession,
        centralLogout);
  }
}
