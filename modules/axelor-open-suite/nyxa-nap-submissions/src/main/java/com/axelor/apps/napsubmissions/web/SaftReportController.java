package com.axelor.apps.napsubmissions.web;

import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftCheckResult;
import com.axelor.apps.napsubmissions.services.saft.SaftNapProtocol;
import com.axelor.apps.napsubmissions.services.saft.SaftReportWorkflowService;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.List;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * Екранът на месечния SAF-T файл (G6b). Тънък: всеки бутон е едно действие на {@link
 * SaftReportWorkflowService}; отчетът се чете от базата по id, никога от формата.
 *
 * <p><b>Гейтът</b> : способността за подаване по вида на документа ({@link
 * NapFilingGate#capabilityFor} — за SAF-T {@code accounting.nap.write}, в която са {@code
 * all.write} и {@code client.admin}) + активното дружество на записа + право за запис върху него. С
 * {@code .read} — само преглед и „Свали за портала“.
 */
@Singleton
public class SaftReportController {

  private static final String OUTCOME = "record an НАП outcome";
  private static final String PROTOCOL = "attach an НАП response";

  /** „Провери“: находките с линк към записа; READY само без ГРЕШКА. */
  public void validate(ActionRequest request, ActionResponse response) {
    try {
      SaftReport report = writable(request, "check a SAF-T report");
      SaftCheckResult result =
          Beans.get(SaftReportWorkflowService.class).validateReport(report, today(report));
      response.setReload(true);
      notify(response, result, I18n.get("Проверката мина без грешки."), "");
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Генерирай“: отказ при поне една ГРЕШКА; иначе нов неизменен файл. */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      SaftReport report = writable(request, "generate a SAF-T file");
      SaftCheckResult result =
          Beans.get(SaftReportWorkflowService.class).generate(report, today(report));
      response.setReload(true);
      notify(
          response,
          result,
          String.format(
              I18n.get("Файлът %s е генериран (S.H.1 %s)."),
              result.getFileName(),
              result.getAuditFileVersion()),
          " " + I18n.get("Файл не е генериран."));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * „Свали за портала“ — и с {@code .read}: последният генериран файл, ако е по версията за днес.
   */
  public void download(ActionRequest request, ActionResponse response) {
    try {
      SaftReport report = managed(request);
      CapabilityGate.requireActiveCompany(report.getCompany());
      CapabilityGate.requireRead(SaftReport.class, report.getId());
      NapArtifact artifact =
          Beans.get(SaftReportWorkflowService.class).fileForPortal(report, today(report));
      CapabilityGate.requireRead(NapArtifact.class, artifact.getId());
      MetaFile file = artifact.getMetaFile();
      response.setView(
          ActionView.define(file.getFileName())
              .add(
                  "html",
                  "ws/rest/com.axelor.meta.db.MetaFile/"
                      + file.getId()
                      + "/content/download?v="
                      + file.getVersion())
              .param("download", "true")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Отбележи подаден“: вх. № и датата от портала за генерирания файл. */
  public void recordFiling(ActionRequest request, ActionResponse response) {
    try {
      SaftReport ctx = request.getContext().asType(SaftReport.class);
      SaftReport report = writable(request, "record a manual НАП submission");
      CapabilityGate.requireWrite(NapSubmission.class, null);
      Beans.get(SaftReportWorkflowService.class)
          .recordPortalFiling(report, ctx.getIncomingNumberInput(), ctx.getPortalDateInput());
      response.setValue("incomingNumberInput", null);
      response.setValue("portalDateInput", null);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Приет“ — за опита, който чака изход; заключва месеца. */
  public void recordAccepted(ActionRequest request, ActionResponse response) {
    try {
      SaftReportWorkflowService service = Beans.get(SaftReportWorkflowService.class);
      NapSubmission submission =
          awaiting(service.awaitingOutcome(writable(request, OUTCOME)), response);
      if (submission == null) {
        return;
      }
      service.recordAccepted(NapFilingGate.require(OUTCOME, submission));
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Отхвърлен“ — с причина; позволява нов опит. */
  public void recordRejected(ActionRequest request, ActionResponse response) {
    try {
      SaftReport ctx = request.getContext().asType(SaftReport.class);
      SaftReportWorkflowService service = Beans.get(SaftReportWorkflowService.class);
      NapSubmission submission =
          awaiting(service.awaitingOutcome(writable(request, OUTCOME)), response);
      if (submission == null) {
        return;
      }
      service.recordRejected(
          NapFilingGate.require(OUTCOME, submission), ctx.getRejectionReasonInput());
      response.setValue("rejectionReasonInput", null);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Качи протокола“ (errsaft XML) към последния подаден опит — грешките стават находки. */
  public void attachProtocol(ActionRequest request, ActionResponse response) {
    try {
      SaftReport ctx = request.getContext().asType(SaftReport.class);
      SaftReportWorkflowService service = Beans.get(SaftReportWorkflowService.class);
      NapSubmission filed = service.lastFiled(writable(request, PROTOCOL));
      if (filed == null) {
        response.setError(
            I18n.get("Няма подаден опит — протоколът се качва след „Отбележи подаден“."));
        return;
      }
      NapSubmission submission = NapFilingGate.require(PROTOCOL, filed);
      MetaFile file = resolveFile(ctx.getProtocolFile());
      if (file == null) {
        response.setError(I18n.get("Изберете файла на протокола (errsaft XML)."));
        return;
      }
      List<SaftNapProtocol.Error> errors = service.attachProtocol(submission, file);
      response.setValue("protocolFile", null);
      response.setReload(true);
      response.setNotify(
          errors.isEmpty()
              ? I18n.get("Протоколът е записан: без грешки.")
              : String.format(
                  I18n.get("Протоколът е записан: %d грешки — виж „Находки“."), errors.size()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Коригиращ файл — извън обхвата: винаги отказ с ясен текст. */
  public void createCorrection(ActionRequest request, ActionResponse response) {
    try {
      SaftReport ctx = request.getContext().asType(SaftReport.class);
      SaftReport report = writable(request, "correct a SAF-T report");
      Beans.get(SaftReportWorkflowService.class)
          .createCorrection(report, ctx.getCorrectionReasonInput());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  // ─────────────── помощни ───────────────

  /** Опитът, който чака изход, или съобщение, че няма такъв. */
  protected static NapSubmission awaiting(NapSubmission submission, ActionResponse response) {
    if (submission == null) {
      response.setError(I18n.get("Няма подаване, което чака изход — първо „Отбележи подаден“."));
    }
    return submission;
  }

  /** Отчетът от базата, след гейта за подаване: способност, активно дружество, запис. */
  protected SaftReport writable(ActionRequest request, String action) {
    SaftReport report = activeReport(request);
    CapabilityGate.requireRole(action, NapFilingGate.capabilityFor(saftKind()));
    CapabilityGate.requireWrite(SaftReport.class, report.getId());
    return report;
  }

  /** Отчетът от базата — само на активното дружество. */
  protected SaftReport activeReport(ActionRequest request) {
    SaftReport report = managed(request);
    CapabilityGate.requireActiveCompany(report.getCompany());
    return report;
  }

  /** Видът „SAF-T“ за гейта по вида на документа (#331) — подаване, което още не съществува. */
  protected static NapSubmission saftKind() {
    NapSubmission kind = new NapSubmission();
    kind.setServiceCodeSelect(NapSubmissionRepository.SERVICE_SAFT);
    return kind;
  }

  protected SaftReport managed(ActionRequest request) {
    SaftReport ctx = request.getContext().asType(SaftReport.class);
    SaftReport report =
        ctx.getId() == null ? null : Beans.get(SaftReportRepository.class).find(ctx.getId());
    if (report == null) {
      throw new UnauthorizedException(I18n.get("Запишете SAF-T отчета първо."));
    }
    return report;
  }

  protected LocalDate today(SaftReport report) {
    return Beans.get(AppBaseService.class).getTodayDate(report.getCompany());
  }

  protected MetaFile resolveFile(MetaFile fromContext) {
    if (fromContext == null || fromContext.getId() == null) {
      return null;
    }
    // G6: only a file the user uploaded; the id comes from the client.
    MetaFile file = Beans.get(MetaFileRepository.class).find(fromContext.getId());
    CapabilityGate.requireOwnFile(file);
    return file;
  }

  /** Резултатът на проверката на екрана: ГРЕШКИТЕ като предупреждение, иначе кратко съобщение. */
  protected static void notify(
      ActionResponse response, SaftCheckResult result, String ok, String refused) {
    long errors = result.getFindings().stream().filter(SaftCheckResult.Finding::isError).count();
    long warnings = result.getFindings().size() - errors;
    if (!result.isChecked() || errors > 0) {
      response.setAlert(
          String.format(
                  I18n.get("%d грешки и %d предупреждения — виж „Находки“."), errors, warnings)
              + refused);
    } else if (warnings > 0) {
      response.setNotify(
          ok + " " + String.format(I18n.get("Предупреждения: %d — виж „Находки“."), warnings));
    } else {
      response.setNotify(I18n.get(ok));
    }
  }
}
