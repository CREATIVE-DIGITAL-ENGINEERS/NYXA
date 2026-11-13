package com.axelor.apps.napsubmissions.common.signing.evrotrust;

/**
 * What the Evrotrust hash-download endpoint actually returns, as classified by the transport.
 *
 * <p><b>Why this exists.</b> НАП wants, per file, a <em>detached PKCS#7 / CMS SignedData over the
 * original TXT</em>. The Evrotrust hash flow signs a SHA-256 we supply, but the public
 * documentation does NOT state whether the download yields a full detached CMS (which binds to the
 * content via the signed {@code messageDigest} attribute and is directly НАП-usable) or a bare
 * signature value (which is NOT a PKCS#7 and must never be forwarded as one). The provider refuses
 * anything that is not {@link #DETACHED_PKCS7}: treating a raw signature as a ready PKCS#7 is
 * precisely the mistake the design forbids.
 */
public enum EvrotrustSignatureFormat {

  /** A detached CMS/PKCS#7 SignedData with no encapsulated content — directly usable by НАП. */
  DETACHED_PKCS7,

  /** A bare signature value (e.g. raw RSA/ECDSA over the hash). NOT a PKCS#7; must be refused. */
  RAW_SIGNATURE,

  /** The transport could not confirm the artifact is a detached PKCS#7. Fail closed. */
  UNKNOWN
}
