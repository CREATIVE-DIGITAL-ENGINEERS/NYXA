package com.axelor.apps.napsubmissions.common.signing;

/**
 * Deterministic scenarios for {@link MockCloudQesProvider}. Each one drives a fixed, repeatable
 * outcome so the submission workflow can be exercised without a real provider.
 */
public enum MockQesScenario {

  /** Signs on the first poll: PENDING is never observed. */
  IMMEDIATE_SUCCESS,

  /** First poll returns PENDING, the next poll returns COMPLETED. */
  PENDING_THEN_SUCCESS,

  /** The signer declines: terminal REJECTED, no signature. */
  USER_REJECTION,

  /** The provider cannot complete: terminal FAILED, no signature. */
  PROVIDER_FAILURE,

  /** The signer never acts: terminal EXPIRED, no signature. */
  EXPIRATION,

  /**
   * Completes, but the returned signature is bound to the WRONG payload digest — exercises the
   * caller's mandatory digest-mismatch rejection.
   */
  WRONG_DIGEST;

  /**
   * Parse a configured scenario name, case-insensitive. A blank/unknown value falls back to {@link
   * #IMMEDIATE_SUCCESS} so a mock that is explicitly selected still behaves usefully.
   */
  public static MockQesScenario fromConfig(String value) {
    if (value == null || value.trim().isEmpty()) {
      return IMMEDIATE_SUCCESS;
    }
    try {
      return MockQesScenario.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return IMMEDIATE_SUCCESS;
    }
  }
}
