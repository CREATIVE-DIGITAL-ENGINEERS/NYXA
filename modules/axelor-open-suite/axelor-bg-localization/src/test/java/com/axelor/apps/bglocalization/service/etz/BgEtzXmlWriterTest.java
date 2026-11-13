package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПИСАЧЪТ ПИШЕ WINDOWS-1251, И ТОВА СЕ МЕРИ В БАЙТОВЕ, А НЕ СЕ ЧЕТЕ ОТ ДЕКЛАРАЦИЯТА.
 *
 * <p>Свидетелят е нужен, защото грешката тук е НЕВИДИМА в низ: {@code toXml()} връща едно и също
 * съдържание независимо от кодировката, а разминаването излиза чак в подадения файл. НАП го
 * проверява с два отделни кода за грешка — за декларацията ({@code API_XML_ENCODING_HEADER}) и за
 * байтовете ({@code API_XML_ENCODING_FILE}).
 */
class BgEtzXmlWriterTest {

  @Test
  @DisplayName("Декларацията обявява WINDOWS-1251, а не UTF-8 на обвивката")
  void headerDeclaresWindows1251() {
    String xml = new BgEtzXmlWriter().toXml();
    assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"WINDOWS-1251\"?>"), xml);
    assertFalse(xml.toLowerCase().contains("utf-8"), "декларацията не бива да казва UTF-8");
  }

  @Test
  @DisplayName("Кирилицата излиза в еднобайтов CP1251, а не в двубайтов UTF-8")
  void cyrillicIsSingleByteCp1251() {
    BgEtzXmlWriter writer = new BgEtzXmlWriter();
    writer.open("X");
    writer.leaf("Y", "Ана");
    writer.close("X");

    byte[] bytes = writer.toBytes();

    // „Ана“ е 3 знака: в CP1251 това са 3 байта, в UTF-8 — 6. Мери се разликата, не текстът.
    byte[] expected = "Ана".getBytes(BgEtzXmlWriter.CHARSET);
    assertEquals(3, expected.length, "контролата на теста: CP1251 дава по един байт на знак");
    assertEquals(6, "Ана".getBytes(StandardCharsets.UTF_8).length, "контролата: UTF-8 дава 6");

    assertTrue(indexOf(bytes, expected) > 0, "байтовете на „Ана“ в CP1251 липсват във файла");
    assertEquals(
        -1,
        indexOf(bytes, "Ана".getBytes(StandardCharsets.UTF_8)),
        "файлът съдържа UTF-8 байтове — писачът е прекодирал грешно");
  }

  @Test
  @DisplayName("Непредставим знак ХВЪРЛЯ, вместо да стане въпросителна")
  void unmappableCharacterIsRefused() {
    BgEtzXmlWriter writer = new BgEtzXmlWriter();
    writer.leaf("Name", "Nicolae Ceaușescu");

    BgEtzXmlWriter.BgEtzEncodingException e =
        assertThrows(BgEtzXmlWriter.BgEtzEncodingException.class, writer::toBytes);

    assertTrue(
        e.getMessage().contains("U+0219"), "отказът трябва да НАЗОВАВА знака: " + e.getMessage());
  }

  @Test
  @DisplayName("Мълчаливата замяна е изключена — „?“ не се появява от само себе си")
  void noSilentReplacement() {
    // Контролният опит: същият знак през обикновеното кодиране на Java става „?“ и минава
    // мълчаливо.
    // Тоест защитата не е теоретична — подразбирането наистина би осакатило името.
    byte[] lenient = "ș".getBytes(BgEtzXmlWriter.CHARSET);
    assertArrayEquals(
        new byte[] {'?'}, lenient, "контролата на теста: String.getBytes замества тихо");

    assertThrows(BgEtzXmlWriter.BgEtzEncodingException.class, () -> BgEtzXmlWriter.encode("ș"));
  }

  @Test
  @DisplayName("Петте знака се екранират, а управляващите се махат")
  void escapingAndControlCharacters() {
    BgEtzXmlWriter writer = new BgEtzXmlWriter();
    writer.leaf("A", "<&>\"'");
    // Знакът U+0001 се строи тук, вместо да стои в изходния файл: суров управляващ байт в .java
    // е точно това, което редакторите и инструментите крият.
    writer.leaf("B", "ок" + ((char) 1) + "ей");

    String xml = writer.toXml();
    assertTrue(xml.contains("<A>&lt;&amp;&gt;&quot;&apos;</A>"), xml);
    assertTrue(xml.contains("<B>окей</B>"), "управляващият знак трябваше да отпадне: " + xml);
  }

  @Test
  @DisplayName("Празната стойност е празен елемент с изричен затварящ таг, не самозатварящ")
  void emptyLeafHasExplicitClosingTag() {
    BgEtzXmlWriter writer = new BgEtzXmlWriter();
    writer.leaf("Empty", null);
    assertTrue(writer.toXml().contains("<Empty></Empty>"), writer.toXml());
    assertFalse(writer.toXml().contains("<Empty/>"), writer.toXml());
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
