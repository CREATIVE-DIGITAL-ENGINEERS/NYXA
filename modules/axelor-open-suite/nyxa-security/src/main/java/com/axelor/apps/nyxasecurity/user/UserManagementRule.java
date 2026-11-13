package com.axelor.apps.nyxasecurity.user;

import com.axelor.apps.nyxasecurity.gate.Capabilities;
import java.util.Collection;
import java.util.Objects;

/**
 * Who may manage the tenant's users (invite, change roles, revoke), as pure decision logic.
 *
 * <p>Technical admins ({@code nyxa.admin}) and {@code client.admin} may manage users at all. Within
 * that, administration itself belongs to the tenant's <b>owner</b>, its initial client
 * administrator ({@code client.owner} in Keycloak, granted at onboarding):
 *
 * <ul>
 *   <li>Only the owner may change, re-invite or revoke a user who holds {@code client.admin}, and
 *       only the owner may grant {@code client.admin}. So the administrators the owner appoints
 *       manage everyone else, but not each other, not themselves and not the owner.
 *   <li>Only a technical admin may change the owner's roles or re-invite them, the owner included;
 *       nobody revokes the owner from here (that is infra's).
 *   <li>A technical admin is never a target, and nobody revokes their own access.
 * </ul>
 *
 * <p>The user directory separately refuses to remove the last {@code client.admin}, and guarantees
 * the owner role itself can't be granted, removed or revoked through its API. Who may change whom
 * is decided here, because the app calls Keycloak as its own service account and Keycloak can't
 * tell which person clicked. The owner and {@code client.admin} facts come from Keycloak.
 */
public final class UserManagementRule {

  /** What the actor is about to do. */
  public enum Operation {
    INVITE,
    CHANGE_ROLES,
    REVOKE,
    RESEND,
    SYNC
  }

  /** Everything the decision depends on. */
  public static final class Request {
    final Operation operation;
    boolean actorIsAdmin;
    Collection<String> actorRoles;
    boolean actorIsOwner;
    Long actorId;
    Long targetId;
    boolean targetIsAdmin;
    boolean targetIsOwner;
    boolean targetIsClientAdmin;
    boolean grantsClientAdmin;

    public Request(Operation operation) {
      this.operation = operation;
    }

    /**
     * @param isAdmin whether the actor is a technical admin
     * @param roles the actor's role names, direct and through their group
     * @param isOwner whether the actor is the tenant's owner, per the directory
     */
    public Request actor(Long id, boolean isAdmin, Collection<String> roles, boolean isOwner) {
      this.actorId = id;
      this.actorIsAdmin = isAdmin;
      this.actorRoles = roles;
      this.actorIsOwner = isOwner;
      return this;
    }

    /**
     * @param id the target's local user id, or null when there is none yet (an invitation)
     * @param isAdmin whether the target is a technical admin
     * @param isOwner whether the target is the tenant's owner, per the directory
     * @param isClientAdmin whether the target holds {@code client.admin}, per the directory
     */
    public Request target(Long id, boolean isAdmin, boolean isOwner, boolean isClientAdmin) {
      this.targetId = id;
      this.targetIsAdmin = isAdmin;
      this.targetIsOwner = isOwner;
      this.targetIsClientAdmin = isClientAdmin;
      return this;
    }

    /** Whether the requested roles include {@code client.admin}. */
    public Request grantsClientAdmin(boolean grants) {
      this.grantsClientAdmin = grants;
      return this;
    }
  }

  private UserManagementRule() {}

  /**
   * @return the reason to refuse, or null when allowed
   */
  public static String refusal(Request r) {
    boolean clientAdmin =
        r.actorRoles != null && r.actorRoles.stream().anyMatch(Capabilities.CLIENT_ADMIN::contains);
    if (!r.actorIsAdmin && !clientAdmin) {
      return "Only a client administrator can manage users.";
    }
    if (r.targetIsAdmin) {
      return "Technical administrators are managed by Nyxa, not from here.";
    }
    if (r.targetIsOwner && r.operation == Operation.REVOKE) {
      return "The tenant's owner can't be revoked here. Ask Nyxa.";
    }
    if (r.operation == Operation.REVOKE
        && r.targetId != null
        && Objects.equals(r.actorId, r.targetId)) {
      return "You can't revoke your own access. Ask the tenant's owner.";
    }
    boolean changes =
        r.operation == Operation.CHANGE_ROLES
            || r.operation == Operation.INVITE
            || r.operation == Operation.REVOKE;
    if (!changes || r.actorIsAdmin) {
      return null;
    }
    if (r.targetIsOwner) {
      return "Only Nyxa can change the roles of the tenant's owner.";
    }
    if (!r.actorIsOwner && r.targetIsClientAdmin) {
      return "Only the tenant's owner can change or revoke a client administrator.";
    }
    if (!r.actorIsOwner && r.grantsClientAdmin) {
      return "Only the tenant's owner can make someone a client administrator.";
    }
    return null;
  }
}
