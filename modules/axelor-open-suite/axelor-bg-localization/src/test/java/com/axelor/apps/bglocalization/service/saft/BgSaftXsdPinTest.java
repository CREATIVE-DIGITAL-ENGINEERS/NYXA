package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СХЕМАТА Е ТОЧНО ТАЗИ, КОЯТО {@link BgSaftXsd} ТВЪРДИ.
 *
 * <p><b>⚠️ ЗАЩО ОТПЕЧАТЪК, А НЕ ДОВЕРИЕ.</b> Замени ли някой файла с по-нова версия, без да пипне
 * закрепката, целият слой започва да валидира срещу ДРУГА схема — мълчаливо и с еднакво зелени
 * тестове. При SAF-T това не е хипотеза: схемата е сменяна ТРИ ПЪТИ с изменителна заповед (1.0 →
 * 1.0.1 → 1.0.2 → 1.0.3), без ДОПК да се пипа. Тук подмяната пада с назована причина — за всеки ред
 * от {@link BgSaftXsd#VERSIONS}.
 *
 * <p><b>⚠️ ЧЕТЕ СЕ ОТ CLASSPATH-А, защото точно оттам го чете и валидаторът.</b> Файлът на диска и
 * файлът в classpath-а са едно и също днес, но копирането минава през {@code processResources} —
 * проверка върху източника не доказва нищо за това, което ще бъде прочетено по време на работа.
 *
 * <p>⚠️ Схемата е публикувана в UTF-8 с CRLF и се пази БАЙТ ПО БАЙТ: {@code schemas/.gitattributes}
 * носи {@code *.xsd -text}, защото кореновият {@code * text=auto eol=lf} инак сменя дължината и
 * отпечатъка — и тестът минава на машината, на която файлът е сложен, а пада при всяко следващо
 * изтегляне.
 */
class BgSaftXsdPinTest {

  private static byte[] schemaBytes(BgSaftXsd.Version v) throws Exception {
    try (InputStream in = BgSaftXsd.class.getResourceAsStream(v.getResource())) {
      assertNotNull(in, "схемата " + v.getResource() + " не е в classpath-а");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    StringBuilder hex = new StringBuilder(64);
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  @Test
  @DisplayName("Дължината и SHA-256 на всяка версия съвпадат със закрепените")
  void отпечатъкът_съвпада() throws Exception {
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      byte[] bytes = schemaBytes(v);
      assertEquals(v.getByteLength(), bytes.length, "дължина на схемата " + v.getSchemaVersion());
      assertEquals(v.getSha256(), sha256Hex(bytes), "SHA-256 на схемата " + v.getSchemaVersion());
    }
  }

  @Test
  @DisplayName("Закрепените отпечатъци — сверени с пакетите на НАП (SHA256SUMS.txt)")
  void отпечатъците_са_на_нап() {
    assertEquals(
        "2bd5fb13072c92b221bee2bea46c4f71c1619c38977e300bc7ac1e75c439cfc7",
        BgSaftXsd.V1_0_2.getSha256());
    assertEquals(251559L, BgSaftXsd.V1_0_2.getByteLength());
    assertEquals(
        "a152c5d02f359d7d1c132121d82db63492eed23cf0475b85f27069a2074d188f",
        BgSaftXsd.V1_0_3.getSha256());
    assertEquals(232985L, BgSaftXsd.V1_0_3.getByteLength());
  }

  /**
   * ⚠️ ТЕСТ 1 КАЗВА „ДНЕС СЪВПАДА“, А ТОЗИ — „ПОДМЯНАТА СЕ ВИЖДА“. Двете са различни твърдения.
   * Пин, който сравнява със себе си, минава и при закръглена наум дължина; тук се променя ЕДИН байт
   * и се иска отпечатъкът ДА СЕ РАЗМИНЕ. Без този тест „пинът пази“ е предположение.
   */
  @Test
  @DisplayName("Един променен байт разминава отпечатъка — пинът наистина пази")
  void подменената_схема_се_хваща() throws Exception {
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      byte[] original = schemaBytes(v);
      assertEquals(v.getSha256(), sha256Hex(original), "изходно състояние " + v.getSchemaVersion());

      byte[] tampered = original.clone();
      // Последният байт, не първият: промяна в началото би могла да счупи и самото четене, тоест
      // тестът щеше да минава по друга причина — „не се парсва“ вместо „не е същият файл“.
      tampered[tampered.length - 1] = (byte) (tampered[tampered.length - 1] ^ 0x01);

      assertEquals(original.length, tampered.length, "дължината нарочно остава същата");
      assertNotEquals(
          v.getSha256(),
          sha256Hex(tampered),
          "един променен байт ТРЯБВА да размине отпечатъка — инак пинът не пази нищо");
    }
  }

  @Test
  @DisplayName("Всяка схема описва AuditFile в пространството на НАП и е с CRLF")
  void схемата_е_очакваната() throws Exception {
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      String text = new String(schemaBytes(v), Charset.forName(BgSaftXsd.ENCODING));
      assertTrue(text.contains("encoding=\"UTF-8\""), "декларация на схемата");
      assertTrue(
          text.contains("<xs:element name=\"" + BgSaftXsd.ROOT_ELEMENT + "\">"), "коренов елемент");
      assertTrue(
          text.contains("targetNamespace=\"" + BgSaftXsd.NAMESPACE + "\""),
          "целевото пространство — „dxxxx“ е ДОСЛОВНО, не шаблон");
      // ⚠️ CRLF: ако някой ден изчезне, `*.xsd -text` е бил заобиколен и отпечатъкът горе лъже.
      assertTrue(text.contains("\r\n"), "схемата е публикувана с CRLF и се пази байт по байт");
    }
  }

  /**
   * ⚠️ ВТОРО, НЕЗАВИСИМО ИЗМЕРВАНЕ НА СЪЩИТЕ БАЙТОВЕ. Тестът горе смята отпечатъка с {@code
   * MessageDigest} тук, в теста; {@code RegulatoryFile} го смята през {@code RegulatoryDigest} в
   * общия модул. Съвпаднат ли, разминаване в регистър, разделител или главни букви между двете
   * реализации е изключено — а точно такова разминаване е невидимо, докато някой не сравни съхранен
   * отпечатък с файл, който НАП вече държи.
   */
  @Test
  @DisplayName("Файлът за регистрация носи същия отпечатък и публикуваното име")
  void файлът_за_регистрация_е_закованият() {
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      var file = BgSaftSchemaRegistrar.schemaFile(v);
      assertEquals(v.getSha256(), file.getSha256(), "отпечатък през RegulatoryDigest");
      assertEquals(v.getByteLength(), file.getByteLength(), "дължина");
      assertEquals(
          "BG_SAFT_Schema_V_" + v.getSchemaVersion() + ".xsd",
          file.getFileName(),
          "името, както НАП го публикува");
    }
  }

  @Test
  @DisplayName("Описанието назовава заповедите — доклад без отваряне на кода")
  void описанието_назовава_заповедите() {
    String described = BgSaftXsd.V1_0_2.describe();
    assertTrue(described.contains("З-ЦУ-30-1085"), described);
    assertTrue(described.contains("З-ЦУ-30-1247"), described);
    assertTrue(described.contains("З-ЦУ-30-359"), described);
    assertTrue(described.contains("01.04.2026"), described);
  }
}
