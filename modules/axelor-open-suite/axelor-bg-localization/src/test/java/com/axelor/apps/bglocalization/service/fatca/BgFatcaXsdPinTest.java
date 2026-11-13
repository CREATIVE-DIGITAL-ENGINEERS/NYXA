package com.axelor.apps.bglocalization.service.fatca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fatca.adapter.BgFatcaXsd;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СХЕМИТЕ СА ТОЧНО ТЕЗИ, КОИТО {@link BgFatcaXsd} ТВЪРДИ — четирите файла от пакета на IRS, байт по
 * байт, прочетени от classpath-а (оттам ги чете и валидаторът).
 */
class BgFatcaXsdPinTest {

  private static byte[] bytes(String resource) throws Exception {
    try (InputStream in = BgFatcaXsd.class.getResourceAsStream(resource)) {
      assertNotNull(in, "схемата " + resource + " не е в classpath-а");
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
  @DisplayName("Дължината и SHA-256 на четирите файла съвпадат със закрепените")
  void отпечатъците_съвпадат() throws Exception {
    assertEquals(4, BgFatcaXsd.FILES.size());
    for (BgFatcaXsd.Pinned pinned : BgFatcaXsd.FILES) {
      byte[] b = bytes(pinned.resource());
      assertEquals(pinned.byteLength, b.length, "дължина на " + pinned.file);
      StringBuilder hex = new StringBuilder(64);
      for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
        hex.append(String.format("%02x", x));
      }
      assertEquals(pinned.sha256, hex.toString(), "SHA-256 на " + pinned.file);
    }
  }

  @Test
  @DisplayName(
      "Основната схема декларира същата версия като константата и е с LF, както я публикува IRS")
  void версията_е_на_едно_място() throws Exception {
    String text = new String(bytes(BgFatcaXsd.RESOURCE), StandardCharsets.UTF_8);
    assertTrue(text.contains("version=\"" + BgFatcaXsd.VERSION + "\""), "version в схемата");
    assertTrue(text.contains("targetNamespace=\"" + BgFatcaXsd.NS_FTC + "\""), "namespace");
    assertTrue(text.contains("<xsd:element name=\"" + BgFatcaXsd.ROOT_ELEMENT + "\">"), "корен");
    assertFalse(text.contains("\r"), "IRS публикува схемата с LF; CR тук значи, че файлът е пипан");
    assertEquals(BgFatcaXsd.RESOURCE, BgFatcaXsd.FILES.get(0).resource());
  }

  @Test
  @DisplayName("Описанието назовава версията и източника")
  void описанието() {
    assertTrue(BgFatcaXsd.describe().contains(BgFatcaXsd.VERSION), BgFatcaXsd.describe());
    assertTrue(BgFatcaXsd.describe().contains("IRS"), BgFatcaXsd.describe());
  }
}
