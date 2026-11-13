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
package com.axelor.apps.base.listener;

import com.axelor.apps.base.service.sync.ModuleResourceSyncService;
import com.axelor.event.Observes;
import com.axelor.events.StartupEvent;
import com.axelor.inject.Beans;
import javax.annotation.Priority;

/**
 * The one and only startup hook for module-resource synchronization — view XML for modules whose
 * version changed, then the declared data-init inputs. See {@link ModuleResourceSyncService} for
 * what it does and why. Modules no longer register their own startup calls for this; they declare
 * their configs instead.
 *
 * <p><b>Runs before the other startup listeners, deliberately.</b> Observers are invoked in
 * ascending {@code @Priority} order and a view re-import is authoritative — it resets the shipped
 * meta-data rows from XML. Other startup listeners patch meta-data at boot ({@link
 * BaseServerStartListener} appends every {@code ZoneId} to the {@code company.timezone.select}
 * selection, for one), so this has to happen first: re-importing afterwards would discard what they
 * just added, and nothing would put it back until the next restart. The negative priority keeps
 * this ahead of every default-priority ({@code 0}) listener.
 */
public class ModuleResourceSyncStartListener {

  public void onStartup(@Observes @Priority(-100) StartupEvent event) {
    Beans.get(ModuleResourceSyncService.class).syncAll();
  }
}
