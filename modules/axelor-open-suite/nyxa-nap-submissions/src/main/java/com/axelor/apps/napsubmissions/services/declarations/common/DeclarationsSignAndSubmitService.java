package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.common.signing.UserQesProfileService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaFiles;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ОБЩОТО ЯДРО НА ДВАТА РЕАЛНИ ПЪТЯ „ПОДПИШИ И ИЗПРАТИ“ за Д1/Д6.
 *
 * <p>Има точно два начина, по които подаване стига до НАП по API, и те се различават само по
 * ПРОИЗХОДА на подписа:
 *
 * <ul>
 *   <li>{@link DeclarationsBrowserSignAndSubmitService} — USB КЕП на машината на потребителя,
 *       подписан от локалния компонент (BISS) в браузъра;
 *   <li>{@link DeclarationsCloudSignAndSubmitService} — облачен КЕП (Evrotrust), подписан след
 *       потвърждение в приложението на доставчика.
 * </ul>
 *
 * <p>Този клас не подписва нищо сам. Той държи това, което и двата пътя ползват еднакво: ТОЧНИТЕ
 * заключени байтове на всеки файл, реда на файловете, одита на опита и записването на това С ЧИЙ
 * КЕП се подписва. Самият подпис живее само в паметта ({@link TransientQesSignature}) и се изхвърля
 * веднага след единствената заявка към НАП.
 *
 * <p>⚠️ Сървърен „локален КЕП адаптер“ НЯМА и не може да има: USB токенът е на машината на
 * потребителя, а Java сървърът няма достъп до него. Предишният {@code LocalQesComponent} (и
 * неговият fail-closed вариант) беше точно такъв — винаги отказваше — и е премахнат заедно с
 * бутона, който водеше към него.
 */
@Singleton
public class DeclarationsSignAndSubmitService {

  protected final DeclarationsNapSubmitService submitService;
  protected final NapAuditService auditService;
  protected final UserQesProfileService profileService;

  @Inject
  public DeclarationsSignAndSubmitService(
      DeclarationsNapSubmitService submitService,
      NapAuditService auditService,
      UserQesProfileService profileService) {
    this.submitService = submitService;
    this.auditService = auditService;
    this.profileService = profileService;
  }

  /**
   * ЗАПИСВА С ЧИЙ КЕП СЕ ПОДПИСВА — задължителна стъпка преди всяко подаване.
   *
   * <p>НАП иска {@code userPin}/{@code userPinType} на АВТОРА, тоест на собственика на сертификата
   * в {@code userSignatureBase64}, а {@link DeclarationsNapSubmitService} ги чете единствено от
   * профила, записан върху подаването. Докато никой не го записваше, всяко подаване на Д1/Д6 по API
   * спираше на „Това подаване не пази с чий КЕП е подписано“ — затова и двата реални пътя минават
   * оттук.
   *
   * <p>Подписващият е ТОЗИ, КОЙТО натиска бутона: неговият токен или облачен профил ще произведе
   * подписа. Записва се върху ЗАКЛЮЧЕНОТО подаване (което {@code RegulatoryImmutabilityGuard}
   * нарочно допуска) и се замразява от {@code signed} нататък.
   *
   * <p>⚠️ Профилът съдържа само идентификатор по смисъла на НАП (ЕГН/ЛНЧ/сл. №/ЕИК) — никога код за
   * отключване на КЕП.
   */
  public UserQesProfile recordSigner(NapSubmission submission) {
    if (submission == null) {
      throw new DeclarationsEvidenceException(I18n.get("There is no submission."));
    }
    UserQesProfile profile = profileService.requireEnabledProfile(AuthUtils.getUser());
    UserQesProfile recorded = submission.getQesSignerProfile();
    if (recorded != null && recorded.getId() != null && !recorded.getId().equals(profile.getId())) {
      // Вече свързано с друг подписващ: НАП не може да бъде уведомена за двама автори.
      throw new DeclarationsEvidenceException(
          I18n.get(
              "This submission is already linked to the QES profile of another signer. It must be"
                  + " signed by the same person, or a new submission must be made."));
    }
    submission.setQesSignerProfile(profile);
    return profile;
  }

  /** Прочита ТОЧНИТЕ заключени байтове на файла от финализирания payload артефакт. */
  protected byte[] readLockedPayload(NapSubmissionFile file) {
    NapArtifact payload = file.getPayloadArtifact();
    if (payload == null
        || !Boolean.TRUE.equals(payload.getFinalized())
        || payload.getMetaFile() == null) {
      throw new DeclarationsEvidenceException(
          I18n.get("There is no finalized file (payload) to submit."));
    }
    try {
      return Files.readAllBytes(MetaFiles.getPath(payload.getMetaFile()));
    } catch (Exception e) {
      throw new DeclarationsEvidenceException(
          I18n.get("The locked submission data cannot be read."));
    }
  }

  /** Одит на опита за подписване и подаване — само файлове и отпечатъци, без тайни. */
  protected void logAttempt(NapSubmission submission, String details) {
    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_API_SUBMISSION,
        submission.getSubmissionStatusSelect(),
        submission.getSubmissionStatusSelect(),
        String.format(
            I18n.get("Signing and submission (the signature is not stored): %s"), details));
  }

  protected List<NapSubmissionFile> orderedFiles(NapSubmission submission) {
    List<NapSubmissionFile> files = new ArrayList<>();
    if (submission.getSubmissionFileList() != null) {
      files.addAll(submission.getSubmissionFileList());
    }
    files.sort(Comparator.comparing(f -> f.getSequence() == null ? 0 : f.getSequence()));
    return files;
  }

  protected static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
