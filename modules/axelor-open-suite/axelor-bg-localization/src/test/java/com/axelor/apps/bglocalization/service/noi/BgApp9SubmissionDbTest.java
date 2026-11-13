package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityException;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapAuditEvent;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
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
 * ОТМЕТКАТА „ПОДАДЕНО В НОИ“, ОТМЯНАТА И ПАЗАЧЪТ СРЕЩУ ПРЕЗАПИСА — през истинските услуги.
 *
 * <p>Генерирането е истинското ({@link BgApp9ExportService}: емитер, офлайн XSD и контроли,
 * неизменяем артефакт), тоест свидетелите за корекцията и повторния износ мерят байтове, не
 * флагове.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9SubmissionDbTest extends JpaSupport {

  private static final LocalDate TRACK_FROM = LocalDate.of(2026, 9, 1);
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

  private static BgApp9SubmissionService submissions() {
    return Beans.get(BgApp9SubmissionService.class);
  }

  /** Лист + пълно удостоверение + (по желание) файл, закачен като текущ. */
  private final class Case {
    Company company;
    EmploymentContract contract;
    BgSickLeave sick;
    BgApp9Declaration declaration;
    NapArtifact artifact;
    User clerk;

    Case(String name, boolean withFile) {
      tx(
          () -> {
            company = fx.company(name, TRACK_FROM);
            contract = fx.contract(company, "01");
            sick = fx.sickLeave(contract, LocalDate.of(2026, 10, 13));
            declaration = BgApp9DbFixture.declaration(sick);
            if (withFile) {
              artifact = fx.artifact("<Pril9 test=\"" + fx.tag + "\"/>");
              declaration.setBgNoiArtifact(artifact);
              declaration.setStatusSelect("GENERATED");
            }
            clerk = fx.user("clerk", true, company);
            return null;
          });
    }
  }

  private static long submissionsOf(Company company) {
    return JPA.em()
        .createQuery("SELECT COUNT(s) FROM NapSubmission s WHERE s.company = :c", Long.class)
        .setParameter("c", company)
        .getSingleResult();
  }

  private static long auditOf(Company company) {
    return JPA.em()
        .createQuery(
            "SELECT COUNT(e) FROM NapAuditEvent e WHERE e.submission.company = :c", Long.class)
        .setParameter("c", company)
        .getSingleResult();
  }

  // ═══ ОТКАЗИТЕ НЕ ПИШАТ НИЩО ══════════════════════════════════════════════

  @Test
  @DisplayName(
      "Без файл, без вх. №, без дата, бъдеща дата, без роля, чужда фирма → отказ, нищо не е записано")
  void refusalsWriteNothing() {
    Case c = new Case("Откази", true);
    Case noFile = new Case("Без файл", false);
    User noRole = tx(() -> fx.user("norole", false, c.company));
    User otherCompany = tx(() -> fx.user("other", true, noFile.company));

    assertRefused("Няма генериран файл", () -> mark(null, "123", PORTAL_DAY, c.clerk));
    assertRefused(
        "Няма генериран файл",
        () -> mark(noFile.declaration.getBgNoiArtifact(), "123", PORTAL_DAY, noFile.clerk));
    assertRefused("Входящият номер", () -> mark(c.artifact, "   ", PORTAL_DAY, c.clerk));
    assertRefused("Датата на подаване", () -> mark(c.artifact, "123", null, c.clerk));
    assertRefused("в бъдещето", () -> mark(c.artifact, "123", TODAY.plusDays(1), c.clerk));
    assertRefused("Нямате право", () -> mark(c.artifact, "123", PORTAL_DAY, noRole));
    assertRefused(
        "не е активното ви дружество", () -> mark(c.artifact, "123", PORTAL_DAY, otherCompany));

    assertEquals(0L, submissionsOf(c.company), "нито едно подаване");
    assertEquals(0L, auditOf(c.company), "нито един одит");
    assertEquals("GENERATED", c.declaration.getStatusSelect(), "статусът не е мръднал");
    assertNull(c.declaration.getBgNoiSubmission());
  }

  // ═══ УСПЕШНАТА ОТМЕТКА ═══════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Успешна отметка: подаване с вх. № и дата, файлът е ТОЧНО артефактът, одит с потребителя")
  void aSuccessfulMarkWritesTheSubmissionAndTheAudit() {
    Case c = new Case("Отметка", true);
    assertEquals(BgApp9DueScope.STATE_GENERATED, BgApp9DbFixture.submissionState(c.sick));

    NapSubmission s = mark(c.artifact, " ВХ-2026-0042 ", PORTAL_DAY, c.clerk);

    assertEquals(NapSubmissionRepository.SERVICE_NOI_APP9, s.getServiceCodeSelect());
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, s.getSubmissionStatusSelect());
    assertEquals(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL, s.getChannelSelect());
    assertEquals("ВХ-2026-0042", s.getNapIncomingNumber());
    assertEquals(PORTAL_DAY.atStartOfDay(), s.getSubmittedOn(), "датата от портала, не „сега“");
    assertSame(c.clerk, s.getSubmittedBy());
    assertSame(c.company, s.getCompany());

    NapSubmissionFile file =
        JPA.all(NapSubmissionFile.class).filter("self.submission = ?", s).fetchOne();
    assertSame(c.artifact, file.getPayloadArtifact(), "отметката сочи ТОЧНО генерирания файл");

    assertSame(s, c.declaration.getBgNoiSubmission());
    assertEquals("SUBMITTED", c.declaration.getStatusSelect());
    assertEquals(BgApp9DueScope.STATE_SUBMITTED, BgApp9DbFixture.submissionState(c.sick));

    NapAuditEvent audit =
        JPA.all(NapAuditEvent.class)
            .filter(
                "self.submission = ? AND self.eventTypeSelect = ?",
                s,
                NapAuditEventRepository.EVENT_MANUAL_SUBMISSION)
            .fetchOne();
    assertNotNull(audit, "одит manual_submission");
    assertSame(c.clerk, audit.getActorUser(), "кой е отбелязал");
    assertTrue(audit.getDetails().contains(c.artifact.getSha256()), "одитът носи SHA-256 на файла");
    assertNotNull(audit.getCreatedOn(), "кога е отбелязал");
  }

  @Test
  @DisplayName(
      "Отметката закача файла ПРЕДИ заключването; файл към вече подадено → отказ от защитата")
  void theFileGoesInBeforeTheLockAndTheGuardStillRefusesALateFile() {
    Case c = new Case("Защита", true);
    NapSubmission s = mark(c.artifact, "ВХ-21", PORTAL_DAY, c.clerk);

    // Записаното в базата: заключено подаване с ТОЧНО един файл.
    String committed =
        JPA.em()
            .createQuery(
                "SELECT x.submissionStatusSelect FROM NapSubmission x WHERE x.id = :id",
                String.class)
            .setParameter("id", s.getId())
            .getSingleResult();
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, committed);
    assertEquals(1L, filesOf(s));

    // Защитата на подаденото не е разхлабена: втори файл към подаденото се отказва от нея.
    RegulatoryImmutabilityException e =
        assertThrows(
            RegulatoryImmutabilityException.class,
            () ->
                tx(
                    () -> {
                      NapSubmissionFile late = new NapSubmissionFile();
                      late.setSubmission(s);
                      late.setPayloadArtifact(c.artifact);
                      late.setSequence(1);
                      late.setSubmittedFileName("късен.xml");
                      late.setFileTypeSelect("xml");
                      late.setFileStatusSelect(NapSubmissionFileRepository.FILE_STATUS_PENDING);
                      return Beans.get(NapSubmissionFileRepository.class).save(late);
                    }));
    assertTrue(e.getMessage().contains("Cannot add a file to a locked submission"), e.getMessage());
    assertEquals(1L, filesOf(s), "отказаният файл не е записан");
  }

  private static long filesOf(NapSubmission submission) {
    return JPA.em()
        .createQuery("SELECT COUNT(f) FROM NapSubmissionFile f WHERE f.submission = :s", Long.class)
        .setParameter("s", submission)
        .getSingleResult();
  }

  @Test
  @DisplayName("Втора отметка за същия файл → отказ: два входящи номера на едни и същи байтове")
  void aSecondMarkOfTheSameFileIsRefused() {
    Case c = new Case("Двойна", true);
    mark(c.artifact, "100", PORTAL_DAY, c.clerk);
    assertRefused("вече е отбелязан", () -> mark(c.artifact, "101", PORTAL_DAY, c.clerk));
    assertEquals(1L, submissionsOf(c.company));
  }

  // ═══ ОТМЯНАТА ════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Отмяна: без причина → отказ; с причина → withdrawn + одит, листът е пак „генерирано“ и се брои")
  void withdrawNeedsAReasonAndRestoresTheDeadline() {
    Case c = new Case("Отмяна", true);
    NapSubmission s = mark(c.artifact, "200", PORTAL_DAY, c.clerk);
    assertEquals(0L, urgentFor(c), "подаденото не се брои");

    assertRefused("причина", () -> submissions().withdraw(s, "  ", c.clerk));
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, s.getSubmissionStatusSelect());

    submissions().withdraw(s, "сбъркан входящ номер", c.clerk);

    assertEquals(NapSubmissionRepository.STATUS_WITHDRAWN, s.getSubmissionStatusSelect());
    assertNull(c.declaration.getBgNoiSubmission());
    assertEquals("GENERATED", c.declaration.getStatusSelect());
    assertEquals(BgApp9DueScope.STATE_GENERATED, BgApp9DbFixture.submissionState(c.sick));
    assertEquals(1L, urgentFor(c), "срокът се показва отново — 10.11.2026, лента от 05.11");

    NapAuditEvent audit =
        JPA.all(NapAuditEvent.class)
            .filter(
                "self.submission = ? AND self.eventTypeSelect = ?",
                s,
                NapAuditEventRepository.EVENT_STATUS_CHANGE)
            .fetchOne();
    assertNotNull(audit);
    assertSame(c.clerk, audit.getActorUser());
    assertTrue(audit.getDetails().contains("сбъркан входящ номер"), "причината е в одита");
  }

  // ═══ Т. 26 ═══════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Т. 26: след отметка на първия лист вторият лист на лицето е „не е първо представяне“")
  void pointTwentySixSeesTheMark() {
    Case c = new Case("Т26", true);
    BgSickLeave second =
        tx(
            () ->
                fx.sickLeave(
                    c.contract,
                    LocalDate.of(2026, 10, 19),
                    LocalDate.of(2026, 10, 23),
                    LocalDate.of(2026, 10, 26),
                    "sick_er"));
    BgApp9DeclarationService declarations = Beans.get(BgApp9DeclarationService.class);
    assertEquals(Boolean.FALSE, declarations.factsOf(second).earlierApp9Submitted, "котвата");

    mark(c.artifact, "300", PORTAL_DAY, c.clerk);

    assertEquals(
        Boolean.TRUE,
        declarations.factsOf(second).earlierApp9Submitted,
        "т. 26 се попълва само при първото представяне — то вече е станало");
  }

  // ═══ ПАЗАЧЪТ СРЕЩУ ПРЕЗАПИСА — ИСТИНСКО ГЕНЕРИРАНЕ ════════════════════════

  @Test
  @DisplayName("⚠️ „Генерирай“ върху подадено → отказ; файлът и статусът не мърдат")
  void generatingASubmittedDeclarationIsRefused() throws Exception {
    Case c = new Case("Пазач", false);
    NapArtifact first = generate(c.declaration);
    NapSubmission s = mark(first, "400", PORTAL_DAY, c.clerk);

    BgApp9Emission again =
        tx(
            () ->
                uncheck(
                    () ->
                        Beans.get(BgApp9ExportService.class)
                            .generateForDeclaration(c.declaration)));

    assertTrue(again.isRefused(), "подадено без „Корекция“ не се генерира наново");
    assertTrue(again.getGaps().get(0).reason.contains("вх. № 400"), again.gapReport());
    assertSame(first, c.declaration.getBgNoiArtifact(), "връзката към файла не е пренасочена");
    assertEquals("SUBMITTED", c.declaration.getStatusSelect());
    assertSame(s, c.declaration.getBgNoiSubmission());
  }

  @Test
  @DisplayName(
      "Корекция: нов файл и ново подаване с previousSubmission; старият SHA-256 и байтове са непокътнати")
  void aCorrectionMakesANewFileAndANewSubmission() throws Exception {
    Case c = new Case("Корекция", false);
    NapArtifact first = generate(c.declaration);
    String firstSha = first.getSha256();
    byte[] firstBytes = Files.readAllBytes(MetaFiles.getPath(first.getMetaFile()));
    NapSubmission original = mark(first, "500", PORTAL_DAY, c.clerk);

    tx(
        () -> {
          c.declaration.setBgSubmissionTypeSelect("CORRECT");
          return null;
        });
    NapArtifact correction = generate(c.declaration);

    assertNotEquals(first.getId(), correction.getId(), "НОВ файл");
    assertSame(original, c.declaration.getBgNoiSubmission(), "подаденото още е активното");
    assertEquals("SUBMITTED", c.declaration.getStatusSelect());

    NapSubmission corrected = mark(correction, "501", TODAY, c.clerk);
    assertSame(original, corrected.getPreviousSubmission(), "веригата на корекциите");
    assertSame(corrected, c.declaration.getBgNoiSubmission());

    assertEquals(firstSha, first.getSha256(), "отпечатъкът на подадения файл е непокътнат");
    assertEquals(
        firstSha,
        com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest.sha256Hex(
            Files.readAllBytes(MetaFiles.getPath(first.getMetaFile()))),
        "и байтовете на диска още дават същия отпечатък");
    assertTrue(
        java.util.Arrays.equals(
            firstBytes, Files.readAllBytes(MetaFiles.getPath(first.getMetaFile()))));
    NapSubmissionFile originalFile =
        JPA.all(NapSubmissionFile.class).filter("self.submission = ?", original).fetchOne();
    assertSame(first, originalFile.getPayloadArtifact(), "подаването още сочи подадения файл");
  }

  @Test
  @DisplayName("⚠️ Повторен износ не пренасочва подаденото удостоверение")
  void reExportDoesNotRepointASubmittedDeclaration() throws Exception {
    Case c = new Case("Повторен износ", false);
    NapArtifact first = generate(c.declaration);
    mark(first, "600", PORTAL_DAY, c.clerk);

    BgApp9Export export =
        tx(
            () -> {
              BgApp9Export e = new BgApp9Export();
              e.setName("Октомври " + fx.tag);
              e.setCompany(c.company);
              e.setStatusSelect("DRAFT");
              e.setNssiRoCode(22);
              e.setNssiRoName("София-град");
              e.setBgPeriodFrom(LocalDate.of(2026, 10, 1));
              e.setBgPeriodTo(LocalDate.of(2026, 10, 31));
              JPA.em().persist(e);
              return e;
            });
    BgApp9Emission emission =
        tx(() -> uncheck(() -> Beans.get(BgApp9ExportService.class).generate(export)));

    assertTrue(emission.isRefused(), "в прозореца има само подадено — няма какво да влезе");
    assertSame(first, c.declaration.getBgNoiArtifact(), "подаденото сочи подадения файл");
    assertEquals("SUBMITTED", c.declaration.getStatusSelect());
    assertEquals(1, export.getBgExportLineList().size(), "в описа остава");
    assertFalse(export.getBgExportLineList().get(0).getBgIncluded());
    assertTrue(export.getBgExportLineList().get(0).getBgNote().contains("Не влиза"));
  }

  // ═══ СЛЕД ОТМЕТКАТА ════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Отметка → групата е „Подадено в НОИ“ веднага; отмяна → пак „Дължи се“, без запис на листа")
  void theGroupFollowsTheMarkAndTheWithdrawal() {
    Case c = new Case("Група", true);
    assertEquals("OWES", groupOf(c.sick), "котвата: дължи се");

    NapSubmission s = mark(c.artifact, "ВХ-25", PORTAL_DAY, c.clerk);
    assertEquals("SUBMITTED", groupOf(c.sick), "групата се опреснява при самата отметка");

    submissions().withdraw(s, "сбъркан входящ номер", c.clerk);
    assertEquals("OWES", groupOf(c.sick), "отменената отметка връща срока");
  }

  @Test
  @DisplayName("Формата на износа показва вх. № и ДАТАТА от подаването — четени, не записани")
  void theExportShowsTheIncomingNumberAndDateFromTheSubmission() {
    Case c = new Case("Износ вх. №", true);
    BgApp9Export export =
        tx(
            () -> {
              BgApp9Export e = new BgApp9Export();
              e.setName("Износ " + fx.tag);
              e.setCompany(c.company);
              e.setStatusSelect("GENERATED");
              e.setBgNoiArtifact(c.artifact);
              JPA.em().persist(e);
              return e;
            });
    assertNull(exportColumn(export, "bgNoiIncomingNumber"));

    NapSubmission s = mark(c.artifact, "ВХ-2026-0777", PORTAL_DAY, c.clerk);

    assertEquals("ВХ-2026-0777", exportColumn(export, "bgNoiIncomingNumber"));
    assertEquals(PORTAL_DAY, exportColumn(export, "bgNoiSubmittedDate"), "дата, не 03:00");
    assertEquals("SUBMITTED", export.getStatusSelect());

    submissions().withdraw(s, "сбъркан входящ номер", c.clerk);
    assertNull(exportColumn(export, "bgNoiIncomingNumber"), "отменената отметка не се показва");
  }

  /** Групата, прочетена от БАЗАТА — не от обекта в паметта. */
  private static String groupOf(BgSickLeave sick) {
    return JPA.em()
        .createQuery(
            "SELECT s.bgNoiObligationSelect FROM BgSickLeave s WHERE s.id = :id", String.class)
        .setParameter("id", sick.getId())
        .getSingleResult();
  }

  /** Изчислимата колона на износа — формулата се смята в SELECT-а. */
  private static Object exportColumn(BgApp9Export export, String field) {
    return JPA.em()
        .createQuery("SELECT e." + field + " FROM BgApp9Export e WHERE e.id = :id")
        .setParameter("id", export.getId())
        .getSingleResult();
  }

  // ═══ ПОМОЩНИ ═════════════════════════════════════════════════════════════

  private static NapSubmission mark(NapArtifact artifact, String number, LocalDate day, User who) {
    return submissions().markSubmitted(artifact, number, day, who, TODAY);
  }

  private NapArtifact generate(BgApp9Declaration declaration) {
    BgApp9Emission emission =
        tx(
            () ->
                uncheck(
                    () ->
                        Beans.get(BgApp9ExportService.class).generateForDeclaration(declaration)));
    assertFalse(emission.isRefused(), "фикстурата трябва да генерира: " + emission.gapReport());
    return declaration.getBgNoiArtifact();
  }

  private long urgentFor(Case c) {
    return Beans.get(BgApp9DeadlineService.class).countUrgent(c.clerk, TODAY);
  }

  private static void assertRefused(String fragment, Runnable action) {
    IllegalStateException e = assertThrows(IllegalStateException.class, action::run);
    assertTrue(
        e.getMessage().contains(fragment), "очаквах „" + fragment + "“, а е: " + e.getMessage());
  }

  interface Io<T> {
    T get() throws Exception;
  }

  private static <T> T uncheck(Io<T> work) {
    try {
      return work.get();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
