package com.axelor.apps.napsubmissions.common.signing;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The stable identifiers of the external cloud electronic-signature services a personal QES profile
 * can sign through.
 *
 * <p><b>These are not Partners.</b> A provider here is an outside e-signature service (B-Trust,
 * Evrotrust), not an Axelor supplier and not company-scoped. Nothing in this class references the
 * Partner model.
 *
 * <p>Each constant is simultaneously:
 *
 * <ul>
 *   <li>the value returned by the matching {@link CloudQesProvider#getProviderId()};
 *   <li>the value the {@code nap.qes.provider.select} selection stores on {@code
 *       UserQesProfile.providerIdentifier};
 *   <li>the value {@link CloudQesConfig} accepts to select the active provider.
 * </ul>
 *
 * Keeping one spelling across all three is what lets {@code QesSigningService} compare the profile
 * against the configured provider by plain string equality, so a profile that says {@code btrust}
 * can never be signed by the {@code mock} provider.
 */
public final class CloudQesProviderIds {

  /** Deterministic in-memory provider. Test/dev only — never a valid production QES. */
  public static final String MOCK = MockCloudQesProvider.PROVIDER_ID;

  /** B-Trust (Борика). Not implemented yet; selecting it fails closed. */
  public static final String BTRUST = "btrust";

  /** Evrotrust. Not implemented yet; selecting it fails closed. */
  public static final String EVROTRUST = "evrotrust";

  /**
   * The id {@link UnconfiguredCloudQesProvider} reports. It is deliberately NOT a selectable
   * profile value: it means "nothing is configured", not "sign through none".
   */
  public static final String NONE = "none";

  /** The selectable ids, in the same order as the {@code nap.qes.provider.select} options. */
  public static final Set<String> SELECTABLE =
      Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(MOCK, BTRUST, EVROTRUST)));

  private CloudQesProviderIds() {}
}
