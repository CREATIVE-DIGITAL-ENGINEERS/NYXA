package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.axelor.apps.napsubmissions.common.config.NapSecretProvider;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.Optional;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

/**
 * НАШИЯТ ЧАСТЕН RSA КЛЮЧ ЗА ОБЛАЧНИЯ ПОТОК — зарежда се от конфигуриран източник ИЗВЪН Git.
 *
 * <p>Evrotrust шифрова резултата на ONLINE hash потока със симетричен ключ, който сам шифрова с
 * {@code publicKey}-а, подаден при стартирането (документирано: RSA-2048, PKCS#1 v1.5). Другата
 * половина на същата двойка стои тук и е ЕДИНСТВЕНОТО, с което свалените данни могат да се
 * прочетат.
 *
 * <p><b>Двойката се прави по документацията:</b>
 *
 * <pre>
 *   openssl genrsa -out private.pem 2048
 *   openssl rsa -in private.pem -pubout -out public.pem
 *   base64 -w 0 public.pem      # → nyxa.qes.evrotrust.publicKey
 * </pre>
 *
 * <p>⚠️ Публичният ключ се подава base64-кодиран ЦЯЛ, заедно с реда {@code -----BEGIN PUBLIC
 * KEY-----} и преходите на нов ред — това е документираният формат на полето {@code publicKey}.
 *
 * <p><b>Какво НЕ влиза в Git.</b> Частният ключ не се вгражда в репото при никакви обстоятелства.
 * Четат се само две неща, и двете сочат навън:
 *
 * <ul>
 *   <li>{@code nyxa.qes.evrotrust.privateKeyPath} — път до PEM файл (четим само от процеса на
 *       Nyxa);
 *   <li>{@code nyxa.qes.evrotrust.privateKeySecretRef} — ИМЕТО на променлива на средата/системно
 *       свойство, чиято СТОЙНОСТ е самият PEM (за key vault / контейнерна среда).
 * </ul>
 *
 * <p>Нищо не се кешира: ключът се чете при всяко ползване и живее само докато трае разшифроването.
 * Никое съобщение за грешка не съдържа ключ, част от ключ или път с тайна в него.
 */
@Singleton
public class EvrotrustPrivateKeyProvider {

  protected final EvrotrustConfig config;
  protected final NapSecretProvider secretProvider;

  @Inject
  public EvrotrustPrivateKeyProvider(EvrotrustConfig config, NapSecretProvider secretProvider) {
    this.config = config;
    this.secretProvider = secretProvider;
  }

  /**
   * Частният ключ, прочетен сега. Отказва ясно при липсваща конфигурация, нечетим файл, нечетим или
   * шифрован PEM, и при ключ, който не е RSA.
   */
  public PrivateKey requirePrivateKey() {
    String pem = readPem();
    try (PEMParser parser = new PEMParser(new StringReader(pem))) {
      Object parsed = parser.readObject();
      if (parsed == null) {
        throw refuse(
            I18n.get(
                "The configured Evrotrust private key is not recognized as PEM; decryption was"
                    + " refused."));
      }
      if (parsed instanceof PEMEncryptedKeyPair || parsed instanceof PKCS8EncryptedPrivateKeyInfo) {
        throw refuse(
            I18n.get(
                "The configured Evrotrust private key is password-protected. Such a key is not"
                    + " supported: supply a PEM without a password at a path readable only by the"
                    + " Nyxa process (or through a key vault), and protect the file with file"
                    + " system permissions."));
      }
      JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
      PrivateKey key;
      if (parsed instanceof PEMKeyPair) {
        // PKCS#1: -----BEGIN RSA PRIVATE KEY----- (openssl genrsa, по-стари версии)
        key = converter.getKeyPair((PEMKeyPair) parsed).getPrivate();
      } else if (parsed instanceof PrivateKeyInfo) {
        // PKCS#8: -----BEGIN PRIVATE KEY----- (openssl 3.x)
        key = converter.getPrivateKey((PrivateKeyInfo) parsed);
      } else {
        throw refuse(
            I18n.get(
                "The configured Evrotrust private key is not a private key; decryption was"
                    + " refused."));
      }
      if (!"RSA".equalsIgnoreCase(key.getAlgorithm())) {
        throw refuse(
            I18n.get(
                "The configured Evrotrust private key is not RSA (RSA-2048 is documented);"
                    + " decryption was refused."));
      }
      return key;
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      // ⚠️ Нарочно без e.getMessage(): съобщението на библиотеката може да носи части от ключа.
      throw refuse(
          I18n.get("The configured Evrotrust private key cannot be read; decryption was refused."));
    }
  }

  /** PEM-ът от файл или от стойността на посочена променлива — в този ред. */
  protected String readPem() {
    String path = config.getPrivateKeyPath();
    if (path != null) {
      Path file = Paths.get(path);
      if (!Files.isRegularFile(file)) {
        throw refuse(
            I18n.get(
                "The Evrotrust private key file was not found"
                    + " (nyxa.qes.evrotrust.privateKeyPath); decryption was refused."));
      }
      try {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      } catch (Exception e) {
        throw refuse(
            I18n.get("The Evrotrust private key file cannot be read; decryption was refused."));
      }
    }
    String reference = config.getPrivateKeySecretRef();
    if (reference != null) {
      Optional<String> secret = secretProvider.resolveSecret(reference);
      return secret
          .filter(value -> !value.isBlank())
          .orElseThrow(
              () ->
                  refuse(
                      I18n.get(
                          "The Evrotrust private key cannot be found by the configured reference;"
                              + " decryption was refused.")));
    }
    throw refuse(
        I18n.get(
            "No Evrotrust private key is configured. The result of cloud signing is returned"
                + " encrypted with our publicKey and cannot be read without the matching private"
                + " key. Set nyxa.qes.evrotrust.privateKeyPath or"
                + " nyxa.qes.evrotrust.privateKeySecretRef; the key is NOT stored in Git."));
  }

  protected QesException refuse(String message) {
    return new QesException(QesException.Category.CONFIG, message);
  }
}
