/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
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
package com.axelor.apps.base.service.app;

import com.axelor.app.AppSettings;

/**
 * Whether demo data may be imported into the apps ("Import demo data" on an app, and "Bulk install"
 * with demo data): only in an environment explicitly marked as development.
 *
 * <p>The mark is {@code nyxa.environment = dev} (from the environment: {@code
 * AXELOR_CONFIG_NYXA_ENVIRONMENT=dev}). Without it, or with any other value, demo data is refused.
 * {@code application.mode} is not the mark: the committed configuration sets it to {@code dev} for
 * every environment, and the stock form shows the button whenever it is not {@code prod}.
 */
public final class DemoDataGate {

  /** The setting that marks a development environment. */
  public static final String ENVIRONMENT = "nyxa.environment";

  /** Its value in a development environment. */
  public static final String DEV = "dev";

  private DemoDataGate() {}

  /** For the views' {@code if}: whether this environment is marked as development. */
  public static boolean allowed() {
    return allowed(AppSettings.get().get(ENVIRONMENT));
  }

  /** Whether {@code environment}, the value of {@link #ENVIRONMENT}, marks development. */
  public static boolean allowed(String environment) {
    return environment != null && DEV.equalsIgnoreCase(environment.trim());
  }
}
