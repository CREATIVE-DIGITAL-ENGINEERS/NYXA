package com.axelor.apps.bglocalization.service.vat.submission;

import com.axelor.apps.bglocalization.db.BgVatPackage;
import com.axelor.apps.bglocalization.db.BgVatPackageFile;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.BgVatSubmission;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageEventRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatSubmissionRepository;
import com.axelor.apps.bglocalization.service.vat.BgVatHashUtil;
import com.axelor.apps.bglocalization.service.vat.BgVatPackageService;
import com.axelor.apps.bglocalization.service.vat.BgVatPartyName;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.auth.AuthUtils;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * VAT-specific, self-contained submission workflow against the LOCAL MOCK server. Each send creates
 * a new immutable {@link BgVatSubmission} attempt bound to the exact {@link BgVatPackage} (and thus
 * its four files) that was transmitted. Reuses the package + append-only event-log infrastructure
 * ({@link BgVatPackageService}); deliberately does NOT reuse {@code NapSubmission} (no cross-module
 * dependency on nyxa-nap-submissions).
 *
 * <p>Safeguards: send requires a usable (enabled + local) gateway, a ready non-stale package and
 * all four files; a pending attempt (submitting / outcome-unknown) blocks a new send so a timed-out
 * attempt is never auto re-POSTed. Status checks are always safe to retry.
 */
@Singleton
public class BgVatSubmissionService {

  protected final BgVatSubmissionRepository submissionRepo;
  protected final BgVatPackageRepository packageRepo;
  protected final BgVatPackageService packageService;
  protected final VatSubmissionGateway gateway;

  @Inject
  public BgVatSubmissionService(
      BgVatSubmissionRepository submissionRepo,
      BgVatPackageRepository packageRepo,
      BgVatPackageService packageService,
      VatSubmissionGateway gateway) {
    this.submissionRepo = submissionRepo;
    this.packageRepo = packageRepo;
    this.packageService = packageService;
    this.gateway = gateway;
  }

  /**
   * Create and send a new attempt. Throws {@link IllegalStateException} (surfaced by the
   * controller) when a precondition fails. {@code exportRequest} is the same request the generator
   * uses — needed for the up-to-date staleness check.
   */
  @Transactional
  public BgVatSubmission submit(
      BgVatReturn vatReturn, BgVatExportRequestDto exportRequest, String scenario) {
    if (!gateway.isUsable()) {
      throw new IllegalStateException(
          "Локалното mock подаване е изключено. Активирайте с -Dnyxa.vat.mock.enabled=true и"
              + " локален URL (по подразбиране "
              + BgVatMockConfig.DEFAULT_URL
              + ").");
    }

    BgVatPackage pkg = packageService.findLatestPrepared(vatReturn);
    if (pkg == null) {
      throw new IllegalStateException("Няма подготвен пакет. Първо генерирайте.");
    }
    if (BgVatPackageRepository.READINESS_ERRORS.equals(pkg.getReadinessSelect())) {
      throw new IllegalStateException(
          "Пакетът има блокиращи грешки — коригирайте и генерирайте отново.");
    }
    List<BgVatPackageFile> files = orderedFiles(pkg);
    if (files.size() < 4) {
      throw new IllegalStateException(
          "Пакетът не съдържа и четирите файла (PRODAGBI/POKUPKI/DEKLAR/VIES).");
    }
    if (packageService.refreshStaleness(pkg, exportRequest)) {
      throw new IllegalStateException(
          "Данните са променени след генерирането — генерирайте отново преди изпращане.");
    }
    if (hasPendingAttempt(vatReturn)) {
      throw new IllegalStateException(
          "Има незавършен опит (изпраща се / неизвестен изход). Проверете статуса — не изпращайте"
              + " повторно.");
    }

    String packageSha = packageSha(files);

    BgVatSubmission attempt = new BgVatSubmission();
    attempt.setBgVatReturn(vatReturn);
    attempt.setBgVatPackage(pkg);
    attempt.setCompany(vatReturn.getCompany());
    attempt.setPeriod(vatReturn.getPeriod());
    attempt.setTargetSelect(BgVatSubmissionRepository.TARGET_LOCAL_MOCK);
    attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_SUBMITTING);
    attempt.setAttemptNumber(nextAttemptNumber(vatReturn));
    attempt.setMockScenario(scenario);
    attempt.setPackageSha256(packageSha);
    attempt.setSubmittedOn(LocalDateTime.now());
    attempt.setSubmittedBy(AuthUtils.getUser());
    attempt = submissionRepo.save(attempt);

    VatSubmissionRequest request = buildRequest(vatReturn, pkg, files, packageSha, scenario);
    attempt.setRequestMeta(
        "target=LOCAL_MOCK; scenario=" + scenario + "; files=" + request.getFilesBase64().keySet());

    VatSubmissionResult result = gateway.submit(request);
    applyResult(attempt, result, true);
    return submissionRepo.save(attempt);
  }

  /** Poll the mock for the latest status of an attempt. Safe to retry; never re-sends. */
  @Transactional
  public BgVatSubmission refreshStatus(BgVatSubmission attemptCtx) {
    BgVatSubmission attempt = submissionRepo.find(attemptCtx.getId());
    if (attempt == null) {
      return attemptCtx;
    }
    if (attempt.getSubmissionRef() == null || attempt.getSubmissionRef().isEmpty()) {
      attempt.setLastStatusCheckOn(LocalDateTime.now());
      packageService.logEvent(
          attempt.getBgVatPackage(),
          attempt.getBgVatReturn(),
          BgVatPackageEventRepository.EVENT_STATUS_CHECKED,
          "Няма mock идентификатор за проверка (опит " + attempt.getAttemptNumber() + ").");
      return submissionRepo.save(attempt);
    }
    VatSubmissionResult result = gateway.checkStatus(attempt.getSubmissionRef());
    applyResult(attempt, result, false);
    return submissionRepo.save(attempt);
  }

  // ---------------------------------------------------------------------------

  protected void applyResult(
      BgVatSubmission attempt, VatSubmissionResult result, boolean submitting) {
    if (result.getHttpStatus() > 0) {
      attempt.setHttpStatus(result.getHttpStatus());
    }
    if (result.getCorrelationId() != null) {
      attempt.setCorrelationId(result.getCorrelationId());
    }
    if (result.getSubmissionRef() != null) {
      attempt.setSubmissionRef(result.getSubmissionRef());
    }
    if (result.getEntryNumber() != null) {
      attempt.setEntryNumber(result.getEntryNumber());
    }
    if (result.getExternalStatus() != null) {
      attempt.setExternalStatus(result.getExternalStatus());
    }
    attempt.setErrorCode(result.getErrorCode());
    attempt.setErrorMessage(result.getErrorMessage());
    if (result.getRawBody() != null) {
      attempt.setResponseMeta(result.getRawBody());
    }
    if (!submitting) {
      attempt.setLastStatusCheckOn(LocalDateTime.now());
    }

    String eventType;
    if (!result.isConfigured()) {
      attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_TECHNICAL_ERROR);
      eventType = BgVatPackageEventRepository.EVENT_SUBMISSION_ERROR;
    } else if (result.isOutcomeUnknown()) {
      attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_OUTCOME_UNKNOWN);
      eventType = BgVatPackageEventRepository.EVENT_OUTCOME_UNKNOWN;
    } else if (!result.isSuccess()) {
      // Transport / HTTP error. On a status check keep the prior status (retryable); on submit it
      // is
      // a technical error for this attempt.
      if (submitting) {
        attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_TECHNICAL_ERROR);
      }
      eventType =
          submitting
              ? BgVatPackageEventRepository.EVENT_SUBMISSION_ERROR
              : BgVatPackageEventRepository.EVENT_STATUS_CHECKED;
    } else {
      eventType = mapSuccess(attempt, result.getExternalStatus(), submitting);
    }

    packageService.logEvent(
        attempt.getBgVatPackage(),
        attempt.getBgVatReturn(),
        eventType,
        (submitting ? "Изпращане" : "Проверка на статус")
            + " (опит "
            + attempt.getAttemptNumber()
            + "): статус="
            + attempt.getStatusSelect()
            + (attempt.getEntryNumber() != null ? ", вх.№=" + attempt.getEntryNumber() : "")
            + (attempt.getErrorMessage() != null ? ", грешка=" + attempt.getErrorMessage() : ""));
  }

  /** Map the mock's external status onto the domain status; return the event type. */
  protected String mapSuccess(BgVatSubmission attempt, String externalStatus, boolean submitting) {
    String status = externalStatus == null ? "" : externalStatus.toLowerCase();
    switch (status) {
      case "accepted":
        attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_ACCEPTED);
        return BgVatPackageEventRepository.EVENT_ACCEPTED;
      case "rejected":
        attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_REJECTED);
        return BgVatPackageEventRepository.EVENT_REJECTED;
      case "duplicate":
        attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_DUPLICATE);
        return BgVatPackageEventRepository.EVENT_DUPLICATE;
      case "processing":
        attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_PROCESSING);
        return submitting
            ? BgVatPackageEventRepository.EVENT_SUBMITTED
            : BgVatPackageEventRepository.EVENT_STATUS_CHECKED;
      default:
        attempt.setStatusSelect(BgVatSubmissionRepository.STATUS_SUBMITTED);
        return submitting
            ? BgVatPackageEventRepository.EVENT_SUBMITTED
            : BgVatPackageEventRepository.EVENT_STATUS_CHECKED;
    }
  }

  protected VatSubmissionRequest buildRequest(
      BgVatReturn vatReturn,
      BgVatPackage pkg,
      List<BgVatPackageFile> files,
      String packageSha,
      String scenario) {
    VatSubmissionRequest request = new VatSubmissionRequest();
    request.setCompanyVatNumber(
        vatReturn.getCompany() != null && vatReturn.getCompany().getPartner() != null
            ? vatReturn.getCompany().getPartner().getTaxNbr()
            : null);
    request.setCompanyName(BgVatPartyName.ofCompany(vatReturn.getCompany()));
    request.setPeriod(vatReturn.getPeriod());
    request.setPackageSha256(packageSha);
    request.setScenario(scenario);
    for (BgVatPackageFile file : files) {
      request.addFile(file.getFileName(), base64(file.getMetaFile()));
    }
    return request;
  }

  protected String base64(MetaFile metaFile) {
    if (metaFile == null) {
      return "";
    }
    try {
      byte[] bytes = Files.readAllBytes(MetaFiles.getPath(metaFile));
      return Base64.getEncoder().encodeToString(bytes);
    } catch (Exception e) {
      return "";
    }
  }

  protected List<BgVatPackageFile> orderedFiles(BgVatPackage pkg) {
    List<BgVatPackageFile> files =
        pkg.getPackageFileList() == null
            ? new ArrayList<>()
            : new ArrayList<>(pkg.getPackageFileList());
    files.sort((a, b) -> nz(a.getScope()).compareTo(nz(b.getScope())));
    return files;
  }

  protected String packageSha(List<BgVatPackageFile> files) {
    StringBuilder sb = new StringBuilder();
    for (BgVatPackageFile file : files) {
      sb.append(nz(file.getScope())).append("=").append(nz(file.getSha256())).append(";");
    }
    return BgVatHashUtil.sha256Hex(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  protected boolean hasPendingAttempt(BgVatReturn vatReturn) {
    return submissionRepo
            .all()
            .filter("self.bgVatReturn.id = :r AND self.statusSelect IN (:submitting, :unknown)")
            .bind("r", vatReturn.getId())
            .bind("submitting", BgVatSubmissionRepository.STATUS_SUBMITTING)
            .bind("unknown", BgVatSubmissionRepository.STATUS_OUTCOME_UNKNOWN)
            .count()
        > 0;
  }

  protected int nextAttemptNumber(BgVatReturn vatReturn) {
    Long count =
        submissionRepo
            .all()
            .filter("self.bgVatReturn.id = :r")
            .bind("r", vatReturn.getId())
            .count();
    return (int) (count == null ? 0 : count) + 1;
  }

  public BgVatSubmission findLatest(BgVatReturn vatReturn) {
    if (vatReturn == null || vatReturn.getId() == null) {
      return null;
    }
    return submissionRepo
        .all()
        .filter("self.bgVatReturn.id = :r")
        .bind("r", vatReturn.getId())
        .order("-attemptNumber")
        .order("-id")
        .fetchOne();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }
}
