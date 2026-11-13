package com.axelor.apps.napsubmissions.db.repo;

import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityException;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityGuard;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.db.JPA;
import java.util.List;
import javax.persistence.FlushModeType;

/**
 * Server-side immutability guard for {@link NapSubmissionFile}: a file's payload, detached
 * signature, signer-certificate snapshot and requested digest are frozen once the file is signed or
 * its parent submission is locked, and a signed submission's payload file list cannot grow or
 * shrink.
 */
public class NapSubmissionFileManagementRepository extends NapSubmissionFileRepository {

  @Override
  public NapSubmissionFile save(NapSubmissionFile entity) {
    NapSubmissionFile original = entity.getId() == null ? null : loadCommitted(entity.getId());
    String parentStatus = committedSubmissionStatus(parentId(entity, original));
    RegulatoryImmutabilityGuard.checkFile(original, parentStatus, entity);
    return super.save(entity);
  }

  @Override
  public void remove(NapSubmissionFile entity) {
    String parentStatus = committedSubmissionStatus(parentId(entity, null));
    if (RegulatoryImmutabilityGuard.isSubmissionLocked(parentStatus)) {
      throw new RegulatoryImmutabilityException(
          "Cannot remove a file from a signed submission; create a new submission instead");
    }
    super.remove(entity);
  }

  protected Long parentId(NapSubmissionFile entity, NapSubmissionFile original) {
    if (entity != null && entity.getSubmission() != null) {
      return entity.getSubmission().getId();
    }
    if (original != null && original.getSubmission() != null) {
      return original.getSubmission().getId();
    }
    return null;
  }

  protected String committedSubmissionStatus(Long submissionId) {
    if (submissionId == null) {
      return null;
    }
    List<String> rows =
        JPA.em()
            .createQuery(
                "SELECT s.submissionStatusSelect FROM NapSubmission s WHERE s.id = :id",
                String.class)
            .setParameter("id", submissionId)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0);
  }

  protected NapSubmissionFile loadCommitted(Long id) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT f.qesSigningStatusSelect, pay.id, sig.id, cert.id,"
                    + " f.signerCertificateSha256, f.qesRequestedSha256, sub.id"
                    + " FROM NapSubmissionFile f"
                    + " LEFT JOIN f.payloadArtifact pay"
                    + " LEFT JOIN f.signatureArtifact sig"
                    + " LEFT JOIN f.signerCertificateArtifact cert"
                    + " LEFT JOIN f.submission sub"
                    + " WHERE f.id = :id",
                Object[].class)
            .setParameter("id", id)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = rows.get(0);
    NapSubmissionFile original = new NapSubmissionFile();
    original.setQesSigningStatusSelect((String) row[0]);
    original.setPayloadArtifact(artifactRef((Long) row[1]));
    original.setSignatureArtifact(artifactRef((Long) row[2]));
    original.setSignerCertificateArtifact(artifactRef((Long) row[3]));
    original.setSignerCertificateSha256((String) row[4]);
    original.setQesRequestedSha256((String) row[5]);
    if (row[6] != null) {
      NapSubmission submission = new NapSubmission();
      submission.setId((Long) row[6]);
      original.setSubmission(submission);
    }
    return original;
  }

  private static NapArtifact artifactRef(Long id) {
    if (id == null) {
      return null;
    }
    NapArtifact artifact = new NapArtifact();
    artifact.setId(id);
    return artifact;
  }
}
