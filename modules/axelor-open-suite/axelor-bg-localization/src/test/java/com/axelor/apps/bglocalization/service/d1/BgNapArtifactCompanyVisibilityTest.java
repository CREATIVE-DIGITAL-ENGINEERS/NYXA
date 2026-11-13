package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.common.submission.ManualSubmissionService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.FileDocumentType;
import com.axelor.apps.napsubmissions.services.saft.SaftReportWorkflowService;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.db.JpaSecurity.AccessType;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ФАЙЛЪТ НА Д1 (И ПОДПИСЪТ, И ПРОТОКОЛЪТ) СЕ ВИЖДА ОТ РОЛЯТА НА СВОЕТО ДРУЖЕСТВО — И САМО ОТ НЕЯ.
 *
 * <p>Условието в каталога е {@code NapArtifact.company}, а не {@code self.saftReport.company}:
 * файловете на Д1/Д6/Прил. 9 се създават със {@code saftReport = null} и по него никоя роля освен
 * admin не би ги отваряла. Тук файловете се правят през ИСТИНСКИТЕ пътища ({@code
 * BgD1SubmissionService#persist}, {@code ManualSubmissionService#attachResponseFile}, {@code
 * SaftReportWorkflowService#attachPreparedArtifact}, генерираният файл с дружество), а правата са
 * ПРОИЗВОДСТВЕНИТЕ — редовете от {@code nx_permission.csv} през {@link BgRoleLogin}.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgNapArtifactCompanyVisibilityTest extends JpaSupport {

  /** Файловете на едно дружество. */
  private static final class Files {
    Long company;
    Long d1;
    Long signature;
    Long protocol;
    Long saft;
    Long app9;
  }

  private Files a;
  private Files b;

  @BeforeEach
  void scene() {
    a = filesOf("А");
    b = filesOf("Б");
    JPA.clear();
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private Files filesOf(String label) {
    Files files = new Files();
    String tag = label + System.nanoTime();
    JPA.runInTransaction(
        () -> {
          try {
            Company company = new Company();
            company.setName("Фирма " + tag);
            company.setCode("NA" + tag);
            JPA.em().persist(company);
            files.company = company.getId();

            // Д1: подаване-чернова и файлът му — през истинския persist на Д1.
            NapSubmission submission = new NapSubmission();
            submission.setCompany(company);
            submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
            submission.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
            submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
            submission.setName("Д1/Д6 " + tag);
            JPA.em().persist(submission);
            NapSubmissionFile file =
                Beans.get(BgD1SubmissionService.class)
                    .persist(
                        submission,
                        new BgD1SubmissionPlan.PlannedFile(
                            1, "EMPL2021.TXT", FileDocumentType.DEC_1, 1, bytes("D1 " + tag)));
            JPA.em().persist(file);
            files.d1 = file.getPayloadArtifact().getId();

            // Подписът — същата врата, през която QesSigningService записва подписа.
            files.signature =
                Beans.get(NapArtifactService.class)
                    .createFromFile(
                        new RegulatoryFile(
                            Institution.NAP, "EMPL2021.TXT.p7s", null, bytes("p7s " + tag)),
                        NapArtifactRepository.TYPE_SIGNATURE,
                        submission.getCompany())
                    .getId();

            // Протоколът от НАП — през ръчното закачане на отговор към подаването.
            files.protocol =
                Beans.get(ManualSubmissionService.class)
                    .attachResponseFile(
                        submission,
                        upload("protocol.pdf", tag),
                        NapArtifactRepository.TYPE_PROTOCOL)
                    .getId();

            // SAF-T: файлът на отчета — както досега, през отчета.
            SaftReport report = new SaftReport();
            report.setCompany(company);
            report.setName("SAF-T " + tag);
            JPA.em().persist(report);
            files.saft =
                Beans.get(SaftReportWorkflowService.class)
                    .attachPreparedArtifact(report, upload("saft.xml", tag))
                    .getId();

            // Прил. 9: генериран файл за НОИ, с дружеството на износа.
            files.app9 =
                Beans.get(NapArtifactService.class)
                    .createFromFile(
                        new RegulatoryFile(
                            Institution.NOI, "Pril9.xml", "text/xml", bytes("app9 " + tag)),
                        NapArtifactRepository.TYPE_PAYLOAD,
                        company)
                    .getId();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    return files;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static MetaFile upload(String name, String tag) throws java.io.IOException {
    return Beans.get(MetaFiles.class)
        .upload(new ByteArrayInputStream(bytes(name + " " + tag)), name);
  }

  private static boolean reads(Long artifactId) {
    return Beans.get(JpaSecurity.class).isPermitted(AccessType.READ, NapArtifact.class, artifactId);
  }

  private static Company company(Long id) {
    return JPA.em().find(Company.class, id);
  }

  @Test
  @DisplayName("Всеки път на създаване е записал дружеството на документа/подаването")
  void everyPathStampsTheCompany() {
    for (Long id : new Long[] {a.d1, a.signature, a.protocol, a.saft, a.app9}) {
      NapArtifact artifact = JPA.em().find(NapArtifact.class, id);
      assertEquals(a.company, artifact.getCompany().getId(), artifact.getName());
    }
    assertNull(JPA.em().find(NapArtifact.class, a.d1).getSaftReport(), "Д1 е без отчет");
  }

  @Test
  @DisplayName(
      "ТРЗ (hr.payroll.read): Д1, подписът и протоколът на своето дружество — да; на чуждото — не")
  void payrollReadSeesItsOwnD1FilesOnly() {
    BgRoleLogin.login(company(a.company), "hr.payroll.read");

    assertTrue(reads(a.d1), "файлът на Д1");
    assertTrue(reads(a.signature), "подписът");
    assertTrue(reads(a.protocol), "протоколът от НАП");

    assertFalse(reads(b.d1), "чуждият файл на Д1");
    assertFalse(reads(b.signature), "чуждият подпис");
    assertFalse(reads(b.protocol), "чуждият протокол");
  }

  @Test
  @DisplayName("НОИ (hr.noi.read): файлът на Прил. 9 на своето дружество — да; на чуждото — не")
  void noiReadSeesItsOwnApp9FileOnly() {
    BgRoleLogin.login(company(a.company), "hr.noi.read");

    assertTrue(reads(a.app9), "файлът на Прил. 9");
    assertFalse(reads(b.app9), "чуждият файл на Прил. 9");
  }

  @Test
  @DisplayName("НАП (accounting.nap.read): файлът на SAF-T отчета остава видим както досега")
  void napReadStillSeesTheSaftFile() {
    BgRoleLogin.login(company(a.company), "accounting.nap.read");

    assertTrue(reads(a.saft), "файлът на SAF-T отчета");
    assertFalse(reads(b.saft), "чуждият SAF-T файл");
  }

  @Test
  @DisplayName("Без роля — нищо")
  void withoutARoleNothing() {
    BgRoleLogin.login(company(a.company));

    assertFalse(reads(a.d1));
    assertFalse(reads(a.saft));
  }
}
