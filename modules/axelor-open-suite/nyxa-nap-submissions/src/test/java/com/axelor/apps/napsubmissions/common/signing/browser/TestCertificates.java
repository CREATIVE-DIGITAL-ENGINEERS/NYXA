package com.axelor.apps.napsubmissions.common.signing.browser;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * TEST-ONLY certificate helpers. They live in {@code src/test} so nothing they produce can travel a
 * production code path — the browser signing path refuses self-signed signer certificates anyway.
 */
final class TestCertificates {

  private TestCertificates() {}

  static X509Certificate selfSigned(KeyPair keyPair, String dn) throws Exception {
    return build(keyPair.getPublic(), keyPair, dn, dn);
  }

  /** A leaf certificate issued by {@code issuerKeys} — deliberately NOT self-signed. */
  static X509Certificate issuedBy(
      PublicKey subjectKey, KeyPair issuerKeys, String issuerDn, String subjectDn)
      throws Exception {
    return build(subjectKey, issuerKeys, issuerDn, subjectDn);
  }

  private static X509Certificate build(
      PublicKey subjectKey, KeyPair issuerKeys, String issuerDn, String subjectDn)
      throws Exception {
    Instant now = Instant.now();
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            new X500Name(issuerDn),
            BigInteger.valueOf(now.toEpochMilli() + subjectDn.hashCode()),
            Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(365, ChronoUnit.DAYS)),
            new X500Name(subjectDn),
            subjectKey);
    ContentSigner signer =
        new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(issuerKeys.getPrivate());
    X509CertificateHolder holder = builder.build(signer);
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(holder);
  }
}
