package com.axelor.apps.nyxasecurity.directory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Outcome of {@link TenantUserDirectory#invite}. */
public final class InviteResult {

  private final DirectoryUser user;
  private final boolean created;
  private final Set<String> rolesAdded;

  public InviteResult(DirectoryUser user, boolean created, Set<String> rolesAdded) {
    this.user = user;
    this.created = created;
    this.rolesAdded =
        rolesAdded == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(new LinkedHashSet<>(rolesAdded));
  }

  public DirectoryUser getUser() {
    return user;
  }

  /** True when the person had no account in the realm before, false when one was reused. */
  public boolean isCreated() {
    return created;
  }

  public Set<String> getRolesAdded() {
    return rolesAdded;
  }
}
