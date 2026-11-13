package com.axelor.apps.bglocalization.service.vat;

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
 * ТРИТЕ ДДС ОБРАЗЕЦА — параметрите, които могат да дойдат ПРАЗНИ, не бива да са задължителни.
 *
 * <p>⚠️ КАКВО ДОКАЗВА И КАКВО НЕ. Доказва, че нито един параметър, който {@link
 * BgVatReportPrintService} може да подаде празен, не е обявен за задължителен. НЕ доказва, че
 * PDF-ът излиза красив — това се вижда само с очи.
 *
 * <p>⚠️ И ЗАЩО Е ВАЖНО ИМЕННО ТУК. BIRT смята скаларния параметър за ЗАДЪЛЖИТЕЛЕН по подразбиране
 * (ROM 4.4.2 дава {@code <Default>true</Default>} за {@code isRequired}) и хвърля
 * ParameterValidationException („Required parameter … is blank“) още преди първата заявка. Тоест
 * пропуснат ред тук не дава празна клетка, а НУЛА страници — и то на съвършено нормална декларация,
 * чиято снимка на адреса просто не е попълнена.
 *
 * <p>Същата поправка вече стои в {@code BgPaySlip.rptdesign} и {@code BgPayrollLedger.rptdesign};
 * тези три образеца са по-стари и не бяха досвирени.
 */
class BgVatReportDesignTest {

  private static final List<String> DESIGNS =
      Arrays.asList(
          "src/main/resources/reports/BgVatDeclaration.rptdesign",
          "src/main/resources/reports/BgVatSalesDiary.rptdesign",
          "src/main/resources/reports/BgVatPurchaseDiary.rptdesign");

  /**
   * Параметрите, които сервизът може да подаде празни: седемте изобразителни (адресът и името на
   * декларатора са празни на всяка декларация преди prefill/генериране; името и ЕИК-ът — на всяко
   * дружество без партньор) и четирите заварени за връзка с базата (наборите данни ползват
   * PASS_IN_CONNECTION, а {@code db.default.password} е празен при trust/peer автентикация).
   */
  private static final List<String> MAY_BE_BLANK =
      Arrays.asList(
          "CompanyName",
          "CompanyVatNumber",
          "CompanyIdNumber",
          "CompanyAddress",
          "PeriodLabel",
          "DeclarantNames",
          "DeclarantCapacity",
          "DeclarantPosition",
          "DBName",
          "UserName",
          "Password",
          "DefaultDriver");

  /** А тези НЕ бива да се разхлабват: изчисляват се на всяко извикване и липсата им е дефект. */
  private static final List<String> MUST_STAY_REQUIRED = Arrays.asList("BgVatReturnId", "GenDate");

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

  @Test
  @DisplayName("⚠️ Всеки параметър, който може да е празен, е isRequired=false и в трите образеца")
  void everyBlankableParameterIsOptional() throws IOException {
    for (String design : DESIGNS) {
      for (String name : MAY_BE_BLANK) {
        assertTrue(
            parameterBlock(design, name).contains("<property name=\"isRequired\">false</property>"),
            design
                + " / "
                + name
                + ": празният низ поваля ЦЕЛИЯ отчет, докато параметърът е задължителен");
      }
    }
  }

  @Test
  @DisplayName("Номерът на записа и датата остават задължителни — липсата им е счупен извикващ")
  void theComputedParametersStayRequired() throws IOException {
    for (String design : DESIGNS) {
      for (String name : MUST_STAY_REQUIRED) {
        assertFalse(
            parameterBlock(design, name).contains("<property name=\"isRequired\">false</property>"),
            design
                + " / "
                + name
                + ": декларация без него би се отпечатала празна, но валидна на вид");
      }
    }
  }
}
