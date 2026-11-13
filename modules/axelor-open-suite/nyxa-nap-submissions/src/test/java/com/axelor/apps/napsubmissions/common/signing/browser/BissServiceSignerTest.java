package com.axelor.apps.napsubmissions.common.signing.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BISS изисква заявката да е подписана от сертификата на УСЛУГАТА. Проверява се, че при ЛИПСВАЩА
 * конфигурация пътят отказва ясно, и че двата източника на хранилището — файл (локална инсталация)
 * и base64 в {@code NYXA_BISS_KEYSTORE_BASE64} (Azure) — зареждат и подписват.
 *
 * <p>Настройките се четат през {@code AppSettings}, което в unit тест не е вдигнато — затова
 * четенето е презаписано тук. Секретите идват през шева {@code NapSecretProvider}, не от реалната
 * среда.
 */
class BissServiceSignerTest {

  private static final String CA_DN = "CN=Test BISS CA, O=Nyxa test";
  private static final String LEAF_DN = "CN=biss.test.nyxa.invalid, O=Nyxa test";

  @TempDir Path tempDir;

  @BeforeAll
  static void registerProvider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  /** Подписващ с дадени настройки и секрети. */
  private static BissServiceSigner signer(
      Map<String, String> settings, Map<String, String> secrets) {
    return new BissServiceSigner(reference -> Optional.ofNullable(secrets.get(reference))) {
      @Override
      protected String setting(String key) {
        return settings.get(key);
      }
    };
  }

  /** Подписващ без никаква конфигурация. */
  private BissServiceSigner unconfigured() {
    return signer(Map.of(), Map.of());
  }

  /** Конфигуриран път, но липсва паролата в средата. */
  private BissServiceSigner withoutPassword(String keystorePath) {
    return signer(Map.of(BissServiceSigner.KEYSTORE_PATH_KEY, keystorePath), Map.of());
  }

  /** PKCS#12 като в Azure: един ключов запис с непредвидим псевдоним, лист + издател. */
  private static final class Issued {
    final byte[] pkcs12;
    final X509Certificate leaf;

    Issued(byte[] pkcs12, X509Certificate leaf) {
      this.pkcs12 = pkcs12;
      this.leaf = leaf;
    }
  }

  private static Issued pkcs12(String password) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair ca = generator.generateKeyPair();
    KeyPair leafKeys = generator.generateKeyPair();
    X509Certificate caCert = TestCertificates.selfSigned(ca, CA_DN);
    X509Certificate leaf = TestCertificates.issuedBy(leafKeys.getPublic(), ca, CA_DN, LEAF_DN);

    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry(
        UUID.randomUUID().toString(),
        leafKeys.getPrivate(),
        password.toCharArray(),
        new Certificate[] {leaf, caCert});
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    store.store(out, password.toCharArray());
    return new Issued(out.toByteArray(), leaf);
  }

  private static Map<String, String> base64Secrets(Issued issued, String password) {
    Map<String, String> secrets = new HashMap<>();
    secrets.put(
        BissServiceSigner.KEYSTORE_BASE64_SECRET,
        Base64.getEncoder().encodeToString(issued.pkcs12));
    secrets.put(BissServiceSigner.KEYSTORE_PASSWORD_SECRET, password);
    return secrets;
  }

  @Test
  void missingKeystoreConfigurationIsRefused() {
    BissServiceSigner signer = unconfigured();
    assertFalse(signer.isConfigured());
    QesException e = assertThrows(QesException.class, signer::serviceCertificateBase64);
    assertEquals(QesException.Category.CONFIG, e.getCategory());
  }

  @Test
  void missingKeystoreConfigurationRefusesSigningToo() {
    assertThrows(QesException.class, () -> unconfigured().signContents(new byte[] {1, 2, 3}));
  }

  @Test
  void missingKeystoreFileIsRefused() {
    BissServiceSigner signer = withoutPassword("C:/no/such/service-keystore.p12");
    QesException e = assertThrows(QesException.class, signer::serviceCertificateBase64);
    assertEquals(QesException.Category.CONFIG, e.getCategory());
  }

  @Test
  void base64SourceAloneCountsAsConfigured() throws Exception {
    Issued issued = pkcs12("pw-azure");
    assertTrue(signer(Map.of(), base64Secrets(issued, "pw-azure")).isConfigured());
  }

  @Test
  void base64SourceLoadsAndSigns() throws Exception {
    Issued issued = pkcs12("pw-azure");
    BissServiceSigner signer = signer(Map.of(), base64Secrets(issued, "pw-azure"));

    assertEquals(
        Base64.getEncoder().encodeToString(issued.leaf.getEncoded()),
        signer.serviceCertificateBase64());

    byte[] content = "<declaration/>".getBytes();
    byte[] signed = Base64.getDecoder().decode(signer.signContents(content));
    assertTrue(verifiesLikeBiss(content, signed, issued.leaf));
  }

  /**
   * Проверката на BISS 3.34: хешира съдържанието и проверява подписа ВЪРХУ ХЕША. Подпис върху
   * самото съдържание не минава — точно грешката „Подписът на заявката е невалиден“.
   */
  private static boolean verifiesLikeBiss(byte[] content, byte[] signed, X509Certificate cert)
      throws Exception {
    Signature verify = Signature.getInstance("SHA256withRSA");
    verify.initVerify(cert);
    verify.update(MessageDigest.getInstance("SHA-256").digest(content));
    return verify.verify(signed);
  }

  @Test
  void signatureIsOverTheHashNotTheRawContent() throws Exception {
    Issued issued = pkcs12("pw-azure");
    BissServiceSigner signer = signer(Map.of(), base64Secrets(issued, "pw-azure"));
    byte[] content = "EMPL2021.TXT bytes".getBytes();
    byte[] signed = Base64.getDecoder().decode(signer.signContents(content));

    Signature overRaw = Signature.getInstance("SHA256withRSA");
    overRaw.initVerify(issued.leaf);
    overRaw.update(content);
    assertFalse(overRaw.verify(signed), "BISS refuses a signature over the raw content");
    assertTrue(verifiesLikeBiss(content, signed, issued.leaf));
  }

  @Test
  void keystorePathWinsOverBase64() throws Exception {
    Issued fromFile = pkcs12("pw-file");
    Path file = tempDir.resolve("service-biss.p12");
    Files.write(file, fromFile.pkcs12);
    Issued fromEnv = pkcs12("pw-azure");

    Map<String, String> secrets = base64Secrets(fromEnv, "pw-azure");
    secrets.put("LOCAL_BISS_PASSWORD", "pw-file");
    Map<String, String> settings =
        Map.of(
            BissServiceSigner.KEYSTORE_PATH_KEY,
            file.toString(),
            BissServiceSigner.KEYSTORE_PASSWORD_REF_KEY,
            "LOCAL_BISS_PASSWORD");

    assertEquals(
        Base64.getEncoder().encodeToString(fromFile.leaf.getEncoded()),
        signer(settings, secrets).serviceCertificateBase64());
  }

  @Test
  void invalidBase64IsRefusedClearly() {
    Map<String, String> secrets =
        Map.of(
            BissServiceSigner.KEYSTORE_BASE64_SECRET, "not*base64!",
            BissServiceSigner.KEYSTORE_PASSWORD_SECRET, "pw");
    QesException e =
        assertThrows(
            QesException.class, () -> signer(Map.of(), secrets).serviceCertificateBase64());
    assertEquals(QesException.Category.CONFIG, e.getCategory());
    assertTrue(e.getMessage().contains("NYXA_BISS_KEYSTORE_BASE64"), e.getMessage());
    assertTrue(e.getMessage().contains("base64"), e.getMessage());
  }

  @Test
  void wrongBase64PasswordGivesCannotOpen() throws Exception {
    Issued issued = pkcs12("pw-azure");
    QesException e =
        assertThrows(
            QesException.class,
            () -> signer(Map.of(), base64Secrets(issued, "wrong")).serviceCertificateBase64());
    assertTrue(e.getMessage().contains("cannot be opened"), e.getMessage());
  }
}
