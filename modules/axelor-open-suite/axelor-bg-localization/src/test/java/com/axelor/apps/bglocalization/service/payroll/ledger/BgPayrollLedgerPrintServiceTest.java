package com.axelor.apps.bglocalization.service.payroll.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.EmploymentContract;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДВЕТЕ НЕЩА, КОИТО УСЛУГАТА ЗА ПЕЧАТ ПРАВИ, БЕЗ ДА ВИКА BIRT — и затова проверими без него.
 *
 * <p><b>⚠️ САМАТА РАЗПЕЧАТКА НЕ СЕ ПРОВЕРЯВА ТУК И ТОВА Е НАРОЧНО.</b> {@code print} иска {@code
 * ReportFactory}, тоест BIRT и жива инсталация; тест, който ги иска, се ПРОПУСКА на всяка машина
 * без база — тоест твърдението му би съществувало само на хартия. Че образецът е съгласен със себе
 * си, се сверява в {@link BgPayrollLedgerDesignTest}; че заявката се изпълнява — в {@code
 * BgReportQueryDbTest}.
 */
class BgPayrollLedgerPrintServiceTest {

  private final BgPayrollLedgerPrintService service = new BgPayrollLedgerPrintService();

  /**
   * ⚠️ СПИСЪКЪТ Е САМИЯТ ФИЛТЪР НА ОТЧЕТА. Заявката не повтаря разбиването — тя чете точно тези
   * номера. Сгрешен ред тук значи ведомост с верни числа на грешния лист.
   */
  @Test
  @DisplayName("⚠️ номерата тръгват в РЕДА НА ХАРТИЯТА, а не в реда, в който са дошли от базата")
  void theIdsTravelInPrintOrder() {
    BgPaySlip last = slip(7L, "Янев");
    BgPaySlip first = slip(3L, "Ангелов");
    BgPayrollLedger ledger = BgPayrollLedger.of(Arrays.asList(last, first));

    assertEquals(
        "3,7",
        service.slipIds(ledger, BgPayrollLedgerSelection.TD),
        "⚠️ „7,3“ тук значи, че редът на хартията идва от базата — а нея я подрежда колацията на"
            + " машината.");
    assertEquals(
        "",
        service.slipIds(ledger, BgPayrollLedgerSelection.GD),
        "⚠️ Празният разрез дава празен низ, а не „null“ и не отказ: месец без граждански договори"
            + " е законно състояние и листът му излиза с нули.");
  }

  /**
   * ⚠️ ДВЕТЕ ЗАМЕНИ СА ИЗМЕРЕНИ ПРИ РЕНДИРАНЕ, НЕ ПРЕДПОЛОЖЕНИ. Знакът U+26A0 няма глиф в шрифта на
   * PDF-емитера и изчезва БЕЗ грешка — предупреждението оставаше като водещ интервал. Новият ред
   * пък трябва да е {@code <br/>}, защото текстът излиза през {@code format="html"}; направено в
   * самия образец, това иска ъглова скоба или entity вътре в CDATA, а и двете там са счупени по
   * различен начин.
   */
  @Test
  @DisplayName("⚠️ докладът за хартията губи знака без глиф и получава истински нов ред")
  void theReportIsTranslatedForPaper() {
    String paper = service.forPaper("⚠️ Едно\nДве");

    assertFalse(
        paper.contains("⚠"), "знакът изчезва мълчаливо от PDF-а — заменя се, а не се оставя");
    assertTrue(paper.startsWith("(!) "), paper);
    assertEquals("(!) Едно<br/>Две", paper);
    assertEquals("", service.forPaper(null), "липсващ доклад е празен низ, не „null“ на хартията");
  }

  private static BgPaySlip slip(Long id, String surname) {
    BgPaySlip slip = new BgPaySlip();
    slip.setId(id);
    slip.setReference("FISH-" + surname);
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgPaySlipLineList(new ArrayList<>());
    EmploymentContract contract = new EmploymentContract();
    contract.setBgInsuredTypeCode("01");
    com.axelor.apps.hr.db.Employee employee = new com.axelor.apps.hr.db.Employee();
    com.axelor.apps.base.db.Partner person = new com.axelor.apps.base.db.Partner();
    person.setName(surname);
    employee.setContactPartner(person);
    contract.setEmployee(employee);
    slip.setEmploymentContract(contract);
    return slip;
  }
}
