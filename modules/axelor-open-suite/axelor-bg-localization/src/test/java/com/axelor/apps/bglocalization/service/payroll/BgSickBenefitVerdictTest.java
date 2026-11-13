package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.pin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Result;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgSickLeaveController;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ПРАВОТО НА ОБЕЗЩЕТЕНИЕ КЪМ НАЧАЛОТО НА НЕРАБОТОСПОСОБНОСТТА — замразената присъда, по реалния път
 * ({@link BgPayrollRealPath}: сийдът → „Преизчисли“ → „Потвърди“ → Д1; „Отрази пристигнал
 * болничен“; „Определи правото наново“).
 *
 * <p><b>Основание.</b> КСО чл. 40 ал. 1 — 6 месеца осигурителен стаж; правото се определя към деня
 * на настъпване на риска (т. 2 на Приложение № 9 е формулирана така). Стажът към дата се ИЗВЕЖДА:
 * декларираните месеци към дадена дата плюс месеците след нея, удостоверени с потвърдени фишове при
 * осигурителя.
 *
 * <p><b>Защо към дата.</b> Стаж като число без дата, четено при всяко пресмятане, мери закъснял
 * лист за минал месец по днешния стаж, а нает със стаж 0 остава „без право“ завинаги, защото
 * числото никой не увеличава.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSickBenefitVerdictTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);
  private static final LocalDate OCT_SICK_FROM = LocalDate.of(2026, 10, 12);
  private static final LocalDate OCT_SICK_TO = LocalDate.of(2026, 10, 16);

  // ═══ (а) ПРИСТИГНАЛ ЛИСТ ЗА ЮЛИ — ПРАВОТО Е КЪМ НАЧАЛОТО МУ ════════════════

  @Test
  @DisplayName(
      "(а) пристигнал лист за юли: стаж 3 м. към 13.07, днес вече 6 — копието е БЕЗ право, т.16.4")
  void anArrivedSheetIsJudgedAtItsStart() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    // 3 месеца към 30.06: котвата е 30.03 → на 13.07 — 3 месеца, днес (октомври) — 6.
    Fixture f = BgPayrollRealPath.fixture("Va", 3, LocalDate.of(2026, 6, 30));
    LocalDate julFrom = LocalDate.of(2026, 7, 1);
    LocalDate julTo = LocalDate.of(2026, 7, 31);
    Long jul = BgPayrollRealPath.period(f, julFrom, julTo);
    Long original = BgPayrollRealPath.slip(f, jul, "JUL");
    BgPayrollRealPath.timesheet(f, julFrom, julTo, null, null);
    loginAs(f, "client.admin");
    BgPayrollRealPath.run(f, original, jul);
    BgPayrollRealPath.periodStatus(jul, PeriodRepository.STATUS_CLOSED);

    Long sheetId =
        BgPayrollRealPath.sickSheet(
            f, "sick_er", LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 17), "Е2026VERD0001");
    ActionResponse applied = pressSheet("applyArrival", sheetId, null);

    List<Long> copies =
        fresh(
            em ->
                em.createQuery(
                        "SELECT s.id FROM BgPaySlip s WHERE s.bgCorrectionOf.id = :id", Long.class)
                    .setParameter("id", original)
                    .getResultList());
    assertEquals(1, copies.size(), "едно коригиращо копие: " + applied.getData());
    BgPaySlip copy = fresh(em -> em.find(BgPaySlip.class, copies.get(0)));
    assertEquals(5, copy.getBgSickDaysNoBenefit(), "13–17.07: 5 дни без право");
    assertNull(amountOf(copy, "pl_bolnichen_rabotodatel"), "перо 195 няма — правото е към 13.07");

    BgSickLeave sheet = fresh(em -> em.find(BgSickLeave.class, sheetId));
    assertEquals(BgSickBenefitVerdictRules.NO_RIGHT, sheet.getBgBenefitVerdict());
    assertEquals(LocalDate.of(2026, 7, 13), sheet.getBgBenefitVerdictAsOf(), "към началото");
    assertEquals(3, sheet.getBgBenefitVerdictServiceMonths(), "стажът към 13.07");
    assertNotNull(sheet.getBgBenefitVerdictOn(), "кога");
  }

  // ═══ (б) НАЕТ СЪС СТАЖ 0, СЛЕД ГОДИНА — С ПРАВО ══════════════════════════

  @Test
  @DisplayName("(б) нает със стаж 0 към 30.09.2025, 12 потвърдени месеца — на 12.10.2026 С право")
  void aYearLaterTheNewcomerHasTheRight() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Fixture f = BgPayrollRealPath.fixture("Vb", 0, LocalDate.of(2025, 9, 30));
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(EmploymentContract.class, f.contractId)
                .setStartDate(LocalDate.of(2025, 10, 1)));
    for (YearMonth m = YearMonth.of(2025, 10);
        m.isBefore(YearMonth.of(2026, 10));
        m = m.plusMonths(1)) {
      BgPayrollRealPath.confirmedSlip(f, m);
    }
    Long sheetId =
        BgPayrollRealPath.sickSheet(f, "sick_er", OCT_SICK_FROM, OCT_SICK_TO, "Е2026VERD0002");
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, OCT_SICK_FROM, OCT_SICK_TO);
    loginAs(f, "client.admin");

    Result r = BgPayrollRealPath.run(f, slipId, oct);

    assertEquals(0, r.slip.getBgSickDaysNoBenefit(), "с право: " + r.trace);
    pin("2", r.rec.getT16aEmployerSickDays(), "т.16.А — първите два дни");
    pin("0", r.rec.getT164NoContributionDays(), "т.16.4");
    assertNotNull(amountOf(r.slip, "pl_bolnichen_rabotodatel"), "перо 195 има");
    BgSickLeave sheet = fresh(em -> em.find(BgSickLeave.class, sheetId));
    assertEquals(BgSickBenefitVerdictRules.HAS_RIGHT, sheet.getBgBenefitVerdict());
    assertEquals(12, sheet.getBgBenefitVerdictServiceMonths(), "0 + 12 потвърдени месеца");
  }

  // ═══ (в) ЛИПСВАЩА ИСТОРИЯ — ОТКАЗ ═════════════════════════════════════════

  @Test
  @DisplayName("(в) стаж 0 към 30.09.2025 без потвърдени фишове след това — отказ, без перо 195")
  void missingHistoryIsRefused() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Fixture f = BgPayrollRealPath.fixture("Vc", 0, LocalDate.of(2025, 9, 30));
    Long sheetId =
        BgPayrollRealPath.sickSheet(f, "sick_er", OCT_SICK_FROM, OCT_SICK_TO, "Е2026VERD0003");
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, OCT_SICK_FROM, OCT_SICK_TO);
    loginAs(f, "client.admin");

    BgPayrollRealPath.press("recalculate", slipId);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, slipId));

    assertNull(amountOf(slip, "pl_bolnichen_rabotodatel"), "без присъда няма перо");
    String gaps = String.valueOf(slip.getBgGeneratorGaps());
    assertTrue(gaps.contains("няма потвърден фиш"), "отказът назовава месеците: " + gaps);
    assertNull(
        fresh(em -> em.find(BgSickLeave.class, sheetId)).getBgBenefitVerdict(),
        "отказ не се замразява");
  }

  // ═══ (г) „ОПРЕДЕЛИ ПРАВОТО НАНОВО“ — С ПРИЧИНА, ЗАД ГЕЙТ ═══════════════════

  @Test
  @DisplayName("(г) ново определяне: client.admin с причина — сменя присъдата; all.read — отказ")
  void redeterminingTheRightIsExplicitAndGated() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Fixture f = BgPayrollRealPath.fixture("Vd", 3);
    Long sheetId =
        BgPayrollRealPath.sickSheet(f, "sick_er", OCT_SICK_FROM, OCT_SICK_TO, "Е2026VERD0004");
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, OCT_SICK_FROM, OCT_SICK_TO);
    loginAs(f, "client.admin");
    BgPayrollRealPath.press("recalculate", slipId);
    assertEquals(
        BgSickBenefitVerdictRules.NO_RIGHT,
        fresh(em -> em.find(BgSickLeave.class, sheetId)).getBgBenefitVerdict(),
        "замразено при пресмятането");

    // ТРЗ открива още стаж: 8 месеца към 30.09.
    BgPayrollRealPath.serviceMonths(f, 8, BgPayrollRealPath.SERVICE_AS_OF);

    ActionResponse done =
        pressSheet("redetermineRight", sheetId, "намерен стаж от предишен работодател");
    BgSickLeave sheet = fresh(em -> em.find(BgSickLeave.class, sheetId));
    assertEquals(
        BgSickBenefitVerdictRules.HAS_RIGHT,
        sheet.getBgBenefitVerdict(),
        String.valueOf(done.getData()));
    assertEquals("намерен стаж от предишен работодател", sheet.getBgBenefitVerdictReason());

    loginAs(f, "all.read");
    ActionResponse refused = pressSheet("redetermineRight", sheetId, "опит без право");
    assertTrue(
        String.valueOf(refused.getData()).contains("redetermine the benefit right"),
        String.valueOf(refused.getData()));
    assertEquals(
        "намерен стаж от предишен работодател",
        fresh(em -> em.find(BgSickLeave.class, sheetId)).getBgBenefitVerdictReason(),
        "all.read не сменя нищо");

    // ⚠️ ОТКАЗЪТ БЕЗ ПРИЧИНА Е ПОСЛЕДЕН: rollback-ът откача контекста, а следващо действие чете
    // служителя наново — с формулата за PostgreSQL, която HSQLDB не изпълнява.
    loginAs(f, "client.admin");
    ActionResponse noReason = pressSheet("redetermineRight", sheetId, null);
    assertTrue(
        String.valueOf(noReason.getData()).contains("иска причина"),
        String.valueOf(noReason.getData()));
    assertEquals(
        "намерен стаж от предишен работодател",
        fresh(em -> em.find(BgSickLeave.class, sheetId)).getBgBenefitVerdictReason(),
        "без причина нищо не се сменя");
  }

  // ═══ ПОМОЩНИТЕ ═══════════════════════════════════════════════════════════

  /** Натискане на действие на листа — контролерът от Guice, без транзакция. */
  private static ActionResponse pressSheet(String action, Long sheetId, String reason) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", sheetId);
    context.put("_model", BgSickLeave.class.getName());
    if (reason != null) {
      context.put("bgBenefitVerdictReasonInput", reason);
    }
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgSickLeave.class.getName());
    request.setData(data);
    ActionResponse response = new ActionResponse();
    assertFalse(JPA.em().getTransaction().isActive(), "действието тръгва БЕЗ транзакция");
    BgSickLeaveController controller = Beans.get(BgSickLeaveController.class);
    if ("applyArrival".equals(action)) {
      controller.applyArrival(request, response);
    } else {
      controller.redetermineRight(request, response);
    }
    return response;
  }

  /**
   * Ролите от каталога — ВЕДНЪЖ на схема и ПРЕДИ данните: първото зареждане изпразва контекста, а
   * след него HSQLDB не изпълнява формулата на Partner за PostgreSQL.
   */
  private static void warmUpRoles() throws java.io.IOException {
    long present =
        JPA.em()
            .createQuery(
                "SELECT COUNT(r) FROM Role r WHERE r.name IN ('client.admin', 'all.read')",
                Long.class)
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
    BgRoleLogin.login(JPA.em().find(Company.class, id[0]), "client.admin", "all.read");
    BgRoleLogin.logout();
    BgPayrollRealPath.seed();
  }

  private static void loginAs(Fixture f, String role) {
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, f.companyId), role);
  }
}
