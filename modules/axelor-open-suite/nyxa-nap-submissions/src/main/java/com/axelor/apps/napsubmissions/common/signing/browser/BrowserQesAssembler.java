package com.axelor.apps.napsubmissions.common.signing.browser;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ПРЕВРЪЩА ПОДПИСИТЕ ОТ БРАУЗЪРА В ПРЕХОДНИ ПОДПИСИ — и отказва всичко съмнително преди мрежата.
 *
 * <p>Това е границата на доверие: отвъд нея материалът идва от машината на потребителя. Тук се
 * проверява СТРУКТУРАТА и ДОВЕРИЕТО (всеки файл подписан точно веднъж, сертификатът се разчита и е
 * от доверен доставчик — {@link SignerCertificateTrustPolicy}). Самата КРИПТОГРАФСКА проверка, че
 * PKCS#7 покрива точните заключени байтове, остава в {@code DeclarationsEvidenceService#
 * verifyTransient} — единственото място, което я прави, и то непосредствено преди изпращането.
 *
 * <p>⚠️ Нищо не се записва трайно: резултатът е {@link TransientQesSignature} само в паметта.
 */
@Singleton
public class BrowserQesAssembler {

  protected final SignerCertificateTrustPolicy trustPolicy;

  @Inject
  public BrowserQesAssembler(SignerCertificateTrustPolicy trustPolicy) {
    this.trustPolicy = trustPolicy;
  }

  /** Същата политика на доверие и за подписите, качени от портала на доставчика. */
  public SignerCertificateTrustPolicy trustPolicy() {
    return trustPolicy;
  }

  /**
   * Подрежда подписите по файл. Отказва при липсващ, излишен или дублиран подпис, при нечетим
   * base64/сертификат и при недоверен сертификат.
   *
   * @param files файловете на подаването (точно те трябва да са подписани)
   * @param signed подписите, както браузърът ги е върнал
   * @param productionEnvironment true когато подаването отива към реалната среда на НАП
   */
  public Map<NapSubmissionFile, TransientQesSignature> assemble(
      List<NapSubmissionFile> files,
      List<BrowserSignedFile> signed,
      boolean productionEnvironment) {
    if (files == null || files.isEmpty()) {
      throw refuse(I18n.get("The submission has no files."));
    }
    if (signed == null || signed.isEmpty()) {
      throw refuse(
          I18n.get("No signatures were received from the local component; nothing was sent."));
    }

    Map<Long, BrowserSignedFile> byId = new HashMap<>();
    for (BrowserSignedFile entry : signed) {
      if (entry == null || entry.fileId == null) {
        throw refuse(
            I18n.get("A signature was received without a file; the submission was refused."));
      }
      if (byId.put(entry.fileId, entry) != null) {
        throw refuse(
            I18n.get(
                "Two signatures were received for the same file; the submission was refused."));
      }
    }

    Set<Long> expected = new HashSet<>();
    Map<NapSubmissionFile, TransientQesSignature> result = new IdentityHashMap<>();
    for (NapSubmissionFile file : files) {
      expected.add(file.getId());
      BrowserSignedFile entry = byId.get(file.getId());
      if (entry == null) {
        throw refuse(
            I18n.get("A submission file was not signed by the local component; nothing was sent."));
      }
      byte[] rawSignature = decode(entry.signatureBase64, I18n.get("the signature"));
      byte[] certificateDer = decode(entry.certificateBase64, I18n.get("the signer's certificate"));
      X509Certificate signer = parseCertificate(certificateDer);
      List<X509Certificate> chain = parseChain(entry.chainBase64);
      trustPolicy.requireTrusted(signer, chain, productionEnvironment);
      // ⚠️ BISS връща СУРОВ подпис; детачният PKCS#7, който НАП иска, се сглобява тук и веднага
      // след това се проверява криптографски върху заключените байтове (verifyTransient).
      byte[] pkcs7 =
          DetachedPkcs7Assembler.assemble(
              rawSignature, signer, chain, isSha512(entry.hashAlgorithm));
      result.put(file, new TransientQesSignature(pkcs7, certificateDer, entry.payloadSha256Hex));
    }

    for (Long id : byId.keySet()) {
      if (!expected.contains(id)) {
        throw refuse(
            I18n.get(
                "A signature was received for a file outside this submission; the submission was"
                    + " refused."));
      }
    }
    return result;
  }

  /**
   * BISS обявява ТОЧНО два литерала (спец. v3.44, стр. 13): {@code SHA256} (по подразбиране) и
   * {@code SHA512}. ⚠️ Нищо друго не се тълкува — непознат или липсващ алгоритъм означава, че не
   * знаем върху какъв дайджест е подписът, а тогава сглобеният PKCS#7 би бил грешен. Отказва се.
   */
  protected boolean isSha512(String hashAlgorithm) {
    String value = hashAlgorithm == null ? "" : hashAlgorithm.trim().toUpperCase();
    if ("SHA512".equals(value)) {
      return true;
    }
    if ("SHA256".equals(value)) {
      return false;
    }
    throw refuse(
        I18n.get(
            "The local component did not specify a supported hash algorithm (SHA256 or SHA512 is"
                + " expected); the submission was refused."));
  }

  protected X509Certificate parseCertificate(byte[] der) {
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(der));
    } catch (Exception e) {
      throw refuse(I18n.get("The signer's certificate cannot be parsed."));
    }
  }

  protected List<X509Certificate> parseChain(List<String> chainBase64) {
    List<X509Certificate> chain = new ArrayList<>();
    if (chainBase64 == null) {
      return chain;
    }
    for (String encoded : chainBase64) {
      if (encoded == null || encoded.isBlank()) {
        continue;
      }
      chain.add(parseCertificate(decode(encoded, I18n.get("a certificate from the chain"))));
    }
    return chain;
  }

  protected byte[] decode(String base64, String what) {
    if (base64 == null || base64.isBlank()) {
      throw refuse(String.format(I18n.get("Missing %s; the submission was refused."), what));
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
      if (bytes.length == 0) {
        throw new IllegalArgumentException("empty");
      }
      return bytes;
    } catch (Exception e) {
      throw refuse(String.format(I18n.get("Cannot parse %s; the submission was refused."), what));
    }
  }

  protected QesException refuse(String message) {
    return new QesException(QesException.Category.INVALID_REQUEST, message);
  }
}
