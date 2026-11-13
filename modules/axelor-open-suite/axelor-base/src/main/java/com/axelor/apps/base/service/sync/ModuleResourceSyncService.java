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
package com.axelor.apps.base.service.sync;

import com.axelor.app.AppSettings;
import com.axelor.apps.base.service.datainit.DataInitSyncService;
import com.axelor.apps.base.service.viewsync.ModuleViewSyncService;
import com.axelor.db.JPA;
import com.axelor.studio.db.App;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single coordinator for module-resource synchronization at application startup. Replaces the
 * per-module startup calls that each module used to make on its own (BG Localization alone made
 * three, one per config, plus a separate listener existed for the view sync).
 *
 * <p><b>What it runs, in order:</b>
 *
 * <ol>
 *   <li><b>View/menu/action/selection XML</b> — delegated to {@link ModuleViewSyncService}, which
 *       stays gated by the deployed module version differing from {@code META_MODULE}. Nothing is
 *       re-imported when versions match. It goes first because a re-import is authoritative and
 *       would otherwise discard what later boot listeners patch onto the meta-data rows.
 *   <li><b>Data-init CSV/XML inputs</b> — delegated to {@link DataInitSyncService}, once per config
 *       declared in {@link ModuleResourceSyncRegistry}, for each module listed in {@code
 *       datainit.sync.modules}. Importer behavior is untouched: upsert by {@code importId}, never a
 *       delete for a row that disappeared from a source file.
 * </ol>
 *
 * <p><b>Startup only, at most once.</b> {@link #syncAll()} is driven exclusively by {@code
 * ModuleResourceSyncStartListener} on {@code StartupEvent} and latches after its first run, so no
 * menu click, form {@code onLoad}, submenu navigation or HTTP request can ever re-trigger it —
 * opening different screens does no work at all. The latch also makes a stray second invocation (a
 * double-fired event, a test, a manual call) a no-op rather than a second import.
 *
 * <p><b>Failures are isolated.</b> Each module, and each config within it, is run in its own
 * try/catch and logged as one concise line, so a bad config never prevents the remaining modules
 * from syncing — nor does it block boot.
 *
 * <p><b>Adding a module</b> is two declarative edits and no new Java type: one line in {@link
 * ModuleResourceSyncRegistry} (or the {@code datainit.sync.<module>.configs} property) and the
 * module name in {@code datainit.sync.modules}.
 */
@Singleton
public class ModuleResourceSyncService {

  private static final Logger LOG = LoggerFactory.getLogger(ModuleResourceSyncService.class);

  /** Comma-separated module names whose declared data-init configs are re-imported on startup. */
  public static final String ENABLED_MODULES_PROPERTY = "datainit.sync.modules";

  private final DataInitSyncService dataInitSyncService;
  private final ModuleViewSyncService moduleViewSyncService;

  /** Latched on the first run; every later call is a no-op. */
  private final AtomicBoolean done = new AtomicBoolean();

  @Inject
  public ModuleResourceSyncService(
      DataInitSyncService dataInitSyncService, ModuleViewSyncService moduleViewSyncService) {
    this.dataInitSyncService = dataInitSyncService;
    this.moduleViewSyncService = moduleViewSyncService;
  }

  /**
   * Entry point for the startup listener. Runs the whole synchronization once per application
   * lifetime and never throws: a failure here must not block boot.
   */
  public void syncAll() {
    if (!done.compareAndSet(false, true)) {
      LOG.debug("Module resource sync already ran in this application; skipping.");
      return;
    }
    syncViews();
    syncDataInputs();
  }

  /** View XML — gated inside {@link ModuleViewSyncService} by the module version comparison. */
  protected void syncViews() {
    try {
      moduleViewSyncService.syncChangedModules();
    } catch (Exception e) {
      // Already defensive internally; this is the last line so a data-init sync still happens.
      LOG.error("View synchronization failed; continuing with data-init synchronization.", e);
    }
  }

  /** Data-init inputs — unconditional on every startup, for the configured modules only. */
  protected void syncDataInputs() {
    Set<String> modules = new LinkedHashSet<>(enabledModules());
    if (modules.isEmpty()) {
      LOG.debug("No module listed in {}; data-init sync skipped.", ENABLED_MODULES_PROPERTY);
      return;
    }
    for (String moduleName : modules) {
      syncModule(moduleName);
    }
  }

  private void syncModule(String moduleName) {
    List<ModuleSyncConfig> configs = configsFor(moduleName);
    if (configs.isEmpty()) {
      LOG.warn(
          "Module {} is listed in {} but declares no data-init config; nothing to sync.",
          moduleName,
          ENABLED_MODULES_PROPERTY);
      return;
    }
    for (ModuleSyncConfig config : configs) {
      if (isAppInitData(config) && !appInstalled(appCode(moduleName))) {
        // A fresh database: this runs before App Install has created the app, so the inputs that
        // link to it ("app" → App by code) would try to create an empty App and fail. App Install
        // imports the same file moments later; the next startup syncs it as usual.
        LOG.info(
            "Data-init sync skipped until app {} is installed: {}", appCode(moduleName), config);
        continue;
      }
      try {
        dataInitSyncService.syncModule(
            config.getModuleName(),
            config.getConfigResourcePath(),
            config.getDataDirResourceName());
        LOG.info("Data-init sync done: {}", config);
      } catch (Exception e) {
        // Isolate: one bad config must not stop the module's remaining configs, nor other modules.
        LOG.error("Data-init sync failed: {}", config, e);
      }
    }
  }

  /** An app's own init data (Studio's {@code apps/init-data}), as opposed to module data-init. */
  static boolean isAppInitData(ModuleSyncConfig config) {
    return config.getConfigResourcePath().startsWith("apps/init-data/");
  }

  /** The Studio app a module installs: its name without "axelor-" (axelor-bg-localization). */
  static String appCode(String moduleName) {
    return moduleName.startsWith("axelor-") ? moduleName.substring("axelor-".length()) : moduleName;
  }

  /** Overridable seam so the coordinator is testable without a database. */
  protected boolean appInstalled(String appCode) {
    return JPA.all(App.class).filter("self.code = ?", appCode).count() > 0;
  }

  /** Overridable seam so the coordinator is testable without {@code AppSettings}. */
  protected List<String> enabledModules() {
    return AppSettings.get().getList(ENABLED_MODULES_PROPERTY);
  }

  /** Overridable seam so the coordinator is testable without {@code AppSettings}. */
  protected List<ModuleSyncConfig> configsFor(String moduleName) {
    return ModuleResourceSyncRegistry.resolveConfigs(
        moduleName,
        AppSettings.get().getList(ModuleResourceSyncRegistry.configsProperty(moduleName)));
  }
}
