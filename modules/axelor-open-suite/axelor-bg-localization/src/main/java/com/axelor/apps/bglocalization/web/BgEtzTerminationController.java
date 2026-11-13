package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgContractTermination;
import com.axelor.apps.bglocalization.service.etz.BgEtzConclusionService;
import com.axelor.apps.bglocalization.service.etz.BgEtzExportService;
import com.axelor.apps.bglocalization.service.etz.BgEtzTerminationService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.submission.ManualSubmissionService;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLockedException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import javax.validation.ValidationException;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * ПАНЕЛЪТ „ЕТЗ — прекратяване“ НА ТРУДОВИЯ ДОГОВОР (тип 3).
 *
 * <p>Тънък слой над {@link BgEtzTerminationService}. Каналът е този на сключването: файл → портал
 * (КЕП или ПИК) → вх. № и дата; API за ЕТЗ отказва ({@code NapFilingLock.MANUAL_ONLY}).
 *
 * <p><b>Гейтът:</b> записът и генерирането искат {@code hr.payroll.write} + активното дружество на
 * ЗАПИСАНИЯ договор + запис върху него; свалянето — {@code hr.payroll.read}; ръчното подаване —
 * {@code NapFilingGate} по вида ({@code DEC_ETZ62} → {@code hr.payroll.write}). {@code
 * all.write}/{@code all.read} ги включват.
 *
 * <p>⚠️ Засега „Генерирай“ отказва открито: т. 25/30/31 още не се изчисляват.
 */
@Singleton
public class BgEtzTerminationController {

  static final String CREATE = "create the termination record";
  static final String GENERATE = "generate an ETZ termination record";

  /** При зареждане: записът, датата, срокът и кои бутони има смисъл да се виждат. */
  public void load(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract contract = etz().stored(request);
      if (contract == null || !etz().mayRead()) {
        response.setAttr("bgEtzEndPanel", "hidden", true);
        return;
      }
      CapabilityGate.requireRead(EmploymentContract.class, contract.getId());
      BgEtzTerminationService.Status status = service().status(contract);
      boolean recorded = status.getTermination() != null;
      boolean filed = status.getFiled() != null;
      boolean draft = status.getDraft() != null;
      response.setAttr("bgEtzEndPanel", "hidden", false);
      response.setAttr("bgEtzEndStatusLabel", "title", status.getText());
      response.setAttr("bgEtzEndOverdueLabel", "hidden", !status.isOverdue());
      if (status.isOverdue()) {
        response.setAttr(
            "bgEtzEndOverdueLabel",
            "title",
            BgEtzConclusionService.overdueWarning(status.getDueDate()));
      }
      response.setAttr("bgEtzEndCreateBtn", "hidden", recorded);
      response.setAttr("bgEtzEndGenerateBtn", "hidden", !recorded || filed);
      response.setAttr("bgEtzEndDownloadBtn", "hidden", !filed && !draft);
      response.setAttr("bgEtzEndIncomingNumber", "hidden", !draft);
      response.setAttr("bgEtzEndPortalDate", "hidden", !draft);
      response.setAttr("bgEtzEndRecordBtn", "hidden", !draft);
      // Датата е на записа: от него нататък полето в договора само показва.
      response.setAttr("bgTerminationDate", "readonly", recorded);
    } catch (UnauthorizedException e) {
      response.setAttr("bgEtzEndPanel", "hidden", true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * „Създай запис за прекратяване“ — датата от договора (или уговореният край), записана в него.
   */
  public void create(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract contract = etz().stored(request);
      if (contract == null) {
        response.setError("Запишете договора, преди да създадете запис за прекратяване.");
        return;
      }
      CapabilityGate.requireRole(CREATE, Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireActiveCompany(contract.getPayCompany());
      CapabilityGate.requireWrite(EmploymentContract.class, contract.getId());
      BgContractTermination termination = service().create(contract);
      response.setNotify(
          Boolean.TRUE.equals(termination.getFromAgreedEnd())
              ? "Записът е създаден с датата на уговорения край — договорът няма изрична дата на"
                  + " прекратяване."
              : "Записът за прекратяване е създаден.");
      response.setReload(true);
    } catch (BgEtzExportService.BgEtzRefusalException | ValidationException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Генерирай ЕТЗ (прекратяване)“ — засега винаги открит отказ, с всички пречки. */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract contract = etz().stored(request);
      if (contract == null) {
        response.setError("Запишете договора, преди да генерирате ЕТЗ.");
        return;
      }
      CapabilityGate.requireRole(GENERATE, Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireActiveCompany(contract.getPayCompany());
      CapabilityGate.requireWrite(EmploymentContract.class, contract.getId());
      service().generate(contract);
      response.setReload(true);
    } catch (BgEtzExportService.BgEtzRefusalException | NapFilingLockedException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Свали файла“ на прекратяването — на подаденото, иначе на последната чернова. */
  public void download(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract contract = etz().stored(request);
      if (contract == null) {
        return;
      }
      CapabilityGate.requireRole(BgEtzController.DOWNLOAD, Capabilities.PAYROLL_READ);
      CapabilityGate.requireRead(EmploymentContract.class, contract.getId());
      CapabilityGate.requireActiveCompany(contract.getPayCompany());
      BgContractTermination termination = service().record(contract);
      NapSubmission submission = service().filed(termination);
      if (submission == null) {
        submission = service().latestDraft(termination);
      }
      MetaFile file = BgEtzController.payload(submission);
      if (file == null) {
        response.setInfo("Няма генериран ЕТЗ файл за прекратяването.");
        return;
      }
      CapabilityGate.requireRead(NapSubmission.class, submission.getId());
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

  /** „Запиши ръчно подаване“ за прекратяването — гейтът и записът са тези на общото подаване. */
  public void recordManual(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract form = request.getContext().asType(EmploymentContract.class);
      EmploymentContract contract = etz().stored(request);
      if (contract == null) {
        return;
      }
      NapSubmission draft = service().latestDraft(service().record(contract));
      if (draft == null) {
        response.setError("Няма чернова за подаване — първо генерирайте ЕТЗ за прекратяването.");
        return;
      }
      NapSubmission submission = NapFilingGate.require(BgEtzController.RECORD, draft);
      Beans.get(ManualSubmissionService.class)
          .recordManualSubmission(
              submission, form.getBgEtzEndIncomingNumber(), form.getBgEtzEndPortalDate());
      response.setNotify("ЕТЗ за прекратяването е отбелязано като подадено през портала.");
      response.setReload(true);
    } catch (NapFilingLockedException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  private static BgEtzTerminationService service() {
    return Beans.get(BgEtzTerminationService.class);
  }

  private static BgEtzController etz() {
    return Beans.get(BgEtzController.class);
  }
}
