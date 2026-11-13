package com.axelor.apps.nyxasecurity.user;

import java.util.function.Supplier;

/**
 * Marks the current thread as mirroring a validated change from the user directory (Keycloak) onto
 * a local {@code User}: an invitation, a role change or a revocation. Inside it, {@link
 * UserSelfEditListener} lets a non-admin change another user's {@link
 * UserSelfEditPolicy#MANAGEMENT_EDITABLE} fields, and only those.
 *
 * <p>Opened only by {@code TenantUserService}, after {@code UserManagementRule} has checked the
 * actor and target and the directory has accepted the change. Same contract as {@link
 * CompanyAssignmentScope}: the work must flush before returning.
 */
public final class UserManagementScope {

  private static final ThreadLocal<Boolean> OPEN = new ThreadLocal<>();

  private UserManagementScope() {}

  public static boolean isOpen() {
    return Boolean.TRUE.equals(OPEN.get());
  }

  public static <T> T call(Supplier<T> work) {
    if (isOpen()) {
      throw new IllegalStateException("A user management change is already in progress.");
    }
    OPEN.set(Boolean.TRUE);
    try {
      return work.get();
    } finally {
      OPEN.remove();
    }
  }
}
