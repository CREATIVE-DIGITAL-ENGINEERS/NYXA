package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.etz.BgEtzConclusionService;
import com.axelor.apps.bglocalization.service.etz.BgEtzEventKind;
import com.axelor.apps.bglocalization.service.etz.BgEtzExportService;
import com.axelor.apps.bglocalization.service.etz.BgEtzSubmissionPlan;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.apps.napsubmissions.common.submission.ManualSubmissionService;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLockedException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * ПАНЕЛЪТ „ЕТЗ“ НА ТРУДОВИЯ ДОГОВОР — сключване (тип 1), само през портала.
 *
 * <p>Тънък слой: правилата са в {@link BgEtzConclusionService}, ръчното подаване — в {@code
 * ManualSubmissionService}, неизменността — в {@code NapSubmissionEvidence}.
 *
 * <p><b>Гейтът:</b> генерирането и ръчното подаване искат {@code hr.payroll.write} (ЕТЗ е на ТРЗ,
 * както Д1/Д6) + активното дружество + запис върху ЗАПИСАНИЯ ред — в този ред, за да назове отказът
 * точно липсващото. Свалянето иска {@code hr.payroll.read} (`all.read` го включва) + четене на
 * договора и на подаването, в активното дружество.
 */
@Singleton
public class BgEtzController {

  static final String GENERATE = "generate an ETZ record";
  static final String RECORD = "record a manual НАП submission";
  static final String VIEW = "view the ETZ filing";
  static final String DOWNLOAD = "download the ETZ file";

  /** При зареждане: срокът, състоянието и кои бутони има смисъл да се виждат. Нищо не записва. */
  public void load(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract contract = stored(request);
      if (contract == null || !mayRead()) {
        response.setAttr("bgEtzPanel", "hidden", true);
        return;
      }
      CapabilityGate.requireRead(EmploymentContract.class, contract.getId());
      BgEtzConclusionService.Status status =
          Beans.get(BgEtzConclusionService.class).status(contract);
      response.setAttr("bgEtzPanel", "hidden", false);
      response.setAttr("bgEtzStatusLabel", "title", status.getText());
      response.setAttr("bgEtzOverdueLabel", "hidden", !status.isOverdue());
      if (status.isOverdue()) {
        response.setAttr(
            "bgEtzOverdueLabel",
            "title",
            BgEtzConclusionService.overdueWarning(status.getDueDate()));
      }
      boolean filed = status.getFiled() != null;
      boolean draft = status.getDraft() != null;
      response.setAttr("bgEtzGenerateBtn", "hidden", filed);
      response.setAttr("bgEtzDownloadBtn", "hidden", !filed && !draft);
      response.setAttr("bgEtzIncomingNumber", "hidden", !draft);
      response.setAttr("bgEtzPortalDate", "hidden", !draft);
      response.setAttr("bgEtzRecordBtn", "hidden", !draft);
    } catch (UnauthorizedException e) {
      response.setAttr("bgEtzPanel", "hidden", true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Генерирай ЕТЗ (сключване)“ — файлът и подаването-чернова, или отказ с полето и екрана. */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract contract = stored(request);
      if (contract == null) {
        response.setError("Запишете договора, преди да генерирате ЕТЗ.");
        return;
      }
      CapabilityGate.requireRole(GENERATE, Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireActiveCompany(contract.getPayCompany());
      CapabilityGate.requireWrite(EmploymentContract.class, contract.getId());
      NapSubmission draft = Beans.get(BgEtzConclusionService.class).generate(contract);
      response.setNotify(
          "Файлът "
              + BgEtzSubmissionPlan.fileNameFor(BgEtzEventKind.CONCLUSION, draft.getTaxpayerPin())
              + " е готов. Свалете го и го качете в портала на НАП с КЕП или ПИК, после запишете"
              + " входящия номер тук. Нищо не е изпратено към НАП.");
      response.setReload(true);
    } catch (BgEtzExportService.BgEtzRefusalException | NapFilingLockedException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Свали файла“ — на подаденото, а ако още няма — на последната чернова. */
  public void download(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract contract = stored(request);
      if (contract == null) {
        return;
      }
      // ⚠️ РОЛЯТА ПЪРВО. Скритият панел (load) не е гейт: действието се вика и направо, а четене на
      // договора и на подаването имат и роли без ТРЗ.
      CapabilityGate.requireRole(DOWNLOAD, Capabilities.PAYROLL_READ);
      CapabilityGate.requireRead(EmploymentContract.class, contract.getId());
      CapabilityGate.requireActiveCompany(contract.getPayCompany());
      BgEtzConclusionService service = Beans.get(BgEtzConclusionService.class);
      NapSubmission submission = service.filed(contract);
      if (submission == null) {
        submission = service.latestDraft(contract);
      }
      MetaFile file = payload(submission);
      if (file == null) {
        response.setInfo("Няма генериран ЕТЗ файл за този договор.");
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

  /**
   * „Запиши ръчно подаване“ — входящият номер и датата от портала върху последната чернова. Гейтът
   * и записът са тези на общото подаване ({@code NapFilingGate} → {@code ManualSubmissionService}).
   */
  public void recordManual(ActionRequest request, ActionResponse response) {
    try {
      EmploymentContract form = request.getContext().asType(EmploymentContract.class);
      EmploymentContract contract = stored(request);
      if (contract == null) {
        return;
      }
      NapSubmission draft = Beans.get(BgEtzConclusionService.class).latestDraft(contract);
      if (draft == null) {
        response.setError("Няма чернова за подаване — първо генерирайте ЕТЗ.");
        return;
      }
      NapSubmission submission = NapFilingGate.require(RECORD, draft);
      Beans.get(ManualSubmissionService.class)
          .recordManualSubmission(
              submission, form.getBgEtzIncomingNumber(), form.getBgEtzPortalDate());
      response.setNotify("ЕТЗ за сключването е отбелязано като подадено през портала.");
      response.setReload(true);
    } catch (NapFilingLockedException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Има ли потребителят право да вижда подаванията на ТРЗ — без да отказва (за панела). */
  protected boolean mayRead() {
    try {
      CapabilityGate.requireRole(VIEW, Capabilities.PAYROLL_READ);
      return true;
    } catch (UnauthorizedException e) {
      return false;
    }
  }

  /** Записаният договор по id — никога стойностите от формата. */
  protected EmploymentContract stored(ActionRequest request) {
    Object id = request.getContext().get("id");
    if (id == null) {
      return null;
    }
    return Beans.get(EmploymentContractRepository.class).find(Long.valueOf(id.toString()));
  }

  /**
   * Файлът на подаването — прочетен със ЗАЯВКА, не от {@code submissionFileList}.
   *
   * <p>⚠️ Редовете на файловете се записват поотделно ({@code BgEtzSubmissionService}) и не влизат
   * в колекцията на вече заредения ред: в същия контекст тя остава празна и „Свали файла“ би казал
   * „няма файл“ за току-що генериран.
   */
  protected static MetaFile payload(NapSubmission submission) {
    if (submission == null || submission.getId() == null) {
      return null;
    }
    NapSubmissionFile file =
        Beans.get(NapSubmissionFileRepository.class)
            .all()
            .filter("self.submission.id = :s AND self.payloadArtifact.metaFile IS NOT NULL")
            .bind("s", submission.getId())
            .order("sequence")
            .fetchOne();
    return file == null ? null : file.getPayloadArtifact().getMetaFile();
  }
}
