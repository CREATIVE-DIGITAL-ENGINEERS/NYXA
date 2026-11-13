package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.common.signing.DetachedPkcs7Verifier;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВЕКТОРИ ЗА ДОКУМЕНТИРАНОТО РАЗШИФРОВАНЕ на отговора от {@code /document/download}.
 *
 * <p>Фикстурата ({@link EvrotrustEncryptedDownloadFixture}) произвежда външния ZIP по същия текст,
 * но в обратна посока и независимо от разшифроващия клас, така че тестовете проверяват процедурата,
 * а не себе си. Нито един от тях не прави мрежова заявка.
 *
 * <p>⚠️ Тези вектори доказват, че НАШАТА имплементация следва описанието. Те не могат да докажат,
 * че описанието съвпада с онова, което реалният Evrotrust връща — това се проверява само срещу жив
 * отговор от sandbox.
 */
class EvrotrustDownloadDecryptorTest {

  private static final byte[] LOCKED_TXT = "EMPL2021 locked bytes".getBytes(StandardCharsets.UTF_8);

  private final EvrotrustDownloadDecryptor decryptor = new EvrotrustDownloadDecryptor();

  private byte[] symmetricKey(char filler) {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) filler);
    return key;
  }

  // ── правилен ключ ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Правилен частен ключ: външният ZIP се разшифрова байт в байт до вътрешния ZIP")
  void theDocumentedProcedureRecoversTheInnerZip() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    byte[] inner =
        EvrotrustEncryptedDownloadFixture.innerZip(
            Collections.singletonMap("EMPL2021.TXT.p7s", "not-a-signature".getBytes()));
    byte[] outer =
        EvrotrustEncryptedDownloadFixture.outerZip(
            inner,
            pair.getPublic(),
            symmetricKey('K'),
            EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd"));

    assertArrayEquals(inner, decryptor.decrypt(outer, pair.getPrivate()));
  }

  @Test
  @DisplayName("Многопарчеен .enc (над 7296 знака) се сглобява в правилния ред")
  void aMultiChunkPayloadIsReassembledInOrder() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    // Достатъчно голям, за да изисква няколко парчета, и несвиваем, за да не се смачка в ZIP-а.
    byte[] big = new byte[40_000];
    new java.util.Random(7).nextBytes(big);
    byte[] inner =
        EvrotrustEncryptedDownloadFixture.innerZip(Collections.singletonMap("big.bin", big));
    byte[] outer =
        EvrotrustEncryptedDownloadFixture.outerZip(
            inner,
            pair.getPublic(),
            symmetricKey('K'),
            EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd"));

    assertArrayEquals(inner, decryptor.decrypt(outer, pair.getPrivate()));
  }

  // ── грешен ключ ──────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Грешен частен ключ: отказ, който казва точно това — а не неразбираема грешка")
  void aWrongPrivateKeyIsRefusedWithThatReason() throws Exception {
    KeyPair used = EvrotrustEncryptedDownloadFixture.rsa2048();
    KeyPair other = EvrotrustEncryptedDownloadFixture.rsa2048();
    byte[] inner =
        EvrotrustEncryptedDownloadFixture.innerZip(
            Collections.singletonMap("a.p7s", "x".getBytes()));
    byte[] outer =
        EvrotrustEncryptedDownloadFixture.outerZip(
            inner,
            used.getPublic(),
            symmetricKey('K'),
            EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd"));

    QesException refused =
        assertThrows(QesException.class, () -> decryptor.decrypt(outer, other.getPrivate()));
    assertTrue(
        refused.getMessage().contains("not the key") || refused.getMessage().contains("key"),
        refused.getMessage());
    // ⚠️ Никакъв ключов материал в съобщението.
    assertTrue(!refused.getMessage().contains("BEGIN"), refused.getMessage());
  }

  @Test
  @DisplayName("Грешен симетричен ключ в .enc.key: разшифрованото не е base64 → отказ")
  void aMismatchedSymmetricKeyYieldsNoise() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    byte[] inner =
        EvrotrustEncryptedDownloadFixture.innerZip(
            Collections.singletonMap("a.p7s", "x".getBytes()));
    String iv = EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd");
    // .enc е шифрован с 'K', а .enc.key носи 'Z' — както би изглеждало разминаване при доставчика.
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put(
        "4242_OUT.enc.key",
        EvrotrustEncryptedDownloadFixture.encryptedKey(symmetricKey('Z'), pair.getPublic()));
    files.put("4242_OUT.enc.iv", iv.getBytes(StandardCharsets.US_ASCII));
    files.put(
        "4242_OUT.enc", EvrotrustEncryptedDownloadFixture.encFile(inner, symmetricKey('K'), iv));

    QesException refused =
        assertThrows(
            QesException.class,
            () ->
                decryptor.decrypt(
                    EvrotrustEncryptedDownloadFixture.innerZip(files), pair.getPrivate()));
    assertTrue(refused.getMessage().contains("wrong key"), refused.getMessage());
  }

  // ── повредени данни ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Отговор, който не е ZIP: отказ, който посочва документирания формат")
  void aNonZipResponseIsRefused() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    byte[] json = "{\"error\":\"not a zip\"}".getBytes(StandardCharsets.UTF_8);

    QesException refused =
        assertThrows(QesException.class, () -> decryptor.decrypt(json, pair.getPrivate()));
    assertTrue(refused.getMessage().contains("/document/download"), refused.getMessage());
  }

  @Test
  @DisplayName("Липсващ .enc.iv: отказът изброява какво всъщност е имало в архива")
  void aMissingMemberIsRefusedAndNamesWhatWasThere() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put(
        "4242_OUT.enc.key",
        EvrotrustEncryptedDownloadFixture.encryptedKey(symmetricKey('K'), pair.getPublic()));
    files.put("4242_OUT.enc", "ignored".getBytes(StandardCharsets.US_ASCII));

    QesException refused =
        assertThrows(
            QesException.class,
            () ->
                decryptor.decrypt(
                    EvrotrustEncryptedDownloadFixture.innerZip(files), pair.getPrivate()));
    assertTrue(refused.getMessage().contains(".enc.iv"), refused.getMessage());
    assertTrue(refused.getMessage().contains("4242_OUT.enc"), refused.getMessage());
  }

  @Test
  @DisplayName("IV с грешна дължина: отказ с измерената дължина")
  void aWrongLengthIvIsRefused() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    byte[] inner =
        EvrotrustEncryptedDownloadFixture.innerZip(
            Collections.singletonMap("a.p7s", "x".getBytes()));
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put(
        "4242_OUT.enc.key",
        EvrotrustEncryptedDownloadFixture.encryptedKey(symmetricKey('K'), pair.getPublic()));
    files.put("4242_OUT.enc.iv", "too-short".getBytes(StandardCharsets.US_ASCII));
    files.put(
        "4242_OUT.enc",
        EvrotrustEncryptedDownloadFixture.encFile(
            inner, symmetricKey('K'), EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd")));

    QesException refused =
        assertThrows(
            QesException.class,
            () ->
                decryptor.decrypt(
                    EvrotrustEncryptedDownloadFixture.innerZip(files), pair.getPrivate()));
    assertTrue(refused.getMessage().contains("9 bytes"), refused.getMessage());
  }

  @Test
  @DisplayName("Шифротекст, който не е кратен на блока от 32 байта: отказ „повредени данни“")
  void truncatedCiphertextIsRefused() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    String iv = EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd");
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put(
        "4242_OUT.enc.key",
        EvrotrustEncryptedDownloadFixture.encryptedKey(symmetricKey('K'), pair.getPublic()));
    files.put("4242_OUT.enc.iv", iv.getBytes(StandardCharsets.US_ASCII));
    // 16 байта шифротекст — половин блок.
    files.put(
        "4242_OUT.enc",
        java.util.Base64.getEncoder()
            .encodeToString(new byte[16])
            .getBytes(StandardCharsets.US_ASCII));

    QesException refused =
        assertThrows(
            QesException.class,
            () ->
                decryptor.decrypt(
                    EvrotrustEncryptedDownloadFixture.innerZip(files), pair.getPrivate()));
    assertTrue(refused.getMessage().contains("multiple of the"), refused.getMessage());
  }

  @Test
  @DisplayName(".enc с невалиден base64: отказ „повредени данни“, а не изключение от библиотека")
  void invalidBase64InTheEncFileIsRefused() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    String iv = EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd");
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put(
        "4242_OUT.enc.key",
        EvrotrustEncryptedDownloadFixture.encryptedKey(symmetricKey('K'), pair.getPublic()));
    files.put("4242_OUT.enc.iv", iv.getBytes(StandardCharsets.US_ASCII));
    files.put("4242_OUT.enc", "###not-base64###".getBytes(StandardCharsets.US_ASCII));

    QesException refused =
        assertThrows(
            QesException.class,
            () ->
                decryptor.decrypt(
                    EvrotrustEncryptedDownloadFixture.innerZip(files), pair.getPrivate()));
    assertTrue(refused.getMessage().contains("invalid base64"), refused.getMessage());
  }

  // ── ограничения на размера ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Прекалено много записи в архива: отказ преди каквото и да е разшифроване")
  void tooManyZipEntriesAreRefused() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    Map<String, byte[]> files = new LinkedHashMap<>();
    for (int i = 0; i <= EvrotrustDownloadDecryptor.MAX_ZIP_ENTRIES; i++) {
      files.put("file-" + i + ".bin", new byte[] {1});
    }

    QesException refused =
        assertThrows(
            QesException.class,
            () ->
                decryptor.decrypt(
                    EvrotrustEncryptedDownloadFixture.innerZip(files), pair.getPrivate()));
    assertTrue(refused.getMessage().contains("entries"), refused.getMessage());
  }

  @Test
  @DisplayName("„Zip bomb“: силно свиваем запис над тавана се отказва по РАЗАРХИВИРАН размер")
  void aHighlyCompressibleEntryIsRefusedByItsInflatedSize() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    // Нули се свиват до нищо, но разархивирани надхвърлят тавана за един запис.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("bomb.bin"));
      byte[] block = new byte[1024 * 1024];
      for (int i = 0; i < (EvrotrustDownloadDecryptor.MAX_ENTRY_BYTES / block.length) + 2; i++) {
        zip.write(block);
      }
      zip.closeEntry();
    }

    QesException refused =
        assertThrows(
            QesException.class, () -> decryptor.decrypt(out.toByteArray(), pair.getPrivate()));
    assertTrue(refused.getMessage().contains("allowed size"), refused.getMessage());
  }

  @Test
  @DisplayName("Отговор над тавана: отказ още преди архивът да се отваря")
  void anOversizedResponseIsRefusedBeforeUnzipping() throws Exception {
    KeyPair pair = EvrotrustEncryptedDownloadFixture.rsa2048();
    byte[] huge = new byte[(int) EvrotrustDownloadDecryptor.MAX_RESPONSE_BYTES + 1];
    huge[0] = 0x50;
    huge[1] = 0x4B;

    QesException refused =
        assertThrows(QesException.class, () -> decryptor.decrypt(huge, pair.getPrivate()));
    assertTrue(refused.getMessage().contains("exceeds the allowed size"), refused.getMessage());
  }

  // ── само детачен PKCS#7 излиза навън ─────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "От вътрешния ZIP излиза ИМЕННО детачният PKCS#7 — и той се проверява върху заключените"
          + " TXT байтове и сертификата на подписващия")
  void onlyADetachedPkcs7LeavesTheArchiveAndItVerifies() throws Exception {
    TestOnlyQesSigner signer = new TestOnlyQesSigner("Тест Подписващ");
    byte[] detached = signer.detachedPkcs7(LOCKED_TXT);

    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("EMPL2021.TXT.p7s", detached);
    entries.put("readme.txt", "придружаващ файл".getBytes(StandardCharsets.UTF_8));

    byte[] extracted =
        decryptor.detachedPkcs7From(EvrotrustEncryptedDownloadFixture.innerZip(entries));

    assertArrayEquals(detached, extracted);
    // Същата проверка, която пази и USB пътя: този PKCS#7, тези байтове, този сертификат.
    assertTrue(DetachedPkcs7Verifier.verifies(LOCKED_TXT, extracted, signer.certificateDer()));
    // И тя пада, ако байтовете са други.
    assertTrue(
        !DetachedPkcs7Verifier.verifies("друго".getBytes(), extracted, signer.certificateDer()));
  }

  @Test
  @DisplayName("Вътрешен ZIP без детачен PKCS#7: отказ, който изброява какво е намерено")
  void anArchiveWithoutADetachedSignatureIsRefused() throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("result.pdf", "%PDF-1.7 not a signature".getBytes(StandardCharsets.UTF_8));
    entries.put("meta.xml", "<meta/>".getBytes(StandardCharsets.UTF_8));

    QesException refused =
        assertThrows(
            QesException.class,
            () -> decryptor.detachedPkcs7From(EvrotrustEncryptedDownloadFixture.innerZip(entries)));
    assertTrue(refused.getMessage().contains("result.pdf"), refused.getMessage());
    assertTrue(refused.getMessage().contains("detached PKCS#7"), refused.getMessage());
  }

  @Test
  @DisplayName("Два детачни подписа: отказ — не се гадае кой е на този файл")
  void anAmbiguousArchiveIsRefused() throws Exception {
    TestOnlyQesSigner signer = new TestOnlyQesSigner("Тест Подписващ");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("one.p7s", signer.detachedPkcs7(LOCKED_TXT));
    entries.put("two.p7s", signer.detachedPkcs7("друг файл".getBytes(StandardCharsets.UTF_8)));

    QesException refused =
        assertThrows(
            QesException.class,
            () -> decryptor.detachedPkcs7From(EvrotrustEncryptedDownloadFixture.innerZip(entries)));
    assertTrue(refused.getMessage().contains("more than one"), refused.getMessage());
  }
}
