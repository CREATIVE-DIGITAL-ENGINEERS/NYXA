package com.axelor.apps.napsubmissions.common.immutability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.rpc.Request;
import org.junit.jupiter.api.Test;

/**
 * Масовото обновяване не мести подаване в/от доказателство: статусът на подаването и подаването на
 * файла не се пишат масово изобщо. Частта „филтърът хваща подадено“ иска база — тя е в
 * интеграционния {@code BgNapSubmissionImmutableRestTest}.
 */
class NapSubmissionRequestGuardTest {

  private static Request massUpdate(String field) {
    return NapArtifactRequestGuardTest.massUpdate(field, "x").getRequest();
  }

  @Test
  void theStatusOfASubmissionIsNotMassUpdated() {
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            NapSubmissionRequestGuard.refuseField(
                massUpdate("submissionStatusSelect"), "submissionStatusSelect", "status"));
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            NapSubmissionRequestGuard.refuseField(
                massUpdate("self.submissionStatusSelect"), "submissionStatusSelect", "status"));
    assertDoesNotThrow(
        () ->
            NapSubmissionRequestGuard.refuseField(
                massUpdate("referenceDate"), "submissionStatusSelect", "status"));
  }

  @Test
  void theSubmissionOfAFileIsNotMassUpdated() {
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            NapSubmissionRequestGuard.refuseField(
                massUpdate("submission"), "submission", "submission"));
  }
}
