package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.d1.BgD1Carrier;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.report.engine.ReportSettings;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * ПЕЧАТНИЯТ ФИШ — по образеца на {@code BgD1ReportPrintService}.
 *
 * <p><b>⚠️ ОТЧЕТЪТ НЕ СМЯТА.</b> Шестте заглавни суми (бруто, осигурителен доход, данъчна основа,
 * данък, нето, разход за работодателя) и всяко перо се четат ПРЯКО от реда на фиша — заявките на
 * образеца ги вадят от {@code bglocalization_bg_pay_slip} и {@code bglocalization_bg_pay_slip_line}
 * и нищо между тях не ги пипа. Единственото, което тази услуга добавя, е РАЗДЕЛЯНЕТО на вноските на
 * две страни и докладът за сверката — и двете през {@link BgPaySlipPrintSummary}, тоест през същите
 * функции на {@code BgPaySlipResolver}, които пълнят заглавната част. Числото на хартията няма
 * втори източник.
 *
 * <p><b>⚠️ ЛИЧНИТЕ ДАННИ ИДВАТ КАТО ПАРАМЕТРИ, А НЕ ПРЕЗ SQL JOIN, и това не е удобство.</b> Името,
 * ЕГН-то, длъжността и дружеството живеят в ЧУЖДИ таблици (hr, base). Заявка, която ги събира, не
 * може да се провери от {@code BgReportQueryColumnTest} — той извежда имената на колоните от
 * домейните на ТОЗИ модул и мълчи за всичко останало. Тоест join щеше да върне точно онзи вид
 * грешка, която образецът не пада при компилация, а при натискане на „Печат“. През параметри
 * връзката минава през генерирани getter-и и я пази компилаторът.
 *
 * <p><b>⚠️ И НОСИТЕЛЯТ СЕ ПИТА ПРЕЗ {@link BgD1Carrier}, А НЕ СЕ ЧЕТЕ ОТ ФИША.</b> Фишът има ДВЕ
 * взаимно изключващи се страни — трудов договор и регистрация на самоосигуряващ се. Пряко четене на
 * {@code getEmploymentContract()} би дало празна шапка за всеки фиш на СОЛ, без нито една причина
 * върху хартията.
 */
@Singleton
public class BgPaySlipPrintService {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * Връща връзка към отпечатания фиш.
   *
   * @param rptdesign логическото име на отчета (виж {@code IReport#BG_PAY_SLIP})
   * @param format {@link ReportSettings#FORMAT_PDF} или {@link ReportSettings#FORMAT_XLSX}
   */
  public String print(BgPaySlip slip, String rptdesign, String format, String outputBaseName)
      throws AxelorException {

    BgD1Carrier carrier = BgD1Carrier.of(slip);
    Company company = carrier.company();
    Employee employee = carrier.employee();
    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    return ReportFactory.createReport(rptdesign, outputBaseName + "-${date}")
        .addParam("BgPaySlipId", slip.getId())
        .addParam("PayerName", payerName(company))
        .addParam("PayerIdNumber", nz(carrier.obligedPersonCode()))
        .addParam("EmployeeNames", employeeNames(employee))
        .addParam("EmployeePersonalId", personalId(employee))
        .addParam("JobTitle", jobTitle(carrier))
        .addParam("InsuredTypeCode", nz(carrier.insuredTypeCode()))
        .addParam("PeriodLabel", periodLabel(slip.getPeriod()))
        .addParam("StatusLabel", statusLabel(slip.getStatusSelect()))
        .addParam("SlipKind", kindLabel(slip))
        .addParam("EmployeeContributions", summary.getEmployeeContributions())
        .addParam("EmployerContributions", summary.getEmployerContributions())
        .addParam("ReconciliationReport", summary.report())
        .addParam("GenDate", LocalDate.now().format(DAY))
        .addParam("__locale", ReportSettings.getPrintingLocale(null))
        .addFormat(format)
        .toAttach(slip)
        .generate()
        .getFileLink();
  }

  /**
   * ⚠️ ПРАЗНО ПРИ САМООСИГУРЯВАЩ СЕ, И ТОВА НЕ Е ЛИПСА: дружество-работодател там няма. Кодът на
   * задълженото лице обаче ГО ИМА (въведен е на регистрацията) и излиза до него — тоест хартията
   * казва кой дължи, без да измисля работодател.
   */
  protected String payerName(Company company) {
    return company == null ? "" : nz(company.getName());
  }

  /** Трите имена, както ги носи партньорът на служителя: собствено, презиме, фамилия. */
  protected String employeeNames(Employee employee) {
    Partner person = employee == null ? null : employee.getContactPartner();
    if (person == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    append(out, person.getFirstName());
    append(out, person.getBgMiddleName());
    append(out, person.getName());
    return out.toString();
  }

  /** „ЕГН 8501014567“ — идентификаторът СЪС своя вид, защото ЛНЧ се чете различно. */
  protected String personalId(Employee employee) {
    // ⚠️ Видът е КОД („1“/„2“) — думата я дава BgPersonalIdLabel, не „1 7501011016“.
    return BgPersonalIdLabel.of(employee);
  }

  /** Длъжността по НКПД: „12345678 · Икономист“, или празно, когато договорът не я носи. */
  protected String jobTitle(BgD1Carrier carrier) {
    BgNkpd nkpd = carrier.getContract() == null ? null : carrier.getContract().getBgNkpd();
    if (nkpd == null) {
      return "";
    }
    String code = nz(nkpd.getCode());
    String name = nz(nkpd.getName());
    if (code.isEmpty()) {
      return name;
    }
    return name.isEmpty() ? code : code + " · " + name;
  }

  /** „ММ.ГГГГ“ — както се чете от човек, а не както се сериализира. */
  protected String periodLabel(Period period) {
    LocalDate from = period == null ? null : period.getFromDate();
    return from == null ? "" : String.format("%02d.%d", from.getMonthValue(), from.getYear());
  }

  /**
   * ⚠️ СТАТУСЪТ СЕ ПЕЧАТА. Разпечатка на ЧЕРНОВА изглежда точно като разпечатка на потвърден фиш —
   * а черновата се мени. Без този надпис лист хартия от понеделник спори с екрана от вторник и нищо
   * върху него не казва кой от двата е бил верен.
   */
  protected String statusLabel(String status) {
    if (status == null) {
      return "";
    }
    switch (status) {
      case "DRAFT":
        return "ЧЕРНОВА";
      case "CONFIRMED":
        return "Потвърден";
      case "PAID":
        return "Изплатен";
      case "CANCELLED":
        return "ОТКАЗАН";
      default:
        return status;
    }
  }

  /**
   * ⚠️ КОРИГИРАЩИЯТ И ЗАЛИЧАВАЩИЯТ ФИШ НЕ СЕ ИЗПЛАЩАТ и затова се назовават върху хартията. Техните
   * числа са пълните верни числа за периода, но към лицето отива само нетна разлика — в ДРУГ фиш.
   * Разпечатка без този надпис е листът, на който човек чете нето, което няма да получи.
   */
  protected String kindLabel(BgPaySlip slip) {
    if (Boolean.TRUE.equals(slip.getBgIsDeletion())) {
      return "ЗАЛИЧАВАЩ ФИШ — не се изплаща. Към лицето се връща цялото нето на оригинала, с"
          + " отделно перо в текущия отворен фиш.";
    }
    if (Boolean.TRUE.equals(slip.getBgIsCorrection())) {
      return "КОРИГИРАЩ ФИШ — не се изплаща. Към лицето отива само нетната разлика, с отделно перо"
          + " в текущия отворен фиш.";
    }
    return "";
  }

  private static void append(StringBuilder out, String part) {
    if (part == null || part.trim().isEmpty()) {
      return;
    }
    if (out.length() > 0) {
      out.append(' ');
    }
    out.append(part.trim());
  }

  protected static String nz(String value) {
    return value == null ? "" : value;
  }
}
