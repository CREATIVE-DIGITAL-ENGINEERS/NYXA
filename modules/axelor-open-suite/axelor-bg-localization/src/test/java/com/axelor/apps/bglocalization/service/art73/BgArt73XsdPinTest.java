package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Xsd;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СХЕМАТА Е ТОЧНО ТАЗИ, КОЯТО {@link BgArt73Xsd} ТВЪРДИ.
 *
 * <p><b>⚠️ ЗАЩО ОТПЕЧАТЪК, А НЕ ДОВЕРИЕ.</b> Замени ли някой файла с по-нова версия, без да пипне
 * закрепката, целият слой започва да валидира срещу ДРУГА схема — мълчаливо и с еднакво зелени
 * тестове. Тук това пада с назована причина.
 *
 * <p><b>⚠️ ЧЕТЕ СЕ ОТ CLASSPATH-А, защото точно оттам го чете и валидаторът.</b> Файлът на диска и
 * файлът в classpath-а са едно и също днес, но копирането минава през {@code processResources} —
 * проверка върху източника не доказва нищо за това, което ще бъде прочетено по време на работа.
 *
 * <p>⚠️ Схемата е публикувана в CP1251 с CRLF и се пази БАЙТ ПО БАЙТ: {@code
 * schemas/.gitattributes} носи {@code *.xsd -text}, защото кореновият {@code * text=auto eol=lf}
 * инак сменя дължината и отпечатъка — и тестът минава на машината, на която файлът е сложен, а пада
 * при всяко следващо изтегляне.
 */
class BgArt73XsdPinTest {

  private static byte[] schemaBytes() throws Exception {
    try (InputStream in = BgArt73Xsd.class.getResourceAsStream(BgArt73Xsd.RESOURCE)) {
      assertNotNull(in, "схемата " + BgArt73Xsd.RESOURCE + " не е в classpath-а");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  @Test
  @DisplayName("Дължината и SHA-256 съвпадат със закрепените")
  void отпечатъкът_съвпада() throws Exception {
    byte[] bytes = schemaBytes();
    assertEquals(BgArt73Xsd.SCHEMA_BYTE_LENGTH, bytes.length, "дължина на схемата");

    StringBuilder hex = new StringBuilder(64);
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
      hex.append(String.format("%02x", b));
    }
    assertEquals(BgArt73Xsd.SCHEMA_SHA256, hex.toString(), "SHA-256 на схемата");
  }

  @Test
  @DisplayName("Схемата описва dec731 и е публикувана в CP1251 с CRLF")
  void схемата_е_очакваната() throws Exception {
    String text = new String(schemaBytes(), Charset.forName(BgArt73Xsd.ENCODING));
    assertTrue(text.contains("encoding=\"WINDOWS-1251\""), "декларация на схемата");
    assertTrue(
        text.contains("<xs:element name=\"" + BgArt73Xsd.ROOT_ELEMENT + "\">"), "коренов елемент");
    // ⚠️ CRLF: ако някой ден изчезне, `*.xsd -text` е бил заобиколен и отпечатъкът горе вече лъже.
    assertTrue(text.contains("\r\n"), "схемата е публикувана с CRLF и се пази байт по байт");
  }

  @Test
  @DisplayName("Описанието назовава заповедта — доклад без отваряне на кода")
  void описанието_назовава_заповедта() {
    assertTrue(BgArt73Xsd.describe().contains("З-ЦУ-1944"), BgArt73Xsd.describe());
    assertTrue(BgArt73Xsd.describe().contains("SPR73_1.xml"), BgArt73Xsd.describe());
  }
}
