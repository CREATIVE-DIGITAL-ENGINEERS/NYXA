package com.weaccount;

import com.axelor.auth.pac4j.ClientListService;
import com.axelor.common.StringUtils;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the Keycloak OIDC client as the <em>only</em> login path. The platform's local login
 * form is deliberately not registered, and HTTP Basic auth stays off because {@code
 * auth.local.basic-auth} is left unset, so every unauthenticated request is redirected to Keycloak
 * by {@code AxelorSecurityLogic}.
 *
 * <p>Note that {@code auth.provider.keycloak.*} / {@code auth.provider.oidc.*} are read by the
 * Axelor Enterprise edition only; in this build nothing but this class reads them, which is why the
 * client is wired up by hand.
 *
 * <p>pac4j's {@code KeycloakOidcClient} is intentionally not used: it only derives the discovery
 * URI from realm + base URI, and its {@code KeycloakRolesAuthorizationGenerator} does strictly less
 * than {@link WeAccountAuthorizationGenerator}.
 *
 * <p>Loaded by AuthPac4jModule.configureShiroWeb
 */
@Singleton
public class KeycloakClientListService extends ClientListService {
  private static final Logger logger = LoggerFactory.getLogger(KeycloakClientListService.class);

  /**
   * Must stay in sync with the front-end's {@code CLIENT_NAME_ALIASES} (oidc -> OidcClient), so
   * that {@code ?client_name=oidc} resolves.
   */
  static final String CLIENT_NAME = "OidcClient";

  @Inject
  public KeycloakClientListService() {
    // Manually configure the Keycloak OIDC client from your axelor-config.properties
    String clientId = settings.get("auth.provider.oidc.client.id");
    String secret = settings.get("auth.provider.oidc.secret");
    String discoveryUri = settings.get("auth.provider.oidc.discovery.uri");
    String callbackUrl = settings.get("auth.provider.oidc.callback.url");
    String scope = settings.get("auth.provider.oidc.scope", "openid profile email");

    logger.info(
        "Configuring Keycloak OIDC: clientId={}, discoveryUri={}, scope={}",
        clientId,
        discoveryUri,
        scope);

    if (StringUtils.isBlank(clientId) || StringUtils.isBlank(discoveryUri)) {
      // Falling back to the login form here would silently reopen the password login path that
      // this class exists to close, so refuse to start instead.
      throw new IllegalStateException(
          "Keycloak OIDC configuration incomplete: auth.provider.oidc.client.id and"
              + " auth.provider.oidc.discovery.uri are required, and there is no local login"
              + " fallback.");
    }

    OidcConfiguration config = new OidcConfiguration();
    config.setClientId(clientId);
    config.setSecret(secret);
    config.setDiscoveryURI(discoveryUri);
    config.setScope(scope);
    // Without this, pac4j auto-negotiates the token endpoint auth method from Keycloak's
    // discovery document and can pick PRIVATE_KEY_JWT (which we don't configure), failing
    // with "privateKeyJwtConfig cannot be null". Force plain client-secret auth instead.
    config.setClientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    // pac4j's defaults (500 ms connect, 5 s read) are too tight for Keycloak behind Azure Front
    // Door: a slow TCP connect during the callback's back-channel calls (token exchange, JWKS)
    // fails the whole login with "SocketTimeoutException: connect timed out".
    config.setConnectTimeout(settings.getInt("auth.provider.oidc.connect.timeout", 5000));
    config.setReadTimeout(settings.getInt("auth.provider.oidc.read.timeout", 10000));
    // Back-channel logout: Keycloak POSTs a logout_token to <callback>?logoutendpoint=true when the
    // SSO session ends. Validate it (pac4j's default, stated so it isn't switched off by accident)
    // and end the matching app session. See BackChannelLogoutHandler.
    config.setLogoutValidation(true);
    config.setLogoutHandler(new BackChannelLogoutHandler());

    OidcClient keycloakClient = new OidcClient(config);
    if (StringUtils.notBlank(callbackUrl)) {
      keycloakClient.setCallbackUrl(callbackUrl);
    }
    keycloakClient.setName(CLIENT_NAME);
    keycloakClient.addAuthorizationGenerator(new WeAccountAuthorizationGenerator());

    clients.add(keycloakClient);
    logger.info("Keycloak OIDC client registered as the only login path");

    defaultClientName = CLIENT_NAME;

    // Initialize pac4j internal components
    init();
  }
}
