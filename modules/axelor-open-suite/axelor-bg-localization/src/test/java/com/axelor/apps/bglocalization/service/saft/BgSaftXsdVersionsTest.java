package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТАБЛИЦАТА „ВЕРСИЯ → ОТ ДАТА“ — ЕДНО МЯСТО ЗА S.H.1 И ЗА СХЕМАТА, СРЕЩУ КОЯТО СЕ ПРОВЕРЯВА.
 *
 * <p>Заповед З-ЦУ-30-1797/13.10.2026 г., с. 1: „Стандартните одитни файлове за данъчни цели се
 * подават съгласно новата версия 1.0.3 на XCD схемата в сроковете по чл. 71к от ДОПК, считано от
 * 01.11.2026 г.“ Решава ДАТАТА НА ГЕНЕРИРАНЕ (подаването), не периодът на файла — QA II.2.4 (с.
 * 13): коригиращите файлове „следва да съответстват на XSD-схемата, която е актуална към момента на
 * подаване“.
 */
class BgSaftXsdVersionsTest {

  @Test
  @DisplayName("1.0.2 до 31.10.2026, 1.0.3 от 01.11.2026 — по датата на генериране")
  void таблицата_по_дата() {
    assertEquals(List.of(BgSaftXsd.V1_0_2, BgSaftXsd.V1_0_3), BgSaftXsd.VERSIONS);
    assertEquals(LocalDate.of(2026, 4, 1), BgSaftXsd.V1_0_2.getAppliesFrom());
    assertEquals(LocalDate.of(2026, 11, 1), BgSaftXsd.V1_0_3.getAppliesFrom());
    assertEquals("1.0.2", BgSaftXsd.forDate(LocalDate.of(2026, 4, 1)).getSchemaVersion());
    assertEquals("1.0.2", BgSaftXsd.forDate(LocalDate.of(2026, 10, 31)).getSchemaVersion());
    assertEquals("1.0.3", BgSaftXsd.forDate(LocalDate.of(2026, 11, 1)).getSchemaVersion());
    assertEquals("1.0.3", BgSaftXsd.forDate(LocalDate.of(2027, 6, 30)).getSchemaVersion());
    assertThrows(IllegalArgumentException.class, () -> BgSaftXsd.forDate(null));
  }

  /**
   * ⚠️ Стойността за периода на 1.0.2 е „1.0.2“ по книгата (S.H.1: „Попълва се съответната версия
   * на спецификацията на SAF-T за България.“) — потвърдено на 17.10.2026 с протоколи 30TST000014745
   * („1.0.2“ — приет) и 30TST000014746 („1.0.3“ — отказан). „007“ от месечния еталон е отхвърлена с
   * протокол 30TST000014738.
   */
  @Test
  @DisplayName("S.H.1 — 1.0.2 → „1.0.2“, 1.0.3 → „1.0.3“; „007“ никога")
  void стойностите_на_sh1() {
    assertEquals("1.0.2", BgSaftXsd.V1_0_2.getAuditFileVersion());
    assertEquals("1.0.3", BgSaftXsd.V1_0_3.getAuditFileVersion());
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      assertNotEquals("007", v.getAuditFileVersion(), v.getSchemaVersion());
    }
  }

  @Test
  @DisplayName("Версията НЕ се чете от схемата: XSD 1.0.3 носи version=„1.0.2“")
  void версията_не_е_от_атрибута() throws Exception {
    String text;
    try (InputStream in = BgSaftXsd.class.getResourceAsStream(BgSaftXsd.V1_0_3.getResource())) {
      assertNotNull(in, BgSaftXsd.V1_0_3.getResource());
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(text.contains("id=\"SAF-T\""), "кореновият xs:schema");
    assertTrue(text.contains(" version=\"1.0.2\" "), "капанът на НАП е още там");
    assertEquals("1.0.3", BgSaftXsd.V1_0_3.getSchemaVersion());
  }

  @Test
  @DisplayName("Описанието на 1.0.3 назовава четирите заповеди и датата 01.11.2026")
  void описанието_на_103() {
    String d = BgSaftXsd.V1_0_3.describe();
    for (String order : List.of("З-ЦУ-30-1085", "З-ЦУ-30-1247", "З-ЦУ-30-359", "З-ЦУ-30-1797")) {
      assertTrue(d.contains(order), d);
    }
    assertTrue(d.contains("01.11.2026"), d);
    assertTrue(d.startsWith("SAF-T BG v1.0.3"), d);
    assertTrue(BgSaftXsd.V1_0_2.describe().contains("01.04.2026"), BgSaftXsd.V1_0_2.describe());
  }

  @Test
  @DisplayName("Header-ът взема S.H.1 от таблицата по датата на създаване")
  void header_по_дата() {
    assertEquals(
        "1.0.2",
        BgSaftHeaderAssemblerTest.headerOn(LocalDate.of(2026, 10, 31)).getAuditFileVersion());
    assertEquals(
        "1.0.3",
        BgSaftHeaderAssemblerTest.headerOn(LocalDate.of(2026, 11, 1)).getAuditFileVersion());
  }

  @Test
  @DisplayName("Месечният файл минава и срещу XSD 1.0.2, и срещу XSD 1.0.3")
  void месечният_файл_минава_двете_схеми() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    byte[] xml = BgSaftXmlWriter.toBytes(file);
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      assertEquals(List.of(), BgSaftFixtures.validate(xml, v), v.describe());
    }
  }
}
