package com.axelor.apps.napsubmissions.common.signing.browser;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.i18n.I18n;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessable;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;

/**
 * ПОДПИСАН ФАЙЛ ОТ ПОРТАЛА НА EVROTRUST — превръща се в детачния PKCS#7, който НАП иска.
 *
 * <p>Потребителят сваля заключения файл, подписва го в портала на доставчика и качва резултата.
 * Порталът може да върне една от двете обичайни CAdES/PKCS#7 форми:
 *
 * <ul>
 *   <li><b>детачен</b> ({@code .p7s}) — само подписът; данните са заключеният файл у нас;
 *   <li><b>прикрепен</b> ({@code .p7m}) — подписът заедно с данните. Тогава данните вътре ТРЯБВА да
 *       са байт по байт заключеният файл (иначе е подписано нещо друго), а към НАП отива същият
 *       подпис без прикрепеното съдържание.
 * </ul>
 *
 * <p>Приема се DER, а също и base64 текст (със или без PEM заглавие), защото някои портали свалят
 * подписа като текст.
 *
 * <p>⚠️ Тук НЕ се проверява криптографски подписът и НЕ се решава доверието: проверката върху
 * точните заключени байтове е в {@code DeclarationsEvidenceService#verifyTransient}, а доверието —
 * в {@link SignerCertificateTrustPolicy}. Нищо не се записва трайно.
 */
public final class PortalSignedPkcs7 {

  /** Детачният PKCS#7 (DER), сертификатът на подписващия (DER) и останалите сертификати. */
  public static final class Parsed {
    public final byte[] detachedPkcs7;
    public final X509Certificate signer;
    public final List<X509Certificate> chain;

    Parsed(byte[] detachedPkcs7, X509Certificate signer, List<X509Certificate> chain) {
      this.detachedPkcs7 = detachedPkcs7;
      this.signer = signer;
      this.chain = chain;
    }
  }

  private PortalSignedPkcs7() {}

  /**
   * @param uploaded качените байтове (DER или base64/PEM текст)
   * @param lockedPayload точните заключени байтове на файла, който е трябвало да бъде подписан
   * @param fileName името на файла — само за съобщенията
   */
  public static Parsed parse(byte[] uploaded, byte[] lockedPayload, String fileName) {
    String name = fileName == null ? "" : fileName;
    if (uploaded == null || uploaded.length == 0) {
      throw refuse(
          String.format(I18n.get("No signed file was uploaded for %s; nothing was sent."), name));
    }
    CMSSignedData cms = readCms(uploaded, name);

    Collection<SignerInformation> signers = cms.getSignerInfos().getSigners();
    if (signers.size() != 1) {
      throw refuse(
          String.format(
              I18n.get(
                  "The signed file for %s must contain exactly one signature, but it contains %s;"
                      + " the submission was refused."),
              name,
              signers.size()));
    }
    SignerInformation signerInfo = signers.iterator().next();

    CMSProcessable content = cms.getSignedContent();
    if (content != null) {
      Object attached = content.getContent();
      if (!(attached instanceof byte[]) || !Arrays.equals((byte[]) attached, lockedPayload)) {
        throw refuse(
            String.format(
                I18n.get(
                    "The uploaded signed file for %s contains different data from the locked file."
                        + " Sign exactly the file downloaded from here; the submission was"
                        + " refused."),
                name));
      }
    }

    X509Certificate signer = null;
    List<X509Certificate> chain = new ArrayList<>();
    for (Object holder : cms.getCertificates().getMatches(null)) {
      X509CertificateHolder cert = (X509CertificateHolder) holder;
      X509Certificate x509 = toX509(cert, name);
      if (signer == null && signerInfo.getSID().match(cert)) {
        signer = x509;
      } else {
        chain.add(x509);
      }
    }
    if (signer == null) {
      throw refuse(
          String.format(
              I18n.get(
                  "The signed file for %s does not contain the signer's certificate; the"
                      + " submission was refused."),
              name));
    }
    return new Parsed(detached(cms, name), signer, chain);
  }

  /** Същата SignedData структура без прикрепено съдържание — подписът и атрибутите не се пипат. */
  static byte[] detached(CMSSignedData cms, String name) {
    try {
      SignedData signedData = SignedData.getInstance(cms.toASN1Structure().getContent());
      SignedData withoutContent =
          new SignedData(
              signedData.getDigestAlgorithms(),
              new ContentInfo(signedData.getEncapContentInfo().getContentType(), null),
              signedData.getCertificates(),
              signedData.getCRLs(),
              signedData.getSignerInfos());
      return new ContentInfo(CMSObjectIdentifiers.signedData, withoutContent)
          .getEncoded(ASN1Encoding.DER);
    } catch (Exception e) {
      throw refuse(
          String.format(
              I18n.get(
                  "The signature in the file for %s cannot be prepared for NAP; the submission"
                      + " was refused."),
              name));
    }
  }

  static CMSSignedData readCms(byte[] uploaded, String name) {
    byte[] der = uploaded;
    if ((uploaded[0] & 0xFF) != 0x30) {
      // Не е DER (SEQUENCE) → base64 текст, евентуално с PEM заглавие.
      String text =
          new String(uploaded, StandardCharsets.US_ASCII)
              .replaceAll("-----(BEGIN|END)[^-]*-----", "")
              .replaceAll("\\s", "");
      try {
        der = Base64.getDecoder().decode(text);
      } catch (IllegalArgumentException e) {
        der = null;
      }
    }
    try {
      if (der == null || der.length == 0) {
        throw new IllegalArgumentException("empty");
      }
      return new CMSSignedData(der);
    } catch (Exception e) {
      throw refuse(
          String.format(
              I18n.get(
                  "The uploaded file for %s is not a signature in PKCS#7/CAdES format (.p7s or"
                      + " .p7m). Upload the file that the Evrotrust portal returned after"
                      + " signing."),
              name));
    }
  }

  static X509Certificate toX509(X509CertificateHolder holder, String name) {
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(holder.getEncoded()));
    } catch (Exception e) {
      throw refuse(
          String.format(
              I18n.get(
                  "A certificate in the signed file for %s cannot be parsed; the submission was"
                      + " refused."),
              name));
    }
  }

  static QesException refuse(String message) {
    return new QesException(QesException.Category.INVALID_REQUEST, message);
  }
}
