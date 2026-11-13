package com.axelor.apps.napsubmissions.common.signing.browser;

import com.axelor.app.AppSettings;
import com.axelor.apps.napsubmissions.common.config.NapSecretProvider;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * ПОДПИСВА ЗАЯВКАТА КЪМ BISS С КЛЮЧА НА НАШАТА УСЛУГА — съвсем различно нещо от КЕП на потребителя.
 *
 * <p>BISS (спец. v3.44, т. 4.4.1) изисква при всяко {@code /sign} освен данните да се подадат и:
 *
 * <ul>
 *   <li>{@code signedContents} — base64 на подпис върху съдържанието, направен с частния ключ на
 *       сертификата на УСЛУГАТА: {@code signedContents = encrypt(sha256(content))} с {@code
 *       SHA256withRSA};
 *   <li>{@code signedContentsCert} — base64 на сертификата на услугата. Той ТРЯБВА да е от доверена
 *       сертификационна верига на машината на клиента (за Windows — Trusted Root / Intermediate
 *       Certification Authorities). <b>Самоподписани сертификати не се приемат</b> — спецификацията
 *       го забранява изрично. Препоръчан е OV/DV SSL сертификат (например на B-Trust, чиито вериги
 *       се инсталират с BISS) или домейн сертификатът на самата услуга.
 * </ul>
 *
 * <p>Това е механизъм за ДОКАЗВАНЕ НА ПРОИЗХОДА на заявката, а не подпис на декларацията. Затова
 * ключът е сървърен и работи САМО тук: подписването става в Java, частният ключ никога не стига до
 * браузъра, а паролите идват от {@link NapSecretProvider} (системно свойство или променлива на
 * средата) — в Git влиза само ИМЕТО на променливата, не стойността ѝ.
 *
 * <p><b>Fail-closed.</b> Без конфигуриран keystore — ясен отказ, без подаване. Със самоподписан
 * сертификат — ясен отказ, защото BISS така или иначе ще откаже.
 */
@Singleton
public class BissServiceSigner {

  /** Път до PKCS#12/JKS хранилище със сертификата на услугата и неговия частен ключ. */
  public static final String KEYSTORE_PATH_KEY = "nyxa.nap.biss.keystore-path";

  /** Тип на хранилището — {@code PKCS12} (по подразбиране) или {@code JKS}. */
  public static final String KEYSTORE_TYPE_KEY = "nyxa.nap.biss.keystore-type";

  /** Псевдоним на записа в хранилището; празно → първият ключов запис. */
  public static final String KEY_ALIAS_KEY = "nyxa.nap.biss.key-alias";

  /** ИМЕ на променливата на средата/системното свойство с паролата на хранилището. */
  public static final String KEYSTORE_PASSWORD_REF_KEY = "nyxa.nap.biss.keystore-password-ref";

  /** ИМЕ на променливата с паролата на ключа; празно → паролата на хранилището. */
  public static final String KEY_PASSWORD_REF_KEY = "nyxa.nap.biss.key-password-ref";

  /**
   * Секрет (име на променлива на средата/системно свойство) с PKCS#12 хранилището като base64 на
   * един ред. Задава се от инсталацията (напр. от Key Vault на средата); ползва се, когато няма
   * {@link #KEYSTORE_PATH_KEY}.
   */
  public static final String KEYSTORE_BASE64_SECRET = "NYXA_BISS_KEYSTORE_BASE64";

  /**
   * Секрет с паролата на хранилището от {@link #KEYSTORE_BASE64_SECRET}, когато {@link
   * #KEYSTORE_PASSWORD_REF_KEY} не е зададен или не дава стойност.
   */
  public static final String KEYSTORE_PASSWORD_SECRET = "NYXA_BISS_KEYSTORE_PASSWORD";

  protected final NapSecretProvider secretProvider;

  @Inject
  public BissServiceSigner(NapSecretProvider secretProvider) {
    this.secretProvider = secretProvider;
  }

  /** Конфигуриран ли е изобщо механизмът (без да се чете ключ): файл или base64 от средата. */
  public boolean isConfigured() {
    String path = setting(KEYSTORE_PATH_KEY);
    return (path != null && !path.isBlank()) || keystoreBase64().isPresent();
  }

  /**
   * Base64 на сертификата на услугата — стойността на {@code signedContentsCert}. Отказва при
   * липсваща конфигурация или самоподписан сертификат.
   */
  public String serviceCertificateBase64() {
    return Base64.getEncoder().encodeToString(encoded(loadMaterial().certificate));
  }

  /**
   * Base64 на {@code SHA256withRSA} подписа върху SHA-256 ХЕША на точните байтове, които ще бъдат
   * подадени на BISS — стойността на {@code signedContents} за този елемент от {@code contents}.
   *
   * <p><b>⚠️ ПОДПИСВА СЕ ХЕШЪТ, НЕ СЪДЪРЖАНИЕТО.</b> Примерът в спецификацията ({@code
   * encrypt(sha256("TestData"))}) може да се прочете и като {@code SHA256withRSA("TestData")}, но
   * BISS 3.34 проверява иначе: хешира съдържанието и проверява подписа ВЪРХУ ХЕША, т.е. {@code
   * SHA256withRSA(sha256(content))}. Подпис върху самото съдържание връща 500 „Подписът на заявката
   * е невалиден“ (проверено с BISS 3.34). Хешът е SHA-256, защото страницата подава {@code
   * hashAlgorithm: "SHA256"}, когато BISS го поддържа — BISS ползва същия алгоритъм и за хеша, и за
   * проверката.
   */
  public String signContents(byte[] content) {
    if (content == null) {
      throw refuse(I18n.get("There is no data to sign for the request to BISS."));
    }
    Material material = loadMaterial();
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(material.privateKey);
      signature.update(MessageDigest.getInstance("SHA-256").digest(content));
      return Base64.getEncoder().encodeToString(signature.sign());
    } catch (Exception e) {
      throw refuse(
          I18n.get(
              "The request to the local signing component cannot be signed with the service key;"
                  + " the submission was refused."));
    }
  }

  /**
   * Сертификатът и ключът на услугата. Чете се при всяко ползване — нищо не се кешира трайно,
   * затова подновен сертификат влиза в сила при рестарт или нова версия на средата.
   *
   * <p>Два източника: файл ({@code nyxa.nap.biss.keystore-path} — локална инсталация) или base64 в
   * {@value #KEYSTORE_BASE64_SECRET} (Azure: Key Vault секретът е текст и не може да е двоичен
   * .p12). Пътят печели, ако са зададени и двата.
   */
  protected Material loadMaterial() {
    String path = setting(KEYSTORE_PATH_KEY);
    if (path == null || path.isBlank()) {
      Optional<String> encoded = keystoreBase64();
      if (encoded.isPresent()) {
        return loadFromBase64(encoded.get());
      }
      throw refuse(
          I18n.get(
              "Signing with a USB QES is not configured: the service certificate for the requests"
                  + " to the local component is missing (setting nyxa.nap.biss.keystore-path or"
                  + " variable NYXA_BISS_KEYSTORE_BASE64). BISS requires the request to be signed"
                  + " by a trusted service certificate."));
    }
    return loadFromFile(path);
  }

  /** Хранилището от {@value #KEYSTORE_BASE64_SECRET}: винаги PKCS#12. */
  protected Material loadFromBase64(String encoded) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
    } catch (IllegalArgumentException e) {
      // Нито стойността, нито дължината ѝ отиват в съобщението или в лога.
      throw refuse(
          I18n.get(
              "The keystore in NYXA_BISS_KEYSTORE_BASE64 is not valid base64; the submission was"
                  + " refused."));
    }
    try {
      char[] storePassword =
          password(
              KEYSTORE_PASSWORD_REF_KEY,
              secretProvider
                  .resolveSecret(KEYSTORE_PASSWORD_SECRET)
                  .map(String::toCharArray)
                  .orElse(null));
      if (storePassword == null) {
        throw refuse(
            I18n.get(
                "The password for the keystore with the service certificate is missing (variable"
                    + " NYXA_BISS_KEYSTORE_PASSWORD); the submission was refused."));
      }
      return open(new ByteArrayInputStream(bytes), "PKCS12", storePassword);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  protected Material loadFromFile(String path) {
    Path file = Paths.get(path.trim());
    if (!Files.isRegularFile(file)) {
      throw refuse(
          I18n.get(
              "The keystore with the service certificate was not found; the submission was"
                  + " refused."));
    }
    char[] storePassword = password(KEYSTORE_PASSWORD_REF_KEY, null);
    if (storePassword == null) {
      throw refuse(
          I18n.get(
              "The password for the keystore with the service certificate is missing. It is"
                  + " supplied through the environment (nyxa.nap.biss.keystore-password-ref points"
                  + " to the NAME of the variable), never through a configuration file in Git."));
    }
    String type = setting(KEYSTORE_TYPE_KEY);
    try (InputStream in = Files.newInputStream(file)) {
      return open(in, type == null || type.isBlank() ? "PKCS12" : type.trim(), storePassword);
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      throw cannotOpen();
    }
  }

  /** Общото за двата източника: зарежда, избира ключа, проверява сертификата. */
  protected Material open(InputStream in, String type, char[] storePassword) {
    char[] keyPassword = password(KEY_PASSWORD_REF_KEY, storePassword);
    try {
      KeyStore store = KeyStore.getInstance(type);
      store.load(in, storePassword);

      String alias = setting(KEY_ALIAS_KEY);
      if (alias == null || alias.isBlank()) {
        alias = firstKeyAlias(store);
      }
      if (alias == null) {
        throw refuse(
            I18n.get(
                "The keystore has no key for the service certificate; the submission was"
                    + " refused."));
      }
      PrivateKey privateKey = (PrivateKey) store.getKey(alias, keyPassword);
      Certificate certificate = store.getCertificate(alias);
      if (privateKey == null || !(certificate instanceof X509Certificate)) {
        throw refuse(
            I18n.get("The service certificate cannot be read; the submission was refused."));
      }
      X509Certificate x509 = (X509Certificate) certificate;
      requireNotSelfSigned(x509);
      return new Material(privateKey, x509);
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      throw cannotOpen();
    }
  }

  protected QesException cannotOpen() {
    return refuse(
        I18n.get(
            "The keystore with the service certificate cannot be opened (wrong password, type or"
                + " corrupted file); the submission was refused."));
  }

  /** Стойността на {@value #KEYSTORE_BASE64_SECRET}, ако е зададена. Никога не се логва. */
  protected Optional<String> keystoreBase64() {
    return secretProvider.resolveSecret(KEYSTORE_BASE64_SECRET).filter(v -> !v.isBlank());
  }

  /** BISS отказва самоподписани сертификати за този механизъм — отказваме по-рано и по-ясно. */
  protected void requireNotSelfSigned(X509Certificate certificate) {
    if (certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
      throw refuse(
          I18n.get(
              "The service certificate is self-signed. BISS does not accept self-signed"
                  + " certificates for signing the request — a certificate from a trusted issuer is"
                  + " needed (for example OV/DV SSL or the service's domain certificate)."));
    }
    try {
      certificate.checkValidity();
    } catch (Exception expired) {
      throw refuse(
          I18n.get(
              "The service certificate is outside its validity period; the submission was"
                  + " refused."));
    }
  }

  protected String firstKeyAlias(KeyStore store) throws Exception {
    for (String alias : java.util.Collections.list(store.aliases())) {
      if (store.isKeyEntry(alias)) {
        return alias;
      }
    }
    return null;
  }

  /** Паролата идва от средата по ИМЕ на променлива — стойността ѝ никога не е в конфигурацията. */
  protected char[] password(String referenceKey, char[] fallback) {
    String reference = setting(referenceKey);
    if (reference == null || reference.isBlank()) {
      return fallback;
    }
    Optional<String> secret = secretProvider.resolveSecret(reference.trim());
    return secret.map(String::toCharArray).orElse(fallback);
  }

  protected String setting(String key) {
    return AppSettings.get().get(key, null);
  }

  protected byte[] encoded(X509Certificate certificate) {
    try {
      return certificate.getEncoded();
    } catch (Exception e) {
      throw refuse(I18n.get("The service certificate cannot be encoded."));
    }
  }

  protected QesException refuse(String message) {
    return new QesException(QesException.Category.CONFIG, message);
  }

  /** Преходна двойка ключ/сертификат — не се кешира и не се записва. */
  protected static final class Material {
    final PrivateKey privateKey;
    final X509Certificate certificate;

    Material(PrivateKey privateKey, X509Certificate certificate) {
      this.privateKey = privateKey;
      this.certificate = certificate;
    }
  }
}
