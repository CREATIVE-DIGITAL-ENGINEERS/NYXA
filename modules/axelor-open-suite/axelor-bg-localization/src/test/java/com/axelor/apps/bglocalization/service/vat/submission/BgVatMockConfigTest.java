package com.axelor.apps.bglocalization.service.vat.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Safety tests for {@link BgVatMockConfig}: loopback-only acceptance and disabled-by-default. */
class BgVatMockConfigTest {

  @Test
  void loopbackAndPrivateHostsAreAccepted() {
    assertTrue(BgVatMockConfig.isLoopbackOrLocal("http://127.0.0.1:8899"));
    assertTrue(BgVatMockConfig.isLoopbackOrLocal("http://localhost:8899"));
    assertTrue(BgVatMockConfig.isLoopbackOrLocal("http://10.0.0.5:9000"));
    assertTrue(BgVatMockConfig.isLoopbackOrLocal("http://192.168.1.10"));
    assertTrue(BgVatMockConfig.isLoopbackOrLocal("http://172.16.0.1"));
    assertTrue(BgVatMockConfig.isLoopbackOrLocal("http://172.31.255.254"));
  }

  @Test
  void publicAndInvalidHostsAreRejected() {
    assertFalse(BgVatMockConfig.isLoopbackOrLocal("http://8.8.8.8"));
    assertFalse(BgVatMockConfig.isLoopbackOrLocal("https://api.nra.bg/vat"));
    assertFalse(BgVatMockConfig.isLoopbackOrLocal("http://172.32.0.1")); // outside 16..31
    assertFalse(BgVatMockConfig.isLoopbackOrLocal("not a url"));
    assertFalse(BgVatMockConfig.isLoopbackOrLocal(null));
    assertFalse(BgVatMockConfig.isLoopbackOrLocal(""));
  }

  @Test
  void isUsableRequiresEnabledAndLocal() {
    assertTrue(new BgVatMockConfig(true, "http://127.0.0.1:8899", 0, 0).isUsable());
    assertFalse(new BgVatMockConfig(false, "http://127.0.0.1:8899", 0, 0).isUsable(), "disabled");
    assertFalse(new BgVatMockConfig(true, "https://api.nra.bg", 0, 0).isUsable(), "non-local");
  }

  @Test
  void defaultsAreSafe() {
    BgVatMockConfig config = new BgVatMockConfig(false, null, 0, 0);
    assertEquals(BgVatMockConfig.DEFAULT_URL, config.getBaseUrl());
    assertEquals(BgVatMockConfig.DEFAULT_REQUEST_TIMEOUT_MS, config.getRequestTimeoutMs());
    assertEquals(BgVatMockConfig.DEFAULT_CONNECT_TIMEOUT_MS, config.getConnectTimeoutMs());
    assertFalse(config.isEnabled(), "disabled by default");
    assertFalse(config.isUsable(), "not usable until explicitly enabled");
  }

  @Test
  void fromEnvironmentReadsSystemProperties() {
    String prevEnabled = System.getProperty("nyxa.vat.mock.enabled");
    String prevUrl = System.getProperty("nyxa.vat.mock.url");
    try {
      System.setProperty("nyxa.vat.mock.enabled", "true");
      System.setProperty("nyxa.vat.mock.url", "http://127.0.0.1:9123");
      BgVatMockConfig config = BgVatMockConfig.fromEnvironment();
      assertTrue(config.isEnabled());
      assertEquals("http://127.0.0.1:9123", config.getBaseUrl());
      assertTrue(config.isUsable());
    } finally {
      restore("nyxa.vat.mock.enabled", prevEnabled);
      restore("nyxa.vat.mock.url", prevUrl);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
