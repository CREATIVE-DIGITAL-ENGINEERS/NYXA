package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgApp9ExportLine;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ИЗВЕДЕНИТЕ ТОЧКИ НА ПРИЛ. 9 — през истинските услуги и истинското генериране.
 *
 * <ul>
 *   <li>областта на осигурителя и на лицето — от града (ЕКАТТЕ), не от празното {@code department};
 *   <li>„вид подаване“ — „Представяне“ за първо удостоверение, без човек да го избира;
 *   <li>невалидиран лист — не се опреснява и не се изнася.
 * </ul>
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9DerivedPointsDbTest extends JpaSupport {

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

  private static BgApp9DeclarationService declarations() {
    return Beans.get(BgApp9DeclarationService.class);
  }

  private static BgApp9Emission generate(BgApp9Declaration declaration) {
    AtomicReference<BgApp9Emission> out = new AtomicReference<>();
    JPA.runInTransaction(
        () -> {
          try {
            out.set(Beans.get(BgApp9ExportService.class).generateForDeclaration(declaration));
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
    return out.get();
  }

  private BgSickLeave validatedSheet(Company company) {
    return tx(() -> fx.sickLeave(fx.contract(company, "01"), LocalDate.of(2026, 10, 13)));
  }

  private static void setStatus(BgSickLeave sick, String status) {
    JPA.runInTransaction(
        () -> {
          sick.setStatusSelect(status);
          Beans.get(BgSickLeaveRepository.class).save(sick);
        });
  }

  // ═══ ОБЛАСТТА ОТ ГРАДА, В ИСТИНСКИЯ ФАЙЛ ═════════════════════════════

  @Test
  @DisplayName("Файлът носи областта от града (ЕКАТТЕ) — и за осигурителя, и за лицето")
  void theFileCarriesTheRegionFromTheCity() throws Exception {
    Company company = tx(() -> fx.company("Област", LocalDate.of(2026, 9, 1)));
    BgSickLeave sick = validatedSheet(company);
    BgApp9Declaration d = tx(() -> BgApp9DbFixture.declaration(sick));

    BgApp9Emission emission = generate(d);

    assertFalse(emission.isRefused(), emission.gapReport());
    String xml =
        new String(
            Files.readAllBytes(MetaFiles.getPath(d.getBgNoiArtifact().getMetaFile())),
            StandardCharsets.UTF_8);
    assertTrue(xml.contains("<BulRegion>София (столица)</BulRegion>"), "областта на осигурителя");
    assertTrue(xml.contains("<BulSubRegion>Столична</BulSubRegion>"), "общината");
    assertTrue(xml.contains("<BulCity>София</BulCity>"), "населеното място");
    assertTrue(xml.contains("<EGNRegion>София (столица)</EGNRegion>"), "областта на лицето");
  }

  @Test
  @DisplayName("Град без ЕКАТТЕ на дружеството → отказ поименно, назовава града")
  void aCompanyCityWithoutEkatteIsRefusedByName() {
    Company company = tx(() -> fx.company("Без ЕКАТТЕ", LocalDate.of(2026, 9, 1)));
    tx(
        () -> {
          company.getAddress().getCity().setEkatte(null);
          return null;
        });
    BgSickLeave sick = validatedSheet(company);
    BgApp9Declaration d = tx(() -> BgApp9DbFixture.declaration(sick));

    BgApp9Emission emission = generate(d);

    assertTrue(emission.isRefused());
    BgApp9Gap gap =
        emission.getGaps().stream()
            .filter(g -> "BulAddressFull".equals(g.tag))
            .findFirst()
            .orElse(null);
    assertNotNull(gap, emission.gapReport());
    assertTrue(gap.reason.contains("няма ЕКАТТЕ"), gap.reason);
    assertTrue(gap.reason.contains("София"), "назовава града");
    assertNull(d.getBgNoiArtifact());
  }

  // ═══ ВИДЪТ ПОДАВАНЕ ══════════════════════════════════════════════════════

  @Test
  @DisplayName("Първо удостоверение → „Представяне“ без избор; подаденото и корекцията не се пипат")
  void theFirstCertificateIsASubmission() {
    Company company = tx(() -> fx.company("Представяне", LocalDate.of(2026, 9, 1)));
    BgSickLeave fresh = validatedSheet(company);

    declarations().createOrResolve(fresh);
    assertEquals("SUBMIT", fresh.getBgApp9Declaration().getBgSubmissionTypeSelect());

    BgSickLeave corrected = validatedSheet(company);
    BgApp9Declaration correction =
        tx(
            () -> {
              BgApp9Declaration d = BgApp9DbFixture.declaration(corrected);
              d.setBgSubmissionTypeSelect("CORRECT");
              return d;
            });
    declarations().createOrResolve(corrected);
    assertEquals("CORRECT", correction.getBgSubmissionTypeSelect(), "изборът на човека остава");

    BgSickLeave legacy = validatedSheet(company);
    BgApp9Declaration submitted =
        tx(
            () -> {
              BgApp9Declaration d = BgApp9DbFixture.declaration(legacy);
              d.setStatusSelect("SUBMITTED");
              d.setBgSubmissionTypeSelect(null);
              return d;
            });
    declarations().createOrResolve(legacy);
    assertNull(submitted.getBgSubmissionTypeSelect(), "подаденото не получава вид със задна дата");
  }

  // ═══ НЕВАЛИДИРАН ЛИСТ — НИТО ОПРЕСНЯВАНЕ, НИТО ИЗНОС ══════════════════════

  @Test
  @DisplayName("Лист, върнат в чернова → опресняването отказва, удостоверението не мърда")
  void refreshingAnUnvalidatedSheetIsRefused() {
    Company company = tx(() -> fx.company("Опресняване", LocalDate.of(2026, 9, 1)));
    BgSickLeave sick = validatedSheet(company);
    declarations().createOrResolve(sick);
    BgApp9Declaration d = sick.getBgApp9Declaration();
    LocalDateTime resolvedAt = d.getBgResolvedAt();

    setStatus(sick, "DRAFT");
    IllegalStateException refusal =
        assertThrows(IllegalStateException.class, () -> declarations().createOrResolve(sick));

    assertEquals(BgApp9DeclarationService.NOT_VALIDATED_REFUSAL, refusal.getMessage());
    assertEquals(resolvedAt, d.getBgResolvedAt(), "извеждането не е минало");
  }

  @Test
  @DisplayName("Лист, върнат в чернова → „Генерирай“ отказва поименно, файл не се ражда")
  void generatingForAnUnvalidatedSheetIsRefused() {
    Company company = tx(() -> fx.company("Износ чернова", LocalDate.of(2026, 9, 1)));
    BgSickLeave sick = validatedSheet(company);
    BgApp9Declaration d = tx(() -> BgApp9DbFixture.declaration(sick));
    setStatus(sick, "DRAFT");

    BgApp9Emission emission = generate(d);

    assertTrue(emission.isRefused());
    assertEquals("болничният лист не е валидиран", emission.getGaps().get(0).clause);
    assertEquals(BgApp9DeclarationService.NOT_VALIDATED_REFUSAL, emission.getGaps().get(0).reason);
    assertNull(d.getBgNoiArtifact());
  }

  @Test
  @DisplayName("Месечният износ оставя невалидирания лист вън — с причината в дневника, не тихо")
  void theMonthlyExportLeavesAnUnvalidatedSheetOut() throws Exception {
    Company company = tx(() -> fx.company("Пакет", LocalDate.of(2026, 9, 1)));
    BgSickLeave good = validatedSheet(company);
    BgSickLeave draft = validatedSheet(company);
    BgApp9Declaration goodDeclaration = tx(() -> BgApp9DbFixture.declaration(good));
    BgApp9Declaration draftDeclaration = tx(() -> BgApp9DbFixture.declaration(draft));
    setStatus(draft, "DRAFT");
    BgApp9Export export =
        tx(
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

    BgApp9Emission emission =
        tx(
            () -> {
              try {
                return Beans.get(BgApp9ExportService.class).generate(export);
              } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
              }
            });

    assertFalse(emission.isRefused(), emission.gapReport());
    assertEquals(1, emission.getDocumentCount(), "само валидираният влиза във файла");
    assertNotNull(goodDeclaration.getBgNoiArtifact());
    assertNull(draftDeclaration.getBgNoiArtifact(), "черновата не получава файл");
    BgApp9ExportLine draftLine =
        export.getBgExportLineList().stream()
            .filter(l -> l.getBgApp9Declaration() == draftDeclaration)
            .findFirst()
            .get();
    assertFalse(draftLine.getBgIncluded());
    assertTrue(draftLine.getBgNote().contains("валидиран"), draftLine.getBgNote());
  }
}
