package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgFatcaReport;
import com.axelor.apps.bglocalization.db.repo.BgFatcaReportRepository;
import com.axelor.apps.bglocalization.service.fatca.BgFatcaReportService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.time.LocalDate;

/**
 * Екранът на FATCA отчета — проверка при запис, файл, връщане в чернова и ръчното подаване.
 *
 * <p>Тънък слой: правилата са в {@code BgFatcaFileValidator} и {@code BgFatcaReportService}. Всяко
 * действие минава през гейта: {@code accounting.nap.write} + запис върху реда + активното дружество
 * на ЗАПИСАНИЯ отчет.
 */
@Singleton
public class BgFatcaReportController {

  /** При запис: отчет има само финансова институция за отчитане. */
  public void checkCompany(ActionRequest request, ActionResponse response) {
    try {
      BgFatcaReport form = request.getContext().asType(BgFatcaReport.class);
      CapabilityGate.requireRole("save a FATCA report", Capabilities.NAP_WRITE);
      CapabilityGate.requireActiveCompany(form.getCompany());
      Beans.get(BgFatcaReportService.class).requireReportingFi(form.getCompany());
    } catch (IllegalStateException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Генерира файла — или казва всички причини, поради които не може. */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgFatcaReport report = writable(request, response, "generate a FATCA report file");
      if (report == null) {
        return;
      }
      report = Beans.get(BgFatcaReportService.class).generate(report, LocalDate.now());
      response.setNotify(
          (report.getFileAccountCount() == 0
                  ? "Генериран е НУЛЕВ отчет (NilReport) — няма сметки за докладване."
                  : "Генериран е файл с " + report.getFileAccountCount() + " сметки.")
              + " Свали го и го провери, преди да го качиш на портала. Нищо не е изпратено"
              + " към НАП.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Забравя генерирания файл, за да се поправят сметките. Само преди подаване. */
  public void returnToDraft(ActionRequest request, ActionResponse response) {
    try {
      BgFatcaReport report = writable(request, response, "return a FATCA report to draft");
      if (report == null) {
        return;
      }
      Beans.get(BgFatcaReportService.class).returnToDraft(report);
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Сваля генерирания файл — прецедентът е справката по чл. 73. */
  public void downloadFile(ActionRequest request, ActionResponse response) {
    try {
      BgFatcaReport report = managed(request, response);
      if (report == null) {
        return;
      }
      CapabilityGate.requireRead(BgFatcaReport.class, report.getId());
      CapabilityGate.requireActiveCompany(report.getCompany());
      MetaFile metaFile = report.getGeneratedFile();
      if (metaFile == null) {
        response.setInfo("Няма генериран файл за изтегляне.");
        return;
      }
      response.setView(
          ActionView.define(metaFile.getFileName())
              .add(
                  "html",
                  "ws/rest/com.axelor.meta.db.MetaFile/"
                      + metaFile.getId()
                      + "/content/download?v="
                      + metaFile.getVersion())
              .param("download", "true")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Отбелязва отчета като подаден през портала — с входящия номер и датата от формата (незаписани
   * стойности на екрана). НИЩО не се изпраща към НАП.
   */
  public void markSubmitted(ActionRequest request, ActionResponse response) {
    try {
      BgFatcaReport form = request.getContext().asType(BgFatcaReport.class);
      BgFatcaReport report = writable(request, response, "mark a FATCA report as submitted");
      if (report == null) {
        return;
      }
      Beans.get(BgFatcaReportService.class)
          .markSubmitted(
              report, form.getNapIncomingNumber(), form.getNapIncomingDate(), LocalDate.now());
      response.setNotify(
          "Отбелязано като подадено през портала; файлът е закачен към подаването. Нищо не е"
              + " изпратено към НАП.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Записаният отчет, след гейта за запис — или {@code null} с обяснение. */
  protected BgFatcaReport writable(ActionRequest request, ActionResponse response, String action) {
    BgFatcaReport report = managed(request, response);
    if (report == null) {
      return null;
    }
    CapabilityGate.requireRole(action, Capabilities.NAP_WRITE);
    CapabilityGate.requireActiveCompany(report.getCompany());
    CapabilityGate.requireWrite(BgFatcaReport.class, report.getId());
    return report;
  }

  /** Записаният отчет по id — или обяснение вместо мълчание. */
  protected BgFatcaReport managed(ActionRequest request, ActionResponse response) {
    BgFatcaReport form = request.getContext().asType(BgFatcaReport.class);
    if (form.getId() == null) {
      response.setInfo("Запиши отчета (компания и година), преди да продължиш.");
      return null;
    }
    BgFatcaReport report = Beans.get(BgFatcaReportRepository.class).find(form.getId());
    if (report == null) {
      response.setInfo("Отчетът вече не съществува — презареди екрана.");
    }
    return report;
  }
}
