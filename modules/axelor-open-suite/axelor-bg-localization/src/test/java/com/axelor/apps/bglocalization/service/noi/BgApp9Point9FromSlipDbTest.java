package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * „Изведи т. 9 от потвърдения фиш“ — през истинската база.
 *
 * <p>Котвата е 0909043: лист 28–30.09.2026, отразен с „Отрази пристигнал болничен“ без т. 9.
 * Оригиналният фиш за 09/2026 е потвърден БЕЗ болнични дни (листът още не е бил дошъл), а
 * коригиращото копие — потвърдено с 2 дни осигурител + 1 ДОО. Т. 9 е празна.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9Point9FromSlipDbTest extends JpaSupport {

  private static final LocalDate FROM = LocalDate.of(2026, 9, 28);
  private static final LocalDate TO = LocalDate.of(2026, 9, 30);

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

  private Period september(Company company) {
    Year year = new Year();
    year.setName("2026 " + fx.tag);
    year.setCode("Y9" + fx.tag + System.nanoTime() % 100000);
    year.setFromDate(LocalDate.of(2026, 1, 1));
    year.setToDate(LocalDate.of(2026, 12, 31));
    year.setCompany(company);
    year.setTypeSelect(YearRepository.TYPE_PAYROLL);
    JPA.em().persist(year);
    Period period = new Period();
    period.setName("09/2026 " + fx.tag);
    period.setFromDate(LocalDate.of(2026, 9, 1));
    period.setToDate(LocalDate.of(2026, 9, 30));
    period.setYear(year);
    JPA.em().persist(period);
    return period;
  }

  private static BgPaySlip slip(
      EmploymentContract contract,
      Period period,
      String reference,
      String status,
      BgPaySlip correctionOf,
      int employerDays,
      int dooDays) {
    BgPaySlip slip = new BgPaySlip();
    slip.setEmploymentContract(contract);
    slip.setPeriod(period);
    slip.setReference(reference);
    slip.setStatusSelect(status);
    slip.setBgIsCorrection(correctionOf != null);
    slip.setBgCorrectionOf(correctionOf);
    slip.setBgSickDaysEmployer(employerDays);
    slip.setBgSickDaysDoo(dooDays);
    slip.setBgRegimeHoursPerDay(new BigDecimal("8.00"));
    slip.setBgRegimeWorkTimeMode("1");
    JPA.em().persist(slip);
    return slip;
  }

  /** Лист като 0909043 — валидиран, т. 9 празна, с удостоверение. */
  private BgSickLeave sheet(EmploymentContract contract) {
    BgSickLeave sick = fx.sickLeave(contract, FROM, TO, LocalDate.of(2026, 10, 2), "sick_er");
    sick.getBgPeriodLineList().clear();
    Beans.get(BgSickLeaveRepository.class).save(sick);
    BgApp9DbFixture.declaration(sick);
    return sick;
  }

  /** Началото на отказа на резолвера за празна т. 9. */
  private static final String NO_POINT_9 = "листът няма нито един ред по т. 9";

  /** Присъдите на резолвера — от базата. */
  private static String notesOf(BgSickLeave sick) {
    String notes =
        JPA.em()
            .createQuery(
                "SELECT d.resolverNotes FROM BgApp9Declaration d WHERE d.sickLeave.id = :id",
                String.class)
            .setParameter("id", sick.getId())
            .getSingleResult();
    return notes == null ? "" : notes;
  }

  private static BgSickLeave reread(BgSickLeave sick) {
    JPA.em().clear();
    return JPA.em().find(BgSickLeave.class, sick.getId());
  }

  private static BgApp9Point9FromSlip.Plan derive(BgSickLeave sick, User user) {
    return Beans.get(BgApp9Point9FromSlipService.class).derive(sick.getId(), user);
  }

  @Test
  @DisplayName(
      "Лист като 0909043 + потвърден коригиращ 09/2026 → т. 9 = 09/2026, 2 дни осигурител + 1 ДОО")
  void theConfirmedCorrectionFillsPoint9WithAudit() {
    Company company = tx(() -> fx.company("Т. 9 от фиша", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgSickLeave sick =
        tx(
            () -> {
              Period period = september(company);
              BgPaySlip original =
                  slip(contract, period, "FISH-" + fx.tag + "-O", "CONFIRMED", null, 0, 0);
              slip(contract, period, "FISH-" + fx.tag + "-K", "CONFIRMED", original, 2, 1);
              return sheet(contract);
            });
    User user = tx(() -> fx.user("t9", false, company));

    assertTrue(tx(() -> Beans.get(BgApp9Point9FromSlipService.class).isOffered(sick)));
    // Резолверът е пускан ПРЕДИ т. 9 — присъдата му е отказът „няма нито един ред по т. 9“.
    // ⚠️ Без reread(): изчистен контекст кара HSQLDB да чете Partner наново, а неговата
    // PostgreSQL формула там не върви. Управляваният запис стига.
    tx(
        () -> {
          BgApp9Declaration d =
              JPA.all(BgApp9Declaration.class).filter("self.sickLeave = ?", sick).fetchOne();
          Beans.get(BgApp9DeclarationService.class).resolveInto(d);
          return JPA.save(d);
        });
    assertTrue(notesOf(sick).contains(NO_POINT_9), notesOf(sick));

    BgApp9Point9FromSlip.Plan plan = tx(() -> derive(sick, user));

    // И присъдата се опреснява при самото действие, не чак при „Изведи/опресни“.
    assertFalse(notesOf(sick).contains(NO_POINT_9), notesOf(sick));
    assertTrue(notesOf(sick).contains("2 реда, 3 работни дни"), notesOf(sick));

    assertFalse(plan.isRefused(), String.valueOf(plan.refusals));
    BgSickLeave saved = tx(() -> reread(sick));
    List<BgSickLeavePeriodLine> lines = saved.getBgPeriodLineList();
    assertEquals(2, lines.size());
    int employer = 0;
    int noi = 0;
    for (BgSickLeavePeriodLine line : lines) {
      assertEquals(2026, (int) line.getPeriodYear());
      assertEquals(9, (int) line.getPeriodMonth());
      if ("EMPLOYER".equals(line.getPayer())) {
        employer += line.getWorkDays();
      } else {
        noi += line.getWorkDays();
      }
    }
    assertEquals(2, employer, "дни за сметка на осигурителя — замразените на коригиращия фиш");
    assertEquals(1, noi, "дни за сметка на ДОО");

    // Одитът: кой, кога, от кой фиш.
    assertEquals(user.getId(), saved.getBgPoint9DerivedBy().getId());
    assertNotNull(saved.getBgPoint9DerivedOn());
    assertTrue(
        saved.getBgPoint9DerivedFrom().contains("„FISH-" + fx.tag + "-K“ (коригиращ фиш"),
        saved.getBgPoint9DerivedFrom());

    // Точно каквото Прил. 9 ще изнесе за листа.
    BgApp9Declaration declaration = saved.getBgApp9Declaration();
    List<BgApp9Point9.Month> months = BgApp9Emitter.monthsOf(declaration);
    assertEquals(1, months.size());
    assertEquals(3, months.get(0).days);
    assertEquals(28, months.get(0).fromDay);
    assertEquals(30, months.get(0).toDay);

    // Втори път — отказ: т. 9 вече не е празна, нищо не се презаписва.
    assertFalse(tx(() -> Beans.get(BgApp9Point9FromSlipService.class).isOffered(reread(sick))));
    BgApp9Point9FromSlip.Plan again = tx(() -> derive(sick, user));
    assertTrue(again.isRefused());
    assertTrue(again.refusals.get(0).contains("само ПРАЗНА т. 9"), again.refusals.get(0));
  }

  @Test
  @DisplayName("Без потвърден фиш за 09/2026 (само чернова) → отказ поименно; нищо не е записано")
  void withoutAConfirmedSlipTheActionIsRefusedByMonth() {
    Company company = tx(() -> fx.company("Без потвърден", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgSickLeave sick =
        tx(
            () -> {
              Period period = september(company);
              slip(contract, period, "FISH-" + fx.tag + "-D", "DRAFT", null, 2, 1);
              return sheet(contract);
            });

    assertFalse(tx(() -> Beans.get(BgApp9Point9FromSlipService.class).isOffered(sick)));

    BgApp9Point9FromSlip.Plan plan = tx(() -> derive(sick, null));

    assertTrue(plan.isRefused());
    assertTrue(
        plan.refusals.get(0).startsWith("09/2026 — няма потвърден фиш"), plan.refusals.get(0));
    BgSickLeave saved = tx(() -> reread(sick));
    assertTrue(saved.getBgPeriodLineList().isEmpty(), "при отказ нищо не се записва");
    assertNull(saved.getBgPoint9DerivedOn());
    assertNull(saved.getBgPoint9DerivedFrom());
  }

  @Test
  @DisplayName(
      "Друг лист в месеца, върнат в ЧЕРНОВА след пресмятането на фиша → отказ поименно;"
          + " отказаният не пречи")
  void anotherSheetReturnedToDraftStillSharesTheSlipDays() {
    Company company = tx(() -> fx.company("Върнат в чернова", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgSickLeave[] other = new BgSickLeave[1];
    BgSickLeave sick =
        tx(
            () -> {
              Period period = september(company);
              // Фишът е сметнат и потвърден, докато и листът за 10–11.09 е бил валидиран...
              other[0] =
                  fx.sickLeave(
                      contract,
                      LocalDate.of(2026, 9, 10),
                      LocalDate.of(2026, 9, 11),
                      LocalDate.of(2026, 9, 12),
                      "sick_er");
              slip(contract, period, "FISH-" + fx.tag + "-B", "CONFIRMED", null, 2, 1);
              // ...а след това първият лист е върнат в чернова.
              other[0].setStatusSelect("DRAFT");
              return sheet(contract);
            });
    String otherNumber =
        BgApp9ChartNumber.compose(
            other[0].getBgSeries(), other[0].getBgYear(), other[0].getBgDocumentNumber());

    BgApp9Point9FromSlip.Plan plan = tx(() -> derive(sick, null));

    assertTrue(plan.isRefused(), "дните на фиша са и на листа в чернова");
    assertTrue(plan.refusals.get(0).contains(otherNumber), plan.refusals.get(0));
    assertEquals(0L, (long) tx(() -> lineCount(sick)), "при отказ нищо не се записва");

    // Отказаният лист („Отказан“) вече не дели фиша — не се брои.
    tx(
        () -> {
          other[0].setStatusSelect("CANCELLED");
          return null;
        });
    BgApp9Point9FromSlip.Plan again = tx(() -> derive(sick, null));
    assertFalse(again.isRefused(), String.valueOf(again.refusals));
    assertEquals(2L, (long) tx(() -> lineCount(sick)));
  }

  /** Редовете по т. 9 на листа — през заявка, без да се чисти контекстът. */
  private static Long lineCount(BgSickLeave sick) {
    return JPA.em()
        .createQuery(
            "SELECT COUNT(l) FROM BgSickLeavePeriodLine l WHERE l.sickLeave.id = :id", Long.class)
        .setParameter("id", sick.getId())
        .getSingleResult();
  }

  @Test
  @DisplayName("Заличен с потвърден заличаващ фиш не се чете; коригиращият печели пред оригинала")
  void theNewestConfirmedNonDeletedSlipIsRead() {
    Company company = tx(() -> fx.company("Кой фиш", LocalDate.of(2026, 9, 1)));
    EmploymentContract contract = tx(() -> fx.contract(company, "01"));
    BgPaySlip[] picked = new BgPaySlip[2];
    tx(
        () -> {
          Period period = september(company);
          BgPaySlip original =
              slip(contract, period, "FISH-" + fx.tag + "-O", "CONFIRMED", null, 0, 0);
          picked[0] = slip(contract, period, "FISH-" + fx.tag + "-K", "PAID", original, 2, 1);
          // Нова, по-късна корекция, но ЗАЛИЧЕНА с потвърден заличаващ фиш — не бива да се чете.
          BgPaySlip wrong =
              slip(contract, period, "FISH-" + fx.tag + "-W", "CONFIRMED", original, 9, 9);
          BgPaySlip deletion =
              slip(contract, period, "FISH-" + fx.tag + "-X", "CONFIRMED", null, 0, 0);
          deletion.setBgIsDeletion(true);
          deletion.setBgDeletionOf(wrong);
          picked[1] = original;
          return null;
        });

    BgPaySlip found =
        tx(
            () ->
                Beans.get(BgApp9Point9FromSlipService.class)
                    .confirmedSlipFor(contract, LocalDate.of(2026, 9, 1)));

    assertEquals(picked[0].getId(), found.getId());
    assertEquals(BgPaySlipResolver.PAID, found.getStatusSelect());
  }
}
