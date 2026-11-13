package com.axelor.apps.napsubmissions.common.artifact;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionArtifact;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionArtifactRepository;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central registry for regulatory files. Computes the SHA-256 / byte length / MIME type once, at
 * creation, from the stored {@link MetaFile} bytes — after that the artifact is treated as
 * immutable (the hash pins the exact content that was, or will be, submitted). The same artifact
 * can be linked to many submission attempts through {@link NapSubmissionArtifact} without copying
 * the file.
 *
 * <p><b>⚠️ THE REGISTRY IS NO LONGER НАП-ONLY, AND THE НАП PATH DID NOT MOVE.</b> НОИ (Приложение №
 * 9) is a second client of the same guarantee — hashed once, stored once, never edited — and it
 * enters through {@link #createFromFile(RegulatoryFile, String)}. {@link
 * #createFromMetaFile(MetaFile, String, SaftReport)} keeps its exact signature and its exact
 * behaviour for Д1/Д6 and SAF-T; the only difference is that the row now says out loud which
 * authority it is addressed to ({@link Institution#NAP}), where before it said nothing.
 *
 * <p>The digest itself lives in {@link RegulatoryDigest} — one implementation, so a stored
 * fingerprint means the same thing on both sides.
 *
 * <p><b>⚠️ EVERY ROW SAYS WHOSE FILE IT IS ({@code company}).</b> The nyxa-security catalogue
 * scopes reading by {@code self.company}; a row without one is readable by admin only. The company
 * comes from the document or submission the file is made for — never from the user's active
 * company, which says who clicked, not whose file it is. Each entry point below takes it: {@link
 * #createForSubmission}, {@link #createForCompany}, {@link #createFromFile(RegulatoryFile, String,
 * Company)}; the SAF-T entry point reads it from the report. The two-argument {@link
 * #createFromFile(RegulatoryFile, String)} stays for callers in flight and stores no company — the
 * only intended user is the SAF-T XSD, one shared row for all companies.
 */
@Singleton
public class NapArtifactService {

  protected final NapArtifactRepository artifactRepo;
  protected final NapSubmissionArtifactRepository submissionArtifactRepo;
  protected final MetaFiles metaFiles;

  @Inject
  public NapArtifactService(
      NapArtifactRepository artifactRepo,
      NapSubmissionArtifactRepository submissionArtifactRepo,
      MetaFiles metaFiles) {
    this.artifactRepo = artifactRepo;
    this.submissionArtifactRepo = submissionArtifactRepo;
    this.metaFiles = metaFiles;
  }

  /**
   * Create an artifact from a stored MetaFile, hashing its exact bytes.
   *
   * <p>The НАП entry point, unchanged. The row is stamped {@link Institution#NAP} — the one
   * addition, and an additive one: before the column existed the same row simply did not say. The
   * company is the report's ({@code null} report — no company: use {@link #createForCompany}).
   */
  @Transactional
  public NapArtifact createFromMetaFile(
      MetaFile metaFile, String artifactType, SaftReport saftReport) throws IOException {
    return createFromMetaFile(metaFile, artifactType, saftReport, Institution.NAP);
  }

  /**
   * BYTES → IMMUTABLE ARTIFACT, for any institution — the door НОИ enters through.
   *
   * <p><b>⚠️ The upload happens here, not at the caller.</b> A caller that uploads its own {@code
   * MetaFile} and then asks for an artifact holds, for one statement, a stored file whose
   * fingerprint nobody has taken — and that is precisely the window in which a retry, a rollback or
   * a second writer leaves an orphan on disk. {@link RegulatoryFile} carries the digest of the
   * exact bytes it was built from, so what is stored and what is hashed cannot come apart.
   *
   * @param file the finished content, its name and its institution
   * @param artifactType one of the {@code NapArtifactRepository.TYPE_*} values
   */
  @Transactional
  public NapArtifact createFromFile(RegulatoryFile file, String artifactType) throws IOException {
    return createFromFile(file, artifactType, null);
  }

  /**
   * {@link #createFromFile(RegulatoryFile, String)} for the file of one company — the door for
   * every generated file (Приложение № 9, чл. 73, the QES signature and certificate).
   *
   * @param company the company of the document the file is made for; {@code null} only for a file
   *     shared by all companies (the SAF-T XSD)
   */
  @Transactional
  public NapArtifact createFromFile(RegulatoryFile file, String artifactType, Company company)
      throws IOException {
    MetaFile metaFile =
        metaFiles.upload(new ByteArrayInputStream(file.getBytes()), file.getFileName());
    return createFromMetaFile(metaFile, artifactType, null, file.getInstitution(), company);
  }

  /**
   * The generated SAF-T file of a report (G6b): uploaded here, from the exact bytes, so what is
   * stored and what is hashed cannot come apart. The company is the report's.
   */
  @Transactional
  public NapArtifact createForReport(RegulatoryFile file, String artifactType, SaftReport report)
      throws IOException {
    MetaFile metaFile =
        metaFiles.upload(new ByteArrayInputStream(file.getBytes()), file.getFileName());
    return createFromMetaFile(
        metaFile, artifactType, report, file.getInstitution(), report.getCompany());
  }

  /**
   * An НАП file of one company that is not part of a SAF-T report (Д1/Д6, ЕТЗ, a declaration filed
   * through the portal).
   */
  @Transactional
  public NapArtifact createForCompany(MetaFile metaFile, String artifactType, Company company)
      throws IOException {
    return createFromMetaFile(metaFile, artifactType, null, Institution.NAP, company);
  }

  /**
   * A file that belongs to a submission attempt (НАП response, processing result): the submission's
   * company and, when it has one, its SAF-T report.
   */
  @Transactional
  public NapArtifact createForSubmission(
      MetaFile metaFile, String artifactType, NapSubmission submission) throws IOException {
    return createFromMetaFile(
        metaFile,
        artifactType,
        submission.getSaftReport(),
        Institution.NAP,
        submission.getCompany());
  }

  /** Unchanged signature for existing callers: the company is the report's, if any. */
  @Transactional
  public NapArtifact createFromMetaFile(
      MetaFile metaFile, String artifactType, SaftReport saftReport, Institution institution)
      throws IOException {
    return createFromMetaFile(metaFile, artifactType, saftReport, institution, null);
  }

  /**
   * The shared body: hash the stored bytes, stamp the institution and the company, freeze the row.
   *
   * <p>⚠️ The hash is taken from the file <b>as stored</b>, not from the array the caller passed —
   * so an upload that truncated or re-encoded the content produces a mismatching fingerprint here
   * instead of a plausible one.
   *
   * <p>An explicit company wins; without one, the report's.
   *
   * @param company whose file it is; {@code null} — the report's company, or none without a report
   */
  @Transactional
  public NapArtifact createFromMetaFile(
      MetaFile metaFile,
      String artifactType,
      SaftReport saftReport,
      Institution institution,
      Company company)
      throws IOException {
    NapArtifact artifact = new NapArtifact();
    artifact.setInstitutionSelect(institution == null ? null : institution.getCode());
    artifact.setCompany(
        company != null ? company : saftReport == null ? null : saftReport.getCompany());
    artifact.setName(metaFile.getFileName());
    artifact.setOriginalFileName(metaFile.getFileName());
    artifact.setArtifactTypeSelect(artifactType);
    artifact.setMetaFile(metaFile);
    artifact.setMimeType(metaFile.getFileType());
    artifact.setSaftReport(saftReport);

    byte[] bytes = readStoredBytes(metaFile);
    artifact.setByteLength((long) bytes.length);
    artifact.setSha256(sha256Hex(bytes));
    // Finalized on creation: the bytes/hash are locked; no service may replace them afterwards.
    artifact.setFinalized(Boolean.TRUE);

    return artifactRepo.save(artifact);
  }

  /** The exact bytes of the file as stored — the input of the fingerprint. */
  protected byte[] readStoredBytes(MetaFile metaFile) throws IOException {
    Path path = MetaFiles.getPath(metaFile);
    return Files.readAllBytes(path);
  }

  /** Link an existing (immutable) artifact to a submission attempt with a role. */
  @Transactional
  public NapSubmissionArtifact link(NapSubmission submission, NapArtifact artifact, String role) {
    NapSubmissionArtifact link = new NapSubmissionArtifact();
    link.setSubmission(submission);
    link.setArtifact(artifact);
    link.setRoleSelect(role);
    return submissionArtifactRepo.save(link);
  }

  /**
   * Lower-case hex SHA-256 of the exact bytes.
   *
   * <p>⚠️ Kept as a {@code protected} method, not deleted, and still with this exact signature: it
   * is a seam a subclass may already override, and narrowing or widening it would break that
   * override silently. The body now delegates to {@link RegulatoryDigest} so the algorithm, the
   * case, and the {@code null} behaviour have one definition — pinned by {@code
   * NapArtifactHashCharacterizationTest}, which was green before this delegation and after it.
   */
  protected String sha256Hex(byte[] bytes) {
    return RegulatoryDigest.sha256Hex(bytes);
  }
}
