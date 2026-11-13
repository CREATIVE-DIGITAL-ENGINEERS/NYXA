package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „СЛУЖИТЕЛ“ Е ИМЕТО НА СЛУЖИТЕЛЯ; ИЗБОРЪТ НА ДОГОВОР — САМО АКТИВНОТО ДРУЖЕСТВО.
 *
 * <p>Колоните „Служител“ на фишовете и болничните показват името на служителя, не името на
 * договора; полето „Трудов договор“ във фиша и в болничния избира само договори на активното
 * дружество, през собствен грид с попълнени колони. Че domain-ът наистина филтрира, а пазачът на
 * сървъра отказва — {@code BgTrzVisibleDefectsPostgresIntegrationTest}.
 */
class BgEmploymentContractPickerViewTest {

  private static final String VIEWS = "src/main/resources/views/";
  private static final String ACTIVE_COMPANY =
      "domain=\"self.payCompany.id IN (SELECT u.activeCompany.id FROM User u WHERE u ="
          + " :__user__)\"";

  private static String read(String file) throws Exception {
    return new String(Files.readAllBytes(Paths.get(VIEWS + file)), StandardCharsets.UTF_8);
  }

  private static String contractField(String xml) {
    Matcher m = Pattern.compile("<field name=\"employmentContract\" canEdit[^>]*/>").matcher(xml);
    assertTrue(m.find(), "полето employmentContract във формата");
    return m.group().replaceAll("\\s+", " ");
  }

  @Test
  @DisplayName("Колоната „Служител“ е името на служителя в двата грида")
  void theEmployeeColumnIsTheEmployeesName() throws Exception {
    for (String file : new String[] {"bg-payslip-views.xml", "bg-sick-leave-views.xml"}) {
      String xml = read(file);
      assertTrue(
          xml.contains("<field name=\"employmentContract.employee.name\" title=\"Служител\"/>"),
          file);
      assertFalse(xml.contains("<field name=\"employmentContract\" title=\"Служител\"/>"), file);
    }
  }

  @Test
  @DisplayName("Изборът на договор — само активното дружество, през BG грида")
  void thePickerIsScopedToTheActiveCompany() throws Exception {
    for (String file : new String[] {"bg-payslip-views.xml", "bg-sick-leave-views.xml"}) {
      String field = contractField(read(file));
      assertTrue(field.contains(ACTIVE_COMPANY.replaceAll("\\s+", " ")), file + ": " + field);
      assertTrue(
          field.contains("grid-view=\"bg-employment-contract-select-grid\""), file + ": " + field);
    }
  }

  @Test
  @DisplayName("Гридът на избора показва служителя, вида по BG и датите — не AOS contractType")
  void thePickerGridShowsFilledColumns() throws Exception {
    String xml = read("EmploymentContract-bg.xml");
    int at = xml.indexOf("<grid name=\"bg-employment-contract-select-grid\"");
    assertTrue(at >= 0, "гридът bg-employment-contract-select-grid");
    String grid = xml.substring(at, xml.indexOf("</grid>", at));
    for (String field : new String[] {"employee", "bgContractType", "startDate", "payCompany"}) {
      assertTrue(grid.contains("<field name=\"" + field + "\""), field + " в " + grid);
    }
    assertFalse(grid.contains("name=\"contractType\""), "AOS contractType е празен в BG");
  }
}
