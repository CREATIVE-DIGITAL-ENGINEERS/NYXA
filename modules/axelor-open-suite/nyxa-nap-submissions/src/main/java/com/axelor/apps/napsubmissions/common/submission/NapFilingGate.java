package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import java.util.Set;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * ГЕЙТЪТ НА ВСЯКО ДЕЙСТВИЕ ВЪРХУ ПОДАВАНЕ — общ за всички видове документи (каталога на ролите §5,
 * решение 17–18.10.2026).
 *
 * <p><b>Способността е по ВИДА НА ДОКУМЕНТА, не по контролера.</b> Д1/Д6, справката по чл. 73 ал. 6
 * и ЕТЗ (регистърът на заетостта, чл. 62 ал. 3 КТ) са на ТРЗ ({@code hr.payroll.write}),
 * удостоверенията по Прил. 9 — на НОИ ({@code hr.noi.write}), всичко останало (SAF-T, чл. 55, чл.
 * 73 ал. 1) — на НАП ({@code accounting.nap.write}); {@code all.write} и {@code client.admin} са
 * във всяка от тях. С {@code .read} остават само прегледът и изтеглянето на вече генериран файл —
 * те минават през правото за четене на записа, не през тук.
 *
 * <p><b>Записът е ОТ БАЗАТА.</b> Видът се чете от реда, зареден по id, а не от формата: клиент може
 * да изпрати какъвто вид иска. Затова видът и връзката към документа са и неизменими след
 * създаването ({@code NapSubmissionManagementRepository}) — инак човек с право за запис на
 * подаванията щеше да „пребоядиса“ чуждо подаване в свое и да го отбележи.
 */
public final class NapFilingGate {

  /**
   * Справката по чл. 73 ал. 6 живее в bg-localization; тук е само името, за да няма обратна
   * зависимост. Съвпадението с класа се сверява в {@code BgFilingGatesTest}.
   */
  public static final String ART73_PARA6_MODEL =
      "com.axelor.apps.bglocalization.db.BgArt73Para6Report";

  /** Отказ, когато подаването го няма (ключът е английски, преводът — в messages_bg.csv). */
  static final String SUBMISSION_MISSING = /*$$(*/ "The submission does not exist." /*)*/;

  private NapFilingGate() {}

  /** Способността, която подава този вид документ. */
  public static Set<String> capabilityFor(NapSubmission submission) {
    String service = submission.getServiceCodeSelect();
    if (NapSubmissionRepository.SERVICE_NOI_APP9.equals(service)) {
      return Capabilities.NOI_WRITE;
    }
    if (NapSubmissionRepository.SERVICE_DECLARATIONS.equals(service)
        && (isPayrollDocument(submission.getServiceDocumentTypeSelect())
            || ART73_PARA6_MODEL.equals(submission.getRelatedModel()))) {
      return Capabilities.PAYROLL_WRITE;
    }
    return Capabilities.NAP_WRITE;
  }

  /**
   * Видовете по Declarations API, които подава ТРЗ: Д1/Д6 и двата ЕТЗ (решение 02.11.2026 — ЕТЗ е
   * запис за трудовия договор, не данъчна декларация).
   */
  static final Set<String> PAYROLL_DOCUMENTS =
      Set.of(
          ServiceDocumentType.DEC_1_6.name(),
          ServiceDocumentType.DEC_ETZ62.name(),
          ServiceDocumentType.DEC_ETZ123.name());

  /** ⚠️ {@code Set.of} не приема {@code null} в {@code contains} — SAF-T и FATCA нямат вид. */
  static boolean isPayrollDocument(String documentType) {
    return documentType != null && PAYROLL_DOCUMENTS.contains(documentType);
  }

  /**
   * Способността за вида + активното дружество + запис върху реда (с условието му по дружество).
   *
   * @param submission редът, зареден от базата по id
   */
  public static NapSubmission require(String action, NapSubmission submission) {
    if (submission == null) {
      throw new UnauthorizedException(I18n.get(SUBMISSION_MISSING));
    }
    CapabilityGate.requireRole(action, capabilityFor(submission));
    CapabilityGate.requireActiveCompany(submission.getCompany());
    CapabilityGate.requireWrite(NapSubmission.class, submission.getId());
    return submission;
  }
}
