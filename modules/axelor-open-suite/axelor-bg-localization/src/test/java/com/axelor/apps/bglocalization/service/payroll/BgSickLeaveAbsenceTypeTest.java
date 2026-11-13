package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
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
 * НА БОЛНИЧЕН ЛИСТ СЕ ДОПУСКА САМО ВИД, КОЙТО СЕ УДОСТОВЕРЯВА С БОЛНИЧЕН ЛИСТ — по реалния път
 * ({@link BgPayrollRealPath}: сийдът → „Преизчисли“ → „Потвърди“).
 *
 * <p><b>Защо.</b> Без ограничение на вида лист с „Платен годишен отпуск“ (платец осигурителят, 100
 * на сто) дава перо 195 „Болничен от осигурител“ за два дни, а останалите дни — в т. 16.2 и в
 * базата на здравната вноска по ЗЗО чл. 40 ал. 1 т. 5. Основание: КСО чл. 40 ал. 5 —
 * възнаграждението на осигурителя е за временна неработоспособност, удостоверена с болничен лист.
 *
 * <p><b>Двата пласта.</b> Записът през repository-то отказва (формата, {@code /ws/rest}, импорт);
 * пътят на фиша отказва разбива за заварен лист или запис покрай repository-то — тук листът се
 * записва пряко с {@code persist}, точно за да мине покрай първия пласт.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSickLeaveAbsenceTypeTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);
  private static final LocalDate FROM = LocalDate.of(2026, 10, 12);
  private static final LocalDate TO = LocalDate.of(2026, 10, 16);

  private static final class Scenario {
    Fixture f;
    Long oct;
    Long slipId;
  }

  /** Данните, без роля: лист с дадения вид, записан ПОКРАЙ repository-то. */
  private static Scenario scenario(String letter, String code, String no) throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Scenario s = new Scenario();
    s.f = BgPayrollRealPath.fixture(letter, 24);
    BgPayrollRealPath.sickSheet(s.f, code, FROM, TO, no);
    s.oct = BgPayrollRealPath.period(s.f, OCT_FROM, OCT_TO);
    s.slipId = BgPayrollRealPath.slip(s.f, s.oct, "OCT");
    BgPayrollRealPath.timesheet(s.f, OCT_FROM, OCT_TO, FROM, TO);
    return s;
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

  private static BgAbsenceType type(String code) {
    return JPA.em()
        .createQuery("SELECT t FROM BgAbsenceType t WHERE t.code = :c", BgAbsenceType.class)
        .setParameter("c", code)
        .getSingleResult();
  }

  // ═══ (а) ЗАВАРЕН ЛИСТ С „ПЛАТЕН ГОДИШЕН ОТПУСК“ ═════════════════════════════

  @Test
  @DisplayName("(а) лист с paid_annual (покрай repository-то) — отказ на разбива, без перо 195")
  void aSheetWithAnnualLeaveIsRefusedOnThePaySlip() throws Exception {
    Scenario s = scenario("Ta", "paid_annual", "Е2026TYPE0001");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    ActionResponse recalc = BgPayrollRealPath.press("recalculate", s.slipId);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, s.slipId));

    assertNull(
        amountOf(slip, "pl_bolnichen_rabotodatel"),
        "перо 195 за отпуск, въведен като болничен: " + BgPayrollRealPath.lines(slip));
    assertEquals(0, slip.getBgSickDaysEmployer().intValue(), "дните не са записани тихо");
    assertEquals(0, slip.getBgSickDaysDoo().intValue(), "нито дни ДОО → т. 16.2");
    String gaps = String.valueOf(slip.getBgGeneratorGaps());
    assertTrue(
        gaps.contains("Е2026TYPE0001") && gaps.contains("не се удостоверява с болничен лист"),
        "отказът назовава листа и причината: " + gaps + " · " + recalc.getData());

    // Отказаният разбив спира и „Потвърди“ — ⚠️ последното действие: отказът връща
    // транзакцията.
    ActionResponse confirm = BgPayrollRealPath.press("confirm", s.slipId);
    assertEquals(
        BgPaySlipResolver.DRAFT,
        fresh(em -> em.find(BgPaySlip.class, s.slipId)).getStatusSelect(),
        "листът с вид отпуск НЕ минава „Потвърди“: " + confirm.getData());
    assertTrue(
        String.valueOf(confirm.getData()).contains("не се удостоверява с болничен лист"),
        String.valueOf(confirm.getData()));
  }

  @Test
  @DisplayName("(а2) лист с sicknote_unsubmitted — също отказ: това е отсъствието БЕЗ лист")
  void aSheetWithTheUnsubmittedTypeIsRefusedOnThePaySlip() throws Exception {
    Scenario s = scenario("Tb", "sicknote_unsubmitted", "Е2026TYPE0002");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    BgPayrollRealPath.press("recalculate", s.slipId);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, s.slipId));

    assertEquals(0, slip.getBgSickDaysDoo().intValue(), "нито дни ДОО → т. 16.2");
    assertTrue(
        String.valueOf(slip.getBgGeneratorGaps()).contains("ДОКАТО болничният лист липсва"),
        String.valueOf(slip.getBgGeneratorGaps()));
  }

  // ═══ (б) ЗАПИСЪТ ПРЕЗ REPOSITORY-ТО ══════════════════════════════════════

  @Test
  @DisplayName("(б) запис през repository-то: paid_annual — отказ; sick_er — записва се")
  void theRepositoryRefusesANonSickLeaveType() throws Exception {
    Scenario s = scenario("Tc", "sick_er", "Е2026TYPE0003");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    Long[] saved = new Long[1];
    JPA.runInTransaction(
        () ->
            saved[0] = Beans.get(BgSickLeaveRepository.class).save(sheet(s.f, "sick_er")).getId());
    assertNotNull(saved[0], "листът с вид от болничен лист се записва");

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът откача контекста, а следващо четене зарежда
    // Partner наново — формулата за PostgreSQL, която HSQLDB не изпълнява.
    BgSickLeave wrong = sheet(s.f, "paid_annual");
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> JPA.runInTransaction(() -> Beans.get(BgSickLeaveRepository.class).save(wrong)));
    assertTrue(refused.getMessage().contains("не е записан"), refused.getMessage());
    assertTrue(refused.getMessage().contains("paid_annual"), refused.getMessage());
  }

  private static BgSickLeave sheet(Fixture f, String code) {
    BgSickLeave sheet = new BgSickLeave();
    sheet.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
    sheet.setBgAbsenceType(type(code));
    sheet.setFromDate(LocalDate.of(2026, 10, 20));
    sheet.setToDate(LocalDate.of(2026, 10, 21));
    sheet.setBgIncapacityStartDate(LocalDate.of(2026, 10, 20));
    sheet.setBgIsContinuation(false);
    sheet.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
    return sheet;
  }

  // ═══ (в) РОЛЯТА: all.read НЕ ЗАПИСВА ═════════════════════════════════════

  @Test
  @DisplayName("(в) all.read — „Потвърди“ се отказва; фишът остава чернова")
  void allReadCannotConfirm() throws Exception {
    Scenario s = scenario("Td", "sick_er", "Е2026TYPE0004");
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
