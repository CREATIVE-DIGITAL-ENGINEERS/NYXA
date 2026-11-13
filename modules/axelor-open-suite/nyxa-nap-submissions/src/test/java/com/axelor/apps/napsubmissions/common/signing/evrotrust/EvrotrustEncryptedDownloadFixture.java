package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.engines.RijndaelEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

/**
 * TEST-ONLY: произвежда отговор на {@code /document/download} ТОЧНО по документираната процедура,
 * за да може разшифроването да се провери срещу истински вектори, а не срещу себе си.
 *
 * <p>Това е ОБРАТНАТА посока на {@link EvrotrustDownloadDecryptor}, написана независимо от него по
 * същия текст: вътрешен ZIP → base64 → допълване с {@code \0} до блока → Rijndael-256-CBC на
 * парчета → base64 → {@code .enc}; симетричният ключ → RSA PKCS#1 v1.5 → base64 → {@code .enc.key};
 * IV → суров текст → {@code .enc.iv}. Живее само в {@code src/test}.
 */
public final class EvrotrustEncryptedDownloadFixture {

  /** Документираният размер на едно парче в base64 знаци. */
  static final int CHUNK_BASE64_CHARS = 7296;

  /** 7296 base64 знака носят точно толкова байта шифротекст. */
  static final int CHUNK_CIPHER_BYTES = 5472;

  static final int BLOCK_BYTES = 32;

  private EvrotrustEncryptedDownloadFixture() {}

  /**
   * The same detached CMS, stripped of its certificate set — a legal container, and the ONLY case
   * in which the provider is allowed to ask the vendor's certificate endpoint.
   */
  public static byte[] withoutCertificates(byte[] detachedPkcs7) {
    try {
      org.bouncycastle.cms.CMSSignedData signed =
          new org.bouncycastle.cms.CMSSignedData(detachedPkcs7);
      return org.bouncycastle.cms.CMSSignedData.replaceCertificatesAndCRLs(
              signed,
              new org.bouncycastle.util.CollectionStore<>(java.util.Collections.emptyList()),
              null,
              null)
          .getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("fixture could not strip certificates", e);
    }
  }

  /** A 32-byte symmetric key of one repeated character — deterministic and obviously synthetic. */
  public static byte[] symmetricKey(char filler) {
    byte[] key = new byte[BLOCK_BYTES];
    java.util.Arrays.fill(key, (byte) filler);
    return key;
  }

  /** Plain ZIP of the given entries — the inner archive, or any archive a test needs. */
  public static byte[] zip(Map<String, byte[]> entries) throws Exception {
    return innerZip(entries);
  }

  public static KeyPair rsa2048() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, new SecureRandom());
    return generator.generateKeyPair();
  }

  /** 32-байтов IV като СУРОВ ASCII текст, точно както го описва документацията. */
  public static String iv(String seed) {
    StringBuilder sb = new StringBuilder();
    while (sb.length() < BLOCK_BYTES) {
      sb.append(seed);
    }
    return sb.substring(0, BLOCK_BYTES);
  }

  /** Вътрешен ZIP с посочените записи. */
  public static byte[] innerZip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  /** Пълният външен ZIP, както доставчикът би го върнал. */
  public static byte[] outerZip(
      byte[] innerZip, PublicKey publicKey, byte[] symmetricKey, String ivText) throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    // ⚠️ Нарочно присъства: разшифроването ТРЯБВА да го игнорира и да ползва конфигурирания ключ.
    files.put(
        "keypair.pem",
        "-----BEGIN PUBLIC KEY-----\nignored\n-----END PUBLIC KEY-----\n"
            .getBytes(StandardCharsets.US_ASCII));
    files.put("4242_OUT.enc.key", encryptedKey(symmetricKey, publicKey));
    files.put("4242_OUT.enc.iv", ivText.getBytes(StandardCharsets.US_ASCII));
    files.put("4242_OUT.enc", encFile(innerZip, symmetricKey, ivText));
    return innerZip(files);
  }

  public static byte[] encryptedKey(byte[] symmetricKey, PublicKey publicKey) throws Exception {
    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    cipher.init(Cipher.ENCRYPT_MODE, publicKey);
    return Base64.getEncoder().encode(cipher.doFinal(symmetricKey));
  }

  /**
   * {@code .enc}: съдържанието се base64-кодира, реже се на парчета, които след шифроване стават
   * точно {@value #CHUNK_BASE64_CHARS} base64 знака, всяко парче се допълва с {@code \0} до блока и
   * се шифрова с НОВО инициализиран Rijndael-256-CBC (същите ключ и IV).
   */
  public static byte[] encFile(byte[] content, byte[] symmetricKey, String ivText) {
    String base64Content = Base64.getEncoder().encodeToString(content);
    byte[] iv = ivText.getBytes(StandardCharsets.US_ASCII);
    StringBuilder enc = new StringBuilder();
    // Точно CHUNK_CIPHER_BYTES открит текст на парче: 5472 = 171 × 32 (цял брой блокове, значи без
    // допълване) и 5472 = 1824 × 3 (значи base64 без „=“, точно CHUNK_BASE64_CHARS знака). Само
    // последното парче е по-късо и се допълва с \0 — то единствено може да носи „=“.
    int plainPerChunk = CHUNK_CIPHER_BYTES;
    for (int offset = 0; offset < base64Content.length(); offset += plainPerChunk) {
      String slice =
          base64Content.substring(offset, Math.min(offset + plainPerChunk, base64Content.length()));
      byte[] padded = zeroPad(slice.getBytes(StandardCharsets.US_ASCII));
      enc.append(Base64.getEncoder().encodeToString(encryptBlock(padded, symmetricKey, iv)));
    }
    return enc.toString().getBytes(StandardCharsets.US_ASCII);
  }

  public static byte[] zeroPad(byte[] data) {
    int remainder = data.length % BLOCK_BYTES;
    if (remainder == 0) {
      return data;
    }
    byte[] padded = new byte[data.length + (BLOCK_BYTES - remainder)];
    System.arraycopy(data, 0, padded, 0, data.length);
    return padded;
  }

  public static byte[] encryptBlock(byte[] plaintext, byte[] key, byte[] iv) {
    try {
      BufferedBlockCipher cipher =
          new BufferedBlockCipher(new CBCBlockCipher(new RijndaelEngine(BLOCK_BYTES * 8)));
      cipher.init(true, new ParametersWithIV(new KeyParameter(key), iv));
      byte[] out = new byte[cipher.getOutputSize(plaintext.length)];
      int written = cipher.processBytes(plaintext, 0, plaintext.length, out, 0);
      written += cipher.doFinal(out, written);
      byte[] exact = new byte[written];
      System.arraycopy(out, 0, exact, 0, written);
      return exact;
    } catch (Exception e) {
      throw new IllegalStateException("fixture could not encrypt", e);
    }
  }
}
