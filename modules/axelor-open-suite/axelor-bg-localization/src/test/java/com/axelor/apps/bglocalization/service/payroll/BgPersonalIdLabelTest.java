package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.hr.db.Employee;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВИДЪТ НА ЛИЧНИЯ НОМЕР ВЪВ ВЕДОМОСТТА — етикет, а не кодът на вида пред номера.
 *
 * <p>{@code Employee.bgPersonalIdType} е „1“ (ЕГН) или „2“ (ЛНЧ). Ведомостта и печатният фиш не
 * слепват кода с номера („1 7501011016“). Данните (10 цифри, без интервал) и файловете за НАП/НОИ
 * не зависят от това — те взимат само номера ({@code BgD1RecordBuilder}, {@code BgD6Emitter},
 * {@code BgApp9Emitter}, {@code BgEtzFacts}).
 */
class BgPersonalIdLabelTest {

  private static Employee employee(String type, String number) {
    Employee e = new Employee();
    e.setBgPersonalIdType(type);
    e.setBgPersonalIdNumber(number);
    return e;
  }

  @Test
  @DisplayName("„1“ → „ЕГН 7501011016“, „2“ → „ЛНЧ …“ — думата, не кодът")
  void theKindIsAWordNotTheCode() {
    assertEquals("ЕГН 7501011016", BgPersonalIdLabel.of(employee("1", "7501011016")));
    assertEquals("ЛНЧ 1001234567", BgPersonalIdLabel.of(employee("2", " 1001234567 ")));
  }

  @Test
  @DisplayName("Непознат или празен вид — само номерът; без номер — празно")
  void unknownKindShowsTheNumberAlone() {
    assertEquals("7501011016", BgPersonalIdLabel.of(employee(null, "7501011016")));
    assertEquals("7501011016", BgPersonalIdLabel.of(employee("9", "7501011016")));
    assertEquals("", BgPersonalIdLabel.of(employee("1", "  ")));
    assertEquals("", BgPersonalIdLabel.of((Employee) null));
  }

  @Test
  @DisplayName("Печатният образец на ведомостта превежда кода по същия начин")
  void theLedgerDesignTranslatesTheCode() throws Exception {
    String xml =
        new String(
            Files.readAllBytes(Paths.get("src/main/resources/reports/BgPayrollLedger.rptdesign")),
            StandardCharsets.UTF_8);
    int at = xml.indexOf("<property name=\"name\">personal_id_full</property>");
    assertTrue(at > 0, "колоната personal_id_full");
    String expression = xml.substring(at, xml.indexOf("</expression>", at));
    assertTrue(expression.contains("\"1\"?\"ЕГН \""), "„1“ → „ЕГН “: " + expression);
    assertTrue(expression.contains("\"2\"?\"ЛНЧ \""), "„2“ → „ЛНЧ “: " + expression);
    assertFalse(expression.contains("t+\" \""), "кодът не се слепва пред номера: " + expression);
  }
}
