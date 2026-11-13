package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Изгледите след отметката „подадено в НОИ“ — без база. */
class BgApp9AfterMarkViewTest {

  private static String view(String name) throws Exception {
    return new String(
        Files.readAllBytes(Paths.get("src/main/resources/views/" + name)), StandardCharsets.UTF_8);
  }

  private static String tag(String xml, String start) {
    Matcher m = Pattern.compile(Pattern.quote(start) + "[^>]*>", Pattern.DOTALL).matcher(xml);
    assertTrue(m.find(), "липсва " + start);
    return m.group();
  }

  @Test
  @DisplayName("„Генерирай файла“ е скрит при подаден износ")
  void generateIsHiddenOnASubmittedExport() throws Exception {
    String button = tag(view("bg-app9-export-views.xml"), "<button name=\"generateBtn\"");
    assertTrue(button.contains("showIf=\"statusSelect != 'SUBMITTED'\""), button);
  }

  @Test
  @DisplayName("Формата на износа показва вх. № и датата на подаването")
  void theExportShowsTheSubmission() throws Exception {
    String xml = view("bg-app9-export-views.xml");
    assertTrue(xml.contains("<field name=\"bgNoiIncomingNumber\""));
    assertTrue(xml.contains("<field name=\"bgNoiSubmittedDate\""));
  }

  @Test
  @DisplayName("Подаванията показват ДАТАТА от портала (referenceDate), не submittedOn с 03:00")
  void submissionsShowTheDateNotMidnightPlusThree() throws Exception {
    String xml = view("bg-app9-due-views.xml");
    assertFalse(xml.contains("<field name=\"submittedOn\""), "submittedOn се показва като 03:00");
    assertTrue(xml.contains("<field name=\"referenceDate\" title=\"Подадено на\""));
  }
}
