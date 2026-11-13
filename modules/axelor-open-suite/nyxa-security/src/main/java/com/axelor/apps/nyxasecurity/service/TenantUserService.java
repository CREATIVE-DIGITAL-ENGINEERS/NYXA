package com.axelor.apps.nyxasecurity.service;

import com.axelor.app.AppSettings;
import com.axelor.apps.nyxasecurity.directory.DirectoryException;
import com.axelor.apps.nyxasecurity.directory.DirectoryRules;
import com.axelor.apps.nyxasecurity.directory.DirectoryUser;
import com.axelor.apps.nyxasecurity.directory.InviteResult;
import com.axelor.apps.nyxasecurity.directory.TenantUserDirectory;
import com.axelor.apps.nyxasecurity.mail.InvitationMailer;
import com.axelor.apps.nyxasecurity.user.RolesApproval;
import com.axelor.apps.nyxasecurity.user.UserAccessRevoked;
import com.axelor.apps.nyxasecurity.user.UserManagementRule;
import com.axelor.apps.nyxasecurity.user.UserManagementRule.Operation;
import com.axelor.apps.nyxasecurity.user.UserManagementScope;
import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy;
import com.axelor.auth.AuthService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.GroupRepository;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.event.Event;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages who may use this tenant: invite, change roles, revoke, and sync from the directory.
 *
 * <p>Keycloak is the source of truth. Every change goes to the {@link TenantUserDirectory} first;
 * only once it has accepted is the local {@code User} brought in line, inside a {@link
 * UserManagementScope} (the only way the user self-edit guard lets a client administrator change
 * another user). Login keeps mirroring roles from the token as before, so a local write that fails
 * after the directory accepted is corrected at the person's next login.
 *
 * <p>Local users are matched on {@code code}, which login sets to the Keycloak username, which is
 * the lowercased email. An invitation creates the local user straight away, so a client
 * administrator can assign companies before the person first signs in. Revoking blocks the local
 * user (every request is refused from then on) but keeps it, with its history and companies;
 * inviting the same address again unblocks it.
 */
@Singleton
public class TenantUserService {

  private static final Logger LOG = LoggerFactory.getLogger(TenantUserService.class);

  private final TenantUserDirectory directory;
  private final UserRepository userRepo;
  private final RoleRepository roleRepo;
  private final GroupRepository groupRepo;
  private final AuthService authService;
  private final InvitationMailer mailer;
  private final Event<UserAccessRevoked> accessRevoked;

  /** Result of an invitation, for the caller to report and to send the invitation email. */
  public static final class Invitation {
    private final User user;
    private final InviteResult result;
    private final String mailProblem;

    Invitation(User user, InviteResult result, String mailProblem) {
      this.user = user;
      this.result = result;
      this.mailProblem = mailProblem;
    }

    public User getUser() {
      return user;
    }

    public InviteResult getResult() {
      return result;
    }

    /** Null when the invitation email was queued; else why not. Access is granted either way. */
    public String getMailProblem() {
      return mailProblem;
    }
  }

  @Inject
  public TenantUserService(
      TenantUserDirectory directory,
      UserRepository userRepo,
      RoleRepository roleRepo,
      GroupRepository groupRepo,
      AuthService authService,
      InvitationMailer mailer,
      Event<UserAccessRevoked> accessRevoked) {
    this.directory = directory;
    this.userRepo = userRepo;
    this.roleRepo = roleRepo;
    this.groupRepo = groupRepo;
    this.authService = authService;
    this.mailer = mailer;
    this.accessRevoked = accessRevoked;
  }

  /**
   * Gives a person access with these roles and creates or unblocks their local user.
   *
   * @throws IllegalArgumentException when the actor may not do this
   * @throws DirectoryException when the directory refuses or is unavailable
   */
  public Invitation invite(String email, Collection<String> roles) {
    String address = DirectoryRules.normalizeEmail(email);
    Set<String> requested = DirectoryRules.checkGrantable(roles);
    // Someone invited with no role could sign in to an empty application: refuse it outright.
    if (requested.isEmpty()) {
      throw new IllegalArgumentException("Choose at least one role.");
    }
    User existing = findLocal(address);
    User actor = checkAllowed(Operation.INVITE, existing, null, null, false);
    List<DirectoryUser> people = directory.list();
    checkAllowed(
        Operation.INVITE,
        existing,
        find(people, address),
        find(people, actor.getCode()),
        requested.contains(DirectoryRules.CLIENT_ADMIN));

    InviteResult result = directory.invite(address, requested);
    User user = mirror(existing, result.getUser());
    LOG.info(
        "User management by {}: invited {} (account {}, roles added {})",
        actor.getCode(),
        user.getCode(),
        result.isCreated() ? "created" : "reused",
        result.getRolesAdded());
    // Only now that access is granted; a failed email leaves the invitation valid and resendable.
    String mailProblem = mailer.send(user, result.getUser().getRoles(), actor.getLanguage());
    return new Invitation(user, result, mailProblem);
  }

  /**
   * Gives a revoked user access again with these roles: exactly an invitation of their address
   * (same rules, the email included), which also unblocks the local user.
   */
  public Invitation restoreAccess(Long userId, Collection<String> roles) {
    User target = require(userId);
    return invite(target.getCode(), roles);
  }

  /**
   * Sends the invitation email again, for someone who still has access.
   *
   * @return a summary for the administrator
   */
  public String resendInvitation(Long userId) {
    User target = require(userId);
    User actor = checkAllowed(Operation.RESEND, target, null, null, false);
    DirectoryUser person = required(find(directory.list(), target.getCode()), target);
    String problem = mailer.send(target, person.getRoles(), actor.getLanguage());
    LOG.info(
        "User management by {}: invitation to {} resent ({})",
        actor.getCode(),
        target.getCode(),
        problem == null ? "queued" : "not sent");
    return problem == null ? "Invitation sent again to " + target.getCode() + "." : problem;
  }

  /** Replaces the user's roles in this tenant with exactly {@code roles}. */
  public String changeRoles(Long userId, Collection<String> roles) {
    User target = require(userId);
    Set<String> requested = DirectoryRules.checkGrantable(roles);
    requireARoleWhileWithAccess(target, requested);
    User actor = checkAllowed(Operation.CHANGE_ROLES, target, null, null, false);
    List<DirectoryUser> people = directory.list();
    DirectoryUser person = required(find(people, target.getCode()), target);
    checkAllowed(
        Operation.CHANGE_ROLES,
        target,
        person,
        find(people, actor.getCode()),
        requested.contains(DirectoryRules.CLIENT_ADMIN));

    DirectoryUser updated = directory.setRoles(person.getId(), requested);
    mirror(target, updated);
    LOG.info(
        "User management by {}: roles of {} set to {}",
        actor.getCode(),
        target.getCode(),
        updated.getRoles());
    return "Roles of " + target.getCode() + ": " + display(updated.getRoles());
  }

  /**
   * Before the user page saves: when the roles on the page differ from the user's current ones,
   * sends them to Keycloak (same rules as {@link #changeRoles}) and records a one-time {@link
   * RolesApproval} for the save that follows. Nothing is written locally here: the page's own save
   * writes the roles together with the rest of the user's configuration.
   *
   * @return the roles the save must write, as Keycloak accepted them; or null when the roles did
   *     not change
   */
  public Set<String> approveRolesForSave(Long userId, Collection<String> roles) {
    User target = require(userId);
    if (isTechnicalAdmin(target)) {
      // Nyxa staff: their roles come from their own login and are never sent from here; the page
      // only changes their companies. A role change slipped into the save is refused by the
      // self-edit guard, as it has no approval.
      return null;
    }
    Set<String> requested = DirectoryRules.checkGrantable(roles);
    requireARoleWhileWithAccess(target, requested);
    if (requested.equals(localRoleNames(target))) {
      return null;
    }
    if (Boolean.TRUE.equals(target.getBlocked())) {
      // No access in the identity provider any more, so no roles to change there.
      throw new IllegalArgumentException("Access is revoked. Use Restore access to give it back.");
    }
    User actor = checkAllowed(Operation.CHANGE_ROLES, target, null, null, false);
    List<DirectoryUser> people = directory.list();
    DirectoryUser person = required(find(people, target.getCode()), target);
    checkAllowed(
        Operation.CHANGE_ROLES,
        target,
        person,
        find(people, actor.getCode()),
        requested.contains(DirectoryRules.CLIENT_ADMIN));

    DirectoryUser updated = directory.setRoles(person.getId(), requested);
    Set<String> toSave = new TreeSet<>();
    for (String name : updated.getRoles()) {
      if (roleRepo.findByName(name) != null) {
        toSave.add(name);
      }
    }
    RolesApproval.approve(actor.getCode(), target.getId(), toSave);
    LOG.info(
        "User management by {}: roles of {} set to {} (saved with the user page)",
        actor.getCode(),
        target.getCode(),
        toSave);
    return toSave;
  }

  /** Removes the user's access to this tenant and blocks their local user. */
  public String revoke(Long userId) {
    User target = require(userId);
    User actor = checkAllowed(Operation.REVOKE, target, null, null, false);
    List<DirectoryUser> people = directory.list();
    DirectoryUser person = required(find(people, target.getCode()), target);
    checkAllowed(Operation.REVOKE, target, person, find(people, actor.getCode()), false);

    directory.revoke(person.getId());
    inScope(
        () -> {
          User user = userRepo.find(target.getId());
          user.setBlocked(true);
          user.clearRoles();
          userRepo.save(user);
        });
    LOG.info("User management by {}: revoked access of {}", actor.getCode(), target.getCode());
    accessRevoked.fire(new UserAccessRevoked(target.getCode()));
    return "Access revoked for " + target.getCode() + ".";
  }

  /** How long a sync stays fresh before opening the Users list runs another one. */
  static final long SYNC_INTERVAL_MS = 5 * 60_000L;

  private static volatile long lastSyncMs;

  /**
   * Run when the Users list is opened: brings local users in line with the identity provider, at
   * most once per {@link #SYNC_INTERVAL_MS}. Never stops the list from opening.
   *
   * @return null when the list is up to date (synced now or recently); else why it couldn't be
   *     refreshed, for a notice on the list
   */
  public String syncOnOpen() {
    if (System.currentTimeMillis() - lastSyncMs < SYNC_INTERVAL_MS) {
      return null;
    }
    try {
      return syncPeople().failed == 0
          ? null
          : "Some users could not be updated from the identity provider. The list shows"
              + " what this application last knew for them.";
    } catch (DirectoryException e) {
      LOG.warn("User list not refreshed from the identity provider: {}", e.getMessage());
      return "The user list could not be refreshed from the identity provider. It shows what"
          + " this application last knew.";
    }
  }

  /** Lets the next {@link #syncOnOpen} run at once (tests, and after a change made here). */
  static void forgetLastSync() {
    lastSyncMs = 0;
  }

  /**
   * Brings local users in line with the identity provider: creates the ones who have access but
   * never signed in (for example the first administrator, invited at onboarding), and sets
   * everyone's roles and unblocks them. Writes only users that differ, so it can run whenever the
   * list is opened without touching rows someone may be editing. Nobody is blocked by a sync:
   * people without access show it in their status, and revoking is an explicit action.
   *
   * <p>Existing users are brought in line before new ones are created, each in its own transaction:
   * correcting an existing user's email can free the address a new user needs. A person who can't
   * be written is logged and skipped, and the others still are.
   *
   * @return a summary, for the log
   */
  public String sync() {
    return syncPeople().toString();
  }

  private static final class SyncCounts {
    int created;
    int updated;
    int failed;

    @Override
    public String toString() {
      return "created " + created + ", updated " + updated + ", failed " + failed;
    }
  }

  private SyncCounts syncPeople() {
    User actor = checkAllowed(Operation.SYNC, null, null, null, false);
    List<DirectoryUser> people = directory.list();

    List<DirectoryUser> toCreate = new ArrayList<>();
    SyncCounts counts = new SyncCounts();
    for (DirectoryUser person : people) {
      String code = person.getEmail() == null ? null : person.getEmail().toLowerCase(Locale.ROOT);
      if (code == null) {
        continue;
      }
      User local = findLocal(code);
      // Nyxa staff and technical admins get their group and roles from their own login.
      if (person.isManagedByNyxa() || (local != null && isTechnicalAdmin(local))) {
        continue;
      }
      if (local == null) {
        toCreate.add(person);
      } else if (!inLine(local, person)) {
        if (mirrorOrSkip(local, person)) {
          counts.updated++;
        } else {
          counts.failed++;
        }
      }
    }
    for (DirectoryUser person : toCreate) {
      if (mirrorOrSkip(null, person)) {
        counts.created++;
      } else {
        counts.failed++;
      }
    }
    lastSyncMs = System.currentTimeMillis();
    LOG.info("User management by {}: sync {}", actor.getCode(), counts);
    return counts;
  }

  /** {@link #mirror}, logging instead of failing the whole sync; whether it was written. */
  private boolean mirrorOrSkip(User local, DirectoryUser person) {
    try {
      mirror(local, person);
      return true;
    } catch (RuntimeException e) {
      // The failed transaction may have left its entities in the persistence context.
      JPA.clear();
      LOG.warn("Sync: could not bring {} in line: {}", person.getEmail(), rootMessage(e));
      return false;
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage();
  }

  /**
   * Whether the local user already matches the identity provider: unblocked, its email, same roles.
   */
  private boolean inLine(User local, DirectoryUser person) {
    if (Boolean.TRUE.equals(local.getBlocked())) {
      return false;
    }
    if (!person.getEmail().equalsIgnoreCase(local.getEmail() == null ? "" : local.getEmail())) {
      return false;
    }
    Set<String> expected = new TreeSet<>();
    for (String name : person.getRoles()) {
      if (roleRepo.findByName(name) != null) {
        expected.add(name);
      }
    }
    return expected.equals(localRoleNames(local));
  }

  /**
   * What the directory says about a local user, for display: their roles in this tenant and whether
   * they have access, have signed in, or are blocked here.
   *
   * @return {@code [roles, status]}
   */
  public String[] describe(Long userId) {
    User target = require(userId);
    checkAllowed(Operation.SYNC, null, null, null, false);
    String code = target.getCode() == null ? "" : target.getCode().toLowerCase(Locale.ROOT);
    for (DirectoryUser person : directory.list()) {
      if (person.getEmail() != null && code.equals(person.getEmail().toLowerCase(Locale.ROOT))) {
        String status =
            (person.isOwner() ? "Owner (the tenant's initial administrator). " : "")
                + (person.isManagedByNyxa()
                    ? "Nyxa staff: only the companies can be changed here"
                    : !person.isEnabled()
                        ? "Has access, but the account is disabled by Nyxa"
                        : person.hasLoggedIn() ? "Has access" : "Invited, has not signed in yet");
        return new String[] {display(person.getRoles()), status};
      }
    }
    return new String[] {
      "none",
      Boolean.TRUE.equals(target.getBlocked())
          ? "No access (revoked)"
          : "No access in the identity provider (revoke to block here)"
    };
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Checks the actor against {@link UserManagementRule}. Callers check once before asking the
   * directory (with {@code targetPerson} and {@code actorPerson} null: is the actor an
   * administrator at all), then again with what the directory reports about both.
   *
   * @param targetPerson the target as the directory reports them, or null
   * @param actorPerson the actor as the directory reports them, or null
   * @param grantsClientAdmin whether the requested roles include {@code client.admin}
   */
  private User checkAllowed(
      Operation operation,
      User target,
      DirectoryUser targetPerson,
      DirectoryUser actorPerson,
      boolean grantsClientAdmin) {
    User actor = AuthUtils.getUser();
    if (actor == null) {
      throw new IllegalArgumentException("Not logged in.");
    }
    String refusal =
        UserManagementRule.refusal(
            new UserManagementRule.Request(operation)
                .actor(
                    actor.getId(),
                    AuthUtils.isAdmin(actor),
                    roleNames(actor),
                    actorPerson != null && actorPerson.isOwner())
                .target(
                    target == null ? null : target.getId(),
                    target != null && isTechnicalAdmin(target),
                    targetPerson != null && targetPerson.isOwner(),
                    targetPerson != null
                        && targetPerson.getRoles().contains(DirectoryRules.CLIENT_ADMIN))
                .grantsClientAdmin(grantsClientAdmin));
    if (refusal != null) {
      throw new IllegalArgumentException(refusal);
    }
    return actor;
  }

  /** Creates the local user for {@code person}, or brings an existing one in line. */
  private User mirror(User existing, DirectoryUser person) {
    Set<String> roleNames = person.getRoles();
    Long[] id = new Long[1];
    inScope(
        () -> {
          User user;
          if (existing == null) {
            String code = person.getEmail().toLowerCase(Locale.ROOT);
            user = new User(code, displayName(person));
            user.setEmail(code);
            user.setGroup(staffGroup());
            // Never used: login is Keycloak-only. Set because the platform requires a password.
            user.setPassword(authService.encrypt(UUID.randomUUID().toString()));
          } else {
            user = userRepo.find(existing.getId());
            // The identity provider's address, which a local edit may have changed (before G12
            // locked it) and which login would restore anyway.
            user.setEmail(person.getEmail().toLowerCase(Locale.ROOT));
          }
          user.setBlocked(false);
          user.clearRoles();
          for (String name : roleNames) {
            Role role = roleRepo.findByName(name);
            if (role == null) {
              LOG.warn(
                  "Role {} granted by the identity provider has no local counterpart; skipped",
                  name);
            } else {
              user.addRole(role);
            }
          }
          id[0] = userRepo.save(user).getId();
        });
    return userRepo.find(id[0]);
  }

  /** Runs a local write inside the management scope, flushing before the scope closes. */
  private static void inScope(Runnable work) {
    UserManagementScope.call(
        () -> {
          JPA.runInTransaction(
              () -> {
                work.run();
                // Flush inside the scope: the self-edit guard runs at flush time.
                JPA.flush();
              });
          return null;
        });
  }

  /** {@code person}, or a 404 when the local user has no access in the directory. */
  private static DirectoryUser required(DirectoryUser person, User target) {
    if (person == null) {
      throw new DirectoryException(
          404,
          "not_found",
          target.getCode() + " has no access to this tenant in the identity provider.");
    }
    return person;
  }

  /** The person with this address among {@code people}, or null. */
  private static DirectoryUser find(List<DirectoryUser> people, String email) {
    String code = email == null ? "" : email.toLowerCase(Locale.ROOT);
    for (DirectoryUser person : people) {
      if (person.getEmail() != null && code.equals(person.getEmail().toLowerCase(Locale.ROOT))) {
        return person;
      }
    }
    return null;
  }

  private User require(Long userId) {
    User user = userId == null ? null : userRepo.find(userId);
    if (user == null) {
      throw new IllegalArgumentException("Choose a user.");
    }
    return user;
  }

  private User findLocal(String code) {
    return userRepo.findByCode(code);
  }

  private Group staffGroup() {
    Group group = groupRepo.findByCode(staffGroupCode());
    if (group == null) {
      throw new IllegalStateException("Group " + staffGroupCode() + " is missing.");
    }
    return group;
  }

  private static String staffGroupCode() {
    return AppSettings.get().get("auth.provider.oidc.staff.group", "staff");
  }

  private static boolean isTechnicalAdmin(User user) {
    return UserSelfEditPolicy.isAdmin(
        user.getCode(), user.getGroup() == null ? null : user.getGroup().getCode());
  }

  private static String displayName(DirectoryUser person) {
    String first = person.getFirstName() == null ? "" : person.getFirstName().trim();
    String last = person.getLastName() == null ? "" : person.getLastName().trim();
    String name = (first + " " + last).trim();
    return name.isEmpty() ? person.getEmail().toLowerCase(Locale.ROOT) : name;
  }

  private static String display(Set<String> roles) {
    return roles.isEmpty() ? "none" : String.join(", ", new TreeSet<>(roles));
  }

  /**
   * A user with access must keep at least one role: with none they could still sign in, to an empty
   * application. A revoked (blocked) user has none by design; their page stays savable.
   */
  private static void requireARoleWhileWithAccess(User target, Set<String> requested) {
    if (requested.isEmpty() && !Boolean.TRUE.equals(target.getBlocked())) {
      throw new IllegalArgumentException("Choose at least one role.");
    }
  }

  /** The user's own roles (not their group's), by name. */
  private static Set<String> localRoleNames(User user) {
    Set<String> names = new TreeSet<>();
    if (user.getRoles() != null) {
      user.getRoles().stream().map(Role::getName).forEach(names::add);
    }
    return names;
  }

  private static List<String> roleNames(User user) {
    List<String> names = new ArrayList<>();
    if (user.getRoles() != null) {
      user.getRoles().stream().map(Role::getName).forEach(names::add);
    }
    if (user.getGroup() != null && user.getGroup().getRoles() != null) {
      user.getGroup().getRoles().stream().map(Role::getName).forEach(names::add);
    }
    return names;
  }
}
