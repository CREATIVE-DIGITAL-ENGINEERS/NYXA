package com.axelor.apps.bglocalization.service.payroll.ledger;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.report.engine.ReportSettings;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ПЕЧАТНАТА ВЕДОМОСТ — по образеца на {@code BgD1ReportPrintService} и {@code
 * BgPaySlipPrintService}.
 *
 * <p><b>⚠️ КОИ РЕДОВЕ ИЗЛИЗАТ, СЕ РЕШАВА В JAVA И СЕ ПОДАВА КАТО СПИСЪК ОТ НОМЕРА.</b> Заявката на
 * образеца НЕ повтаря нито разбиването по вид правоотношение, нито избора от екрана: тя чете точно
 * фишовете, чиито номера получава. Това е разликата между отчет, който показва каквото е преброено,
 * и отчет, който брои сам — второто дава ДВЕ формулировки на едно правило (веднъж в {@link
 * BgPayrollLedgerVariant}, веднъж в SQL), а две формулировки се разминават тихо. Тук разминаване
 * няма къде да се появи: списъкът е един.
 *
 * <p><b>⚠️ И РЕДЪТ ИДВА СЪС СПИСЪКА, А НЕ СЕ ПОДРЕЖДА В SQL.</b> {@code ORDER BY} в Postgres
 * подрежда по колацията на базата — свойство на машината, не на данните. Затова заявката подрежда
 * по ПОЗИЦИЯТА в подадения списък ({@code array_position}), а самият списък е подреден от {@link
 * BgPayrollLedger} с български {@code Collator}.
 *
 * <p><b>⚠️ ЗАЩО СУМИТЕ СЕ ПОДАВАТ ВТОРИ ПЪТ, СЛЕД КАТО ОБРАЗЕЦЪТ ГИ СЪБИРА САМ.</b> Не се подават
 * вместо реда „Общо“ — той си остава СБОР НА ОТПЕЧАТАНИТЕ РЕДОВЕ и не може да не е. Подават се
 * НАРЕД с него, като ВТОРИ ред, сметнат по друг път: заглавните суми на фишовете, минали през
 * {@code BgPaySlipResolver}. Двата реда отговарят на два различни въпроса — „събират ли се редовете
 * на този лист“ и „това ли казват фишовете“ — и докато съвпадат, отговорът и на двата е „да“.
 * Единственият начин разликата между тях да остане скрита е да не се отпечата.
 */
@Singleton
public class BgPayrollLedgerPrintService {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * Връща връзка към отпечатаната ведомост.
   *
   * @param rptdesign логическото име на отчета (виж {@code IReport#BG_PAYROLL_LEDGER})
   * @param format {@link ReportSettings#FORMAT_PDF} или {@link ReportSettings#FORMAT_XLSX}
   */
  public String print(
      BgPayrollLedger ledger,
      BgPayrollLedgerSelection selection,
      Company company,
      Period period,
      String rptdesign,
      String format,
      String outputBaseName)
      throws AxelorException {

    Partner partner = company == null ? null : company.getPartner();
    BgPayrollLedgerTotals totals = ledger.totals(selection);

    return ReportFactory.createReport(rptdesign, outputBaseName + "-${date}")
        .addParam("SlipIds", slipIds(ledger, selection))
        // ⚠️ Вторият е СЪЩИЯТ низ: заявката го чете веднъж за филтъра и веднъж за подредбата.
        // Един параметър, ползван на две места, не може да се разсинхронизира; два биха могли.
        .addParam("SlipIdsOrder", slipIds(ledger, selection))
        .addParam("CompanyName", company == null ? "" : nz(company.getName()))
        // ⚠️ registrationCode, НЕ taxNbr — ведомостта е документ на предприятието по ЕИК, а не по
        // номер по ЗДДС; същият избор като при Д1.
        .addParam("CompanyIdNumber", partner == null ? "" : nz(partner.getRegistrationCode()))
        .addParam("PeriodLabel", periodLabel(period))
        .addParam("VariantLabel", selection.getLabel())
        .addParam("RowCount", String.valueOf(totals.getRowCount()))
        .addParam("CheckGross", totals.getGross())
        .addParam("CheckEmployeeContributions", totals.getEmployeeContributions())
        .addParam("CheckTax", totals.getTax())
        .addParam("CheckAdvance", totals.getAdvance())
        .addParam("CheckDeductions", totals.getDeductions())
        .addParam("CheckNet", totals.getNet())
        .addParam("CheckEmployerContributions", totals.getEmployerContributions())
        .addParam("CheckEmployerCost", totals.getEmployerCost())
        .addParam("BoundaryReport", forPaper(ledger.boundaryReport(selection)))
        .addParam("GenDate", LocalDate.now().format(DAY))
        .addParam("__locale", ReportSettings.getPrintingLocale(null))
        .addFormat(format)
        // ⚠️ БЕЗ toAttach, ЗА РАЗЛИКА ОТ ФИША И ОТ Д1/Д6, И ТОВА НЕ Е ПРОПУСК. Там листът се закача
        // за СВОЯ запис — фиш, декларация — и оттам нататък разпечатката и носителят ѝ се отварят
        // заедно. Ведомостта няма такъв запис: тя е изглед към период, а екранът, от който се
        // пуска, е преходен и не се записва. Закачена за него, тя щеше да виси на ред, който
        // изчезва при затваряне на формата.
        .generate()
        .getFileLink();
  }

  /**
   * ДОКЛАДЪТ, ПРЕВЕДЕН ЗА ХАРТИЯТА — две замени, и двете ИЗМЕРЕНИ при рендиране.
   *
   * <p><b>⚠️ ЗНАКЪТ „ПРЕДУПРЕЖДЕНИЕ“ (U+26A0) НЯМА ГЛИФ В ШРИФТА НА PDF-ЕМИТЕРА И ИЗЧЕЗВА БЕЗ
   * ГРЕШКА.</b> Върху екрана той е верният знак и остава там; върху хартията оставяше само ВОДЕЩ
   * ИНТЕРВАЛ — тоест изречението губеше точно онова, което го прави забележимо.
   *
   * <p><b>⚠️ И НОВИЯТ РЕД СЕ ПРЕВРЪЩА ТУК, А НЕ В ОБРАЗЕЦА.</b> Текстът излиза през {@code
   * <value-of format="html">}, тоест новите редове трябва да са {@code <br/>}. Направено в
   * JavaScript вътре в образеца, това иска или ъглова скоба вътре в CDATA (която HTML-разборът на
   * BIRT изяжда за таг), или XML entity (което в CDATA стига до Rhino ДОСЛОВНО и поваля целия отчет
   * с „missing ) in parenthetical“). В Java няма нито един от двата въпроса.
   *
   * <p>И двете замени са само по пътя към хартията, а не в {@code BgPayrollLedger}: докладът е
   * един, а носителите са два.
   */
  protected String forPaper(String report) {
    if (report == null) {
      return "";
    }
    return report.replace("⚠️", "(!)").replace("⚠", "(!)").replace("\n", "<br/>");
  }

  /**
   * Номерата на фишовете, разделени със запетая, В РЕДА НА ХАРТИЯТА.
   *
   * <p>⚠️ ПРАЗНАТА ВЕДОМОСТ ДАВА ПРАЗЕН НИЗ, И КАКВО ПРАВИ ТОЙ Е ИЗМЕРЕНО, А НЕ ПОДРАЗБИРАНО:
   * {@code string_to_array('', ',')} в Postgres връща ПРАЗЕН МАСИВ ({@code {}}), тоест {@code
   * id::varchar = any(...)} не намира нищо и отчетът излиза с нула реда и нули на реда „Общо“. Това
   * е верният изход, а не празен лист по погрешка: период без лица в избрания разрез е законно
   * състояние (ведомостта на гражданските договори в месец без граждански договори) и листът трябва
   * да се отпечата, за да се види, че е гледан. Границата е записана и в {@code
   * BgPayrollLedgerDesignTest}, защото поведението на празния низ е на БАЗАТА, не на този код.
   */
  protected String slipIds(BgPayrollLedger ledger, BgPayrollLedgerSelection selection) {
    List<Long> ids = ledger.slipIds(selection);
    return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
  }

  /** „ММ.ГГГГ“ — както се чете от човек, а не както се сериализира. */
  protected String periodLabel(Period period) {
    LocalDate from = period == null ? null : period.getFromDate();
    return from == null ? "" : String.format("%02d.%d", from.getMonthValue(), from.getYear());
  }

  protected static String nz(String value) {
    return value == null ? "" : value;
  }
}
