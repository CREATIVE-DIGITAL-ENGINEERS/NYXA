package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.google.inject.Singleton;

/**
 * Non-secret configuration that selects and parameterizes the Evrotrust vendor transport. Resolved
 * from a JVM system property first, then an environment variable of the same meaning — the same
 * deliberately NOT-database-backed pattern as {@code CloudQesConfig} / {@code
 * EnvNapSecretProvider}.
 *
 * <p><b>No secret values here.</b> The API key is never stored in this config (or in Git): only a
 * <em>reference</em> ({@link #getApiKeySecretRef()}) is kept, which {@code NapSecretProvider}
 * resolves at call time from the environment / a key vault. A blank reference, a missing vendor
 * number or an unrecognized environment all make {@link #isConfigured()} return {@code false},
 * which the provider selector turns into the fail-closed {@code UnconfiguredCloudQesProvider}.
 *
 * <ul>
 *   <li>environment — {@value #PROP_ENVIRONMENT} / {@value #ENV_ENVIRONMENT} (test|prod)
 *   <li>vendor number — {@value #PROP_VENDOR_NUMBER} / {@value #ENV_VENDOR_NUMBER}
 *   <li>API-key secret reference — {@value #PROP_API_KEY_SECRET_REF} / {@value
 *       #ENV_API_KEY_SECRET_REF}
 *   <li>default signer country — {@value #PROP_DEFAULT_COUNTRY} / {@value #ENV_DEFAULT_COUNTRY}
 *       (ISO 3166-1 alpha-2; defaults to {@code BG})
 *   <li>pending-confirmation window in seconds — {@value #PROP_EXPIRE_SECONDS} / {@value
 *       #ENV_EXPIRE_SECONDS} (defaults to {@value #DEFAULT_EXPIRE_SECONDS})
 * </ul>
 */
@Singleton
public class EvrotrustConfig {

  public static final String PROP_ENVIRONMENT = "nyxa.qes.evrotrust.environment";
  public static final String ENV_ENVIRONMENT = "NYXA_QES_EVROTRUST_ENVIRONMENT";
  public static final String PROP_VENDOR_NUMBER = "nyxa.qes.evrotrust.vendorNumber";
  public static final String ENV_VENDOR_NUMBER = "NYXA_QES_EVROTRUST_VENDOR_NUMBER";
  public static final String PROP_API_KEY_SECRET_REF = "nyxa.qes.evrotrust.apiKeySecretRef";
  public static final String ENV_API_KEY_SECRET_REF = "NYXA_QES_EVROTRUST_API_KEY_SECRET_REF";
  public static final String PROP_PUBLIC_KEY = "nyxa.qes.evrotrust.publicKey";
  public static final String ENV_PUBLIC_KEY = "NYXA_QES_EVROTRUST_PUBLIC_KEY";
  public static final String PROP_PRIVATE_KEY_PATH = "nyxa.qes.evrotrust.privateKeyPath";
  public static final String ENV_PRIVATE_KEY_PATH = "NYXA_QES_EVROTRUST_PRIVATE_KEY_PATH";
  public static final String PROP_PRIVATE_KEY_SECRET_REF = "nyxa.qes.evrotrust.privateKeySecretRef";
  public static final String ENV_PRIVATE_KEY_SECRET_REF =
      "NYXA_QES_EVROTRUST_PRIVATE_KEY_SECRET_REF";
  public static final String PROP_DEFAULT_COUNTRY = "nyxa.qes.evrotrust.defaultCountry";
  public static final String ENV_DEFAULT_COUNTRY = "NYXA_QES_EVROTRUST_DEFAULT_COUNTRY";
  public static final String PROP_EXPIRE_SECONDS = "nyxa.qes.evrotrust.dateExpireSeconds";
  public static final String ENV_EXPIRE_SECONDS = "NYXA_QES_EVROTRUST_DATE_EXPIRE_SECONDS";

  public static final String DEFAULT_COUNTRY = "BG";
  public static final long DEFAULT_EXPIRE_SECONDS = 3600L;

  /** The resolved environment, or {@code null} when unset/unrecognized. */
  public EvrotrustEnvironment getEnvironment() {
    return EvrotrustEnvironment.fromConfig(resolve(PROP_ENVIRONMENT, ENV_ENVIRONMENT));
  }

  /** The vendor number issued by Evrotrust, or {@code null} when unset. */
  public String getVendorNumber() {
    return trimToNull(resolve(PROP_VENDOR_NUMBER, ENV_VENDOR_NUMBER));
  }

  /**
   * OUR OWN public key, Base64, as the documented REQUIRED {@code publicKey} field of {@code
   * /document/hash/online} — "necessary for encryption process". Evrotrust encrypts the result of
   * the online flow with it, so the matching private key must exist on our side to read the
   * signature back. Not a secret in itself (it is a public key), hence plain configuration; the
   * PRIVATE half is not held by this class and is not implemented yet — see {@code
   * EvrotrustCloudQesProvider}.
   *
   * @return the configured Base64 public key, or {@code null} when unset
   */
  public String getPublicKeyBase64() {
    return trimToNull(resolve(PROP_PUBLIC_KEY, ENV_PUBLIC_KEY));
  }

  /**
   * Path to the PEM file holding OUR RSA private key — the other half of {@link
   * #getPublicKeyBase64()}, needed to decrypt what {@code /document/download} returns. ⚠️ A path,
   * never the key: the key file lives outside Git and is readable only by the Nyxa process.
   */
  public String getPrivateKeyPath() {
    return trimToNull(resolve(PROP_PRIVATE_KEY_PATH, ENV_PRIVATE_KEY_PATH));
  }

  /**
   * Alternative to {@link #getPrivateKeyPath()} for key-vault / container setups: the NAME of the
   * environment variable or system property whose VALUE is the PEM itself. Never the PEM.
   */
  public String getPrivateKeySecretRef() {
    return trimToNull(resolve(PROP_PRIVATE_KEY_SECRET_REF, ENV_PRIVATE_KEY_SECRET_REF));
  }

  /** Whether some source of our private key is configured (not whether it parses). */
  public boolean hasPrivateKeySource() {
    return getPrivateKeyPath() != null || getPrivateKeySecretRef() != null;
  }

  /**
   * The <em>reference</em> (not the value) under which the API key is resolved by {@code
   * NapSecretProvider}. Never the key itself.
   */
  public String getApiKeySecretRef() {
    return trimToNull(resolve(PROP_API_KEY_SECRET_REF, ENV_API_KEY_SECRET_REF));
  }

  /** Default signer country (ISO 3166-1 alpha-2), {@value #DEFAULT_COUNTRY} when unset. */
  public String getDefaultCountry() {
    String value = trimToNull(resolve(PROP_DEFAULT_COUNTRY, ENV_DEFAULT_COUNTRY));
    return value == null ? DEFAULT_COUNTRY : value.toUpperCase();
  }

  /**
   * Pending-confirmation window in seconds; {@value #DEFAULT_EXPIRE_SECONDS} when unset/invalid.
   */
  public long getExpireSeconds() {
    String value = trimToNull(resolve(PROP_EXPIRE_SECONDS, ENV_EXPIRE_SECONDS));
    if (value == null) {
      return DEFAULT_EXPIRE_SECONDS;
    }
    try {
      long parsed = Long.parseLong(value);
      return parsed > 0 ? parsed : DEFAULT_EXPIRE_SECONDS;
    } catch (NumberFormatException e) {
      return DEFAULT_EXPIRE_SECONDS;
    }
  }

  /**
   * Whether enough NON-SECRET configuration is present to even attempt Evrotrust signing: a known
   * environment, a vendor number and an API-key secret reference. Note this does NOT prove the
   * secret resolves — the transport resolves it at call time and fails closed if it is absent,
   * exactly like {@code NraDeclarationsApiClient}.
   */
  public boolean isConfigured() {
    return getEnvironment() != null && getVendorNumber() != null && getApiKeySecretRef() != null;
  }

  /**
   * Whether the ONLINE hash flow can even build a conforming request: it additionally needs our
   * {@code publicKey}, which the documented request marks REQUIRED. Kept separate from {@link
   * #isConfigured()} so a half-configured install still selects the Evrotrust provider and gets its
   * precise refusal, instead of silently falling back to "no provider configured".
   */
  public boolean isOnlineFlowConfigured() {
    return isConfigured() && getPublicKeyBase64() != null && hasPrivateKeySource();
  }

  /** System property first, then environment variable; blank is treated as unset. */
  protected String resolve(String propertyKey, String envKey) {
    String fromProperty = System.getProperty(propertyKey);
    if (fromProperty != null && !fromProperty.isBlank()) {
      return fromProperty;
    }
    String fromEnv = System.getenv(envKey);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    return null;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
