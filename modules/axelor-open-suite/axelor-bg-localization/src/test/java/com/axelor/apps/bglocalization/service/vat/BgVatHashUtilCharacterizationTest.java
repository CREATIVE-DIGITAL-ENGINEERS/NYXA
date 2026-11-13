package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CHARACTERIZATION TEST — заковава отпечатъка на ДДС-пакета ПРЕДИ да мине през общото ядро.
 *
 * <p><b>Защо е нужен, при положение че {@code BgVatPackageBuilderTest} вече проверява sha256.</b>
 * Проверява го СРЕЩУ САМИЯ {@code BgVatHashUtil} (редове 81, 167 и 187): „хешът на файла е равен на
 * хеша на файла“. Такъв свидетел е верен при всяка реализация, включително при сменена — тоест той
 * пази съответствието между двете полета, а НЕ алгоритъма. Смяна на алгоритъма щеше да мине зелено
 * през целия пакет и да се види чак когато някой сравни съхранен отпечатък със заварен файл.
 *
 * <p>Тук стойностите са ВЪНШНИ — публикуваните вектори на SHA-256 — тоест могат да се сверят, без
 * този код да се пуска.
 */
class BgVatHashUtilCharacterizationTest {

  @Test
  @DisplayName("„abc“ — публикуваният вектор, малки шестнайсетични, без разделители")
  void abcHashesToThePublishedVector() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        BgVatHashUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("Празен файл — известният вектор")
  void emptyFileHashesToTheKnownVector() {
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        BgVatHashUtil.sha256Hex(new byte[0]));
  }

  @Test
  @DisplayName("⚠️ null се хешира като ПРАЗЕН ФАЙЛ — това е поведението на ДДС-стека и то остава")
  void nullIsHashedAsTheEmptyFile() {
    // ⚠️ Различно от НАП пътя, който хвърля (NapArtifactHashCharacterizationTest). Двете НЕ се
    // сливат: сливането щеше да смени мълчаливо едната страна. Затова разликата е тук, на един ред.
    assertEquals(BgVatHashUtil.sha256Hex(new byte[0]), BgVatHashUtil.sha256Hex(null));
  }

  @Test
  @DisplayName("Един байт разлика мени отпечатъка")
  void oneByteChangesTheDigest() {
    org.junit.jupiter.api.Assertions.assertNotEquals(
        BgVatHashUtil.sha256Hex("DEKLAR.TXT".getBytes(StandardCharsets.UTF_8)),
        BgVatHashUtil.sha256Hex("DEKLAR.TXt".getBytes(StandardCharsets.UTF_8)));
  }
}
