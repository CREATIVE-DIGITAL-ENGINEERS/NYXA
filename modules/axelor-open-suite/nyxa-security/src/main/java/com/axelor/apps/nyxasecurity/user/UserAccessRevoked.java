package com.axelor.apps.nyxasecurity.user;

/**
 * Fired once a user's access has been revoked, in the identity provider and locally. The
 * application observes it to end that user's open sessions straight away, rather than leave them
 * running into "Unauthorized" on every click (com.weaccount.SingleSessionEnforcer).
 */
public class UserAccessRevoked {

  private final String userCode;

  public UserAccessRevoked(String userCode) {
    this.userCode = userCode;
  }

  /** The revoked user's login ({@code User.code}). */
  public String getUserCode() {
    return userCode;
  }
}
