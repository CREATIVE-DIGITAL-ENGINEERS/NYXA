package com.axelor.apps.napsubmissions.common.signing;

import com.google.inject.Singleton;

/**
 * Fail-closed default {@link CloudQesProvider}: bound whenever no signing provider is configured.
 * It binds cleanly and NEVER fails at construction/injection — it refuses only when a method is
 * actually called, so the app keeps booting and the rest of the submission workflow stays usable.
 *
 * <p>This is what makes "no provider = no signing" safe: a missing/blank configuration resolves
 * here and every signing attempt throws {@link QesException.Category#CONFIG} instead of silently
 * producing nothing. The deterministic mock is never reached by default — it is selected only by an
 * explicit, non-default configuration value.
 */
@Singleton
public class UnconfiguredCloudQesProvider implements CloudQesProvider {

  /** The id this fail-closed provider reports — i.e. "no cloud provider is configured". */
  public static final String PROVIDER_ID = "none";

  private static final String MESSAGE =
      "No cloud-QES signing provider is configured. Set the signing provider explicitly"
          + " (see CloudQesConfig); signing is disabled until then.";

  @Override
  public String getProviderId() {
    return PROVIDER_ID;
  }

  @Override
  public QesSigningSession startSigning(QesSigningRequest request) {
    throw new QesException(QesException.Category.CONFIG, MESSAGE);
  }

  @Override
  public QesSigningStatusResponse pollStatus(String externalRequestId) {
    throw new QesException(QesException.Category.CONFIG, MESSAGE);
  }
}
