package com.axelor.apps.napsubmissions.common.signing;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * ⚠️ <b>TEST-ONLY подписващ адаптер.</b> Произвежда <b>истински</b> detached PKCS#7 (CMS
 * SignedData, encapsulate=false) върху точните подадени байтове, подписан с еднократен, саморъчно
 * подписан X.509 сертификат, създаден в паметта. Това е единственото, което добавя спрямо мока:
 * реална криптография вместо байт-низове, за да се упражни пътят „подпиши и изпрати" с годна по
 * форма структура.
 *
 * <p><b>Защо е само за тест и не може да стигне до production път.</b> Класът живее в {@code
 * src/test}, затова го няма на production classpath-а — нито едно production извикване не може да
 * го види, да го свърже или да приеме подписа му. Той НЕ имплементира {@link CloudQesProvider} и не
 * се регистрира в Guice; връща точно двойката, която истински {@code
 * CloudQesProvider.pollStatus(...) .getSignature()} един ден ще достави: {@link
 * #detachedPkcs7(byte[])} ↔ {@code QesSignature.getDetachedPkcs7()} и {@link #certificateDer()} ↔
 * {@code QesSignature.getCertificateBytes()}. Частен ключ никъде не напуска този обект.
 *
 * <p>Сертификатът е саморъчно подписан и за това НЕ би бил приет от НАП — целта тук е формата и
 * обвързаността на подписа с байтовете, а не доверие във веригата. Истинският КЕП адаптер остава
 * нереализиран (виж {@link QesSigningService}); този клас не го замества в production.
 */
public final class TestOnlyQesSigner {

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private final PrivateKey privateKey;
  private final X509Certificate certificate;

  public TestOnlyQesSigner(String signerCommonName) {
    try {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
      keyGen.initialize(2048);
      KeyPair keyPair = keyGen.generateKeyPair();
      this.privateKey = keyPair.getPrivate();

      X500Name subject = new X500Name("CN=" + signerCommonName + ", O=TEST-ONLY, C=BG");
      Instant now = Instant.now();
      JcaX509v3CertificateBuilder certBuilder =
          new JcaX509v3CertificateBuilder(
              subject, // self-signed: issuer == subject
              BigInteger.valueOf(now.toEpochMilli()),
              Date.from(now.minus(1, ChronoUnit.DAYS)),
              Date.from(now.plus(365, ChronoUnit.DAYS)),
              subject,
              keyPair.getPublic());
      ContentSigner certSigner =
          new JcaContentSignerBuilder("SHA256withRSA")
              .setProvider(BouncyCastleProvider.PROVIDER_NAME)
              .build(privateKey);
      X509CertificateHolder holder = certBuilder.build(certSigner);
      this.certificate =
          new JcaX509CertificateConverter()
              .setProvider(BouncyCastleProvider.PROVIDER_NAME)
              .getCertificate(holder);
    } catch (Exception e) {
      throw new IllegalStateException("TEST-ONLY signer could not be initialised", e);
    }
  }

  /**
   * The signer's public X.509 certificate in DER form (НАП's {@code userSignatureBase64} source).
   */
  public byte[] certificateDer() {
    try {
      return certificate.getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("Could not encode the test certificate", e);
    }
  }

  /**
   * A GENUINE detached PKCS#7 (CMS SignedData, no encapsulated content) over the exact {@code
   * payload} bytes, signed SHA256withRSA by this signer. The returned DER bytes are НАП's {@code
   * base64EncodedPkcs7} source once base64-encoded.
   */
  public byte[] detachedPkcs7(byte[] payload) {
    try {
      ContentSigner signer =
          new JcaContentSignerBuilder("SHA256withRSA")
              .setProvider(BouncyCastleProvider.PROVIDER_NAME)
              .build(privateKey);
      CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
      generator.addSignerInfoGenerator(
          new JcaSignerInfoGeneratorBuilder(
                  new JcaDigestCalculatorProviderBuilder()
                      .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                      .build())
              .build(signer, certificate));
      generator.addCertificates(new JcaCertStore(Collections.singletonList(certificate)));
      // encapsulate=false → DETACHED: the signed bytes are NOT carried inside the PKCS#7.
      CMSSignedData signed = generator.generate(new CMSProcessableByteArray(payload), false);
      return signed.getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("TEST-ONLY detached PKCS#7 generation failed", e);
    }
  }

  /**
   * Prove a detached PKCS#7 really is a signature over {@code payload} by this signer's certificate
   * — used by the end-to-end test to assert the request carries genuine, verifiable cryptography,
   * not just hash-matching placeholder bytes.
   */
  public boolean verifyDetached(byte[] payload, byte[] detachedPkcs7) {
    try {
      CMSSignedData signed = new CMSSignedData(new CMSProcessableByteArray(payload), detachedPkcs7);
      for (SignerInformation signerInfo : signed.getSignerInfos().getSigners()) {
        boolean ok =
            signerInfo.verify(
                new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(certificate));
        if (ok) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }
}
