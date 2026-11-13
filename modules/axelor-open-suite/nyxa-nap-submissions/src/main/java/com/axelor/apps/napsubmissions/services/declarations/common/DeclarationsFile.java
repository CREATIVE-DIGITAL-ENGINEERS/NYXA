package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * One file of a Declarations submit request ({@code UploadFileDto}). Contract-accurate transport
 * DTO.
 *
 * <p>NOTE: {@code fileContentBase64} and {@code base64EncodedPkcs7} are produced at submit time and
 * are NOT persisted on this DTO's lifetime beyond the request. The immutable bytes live in a {@code
 * NapArtifact}; the private KEP key that produced the PKCS#7 signature never enters the ERP.
 */
public class DeclarationsFile {
  public String fileName;

  /** txt | csv | xml. */
  public String fileType;

  public long fileSize;
  public String fileContentBase64;

  /** Base64 detached PKCS#7 signature of the exact file bytes. */
  public String base64EncodedPkcs7;

  public FileDocumentType fileDocumentType;

  /** Required for some declaration types (e.g. record count). */
  public Integer numRecords;
}
