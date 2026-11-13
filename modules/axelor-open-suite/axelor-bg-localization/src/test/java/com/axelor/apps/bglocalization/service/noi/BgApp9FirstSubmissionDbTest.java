package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgApp9ExportLine;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.auth.db.User;
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
 * Т. 26 („първо представяне“) СЕ СВЕРЯВА ПРИ ГЕНЕРИРАНЕ с днешните подадени удостоверения.
 *
 * <p>Приложение № 1 към функционалните изисквания на НОИ (Заповед № 1016-40-732/05.08.2022) —
 * {@code Pril_1_Format_Kontrol_Nomecl_03_2024.xls}, nssi.bg, записан на 07.03.2024; лист {@code
 * Pril9_Boln}, ред 205, BankAccountDeclared: „0 – декларира, 1 – има декларирана в НОИ сметка …
 * Задължително се декларира сметка при първото представяне“.
 *
 * <p>Удостоверение A на лицето се отбелязва като подадено → записаното при B „ДА — декларира“
 * остарява. Генерирането НЕ го преизчислява тихо: отказ, който назовава B и бутона.
 *
 * <p>⚠️ Два листа на едно лице в един файл, нищо подадено: и двата декларират (0). Да се потвърди с
 * НОИ дали второто деклариране не се отхвърля като повторно.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9FirstSubmissionDbTest extends JpaSupport {

  private static final LocalDate PORTAL_DAY = LocalDate.of(2026, 11, 3);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

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

  private BgApp9Declaration sheet(EmploymentContract contract) {
    return tx(
        () -> BgApp9DbFixture.declaration(fx.sickLeave(contract, LocalDate.of(2026, 10, 13))));
  }

  private BgApp9Emission generate(BgApp9Declaration declaration) {
    return tx(
        () -> {
          try {
            return Beans.get(BgApp9ExportService.class).generateForDeclaration(declaration);
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
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

  /** A се генерира и отбелязва като подадено. */
  private void submit(BgApp9Declaration a, Company company) {
    assertFalse(generate(a).isRefused(), "фикстурата трябва да генерира A");
    User clerk = tx(() -> fx.user("clerk", true, company));
    Beans.get(BgApp9SubmissionService.class)
        .markSubmitted(a.getBgNoiArtifact(), "ВХ-26-" + fx.tag, PORTAL_DAY, clerk, TODAY);
    assertEquals("SUBMITTED", a.getStatusSelect());
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

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      n++;
    }
    return n;
  }

  private static String xmlOf(BgApp9Export export) throws Exception {
    return new String(
        Files.readAllBytes(MetaFiles.getPath(export.getBgNoiArtifact().getMetaFile())),
        StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("т. 26: A е подадено → B с остаряло „ДА — декларира“ се отказва поименно, без файл")
  void aStaleFirstSubmissionIsRefusedByName() {
    Company company = tx(() -> fx.company("Т. 26", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration a = sheet(contract);
    BgApp9Declaration b = sheet(contract);
    assertEquals("YES", b.getBgP26FirstSubmissionSelect(), "котвата: изведено преди A");

    submit(a, company);
    BgApp9Emission emission = generate(b);

    assertTrue(emission.isRefused());
    BgApp9Gap gap =
        emission.getGaps().stream()
            .filter(g -> "BankAccountDeclared".equals(g.tag))
            .findFirst()
            .orElse(null);
    assertNotNull(gap, emission.gapReport());
    assertEquals(BgApp9MandatoryPoints.label(b), gap.document, "назовава удостоверението");
    assertTrue(gap.reason.contains("ОСТАРЯЛА"), gap.reason);
    assertTrue(gap.reason.contains("„Изведи/опресни обстоятелствата“"), gap.reason);
    assertNull(b.getBgNoiArtifact(), "без файл");
    assertEquals("YES", b.getBgP26FirstSubmissionSelect(), "стойността НЕ е преизчислена тихо");
  }

  @Test
  @DisplayName("т. 26: в месечния износ остарялото B остава вън, а изрядното C влиза")
  void inTheMonthlyExportTheStaleSheetIsLeftOut() throws Exception {
    Company company = tx(() -> fx.company("Т. 26 месечен", LocalDate.of(2026, 9, 1)));
    EmploymentContract person = tx(() -> fx.contract(company, "01"));
    EmploymentContract other = tx(() -> fx.contract(company, "01"));
    BgApp9Declaration a = sheet(person);
    BgApp9Declaration b = sheet(person);
    BgApp9Declaration c = sheet(other);
    submit(a, company);
    BgApp9Export export = october(company);

    BgApp9Emission emission = generate(export);

    assertFalse(emission.isRefused(), emission.gapReport());
    assertEquals(1, emission.getDocumentCount(), "само C");
    assertNotNull(c.getBgNoiArtifact());
    assertNull(b.getBgNoiArtifact());
    BgApp9ExportLine line =
        export.getBgExportLineList().stream()
            .filter(l -> l.getBgApp9Declaration() == b)
            .findFirst()
            .get();
    assertFalse(line.getBgIncluded());
    assertTrue(line.getBgNote().contains("т. 26 · първо представяне"), line.getBgNote());
  }

  @Test
  @DisplayName("т. 26: два листа на едно лице в един файл, нищо подадено → и двата ДЕКЛАРИРАТ (0)")
  void twoSheetsOfOnePersonInOneFileBothDeclare() throws Exception {
    Company company = tx(() -> fx.company("Т. 26 двата", LocalDate.of(2026, 9, 1)));
    EmploymentContract person = tx(() -> fx.contract(company, "01"));
    sheet(person);
    sheet(person);
    BgApp9Export export = october(company);

    BgApp9Emission emission = generate(export);

    assertFalse(emission.isRefused(), emission.gapReport());
    assertEquals(2, emission.getDocumentCount());
    assertEquals(2, count(xmlOf(export), "<BankAccountDeclared>0</BankAccountDeclared>"));
  }
}
