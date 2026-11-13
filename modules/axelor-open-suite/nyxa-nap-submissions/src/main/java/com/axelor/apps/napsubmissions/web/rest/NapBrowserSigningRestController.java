package com.axelor.apps.napsubmissions.web.rest;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.browser.BissServiceSigner;
import com.axelor.apps.napsubmissions.common.signing.browser.BrowserSignedFile;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustPortal;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLockedException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsBrowserSignAndSubmitService;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsCloudSignAndSubmitService;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsEvidenceException;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * СЪРВЪРНАТА СТРАНА НА „ПОДПИШИ И ИЗПРАТИ“ — и за двата реални метода.
 *
 * <p>Нито един от двата подписа не може да бъде произведен в Java: при USB КЕП токенът е на
 * машината на потребителя, при облачния КЕП подписва доставчикът след потвърждение от човека.
 * Затова подписващата страница се отваря в Nyxa (същият произход, същата сесия) и оркестрира
 * съответния поток, а тук остават само точките, през които минават данни.
 *
 * <p><b>USB КЕП (BISS/SCS — {@code GET /version}, {@code POST /getsigner}, {@code POST /sign} на
 * {@code https://localhost:53952..53955}):</b>
 *
 * <ul>
 *   <li>{@code GET .../page} — самата страница (HTML + JS);
 *   <li>{@code GET .../{id}/payloads} — ТОЧНИТЕ заключени байтове на всеки файл и техният SHA-256
 *       (плюс подписа и сертификата на УСЛУГАТА, които BISS изисква за всяка заявка);
 *   <li>{@code POST .../{id}/submit} — детачните PKCS#7 и сертификатите, проверка и ЕДНО подаване.
 * </ul>
 *
 * <p><b>Облачен КЕП (Evrotrust — документираният асинхронен поток):</b>
 *
 * <ul>
 *   <li>{@code POST .../{id}/cloud/start} — стартира подписването на всеки файл; връща по един
 *       {@code transactionID};
 *   <li>{@code POST .../{id}/cloud/status} — състоянието на транзакциите, докато човекът
 *       потвърждава;
 *   <li>{@code POST .../{id}/cloud/submit} — сваля, проверява и подава ВЕДНЪЖ.
 * </ul>
 *
 * <p>⚠️ PIN-ът за отключване на токена и частният ключ не минават през нито една от тях. Подписът и
 * сертификатът не се записват — само се проверяват и пътуват към НАП в рамките на заявката. Между
 * стъпките на облачния поток се пази единствено {@code transactionID}, който не е тайна. Всяка
 * точка минава през {@link NapFilingGate} (права, дружество, запис), точно както бутонът.
 */
@Path("/nyxa/nap/browser-signing")
@Singleton
public class NapBrowserSigningRestController {

  /** Самата подписваща страница — отваря се като {@code html} изглед в Nyxa. */
  @GET
  @Path("/page")
  @Produces("text/html; charset=UTF-8")
  public Response page() {
    try {
      // Страницата с преводите на текущия потребител (виж NapBrowserSigningPage).
      return Response.ok(NapBrowserSigningPage.render()).build();
    } catch (Exception e) {
      return Response.serverError().entity("signing page not readable").build();
    }
  }

  /**
   * Точните заключени байтове, които трябва да бъдат подписани, и избраният метод.
   *
   * <p>За USB КЕП връща и base64 на съдържанието (локалният компонент подписва самите байтове) плюс
   * подписа и сертификата на УСЛУГАТА, които BISS изисква за всяка заявка. За облачния метод тези
   * полета липсват нарочно: там байтовете не напускат сървъра — на доставчика се праща само техният
   * SHA-256, а сертификат на услуга не е нужен.
   */
  @GET
  @Path("/{id}/payloads")
  @Produces(MediaType.APPLICATION_JSON)
  public Response payloads(@PathParam("id") Long id) {
    try {
      NapSubmission submission = gated(id, "prepare browser signing");
      boolean usb =
          NapSubmissionRepository.QES_METHOD_LOCAL_USB.equals(
              submission.getQesSigningMethodSelect());
      Map<NapSubmissionFile, byte[]> payloads =
          Beans.get(DeclarationsBrowserSignAndSubmitService.class).lockedPayloads(submission);
      // ⚠️ Само за USB: частният ключ на услугата не стига до браузъра, подписът се прави тук.
      // Липсва ли конфигурация → ясен отказ (и никакъв опит за подписване).
      BissServiceSigner serviceSigner = usb ? Beans.get(BissServiceSigner.class) : null;
      String serviceCertificate = usb ? serviceSigner.serviceCertificateBase64() : null;
      List<Map<String, Object>> files = new ArrayList<>();
      for (Map.Entry<NapSubmissionFile, byte[]> entry : payloads.entrySet()) {
        NapSubmissionFile file = entry.getKey();
        byte[] bytes = entry.getValue();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fileId", file.getId());
        row.put("fileName", file.getSubmittedFileName());
        row.put("documentType", file.getFileDocumentTypeSelect());
        row.put("byteLength", bytes.length);
        row.put(
            "sha256Hex",
            com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest.sha256Hex(bytes));
        if (usb) {
          row.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
          row.put("signedContentsBase64", serviceSigner.signContents(bytes));
        }
        files.add(row);
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("submissionId", submission.getId());
      body.put("method", submission.getQesSigningMethodSelect());
      body.put("signedContentsCertBase64", serviceCertificate);
      if (NapSubmissionRepository.QES_METHOD_CLOUD.equals(submission.getQesSigningMethodSelect())) {
        body.put("cloudFlow", EvrotrustPortal.flow());
        body.put("portalUrl", EvrotrustPortal.PORTAL_URL);
      }
      body.put("files", files);
      return Response.ok(body).build();
    } catch (Exception e) {
      return refusal(e);
    }
  }

  // --- Evrotrust през портала: сваляне → подписване в портала → качване и ЕДНО подаване ---------

  /**
   * Точните заключени байтове на един файл, за сваляне и подписване в портала на доставчика. Името
   * е официалното име на файла (напр. {@code EMPL2021.TXT}); съдържанието не се променя.
   */
  @GET
  @Path("/{id}/file/{fileId}")
  public Response lockedFile(@PathParam("id") Long id, @PathParam("fileId") Long fileId) {
    try {
      NapSubmission submission = gated(id, "prepare browser signing");
      Map<NapSubmissionFile, byte[]> payloads =
          Beans.get(DeclarationsBrowserSignAndSubmitService.class).lockedPayloads(submission);
      for (Map.Entry<NapSubmissionFile, byte[]> entry : payloads.entrySet()) {
        NapSubmissionFile file = entry.getKey();
        if (file.getId().equals(fileId)) {
          String name = safeFileName(file.getSubmittedFileName(), fileId);
          return Response.ok(entry.getValue(), MediaType.APPLICATION_OCTET_STREAM_TYPE)
              .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
              .header("Cache-Control", "no-store")
              .build();
        }
      }
      throw new DeclarationsEvidenceException(I18n.get("The file is not part of this submission."));
    } catch (Exception e) {
      // Свалянето се отваря като връзка, не през JSON — отказът е обикновен текст.
      Response json = refusal(e);
      Object message = ((Map<?, ?>) json.getEntity()).get("message");
      return Response.status(json.getStatus())
          .type("text/plain; charset=UTF-8")
          .entity(String.valueOf(message))
          .build();
    }
  }

  /**
   * Качените след подписване в портала файлове ({@code fileId → base64 на качения файл}), проверка
   * и ЕДНА заявка към НАП. Нищо от подписа не се записва.
   */
  @POST
  @Path("/{id}/portal/submit")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response portalSubmit(@PathParam("id") Long id, Map<String, String> signedByFileId) {
    try {
      NapSubmission submission = gated(id, "submit a declaration to NAP");
      Map<Long, byte[]> uploaded = new LinkedHashMap<>();
      if (signedByFileId != null) {
        for (Map.Entry<String, String> entry : signedByFileId.entrySet()) {
          Long fileId;
          try {
            fileId = Long.valueOf(entry.getKey().trim());
          } catch (NumberFormatException bad) {
            throw new DeclarationsEvidenceException(
                I18n.get("Unknown file in the submission request; the submission was refused."));
          }
          String encoded = entry.getValue() == null ? "" : entry.getValue().replaceAll("\\s", "");
          try {
            uploaded.put(fileId, Base64.getDecoder().decode(encoded));
          } catch (IllegalArgumentException bad) {
            throw new DeclarationsEvidenceException(
                I18n.get("The uploaded file cannot be read; the submission was refused."));
          }
        }
      }
      NapSubmission sent =
          Beans.get(DeclarationsBrowserSignAndSubmitService.class)
              .submitPortalSigned(submission, uploaded);
      return Response.ok(outcome(sent)).build();
    } catch (Exception e) {
      return refusal(e);
    }
  }

  /** Име за Content-Disposition: само безопасни знаци, без кавички и пътища. */
  protected static String safeFileName(String name, Long fileId) {
    String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? "nap-file-" + fileId : cleaned;
  }

  // --- облачен КЕП (Evrotrust): старт → статус → получаване, проверка и ЕДНО подаване -----------

  /** СТЪПКА 1: стартира подписването на всеки файл; връща по една транзакция на файл. */
  @POST
  @Path("/{id}/cloud/start")
  @Produces(MediaType.APPLICATION_JSON)
  public Response cloudStart(@PathParam("id") Long id) {
    try {
      NapSubmission submission = gated(id, "sign a NAP submission");
      List<DeclarationsCloudSignAndSubmitService.StartedSignature> started =
          Beans.get(DeclarationsCloudSignAndSubmitService.class).start(submission);
      List<Map<String, Object>> rows = new ArrayList<>();
      for (DeclarationsCloudSignAndSubmitService.StartedSignature one : started) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fileId", one.fileId);
        row.put("fileName", one.fileName);
        row.put("transactionId", one.transactionId);
        row.put("sha256Hex", one.payloadSha256Hex);
        rows.add(row);
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("ok", true);
      body.put("started", rows);
      return Response.ok(body).build();
    } catch (Exception e) {
      return refusal(e);
    }
  }

  /** СТЪПКА 2: състоянието на всяка транзакция, докато подписващият потвърждава в приложението. */
  @POST
  @Path("/{id}/cloud/status")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response cloudStatus(@PathParam("id") Long id, List<String> transactionIds) {
    try {
      gated(id, "sign a NAP submission");
      Map<String, String> statuses =
          Beans.get(DeclarationsCloudSignAndSubmitService.class).status(transactionIds);
      boolean allCompleted =
          !statuses.isEmpty() && statuses.values().stream().allMatch("COMPLETED"::equals);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("ok", true);
      body.put("statuses", statuses);
      body.put("allCompleted", allCompleted);
      return Response.ok(body).build();
    } catch (Exception e) {
      return refusal(e);
    }
  }

  /** СТЪПКА 3: сваля и проверява подписите и прави ЕДНА заявка към НАП. */
  @POST
  @Path("/{id}/cloud/submit")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response cloudSubmit(@PathParam("id") Long id, Map<String, String> transactionsByFileId) {
    try {
      NapSubmission submission = gated(id, "submit a declaration to NAP");
      Map<Long, String> byFileId = new LinkedHashMap<>();
      if (transactionsByFileId != null) {
        for (Map.Entry<String, String> entry : transactionsByFileId.entrySet()) {
          try {
            byFileId.put(Long.valueOf(entry.getKey().trim()), entry.getValue());
          } catch (NumberFormatException bad) {
            throw new DeclarationsEvidenceException(
                I18n.get("Unknown file in the submission request; the submission was refused."));
          }
        }
      }
      NapSubmission sent =
          Beans.get(DeclarationsCloudSignAndSubmitService.class)
              .collectAndSubmit(submission, byFileId);
      return Response.ok(outcome(sent)).build();
    } catch (Exception e) {
      return refusal(e);
    }
  }

  /**
   * Приема подписите от локалния компонент, проверява ги криптографски върху точните заключени
   * байтове и прави ЕДНА заявка към НАП. Нищо от подписа не се записва.
   */
  @POST
  @Path("/{id}/submit")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response submit(@PathParam("id") Long id, List<BrowserSignedFile> signed) {
    try {
      NapSubmission submission = gated(id, "submit a declaration to NAP");
      NapSubmission sent =
          Beans.get(DeclarationsBrowserSignAndSubmitService.class)
              .submitBrowserSigned(submission, signed);
      return Response.ok(outcome(sent)).build();
    } catch (Exception e) {
      return refusal(e);
    }
  }

  /** Еднакъв отговор за двата метода: какво е станало с подаването след единствената заявка. */
  protected Map<String, Object> outcome(NapSubmission sent) {
    Map<String, Object> body = new LinkedHashMap<>();
    String status = sent.getSubmissionStatusSelect();
    body.put("status", status);
    body.put("entryNumber", sent.getEntryNumber());
    body.put("documentId", sent.getDocumentId());
    body.put("ok", NapSubmissionRepository.STATUS_SUBMITTED.equals(status));
    body.put("message", message(sent, status));
    return body;
  }

  protected String message(NapSubmission sent, String status) {
    if (NapSubmissionRepository.STATUS_SUBMITTED.equals(status)) {
      return String.format(
          I18n.get(
              "The declaration was accepted for processing by NAP (incoming no. %s). The final"
                  + " outcome is checked with “Refresh the result from NAP”."),
          sent.getEntryNumber() == null ? "" : sent.getEntryNumber());
    }
    if (NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(status)) {
      return I18n.get(
          "Unclear outcome: it is not known whether NAP accepted the submission."
              + " Resubmitting is blocked. First check the result or record the outcome"
              + " manually.");
    }
    if (NapSubmissionRepository.STATUS_REJECTED.equals(status)) {
      return String.format(
          I18n.get("NAP rejected the submission: %s"),
          sent.getErrorMessage() == null ? "" : sent.getErrorMessage());
    }
    return I18n.get("The submission was not accepted. Check the status and the audit.");
  }

  /** Same gate as the buttons: capability, active company and write on the record from the DB. */
  protected NapSubmission gated(Long id, String action) {
    NapSubmission submission = Beans.get(NapSubmissionRepository.class).find(id);
    if (submission == null) {
      throw new DeclarationsEvidenceException(I18n.get("There is no submission."));
    }
    return NapFilingGate.require(action, submission);
  }

  /** Clear, human refusal — never a stack trace, never anything about keys or signatures. */
  protected Response refusal(Exception e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    if (e instanceof QesException
        || e instanceof DeclarationsEvidenceException
        || e instanceof NapFilingLockedException) {
      body.put("message", e.getMessage());
      return Response.status(Response.Status.BAD_REQUEST).entity(body).build();
    }
    body.put(
        "message",
        String.format(I18n.get("The submission was not carried out: %s"), e.getMessage()));
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
  }
}
