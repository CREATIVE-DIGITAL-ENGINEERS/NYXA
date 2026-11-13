package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContractAmendment;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИЗМЕНЕНИЕТО (тип на документа 2) — <b>по едно събитие на анекс, и вторият не трие първия</b>.
 *
 * <p>Това е свидетелят за цялата Фаза 3: докато историята беше едно поле на договора, два анекса
 * даваха ЕДИН запис. Тук се доказва, че вече дават два — различни по дата, по основание и по
 * съдържание — и че всеки от тях минава срещу истинската схема.
 */
class BgEtzAmendmentTest {

  private final BgEtzFixture fixture = new BgEtzFixture();
  private final BgEtzResolver resolver = new BgEtzResolver();
  private final BgEtzEmitter emitter = new BgEtzEmitter();
  private final BgEtzValidator validator = new BgEtzValidator();

  /** Анекс за увеличение на заплатата (основание 019). */
  private BgContractAmendment salaryRaise(EmploymentContract contract) {
    BgContractAmendment a = new BgContractAmendment();
    a.setEmploymentContract(contract);
    a.setChangeDate(LocalDate.of(2026, 6, 1));
    a.setEntryDate(LocalDate.of(2026, 7, 1));
    a.setBgChangeGround("019");
    a.setNewSalary(new BigDecimal("1800.00"));
    a.setNewVacationDays(new BigDecimal("20"));
    a.setNewHoursCode(1);
    a.setNewWorkingHours(8);
    BgNkpd nkpd = new BgNkpd();
    nkpd.setCode(BgEtzFixture.NKPD);
    a.setNewNkpd(nkpd);
    return a;
  }

  /** Втори анекс на СЪЩИЯ договор — смяна на работното време (основание 020). */
  private BgContractAmendment hoursChange(EmploymentContract contract) {
    BgContractAmendment a = salaryRaise(contract);
    a.setChangeDate(LocalDate.of(2026, 8, 15));
    a.setEntryDate(LocalDate.of(2026, 9, 1));
    a.setBgChangeGround("020");
    a.setNewSalary(new BigDecimal("1800.00"));
    a.setNewWorkingHours(4);
    return a;
  }

  @Test
  @DisplayName("Изменение: 0 пречки и 0 грешки срещу etz_employ_restrict.xsd")
  void amendmentValidatesCleanly() {
    EmploymentContract contract = fixture.contract();
    List<BgEtzGap> gaps = new ArrayList<>();

    BgEtzFile file =
        resolver.resolveAmendment(BgEtzFacts.of(contract), salaryRaise(contract), gaps);

    assertTrue(gaps.isEmpty(), "пречки:\n" + BgEtzExportService.describeGaps(gaps));

    byte[] bytes = emitter.emit(file);
    List<BgEtzFinding> findings = validator.validate(bytes, file);
    assertEquals(
        0,
        findings.size(),
        "файлът не мина схемата:\n"
            + BgEtzExportService.describeFindings(findings)
            + "\n--- файлът ---\n"
            + new String(bytes, BgEtzXmlWriter.CHARSET));
  }

  @Test
  @DisplayName("⚠️ ДВА АНЕКСА НА ЕДИН ДОГОВОР → ДВА РАЗЛИЧНИ ЗАПИСА, без презапис")
  void twoAmendmentsProduceTwoDistinctRecords() {
    EmploymentContract contract = fixture.contract();

    String first = emit(contract, salaryRaise(contract));
    String second = emit(contract, hoursChange(contract));

    assertNotEquals(first, second, "двата анекса дадоха ЕДИН И СЪЩ запис — историята пак се губи");

    // Първият: заплата 1800, 8 часа, основание 019, дати 01.06 → 01.07
    assertTrue(first.contains("<changedate>2026-06-01</changedate>"), first);
    assertTrue(first.contains("<entrydate>2026-07-01</entrydate>"), first);
    assertTrue(first.contains("<contracttype>019</contracttype>"), first);
    assertTrue(first.contains("<workinghours>8</workinghours>"), first);

    // Вторият: 4 часа, основание 020, дати 15.08 → 01.09
    assertTrue(second.contains("<changedate>2026-08-15</changedate>"), second);
    assertTrue(second.contains("<entrydate>2026-09-01</entrydate>"), second);
    assertTrue(second.contains("<contracttype>020</contracttype>"), second);
    assertTrue(second.contains("<workinghours>4</workinghours>"), second);

    // И двата са тип 2 и минават схемата.
    for (String xml : new String[] {first, second}) {
      assertTrue(xml.contains("<documenttype>2</documenttype>"), xml);
      assertEquals(
          0,
          validator.validate(xml.getBytes(BgEtzXmlWriter.CHARSET), BgEtzXsd.Schema.EMPLOY).size(),
          xml);
    }
  }

  @Test
  @DisplayName("т. 16 и 16А са РАЗЛИЧНИ дати и двете са задължителни при тип 2")
  void bothAmendmentDatesAreRequired() {
    EmploymentContract contract = fixture.contract();
    BgContractAmendment a = salaryRaise(contract);
    a.setEntryDate(null);

    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolveAmendment(BgEtzFacts.of(contract), a, gaps);

    String text = BgEtzExportService.describeGaps(gaps);
    assertTrue(text.contains("16А"), text);
    assertTrue(text.contains("РАЗЛИЧНА"), text);
  }

  @Test
  @DisplayName("Поле 12 идва от АНЕКСА, не от вида договор — иначе всяко изменение е сключване")
  void field12ComesFromTheAmendment() {
    EmploymentContract contract = fixture.contract();
    String xml = emit(contract, salaryRaise(contract));

    // Договорът е по чл. 67 → код 001 при СКЛЮЧВАНЕ. При изменение т. 12 носи 019.
    assertTrue(xml.contains("<contracttype>019</contracttype>"), xml);
    assertFalse(xml.contains("<contracttype>001</contracttype>"), xml);
  }

  @Test
  @DisplayName("Служебният маркер „000“ от миграцията ОТКАЗВА и назовава защо")
  void migrationPlaceholderRefuses() {
    EmploymentContract contract = fixture.contract();
    BgContractAmendment a = salaryRaise(contract);
    a.setBgChangeGround("000");

    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolveAmendment(BgEtzFacts.of(contract), a, gaps);

    String text = BgEtzExportService.describeGaps(gaps);
    assertTrue(text.contains("служебният маркер"), text);
    assertTrue(text.contains("amendmentTypeSelect"), text);
  }

  @Test
  @DisplayName("Непознато основание по т. 12 отказва, вместо да мине")
  void unknownGroundRefuses() {
    EmploymentContract contract = fixture.contract();
    BgContractAmendment a = salaryRaise(contract);
    a.setBgChangeGround("777");

    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolveAmendment(BgEtzFacts.of(contract), a, gaps);

    assertTrue(
        BgEtzExportService.describeGaps(gaps).contains("не е в номенклатурата"),
        BgEtzExportService.describeGaps(gaps));
  }

  @Test
  @DisplayName("Липсваща ЕКАТТЕ при тип 2 е отказ — схемата я пропуска, приложението не")
  void missingEkatteRefused() {
    EmploymentContract contract = fixture.contract();
    contract.setBgWorkplaceEkatte(null);
    BgContractAmendment a = salaryRaise(contract);
    a.setNewWorkplaceEkatte(null);

    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = resolver.resolveAmendment(BgEtzFacts.of(contract), a, gaps);

    assertTrue(
        BgEtzExportService.describeGaps(gaps).contains("ЕКАТТЕ"),
        BgEtzExportService.describeGaps(gaps));
    // Контролът: същият файл МИНАВА схемата — затова условието живее в BgEtzMandatory.
    assertEquals(0, validator.validate(emitter.emit(file), file).size());
  }

  @Test
  @DisplayName("Празна ЕКАТТЕ на анекса пада към договора — „не се мени“ ≠ „няма“")
  void ekatteFallsBackToTheContract() {
    EmploymentContract contract = fixture.contract();
    BgContractAmendment a = salaryRaise(contract);
    a.setNewWorkplaceEkatte(null);

    assertTrue(emit(contract, a).contains("<codeekatte>" + BgEtzFixture.EKATTE + "</codeekatte>"));
  }

  @Test
  @DisplayName("Изменението НЕ се сглобява от договора — маршрутът е назован")
  void contractPathRefusesAndPointsToTheEntity() {
    BgEtzResolver.BgEtzUnsupportedEventException e =
        assertThrows(
            BgEtzResolver.BgEtzUnsupportedEventException.class,
            () ->
                resolver.resolve(
                    BgEtzFacts.of(fixture.contract()),
                    BgEtzEventKind.AMENDMENT,
                    new ArrayList<>()));
    assertTrue(e.getMessage().contains("resolveAmendment"), e.getMessage());
  }

  @Test
  @DisplayName("Кирилицата в анекса излиза в CP1251, не в UTF-8")
  void amendmentIsCp1251() {
    EmploymentContract contract = fixture.contract();
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file =
        resolver.resolveAmendment(BgEtzFacts.of(contract), salaryRaise(contract), gaps);
    byte[] bytes = emitter.emit(file);

    assertTrue(
        BgEtzFixture.indexOf(bytes, "Георгиев".getBytes(BgEtzXmlWriter.CHARSET)) > 0,
        "фамилията липсва в CP1251 байтове");
    assertEquals(
        -1,
        BgEtzFixture.indexOf(bytes, "Георгиев".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "файлът съдържа UTF-8 байтове");
  }

  private String emit(EmploymentContract contract, BgContractAmendment amendment) {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = resolver.resolveAmendment(BgEtzFacts.of(contract), amendment, gaps);
    return new String(emitter.emit(file), BgEtzXmlWriter.CHARSET);
  }
}
