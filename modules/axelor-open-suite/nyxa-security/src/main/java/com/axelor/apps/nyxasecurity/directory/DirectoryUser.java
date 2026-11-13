package com.axelor.apps.nyxasecurity.directory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A person with access to this tenant, as the identity provider (Keycloak) reports them. Keycloak
 * is the source of truth for access and roles; the local {@code User} row mirrors this.
 */
public final class DirectoryUser {

  private final String id;
  private final String email;
  private final String firstName;
  private final String lastName;
  private final boolean enabled;
  private final boolean hasLoggedIn;
  private final Set<String> roles;
  private final boolean managedByNyxa;
  private final boolean owner;

  public DirectoryUser(
      String id,
      String email,
      String firstName,
      String lastName,
      boolean enabled,
      boolean hasLoggedIn,
      Set<String> roles,
      boolean managedByNyxa,
      boolean owner) {
    this.id = id;
    this.email = email;
    this.firstName = firstName;
    this.lastName = lastName;
    this.enabled = enabled;
    this.hasLoggedIn = hasLoggedIn;
    this.roles =
        roles == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    this.managedByNyxa = managedByNyxa;
    this.owner = owner;
  }

  /** Keycloak's user id. Stable, unlike the email. */
  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  /** False when Nyxa disabled the account realm-wide; this tenant can't change that. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Whether the person has signed in at least once, i.e. accepted the invitation. */
  public boolean hasLoggedIn() {
    return hasLoggedIn;
  }

  /** This tenant's functional roles, never {@code restricted-access}. */
  public Set<String> getRoles() {
    return roles;
  }

  /** Holds {@code nyxa.admin} here: Nyxa staff, read-only to the tenant. */
  public boolean isManagedByNyxa() {
    return managedByNyxa;
  }

  /**
   * Holds {@code client.owner} here: the tenant's initial client administrator, granted at
   * onboarding. Only Nyxa may change their roles, and nobody revokes them from the app. The owner
   * role itself never appears in {@link #getRoles()}.
   */
  public boolean isOwner() {
    return owner;
  }
}
