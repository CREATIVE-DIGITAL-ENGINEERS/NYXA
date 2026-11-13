package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.google.inject.AbstractModule;
import com.google.inject.util.Modules;

/**
 * Производственото окабеляване (HSQLDB, истински Guice) с ЕДНА подмяна: записът на периода може да
 * падне ПОСЛЕДЕН — след като отказът вече е записал фиша и е затворил следата на корекцията.
 * {@code @Transactional} на {@code BgPaySlipService#cancel} се наследява непокътнат.
 */
public class BgPaySlipCancelTxTestModule extends AbstractModule {

  @Override
  protected void configure() {
    install(
        Modules.override(new BgLocalizationProductionWiringTestModule())
            .with(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    bind(BgPaySlipService.class).to(FailingPeriodSave.class);
                  }
                }));
  }

  /** Истинската услуга; при вдигнат признак записът на периода пада. */
  public static class FailingPeriodSave extends BgPaySlipService {

    static volatile boolean failOnPeriodSave;

    @Override
    protected void savePeriod(Period period) {
      if (failOnPeriodSave) {
        throw new IllegalStateException("провал при записа на периода");
      }
      super.savePeriod(period);
    }
  }
}
