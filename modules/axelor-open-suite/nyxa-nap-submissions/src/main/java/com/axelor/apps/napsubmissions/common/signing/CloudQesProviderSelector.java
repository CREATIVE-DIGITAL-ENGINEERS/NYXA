package com.axelor.apps.napsubmissions.common.signing;

import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustCloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustConfig;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustTransport;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guice {@link Provider} that chooses the active {@link CloudQesProvider} from {@link
 * CloudQesConfig}. It is the single place a new provider (B-Trust, Evrotrust) is added later — a
 * new {@code case} here, with no change to the D1/D6 submission service.
 *
 * <p><b>Fail closed.</b> When nothing is configured, or an unknown/not-yet-implemented provider is
 * requested, it returns the {@link UnconfiguredCloudQesProvider} so every signing attempt throws
 * rather than silently doing nothing. The deterministic {@link MockCloudQesProvider} is selected
 * ONLY by the explicit, non-default value {@code mock} — so it is never active by default,
 * including in production.
 */
@Singleton
public class CloudQesProviderSelector implements Provider<CloudQesProvider> {

  private static final Logger LOG = LoggerFactory.getLogger(CloudQesProviderSelector.class);

  protected final CloudQesConfig config;
  protected final UnconfiguredCloudQesProvider unconfigured;
  protected final EvrotrustConfig evrotrustConfig;
  protected final EvrotrustTransport evrotrustTransport;

  @Inject
  public CloudQesProviderSelector(
      CloudQesConfig config,
      UnconfiguredCloudQesProvider unconfigured,
      EvrotrustConfig evrotrustConfig,
      EvrotrustTransport evrotrustTransport) {
    this.config = config;
    this.unconfigured = unconfigured;
    this.evrotrustConfig = evrotrustConfig;
    this.evrotrustTransport = evrotrustTransport;
  }

  @Override
  public CloudQesProvider get() {
    String providerId = config.getProviderId();
    if (providerId.isEmpty()) {
      LOG.info("Cloud-QES: no provider configured — signing is disabled (fail closed).");
      return unconfigured;
    }
    switch (providerId) {
      case MockCloudQesProvider.PROVIDER_ID:
        MockQesScenario scenario = config.getMockScenario();
        LOG.warn(
            "Cloud-QES: using the DETERMINISTIC MOCK provider (scenario={}). Test/dev only —"
                + " its output is NOT a valid production QES.",
            scenario);
        return new MockCloudQesProvider(scenario);
      case CloudQesProviderIds.EVROTRUST:
        if (!evrotrustConfig.isConfigured()) {
          // Selected but without credentials/environment: fail closed, never half-configured.
          LOG.warn(
              "Cloud-QES: Evrotrust is selected but not fully configured (environment, vendor"
                  + " number and API-key secret reference) — signing is disabled (fail closed).");
          return unconfigured;
        }
        LOG.info(
            "Cloud-QES: using the Evrotrust provider (environment={}).",
            evrotrustConfig.getEnvironment());
        return new EvrotrustCloudQesProvider(evrotrustConfig, evrotrustTransport);
      default:
        // Known-but-unimplemented (btrust) or unrecognized: fail closed, never guess.
        LOG.warn(
            "Cloud-QES: provider '{}' is not implemented — signing is disabled (fail closed).",
            providerId);
        return unconfigured;
    }
  }
}
