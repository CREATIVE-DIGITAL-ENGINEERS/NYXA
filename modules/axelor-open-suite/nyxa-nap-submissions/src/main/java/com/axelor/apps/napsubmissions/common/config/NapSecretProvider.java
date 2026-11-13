package com.axelor.apps.napsubmissions.common.config;

import java.util.Optional;

/**
 * Resolves a secret (e.g. the НАП API key) from a non-secret <em>reference</em> stored in
 * configuration — never from a plain-text value in the database. The private KEP key is out of
 * scope here entirely and must never pass through the ERP.
 *
 * <p>Implementations resolve the reference from a secure source (environment/JVM property today; a
 * key vault later). A missing/unresolvable reference returns {@link Optional#empty()} rather than
 * throwing, so nothing breaks at startup.
 */
public interface NapSecretProvider {

  /**
   * Resolve the secret identified by {@code secretReference}. Returns empty if the reference is
   * blank or the secret is not available in this environment.
   */
  Optional<String> resolveSecret(String secretReference);
}
