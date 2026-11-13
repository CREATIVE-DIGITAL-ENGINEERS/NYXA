package com.axelor.apps.nyxasecurity.directory;

import java.util.List;
import java.util.Set;

/**
 * Who may use this tenant, and with which roles, in the identity provider.
 *
 * <p>In production this is Keycloak's {@code client-users} endpoint (weAccount.Keycloak), called
 * with this tenant's own client credentials. That endpoint takes the tenant from the token, so this
 * application can only ever manage its own users, and it enforces the rules itself: catalogue roles
 * only, never {@code nyxa.admin}, never the last {@code client.admin}, never a Nyxa-managed user.
 * Callers still validate first, for a clear message, but must not rely on being the only check.
 *
 * <p>Every method throws {@link DirectoryException} on a refusal or an outage.
 */
public interface TenantUserDirectory {

  /** Everyone with access to this tenant. */
  List<DirectoryUser> list();

  /** One user, or a 404 {@link DirectoryException} if they have no access to this tenant. */
  DirectoryUser get(String id);

  /**
   * Gives a person access to this tenant with these roles, creating their account if they have
   * none. Safe to repeat.
   *
   * @param email any case; the directory lowercases it
   * @param roles functional roles; may be empty
   */
  InviteResult invite(String email, Set<String> roles);

  /** Replaces this tenant's functional roles of a user with exactly {@code roles}. */
  DirectoryUser setRoles(String id, Set<String> roles);

  /**
   * Removes the user's access to this tenant and all its roles. The account itself is left alone:
   * it may belong to other tenants.
   */
  void revoke(String id);
}
