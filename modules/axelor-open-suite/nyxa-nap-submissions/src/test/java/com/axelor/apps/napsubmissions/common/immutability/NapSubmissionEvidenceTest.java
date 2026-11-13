package com.axelor.apps.napsubmissions.common.immutability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.auth.db.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Подаденото подаване е доказателство: какво още се пише, кое е замръзнало, кое не се трие.
 * Състоянията се строят като от Hibernate — имена и стойности, старо и ново.
 */
class NapSubmissionEvidenceTest {

  private static final String[] FILED = {
    NapSubmissionRepository.STATUS_SIGNED,
    NapSubmissionRepository.STATUS_SUBMITTING,
    NapSubmissionRepository.STATUS_SUBMITTED,
    NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN,
    NapSubmissionRepository.STATUS_ACCEPTED,
    NapSubmissionRepository.STATUS_REJECTED,
    NapSubmissionRepository.STATUS_CORRECTION_REQUIRED,
    NapSubmissionRepository.STATUS_TECHNICAL_ERROR,
    NapSubmissionRepository.STATUS_WITHDRAWN
  };

  /** Подаване, както го държи базата: подадено ръчно, с входящ номер. */
  private static Map<String, Object> filed() {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("name", "Подаване 1");
    s.put("serviceCodeSelect", NapSubmissionRepository.SERVICE_DECLARATIONS);
    s.put("serviceDocumentTypeSelect", "DEC_1_6");
    s.put("company", ref(new Company(), 1L));
    s.put("period", ref(new Period(), 2L));
    s.put("referenceDate", LocalDate.of(2026, 9, 1));
    s.put("channelSelect", NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    s.put("environmentSelect", NapSubmissionRepository.ENVIRONMENT_PROD);
    s.put("submissionStatusSelect", NapSubmissionRepository.STATUS_SUBMITTED);
    s.put("externalStatus", null);
    s.put("documentId", 0L);
    s.put("entryNumber", null);
    s.put("entryDate", null);
    s.put("napIncomingNumber", "ТЕСТ-0001");
    s.put("filedByInsuranceFund", Boolean.FALSE);
    s.put("attemptNumber", 1);
    s.put("idempotencyKey", "k-1");
    s.put("correlationId", null);
    s.put("submittedOn", LocalDateTime.of(2026, 10, 1, 0, 0));
    s.put("processedOn", null);
    s.put("lastStatusCheckOn", null);
    s.put("submittedBy", ref(new User(), 3L));
    s.put("signerCertificateBase64", null);
    s.put("taxpayerPin", "000000000");
    s.put("errorCode", null);
    s.put("errorMessage", null);
    s.put("exceptionId", null);
    s.put("requestMeta", null);
    s.put("responseMeta", null);
    s.put("correctionReason", null);
    s.put("previousSubmission", null);
    s.put("relatedModel", "x.Y");
    s.put("relatedRecordId", 7L);
    s.put("archived", null);
    s.put("submissionFileList", new ArrayList<>());
    s.put("version", 4);
    s.put("updatedOn", LocalDateTime.of(2026, 10, 1, 0, 0));
    s.put("updatedBy", ref(new User(), 3L));
    s.put("attrs", null);
    return s;
  }

  private static <T extends com.axelor.db.Model> T ref(T model, Long id) {
    model.setId(id);
    return model;
  }

  private static void update(Map<String, Object> before, Map<String, Object> after) {
    String[] names = before.keySet().toArray(new String[0]);
    Object[] old = before.values().toArray();
    Object[] now = new Object[names.length];
    for (int i = 0; i < names.length; i++) {
      now[i] = after.get(names[i]);
    }
    NapSubmissionEvidence.checkSubmissionUpdate(names, old, now);
  }

  private static Map<String, Object> with(Map<String, Object> state, String field, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(state);
    copy.put(field, value);
    return copy;
  }

  private static Map<String, Object> status(String status) {
    return with(filed(), "submissionStatusSelect", status);
  }

  // --- кога е доказателство ------------------------------------------------------------------

  @Test
  void everyStatusAfterSigningIsEvidence() {
    for (String status : FILED) {
      assertTrue(NapSubmissionEvidence.isEvidence(status), status);
    }
    assertFalse(NapSubmissionEvidence.isEvidence(NapSubmissionRepository.STATUS_DRAFT));
    assertFalse(NapSubmissionEvidence.isEvidence(NapSubmissionRepository.STATUS_SIGNING));
    assertFalse(NapSubmissionEvidence.isEvidence(null));
  }

  // --- замръзнало ------------------------------------------------------------------------------

  @Test
  void theFiledContentIsFrozen() {
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("name", "друго");
    changes.put("serviceCodeSelect", NapSubmissionRepository.SERVICE_SAFT);
    changes.put("serviceDocumentTypeSelect", "DEC_6");
    changes.put("company", ref(new Company(), 9L));
    changes.put("period", ref(new Period(), 9L));
    changes.put("referenceDate", LocalDate.of(2026, 1, 1));
    changes.put("environmentSelect", NapSubmissionRepository.ENVIRONMENT_TEST);
    changes.put("filedByInsuranceFund", Boolean.TRUE);
    changes.put("attemptNumber", 2);
    changes.put("idempotencyKey", "k-2");
    changes.put("taxpayerPin", "111111111");
    changes.put("requestMeta", "x");
    changes.put("previousSubmission", ref(new NapSubmission(), 5L));
    changes.put("relatedModel", "x.Z");
    changes.put("relatedRecordId", 8L);
    changes.put("archived", Boolean.TRUE);
    // подадено веднъж — каналът, моментът и кой вече не се пренаписват
    changes.put("channelSelect", NapSubmissionRepository.CHANNEL_API);
    changes.put("submittedOn", LocalDateTime.of(2026, 10, 20, 0, 0));
    changes.put("submittedBy", ref(new User(), 9L));
    changes.put("signerCertificateBase64", "QUJD");
    for (String status : FILED) {
      for (Map.Entry<String, Object> change : changes.entrySet()) {
        Map<String, Object> before = status(status);
        RegulatoryImmutabilityException e =
            assertThrows(
                RegulatoryImmutabilityException.class,
                () -> update(before, with(before, change.getKey(), change.getValue())),
                status + " / " + change.getKey());
        assertTrue(e.getMessage().contains(change.getKey()), e.getMessage());
      }
    }
  }

  @Test
  void aFiledSubmissionDoesNotGoBackToDraftOrSigning() {
    for (String status : FILED) {
      for (String back :
          new String[] {
            NapSubmissionRepository.STATUS_DRAFT, NapSubmissionRepository.STATUS_SIGNING, null
          }) {
        Map<String, Object> before = status(status);
        assertThrows(
            RegulatoryImmutabilityException.class,
            () -> update(before, with(before, "submissionStatusSelect", back)),
            status + " → " + back);
      }
    }
  }

  @Test
  void theIncomingNumberIsNotCleared() {
    Map<String, Object> before = filed();
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> update(before, with(before, "napIncomingNumber", null)));
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> update(before, with(before, "napIncomingNumber", " ")));
    Map<String, Object> api = with(with(filed(), "documentId", 42L), "entryNumber", "E-1");
    assertThrows(
        RegulatoryImmutabilityException.class, () -> update(api, with(api, "documentId", 0L)));
    assertThrows(
        RegulatoryImmutabilityException.class, () -> update(api, with(api, "entryNumber", null)));
  }

  // --- позволено ------------------------------------------------------------------------------

  @Test
  void theOutcomeOfTheAuthorityIsWritten() {
    Map<String, Object> after = filed();
    after.put("submissionStatusSelect", NapSubmissionRepository.STATUS_ACCEPTED);
    after.put("externalStatus", "accepted");
    after.put("documentId", 42L);
    after.put("entryNumber", "E-1");
    after.put("entryDate", "2026-10-02");
    after.put("napIncomingNumber", "ТЕСТ-0002");
    after.put("processedOn", LocalDateTime.of(2026, 10, 2, 0, 0));
    after.put("lastStatusCheckOn", LocalDateTime.of(2026, 10, 2, 1, 0));
    after.put("errorCode", "E");
    after.put("errorMessage", "грешка");
    after.put("exceptionId", "X-1");
    after.put("correlationId", "C-1");
    after.put("responseMeta", "протокол № 1");
    after.put("correctionReason", "бележка");
    // платформата: версия, одит, custom полета; колекцията (обратната страна) не е колона
    after.put("version", 5);
    after.put("updatedOn", LocalDateTime.of(2026, 10, 2, 0, 0));
    after.put("updatedBy", ref(new User(), 9L));
    after.put("attrs", "{}");
    after.put("submissionFileList", new ArrayList<>(List.of(new Object())));
    assertDoesNotThrow(() -> update(filed(), after));
  }

  @Test
  void everyTransitionOfTheProcessBetweenFiledStatusesPasses() {
    String[][] transitions = {
      // API: изпращане, резултат, неизвестен изход, ръчно решение
      {NapSubmissionRepository.STATUS_SIGNED, NapSubmissionRepository.STATUS_SUBMITTING},
      {NapSubmissionRepository.STATUS_SUBMITTING, NapSubmissionRepository.STATUS_SUBMITTED},
      {NapSubmissionRepository.STATUS_SUBMITTING, NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN},
      {NapSubmissionRepository.STATUS_SUBMITTING, NapSubmissionRepository.STATUS_TECHNICAL_ERROR},
      {NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, NapSubmissionRepository.STATUS_SUBMITTED},
      {NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, NapSubmissionRepository.STATUS_REJECTED},
      {
        NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN,
        NapSubmissionRepository.STATUS_TECHNICAL_ERROR
      },
      // изходът — от API или ръчно
      {NapSubmissionRepository.STATUS_SUBMITTED, NapSubmissionRepository.STATUS_ACCEPTED},
      {NapSubmissionRepository.STATUS_SUBMITTED, NapSubmissionRepository.STATUS_REJECTED},
      {
        NapSubmissionRepository.STATUS_SUBMITTED, NapSubmissionRepository.STATUS_CORRECTION_REQUIRED
      },
      // обновяване на статуса без конфигуриран API
      {NapSubmissionRepository.STATUS_SUBMITTED, NapSubmissionRepository.STATUS_TECHNICAL_ERROR},
      // НОИ, Прил. 9: отмяна на отметката (с причина в одита)
      {NapSubmissionRepository.STATUS_SUBMITTED, NapSubmissionRepository.STATUS_WITHDRAWN},
      // повторно изпращане след техническа грешка
      {NapSubmissionRepository.STATUS_TECHNICAL_ERROR, NapSubmissionRepository.STATUS_SUBMITTED}
    };
    for (String[] t : transitions) {
      assertDoesNotThrow(
          () -> update(status(t[0]), with(status(t[0]), "submissionStatusSelect", t[1])),
          t[0] + " → " + t[1]);
    }
  }

  @Test
  void aResendWithoutAnIdentifierWritesChannelMomentAndSender() {
    for (String from :
        new String[] {
          NapSubmissionRepository.STATUS_SIGNED, NapSubmissionRepository.STATUS_TECHNICAL_ERROR
        }) {
      Map<String, Object> before = with(status(from), "napIncomingNumber", null);
      Map<String, Object> after = with(before, "submissionStatusSelect", "submitted");
      after.put("channelSelect", NapSubmissionRepository.CHANNEL_API);
      after.put("submittedOn", LocalDateTime.of(2026, 10, 20, 0, 0));
      after.put("submittedBy", ref(new User(), 9L));
      after.put("signerCertificateBase64", "QUJD");
      after.put("napIncomingNumber", "ТЕСТ-0003");
      assertDoesNotThrow(() -> update(before, after), from);
      // с входящ номер вече е подадено — повторното изпращане не пренаписва момента
      Map<String, Object> numbered = status(from);
      assertThrows(
          RegulatoryImmutabilityException.class,
          () -> update(numbered, with(numbered, "submittedOn", LocalDateTime.now())),
          from);
    }
  }

  @Test
  void aDraftIsEditedAsBefore() {
    for (String status :
        new String[] {
          NapSubmissionRepository.STATUS_DRAFT, NapSubmissionRepository.STATUS_SIGNING, null
        }) {
      Map<String, Object> before = status(status);
      Map<String, Object> after = with(before, "company", ref(new Company(), 9L));
      after.put("referenceDate", LocalDate.of(2026, 1, 1));
      after.put("napIncomingNumber", null);
      after.put("submissionStatusSelect", NapSubmissionRepository.STATUS_SUBMITTED);
      assertDoesNotThrow(() -> update(before, after), String.valueOf(status));
    }
  }

  @Test
  void blankAndMissingTextAreTheSame() {
    Map<String, Object> before = with(filed(), "requestMeta", null);
    assertDoesNotThrow(() -> update(before, with(before, "requestMeta", "")));
  }

  // --- заключено, но още неподписано ({@code locked}) -----------------------------------------

  @Test
  void lockedIsNotYetEvidence() {
    assertFalse(NapSubmissionEvidence.isEvidence(NapSubmissionRepository.STATUS_LOCKED));
    assertTrue(NapSubmissionEvidence.isSelectionLocked(NapSubmissionRepository.STATUS_LOCKED));
  }

  @Test
  void aLockedSubmissionContentIsFrozen() {
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("serviceCodeSelect", NapSubmissionRepository.SERVICE_SAFT);
    changes.put("serviceDocumentTypeSelect", "DEC_6");
    changes.put("company", ref(new Company(), 9L));
    changes.put("period", ref(new Period(), 9L));
    changes.put("taxpayerPin", "111111111");
    for (Map.Entry<String, Object> change : changes.entrySet()) {
      Map<String, Object> before = status(NapSubmissionRepository.STATUS_LOCKED);
      RegulatoryImmutabilityException e =
          assertThrows(
              RegulatoryImmutabilityException.class,
              () -> update(before, with(before, change.getKey(), change.getValue())),
              change.getKey());
      assertTrue(e.getMessage().contains(change.getKey()), e.getMessage());
    }
  }

  @Test
  void aLockedSubmissionMovesForwardButNotBackToDraft() {
    Map<String, Object> before = status(NapSubmissionRepository.STATUS_LOCKED);
    // forward to signing (and to a filed state via the manual portal) is allowed
    assertDoesNotThrow(
        () ->
            update(
                before,
                with(before, "submissionStatusSelect", NapSubmissionRepository.STATUS_SIGNING)));
    assertDoesNotThrow(
        () ->
            update(
                before,
                with(before, "submissionStatusSelect", NapSubmissionRepository.STATUS_SUBMITTED)));
    // unlocking back to draft/null is refused — it would thaw the frozen selection
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            update(
                before,
                with(before, "submissionStatusSelect", NapSubmissionRepository.STATUS_DRAFT)));
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> update(before, with(before, "submissionStatusSelect", null)));
  }

  @Test
  void aFileOfALockedSubmissionFreezesPayloadButAllowsSigningFields() {
    // payload (content) is frozen
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            fileUpdateLocked(file(), with(file(), "payloadArtifact", ref(new NapArtifact(), 11L))));
    // the requested digest is written by signing on the exact frozen bytes — allowed
    assertDoesNotThrow(
        () -> fileUpdateLocked(file(), with(file(), "qesRequestedSha256", "b".repeat(64))));
  }

  @Test
  void aFileIsNotAddedDeletedOrMovedIntoALockedSubmission() {
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> NapSubmissionEvidence.checkFileInsert(false, true));
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> NapSubmissionEvidence.checkFileDelete(false, true));
    String[] names = file().keySet().toArray(new String[0]);
    Object[] state = file().values().toArray();
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            NapSubmissionEvidence.checkFileUpdate(names, state, state, false, false, false, true));
  }

  private static void fileUpdateLocked(Map<String, Object> before, Map<String, Object> after) {
    String[] names = before.keySet().toArray(new String[0]);
    Object[] now = new Object[names.length];
    for (int i = 0; i < names.length; i++) {
      now[i] = after.get(names[i]);
    }
    NapSubmissionEvidence.checkFileUpdate(
        names, before.values().toArray(), now, false, false, true, false);
  }

  // --- триене ---------------------------------------------------------------------------------

  @Test
  void aFiledSubmissionIsNotDeleted() {
    for (String status : FILED) {
      assertThrows(
          RegulatoryImmutabilityException.class,
          () -> NapSubmissionEvidence.checkSubmissionDelete(status),
          status);
    }
    assertDoesNotThrow(
        () -> NapSubmissionEvidence.checkSubmissionDelete(NapSubmissionRepository.STATUS_DRAFT));
    assertDoesNotThrow(
        () -> NapSubmissionEvidence.checkSubmissionDelete(NapSubmissionRepository.STATUS_SIGNING));
  }

  // --- файл -----------------------------------------------------------------------------------

  private static Map<String, Object> file() {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("submission", ref(new NapSubmission(), 1L));
    f.put("payloadArtifact", ref(new NapArtifact(), 10L));
    f.put("signatureArtifact", null);
    f.put("sequence", 0);
    f.put("submittedFileName", "EMPL.TXT");
    f.put("fileDocumentTypeSelect", "DEC_1");
    f.put("fileTypeSelect", "txt");
    f.put("fileSize", 10L);
    f.put("numRecords", 1);
    f.put("fileStatusSelect", "pending");
    f.put("fileError", null);
    f.put("qesRequestedSha256", "a".repeat(64));
    f.put("qesSigningStatusSelect", "not_started");
    f.put("signerCertificateArtifact", null);
    f.put("signerCertificateSha256", null);
    f.put("version", 1);
    f.put("updatedOn", null);
    return f;
  }

  private static void fileUpdate(
      Map<String, Object> before, Map<String, Object> after, boolean parentFiled) {
    String[] names = before.keySet().toArray(new String[0]);
    Object[] now = new Object[names.length];
    for (int i = 0; i < names.length; i++) {
      now[i] = after.get(names[i]);
    }
    NapSubmissionEvidence.checkFileUpdate(
        names, before.values().toArray(), now, parentFiled, false);
  }

  @Test
  void aFileOfAFiledSubmissionIsFrozenExceptTheVerdictOfTheAuthority() {
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("submission", ref(new NapSubmission(), 2L));
    changes.put("payloadArtifact", ref(new NapArtifact(), 11L));
    changes.put("signatureArtifact", ref(new NapArtifact(), 12L));
    changes.put("sequence", 1);
    changes.put("submittedFileName", "друго.TXT");
    changes.put("fileDocumentTypeSelect", "DEC_6");
    changes.put("fileSize", 11L);
    changes.put("numRecords", 2);
    changes.put("qesRequestedSha256", "b".repeat(64));
    changes.put("signerCertificateSha256", "c".repeat(64));
    for (Map.Entry<String, Object> change : changes.entrySet()) {
      RegulatoryImmutabilityException e =
          assertThrows(
              RegulatoryImmutabilityException.class,
              () -> fileUpdate(file(), with(file(), change.getKey(), change.getValue()), true),
              change.getKey());
      assertTrue(e.getMessage().contains(change.getKey()), e.getMessage());
    }
    Map<String, Object> verdict = with(file(), "fileStatusSelect", "accepted");
    verdict.put("fileError", "ред 3");
    verdict.put("version", 2);
    assertDoesNotThrow(() -> fileUpdate(file(), verdict, true));
  }

  @Test
  void aFileOfADraftIsEditedAsBefore() {
    Map<String, Object> after = with(file(), "payloadArtifact", ref(new NapArtifact(), 11L));
    after.put("submittedFileName", "друго.TXT");
    assertDoesNotThrow(() -> fileUpdate(file(), after, false));
  }

  @Test
  void aSignedFileKeepsItsPayloadAndSignatureEvenBeforeTheSubmissionIsSigned() {
    Map<String, Object> signed = with(file(), "qesSigningStatusSelect", "completed");
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            fileUpdate(
                signed, with(signed, "payloadArtifact", ref(new NapArtifact(), 11L)), false));
    assertDoesNotThrow(() -> fileUpdate(signed, with(signed, "submittedFileName", "x"), false));
  }

  @Test
  void aFileIsNotMovedIntoAddedToOrDeletedFromAFiledSubmission() {
    String[] names = file().keySet().toArray(new String[0]);
    Object[] state = file().values().toArray();
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> NapSubmissionEvidence.checkFileUpdate(names, state, state, false, true));
    assertThrows(
        RegulatoryImmutabilityException.class, () -> NapSubmissionEvidence.checkFileInsert(true));
    assertThrows(
        RegulatoryImmutabilityException.class, () -> NapSubmissionEvidence.checkFileDelete(true));
    assertDoesNotThrow(() -> NapSubmissionEvidence.checkFileInsert(false));
    assertDoesNotThrow(() -> NapSubmissionEvidence.checkFileDelete(false));
  }
}
