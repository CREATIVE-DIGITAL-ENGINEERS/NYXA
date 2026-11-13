package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgArt73Para6Annual;
import com.axelor.apps.bglocalization.db.BgArt73Para6Record;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileIncomeRow;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FilePersonRow;
import com.axelor.apps.hr.db.Employee;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СЪБИРАЧЪТ ПРЕСТРУКТУРИРА, НО НЕ ПРЕИЗЧИСЛЯВА — плоските редове на базата → вложените на файла.
 *
 * <p>⚠️ Свидетелите тук пазят точно трите правила на групирането по „Ред и формат“ (заповед №
 * З-ЦУ-1877/05.12.2019 г.): един запис на лице, един ред на (лице, код, работодател) по т. 15 и
 * ОТДЕЛЕН ред за преобразуващото се предприятие по т. 16.
 */
class BgArt73Para6FileCollectorTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static Employee employee(Long id) {
    Employee employee = new Employee();
    employee.setId(id);
    return employee;
  }

  private static BgIncomeType incomeType(String code) {
    BgIncomeType type = new BgIncomeType();
    type.setCode(code);
    return type;
  }

  private static BgArt73Para6Record record(
      Long id, Employee employee, String code, String employerEik, String income, String tax) {
    BgArt73Para6Record record = new BgArt73Para6Record();
    record.setId(id);
    record.setEmployee(employee);
    record.setCorrectionCode("0");
    record.setFirstName("Иван");
    record.setMiddleName("Петров");
    record.setLastName("Георгиев");
    record.setIdentifierType("0");
    record.setIdentifier("8001010013");
    record.setIsMainEmployer(Boolean.FALSE);
    record.setIncomeType(incomeType(code));
    record.setPayerIdentifier(employerEik);
    record.setPayerName("НЯКОЕ ЕООД");
    record.setIncome(bd(income));
    record.setAdvanceTax(bd(tax));
    return record;
  }

  private static BgArt73Para6Report report(BgArt73Para6Record... records) {
    BgArt73Para6Report report = new BgArt73Para6Report();
    report.setTaxYear(2026);
    report.setEmployerIdentifier("100000033");
    report.setEmployerName("НЯКОЕ ЕООД");
    report.setContactPhone("0700 18 700");
    report.setContactEmail("office@example.bg");
    report.setRecordList(new ArrayList<>(Arrays.asList(records)));
    report.setAnnualList(new ArrayList<>());
    return report;
  }

  @Test
  @DisplayName("Част І се пренася ВЕДНЪЖ, включително двете нови контактни полета")
  void част_първа_се_пренася() {
    BgArt73Para6FileContent content = BgArt73Para6FileCollector.collect(report());
    assertEquals(2026, content.getTaxYear());
    assertEquals("100000033", content.getPayerIdentifier());
    assertEquals("0700 18 700", content.getContactPhone());
    assertEquals("office@example.bg", content.getContactEmail());
  }

  @Test
  @DisplayName("⚠️ ДВА реда на едно лице → ЕДИН запис с ДВА вложени реда доход")
  void едно_лице_един_запис() {
    Employee ivan = employee(1L);
    BgArt73Para6FileContent content =
        BgArt73Para6FileCollector.collect(
            report(
                record(1L, ivan, "101", "100000033", "24000.00", "2280.00"),
                record(2L, ivan, "104", "100000033", "600.00", "0.00")));

    assertEquals(1, content.getPersons().size(), "лицето е ЕДИН запис");
    assertEquals(2, content.getPersons().get(0).getIncomeRows().size(), "два реда доход");
  }

  @Test
  @DisplayName(
      "⚠️ Т. 15: същият работодател със същия код се СЛИВА в един ред, а сумите се събират")
  void същата_тройка_се_слива() {
    Employee ivan = employee(1L);
    BgArt73Para6FileContent content =
        BgArt73Para6FileCollector.collect(
            report(
                record(1L, ivan, "101", "100000033", "20000.00", "1900.00"),
                record(2L, ivan, "101", "100000033", "4000.00", "380.00")));

    List<BgArt73Para6FileIncomeRow> rows = content.getPersons().get(0).getIncomeRows();
    assertEquals(1, rows.size(), "т. 15 иска ЕДИН ред на (лице, код, работодател)");
    assertEquals(bd("24000.00"), rows.get(0).getIncome());
    assertEquals(bd("2280.00"), rows.get(0).getAdvanceTax());
  }

  @Test
  @DisplayName("⚠️ Т. 16: преобразуващото се предприятие е ОТДЕЛЕН ред със СЪЩИЯ код")
  void преобразуването_е_отделен_ред() {
    Employee ivan = employee(1L);
    BgArt73Para6Record fromPredecessor = record(2L, ivan, "101", "100000033", "6000.00", "570.00");
    fromPredecessor.setPredecessorIdentifier("831234567");

    BgArt73Para6FileContent content =
        BgArt73Para6FileCollector.collect(
            report(record(1L, ivan, "101", "100000033", "24000.00", "2280.00"), fromPredecessor));

    List<BgArt73Para6FileIncomeRow> rows = content.getPersons().get(0).getIncomeRows();
    assertEquals(2, rows.size(), "т. 16 РАЗДЕЛЯ двата реда, макар кодът да е един");
    assertNull(rows.get(0).getPredecessorIdentifier());
    assertEquals("831234567", rows.get(1).getPredecessorIdentifier());
  }

  @Test
  @DisplayName("⚠️ Разминаване по „основен работодател“ се ЗАПОМНЯ, а не се избира мълчаливо")
  void противоречието_се_запомня() {
    Employee ivan = employee(1L);
    BgArt73Para6Record second = record(2L, ivan, "104", "100000033", "600.00", "0.00");
    second.setIsMainEmployer(Boolean.TRUE);

    BgArt73Para6FilePersonRow person =
        BgArt73Para6FileCollector.collect(
                report(record(1L, ivan, "101", "100000033", "24000.00", "2280.00"), second))
            .getPersons()
            .get(0);

    assertTrue(person.isMainEmployerConflict(), "събирачът е видял разминаването");
  }

  @Test
  @DisplayName("Съгласните редове НЕ вдигат признак за противоречие")
  void съгласните_редове_мълчат() {
    Employee ivan = employee(1L);
    BgArt73Para6FilePersonRow person =
        BgArt73Para6FileCollector.collect(
                report(
                    record(1L, ivan, "101", "100000033", "24000.00", "2280.00"),
                    record(2L, ivan, "104", "100000033", "600.00", "0.00")))
            .getPersons()
            .get(0);

    assertFalse(person.isMainEmployerConflict());
  }

  @Test
  @DisplayName("⚠️ БЕЗ годишен ред сборът по колона 22 е САМО сборът на авансовия данък")
  void сборът_без_годишен_ред() {
    Employee ivan = employee(1L);
    BgArt73Para6FilePersonRow person =
        BgArt73Para6FileCollector.collect(
                report(
                    record(1L, ivan, "101", "100000033", "20000.00", "1900.00"),
                    record(2L, ivan, "104", "100000033", "600.00", "57.00")))
            .getPersons()
            .get(0);

    assertNull(person.getAnnual(), "не е основен работодател");
    assertEquals(bd("1957.00"), person.getTotalTaxWithheld());
  }

  @Test
  @DisplayName("Годишният ред дава taxbase251/253 и целия блок по чл. 49 — ЗАПИСАНОТО побеждава")
  void годишният_ред_се_пренася() {
    Employee ivan = employee(1L);
    BgArt73Para6Record main = record(1L, ivan, "101", "100000033", "24000.00", "2280.00");
    main.setIsMainEmployer(Boolean.TRUE);

    BgArt73Para6Annual annual = new BgArt73Para6Annual();
    annual.setEmployee(ivan);
    annual.setBaseArt25Para1(bd("28080.00"));
    annual.setBaseArt49(bd("28080.00"));
    annual.setReliefChildren(bd("6000.00"));
    annual.setTaxableBase(bd("22080.00"));
    annual.setAnnualTax(bd("2208.00"));
    annual.setSettlement(bd("-72.00"));
    annual.setToRefund(bd("72.00"));
    // ⚠️ Нарочно РАЗЛИЧНО от сбора на авансовия данък: записаното трябва да победи сметката.
    annual.setTotalWithheld(bd("2208.00"));

    BgArt73Para6Report report = report(main);
    report.getAnnualList().add(annual);

    BgArt73Para6FilePersonRow person =
        BgArt73Para6FileCollector.collect(report).getPersons().get(0);

    assertEquals(bd("28080.00"), person.getTaxBaseArt25Para1());
    assertNotNull(person.getAnnual());
    assertEquals(bd("6000.00"), person.getAnnual().getReliefChildren());
    assertEquals(bd("-72.00"), person.getAnnual().getSettlement());
    assertEquals(bd("2208.00"), person.getTotalTaxWithheld(), "записаното побеждава сметката");
  }

  @Test
  @DisplayName("⚠️ Годишен ред с НУЛЕВ сбор дава 0.00, а НЕ резервна сметка")
  void нулевият_сбор_не_пада_към_сметка() {
    Employee ivan = employee(1L);
    BgArt73Para6Record main = record(1L, ivan, "101", "100000033", "24000.00", "2280.00");
    main.setIsMainEmployer(Boolean.TRUE);

    // ⚠️ totalWithheld НЕ се пипа: генерираният getter връща ZERO, не null. Събирач, който пита за
    // null и пада обратно към сбора на авансовия данък, би сложил 2280.00 там, където годишният
    // ред казва нула.
    BgArt73Para6Annual annual = new BgArt73Para6Annual();
    annual.setEmployee(ivan);

    BgArt73Para6Report report = report(main);
    report.getAnnualList().add(annual);

    assertEquals(
        BigDecimal.ZERO,
        BgArt73Para6FileCollector.collect(report).getPersons().get(0).getTotalTaxWithheld(),
        "има ли годишен ред, неговото число е отговорът — и нулата е число");
  }

  @Test
  @DisplayName("⚠️ Годишен ред за ДРУГО лице не се залепва за това")
  void чуждият_годишен_ред_не_се_залепва() {
    Employee ivan = employee(1L);
    BgArt73Para6Annual foreign = new BgArt73Para6Annual();
    foreign.setEmployee(employee(2L));
    foreign.setBaseArt49(bd("999.00"));

    BgArt73Para6Report report = report(record(1L, ivan, "101", "100000033", "100.00", "9.00"));
    report.getAnnualList().add(foreign);

    assertNull(BgArt73Para6FileCollector.collect(report).getPersons().get(0).getAnnual());
  }

  @Test
  @DisplayName("Подредбата е по id — два пробега дават един и същ файл")
  void подредбата_е_възпроизводима() {
    BgArt73Para6Report report =
        report(
            record(2L, employee(2L), "101", "100000033", "2.00", "0.19"),
            record(1L, employee(1L), "101", "100000033", "1.00", "0.09"));

    List<BgArt73Para6FilePersonRow> persons =
        BgArt73Para6FileCollector.collect(report).getPersons();
    assertEquals(bd("1.00"), persons.get(0).getIncomeRows().get(0).getIncome());
    assertEquals(bd("2.00"), persons.get(1).getIncomeRows().get(0).getIncome());
  }

  @Test
  @DisplayName("null справка дава празно съдържание, а не изключение")
  void празната_справка_не_гърми() {
    assertTrue(BgArt73Para6FileCollector.collect(null).getPersons().isEmpty());
  }
}
