package com.axelor.apps.napsubmissions.common.config;

import com.google.inject.Singleton;
import java.util.Optional;

/**
 * Default {@link NapSecretProvider}: resolves the reference from a JVM system property first, then
 * an environment variable of the same name. This is deliberately <strong>not</strong>
 * database-backed — secrets never live in the ERP DB. A blank reference or an unset variable yields
 * {@link Optional#empty()} (never an exception), so startup is unaffected when no secret is
 * configured.
 *
 * <p>Swap this binding for a key-vault-backed provider when one is available; the call sites do not
 * change.
 */
@Singleton
public class EnvNapSecretProvider implements NapSecretProvider {

  @Override
  public Optional<String> resolveSecret(String secretReference) {
    if (secretReference == null || secretReference.isBlank()) {
      return Optional.empty();
    }
    String fromProperty = System.getProperty(secretReference);
    if (fromProperty != null && !fromProperty.isBlank()) {
      return Optional.of(fromProperty);
    }
    String fromEnv = System.getenv(secretReference);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return Optional.of(fromEnv);
    }
    return Optional.empty();
  }
}
