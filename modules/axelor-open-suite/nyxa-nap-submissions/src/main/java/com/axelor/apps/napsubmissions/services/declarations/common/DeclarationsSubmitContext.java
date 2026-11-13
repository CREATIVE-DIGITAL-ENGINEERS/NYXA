package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import java.util.Map;

/**
 * The transport-level scalar inputs a caller must supply to build a submit request, i.e. the values
 * that are NOT already snapshotted on the {@code NapSubmission} / its files. Kept separate so the
 * transport layer carries no business modelling.
 *
 * <ul>
 *   <li>{@code userCertificateBase64} — the submitting user's PUBLIC certificate (base64, header/
 *       footer + newlines stripped). Sourced by the caller from the signing side; the private KEP
 *       key never enters Nyxa.
 *   <li>{@code userPin}/{@code userPinType} — the SIGNER's official identifier, i.e. the owner of
 *       the certificate in {@code userCertificateBase64}. It lives on the signer's {@code
 *       UserQesProfile} (where it is encrypted at rest) and is deliberately NOT copied onto the
 *       submission, which is neither encrypted nor hidden on screen.
 *   <li>{@code taxPeriodFrom}/{@code taxPeriodTo}/{@code year}/{@code insuranceFund} — required
 *       only for {@code DEC_1_6}; the per-service phase fills these from payroll data.
 * </ul>
 */
public class DeclarationsSubmitContext {
  public String userCertificateBase64;
  public String userPin;
  public String userPinType;
  public String taxPeriodFrom;
  public String taxPeriodTo;
  public String year;
  public Integer insuranceFund;

  /**
   * TRANSIENT signing material — the detached PKCS#7 of each file, held only in memory for this one
   * submission and never persisted. When present (the real sign-at-submit path), the mapper takes
   * the PKCS#7 from here instead of a stored {@code signatureArtifact}; when {@code null} it falls
   * back to the file's persisted signature artifact (legacy path). Keyed by the file instance so it
   * works before the rows have database ids.
   */
  public Map<NapSubmissionFile, byte[]> detachedPkcs7ByFile;
}
