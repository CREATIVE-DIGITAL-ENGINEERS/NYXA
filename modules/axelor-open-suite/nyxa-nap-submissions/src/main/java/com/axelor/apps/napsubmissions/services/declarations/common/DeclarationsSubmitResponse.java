package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * Declarations submit response ({@code ApiDeclarationsSubmitOutputDto}). A 200 means "accepted for
 * processing", NOT final acceptance — the real outcome is fetched later from the result endpoint
 * using these identifiers.
 */
public class DeclarationsSubmitResponse {
  public Long documentId;
  public String entryNumber;
  public String entryDate;
}
