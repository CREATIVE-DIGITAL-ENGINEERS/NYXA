package com.axelor.apps.napsubmissions.common.signing.browser;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.i18n.I18n;
import java.security.cert.X509Certificate;
import java.util.List;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.cms.SignerIdentifier;
import org.bouncycastle.asn1.cms.SignerInfo;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

/**
 * СГЛОБЯВА ДЕТАЧЕН PKCS#7 ОТ СУРОВИЯ ПОДПИС, КОЙТО ЛОКАЛНИЯТ КОМПОНЕНТ ВРЪЩА.
 *
 * <p><b>Защо изобщо.</b> BISS (спец. v3.44, т. 4.4) не връща готов PKCS#7/CAdES — {@code
 * signatureType} по подразбиране и единствената документирана стойност е {@code "signature"}, а
 * отговорът носи {@code signatures[]} — СУРОВА подписна стойност. НАП обаче иска детачен PKCS#7
 * ({@code base64EncodedPkcs7}). Разликата е само ОБВИВКАТА: подписната стойност е една и съща.
 *
 * <p><b>Какво точно подписва BISS.</b> При {@code contentType: "data"} полето {@code contents}
 * съдържа данните, които ще бъдат хеширани с {@code hashAlgorithm} и след това подписани — тоест
 * {@code signature = RSA_PKCS1(sha256(content))}, подпис от тип {@code SHA256withRSA} върху ТОЧНИТЕ
 * байтове. Това е буквално стойността, която RFC 5652 §5.4 поставя в {@code SignerInfo.signature},
 * КОГАТО {@code signedAttrs} ОТСЪСТВА: тогава подписът покрива самото съдържание, а не дайджест на
 * атрибути. Затова сглобяването е коректно, а не импровизация — не се изобретява нищо, само се
 * описва наличният подпис в ASN.1.
 *
 * <p><b>Какво НЕ се прави тук.</b> Нищо не се подписва — няма частен ключ. КЕП ключът и PIN-ът
 * остават на токена. Сглобеният контейнер веднага минава през {@code DetachedPkcs7Verifier} върху
 * оригиналните заключени байтове; не мине ли — подаването се отказва и НИЩО не тръгва към НАП.
 */
public final class DetachedPkcs7Assembler {

  private DetachedPkcs7Assembler() {}

  /**
   * Обвива сурова подписна стойност в детачен CMS SignedData (без вграждано съдържание и без
   * signedAttrs).
   *
   * @param rawSignature подписната стойност, както {@code /sign} я е върнал ({@code signatures[0]})
   * @param signer сертификатът на подписващия
   * @param chain междинните сертификати (влагат се в контейнера, ако има)
   * @param sha512 true, когато е договорен SHA512 вместо SHA256
   * @return DER-кодиран детачен PKCS#7
   */
  public static byte[] assemble(
      byte[] rawSignature, X509Certificate signer, List<X509Certificate> chain, boolean sha512) {
    if (rawSignature == null || rawSignature.length == 0) {
      throw refuse(I18n.get("The local component returned no signature."));
    }
    if (signer == null) {
      throw refuse(I18n.get("The signer's certificate is missing."));
    }
    try {
      AlgorithmIdentifier digestAlgorithm =
          new AlgorithmIdentifier(
              sha512 ? NISTObjectIdentifiers.id_sha512 : NISTObjectIdentifiers.id_sha256,
              DERNull.INSTANCE);
      // RFC 5652: при RSA подписният алгоритъм е rsaEncryption, а хешът идва от digestAlgorithm.
      AlgorithmIdentifier signatureAlgorithm =
          new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);

      X509CertificateHolder signerHolder = new JcaX509CertificateHolder(signer);
      SignerIdentifier signerId =
          new SignerIdentifier(
              new IssuerAndSerialNumber(signerHolder.getIssuer(), signerHolder.getSerialNumber()));

      SignerInfo signerInfo =
          new SignerInfo(
              signerId,
              digestAlgorithm,
              (ASN1Set) null, // signedAttrs ОТСЪСТВА — подписът покрива самото съдържание
              signatureAlgorithm,
              new DEROctetString(rawSignature),
              (ASN1Set) null);

      ASN1EncodableVector certificates = new ASN1EncodableVector();
      certificates.add(signerHolder.toASN1Structure());
      if (chain != null) {
        for (X509Certificate intermediate : chain) {
          if (intermediate != null && !intermediate.equals(signer)) {
            certificates.add(new JcaX509CertificateHolder(intermediate).toASN1Structure());
          }
        }
      }

      ASN1EncodableVector digestAlgorithms = new ASN1EncodableVector();
      digestAlgorithms.add(digestAlgorithm);

      // Детачно: eContentType = id-data, БЕЗ eContent.
      ContentInfo encapsulated = new ContentInfo(CMSObjectIdentifiers.data, null);

      ASN1EncodableVector signerInfos = new ASN1EncodableVector();
      signerInfos.add(signerInfo);

      SignedData signedData =
          new SignedData(
              new DERSet(digestAlgorithms),
              encapsulated,
              new DERSet(certificates),
              null,
              new DERSet(signerInfos));
      return new ContentInfo((ASN1ObjectIdentifier) CMSObjectIdentifiers.signedData, signedData)
          .getEncoded("DER");
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      throw refuse(
          I18n.get("The signature cannot be assembled into a PKCS#7; the submission was refused."));
    }
  }

  private static QesException refuse(String message) {
    return new QesException(QesException.Category.INVALID_REQUEST, message);
  }
}
