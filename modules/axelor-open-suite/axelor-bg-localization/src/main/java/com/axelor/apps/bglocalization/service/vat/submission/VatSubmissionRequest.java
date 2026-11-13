package com.axelor.apps.bglocalization.service.vat.submission;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal Nyxa mock-submission request (NOT an NRA specification). Carries the identifying header
 * and the four package files as Base64. The {@code scenario} selects the deterministic behaviour of
 * the local mock server and has no meaning for any real API.
 */
public class VatSubmissionRequest {

  private String companyVatNumber;
  private String companyName;
  private String period;
  private String packageSha256;
  private String scenario;

  /** filename → Base64 content (PRODAGBI.TXT / POKUPKI.TXT / DEKLAR.TXT / VIES.TXT). */
  private final Map<String, String> filesBase64 = new LinkedHashMap<>();

  public String getCompanyVatNumber() {
    return companyVatNumber;
  }

  public void setCompanyVatNumber(String companyVatNumber) {
    this.companyVatNumber = companyVatNumber;
  }

  public String getCompanyName() {
    return companyName;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public String getPackageSha256() {
    return packageSha256;
  }

  public void setPackageSha256(String packageSha256) {
    this.packageSha256 = packageSha256;
  }

  public String getScenario() {
    return scenario;
  }

  public void setScenario(String scenario) {
    this.scenario = scenario;
  }

  public Map<String, String> getFilesBase64() {
    return filesBase64;
  }

  public void addFile(String fileName, String base64) {
    filesBase64.put(fileName, base64);
  }
}
