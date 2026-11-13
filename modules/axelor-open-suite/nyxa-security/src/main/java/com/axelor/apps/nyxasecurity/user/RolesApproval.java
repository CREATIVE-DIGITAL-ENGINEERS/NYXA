package com.axelor.apps.nyxasecurity.user;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A one-time permission to save a user's roles, granted only after Keycloak accepted exactly those
 * roles.
 *
 * <p>The user page saves roles with the platform's normal Save. Its {@code onSave} action sends the
 * roles to Keycloak first and, if Keycloak accepts, records an approval here; the save request that
 * follows then writes them, and {@link UserSelfEditListener} lets the {@code roles} change through
 * only if it matches an approval for that actor, user and set of role names. The approval is used
 * up by that match. So the local roles can't be changed by a save that skipped Keycloak, or by one
 * that differs from what Keycloak accepted.
 *
 * <p>The two requests are separate, so the approval lives in this JVM for a short while. That holds
 * for the current single-container deployment, as {@code SingleSessionEnforcer}'s session registry
 * does; a scaled-out deployment would need it in the database.
 */
public final class RolesApproval {

  /** Long enough for the save that follows the {@code onSave} action. */
  static final long TTL_MS = 120_000L;

  private static final Map<String, Entry> APPROVALS = new ConcurrentHashMap<>();

  private static final class Entry {
    final Set<String> roles;
    final long expiresAt;

    Entry(Set<String> roles, long expiresAt) {
      this.roles = roles;
      this.expiresAt = expiresAt;
    }
  }

  private RolesApproval() {}

  /** Records that Keycloak accepted exactly {@code roles} for this user, from this actor. */
  public static void approve(String actorCode, Long userId, Set<String> roles) {
    APPROVALS.put(key(actorCode, userId), new Entry(new HashSet<>(roles), now() + TTL_MS));
  }

  /**
   * @return whether an unexpired approval for exactly these roles existed; it is used up either way
   *     once it matched
   */
  public static boolean consume(String actorCode, Long userId, Set<String> roles) {
    String key = key(actorCode, userId);
    Entry entry = APPROVALS.get(key);
    if (entry == null) {
      return false;
    }
    if (entry.expiresAt < now()) {
      APPROVALS.remove(key, entry);
      return false;
    }
    if (!entry.roles.equals(roles)) {
      return false;
    }
    return APPROVALS.remove(key, entry);
  }

  private static String key(String actorCode, Long userId) {
    return actorCode + "|" + userId;
  }

  static long now() {
    return System.currentTimeMillis();
  }
}
