package com.axelor.apps.napsubmissions.common.signing.browser;

import java.util.List;

/**
 * ЕДИН ПОДПИСАН ФАЙЛ, КАКТО ГО ВРЪЩА БРАУЗЪРЪТ — преходен вход, не запис.
 *
 * <p>Произходът е локалният подписващ компонент на машината на потребителя (BISS, {@code POST
 * https://localhost:5395x/sign}). ⚠️ Полето {@code signatureBase64} е СУРОВАТА подписна стойност
 * ({@code signatures[0]} от отговора), а НЕ готов PKCS#7 — BISS не връща такъв. Обвивката се
 * сглобява на сървъра ({@link DetachedPkcs7Assembler}) и чак тогава се проверява криптографски.
 *
 * <p>В Nyxa влизат само публични неща: подписна стойност, сертификат на подписващия и веригата му.
 * Частният ключ и PIN-ът за отключване на токена НИКОГА не минават оттук — те не напускат
 * устройството. Обектът живее само докато трае едно подаване.
 */
public class BrowserSignedFile {

  /** Идентификаторът на {@code NapSubmissionFile}, за който е този подпис. */
  public Long fileId;

  /** Base64 на СУРОВАТА подписна стойност от {@code /sign} ({@code signatures[0]}). */
  public String signatureBase64;

  /** Base64 (DER) на сертификата на подписващия. */
  public String certificateBase64;

  /** Base64 (DER) на междинните сертификати, както компонентът ги е върнал. Може да е празно. */
  public List<String> chainBase64;

  /** Хеш алгоритъмът, договорен с компонента — {@code SHA256} или {@code SHA512}. */
  public String hashAlgorithm;

  /** SHA-256 (hex) на байтовете, които браузърът твърди, че е подписал. */
  public String payloadSha256Hex;
}
