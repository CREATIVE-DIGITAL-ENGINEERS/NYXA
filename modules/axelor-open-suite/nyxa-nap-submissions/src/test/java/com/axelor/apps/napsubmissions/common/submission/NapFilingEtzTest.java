package com.axelor.apps.napsubmissions.common.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЕТЗ В ОБЩИЯ ПЪТ НА ПОДАВАНИЯТА (решение 02.11.2026) — без база.
 *
 * <ul>
 *   <li>ЕТЗ е на ТРЗ ({@code hr.payroll.write}), не на НАП — както Д1/Д6;
 *   <li>каналът API отказва за ЕТЗ ПРЕДИ всичко друго — дори преди заявката за подадени „сестри“
 *       (тук репозиторият е {@code null}: стигне ли се дотам, тестът пада с NPE, не със зелено).
 * </ul>
 */
class NapFilingEtzTest {

  private static NapSubmission submission(String service, String documentType) {
    NapSubmission s = new NapSubmission();
    s.setServiceCodeSelect(service);
    s.setServiceDocumentTypeSelect(documentType);
    return s;
  }

  @Test
  @DisplayName("Гейтът по вид: ЕТЗ62 и ЕТЗ123 — hr.payroll.write; SAF-T без вид — НАП, без NPE")
  void etzIsPayroll() {
    for (ServiceDocumentType type :
        new ServiceDocumentType[] {
          ServiceDocumentType.DEC_ETZ62, ServiceDocumentType.DEC_ETZ123, ServiceDocumentType.DEC_1_6
        }) {
      assertEquals(
          Capabilities.PAYROLL_WRITE,
          NapFilingGate.capabilityFor(
              submission(NapSubmissionRepository.SERVICE_DECLARATIONS, type.name())),
          type.name());
    }
    assertEquals(
        Capabilities.NAP_WRITE,
        NapFilingGate.capabilityFor(submission(NapSubmissionRepository.SERVICE_SAFT, null)));
    assertEquals(
        Capabilities.NAP_WRITE,
        NapFilingGate.capabilityFor(
            submission(NapSubmissionRepository.SERVICE_DECLARATIONS, null)));
  }

  @Test
  @DisplayName("Канал API за ЕТЗ — отказ „без КЕП … КЕП или ПИК“, преди заявката за сестри")
  void theApiChannelRefusesEtzFirst() {
    NapFilingLock lock = new NapFilingLock(null);
    for (ServiceDocumentType type :
        new ServiceDocumentType[] {ServiceDocumentType.DEC_ETZ62, ServiceDocumentType.DEC_ETZ123}) {
      NapSubmission etz = submission(NapSubmissionRepository.SERVICE_DECLARATIONS, type.name());
      etz.setRelatedModel("com.axelor.apps.hr.db.EmploymentContract");
      etz.setRelatedRecordId(41L);
      NapFilingLockedException e =
          assertThrows(NapFilingLockedException.class, () -> lock.requireApiAllowed(etz));
      assertEquals(NapFilingLock.MANUAL_ONLY_REFUSAL, e.getMessage(), type.name());
    }
  }

  @Test
  @DisplayName("Д1/Д6 по API не са засегнати — отказът е само по вида")
  void otherKindsAreNotManualOnly() {
    NapFilingLock.refuseManualOnly(
        submission(
            NapSubmissionRepository.SERVICE_DECLARATIONS, ServiceDocumentType.DEC_1_6.name()));
    NapFilingLock.refuseManualOnly(submission(NapSubmissionRepository.SERVICE_SAFT, null));
    NapFilingLock.refuseManualOnly(null);
  }
}
