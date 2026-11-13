package com.axelor.apps.napsubmissions.common.signing;

/**
 * ПОДПИСЪТ И ПУБЛИЧНИЯТ СЕРТИФИКАТ — САМО В ПАМЕТТА, САМО ЗА ЕДНО ИЗПРАЩАНЕ.
 *
 * <p>Носи детачния PKCS#7 върху точните заключени байтове на ЕДИН файл и публичния сертификат на
 * подписващия, за да се сглоби непосредствената заявка към НАП. ⚠️ Този обект НЕ се записва никъде
 * трайно — нито в базата, нито като {@code MetaFile}, нито в одита, лога или постоянен файлов кеш.
 * Той живее само докато трае една операция по подаване/проверка и се изхвърля след нея.
 *
 * <p><b>Последствия, които този модел налага нарочно:</b>
 *
 * <ul>
 *   <li>След рестарт подписът го няма — няма какво да се подаде автоматично повторно (без трайно
 *       съхранение няма „висящо" подаване за автоматичен повторен POST).
 *   <li>При повторен опит се подписва НАНОВО — нов подпис върху същите байтове и същия дайджест.
 *   <li>По-късната проверка на резултата изисква подписващият да предостави ОТНОВО публичния си
 *       сертификат; входящият номер сам по себе си не е достатъчен (контрактът на НАП иска
 *       сертификат и при заявката за резултат).
 * </ul>
 *
 * <p>⚠️ Не съдържа частен ключ и не съдържа PIN — те не напускат устройството на подписващия.
 */
public final class TransientQesSignature {

  private final byte[] detachedPkcs7;
  private final byte[] certificate;
  private final String payloadSha256Hex;

  public TransientQesSignature(byte[] detachedPkcs7, byte[] certificate, String payloadSha256Hex) {
    this.detachedPkcs7 = detachedPkcs7;
    this.certificate = certificate;
    this.payloadSha256Hex = payloadSha256Hex;
  }

  /** Детачният PKCS#7 върху точните заключени байтове на файла. */
  public byte[] getDetachedPkcs7() {
    return detachedPkcs7;
  }

  /** Публичният X.509 сертификат на подписващия (без ключов материал). */
  public byte[] getCertificate() {
    return certificate;
  }

  /** SHA-256 (hex) на байтовете, върху които е произведен подписът. */
  public String getPayloadSha256Hex() {
    return payloadSha256Hex;
  }
}
