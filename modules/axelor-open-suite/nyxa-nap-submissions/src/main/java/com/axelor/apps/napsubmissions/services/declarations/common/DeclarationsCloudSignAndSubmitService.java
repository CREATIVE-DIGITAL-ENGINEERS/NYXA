package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.QesSignature;
import com.axelor.apps.napsubmissions.common.signing.QesSigningRequest;
import com.axelor.apps.napsubmissions.common.signing.QesSigningSession;
import com.axelor.apps.napsubmissions.common.signing.QesSigningStatusResponse;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.common.signing.UnconfiguredCloudQesProvider;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * „ПОДПИШИ И ИЗПРАТИ“ ПРЕЗ ОБЛАЧЕН КЕП (Evrotrust) — вторият реален път до НАП.
 *
 * <p>Различава се от {@link DeclarationsBrowserSignAndSubmitService} само по ПРОИЗХОДА на подписа:
 * там подписва USB токенът на машината на потребителя през локалния компонент, тук подписва
 * облачният доставчик, след като човекът потвърди в приложението си. Всичко останало е същото:
 * подписът се проверява криптографски върху точните заключени байтове и се прави ЕДНА заявка към
 * НАП.
 *
 * <p><b>Потокът, както е документиран (docs.evrotrust.com), в три отделни заявки:</b>
 *
 * <ol>
 *   <li>{@link #start} — по един {@code /document/hash/online} за всеки файл, върху Base64 SHA-256
 *       на точните заключени байтове. Връща по един {@code transactionID} на файл;
 *   <li>{@link #status} — {@code /document/status} за всяка транзакция, докато човекът потвърждава
 *       в приложението си (pending → signed / rejected / expired);
 *   <li>{@link #collectAndSubmit} — за всяка завършила транзакция сваля детачния PKCS#7 и
 *       сертификата, проверява ги и подава ВЕДНЪЖ.
 * </ol>
 *
 * <p><b>Защо на три заявки, а не една дълга.</b> Правилото „подписът и сертификатът не се пазят
 * трайно“ се спазва буквално: между стъпките не се записва НИЩО освен {@code transactionID}, който
 * не е тайна и живее в отворената страница на подписващия. Самият подпис се сваля чак в последната
 * заявка, проверява се и умира с нея ({@code finally}). Рестарт на сървъра губи връзката
 * транзакция→дайджест и подписването се отказва ясно, вместо да се подаде нещо непроверено.
 *
 * <p><b>Нашата двойка ключове е задължителна.</b> {@code /document/hash/online} иска НАШ {@code
 * publicKey} и доставчикът шифрова резултата с него, затова стартирането отказва още ПРЕДИ мрежата,
 * ако липсва публичният ключ ИЛИ източникът на частния — иначе човек би потвърдил подпис, който
 * Nyxa после не може да прочете. Свалянето се разшифрова по документираната процедура (RSA PKCS#1
 * v1.5 → Rijndael-256-CBC на парчета → вътрешен ZIP) и от вътрешния ZIP излиза САМО детачен PKCS#7;
 * всичко друго е отказ с конкретна причина. Нищо не се симулира.
 *
 * <p>⚠️ Потокът е ONLINE. Offline потокът е отделна редица с отделни endpoint-и и не се смесва с
 * този по сходство на имената.
 *
 * <p>⚠️ Непроверимо без жив отговор от Evrotrust: че реалният {@code /document/download} връща
 * точно ZIP-а, който документацията описва. Докато това не се потвърди срещу sandbox, пътят е
 * проверен само срещу вектори, построени по същия текст.
 */
@Singleton
public class DeclarationsCloudSignAndSubmitService {

  protected final DeclarationsSignAndSubmitService base;
  protected final DeclarationsNapSubmitService submitService;
  protected final CloudQesProvider provider;

  @Inject
  public DeclarationsCloudSignAndSubmitService(
      DeclarationsSignAndSubmitService base,
      DeclarationsNapSubmitService submitService,
      CloudQesProvider provider) {
    this.base = base;
    this.submitService = submitService;
    this.provider = provider;
  }

  /** Една стартирана транзакция: кой файл, коя транзакция, върху кой дайджест. */
  public static final class StartedSignature {
    public final Long fileId;
    public final String fileName;
    public final String transactionId;
    public final String payloadSha256Hex;

    StartedSignature(Long fileId, String fileName, String transactionId, String sha256Hex) {
      this.fileId = fileId;
      this.fileName = fileName;
      this.transactionId = transactionId;
      this.payloadSha256Hex = sha256Hex;
    }
  }

  /**
   * СТЪПКА 1 — стартира подписването на всеки заключен файл при доставчика и записва КОЙ подписва.
   * Не чака потвърждение: връща транзакциите, по които страницата ще следи статуса.
   */
  public List<StartedSignature> start(NapSubmission submission) {
    List<NapSubmissionFile> files = requireFiles(submission);
    UserQesProfile profile = base.recordSigner(submission);
    String signerRef = requireCloudProfile(profile);

    List<StartedSignature> started = new ArrayList<>();
    for (NapSubmissionFile file : files) {
      byte[] payload = base.readLockedPayload(file);
      QesSigningSession session =
          provider.startSigning(
              new QesSigningRequest(payload, file.getSubmittedFileName(), signerRef));
      started.add(
          new StartedSignature(
              file.getId(),
              file.getSubmittedFileName(),
              session.getExternalRequestId(),
              RegulatoryDigest.sha256Hex(payload)));
    }
    return started;
  }

  /**
   * СТЪПКА 2 — текущото състояние на всяка транзакция. Връща {@code pending} само докато ВСИЧКИ са
   * в ход; един отказ/изтекъл срок прекратява целия опит (частично подписано подаване не се
   * подава).
   */
  public Map<String, String> status(List<String> transactionIds) {
    Map<String, String> byTransaction = new LinkedHashMap<>();
    if (transactionIds == null || transactionIds.isEmpty()) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          I18n.get("No cloud signing has been started to check."));
    }
    for (String transactionId : transactionIds) {
      QesSigningStatusResponse response = provider.pollStatus(transactionId);
      if (response.getStatus().isTerminal() && response.getSignature() == null) {
        throw new QesException(
            QesException.Category.INVALID_REQUEST,
            String.format(
                I18n.get("The signing was terminated by the provider: %s"),
                nullSafe(response.getReason())));
      }
      byTransaction.put(transactionId, response.getStatus().name());
    }
    return byTransaction;
  }

  /**
   * СТЪПКА 3 — сваля и проверява подписите и прави ЕДНА заявка към НАП. Преходният материал се
   * изхвърля в {@code finally}; при повторен опит се подписва наново.
   *
   * @param transactionsByFileId файл → транзакция, както стъпка 1 ги е върнала
   */
  public NapSubmission collectAndSubmit(
      NapSubmission submission, Map<Long, String> transactionsByFileId) throws AxelorException {
    List<NapSubmissionFile> files = requireFiles(submission);
    base.recordSigner(submission);

    Map<NapSubmissionFile, TransientQesSignature> signatures = new IdentityHashMap<>();
    try {
      StringJoiner details = new StringJoiner(", ");
      for (NapSubmissionFile file : files) {
        String transactionId =
            transactionsByFileId == null ? null : transactionsByFileId.get(file.getId());
        if (transactionId == null || transactionId.isBlank()) {
          throw new QesException(
              QesException.Category.INVALID_REQUEST,
              I18n.get("A submission file has no signing started; nothing was sent."));
        }
        QesSigningStatusResponse response = provider.pollStatus(transactionId);
        QesSignature signature = response.getSignature();
        if (signature == null) {
          throw new QesException(
              QesException.Category.INVALID_REQUEST,
              I18n.get("Signing has not finished yet for a submission file; wait and try again."));
        }
        // Дайджестът, към който доставчикът е обвързал заявката, срещу текущите заключени байтове.
        String currentSha = RegulatoryDigest.sha256Hex(base.readLockedPayload(file));
        if (!currentSha.equals(signature.getSignedPayloadSha256Hex())) {
          throw new QesException(
              QesException.Category.INVALID_REQUEST,
              I18n.get(
                  "The signature was produced over different data; the submission was refused."));
        }
        signatures.put(
            file,
            new TransientQesSignature(
                signature.getDetachedPkcs7(), signature.getCertificateBytes(), currentSha));
        details.add(
            DeclarationsSignAndSubmitService.nullSafe(file.getFileDocumentTypeSelect())
                + " SHA-256="
                + currentSha);
      }
      // Одит на ОПИТА: кои файлове и техните отпечатъци. ⚠️ Никога сертификат, PKCS#7 или тайна.
      base.logAttempt(submission, String.format(I18n.get("cloud QES — %s"), details));
      // Криптографската проверка върху точните байтове е в evidenceService.verifyTransient —
      // непосредствено преди мрежата, за двата пътя еднакво.
      return submitService.submitSignedTransient(submission, signatures);
    } finally {
      signatures.clear();
    }
  }

  protected List<NapSubmissionFile> requireFiles(NapSubmission submission) {
    if (submission == null) {
      throw new DeclarationsEvidenceException(I18n.get("There is no submission."));
    }
    List<NapSubmissionFile> files = base.orderedFiles(submission);
    if (files.isEmpty()) {
      throw new DeclarationsEvidenceException(I18n.get("The submission has no files."));
    }
    return files;
  }

  /**
   * Референцията към облачния КЕП се ИЗВЕЖДА от записаното ЕГН/ЛНЧ на подписващия — потребителят не
   * въвежда технически идентификатори при доставчика. Серийният номер на сертификата се взима от
   * самия доставчик ({@code /user/certificate/get}) преди старта.
   */
  protected String requireCloudProfile(UserQesProfile profile) {
    String configured = provider.getProviderId();
    // Нищо не е конфигурирано на сървъра: не вини профила на подписващия — кажи истинската причина.
    if (configured == null
        || configured.isBlank()
        || UnconfiguredCloudQesProvider.PROVIDER_ID.equals(configured)) {
      throw new QesException(
          QesException.Category.CONFIG,
          I18n.get(
              "Cloud signing (Evrotrust) is not configured on the server. Choose “USB QES” or sign"
                  + " and submit through the NAP portal."));
    }
    String pin = profile.getSignerPin();
    if (pin == null || pin.isBlank()) {
      throw new QesException(
          QesException.Category.CONFIG,
          I18n.get(
              "Your signing profile has no EGN/LNCh recorded (NAP › QES signing profiles); signing"
                  + " was refused."));
    }
    String type = profile.getSignerPinTypeSelect();
    if (type != null && !PIN_TYPE_EGN.equals(type) && !PIN_TYPE_LNCH.equals(type)) {
      throw new QesException(
          QesException.Category.CONFIG,
          I18n.get(
              "Evrotrust addresses the signer only by EGN/LNCh; the identifier type in your profile"
                  + " is neither. Choose “USB QES”."));
    }
    return "egn=" + pin.trim();
  }

  /** Стойности от {@code nap.pin.type.select}. */
  static final String PIN_TYPE_EGN = "IND_EGN";

  static final String PIN_TYPE_LNCH = "IND_LNCH";

  protected static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
