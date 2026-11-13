package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.auth.db.Permission;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.persistence.Column;
import javax.validation.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * Catches catalogue mistakes at build time that would otherwise only show up as a log line at boot:
 * a CSV the config reads but the importer doesn't extract (or the reverse), a permission attached
 * to a role name that doesn't exist (ImportPermission silently skips it), or a permission name that
 * could collide with the stock "perm.*" rows linked to other roles.
 */
class RoleCatalogueFilesTest {

  @Test
  void extractedFilesAreExactlyTheFilesTheConfigReads() throws IOException {
    Set<String> referenced = new HashSet<>();
    Matcher m = Pattern.compile("<input\\s+file=\"([^\"]+)\"").matcher(read("security-config.xml"));
    while (m.find()) {
      referenced.add(m.group(1));
    }
    assertEquals(new HashSet<>(RoleCatalogueImporter.DATA_FILES), referenced);
  }

  /** Role.description is varchar(255); a longer one fails that role's import at every boot. */
  @Test
  void roleDescriptionsFitTheColumn() throws IOException {
    List<String> names = column("nx_role.csv", "name");
    List<String> descriptions = column("nx_role.csv", "description");
    for (int i = 0; i < names.size(); i++) {
      int length = descriptions.get(i).codePointCount(0, descriptions.get(i).length());
      assertTrue(length <= 255, names.get(i) + " description is " + length + " chars, max 255");
    }
  }

  @Test
  void everyReferencedRoleIsDefined() throws IOException {
    Set<String> roles = new HashSet<>(column("nx_role.csv", "name"));
    for (String role : column("nx_permission.csv", "roleName")) {
      assertTrue(roles.contains(role), "nx_permission.csv references undefined role " + role);
    }
    for (String role : column("nx_group.csv", "roleName")) {
      assertTrue(roles.contains(role), "nx_group.csv references undefined role " + role);
    }
    // Role names hard-coded outside nyxa-security (it can't see those modules' constants).
    for (String role :
        List.of("accounting.ai.read", "accounting.ai.write", "all.read", "all.write")) {
      assertTrue(roles.contains(role), "code refers to undefined role " + role);
    }
    for (String role : ClosureRoleSeeder.CLOSING_ROLES) {
      assertTrue(roles.contains(role), "ClosureRoleSeeder names undefined role " + role);
    }
    for (List<String> menuRoles : menuGrants().values()) {
      for (String role : menuRoles) {
        assertTrue(
            "Admin".equals(role) || roles.contains(role),
            "nx_menu.csv references undefined role " + role);
      }
    }
  }

  private static Map<String, List<String>> menuGrants() throws IOException {
    try (InputStream in = resource(RoleCatalogueMenus.MENU_FILE)) {
      return RoleCatalogueMenus.parse(in);
    }
  }

  @Test
  void permissionNamesAreUniqueAndPrefixed() throws IOException {
    List<String> names = column("nx_permission.csv", "name");
    assertEquals(names.size(), new HashSet<>(names).size(), "duplicate permission name");
    for (String name : names) {
      assertTrue(name.startsWith("nx."), "permission name must start with nx.: " + name);
    }
  }

  /**
   * {@code Filter.build} renumbers every {@code ?} character, so a numbered {@code ?1} becomes
   * {@code ?11} and the permission silently denies everything. Each {@code ?} also consumes one
   * parameter, so a value used twice must be listed twice.
   */
  @Test
  void conditionsUsePlainPlaceholdersMatchingTheirParams() throws IOException {
    for (String pair : pairs("nx_permission.csv", "condition", "conditionParams")) {
      String condition = pair.substring(0, pair.indexOf('|'));
      String params = pair.substring(pair.indexOf('|') + 1);
      assertTrue(
          !condition.matches(".*\\?\\d.*"), "numbered placeholder in condition: " + condition);
      long placeholders = condition.chars().filter(c -> c == '?').count();
      long paramCount = params.isEmpty() ? 0 : params.split(",").length;
      assertEquals(placeholders, paramCount, "placeholders vs params in: " + condition);
    }
  }

  /**
   * A retirement the catalogue still grants would be added by the import and removed again at every
   * boot. Fails the build instead.
   */
  @Test
  void retiredGrantsAreNotGrantedAgain() throws IOException {
    List<RoleCatalogueRetirement.Entry> entries;
    try (InputStream in = resource(RoleCatalogueRetirement.RETIRED_FILE)) {
      entries = RoleCatalogueRetirement.parse(in);
    }
    Set<String> permissionRoles = pairs("nx_permission.csv", "name", "roleName");
    Set<String> permissions = new HashSet<>(column("nx_permission.csv", "name"));
    Set<String> groupRoles = pairs("nx_group.csv", "roleName", "code");
    for (RoleCatalogueRetirement.Entry e : entries) {
      switch (e.kind) {
        case PERMISSION_ROLE:
          assertTrue(
              !permissionRoles.contains(e.target + "|" + e.from),
              "retired but still granted in nx_permission.csv: " + e);
          break;
        case PERMISSION:
          assertTrue(
              !permissions.contains(e.target), "retired but still in nx_permission.csv: " + e);
          break;
        case ROLE_GROUP:
          assertTrue(
              !groupRoles.contains(e.target + "|" + e.from),
              "retired but still granted in nx_group.csv: " + e);
          break;
        case ROLE_MENU:
          List<String> granted = menuGrants().get(e.from);
          assertTrue(
              granted == null || !granted.contains(e.target),
              "retired but still granted in nx_menu.csv: " + e);
          break;
        case ROLE:
          // A retired role still defined would be re-created and emptied again at every boot.
          assertTrue(
              !column("nx_role.csv", "name").contains(e.target),
              "retired but still defined in nx_role.csv: " + e);
          break;
        default:
          break;
      }
    }
  }

  /**
   * Permission.condition is {@code @Size(max = 1024)} with no {@code @Column(length)}, and
   * Hibernate sizes a column from {@code @Size} only while bean validation is on. With {@code
   * javax.persistence.validation.mode=none}, as in the HSQLDB test units, the column is the JPA
   * default VARCHAR(255) and a longer condition fails its row at import, so the roles lose the
   * grant. The limit is read from the model, so a column length added upstream moves it;
   * tools/gen-catalogue.js refuses the same.
   */
  @Test
  void conditionsFitTheColumn() throws IOException, ReflectiveOperationException {
    int conditionMax = columnLength("condition");
    int paramsMax = columnLength("conditionParams");
    List<String> tooLong = new ArrayList<>();
    for (String pair : pairs("nx_permission.csv", "name", "condition")) {
      String condition = pair.substring(pair.indexOf('|') + 1);
      if (condition.length() > conditionMax) {
        tooLong.add(pair.substring(0, pair.indexOf('|')) + " (" + condition.length() + ")");
      }
    }
    for (String pair : pairs("nx_permission.csv", "name", "conditionParams")) {
      String params = pair.substring(pair.indexOf('|') + 1);
      if (params.length() > paramsMax) {
        tooLong.add(pair.substring(0, pair.indexOf('|')) + " params (" + params.length() + ")");
      }
    }
    assertTrue(
        tooLong.isEmpty(),
        "conditions longer than the column (" + conditionMax + "/" + paramsMax + "): " + tooLong);

    Path generator = Paths.get("").toAbsolutePath().resolve("tools/gen-catalogue.js");
    if (Files.exists(generator)) {
      String js = Files.readString(generator);
      assertEquals(conditionMax, generatorLimit(js, "MAX_CONDITION"), "gen-catalogue.js limit");
      assertEquals(paramsMax, generatorLimit(js, "MAX_CONDITION_PARAMS"), "gen-catalogue.js limit");
    }
  }

  /** The column length without bean validation: {@code @Column(length)}, else the JPA default. */
  static int columnLength(String field) throws ReflectiveOperationException {
    Field f = Permission.class.getDeclaredField(field);
    Column column = f.getAnnotation(Column.class);
    int length =
        column != null
            ? column.length()
            : (Integer) Column.class.getMethod("length").getDefaultValue();
    Size size = f.getAnnotation(Size.class);
    return size == null ? length : Math.min(length, size.max());
  }

  private static int generatorLimit(String js, String name) {
    Matcher m = Pattern.compile("\\b" + name + " = (\\d+)").matcher(js);
    assertTrue(m.find(), "gen-catalogue.js has no " + name);
    return Integer.parseInt(m.group(1));
  }

  /** "a|b" for every row, from two columns, keeping rows aligned (unlike {@link #column}). */
  private static Set<String> pairs(String file, String first, String second) throws IOException {
    Set<String> pairs = new HashSet<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(resource(file), StandardCharsets.UTF_8))) {
      List<String> headers = List.of(split(reader.readLine()));
      int a = headers.indexOf(first);
      int b = headers.indexOf(second);
      assertTrue(a >= 0 && b >= 0, file + " lacks " + first + " or " + second);
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (!line.isBlank()) {
          String[] cells = split(line);
          pairs.add(cells[a] + "|" + cells[b]);
        }
      }
    }
    return pairs;
  }

  private static String read(String file) throws IOException {
    try (InputStream in = resource(file)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static InputStream resource(String file) {
    InputStream in =
        RoleCatalogueFilesTest.class
            .getClassLoader()
            .getResourceAsStream(RoleCatalogueImporter.RESOURCE_DIR + file);
    assertNotNull(in, "missing resource " + file);
    return in;
  }

  /** Values of one column of a ";"-separated, double-quoted CSV (no embedded separators). */
  private static List<String> column(String file, String header) throws IOException {
    List<String> values = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(resource(file), StandardCharsets.UTF_8))) {
      String[] headers = split(reader.readLine());
      int index = -1;
      for (int i = 0; i < headers.length; i++) {
        if (headers[i].equals(header)) {
          index = i;
        }
      }
      assertTrue(index >= 0, file + " has no column " + header);
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (!line.isBlank()) {
          String[] cells = split(line);
          if (index < cells.length && !cells[index].isEmpty()) {
            values.add(cells[index]);
          }
        }
      }
    }
    return values;
  }

  private static String[] split(String line) {
    String[] cells = line.split(";", -1);
    for (int i = 0; i < cells.length; i++) {
      cells[i] = cells[i].trim().replaceAll("^\"|\"$", "");
    }
    return cells;
  }
}
