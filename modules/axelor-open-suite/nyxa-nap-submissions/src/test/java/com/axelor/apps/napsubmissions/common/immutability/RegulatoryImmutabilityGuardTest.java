package com.axelor.apps.napsubmissions.common.immutability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.meta.db.MetaFile;
import org.junit.jupiter.api.Test;

/** The pure immutability rules behind the repository guards. */
class RegulatoryImmutabilityGuardTest {

  private NapArtifact finalizedArtifact() {
    MetaFile metaFile = new MetaFile();
    metaFile.setId(10L);
    NapArtifact artifact = new NapArtifact();
    artifact.setSha256("abc123");
    artifact.setByteLength(42L);
    artifact.setFinalized(Boolean.TRUE);
    artifact.setMetaFile(metaFile);
    return artifact;
  }

  @Test
  void finalizedArtifactHashCannotChange() {
    NapArtifact original = finalizedArtifact();
    NapArtifact changed = finalizedArtifact();
    changed.setSha256("deadbeef");

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkArtifact(original, changed));
  }

  @Test
  void finalizedArtifactStoredFileCannotChange() {
    NapArtifact original = finalizedArtifact();
    NapArtifact changed = finalizedArtifact();
    MetaFile other = new MetaFile();
    other.setId(99L);
    changed.setMetaFile(other);

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkArtifact(original, changed));
  }

  @Test
  void notYetFinalizedArtifactMayChange() {
    NapArtifact original = finalizedArtifact();
    original.setFinalized(Boolean.FALSE);
    NapArtifact changed = finalizedArtifact();
    changed.setSha256("deadbeef");

    assertDoesNotThrow(() -> RegulatoryImmutabilityGuard.checkArtifact(original, changed));
  }

  @Test
  void signedSubmissionIdentityCannotChange() {
    NapSubmission original = new NapSubmission();
    original.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);
    original.setTaxpayerPin("1234567890");

    NapSubmission changed = new NapSubmission();
    changed.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);
    changed.setTaxpayerPin("9999999999");

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkSubmission(original, changed));
  }

  /**
   * A LOCKED submission (the operator froze the selection before signing) is already treated as
   * locked — its reporting period cannot change, exactly as for a SIGNED one. This is what makes
   * the third-menu "lock" a real server-side freeze, not only readonly fields.
   */
  @Test
  void lockedSubmissionPeriodCannotChange() {
    Period january = new Period();
    january.setId(1L);
    Period february = new Period();
    february.setId(2L);

    NapSubmission original = new NapSubmission();
    original.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_LOCKED);
    original.setPeriod(january);

    NapSubmission changed = new NapSubmission();
    changed.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_LOCKED);
    changed.setPeriod(february);

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkSubmission(original, changed));
  }

  /** A locked submission's file set cannot grow — the selection is frozen. */
  @Test
  void cannotAddAFileToALockedSubmission() {
    NapSubmissionFile newFile = new NapSubmissionFile();
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            RegulatoryImmutabilityGuard.checkFile(
                null, NapSubmissionRepository.STATUS_LOCKED, newFile));
  }

  /**
   * The lock must not block signing: a LOCKED submission still has no signer, and recording WHO
   * signs is the first thing signing does. The signer profile is therefore writable while locked
   * (it freezes only from {@code signed} onward).
   */
  @Test
  void lockedSubmissionSignerProfileMayStillBeSet() {
    NapSubmission original = new NapSubmission();
    original.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_LOCKED);

    NapSubmission incoming = new NapSubmission();
    incoming.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_LOCKED);
    incoming.setQesSignerProfile(profile(7L));

    assertDoesNotThrow(() -> RegulatoryImmutabilityGuard.checkSubmission(original, incoming));
  }

  /**
   * On a LOCKED file the payload (content) is frozen, but the signing step must still be able to
   * write the requested digest / signature onto the exact frozen bytes.
   */
  @Test
  void lockedFileSigningFieldsAreWritableButPayloadIsFrozen() {
    NapArtifact payload = new NapArtifact();
    payload.setId(1L);

    NapSubmissionFile original = new NapSubmissionFile();
    original.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_NOT_STARTED);
    original.setPayloadArtifact(payload);

    // signing records the requested digest on a locked file — allowed
    NapSubmissionFile writing = new NapSubmissionFile();
    writing.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_PENDING);
    writing.setPayloadArtifact(payload);
    writing.setQesRequestedSha256("a".repeat(64));
    assertDoesNotThrow(
        () ->
            RegulatoryImmutabilityGuard.checkFile(
                original, NapSubmissionRepository.STATUS_LOCKED, writing));

    // but the payload itself cannot be re-pointed while locked
    NapArtifact other = new NapArtifact();
    other.setId(2L);
    NapSubmissionFile swap = new NapSubmissionFile();
    swap.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_NOT_STARTED);
    swap.setPayloadArtifact(other);
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            RegulatoryImmutabilityGuard.checkFile(
                original, NapSubmissionRepository.STATUS_LOCKED, swap));
  }

  /**
   * Whose КЕП signed is part of the signed identity: НАП was told an author, and that claim cannot
   * be rewritten afterwards — not to another person, and not to nobody.
   */
  @Test
  void signedSubmissionSignerProfileCannotChange() {
    UserQesProfile alice = profile(1L);
    UserQesProfile bob = profile(2L);

    NapSubmission original = new NapSubmission();
    original.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);
    original.setQesSignerProfile(alice);

    NapSubmission toBob = new NapSubmission();
    toBob.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);
    toBob.setQesSignerProfile(bob);

    NapSubmission toNone = new NapSubmission();
    toNone.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkSubmission(original, toBob));
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkSubmission(original, toNone));
  }

  /** Still a draft: a failed attempt may be re-signed by somebody else. */
  @Test
  void draftSubmissionSignerProfileMayChange() {
    NapSubmission original = new NapSubmission();
    original.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    original.setQesSignerProfile(profile(1L));

    NapSubmission changed = new NapSubmission();
    changed.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    changed.setQesSignerProfile(profile(2L));

    assertDoesNotThrow(() -> RegulatoryImmutabilityGuard.checkSubmission(original, changed));
  }

  private static UserQesProfile profile(Long id) {
    UserQesProfile profile = new UserQesProfile();
    profile.setId(id);
    return profile;
  }

  @Test
  void draftSubmissionIdentityMayChange() {
    NapSubmission original = new NapSubmission();
    original.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    original.setTaxpayerPin("1234567890");

    NapSubmission changed = new NapSubmission();
    changed.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    changed.setTaxpayerPin("9999999999");

    assertDoesNotThrow(() -> RegulatoryImmutabilityGuard.checkSubmission(original, changed));
  }

  @Test
  void signedFilePayloadAndSignatureCannotChange() {
    NapArtifact payload = new NapArtifact();
    payload.setId(1L);
    NapArtifact signature = new NapArtifact();
    signature.setId(2L);

    NapSubmissionFile original = new NapSubmissionFile();
    original.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_COMPLETED);
    original.setPayloadArtifact(payload);
    original.setSignatureArtifact(signature);

    NapSubmissionFile changed = new NapSubmissionFile();
    changed.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_COMPLETED);
    NapArtifact otherSignature = new NapArtifact();
    otherSignature.setId(3L);
    changed.setPayloadArtifact(payload);
    changed.setSignatureArtifact(otherSignature);

    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            RegulatoryImmutabilityGuard.checkFile(
                original, NapSubmissionRepository.STATUS_SIGNED, changed));
  }

  @Test
  void cannotAddAFileToASignedSubmission() {
    NapSubmissionFile newFile = new NapSubmissionFile();
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            RegulatoryImmutabilityGuard.checkFile(
                null, NapSubmissionRepository.STATUS_SIGNED, newFile));
  }

  // --- company: whose file it is (decision of 17.10.2026) --------------------------------------
  // Every file belongs to exactly one company and never moves — whether finalized or not. Only the
  // server fills a blank: at creation, and the start-up/migration back-fill, which writes by bulk
  // update and never reaches this guard.

  private static Company company(long id) {
    Company company = new Company();
    company.setId(id);
    return company;
  }

  @Test
  void artifactCompanyCannotMoveToAnotherCompany() {
    NapArtifact original = finalizedArtifact();
    original.setCompany(company(1L));
    NapArtifact changed = finalizedArtifact();
    changed.setCompany(company(2L));

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkArtifact(original, changed));
  }

  @Test
  void artifactCompanyCannotBeCleared() {
    NapArtifact original = finalizedArtifact();
    original.setCompany(company(1L));
    NapArtifact changed = finalizedArtifact();

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkArtifact(original, changed));
  }

  @Test
  void blankCompanyCannotBeFilledThroughASave() {
    NapArtifact original = finalizedArtifact();
    NapArtifact changed = finalizedArtifact();
    changed.setCompany(company(1L));

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkArtifact(original, changed));
  }

  @Test
  void companyIsFixedEvenBeforeFinalization() {
    NapArtifact original = finalizedArtifact();
    original.setFinalized(Boolean.FALSE);
    original.setCompany(company(1L));
    NapArtifact changed = finalizedArtifact();
    changed.setFinalized(Boolean.FALSE);
    changed.setCompany(company(2L));

    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> RegulatoryImmutabilityGuard.checkArtifact(original, changed));
  }

  @Test
  void sameCompanyIsNotAChange() {
    NapArtifact original = finalizedArtifact();
    original.setCompany(company(1L));
    NapArtifact same = finalizedArtifact();
    same.setCompany(company(1L));

    assertDoesNotThrow(() -> RegulatoryImmutabilityGuard.checkArtifact(original, same));
  }
}
