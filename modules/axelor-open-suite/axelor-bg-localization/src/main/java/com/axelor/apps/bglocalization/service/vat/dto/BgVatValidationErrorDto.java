package com.axelor.apps.bglocalization.service.vat.dto;

/** A single validation finding raised before/against a generated diary. */
public class BgVatValidationErrorDto {

  public static final String SEVERITY_ERROR = "ERROR";
  public static final String SEVERITY_WARNING = "WARNING";

  private String code;
  private String message;
  private String severity = SEVERITY_ERROR;
  private String documentRef;

  public BgVatValidationErrorDto() {}

  public BgVatValidationErrorDto(String code, String severity, String message) {
    this.code = code;
    this.severity = severity;
    this.message = message;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getDocumentRef() {
    return documentRef;
  }

  public void setDocumentRef(String documentRef) {
    this.documentRef = documentRef;
  }
}
