package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Result;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.hr.db.Employee;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ЕДНОКРАТНИТЕ ПОМОЩИ — ПРЕВИШЕНИЕТО НАД ТАВАНА ВЛИЗА В ОБЛАГАЕМИЯ ДОХОД НА ЛИЦЕТО, по реалния път
 * ({@link BgPayrollRealPath}): „Преизчисли“ → „Потвърди“ → Д1, с {@code client.admin}.
 *
 * <p><b>Норма.</b> ЗДДФЛ чл. 24 ал. 2 т. 11 — „стойността на еднократните помощи, предоставени от
 * работодателя за сметка на социалните разходи при: раждане или осиновяване на дете, сключване на
 * граждански брак или смърт на член от семейството - общо до 2400 лв.;“ — календарна година, един
 * работодател. 2400 ÷ 1,95583 = 1227,10 € (параметърът в {@code bg_social_expense_cap.csv}).
 * Превишението е облагаем доход по чл. 24 ал. 1 във връзка с чл. 205 ЗКПО и влиза в основата по чл.
 * 42 ал. 2.
 *
 * <p><b>Мерило.</b> Всеки свидетел има ДВОЙНИК без помощта — друго лице в друго дружество, същият
 * месец, същите условия на договора. Разликите в основата, данъка, т. 21 и т. 31 са помощта и само
 * тя. Лицето е без ТЕЛК: облекчението по чл. 18 щеше да глътне част от превишението и „10 на сто от
 * основата“ нямаше да е видимо.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSocialAidExcessTest extends JpaSupport {

  private static final String CLIENT_ADMIN = "client.admin";
  private static final String BIRTH = "pl_soc_pomosht_razhdane_v_ramkite";
  private static final String BIRTH_EXCESS = "pl_soc_pomosht_razhdane_previshenie";
  private static final String MARRIAGE = "pl_soc_pomosht_brak_v_ramkite";
  private static final String MARRIAGE_EXCESS = "pl_soc_pomosht_brak_previshenie";
  private static final String DEATH = "pl_soc_pomosht_smart_v_ramkite";
  private static final String DEATH_EXCESS = "pl_soc_pomosht_smart_previshenie";

  private static final LocalDate JUL = LocalDate.of(2026, 7, 1);
  private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
  private static final LocalDate SEP = LocalDate.of(2026, 9, 1);

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ (1) 1000 € — В РАМКИТЕ ═════════════════════════════════════════════

  @Test
  @DisplayName("(1) помощ 1000 € — без превишение, основата и данъкът са тези на двойника")
  void underTheCapNothingIsTaxed() throws Exception {
    Fixture f = scene("Va");
    Long jul = month(f, JUL);
    Result twin = twin(f, JUL);
    Result r = BgPayrollRealPath.run(f, aidSlip(f, jul, "JUL", BIRTH, "1000.00"), jul);

    pin("1000.00", amountOf(r.slip, BIRTH), "перо 220 — цялата помощ е в рамките");
    assertNull(lineOf(r.slip, BIRTH_EXCESS), "превишение няма: " + BgPayrollRealPath.lines(r.slip));
    diff("0.00", twin.slip.getBgTaxBase(), r.slip.getBgTaxBase(), "основата по чл. 42 ал. 2");
    diff("0.00", twin.slip.getBgTaxAmount(), r.slip.getBgTaxAmount(), "данъкът");
    diff("0.00", twin.rec.getT31TaxableIncome(), r.rec.getT31TaxableIncome(), "Д1 т. 31");
  }

  // ═══ (2) 1500 € — ПРЕВИШЕНИЕ 272,90 ═════════════════════════════════════

  @Test
  @DisplayName("(2) помощ 1500 € — 272,90 в 221, облагаемо; данъкът расте с точно 27,29")
  void overTheCapTheExcessIsTaxed() throws Exception {
    Fixture f = scene("Vb");
    Long jul = month(f, JUL);
    Result twin = twin(f, JUL);
    Long slipId = aidSlip(f, jul, "JUL", BIRTH, "1500.00");
    // ⚠️ ДВЕ „Преизчисли“ ПРЕДИ „Потвърди“: второто трябва да дели ВЪВЕДЕНАТА сума (1500,00), не
    // вече намалената на перото (1227,10) — инак превишението изчезва.
    BgPayrollRealPath.press("recalculate", slipId);
    Result r = BgPayrollRealPath.run(f, slipId, jul);

    BgPaySlipLine within = lineOf(r.slip, BIRTH);
    pin("1227.10", within.getBgAmount(), "220 — освободената част");
    pin("1500.00", within.getBgEnteredAmount(), "220 — въведената пълна сума стои");
    BgPaySlipLine excess = lineOf(r.slip, BIRTH_EXCESS);
    assertNotNull(excess, "превишението е перо 221: " + BgPayrollRealPath.lines(r.slip));
    pin("272.90", excess.getBgAmount(), "221 — 1500,00 − 1227,10");
    assertTrue(Boolean.TRUE.equals(excess.getBgIsGenerated()), "221 е генерирано, не ръчно");
    assertTrue(Boolean.TRUE.equals(excess.getBgIsTaxable()), "221 е облагаемо");

    diff("272.90", twin.slip.getBgTaxBase(), r.slip.getBgTaxBase(), "основата по чл. 42 ал. 2");
    diff("27.29", twin.slip.getBgTaxAmount(), r.slip.getBgTaxAmount(), "данъкът — 10 на сто");
    diff("272.90", twin.rec.getT31TaxableIncome(), r.rec.getT31TaxableIncome(), "Д1 т. 31");
    diff("0.00", twin.rec.getT21InsuranceIncome(), r.rec.getT21InsuranceIncome(), "Д1 т. 21");
  }

  // ═══ (3) 700 + 700 — ВТОРАТА ДАВА 172,90 ═══════════════════════════════

  @Test
  @DisplayName("(3) две помощи в годината (700 + 700 €) — втората дава превишение 172,90")
  void theSecondAidUsesTheRest() throws Exception {
    Fixture f = scene("Vc");
    Long jul = month(f, JUL);
    Result first = BgPayrollRealPath.run(f, aidSlip(f, jul, "JUL", BIRTH, "700.00"), jul);
    assertNull(lineOf(first.slip, BIRTH_EXCESS), "юли — в рамките");

    Long aug = month(f, AUG);
    Result twin = twin(f, AUG);
    Result r = BgPayrollRealPath.run(f, aidSlip(f, aug, "AUG", BIRTH, "700.00"), aug);
    pin("527.10", amountOf(r.slip, BIRTH), "220 — остатъкът от тавана");
    pin("172.90", amountOf(r.slip, BIRTH_EXCESS), "221 — 700,00 − (1227,10 − 700,00)");
    diff("172.90", twin.slip.getBgTaxBase(), r.slip.getBgTaxBase(), "основата");
  }

  // ═══ (4) ДРУЖЕСТВО Б НЕ ВЛИЯЕ НА А ═════════════════════════════════════════

  @Test
  @DisplayName("(4) помощ в дружество Б не намалява тавана в дружество А")
  void anotherCompanyDoesNotUseTheCap() throws Exception {
    Fixture a = scene("Vd");
    Fixture b = BgPayrollRealPath.otherCompany(a, "Ve");
    noPartners(b);

    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, b.companyId), CLIENT_ADMIN);
    Long julB = month(b, JUL);
    BgPayrollRealPath.run(b, aidSlip(b, julB, "JUL-B", BIRTH, "1000.00"), julB);

    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, a.companyId), CLIENT_ADMIN);
    Long aug = month(a, AUG);
    Result r = BgPayrollRealPath.run(a, aidSlip(a, aug, "AUG", BIRTH, "1000.00"), aug);
    pin("1000.00", amountOf(r.slip, BIRTH), "в А таванът е цял");
    assertNull(
        lineOf(r.slip, BIRTH_EXCESS), "превишение в А няма: " + BgPayrollRealPath.lines(r.slip));
  }

  // ═══ (5) КОРИГИРАЩОТО КОПИЕ ПОЛЗВА ЗАМРАЗЕНОТО ═════════════════════════════

  @Test
  @DisplayName("(5) коригиращото копие пази превишението на оригинала, не днешното")
  void theCorrectionKeepsTheFrozenExcess() throws Exception {
    Fixture a = scene("Vf");
    Long jul = month(a, JUL);
    BgPayrollRealPath.run(a, aidSlip(a, jul, "JUL", BIRTH, "700.00"), jul);
    Long sep = month(a, SEP);
    Long sepSlip = aidSlip(a, sep, "SEP", BIRTH, "700.00");
    Result r = BgPayrollRealPath.run(a, sepSlip, sep);
    pin("172.90", amountOf(r.slip, BIRTH_EXCESS), "септември при пресмятането");

    // СЛЕД септември — август по втори договор: днешното използвано за септември става 1000,00.
    Fixture b = BgPayrollRealPath.secondContract(a);
    Long aug = month(a, AUG);
    BgPayrollRealPath.run(b, aidSlip(b, aug, "AUG-B", BIRTH, "300.00"), aug);

    Long copy = correctUnchanged(sep, sepSlip);
    BgPaySlip c = slip(copy);
    pin("527.10", amountOf(c, BIRTH), "копието — 220 по замразеното");
    pin(
        "172.90",
        amountOf(c, BIRTH_EXCESS),
        "копието — 221 по замразеното; живо щеше да е 472,90 и копието щеше да има нетна разлика");
  }

  // ═══ (6) БРАК — ОСИГУРИТЕЛЕН ДОХОД; РАЖДАНЕ И СМЪРТ — НЕ ═══════════════════

  @Test
  @DisplayName(
      "(6) брак 1500 € — и 222, и 223 в т. 21; смърт 1500 € — нито едното; т. 31 и двете +272,90")
  void marriageIsInsurableBirthAndDeathAreNot() throws Exception {
    Fixture f = scene("Vg");
    Long jul = month(f, JUL);
    Result twin = twin(f, JUL);
    Result marriage = BgPayrollRealPath.run(f, aidSlip(f, jul, "JUL-M", MARRIAGE, "1500.00"), jul);

    pin("1227.10", amountOf(marriage.slip, MARRIAGE), "222 — в рамките");
    pin("272.90", amountOf(marriage.slip, MARRIAGE_EXCESS), "223 — превишението");
    assertTrue(
        Boolean.TRUE.equals(lineOf(marriage.slip, MARRIAGE).getBgIsInsuranceIncome())
            && Boolean.TRUE.equals(lineOf(marriage.slip, MARRIAGE_EXCESS).getBgIsInsuranceIncome()),
        "222 и 223 — осигурителен доход (НЕВД чл. 2 ал. 1; бракът не е в чл. 2 ал. 2)");
    diff(
        "1500.00",
        twin.rec.getT21InsuranceIncome(),
        marriage.rec.getT21InsuranceIncome(),
        "брак: Д1 т. 21 — И частта в рамките, И превишението");
    diff(
        "272.90",
        twin.rec.getT31TaxableIncome(),
        marriage.rec.getT31TaxableIncome(),
        "брак: Д1 т. 31 — само превишението");

    Fixture g = scene("Vh");
    Long julG = month(g, JUL);
    Result twinG = twin(g, JUL);
    Result death = BgPayrollRealPath.run(g, aidSlip(g, julG, "JUL-D", DEATH, "1500.00"), julG);
    pin("272.90", amountOf(death.slip, DEATH_EXCESS), "225 — превишението");
    assertTrue(
        !Boolean.TRUE.equals(lineOf(death.slip, DEATH).getBgIsInsuranceIncome())
            && !Boolean.TRUE.equals(lineOf(death.slip, DEATH_EXCESS).getBgIsInsuranceIncome()),
        "224 и 225 — НЕ осигурителен доход (НЕВД чл. 2 ал. 2)");
    diff(
        "0.00",
        twinG.rec.getT21InsuranceIncome(),
        death.rec.getT21InsuranceIncome(),
        "смърт: Д1 т. 21 — нищо от помощта");
    diff(
        "272.90",
        twinG.rec.getT31TaxableIncome(),
        death.rec.getT31TaxableIncome(),
        "смърт: Д1 т. 31 — само превишението");
  }

  // ═══ (7) ЛИПСВАЩ ВХОД — ШУМЕН ОТКАЗ ═════════════════════════════════════

  @Test
  @DisplayName("(7) перо от 2xx без вид (събитието е неизвестно) — шумен отказ, нищо не е записано")
  void aSocialLineWithoutKindIsRefused() throws Exception {
    Fixture f = scene("Vi");
    Long jul = month(f, JUL);
    Long slipId = aidSlip(f, jul, "JUL", BIRTH, "1500.00");
    JPA.runInTransaction(
        () -> {
          for (BgPaySlipLine l : JPA.em().find(BgPaySlip.class, slipId).getBgPaySlipLineList()) {
            if (l.getBgLineNumber() == 220) {
              l.setBgPaySlipLineType(null);
            }
          }
        });
    ActionResponse recalc = BgPayrollRealPath.press("recalculate", slipId);
    String text = String.valueOf(recalc.getData());
    assertTrue(text.contains("220") && text.contains("вид"), "отказът назовава перото: " + text);
    assertNull(lineOf(slip(slipId), BIRTH_EXCESS), "нищо не е генерирано");
  }

  // ═══ сцената ════════════════════════════════════════════════════════════

  /** Лице без ТЕЛК, без Partner (виж BgSocialCapUsageCountTest#scene); client.admin. */
  private static Fixture scene(String letter) throws Exception {
    BgPayrollRealPath.seedData();
    Fixture f = BgPayrollRealPath.fixture(letter, 12);
    noPartners(f);
    JPA.runInTransaction(
        () -> {
          Employee e = JPA.em().find(Employee.class, f.employeeId);
          e.setBgDisabilityRelief(false);
          e.setBgDisabilityValidUntil(null);
        });
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, f.companyId), CLIENT_ADMIN);
    return f;
  }

  private static void noPartners(Fixture f) {
    JPA.runInTransaction(
        () -> {
          JPA.em().find(Employee.class, f.employeeId).setContactPartner(null);
          JPA.em().find(Company.class, f.companyId).setPartner(null);
        });
  }

  private static Long month(Fixture f, LocalDate from) {
    LocalDate to = from.plusMonths(1).minusDays(1);
    Long id = BgPayrollRealPath.period(f, from, to);
    BgPayrollRealPath.timesheet(f, from, to, null, null);
    return id;
  }

  /**
   * ДВОЙНИКЪТ — друго лице в друго дружество, същите условия, същият месец, БЕЗ помощ. Друго лице,
   * а не втори договор на същото: два договора на едно лице в един месец се събират в един запис на
   * Д1 и разликата нямаше да е чиста. Влиза се като client.admin на дружеството му и се връща.
   */
  private static Result twin(Fixture of, LocalDate from) throws Exception {
    Fixture t = scene(of.tag.substring(0, 2) + "0");
    Long period = month(t, from);
    Result r = BgPayrollRealPath.run(t, BgPayrollRealPath.slip(t, period, "TWIN"), period);
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, of.companyId), CLIENT_ADMIN);
    return r;
  }

  private static Long aidSlip(Fixture f, Long periodId, String suffix, String code, String amount) {
    Long id = BgPayrollRealPath.slip(f, periodId, suffix);
    JPA.runInTransaction(
        () -> {
          BgPaySlip slip = JPA.em().find(BgPaySlip.class, id);
          BgPaySlipLineType type = BgPayrollRealPath.lineType(code);
          BgPaySlipService service = Beans.get(BgPaySlipService.class);
          service.addLine(slip, service.lineFrom(type, new BigDecimal(amount)));
        });
    return id;
  }

  private static Long correctUnchanged(Long periodId, Long originalId) {
    BgPayrollRealPath.periodStatus(periodId, PeriodRepository.STATUS_CLOSED);
    ActionResponse correct = BgPayrollRealPath.press("correct", originalId);
    Long copy =
        BgPayrollRealPath.fresh(
            em -> {
              List<Long> found =
                  em.createQuery(
                          "SELECT s.id FROM BgPaySlip s WHERE s.bgCorrectionOf.id = :o"
                              + " ORDER BY s.id DESC",
                          Long.class)
                      .setParameter("o", originalId)
                      .getResultList();
              return found.isEmpty() ? null : found.get(0);
            });
    assertNotNull(copy, "коригиращото копие е създадено: " + correct.getData());
    ActionResponse recalc = BgPayrollRealPath.press("recalculate", copy);
    ActionResponse confirm = BgPayrollRealPath.press("confirm", copy);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        slip(copy).getStatusSelect(),
        "копието е потвърдено: " + recalc.getData() + " / " + confirm.getData());
    return copy;
  }

  private static BgPaySlip slip(Long id) {
    return BgPayrollRealPath.fresh(em -> em.find(BgPaySlip.class, id));
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, String code) {
    return BgPayrollRealPath.lineOf(slip, code);
  }

  private static BigDecimal amountOf(BgPaySlip slip, String code) {
    return BgPayrollRealPath.amountOf(slip, code);
  }

  private static void pin(String expected, Object actual, String what) {
    BgPayrollRealPath.pin(expected, actual, what);
  }

  /** Разликата „с помощта − двойника“. */
  private static void diff(String expected, BigDecimal twin, BigDecimal with, String what) {
    assertNotNull(twin, what + ": двойникът е празен");
    assertNotNull(with, what + ": празно");
    pin(expected, with.subtract(twin), what + " (с помощта " + with + ", двойник " + twin + ")");
  }
}
