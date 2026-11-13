package com.axelor.apps.nyxasecurity.user;

import java.util.Collection;
import java.util.Objects;

/**
 * Which company a user should be working in after login.
 *
 * <p>Company-scoped permissions compare records against {@code __user__.activeCompany}, so an
 * active company outside the user's assigned companies ({@code companySet}) would either show
 * nothing or, worse, keep showing a company the user was removed from. Companies are assigned by an
 * administrator in Axelor; this rule never assigns any, it only keeps the active one inside the
 * assigned ones.
 *
 * <p>Pure decision logic on company ids, so it can be tested without a database.
 */
public final class ActiveCompanyRule {

  private ActiveCompanyRule() {}

  /** The result of {@link #decide}: whether to change the active company, and to what. */
  public static final class Decision {
    static final Decision KEEP = new Decision(false, null);

    final boolean change;
    final Long companyId;

    private Decision(boolean change, Long companyId) {
      this.change = change;
      this.companyId = companyId;
    }

    public boolean isChange() {
      return change;
    }

    /** The new active company id; null means clear it. Only meaningful when {@link #isChange}. */
    public Long getCompanyId() {
      return companyId;
    }
  }

  /**
   * @param activeId the user's current active company id, or null
   * @param assignedIds the ids of the user's assigned companies ({@code companySet})
   * @return keep, switch to the lowest assigned id (deterministic), or clear when none is assigned
   */
  public static Decision decide(Long activeId, Collection<Long> assignedIds) {
    if (activeId != null && assignedIds.contains(activeId)) {
      return Decision.KEEP; // a valid choice the user made themselves
    }
    Long first = assignedIds.stream().filter(Objects::nonNull).min(Long::compare).orElse(null);
    if (Objects.equals(first, activeId)) {
      return Decision.KEEP; // nothing assigned and nothing active
    }
    return new Decision(true, first);
  }
}
