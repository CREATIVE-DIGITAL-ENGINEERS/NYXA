package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * Per-file {@code fileDocumentType} of the НАП Declarations API (the type of one file inside a
 * submission). Fixed by the official contract. Which values are valid under each {@link
 * ServiceDocumentType} must be taken from the archive's samples when a service is implemented.
 */
public enum FileDocumentType {
  DEC_1,
  DEC_6,
  DEC_FISC_RISK_EU_BG,
  DEC_FISC_RISK_BG_EU,
  DEC_FISC_RISK_ANNUL,
  DEC_FISC_RISK_CONFIRM,
  DEC_FISC_RISK_BG_BG,
  DEC_FISC_RISK_IMPORT,
  DEC_ETZ62,
  DEC_ETZ123
}
