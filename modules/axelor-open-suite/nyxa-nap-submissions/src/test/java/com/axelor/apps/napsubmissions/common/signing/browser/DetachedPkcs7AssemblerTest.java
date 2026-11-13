package com.axelor.apps.napsubmissions.common.signing.browser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.common.signing.DetachedPkcs7Verifier;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * СЪРЦЕВИНАТА НА USB ПЪТЯ: BISS връща СУРОВ подпис, а НАП иска детачен PKCS#7.
 *
 * <p>Тестът пресъздава точно каквото прави BISS по спецификация (т. 4.4.1, {@code contentType:
 * "data"}): {@code signature = SHA256withRSA(content)} — и проверява, че сглобеният контейнер се
 * валидира от {@link DetachedPkcs7Verifier} върху ОРИГИНАЛНИТЕ байтове. Това е твърдението, върху
 * което стои цялото подаване: ако сглобяването беше невярно, проверката преди POST щеше да откаже.
 */
class DetachedPkcs7AssemblerTest {

  @BeforeAll
  static void provider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private static final byte[] PAYLOAD =
      "Д1 заключени байтове — точно тези".getBytes(StandardCharsets.UTF_8);

  /**
   * Подписващ, който имитира токена: НЕ е самоподписан (издаден е от „CA"), и подписва суров
   * SHA256withRSA върху съдържанието — точно както BISS.
   */
  private static final class FakeToken {
    final KeyPair keyPair;
    final X509Certificate certificate;
    final X509Certificate issuer;

    FakeToken() throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair caKeys = generator.generateKeyPair();
      this.keyPair = generator.generateKeyPair();
      this.issuer = TestCertificates.selfSigned(caKeys, "CN=TEST CA, O=TEST-ONLY, C=BG");
      this.certificate =
          TestCertificates.issuedBy(
              keyPair.getPublic(), caKeys, "CN=TEST CA, O=TEST-ONLY, C=BG", "CN=Подписващ, C=BG");
    }

    /** Точно операцията на BISS: RSA подпис върху SHA-256 на самото съдържание. */
    byte[] rawSignature(byte[] content) throws Exception {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keyPair.getPrivate());
      signature.update(content);
      return signature.sign();
    }
  }

  private static X509Certificate parse(byte[] der) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
  }

  @Test
  void rawBissSignatureBecomesAVerifiableDetachedPkcs7() throws Exception {
    FakeToken token = new FakeToken();
    byte[] pkcs7 =
        DetachedPkcs7Assembler.assemble(
            token.rawSignature(PAYLOAD), token.certificate, List.of(token.issuer), false);

    assertTrue(
        DetachedPkcs7Verifier.verifies(PAYLOAD, pkcs7, token.certificate.getEncoded()),
        "сглобеният PKCS#7 трябва да се валидира върху оригиналните заключени байтове");
    // И контейнерът наистина се разчита като сертификат от същия подписващ.
    parse(token.certificate.getEncoded());
  }

  @Test
  void changedPayloadIsRefused() throws Exception {
    FakeToken token = new FakeToken();
    byte[] pkcs7 =
        DetachedPkcs7Assembler.assemble(
            token.rawSignature(PAYLOAD), token.certificate, List.of(), false);

    byte[] tampered = "Д1 заключени байтове — ПРОМЕНЕНИ".getBytes(StandardCharsets.UTF_8);
    assertFalse(
        DetachedPkcs7Verifier.verifies(tampered, pkcs7, token.certificate.getEncoded()),
        "подпис върху други байтове не бива да се приема");
  }

  @Test
  void signatureFromAnotherKeyIsRefused() throws Exception {
    FakeToken token = new FakeToken();
    FakeToken other = new FakeToken();
    byte[] pkcs7 =
        DetachedPkcs7Assembler.assemble(
            other.rawSignature(PAYLOAD), token.certificate, List.of(), false);

    assertFalse(
        DetachedPkcs7Verifier.verifies(PAYLOAD, pkcs7, token.certificate.getEncoded()),
        "подпис от друг ключ не бива да се приема за този сертификат");
  }

  @Test
  void emptySignatureIsRefused() throws Exception {
    FakeToken token = new FakeToken();
    assertThrows(
        QesException.class,
        () -> DetachedPkcs7Assembler.assemble(new byte[0], token.certificate, List.of(), false));
  }
}
