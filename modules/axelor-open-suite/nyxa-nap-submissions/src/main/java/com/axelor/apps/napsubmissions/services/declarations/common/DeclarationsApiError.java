package com.axelor.apps.napsubmissions.services.declarations.common;

import java.util.List;

/**
 * Declarations API error ({@code ApiError}). {@code exceptionId} should be quoted to НАП support.
 * {@code errorCode} is a large fixed enum in the contract (auth / param / file / access /
 * XML-validation / duplicate …) — kept as a String here to stay forward-compatible.
 */
public class DeclarationsApiError {
  public Integer code;
  public String errorCode;
  public List<String> errors;
  public String exceptionId;
  public String i18nMessage;
  public String reason;
  public String timestamp;
}
