package com.axelor.apps.napsubmissions.common.artifact;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * THE fingerprint of a regulatory file — one implementation, for every institution.
 *
 * <p><b>Why this class exists.</b> The same twenty lines of SHA-256 hex were written twice: once in
 * {@link NapArtifactService#sha256Hex} (Д1/Д6, SAF-T) and once in {@code BgVatHashUtil} in the
 * localization module, whose own javadoc admitted it was a copy kept "to avoid a cross-module
 * dependency". A fingerprint is the one thing in a submission stack that must not have two
 * implementations: two copies can drift in case, in separator or in null handling, and the drift is
 * invisible until someone compares a stored hash against a file НОИ or НАП already holds.
 *
 * <p><b>The dependency argument is gone.</b> {@code axelor-bg-localization} already depends on this
 * module (it attaches Д1/Д6 through {@link NapArtifactService}), so the copy no longer buys
 * isolation — it only buys a second answer.
 *
 * <p><b>⚠️ The two callers differ on {@code null}, and this class refuses to pick a winner.</b> The
 * НАП path throws (missing bytes are a defect, not an empty file); the ДДС helper substitutes an
 * empty array. Merging them here would silently change one of the two. So {@link
 * #sha256Hex(byte[])} keeps the strict НАП behaviour and each caller keeps its own guard in front
 * of it — see the characterization test that pins exactly this.
 */
public final class RegulatoryDigest {

  /** The algorithm, named once. Any change here changes every stored fingerprint. */
  public static final String ALGORITHM = "SHA-256";

  private RegulatoryDigest() {}

  /**
   * Lower-case hex SHA-256 over the exact bytes — 64 characters, no separators, no prefix.
   *
   * @throws NullPointerException when {@code bytes} is {@code null}; see the class javadoc
   */
  public static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
      byte[] hash = digest.digest(bytes);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by the JLS; this cannot happen on a standard JVM.
      throw new IllegalStateException(e);
    }
  }
}
