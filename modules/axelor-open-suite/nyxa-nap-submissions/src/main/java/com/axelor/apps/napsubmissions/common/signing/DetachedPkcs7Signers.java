package com.axelor.apps.napsubmissions.common.signing;

import com.axelor.i18n.I18n;
import java.util.Collection;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;

/**
 * КОЙ СЕРТИФИКАТ Е НАПРАВИЛ ТОЗИ ПОДПИС — прочетено ОТ САМИЯ контейнер, не предположено.
 *
 * <p>Детачният CMS SignedData носи две неща, които отговарят на въпроса точно: {@code SignerInfo}
 * със {@code SignerId} (издател + сериен номер, или ключов идентификатор), и — почти винаги —
 * самите сертификати. Това е по-надеждно от „вземи сертификата на този потребител от доставчика“:
 * при подновен или втори сертификат на същия човек отдалеченият отговор може да е ДРУГ сертификат,
 * с който подписът няма да се провери, а НАП ще получи сертификат, който не отговаря на подписа.
 *
 * <p>Затова редът е: първо вграденият сертификат, съответстващ на {@code SignerId}; само ако
 * контейнерът не носи сертификати — документираният endpoint на доставчика, и то след като
 * полученият сертификат бъде съпоставен със {@code SignerId} ({@link #matchesSigner}). Произволен
 * сертификат не се приема на нито едно от двете места.
 *
 * <p><b>Един подпис, един автор.</b> НАП получава един автор на подаване, затова контейнер с нула
 * или с повече от един подписващ се отказва тук — преди сертификатът изобщо да бъде търсен.
 *
 * <p>Stateless и без странични ефекти: чете подадените байтове и не пише никъде. Самата
 * криптографска проверка, че подписът покрива точните заключени байтове, е в {@link
 * DetachedPkcs7Verifier} и остава единственото място, което я прави.
 */
public final class DetachedPkcs7Signers {

  private DetachedPkcs7Signers() {}

  /**
   * Вграденият сертификат на ЕДИНСТВЕНИЯ подписващ, в DER, или {@code null} когато контейнерът не
   * носи сертификати (валиден случай — тогава се пита доставчикът).
   *
   * @throws QesException при нечетим контейнер, при нула или повече от един подписващ, и когато
   *     носените сертификати не съдържат този на подписващия
   */
  public static byte[] embeddedSignerCertificate(byte[] detachedPkcs7) {
    SignerInformation signer = singleSigner(detachedPkcs7);
    try {
      CMSSignedData signed = new CMSSignedData(detachedPkcs7);
      Collection<X509CertificateHolder> all = signed.getCertificates().getMatches(null);
      if (all.isEmpty()) {
        // Контейнерът е без сертификати — допустимо за детачен CMS; решава повикващият.
        return null;
      }
      Collection<X509CertificateHolder> matches =
          signed.getCertificates().getMatches(signer.getSID());
      if (matches.isEmpty()) {
        throw refuse(
            I18n.get(
                "The detached PKCS#7 carries certificates, but none of them belongs to the signer"
                    + " (SignerId does not match); the submission was refused."));
      }
      return matches.iterator().next().getEncoded();
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      throw refuse(I18n.get("The signer's certificate cannot be read from the signature."));
    }
  }

  /**
   * Вярно само когато {@code certificateDer} е ИМЕННО сертификатът, посочен от {@code SignerId} на
   * единствения подписващ. Това е проверката, която прави отдалечено взет сертификат приемлив.
   */
  public static boolean matchesSigner(byte[] detachedPkcs7, byte[] certificateDer) {
    if (certificateDer == null || certificateDer.length == 0) {
      return false;
    }
    try {
      SignerInformation signer = singleSigner(detachedPkcs7);
      return signer.getSID().match(new X509CertificateHolder(certificateDer));
    } catch (Exception e) {
      return false;
    }
  }

  /** Единственият подписващ; нула или няколко е отказ, не избор. */
  private static SignerInformation singleSigner(byte[] detachedPkcs7) {
    if (detachedPkcs7 == null || detachedPkcs7.length == 0) {
      throw refuse(I18n.get("The signature (PKCS#7) is missing; the submission was refused."));
    }
    Collection<SignerInformation> signers;
    try {
      signers = new CMSSignedData(detachedPkcs7).getSignerInfos().getSigners();
    } catch (Exception e) {
      throw refuse(I18n.get("The signature cannot be read as PKCS#7; the submission was refused."));
    }
    if (signers.isEmpty()) {
      throw refuse(I18n.get("The signature (PKCS#7) has no signer; the submission was refused."));
    }
    if (signers.size() > 1) {
      throw refuse(
          I18n.get(
              "The signature (PKCS#7) has more than one signer, but NAP accepts one author per"
                  + " submission; the submission was refused."));
    }
    return signers.iterator().next();
  }

  private static QesException refuse(String message) {
    return new QesException(QesException.Category.INVALID_REQUEST, message);
  }
}
