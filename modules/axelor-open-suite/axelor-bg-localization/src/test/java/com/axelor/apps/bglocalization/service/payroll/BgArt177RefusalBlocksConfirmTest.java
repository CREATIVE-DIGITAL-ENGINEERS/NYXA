package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.pin;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.quantityOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ОТКАЗЪТ ПО ЧЛ. 177 СПИРА „ПОТВЪРДИ“, по реалния път ({@link BgPayrollRealPath}).
 *
 * <p><b>Сцената е вариант „Б“ на {@code BgTrzEtalonWitnessTest}:</b> септември — цял отработен
 * базов месец; октомври — 16 отработени + 4 дни ПГО + 2 дни по чл. 157 (режимът по чл. 177).
 * Разликата е една: СЛЕД като септември е потвърден, в него се появява заявка за отпуск
 * събота–неделя. Тя не покрива нито един работен ден, тоест отсъствията на базовия месец не могат
 * да се преброят честно ({@code BgArt177LeaveService#findBaseMonth}) и чл. 177 отказва.
 *
 * <p><b>Рискът.</b> Отказ, който седи само в присъдите, не стига: дните на отпуска вече са свалени
 * от основната заплата (карвът), перо 165 го няма и ако „Потвърди“ мине, отпускът остава неплатен,
 * без грешка.
 *
 * <p><b>Ролите</b> — по образеца на {@code BgSickEligibilityByRiskTest}: действията са като {@code
 * all.write}, а {@code all.read} не потвърждава. Лицето и дружеството са без Partner: отказаното
 * „Потвърди“ връща транзакцията и чисти контекста, а HSQLDB не изпълнява PostgreSQL-формулите на
 * Partner при презареждането (виж {@code BgSocialCapUsageCountTest#scene}).
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgArt177RefusalBlocksConfirmTest extends JpaSupport {

  private static final LocalDate SEP_FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate SEP_TO = LocalDate.of(2026, 9, 30);
  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);

  /** Събота–неделя в базовия месец: заявка, която не покрива нито един работен ден. */
  private static final LocalDate SAT = LocalDate.of(2026, 9, 5);

  private static final LocalDate SUN = LocalDate.of(2026, 9, 6);

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  /** Сцената след потвърден септември и подготвен октомври — ДАННИТЕ; ролята — накрая. */
  private static final class Scene {
    Fixture f;
    Long octSlipId;
  }

  private static Scene scene(String letter) throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Scene s = new Scene();
    s.f = BgPayrollRealPath.fixture(letter, 120);
    JPA.runInTransaction(
        () -> {
          JPA.em().find(Employee.class, s.f.employeeId).setContactPartner(null);
          JPA.em().find(Company.class, s.f.companyId).setPartner(null);
        });
    Long sep = BgPayrollRealPath.period(s.f, SEP_FROM, SEP_TO);
    Long sepSlipId = BgPayrollRealPath.slip(s.f, sep, "SEP");
    BgPayrollRealPath.timesheet(s.f, SEP_FROM, SEP_TO, null, null);

    Long oct = BgPayrollRealPath.period(s.f, OCT_FROM, OCT_TO);
    s.octSlipId = BgPayrollRealPath.slip(s.f, oct, "OCT");
    BgPayrollRealPath.leave(
        s.f, "paid_annual", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15), 4);
    BgPayrollRealPath.leave(
        s.f, "paid_157_art177", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), 2);
    BgPayrollRealPath.timesheet(
        s.f,
        OCT_FROM,
        LocalDate.of(2026, 10, 18),
        LocalDate.of(2026, 10, 12),
        LocalDate.of(2026, 10, 15));
    BgPayrollRealPath.timesheet(
        s.f,
        LocalDate.of(2026, 10, 19),
        OCT_TO,
        LocalDate.of(2026, 10, 19),
        LocalDate.of(2026, 10, 20));

    loginAs(s.f, BgRoleLogin.WRITE_ROLE);
    BgPayrollRealPath.press("recalculate", sepSlipId);
    BgPayrollRealPath.press("confirm", sepSlipId);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        fresh(em -> em.find(BgPaySlip.class, sepSlipId)).getStatusSelect(),
        "базовият месец е потвърден");
    // СЛЕД потвърждаването: заявка събота–неделя в базовия месец.
    BgPayrollRealPath.leave(s.f, "paid_annual", SAT, SUN, 1);
    return s;
  }

  // ═══ (а) ОТКАЗ → „ПОТВЪРДИ“ СПИРА; ПОПРАВКА → ПЕРО 165 → „ПОТВЪРДИ“ МИНАВА ═══════

  @Test
  @DisplayName(
      "(а) неброими отсъствия в базовия месец — остава чернова; поправени — 165 и потвърден")
  void theRefusalBlocksConfirmUntilTheInputIsFixed() throws Exception {
    Scene s = scene("Wa");
    Long slipId = s.octSlipId;

    ActionResponse recalc = BgPayrollRealPath.press("recalculate", slipId);
    BgPaySlip refused = slip(slipId);
    String refusal = refused.getBgArt177Refusal();
    assertNotNull(refusal, "отказът е записан на фиша: " + recalc.getData());
    assertTrue(
        refusal.contains("отсъствията на базовия месец"), "отказът назовава причината: " + refusal);
    assertNull(amountOf(refused, 165), "перо 165 няма: " + BgPayrollRealPath.lines(refused));
    pin("225.53", amountOf(refused, "pl_osnovna"), "дните отпуск ВЕЧЕ са свалени от основната");

    ActionResponse confirm = BgPayrollRealPath.press("confirm", slipId);
    String text = String.valueOf(confirm.getData());
    assertEquals(
        BgPaySlipResolver.DRAFT, slip(slipId).getStatusSelect(), "фишът остава чернова: " + text);
    assertTrue(
        text.contains("чл. 177") && text.contains("отсъствията на базовия месец"),
        "„Потвърди“ отказва шумно с текста на причината: " + text);

    // Поправка на входа: заявката събота–неделя се маха.
    JPA.runInTransaction(
        () -> {
          List<LeaveRequest> bad =
              JPA.em()
                  .createQuery(
                      "SELECT l FROM LeaveRequest l WHERE l.employee.id = :e AND l.fromDateT = :from",
                      LeaveRequest.class)
                  .setParameter("e", s.f.employeeId)
                  .setParameter("from", SAT.atStartOfDay())
                  .getResultList();
          assertEquals(1, bad.size(), "заявката събота–неделя");
          JPA.em().remove(bad.get(0));
        });

    BgPayrollRealPath.press("recalculate", slipId);
    BgPaySlip fixed = slip(slipId);
    assertNull(fixed.getBgArt177Refusal(), "„Преизчисли“ без отказ изчиства полето");
    pin("6", quantityOf(fixed, 165), "165: 6 дни (4 ПГО + 2 по чл. 157)");
    pin("116.03", amountOf(fixed, 165), "165 — както в еталона Б (закръглянето не е пипано)");

    ActionResponse ok = BgPayrollRealPath.press("confirm", slipId);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        slip(slipId).getStatusSelect(),
        "след поправката „Потвърди“ минава: " + ok.getData());
  }

  // ═══ (б) all.read НЕ ПОТВЪРЖДАВА ═════════════════════════════════════════

  @Test
  @DisplayName("(б) all.read вижда отказа, но „Потвърди“ му се отказва по роля")
  void allReadSeesTheRefusalButCannotConfirm() throws Exception {
    Scene s = scene("Wb");
    Long slipId = s.octSlipId;
    BgPayrollRealPath.press("recalculate", slipId);

    loginAs(s.f, BgRoleLogin.READ_ROLE);
    assertNotNull(slip(slipId).getBgArt177Refusal(), "отказът е на фиша");
    ActionResponse confirm = BgPayrollRealPath.press("confirm", slipId);
    assertEquals(
        BgPaySlipResolver.DRAFT,
        slip(slipId).getStatusSelect(),
        "all.read не потвърждава: " + confirm.getData());
    assertNotNull(confirm.getData(), "отказът е на екрана, не тих");
  }

  // ═══ (в) НЯМА БАЗОВ МЕСЕЦ — АЛ. 2, НЕ ОТКАЗ ═══════════════════════════════

  /**
   * КТ чл. 177 ал. 2: „Когато няма месец, през който работникът е отработил най-малко 10 работни
   * дни при същия работодател, възнаграждението се определя от уговорените в трудовия договор
   * основно и допълнителни трудови възнаграждения с постоянен характер.“ — тоест липсата на базов
   * месец е резервен ред, не отказ ({@code BgArt177LeaveService#contractualRate}).
   */
  @Test
  @DisplayName("(в) нов служител без месец с 10 дни — 165 по договорното (ал. 2), без отказ")
  void withoutABaseMonthTheContractualRateApplies() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Fixture f = BgPayrollRealPath.fixture("Wc", 120);
    JPA.runInTransaction(
        () -> {
          JPA.em().find(Employee.class, f.employeeId).setContactPartner(null);
          JPA.em().find(Company.class, f.companyId).setPartner(null);
        });
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.leave(
        f, "paid_annual", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15), 4);
    BgPayrollRealPath.leave(
        f, "paid_157_art177", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), 2);
    BgPayrollRealPath.timesheet(
        f,
        OCT_FROM,
        LocalDate.of(2026, 10, 18),
        LocalDate.of(2026, 10, 12),
        LocalDate.of(2026, 10, 15));
    BgPayrollRealPath.timesheet(
        f,
        LocalDate.of(2026, 10, 19),
        OCT_TO,
        LocalDate.of(2026, 10, 19),
        LocalDate.of(2026, 10, 20));

    loginAs(f, BgRoleLogin.WRITE_ROLE);
    ActionResponse recalc = BgPayrollRealPath.press("recalculate", slipId);
    BgPaySlip s = slip(slipId);
    System.out.println("\n=== A8 (в) ===\n" + BgPayrollRealPath.lines(s));
    assertNull(s.getBgArt177Refusal(), "без базов месец НЕ е отказ: " + recalc.getData());
    pin("6", quantityOf(s, 165), "165: 6 дни");
    // Ал. 2 + НСОРЗ чл. 18 ал. 1 изр. 2: уговореното с постоянен характер (310,10 основна + 115,36
    // клас = 425,46) ÷ средномесечните работни дни за 2026 (годишните ÷ 12) × 6 дни. Без коефициент
    // по чл. 18 ал. 2 и без под (непълно работно време) — закръглянето не е пипано.
    int yearDays =
        BgPayrollRealPath.workingDays(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    assertEquals(249, yearDays, "работните дни за 2026 по календара на сийда");
    pin("123.02", amountOf(s, 165), "165 = 425,46 × 12 / 249 × 6 (ал. 2)");
    String note = String.valueOf(BgPayrollRealPath.lineOf(s, 165).getNote());
    assertTrue(note.contains("чл. 177 ал. 2"), "бележката на 165 назовава ал. 2: " + note);

    ActionResponse confirm = BgPayrollRealPath.press("confirm", slipId);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        slip(slipId).getStatusSelect(),
        "„Потвърди“ минава: " + confirm.getData());
  }

  // ═══ (г) ПРЕДИШЕН ДОГОВОР ПРИ СЪЩИЯ РАБОТОДАТЕЛ — БАЗАТА Е ОТ НЕГО ════════════

  @Test
  @DisplayName("(г) нов договор в същото дружество без месец с 10 дни — базата е от стария договор")
  void thePreviousContractAtTheSameEmployerIsTheBase() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Fixture old = fixtureWithoutPartners("Wd");
    Long sepSlipId = fullSeptember(old);
    Fixture fresh = BgPayrollRealPath.secondContract(old);
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(com.axelor.apps.hr.db.EmploymentContract.class, fresh.contractId)
                .setStartDate(OCT_FROM));
    Long octSlipId = octoberWithArt177Leave(fresh);

    loginAs(old, BgRoleLogin.WRITE_ROLE);
    confirmed(sepSlipId, "септември по стария договор");
    BgPayrollRealPath.press("recalculate", octSlipId);
    BgPaySlip s = slip(octSlipId);
    assertNull(s.getBgArt177Refusal(), "без отказ");
    pin("116.03", amountOf(s, 165), "165 по базата от стария договор — както в еталона Б");
    String note = String.valueOf(BgPayrollRealPath.lineOf(s, 165).getNote());
    assertTrue(note.contains("FISH-SEP"), "бележката назовава базовия месец: " + note);
  }

  // ═══ (д) ДРУГ РАБОТОДАТЕЛ — НЕ Е БАЗА, А АЛ. 2 ═══════════════════════════

  @Test
  @DisplayName("(д) месецът с 10 дни е в друго дружество — не се ползва, минава се по ал. 2")
  void anotherEmployerIsNotTheBase() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Fixture elsewhere = fixtureWithoutPartners("We");
    Long sepSlipId = fullSeptember(elsewhere);
    Fixture here = BgPayrollRealPath.otherCompany(elsewhere, "Wf");
    noPartners(here);
    Long octSlipId = octoberWithArt177Leave(here);

    loginAs(elsewhere, BgRoleLogin.WRITE_ROLE);
    confirmed(sepSlipId, "септември в другото дружество");
    loginAs(here, BgRoleLogin.WRITE_ROLE);
    BgPayrollRealPath.press("recalculate", octSlipId);
    BgPaySlip s = slip(octSlipId);
    assertNull(s.getBgArt177Refusal(), "без отказ");
    pin("123.02", amountOf(s, 165), "165 по ал. 2 — другото дружество е друг работодател");
  }

  // ═══ (е) АНУЛИРАН ФИШ НЕ Е БАЗОВ МЕСЕЦ ═════════════════════════════════════

  @Test
  @DisplayName("(е) анулиран септември не е базов месец — само потвърдени; минава се по ал. 2")
  void aCancelledSlipIsNotTheBase() throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Fixture f = fixtureWithoutPartners("Wg");
    Long sepSlipId = fullSeptember(f);
    Long octSlipId = octoberWithArt177Leave(f);

    loginAs(f, BgRoleLogin.WRITE_ROLE);
    confirmed(sepSlipId, "септември");
    // Заварен анулиран фиш: статусът се задава направо — пътят на анулирането не е предмет тук.
    JPA.runInTransaction(
        () ->
            JPA.em().find(BgPaySlip.class, sepSlipId).setStatusSelect(BgPaySlipResolver.CANCELLED));
    BgPayrollRealPath.press("recalculate", octSlipId);
    BgPaySlip s = slip(octSlipId);
    assertNull(s.getBgArt177Refusal(), "без отказ");
    pin("123.02", amountOf(s, 165), "165 по ал. 2 — анулираният септември не е начислено");
  }

  // ═══ общата сцена ══════════════════════════════════════════════════════════

  private static Fixture fixtureWithoutPartners(String letter) {
    Fixture f = BgPayrollRealPath.fixture(letter, 120);
    noPartners(f);
    return f;
  }

  private static void noPartners(Fixture f) {
    JPA.runInTransaction(
        () -> {
          JPA.em().find(Employee.class, f.employeeId).setContactPartner(null);
          JPA.em().find(Company.class, f.companyId).setPartner(null);
        });
  }

  /** Цял отработен септември — черновата; потвърждава се от теста, с ролята. */
  private static Long fullSeptember(Fixture f) {
    Long sep = BgPayrollRealPath.period(f, SEP_FROM, SEP_TO);
    Long id = BgPayrollRealPath.slip(f, sep, "SEP");
    BgPayrollRealPath.timesheet(f, SEP_FROM, SEP_TO, null, null);
    return id;
  }

  /** Октомври на варианта „Б“: 4 дни ПГО + 2 по чл. 157, отчет за останалите 16 дни. */
  private static Long octoberWithArt177Leave(Fixture f) {
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long id = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.leave(
        f, "paid_annual", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15), 4);
    BgPayrollRealPath.leave(
        f, "paid_157_art177", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), 2);
    BgPayrollRealPath.timesheet(
        f,
        OCT_FROM,
        LocalDate.of(2026, 10, 18),
        LocalDate.of(2026, 10, 12),
        LocalDate.of(2026, 10, 15));
    BgPayrollRealPath.timesheet(
        f,
        LocalDate.of(2026, 10, 19),
        OCT_TO,
        LocalDate.of(2026, 10, 19),
        LocalDate.of(2026, 10, 20));
    return id;
  }

  private static void confirmed(Long slipId, String what) {
    BgPayrollRealPath.press("recalculate", slipId);
    ActionResponse r = BgPayrollRealPath.press("confirm", slipId);
    assertEquals(
        BgPaySlipResolver.CONFIRMED, slip(slipId).getStatusSelect(), what + ": " + r.getData());
  }

  // ═══ ролите ═══════════════════════════════════════════════════════════════

  /** Двете роли — ВЕДНЪЖ на схема и ПРЕДИ данните (виж {@code BgSickEligibilityByRiskTest}). */
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

  private static BgPaySlip slip(Long id) {
    return fresh(em -> em.find(BgPaySlip.class, id));
  }
}
