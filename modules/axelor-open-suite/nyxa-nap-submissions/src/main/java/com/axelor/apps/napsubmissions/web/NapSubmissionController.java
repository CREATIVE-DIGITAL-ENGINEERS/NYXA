package com.axelor.apps.napsubmissions.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProviderIds;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.QesSigningException;
import com.axelor.apps.napsubmissions.common.signing.UnconfiguredCloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.UserQesProfileService;
import com.axelor.apps.napsubmissions.common.signing.browser.BissServiceSigner;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustConfig;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustPortal;
import com.axelor.apps.napsubmissions.common.submission.ManualSubmissionService;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLockedException;
import com.axelor.apps.napsubmissions.common.submission.NapProcessingResultService;
import com.axelor.apps.napsubmissions.common.submission.NapSubmissionWorkflowService;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsEvidenceException;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsNapSubmitService;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsSubmissionService;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * НАП submission actions. Thin delegation to the workflow / manual services; each button is one
 * explicit action. Submitting is always human-initiated.
 *
 * <p><b>Every action passes {@link NapFilingGate}</b>: the capability of the document's kind (Д1/Д6
 * and чл. 73(6) — {@code hr.payroll.write}; Прил. 9 — {@code hr.noi.write}; the rest — {@code
 * accounting.nap.write}), the active company, and write on the record as loaded from the database.
 *
 * <p><b>ЕДНО действие за подписване и подаване.</b> {@link #signAndSubmit} е единственият бутон:
 * проверява, че подаването изобщо може да бъде подписано, и ВГРАЖДА подписващата страница за
 * избрания метод във формата (iframe към {@code NapBrowserSigningRestController}, същият произход и
 * сесия) — USB КЕП през локалния компонент (BISS) или облачен КЕП (Evrotrust). Самото подписване и
 * единствената заявка към НАП стават в {@code NapBrowserSigningRestController}, защото нито един от
 * двата истински подписа не може да бъде произведен в Java: при USB токенът е на машината на
 * потребителя, при облака подписва доставчикът след потвърждение от човека. Нито един път не чете
 * частен ключ или PIN в Nyxa и нито един не симулира подпис.
 *
 * <p>Ръчният път (файлът е подписан и подаден в портала на институцията, входящият номер се въвежда
 * тук) остава наличен за всеки документ.
 */
@Singleton
public class NapSubmissionController {

  public void submitThroughApi(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission submission = NapFilingGate.require("submit to NAP", managed(request));
      Beans.get(NapSubmissionWorkflowService.class).submitThroughApi(submission);
      response.setReload(true);
    } catch (NapFilingLockedException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  public void refreshStatus(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission submission = NapFilingGate.require("refresh the NAP status", managed(request));
      Beans.get(NapSubmissionWorkflowService.class).refreshSubmissionStatus(submission);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * „ПОДПИШИ И ИЗПРАТИ“ — ЕДИНСТВЕНИЯТ бутон за подписване и подаване по API.
   *
   * <p>Проверява ТУК всичко, което може да се провери без подпис (права, дружество, заключен избор,
   * наличие на файлове, вече подадено), и ако всичко е наред ВГРАЖДА подписващата страница за
   * избрания метод във формата (задава {@code signingEmbedUrl}; показва се в iframe). Самото
   * подписване и ЕДНАТА заявка към НАП стават там, защото нито един от двата реална подписа не се
   * произвежда в Java:
   *
   * <ul>
   *   <li><b>USB КЕП</b> — токенът е на машината на потребителя; подписва локалният компонент
   *       (BISS) в браузъра, частният ключ и PIN не влизат в Nyxa;
   *   <li><b>Evrotrust</b> — подписва облачният доставчик, след като човекът потвърди в
   *       приложението си; Nyxa получава само детачен PKCS#7 и публичен сертификат.
   * </ul>
   *
   * <p>И в двата случая подписът и сертификатът се проверяват криптографски върху ТОЧНИТЕ заключени
   * байтове, пътуват само в рамките на подаващата заявка и не се записват никъде. Подпис не се
   * симулира при никакви обстоятелства.
   */
  public void signAndSubmit(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      NapSubmission submission = NapFilingGate.require("submit a declaration to NAP", find(ctx));
      // ЕТЗ е само през портала: отказът е ПРЕДИ „първо заключете избора“, който би пратил човека
      // по път, който за този вид не съществува.
      NapFilingLock.refuseManualOnly(submission);
      if (DeclarationsSubmissionService.hasDocumentId(submission)) {
        response.setError(
            I18n.get(
                "The submission has already been sent to NAP. Use “Refresh the result"
                    + " from NAP” instead of submitting again."));
        return;
      }
      String status = submission.getSubmissionStatusSelect();
      if (!NapSubmissionRepository.STATUS_LOCKED.equals(status)
          && !NapSubmissionRepository.STATUS_SIGNED.equals(status)
          && !NapSubmissionRepository.STATUS_TECHNICAL_ERROR.equals(status)) {
        response.setError(
            I18n.get(
                "Lock the selection first (screen “File D1/D6 with NAP”) before signing"
                    + " and sending."));
        return;
      }
      if (submission.getSubmissionFileList() == null
          || submission.getSubmissionFileList().isEmpty()) {
        response.setError(I18n.get("The submission has no files."));
        return;
      }
      // ЕДИН ДОКУМЕНТ — ЕДНО ПОДАВАНЕ: същият документ, вече подаден ръчно (или от друг ред),
      // заключва пътя по API. Проверява се ТУК, за да не се отваря подписваща страница за нещо,
      // което подаващата заявка така или иначе ще откаже.
      Beans.get(NapFilingLock.class).requireApiAllowed(submission);

      // Методът се чете от ЖИВАТА форма (контекста), а не от заварения ред — така изборът не се
      // губи, ако предходното „save" не е стигнало до базата (причината за „Не е избран метод" при
      // повторно натискане). После се записва АВТОРИТЕТНО върху подаването по-долу, за да го види и
      // вградената страница (/payloads чете метода пак от базата).
      String method = ctx.getQesSigningMethodSelect();
      if (!NapSubmissionRepository.QES_METHOD_LOCAL_USB.equals(method)
          && !NapSubmissionRepository.QES_METHOD_CLOUD.equals(method)) {
        response.setError(I18n.get("Choose a signing method: USB QES or Evrotrust (cloud QES)."));
        return;
      }
      // Изборът се записва ВЕДНАГА — преди всяка проверка на конфигурацията — за да не се губи при
      // отказ (RegulatoryImmutabilityGuard го допуска в „locked").
      if (!method.equals(submission.getQesSigningMethodSelect())) {
        submission.setQesSigningMethodSelect(method);
        JPA.runInTransaction(() -> Beans.get(NapSubmissionRepository.class).save(submission));
      }
      response.setValue("qesSigningMethodSelect", method);

      // Подписва се САМО със собствения профил на влезлия потребител (ЕГН/ЛНЧ, записани веднъж) —
      // никога с профил, избран от списък или подаден от формата.
      UserQesProfile profile =
          Beans.get(UserQesProfileService.class).requireSigningProfile(AuthUtils.getUser());

      // Покажи ТОЧНО кое липсва за избрания метод — ПРЕДИ да се отвори подписващият панел, за да не
      // се стига до отказ чак вътре в iframe-а. Конфигурационна липса НЕ се заобикаля и НЕ се
      // „лекува" с фиктивен подпис — само се съобщава ясно.
      if (NapSubmissionRepository.QES_METHOD_LOCAL_USB.equals(method)) {
        if (!Beans.get(BissServiceSigner.class).isConfigured()) {
          response.setError(
              I18n.get(
                  "USB QES is not configured on the server: the SERVICE certificate"
                      + " (keystore) for the requests to the local component is missing (setting"
                      + " nyxa.nap.biss.keystore-path). Contact the administrator or sign through"
                      + " the NAP portal."));
          return;
        }
      } else if (EvrotrustPortal.apiFlowConfigured()
          && cloudProviderIfConfigured(response) == null) {
        // Без API конфигурация Evrotrust не отказва: страницата води към портала на доставчика
        // (сваляне, подписване там, качване на подписаните файлове) — EvrotrustPortal.
        return;
      }

      // Подаване, вече свързано с друг подписващ, не се преприсвоява (същото правило като
      // recordSigner).
      UserQesProfile recorded = submission.getQesSignerProfile();
      if (recorded != null && !recorded.getId().equals(profile.getId())) {
        response.setError(
            I18n.get(
                "This submission is already linked to the QES profile of another signer."
                    + " It must be signed by the same person, or a new submission must be made."));
        return;
      }
      if (recorded == null) {
        submission.setQesSignerProfile(profile);
        JPA.runInTransaction(() -> Beans.get(NapSubmissionRepository.class).save(submission));
      }
      response.setValue("qesSignerProfile", profile);
      // ⚠️ НЕ отваряме отделен екран: вграждаме подписващата страница ВЪВ формата, като задаваме
      // релативния (same-origin) URL към същия REST ресурс. napSigningEmbedPanel го показва в
      // iframe, JavaScript-ът се изпълнява в браузъра (USB КЕП / облачен поток) и прогресът и
      // резултатът се виждат на ТОЗИ екран. signingEmbedUrl е transient — не се записва.
      response.setValue("signingEmbedUrl", browserSigningUrl(submission.getId(), method));
    } catch (QesException | QesSigningException | NapFilingLockedException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Активният облачен доставчик, ако е конфигуриран — иначе задава ясен отказ в {@code response} и
   * връща {@code null}. Съобщава ТОЧНО кое липсва: сървърни данни (среда/vendor/API ключ) или
   * нашата ключова двойка за Evrotrust. Нищо не се заобикаля.
   */
  protected String cloudProviderIfConfigured(ActionResponse response) {
    String providerId = Beans.get(CloudQesProvider.class).getProviderId();
    if (providerId == null
        || providerId.isBlank()
        || UnconfiguredCloudQesProvider.PROVIDER_ID.equals(providerId)) {
      response.setError(
          I18n.get(
              "Cloud signing (Evrotrust) is not configured on the server: server data"
                  + " is missing (environment, vendor number and API key reference). Choose"
                  + " “USB QES” or sign through the NAP portal."));
      return null;
    }
    // Evrotrust е избран, но ONLINE потокът иска и НАШАТА ключова двойка (публичен ключ + източник
    // на частния) — иначе резултатът не може да бъде разшифрован след потвърждението.
    if (CloudQesProviderIds.EVROTRUST.equals(providerId)
        && !Beans.get(EvrotrustConfig.class).isOnlineFlowConfigured()) {
      response.setError(
          I18n.get(
              "Evrotrust is selected, but our key pair (public key and private key"
                  + " source) is missing; signing was refused. See"
                  + " the official NAP API specification"));
      return null;
    }
    return providerId;
  }

  /** Fetch and interpret the asynchronous НАП result for an already submitted declaration. */
  public void fetchNapResult(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      NapSubmission submission = NapFilingGate.require("fetch the NAP result", find(ctx));
      // Сертификатът, с който е изпратено, е на подаването (signerCertificateBase64). Поставен на
      // ръка (transient, не стига до базата) — само за подавания отпреди това; има предимство.
      submission.setResultSignerCertificatePem(ctx.getResultSignerCertificatePem());
      NapSubmission updated = Beans.get(DeclarationsNapSubmitService.class).fetchResult(submission);
      response.setInfo(resultMessage(updated));
      response.setReload(true);
    } catch (DeclarationsEvidenceException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  protected String resultMessage(NapSubmission submission) {
    String status = submission.getSubmissionStatusSelect();
    if (NapSubmissionRepository.STATUS_ACCEPTED.equals(status)) {
      return I18n.get("NAP accepted the submission.");
    }
    if (NapSubmissionRepository.STATUS_REJECTED.equals(status)) {
      return String.format(
          I18n.get("NAP rejected the submission: %s"), nullSafe(submission.getErrorMessage()));
    }
    if ("RESULT_NEEDS_REVIEW".equals(submission.getExternalStatus())) {
      return I18n.get(
          "The NAP response was saved but cannot be read automatically — the"
              + " original needs to be reviewed.");
    }
    if ("RESULT_NOT_READY".equals(submission.getExternalStatus())) {
      return I18n.get("NAP is still processing the submission; try again later.");
    }
    return I18n.get("The submission is still being processed by NAP.");
  }

  protected String nullSafe(String value) {
    return value == null ? "" : value;
  }

  /**
   * The manual path, available for every document: the file was filed on the portal with the
   * person's own КЕП; the incoming number and the portal's date are entered here.
   */
  public void recordManualSubmission(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      NapSubmission submission = NapFilingGate.require("record a manual NAP submission", find(ctx));
      Beans.get(ManualSubmissionService.class)
          .recordManualSubmission(
              submission, ctx.getNapIncomingNumber(), ctx.getPortalSubmittedDate());
      response.setReload(true);
    } catch (NapFilingLockedException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  public void recordAccepted(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      NapSubmission submission = NapFilingGate.require("record a NAP outcome", find(ctx));
      Beans.get(NapSubmissionWorkflowService.class)
          .recordAccepted(submission, ctx.getNapIncomingNumber());
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  public void recordRejected(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      NapSubmission submission = NapFilingGate.require("record a NAP outcome", find(ctx));
      Beans.get(NapSubmissionWorkflowService.class)
          .recordRejected(submission, ctx.getActionReason());
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  public void recordCorrectionRequired(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      NapSubmission submission = NapFilingGate.require("record a NAP outcome", find(ctx));
      Beans.get(ManualSubmissionService.class)
          .recordCorrectionRequired(submission, ctx.getActionReason());
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** The optional protocol / confirmation of the portal (or any НАП response) for the filing. */
  public void attachResponseFile(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      MetaFile file = resolveFile(ctx.getResponseFile());
      if (file == null) {
        response.setError(I18n.get("Select a response file first."));
        return;
      }
      NapSubmission submission = NapFilingGate.require("attach a NAP response", find(ctx));
      Beans.get(ManualSubmissionService.class)
          .attachResponseFile(submission, file, NapArtifactRepository.TYPE_RESPONSE);
      response.setValue("responseFile", null);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Record a НАП processing result from an attached raw response file. The raw bytes are preserved
   * immutably; the format is inferred from the MIME type and the outcome is left {@code unknown}
   * for a human to interpret (raw HTML is stored, never rendered).
   */
  public void recordProcessingResult(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      MetaFile file = resolveFile(ctx.getResponseFile());
      if (file == null) {
        response.setError(I18n.get("Attach the raw NAP response file first."));
        return;
      }
      NapSubmission submission = NapFilingGate.require("record a NAP processing result", find(ctx));
      Beans.get(NapProcessingResultService.class)
          .recordResult(
              submission,
              file,
              inferFormat(file),
              NapProcessingResultRepository.OUTCOME_UNKNOWN,
              ctx.getResultSummary(),
              "manual-1");
      response.setValue("responseFile", null);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  protected String inferFormat(MetaFile file) {
    String mime = file.getFileType() == null ? "" : file.getFileType().toLowerCase();
    if (mime.contains("html")) {
      return NapProcessingResultRepository.FORMAT_HTML;
    }
    if (mime.contains("json")) {
      return NapProcessingResultRepository.FORMAT_JSON;
    }
    if (mime.contains("text")) {
      return NapProcessingResultRepository.FORMAT_TEXT;
    }
    if (mime.isEmpty()) {
      return NapProcessingResultRepository.FORMAT_UNKNOWN;
    }
    return NapProcessingResultRepository.FORMAT_BINARY;
  }

  /** Explicit, authorized manual resolution of an ambiguous (outcome-unknown) submission. */
  public void resolveOutcomeUnknown(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission ctx = request.getContext().asType(NapSubmission.class);
      NapSubmission submission = NapFilingGate.require("resolve an unknown NAP outcome", find(ctx));
      Beans.get(DeclarationsSubmissionService.class)
          .manuallyResolveOutcomeUnknown(
              submission,
              NapSubmissionRepository.STATUS_TECHNICAL_ERROR,
              ctx.getResolutionReason());
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  protected NapSubmission managed(ActionRequest request) {
    return find(request.getContext().asType(NapSubmission.class));
  }

  /** The record as stored — never the client's copy: its kind and company decide the gate. */
  protected NapSubmission find(NapSubmission ctx) {
    return ctx == null || ctx.getId() == null
        ? null
        : Beans.get(NapSubmissionRepository.class).find(ctx.getId());
  }

  /**
   * Релативен (same-origin) URL към вградената подписваща страница за избрания метод. Релативен,
   * защото се показва в iframe В САМАТА форма (същият произход и сесия) — никога външна страница;
   * вътрешният JavaScript ползва същите REST услуги спрямо този адрес.
   */
  protected String browserSigningUrl(Long submissionId, String method) {
    return "ws/nyxa/nap/browser-signing/page?submissionId=" + submissionId + "&method=" + method;
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
}
