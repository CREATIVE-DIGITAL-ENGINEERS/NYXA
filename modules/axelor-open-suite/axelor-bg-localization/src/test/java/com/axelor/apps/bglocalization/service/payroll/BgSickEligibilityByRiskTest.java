package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.pin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Result;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ПРАВОТО НА ОБЕЗЩЕТЕНИЕ СЕ ИЗЧИСЛЯВА ПО РИСКА, НЕ ПО ПЛАТЕЦА — по реалния път ({@link
 * BgPayrollRealPath}: сийдът → „Преизчисли“ → „Потвърди“ → Д1).
 *
 * <p><b>Основание.</b> КСО чл. 40 ал. 1 — шест месеца осигурителен стаж за паричното обезщетение
 * при временна неработоспособност, без значение кой плаща първите два дни (чл. 40 ал. 5).
 * Изключения: трудова злополука и професионална болест (ал. 2), ненавършили 18 години (ал. 1 изр.
 * 2). Указанията за Д1: т. 16.2 — дни с право на обезщетение; т. 16.4 — неплатени дни ВН, зачетени
 * за стаж (КСО чл. 9 ал. 2 т. 2).
 *
 * <p><b>Защо по риска.</b> Резолвер, който пита платеца ПРЕДИ прага, пуска самостоятелен лист ДОО,
 * гледане на болен и вид „без право на обезщетение“ с „не“ без стаж и дните отиват в т. 16.2 —
 * видът, въведен от човек, става присъдата. Затова видът е твърдение и се сверява с изчислената
 * присъда — при разминаване „Потвърди“ отказва.
 *
 * <p><b>Ролите.</b> Действията вървят като {@code all.write} — {@link BgRoleLogin#WRITE_ROLE}
 * (каталогът на nyxa-security, само активното дружество), а {@code all.read} получава отказ при
 * „Потвърди“.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSickEligibilityByRiskTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);

  /** пн 12.10 – пт 16.10: пет работни дни, нула празници. */
  private static final LocalDate FROM = LocalDate.of(2026, 10, 12);

  private static final LocalDate TO = LocalDate.of(2026, 10, 16);

  /** Сийдът, фикстурата, листът, периодът, черновата и отчетът за време — ДАННИТЕ, без роля. */
  private static final class Scenario {
    Fixture f;
    Long oct;
    Long slipId;
  }

  private static Scenario scenario(String letter, Integer months, String code, String no)
      throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Scenario s = new Scenario();
    s.f = BgPayrollRealPath.fixture(letter, months);
    BgPayrollRealPath.sickSheet(s.f, code, FROM, TO, no);
    s.oct = BgPayrollRealPath.period(s.f, OCT_FROM, OCT_TO);
    s.slipId = BgPayrollRealPath.slip(s.f, s.oct, "OCT");
    BgPayrollRealPath.timesheet(s.f, OCT_FROM, OCT_TO, FROM, TO);
    return s;
  }

  /**
   * Входът с роля от каталога — СЛЕД данните, непосредствено преди действията.
   *
   * <p>⚠️ И ДВЕТЕ ПОДРЕДБИ СА ИЗМЕРЕНИ. (1) Без откачане seed() оставя администратор на нишката и
   * записът на новия потребител пуска одита → MailMessage → GlobalEntityListener на отделна нишка,
   * която чака заключването на същата транзакция в HSQLDB: тестът висеше безкрайно. (2) Вход ПРЕДИ
   * данните: първото зареждане на ролята от каталога оставя контекста така, че следващият flush
   * чете Partner с формулата за PostgreSQL, която HSQLDB не изпълнява. Образецът е {@code
   * BgSickNoteArrivalAtomicityTest}: данните първо, ролята накрая.
   */
  /**
   * Двете роли от каталога — ВЕДНЪЖ на схема и ПРЕДИ данните.
   *
   * <p>⚠️ ИЗМЕРЕНО: първото зареждане на роля (CSVImporter на nyxa-security) изпразва контекста, и
   * следващото пресмятане чете Partner наново — с формулата за PostgreSQL, която HSQLDB не
   * изпълнява. Падаше само първият тест на класа. Заредени тук, преди фикстурата, ролите не пипат
   * контекста на данните; {@link #loginAs} после само ги дава.
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

  // ═══ (а) САМОСТОЯТЕЛЕН ЛИСТ ДОО ПОД ПРАГА ═══════════════════════════════════

  @Test
  @DisplayName(
      "(а) самостоятелен sick_doo при стаж 3 месеца — т.16.4 = 5, т.16.2 = 0, без перо 195")
  void aStandaloneDooSheetUnderTheThresholdGoesTo164() throws Exception {
    Scenario s = scenario("Ra", 3, "sick_doo", "Е2026RISK0001");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    Result r = BgPayrollRealPath.run(s.f, s.slipId, s.oct);

    assertEquals(5, r.slip.getBgSickDaysNoBenefit(), "присъдата е замразена на фиша: " + r.trace);
    pin("5", r.rec.getT164NoContributionDays(), "т.16.4 — дните без право");
    pin("0", r.rec.getT162SickDays(), "т.16.2 — НЕ „с право“ само защото листът е ДОО");
    pin("0", r.rec.getT16aEmployerSickDays(), "т.16.А — платецът е ДОО");
    pin("17", r.rec.getT161WorkedDays(), "т.16.1 = 22 − 5");
    assertNull(amountOf(r.slip, "pl_bolnichen_rabotodatel"), "перо 195 няма");
  }

  // ═══ (б) ГЛЕДАНЕ НА БОЛНО ДЕТЕ ПОД ПРАГА ══════════════════════════════════

  @Test
  @DisplayName("(б) care_sick_child_u18 при стаж 3 месеца — същият риск, т.16.4 = 5")
  void caringForASickChildUnderTheThresholdGoesTo164() throws Exception {
    Scenario s = scenario("Rb", 3, "care_sick_child_u18", "Е2026RISK0002");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    Result r = BgPayrollRealPath.run(s.f, s.slipId, s.oct);

    assertEquals(5, r.slip.getBgSickDaysNoBenefit(), r.trace);
    pin("5", r.rec.getT164NoContributionDays(), "т.16.4");
    pin("0", r.rec.getT162SickDays(), "т.16.2");
  }

  // ═══ (в) ВИД „БЕЗ ПРАВО“ ПРИ ЛИЦЕ С ПРАВО ══════════════════════════════════

  @Test
  @DisplayName(
      "(в) sick_no_benefit_er при стаж 24 месеца — отказ: „Потвърди“ не минава, фишът е чернова")
  void aNoBenefitSheetForAPersonWithTheRightIsRefused() throws Exception {
    Scenario s = scenario("Rc", 24, "sick_no_benefit_er", "Е2026RISK0003");
    Long slipId = s.slipId;
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    ActionResponse recalc = BgPayrollRealPath.press("recalculate", slipId);
    BgPaySlip calculated = fresh(em -> em.find(BgPaySlip.class, slipId));
    String refusal = calculated.getBgSickDaysRefusal();
    assertNotNull(refusal, "отказаният разбив е на фиша: " + recalc.getData());
    assertTrue(
        refusal.contains("sick_no_benefit_er") && refusal.contains("С ПРАВО"),
        "отказът назовава вида и присъдата: " + refusal);
    assertNull(amountOf(calculated, "pl_bolnichen_rabotodatel"), "перо 195 не е родено тихо");

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът чисти контекста (виж
    // BgD1SickWithoutBenefitTest).
    ActionResponse confirm = BgPayrollRealPath.press("confirm", slipId);
    String text = String.valueOf(confirm.getData());
    assertEquals(
        BgPaySlipResolver.DRAFT,
        fresh(em -> em.find(BgPaySlip.class, slipId)).getStatusSelect(),
        "НЕ е потвърден тихо: " + text);
    assertTrue(
        text.contains("Болничните дни НЕ са разделени") && text.contains("sick_no_benefit_er"),
        "„Потвърди“ назовава причината: " + text);
  }

  // ═══ (г) РОЛЯТА: all.read НЕ ЗАПИСВА ═════════════════════════════════════

  @Test
  @DisplayName("(г) all.read — „Потвърди“ се отказва; фишът остава чернова")
  void allReadCannotConfirm() throws Exception {
    Scenario s = scenario("Rd", 3, "sick_doo", "Е2026RISK0004");
    Long slipId = s.slipId;
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);
    BgPayrollRealPath.press("recalculate", slipId);

    loginAs(s.f, BgRoleLogin.READ_ROLE);
    ActionResponse confirm = BgPayrollRealPath.press("confirm", slipId);

    assertEquals(
        BgPaySlipResolver.DRAFT,
        fresh(em -> em.find(BgPaySlip.class, slipId)).getStatusSelect(),
        "all.read не потвърждава: " + confirm.getData());
    assertNotNull(confirm.getData(), "отказът е на екрана, не тих");
  }
}
