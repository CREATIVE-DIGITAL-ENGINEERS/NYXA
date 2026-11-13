package com.axelor.apps.nyxasecurity.user;

import com.axelor.apps.nyxasecurity.gate.Refusals;
import com.axelor.i18n.I18n;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;

/**
 * Who may assign which companies to whom. Pure decision logic, so it can be tested without a
 * database; {@code CompanyAssignmentService} applies it before opening a {@link
 * CompanyAssignmentScope}.
 */
public final class CompanyAssignmentRule {

  /** The client administrator assigns staff to client companies. */
  public static final Set<String> ASSIGNING_ROLES = Set.of("client.admin");

  /** Only ordinary users are assigned this way; admins are managed by admins. */
  public static final String STAFF_GROUP = "staff";

  private CompanyAssignmentRule() {}

  /** What the actor asks for. */
  public static final class Request {
    final boolean actorAdmin;
    final Collection<String> actorRoles;
    final Long actorId;
    final Collection<Long> actorCompanyIds;
    final Long targetId;
    final String targetGroupCode;
    final Collection<Long> companyIds;

    public Request(
        boolean actorAdmin,
        Collection<String> actorRoles,
        Long actorId,
        Collection<Long> actorCompanyIds,
        Long targetId,
        String targetGroupCode,
        Collection<Long> companyIds) {
      this.actorAdmin = actorAdmin;
      this.actorRoles = actorRoles;
      this.actorId = actorId;
      this.actorCompanyIds = actorCompanyIds;
      this.targetId = targetId;
      this.targetGroupCode = targetGroupCode;
      this.companyIds = companyIds;
    }
  }

  /**
   * @return null if the assignment is allowed, otherwise the reason it is refused, as a {@link
   *     Refusals} key ({@link #message} renders it)
   */
  public static String refusal(Request r) {
    if (!r.actorAdmin
        && (r.actorRoles == null || r.actorRoles.stream().noneMatch(ASSIGNING_ROLES::contains))) {
      return Refusals.ONLY_ASSIGNER;
    }
    if (r.targetId == null) {
      return Refusals.CHOOSE_USER;
    }
    if (!STAFF_GROUP.equals(r.targetGroupCode)) {
      return Refusals.ONLY_STAFF;
    }
    if (r.companyIds == null || r.companyIds.isEmpty()) {
      return Refusals.CHOOSE_COMPANY;
    }
    return null;
  }

  /** The refusal for the user, translated; {@link Refusals#ONLY_ASSIGNER} names the role. */
  public static String message(String refusal) {
    return message(refusal, I18n::get);
  }

  /** Same, with the translation passed in. */
  public static String message(String refusal, Function<String, String> translate) {
    return Refusals.ONLY_ASSIGNER.equals(refusal)
        ? Refusals.render(translate, refusal, Refusals.roles(translate, ASSIGNING_ROLES))
        : Refusals.render(translate, refusal);
  }
}
