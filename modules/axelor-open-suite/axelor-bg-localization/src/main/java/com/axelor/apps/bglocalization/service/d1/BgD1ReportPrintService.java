package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.report.engine.ReportSettings;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ПЕЧАТНИЯТ ОБРАЗЕЦ на Декларация обр. № 1 — по образеца на {@code BgVatReportPrintService}.
 *
 * <p><b>⚠️ ЗАЩО ПЕЧАТ ИМА, А ФАЙЛ ЗА НАП НЯМА.</b> Не е компромис и не е „докато чакаме“. Чл. 8 ал.
 * 1 т. 1 разрешава декларацията да се подава <b>на хартиен носител</b>; чл. 8 ал. 5 казва, че
 * структурата и форматът на ЕЛЕКТРОННИЯ носител се утвърждават със заповед на изпълнителния
 * директор на НАП. Тоест хартията е уреден път, а електронният чака документ, който още го няма.
 * Печатът е ПЪЛНОЦЕНЕН изход на Фаза 1, не заместител.
 *
 * <p><b>⚠️ И ЕДНА РАЗЛИКА ОТ ДДС-ПЕЧАТА, КОЯТО НЕ Е КОЗМЕТИЧНА.</b> ДДС-печатът взима номера на
 * дружеството от {@code partner.taxNbr} и маха префикса „BG“ — за ДДС това е вярното. Тук се чете
 * {@code registrationCode}: т. 4 иска ЕИК по БУЛСТАТ/ЗТРРЮЛНЦ. Копиране на ДДС-реда би сложило
 * номера по ЗДДС на хартия, подписана от управителя.
 */
@Singleton
public class BgD1ReportPrintService {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * Връща връзка към отпечатаната декларация.
   *
   * @param rptdesign логическото име на отчета (виж {@code IReport})
   * @param format {@link ReportSettings#FORMAT_PDF} или {@link ReportSettings#FORMAT_XLSX}
   */
  public String print(
      BgD1Declaration declaration, String rptdesign, String format, String outputBaseName)
      throws AxelorException {

    ReportSettings report = ReportFactory.createReport(rptdesign, outputBaseName + "-${date}");
    params(declaration).forEach(report::addParam);
    return report
        .addParam("__locale", ReportSettings.getPrintingLocale(null))
        .addFormat(format)
        .toAttach(declaration)
        .generate()
        .getFileLink();
  }

  /**
   * Параметрите на образеца — точно това, което бутонът „Печат (PDF)“ подава на BIRT.
   *
   * <p>⚠️ Отделени, за да ги ползва и свидетелят ({@code BgD1PrintPdfPostgresIntegrationTest}):
   * празен низ тук (доклад без откази, дружество без ЕИК, декларация без декларатор) е НОРМАЛНО
   * състояние, а образецът трябва да го приеме — иначе празният {@code RefusalReport} поваля целия
   * печат с „Required parameter RefusalReport is blank“.
   */
  public Map<String, Object> params(BgD1Declaration declaration) {
    Company company = declaration.getCompany();
    Partner partner = company == null ? null : company.getPartner();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("BgD1DeclarationId", declaration.getId());
    params.put("CompanyName", company == null ? "" : nz(company.getName()));
    // ⚠️ registrationCode, НЕ taxNbr — т. 4 иска ЕИК по БУЛСТАТ.
    params.put("CompanyIdNumber", partner == null ? "" : nz(partner.getRegistrationCode()));
    params.put("PeriodLabel", periodLabel(declaration.getBgDataPeriod()));
    params.put("SubmissionPeriodLabel", periodLabel(declaration.getBgSubmissionPeriod()));
    params.put("DeclarantNames", declarantNames(declaration));
    params.put("RefusalReport", nz(declaration.getBgRefusalReport()));
    params.put("GenDate", LocalDate.now().format(DAY));
    return params;
  }

  /** „ММ.ГГГГ“ — както се чете от човек, а не както се сериализира. */
  protected String periodLabel(Period period) {
    LocalDate from = period == null ? null : period.getFromDate();
    return from == null ? "" : String.format("%02d.%d", from.getMonthValue(), from.getYear());
  }

  protected String declarantNames(BgD1Declaration declaration) {
    return declaration.getBgDeclarant() == null
        ? ""
        : nz(declaration.getBgDeclarant().getDeclarantNames());
  }

  protected static String nz(String value) {
    return value == null ? "" : value;
  }
}
