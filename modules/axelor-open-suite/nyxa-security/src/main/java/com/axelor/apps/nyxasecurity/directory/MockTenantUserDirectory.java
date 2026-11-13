package com.axelor.apps.nyxasecurity.directory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Singleton;

/**
 * In-memory {@link TenantUserDirectory} for development and tests ({@code nyxa.users.directory =
 * mock}). Applies the same rules as the Keycloak endpoint, so the screens behave the same offline.
 * Forgets everything on restart. The owner (onboarding's {@code client.owner}) can only be set
 * through {@link #markOwner}, as in Keycloak only infra grants it. Never use it in a deployed
 * environment: invitations made through it grant nothing in Keycloak.
 */
@Singleton
public class MockTenantUserDirectory implements TenantUserDirectory {

  private static final class Entry {
    final String id;
    final String email;
    final Set<String> roles = new LinkedHashSet<>();
    boolean loggedIn;
    boolean owner;

    Entry(String id, String email) {
      this.id = id;
      this.email = email;
    }
  }

  /** Keyed by id; only people with access to this tenant. */
  private final Map<String, Entry> users = new LinkedHashMap<>();

  @Override
  public synchronized List<DirectoryUser> list() {
    List<DirectoryUser> out = new ArrayList<>();
    users.values().forEach(entry -> out.add(toUser(entry)));
    return out;
  }

  @Override
  public synchronized DirectoryUser get(String id) {
    return toUser(find(id));
  }

  @Override
  public synchronized InviteResult invite(String email, Set<String> roles) {
    String address = DirectoryRules.normalizeEmail(email);
    Set<String> requested = DirectoryRules.checkGrantable(roles);
    Entry entry = byEmail(address);
    boolean created = entry == null;
    if (created) {
      entry = new Entry(UUID.randomUUID().toString(), address);
      users.put(entry.id, entry);
    } else if (entry.roles.contains(DirectoryRules.NYXA_ADMIN)) {
      throw new DirectoryException(409, "managed_by_nyxa", "This user is managed by Nyxa.");
    } else if (entry.owner) {
      throw new DirectoryException(409, "owner", "This user is the tenant's owner.");
    }
    Set<String> added = new LinkedHashSet<>(requested);
    added.removeAll(entry.roles);
    entry.roles.addAll(requested);
    return new InviteResult(toUser(entry), created, added);
  }

  @Override
  public synchronized DirectoryUser setRoles(String id, Set<String> roles) {
    Entry entry = modifiable(id);
    Set<String> requested = DirectoryRules.checkGrantable(roles);
    checkAdminRemains(entry, requested.contains(DirectoryRules.CLIENT_ADMIN));
    entry.roles.clear();
    entry.roles.addAll(requested);
    return toUser(entry);
  }

  @Override
  public synchronized void revoke(String id) {
    Entry entry = modifiable(id);
    if (entry.owner) {
      throw new DirectoryException(403, "owner", "The tenant's owner can't be revoked here.");
    }
    checkAdminRemains(entry, false);
    users.remove(entry.id);
  }

  /** Test and demo hook: makes a user the tenant's owner, as onboarding does in Keycloak. */
  public synchronized void markOwner(String id) {
    find(id).owner = true;
  }

  /** Test and demo hook: marks a user as having accepted their invitation. */
  public synchronized void markLoggedIn(String id) {
    find(id).loggedIn = true;
  }

  private Entry modifiable(String id) {
    Entry entry = find(id);
    if (entry.roles.contains(DirectoryRules.NYXA_ADMIN)) {
      throw new DirectoryException(403, "managed_by_nyxa", "This user is managed by Nyxa.");
    }
    return entry;
  }

  private void checkAdminRemains(Entry target, boolean targetIsAdminAfter) {
    int otherAdmins = 0;
    for (Entry entry : users.values()) {
      if (entry != target
          && entry.roles.contains(DirectoryRules.CLIENT_ADMIN)
          && !entry.roles.contains(DirectoryRules.NYXA_ADMIN)) {
        otherAdmins++;
      }
    }
    // Only a change that takes client.admin away from someone can remove the last one.
    if (target.roles.contains(DirectoryRules.CLIENT_ADMIN)
        && DirectoryRules.removesLastAdmin(otherAdmins, targetIsAdminAfter)) {
      throw new DirectoryException(
          409, "last_admin", "At least one client administrator must remain.");
    }
  }

  private Entry find(String id) {
    Entry entry = id == null ? null : users.get(id);
    if (entry == null) {
      throw new DirectoryException(404, "not_found", "No such user in this tenant.");
    }
    return entry;
  }

  private Entry byEmail(String email) {
    for (Entry entry : users.values()) {
      if (entry.email.equals(email)) {
        return entry;
      }
    }
    return null;
  }

  private static DirectoryUser toUser(Entry entry) {
    return new DirectoryUser(
        entry.id,
        entry.email,
        null,
        null,
        true,
        entry.loggedIn,
        entry.roles,
        entry.roles.contains(DirectoryRules.NYXA_ADMIN),
        entry.owner);
  }
}
