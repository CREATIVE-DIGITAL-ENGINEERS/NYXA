package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.google.inject.AbstractModule;
import com.google.inject.util.Modules;

/**
 * Производственото окабеляване (HSQLDB, истински Guice) с ЕДНА подмяна: след записа на документа
 * отказът може да падне. {@code @Transactional} на {@code BgCompensationService#cancel} се
 * наследява непокътнат.
 */
public class BgCompensationCancelTxTestModule extends AbstractModule {

  @Override
  protected void configure() {
    install(
        Modules.override(new BgLocalizationProductionWiringTestModule())
            .with(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    bind(BgCompensationService.class).to(FailingAfterSave.class);
                  }
                }));
  }

  /** Истинската услуга; при вдигнат признак пада СЛЕД записа на документа. */
  public static class FailingAfterSave extends BgCompensationService {

    static volatile boolean failAfterSave;

    @Override
    protected void save(BgCompensation doc) {
      super.save(doc);
      if (failAfterSave) {
        throw new IllegalStateException("провал след записа на документа");
      }
    }
  }
}
