package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * Declarations result request ({@code ApiDeclarationsResultInputDto}). Search key is EITHER {@code
 * documentId} OR the {@code entryNumber} + {@code entryDate} combination — not both.
 */
public class DeclarationsResultRequest {
  public String taxpayerPin;
  public PinType taxpayerPinType;
  public String userPin;
  public PinType userPinType;
  public String userSignatureBase64;
  public Long documentId;
  public String entryNumber;
  public String entryDate;
}
