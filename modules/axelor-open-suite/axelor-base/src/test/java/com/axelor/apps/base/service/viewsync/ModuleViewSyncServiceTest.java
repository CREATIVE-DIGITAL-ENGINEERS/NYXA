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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gating decision of {@link ModuleViewSyncService}, which is also what decides whether a
 * module's {@code i18n/messages_*.csv} is re-read into {@code META_TRANSLATION} — the translations
 * are loaded by {@code I18nLoader} inside the same {@code ModuleManager.installMeta()} call as the
 * views.
 *
 * <p>No database and no {@code ModuleManager}: {@link FakeViewSync} stands in for {@code
 * META_MODULE} with a map, and mirrors the one behavior that matters for the retry contract —
 * {@code ModuleManager.update()} stamps the deployed version into the table through {@code
 * resolve(true)} <i>before</i> any loader runs, so the stamp survives a failing import.
 */
class ModuleViewSyncServiceTest {

  private static final String DEPLOYED = "0.7.0.20261016163741";
  private static final String RECORDED = "0.7.0.20260901120000";

  private static class FakeViewSync extends ModuleViewSyncService {

    /** Stands in for META_MODULE: module name -> recorded version. */
    final Map<String, String> metaModule = new LinkedHashMap<>();

    /** One entry per import attempt, holding the modules it was asked to re-import. */
    final List<List<String>> importAttempts = new ArrayList<>();

    boolean importFails;

    FakeViewSync(String... modules) {
      for (String module : modules) {
        metaModule.put(module, RECORDED);
      }
    }

    /** One application startup. Mirrors the real catch, bypassing the AppSettings enable flag. */
    void boot() {
      try {
        runSync();
      } catch (Exception e) {
        // as in syncChangedModules()
      }
    }

    @Override
    protected List<String> findModulesWithChangedVersion() {
      return metaModule.entrySet().stream()
          .filter(e -> !DEPLOYED.equals(e.getValue()))
          .map(Map.Entry::getKey)
          .collect(Collectors.toList());
    }

    @Override
    protected void runLocked(Runnable body) {
      body.run(); // no database, no advisory lock
    }

    @Override
    protected Map<String, String> recordedVersions(List<String> moduleNames) {
      Map<String, String> versions = new LinkedHashMap<>();
      moduleNames.forEach(name -> versions.put(name, metaModule.get(name)));
      return versions;
    }

    @Override
    protected void restoreRecordedVersions(Map<String, String> previousVersions) {
      metaModule.putAll(previousVersions);
    }

    @Override
    protected void importViews(List<String> changed) {
      importAttempts.add(new ArrayList<>(changed));
      // resolve(true) commits the new versions before the loaders run.
      changed.forEach(name -> metaModule.put(name, DEPLOYED));
      if (importFails) {
        throw new IllegalStateException("loader blew up");
      }
    }
  }

  @Test
  @DisplayName(
      "unchanged module versions re-import nothing — no translation reload on a plain boot")
  void unchangedVersionsImportNothing() {
    FakeViewSync sync = new FakeViewSync("axelor-base", "axelor-bg-localization");
    sync.metaModule.replaceAll((module, version) -> DEPLOYED);

    sync.boot();

    assertTrue(sync.importAttempts.isEmpty());
  }

  @Test
  @DisplayName("a changed version re-imports every changed module, once, and not again on reboot")
  void changedVersionImportsOnceThenStaysQuiet() {
    FakeViewSync sync = new FakeViewSync("axelor-base", "axelor-bg-localization");

    sync.boot();
    sync.boot();

    assertEquals(1, sync.importAttempts.size());
    assertEquals(
        java.util.Arrays.asList("axelor-base", "axelor-bg-localization"),
        sync.importAttempts.get(0));
  }

  @Test
  @DisplayName("a failed re-import is retried on the next startup instead of being lost")
  void failedImportIsRetriedOnTheNextStartup() {
    FakeViewSync sync = new FakeViewSync("axelor-base");
    sync.importFails = true;

    sync.boot();

    assertEquals(1, sync.importAttempts.size());
    assertEquals(RECORDED, sync.metaModule.get("axelor-base")); // rolled back, mismatch persists

    sync.importFails = false;
    sync.boot();

    assertEquals(2, sync.importAttempts.size());
    assertEquals(DEPLOYED, sync.metaModule.get("axelor-base"));
  }
}
