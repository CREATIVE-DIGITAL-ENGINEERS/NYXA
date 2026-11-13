package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Only explicitly-confirmed combinations are allowed; everything else is rejected. */
class DeclarationsDocumentTypeMatrixTest {

  @Test
  void confirmedCombinationsAllowed() {
    assertTrue(
        DeclarationsDocumentTypeMatrix.isConfirmed(
            ServiceDocumentType.DEC_1_6, FileDocumentType.DEC_1));
    assertTrue(
        DeclarationsDocumentTypeMatrix.isConfirmed(
            ServiceDocumentType.DEC_1_6, FileDocumentType.DEC_6));
    assertTrue(
        DeclarationsDocumentTypeMatrix.isConfirmed(
            ServiceDocumentType.DEC_ETZ62, FileDocumentType.DEC_ETZ62));
    assertTrue(
        DeclarationsDocumentTypeMatrix.isConfirmed(
            ServiceDocumentType.DEC_ETZ123, FileDocumentType.DEC_ETZ123));
  }

  @Test
  void unconfirmedCombinationsRejected() {
    // wrong file for a service
    assertFalse(
        DeclarationsDocumentTypeMatrix.isConfirmed(
            ServiceDocumentType.DEC_1_6, FileDocumentType.DEC_ETZ62));
    // high-fiscal-risk file set is unresolved -> not confirmed
    assertFalse(
        DeclarationsDocumentTypeMatrix.isConfirmed(
            ServiceDocumentType.DEC_HIGH_FISC_RISK, FileDocumentType.DEC_FISC_RISK_BG_BG));
    assertFalse(
        DeclarationsDocumentTypeMatrix.hasConfirmedRules(ServiceDocumentType.DEC_HIGH_FISC_RISK));
    assertTrue(DeclarationsDocumentTypeMatrix.hasConfirmedRules(ServiceDocumentType.DEC_1_6));
  }
}
