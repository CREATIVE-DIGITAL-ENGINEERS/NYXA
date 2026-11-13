package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.pin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Result;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import javax.validation.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ПРОДЪЛЖЕНИЕТО НАСЛЕДЯВА НАЧАЛОТО НА ВЕРИГАТА — по реалния път ({@link BgPayrollRealPath}: сийдът
 * → „Преизчисли“ → „Потвърди“ → Д1).
 *
 * <p><b>Основание.</b> КСО чл. 40 ал. 5 — осигурителят изплаща първите ДВА работни дни от
 * временната неработоспособност, тоест от целия непрекъснат период, не от всеки лист. Продължението
 * е същата неработоспособност.
 *
 * <p><b>Капанът.</b> Листът A е 22–30.06, продължението B — 01–10.07. Заявката на юлския фиш чете
 * листовете, засягащи юли: A не влиза, B излиза първо и ако открие нов период, работодателят плаща
 * 1–2 юли като първите два дни — ВТОРИ път.
 *
 * <p><b>Листовете се записват ПОКРАЙ repository-то</b> ({@code persist}) — за да има заварено
 * продължение с вид Р-л (repository-то отказва такова) и заварено продължение без връзка. Какво
 * отказва repository-то, мери (в).
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSickLeaveContinuationChainTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static final LocalDate JUL_FROM = LocalDate.of(2026, 7, 1);
  private static final LocalDate JUL_TO = LocalDate.of(2026, 7, 31);
  private static final LocalDate A_FROM = LocalDate.of(2026, 6, 22);
  private static final LocalDate A_TO = LocalDate.of(2026, 6, 30);
  private static final LocalDate B_FROM = LocalDate.of(2026, 7, 1);
  private static final LocalDate B_TO = LocalDate.of(2026, 7, 10);

  private static final class Scenario {
    Fixture f;
    Long a;
    Long jul;
    Long slipId;
  }

  /** Сийдът, ролите, фикстурата, листът A, юлският период, черновата и отчетът — без роля. */
  private static Scenario scenario(String letter) throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Scenario s = new Scenario();
    // Стажът е към 31.05 — преди листа A (22.06): правото се определя към началото му.
    s.f = BgPayrollRealPath.fixture(letter, 24, LocalDate.of(2026, 5, 31));
    s.a = persisted(s.f, "sick_er", A_FROM, A_TO, "Е2026CHAIN0" + letter, null, false);
    s.jul = BgPayrollRealPath.period(s.f, JUL_FROM, JUL_TO);
    s.slipId = BgPayrollRealPath.slip(s.f, s.jul, "JUL");
    BgPayrollRealPath.timesheet(s.f, JUL_FROM, JUL_TO, B_FROM, B_TO);
    return s;
  }

  /** Валидиран лист, записан с {@code persist} — покрай repository-то. */
  private static Long persisted(
      Fixture f,
      String code,
      LocalDate from,
      LocalDate to,
      String no,
      Long continuationOf,
      boolean flag) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgSickLeave sheet = sheet(f, code, from, to);
          sheet.setBgDocumentNumber(no);
          sheet.setBgIsContinuation(flag || continuationOf != null);
          if (continuationOf != null) {
            sheet.setBgContinuationOf(JPA.em().find(BgSickLeave.class, continuationOf));
          }
          JPA.em().persist(sheet);
          id[0] = sheet.getId();
        });
    return id[0];
  }

  private static BgSickLeave sheet(Fixture f, String code, LocalDate from, LocalDate to) {
    BgSickLeave sheet = new BgSickLeave();
    sheet.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
    sheet.setBgAbsenceType(
        JPA.em()
            .createQuery("SELECT t FROM BgAbsenceType t WHERE t.code = :c", BgAbsenceType.class)
            .setParameter("c", code)
            .getSingleResult());
    sheet.setFromDate(from);
    sheet.setToDate(to);
    sheet.setBgIncapacityStartDate(from);
    sheet.setBgIsContinuation(false);
    sheet.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
    sheet.setBgEmployerReceivedDate(from.plusDays(3));
    return sheet;
  }

  /**
   * Ролите от каталога — ВЕДНЪЖ на схема и ПРЕДИ данните. Виж {@code
   * BgSickEligibilityByRiskTest#warmUpRoles}: първото зареждане изпразва контекста, а след него
   * HSQLDB не изпълнява формулата на Partner за PostgreSQL.
   */
  private static void warmUpRoles() {
    long present =
        JPA.em()
            .createQuery("SELECT COUNT(r) FROM Role r WHERE r.name IN (:w, :r)", Long.class)
            .setParameter("w", BgRoleLogin.WRITE_ROLE)
            .setParameter("r", BgRoleLogin.READ_ROLE)
            .getSingleResult();
    if (present == 2) {
      return;
    }
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Company c = new Company();
          c.setName("Зареждане на ролите " + System.nanoTime());
          c.setCode("RW" + System.nanoTime());
          JPA.em().persist(c);
          id[0] = c.getId();
        });
    BgRoleLogin.logout();
    BgRoleLogin.login(
        JPA.em().find(Company.class, id[0]), BgRoleLogin.WRITE_ROLE, BgRoleLogin.READ_ROLE);
    BgRoleLogin.logout();
  }

  private static void loginAs(Fixture f, String role) {
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, f.companyId), role);
  }

  // ═══ (а) ВЕРИГАТА ОТ ПРЕДХОДНИЯ МЕСЕЦ ═════════════════════════════════════

  @Test
  @DisplayName(
      "(а) A 22–30.06 + B 01–10.07 продължение, вид sick_er — юли: 195 = 0, т.16.А = 0, т.16.2 = 8")
  void theContinuationInheritsTheStartOfTheChain() throws Exception {
    Scenario s = scenario("a");
    persisted(s.f, "sick_er", B_FROM, B_TO, "Е2026CHAIN1a", s.a, true);
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    Result r = BgPayrollRealPath.run(s.f, s.slipId, s.jul);

    assertEquals(
        0, r.slip.getBgSickDaysEmployer().intValue(), "двата дни са 22–23 юни: " + r.trace);
    assertEquals(8, r.slip.getBgSickDaysDoo().intValue(), "1–10 юли: 8 работни дни, всичките ДОО");
    assertNull(amountOf(r.slip, "pl_bolnichen_rabotodatel"), "перо 195 НЕ втори път");
    pin("0", r.rec.getT16aEmployerSickDays(), "т.16.А");
    pin("8", r.rec.getT162SickDays(), "т.16.2 — дните са СМЕТНАТИ, не отказани");
  }

  // ═══ (б) СКЪСАНА ВЕРИГА ══════════════════════════════════════════════════

  @Test
  @DisplayName("(б) заварено продължение без връзка — отказ на разбива, без перо 195")
  void aContinuationWithoutTheLinkIsRefused() throws Exception {
    Scenario s = scenario("b");
    persisted(s.f, "sick_er", B_FROM, B_TO, "Е2026CHAIN1b", null, true);
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    BgPayrollRealPath.press("recalculate", s.slipId);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, s.slipId));

    assertNull(
        amountOf(slip, "pl_bolnichen_rabotodatel"),
        "продължението НЕ открива нов период: " + BgPayrollRealPath.lines(slip));
    String gaps = String.valueOf(slip.getBgGeneratorGaps());
    assertTrue(
        gaps.contains("Е2026CHAIN1b") && gaps.contains("ПРОДЪЛЖЕНИЕ"),
        "отказът назовава листа: " + gaps);
  }

  // ═══ (в) ЗАПИСЪТ ПРЕЗ REPOSITORY-ТО ══════════════════════════════════════

  @Test
  @DisplayName("(в1) връзка + вид ДОО — записва се, отметката се вдига; връзка + вид Р-л — отказ")
  void theRepositoryKeepsTheChainHonest() throws Exception {
    Scenario s = scenario("c");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    Long[] saved = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgSickLeave b = sheet(s.f, "sick_doo", B_FROM, B_TO);
          b.setBgContinuationOf(JPA.em().find(BgSickLeave.class, s.a));
          saved[0] = Beans.get(BgSickLeaveRepository.class).save(b).getId();
        });
    assertTrue(
        fresh(em -> em.find(BgSickLeave.class, saved[0])).getBgIsContinuation(),
        "отметката е производна на връзката");

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът откача контекста.
    BgSickLeave wrong = sheet(s.f, "sick_er", LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 17));
    wrong.setBgContinuationOf(JPA.em().find(BgSickLeave.class, saved[0]));
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> JPA.runInTransaction(() -> Beans.get(BgSickLeaveRepository.class).save(wrong)));
    assertTrue(refused.getMessage().contains("платец"), refused.getMessage());
  }

  @Test
  @DisplayName("(в2) нов лист с отметка „продължение“ без връзка — отказ")
  void aNewContinuationWithoutTheLinkIsRefused() throws Exception {
    Scenario s = scenario("d");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    BgSickLeave b = sheet(s.f, "sick_doo", B_FROM, B_TO);
    b.setBgIsContinuation(true);
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> JPA.runInTransaction(() -> Beans.get(BgSickLeaveRepository.class).save(b)));
    assertTrue(refused.getMessage().contains("НА КОЙ лист"), refused.getMessage());
  }

  @Test
  @DisplayName("(в3) връзка към лист по ДРУГ договор на ДРУГО дружество — отказ")
  void aLinkToAnotherCompanyIsRefused() throws Exception {
    Scenario s = scenario("e");
    Fixture other = BgPayrollRealPath.fixture("e2", 24);
    Long foreign = persisted(other, "sick_er", A_FROM, A_TO, "Е2026CHAIN0e2", null, false);
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    BgSickLeave b = sheet(s.f, "sick_doo", B_FROM, B_TO);
    b.setBgContinuationOf(JPA.em().find(BgSickLeave.class, foreign));
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> JPA.runInTransaction(() -> Beans.get(BgSickLeaveRepository.class).save(b)));
    assertTrue(
        refused.getMessage().contains("ДРУГ трудов договор")
            && refused.getMessage().contains("ДРУГО дружество"),
        refused.getMessage());
  }

  @Test
  @DisplayName(
      "(в6) продължение с ПРОПУСК (A свършва 30.06, B почва 03.07) — отказ: листът е първичен")
  void aContinuationWithAGapIsRefused() throws Exception {
    Scenario s = scenario("i");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът откача контекста.
    BgSickLeave gap = sheet(s.f, "sick_doo", LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 10));
    gap.setBgContinuationOf(JPA.em().find(BgSickLeave.class, s.a));
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> JPA.runInTransaction(() -> Beans.get(BgSickLeaveRepository.class).save(gap)));
    assertTrue(
        refused.getMessage().contains("ПРОПУСК") && refused.getMessage().contains("ПЪРВИЧЕН"),
        refused.getMessage());
  }

  @Test
  @DisplayName("(в4) валидирано продължение, началото преместено преди края на предходния — отказ")
  void movingTheStartIntoThePreviousSheetIsRefused() throws Exception {
    Scenario s = scenario("g");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    Long[] b = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgSickLeave sheet = sheet(s.f, "sick_doo", B_FROM, B_TO);
          sheet.setBgContinuationOf(JPA.em().find(BgSickLeave.class, s.a));
          b[0] = Beans.get(BgSickLeaveRepository.class).save(sheet).getId();
        });

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът откача контекста.
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () ->
                JPA.runInTransaction(
                    () -> {
                      BgSickLeave sheet = JPA.em().find(BgSickLeave.class, b[0]);
                      sheet.setFromDate(LocalDate.of(2026, 6, 28)); // A свършва на 30.06
                      Beans.get(BgSickLeaveRepository.class).save(sheet);
                    }));
    assertTrue(refused.getMessage().contains("застъпват"), refused.getMessage());
  }

  @Test
  @DisplayName("(в5) смяна САМО на статуса към „Валидиран“ пуска проверката — застъпване → отказ")
  void validatingIsAChainInputChange() throws Exception {
    Scenario s = scenario("h");
    // Чернова, записана покрай repository-то, застъпена с A (28.06 < 30.06).
    Long[] b = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgSickLeave sheet = sheet(s.f, "sick_doo", LocalDate.of(2026, 6, 28), B_TO);
          sheet.setBgContinuationOf(JPA.em().find(BgSickLeave.class, s.a));
          sheet.setBgIsContinuation(true);
          sheet.setStatusSelect("DRAFT");
          JPA.em().persist(sheet);
          b[0] = sheet.getId();
        });
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът откача контекста.
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () ->
                JPA.runInTransaction(
                    () -> {
                      BgSickLeave sheet = JPA.em().find(BgSickLeave.class, b[0]);
                      sheet.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
                      Beans.get(BgSickLeaveRepository.class).save(sheet);
                    }));
    assertTrue(refused.getMessage().contains("застъпват"), refused.getMessage());
  }

  // ═══ (г) РОЛЯТА: all.read НЕ ЗАПИСВА ═════════════════════════════════════

  @Test
  @DisplayName("(г) all.read — „Потвърди“ се отказва; фишът остава чернова")
  void allReadCannotConfirm() throws Exception {
    Scenario s = scenario("f");
    persisted(s.f, "sick_doo", B_FROM, B_TO, "Е2026CHAIN1f", s.a, true);
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);
    BgPayrollRealPath.press("recalculate", s.slipId);

    loginAs(s.f, BgRoleLogin.READ_ROLE);
    ActionResponse confirm = BgPayrollRealPath.press("confirm", s.slipId);

    assertEquals(
        BgPaySlipResolver.DRAFT,
        fresh(em -> em.find(BgPaySlip.class, s.slipId)).getStatusSelect(),
        "all.read не потвърждава: " + confirm.getData());
    assertNotNull(confirm.getData(), "отказът е на екрана, не тих");
  }
}
