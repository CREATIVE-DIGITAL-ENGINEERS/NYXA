package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;

/**
 * SHA-256 hex helper for VAT package artifacts.
 *
 * <p><b>⚠️ NO LONGER A SECOND IMPLEMENTATION — it now delegates.</b> The javadoc that stood here
 * said this class "mirrors" {@code NapArtifactService#sha256Hex} and was "kept VAT-local to avoid a
 * cross-module dependency". That dependency has existed since Д1/Д6 started attaching files through
 * {@code nyxa-nap-submissions}, so the copy stopped buying isolation and only kept a second answer
 * alive for the same question. The body is gone; {@link RegulatoryDigest} is the one definition.
 *
 * <p><b>⚠️ THE {@code null} GUARD STAYS HERE, AND THAT IS THE WHOLE REASON THIS CLASS SURVIVES.</b>
 * The ДДС stack has always hashed {@code null} as the empty file; the НАП path has always thrown.
 * Both behaviours have callers, and folding them into the shared helper would have silently changed
 * one of the two — so the difference is kept where it belongs: at the caller, one line, visible.
 */
public final class BgVatHashUtil {

  private BgVatHashUtil() {}

  /** Lower-case hex SHA-256 of the exact bytes; {@code null} is hashed as the empty file. */
  public static String sha256Hex(byte[] bytes) {
    return RegulatoryDigest.sha256Hex(bytes == null ? new byte[0] : bytes);
  }
}
