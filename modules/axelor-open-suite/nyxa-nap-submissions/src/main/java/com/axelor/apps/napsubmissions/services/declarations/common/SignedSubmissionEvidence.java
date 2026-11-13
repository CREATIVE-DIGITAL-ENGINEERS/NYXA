package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The verified signing evidence of one {@link com.axelor.apps.napsubmissions.db.NapSubmission},
 * produced by {@link DeclarationsEvidenceService} immediately before a submit. Everything here was
 * re-read from storage and re-hashed in that same check — it is the proof that what will be sent is
 * byte-identical to what was signed.
 */
public class SignedSubmissionEvidence {

  /** Base64 of the single signer certificate shared by every file (НАП {@code userSignature}). */
  public final String certificateBase64;

  /** SHA-256 of that certificate, as recorded on every file. */
  public final String certificateSha256;

  /** The verified files, ordered by their sequence (the order they will be sent in). */
  public final List<NapSubmissionFile> files;

  /** The per-file НАП document types actually carried by this submission. */
  public final Set<FileDocumentType> fileDocumentTypes;

  public SignedSubmissionEvidence(
      String certificateBase64,
      String certificateSha256,
      List<NapSubmissionFile> files,
      Set<FileDocumentType> fileDocumentTypes) {
    this.certificateBase64 = certificateBase64;
    this.certificateSha256 = certificateSha256;
    this.files = Collections.unmodifiableList(files);
    this.fileDocumentTypes = Collections.unmodifiableSet(fileDocumentTypes);
  }
}
