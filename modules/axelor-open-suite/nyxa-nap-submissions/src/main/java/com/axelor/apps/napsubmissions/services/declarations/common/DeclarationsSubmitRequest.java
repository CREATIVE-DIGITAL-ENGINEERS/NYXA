package com.axelor.apps.napsubmissions.services.declarations.common;

import java.util.List;

/**
 * Declarations submit request ({@code ApiDeclarationsSubmitInputDto}). Contract-accurate transport
 * DTO — no invented fields. {@code userSignatureBase64} is the submitting person's public
 * certificate; the API key (Bearer) is passed as a transport header, not in this body.
 */
public class DeclarationsSubmitRequest {
  public String taxpayerPin;
  public PinType taxpayerPinType;
  public String userPin;
  public PinType userPinType;
  public ServiceDocumentType serviceDocumentType;

  /** Base64 of the submitting user's public certificate. */
  public String userSignatureBase64;

  /** "01".."12" — conditional per declaration. */
  public String taxPeriodFrom;

  public String taxPeriodTo;
  public String year;

  /** 0 = no, 1 = filed by an insurance fund (conditional). */
  public Integer insuranceFund;

  public List<DeclarationsFile> files;
}
