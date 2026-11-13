package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.report.engine.ReportSettings;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Renders the persisted VAT diary snapshot ({@link
 * com.axelor.apps.bglocalization.db.BgVatSalesLine} / {@link
 * com.axelor.apps.bglocalization.db.BgVatPurchaseLine}) and the declaration cells (on {@link
 * BgVatReturn}) as BIRT reports laid out 1:1 against the official НАП образци. Because the
 * rows/cells are the frozen output of the generate() run, the reports show exactly the filed TXT
 * numbers (report == TXT). The same .rptdesign is emitted as PDF or XLSX via the format flag.
 */
@Singleton
public class BgVatReportPrintService {

  /**
   * Build a report link for the given return.
   *
   * @param rptdesign logical report name (see {@link
   *     com.axelor.apps.bglocalization.report.IReport})
   * @param format {@link ReportSettings#FORMAT_PDF} or {@link ReportSettings#FORMAT_XLSX}
   * @param outputBaseName output file base name (without extension)
   */
  public String print(
      BgVatReturn bgVatReturn, String rptdesign, String format, String outputBaseName)
      throws AxelorException {
    Company company = bgVatReturn.getCompany();
    String companyName = company != null ? company.getName() : "";
    Partner partner = company != null ? company.getPartner() : null;
    String vatNumber = partner != null && partner.getTaxNbr() != null ? partner.getTaxNbr() : "";
    // ИН = ЕИК без "BG" префикс.
    String idNumber = vatNumber.toUpperCase().startsWith("BG") ? vatNumber.substring(2) : vatNumber;

    return ReportFactory.createReport(rptdesign, outputBaseName + "-${date}")
        .addParam("BgVatReturnId", bgVatReturn.getId())
        .addParam("CompanyName", companyName)
        .addParam("CompanyVatNumber", vatNumber)
        .addParam("CompanyIdNumber", idNumber)
        .addParam("CompanyAddress", companyAddress(bgVatReturn))
        .addParam("PeriodLabel", formatPeriod(bgVatReturn.getPeriod()))
        .addParam("DeclarantNames", nz(bgVatReturn.getBgDeclarantNames()))
        .addParam("DeclarantCapacity", capacityLabel(bgVatReturn.getBgDeclarantCapacity()))
        // „Длъжност“ в подписа на Прил. № 13 — длъжността на декларатора, не кодът на „Качество“.
        .addParam("DeclarantPosition", nz(bgVatReturn.getBgDeclarantPosition()))
        .addParam("GenDate", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
        .addParam("__locale", ReportSettings.getPrintingLocale(null))
        .addFormat(format)
        .toAttach(bgVatReturn)
        .generate()
        .getFileLink();
  }

  /**
   * АДРЕСЪТ ЗА КОРЕСПОНДЕНЦИЯ НА РЕГИСТРИРАНОТО ЛИЦЕ (секция А) — снимката, а ако я няма,
   * правилото.
   *
   * <p>⚠️ РЕДЪТ НЕ Е ПРОИЗВОЛЕН. Първо е {@code bgVatReturn.companyAddress} — снимката, направена
   * при генерирането. Този отчет твърди „report == TXT“, тоест показва подаденото, а не днешното:
   * преместило ли се е дружеството след подаването, разпечатката трябва да носи адреса от файла.
   *
   * <p>Без снимка — {@link BgVatRegisteredAddress}, ЕДИНСТВЕНОТО правило за ДДС („Адрес за
   * кореспонденция“ на дружеството, иначе „Адрес“). ⚠️ Партньорът на дружеството вече НЕ се чете:
   * дотук печатът го предпочиташе и тихо падаше към „Адрес“, а VIES файлът четеше само партньора —
   * два отговора на един въпрос. Без адрес — празно, а не изключение: отчетът пак се рендира.
   */
  protected String companyAddress(BgVatReturn bgVatReturn) {
    String snapshot = nz(bgVatReturn.getCompanyAddress()).trim();
    if (!snapshot.isEmpty()) {
      return snapshot;
    }
    return liveAddress(bgVatReturn.getCompany());
  }

  /** Адресът по действащото правило — {@link BgVatRegisteredAddress}; празно, ако няма. */
  protected String liveAddress(Company company) {
    return nz(BgVatRegisteredAddress.of(company));
  }

  /** "ГГГГММ" → "ММ.ГГГГ" (as printed on the НАП образец, e.g. 202607 → 07.2026). */
  protected String formatPeriod(String period) {
    if (period != null && period.matches("\\d{6}")) {
      return period.substring(4, 6) + "." + period.substring(0, 4);
    }
    return period == null ? "" : period;
  }

  /** VIES declarant-capacity code (A/R) → label; free text otherwise. */
  protected String capacityLabel(String code) {
    if ("A".equalsIgnoreCase(code)) {
      return "Управител";
    }
    if ("R".equalsIgnoreCase(code)) {
      return "Представител";
    }
    return nz(code);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
