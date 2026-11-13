package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.db.Model;
import java.util.Objects;

/**
 * THE server-side immutability rules for finalized regulatory artifacts and signed submissions, in
 * one pure place so the repository guards and their tests share a single definition.
 *
 * <p><b>Why pure static methods over POJOs.</b> Each check compares an {@code original} snapshot
 * (what the database currently holds) with the {@code incoming} entity (what a {@code save} is
 * about to write). The repository builds the snapshot from the committed row (read without flushing
 * the dirty entity); a test builds it by hand. Neither needs Postgres, and both exercise the exact
 * same comparison — so "readonly in the UI" is backed by a real guard, not a hope.
 *
 * <p>No message here carries a secret, a PIN or any payload content — only field names.
 */
public final class RegulatoryImmutabilityGuard {

  private RegulatoryImmutabilityGuard() {}

  /**
   * CONTENT/SELECTION lock: a submission's declared content (company, period, document type,
   * taxpayer) and its file set are frozen once its status is anything other than {@code draft} or
   * {@code signing}. This now includes the pre-signing {@code locked} state (the operator froze the
   * selection) as well as {@code signed} and every later transport state.
   */
  public static boolean isSubmissionLocked(String status) {
    return status != null
        && !NapSubmissionRepository.STATUS_DRAFT.equals(status)
        && !NapSubmissionRepository.STATUS_SIGNING.equals(status);
  }

  /**
   * SIGNER/SIGNATURE lock: whose КЕП signed and the per-file signature/certificate/requested-digest
   * are frozen only once a signature actually exists — i.e. from {@code signed} onward, NOT while
   * merely {@code locked}. This is what lets the lifecycle move {@code locked → signing → signed}:
   * the signing step still has to record the signer and write each file's signature, which the
   * content lock above deliberately leaves alone.
   */
  public static boolean isSignerLocked(String status) {
    return isSubmissionLocked(status) && !NapSubmissionRepository.STATUS_LOCKED.equals(status);
  }

  /**
   * A finalized artifact's bytes, hash, length, finalized flag and stored file can never change.
   * Does nothing for a not-yet-finalized original (first persistence is always allowed).
   */
  public static void checkArtifact(NapArtifact original, NapArtifact incoming) {
    if (original == null) {
      return;
    }
    // WHOSE FILE IT IS (decision of 17.10.2026): every file belongs to exactly one company and
    // never
    // moves — finalized or not, for every user including admin. The catalogue reads files by
    // company, so a move is a disclosure to another company. Only the server fills a blank: at
    // creation, and the start-up/migration back-fill, which writes by bulk update and never reaches
    // a repository save.
    requireUnchanged(
        "artifact", "company", refId(original.getCompany()), refId(incoming.getCompany()));
    if (!Boolean.TRUE.equals(original.getFinalized())) {
      return;
    }
    requireUnchanged("artifact", "SHA-256", original.getSha256(), incoming.getSha256());
    requireUnchanged("artifact", "byte length", original.getByteLength(), incoming.getByteLength());
    requireUnchanged(
        "artifact", "finalized flag", original.getFinalized(), incoming.getFinalized());
    requireUnchanged(
        "artifact", "stored file", refId(original.getMetaFile()), refId(incoming.getMetaFile()));
  }

  /**
   * Once a submission is locked, its taxpayer / period / document-type / company identity cannot
   * change — a correction must create a NEW submission rather than mutate the locked one. The
   * signer profile is frozen only from {@code signed} onward (see {@link #isSignerLocked}), so the
   * signing step can still record WHO signs while the content stays frozen.
   */
  public static void checkSubmission(NapSubmission original, NapSubmission incoming) {
    if (original == null) {
      return;
    }
    String status = original.getSubmissionStatusSelect();
    if (isSubmissionLocked(status)) {
      requireUnchanged(
          "locked submission",
          "service code",
          original.getServiceCodeSelect(),
          incoming.getServiceCodeSelect());
      requireUnchanged(
          "locked submission",
          "document type",
          original.getServiceDocumentTypeSelect(),
          incoming.getServiceDocumentTypeSelect());
      requireUnchanged(
          "locked submission", "taxpayer ID", original.getTaxpayerPin(), incoming.getTaxpayerPin());
      requireUnchanged(
          "locked submission",
          "taxpayer ID type",
          original.getTaxpayerPinTypeSelect(),
          incoming.getTaxpayerPinTypeSelect());
      requireUnchanged(
          "locked submission",
          "company",
          refId(original.getCompany()),
          refId(incoming.getCompany()));
      requireUnchanged(
          "locked submission",
          "reporting period",
          refId(original.getPeriod()),
          refId(incoming.getPeriod()));
    }
    if (isSignerLocked(status)) {
      // WHOSE КЕП signed is part of the signed identity: the НАП author cannot be rewritten after
      // the fact, neither to another person's profile nor to none.
      requireUnchanged(
          "signed submission",
          "signer QES profile",
          refId(original.getQesSignerProfile()),
          refId(incoming.getQesSignerProfile()));
    }
  }

  /**
   * A file's payload, detached signature, signer-certificate snapshot and requested digest are
   * frozen once the file is itself {@code COMPLETED} (signed) OR once its parent submission is
   * locked — whichever happens first. This is what makes "the signed payload list / artifacts
   * cannot change" true at persistence time, not only in the UI.
   */
  public static void checkFile(
      NapSubmissionFile original, String parentStatus, NapSubmissionFile incoming) {
    boolean contentLocked = isSubmissionLocked(parentStatus);
    if (original == null) {
      // A brand-new file is fine — unless it is being added to a locked (or signed) submission,
      // which would change the frozen payload/selection list.
      if (contentLocked) {
        throw new RegulatoryImmutabilityException(
            "Cannot add a file to a locked submission; create a new submission instead");
      }
      return;
    }
    boolean fileSigned =
        NapSubmissionFileRepository.QES_STATUS_COMPLETED.equals(
            original.getQesSigningStatusSelect());
    // Payload bytes/reference are CONTENT: frozen from the moment the submission is locked, or once
    // this file is itself signed.
    if (contentLocked || fileSigned) {
      requireUnchanged(
          "locked file",
          "payload",
          refId(original.getPayloadArtifact()),
          refId(incoming.getPayloadArtifact()));
    }
    // Signature / certificate / requested digest are PRODUCED by the signing step, so they are
    // frozen only once the file is signed or the submission is past signing (signed+). A merely
    // locked submission leaves them writable, so signing can fill them in on the exact frozen
    // bytes.
    if (fileSigned || isSignerLocked(parentStatus)) {
      requireUnchanged(
          "signed file",
          "signature",
          refId(original.getSignatureArtifact()),
          refId(incoming.getSignatureArtifact()));
      requireUnchanged(
          "signed file",
          "signer certificate",
          refId(original.getSignerCertificateArtifact()),
          refId(incoming.getSignerCertificateArtifact()));
      requireUnchanged(
          "signed file",
          "signer certificate SHA-256",
          original.getSignerCertificateSha256(),
          incoming.getSignerCertificateSha256());
      requireUnchanged(
          "signed file",
          "requested digest",
          original.getQesRequestedSha256(),
          incoming.getQesRequestedSha256());
    }
  }

  /**
   * WHAT a submission files and FOR WHOM never change once set — BEFORE filing ({@code draft} /
   * {@code signing}). The gate of every filing action is chosen by the document's kind ({@code
   * NapFilingGate}); a kind that could be edited in a draft would let someone with write on
   * submissions relabel another area's submission as their own and then mark it filed. Setting a
   * value that was still empty is allowed (creators fill them in steps). Once filed, the whole row
   * is evidence and only the rule of #334 applies ({@link NapSubmissionEvidence}).
   */
  public static void checkFilingIdentity(NapSubmission original, NapSubmission incoming) {
    if (original == null || isSubmissionLocked(original.getSubmissionStatusSelect())) {
      return;
    }
    requireKept("service code", original.getServiceCodeSelect(), incoming.getServiceCodeSelect());
    requireKept(
        "document type",
        original.getServiceDocumentTypeSelect(),
        incoming.getServiceDocumentTypeSelect());
    requireKept("related model", original.getRelatedModel(), incoming.getRelatedModel());
    requireKept(
        "related record", nonZero(original.getRelatedRecordId()), incoming.getRelatedRecordId());
    requireKept("company", refId(original.getCompany()), refId(incoming.getCompany()));
  }

  private static Long nonZero(Long id) {
    return id == null || id == 0L ? null : id;
  }

  private static void requireKept(String field, Object original, Object incoming) {
    if (original == null || (original instanceof String && ((String) original).isEmpty())) {
      return;
    }
    requireUnchanged("submission", field, original, incoming);
  }

  private static Long refId(Model model) {
    return model == null ? null : model.getId();
  }

  private static void requireUnchanged(
      String what, String field, Object original, Object incoming) {
    if (!Objects.equals(original, incoming)) {
      throw new RegulatoryImmutabilityException(
          "Cannot change the " + field + " of a " + what + " once it has been persisted");
    }
  }
}
