package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.bglocalization.report.IReport;
import com.axelor.apps.report.engine.ReportSettings;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Map;

/**
 * ПЕЧАТ НА ПРОТОКОЛА ПО ЧЛ. 117 ЗДДС — PDF на български, закачен за статията.
 *
 * <p>Стойностите са тези на {@link BgVatProtocolPrintBuilder}: образецът само ги показва (виж
 * {@link IReport#BG_VAT_PROTOCOL}).
 */
@Singleton
public class BgVatProtocolPrintService {

  protected final BgVatProtocolPrintBuilder builder;

  @Inject
  public BgVatProtocolPrintService(BgVatProtocolPrintBuilder builder) {
    this.builder = builder;
  }

  /** Връзка към PDF-а на протокола. */
  public String print(Move move) throws AxelorException {
    BgVatProtocolPrint print = builder.build(move);
    ReportSettings report =
        ReportFactory.createReport(
            IReport.BG_VAT_PROTOCOL, "Протокол-" + print.getNumber() + "-${date}");
    for (Map.Entry<String, Object> param : print.params().entrySet()) {
      report.addParam(param.getKey(), param.getValue());
    }
    return report
        .addParam("__locale", ReportSettings.getPrintingLocale(null))
        .addFormat(ReportSettings.FORMAT_PDF)
        .toAttach(move)
        .generate()
        .getFileLink();
  }
}
