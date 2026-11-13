package com.axelor.apps.napsubmissions.common.signing.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import java.nio.charset.StandardCharsets;
import java.security.cert.TrustAnchor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Какво приема и какво отказва границата към браузъра — структурно, без да се пипа мрежа или база.
 *
 * <p>Доверието към сертификата е тествано отделно ({@link SignerCertificateTrustPolicyTest}),
 * затова тук политиката е заменена с „пуска всичко", за да се види само подредбата на подписите.
 */
class BrowserQesAssemblerTest {

  /** Политика, която не отказва — така тестът изследва само структурните откази. */
  private BrowserQesAssembler assembler() {
    return new BrowserQesAssembler(
        new SignerCertificateTrustPolicy() {
          @Override
          public void requireTrusted(
              java.security.cert.X509Certificate leaf,
              List<java.security.cert.X509Certificate> chain,
              boolean productionEnvironment) {
            // тестът проверява подредбата, не доверието
          }

          @Override
          protected Set<TrustAnchor> loadAnchors() {
            return Collections.emptySet();
          }
        });
  }

  private NapSubmissionFile file(long id) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setId(id);
    return file;
  }

  private BrowserSignedFile signed(long fileId, byte[] payload) {
    TestOnlyQesSigner signer = new TestOnlyQesSigner("Тест");
    BrowserSignedFile entry = new BrowserSignedFile();
    entry.fileId = fileId;
    // Структурен тест: важи само че стойността е четим base64 — сглобяването и криптографската
    // проверка имат свои тестове.
    entry.signatureBase64 = Base64.getEncoder().encodeToString(signer.detachedPkcs7(payload));
    entry.certificateBase64 = Base64.getEncoder().encodeToString(signer.certificateDer());
    entry.chainBase64 = new ArrayList<>();
    entry.hashAlgorithm = "SHA256";
    entry.payloadSha256Hex = "deadbeef";
    return entry;
  }

  private static final byte[] PAYLOAD = "Д1 заключени байтове".getBytes(StandardCharsets.UTF_8);

  @Test
  void everyFileSignedExactlyOnceIsAccepted() {
    List<NapSubmissionFile> files = List.of(file(1L), file(2L));
    Map<NapSubmissionFile, TransientQesSignature> result =
        assembler().assemble(files, List.of(signed(1L, PAYLOAD), signed(2L, PAYLOAD)), false);
    assertEquals(2, result.size());
  }

  @Test
  void missingSignatureForOneFileIsRefused() {
    List<NapSubmissionFile> files = List.of(file(1L), file(2L));
    assertThrows(
        QesException.class, () -> assembler().assemble(files, List.of(signed(1L, PAYLOAD)), false));
  }

  @Test
  void signatureForAForeignFileIsRefused() {
    List<NapSubmissionFile> files = List.of(file(1L));
    assertThrows(
        QesException.class,
        () ->
            assembler().assemble(files, List.of(signed(1L, PAYLOAD), signed(9L, PAYLOAD)), false));
  }

  @Test
  void duplicateSignatureForTheSameFileIsRefused() {
    List<NapSubmissionFile> files = List.of(file(1L));
    assertThrows(
        QesException.class,
        () ->
            assembler().assemble(files, List.of(signed(1L, PAYLOAD), signed(1L, PAYLOAD)), false));
  }

  @Test
  void unreadableBase64IsRefused() {
    List<NapSubmissionFile> files = List.of(file(1L));
    BrowserSignedFile broken = signed(1L, PAYLOAD);
    broken.signatureBase64 = "не е base64 !!!";
    assertThrows(QesException.class, () -> assembler().assemble(files, List.of(broken), false));
  }

  @Test
  void noSignaturesAtAllIsRefused() {
    assertThrows(
        QesException.class, () -> assembler().assemble(List.of(file(1L)), List.of(), false));
  }

  @Test
  void unknownHashAlgorithmIsRefused() {
    List<NapSubmissionFile> files = List.of(file(1L));
    BrowserSignedFile entry = signed(1L, PAYLOAD);
    entry.hashAlgorithm = "SH256"; // старият, погрешен литерал — вече не се тълкува
    assertThrows(QesException.class, () -> assembler().assemble(files, List.of(entry), false));
  }

  @Test
  void missingHashAlgorithmIsRefused() {
    List<NapSubmissionFile> files = List.of(file(1L));
    BrowserSignedFile entry = signed(1L, PAYLOAD);
    entry.hashAlgorithm = null;
    assertThrows(QesException.class, () -> assembler().assemble(files, List.of(entry), false));
  }
}
