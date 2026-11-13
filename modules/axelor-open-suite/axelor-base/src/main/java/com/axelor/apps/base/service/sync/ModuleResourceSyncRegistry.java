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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The declaration of <b>which</b> data-init inputs each module re-imports on startup. Two sources,
 * both declarative — neither scans the classpath, so demo data and stray resources are never picked
 * up:
 *
 * <ol>
 *   <li>{@link #DEFAULTS} — a built-in line per config, for configs shipped by modules of this
 *       repository. Adding a module here is one entry, not a new listener or service.
 *   <li>the optional {@code datainit.sync.<module>.configs} property, one {@code config.xml >
 *       data-dir} pair per comma-separated item. A module that does not want an entry in this class
 *       (or an environment that needs a different set) declares it purely in {@code
 *       axelor-config.properties}.
 * </ol>
 *
 * <p>The property <b>replaces</b> the built-in list for that module when present, so an environment
 * can narrow the set without editing code. A module still only syncs at all when its name is listed
 * in {@code datainit.sync.modules} — see {@link ModuleResourceSyncService}.
 */
public final class ModuleResourceSyncRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ModuleResourceSyncRegistry.class);

  /** Per-module property carrying {@code config.xml > data-dir} pairs; overrides the built-ins. */
  public static final String CONFIGS_PROPERTY_PREFIX = "datainit.sync.";

  public static final String CONFIGS_PROPERTY_SUFFIX = ".configs";

  private static final String PAIR_SEPARATOR = ">";

  public static final String BG_LOCALIZATION = "axelor-bg-localization";

  /**
   * Built-in declarations. One entry per config a module wants re-imported on startup — exactly the
   * set {@code BgLocalizationServerStartListener} used to call one by one:
   *
   * <ul>
   *   <li>{@code apps/init-data/bg-localization-config.xml} — payroll/HR reference data
   *       (contribution funds, NKID/NKPD, minimum insurance income, payroll parameters, TZPB rates,
   *       …);
   *   <li>{@code data-init/input-config.xml} — Bulgarian units of measure, unit conversions and the
   *       localization object-permission grant;
   *   <li>{@code apps/init-data/bg-localization-leave-reason-config.xml} — the {@code LeaveReason}
   *       seed ({@code lr_paid_annual}, {@code lr_unpaid_own_gt30}, …), a SEPARATE file from the
   *       first one because an installer picks ONE importer per config by its root element, so a
   *       {@code csv-inputs} config can never also carry an {@code xml-inputs} {@code <input>}.
   *       {@code DataInitSyncService} dispatches it to Axelor's {@code XMLImporter}.
   * </ul>
   *
   * <p>Why any of this is re-imported at all: Axelor's own data-init loader only runs on a fresh
   * module install ({@code ModuleManager.resolve()} never marks an already-installed module pending
   * on a plain restart), so a database installed before a CSV row existed — or before it was
   * corrected — never receives it short of an uninstall/reinstall. The importers' {@code
   * search="self.importId = :importId"} upsert makes the reruns idempotent and never deletes a
   * hand-added row.
   */
  private static final Map<String, List<ModuleSyncConfig>> DEFAULTS = buildDefaults();

  private ModuleResourceSyncRegistry() {}

  private static Map<String, List<ModuleSyncConfig>> buildDefaults() {
    Map<String, List<ModuleSyncConfig>> defaults = new LinkedHashMap<>();
    defaults.put(
        BG_LOCALIZATION,
        Collections.unmodifiableList(
            Arrays.asList(
                new ModuleSyncConfig(
                    BG_LOCALIZATION,
                    "apps/init-data/bg-localization-config.xml",
                    "apps/init-data/en"),
                new ModuleSyncConfig(
                    BG_LOCALIZATION, "data-init/input-config.xml", "data-init/input"),
                new ModuleSyncConfig(
                    BG_LOCALIZATION,
                    "apps/init-data/bg-localization-leave-reason-config.xml",
                    "apps/init-data/en"))));
    return Collections.unmodifiableMap(defaults);
  }

  /** The built-in declarations for a module, empty when it declares none. */
  public static List<ModuleSyncConfig> defaultConfigs(String moduleName) {
    return DEFAULTS.getOrDefault(moduleName, Collections.emptyList());
  }

  /**
   * Resolves the configs to re-import for one module: the parsed {@code declaredPairs} when that
   * property is set, the built-ins otherwise. Duplicates are collapsed so a config is never
   * imported twice in one startup.
   *
   * @param moduleName the Axelor module name
   * @param declaredPairs raw value of {@code datainit.sync.<module>.configs}, already split on
   *     commas; empty/{@code null} falls back to {@link #defaultConfigs(String)}
   */
  public static List<ModuleSyncConfig> resolveConfigs(
      String moduleName, List<String> declaredPairs) {
    List<ModuleSyncConfig> declared = parse(moduleName, declaredPairs);
    List<ModuleSyncConfig> source = declared.isEmpty() ? defaultConfigs(moduleName) : declared;
    Set<ModuleSyncConfig> unique = new LinkedHashSet<>(source);
    return Collections.unmodifiableList(new ArrayList<>(unique));
  }

  private static List<ModuleSyncConfig> parse(String moduleName, List<String> declaredPairs) {
    List<ModuleSyncConfig> parsed = new ArrayList<>();
    if (declaredPairs == null) {
      return parsed;
    }
    for (String pair : declaredPairs) {
      if (pair == null || pair.trim().isEmpty()) {
        continue;
      }
      String[] parts = pair.split(PAIR_SEPARATOR, 2);
      if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
        LOG.warn(
            "Ignoring malformed {}{}{} entry '{}' — expected 'config.xml > data-dir'.",
            CONFIGS_PROPERTY_PREFIX,
            moduleName,
            CONFIGS_PROPERTY_SUFFIX,
            pair);
        continue;
      }
      parsed.add(new ModuleSyncConfig(moduleName, parts[0].trim(), parts[1].trim()));
    }
    return parsed;
  }

  /** The per-module property name carrying the declarations. */
  public static String configsProperty(String moduleName) {
    return CONFIGS_PROPERTY_PREFIX + moduleName + CONFIGS_PROPERTY_SUFFIX;
  }
}
