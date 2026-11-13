package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * Declarations result response ({@code ApiDeclarationsResultOutputDto}). The result arrives as HTML
 * or non-HTML (base64). The raw bytes must be persisted immutably; raw HTML must never be rendered
 * directly (store + sanitize).
 */
public class DeclarationsResultResponse {
  public String entryNumber;
  public String entryDate;

  /** HTML fragments per file. */
  public String base64HtmlResult;

  /** Full formatted HTML page. */
  public String base64FullHtmlResultInfo;

  /** Base64 result when the format is not HTML. */
  public String base64Result;

  public String taxPeriodFrom;
  public String taxPeriodTo;
}
