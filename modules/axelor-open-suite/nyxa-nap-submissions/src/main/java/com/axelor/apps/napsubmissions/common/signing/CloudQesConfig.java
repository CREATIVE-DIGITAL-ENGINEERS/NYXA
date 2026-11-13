package com.axelor.apps.napsubmissions.common.signing;

import com.google.inject.Singleton;

/**
 * Non-secret configuration that selects the active {@link CloudQesProvider}. Resolved from a JVM
 * system property first, then an environment variable of the same meaning — the same deliberately
 * NOT-database-backed pattern as {@code EnvNapSecretProvider}. Unset/blank means "no provider",
 * which resolves to the fail-closed {@link UnconfiguredCloudQesProvider}.
 *
 * <ul>
 *   <li>provider id — system property {@value #PROP_PROVIDER} / env {@value #ENV_PROVIDER}
 *   <li>mock scenario — system property {@value #PROP_MOCK_SCENARIO} / env {@value
 *       #ENV_MOCK_SCENARIO} (only consulted when the provider is {@code mock})
 * </ul>
 */
@Singleton
public class CloudQesConfig {

  public static final String PROP_PROVIDER = "nyxa.qes.provider";
  public static final String ENV_PROVIDER = "NYXA_QES_PROVIDER";
  public static final String PROP_MOCK_SCENARIO = "nyxa.qes.mock.scenario";
  public static final String ENV_MOCK_SCENARIO = "NYXA_QES_MOCK_SCENARIO";

  /** Configured provider id, lower-cased and trimmed, or empty string when none is set. */
  public String getProviderId() {
    String value = resolve(PROP_PROVIDER, ENV_PROVIDER);
    return value == null ? "" : value.trim().toLowerCase();
  }

  /** Configured mock scenario (defaults to {@link MockQesScenario#IMMEDIATE_SUCCESS}). */
  public MockQesScenario getMockScenario() {
    return MockQesScenario.fromConfig(resolve(PROP_MOCK_SCENARIO, ENV_MOCK_SCENARIO));
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
}
