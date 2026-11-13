package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgPayrollLedgerWizard;
import com.axelor.apps.bglocalization.report.IReport;
import com.axelor.apps.bglocalization.service.payroll.ledger.BgPayrollLedger;
import com.axelor.apps.bglocalization.service.payroll.ledger.BgPayrollLedgerPrintService;
import com.axelor.apps.bglocalization.service.payroll.ledger.BgPayrollLedgerSelection;
import com.axelor.apps.bglocalization.service.payroll.ledger.BgPayrollLedgerService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * ЕКРАНЪТ НА ВЕДОМОСТТА — два бутона върху един период.
 *
 * <p>Тънък слой нарочно: нито едно правило не живее тук. Разбиването е в {@code
 * BgPayrollLedgerVariant}, изборът — в {@code BgPayrollLedgerSelection}, обходът — в {@code
 * BgPayrollLedgerService}, числата — в {@code BgPayrollLedgerRow}.
 *
 * <p><b>⚠️ „ПРЕБРОИ“ Е ОТДЕЛЕН БУТОН ОТ „ПЕЧАТАЙ“, И ТОВА Е РЕШЕНИЕ НА ПРОДУКТА.</b> Печатът ражда
 * PDF и го отваря; преброяването отговаря на въпроса, който се задава ПРЕДИ печата — колко души има
 * в кой разрез, има ли някой извън четирите на образеца и има ли ред, чиито пера не се сверяват със
 * заглавните му суми. Слети в един бутон, тези отговори щяха да се четат едва след като листът
 * излезе, тоест след като е бил отпечатан грешен.
 *
 * <p><b>⚠️ РАБОТИ ВЪРХУ КОНТЕКСТА НА ФОРМАТА, БЕЗ {@code save}</b> — wizard-ът е преходен, по
 * образеца на {@code BgPayrollSepaController}.
 */
@Singleton
public class BgPayrollLedgerController {

  /**
   * ПРЕБРОЯВАНЕТО — без да се ражда файл.
   *
   * <p>⚠️ ПРЕБРОЯВАНЕТО Е НА ЦЕЛИЯ ПЕРИОД, а не на избрания разрез, и точно затова е полезно:
   * въпросът „кого не виждам на този лист“ няма отговор от самия лист.
   */
  public void count(ActionRequest request, ActionResponse response) {
    try {
      BgPayrollLedgerWizard form = request.getContext().asType(BgPayrollLedgerWizard.class);
      CapabilityGate.requireRole("view the payroll ledger", Capabilities.PAYROLL_READ);
      CapabilityGate.requireActiveCompany(form.getCompany());
      BgPayrollLedgerSelection selection = BgPayrollLedgerSelection.of(form.getVariantSelect());
      BgPayrollLedger ledger =
          Beans.get(BgPayrollLedgerService.class).build(form.getCompany(), form.getPeriod());

      response.setValue("resultSummary", ledger.boundaryReport(selection));
    } catch (Exception e) {
      response.setValue("resultSummary", null);
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ПЕЧАТЪТ — PDF-ът се ражда и се отваря.
   *
   * <p>⚠️ И ПРАЗНАТА ВЕДОМОСТ СЕ ПЕЧАТА. Разрез без лица е законно състояние (месец без граждански
   * договори), а отказ „няма редове“ би оставил човека без лист, на който да се види, че е гледано.
   * Листът излиза с нула реда, нули на реда „Общо“ и с преброяването на целия период отдолу.
   *
   * <p>⚠️ И ДОКЛАДЪТ ЗА ГРАНИЦИТЕ СЕ ПЪЛНИ И ПРИ ПЕЧАТ, а не само при преброяване: човекът, който е
   * натиснал направо „Печатай“, трябва да получи същия отговор на екрана, какъвто носи и хартията.
   */
  public void print(ActionRequest request, ActionResponse response) {
    try {
      BgPayrollLedgerWizard form = request.getContext().asType(BgPayrollLedgerWizard.class);
      CapabilityGate.requireRole("print the payroll ledger", Capabilities.PAYROLL_READ);
      CapabilityGate.requireActiveCompany(form.getCompany());
      BgPayrollLedgerSelection selection = BgPayrollLedgerSelection.of(form.getVariantSelect());
      BgPayrollLedger ledger =
          Beans.get(BgPayrollLedgerService.class).build(form.getCompany(), form.getPeriod());

      String fileLink =
          Beans.get(BgPayrollLedgerPrintService.class)
              .print(
                  ledger,
                  selection,
                  form.getCompany(),
                  form.getPeriod(),
                  IReport.BG_PAYROLL_LEDGER,
                  ReportSettings.FORMAT_PDF,
                  "Ведомост");

      response.setValue("resultSummary", ledger.boundaryReport(selection));
      response.setView(
          ActionView.define("Разчетно-платежна ведомост")
              .add("html", fileLink)
              .param("download", "false")
              .map());
    } catch (Exception e) {
      response.setValue("resultSummary", null);
      TraceBackService.trace(response, e);
    }
  }
}
