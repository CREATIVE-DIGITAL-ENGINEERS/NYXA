package com.axelor.apps.napsubmissions.common.submission;

/**
 * Structured, transport-agnostic outcome of a {@link NapSubmissionClient} call. Deliberately small:
 * no secrets, no raw HTTP objects. Carries the НАП external identifiers (documentId / entryNumber /
 * entryDate) a real client would return, plus an {@link #isOutcomeUnknown() ambiguous-outcome} flag
 * for submit timeouts where it is unknown whether НАП accepted the request.
 *
 * <p>Until the official contract is implemented, the only producer is {@link
 * UnsupportedNapApiSubmissionClient}, which returns {@link #notConfigured(String)}.
 */
public class NapSubmissionResult {

  private boolean configured;
  private boolean success;
  private boolean outcomeUnknown;
  private String externalStatus;
  private String errorCode;
  private String errorMessage;
  private String exceptionId;
  private String correlationId;
  private Long documentId;
  private String entryNumber;
  private String entryDate;

  private NapSubmissionResult() {}

  public static Builder builder() {
    return new Builder();
  }

  /** The official API is not configured; API submission is unavailable. Never a startup failure. */
  public static NapSubmissionResult notConfigured(String message) {
    return builder().errorCode("API_NOT_CONFIGURED").errorMessage(message).build();
  }

  /** Submit timed out / connection dropped: it is unknown whether НАП accepted it. Do not retry. */
  public static NapSubmissionResult outcomeUnknown(String message) {
    return builder().configured(true).outcomeUnknown(true).errorMessage(message).build();
  }

  public boolean isConfigured() {
    return configured;
  }

  public boolean isSuccess() {
    return success;
  }

  public boolean isOutcomeUnknown() {
    return outcomeUnknown;
  }

  public String getExternalStatus() {
    return externalStatus;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getExceptionId() {
    return exceptionId;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Long getDocumentId() {
    return documentId;
  }

  public String getEntryNumber() {
    return entryNumber;
  }

  public String getEntryDate() {
    return entryDate;
  }

  /** Fluent builder — keeps the many optional fields readable at call sites. */
  public static class Builder {
    private final NapSubmissionResult r = new NapSubmissionResult();

    public Builder configured(boolean v) {
      r.configured = v;
      return this;
    }

    public Builder success(boolean v) {
      r.success = v;
      return this;
    }

    public Builder outcomeUnknown(boolean v) {
      r.outcomeUnknown = v;
      return this;
    }

    public Builder externalStatus(String v) {
      r.externalStatus = v;
      return this;
    }

    public Builder errorCode(String v) {
      r.errorCode = v;
      return this;
    }

    public Builder errorMessage(String v) {
      r.errorMessage = v;
      return this;
    }

    public Builder exceptionId(String v) {
      r.exceptionId = v;
      return this;
    }

    public Builder correlationId(String v) {
      r.correlationId = v;
      return this;
    }

    public Builder documentId(Long v) {
      r.documentId = v;
      return this;
    }

    public Builder entryNumber(String v) {
      r.entryNumber = v;
      return this;
    }

    public Builder entryDate(String v) {
      r.entryDate = v;
      return this;
    }

    public NapSubmissionResult build() {
      return r;
    }
  }
}
