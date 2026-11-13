package com.axelor.apps.nyxasecurity.service;

import com.axelor.auth.db.Role;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.persistence.FlushModeType;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Grants menus to catalogue roles, from {@code nyxa-security/nx_menu.csv} ({@code menu;roles},
 * roles separated by {@code |}).
 *
 * <p><b>Why not a CSV input like the rest.</b> A menu redefined by a later module is a separate
 * {@code MetaMenu} row with the same name, and the stock CSV binder attaches roles to whichever row
 * its search happens to return first. The row actually displayed is the highest-priority one, so a
 * CSV-imported grant can land on a row nobody sees. This grants the role on <em>every</em> row with
 * the name, as {@link RoleCatalogueRetirement} does for {@code role-menu} revocations.
 *
 * <p>Only adds, like the rest of the import; revocations go through {@code nx_retired.csv}. Runs
 * before the retirements, so a retired grant is removed again in the same boot.
 */
@Singleton
public class RoleCatalogueMenus {

  static final String MENU_FILE = "nx_menu.csv";

  /** What one run did. */
  public static final class Result {
    final List<String> granted = new ArrayList<>();
    final List<String> problems = new ArrayList<>();

    public List<String> getGranted() {
      return granted;
    }

    /** Menus or roles named in the file that don't exist in this database. */
    public List<String> getProblems() {
      return problems;
    }
  }

  private final RoleRepository roleRepo;

  @Inject
  public RoleCatalogueMenus(RoleRepository roleRepo) {
    this.roleRepo = roleRepo;
  }

  /**
   * Loads every named menu and role in two queries and writes once, at commit. Looking each one up
   * separately with automatic flushing made every lookup write out all the grants added so far, so
   * the step slowed down with every grant (minutes for a few thousand).
   */
  public Result apply() throws IOException {
    Map<String, List<String>> grants = read();
    Result result = new Result();
    JPA.runInTransaction(
        () -> {
          FlushModeType previous = JPA.em().getFlushMode();
          JPA.em().setFlushMode(FlushModeType.COMMIT);
          try {
            Map<String, List<MetaMenu>> menus = new HashMap<>();
            if (!grants.isEmpty()) {
              for (MetaMenu menu :
                  Query.of(MetaMenu.class)
                      .filter("self.name IN :names")
                      .bind("names", new ArrayList<>(grants.keySet()))
                      .fetch()) {
                menus.computeIfAbsent(menu.getName(), k -> new ArrayList<>()).add(menu);
              }
            }
            Set<String> roleNames = new HashSet<>();
            grants.values().forEach(roleNames::addAll);
            Map<String, Role> roles = new HashMap<>();
            if (!roleNames.isEmpty()) {
              for (Role role :
                  Query.of(Role.class)
                      .filter("self.name IN :names")
                      .bind("names", new ArrayList<>(roleNames))
                      .fetch()) {
                roles.put(role.getName(), role);
              }
            }
            for (Map.Entry<String, List<String>> grant : grants.entrySet()) {
              applyOne(grant.getKey(), grant.getValue(), menus, roles, result);
            }
          } finally {
            JPA.em().setFlushMode(previous);
          }
        });
    return result;
  }

  private void applyOne(
      String menuName,
      List<String> roleNames,
      Map<String, List<MetaMenu>> menus,
      Map<String, Role> roles,
      Result result) {
    List<MetaMenu> rows = menus.getOrDefault(menuName, List.of());
    if (rows.isEmpty()) {
      result.problems.add("menu " + menuName + " does not exist");
      return;
    }
    for (String roleName : roleNames) {
      Role role = roles.get(roleName);
      if (role == null) {
        result.problems.add("role " + roleName + " (for menu " + menuName + ") does not exist");
        continue;
      }
      for (MetaMenu row : rows) {
        if (row.getRoles() == null || !row.getRoles().contains(role)) {
          row.addRole(role);
          result.granted.add(menuName + " -> " + roleName);
        }
      }
    }
  }

  static Map<String, List<String>> read() throws IOException {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    try (InputStream in =
        loader.getResourceAsStream(RoleCatalogueImporter.RESOURCE_DIR + MENU_FILE)) {
      return in == null ? Collections.emptyMap() : parse(in);
    }
  }

  static Map<String, List<String>> parse(InputStream in) throws IOException {
    Map<String, List<String>> grants = new LinkedHashMap<>();
    try (CSVParser parser =
        CSVFile.DEFAULT
            .withDelimiter(';')
            .withFirstRecordAsHeader()
            .parse(in, StandardCharsets.UTF_8)) {
      for (CSVRecord record : parser) {
        String menu = record.get("menu").trim();
        if (menu.isEmpty()) {
          continue;
        }
        List<String> roles =
            Arrays.stream(record.get("roles").split("\\|"))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .collect(Collectors.toList());
        if (roles.isEmpty()) {
          throw new IllegalArgumentException(
              MENU_FILE + " line " + record.getRecordNumber() + ": no roles for menu " + menu);
        }
        grants.computeIfAbsent(menu, k -> new ArrayList<>()).addAll(roles);
      }
    }
    return grants;
  }
}
