package com.axelor.apps.napsubmissions.common.signing.browser;

import com.axelor.app.AppSettings;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.i18n.I18n;
import com.google.inject.Singleton;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * КОЙ СЕРТИФИКАТ СЕ ПРИЕМА ОТ БРАУЗЪРА.
 *
 * <p>Подписът по браузърния път идва от МАШИНАТА НА ПОТРЕБИТЕЛЯ, не от сървърен адаптер. Затова
 * криптографската проверка на PKCS#7 (виж {@code DetachedPkcs7Verifier}) не е достатъчна сама по
 * себе си: тя доказва само, че подписът отговаря на ПРИЛОЖЕНИЯ сертификат — а приложеният
 * сертификат може да е произволен самоподписан. Тази политика решава ДАЛИ на този сертификат изобщо
 * да се вярва.
 *
 * <p>Правила, в този ред:
 *
 * <ol>
 *   <li><b>Самоподписан → ВИНАГИ отказ.</b> Това затваря тестовия подписващ ({@code
 *       TestOnlyQesSigner}, issuer == subject) за този път, включително в TEST среда.
 *   <li><b>Извън срок на валидност → отказ.</b>
 *   <li><b>Конфигурирани доверени котви</b> ({@code nyxa.nap.qes.trust-anchor-dir}, директория с
 *       PEM/DER сертификати на българските доставчици на КУУ) → задължителна PKIX проверка на
 *       веригата, подадена от браузъра.
 *   <li><b>Без конфигурирани котви:</b> в <b>PROD</b> — ОТКАЗ (fail-closed, не се подава към реална
 *       НАП с непроверена верига); в <b>TEST</b> — допуска се, за да може средата да се тества
 *       преди доставчиците да са заредени.
 * </ol>
 *
 * <p>Нищо тук не се записва: сертификатите пристигат като преходни байтове и се изхвърлят.
 */
@Singleton
public class SignerCertificateTrustPolicy {

  /** Директория с доверени коренови/междинни сертификати на доставчици на КУУ (PEM или DER). */
  public static final String TRUST_ANCHOR_DIR_KEY = "nyxa.nap.qes.trust-anchor-dir";

  /**
   * Отказва ({@link QesException}) когато сертификатът не бива да се приема. Връща нормално, когато
   * подписът може да продължи към проверката на PKCS#7 и към НАП.
   *
   * @param leaf сертификатът на подписващия (от локалния компонент)
   * @param chain междинните сертификати, както ги е върнал компонентът (може да е празно)
   * @param productionEnvironment true когато подаването отива към реалната среда на НАП
   */
  public void requireTrusted(
      X509Certificate leaf, List<X509Certificate> chain, boolean productionEnvironment) {
    if (leaf == null) {
      throw refuse(I18n.get("The signer's certificate is missing."));
    }
    try {
      leaf.checkValidity(new Date());
    } catch (Exception expired) {
      throw refuse(
          I18n.get(
              "The signer's certificate is outside its validity period; the submission was"
                  + " refused."));
    }
    if (leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) {
      throw refuse(
          I18n.get(
              "A self-signed certificate is not accepted for submitting to NAP. A QES issued by a"
                  + " qualified trust service provider is required."));
    }

    Set<TrustAnchor> anchors = loadAnchors();
    if (anchors.isEmpty()) {
      if (productionEnvironment) {
        throw refuse(
            I18n.get(
                "No trusted certificates of qualified trust service providers are loaded (setting"
                    + " nyxa.nap.qes.trust-anchor-dir). In PRODUCTION the submission is refused"
                    + " while the certificate chain cannot be verified."));
      }
      // TEST: допуска се нарочно, за да може средата да се тества преди котвите да са заредени.
      return;
    }
    validatePath(leaf, chain, anchors);
  }

  /** PKIX проверка на веригата (без CRL/OCSP — мрежова проверка не се прави тук). */
  protected void validatePath(
      X509Certificate leaf, List<X509Certificate> chain, Set<TrustAnchor> anchors) {
    try {
      List<X509Certificate> path = new ArrayList<>();
      path.add(leaf);
      if (chain != null) {
        for (X509Certificate intermediate : chain) {
          if (intermediate != null
              && !intermediate.equals(leaf)
              && !isAnchor(intermediate, anchors)) {
            path.add(intermediate);
          }
        }
      }
      CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
      PKIXParameters params = new PKIXParameters(anchors);
      params.setRevocationEnabled(false);
      CertPathValidator.getInstance("PKIX").validate(certPath, params);
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      throw refuse(
          I18n.get(
              "The signer's certificate does not chain to a trusted qualified trust service"
                  + " provider; the submission was refused."));
    }
  }

  protected boolean isAnchor(X509Certificate certificate, Set<TrustAnchor> anchors) {
    for (TrustAnchor anchor : anchors) {
      if (certificate.equals(anchor.getTrustedCert())) {
        return true;
      }
    }
    return false;
  }

  /** Чете доверените котви от конфигурираната директория. Липсва ли — празно множество. */
  protected Set<TrustAnchor> loadAnchors() {
    String dir = AppSettings.get().get(TRUST_ANCHOR_DIR_KEY, null);
    Set<TrustAnchor> anchors = new LinkedHashSet<>();
    if (dir == null || dir.isBlank()) {
      return anchors;
    }
    Path root = Paths.get(dir.trim());
    if (!Files.isDirectory(root)) {
      return anchors;
    }
    CertificateFactory factory;
    try {
      factory = CertificateFactory.getInstance("X.509");
    } catch (Exception e) {
      return anchors;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
      for (Path file : files) {
        if (!Files.isRegularFile(file)) {
          continue;
        }
        try (InputStream in = Files.newInputStream(file)) {
          for (java.security.cert.Certificate certificate : factory.generateCertificates(in)) {
            if (certificate instanceof X509Certificate) {
              anchors.add(new TrustAnchor((X509Certificate) certificate, null));
            }
          }
        } catch (Exception skip) {
          // Файл, който не е сертификат, не блокира останалите котви.
        }
      }
    } catch (Exception e) {
      return anchors;
    }
    return anchors;
  }

  protected QesException refuse(String message) {
    return new QesException(QesException.Category.INVALID_REQUEST, message);
  }
}
