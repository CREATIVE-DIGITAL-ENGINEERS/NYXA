package com.axelor.apps.napsubmissions.common.signing;

import java.io.ByteArrayInputStream;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * PRODUCTION cryptographic check of a <b>detached</b> PKCS#7 (CMS SignedData) against the exact
 * bytes it must cover and the certificate of the signer it must belong to. This is what replaces a
 * mere SHA-256 compare of stored artifacts: it proves the signature was produced by the private key
 * of {@code certificateDer} over {@code payload} — i.e. both that the bytes are the signed bytes
 * and that the signer corresponds to the certificate НАП will be told about.
 *
 * <p>Stateless and side-effect-free. It reads no storage and writes nothing: the signature and the
 * certificate are handed in as transient in-memory bytes (see {@link TransientQesSignature}) and
 * are never persisted.
 */
public final class DetachedPkcs7Verifier {

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private DetachedPkcs7Verifier() {}

  /**
   * True only when {@code detachedPkcs7} is a CMS SignedData whose signer signature verifies, with
   * the public key of {@code certificateDer}, over exactly {@code payload}. A different signer, a
   * different payload, a malformed container or an unreadable certificate all return {@code false}
   * — the caller refuses before the network.
   *
   * @param payload the exact locked bytes that were signed (the declaration file content)
   * @param detachedPkcs7 the detached CMS/PKCS#7 signature bytes (no encapsulated content)
   * @param certificateDer the signer's public X.509 certificate in DER form
   */
  public static boolean verifies(byte[] payload, byte[] detachedPkcs7, byte[] certificateDer) {
    if (payload == null
        || detachedPkcs7 == null
        || detachedPkcs7.length == 0
        || certificateDer == null
        || certificateDer.length == 0) {
      return false;
    }
    try {
      X509Certificate certificate =
          (X509Certificate)
              CertificateFactory.getInstance("X.509")
                  .generateCertificate(new ByteArrayInputStream(certificateDer));
      CMSSignedData signed = new CMSSignedData(new CMSProcessableByteArray(payload), detachedPkcs7);
      for (SignerInformation signerInfo : signed.getSignerInfos().getSigners()) {
        try {
          boolean ok =
              signerInfo.verify(
                  new JcaSimpleSignerInfoVerifierBuilder()
                      .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                      .build(certificate));
          if (ok) {
            return true;
          }
        } catch (Exception mismatch) {
          // This signer was not produced by this certificate's key; try the next one.
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }
}
