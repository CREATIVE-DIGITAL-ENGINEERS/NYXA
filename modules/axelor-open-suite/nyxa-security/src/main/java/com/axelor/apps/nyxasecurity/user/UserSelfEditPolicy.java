package com.axelor.apps.nyxasecurity.user;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Which fields of a {@code User} record a non-admin may change.
 *
 * <p>The baseline grants every non-admin write on their <em>own</em> {@code User} (condition {@code
 * self.id = __user__.id}), so they can edit preferences. The permission engine can't limit that
 * write to some fields, and the same record carries the fields that decide what the user may do at
 * all: {@code group} (setting it to {@code admins} bypasses every check), {@code roles}, {@code
 * permissions}, {@code companySet}, and {@code password} (a local password opens HTTP Basic login,
 * which bypasses Keycloak). So this is an <b>allowlist</b>: anything not named here, including any
 * field added to {@code User} later, is refused.
 *
 * <p>Pure decision logic, no Hibernate or database access, so it can be tested directly. {@link
 * UserSelfEditListener} applies it at flush time.
 */
public final class UserSelfEditPolicy {

  /** Preferences a user may change on their own record. */
  static final Set<String> SELF_EDITABLE =
      Set.of(
          "language",
          "localization",
          "theme",
          "noHelp",
          "singleTab",
          "receiveEmails",
          "emailSignature",
          "image",
          // Allowed only within the user's own companySet; UserSelfEditListener checks that.
          "activeCompany",
          // DMS favourites, maintained by the documents UI.
          "favouriteFileSet",
          "favouriteFolderSet");

  /**
   * Fields a user may <em>clear</em> (set to null) but not set. {@code
   * UserService.setActiveCompany} clears the trading name on every company switch, since it belongs
   * to the previous company; choosing a trading name is not self-service.
   */
  static final Set<String> SELF_CLEARABLE = Set.of("tradingName");

  /** Bookkeeping fields the platform itself writes on every update. */
  static final Set<String> MANAGED = Set.of("version", "updatedOn", "updatedBy");

  static final String ADMIN_CODE = "admin";
  static final String ADMIN_GROUP = "admins";

  /** Who is making a change to a {@code User} row, relative to that row. */
  public enum Actor {
    /** No logged-in user: login provisioning, startup, schedulers. Not restricted here. */
    NONE,
    /** An administrator. Admins bypass the permission engine by design. */
    ADMIN,
    /** A non-admin changing their own record. */
    SELF,
    /** A non-admin changing someone else's record. */
    OTHER
  }

  private UserSelfEditPolicy() {}

  /**
   * Classifies the actor. The admin test mirrors {@code AuthUtils.isAdmin}, evaluated on the
   * target's values <em>before</em> this update, so an update that makes a user admin can't
   * authorize itself.
   *
   * @param actorCode code of the logged-in user, or null if there is none
   * @param targetCode the target row's code before this update
   * @param targetGroupCode the target row's group code before this update
   * @param actorIsAdmin whether the actor is an admin; only consulted when the actor is not the
   *     target
   */
  public static Actor classify(
      String actorCode, String targetCode, String targetGroupCode, boolean actorIsAdmin) {
    if (actorCode == null) {
      return Actor.NONE;
    }
    if (actorCode.equals(targetCode)) {
      return isAdmin(targetCode, targetGroupCode) ? Actor.ADMIN : Actor.SELF;
    }
    return actorIsAdmin ? Actor.ADMIN : Actor.OTHER;
  }

  /** Whether this change is the user clearing a field they may clear but not set. */
  public static boolean isAllowedClear(Actor actor, String property, Object newValue) {
    return actor == Actor.SELF && newValue == null && SELF_CLEARABLE.contains(property);
  }

  public static boolean isAdmin(String code, String groupCode) {
    return ADMIN_CODE.equals(code) || ADMIN_GROUP.equals(groupCode);
  }

  /**
   * The first changed field the actor may not change, or null if the change is allowed.
   *
   * @param actor from {@link #classify}
   * @param changed names of the properties this update changes
   */
  public static String firstForbidden(Actor actor, Collection<String> changed) {
    return firstForbidden(actor, changed, false);
  }

  /**
   * Fields a manager may change on <em>another</em> user, and only inside an explicitly opened
   * company-assignment context ({@link CompanyAssignmentScope}): the assigned companies, plus the
   * active company and trading name, which have to follow when a company is removed. Who may
   * assign, and which companies, is checked by {@code CompanyAssignmentRule} before the context is
   * opened.
   */
  static final Set<String> ASSIGNMENT_EDITABLE =
      Set.of("companySet", "activeCompany", "tradingName");

  /**
   * Fields a client administrator may change on <em>another</em> user, and only inside an
   * explicitly opened user-management context ({@link UserManagementScope}): mirroring what the
   * user directory (Keycloak) already accepted — the roles it granted, access (blocked) and the
   * address it holds. Never group, permissions or password. Who may manage whom is checked by
   * {@code UserManagementRule} before the context is opened.
   */
  static final Set<String> MANAGEMENT_EDITABLE = Set.of("roles", "blocked", "email");

  /**
   * @param assignment whether a company-assignment context is open on this thread
   */
  public static String firstForbidden(Actor actor, Collection<String> changed, boolean assignment) {
    return firstForbidden(actor, changed, assignment, false);
  }

  /**
   * Fields a client administrator may change on any staff user's record, its own included, from the
   * user page (Application Config › Users): the user's configuration. Who the user is and what they
   * may do stay out: name and login come from Keycloak at every login, roles go through Keycloak
   * first (a one-time {@link RolesApproval}), and group, permissions, password and access are never
   * the client administrator's. Nor is {@code email}: see {@link #IDENTITY_PROVIDER_OWNED}.
   */
  static final Set<String> CLIENT_ADMIN_EDITABLE =
      Set.of(
          "language",
          "localization",
          "theme",
          "homeAction",
          "singleTab",
          "noHelp",
          "receiveEmails",
          "emailSignature",
          "companySet",
          "activeCompany",
          "tradingName",
          "partner",
          "employee");

  /**
   * Fields the identity provider owns. Login writes them from the token (no actor) and the user
   * directory mirror writes them inside {@link UserManagementScope}; nobody edits them, technical
   * admins included. A local email would be overwritten at the next login anyway, and until then
   * invitations and notifications would go to an address the person never confirmed.
   */
  static final Set<String> IDENTITY_PROVIDER_OWNED = Set.of("email");

  /**
   * The first changed field that only the identity provider may set, or null. Unlike {@link
   * #firstForbidden}, this applies to administrators too.
   *
   * @param management whether a user-management context is open on this thread
   */
  public static String firstIdentityProviderOwned(
      Actor actor, Collection<String> changed, boolean management) {
    if (actor == Actor.NONE || management || changed == null) {
      return null;
    }
    for (String property : changed) {
      if (IDENTITY_PROVIDER_OWNED.contains(property)) {
        return property;
      }
    }
    return null;
  }

  /**
   * @param assignment whether a company-assignment context is open on this thread
   * @param management whether a user-management context is open on this thread
   */
  public static String firstForbidden(
      Actor actor, Collection<String> changed, boolean assignment, boolean management) {
    return firstForbidden(actor, changed, assignment, management, false);
  }

  /**
   * @param assignment whether a company-assignment context is open on this thread
   * @param management whether a user-management context is open on this thread
   * @param clientAdminEdit whether a client administrator is changing a staff user's record
   */
  public static String firstForbidden(
      Actor actor,
      Collection<String> changed,
      boolean assignment,
      boolean management,
      boolean clientAdminEdit) {
    return firstForbidden(actor, changed, assignment, management, clientAdminEdit, false);
  }

  /**
   * Fields a client administrator may change on a Nyxa technical admin's record ({@link
   * #isNyxaAdmin}) from the user page: only the companies it works in. Everything else on that row
   * is Nyxa's.
   */
  static final Set<String> CLIENT_ADMIN_ON_NYXA_ADMIN_EDITABLE = ASSIGNMENT_EDITABLE;

  /**
   * A Nyxa technical admin: a Keycloak login in the {@code admins} group. Never the local {@code
   * admin} account, which is nobody's to change from the tenant.
   */
  public static boolean isNyxaAdmin(String code, String groupCode) {
    return ADMIN_GROUP.equals(groupCode) && !ADMIN_CODE.equals(code);
  }

  /**
   * @param clientAdminEdit whether a client administrator is changing a staff user's record
   * @param clientAdminOnNyxaAdmin whether a client administrator is changing a Nyxa technical
   *     admin's record
   */
  public static String firstForbidden(
      Actor actor,
      Collection<String> changed,
      boolean assignment,
      boolean management,
      boolean clientAdminEdit,
      boolean clientAdminOnNyxaAdmin) {
    if (actor == Actor.NONE || actor == Actor.ADMIN) {
      return null;
    }
    for (String property : changed == null ? Collections.<String>emptyList() : changed) {
      if (MANAGED.contains(property)) {
        continue;
      }
      // Inside the assignment scope (opened only after CompanyAssignmentRule allowed it) the
      // companies may change on any staff user, the client administrator itself included. The
      // management scope works the same way for what the user directory decides.
      boolean allowed =
          (actor == Actor.SELF && SELF_EDITABLE.contains(property))
              || (assignment && ASSIGNMENT_EDITABLE.contains(property))
              || (management && MANAGEMENT_EDITABLE.contains(property))
              || (clientAdminEdit && CLIENT_ADMIN_EDITABLE.contains(property))
              || (clientAdminOnNyxaAdmin && CLIENT_ADMIN_ON_NYXA_ADMIN_EDITABLE.contains(property));
      if (!allowed) {
        return property;
      }
    }
    return null;
  }
}
