package com.axelor.apps.nyxasecurity.user;

import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.Refusals;
import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy.Actor;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.db.Model;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.persistence.PersistenceException;
import org.hibernate.Hibernate;
import org.hibernate.StatelessSession;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.CollectionEntry;
import org.hibernate.event.spi.AbstractCollectionEvent;
import org.hibernate.event.spi.PreCollectionRecreateEvent;
import org.hibernate.event.spi.PreCollectionRecreateEventListener;
import org.hibernate.event.spi.PreCollectionRemoveEvent;
import org.hibernate.event.spi.PreCollectionRemoveEventListener;
import org.hibernate.event.spi.PreCollectionUpdateEvent;
import org.hibernate.event.spi.PreCollectionUpdateEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;

/**
 * Enforces {@link UserSelfEditPolicy} on every write to a {@code User} row, whatever the path:
 * {@code /ws/rest} saves, a {@code User} nested in another record's save, action-methods, services.
 * A check in the REST layer alone would miss all but the first.
 *
 * <p>Field changes arrive as {@code PreUpdate}. Collection changes ({@code roles}, {@code
 * companySet}, ...) arrive separately as collection events. Axelor assigns collections by {@code
 * clear()} + {@code addAll()} on the existing instance, so an unchanged set still fires an update
 * event; the stored snapshot is compared with the current contents to find real changes.
 *
 * <p>Throws {@link PersistenceException} ({@link Refusals.Refusal}, translated when it is read), as
 * {@code AuditInterceptor} does for its own {@code User} rules. That fails the whole flush, so
 * nothing from the request is written.
 */
public class UserSelfEditListener
    implements PreUpdateEventListener,
        PreCollectionUpdateEventListener,
        PreCollectionRecreateEventListener,
        PreCollectionRemoveEventListener {

  private static final long serialVersionUID = 1L;

  private static final String ACTIVE_COMPANY = "activeCompany";

  private static final String STAFF_GROUP = "staff";

  private static final String ROLES = "roles";

  private static final String COMPANY_SET = "companySet";

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    if (!(event.getEntity() instanceof User) || event.getOldState() == null) {
      return false;
    }
    String actorCode = actorCode();
    if (actorCode == null) {
      return false;
    }

    EntityPersister persister = event.getPersister();
    String[] names = persister.getPropertyNames();
    Type[] types = persister.getPropertyTypes();
    Object[] oldState = event.getOldState();
    Object[] state = event.getState();

    Actor actor =
        UserSelfEditPolicy.classify(
            actorCode,
            (String) value(names, oldState, "code"),
            groupCode(value(names, oldState, "group")),
            isActorAdmin(actorCode, (String) value(names, oldState, "code")));

    List<String> changed = new ArrayList<>();
    int[] dirty = persister.findDirty(state, oldState, event.getEntity(), event.getSession());
    if (dirty != null) {
      for (int i : dirty) {
        // Collections are judged by the collection events below, on their real contents.
        if (!types[i].isCollectionType()
            && !UserSelfEditPolicy.isAllowedClear(actor, names[i], state[i])) {
          changed.add(names[i]);
        }
      }
    }

    String owned =
        UserSelfEditPolicy.firstIdentityProviderOwned(actor, changed, UserManagementScope.isOpen());
    if (owned != null) {
      throw new Refusals.Refusal(
          Refusals.IDENTITY_PROVIDER_FIELD, owned, ((User) event.getEntity()).getCode());
    }

    String targetCode = (String) value(names, oldState, "code");
    String targetGroup = groupCode(value(names, oldState, "group"));
    boolean clientAdminEdit = isClientAdminEdit(actor, actorCode, targetGroup);
    boolean onNyxaAdmin = isClientAdminOnNyxaAdmin(actor, actorCode, targetCode, targetGroup);
    reject(actor, changed, (User) event.getEntity(), clientAdminEdit, onNyxaAdmin);

    // G11: the active company is one of the user's assigned companies, for everyone who may change
    // it (the user on their own record, a client administrator on any staff user's or Nyxa
    // admin's).
    // Checked against the companies as they will be after this save: the user page may change both
    // at once.
    if ((actor == Actor.SELF || clientAdminEdit || onNyxaAdmin)
        && changed.contains(ACTIVE_COMPANY)) {
      Object company = value(names, state, ACTIVE_COMPANY);
      if (company != null
          && !companySetIdsAfter(event, (User) event.getEntity()).contains(id(company))) {
        throw new Refusals.Refusal(
            Refusals.ONLY_ASSIGNED_COMPANY, ((User) event.getEntity()).getCode());
      }
    }
    return false;
  }

  @Override
  public void onPreUpdateCollection(PreCollectionUpdateEvent event) {
    checkCollection(event, true);
  }

  @Override
  public void onPreRecreateCollection(PreCollectionRecreateEvent event) {
    checkCollection(event, false);
  }

  @Override
  public void onPreRemoveCollection(PreCollectionRemoveEvent event) {
    checkCollection(event, false);
  }

  /**
   * @param comparable true when the collection kept its instance, so its stored snapshot holds the
   *     previous contents. A recreated or removed collection can't be compared and counts as
   *     changed.
   */
  private void checkCollection(AbstractCollectionEvent event, boolean comparable) {
    Object owner = event.getAffectedOwnerOrNull();
    if (!(owner instanceof User) || ((User) owner).getId() == null) {
      return;
    }
    String actorCode = actorCode();
    if (actorCode == null) {
      return;
    }
    PersistentCollection collection = event.getCollection();
    if (comparable && !contentChanged(collection)) {
      return;
    }
    String role = roleOf(event, collection);
    String property = role == null ? "?" : role.substring(role.lastIndexOf('.') + 1);

    User user = (User) owner;
    Actor actor =
        UserSelfEditPolicy.classify(
            actorCode,
            user.getCode(),
            groupCode(user.getGroup()),
            isActorAdmin(actorCode, user.getCode()));
    // G11 the other way round: a change of the assigned companies may not leave the active company
    // outside them. The company-assignment screen moves the active company before it flushes.
    if (COMPANY_SET.equals(property)
        && (actor == Actor.SELF || actor == Actor.OTHER)
        && user.getActiveCompany() != null
        && collection instanceof Collection
        && !ids((Collection<?>) collection).contains(user.getActiveCompany().getId())) {
      throw new Refusals.Refusal(Refusals.ACTIVE_COMPANY_STAYS_ASSIGNED, user.getCode());
    }
    // Roles saved from the user page: allowed only as exactly what Keycloak just accepted.
    if (ROLES.equals(property)
        && (actor == Actor.SELF || actor == Actor.OTHER)
        && RolesApproval.consume(actorCode, user.getId(), roleNames(collection))) {
      return;
    }
    String targetGroup = groupCode(user.getGroup());
    reject(
        actor,
        List.of(property),
        user,
        isClientAdminEdit(actor, actorCode, targetGroup),
        isClientAdminOnNyxaAdmin(actor, actorCode, user.getCode(), targetGroup));
  }

  private static void reject(
      Actor actor,
      Collection<String> changed,
      User target,
      boolean clientAdminEdit,
      boolean clientAdminOnNyxaAdmin) {
    String forbidden =
        UserSelfEditPolicy.firstForbidden(
            actor,
            changed,
            CompanyAssignmentScope.isOpen(),
            UserManagementScope.isOpen(),
            clientAdminEdit,
            clientAdminOnNyxaAdmin);
    if (forbidden != null) {
      throw actor == Actor.SELF
          ? new Refusals.Refusal(Refusals.OWN_PROFILE_FIELD, forbidden)
          : new Refusals.Refusal(Refusals.OTHER_USER_FIELD, target.getCode(), forbidden);
    }
  }

  /**
   * The collection's role ({@code com.axelor.auth.db.User.roles}). A collection being recreated for
   * a row that is being inserted doesn't know its role yet, but the session's entry for it does.
   * Without this, every insert of a {@code User} with a collection by a non-admin was refused as a
   * change to {@code '?'}, whatever scope was open.
   */
  private static String roleOf(AbstractCollectionEvent event, PersistentCollection collection) {
    if (collection.getRole() != null) {
      return collection.getRole();
    }
    CollectionEntry entry =
        event.getSession().getPersistenceContext().getCollectionEntry(collection);
    if (entry == null) {
      return null;
    }
    CollectionPersister persister =
        entry.getCurrentPersister() != null
            ? entry.getCurrentPersister()
            : entry.getLoadedPersister();
    return persister == null ? null : persister.getRole();
  }

  private static boolean contentChanged(PersistentCollection collection) {
    if (!(collection instanceof Collection)) {
      return true; // not a set or bag (never the case on User); don't guess
    }
    Object snapshot = collection.getStoredSnapshot();
    Collection<?> before =
        snapshot instanceof Map
            ? ((Map<?, ?>) snapshot).keySet()
            : snapshot instanceof Collection ? (Collection<?>) snapshot : null;
    if (before == null) {
      return true;
    }
    return !ids(before).equals(ids((Collection<?>) collection));
  }

  private static Set<Object> ids(Collection<?> items) {
    Set<Object> ids = new HashSet<>();
    for (Object item : items) {
      Object id = id(item);
      // An unsaved element has no id yet, so it can't be in the previous contents.
      ids.add(id == null ? new Object() : id);
    }
    return ids;
  }

  private static Object id(Object item) {
    return item instanceof Model ? ((Model) item).getId() : item;
  }

  /**
   * The user's assigned company ids as they will be after this flush: the in-memory set when this
   * save loaded (and so possibly changed) it, else the stored one.
   */
  private static Set<Object> companySetIdsAfter(PreUpdateEvent event, User user) {
    if (user.getCompanySet() != null && Hibernate.isInitialized(user.getCompanySet())) {
      return ids(user.getCompanySet());
    }
    return companySetIds(event, user);
  }

  /**
   * The target's assigned company ids, read through a separate stateless session. Initializing the
   * lazy {@code companySet} of an entity that is being flushed is not allowed.
   */
  private static Set<Object> companySetIds(PreUpdateEvent event, User user) {
    try (StatelessSession session = event.getSession().getFactory().openStatelessSession()) {
      List<?> rows =
          session
              .createQuery("SELECT c.id FROM User u JOIN u.companySet c WHERE u.id = :id")
              .setParameter("id", user.getId())
              .list();
      return new HashSet<>(rows);
    }
  }

  /** Code of the logged-in user, or null when there is none (login, startup, background jobs). */
  public static String actorCode() {
    try {
      Object principal = AuthUtils.getSubject().getPrincipal();
      return principal == null ? null : principal.toString();
    } catch (Exception e) {
      // No SecurityManager or session on this thread: not a user request.
      return null;
    }
  }

  /**
   * Whether this is a client administrator changing a staff user's record, its own included: the
   * user page (Application Config › Users). Technical admins are classified ADMIN before this.
   */
  private static boolean isClientAdminEdit(Actor actor, String actorCode, String targetGroupCode) {
    if ((actor != Actor.SELF && actor != Actor.OTHER) || !STAFF_GROUP.equals(targetGroupCode)) {
      return false;
    }
    return isClientAdmin(actorCode);
  }

  /**
   * Whether this is a client administrator changing a Nyxa technical admin's record from the user
   * page, to assign it companies. The local {@code admin} account is never one.
   */
  private static boolean isClientAdminOnNyxaAdmin(
      Actor actor, String actorCode, String targetCode, String targetGroupCode) {
    return actor == Actor.OTHER
        && UserSelfEditPolicy.isNyxaAdmin(targetCode, targetGroupCode)
        && isClientAdmin(actorCode);
  }

  private static boolean isClientAdmin(String actorCode) {
    User user = AuthUtils.getUser(actorCode);
    if (user == null) {
      return false;
    }
    Set<String> names = new HashSet<>();
    if (user.getRoles() != null) {
      user.getRoles().forEach(r -> names.add(r.getName()));
    }
    if (user.getGroup() != null && user.getGroup().getRoles() != null) {
      user.getGroup().getRoles().forEach(r -> names.add(r.getName()));
    }
    return names.stream().anyMatch(Capabilities.CLIENT_ADMIN::contains);
  }

  private static Set<String> roleNames(PersistentCollection collection) {
    Set<String> names = new HashSet<>();
    if (collection instanceof Collection) {
      for (Object item : (Collection<?>) collection) {
        if (item instanceof Role) {
          names.add(((Role) item).getName());
        }
      }
    }
    return names;
  }

  /** Only needed when the actor edits someone else's row, which is rare; only then query. */
  private static boolean isActorAdmin(String actorCode, String targetCode) {
    if (Objects.equals(actorCode, targetCode)) {
      return false; // decided from the target's own values in UserSelfEditPolicy.classify
    }
    User actor = AuthUtils.getUser(actorCode);
    return actor != null && AuthUtils.isAdmin(actor);
  }

  private static String groupCode(Object group) {
    return group instanceof Group ? ((Group) group).getCode() : null;
  }

  private static Object value(String[] names, Object[] state, String name) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name)) {
        return state[i];
      }
    }
    return null;
  }
}
