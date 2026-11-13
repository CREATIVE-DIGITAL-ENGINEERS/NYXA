package com.axelor.apps.napsubmissions.common.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustConfig;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.FakeEvrotrustTransport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Provider selection: fail-closed by default, deterministic mock only when explicitly selected. */
class CloudQesProviderSelectorTest {

  @AfterEach
  void clearProps() {
    System.clearProperty(CloudQesConfig.PROP_PROVIDER);
    System.clearProperty(CloudQesConfig.PROP_MOCK_SCENARIO);
    System.clearProperty(EvrotrustConfig.PROP_ENVIRONMENT);
    System.clearProperty(EvrotrustConfig.PROP_VENDOR_NUMBER);
    System.clearProperty(EvrotrustConfig.PROP_API_KEY_SECRET_REF);
  }

  private CloudQesProvider resolve() {
    return new CloudQesProviderSelector(
            new CloudQesConfig(),
            new UnconfiguredCloudQesProvider(),
            new EvrotrustConfig(),
            new FakeEvrotrustTransport())
        .get();
  }

  @Test
  void noConfigResolvesToFailClosedProvider() {
    CloudQesProvider provider = resolve();
    assertEquals("none", provider.getProviderId());
    // Fail closed: any signing attempt throws CONFIG rather than silently doing nothing.
    QesException e =
        assertThrows(
            QesException.class,
            () ->
                provider.startSigning(
                    new QesSigningRequest("x".getBytes(StandardCharsets.UTF_8), null, null)));
    assertEquals(QesException.Category.CONFIG, e.getCategory());
  }

  @Test
  void unknownProviderFailsClosed() {
    System.setProperty(CloudQesConfig.PROP_PROVIDER, "btrust");
    assertEquals("none", resolve().getProviderId());
  }

  @Test
  void evrotrustSelectedButUnconfiguredFailsClosed() {
    // Provider chosen, but no environment/vendor/secret-ref: never half-configured.
    System.setProperty(CloudQesConfig.PROP_PROVIDER, "evrotrust");
    assertEquals("none", resolve().getProviderId());
  }

  @Test
  void evrotrustSelectedAndConfiguredResolvesToEvrotrust() {
    System.setProperty(CloudQesConfig.PROP_PROVIDER, "evrotrust");
    System.setProperty(EvrotrustConfig.PROP_ENVIRONMENT, "test");
    System.setProperty(EvrotrustConfig.PROP_VENDOR_NUMBER, "V-123");
    System.setProperty(EvrotrustConfig.PROP_API_KEY_SECRET_REF, "SOME_SECRET_REF");
    assertEquals(CloudQesProviderIds.EVROTRUST, resolve().getProviderId());
  }

  @Test
  void mockSelectedExplicitly() {
    System.setProperty(CloudQesConfig.PROP_PROVIDER, "mock");
    System.setProperty(CloudQesConfig.PROP_MOCK_SCENARIO, "user_rejection");
    CloudQesProvider provider = resolve();
    assertEquals(MockCloudQesProvider.PROVIDER_ID, provider.getProviderId());

    String id =
        provider
            .startSigning(new QesSigningRequest("x".getBytes(StandardCharsets.UTF_8), null, null))
            .getExternalRequestId();
    assertEquals(QesSigningStatus.REJECTED, provider.pollStatus(id).getStatus());
  }
}
