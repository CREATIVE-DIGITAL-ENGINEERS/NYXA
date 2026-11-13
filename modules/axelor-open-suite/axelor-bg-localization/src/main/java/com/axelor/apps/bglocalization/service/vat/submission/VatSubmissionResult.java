package com.axelor.apps.bglocalization.service.vat.submission;

/**
 * Transport-level outcome of a mock submit/status call. The gateway is transport-only: it reports
 * what happened on the wire (delivered / timed out / HTTP error / unparseable) and the raw external
 * status; the workflow service maps that onto the {@code BgVatSubmission} domain status.
 *
 * <p>Immutable; construct via {@link #builder()} or the transport-failure factories.
 */
public class VatSubmissionResult {

  private final boolean configured;
  private final boolean success;
  private final boolean outcomeUnknown;
  private final int httpStatus;
  private final String externalStatus;
  private final String submissionRef;
  private final String entryNumber;
  private final String correlationId;
  private final String errorCode;
  private final String errorMessage;
  private final String rawBody;

  private VatSubmissionResult(Builder b) {
    this.configured = b.configured;
    this.success = b.success;
    this.outcomeUnknown = b.outcomeUnknown;
    this.httpStatus = b.httpStatus;
    this.externalStatus = b.externalStatus;
    this.submissionRef = b.submissionRef;
    this.entryNumber = b.entryNumber;
    this.correlationId = b.correlationId;
    this.errorCode = b.errorCode;
    this.errorMessage = b.errorMessage;
    this.rawBody = b.rawBody;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The gateway is disabled or points at a non-local URL — nothing was sent. */
  public static VatSubmissionResult notConfigured(String message) {
    return builder().configured(false).success(false).errorMessage(message).build();
  }

  /** The send timed out — the outcome is genuinely unknown; never auto re-POST. */
  public static VatSubmissionResult outcomeUnknown(String message) {
    return builder()
        .configured(true)
        .success(false)
        .outcomeUnknown(true)
        .errorMessage(message)
        .build();
  }

  /** Transport / HTTP / parsing failure (retryable for status checks). */
  public static VatSubmissionResult transportError(int httpStatus, String code, String message) {
    return builder()
        .configured(true)
        .success(false)
        .httpStatus(httpStatus)
        .errorCode(code)
        .errorMessage(message)
        .build();
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

  public int getHttpStatus() {
    return httpStatus;
  }

  public String getExternalStatus() {
    return externalStatus;
  }

  public String getSubmissionRef() {
    return submissionRef;
  }

  public String getEntryNumber() {
    return entryNumber;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getRawBody() {
    return rawBody;
  }

  public static class Builder {
    private boolean configured = true;
    private boolean success;
    private boolean outcomeUnknown;
    private int httpStatus;
    private String externalStatus;
    private String submissionRef;
    private String entryNumber;
    private String correlationId;
    private String errorCode;
    private String errorMessage;
    private String rawBody;

    public Builder configured(boolean v) {
      this.configured = v;
      return this;
    }

    public Builder success(boolean v) {
      this.success = v;
      return this;
    }

    public Builder outcomeUnknown(boolean v) {
      this.outcomeUnknown = v;
      return this;
    }

    public Builder httpStatus(int v) {
      this.httpStatus = v;
      return this;
    }

    public Builder externalStatus(String v) {
      this.externalStatus = v;
      return this;
    }

    public Builder submissionRef(String v) {
      this.submissionRef = v;
      return this;
    }

    public Builder entryNumber(String v) {
      this.entryNumber = v;
      return this;
    }

    public Builder correlationId(String v) {
      this.correlationId = v;
      return this;
    }

    public Builder errorCode(String v) {
      this.errorCode = v;
      return this;
    }

    public Builder errorMessage(String v) {
      this.errorMessage = v;
      return this;
    }

    public Builder rawBody(String v) {
      this.rawBody = v;
      return this;
    }

    public VatSubmissionResult build() {
      return new VatSubmissionResult(this);
    }
  }
}
