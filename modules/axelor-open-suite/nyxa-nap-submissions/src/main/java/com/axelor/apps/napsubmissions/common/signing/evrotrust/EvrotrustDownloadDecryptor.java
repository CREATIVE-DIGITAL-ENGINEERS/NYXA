package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.i18n.I18n;
import com.google.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.engines.RijndaelEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

/**
 * РАЗШИФРОВА ОТГОВОРА НА {@code /document/download} ТОЧНО ПО ДОКУМЕНТАЦИЯТА и вади от него детачния
 * PKCS#7 — единственото, което изобщо може да тръгне към НАП.
 *
 * <p><b>Документираната процедура (docs.evrotrust.com, „Decrypt downloaded documents“):</b>
 *
 * <ol>
 *   <li>Външният отговор е ZIP с {@code <id>_OUT.enc.key}, {@code <id>_OUT.enc.iv}, {@code
 *       <id>_OUT.enc} (и {@code keypair.pem}, който тук НИКОГА не се чете — ключът идва само от
 *       нашата конфигурация, вж. {@link EvrotrustPrivateKeyProvider});
 *   <li>{@code .enc.key} се base64-декодира и се разшифрова с <b>RSA-2048, PKCS#1 v1.5</b> → точно
 *       32 байта симетричен ключ;
 *   <li>{@code .enc.iv} се взема като СУРОВ текст без обкръжаващи интервали → точно 32 байта IV (не
 *       се base64-декодира и не минава през RSA);
 *   <li>{@code .enc} се обработва на парчета от <b>7296 base64 знака</b> (5472 байта шифротекст;
 *       последното парче може да е по-късо). За всяко парче: base64-декодиране → <b>Rijndael с
 *       256-битов ключ и 256-битов блок, CBC, без допълване</b>, с НОВО инициализиран шифър (същите
 *       ключ и IV за всяко парче) → премахване на крайните {@code \0} → ВТОРО base64-декодиране →
 *       добавяне към резултата;
 *   <li>Резултатът е вътрешен ZIP.
 * </ol>
 *
 * <p>⚠️ Rijndael с 256-битов блок <b>не е AES</b> (AES е Rijndael със 128-битов блок), затова и не
 * минава през JCE името {@code AES}: ползва се {@link RijndaelEngine} с изрична дължина на блока.
 *
 * <p><b>Нищо не се пише на диск и нищо не се кешира</b> — всичко живее в паметта за една заявка.
 * Всеки отказ назовава КОНКРЕТНАТА причина и никога не издава ключ или част от него.
 */
@Singleton
public class EvrotrustDownloadDecryptor {

  /** Документираният размер на едно парче в base64 знаци. */
  static final int CHUNK_BASE64_CHARS = 7296;

  /** Документираната дължина на симетричния ключ. */
  static final int SYMMETRIC_KEY_BYTES = 32;

  /** Документираната дължина на IV. */
  static final int IV_BYTES = 32;

  /** Документираният размер на блока на Rijndael в битове. */
  static final int RIJNDAEL_BLOCK_BITS = 256;

  // ── Ограничения срещу „zip bomb“ и срещу отговор, който би изял паметта ───────────────────────
  /** Най-много записи, които се разглеждат в който и да е ZIP. */
  static final int MAX_ZIP_ENTRIES = 64;

  /** Най-голям допустим РАЗАРХИВИРАН размер на един запис. */
  static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;

  /** Най-голям допустим общ разархивиран размер на един ZIP. */
  static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

  /** Най-голям допустим размер на самия отговор, преди изобщо да се отваря като ZIP. */
  static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

  private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

  /**
   * Външен ZIP → вътрешен ZIP. Отказва при всяко отклонение от документираната структура, при
   * грешен ключ, при повредени данни и при надвишено ограничение за размер.
   */
  public byte[] decrypt(byte[] responseBody, PrivateKey privateKey) {
    if (responseBody == null || responseBody.length == 0) {
      throw refuse(
          I18n.get("Evrotrust returned an empty download response; the submission was refused."));
    }
    if (responseBody.length > MAX_RESPONSE_BYTES) {
      throw refuse(
          String.format(
              I18n.get(
                  "The download response from Evrotrust exceeds the allowed size (%s bytes); the"
                      + " submission was refused."),
              MAX_RESPONSE_BYTES));
    }
    requireZip(responseBody, I18n.get("The download response from Evrotrust"));
    if (privateKey == null) {
      throw refuse(
          I18n.get(
              "The private key for decrypting the response is missing; the submission was"
                  + " refused."));
    }

    Map<String, byte[]> entries = unzip(responseBody);
    byte[] encryptedKey = bySuffix(entries, ".enc.key");
    byte[] rawIv = bySuffix(entries, ".enc.iv");
    byte[] encrypted = encEntry(entries);

    byte[] symmetricKey = rsaDecryptKey(encryptedKey, privateKey);
    byte[] iv = trimmedIv(rawIv);
    byte[] innerZip = decryptChunks(encrypted, symmetricKey, iv);

    requireZip(innerZip, I18n.get("The decrypted result from Evrotrust"));
    return innerZip;
  }

  /**
   * Вътрешен ZIP → ДЕТАЧЕН PKCS#7 и нищо друго.
   *
   * <p>Приема се само запис, който се разчита като CMS SignedData <b>без вграденo съдържание</b>
   * (детачен) и с поне един подписващ. Всичко останало — прикачен PKCS#7, PDF, XML, произволен файл
   * — се отказва с изброяване на намереното, защото НАП получава точно този контейнер.
   *
   * <p>⚠️ Това е проверка на ФОРМАТА. Че подписът покрива точните заключени TXT байтове и
   * съответства на сертификата на подписващия се доказва на едно-единствено място — {@code
   * DeclarationsEvidenceService#verifyTransient} през {@code DetachedPkcs7Verifier}, непосредствено
   * преди мрежата.
   */
  public byte[] detachedPkcs7From(byte[] innerZip) {
    Map<String, byte[]> entries = unzip(innerZip);
    List<String> detached = new ArrayList<>();
    byte[] found = null;
    for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
      if (isDetachedPkcs7(entry.getValue())) {
        detached.add(entry.getKey());
        found = entry.getValue();
      }
    }
    if (detached.isEmpty()) {
      throw refuse(
          String.format(
              I18n.get(
                  "The decrypted result from Evrotrust has no detached PKCS#7 signature. Entries"
                      + " found: %s. Only a detached PKCS#7 is submitted to NAP, so the submission"
                      + " was refused."),
              String.join(", ", entries.keySet())));
    }
    if (detached.size() > 1) {
      throw refuse(
          String.format(
              I18n.get(
                  "The decrypted result from Evrotrust has more than one detached PKCS#7 (%s),"
                      + " and it is not clear which one is the signature of this file; the"
                      + " submission was refused."),
              String.join(", ", detached)));
    }
    return found;
  }

  /** CMS SignedData без вградено съдържание и с поне един подписващ. */
  protected boolean isDetachedPkcs7(byte[] candidate) {
    if (candidate == null || candidate.length == 0) {
      return false;
    }
    try {
      CMSSignedData signed = new CMSSignedData(candidate);
      return signed.getSignedContent() == null && !signed.getSignerInfos().getSigners().isEmpty();
    } catch (Exception notCms) {
      return false;
    }
  }

  // ── стъпките по документацията ───────────────────────────────────────────────────────────────

  /** {@code .enc.key}: base64 → RSA/PKCS#1 v1.5 → точно 32 байта. */
  protected byte[] rsaDecryptKey(byte[] base64EncryptedKey, PrivateKey privateKey) {
    byte[] encrypted;
    try {
      encrypted = Base64.getDecoder().decode(stripWhitespace(asText(base64EncryptedKey)));
    } catch (IllegalArgumentException bad) {
      throw refuse(
          I18n.get(
              "The encrypted symmetric key (.enc.key) is not valid base64; the submission was"
                  + " refused."));
    }
    byte[] key;
    try {
      Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      cipher.init(Cipher.DECRYPT_MODE, privateKey);
      key = cipher.doFinal(encrypted);
    } catch (Exception e) {
      // ⚠️ Най-честата причина е ГРЕШЕН ключ. Съдържанието на изключението не се показва.
      throw refuse(
          I18n.get(
              "The symmetric key cannot be decrypted with the configured private key. Most likely"
                  + " this is not the key whose publicKey was sent when the signing was started;"
                  + " the submission was refused."));
    }
    if (key.length != SYMMETRIC_KEY_BYTES) {
      throw refuse(
          String.format(
              I18n.get(
                  "The decrypted symmetric key is %s bytes instead of the documented %s; the"
                      + " submission was refused."),
              key.length,
              SYMMETRIC_KEY_BYTES));
    }
    return key;
  }

  /** {@code .enc.iv}: суров текст без обкръжаващи интервали → точно 32 байта. */
  protected byte[] trimmedIv(byte[] rawIv) {
    byte[] iv = asText(rawIv).trim().getBytes(StandardCharsets.US_ASCII);
    if (iv.length != IV_BYTES) {
      throw refuse(
          String.format(
              I18n.get(
                  "The initialization vector (.enc.iv) is %s bytes instead of the documented %s;"
                      + " the submission was refused."),
              iv.length,
              IV_BYTES));
    }
    return iv;
  }

  /**
   * {@code .enc} на парчета от {@value #CHUNK_BASE64_CHARS} base64 знака.
   *
   * <p>Преди разделянето всички интервали и преходи на нов ред се махат. Това не променя нищо, ако
   * файлът е на един ред (както го описва документацията), и е единственото тълкуване, което
   * работи, ако доставчикът го върне пренесен на редове — строгият base64 декодер иначе би отказал.
   */
  protected byte[] decryptChunks(byte[] encFile, byte[] symmetricKey, byte[] iv) {
    String base64 = stripWhitespace(asText(encFile));
    if (base64.isEmpty()) {
      throw refuse(I18n.get("The encrypted result (.enc) is empty; the submission was refused."));
    }
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    long total = 0;
    for (int offset = 0; offset < base64.length(); offset += CHUNK_BASE64_CHARS) {
      String chunk =
          base64.substring(offset, Math.min(offset + CHUNK_BASE64_CHARS, base64.length()));
      byte[] ciphertext;
      try {
        ciphertext = Base64.getDecoder().decode(chunk);
      } catch (IllegalArgumentException bad) {
        throw refuse(
            I18n.get(
                "The encrypted result (.enc) contains invalid base64; the data is corrupted and"
                    + " the submission was refused."));
      }
      byte[] plaintext = rijndael256Cbc(ciphertext, symmetricKey, iv);
      byte[] stripped = stripTrailingZeros(plaintext);
      byte[] decoded;
      try {
        decoded =
            Base64.getDecoder()
                .decode(stripWhitespace(new String(stripped, StandardCharsets.US_ASCII)));
      } catch (IllegalArgumentException bad) {
        // Най-честата причина: разшифровано с грешен ключ/IV — изходът е шум, а не base64.
        throw refuse(
            I18n.get(
                "The decrypted chunk is not valid base64. This means a wrong key or corrupted"
                    + " data; the submission was refused."));
      }
      total += decoded.length;
      if (total > MAX_TOTAL_BYTES) {
        throw refuse(
            String.format(
                I18n.get(
                    "The decrypted result exceeds the allowed size (%s bytes); the submission was"
                        + " refused."),
                MAX_TOTAL_BYTES));
      }
      result.write(decoded, 0, decoded.length);
    }
    return result.toByteArray();
  }

  /** Rijndael, 256-битов ключ, 256-битов блок, CBC, БЕЗ допълване; нов шифър за всяко парче. */
  protected byte[] rijndael256Cbc(byte[] ciphertext, byte[] key, byte[] iv) {
    int blockBytes = RIJNDAEL_BLOCK_BITS / 8;
    if (ciphertext.length == 0 || ciphertext.length % blockBytes != 0) {
      throw refuse(
          String.format(
              I18n.get(
                  "The encrypted data is not a multiple of the %s-byte block (%s); the data is"
                      + " corrupted and the submission was refused."),
              blockBytes,
              ciphertext.length));
    }
    try {
      BufferedBlockCipher cipher =
          new BufferedBlockCipher(new CBCBlockCipher(new RijndaelEngine(RIJNDAEL_BLOCK_BITS)));
      cipher.init(false, new ParametersWithIV(new KeyParameter(key), iv));
      byte[] out = new byte[cipher.getOutputSize(ciphertext.length)];
      int written = cipher.processBytes(ciphertext, 0, ciphertext.length, out, 0);
      written += cipher.doFinal(out, written);
      byte[] exact = new byte[written];
      System.arraycopy(out, 0, exact, 0, written);
      return exact;
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      throw refuse(I18n.get("The encrypted data cannot be decrypted; the submission was refused."));
    }
  }

  protected byte[] stripTrailingZeros(byte[] plaintext) {
    int end = plaintext.length;
    while (end > 0 && plaintext[end - 1] == 0) {
      end--;
    }
    byte[] stripped = new byte[end];
    System.arraycopy(plaintext, 0, stripped, 0, end);
    return stripped;
  }

  // ── ZIP с ограничения ────────────────────────────────────────────────────────────────────────

  /**
   * Разархивира в паметта с твърди ограничения за брой записи и размер. {@code keypair.pem} НЕ се
   * чете: ключът идва единствено от конфигурацията, не от отговора на доставчика.
   */
  protected Map<String, byte[]> unzip(byte[] zip) {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    long total = 0;
    int count = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        if (++count > MAX_ZIP_ENTRIES) {
          throw refuse(
              String.format(
                  I18n.get(
                      "The archive from Evrotrust contains more than %s entries; the submission"
                          + " was refused."),
                  MAX_ZIP_ENTRIES));
        }
        String name = entry.getName();
        if (name.endsWith("keypair.pem")) {
          // Нарочно пропуснат: ключов материал от доставчика никога не се ползва.
          continue;
        }
        total += readLimited(in, entries, name, total);
      }
    } catch (QesException e) {
      throw e;
    } catch (Exception e) {
      throw refuse(
          I18n.get("The archive from Evrotrust cannot be read; the submission was refused."));
    }
    if (entries.isEmpty()) {
      throw refuse(I18n.get("The archive from Evrotrust is empty; the submission was refused."));
    }
    return entries;
  }

  /**
   * Чете един запис с таван на записа и на целия архив (разархивираният размер е този, който тежи).
   */
  protected long readLimited(
      ZipInputStream in, Map<String, byte[]> entries, String name, long totalSoFar)
      throws java.io.IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] block = new byte[8192];
    long size = 0;
    int read;
    while ((read = in.read(block)) > 0) {
      size += read;
      if (size > MAX_ENTRY_BYTES) {
        throw refuse(
            String.format(
                I18n.get(
                    "An entry in the archive from Evrotrust exceeds the allowed size (%s bytes);"
                        + " the submission was refused."),
                MAX_ENTRY_BYTES));
      }
      if (totalSoFar + size > MAX_TOTAL_BYTES) {
        throw refuse(
            String.format(
                I18n.get(
                    "The archive from Evrotrust exceeds the allowed total size (%s bytes); the"
                        + " submission was refused."),
                MAX_TOTAL_BYTES));
      }
      buffer.write(block, 0, read);
    }
    entries.put(name, buffer.toByteArray());
    return size;
  }

  protected byte[] bySuffix(Map<String, byte[]> entries, String suffix) {
    byte[] found = null;
    for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
      if (entry.getKey().endsWith(suffix)) {
        if (found != null) {
          throw refuse(
              String.format(
                  I18n.get(
                      "The archive from Evrotrust contains more than one “%s” file; the"
                          + " submission was refused."),
                  suffix));
        }
        found = entry.getValue();
      }
    }
    if (found == null) {
      throw refuse(
          String.format(
              I18n.get(
                  "The archive from Evrotrust has no “%s” file. Entries found: %s; the"
                      + " submission was refused."),
              suffix,
              String.join(", ", entries.keySet())));
    }
    return found;
  }

  /** {@code .enc} — но НЕ {@code .enc.key} / {@code .enc.iv}, които също съдържат „.enc“. */
  protected byte[] encEntry(Map<String, byte[]> entries) {
    byte[] found = null;
    for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
      String name = entry.getKey();
      if (name.endsWith(".enc")) {
        if (found != null) {
          throw refuse(
              String.format(
                  I18n.get(
                      "The archive from Evrotrust contains more than one “%s” file; the"
                          + " submission was refused."),
                  ".enc"));
        }
        found = entry.getValue();
      }
    }
    if (found == null) {
      throw refuse(
          String.format(
              I18n.get(
                  "The archive from Evrotrust has no “%s” file. Entries found: %s; the"
                      + " submission was refused."),
              ".enc",
              String.join(", ", entries.keySet())));
    }
    return found;
  }

  protected void requireZip(byte[] candidate, String what) {
    if (candidate.length < ZIP_MAGIC.length) {
      throw refuse(
          String.format(
              I18n.get("%s is too short to be a ZIP; the submission was refused."), what));
    }
    for (int i = 0; i < ZIP_MAGIC.length; i++) {
      if (candidate[i] != ZIP_MAGIC[i]) {
        throw refuse(
            String.format(
                I18n.get(
                    "%s is not a ZIP (“PK” is expected at the start). The documented response of"
                        + " /document/download is a ZIP with .enc.key/.enc.iv/.enc; the submission"
                        + " was refused."),
                what));
      }
    }
  }

  protected static String asText(byte[] bytes) {
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  protected static String stripWhitespace(String value) {
    return value.replaceAll("\\s", "");
  }

  protected QesException refuse(String message) {
    return new QesException(QesException.Category.TRANSPORT, message);
  }
}
