package com.axelor.apps.napsubmissions.common.signing.evrotrust;

/**
 * The two Evrotrust vendor API environments and their official base URLs.
 *
 * <p>Verified against the public Evrotrust developer documentation (docs.evrotrust.com): every
 * vendor endpoint is served under one of these two hosts, and the TEST host is the sandbox. The two
 * are kept strictly separate — exactly like the НАП TEST/PROD split in {@code
 * NraDeclarationsApiClient} — so a TEST profile can never accidentally sign against production or
 * vice versa.
 */
public enum EvrotrustEnvironment {

  /** Sandbox. Non-binding test signatures only. */
  TEST("https://api.sandbox.evrotrust.com/vendor"),

  /** Production. Real qualified signatures. */
  PROD("https://v.evrotrust.com/vendor");

  private final String baseUrl;

  EvrotrustEnvironment(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /** Base URL (no trailing slash); endpoint paths are appended to it. */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Parse a configured value into an environment, or {@code null} when unset/unrecognized. Never
   * guesses a default — an unknown value resolves to {@code null} so the caller fails closed.
   */
  public static EvrotrustEnvironment fromConfig(String value) {
    if (value == null) {
      return null;
    }
    switch (value.trim().toLowerCase()) {
      case "test":
      case "sandbox":
        return TEST;
      case "prod":
      case "production":
        return PROD;
      default:
        return null;
    }
  }
}
