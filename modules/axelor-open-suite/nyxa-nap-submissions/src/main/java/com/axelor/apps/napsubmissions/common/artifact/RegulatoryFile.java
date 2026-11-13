package com.axelor.apps.napsubmissions.common.artifact;

import java.util.Arrays;

/**
 * ONE FINISHED REGULATORY FILE, before it ever touches the database — bytes, name, institution, and
 * the fingerprint of those exact bytes.
 *
 * <p><b>Why the immutability is a type and not a convention.</b> Until now "the bytes are frozen at
 * creation" was a sentence in {@link NapArtifactService}'s javadoc and a {@code finalized} boolean
 * on the row. Both describe the intent; neither prevents a service from computing the hash, then
 * handing the array on and letting a later step append to it. Here the array is copied in and
 * copied out, and {@link #getSha256()} is computed once in the constructor — so a caller physically
 * cannot hand out one set of bytes and store the fingerprint of another.
 *
 * <p><b>Pure.</b> No repository, no {@code MetaFile}, no Guice — which is why the НОИ emitter can
 * be tested end to end without Postgres, exactly like {@code BgD1SubmissionPlan} is.
 */
public final class RegulatoryFile {

  private final Institution institution;
  private final String fileName;
  private final String mimeType;
  private final byte[] bytes;
  private final String sha256;

  /**
   * @param institution which authority the file is addressed to — never {@code null}
   * @param fileName the name the authority expects (Д1: {@code EMPL2021.TXT}; НОИ: {@code
   *     Pril9_ddmmyyyy_n.xml})
   * @param mimeType the content type, for the stored {@code MetaFile}
   * @param bytes the exact content; copied, never referenced
   */
  public RegulatoryFile(Institution institution, String fileName, String mimeType, byte[] bytes) {
    if (institution == null) {
      throw new IllegalArgumentException("institution is required — the core has no default");
    }
    if (fileName == null || fileName.trim().isEmpty()) {
      throw new IllegalArgumentException("fileName is required");
    }
    if (bytes == null) {
      // ⚠️ Not an empty array. A missing payload is a defect upstream, and the fingerprint of the
      // empty file is a perfectly valid-looking answer to it.
      throw new IllegalArgumentException(
          "bytes are required — missing content is not an empty file");
    }
    this.institution = institution;
    this.fileName = fileName.trim();
    this.mimeType = mimeType;
    this.bytes = Arrays.copyOf(bytes, bytes.length);
    this.sha256 = RegulatoryDigest.sha256Hex(this.bytes);
  }

  public Institution getInstitution() {
    return institution;
  }

  public String getFileName() {
    return fileName;
  }

  public String getMimeType() {
    return mimeType;
  }

  /** A copy of the content. The original array never leaves this object. */
  public byte[] getBytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }

  /** Lower-case hex SHA-256 of {@link #getBytes()}, computed once at construction. */
  public String getSha256() {
    return sha256;
  }

  public long getByteLength() {
    return bytes.length;
  }

  @Override
  public String toString() {
    return institution.getLabel()
        + " · "
        + fileName
        + " · "
        + bytes.length
        + " B · "
        + sha256.substring(0, 12)
        + "…";
  }
}
