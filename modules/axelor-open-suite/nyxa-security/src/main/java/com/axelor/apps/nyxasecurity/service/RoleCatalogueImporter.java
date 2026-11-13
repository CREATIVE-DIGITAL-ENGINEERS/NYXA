package com.axelor.apps.nyxasecurity.service;

import com.axelor.apps.nyxasecurity.db.NxCatalogueState;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Permission;
import com.axelor.auth.db.Role;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.Query;
import com.axelor.meta.db.MetaMenu;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the role catalogue to the database at every boot.
 *
 * <h2>Why not data-init</h2>
 *
 * {@code data-init} runs once, when a module is first installed. The catalogue changes as roles are
 * designed and tested, and every existing tenant database has to follow. So the same CSV format the
 * stock {@code apps/roles} use is imported here on every startup instead. The files deliberately
 * live outside {@code data-init/}, so the platform's DataLoader doesn't import them a second time.
 *
 * <h2>Idempotent, and additive only</h2>
 *
 * Every input searches by its natural key ({@code Role.name}, {@code Permission.name}, {@code
 * Group.code}), so a re-run updates rows instead of duplicating them. Collections (a role's
 * permissions, a group's roles, a menu's roles) are only ever <em>added</em> to, never replaced
 * ({@code CSVBinder} uses {@code addAll}, and {@code ImportPermission.importPermissionToRole} uses
 * {@code addPermission}). Removing a row from a CSV therefore does <b>not</b> revoke the grant in
 * existing databases. To revoke, add an entry to {@code nx_retired.csv}, which {@link
 * RoleCatalogueRetirement} applies right after the import.
 */
@Singleton
public class RoleCatalogueImporter {

  private static final Logger LOG = LoggerFactory.getLogger(RoleCatalogueImporter.class);

  static final String RESOURCE_DIR = "nyxa-security/";
  static final String CONFIG_FILE = "security-config.xml";

  /** NxCatalogueState key holding the hash of the last cleanly imported catalogue. */
  static final String STATE_CODE = "role-catalogue";

  /** Every file the config references. CSVImporter needs them on disk, not on the classpath. */
  static final List<String> DATA_FILES =
      Collections.unmodifiableList(
          Arrays.asList("nx_role.csv", "nx_permission.csv", "nx_group.csv"));

  private final RoleCatalogueMenus menus;
  private final ClosureRoleSeeder closureRoles;
  private final DaybookOffMigration daybookOff;
  private final ActiveCompanyShortcutSeeder activeCompanyShortcut;
  private final RoleCatalogueRetirement retirement;

  @Inject
  public RoleCatalogueImporter(
      RoleCatalogueMenus menus,
      ClosureRoleSeeder closureRoles,
      DaybookOffMigration daybookOff,
      ActiveCompanyShortcutSeeder activeCompanyShortcut,
      RoleCatalogueRetirement retirement) {
    this.menus = menus;
    this.closureRoles = closureRoles;
    this.daybookOff = daybookOff;
    this.activeCompanyShortcut = activeCompanyShortcut;
    this.retirement = retirement;
  }

  /** Runs the import and reports it, without ever failing the boot. */
  public void importOnStartup() {
    Path dir = null;
    try {
      dir = extractResources();
      String hash = hash(dir);
      if (hash.equals(storedHash())) {
        // Re-importing ~6k unchanged rows costs minutes at every boot; the steps below still run.
        LOG.info("Role catalogue unchanged since the last import; import skipped.");
      } else {
        importChanged(dir, hash);
      }
    } catch (Exception e) {
      LOG.error("Role catalogue could not be applied; roles keep their previous state.", e);
    } finally {
      deleteQuietly(dir);
    }
    applyRest();
  }

  private void importChanged(Path dir, String hash) {
    List<String> errors = runImport(dir);
    if (errors.isEmpty()) {
      storeHash(hash);
      LOG.info("Role catalogue applied.");
    } else {
      errors.forEach(e -> LOG.error("Role catalogue: {}", e));
      LOG.error(
          "Role catalogue applied with {} failed row(s); see above. Rows that failed keep their"
              + " previous state.",
          errors.size());
    }
  }

  private void applyRest() {
    // Menu grants need the roles the import just created. Retirements run last, so a retired
    // grant the import or the menu step would still add is removed again, and they run even if
    // an earlier step failed: a failed import must not stop revocations.
    grantMenus();
    seedClosureRoles();
    switchDaybookOff();
    seedActiveCompanyShortcut();
    retire();
    evictSecurityCache();
  }

  /** SHA-256 over the catalogue files, in a fixed order. */
  static String hash(Path dir) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      List<String> files = new ArrayList<>(DATA_FILES);
      files.add(CONFIG_FILE);
      for (String file : files) {
        digest.update(file.getBytes(StandardCharsets.UTF_8));
        digest.update(Files.readAllBytes(dir.resolve(file)));
      }
      StringBuilder hex = new StringBuilder();
      for (byte b : digest.digest()) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String storedHash() {
    NxCatalogueState state =
        Query.of(NxCatalogueState.class).filter("self.code = ?", STATE_CODE).fetchOne();
    return state == null ? null : state.getHash();
  }

  /** Stored only after a clean import, so failed rows are retried at the next boot. */
  private static void storeHash(String hash) {
    JPA.runInTransaction(
        () -> {
          NxCatalogueState state =
              Query.of(NxCatalogueState.class).filter("self.code = ?", STATE_CODE).fetchOne();
          if (state == null) {
            state = new NxCatalogueState();
            state.setCode(STATE_CODE);
          }
          state.setHash(hash);
          JPA.save(state);
        });
  }

  protected void seedActiveCompanyShortcut() {
    try {
      if (activeCompanyShortcut.apply()) {
        LOG.info("Active company shortcut switched on in the header.");
      }
    } catch (Exception e) {
      LOG.error("Active company shortcut could not be seeded; the header won't show it.", e);
    }
  }

  protected void switchDaybookOff() {
    try {
      List<String> switched = daybookOff.apply();
      if (!switched.isEmpty()) {
        LOG.info("Daybook mode switched off for: {}", switched);
      }
    } catch (Exception e) {
      LOG.error("Daybook mode could not be switched off; it is retried at the next boot.", e);
    }
  }

  protected void seedClosureRoles() {
    try {
      List<String> added = closureRoles.apply();
      if (!added.isEmpty()) {
        LOG.info("Role catalogue: {} closure role(s) added: {}", added.size(), added);
      }
    } catch (Exception e) {
      LOG.error("Period-closure role lists could not be seeded; closing may be refused.", e);
    }
  }

  protected void grantMenus() {
    try {
      RoleCatalogueMenus.Result result = menus.apply();
      if (!result.getGranted().isEmpty()) {
        LOG.info(
            "Role catalogue: {} menu grant(s) added: {}",
            result.getGranted().size(),
            result.getGranted());
      }
      result.getProblems().forEach(p -> LOG.warn("Role catalogue menus: {}", p));
    } catch (Exception e) {
      LOG.error("Role catalogue menu grants could not be applied.", e);
    }
  }

  protected void retire() {
    try {
      List<String> changes = retirement.apply();
      changes.forEach(c -> LOG.info("Role catalogue: retired {}", c));
    } catch (Exception e) {
      LOG.error(
          "Role catalogue retirements could not be applied; retired grants may still be active.",
          e);
    }
  }

  protected Path extractResources() throws IOException {
    Path dir = Files.createTempDirectory("nyxa-security-");
    List<String> files = new ArrayList<>(DATA_FILES);
    files.add(CONFIG_FILE);
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    for (String name : files) {
      try (InputStream in = loader.getResourceAsStream(RESOURCE_DIR + name)) {
        if (in == null) {
          throw new IOException("Missing classpath resource " + RESOURCE_DIR + name);
        }
        Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return dir;
  }

  protected List<String> runImport(Path dir) {
    List<String> errors = new ArrayList<>();
    CSVImporter importer =
        new CSVImporter(dir.resolve(CONFIG_FILE).toString(), dir.toString(), null);
    importer.addListener(
        new Listener() {
          @Override
          public void imported(Model bean) {}

          @Override
          public void imported(Integer total, Integer success) {
            if (total != null && success != null && !total.equals(success)) {
              errors.add((total - success) + " of " + total + " rows in a file failed");
            }
          }

          @Override
          public void handle(Model bean, Exception e) {
            errors.add(
                (bean == null ? "row" : bean.getClass().getSimpleName())
                    + " failed: "
                    + e.getMessage());
          }
        });
    importer.run();
    return errors;
  }

  /**
   * Permission, Role and Group are cacheable entities that AuthSecurity reads per request, and
   * MenuChecker's query is cacheable. Evict them so the first request after boot sees the
   * catalogue.
   */
  protected void evictSecurityCache() {
    try {
      javax.persistence.Cache cache = JPA.em().getEntityManagerFactory().getCache();
      cache.evict(Permission.class);
      cache.evict(Role.class);
      cache.evict(Group.class);
      cache.evict(MetaMenu.class);
    } catch (Exception e) {
      LOG.warn("Could not evict the security cache; a restart will pick the change up.", e);
    }
  }

  private static void deleteQuietly(Path dir) {
    if (dir == null) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      LOG.debug("Could not delete {}", dir, e);
    }
  }
}
