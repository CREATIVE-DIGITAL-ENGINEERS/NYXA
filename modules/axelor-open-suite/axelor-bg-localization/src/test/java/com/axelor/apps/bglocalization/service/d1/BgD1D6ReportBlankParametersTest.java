package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПЕЧАТЪТ НА Д1 И Д6 — параметрите, които могат да дойдат ПРАЗНИ, не са задължителни.
 *
 * <p>Задължителен празен параметър поваля „Печат (PDF)“ с „ParameterValidationException: Required
 * parameter RefusalReport is blank“ — BIRT смята скаларния параметър за задължителен по
 * подразбиране, а {@link BgD1ReportPrintService} подава празен доклад за откази на всяка декларация
 * без отказан фиш, тоест на НОРМАЛНАТА. Същият образец като {@code BgVatReportDesignTest}; че
 * PDF-ът наистина излиза с данни — {@code BgTrzVisibleDefectsPostgresIntegrationTest}.
 */
class BgD1D6ReportBlankParametersTest {

  private static final String D1 = "src/main/resources/reports/BgD1Declaration.rptdesign";
  private static final String D6 = "src/main/resources/reports/BgD6Declaration.rptdesign";

  /** Празни по устройство: без откази, дружество без партньор/ЕИК, без декларатор, без период. */
  private static final List<String> D1_MAY_BE_BLANK =
      Arrays.asList(
          "CompanyName",
          "CompanyIdNumber",
          "PeriodLabel",
          "SubmissionPeriodLabel",
          "DeclarantNames",
          "RefusalReport");

  private static final List<String> D6_MAY_BE_BLANK =
      Arrays.asList(
          "CompanyName", "CompanyIdNumber", "PeriodLabel", "DeclarantNames", "RefusalReport");

  private static String parameterBlock(String design, String name) throws IOException {
    String xml = new String(Files.readAllBytes(Paths.get(design)), StandardCharsets.UTF_8);
    Matcher m =
        Pattern.compile(
                "<scalar-parameter name=\"" + name + "\" id=\"\\d+\">(.*?)</scalar-parameter>",
                Pattern.DOTALL)
            .matcher(xml);
    assertTrue(m.find(), design + " няма параметър " + name);
    return m.group(1);
  }

  private static boolean optional(String design, String name) throws IOException {
    return parameterBlock(design, name).contains("<property name=\"isRequired\">false</property>");
  }

  @Test
  @DisplayName("⚠️ Д1: празният доклад за откази (и останалите празни) не поваля печата")
  void d1BlankableParametersAreOptional() throws IOException {
    for (String name : D1_MAY_BE_BLANK) {
      assertTrue(optional(D1, name), "Д1 / " + name + ": празният низ поваля ЦЕЛИЯ печат");
    }
  }

  @Test
  @DisplayName("⚠️ Д6: същото — докладите са празни на всяка чиста декларация")
  void d6BlankableParametersAreOptional() throws IOException {
    for (String name : D6_MAY_BE_BLANK) {
      assertTrue(optional(D6, name), "Д6 / " + name + ": празният низ поваля ЦЕЛИЯ печат");
    }
  }

  @Test
  @DisplayName("Номерът на декларацията и датата остават задължителни — липсата им е дефект")
  void theComputedParametersStayRequired() throws IOException {
    assertFalse(optional(D1, "BgD1DeclarationId"), "Д1 без номер би излязла празна");
    assertFalse(optional(D1, "GenDate"), "Д1 / GenDate");
    assertFalse(optional(D6, "BgD6DeclarationId"), "Д6 без номер би излязла празна");
    assertFalse(optional(D6, "GenDate"), "Д6 / GenDate");
  }
}
