package com.axelor.apps.bglocalization.service.napartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgArt73Para1Report;
import com.axelor.apps.bglocalization.listener.BgLocalizationServerStartListener;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * СТАРТОВАТА СТЪПКА ПОПЪЛВА ПРАЗНОТО {@code NapArtifact.company} — по трите стъпки на миграцията,
 * само еднозначното, и второ пускане не прави нищо.
 *
 * <p>Заварените файлове се правят както ги оставя ddl=update: създадени без дружество, а връзката
 * към отчета се слага с масов UPDATE (покрай пазача на репозиторито — точно както стоят в базата).
 *
 * <p>Случаите: отчет (А) · връзка с подаване (Б) · файл на подаване — полезен товар и подпис (А) ·
 * износ по Прил. 9 (Б) · справка по чл. 73 ал. 1 (А) · двусмислен (подаване Б + файл на подаване А)
 * · схема на SAF-T · без източник · вече попълнен (Б, вързан с подаване А) — не се пипа.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}. Базата е една за целия
 * клас; броячите се сравняват само за ПОПЪЛНЕНОТО в това пускане, а двусмислените и празните — с
 * долна граница.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgNapArtifactCompanyBackfillTest extends JpaSupport {

  /** id-тата на фикстурата. */
  private static final class Scene {
    Long a;
    Long b;
    Long report;
    Long link;
    Long payload;
    Long signature;
    Long app9;
    Long art73;
    Long ambiguous;
    Long schema;
    Long orphan;
    Long preset;
  }

  private Scene scene() {
    Scene s = new Scene();
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          try {
            Company a = company("А" + tag);
            Company b = company("Б" + tag);
            s.a = a.getId();
            s.b = b.getId();

            s.report = blank("report", tag);
            s.link = blank("link", tag);
            s.payload = blank("payload", tag);
            s.signature = blank("signature", tag);
            s.app9 = blank("app9", tag);
            s.art73 = blank("art73", tag);
            s.ambiguous = blank("ambiguous", tag);
            s.schema =
                Beans.get(NapArtifactService.class)
                    .createFromFile(file("schema", tag), NapArtifactRepository.TYPE_SCHEMA)
                    .getId();
            s.orphan = blank("orphan", tag);
            s.preset = blank("preset", tag);

            SaftReport report = new SaftReport();
            report.setCompany(a);
            report.setName("SAF-T " + tag);
            JPA.em().persist(report);
            // Масовият UPDATE не изпраща чужда таблица преди себе си — отчетът трябва да е в
            // базата.
            JPA.em().flush();
            JPA.em()
                .createQuery("UPDATE NapArtifact x SET x.saftReport = :r WHERE x.id = :id")
                .setParameter("r", report)
                .setParameter("id", s.report)
                .executeUpdate();
            JPA.em()
                .createQuery("UPDATE NapArtifact x SET x.company = :c WHERE x.id = :id")
                .setParameter("c", b)
                .setParameter("id", s.preset)
                .executeUpdate();

            NapSubmission ofA = submission(a, tag);
            NapSubmission ofB = submission(b, tag);
            link(ofB, s.link);
            link(ofB, s.ambiguous);
            link(ofA, s.preset);
            submissionFile(ofA, s.payload, s.signature);
            submissionFile(ofA, s.ambiguous, null);

            BgApp9Export export = new BgApp9Export();
            export.setCompany(b);
            export.setStatusSelect("GENERATED");
            export.setBgNoiArtifact(JPA.em().find(NapArtifact.class, s.app9));
            JPA.em().persist(export);

            BgArt73Para1Report art73 = new BgArt73Para1Report();
            art73.setCompany(a);
            art73.setTaxYear(2026);
            art73.setStatusSelect("GENERATED");
            art73.setGeneratedArtifact(JPA.em().find(NapArtifact.class, s.art73));
            JPA.em().persist(art73);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    JPA.clear();
    return s;
  }

  private static Company company(String name) {
    Company company = new Company();
    company.setName("Фирма " + name);
    company.setCode("BF" + name);
    JPA.em().persist(company);
    return company;
  }

  private static RegulatoryFile file(String name, String tag) {
    String fileName = name + "-" + tag + ".xml";
    return new RegulatoryFile(
        Institution.NAP, fileName, "text/xml", fileName.getBytes(StandardCharsets.UTF_8));
  }

  /** Заварен файл: без дружество, без отчет. */
  private static Long blank(String name, String tag) throws Exception {
    return Beans.get(NapArtifactService.class)
        .createFromFile(file(name, tag), NapArtifactRepository.TYPE_PAYLOAD)
        .getId();
  }

  private static NapSubmission submission(Company company, String tag) {
    NapSubmission submission = new NapSubmission();
    submission.setCompany(company);
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
    submission.setName("Подаване " + tag);
    JPA.em().persist(submission);
    return submission;
  }

  private static void link(NapSubmission submission, Long artifactId) {
    NapSubmissionArtifact link = new NapSubmissionArtifact();
    link.setSubmission(submission);
    link.setArtifact(JPA.em().find(NapArtifact.class, artifactId));
    link.setRoleSelect("payload");
    JPA.em().persist(link);
  }

  private static void submissionFile(NapSubmission submission, Long payload, Long signature) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setSubmission(submission);
    file.setPayloadArtifact(JPA.em().find(NapArtifact.class, payload));
    if (signature != null) {
      file.setSignatureArtifact(JPA.em().find(NapArtifact.class, signature));
    }
    JPA.em().persist(file);
  }

  private static Long companyOf(Long artifactId) {
    Company company = JPA.em().find(NapArtifact.class, artifactId).getCompany();
    return company == null ? null : company.getId();
  }

  private static Integer versionOf(Long artifactId) {
    return JPA.em().find(NapArtifact.class, artifactId).getVersion();
  }

  @Test
  @DisplayName(
      "Първо пускане: отчет, подаване, документ — по едно дружество; двусмисленото и схемата остават празни; второ — нищо")
  void fillsOnlyTheUnambiguousAndIsIdempotent() {
    Scene s = scene();
    BgNapArtifactCompanyBackfill backfill = Beans.get(BgNapArtifactCompanyBackfill.class);

    BgNapArtifactCompanyBackfill.Result first = backfill.run();
    JPA.clear();

    assertEquals(s.a, companyOf(s.report), "от отчета");
    assertEquals(s.b, companyOf(s.link), "от връзката с подаването");
    assertEquals(s.a, companyOf(s.payload), "полезният товар на файла на подаването");
    assertEquals(s.a, companyOf(s.signature), "подписът на файла на подаването");
    assertEquals(s.b, companyOf(s.app9), "от износа по Прил. 9");
    assertEquals(s.a, companyOf(s.art73), "от справката по чл. 73");
    assertNull(companyOf(s.ambiguous), "двусмислен (Б и А) — остава празен");
    assertNull(companyOf(s.schema), "схемата — нарочно без дружество");
    assertNull(companyOf(s.orphan), "без източник");
    assertEquals(s.b, companyOf(s.preset), "вече попълненият не се пипа");

    assertEquals(1, first.fromReport, "от отчет");
    assertEquals(3, first.fromSubmission, "от подаване");
    assertEquals(2, first.fromDocument, "от документ");
    assertTrue(first.ambiguous >= 1, "двусмислени: " + first.ambiguous);
    assertTrue(first.blankAfter >= 3, "празни след: " + first.blankAfter);
    assertTrue(first.schemaBlank >= 1, "схема: " + first.schemaBlank);

    Integer version = versionOf(s.report);
    BgNapArtifactCompanyBackfill.Result second = backfill.run();
    JPA.clear();

    assertEquals(0, second.filled(), "второ пускане не попълва нищо");
    assertEquals(first.blankAfter, second.blankAfter, "празните са същите");
    assertEquals(version, versionOf(s.report), "второ пускане не мести version");
  }

  @Test
  @DisplayName("Стартовият listener вика попълването")
  void startupListenerRunsTheBackfill() {
    Scene s = scene();

    new BgLocalizationServerStartListener().ensureNapArtifactCompanies();
    JPA.clear();

    assertEquals(s.a, companyOf(s.report));
    assertEquals(s.b, companyOf(s.app9));
  }
}
