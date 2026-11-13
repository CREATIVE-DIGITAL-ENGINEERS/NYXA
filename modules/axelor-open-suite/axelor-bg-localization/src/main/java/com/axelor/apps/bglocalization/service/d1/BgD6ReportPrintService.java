package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.report.engine.ReportSettings;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * ПЕЧАТНИЯТ ОБРАЗЕЦ на Декларация обр. № 6 — по образеца на {@link BgD1ReportPrintService}.
 *
 * <p><b>⚠️ ЕДИН ПЕРИОД, НЕ ДВА — И ТОВА Е ЕДИНСТВЕНАТА СЪЩЕСТВЕНА РАЗЛИКА С Д1-ПЕЧАТА.</b> Д1
 * различава периода на ДАННИТЕ от периода на ПОДАВАНЕ (чл. 4 ал. 1 т. 1 б. „б“) и печата двата. Д6
 * носи само месеца, „за които се отнасят вноските“ (указание т. 9); моментът на подаване се
 * изразява през ДАТАТА НА ИЗПЛАЩАНЕ (т. 18) и през ВИДА ПЛАЩАНЕ (т. 8), тоест през две полета на
 * самата декларация, а не през втори период. Копиран ред по ред от Д1-печата, отчетът щеше да иска
 * {@code bgSubmissionPeriod}, което на Д6 не съществува.
 *
 * <p><b>⚠️ И ТУК {@code registrationCode}, НЕ {@code taxNbr}.</b> Т. 1 иска ЕИК по БУЛСТАТ/ЗТРРЮЛНЦ
 * — същият капан като при т. 4 на Д1 и при ДДС-печата, който взима номера по ЗДДС.
 *
 * <p><b>⚠️ ОТКАЗИТЕ И ПРОВЕРКИТЕ СЕ ПЕЧАТАТ ЗАЕДНО, в едно поле.</b> На Д1 отказът е за ФИШ (лице
 * без запис) и стои отделно от находките. Д6 няма записи по лице — тя е едно число на фонд, тоест и
 * двата ѝ доклада описват ЕДНА И СЪЩА декларация. Две отделни полета тук биха били две заглавия над
 * един и същи списък.
 */
@Singleton
public class BgD6ReportPrintService {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * Връща връзка към отпечатаната декларация.
   *
   * @param rptdesign логическото име на отчета (виж {@code IReport#BG_D6_DECLARATION})
   * @param format {@link ReportSettings#FORMAT_PDF} или {@link ReportSettings#FORMAT_XLSX}
   */
  public String print(
      BgD6Declaration declaration, String rptdesign, String format, String outputBaseName)
      throws AxelorException {

    Company company = declaration.getCompany();
    Partner partner = company == null ? null : company.getPartner();

    return ReportFactory.createReport(rptdesign, outputBaseName + "-${date}")
        .addParam("BgD6DeclarationId", declaration.getId())
        .addParam("CompanyName", company == null ? "" : nz(company.getName()))
        // ⚠️ registrationCode, НЕ taxNbr — т. 1 иска ЕИК по БУЛСТАТ.
        .addParam("CompanyIdNumber", partner == null ? "" : nz(partner.getRegistrationCode()))
        .addParam("PeriodLabel", periodLabel(declaration.getBgPeriod()))
        .addParam("DeclarantNames", declarantNames(declaration))
        .addParam("RefusalReport", reports(declaration))
        .addParam("GenDate", LocalDate.now().format(DAY))
        .addParam("__locale", ReportSettings.getPrintingLocale(null))
        .addFormat(format)
        .toAttach(declaration)
        .generate()
        .getFileLink();
  }

  /** „ММ.ГГГГ“ — както се чете от човек, а не както се сериализира. */
  protected String periodLabel(Period period) {
    LocalDate from = period == null ? null : period.getFromDate();
    return from == null ? "" : String.format("%02d.%d", from.getMonthValue(), from.getYear());
  }

  /**
   * Двата доклада, слепени — отказите ПЪРВИ.
   *
   * <p>⚠️ РЕДЪТ НЕ Е ПРОИЗВОЛЕН: онова, което НЕ е влязло, е по-опасно от онова, което е. Същият
   * избор като разположението на панелите на екрана.
   */
  protected String reports(BgD6Declaration declaration) {
    String refusals = nz(declaration.getBgRefusalReport());
    String findings = nz(declaration.getBgValidationReport());
    if (refusals.isEmpty()) {
      return findings;
    }
    if (findings.isEmpty()) {
      return refusals;
    }
    return refusals + "\n" + findings;
  }

  protected String declarantNames(BgD6Declaration declaration) {
    return declaration.getBgDeclarant() == null
        ? ""
        : nz(declaration.getBgDeclarant().getDeclarantNames());
  }

  protected static String nz(String value) {
    return value == null ? "" : value;
  }
}
