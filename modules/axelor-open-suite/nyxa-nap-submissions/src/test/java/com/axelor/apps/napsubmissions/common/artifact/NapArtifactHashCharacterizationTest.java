package com.axelor.apps.napsubmissions.common.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CHARACTERIZATION TEST — pins the fingerprint НАП artifacts carry <b>today</b>, before the
 * institution core is extracted underneath them.
 *
 * <p><b>Why it is written first and separately.</b> {@link NapArtifactService} had no test at all,
 * and it is the single place where a Д1/Д6 payload becomes an immutable {@code NapArtifact}. Any
 * refactor under it would have been unfalsifiable: the НАП export would still compile, still run,
 * still attach a file — and a changed digest would only surface at НАП, on a submission nobody
 * could take back. These expectations are the control experiment; they were green before the
 * extraction and must stay green after it.
 *
 * <p><b>What it pins.</b> Lower-case hex, no separators, no prefix, 64 characters, over the exact
 * bytes — including the empty input, which is the one case where a "helpful" null guard would
 * silently change the answer. The vectors are the published SHA-256 test vectors, so a reader can
 * check them without running this file.
 *
 * <p><b>What it does not pin.</b> Nothing about MetaFile, persistence or the repositories: the
 * constructor is called with nulls on purpose, because the digest must not depend on them.
 */
class NapArtifactHashCharacterizationTest {

  /** No repositories, no MetaFiles: the digest is a pure function and this proves it. */
  private static final NapArtifactService SERVICE = new NapArtifactService(null, null, null);

  private static String hash(String text) {
    return SERVICE.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("Празен вход — известният вектор, а не празен низ")
  void emptyInputHashesToTheKnownVector() {
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        SERVICE.sha256Hex(new byte[0]));
  }

  @Test
  @DisplayName("„abc“ — публикуваният вектор на SHA-256")
  void abcHashesToThePublishedVector() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash("abc"));
  }

  @Test
  @DisplayName("Кирилицата се хешира като UTF-8 байтове, не като знаци")
  void cyrillicIsHashedAsBytes() {
    // 4 знака, 8 байта в UTF-8 — стойността зависи от БАЙТОВЕТЕ, тоест от кодировката на входа.
    assertEquals(hash("тест"), SERVICE.sha256Hex("тест".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("64 знака, само малки шестнайсетични — форматът, който влиза в базата")
  void formatIsSixtyFourLowerCaseHexDigits() {
    String digest = hash("EMPL2021.TXT");
    assertEquals(64, digest.length());
    assertEquals(digest.toLowerCase(), digest, "главни букви биха сменили съхранения отпечатък");
    for (char c : digest.toCharArray()) {
      if (Character.digit(c, 16) < 0) {
        throw new AssertionError("недопустим знак в отпечатъка: " + c);
      }
    }
  }

  @Test
  @DisplayName("Един байт разлика мени отпечатъка — иначе той не пази нищо")
  void oneByteChangesTheDigest() {
    org.junit.jupiter.api.Assertions.assertNotEquals(hash("EMPL2021.TXT"), hash("EMPL2021.TXt"));
  }

  @Test
  @DisplayName("⚠️ null НЕ се преглъща — НАП пътят никога не хешира липсващи байтове")
  void nullBytesAreNotSwallowed() {
    // Заковано нарочно: BgVatHashUtil ЗАМЕНЯ null с празен масив, тоест дава отпечатъка на празния
    // файл за липсващо съдържание. Тук поведението е обратното и остава такова — сливането на двете
    // би било мълчалива смяна на едната страна.
    assertThrows(NullPointerException.class, () -> SERVICE.sha256Hex(null));
  }
}
