package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.common.signing.browser.BrowserQesAssembler;
import com.axelor.apps.napsubmissions.common.signing.browser.BrowserSignedFile;
import com.axelor.apps.napsubmissions.common.signing.browser.PortalSignedPkcs7;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.i18n.I18n;
import com.axelor.studio.db.AppNapSubmissions;
import com.axelor.studio.db.repo.AppNapSubmissionsRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * „ПОДПИШИ С USB КЕП И ИЗПРАТИ“ — ВАРИАНТЪТ, ПРИ КОЙТО ПОДПИСВАНЕТО СТАВА В БРАУЗЪРА.
 *
 * <p>Различава се от {@link DeclarationsCloudSignAndSubmitService} само по ПРОИЗХОДА на подписа:
 * тук той идва от локалния подписващ компонент на машината на потребителя (BISS/SCS на {@code
 * https://localhost:53952..53955}), защото USB токенът е на НЕГОВИЯ компютър, а Java сървърът няма
 * достъп до него. Останалото е едно и също: подписът живее само в паметта, проверява се
 * криптографски върху точните заключени байтове и веднага се прави ЕДНА заявка към НАП по същия
 * преходен път.
 *
 * <p><b>Какво НЕ влиза в Nyxa:</b> частният ключ и PIN-ът за отключване на токена. Те не напускат
 * устройството — браузърът изпраща само детачен PKCS#7 и публични сертификати, и те не се записват
 * нито в базата, нито в {@code MetaFile}, нито в одита или лог.
 *
 * <p><b>Къде се отказва:</b> недоверен/самоподписан/изтекъл сертификат и липсващ или излишен подпис
 * — в {@link BrowserQesAssembler}; подпис върху различни байтове или невалиден PKCS#7 — в {@code
 * DeclarationsEvidenceService#verifyTransient}, непосредствено преди мрежата. Отказът от
 * потребителя и липсващият компонент изобщо не стигат дотук: браузърът не праща нищо.
 */
@Singleton
public class DeclarationsBrowserSignAndSubmitService {

  protected final DeclarationsSignAndSubmitService base;
  protected final DeclarationsNapSubmitService submitService;
  protected final BrowserQesAssembler assembler;
  protected final NapAuditService auditService;
  protected final AppNapSubmissionsRepository appRepo;

  @Inject
  public DeclarationsBrowserSignAndSubmitService(
      DeclarationsSignAndSubmitService base,
      DeclarationsNapSubmitService submitService,
      BrowserQesAssembler assembler,
      NapAuditService auditService,
      AppNapSubmissionsRepository appRepo) {
    this.base = base;
    this.submitService = submitService;
    this.assembler = assembler;
    this.auditService = auditService;
    this.appRepo = appRepo;
  }

  /**
   * Точните заключени байтове, които браузърът трябва да подаде на локалния компонент — по файл, в
   * реда на подаването. Връща съдържание и неговия SHA-256, нищо друго.
   */
  public Map<NapSubmissionFile, byte[]> lockedPayloads(NapSubmission submission) {
    if (submission == null) {
      throw new DeclarationsEvidenceException(I18n.get("There is no submission."));
    }
    List<NapSubmissionFile> files = base.orderedFiles(submission);
    if (files.isEmpty()) {
      throw new DeclarationsEvidenceException(I18n.get("The submission has no files."));
    }
    Map<NapSubmissionFile, byte[]> payloads = new LinkedHashMap<>();
    for (NapSubmissionFile file : files) {
      payloads.put(file, base.readLockedPayload(file));
    }
    return payloads;
  }

  /**
   * Проверява подписите от браузъра и прави ЕДНА заявка към НАП. Преходният материал се изхвърля в
   * {@code finally} — при повторен опит се подписва наново.
   */
  public NapSubmission submitBrowserSigned(NapSubmission submission, List<BrowserSignedFile> signed)
      throws AxelorException {
    if (submission == null) {
      throw new DeclarationsEvidenceException(I18n.get("There is no submission."));
    }
    List<NapSubmissionFile> files = base.orderedFiles(submission);
    Map<NapSubmissionFile, TransientQesSignature> signatures =
        assembler.assemble(files, signed, isProductionEnvironment());
    // С ЧИЙ КЕП: НАП иска идентификатора на АВТОРА, а транспортът го чете само от този профил.
    base.recordSigner(submission);
    try {
      StringJoiner details = new StringJoiner(", ");
      for (NapSubmissionFile file : files) {
        details.add(
            DeclarationsSignAndSubmitService.nullSafe(file.getFileDocumentTypeSelect())
                + " SHA-256="
                + RegulatoryDigest.sha256Hex(base.readLockedPayload(file)));
      }
      // Одит на ОПИТА: кои файлове и техните отпечатъци. ⚠️ Никога сертификат, PKCS#7 или тайна.
      base.logAttempt(
          submission, String.format(I18n.get("local component in the browser — %s"), details));
      return submitService.submitSignedTransient(submission, signatures);
    } finally {
      signatures.clear();
    }
  }

  /**
   * ПОДПИСАНИТЕ В ПОРТАЛА НА EVROTRUST ФАЙЛОВЕ: потребителят е свалил заключените файлове, подписал
   * ги е в портала на доставчика и ги е качил обратно. Всеки качен PKCS#7 (детачен или прикрепен)
   * се превръща в детачния, който НАП иска, сертификатът минава през същата политика на доверие, а
   * криптографската проверка върху точните заключени байтове и общият сертификат — през {@code
   * verifyTransient}, както при USB КЕП. После ЕДНА заявка към НАП; нищо от подписа не се записва.
   *
   * @param uploadedByFileId качените байтове по идентификатор на файл от подаването
   */
  public NapSubmission submitPortalSigned(
      NapSubmission submission, Map<Long, byte[]> uploadedByFileId) throws AxelorException {
    if (submission == null) {
      throw new DeclarationsEvidenceException(I18n.get("There is no submission."));
    }
    List<NapSubmissionFile> files = base.orderedFiles(submission);
    if (files.isEmpty()) {
      throw new DeclarationsEvidenceException(I18n.get("The submission has no files."));
    }
    Map<Long, byte[]> uploaded = uploadedByFileId == null ? Map.of() : uploadedByFileId;
    java.util.Set<Long> expected = new java.util.HashSet<>();
    for (NapSubmissionFile file : files) {
      expected.add(file.getId());
    }
    for (Long id : uploaded.keySet()) {
      if (!expected.contains(id)) {
        throw new QesException(
            QesException.Category.INVALID_REQUEST,
            I18n.get(
                "A signed file was uploaded for a file outside this submission; the submission"
                    + " was refused."));
      }
    }

    boolean production = isProductionEnvironment();
    Map<NapSubmissionFile, TransientQesSignature> signatures = new java.util.IdentityHashMap<>();
    try {
      StringJoiner details = new StringJoiner(", ");
      for (NapSubmissionFile file : files) {
        byte[] locked = base.readLockedPayload(file);
        PortalSignedPkcs7.Parsed parsed =
            PortalSignedPkcs7.parse(
                uploaded.get(file.getId()), locked, file.getSubmittedFileName());
        assembler.trustPolicy().requireTrusted(parsed.signer, parsed.chain, production);
        String sha = RegulatoryDigest.sha256Hex(locked);
        signatures.put(
            file, new TransientQesSignature(parsed.detachedPkcs7, encoded(parsed.signer), sha));
        details.add(
            DeclarationsSignAndSubmitService.nullSafe(file.getFileDocumentTypeSelect())
                + " SHA-256="
                + sha);
      }
      base.recordSigner(submission);
      // Одит на ОПИТА: кои файлове и техните отпечатъци. ⚠️ Никога сертификат, PKCS#7 или тайна.
      base.logAttempt(
          submission, String.format(I18n.get("signed in the Evrotrust portal — %s"), details));
      return submitService.submitSignedTransient(submission, signatures);
    } finally {
      signatures.clear();
    }
  }

  protected byte[] encoded(java.security.cert.X509Certificate certificate) {
    try {
      return certificate.getEncoded();
    } catch (Exception e) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          I18n.get("The signer's certificate cannot be encoded; the submission was refused."));
    }
  }

  /**
   * Реалната среда на НАП ли е. Строго както в транспорта: нито TEST, нито PROD → отказ, вместо
   * тихо да се предположи по-слабото.
   */
  protected boolean isProductionEnvironment() {
    AppNapSubmissions app = appRepo.all().fetchOne();
    String env = app == null ? null : app.getEnvironmentSelect();
    if (AppNapSubmissionsRepository.ENVIRONMENT_PROD.equals(env)) {
      return true;
    }
    if (AppNapSubmissionsRepository.ENVIRONMENT_TEST.equals(env)) {
      return false;
    }
    throw new QesException(
        QesException.Category.CONFIG,
        I18n.get(
            "The NAP environment must be explicitly TEST or PRODUCTION; the submission was"
                + " refused."));
  }
}
