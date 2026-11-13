package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * ПОДАДЕНОТО ПОДАВАНЕ Е ДОКАЗАТЕЛСТВО — какво от него още може да се пише и кое не се трие.
 *
 * <p>Чисти правила по старото и новото състояние (имената на полетата и стойностите им, както ги
 * дава Hibernate) — без база, за да ги делят слушателят ({@link NapSubmissionImmutabilityListener})
 * и тестовете.
 *
 * <p><b>Кога е доказателство.</b> Статусът е извън {@code draft} и {@code signing} — същото {@link
 * RegulatoryImmutabilityGuard#isSubmissionLocked}: подписано ({@code signed}), изпраща се, подадено
 * (вкл. ръчната отметка с входящ номер), неизвестен изход, прието, отхвърлено, иска корекция,
 * техническа грешка, отменена отметка. Статусът е по ЗАРЕДЕНОТО (записаното) състояние, не по
 * новото — иначе заявка, която връща статуса в чернова, сама би си отключила промяната.
 *
 * <p><b>Какво остава позволено след това</b> — само каквото процесът пише (решение от 18.10.2026):
 * статусът и резултатът от НАП/НОИ, входящият номер, протоколът, грешките, бележката за корекция
 * ({@link #SUBMISSION_AFTER_FILING}); при файла — изходът на НАП за него ({@link
 * #FILE_AFTER_FILING}). Всичко останало е замръзнало — и ново поле в домейна е замръзнало, докато
 * някой не го добави тук съзнателно.
 */
public final class NapSubmissionEvidence {

  private NapSubmissionEvidence() {}

  /**
   * Пише ги платформата при всеки запис (версия, одит, custom полета от Studio) — не съдържание.
   */
  static final Set<String> TECHNICAL = Set.of("version", "updatedOn", "updatedBy", "attrs");

  /**
   * Подаване след подаването: какво пишат процесите (файл:ред — в доклада на клона).
   *
   * <ul>
   *   <li>статус и външен статус — изходът (прието/отхвърлено/корекция/отменено, резултат от API);
   *   <li>{@code documentId}, {@code entryNumber}, {@code entryDate} — идентификаторите от API;
   *   <li>{@code napIncomingNumber} — входящият номер/протоколът, въведен ръчно;
   *   <li>{@code processedOn}, {@code lastStatusCheckOn} — кога е отговорил органът/проверено;
   *   <li>{@code errorCode}, {@code errorMessage}, {@code exceptionId}, {@code correlationId},
   *       {@code responseMeta} — отговорът и грешките;
   *   <li>{@code correctionReason} — „иска корекция“ и бележката при ръчно решен неизвестен изход.
   * </ul>
   */
  public static final Set<String> SUBMISSION_AFTER_FILING =
      Set.of(
          "submissionStatusSelect",
          "externalStatus",
          "documentId",
          "entryNumber",
          "entryDate",
          "napIncomingNumber",
          "processedOn",
          "lastStatusCheckOn",
          "errorCode",
          "errorMessage",
          "exceptionId",
          "correlationId",
          "responseMeta",
          "correctionReason");

  /**
   * Повторно изпращане: подписано или с техническа грешка, БЕЗ идентификатор от органа — там
   * изпращането (API или ръчната отметка) пише канала, момента и кой, и сертификата, с който е
   * изпратено (нужен за заявката за резултат). Подадено веднъж — вече не.
   */
  public static final Set<String> SUBMISSION_RESEND =
      Set.of("channelSelect", "submittedOn", "submittedBy", "signerCertificateBase64");

  /** Идентификаторите от органа: могат да се допишат или поправят, не и да се изчистят. */
  static final List<String> IDENTIFIERS = List.of("napIncomingNumber", "entryNumber", "documentId");

  /** Файл в подадено подаване: само изходът на НАП за него. */
  public static final Set<String> FILE_AFTER_FILING = Set.of("fileStatusSelect", "fileError");

  /** Подписан файл (КЕП) в още неподписано подаване — същото правило, по всеки път. */
  static final List<String> SIGNED_FILE_FIXED =
      List.of(
          "payloadArtifact",
          "signatureArtifact",
          "signerCertificateArtifact",
          "signerCertificateSha256",
          "qesRequestedSha256");

  /**
   * ЗАКЛЮЧЕНО, НО ОЩЕ НЕПОДПИСАНО ({@code locked}): съдържанието и изборът са замразени, но
   * подписването тепърва предстои. Затова тук се пазят ПОЛЕТАТА НА СЪДЪРЖАНИЕТО (същите, които
   * {@link RegulatoryImmutabilityGuard#checkSubmission} пази при заключено), а статусът,
   * подписващият профил и работните полета на подписването остават за процеса. Връщане в чернова е
   * ОТКАЗ — то би отключило съдържанието.
   */
  static final List<String> LOCKED_CONTENT_FIXED =
      List.of(
          "serviceCodeSelect",
          "serviceDocumentTypeSelect",
          "company",
          "period",
          "taxpayerPin",
          "taxpayerPinTypeSelect");

  /**
   * Подаването е ДОКАЗАТЕЛСТВО (подадено/подписано) при този статус: {@code signed} и нататък. ⚠️
   * НЕ включва само-заключеното {@code locked} — то е предподписна фаза, чието съдържание е
   * замразено, но статусът още законно се движи към подписване (виж {@link #LOCKED_CONTENT_FIXED} и
   * {@link RegulatoryImmutabilityGuard#isSignerLocked}).
   */
  public static boolean isEvidence(String status) {
    return RegulatoryImmutabilityGuard.isSignerLocked(status);
  }

  /**
   * Съдържанието/изборът са ЗАМРАЗЕНИ при този статус: {@code locked} и всичко след подписването.
   * Това заключва кои файлове носи подаването, периода и вида — но не и самото подписване.
   */
  public static boolean isSelectionLocked(String status) {
    return RegulatoryImmutabilityGuard.isSubmissionLocked(status);
  }

  // --- подаване --------------------------------------------------------------------------------

  /**
   * Промяна на подаване по старото и новото състояние.
   *
   * @throws RegulatoryImmutabilityException при замръзнало поле, връщане в чернова или изчистен
   *     идентификатор
   */
  public static void checkSubmissionUpdate(String[] names, Object[] oldState, Object[] state) {
    String before = (String) value(names, oldState, "submissionStatusSelect");
    if (NapSubmissionRepository.STATUS_LOCKED.equals(before)) {
      checkLockedSubmissionUpdate(names, oldState, state);
      return;
    }
    if (!isEvidence(before)) {
      return;
    }
    String what = "submitted НАП submission (status '" + before + "')";
    String after = (String) value(names, state, "submissionStatusSelect");
    if (!isEvidence(after)) {
      throw new RegulatoryImmutabilityException(
          "Cannot change 'submissionStatusSelect' of a "
              + what
              + " back to '"
              + after
              + "' — a filed submission is evidence; create a new submission instead");
    }
    boolean resend =
        (NapSubmissionRepository.STATUS_SIGNED.equals(before)
                || NapSubmissionRepository.STATUS_TECHNICAL_ERROR.equals(before))
            && !hasIdentifier(names, oldState);
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      if (skipped(name, oldState[i], state[i]) || same(oldState[i], state[i])) {
        continue;
      }
      if (SUBMISSION_AFTER_FILING.contains(name)) {
        if (IDENTIFIERS.contains(name) && isSet(oldState[i]) && !isSet(state[i])) {
          throw new RegulatoryImmutabilityException(
              "Cannot clear '" + name + "' of a " + what + " — it identifies the filing");
        }
        continue;
      }
      if (resend && SUBMISSION_RESEND.contains(name)) {
        continue;
      }
      throw refusal(name, what);
    }
  }

  /**
   * ЗАКЛЮЧЕНО, но още неподписано: замразява съдържанието и забранява отключване към чернова,
   * докато оставя статуса да върви напред (подписване/подадено) и подписващия профил да се запише.
   */
  static void checkLockedSubmissionUpdate(String[] names, Object[] oldState, Object[] state) {
    String after = (String) value(names, state, "submissionStatusSelect");
    if (after == null || NapSubmissionRepository.STATUS_DRAFT.equals(after)) {
      throw new RegulatoryImmutabilityException(
          "Cannot unlock a locked НАП submission back to '"
              + after
              + "' — its selection and period are frozen; create a new submission instead");
    }
    for (String field : LOCKED_CONTENT_FIXED) {
      if (!same(value(names, oldState, field), value(names, state, field))) {
        throw new RegulatoryImmutabilityException(
            "Cannot change '"
                + field
                + "' of a locked НАП submission — it is frozen until signing");
      }
    }
  }

  /** Триене на подаване: доказателството не се трие — за никого. */
  public static void checkSubmissionDelete(String status) {
    if (isEvidence(status)) {
      throw new RegulatoryImmutabilityException(
          "Cannot delete a submitted НАП submission (status '"
              + status
              + "') — a filed submission is evidence");
    }
  }

  // --- файл ------------------------------------------------------------------------------------

  /**
   * Промяна на файл. {@code parentFiled} — подаването, в което е бил, е доказателство; {@code
   * movedIntoFiled} — файлът се мести в подаване, което е доказателство.
   */
  public static void checkFileUpdate(
      String[] names,
      Object[] oldState,
      Object[] state,
      boolean parentFiled,
      boolean movedIntoFiled) {
    checkFileUpdate(names, oldState, state, parentFiled, movedIntoFiled, false, false);
  }

  /**
   * Промяна на файл, включваща ЗАКЛЮЧЕНОТО (но още неподписано) подаване: {@code parentLocked} —
   * подаването му е заключено; {@code movedIntoLocked} — файлът се мести в заключено подаване. В
   * заключено подаване полезният товар (байтовете) е замразен, но работните полета на подписването
   * остават за процеса; добавяне/местене се отказва (виж {@link #checkFileInsert}).
   */
  public static void checkFileUpdate(
      String[] names,
      Object[] oldState,
      Object[] state,
      boolean parentFiled,
      boolean movedIntoFiled,
      boolean parentLocked,
      boolean movedIntoLocked) {
    if (movedIntoFiled) {
      throw new RegulatoryImmutabilityException(
          "Cannot move a file into a submitted НАП submission; create a new submission instead");
    }
    if (parentFiled) {
      for (int i = 0; i < names.length; i++) {
        String name = names[i];
        if (skipped(name, oldState[i], state[i])
            || same(oldState[i], state[i])
            || FILE_AFTER_FILING.contains(name)) {
          continue;
        }
        throw refusal(name, "file of a submitted НАП submission");
      }
      return;
    }
    if (movedIntoLocked) {
      throw new RegulatoryImmutabilityException(
          "Cannot move a file into a locked НАП submission; create a new submission instead");
    }
    if (parentLocked) {
      // Съдържанието (полезният товар) е замразено; подписът/сертификатът/заявеният дайджест още се
      // пишат от подписването, затова тук се пази само полезният товар.
      if (!same(
          value(names, oldState, "payloadArtifact"), value(names, state, "payloadArtifact"))) {
        throw refusal("payloadArtifact", "file of a locked НАП submission");
      }
      return;
    }
    if (NapSubmissionFileRepository.QES_STATUS_COMPLETED.equals(
        value(names, oldState, "qesSigningStatusSelect"))) {
      for (int i = 0; i < names.length; i++) {
        if (SIGNED_FILE_FIXED.contains(names[i]) && !same(oldState[i], state[i])) {
          throw refusal(names[i], "signed file");
        }
      }
    }
  }

  /** Нов файл в подаване, което е доказателство, би сменил подаденото. */
  public static void checkFileInsert(boolean parentFiled) {
    checkFileInsert(parentFiled, false);
  }

  /** Нов файл в заключено (или подадено) подаване би сменил замразения избор. */
  public static void checkFileInsert(boolean parentFiled, boolean parentLocked) {
    if (parentFiled) {
      throw new RegulatoryImmutabilityException(
          "Cannot add a file to a submitted НАП submission; create a new submission instead");
    }
    if (parentLocked) {
      throw new RegulatoryImmutabilityException(
          "Cannot add a file to a locked НАП submission; create a new submission instead");
    }
  }

  /** Файл от подаване, което е доказателство, не се трие. */
  public static void checkFileDelete(boolean parentFiled) {
    checkFileDelete(parentFiled, false);
  }

  /** Файл от заключено (или подадено) подаване не се трие — изборът е замразен. */
  public static void checkFileDelete(boolean parentFiled, boolean parentLocked) {
    if (parentFiled) {
      throw new RegulatoryImmutabilityException(
          "Cannot delete a file of a submitted НАП submission — it is part of the evidence");
    }
    if (parentLocked) {
      throw new RegulatoryImmutabilityException(
          "Cannot delete a file of a locked НАП submission — its selection is frozen");
    }
  }

  // --- общо ------------------------------------------------------------------------------------

  /**
   * Не се сравняват: техническите полета и колекциите (обратната страна на one-to-many — редът на
   * подаването не се мени от тях; файловете и връзките пазят собствените си редове).
   */
  private static boolean skipped(String name, Object before, Object after) {
    return TECHNICAL.contains(name) || before instanceof Collection || after instanceof Collection;
  }

  /** Като при NapArtifact (по id за връзките), плюс празен низ = липсващ низ. */
  static boolean same(Object before, Object after) {
    if ((before == null || before instanceof String)
        && (after == null || after instanceof String)) {
      return blankToNull((String) before) == null
          ? blankToNull((String) after) == null
          : before.equals(after);
    }
    return NapArtifactImmutabilityListener.same(before, after);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** Номер, низ или число, различно от 0 ({@code documentId} по подразбиране е 0). */
  private static boolean isSet(Object value) {
    if (value instanceof String) {
      return !((String) value).isBlank();
    }
    if (value instanceof Number) {
      return ((Number) value).longValue() != 0L;
    }
    return value != null;
  }

  private static boolean hasIdentifier(String[] names, Object[] state) {
    for (String id : IDENTIFIERS) {
      if (isSet(value(names, state, id))) {
        return true;
      }
    }
    return false;
  }

  static Object value(String[] names, Object[] state, String name) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name)) {
        return state[i];
      }
    }
    return null;
  }

  private static RegulatoryImmutabilityException refusal(String field, String what) {
    return new RegulatoryImmutabilityException(
        "Cannot change '" + field + "' of a " + what + " — it is part of the evidence");
  }
}
