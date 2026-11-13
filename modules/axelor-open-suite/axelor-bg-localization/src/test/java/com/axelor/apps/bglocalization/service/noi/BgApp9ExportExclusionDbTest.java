package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgApp9ExportLine;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ЛИСТ С ЛИПСИ ОСТАВА ВЪН, НЕ СПИРА ЦЕЛИЯ МЕСЕЧЕН ИЗНОС — и РЕЗУЛТАТЪТ ОТ ПОСЛЕДНИЯ ОПИТ.
 *
 * <p>Случаят: 0909043 няма т. 9; 1001043 е изряден. Двата са в октомврийския прозорец и липсата на
 * първия не оставя втория без файл.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9ExportExclusionDbTest extends JpaSupport {

  private BgApp9DbFixture fx;

  @BeforeEach
  void setUp() {
    fx = new BgApp9DbFixture();
    BgApp9DbFixture.loadCalendar();
  }

  private <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  /** Изряден лист — с редове по т. 9 (05–12.10.2026: 2 + 4 дни). */
  private BgApp9Declaration good(EmploymentContract contract) {
    return tx(
        () -> BgApp9DbFixture.declaration(fx.sickLeave(contract, LocalDate.of(2026, 10, 13))));
  }

  /** Лист като 0909043 — т. 9 празна. */
  private BgApp9Declaration withoutPoint9(EmploymentContract contract) {
    return tx(
        () -> {
          BgSickLeave sick = fx.sickLeave(contract, LocalDate.of(2026, 10, 2));
          sick.getBgPeriodLineList().clear();
          Beans.get(BgSickLeaveRepository.class).save(sick);
          return BgApp9DbFixture.declaration(sick);
        });
  }

  private BgApp9Export october(Company company) {
    return tx(
        () -> {
          BgApp9Export e = new BgApp9Export();
          e.setName("Октомври " + fx.tag);
          e.setCompany(company);
          e.setStatusSelect("DRAFT");
          e.setNssiRoCode(22);
          e.setNssiRoName("София-град");
          e.setBgPeriodFrom(LocalDate.of(2026, 10, 1));
          e.setBgPeriodTo(LocalDate.of(2026, 10, 31));
          JPA.em().persist(e);
          return e;
        });
  }

  private BgApp9Emission generate(BgApp9Export export) {
    return tx(
        () -> {
          try {
            return Beans.get(BgApp9ExportService.class).generate(export);
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static BgApp9ExportLine lineOf(BgApp9Export export, BgApp9Declaration d) {
    return export.getBgExportLineList().stream()
        .filter(l -> l.getBgApp9Declaration() == d)
        .findFirst()
        .get();
  }

  private static String xmlOf(BgApp9Export export) throws Exception {
    return new String(
        Files.readAllBytes(MetaFiles.getPath(export.getBgNoiArtifact().getMetaFile())),
        StandardCharsets.UTF_8);
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      n++;
    }
    return n;
  }

  @Test
  @DisplayName(
      "Два листа, единият без т. 9 → файл с ЕДИН <BSickLeave>; другият — в дневника с причината")
  void aSheetWithGapsIsLeftOutAndTheOtherGetsItsFile() throws Exception {
    Company company = tx(() -> fx.company("Изключване", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration missing = withoutPoint9(contract);
    BgApp9Declaration ok = good(contract);
    BgApp9Export export = october(company);

    BgApp9Emission emission = generate(export);

    assertFalse(emission.isRefused(), emission.gapReport());
    assertEquals(1, emission.getDocumentCount());
    String xml = xmlOf(export);
    assertEquals(1, count(xml, "<BSickLeave>"), "само изрядният лист носи т. 9 във файла");
    assertNotNull(ok.getBgNoiArtifact());
    assertEquals("GENERATED", ok.getStatusSelect());

    // Изключеният: без файл, статусът не мърда, „не е генерирано“ в „Прил. 9 за подаване“.
    assertNull(missing.getBgNoiArtifact());
    assertNotEquals("GENERATED", missing.getStatusSelect());
    assertEquals(1, tx(() -> BgApp9DbFixture.submissionState(missing.getSickLeave())));

    BgApp9ExportLine line = lineOf(export, missing);
    assertFalse(line.getBgIncluded());
    assertTrue(line.getBgNote().startsWith("Не влиза в този файл — липси"), line.getBgNote());
    assertTrue(line.getBgNote().contains("т. 9"), line.getBgNote());
    assertTrue(lineOf(export, ok).getBgIncluded());

    // Причината и в съобщението, и в отчета на износа, и върху самото удостоверение.
    assertEquals(1, emission.excludedDocuments().size());
    assertEquals(BgApp9MandatoryPoints.label(missing), emission.excludedDocuments().get(0));
    assertTrue(export.getBgEmissionGaps().startsWith("Файлът е генериран БЕЗ 1"));
    assertTrue(export.getBgLastAttempt().contains("изключени: 1"), export.getBgLastAttempt());
    assertTrue(missing.getBgEmissionGaps().contains("т. 9"), missing.getBgEmissionGaps());
    assertTrue(missing.getBgLastAttempt().contains("ИЗКЛЮЧЕНО"), missing.getBgLastAttempt());
  }

  @Test
  @DisplayName("Всички невалидни → отказ, без файл")
  void whenNothingRemainsTheExportIsRefused() {
    Company company = tx(() -> fx.company("Всички без т. 9", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration a = withoutPoint9(contract);
    BgApp9Declaration b = withoutPoint9(contract);
    BgApp9Export export = october(company);

    BgApp9Emission emission = generate(export);

    assertTrue(emission.isRefused());
    assertTrue(emission.getExcluded().isEmpty(), "при отказ няма „изключени“ — отказани са всички");
    assertNull(export.getBgNoiArtifact());
    assertNull(a.getBgNoiArtifact());
    assertNull(b.getBgNoiArtifact());
    assertTrue(export.getBgEmissionGaps().contains(BgApp9MandatoryPoints.label(a)));
    assertTrue(export.getBgEmissionGaps().contains(BgApp9MandatoryPoints.label(b)));
    assertTrue(export.getBgLastAttempt().contains("ОТКАЗ"), export.getBgLastAttempt());
  }

  @Test
  @DisplayName("Липса в заглавната част (адрес) → отказ за ЦЕЛИЯ файл, и изрядният остава без файл")
  void aHeaderGapStillStopsTheWholeFile() {
    Company company = tx(() -> fx.company("Без адрес", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration missing = withoutPoint9(contract);
    BgApp9Declaration ok = good(contract);
    tx(
        () -> {
          company.setAddress(null);
          return null;
        });
    BgApp9Export export = october(company);

    BgApp9Emission emission = generate(export);

    assertTrue(emission.isRefused());
    assertTrue(
        emission.getGaps().stream().anyMatch(g -> BgApp9Gap.FILE_LEVEL.equals(g.document)),
        emission.gapReport());
    assertNull(export.getBgNoiArtifact());
    assertNull(ok.getBgNoiArtifact(), "заглавната липса засяга и изрядния лист");
    assertNull(missing.getBgNoiArtifact());
    assertFalse(lineOf(export, ok).getBgIncluded());
  }

  @Test
  @DisplayName("Втори неуспешен опит → „Какво липсва“ е от НЕГО, не от първия (адресът отпада)")
  void theSecondFailedAttemptReplacesTheFirstReport() {
    Company company = tx(() -> fx.company("Два опита", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration missing = withoutPoint9(contract);
    Object address = tx(() -> company.getAddress());
    tx(
        () -> {
          company.setAddress(null);
          return null;
        });
    BgApp9Export export = october(company);

    assertTrue(generate(export).isRefused());
    assertTrue(export.getBgEmissionGaps().contains("BulAddressFull"), export.getBgEmissionGaps());
    String firstAttempt = export.getBgLastAttempt();
    assertNotNull(firstAttempt);

    // Адресът е поправен, а листът още няма т. 9.
    tx(
        () -> {
          company.setAddress((com.axelor.apps.base.db.Address) address);
          return null;
        });
    assertTrue(generate(export).isRefused());

    assertFalse(
        export.getBgEmissionGaps().contains("BulAddressFull"),
        "старият отказ за адреса не бива да остава: " + export.getBgEmissionGaps());
    assertTrue(export.getBgEmissionGaps().contains("т. 9"), export.getBgEmissionGaps());
    assertFalse(
        missing.getBgEmissionGaps().contains("BulAddressFull"), missing.getBgEmissionGaps());
    assertTrue(export.getBgLastAttempt().contains("ОТКАЗ"), export.getBgLastAttempt());
    assertNull(export.getBgNoiArtifact(), "полуготов износ не се записва");
  }

  /** Втори договор на СЪЩОТО лице в същото дружество. */
  private static EmploymentContract secondContractOf(EmploymentContract first) {
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(first.getEmployee());
    contract.setPayCompany(first.getPayCompany());
    contract.setBgInsuredTypeCode(first.getBgInsuredTypeCode());
    JPA.em().persist(contract);
    return contract;
  }

  @Test
  @DisplayName(
      "Лице с два договора, липси само в едното удостоверение → изключено е САМО то, не и"
          + " изрядното със същия етикет")
  void withTwoContractsOnlyTheDeclarationWithGapsIsLeftOut() throws Exception {
    Company company = tx(() -> fx.company("Два договора", LocalDate.of(2026, 9, 1)));
    EmploymentContract first = tx(() -> fx.contract(company, "01"));
    EmploymentContract second = tx(() -> secondContractOf(first));
    BgApp9Declaration ok = good(first);
    // Същият болничен лист, отразен и по втория договор — но без т. 9.
    BgApp9Declaration missing =
        tx(
            () -> {
              BgSickLeave sick = fx.sickLeave(second, LocalDate.of(2026, 10, 13));
              sick.setBgDocumentNumber(ok.getSickLeave().getBgDocumentNumber());
              sick.getBgPeriodLineList().clear();
              Beans.get(BgSickLeaveRepository.class).save(sick);
              return BgApp9DbFixture.declaration(sick);
            });
    assertEquals(
        BgApp9MandatoryPoints.label(ok),
        BgApp9MandatoryPoints.label(missing),
        "котвата: двете удостоверения имат ЕДИН етикет");
    BgApp9Export export = october(company);

    BgApp9Emission emission = generate(export);

    assertFalse(emission.isRefused(), emission.gapReport());
    assertEquals(1, emission.getDocumentCount(), "изрядното влиза");
    assertEquals(1, count(xmlOf(export), "<BSickLeave>"));
    assertNotNull(ok.getBgNoiArtifact());
    assertEquals("GENERATED", ok.getStatusSelect());
    assertTrue(lineOf(export, ok).getBgIncluded());
    assertNull(ok.getBgEmissionGaps(), "липсите на другия договор не са негови");

    assertNull(missing.getBgNoiArtifact());
    assertFalse(lineOf(export, missing).getBgIncluded());
    assertTrue(missing.getBgLastAttempt().contains("ИЗКЛЮЧЕНО"), missing.getBgLastAttempt());
    assertEquals(1, emission.excludedDocuments().size());
  }

  @Test
  @DisplayName(
      "Отказ от схемата → отчетът казва, че файл НЯМА; нищо не е отбелязано като влязло във файла")
  void aSchemaRefusalLeavesNoTraceOfAFile() {
    Company company = tx(() -> fx.company("Отказ от схемата", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration missing = withoutPoint9(contract);
    BgApp9Declaration ok = good(contract);
    BgApp9Export export = october(company);
    // Втората врата спира всеки файл — истинският емитер, истинското ядро, само валидаторът казва
    // „не“.
    BgApp9Validator rejecting =
        new BgApp9Validator() {
          @Override
          public java.util.List<BgApp9Finding> validate(
              String xml, java.util.List<BgApp9Declaration> declarations) {
            return java.util.Collections.singletonList(
                BgApp9Finding.schema("BPril9", "тестова грешка от схемата"));
          }
        };
    BgApp9ExportService service =
        new BgApp9ExportService(
            Beans.get(com.axelor.apps.napsubmissions.common.artifact.NapArtifactService.class),
            rejecting);

    BgApp9Emission emission =
        tx(
            () -> {
              try {
                return service.generate(export);
              } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
              }
            });

    assertTrue(emission.isRefused());
    assertNull(export.getBgNoiArtifact());
    assertNull(ok.getBgNoiArtifact());
    assertNotEquals("GENERATED", ok.getStatusSelect());
    assertNotNull(export.getBgValidationReport(), "грешките на схемата са в „Проверки“");

    String report = export.getBgEmissionGaps();
    assertFalse(report.startsWith("Файлът е генериран"), report);
    assertTrue(report.startsWith("Файлът НЕ е генериран"), report);
    assertTrue(report.contains(BgApp9MandatoryPoints.label(missing)), "изключеният — отделно");
    assertTrue(
        export.getBgLastAttempt().contains("ОТКАЗ от проверката"), export.getBgLastAttempt());

    // Нищо не е „влязло във файла“ — нито в дневника, нито върху удостоверенията.
    assertFalse(lineOf(export, ok).getBgIncluded());
    assertFalse(lineOf(export, missing).getBgIncluded());
    assertFalse(ok.getBgLastAttempt().contains("влиза в файла"), ok.getBgLastAttempt());
    assertTrue(ok.getBgLastAttempt().contains("ОТКАЗ"), ok.getBgLastAttempt());
  }

  @Test
  @DisplayName("Успешен месечен износ изчиства стария отказ върху удостоверението, което влиза")
  void aSuccessfulExportClearsTheStaleReportOfAnIncludedDeclaration() {
    Company company = tx(() -> fx.company("Стар отказ", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration ok = good(contract);
    tx(
        () -> {
          ok.setBgEmissionGaps("[! ЛИПСВА] заглавна част на файла · адрес на осигурителя — стар");
          return null;
        });
    BgApp9Export export = october(company);

    assertFalse(generate(export).isRefused());

    assertNull(ok.getBgEmissionGaps(), "предишният отказ не бива да виси върху влязло");
    assertTrue(ok.getBgLastAttempt().contains("Октомври " + fx.tag), ok.getBgLastAttempt());
    assertTrue(ok.getBgLastAttempt().contains("влиза в файла"), ok.getBgLastAttempt());
  }
}
