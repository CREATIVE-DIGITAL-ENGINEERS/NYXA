package com.axelor.apps.bglocalization.service.vat.submission;

import java.net.URI;

/**
 * Configuration for the LOCAL MOCK VAT submission gateway. Deliberately dev-only and safe by
 * default: submission is <b>disabled</b> unless explicitly enabled, and only loopback/private
 * (local) URLs are accepted so no real НАП endpoint can ever be targeted. Resolved from JVM system
 * properties first, then environment variables — never from the database, so the application starts
 * and previews files normally with no configuration and no running mock server.
 *
 * <ul>
 *   <li>{@code nyxa.vat.mock.enabled} / {@code NYXA_VAT_MOCK_ENABLED} — default {@code false}
 *   <li>{@code nyxa.vat.mock.url} / {@code NYXA_VAT_MOCK_URL} — default {@code
 *       http://127.0.0.1:8899}
 *   <li>{@code nyxa.vat.mock.requestTimeoutMs} — default 15000
 *   <li>{@code nyxa.vat.mock.connectTimeoutMs} — default 5000
 * </ul>
 */
public class BgVatMockConfig {

  public static final String DEFAULT_URL = "http://127.0.0.1:8899";
  public static final int DEFAULT_REQUEST_TIMEOUT_MS = 15000;
  public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;

  private final boolean enabled;
  private final String baseUrl;
  private final int requestTimeoutMs;
  private final int connectTimeoutMs;

  public BgVatMockConfig(
      boolean enabled, String baseUrl, int requestTimeoutMs, int connectTimeoutMs) {
    this.enabled = enabled;
    this.baseUrl = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_URL : baseUrl.trim();
    this.requestTimeoutMs = requestTimeoutMs > 0 ? requestTimeoutMs : DEFAULT_REQUEST_TIMEOUT_MS;
    this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
  }

  public static BgVatMockConfig fromEnvironment() {
    boolean enabled =
        Boolean.parseBoolean(resolve("nyxa.vat.mock.enabled", "NYXA_VAT_MOCK_ENABLED"));
    String url = resolve("nyxa.vat.mock.url", "NYXA_VAT_MOCK_URL");
    int requestTimeout =
        parseInt(
            resolve("nyxa.vat.mock.requestTimeoutMs", "NYXA_VAT_MOCK_REQUEST_TIMEOUT_MS"),
            DEFAULT_REQUEST_TIMEOUT_MS);
    int connectTimeout =
        parseInt(
            resolve("nyxa.vat.mock.connectTimeoutMs", "NYXA_VAT_MOCK_CONNECT_TIMEOUT_MS"),
            DEFAULT_CONNECT_TIMEOUT_MS);
    return new BgVatMockConfig(enabled, url, requestTimeout, connectTimeout);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public int getRequestTimeoutMs() {
    return requestTimeoutMs;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  /** True when this config is safe to use for sending: enabled AND targeting a local URL. */
  public boolean isUsable() {
    return enabled && isLoopbackOrLocal(baseUrl);
  }

  /**
   * Accept only loopback / private (RFC1918) hosts so a real, public НАП endpoint can never be
   * targeted from this mock gateway.
   */
  public static boolean isLoopbackOrLocal(String url) {
    if (url == null || url.trim().isEmpty()) {
      return false;
    }
    String host;
    try {
      host = URI.create(url.trim()).getHost();
    } catch (RuntimeException e) {
      return false;
    }
    if (host == null) {
      return false;
    }
    host = host.toLowerCase();
    if (host.equals("localhost")
        || host.equals("::1")
        || host.equals("0:0:0:0:0:0:0:1")
        || host.equals("0.0.0.0")
        || host.startsWith("127.")
        || host.startsWith("10.")
        || host.startsWith("192.168.")) {
      return true;
    }
    // 172.16.0.0 – 172.31.255.255
    if (host.startsWith("172.")) {
      String[] parts = host.split("\\.");
      if (parts.length >= 2) {
        try {
          int second = Integer.parseInt(parts[1]);
          return second >= 16 && second <= 31;
        } catch (NumberFormatException ignore) {
          return false;
        }
      }
    }
    return false;
  }

  private static String resolve(String propertyKey, String envKey) {
    String value = System.getProperty(propertyKey);
    if (value != null && !value.trim().isEmpty()) {
      return value.trim();
    }
    value = System.getenv(envKey);
    return value == null ? null : value.trim();
  }

  private static int parseInt(String value, int fallback) {
    if (value == null || value.trim().isEmpty()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
