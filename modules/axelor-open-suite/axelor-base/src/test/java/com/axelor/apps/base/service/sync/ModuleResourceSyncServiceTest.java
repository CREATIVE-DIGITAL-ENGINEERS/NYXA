package com.axelor.apps.base.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.service.datainit.DataInitSyncService;
import com.axelor.apps.base.service.viewsync.ModuleViewSyncService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the two guarantees the central coordinator exists to give, with the real {@link
 * ModuleResourceSyncService} driving recording stand-ins for the two services it delegates to (both
 * are plain non-final classes, so no mocking framework or Guice/DB is needed):
 *
 * <ol>
 *   <li>a startup processes each registered module/config exactly once — a second call, whatever
 *       triggers it, imports nothing;
 *   <li>data-init inputs run on every startup, while the view re-import stays gated by the module
 *       version comparison.
 * </ol>
 */
class ModuleResourceSyncServiceTest {

  private static final String MODULE = "axelor-bg-localization";

  /** Records every (module, config, dir) triple handed to the real service's entry point. */
  private static class RecordingDataInitSyncService extends DataInitSyncService {
    final List<String> calls = new ArrayList<>();

    @Override
    public void syncModule(String moduleName, String configResourcePath, String dirResourceName) {
      calls.add(moduleName + "|" + configResourcePath + "|" + dirResourceName);
    }
  }

  /**
   * Keeps {@link ModuleViewSyncService#runSync()}'s real gating logic — only the version lookup,
   * the cross-instance lock and the import itself are stubbed, so what is asserted is the service's
   * own decision to import or not.
   */
  private static class RecordingViewSyncService extends ModuleViewSyncService {
    final List<String> imported = new ArrayList<>();
    private final List<String> changedModules;

    RecordingViewSyncService(List<String> changedModules) {
      this.changedModules = changedModules;
    }

    @Override
    public void syncChangedModules() {
      runSync(); // skip the AppSettings-backed enable flag, exercise the gating below
    }

    @Override
    protected List<String> findModulesWithChangedVersion() {
      return changedModules;
    }

    @Override
    protected void runLocked(Runnable body) {
      body.run(); // no database, no advisory lock
    }

    @Override
    protected Map<String, String> recordedVersions(List<String> moduleNames) {
      return Collections.emptyMap(); // no META_MODULE here; the retry contract is tested separately
    }

    @Override
    protected void importViews(List<String> changed) {
      imported.addAll(changed);
    }
  }

  private ModuleResourceSyncService coordinator(
      DataInitSyncService dataInit,
      ModuleViewSyncService viewSync,
      List<ModuleSyncConfig> configs) {
    return coordinator(dataInit, viewSync, configs, true);
  }

  private ModuleResourceSyncService coordinator(
      DataInitSyncService dataInit,
      ModuleViewSyncService viewSync,
      List<ModuleSyncConfig> configs,
      boolean appInstalled) {
    return new ModuleResourceSyncService(dataInit, viewSync) {
      @Override
      protected boolean appInstalled(String appCode) {
        return appInstalled;
      }

      @Override
      protected List<String> enabledModules() {
        return Collections.singletonList(MODULE);
      }

      @Override
      protected List<ModuleSyncConfig> configsFor(String moduleName) {
        return MODULE.equals(moduleName) ? configs : Collections.emptyList();
      }
    };
  }

  @Test
  @DisplayName("one startup processes a registered module/config exactly once")
  void processesEachRegisteredConfigOnlyOnce() {
    RecordingDataInitSyncService dataInit = new RecordingDataInitSyncService();
    RecordingViewSyncService viewSync = new RecordingViewSyncService(Collections.emptyList());
    ModuleResourceSyncService service =
        coordinator(dataInit, viewSync, ModuleResourceSyncRegistry.defaultConfigs(MODULE));

    service.syncAll();
    // Anything re-entering the coordinator (a double-fired startup event, or a caller mistakenly
    // wiring it to a screen) must find the work already done.
    service.syncAll();
    service.syncAll();

    assertEquals(
        Arrays.asList(
            MODULE + "|apps/init-data/bg-localization-config.xml|apps/init-data/en",
            MODULE + "|data-init/input-config.xml|data-init/input",
            MODULE + "|apps/init-data/bg-localization-leave-reason-config.xml|apps/init-data/en"),
        dataInit.calls,
        "each declared config must be imported exactly once per application startup");
  }

  @Test
  @DisplayName("fresh database: an app's init data waits for App Install; module data-init runs")
  void appInitDataWaitsForTheAppToBeInstalled() {
    // test99 / a fresh local database: the sync ran before App Install had created the
    // bg-localization App, so studio_appBgLocalization.csv tried to create an empty App and failed.
    RecordingDataInitSyncService dataInit = new RecordingDataInitSyncService();
    RecordingViewSyncService viewSync = new RecordingViewSyncService(Collections.emptyList());

    coordinator(dataInit, viewSync, ModuleResourceSyncRegistry.defaultConfigs(MODULE), false)
        .syncAll();

    assertEquals(
        Collections.singletonList(MODULE + "|data-init/input-config.xml|data-init/input"),
        dataInit.calls,
        "only the module data-init may run before the app exists");
    assertEquals("bg-localization", ModuleResourceSyncService.appCode(MODULE));
  }

  @Test
  @DisplayName("data inputs run on startup; view sync only when a module version changed")
  void dataInputsAlwaysRunWhileViewSyncStaysVersionGated() {
    List<ModuleSyncConfig> configs =
        Collections.singletonList(
            new ModuleSyncConfig(
                MODULE, "apps/init-data/bg-localization-config.xml", "apps/init-data/en"));

    // No module version changed: data-init still runs, views are left alone.
    RecordingDataInitSyncService unchangedDataInit = new RecordingDataInitSyncService();
    RecordingViewSyncService unchangedViews = new RecordingViewSyncService(Collections.emptyList());
    coordinator(unchangedDataInit, unchangedViews, configs).syncAll();

    assertEquals(1, unchangedDataInit.calls.size(), "data-init must run on every startup");
    assertTrue(
        unchangedViews.imported.isEmpty(),
        "no view XML may be re-imported while the deployed module version matches META_MODULE");

    // A module version differs: the same data-init work happens, plus the view re-import.
    RecordingDataInitSyncService changedDataInit = new RecordingDataInitSyncService();
    RecordingViewSyncService changedViews =
        new RecordingViewSyncService(Collections.singletonList(MODULE));
    coordinator(changedDataInit, changedViews, configs).syncAll();

    assertEquals(1, changedDataInit.calls.size(), "data-init must run on every startup");
    assertEquals(
        Collections.singletonList(MODULE),
        changedViews.imported,
        "a changed module version must trigger the view re-import");
  }
}
