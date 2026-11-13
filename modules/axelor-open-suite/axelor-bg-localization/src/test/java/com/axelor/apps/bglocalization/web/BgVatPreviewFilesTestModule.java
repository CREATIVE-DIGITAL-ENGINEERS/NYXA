package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.vat.BgVatDocumentCollector;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.util.Modules;
import java.util.Collections;
import java.util.List;

/**
 * Производственото окабеляване с ЕДНА подмяна: събирачът на ДДС документите връща празен списък.
 * Истинският чете {@code Move}, чийто PostgreSQL {@code @Formula} (array_agg) HSQLDB на
 * integrationTest не разбира — отпечатъкът на пакета не може да се смята там. Празният списък дава
 * валиден отпечатък, различен от записания във фикстурата, тоест пакетът е „остарял“ — точно
 * случаят, в който onLoad и прегледът пишеха.
 */
public class BgVatPreviewFilesTestModule extends AbstractModule {

  @Override
  protected void configure() {
    install(
        Modules.override(new BgLocalizationProductionWiringTestModule())
            .with(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    bind(BgVatDocumentCollector.class).to(NoDocuments.class);
                  }
                }));
  }

  public static class NoDocuments extends BgVatDocumentCollector {

    @Inject
    public NoDocuments(MoveRepository moveRepo) {
      super(moveRepo);
    }

    @Override
    public List<BgVatDocumentDto> collect(BgVatExportRequestDto request) {
      return Collections.emptyList();
    }
  }
}
