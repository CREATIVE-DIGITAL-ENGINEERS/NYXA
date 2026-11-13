package com.axelor.apps.napsubmissions.common.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared core's promise: what is stored and what is fingerprinted cannot come apart.
 *
 * <p>Every assertion here is about the guarantee both institutions rely on. The НАП path relied on
 * it by convention ({@code finalized = true} plus a javadoc); this pins it as behaviour.
 */
class RegulatoryFileTest {

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static RegulatoryFile file(Institution institution, String name, String content) {
    return new RegulatoryFile(institution, name, "text/xml", bytes(content));
  }

  @Test
  @DisplayName("Отпечатъкът е на СЪДЪРЖАНИЕТО и съвпада с ядрото")
  void digestMatchesTheSharedImplementation() {
    RegulatoryFile f = file(Institution.NOI, "Pril9_03092026_1.xml", "abc");
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", f.getSha256());
    assertEquals(RegulatoryDigest.sha256Hex(bytes("abc")), f.getSha256());
  }

  @Test
  @DisplayName("⚠️ Подаденият масив се КОПИРА — промяна след създаването не мърда отпечатъка")
  void mutatingTheCallersArrayDoesNotChangeTheFile() {
    byte[] payload = bytes("abc");
    RegulatoryFile f = new RegulatoryFile(Institution.NAP, "EMPL2021.TXT", "text/plain", payload);

    payload[0] = (byte) 'X'; // подателят продължава да държи масива и го пипа

    assertArrayEquals(bytes("abc"), f.getBytes(), "съдържанието се промени под отпечатъка");
    assertEquals(RegulatoryDigest.sha256Hex(bytes("abc")), f.getSha256());
  }

  @Test
  @DisplayName("⚠️ Върнатият масив също е копие — четящият не може да пренапише файла")
  void mutatingTheReturnedArrayDoesNotChangeTheFile() {
    RegulatoryFile f = file(Institution.NOI, "Pril9_03092026_1.xml", "abc");

    byte[] handed = f.getBytes();
    handed[0] = (byte) 'X';

    assertArrayEquals(bytes("abc"), f.getBytes());
  }

  @Test
  @DisplayName("Дължината е на байтовете, не на знаците")
  void lengthIsInBytes() {
    assertEquals(8, file(Institution.NOI, "f.xml", "тест").getByteLength());
  }

  @Test
  @DisplayName("Институцията е задължителна — ядрото няма подразбираща се страна")
  void institutionIsRequired() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RegulatoryFile(null, "f.xml", "text/xml", bytes("x")));
  }

  @Test
  @DisplayName("⚠️ Липсващи байтове НЕ са празен файл")
  void missingBytesAreNotAnEmptyFile() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RegulatoryFile(Institution.NOI, "f.xml", "text/xml", null));
  }

  @Test
  @DisplayName("Името е задължително — файл без име не може да се подаде")
  void fileNameIsRequired() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RegulatoryFile(Institution.NOI, "   ", "text/xml", bytes("x")));
  }

  @Test
  @DisplayName("Дискриминаторът: кодовете са стабилни, а непознат/празен НЕ става НАП")
  void institutionCodesAreStableAndBlankIsNotNap() {
    assertEquals("nap", Institution.NAP.getCode());
    assertEquals("noi", Institution.NOI.getCode());
    assertEquals(Institution.NOI, Institution.of("noi"));
    assertNull(Institution.of(null), "празно значи „не е казано“, не „НАП“");
    assertNull(Institution.of("nra"));
  }

  @Test
  @DisplayName("Две институции, едно съдържание — различни файлове, ЕДИН отпечатък")
  void institutionDoesNotEnterTheDigest() {
    RegulatoryFile nap = file(Institution.NAP, "a.xml", "abc");
    RegulatoryFile noi = file(Institution.NOI, "a.xml", "abc");
    // Отпечатъкът е на СЪДЪРЖАНИЕТО: институцията е метаданна на реда, не част от подаваното.
    assertEquals(nap.getSha256(), noi.getSha256());
    assertNotEquals(nap.getInstitution(), noi.getInstitution());
  }
}
