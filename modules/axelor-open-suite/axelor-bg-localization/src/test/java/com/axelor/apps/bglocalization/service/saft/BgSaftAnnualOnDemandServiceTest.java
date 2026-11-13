package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftFileKind;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГОДИШНИЯТ ФАЙЛ И ФАЙЛЪТ ПРИ ПОИСКВАНЕ — ОБЩАТА ОСНОВА (T1), без база: Header, името, IBAN,
 * сроковете и „съдържанието идва с пистата“.
 *
 * <p><b>⚠️ СЕКЦИИТЕ ТУК СА ФИКСТУРИ.</b> В T1 съдържанието го няма ({@link
 * BgSaftAnnualOnDemandService.Content#pending}). За да се докаже, че основата дава валиден файл и
 * по двете схеми, секциите са минималните на F0 ({@link BgSaftFixtures#minimalAnnual}, {@link
 * BgSaftFixtures#minimalOnDemand}) — зеленото значи структура, не съдържание.
 */
class BgSaftAnnualOnDemandServiceTest {

  private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);
  private static final LocalDate JAN_15 = LocalDate.of(2027, 1, 15);

  /** Дружеството и настройките на чистия април — без месечните входове. */
  private static BgSaftAnnualOnDemandService.Inputs inputs(
      BgSaftSelection selection, LocalDate createdOn, BgSaftAnnualOnDemandService.Content content) {
    BgSaftAuditFileService.Inputs clean = BgSaftComposeTest.clean();
    BgSaftAnnualOnDemandService.Inputs in = new BgSaftAnnualOnDemandService.Inputs();
    in.company = clean.company;
    in.app = clean.app;
    in.ibanRefusal = clean.ibanRefusal;
    in.selection = selection;
    in.createdOn = createdOn;
    in.content = content;
    return in;
  }

  private static BgSaftAnnualOnDemandService.Content annualSections() {
    SaftAuditFile f = BgSaftFixtures.minimalAnnual();
    return BgSaftAnnualOnDemandService.Content.annual(
        f.getMasterFilesAnnual(), f.getAssetTransactions(), List.of());
  }

  private static BgSaftAnnualOnDemandService.Content onDemandSections() {
    SaftAuditFile f = BgSaftFixtures.minimalOnDemand();
    return BgSaftAnnualOnDemandService.Content.onDemand(
        f.getMasterFilesOnDemand(), f.getMovementOfGoods(), List.of());
  }

  private static List<String> blocking(BgSaftAuditFileService.Result result) {
    return result.getFindings().stream()
        .filter(BgDeclarationFileFinding::isBlocking)
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  private static String text(BgSaftGenerationService.Generated g) {
    return new String(g.getBytes(), StandardCharsets.UTF_8);
  }

  // ─────────────── годишният ───────────────

  @Test
  @DisplayName(
      "Годишен за 2025, генериран 31.10.2026: ЕИК_2025.xml, периоди 1–12, „A“, минава XSD 1.0.2")
  void годишният_по_1_0_2() {
    BgSaftAuditFileService.Result result =
        BgSaftAnnualOnDemandService.compose(
            inputs(BgSaftSelection.annual(2025), OCT_31, annualSections()));
    assertEquals(List.of(), blocking(result), result.getFindings().toString());
    assertEquals("123456789_2025.xml", result.getFileName());
    assertEquals(SaftFileKind.ANNUAL, result.getFile().getKind());

    BgSaftGenerationService.Generated g = BgSaftGenerationService.generate(result);
    assertTrue(g.isGenerated(), g.getFindings().toString());
    String xml = text(g);
    assertTrue(xml.contains("<nsSAFT:HeaderComment>A</nsSAFT:HeaderComment>"));
    assertTrue(xml.contains("<nsSAFT:PeriodStart>1</nsSAFT:PeriodStart>"));
    assertTrue(xml.contains("<nsSAFT:PeriodStartYear>2025</nsSAFT:PeriodStartYear>"));
    assertTrue(xml.contains("<nsSAFT:PeriodEnd>12</nsSAFT:PeriodEnd>"));
    assertTrue(xml.contains("<nsSAFT:MasterFilesAnnual>"));
    assertFalse(xml.contains("MasterFilesMonthly"));
    assertEquals("1.0.2", BgSaftSubmissionVersion.auditFileVersion(g.getBytes()));
    assertEquals(List.of(), BgSaftFixtures.validate(g.getBytes(), BgSaftXsd.V1_0_2));
  }

  @Test
  @DisplayName("Годишен за 2026, генериран 15.01.2027: S.H.1 1.0.3 и минава XSD 1.0.3")
  void годишният_по_1_0_3() {
    BgSaftGenerationService.Generated g =
        BgSaftGenerationService.generate(
            BgSaftAnnualOnDemandService.compose(
                inputs(BgSaftSelection.annual(2026), JAN_15, annualSections())));
    assertTrue(g.isGenerated(), g.getFindings().toString());
    assertEquals("123456789_2026.xml", g.getFileName());
    assertEquals("1.0.3", BgSaftSubmissionVersion.auditFileVersion(g.getBytes()));
    assertEquals(List.of(), BgSaftFixtures.validate(g.getBytes(), BgSaftXsd.V1_0_3));
  }

  @Test
  @DisplayName(
      "Годината още не е свършила (31.12 включително) — ГРЕШКА, файл няма; от 01.01 — минава")
  void годината_не_е_свършила() {
    BgSaftAuditFileService.Result dec31 =
        BgSaftAnnualOnDemandService.compose(
            inputs(BgSaftSelection.annual(2026), LocalDate.of(2026, 12, 31), annualSections()));
    assertEquals(List.of(BgSaftAnnualOnDemandService.YEAR_NOT_ENDED), blocking(dec31));
    assertFalse(BgSaftGenerationService.generate(dec31).isGenerated());

    BgSaftAuditFileService.Result jan01 =
        BgSaftAnnualOnDemandService.compose(
            inputs(BgSaftSelection.annual(2026), LocalDate.of(2027, 1, 1), annualSections()));
    assertEquals(List.of(), blocking(jan01));
  }

  @Test
  @DisplayName(
      "T1 без читатели: годишният дава ГРЕШКА „идва с пистата на активите“ — НЕ празен файл")
  void годишният_чака_пистата() {
    BgSaftAuditFileService.Result result =
        BgSaftAnnualOnDemandService.compose(
            inputs(
                BgSaftSelection.annual(2025),
                OCT_31,
                BgSaftAnnualOnDemandService.Content.pending(SaftFileKind.ANNUAL)));
    assertEquals(List.of(BgSaftAnnualOnDemandService.ANNUAL_CONTENT_PENDING), blocking(result));
    assertEquals("123456789_2025.xml", result.getFileName(), "името е готово");
    assertFalse(BgSaftGenerationService.generate(result).isGenerated(), "файл няма");
  }

  // ─────────────── при поискване ───────────────

  @Test
  @DisplayName(
      "При поискване 01.07–15.07.2026: ЕИК_01_07_2026_15_07_2026.xml, датите, „D“, двете схеми")
  void при_поискване() {
    BgSaftSelection july =
        BgSaftSelection.onDemand(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15));
    BgSaftGenerationService.Generated oct =
        BgSaftGenerationService.generate(
            BgSaftAnnualOnDemandService.compose(inputs(july, OCT_31, onDemandSections())));
    assertTrue(oct.isGenerated(), oct.getFindings().toString());
    assertEquals("123456789_01_07_2026_15_07_2026.xml", oct.getFileName());
    String xml = text(oct);
    assertTrue(xml.contains("<nsSAFT:SelectionStartDate>2026-07-01</nsSAFT:SelectionStartDate>"));
    assertTrue(xml.contains("<nsSAFT:SelectionEndDate>2026-07-15</nsSAFT:SelectionEndDate>"));
    assertFalse(xml.contains("<nsSAFT:PeriodStart>"), "точно един клон на SelectionCriteria");
    assertTrue(xml.contains("<nsSAFT:HeaderComment>D</nsSAFT:HeaderComment>"));
    assertEquals(List.of(), BgSaftFixtures.validate(oct.getBytes(), BgSaftXsd.V1_0_2));

    BgSaftGenerationService.Generated nov =
        BgSaftGenerationService.generate(
            BgSaftAnnualOnDemandService.compose(
                inputs(july, LocalDate.of(2026, 11, 1), onDemandSections())));
    assertTrue(nov.isGenerated(), nov.getFindings().toString());
    assertEquals("1.0.3", BgSaftSubmissionVersion.auditFileVersion(nov.getBytes()));
    assertEquals(List.of(), BgSaftFixtures.validate(nov.getBytes(), BgSaftXsd.V1_0_3));
  }

  @Test
  @DisplayName(
      "При поискване: краят след деня на генериране — ГРЕШКА; краят в деня на генериране — минава")
  void периодът_не_е_изтекъл() {
    BgSaftSelection july =
        BgSaftSelection.onDemand(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15));
    BgSaftAuditFileService.Result before =
        BgSaftAnnualOnDemandService.compose(
            inputs(july, LocalDate.of(2026, 7, 14), onDemandSections()));
    assertEquals(List.of(BgSaftAnnualOnDemandService.PERIOD_NOT_ENDED), blocking(before));
    BgSaftAuditFileService.Result same =
        BgSaftAnnualOnDemandService.compose(
            inputs(july, LocalDate.of(2026, 7, 15), onDemandSections()));
    assertEquals(List.of(), blocking(same));
  }

  @Test
  @DisplayName("T1 без читатели: при поискване дава ГРЕШКА „идва с пистата на склада“")
  void при_поискване_чака_пистата() {
    BgSaftAuditFileService.Result result =
        BgSaftAnnualOnDemandService.compose(
            inputs(
                BgSaftSelection.onDemand(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15)),
                OCT_31,
                BgSaftAnnualOnDemandService.Content.pending(SaftFileKind.ON_DEMAND)));
    assertEquals(List.of(BgSaftAnnualOnDemandService.ON_DEMAND_CONTENT_PENDING), blocking(result));
    assertFalse(BgSaftGenerationService.generate(result).isGenerated());
  }

  // ─────────────── общото ───────────────

  @Test
  @DisplayName("IBAN на подаващия е задължителен и тук — ГРЕШКА към дружеството, файл няма")
  void ibanът() {
    BgSaftAnnualOnDemandService.Inputs in =
        inputs(BgSaftSelection.annual(2025), OCT_31, annualSections());
    in.company.setDefaultBankDetails(null);
    BgSaftAuditFileService.Result result = BgSaftAnnualOnDemandService.compose(in);
    assertEquals(List.of("SAFT_HEADER_IBAN_MISSING"), blocking(result));
    BgDeclarationFileFinding missing =
        result.getFindings().stream()
            .filter(f -> f.getCode().equals("SAFT_HEADER_IBAN_MISSING"))
            .findFirst()
            .orElseThrow();
    assertEquals(BgSaftRef.COMPANY, missing.getTargetModel());
    assertFalse(BgSaftGenerationService.generate(result).isGenerated());
  }

  @Test
  @DisplayName("ЕИК на клон: ГРЕШКА в името (Прил. 3 т. I.2), файл няма")
  void ЕИК_на_клон() {
    BgSaftAnnualOnDemandService.Inputs in =
        inputs(BgSaftSelection.annual(2025), OCT_31, annualSections());
    in.company.getPartner().setRegistrationCode("1234567890001");
    BgSaftAuditFileService.Result result = BgSaftAnnualOnDemandService.compose(in);
    assertTrue(blocking(result).contains("SAFT_FILE_NAME_BRANCH_EIK"), blocking(result).toString());
    assertFalse(BgSaftGenerationService.generate(result).isGenerated());
  }

  @Test
  @DisplayName("Месечен избор или съдържание за друг вид — отказ (не тих грешен файл)")
  void отказите() {
    BgSaftAuditFileService.Inputs clean = BgSaftComposeTest.clean();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BgSaftAnnualOnDemandService.compose(
                inputs(BgSaftSelection.monthly(clean.period), OCT_31, annualSections())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BgSaftAnnualOnDemandService.compose(
                inputs(BgSaftSelection.annual(2025), OCT_31, onDemandSections())));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftAnnualOnDemandService.Content.pending(SaftFileKind.MONTHLY));
  }
}
