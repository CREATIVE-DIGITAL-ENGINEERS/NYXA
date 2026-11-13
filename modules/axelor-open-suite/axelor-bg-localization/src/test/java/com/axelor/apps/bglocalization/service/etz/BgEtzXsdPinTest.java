package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СХЕМИТЕ В РЕПОТО СА ТОЧНО ТЕЗИ, КОИТО {@link BgEtzXsd} ТВЪРДИ, ЧЕ СА.
 *
 * <p>Копие без отпечатък е копие, което може да бъде подменено — от някого, който сваля „по-нова
 * версия“, или от неуспешно сливане — и подмяната НЕ БИ ПАДНАЛА никъде: емитерът щеше да продължи
 * да произвежда файлове, валидаторът да ги приема, а зеленото да значи „валидно срещу неизвестно
 * каква схема“.
 *
 * <p><b>⚠️ И ТОЗИ ТЕСТ ПАЗИ И ОТ НЕЩО, КОЕТО НЕ Е ПОДМЯНА.</b> Схемите са с CRLF; кореновият {@code
 * .gitattributes} носи {@code * text=auto eol=lf}. Без изключението в {@code
 * schemas/.gitattributes} git би ги записал с LF и отпечатъкът би паднал — но чак при СЛЕДВАЩОТО
 * изтегляне, тоест в CI и при следващия човек, а не при този, който ги е сложил.
 */
class BgEtzXsdPinTest {

  private static byte[] bytesOf(BgEtzXsd.Schema schema) throws Exception {
    try (InputStream in = BgEtzXsd.class.getResourceAsStream(schema.getResource())) {
      assertNotNull(in, "схемата " + schema.getResource() + " не е в classpath-а");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder sb = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  @Test
  @DisplayName("И двете схеми съвпадат по размер и отпечатък със закованите")
  void schemasAreExactlyThePinnedOnes() throws Exception {
    for (BgEtzXsd.Schema schema : BgEtzXsd.Schema.values()) {
      byte[] bytes = bytesOf(schema);
      assertEquals(
          schema.getByteLength(),
          bytes.length,
          "размерът на " + schema.getResource() + " е различен");
      assertEquals(
          schema.getSha256(),
          sha256(bytes),
          "схемата "
              + schema.getResource()
              + " НЕ е тази, която BgEtzXsd описва — сверете версията и датата, преди да смените"
              + " отпечатъка");
    }
  }

  @Test
  @DisplayName("Краят на реда е CRLF — нормализацията би сменила отпечатъка мълчаливо")
  void schemasKeepCrlf() throws Exception {
    for (BgEtzXsd.Schema schema : BgEtzXsd.Schema.values()) {
      String text = new String(bytesOf(schema), BgEtzXmlWriter.CHARSET);
      assertTrue(
          text.contains("\r\n"),
          schema.getResource()
              + " е без CRLF — най-вероятно `* text=auto eol=lf` го е нормализирал, тоест"
              + " изключението в schemas/.gitattributes не е сработило");
    }
  }

  @Test
  @DisplayName("Схемите обявяват WINDOWS-1251 и описват обявения корен")
  void schemasDeclareEncodingAndRoot() throws Exception {
    for (BgEtzXsd.Schema schema : BgEtzXsd.Schema.values()) {
      String text = new String(bytesOf(schema), BgEtzXmlWriter.CHARSET);
      assertTrue(
          text.startsWith("<?xml version=\"1.0\" encoding=\"WINDOWS-1251\"?>"), schema.name());
      assertTrue(
          text.contains("<xs:element name=\"" + schema.getRootElement() + "\">"),
          schema.getRootElement() + " не е коренът на " + schema.getResource());
    }
  }

  @Test
  @DisplayName("Всяко събитие сочи своята схема")
  void eventMapsToSchema() {
    assertEquals(BgEtzXsd.Schema.EMPLOY, BgEtzXsd.forEvent(BgEtzEventKind.CONCLUSION));
    assertEquals(BgEtzXsd.Schema.EMPLOY, BgEtzXsd.forEvent(BgEtzEventKind.AMENDMENT));
    assertEquals(BgEtzXsd.Schema.EMPLOY, BgEtzXsd.forEvent(BgEtzEventKind.TERMINATION));
    assertEquals(
        BgEtzXsd.Schema.EMPLOYER_CHANGE, BgEtzXsd.forEvent(BgEtzEventKind.EMPLOYER_CHANGE));
  }
}
