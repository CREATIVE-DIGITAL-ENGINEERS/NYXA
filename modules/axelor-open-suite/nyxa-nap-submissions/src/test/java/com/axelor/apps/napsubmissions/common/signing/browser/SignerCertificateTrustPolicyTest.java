package com.axelor.apps.napsubmissions.common.signing.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Границата на доверие за подпис, който идва от машината на потребителя.
 *
 * <p>Най-важното твърдение тук: ТЕСТОВИЯТ ПОДПИСВАЩ НЕ МИНАВА. Той прави самоподписан сертификат, а
 * тази политика отказва самоподписани винаги — така подпис от {@code src/test} не може да тръгне по
 * браузърния път нито в ПРОДУКЦИЯ, нито в ТЕСТ среда.
 */
class SignerCertificateTrustPolicyTest {

  /** Политика без конфигурирани котви — чете се от настройките, затова ги изключваме явно. */
  private SignerCertificateTrustPolicy withoutAnchors() {
    return new SignerCertificateTrustPolicy() {
      @Override
      protected Set<java.security.cert.TrustAnchor> loadAnchors() {
        return Collections.emptySet();
      }
    };
  }

  private X509Certificate testSignerCertificate() throws Exception {
    byte[] der = new TestOnlyQesSigner("Тест Подписващ").certificateDer();
    return (X509Certificate)
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(der));
  }

  @Test
  void selfSignedCertificateIsRefusedInTestEnvironment() {
    QesException e =
        assertThrows(
            QesException.class,
            () -> withoutAnchors().requireTrusted(testSignerCertificate(), List.of(), false));
    assertEquals(QesException.Category.INVALID_REQUEST, e.getCategory());
  }

  @Test
  void selfSignedCertificateIsRefusedInProduction() {
    assertThrows(
        QesException.class,
        () -> withoutAnchors().requireTrusted(testSignerCertificate(), List.of(), true));
  }

  @Test
  void missingCertificateIsRefused() {
    assertThrows(QesException.class, () -> withoutAnchors().requireTrusted(null, List.of(), false));
  }
}
