package com.axelor.apps.nyxasecurity.web;

import com.axelor.apps.nyxasecurity.directory.DirectoryException;
import com.axelor.apps.nyxasecurity.directory.DirectoryRules;
import com.axelor.apps.nyxasecurity.service.TenantUserService;
import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Context;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application Config › Users: the list, the read-only user page with its actions, and the invite
 * and change-roles popups. All checks are in {@link TenantUserService} and the directory.
 */
public class TenantUsersController {

  /** User page: what Keycloak says about this person; the role picker offers grantable roles. */
  public void describe(ActionRequest request, ActionResponse response) {
    response.setAttr("roles", "domain", grantableDomain());
    Long userId = id(request.getContext());
    if (userId == null) {
      return;
    }
    // Nyxa staff: the page offers only their companies (UserSelfEditPolicy, client admin on a Nyxa
    // admin). The guard decides; this only keeps the page from offering what it would refuse.
    User user = Beans.get(UserRepository.class).find(userId);
    response.setValue(
        "nyxaAdmin",
        user != null
            && UserSelfEditPolicy.isNyxaAdmin(
                user.getCode(), user.getGroup() == null ? null : user.getGroup().getCode()));
    try {
      String[] description = Beans.get(TenantUserService.class).describe(userId);
      response.setValue("accessStatus", description[1]);
    } catch (IllegalArgumentException | DirectoryException e) {
      // Shown in place rather than as an error, so the page still opens when Keycloak is down.
      response.setValue("accessStatus", e.getMessage());
    }
  }

  /** Invite popup: limits the role picker to the roles a client administrator may grant. */
  public void roleDomain(ActionRequest request, ActionResponse response) {
    response.setAttr("inviteRoles", "domain", grantableDomain());
  }

  public void invite(ActionRequest request, ActionResponse response) {
    String email = (String) request.getContext().get("inviteEmail");
    List<String> roles = names(request.getContext().get("inviteRoles"));
    run(
        response,
        () -> {
          TenantUserService.Invitation invitation =
              Beans.get(TenantUserService.class).invite(email, roles);
          String done =
              (invitation.getResult().isCreated() ? "Invited " : "Access granted to ")
                  + invitation.getUser().getCode()
                  + ".";
          response.setInfo(
              invitation.getMailProblem() == null
                  ? done + " The invitation email is on its way."
                  : done + " " + invitation.getMailProblem());
          response.setCanClose(true);
        });
  }

  /**
   * User page, before the platform save: the active company must be one of the assigned ones, and
   * changed roles go to Keycloak first. If Keycloak accepts, the save that follows writes them with
   * the rest (a one-time approval, see RolesApproval); any refusal stops the save, so nothing of
   * the page is written.
   */
  public void beforeSave(ActionRequest request, ActionResponse response) {
    Context context = request.getContext();
    Long userId = id(context);
    if (userId == null) {
      return;
    }
    Long active = id(context.get("activeCompany"));
    if (active != null && !ids(context.get("companySet")).contains(active)) {
      response.setError("The active company must be one of the assigned companies.");
      return;
    }
    List<String> roles = names(context.get("roles"));
    run(
        response,
        () -> {
          Set<String> accepted =
              Beans.get(TenantUserService.class).approveRolesForSave(userId, roles);
          if (accepted != null) {
            response.setValue("roles", roleValues(accepted));
          }
        });
  }

  /** Restore popup: the user from the page that opened it; grantable roles only. */
  public void loadRestore(ActionRequest request, ActionResponse response) {
    response.setAttr("restoreRoles", "domain", grantableDomain());
    Object raw = request.getContext().get("_targetUserId");
    User user =
        raw == null ? null : Beans.get(UserRepository.class).find(Long.valueOf(raw.toString()));
    if (user != null) {
      Map<String, Object> target = new LinkedHashMap<>();
      target.put("id", user.getId());
      target.put("name", user.getName());
      target.put("code", user.getCode());
      response.setValue("targetUser", target);
    }
  }

  /**
   * Restore popup: gives a revoked user access back with the roles chosen in the popup. The same as
   * inviting their address again (TenantUserService#restoreAccess), email included.
   */
  public void restore(ActionRequest request, ActionResponse response) {
    Long userId = id(request.getContext().get("targetUser"));
    List<String> roles = names(request.getContext().get("restoreRoles"));
    run(
        response,
        () -> {
          TenantUserService.Invitation invitation =
              Beans.get(TenantUserService.class).restoreAccess(userId, roles);
          String done = "Access restored for " + invitation.getUser().getCode() + ".";
          response.setInfo(
              invitation.getMailProblem() == null
                  ? done + " The invitation email is on its way."
                  : done + " " + invitation.getMailProblem());
          response.setCanClose(true);
        });
  }

  public void resend(ActionRequest request, ActionResponse response) {
    Long userId = id(request.getContext());
    run(
        response,
        () -> response.setInfo(Beans.get(TenantUserService.class).resendInvitation(userId)));
  }

  public void revoke(ActionRequest request, ActionResponse response) {
    Long userId = id(request.getContext());
    run(
        response,
        () -> {
          response.setInfo(Beans.get(TenantUserService.class).revoke(userId));
          response.setReload(true);
        });
  }

  /**
   * The Users menu: brings the local users in line with the identity provider (at most every few
   * minutes, see TenantUserService#syncOnOpen), then opens the list. The list opens whatever
   * happens; when it couldn't be refreshed, a notice says so.
   */
  public void open(ActionRequest request, ActionResponse response) {
    String problem;
    try {
      problem = Beans.get(TenantUserService.class).syncOnOpen();
    } catch (IllegalArgumentException e) {
      // Not someone who manages users (the menu is client.admin's): just show the list.
      problem = null;
    }
    if (problem != null) {
      response.setNotify(problem);
    }
    response.setView(
        ActionView.define("Users")
            .model(User.class.getName())
            .add("grid", "nx-tenant-user-grid")
            .add("form", "nx-tenant-user-form")
            // Staff, and Nyxa technical admins so they can be given companies; never the local
            // admin account.
            .domain(
                "self.group.code = 'staff'"
                    + " OR (self.group.code = 'admins' AND self.code <> 'admin')")
            .map());
  }

  /**
   * The roles as the client sends them for a many-to-many field, so the save writes exactly them.
   */
  private static List<Map<String, Object>> roleValues(Set<String> names) {
    List<Map<String, Object>> values = new ArrayList<>();
    RoleRepository roles = Beans.get(RoleRepository.class);
    for (String name : names) {
      Role role = roles.findByName(name);
      if (role != null) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", role.getId());
        value.put("name", role.getName());
        values.add(value);
      }
    }
    return values;
  }

  private static Set<Long> ids(Object value) {
    Set<Long> ids = new HashSet<>();
    if (value instanceof Collection) {
      for (Object item : (Collection<?>) value) {
        Long id = id(item);
        if (id != null) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  /** Refusals and outages are shown to the administrator; anything else is a bug and propagates. */
  private static void run(ActionResponse response, Runnable work) {
    try {
      work.run();
    } catch (IllegalArgumentException | DirectoryException e) {
      response.setError(e.getMessage());
    }
  }

  private static String grantableDomain() {
    return "self.name IN ("
        + DirectoryRules.grantableRoles().stream()
            .map(name -> "'" + name + "'")
            .collect(Collectors.joining(", "))
        + ")";
  }

  private static Long id(Object value) {
    if (value instanceof Map) {
      Object id = ((Map<?, ?>) value).get("id");
      return id == null ? null : Long.valueOf(id.toString());
    }
    if (value instanceof com.axelor.db.Model) {
      return ((com.axelor.db.Model) value).getId();
    }
    return null;
  }

  /** Role names from a role picker, looked up by id: the name the client sends is not trusted. */
  private static List<String> names(Object value) {
    List<String> names = new ArrayList<>();
    if (value instanceof Collection) {
      RoleRepository roles = Beans.get(RoleRepository.class);
      for (Object item : (Collection<?>) value) {
        Long id = id(item);
        Role role = id == null ? null : roles.find(id);
        if (role != null) {
          names.add(role.getName());
        }
      }
    }
    return names;
  }
}
