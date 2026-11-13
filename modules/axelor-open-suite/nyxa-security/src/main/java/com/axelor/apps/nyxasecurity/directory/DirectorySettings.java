package com.axelor.apps.nyxasecurity.directory;

import com.axelor.app.AppSettings;

/**
 * Where the tenant user directory lives and how this application authenticates to it.
 *
 * <p>No settings of its own beyond the mode: the directory is the realm this application already
 * logs users in through, and it authenticates as the same Keycloak client ({@code
 * auth.provider.oidc.*}), using that client's service account. Keys deliberately have no hyphens,
 * so {@code AXELOR_CONFIG_*} environment variables can reach them.
 */
public final class DirectorySettings {

  /** {@code keycloak} (default) or {@code mock} (in-memory, for development and tests). */
  public static final String MODE = "nyxa.users.directory";

  /** Seconds to wait for Keycloak, per request. */
  public static final String TIMEOUT = "nyxa.users.directory.timeout";

  public static final String MODE_KEYCLOAK = "keycloak";
  public static final String MODE_MOCK = "mock";

  static final String DISCOVERY_URI = "auth.provider.oidc.discovery.uri";
  static final String CLIENT_ID = "auth.provider.oidc.client.id";
  static final String CLIENT_SECRET = "auth.provider.oidc.secret";

  private static final String WELL_KNOWN = "/.well-known/openid-configuration";

  private final String mode;
  private final String issuer;
  private final String clientId;
  private final String clientSecret;
  private final int timeoutSeconds;

  DirectorySettings(
      String mode, String issuer, String clientId, String clientSecret, int timeoutSeconds) {
    this.mode = mode;
    this.issuer = issuer;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.timeoutSeconds = timeoutSeconds;
  }

  public static DirectorySettings fromAppSettings() {
    AppSettings settings = AppSettings.get();
    return new DirectorySettings(
        settings.get(MODE, MODE_KEYCLOAK).trim().toLowerCase(),
        issuerOf(settings.get(DISCOVERY_URI, "")),
        settings.get(CLIENT_ID, "").trim(),
        settings.get(CLIENT_SECRET, "").trim(),
        Math.max(1, settings.getInt(TIMEOUT, 10)));
  }

  /**
   * The realm's issuer URL ({@code https://host/realms/<realm>}) from the discovery URI, which is
   * the issuer plus the well-known suffix. Empty when the URI isn't of that form.
   */
  static String issuerOf(String discoveryUri) {
    String uri = discoveryUri == null ? "" : discoveryUri.trim();
    if (!uri.endsWith(WELL_KNOWN) || !uri.contains("/realms/")) {
      return "";
    }
    return uri.substring(0, uri.length() - WELL_KNOWN.length());
  }

  public boolean isMock() {
    return MODE_MOCK.equals(mode);
  }

  public String getMode() {
    return mode;
  }

  public String getTokenUrl() {
    return issuer + "/protocol/openid-connect/token";
  }

  /** Base of the {@code client-users} endpoint, without a trailing slash. */
  public String getDirectoryUrl() {
    return issuer + "/client-users";
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  /** What is missing for the Keycloak mode to work, or null when nothing is. */
  public String missing() {
    if (issuer.isEmpty()) {
      return DISCOVERY_URI + " (must be <issuer>" + WELL_KNOWN + ")";
    }
    if (clientId.isEmpty()) {
      return CLIENT_ID;
    }
    if (clientSecret.isEmpty()) {
      return CLIENT_SECRET;
    }
    return null;
  }
}
