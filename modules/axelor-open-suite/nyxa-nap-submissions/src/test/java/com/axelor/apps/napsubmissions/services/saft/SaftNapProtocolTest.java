package com.axelor.apps.napsubmissions.services.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРОТОКОЛЪТ НА НАП (errsaft) — по формата на протокол 30TST000014738 (15.10.2026), с измислени
 * стойности.
 */
class SaftNapProtocolTest {

  private static byte[] xml(String body) {
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("Две грешки: номер, елемент по книгата и описание — по реда им")
  void грешките_се_разчитат() {
    List<SaftNapProtocol.Error> errors =
        SaftNapProtocol.parse(
            xml(
                "<errsaft>\n"
                    + "  <error><err_number>1</err_number>"
                    + "<err_code>AuditFileVersion S.H.1</err_code>"
                    + "<err_descr>Версията не отговаря.</err_descr></error>\n"
                    + "  <error><err_number>2</err_number>"
                    + "<err_code>IBANNumber S.BA.1</err_code>"
                    + "<err_descr> Невалиден IBAN </err_descr></error>\n"
                    + "</errsaft>"));
    assertEquals(2, errors.size());
    assertEquals("1", errors.get(0).getNumber());
    assertEquals("AuditFileVersion S.H.1", errors.get(0).getCode());
    assertEquals("Невалиден IBAN", errors.get(1).getDescription());
    assertEquals("2. IBANNumber S.BA.1 — Невалиден IBAN", errors.get(1).toString());
  }

  @Test
  @DisplayName("Протокол без грешки — празен списък (статусът не се извежда от него)")
  void празен_протокол() {
    assertTrue(SaftNapProtocol.parse(xml("<errsaft/>")).isEmpty());
  }

  @Test
  @DisplayName("Друг XML, повреден файл и празен файл — отказ, не тихо „без грешки“")
  void не_е_протокол() {
    IllegalArgumentException other =
        assertThrows(
            IllegalArgumentException.class, () -> SaftNapProtocol.parse(xml("<AuditFile/>")));
    assertTrue(other.getMessage().contains("AuditFile"), other.getMessage());
    assertThrows(IllegalArgumentException.class, () -> SaftNapProtocol.parse(xml("<errsaft>")));
    assertThrows(IllegalArgumentException.class, () -> SaftNapProtocol.parse(new byte[0]));
  }

  @Test
  @DisplayName("DTD с външно ентити — не се разрешава (файлът е от потребителя)")
  void без_външни_ентитита() {
    byte[] xxe =
        xml(
            "<!DOCTYPE errsaft [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<errsaft><error><err_number>1</err_number><err_code>X</err_code>"
                + "<err_descr>&x;</err_descr></error></errsaft>");
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> SaftNapProtocol.parse(xxe));
    assertTrue(e.getMessage().contains("errsaft"), e.getMessage());
  }
}
