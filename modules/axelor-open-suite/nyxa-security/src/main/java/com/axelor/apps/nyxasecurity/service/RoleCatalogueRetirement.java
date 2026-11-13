package com.axelor.apps.nyxasecurity.service;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Permission;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.GroupRepository;
import com.axelor.auth.db.repo.PermissionRepository;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.common.csv.CSVFile;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.meta.db.MetaMenu;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.persistence.FlushModeType;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Revokes grants listed in {@code nyxa-security/nx_retired.csv}, the counterpart of the additive
 * import in {@link RoleCatalogueImporter}.
 *
 * <p>The import can only add: removing a row from a catalogue CSV leaves the grant in place in
 * every existing database. To take access away, add an entry here. Runs after the import on every
 * boot; an entry that has already been applied (or whose target never existed) does nothing, so
 * entries can stay in the file indefinitely.
 *
 * <p>Entry kinds, columns {@code kind;target;from}:
 *
 * <ul>
 *   <li>{@code permission-role}: unlink permission {@code target} from role {@code from}
 *   <li>{@code role-group}: unlink role {@code target} from group {@code from} (group code)
 *   <li>{@code role-menu}: remove role {@code target} from every menu named {@code from}. Every row
 *       with that name, since a menu redefinition is a separate row with its own roles.
 *   <li>{@code permission}: delete permission {@code target}, after unlinking it from every role,
 *       group and user that holds it
 *   <li>{@code role}: retire role {@code target}: unlink it from every user, group, menu and the
 *       period-closing role lists of every company, and delete its own {@code nx.*} permissions
 *       that nothing else holds. The emptied role row stays, so an unknown reference to it can't
 *       fail the whole step.
 * </ul>
 *
 * <p>An entry must not be re-granted by the catalogue CSVs, or the grant would be added and removed
 * again at every boot. {@code RoleCatalogueFilesTest} checks this at build time.
 */
@Singleton
public class RoleCatalogueRetirement {

  static final String RETIRED_FILE = "nx_retired.csv";

  enum Kind {
    PERMISSION_ROLE("permission-role"),
    ROLE_GROUP("role-group"),
    ROLE_MENU("role-menu"),
    PERMISSION("permission"),
    ROLE("role");

    final String code;

    Kind(String code) {
      this.code = code;
    }

    static Kind of(String code) {
      String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
      for (Kind kind : values()) {
        if (kind.code.equals(normalized)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown retirement kind '" + code + "'");
    }
  }

  static final class Entry {
    final Kind kind;
    final String target;
    final String from;

    Entry(Kind kind, String target, String from) {
      this.kind = kind;
      this.target = target;
      this.from = from;
    }

    @Override
    public String toString() {
      return kind.code + " " + target + (from.isEmpty() ? "" : " from " + from);
    }
  }

  private final PermissionRepository permissionRepo;
  private final RoleRepository roleRepo;
  private final GroupRepository groupRepo;

  @Inject
  public RoleCatalogueRetirement(
      PermissionRepository permissionRepo, RoleRepository roleRepo, GroupRepository groupRepo) {
    this.permissionRepo = permissionRepo;
    this.roleRepo = roleRepo;
    this.groupRepo = groupRepo;
  }

  /**
   * Applies every entry in one transaction.
   *
   * @return a description of each change actually made; empty when everything was already applied
   */
  public List<String> apply() throws IOException {
    List<Entry> entries = read();
    List<String> changes = new ArrayList<>();
    JPA.runInTransaction(
        () -> {
          // Written once, at commit: with automatic flushing every lookup below wrote out all the
          // unlinks so far, and retiring a role with hundreds of permissions took many minutes.
          FlushModeType previous = JPA.em().getFlushMode();
          JPA.em().setFlushMode(FlushModeType.COMMIT);
          try {
            for (Entry entry : entries) {
              if (applyOne(entry)) {
                changes.add(entry.toString());
              }
            }
          } finally {
            JPA.em().setFlushMode(previous);
          }
        });
    return changes;
  }

  static List<Entry> read() throws IOException {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    try (InputStream in =
        loader.getResourceAsStream(RoleCatalogueImporter.RESOURCE_DIR + RETIRED_FILE)) {
      if (in == null) {
        return Collections.emptyList();
      }
      return parse(in);
    }
  }

  static List<Entry> parse(InputStream in) throws IOException {
    List<Entry> entries = new ArrayList<>();
    try (CSVParser parser =
        CSVFile.DEFAULT
            .withDelimiter(';')
            .withFirstRecordAsHeader()
            .parse(in, StandardCharsets.UTF_8)) {
      for (CSVRecord record : parser) {
        String kind = record.get("kind");
        if (kind == null || kind.isBlank()) {
          continue;
        }
        String target = record.get("target").trim();
        String from = record.isSet("from") ? record.get("from").trim() : "";
        Kind parsed = Kind.of(kind);
        if (target.isEmpty()
            || (parsed != Kind.PERMISSION && parsed != Kind.ROLE && from.isEmpty())) {
          throw new IllegalArgumentException(
              RETIRED_FILE + " line " + record.getRecordNumber() + ": missing target or from");
        }
        entries.add(new Entry(parsed, target, from));
      }
    }
    return entries;
  }

  private boolean applyOne(Entry entry) {
    switch (entry.kind) {
      case PERMISSION_ROLE:
        {
          Permission permission = permissionRepo.findByName(entry.target);
          Role role = roleRepo.findByName(entry.from);
          if (permission == null || role == null || !contains(role.getPermissions(), permission)) {
            return false;
          }
          role.removePermission(permission);
          return true;
        }
      case ROLE_GROUP:
        {
          Role role = roleRepo.findByName(entry.target);
          Group group = groupRepo.findByCode(entry.from);
          if (role == null || group == null || !contains(group.getRoles(), role)) {
            return false;
          }
          group.removeRole(role);
          return true;
        }
      case ROLE_MENU:
        {
          Role role = roleRepo.findByName(entry.target);
          if (role == null) {
            return false;
          }
          boolean changed = false;
          for (MetaMenu menu :
              Query.of(MetaMenu.class).filter("self.name = ?", entry.from).fetch()) {
            if (contains(menu.getRoles(), role)) {
              menu.removeRole(role);
              changed = true;
            }
          }
          return changed;
        }
      case PERMISSION:
        {
          Permission permission = permissionRepo.findByName(entry.target);
          if (permission == null) {
            return false;
          }
          for (Role role : holders(Role.class, permission)) {
            role.removePermission(permission);
          }
          for (Group group : holders(Group.class, permission)) {
            group.removePermission(permission);
          }
          for (User user : holders(User.class, permission)) {
            user.removePermission(permission);
          }
          permissionRepo.remove(permission);
          return true;
        }
      case ROLE:
        return retireRole(entry.target);
      default:
        return false;
    }
  }

  private boolean retireRole(String name) {
    Role role = roleRepo.findByName(name);
    if (role == null) {
      return false;
    }
    boolean changed = false;
    for (User user : Query.of(User.class).filter("? MEMBER OF self.roles", role).fetch()) {
      user.removeRole(role);
      changed = true;
    }
    for (Group group : Query.of(Group.class).filter("? MEMBER OF self.roles", role).fetch()) {
      group.removeRole(role);
      changed = true;
    }
    for (MetaMenu menu : Query.of(MetaMenu.class).filter("? MEMBER OF self.roles", role).fetch()) {
      menu.removeRole(role);
      changed = true;
    }
    for (AccountConfig config : Query.of(AccountConfig.class).fetch()) {
      if (contains(config.getClosureAuthorizedRoleList(), role)) {
        config.removeClosureAuthorizedRoleListItem(role);
        changed = true;
      }
      if (contains(config.getTemporaryClosureAuthorizedRoleList(), role)) {
        config.removeTemporaryClosureAuthorizedRoleListItem(role);
        changed = true;
      }
      if (contains(config.getMoveOnTempClosureAuthorizedRoleList(), role)) {
        config.removeMoveOnTempClosureAuthorizedRoleListItem(role);
        changed = true;
      }
    }
    if (role.getPermissions() != null) {
      for (Permission permission : new ArrayList<>(role.getPermissions())) {
        role.removePermission(permission);
        changed = true;
        boolean ours = permission.getName() != null && permission.getName().startsWith("nx.");
        // Without automatic flushing the database still links this role, so it is excluded here.
        if (ours
            && Query.of(Role.class)
                    .filter(
                        "? MEMBER OF self.permissions AND self.id != ?", permission, role.getId())
                    .count()
                == 0
            && holders(Group.class, permission).isEmpty()
            && holders(User.class, permission).isEmpty()) {
          permissionRepo.remove(permission);
        }
      }
    }
    return changed;
  }

  private static <T extends com.axelor.db.Model> List<T> holders(
      Class<T> type, Permission permission) {
    return Query.of(type).filter("? MEMBER OF self.permissions", permission).fetch();
  }

  private static boolean contains(java.util.Collection<?> items, Object item) {
    return items != null && items.contains(item);
  }
}
