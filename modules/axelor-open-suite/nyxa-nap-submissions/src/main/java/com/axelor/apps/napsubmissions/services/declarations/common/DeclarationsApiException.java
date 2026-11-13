package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * Structured failure of a НАП Declarations API call. Carries the official {@link
 * DeclarationsApiError} body when one was returned, the HTTP status, and a coarse {@link Category}
 * the caller uses to decide how to update the submission (retry vs. reconcile vs. fail). Messages
 * here are safe to log; they must never contain the API key or an {@code Authorization} header.
 */
public class DeclarationsApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** How the caller should treat the failure. */
  public enum Category {
    /** API disabled / not configured / secret unresolved — a local configuration problem. */
    CONFIG,
    /** 401/403 or a token error — authentication/authorization. */
    AUTH,
    /** 400 validation of the request (bad params, wrong enum, file-size mismatch, …). */
    INVALID_REQUEST,
    /** {@code API_DUPLICATED_FILE} — identical content already submitted for the taxpayer. */
    DUPLICATE,
    /** 429 — rate limited; safe to retry the result query with backoff. */
    RATE_LIMIT,
    /** 5xx — temporary server failure. */
    SERVER,
    /** {@code API_DOCUMENT_RESULT_NOT_FOUND} on a result query — not ready yet, not a rejection. */
    RESULT_NOT_READY,
    /** Submit failed mid-flight; НАП may or may not have received it. Do NOT blind-retry. */
    UNKNOWN_OUTCOME,
    /** Network/transport error on a safe (retryable) operation. */
    TRANSPORT,
    /** Anything else. */
    UNKNOWN
  }

  private final transient Category category;
  private final transient DeclarationsApiError error;
  private final int httpStatus;
  private final String operation;

  public DeclarationsApiException(
      Category category,
      int httpStatus,
      String operation,
      DeclarationsApiError error,
      String message) {
    super(message);
    this.category = category;
    this.httpStatus = httpStatus;
    this.operation = operation;
    this.error = error;
  }

  public DeclarationsApiException(
      Category category, String operation, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
    this.httpStatus = 0;
    this.operation = operation;
    this.error = null;
  }

  public Category getCategory() {
    return category;
  }

  public DeclarationsApiError getError() {
    return error;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public String getOperation() {
    return operation;
  }

  /** The official НАП error code, if the response carried one. */
  public String getErrorCode() {
    return error == null ? null : error.errorCode;
  }

  public String getExceptionId() {
    return error == null ? null : error.exceptionId;
  }
}
