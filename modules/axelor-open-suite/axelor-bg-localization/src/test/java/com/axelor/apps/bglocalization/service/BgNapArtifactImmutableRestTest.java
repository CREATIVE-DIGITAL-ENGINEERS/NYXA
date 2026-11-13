package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityException;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionArtifact;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactManagementRepository;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.rpc.Request;
import com.axelor.rpc.Resource;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ДОКАЗАТЕЛСТВОТО НЕ СЕ ПРЕНАПИСВА — файлът, SHA-256, типът, дружеството и връзките (подаване,
 * отчет) на NapArtifact не се сменят през /ws/rest, през UI и през услугите, и за admin.
 *
 * <p>Те доказват, че ТОЧНО ТОЗИ файл е подаден. Празното {@code company} го попълват само
 * стартовата стъпка и миграцията (масов UPDATE).
 *
 * <p>Пътят на REST е ИСТИНСКИЯТ на {@code POST /ws/rest/<model>/<id>}: {@link Resource#save} от
 * инжектора — с проверките на правата и с {@code JPA.manage}, който прави flush ПРЕДИ {@code
 * repository.save}. Затова пазач само в репозиторито тук мълчи; хваща го пазачът на Hibernate
 * (PreUpdate, по старото състояние).
 *
 * <p><b>⚠️ Отказът трябва да е от НЕИЗМЕНИМОСТТА</b> ({@link RegulatoryImmutabilityException} във
 * веригата на причините, с името на полето) — не какъв да е. За потребител с роля Axelor така или
 * иначе отказва при смяна на дружеството, но по права.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgNapArtifactImmutableRestTest extends JpaSupport {

  private Long companyA;
  private Long companyB;
  private Long fileOfA;
  private Long blankFile;
  private Long otherMetaFile;
  private Long otherReport;
  private Long link;

  @BeforeEach
  void scene() {
    // Пазачът на услугите е в репозиторито — то трябва да е истинското, както в приложението.
    assertTrue(
        Beans.get(NapArtifactRepository.class) instanceof NapArtifactManagementRepository,
        "NapArtifactRepository е " + Beans.get(NapArtifactRepository.class).getClass());
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          try {
            Company a = company("А" + tag);
            Company b = company("Б" + tag);
            companyA = a.getId();
            companyB = b.getId();
            NapArtifactService service = Beans.get(NapArtifactService.class);
            fileOfA =
                service
                    .createFromFile(
                        file("EMPL2021-" + tag + ".TXT"), NapArtifactRepository.TYPE_PAYLOAD, a)
                    .getId();
            // Заварен файл без дружество — каквито оставя ddl=update преди попълването.
            blankFile =
                service
                    .createFromFile(file("old-" + tag + ".TXT"), NapArtifactRepository.TYPE_PAYLOAD)
                    .getId();
            otherMetaFile =
                Beans.get(MetaFiles.class)
                    .upload(
                        new ByteArrayInputStream(bytes("подменен " + tag)),
                        "forged-" + tag + ".TXT")
                    .getId();
            SaftReport report = new SaftReport();
            report.setCompany(a);
            report.setName("SAF-T " + tag);
            JPA.em().persist(report);
            otherReport = report.getId();
            NapSubmission submission = new NapSubmission();
            submission.setCompany(a);
            submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
            submission.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
            submission.setName("Подаване " + tag);
            JPA.em().persist(submission);
            link =
                service
                    .link(submission, JPA.em().find(NapArtifact.class, fileOfA), "payload")
                    .getId();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    JPA.clear();
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static Company company(String name) {
    Company company = new Company();
    company.setName("Фирма " + name);
    company.setCode("IM" + name);
    JPA.em().persist(company);
    return company;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static RegulatoryFile file(String name) {
    return new RegulatoryFile(Institution.NAP, name, null, bytes(name));
  }

  private static Map<String, Object> ref(Long id) {
    Map<String, Object> ref = new HashMap<>();
    ref.put("id", id);
    return ref;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Model> Resource<T> rest(Class<T> model) {
    return (Resource<T>)
        Beans.get(Injector.class)
            .getInstance(Key.get(Types.newParameterizedType(Resource.class, model)));
  }

  /** {@code POST /ws/rest/<model>/<id>} с едно поле — както го праща клиентът. */
  private static <T extends Model> Throwable save(
      Class<T> model, Long id, String field, Object value) {
    T current = JPA.em().find(model, id);
    Map<String, Object> data = new HashMap<>();
    data.put("id", id);
    data.put("version", current.getVersion());
    data.put(field, value);
    Request request = new Request();
    request.setModel(model.getName());
    request.setData(data);
    JPA.clear();
    try {
      rest(model).save(request);
      return null;
    } catch (Throwable t) {
      return t;
    } finally {
      JPA.clear();
    }
  }

  private static void assertImmutabilityRefusal(Throwable t, String field) {
    assertNotNull(t, "записът мина — " + field + " е сменено");
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof RegulatoryImmutabilityException) {
        assertTrue(c.getMessage().contains(field), c.getMessage());
        return;
      }
    }
    throw new AssertionError("отказът не е от неизменимостта на " + field + ", а: " + t, t);
  }

  private static NapArtifact artifact(Long id) {
    return JPA.em().find(NapArtifact.class, id);
  }

  private static Long companyOf(Long artifactId) {
    Company company = artifact(artifactId).getCompany();
    return company == null ? null : company.getId();
  }

  // --- company --------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Потребител с право за запис (accounting.nap.write) мести файла към друго дружество → отказ")
  void writerCannotMoveTheFile() {
    BgRoleLogin.login(JPA.em().find(Company.class, companyA), "accounting.nap.write");

    assertImmutabilityRefusal(
        save(NapArtifact.class, fileOfA, "company", ref(companyB)), "company");
    assertEquals(companyA, companyOf(fileOfA), "файлът остава на своето дружество");
  }

  @Test
  @DisplayName("Admin мести файла към друго дружество → отказ")
  void adminCannotMoveTheFile() {
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));

    assertImmutabilityRefusal(
        save(NapArtifact.class, fileOfA, "company", ref(companyB)), "company");
    assertEquals(companyA, companyOf(fileOfA));
  }

  @Test
  @DisplayName("Admin попълва празно company през REST → отказ (празното го попълва само сървърът)")
  void adminCannotFillABlankCompany() {
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));

    assertImmutabilityRefusal(
        save(NapArtifact.class, blankFile, "company", ref(companyA)), "company");
    assertNull(companyOf(blankFile), "празното остава празно");
  }

  // --- доказателството: файлът, SHA-256, типът, отчетът ----------------------------------------

  @Test
  @DisplayName("Admin сменя SHA-256 през REST → отказ")
  void adminCannotChangeTheSha256() {
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));
    String before = artifact(fileOfA).getSha256();

    assertImmutabilityRefusal(save(NapArtifact.class, fileOfA, "sha256", "0".repeat(64)), "sha256");
    assertEquals(before, artifact(fileOfA).getSha256());
  }

  @Test
  @DisplayName("Admin подменя файла (metaFile) през REST → отказ")
  void adminCannotSwapTheStoredFile() {
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));
    Long before = artifact(fileOfA).getMetaFile().getId();

    assertImmutabilityRefusal(
        save(NapArtifact.class, fileOfA, "metaFile", ref(otherMetaFile)), "metaFile");
    assertEquals(before, artifact(fileOfA).getMetaFile().getId());
  }

  @Test
  @DisplayName("Admin сменя типа и отчета през REST → отказ")
  void adminCannotChangeTheTypeOrTheReport() {
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));

    assertImmutabilityRefusal(
        save(NapArtifact.class, fileOfA, "artifactTypeSelect", NapArtifactRepository.TYPE_PROTOCOL),
        "artifactTypeSelect");
    assertImmutabilityRefusal(
        save(NapArtifact.class, fileOfA, "saftReport", ref(otherReport)), "saftReport");
    assertEquals(NapArtifactRepository.TYPE_PAYLOAD, artifact(fileOfA).getArtifactTypeSelect());
    assertNull(artifact(fileOfA).getSaftReport());
  }

  @Test
  @DisplayName("Admin пренасочва връзката подаване ↔ файл (NapSubmissionArtifact.artifact) → отказ")
  void adminCannotRedirectTheSubmissionLink() {
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));

    assertImmutabilityRefusal(
        save(NapSubmissionArtifact.class, link, "artifact", ref(blankFile)), "artifact");
    assertEquals(fileOfA, JPA.em().find(NapSubmissionArtifact.class, link).getArtifact().getId());
  }

  @Test
  @DisplayName("Услуга сменя SHA-256 на зареден файл без repository.save → отказ при flush")
  void aServiceCannotRewriteTheSha256EitherAtFlush() {
    Throwable t = null;
    try {
      JPA.runInTransaction(() -> artifact(fileOfA).setSha256("f".repeat(64)));
    } catch (Throwable e) {
      t = e;
    } finally {
      JPA.clear();
    }
    assertImmutabilityRefusal(t, "sha256");
    assertTrue(!"f".repeat(64).equals(artifact(fileOfA).getSha256()));
  }

  @Test
  @DisplayName("Името за показ НЕ е доказателство — admin може да го смени")
  void theDisplayNameStaysEditable() {
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));

    assertNull(save(NapArtifact.class, fileOfA, "name", "преименуван.TXT"));
    assertEquals("преименуван.TXT", artifact(fileOfA).getName());
  }
}
