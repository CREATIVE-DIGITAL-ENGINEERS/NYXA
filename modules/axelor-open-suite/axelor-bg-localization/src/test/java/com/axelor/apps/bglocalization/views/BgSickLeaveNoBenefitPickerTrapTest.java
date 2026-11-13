package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ВИДОВЕТЕ „БЕЗ ПРАВО НА ОБЕЗЩЕТЕНИЕ“ НЕ СЕ ПРЕДЛАГАТ ЗА НОВИ ЛИСТОВЕ, НО ОСТАВАТ В РЕЧНИКА.
 *
 * <p>Правото се изчислява ({@code BgSickLeaveResolver#eligibilityOf}); видът е само твърдение и при
 * разминаване фишът отказва. Изборът на листа изключва точно {@link
 * BgSickLeaveResolver#CODES_CLAIMING_NO_BENEFIT} — изгледът е XML и не може да прочете константата,
 * затова този капан сверява двете места. Речникът пази кодовете: заварените листове и миграцията ги
 * носят.
 */
class BgSickLeaveNoBenefitPickerTrapTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/bg-sick-leave-views.xml");
  private static final Path SEED =
      Paths.get("src/main/resources/apps/init-data/en/bg_absence_type.csv");

  private static Element absenceTypeField() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    Document doc = factory.newDocumentBuilder().parse(VIEW.toFile());
    NodeList forms = doc.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element form = (Element) forms.item(i);
      if (!"bg-sick-leave-form".equals(form.getAttribute("name"))) {
        continue;
      }
      NodeList fields = form.getElementsByTagName("field");
      for (int j = 0; j < fields.getLength(); j++) {
        Element field = (Element) fields.item(j);
        if ("bgAbsenceType".equals(field.getAttribute("name"))) {
          return field;
        }
      }
    }
    throw new AssertionError("полето bgAbsenceType на bg-sick-leave-form изчезна");
  }

  @Test
  @DisplayName("Изборът на вид изключва всеки код, който твърди „без право“")
  void thePickerExcludesEveryClaimingCode() throws Exception {
    String domain = absenceTypeField().getAttribute("domain");
    assertNotNull(domain);
    assertTrue(domain.contains("NOT IN"), "домейнът изключва, не изброява: " + domain);
    for (String code : BgSickLeaveResolver.CODES_CLAIMING_NO_BENEFIT) {
      assertTrue(
          domain.contains("'" + code + "'"),
          "видът " + code + " пак се предлага за нови листове: " + domain);
    }
    assertEquals(
        BgSickLeaveResolver.CODES_CLAIMING_NO_BENEFIT.size(),
        domain.split("'", -1).length / 2,
        "домейнът изключва повече кодове от константата — второ мнение по същия въпрос: " + domain);
  }

  @Test
  @DisplayName("Кодовете остават в речника — заварените листове ги носят")
  void theCodesStayInTheDictionary() throws Exception {
    List<String> rows = Files.readAllLines(SEED, StandardCharsets.UTF_8);
    for (String code : BgSickLeaveResolver.CODES_CLAIMING_NO_BENEFIT) {
      assertTrue(
          rows.stream().anyMatch(r -> r.split(",", -1)[1].equals(code)),
          "кодът " + code + " изчезна от bg_absence_type.csv");
    }
  }
}
