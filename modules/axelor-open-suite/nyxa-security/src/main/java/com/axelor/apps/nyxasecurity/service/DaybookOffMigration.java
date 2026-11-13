package com.axelor.apps.nyxasecurity.service;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.nyxasecurity.db.NxCatalogueState;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Switches daybook mode off for every company, once (decision of 17.10.2026).
 *
 * <p>Posting is as in standard Axelor: validating gives Accounted. The former {@code DaybookSeeder}
 * had turned daybook mode on for every company at each boot; this undoes it in existing databases.
 * Entries already in Daybook are left as they are.
 *
 * <p>Runs at boot but applies only once: a {@link NxCatalogueState} row marks it done, so a company
 * that the technical administrator later switches to daybook mode on purpose stays that way. A
 * second run, with or without the marker, changes nothing.
 */
@Singleton
public class DaybookOffMigration {

  /** NxCatalogueState key marking the migration as done. */
  static final String STATE_CODE = "daybook-off-2026-10-17";

  /**
   * @return the names of the companies switched off; empty when already done or nothing was on
   */
  public List<String> apply() {
    List<String> switched = new ArrayList<>();
    JPA.runInTransaction(
        () -> {
          if (Query.of(NxCatalogueState.class).filter("self.code = ?", STATE_CODE).count() > 0) {
            return;
          }
          for (AccountConfig config : Query.of(AccountConfig.class).fetch()) {
            if (Boolean.TRUE.equals(config.getAccountingDaybook())) {
              config.setAccountingDaybook(false);
              switched.add(config.getCompany() == null ? "?" : config.getCompany().getName());
            }
          }
          NxCatalogueState done = new NxCatalogueState();
          done.setCode(STATE_CODE);
          done.setHash("done");
          JPA.save(done);
        });
    return switched;
  }
}
