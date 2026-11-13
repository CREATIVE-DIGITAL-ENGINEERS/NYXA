package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * Top-level {@code serviceDocumentType} of the НАП Declarations API (the service being called).
 * Fixed by the official contract. Not a large nomenclature — a stable, small API enum. Each value
 * maps to a future {@code services/declarations/*} implementation package.
 */
public enum ServiceDocumentType {
  DEC_1_6,
  DEC_HIGH_FISC_RISK,
  DEC_ETZ62,
  DEC_ETZ123
}
