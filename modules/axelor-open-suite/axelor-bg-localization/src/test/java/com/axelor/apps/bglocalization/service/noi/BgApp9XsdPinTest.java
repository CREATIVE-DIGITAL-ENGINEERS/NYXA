package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СХЕМАТА В РЕПОТО Е ТОЧНО ТАЗИ, КОЯТО {@link BgApp9Xsd} ТВЪРДИ, ЧЕ Е.
 *
 * <p><b>Защо съществува.</b> Валидацията е офлайн, срещу копие. Копие без отпечатък е копие, което
 * може да бъде подменено — от някого, който сваля „по-нова версия“, или от неуспешно сливане — и
 * подмяната НЕ БИ ПАДНАЛА никъде: емитерът щеше да продължи да произвежда файлове, валидаторът да
 * ги приема, а зеленото на билда щеше да значи „валидно срещу неизвестно каква схема“.
 *
 * <p>Затова версията, датата и отпечатъкът са в код, а този свидетел ги сверява с файла. Подмяна на
 * схемата ЗАДЪЛЖИТЕЛНО минава през промяна и на двете, тоест през човек, който е погледнал какво се
 * е сменило.
 */
class BgApp9XsdPinTest {

  private static byte[] schemaBytes() throws Exception {
    try (InputStream in = BgApp9Xsd.class.getResourceAsStream(BgApp9Xsd.RESOURCE)) {
      assertNotNull(in, "схемата " + BgApp9Xsd.RESOURCE + " не е в classpath-а");
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
  @DisplayName("Отпечатъкът на Pril9.xsd съвпада със закования — подмяна не минава мълчаливо")
  void schemaIsExactlyThePinnedOne() throws Exception {
    byte[] bytes = schemaBytes();
    assertEquals(BgApp9Xsd.SCHEMA_BYTE_LENGTH, bytes.length, "размерът на схемата е различен");

    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(bytes);
    StringBuilder sb = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    assertEquals(
        BgApp9Xsd.SCHEMA_SHA256,
        sb.toString(),
        "схемата в repo-то НЕ е тази, която BgApp9Xsd описва — сверете версията и датата, преди"
            + " да смените отпечатъка");
  }

  @Test
  @DisplayName("Схемата описва BPril9 и е свалена от адреса, който константата сочи")
  void schemaDescribesTheExpectedRoot() throws Exception {
    String text = new String(schemaBytes(), StandardCharsets.UTF_8);
    assertTrue(
        text.contains("<xsd:element name=\"" + BgApp9Xsd.ROOT_ELEMENT + "\">"),
        "кореновият елемент не е " + BgApp9Xsd.ROOT_ELEMENT);
    assertTrue(BgApp9Xsd.PACKAGE_URL.startsWith("https://www.nssi.bg/"), "адресът не е на НОИ");
    assertTrue(BgApp9Xsd.SOURCE_URL.contains("razrabotchitsi-na-software"));
  }

  @Test
  @DisplayName("⚠️ Схемата е ПО-СТАРА от Наредбата — разминаването е обявено, не скрито")
  void schemaIsOlderThanTheOrdinanceAndSaysSo() {
    // Това не е „да проверим, че низовете са низове“. Свидетелят закрепва ФАКТА, че трите
    // първоизточника са от три различни години, за да не се появи някой ден код, който твърди, че
    // схемата покрива ДВ бр. 115/2025. Тя не го покрива — тя е последната ПУБЛИКУВАНА.
    assertEquals("2019-06-28", BgApp9Xsd.SCHEMA_DATE);
    assertEquals("2022-08-05", BgApp9Xsd.PACKAGE_DATE);
    assertEquals("2024-03-07", BgApp9Xsd.CONTROL_SPEC_DATE);
    assertEquals("ДВ бр. 115 от 2025 г.", BgApp9Xsd.ORDINANCE_REVISION);
    assertTrue(BgApp9Xsd.describe().contains("Pril9.xsd"));
  }
}
