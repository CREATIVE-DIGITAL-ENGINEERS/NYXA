package com.axelor.apps.nyxasecurity.service;

import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppBaseRepository;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shows the active company in the header for everyone (AOS's "Active company" quick menu: the
 * company's name, with a list to switch between the user's assigned companies).
 *
 * <p>The stock menu appears only when Base has multi-company management on and "Active company"
 * ticked under "Activate changes shortcut for". Runs at every boot and only adds: other ticked
 * shortcuts stay, and a database without the Base app yet is covered on the next restart. Switching
 * through the menu goes through {@code UserController.setActiveCompany}, so the G11/G12 rules on
 * {@code activeCompany} still apply.
 */
@Singleton
public class ActiveCompanyShortcutSeeder {

  /**
   * @return true when the configuration was changed
   */
  public boolean apply() {
    boolean[] changed = {false};
    JPA.runInTransaction(
        () -> {
          AppBase appBase = Query.of(AppBase.class).fetchOne();
          if (appBase == null) {
            return;
          }
          if (!Boolean.TRUE.equals(appBase.getEnableMultiCompany())) {
            appBase.setEnableMultiCompany(true);
            changed[0] = true;
          }
          String shortcuts = withActiveCompany(appBase.getShortcutMultiSelect());
          if (!shortcuts.equals(appBase.getShortcutMultiSelect())) {
            appBase.setShortcutMultiSelect(shortcuts);
            changed[0] = true;
          }
        });
    return changed[0];
  }

  /** Adds the active-company value to a comma-separated multi-select, keeping the others. */
  static String withActiveCompany(String current) {
    List<String> values =
        current == null || current.isBlank()
            ? new ArrayList<>()
            : Arrays.stream(current.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    if (!values.contains(AppBaseRepository.SHORTCUT_ACTIVE_COMPANY)) {
      values.add(0, AppBaseRepository.SHORTCUT_ACTIVE_COMPANY);
    }
    return String.join(",", values);
  }
}
