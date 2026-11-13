package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveTypeRules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A4 — НА БОЛНИЧЕН ЛИСТ СЕ ИЗБИРА САМО ВИД, КОЙТО СЕ УДОСТОВЕРЯВА С БОЛНИЧЕН ЛИСТ.
 *
 * <p>Изгледът е XML и не може да прочете {@link BgSickLeaveTypeRules#PICKER_DOMAIN}, затова този
 * капан сверява двете места. Отделно сверява правилото с всеки ред на сийда срещу ИЗРИЧЕН списък:
 * нов вид в {@code bg_absence_type.csv} с вдигнат „Изисква болничен лист“ се появява тук като
 * разлика и иска решение, а не влиза в избора тихо.
 */
class BgSickLeaveTypePickerTrapTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/bg-sick-leave-views.xml");
  private static final Path SEED =
      Paths.get("src/main/resources/apps/init-data/en/bg_absence_type.csv");

  /** Видовете на болничен лист по сийда — болест, злополука, гледане на болен, майчинство. */
  private static final Set<String> ON_SICK_LEAVE =
      new TreeSet<>(
          Arrays.asList(
              "sick_er",
              "sick_doo",
              "sick_no_benefit_er",
              "sick_no_benefit_doo",
              "home_accident_er",
              "home_accident_doo",
              "work_accident_er",
              "work_accident_doo",
              "occupational_disease_er",
              "occupational_disease_doo",
              "care_sick_child_u18",
              "care_sick_adult_o18",
              "maternity_birth"));

  @Test
  @DisplayName("A4 · изборът на вид на листа е дословно правилото от Java")
  void thePickerDomainIsTheRule() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    Document doc = factory.newDocumentBuilder().parse(VIEW.toFile());
    NodeList forms = doc.getElementsByTagName("form");
    Element field = null;
    for (int i = 0; i < forms.getLength() && field == null; i++) {
      Element form = (Element) forms.item(i);
      if (!"bg-sick-leave-form".equals(form.getAttribute("name"))) {
        continue;
      }
      NodeList fields = form.getElementsByTagName("field");
      for (int j = 0; j < fields.getLength(); j++) {
        Element f = (Element) fields.item(j);
        if ("bgAbsenceType".equals(f.getAttribute("name"))) {
          field = f;
          break;
        }
      }
    }
    assertNotNull(field, "полето bgAbsenceType на bg-sick-leave-form изчезна");
    assertEquals(BgSickLeaveTypeRules.PICKER_DOMAIN, field.getAttribute("domain"));
  }

  @Test
  @DisplayName("A4 · правилото допуска точно видовете с болничен лист — и отказва всички останали")
  void theRuleAdmitsExactlyTheSickLeaveTypesOfTheSeed() throws Exception {
    List<String> rows = Files.readAllLines(SEED, StandardCharsets.UTF_8);
    List<String> header = Arrays.asList(rows.get(0).split(",", -1));
    int code = header.indexOf("code");
    int name = header.indexOf("name");
    int requires = header.indexOf("requiresSickNote");
    int counts = header.indexOf("countsAsInsuranceService");

    Set<String> admitted = new TreeSet<>();
    for (String row : rows.subList(1, rows.size())) {
      if (row.trim().isEmpty()) {
        continue;
      }
      String[] c = row.split(",", -1);
      String refusal =
          BgSickLeaveTypeRules.refusal(
              c[code],
              c[name],
              Boolean.valueOf(c[requires].trim()),
              Boolean.valueOf(c[counts].trim()));
      if (refusal == null) {
        admitted.add(c[code]);
      }
    }
    assertEquals(
        ON_SICK_LEAVE,
        admitted,
        "сийдът и правилото се разминават — нов вид с „Изисква болничен лист“ иска решение");
  }

  @Test
  @DisplayName("A4 · „Непредставен болничен“ иска лист, но не е вид на листа")
  void theUnsubmittedSickNoteIsNotASickLeaveType() {
    assertNotNull(
        BgSickLeaveTypeRules.refusal("sicknote_unsubmitted", "Непредставен болничен", true, false));
    assertNotNull(
        BgSickLeaveTypeRules.refusal("paid_annual", "Платен годишен отпуск", false, true));
    assertNull(BgSickLeaveTypeRules.refusal("sick_er", "Болнични Р-л", true, true));
  }
}
