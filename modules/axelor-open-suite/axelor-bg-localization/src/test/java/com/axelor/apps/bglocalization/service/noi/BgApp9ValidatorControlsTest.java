package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СЛОЙ 2 — КОНТРОЛИТЕ, КОИТО СХЕМАТА НЕ МОЖЕ ДА ИЗРАЗИ.
 *
 * <p>Всяко правило тук е от таблицата „Структура, формат, използвани номенклатури и изисквания към
 * контрола“ на НОИ ({@link BgApp9Xsd#CONTROL_SPEC_URL}) и е ЕДНОЗНАЧНО. Правило, което зависи от
 * регистър на НОИ или от данни, каквито модулът няма, се ФЛАГВА — свидетелите за флаговете също са
 * тук, защото обявената несигурност е изход, а не липса на изход.
 */
class BgApp9ValidatorControlsTest {

  private static final BgApp9Validator VALIDATOR = new BgApp9Validator();

  private static boolean hasError(List<BgApp9Finding> findings, String fragment) {
    for (BgApp9Finding f : findings) {
      if (f.isError() && (f.message.contains(fragment) || f.subject.contains(fragment))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasFlag(List<BgApp9Finding> findings, String fragment) {
    for (BgApp9Finding f : findings) {
      if (!f.isError() && (f.message.contains(fragment) || f.subject.contains(fragment))) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName("Пълната фикстура минава и двата слоя без нито една ГРЕШКА")
  void theFixturePassesBothLayers() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(d), 1);

    List<BgApp9Finding> findings = VALIDATOR.validate(emission.getXml(), BgApp9Fixture.one(d));

    assertFalse(BgApp9Validator.hasErrors(findings), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName(
      "⚠️ Сбраните два реда на месец се ФЛАГВАТ — контролът допуска два комплекта само по т. 7")
  void mergedPayerLinesAreFlagged() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasFlag(findings, "СБРАНИ в един комплект"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Броят комплекти на т. 9 трябва да е броят КАЛЕНДАРНИ месеца на периода")
  void monthCountMustMatchTheCalendarSpan() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    // Периодът се разтяга в юли, но ред за юли няма — точно случаят „непресметнат фиш“.
    d.getSickLeave().setToDate(LocalDate.of(2026, 7, 3));

    List<BgApp9Finding> findings = VALIDATOR.controls(d);

    assertTrue(hasError(findings, "КАЛЕНДАРНИ месеца"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Денят „от“ на първия комплект трябва да СЪВПАДА с началото на отпуска")
  void firstDayMustMatchTheLeaveStart() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().setFromDate(LocalDate.of(2026, 6, 3));

    List<BgApp9Finding> findings = VALIDATOR.controls(d);

    assertTrue(hasError(findings, "да СЪВПАДА с началото"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Т. 7 и т. 8 не се попълват едновременно")
  void terminationAndInterruptionAreExclusive() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP7TerminatedFrom(LocalDate.of(2026, 6, 10));
    d.setBgP8InterruptedFrom(LocalDate.of(2026, 6, 9));

    List<BgApp9Finding> findings = VALIDATOR.controls(d);

    assertTrue(hasError(findings, "НЕ е допустимо едновременно"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Т. 8 при „осигурен“ е недопустима")
  void interruptionIsNotAllowedWhenInsured() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP8InterruptedFrom(LocalDate.of(2026, 6, 4));

    List<BgApp9Finding> findings = VALIDATOR.controls(d);

    assertTrue(hasError(findings, "НЕ е осигурено"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("ЕГН се проверява ПО МОДУЛ — сгрешена контролна цифра пада")
  void egnChecksumIsVerified() {
    assertTrue(BgApp9Validator.isValidEgnChecksum(BgApp9Fixture.VALID_EGN));
    assertFalse(BgApp9Validator.isValidEgnChecksum("8505151030"));

    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().getEmploymentContract().getEmployee().setBgPersonalIdNumber("8505151030");

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasError(findings, "по МОДУЛ"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("⚠️ ЛН/ЛНЧ/СлН НЕ се проверява по модул — той не е ЕГН")
  void nonEgnIdentifiersAreNotCheckedByModulus() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().getEmploymentContract().getEmployee().setBgPersonalIdNumber("8505151030");
    d.getSickLeave().getEmploymentContract().getEmployee().setBgPersonalIdType("LNCH");

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertFalse(hasError(findings, "по МОДУЛ"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("IBAN се проверява по ISO 13616 (mod-97) и само в български формат")
  void ibanIsCheckedByMod97() {
    assertTrue(BgApp9Validator.isValidBgIban(BgApp9Fixture.VALID_IBAN));
    assertTrue(BgApp9Validator.isValidBgIban("BG80BNBG96611020345678"));
    assertFalse(
        BgApp9Validator.isValidBgIban("BG19RZBB91550123456789"), "сгрешена контролна двойка");
    assertFalse(
        BgApp9Validator.isValidBgIban("DE89370400440532013000"), "полето е за БЪЛГАРСКА банка");
    assertFalse(BgApp9Validator.isValidBgIban("BG18RZBB9155012345678"), "21 знака");

    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBenefitIban("BG19RZBB91550123456789");
    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasError(findings, "mod-97"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Дата на издаване преди 01.01.2007 г. пада")
  void issueDateFloorIsChecked() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().setBgIssueDate(LocalDate.of(2006, 12, 31));

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasError(findings, "01.01.2007"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Т. 25 · датата на представяне не може да е ПРЕДИ издаването на листа")
  void employerReceiptCannotPrecedeIssue() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().setBgEmployerReceivedDate(LocalDate.of(2026, 6, 1));

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasError(findings, "датата на ИЗДАВАНЕ"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("⚠️ Заместващите 8 часа и закръглянето ИЗЛИЗАТ в отчета, а не мълчат")
  void substitutionAndRoundingAreReported() {
    BgApp9Declaration substituted = BgApp9Fixture.declaration();
    for (com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine line :
        substituted.getSickLeave().getBgPeriodLineList()) {
      line.setDailyHours(null);
    }
    assertTrue(
        hasFlag(VALIDATOR.controls(substituted), "ЗАМЕСТВАЩОТО правило"),
        BgApp9Validator.report(VALIDATOR.controls(substituted)));

    BgApp9Declaration rounded = BgApp9Fixture.declaration();
    for (com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine line :
        rounded.getSickLeave().getBgPeriodLineList()) {
      line.setDailyHours(new BigDecimal("7.50"));
    }
    assertTrue(
        hasFlag(VALIDATOR.controls(rounded), "ЗАКРЪГЛЕНО"),
        BgApp9Validator.report(VALIDATOR.controls(rounded)));
  }

  @Test
  @DisplayName("⚠️ Т. 11 без номенклатура на НОИ се ФЛАГВА, а не се измисля код")
  void surveyCodeIsFlaggedNotInvented() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP11SurveyNo("АЛ-7");
    d.setBgP11SurveyDate(LocalDate.of(2026, 6, 8));
    d.setBgP11FindingSelect("REGIME_BREACH");

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasFlag(findings, "AnketaCode"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Отчетът казва СРЕЩУ КОЯ схема е минала проверката")
  void reportNamesTheSchemaVersion() {
    String report = BgApp9Validator.report(VALIDATOR.controls(BgApp9Fixture.declaration()));
    assertTrue(report.contains("Pril9.xsd"), report);
    assertTrue(report.contains(BgApp9Xsd.SCHEMA_DATE), report);
  }

  @Test
  @DisplayName("⚠️ Липсваща схема НЕ се брои за минала проверка")
  void aMissingSchemaIsNotAPass() {
    // Свидетелят пази поведението, а не пътя: ако някой ден схемата изпадне от jar-а, валидаторът
    // трябва да ГРЪМНЕ, а не да върне празен списък, който изглежда като зелено.
    List<BgApp9Finding> findings = VALIDATOR.validateAgainstSchema("");
    assertTrue(BgApp9Validator.hasErrors(findings));
  }
}
