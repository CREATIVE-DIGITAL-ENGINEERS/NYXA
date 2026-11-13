package com.axelor.apps.nyxasecurity.gate;

import com.axelor.apps.base.db.Company;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.db.JpaSecurity;
import com.axelor.db.Model;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * Code-level gates for business actions.
 *
 * <p>Axelor checks only READ on the record before running an action-method, so a controller that
 * changes state (generate, approve, submit, cancel…) must check the rest itself. Each method throws
 * the same {@link UnauthorizedException} Axelor's own permission check throws, so the user sees the
 * usual "not authorized" message, in their language ({@link Refusals}). Administrators pass every
 * gate, as they bypass the permission engine anyway.
 *
 * <p>Call these on the record <em>as loaded from the database</em> (its id), never on values sent
 * by the client: a client can send any company or id it likes.
 */
public final class CapabilityGate {

  private CapabilityGate() {}

  /**
   * The user holds at least one of {@code anyOf} (own or group role), or is an admin.
   *
   * @param action what the user tried, as a translation key of the calling module ("generate a VAT
   *     return"); the refusal names it and the roles that would allow it
   */
  public static void requireRole(String action, Collection<String> anyOf) {
    User user = AuthUtils.getUser();
    if (user == null) {
      throw new UnauthorizedException(Refusals.tr(Refusals.NOT_LOGGED_IN));
    }
    if (AuthUtils.isAdmin(user) || hasAny(user, anyOf)) {
      return;
    }
    throw new UnauthorizedException(Refusals.notAllowed(action, anyOf));
  }

  /**
   * The user may write this record: the WRITE permission including its company condition, which
   * also restricts it to the active company. A null id checks CREATE instead.
   */
  public static void requireWrite(Class<? extends Model> model, Long id) {
    JpaSecurity security = Beans.get(JpaSecurity.class);
    if (id == null) {
      security.check(JpaSecurity.CAN_CREATE, model);
    } else {
      security.check(JpaSecurity.CAN_WRITE, model, id);
    }
  }

  /** The user may read this record (e.g. before serving a file the client named by id). */
  public static void requireRead(Class<? extends Model> model, Long id) {
    if (id != null) {
      Beans.get(JpaSecurity.class).check(JpaSecurity.CAN_READ, model, id);
    }
  }

  /**
   * The company a not-yet-saved record or a wizard targets is the user's active company. Needed
   * where there is no stored record whose permission condition could do it.
   */
  public static void requireActiveCompany(Company company) {
    User user = AuthUtils.getUser();
    if (user == null) {
      throw new UnauthorizedException(Refusals.tr(Refusals.NOT_LOGGED_IN));
    }
    if (AuthUtils.isAdmin(user)) {
      return;
    }
    Long active = user.getActiveCompany() == null ? null : user.getActiveCompany().getId();
    Long wanted = company == null ? null : company.getId();
    if (wanted == null || !Objects.equals(active, wanted)) {
      throw new UnauthorizedException(Refusals.tr(Refusals.ONLY_ACTIVE_COMPANY));
    }
  }

  /**
   * G6: a file id taken from the client must be one the user uploaded. Without this, a user can
   * point an action at any stored file's id and have it attached, hashed or processed.
   */
  public static void requireOwnFile(com.axelor.meta.db.MetaFile file) {
    if (file == null) {
      return;
    }
    User user = AuthUtils.getUser();
    if (user == null) {
      throw new UnauthorizedException(Refusals.tr(Refusals.NOT_LOGGED_IN));
    }
    if (AuthUtils.isAdmin(user)) {
      return;
    }
    // Reload by id: a file taken from the form context is a detached stub without its auditing
    // fields, and its id is the only part of it that can be trusted anyway.
    if (file.getId() != null) {
      file = Beans.get(com.axelor.meta.db.repo.MetaFileRepository.class).find(file.getId());
      if (file == null) {
        throw new UnauthorizedException(Refusals.tr(Refusals.ONLY_OWN_FILES));
      }
    }
    User owner = file.getCreatedBy();
    if (owner == null || !Objects.equals(owner.getId(), user.getId())) {
      throw new UnauthorizedException(Refusals.tr(Refusals.ONLY_OWN_FILES));
    }
  }

  /**
   * Segregation of duties: the user acting now must not be the one who prepared the record. Admins
   * are exempt, as everywhere else.
   *
   * @param action a translation key of the calling module, as for {@link #requireRole}
   */
  public static void requireDifferentUser(User preparer, String action) {
    User user = AuthUtils.getUser();
    if (user == null) {
      throw new UnauthorizedException(Refusals.tr(Refusals.NOT_LOGGED_IN));
    }
    if (AuthUtils.isAdmin(user) || preparer == null) {
      return;
    }
    if (Objects.equals(preparer.getId(), user.getId())) {
      throw new UnauthorizedException(Refusals.tr(Refusals.NOT_BY_PREPARER, I18n.get(action)));
    }
  }

  public static Set<String> roles(String... names) {
    return new HashSet<>(Arrays.asList(names));
  }

  private static boolean hasAny(User user, Collection<String> anyOf) {
    Set<String> names = new HashSet<>();
    if (user.getRoles() != null) {
      user.getRoles().stream().map(Role::getName).forEach(names::add);
    }
    if (user.getGroup() != null && user.getGroup().getRoles() != null) {
      user.getGroup().getRoles().stream().map(Role::getName).forEach(names::add);
    }
    return anyOf.stream().anyMatch(names::contains);
  }
}
