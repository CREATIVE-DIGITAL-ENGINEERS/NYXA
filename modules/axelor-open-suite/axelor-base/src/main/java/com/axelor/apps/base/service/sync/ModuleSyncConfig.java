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

import java.util.Objects;

/**
 * One declared data-init input of a module: the importer config XML plus the directory holding the
 * files it references, both as classpath resource paths relative to the module's resources.
 *
 * <p>Purely declarative — it names a config, it does not decide whether or when that config runs.
 * That is {@link ModuleResourceSyncService}'s job.
 */
public final class ModuleSyncConfig {

  private final String moduleName;
  private final String configResourcePath;
  private final String dataDirResourceName;

  public ModuleSyncConfig(
      String moduleName, String configResourcePath, String dataDirResourceName) {
    this.moduleName = Objects.requireNonNull(moduleName);
    this.configResourcePath = Objects.requireNonNull(configResourcePath);
    this.dataDirResourceName = Objects.requireNonNull(dataDirResourceName);
  }

  public String getModuleName() {
    return moduleName;
  }

  public String getConfigResourcePath() {
    return configResourcePath;
  }

  public String getDataDirResourceName() {
    return dataDirResourceName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ModuleSyncConfig)) {
      return false;
    }
    ModuleSyncConfig other = (ModuleSyncConfig) o;
    return moduleName.equals(other.moduleName)
        && configResourcePath.equals(other.configResourcePath)
        && dataDirResourceName.equals(other.dataDirResourceName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(moduleName, configResourcePath, dataDirResourceName);
  }

  @Override
  public String toString() {
    return moduleName + ":" + configResourcePath + " > " + dataDirResourceName;
  }
}
