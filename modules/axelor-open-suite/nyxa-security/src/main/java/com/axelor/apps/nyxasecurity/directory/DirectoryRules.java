package com.axelor.apps.nyxasecurity.directory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The rules for managing tenant users, as pure functions. The Keycloak endpoint enforces the same
 * rules on its side; checking them here first gives the administrator a clear message without a
 * round trip, and lets the offline {@link MockTenantUserDirectory} behave like the real thing.
 */
public final class DirectoryRules {

  /** Grants access to the tenant; managed by the directory, never requested. */
  public static final String ACCESS_ROLE = "restricted-access";

  /** Nyxa's break-glass role; only infra grants it. */
  public static final String NYXA_ADMIN = "nyxa.admin";

  /**
   * The tenant's initial client administrator, granted by onboarding in Keycloak. Never grantable
   * or removable through the directory; only Nyxa changes the owner's roles.
   */
  public static final String OWNER = "client.owner";

  /** The tenant's business administrator. At least one must always remain. */
  public static final String CLIENT_ADMIN = "client.admin";

  /** Linked to the staff group, never mapped from Keycloak (nx_role.csv). */
  static final String STAFF_BASELINE = "Staff Baseline";

  static final String CATALOGUE = "nyxa-security/nx_role.csv";

  /** Deliberately loose: the directory and the mail server are the real judges of an address. */
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  private static final int EMAIL_MAX = 254;

  private static volatile Set<String> grantable;

  private DirectoryRules() {}

  /**
   * The roles a tenant administrator may grant: every catalogue role (nx_role.csv) except the staff
   * baseline and {@code nyxa.admin}. Sorted, for stable display.
   */
  public static Set<String> grantableRoles() {
    Set<String> roles = grantable;
    if (roles == null) {
      roles = Collections.unmodifiableSet(loadGrantable());
      grantable = roles;
    }
    return roles;
  }

  /**
   * @return the address trimmed and lowercased
   * @throws DirectoryException 400 when it isn't a plausible address
   */
  public static String normalizeEmail(String email) {
    String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.length() > EMAIL_MAX || !EMAIL.matcher(value).matches()) {
      throw new DirectoryException(400, "invalid_email", "Not a valid email address: " + email);
    }
    return value;
  }

  /**
   * @return the roles, deduplicated
   * @throws DirectoryException 400 naming every role that may not be granted
   */
  public static Set<String> checkGrantable(Collection<String> roles) {
    Set<String> requested = new LinkedHashSet<>();
    if (roles != null) {
      for (String role : roles) {
        if (role != null && !role.isBlank()) {
          requested.add(role.trim());
        }
      }
    }
    Set<String> refused = new TreeSet<>();
    for (String role : requested) {
      if (!grantableRoles().contains(role)) {
        refused.add(role);
      }
    }
    if (!refused.isEmpty()) {
      throw new DirectoryException(
          400, "invalid_roles", "These roles can't be granted: " + String.join(", ", refused));
    }
    return requested;
  }

  /**
   * Whether a change leaves the tenant without a {@code client.admin}.
   *
   * @param otherAdmins users other than the target who hold {@code client.admin} and are not
   *     Nyxa-managed (those are temporary and don't count)
   * @param targetIsAdminAfter whether the target still holds {@code client.admin} after the change
   */
  public static boolean removesLastAdmin(int otherAdmins, boolean targetIsAdminAfter) {
    return otherAdmins == 0 && !targetIsAdminAfter;
  }

  private static Set<String> loadGrantable() {
    Set<String> roles = new TreeSet<>();
    InputStream in = DirectoryRules.class.getClassLoader().getResourceAsStream(CATALOGUE);
    if (in == null) {
      throw new IllegalStateException("Role catalogue not found on the classpath: " + CATALOGUE);
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line = reader.readLine(); // header
      while ((line = reader.readLine()) != null) {
        String name = firstColumn(line);
        if (!name.isEmpty() && !STAFF_BASELINE.equals(name) && !NYXA_ADMIN.equals(name)) {
          roles.add(name);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return roles;
  }

  /** nx_role.csv is "name";"description" with every value quoted; names contain no quotes. */
  private static String firstColumn(String line) {
    if (!line.startsWith("\"")) {
      return "";
    }
    int end = line.indexOf('"', 1);
    return end < 0 ? "" : line.substring(1, end).trim();
  }
}
