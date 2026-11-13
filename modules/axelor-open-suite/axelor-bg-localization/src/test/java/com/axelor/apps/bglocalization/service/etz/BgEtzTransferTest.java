package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContractTransfer;
import com.axelor.apps.hr.db.EmploymentContract;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРОМЯНАТА НА РАБОТОДАТЕЛЯ по чл. 123 — <b>вторият корен, другата схема</b>.
 *
 * <p>Тук се доказва и посоката: {@code formereik} е ПРЕДИШНИЯТ, {@code eik} — новият. Разменени,
 * файлът остава валиден по схема и вписва прехвърлянето наопаки — грешка, която нито XSD-то, нито
 * НАП биха хванали.
 */
class BgEtzTransferTest {

  private static final String FORMER_EIK = "121817309";

  private final BgEtzFixture fixture = new BgEtzFixture();
  private final BgEtzResolver resolver = new BgEtzResolver();
  private final BgEtzEmitter emitter = new BgEtzEmitter();
  private final BgEtzValidator validator = new BgEtzValidator();

  private BgContractTransfer transfer(EmploymentContract contract) {
    BgContractTransfer t = new BgContractTransfer();
    t.setEmploymentContract(contract);
    t.setChangeDate(LocalDate.of(2026, 7, 1));
    t.setFormerEik(FORMER_EIK);
    t.setBgChangeReason("2"); // вливане на едно предприятие в друго
    t.setNewWorkplaceEkatte(BgEtzFixture.EKATTE);
    return t;
  }

  @Test
  @DisplayName("чл. 123: 0 пречки и 0 грешки срещу etz_123_employer.xsd")
  void transferValidatesCleanly() {
    EmploymentContract contract = fixture.contract();
    List<BgEtzGap> gaps = new ArrayList<>();

    BgEtzFile file = resolver.resolveTransfer(BgEtzFacts.of(contract), transfer(contract), gaps);

    assertTrue(gaps.isEmpty(), "пречки:\n" + BgEtzExportService.describeGaps(gaps));
    assertEquals(BgEtzXsd.Schema.EMPLOYER_CHANGE, file.getSchema());

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
  @DisplayName("Коренът е etz123employer и носи ДВАТА ЕИК — предишен, после нов")
  void rootCarriesBothEiksInOrder() {
    String xml = emit(fixture.contract());

    assertTrue(xml.contains("<etz123employer>"), xml);
    assertFalse(xml.contains("<etzemploy>"), xml);
    assertTrue(xml.contains("<formereik>" + FORMER_EIK + "</formereik>"), xml);
    assertTrue(xml.contains("<eik>" + BgEtzFixture.EIK + "</eik>"), xml);

    // ⚠️ Посоката: предишният ЕИК стои ПРЕДИ новия.
    assertTrue(
        xml.indexOf("<formereik>") < xml.indexOf("<eik>"),
        "редът на двата ЕИК е обърнат — прехвърлянето би се вписало наопаки");
  }

  @Test
  @DisplayName("Основанието на промяната е т. 3 (1–11), а не „код корекция“")
  void changeReasonIsFieldThreeNotCorrectionCode() {
    String xml = emit(fixture.contract());

    assertTrue(xml.contains("<correctiontype>2</correctiontype>"), xml);
    assertTrue(xml.contains("<codecorrection>0</codecorrection>"), xml);
  }

  @Test
  @DisplayName("Поле 12 при чл. 123 е основанието на СЪЩЕСТВУВАЩИЯ договор (001), не на промяната")
  void field12IsTheExistingContractGround() {
    String xml = emit(fixture.contract());
    assertTrue(xml.contains("<contracttype>001</contracttype>"), xml);
  }

  @Test
  @DisplayName("Неясна корекция ОТКАЗВА — „1 — сливане“ не е подразбиране")
  void unknownChangeReasonRefuses() {
    for (String reason : new String[] {"0", "12", "abc", null}) {
      EmploymentContract contract = fixture.contract();
      BgContractTransfer t = transfer(contract);
      t.setBgChangeReason(reason);

      List<BgEtzGap> gaps = new ArrayList<>();
      resolver.resolveTransfer(BgEtzFacts.of(contract), t, gaps);

      String text = BgEtzExportService.describeGaps(gaps);
      assertTrue(text.contains("т. 3"), "за „" + reason + "“: " + text);
      assertFalse(text.isEmpty());
    }
  }

  @Test
  @DisplayName("Липсващ formereik е отказ — иначе прехвърлянето няма откъде")
  void missingFormerEikRefuses() {
    EmploymentContract contract = fixture.contract();
    BgContractTransfer t = transfer(contract);
    t.setFormerEik(null);

    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolveTransfer(BgEtzFacts.of(contract), t, gaps);

    assertTrue(
        BgEtzExportService.describeGaps(gaps).contains("т. 4"),
        BgEtzExportService.describeGaps(gaps));
  }

  @Test
  @DisplayName("Един и същ ЕИК от двете страни не е прехвърляне — схемата би го приела")
  void sameEikBothSidesIsCrossCheckFailure() {
    EmploymentContract contract = fixture.contract();
    BgContractTransfer t = transfer(contract);
    t.setFormerEik(BgEtzFixture.EIK);

    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = resolver.resolveTransfer(BgEtzFacts.of(contract), t, gaps);

    assertTrue(
        BgEtzExportService.describeGaps(gaps).contains("това не е"),
        BgEtzExportService.describeGaps(gaps));
    // Контролът: схемата го приема — затова проверката е наша.
    assertEquals(0, validator.validate(emitter.emit(file), file).size());
  }

  @Test
  @DisplayName("Липсваща ЕКАТТЕ при вид 01 и код корекция 0 е отказ (Прил. № 2)")
  void missingEkatteRefused() {
    EmploymentContract contract = fixture.contract();
    contract.setBgWorkplaceEkatte(null);
    BgContractTransfer t = transfer(contract);
    t.setNewWorkplaceEkatte(null);

    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolveTransfer(BgEtzFacts.of(contract), t, gaps);

    assertTrue(
        BgEtzExportService.describeGaps(gaps).contains("ЕКАТТЕ"),
        BgEtzExportService.describeGaps(gaps));
  }

  @Test
  @DisplayName("Името на файла е ETZ123_<ЕИК на НОВИЯ>")
  void fileNameUsesTheNewEmployerEik() {
    assertEquals(
        "ETZ123_" + BgEtzFixture.EIK + ".XML",
        BgEtzSubmissionPlan.fileNameFor(BgEtzEventKind.EMPLOYER_CHANGE, BgEtzFixture.EIK));
  }

  @Test
  @DisplayName("Прехвърлянето НЕ се сглобява от договора — маршрутът е назован")
  void contractPathRefusesAndPointsToTheEntity() {
    BgEtzResolver.BgEtzUnsupportedEventException e =
        assertThrows(
            BgEtzResolver.BgEtzUnsupportedEventException.class,
            () ->
                resolver.resolve(
                    BgEtzFacts.of(fixture.contract()),
                    BgEtzEventKind.EMPLOYER_CHANGE,
                    new ArrayList<>()));
    assertTrue(e.getMessage().contains("resolveTransfer"), e.getMessage());
  }

  @Test
  @DisplayName("Планът пита матрицата — DEC_ETZ123 е ПОТВЪРДЕНА комбинация")
  void planAsksTheMatrix() {
    List<BgEtzSubmissionPlan.PlannedFile> planned =
        BgEtzSubmissionPlan.plan(BgEtzEventKind.EMPLOYER_CHANGE, new byte[] {60, 63}, "1");
    assertEquals(1, planned.size());
    assertEquals("DEC_ETZ123", planned.get(0).getDocumentType().name());
  }

  private String emit(EmploymentContract contract) {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = resolver.resolveTransfer(BgEtzFacts.of(contract), transfer(contract), gaps);
    return new String(emitter.emit(file), BgEtzXmlWriter.CHARSET);
  }
}
