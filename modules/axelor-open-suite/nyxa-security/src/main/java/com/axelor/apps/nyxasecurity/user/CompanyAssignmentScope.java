package com.axelor.apps.nyxasecurity.user;

import java.util.function.Supplier;

/**
 * Marks the current thread as performing a validated company assignment, so that {@link
 * UserSelfEditListener} lets a non-admin change another user's assigned companies, and only those
 * ({@link UserSelfEditPolicy#ASSIGNMENT_EDITABLE}).
 *
 * <p>Opened only by {@code CompanyAssignmentService}, after {@code CompanyAssignmentRule} has
 * checked the actor, the target and the companies, and closed again in a {@code finally}. The work
 * passed in must flush before returning: the listener runs at flush time, and the scope is gone
 * once this method returns.
 */
public final class CompanyAssignmentScope {

  private static final ThreadLocal<Boolean> OPEN = new ThreadLocal<>();

  private CompanyAssignmentScope() {}

  public static boolean isOpen() {
    return Boolean.TRUE.equals(OPEN.get());
  }

  public static <T> T call(Supplier<T> work) {
    if (isOpen()) {
      throw new IllegalStateException("A company assignment is already in progress.");
    }
    OPEN.set(Boolean.TRUE);
    try {
      return work.get();
    } finally {
      OPEN.remove();
    }
  }
}
