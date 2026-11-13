package com.axelor.apps.bglocalization.service.vat.dto;

/**
 * One NAP file produced by a production run: its scope (sales/purchases/declaration/vies), file
 * name, the uploaded {@link com.axelor.meta.db.MetaFile} id/version, and the raw bytes (kept so the
 * controller can zip an "all" run without re-reading from disk).
 */
public class BgVatGeneratedFileDto {

  private final String scope;
  private final String fileName;
  private final Long metaFileId;
  private final Integer metaFileVersion;
  private final byte[] content;

  public BgVatGeneratedFileDto(
      String scope, String fileName, Long metaFileId, Integer metaFileVersion, byte[] content) {
    this.scope = scope;
    this.fileName = fileName;
    this.metaFileId = metaFileId;
    this.metaFileVersion = metaFileVersion;
    this.content = content;
  }

  public String getScope() {
    return scope;
  }

  public String getFileName() {
    return fileName;
  }

  public Long getMetaFileId() {
    return metaFileId;
  }

  public Integer getMetaFileVersion() {
    return metaFileVersion;
  }

  public byte[] getContent() {
    return content;
  }
}
