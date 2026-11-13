package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Para6Xsd;
import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Xsd;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СХЕМАТА НА АЛ. 6 Е ТОЧНО ТАЗИ, КОЯТО {@link BgArt73Para6Xsd} ТВЪРДИ.
 *
 * <p>Огледално на {@code BgArt73XsdPinTest} за ал. 1, със същия довод: замени ли някой файла, без
 * да пипне закрепката, слоят валидира срещу ДРУГА схема — мълчаливо и с еднакво зелени тестове.
 *
 * <p><b>⚠️ И ОЩЕ ЕДНА ПРОВЕРКА, КОЯТО АЛ. 1 НЕ МОЖЕШЕ ДА ИМА: ДВЕТЕ СХЕМИ СА РАЗЛИЧНИ.</b> Двата
 * пин класа са близнаци и копирането на единия в другия е точно това, което човек прави. Останала
 * непроменена стойност би насочила ал. 6 към {@code dec731} — файл, който минава чужда схема и НАП
 * отказва.
 */
class BgArt73Para6XsdPinTest {

  private static byte[] schemaBytes() throws Exception {
    try (InputStream in = BgArt73Para6Xsd.class.getResourceAsStream(BgArt73Para6Xsd.RESOURCE)) {
      assertNotNull(in, "схемата " + BgArt73Para6Xsd.RESOURCE + " не е в classpath-а");
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
    assertEquals(BgArt73Para6Xsd.SCHEMA_BYTE_LENGTH, bytes.length, "дължина на схемата");

    StringBuilder hex = new StringBuilder(64);
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
      hex.append(String.format("%02x", b));
    }
    assertEquals(BgArt73Para6Xsd.SCHEMA_SHA256, hex.toString(), "SHA-256 на схемата");
  }

  @Test
  @DisplayName("Схемата описва dec736 и е публикувана в CP1251 с CRLF")
  void схемата_е_очакваната() throws Exception {
    String text = new String(schemaBytes(), Charset.forName(BgArt73Para6Xsd.ENCODING));
    assertTrue(text.contains("encoding=\"WINDOWS-1251\""), "декларация на схемата");
    assertTrue(
        text.contains("<xs:element name=\"" + BgArt73Para6Xsd.ROOT_ELEMENT + "\">"),
        "коренов елемент");
    // ⚠️ CRLF: изчезне ли, `*.xsd -text` е бил заобиколен и отпечатъкът горе вече лъже.
    assertTrue(text.contains("\r\n"), "схемата е публикувана с CRLF и се пази байт по байт");
  }

  @Test
  @DisplayName("⚠️ Схемата на ал. 6 НЕ Е схемата на ал. 1 — нито едно поле не е преписано")
  void двете_схеми_са_различни() {
    assertNotEquals(BgArt73Xsd.RESOURCE, BgArt73Para6Xsd.RESOURCE, "път до схемата");
    assertNotEquals(BgArt73Xsd.SCHEMA_SHA256, BgArt73Para6Xsd.SCHEMA_SHA256, "отпечатък");
    assertNotEquals(BgArt73Xsd.SCHEMA_BYTE_LENGTH, BgArt73Para6Xsd.SCHEMA_BYTE_LENGTH, "дължина");
    assertNotEquals(BgArt73Xsd.ROOT_ELEMENT, BgArt73Para6Xsd.ROOT_ELEMENT, "коренов елемент");
    assertNotEquals(BgArt73Xsd.FILE_NAME, BgArt73Para6Xsd.FILE_NAME, "име на файла");
    assertNotEquals(BgArt73Xsd.ORDER, BgArt73Para6Xsd.ORDER, "заповед");
  }

  @Test
  @DisplayName("Описанието назовава заповедта — доклад без отваряне на кода")
  void описанието_назовава_заповедта() {
    assertTrue(BgArt73Para6Xsd.describe().contains("З-ЦУ-1877"), BgArt73Para6Xsd.describe());
    assertTrue(BgArt73Para6Xsd.describe().contains("SPR73_6.xml"), BgArt73Para6Xsd.describe());
  }
}
