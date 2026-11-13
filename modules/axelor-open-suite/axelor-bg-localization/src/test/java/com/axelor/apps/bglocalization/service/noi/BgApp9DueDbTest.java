package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * СРОКЪТ, ГРУПАТА И ОБХВАТЪТ — през истинския път: repository-то, услугата и JPQL-а на брояча.
 *
 * <p>Окабеляването е производственото ({@link BgLocalizationProductionWiringTestModule}), тоест
 * {@code BgSickLeaveRepository} е {@code BgSickLeaveManagementRepository} и срокът се закача при
 * запис — точно както в приложението. Календарът е заредените 2026–2027 от seed-а.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9DueDbTest extends JpaSupport {

  private static final LocalDate TRACK_FROM = LocalDate.of(2026, 9, 1);

  private BgApp9DbFixture fx;

  @BeforeEach
  void setUp() {
    fx = new BgApp9DbFixture();
    BgApp9DbFixture.loadCalendar();
  }

  private <T> T tx(java.util.function.Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  // ═══ ЗАКАЧАНЕТО ПРИ ЗАПИС ════════════════════════════════════════════════

  @Test
  @DisplayName("Записът на листа закача срока: представен 01.10.2026 → 10.11.2026, лента от 05.11")
  void theDeadlineIsStampedOnSave() {
    BgSickLeave sick =
        tx(
            () -> {
              Company company = fx.company("Срок", TRACK_FROM);
              return fx.sickLeave(fx.contract(company, "01"), LocalDate.of(2026, 10, 1));
            });
    assertEquals("OWES", sick.getBgNoiObligationSelect());
    assertEquals(LocalDate.of(2026, 11, 10), sick.getBgNoiDueDate());
    assertEquals(LocalDate.of(2026, 11, 5), sick.getBgNoiDueSoonFrom());
    assertNull(sick.getBgNoiDueWarning(), "2026 е заредена — срокът е надежден");
  }

  @Test
  @DisplayName("⚠️ Представен 15.12.2027 → видимо „ненадежден срок (2028 не е заредена)“")
  void anUnloadedYearIsVisiblyUnreliable() {
    BgSickLeave sick =
        tx(
            () -> {
              Company company = fx.company("Ненадежден", TRACK_FROM);
              return fx.sickLeave(
                  fx.contract(company, "01"),
                  LocalDate.of(2027, 12, 6),
                  LocalDate.of(2027, 12, 10),
                  LocalDate.of(2027, 12, 15),
                  "sick_er");
            });
    assertEquals("ненадежден срок (2028 не е заредена)", sick.getBgNoiDueWarning());
    assertEquals(LocalDate.of(2028, 1, 10), sick.getBgNoiDueDate());
  }

  @Test
  @DisplayName("Код 10 (управител по договор за управление) с болничен: срок от ПРЕДСТАВЯНЕТО")
  void aManagementContractRunsFromPresentation() {
    BgSickLeave sick =
        tx(
            () -> {
              Company company = fx.company("Управител", TRACK_FROM);
              return fx.sickLeave(fx.contract(company, "10"), LocalDate.of(2026, 9, 15));
            });
    assertEquals("OWES", sick.getBgNoiObligationSelect());
    assertEquals(
        LocalDate.of(2026, 10, 12), sick.getBgNoiDueDate(), "10.10.2026 е събота — вариант Б");
  }

  @Test
  @DisplayName("Обжалван: срокът не тече; приключил на 04.12.2026 → тече до 11.01.2027")
  void anAppealStopsAndThenStartsTheClock() {
    BgSickLeave sick =
        tx(
            () -> {
              Company company = fx.company("Обжалване", TRACK_FROM);
              BgSickLeave s = fx.sickLeave(fx.contract(company, "01"), LocalDate.of(2026, 10, 1));
              s.setBgAppealedByEmployer(Boolean.TRUE);
              return Beans.get(BgSickLeaveRepository.class).save(s);
            });
    assertEquals("APPEALED", sick.getBgNoiObligationSelect());
    assertNull(sick.getBgNoiDueDate(), "срокът не тече");

    BgSickLeave closed =
        tx(
            () -> {
              sick.setBgAppealClosedDate(LocalDate.of(2026, 12, 4));
              return Beans.get(BgSickLeaveRepository.class).save(sick);
            });
    assertEquals("OWES", closed.getBgNoiObligationSelect());
    assertEquals(LocalDate.of(2027, 1, 11), closed.getBgNoiDueDate());
  }

  // ═══ ГРАНИЦАТА И ИСТОРИЯТА ═══════════════════════════════════════════════

  @Test
  @DisplayName("Празна граница → нищо; лист преди границата → не излиза; внесен с флаг → не излиза")
  void theCutoffAndTheImportFlagKeepHistoryOut() {
    List<Long> ids = new ArrayList<>();
    List<User> users = new ArrayList<>();
    tx(
        () -> {
          Company off = fx.company("Изключено", null);
          BgSickLeave a = fx.sickLeave(fx.contract(off, "01"), LocalDate.of(2026, 10, 1));
          Company on = fx.company("Граница", TRACK_FROM);
          BgSickLeave b = fx.sickLeave(fx.contract(on, "01"), LocalDate.of(2026, 8, 20));
          BgSickLeave c = fx.sickLeave(fx.contract(on, "01"), LocalDate.of(2026, 10, 1));
          c.setBgIsHistoricalImport(Boolean.TRUE);
          Beans.get(BgSickLeaveRepository.class).save(c);
          assertEquals("TRACKING_OFF", a.getBgNoiObligationSelect());
          assertEquals("BEFORE_CUTOFF", b.getBgNoiObligationSelect());
          assertEquals("HISTORICAL", c.getBgNoiObligationSelect());
          ids.add(a.getId());
          ids.add(b.getId());
          ids.add(c.getId());
          // Обхватът е АКТИВНОТО дружество — проверява се от двете страни, за да не мине
          // листът на едното „по грешна причина“ (просто защото дружеството не е активно).
          users.add(fx.user("cut-off", true, off, on));
          users.add(fx.user("cut-on", true, on, off));
          return null;
        });
    for (User user : users) {
      List<Long> listed = listedIds(user);
      for (Long id : ids) {
        assertFalse(listed.contains(id), "лист " + id + " не бива да е в „Прил. 9 за подаване“");
      }
      assertEquals(
          0L,
          Beans.get(BgApp9DeadlineService.class).countUrgent(user, LocalDate.of(2026, 11, 20)),
          "и броячът не ги брои");
    }
  }

  // ═══ ИЗОЛАЦИЯТА: БРОЯЧЪТ = РЕДОВЕТЕ ══════════════════════════════════════

  @Test
  @DisplayName("Фирми A и Б: потребител от A вижда и брои само A — и броят съвпада с редовете")
  void theCounterAndTheListAgreeForARestrictedUser() {
    AtomicReference<User> fromA = new AtomicReference<>();
    AtomicReference<User> fromB = new AtomicReference<>();
    AtomicReference<Long> idA = new AtomicReference<>();
    AtomicReference<Long> idB = new AtomicReference<>();
    tx(
        () -> {
          Company a = fx.company("Фирма А", TRACK_FROM);
          Company b = fx.company("Фирма Б", TRACK_FROM);
          idA.set(fx.sickLeave(fx.contract(a, "01"), LocalDate.of(2026, 10, 1)).getId());
          // Представен през ноември → срок 10.12: в списъка е, в брояча (към 05.11) не.
          fx.sickLeave(fx.contract(a, "01"), LocalDate.of(2026, 11, 2));
          idB.set(fx.sickLeave(fx.contract(b, "01"), LocalDate.of(2026, 10, 1)).getId());
          fromA.set(fx.user("onlyA", false, a));
          fromB.set(fx.user("onlyB", false, b));
          return null;
        });
    LocalDate today = LocalDate.of(2026, 11, 5);

    List<Long> rowsA = listedIds(fromA.get());
    assertTrue(rowsA.contains(idA.get()));
    assertFalse(rowsA.contains(idB.get()), "листът на Б не бива да се вижда от A");
    assertEquals(2, rowsA.size());

    long countA = Beans.get(BgApp9DeadlineService.class).countUrgent(fromA.get(), today);
    assertEquals(urgentAmongListed(fromA.get(), today), countA, "броячът = спешните редове");
    assertEquals(1L, countA, "само листът с лента от 05.11");
    assertEquals(1L, Beans.get(BgApp9DeadlineService.class).countUrgent(fromB.get(), today));
  }

  // ═══ ЗАВАРЕНОТО ПОДАДЕНО ═════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Заварено SUBMITTED без NapSubmission → „подадено (заварено)“; опресняването не го пипа")
  void aLegacySubmittedDeclarationStaysLegacy() {
    AtomicReference<BgSickLeave> sick = new AtomicReference<>();
    AtomicReference<BgApp9Declaration> legacy = new AtomicReference<>();
    AtomicReference<Company> company = new AtomicReference<>();
    tx(
        () -> {
          company.set(fx.company("Заварено", TRACK_FROM));
          sick.set(fx.sickLeave(fx.contract(company.get(), "01"), LocalDate.of(2026, 10, 1)));
          BgApp9Declaration d = BgApp9DbFixture.declaration(sick.get());
          d.setStatusSelect("SUBMITTED");
          legacy.set(d);
          return null;
        });
    assertEquals(
        BgApp9DueScope.STATE_SUBMITTED_LEGACY, BgApp9DbFixture.submissionState(sick.get()));

    tx(
        () ->
            Beans.get(BgApp9DeadlineService.class)
                .refresh(java.util.Collections.singleton(company.get().getId())));
    assertEquals("SUBMITTED", legacy.get().getStatusSelect(), "не се преобразува");
    assertNull(legacy.get().getBgNoiSubmission());
  }

  // ═══ ПЕРИОДЪТ НА ИЗНОСА ══════════════════════════════════════════════════

  @Test
  @DisplayName("Износ без bgExportMonth → месецът на ПРЕДСТАВЯНЕТО; без двете дати → не влиза")
  void theExportWindowUsesThePresentationMonth() {
    AtomicReference<BgApp9Declaration> late = new AtomicReference<>();
    AtomicReference<BgApp9Declaration> undated = new AtomicReference<>();
    AtomicReference<Company> company = new AtomicReference<>();
    tx(
        () -> {
          company.set(fx.company("Прозорец", TRACK_FROM));
          EmploymentContract contract = fx.contract(company.get(), "01");
          // Лист за края на септември, представен на 2 октомври.
          BgSickLeave a =
              fx.sickLeave(
                  contract,
                  LocalDate.of(2026, 9, 28),
                  LocalDate.of(2026, 9, 30),
                  LocalDate.of(2026, 10, 2),
                  "sick_er");
          late.set(BgApp9DbFixture.declaration(a));
          BgSickLeave b =
              fx.sickLeave(
                  contract, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7), null, "sick_er");
          assertEquals("NO_ANCHOR_DATE", b.getBgNoiObligationSelect(), "в групата „няма дата“");
          undated.set(BgApp9DbFixture.declaration(b));
          return null;
        });

    List<BgApp9Declaration> october =
        Beans.get(BgApp9ExportService.class).declarationsIn(window(company.get(), 10));
    assertTrue(october.contains(late.get()), "представен през октомври → октомврийският пакет");
    assertFalse(october.contains(undated.get()), "без дата на представяне не влиза в пакет");

    List<BgApp9Declaration> september =
        Beans.get(BgApp9ExportService.class).declarationsIn(window(company.get(), 9));
    assertFalse(
        september.contains(late.get()),
        "⚠️ не влиза през fromDate в септемврийския пакет — срокът му е 10 ноември");
    assertFalse(september.contains(undated.get()));
  }

  // ═══ ПОМОЩНИ ═════════════════════════════════════════════════════════════

  private static BgApp9Export window(Company company, int month) {
    LocalDate first = LocalDate.of(2026, month, 1);
    BgApp9Export export = new BgApp9Export();
    export.setCompany(company);
    export.setBgPeriodFrom(first);
    export.setBgPeriodTo(first.withDayOfMonth(first.lengthOfMonth()));
    return export;
  }

  /** Редовете на списъка — СЪЩИЯТ домейн, който отваря екранът. */
  static List<Long> listedIds(User user) {
    return JPA.em()
        .createQuery(
            "SELECT self.id FROM BgSickLeave self WHERE "
                + BgApp9DueScope.listDomain(BgApp9DueScope.activeCompanyIds(user)),
            Long.class)
        .getResultList();
  }

  /** Спешните сред редовете на списъка — броени в Java, за да се сверят с JPQL-а на брояча. */
  static long urgentAmongListed(User user, LocalDate today) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT self.bgNoiObligationSelect, self.bgNoiDueDate, self.bgNoiDueSoonFrom,"
                    + " self.bgNoiSubmissionState FROM BgSickLeave self WHERE "
                    + BgApp9DueScope.listDomain(BgApp9DueScope.activeCompanyIds(user)),
                Object[].class)
            .getResultList();
    long urgent = 0;
    for (Object[] row : rows) {
      Integer state = (Integer) row[3];
      boolean pending =
          state != null
              && (state == BgApp9DueScope.STATE_NOT_GENERATED
                  || state == BgApp9DueScope.STATE_GENERATED);
      if ("OWES".equals(row[0])
          && pending
          && BgApp9Deadline.band(today, (LocalDate) row[1], (LocalDate) row[2])
              != BgApp9Deadline.Band.ON_TIME) {
        urgent++;
      }
    }
    return urgent;
  }
}
