/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2026 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.base.service.viewsync;

import com.axelor.app.AppSettings;
import com.axelor.db.JPA;
import com.axelor.i18n.I18nBundle;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.axelor.meta.MetaStore;
import com.axelor.meta.db.MetaModule;
import com.axelor.meta.db.repo.MetaModuleRepository;
import com.axelor.meta.loader.ModuleManager;
import com.google.inject.Singleton;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-imports a module's view XML (views, menus, selections, actions, translations) on startup when
 * the deployed code's module version differs from the version recorded in the database.
 *
 * <p>Why this exists: Axelor writes each module's version into {@code MetaModule.moduleVersion} but
 * never compares it. {@code ModuleManager.resolve(update)} only marks a module for re-processing
 * when its row is new or when {@code update} is true, and {@code AppStartup.init()} always passes
 * {@code update = false}. {@code ViewLoader} then skips every entity that already exists ({@code if
 * (entity.getId() != null && !update) return;}). The consequence is that on an ordinary redeploy +
 * restart, view XML changes never reach an already-installed database — the only thing that applied
 * them was running {@code gradlew database --update} by hand as a separate step, which is easy to
 * forget and was in fact not being run.
 *
 * <p>This service supplies the missing comparison. The version itself is produced per build (see
 * {@code modules/axelor-open-suite/version.gradle}), so any deployed build differs from whatever
 * the database last recorded, and a plain restart is enough.
 *
 * <p><b>Meta only, never data.</b> {@code ModuleManager.update(...)} would also re-run {@code
 * DataLoader}/{@code DemoLoader} for the named modules; {@link ModuleManager#setLoadData(boolean)}
 * is set to {@code false} first so only view XML is touched. Reference/seed data is out of scope
 * here — that is what {@code DataInitSyncService} and App Install do.
 *
 * <p><b>Translations ride along, by design.</b> {@code ModuleManager.installMeta()} runs a fixed
 * trio of meta loaders — {@code ModelLoader}, {@code ViewLoader}, {@code I18nLoader} — so every
 * {@code i18n/messages_<lang>.csv} and {@code i18n/custom_<lang>.csv} of each re-imported module is
 * re-read into {@code META_TRANSLATION} here, for all modules, not just the custom ones. {@code
 * I18nLoader} ignores its {@code update} flag, upserts by {@code (key, language)} and never
 * deletes, so the re-read is idempotent and creates no duplicate rows; it does overwrite a row an
 * administrator edited by hand, exactly as {@code Restore all} does. Load order is the module
 * resolution order with {@code custom_*.csv} sorted last, so the standard precedence is preserved.
 * There is no way to re-read translations <i>without</i> the views: {@code I18nLoader}, {@code
 * AbstractLoader} and {@code Module} are all package-private in {@code com.axelor.meta.loader}, and
 * {@code installMeta} always runs the three loaders together.
 *
 * <p><b>What it overwrites.</b> A re-import is authoritative: the shipped XML replaces the {@code
 * META_VIEW}/{@code META_MENU}/{@code META_SELECT}/{@code META_ACTION} rows unconditionally, and
 * there is no "customized, do not overwrite" marker on those entities. Anything edited directly on
 * those rows (Views admin screen, hand-SQL) reverts to the shipped XML. Per-user customizations
 * kept in separate entities survive, with one exception: for a {@code dashboard} whose XML changed,
 * {@code ViewLoader} deletes every user's {@code MetaViewCustom} row for that dashboard. All of
 * this is equally true of {@code gradlew database --update}; the difference is that it now happens
 * on every deploy that changes the version, so it is gated by {@link #ENABLED_PROPERTY}.
 */
@Singleton
public class ModuleViewSyncService {

  private static final Logger LOG = LoggerFactory.getLogger(ModuleViewSyncService.class);

  /** Set to {@code false} in axelor-config.properties to disable the startup re-import. */
  public static final String ENABLED_PROPERTY = "view.sync.on-version-change";

  private static final long LOCK_KEY = 900_000_000_001L;

  /** Entry point for the startup listener. Never throws: a failure here must not block boot. */
  public void syncChangedModules() {
    if (!AppSettings.get().getBoolean(ENABLED_PROPERTY, true)) {
      return;
    }
    try {
      runSync();
    } catch (Exception e) {
      // runSync() has already put the pre-import versions back, so the next boot retries.
      LOG.error("View re-import on version change failed.", e);
    }
  }

  // Deliberately NOT @Transactional: ModuleManager.update() -> resolve() is itself @Transactional
  // and installMeta() runs the loaders through a ParallelTransactionExecutor that opens its own
  // transaction per thread. An enclosing guice-persist transaction collides with that, the same way
  // it does for CSVImporter (see DataInitSyncService#runSync).
  protected void runSync() {
    List<String> changed = findModulesWithChangedVersion();
    if (changed.isEmpty()) {
      LOG.debug("No module version changed; view XML in the database is current.");
      return;
    }

    // Serialize across app instances: a concurrent boot elsewhere waits rather than racing the
    // same import. Session-level (not xact) so the lock outlives ModuleManager's own transactions.
    runLocked(
        () -> {
          // Re-check under the lock: another instance may have just done the work.
          List<String> stillChanged = findModulesWithChangedVersion();
          if (stillChanged.isEmpty()) {
            return;
          }
          // Captured BEFORE the import: ModuleManager.update() starts with resolve(true), which
          // stamps the deployed version into META_MODULE for every module in its own committed
          // transaction, before a single loader has run. Without putting those values back, one
          // failed import leaves the database claiming it is current, findModulesWithChangedVersion
          // returns nothing on the next boot, and the views AND translations stay stale forever —
          // silently, until someone runs "Restore all" by hand.
          Map<String, String> previousVersions = recordedVersions(stillChanged);
          try {
            importViews(stillChanged);
          } catch (RuntimeException e) {
            restoreRecordedVersions(previousVersions);
            throw e;
          }
        });
  }

  /** The authoritative re-import itself. Only ever reached for modules whose version changed. */
  protected void importViews(List<String> changed) {
    LOG.info("Module version changed for {} — re-importing view XML.", changed);
    long start = System.currentTimeMillis();

    ModuleManager moduleManager = Beans.get(ModuleManager.class);
    // Views/menus/selections/actions only. ModuleManager is not a singleton, so this flag is
    // scoped to the instance we just obtained.
    moduleManager.setLoadData(false);
    moduleManager.update(false, changed.toArray(new String[0]));

    // The caller owns cache invalidation; MetaController.restoreAll() pairs the same two calls
    // with its reload. Without them the old views keep being served from cache.
    MetaStore.clear();
    I18nBundle.invalidate();

    LOG.info(
        "Re-imported view XML for {} module(s) in {} ms.",
        changed.size(),
        System.currentTimeMillis() - start);
  }

  /**
   * The versions currently recorded in {@code META_MODULE} for the given modules, read before the
   * re-import rewrites them. A module with no row, or no recorded version, is left out — there is
   * nothing to put back for it.
   */
  protected Map<String, String> recordedVersions(List<String> moduleNames) {
    MetaModuleRepository metaModuleRepo = Beans.get(MetaModuleRepository.class);
    Map<String, String> versions = new LinkedHashMap<>();
    for (String name : moduleNames) {
      MetaModule stored = metaModuleRepo.findByName(name);
      if (stored != null && stored.getModuleVersion() != null) {
        versions.put(name, stored.getModuleVersion());
      }
    }
    return versions;
  }

  /**
   * Puts the pre-import versions back after a failed re-import, so the next startup sees the
   * mismatch again and retries. Own transaction: {@link #runSync()} deliberately runs outside one,
   * and the failed import may well have left the shared entity manager unusable for writes.
   *
   * <p>Never rethrows. If even the restore fails, the only cost is the pre-existing behavior — a
   * database that claims to be current — and the log says so explicitly.
   */
  protected void restoreRecordedVersions(Map<String, String> previousVersions) {
    if (previousVersions.isEmpty()) {
      return;
    }
    try {
      JPA.em().getTransaction().begin();
      for (Map.Entry<String, String> entry : previousVersions.entrySet()) {
        JPA.em()
            .createQuery("UPDATE MetaModule m SET m.moduleVersion = :version WHERE m.name = :name")
            .setParameter("version", entry.getValue())
            .setParameter("name", entry.getKey())
            .executeUpdate();
      }
      JPA.em().getTransaction().commit();
      LOG.warn(
          "Re-import failed; restored the pre-import version of {} module(s) so the next startup"
              + " retries.",
          previousVersions.size());
    } catch (Exception e) {
      if (JPA.em().getTransaction().isActive()) {
        JPA.em().getTransaction().rollback();
      }
      LOG.error(
          "Re-import failed AND the pre-import module versions could not be restored. The next"
              + " startup will not retry; run Administration > View Management > Restore all.",
          e);
    }
  }

  /** Runs {@code body} holding the cross-instance advisory lock. */
  protected void runLocked(Runnable body) {
    acquireAdvisoryLock();
    try {
      body.run();
    } finally {
      releaseAdvisoryLock();
    }
  }

  /**
   * Compares the version in each deployed module's {@code META-INF/axelor-module.properties}
   * against the version recorded in {@code META_MODULE}.
   *
   * <p>Must be evaluated before the first {@code update(...)} call: that rewrites {@code
   * moduleVersion} for every module, which is what makes this self-clearing on the next boot.
   *
   * <p>The result is deduplicated by module name. {@code MetaScanner.findModuleProperties()}
   * returns one entry per {@code META-INF/axelor-module.properties} found on the classpath, and a
   * module can contribute more than one — a {@code gradlew run} has both the exploded {@code
   * build/resources/main} and the module jar on it, which reports every module twice.
   */
  protected List<String> findModulesWithChangedVersion() {
    MetaModuleRepository metaModuleRepo = Beans.get(MetaModuleRepository.class);
    List<String> changed = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (Properties properties : MetaScanner.findModuleProperties()) {
      String name = properties.getProperty("name");
      String deployedVersion = properties.getProperty("version");
      if (name == null || deployedVersion == null) {
        continue;
      }
      if (!seen.add(name)) {
        continue;
      }
      // Not installed: nothing to refresh, and a brand-new module was just installed in full by
      // AppStartup. Either way its views already match the deployed code.
      if (!ModuleManager.isInstalled(name)) {
        continue;
      }
      MetaModule stored = metaModuleRepo.findByName(name);
      if (stored == null) {
        continue;
      }
      if (!Objects.equals(deployedVersion, stored.getModuleVersion())) {
        LOG.info(
            "Module {}: deployed version {} != recorded version {}.",
            name,
            deployedVersion,
            stored.getModuleVersion());
        changed.add(name);
      }
    }
    return changed;
  }

  private void acquireAdvisoryLock() {
    runInOwnTransaction("SELECT pg_advisory_lock(?)");
  }

  private void releaseAdvisoryLock() {
    runInOwnTransaction("SELECT pg_advisory_unlock(?)");
  }

  private void runInOwnTransaction(String sql) {
    JPA.em().getTransaction().begin();
    try {
      JPA.em()
          .unwrap(Session.class)
          .doWork(
              conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                  ps.setLong(1, LOCK_KEY);
                  ps.execute();
                }
              });
      JPA.em().getTransaction().commit();
    } catch (RuntimeException e) {
      if (JPA.em().getTransaction().isActive()) {
        JPA.em().getTransaction().rollback();
      }
      throw e;
    }
  }
}
