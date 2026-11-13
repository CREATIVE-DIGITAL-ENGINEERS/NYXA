package com.axelor.apps.napsubmissions.common.signing.browser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.common.signing.DetachedPkcs7Verifier;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Файлът, качен след подписване в портала на Evrotrust, става детачният PKCS#7 за НАП — и при
 * детачна (.p7s), и при прикрепена (.p7m) форма, като DER и като base64/PEM текст. Подписите тук са
 * истински CMS с подписани атрибути (CAdES форма), направени с BouncyCastle, а детачният резултат
 * се проверява със същия {@link DetachedPkcs7Verifier}, който пази пътя към НАП.
 */
class PortalSignedPkcs7Test {

  private static final String CA_DN = "CN=Test Evrotrust CA, O=Nyxa test";
  private static final String LEAF_DN = "CN=Test Signer, O=Nyxa test";
  private static final byte[] LOCKED = "EMPL2021 locked bytes\r\n".getBytes(StandardCharsets.UTF_8);

  private static KeyPair ca;
  private static KeyPair leafKeys;
  private static X509Certificate caCert;
  private static X509Certificate leaf;

  @BeforeAll
  static void keys() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    ca = generator.generateKeyPair();
    leafKeys = generator.generateKeyPair();
    caCert = TestCertificates.selfSigned(ca, CA_DN);
    leaf = TestCertificates.issuedBy(leafKeys.getPublic(), ca, CA_DN, LEAF_DN);
  }

  private static byte[] cms(byte[] content, boolean attached, boolean withCerts, int signers)
      throws Exception {
    CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
    for (int i = 0; i < signers; i++) {
      generator.addSignerInfoGenerator(
          new JcaSignerInfoGeneratorBuilder(
                  new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
              .build(
                  new JcaContentSignerBuilder("SHA256withRSA")
                      .setProvider("BC")
                      .build(leafKeys.getPrivate()),
                  leaf));
    }
    if (withCerts) {
      generator.addCertificates(new JcaCertStore(List.of(leaf, caCert)));
    }
    return generator.generate(new CMSProcessableByteArray(content), attached).getEncoded();
  }

  private static void assertReadyForNap(PortalSignedPkcs7.Parsed parsed) throws Exception {
    assertEquals(leaf, parsed.signer);
    assertEquals(List.of(caCert), parsed.chain);
    assertNull(
        new CMSSignedData(parsed.detachedPkcs7).getSignedContent(), "NAP gets a DETACHED PKCS#7");
    assertTrue(
        DetachedPkcs7Verifier.verifies(LOCKED, parsed.detachedPkcs7, leaf.getEncoded()),
        "the detached signature verifies over the exact locked bytes");
  }

  @Test
  void detachedP7sIsAccepted() throws Exception {
    assertReadyForNap(PortalSignedPkcs7.parse(cms(LOCKED, false, true, 1), LOCKED, "EMPL2021.TXT"));
  }

  @Test
  void attachedP7mBecomesDetached() throws Exception {
    assertReadyForNap(PortalSignedPkcs7.parse(cms(LOCKED, true, true, 1), LOCKED, "EMPL2021.TXT"));
  }

  @Test
  void base64AndPemTextAreAccepted() throws Exception {
    String b64 = Base64.getMimeEncoder().encodeToString(cms(LOCKED, true, true, 1));
    assertReadyForNap(
        PortalSignedPkcs7.parse(b64.getBytes(StandardCharsets.US_ASCII), LOCKED, "DEC6.TXT"));
    String pem = "-----BEGIN PKCS7-----\n" + b64 + "\n-----END PKCS7-----\n";
    assertReadyForNap(
        PortalSignedPkcs7.parse(pem.getBytes(StandardCharsets.US_ASCII), LOCKED, "DEC6.TXT"));
  }

  @Test
  void attachedContentThatDiffersFromTheLockedFileIsRefused() throws Exception {
    byte[] other = "something else".getBytes(StandardCharsets.UTF_8);
    QesException e =
        assertThrows(
            QesException.class,
            () -> PortalSignedPkcs7.parse(cms(other, true, true, 1), LOCKED, "EMPL2021.TXT"));
    assertTrue(e.getMessage().contains("different data"), e.getMessage());
  }

  @Test
  void detachedSignatureOverOtherBytesDoesNotVerify() throws Exception {
    byte[] other = "something else".getBytes(StandardCharsets.UTF_8);
    PortalSignedPkcs7.Parsed parsed =
        PortalSignedPkcs7.parse(cms(other, false, true, 1), LOCKED, "EMPL2021.TXT");
    // Тук не се отказва (детачният не носи данните); отказва verifyTransient преди мрежата.
    assertTrue(!DetachedPkcs7Verifier.verifies(LOCKED, parsed.detachedPkcs7, leaf.getEncoded()));
  }

  @Test
  void aFileThatIsNotPkcs7IsRefusedClearly() {
    QesException e =
        assertThrows(
            QesException.class,
            () -> PortalSignedPkcs7.parse("%PDF-1.7 not a signature".getBytes(), LOCKED, "X.TXT"));
    assertTrue(e.getMessage().contains("PKCS#7"), e.getMessage());
    assertThrows(QesException.class, () -> PortalSignedPkcs7.parse(new byte[0], LOCKED, "X.TXT"));
  }

  @Test
  void signatureWithoutTheSignerCertificateIsRefused() {
    assertThrows(
        QesException.class,
        () -> PortalSignedPkcs7.parse(cms(LOCKED, false, false, 1), LOCKED, "X.TXT"));
  }

  @Test
  void moreThanOneSignatureIsRefused() {
    assertThrows(
        QesException.class,
        () -> PortalSignedPkcs7.parse(cms(LOCKED, false, true, 2), LOCKED, "X.TXT"));
  }

  @Test
  void detachingKeepsTheSignatureBytes() throws Exception {
    byte[] attached = cms(LOCKED, true, true, 1);
    PortalSignedPkcs7.Parsed parsed = PortalSignedPkcs7.parse(attached, LOCKED, "X.TXT");
    assertArrayEquals(
        new CMSSignedData(attached).getSignerInfos().getSigners().iterator().next().getSignature(),
        new CMSSignedData(parsed.detachedPkcs7)
            .getSignerInfos()
            .getSigners()
            .iterator()
            .next()
            .getSignature());
  }
}
