package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipCapUsage;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ИЗПОЛЗВАНОТО ОТ ТАВАНА НА СОЦИАЛЕН РАЗХОД — КАКВО СЕ БРОИ (PR E), по реалния път ({@link
 * BgPayrollRealPath}): „Преизчисли“ → „Потвърди“ → Д1, с потребител {@code client.admin} и активно
 * дружество, не администратор.
 *
 * <p><b>Мерило.</b> Капачката ONE_OFF_AID (ЗДДФЛ чл. 24 ал. 2 т. 11, 1227,10 € на календарна година
 * при един работодател) и перо 220 „помощ при раждане — в рамките“ по 700,00 €. Броят се
 * освободените части ({@code BgPaySlipCapUsage.bgExempt}) на ДЕЙСТВАЩИТЕ ПОТВЪРДЕНИ фишове на
 * ЛИЦЕТО в ДРУЖЕСТВОТО. Втора помощ от 700,00 след първа: освободено 527,10 (= 1227,10 − 700,00).
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSocialCapUsageCountTest extends JpaSupport {

  private static final String CLIENT_ADMIN = "client.admin";
  private static final String AID = "pl_soc_pomosht_razhdane_v_ramkite";
  private static final String CAP = "ONE_OFF_AID";

  private static final LocalDate JUL = LocalDate.of(2026, 7, 1);
  private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
  private static final LocalDate SEP = LocalDate.of(2026, 9, 1);

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ (1) ЧЕРНОВАТА НЕ НАМАЛЯВА ТАВАНА ═══════════════════════════════════

  @Test
  @DisplayName("(1) чернова за юли не намалява тавана на август")
  void aDraftDoesNotUseTheCap() throws Exception {
    Fixture f = scene("Ua");
    Long jul = month(f, JUL);
    Long julSlip = aidSlip(f, jul, "JUL", "700.00");
    ActionResponse recalc = BgPayrollRealPath.press("recalculate", julSlip);
    assertEquals(
        BgPaySlipResolver.DRAFT, status(julSlip), "юли остава чернова: " + recalc.getData());

    Long aug = month(f, AUG);
    Long augSlip = aidSlip(f, aug, "AUG", "700.00");
    BgPayrollRealPath.run(f, augSlip, aug);

    pinUsage(augSlip, "0.00", "700.00", "черновата за юли не е твърдение — таванът е цял");
  }

  // ═══ (2) ОРИГИНАЛ + КОРИГИРАЩО КОПИЕ — ВЕДНЪЖ ══════════════════════════════

  @Test
  @DisplayName("(2) оригинал и потвърдено коригиращо копие се броят веднъж")
  void theOriginalAndItsCorrectionCountOnce() throws Exception {
    Fixture f = scene("Ub");
    Long jul = month(f, JUL);
    Long julSlip = aidSlip(f, jul, "JUL", "700.00");
    BgPayrollRealPath.run(f, julSlip, jul);

    Long copy = correctUnchanged(jul, julSlip);
    assertEquals(BgPaySlipResolver.CONFIRMED, status(copy), "копието е потвърдено");

    Long aug = month(f, AUG);
    Long augSlip = aidSlip(f, aug, "AUG", "700.00");
    BgPayrollRealPath.run(f, augSlip, aug);

    pinUsage(
        augSlip,
        "700.00",
        "527.10",
        "копието ЗАМЕНЯ оригинала — 700,00 веднъж, не 1400,00 (тогава освободеното щеше да е 0)");
  }

  // ═══ (3) ДВА ДОГОВОРА В ЕДНО ДРУЖЕСТВО ДЕЛЯТ ТАВАНА ════════════════════════

  @Test
  @DisplayName("(3) два договора на лицето в едно дружество делят тавана")
  void twoContractsInOneCompanyShareTheCap() throws Exception {
    Fixture a = scene("Uc");
    Long jul = month(a, JUL);
    Long julSlip = aidSlip(a, jul, "JUL", "700.00");
    BgPayrollRealPath.run(a, julSlip, jul);

    Fixture b = BgPayrollRealPath.secondContract(a);
    Long aug = month(a, AUG);
    Long augSlip = aidSlip(b, aug, "AUG-B", "700.00");
    BgPayrollRealPath.run(b, augSlip, aug);

    pinUsage(
        augSlip,
        "700.00",
        "527.10",
        "таванът е на ЛИЦЕТО при работодателя — вторият договор ползва остатъка, не нов таван");
  }

  // ═══ (4) ДРУЖЕСТВО Б НЕ ВЛИЯЕ НА А ═════════════════════════════════════════

  @Test
  @DisplayName("(4) помощта от дружество Б не намалява тавана в дружество А")
  void anotherCompanyDoesNotUseTheCap() throws Exception {
    Fixture a = scene("Ud");
    Fixture b = BgPayrollRealPath.otherCompany(a, "Ue");

    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, b.companyId), CLIENT_ADMIN);
    Long julB = month(b, JUL);
    Long julSlipB = aidSlip(b, julB, "JUL-B", "700.00");
    BgPayrollRealPath.run(b, julSlipB, julB);

    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, a.companyId), CLIENT_ADMIN);
    Long aug = month(a, AUG);
    Long augSlip = aidSlip(a, aug, "AUG", "700.00");
    BgPayrollRealPath.run(a, augSlip, aug);

    pinUsage(augSlip, "0.00", "700.00", "дружество Б е друг работодател — таванът в А е цял");
  }

  // ═══ (5) ВТОРО „ПРЕИЗЧИСЛИ“ НЕ МЕНИ НИЩО ═══════════════════════════════════

  @Test
  @DisplayName("(5) второ „Преизчисли“ дава същия фиш — един ред, същите числа")
  void aSecondRecalculationChangesNothing() throws Exception {
    Fixture f = scene("Uf");
    Long jul = month(f, JUL);
    Long julSlip = aidSlip(f, jul, "JUL", "700.00");
    BgPayrollRealPath.run(f, julSlip, jul);

    Long aug = month(f, AUG);
    Long augSlip = aidSlip(f, aug, "AUG", "700.00");
    BgPayrollRealPath.press("recalculate", augSlip);
    BigDecimal netFirst = slip(augSlip).getBgNetTotal();
    pinUsage(augSlip, "700.00", "527.10", "след първото");

    BgPayrollRealPath.run(f, augSlip, aug);
    pinUsage(augSlip, "700.00", "527.10", "след второто „Преизчисли“ и „Потвърди“");
    assertEquals(
        0, netFirst.compareTo(slip(augSlip).getBgNetTotal()), "нетото е същото след второто");
    // От PR D перото носи освободената част, а въведената помощ е в bgEnteredAmount: второто
    // „Преизчисли“ дели 700,00, не вече намалените 527,10.
    BgPaySlipLine within = BgPayrollRealPath.lineOf(slip(augSlip), AID);
    pin("700.00", within.getBgEnteredAmount(), "въведената помощ — не е делена наново");
    pin("527.10", within.getBgAmount(), "перото — освободената част");
  }

  // ═══ (6) КОРИГИРАЩОТО КОПИЕ ПОЛЗВА ЗАМРАЗЕНОТО ═════════════════════════════

  @Test
  @DisplayName("(6) коригиращото копие на септември ползва замразеното, не днешното")
  void theCorrectionUsesTheFrozenUsage() throws Exception {
    Fixture a = scene("Ug");
    Long jul = month(a, JUL);
    Long julSlip = aidSlip(a, jul, "JUL", "700.00");
    BgPayrollRealPath.run(a, julSlip, jul);

    Long sep = month(a, SEP);
    Long sepSlip = aidSlip(a, sep, "SEP", "700.00");
    BgPayrollRealPath.run(a, sepSlip, sep);
    pinUsage(sepSlip, "700.00", "527.10", "септември при пресмятането");

    // СЛЕД септември се потвърждава август по втори договор — днешното използвано за септември
    // става 700,00 + 300,00 = 1000,00. Корекцията на септември не бива да го вижда.
    Fixture b = BgPayrollRealPath.secondContract(a);
    Long aug = month(a, AUG);
    Long augSlip = aidSlip(b, aug, "AUG-B", "300.00");
    BgPayrollRealPath.run(b, augSlip, aug);

    Long copy = correctUnchanged(sep, sepSlip);
    pinUsage(
        copy,
        "700.00",
        "527.10",
        "копието наследява използваното ОТ ОРИГИНАЛА — живо щеше да е 1000,00 и освободеното 227,10");
  }

  // ═══ (7) ПОТВЪРДЕН МЕЖДУВРЕМЕННО ФИШ СПИРА „ПОТВЪРДИ“ ═══════════════════════

  @Test
  @DisplayName("(7) фиш, потвърден между „Преизчисли“ и „Потвърди“, спира потвърждаването")
  void aSlipConfirmedInBetweenStopsTheConfirmation() throws Exception {
    Fixture a = scene("Uh");
    Long aug = month(a, AUG);
    Long augSlip = aidSlip(a, aug, "AUG", "700.00");
    BgPayrollRealPath.press("recalculate", augSlip);
    pinUsage(augSlip, "0.00", "700.00", "август, пресметнат преди юли");

    Fixture b = BgPayrollRealPath.secondContract(a);
    Long jul = month(a, JUL);
    Long julSlip = aidSlip(b, jul, "JUL-B", "700.00");
    BgPayrollRealPath.run(b, julSlip, jul);

    ActionResponse confirm = BgPayrollRealPath.press("confirm", augSlip);
    String text = String.valueOf(confirm.getData());
    assertEquals(BgPaySlipResolver.DRAFT, status(augSlip), "август остава чернова: " + text);
    assertTrue(
        text.contains(CAP) && text.contains("700.00") && text.contains("0.00"),
        "отказът назовава тавана и двете числа: " + text);

    BgPayrollRealPath.run(a, augSlip, aug);
    pinUsage(augSlip, "700.00", "527.10", "след „Преизчисли“ — остатъкът");
  }

  // ═══ сцената ════════════════════════════════════════════════════════════

  /**
   * Сийдът, лицето и дружеството; потребител client.admin с това дружество за активно.
   *
   * <p>⚠️ ЛИЦЕТО Е БЕЗ Partner. Отказът и корекцията връщат транзакция, а rollback-ът чисти
   * контекста — следващото четене на договора зарежда служителя наново, а с него и Partner,
   * чиито @Formula са за PostgreSQL и HSQLDB не ги изпълнява (виж {@code BgPayrollRealPath#press}).
   * Затова и дружеството е без Partner. Д1 чете и двете null-безопасно; мерилото тук е таванът, не
   * т. 6/7.
   */
  private static Fixture scene(String letter) throws Exception {
    BgPayrollRealPath.seedData();
    Fixture f = BgPayrollRealPath.fixture(letter, 12);
    JPA.runInTransaction(
        () -> {
          JPA.em().find(Employee.class, f.employeeId).setContactPartner(null);
          JPA.em().find(Company.class, f.companyId).setPartner(null);
        });
    BgRoleLogin.login(JPA.em().find(Company.class, f.companyId), CLIENT_ADMIN);
    return f;
  }

  /** Отворен месец с отчет за времето на лицето. */
  private static Long month(Fixture f, LocalDate from) {
    LocalDate to = from.plusMonths(1).minusDays(1);
    Long id = BgPayrollRealPath.period(f, from, to);
    BgPayrollRealPath.timesheet(f, from, to, null, null);
    return id;
  }

  /** Чернова на фикстурата с перо 220 — пълната въведена сума на помощта. */
  private static Long aidSlip(Fixture f, Long periodId, String suffix, String amount) {
    Long id = BgPayrollRealPath.slip(f, periodId, suffix);
    JPA.runInTransaction(
        () -> {
          BgPaySlip slip = JPA.em().find(BgPaySlip.class, id);
          BgPaySlipLineType type = BgPayrollRealPath.lineType(AID);
          BgPaySlipService service = Beans.get(BgPaySlipService.class);
          BgPaySlipLine line = service.lineFrom(type, new BigDecimal(amount));
          service.addLine(slip, line);
        });
    return id;
  }

  /**
   * „Коригирай“ на потвърден фиш, без промяна в числата, и копието — „Преизчисли“ → „Потвърди“.
   * Нетна разлика няма, тоест текущ фиш не трябва.
   */
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
        status(copy),
        "копието е потвърдено: " + recalc.getData() + " / " + confirm.getData());
    return copy;
  }

  // ═══ четенето ═══════════════════════════════════════════════════════════

  private static BgPaySlip slip(Long id) {
    return BgPayrollRealPath.fresh(em -> em.find(BgPaySlip.class, id));
  }

  private static String status(Long id) {
    return slip(id).getStatusSelect();
  }

  private static BigDecimal amountOf(Long slipId, String code) {
    return BgPayrollRealPath.amountOf(slip(slipId), code);
  }

  /** Замразените редове на фиша — четени през отделен EntityManager. */
  private static List<String[]> usage(Long slipId) {
    return BgPayrollRealPath.fresh(
        em -> {
          List<String[]> out = new ArrayList<>();
          for (BgPaySlipCapUsage row :
              em.createQuery(
                      "SELECT u FROM BgPaySlipCapUsage u WHERE u.paySlip.id = :s",
                      BgPaySlipCapUsage.class)
                  .setParameter("s", slipId)
                  .getResultList()) {
            out.add(
                new String[] {
                  row.getBgSocialExpenseCap().getCode(),
                  String.valueOf(row.getBgUsedBefore()),
                  String.valueOf(row.getBgExempt())
                });
          }
          return out;
        });
  }

  /** ТОЧНО един ред за ONE_OFF_AID — използвано преди и освободено. */
  private static void pinUsage(Long slipId, String usedBefore, String exempt, String why) {
    List<String[]> rows = usage(slipId);
    StringBuilder seen = new StringBuilder();
    for (String[] r : rows) {
      seen.append(String.join(" / ", r)).append("; ");
    }
    assertEquals(1, rows.size(), why + " — един ред на таван, намерени: " + seen);
    assertEquals(CAP, rows.get(0)[0], why);
    pin(usedBefore, rows.get(0)[1], why + " — използвано преди фиша");
    pin(exempt, rows.get(0)[2], why + " — освободено с фиша");
  }

  private static void pin(String expected, Object actual, String what) {
    BgPayrollRealPath.pin(expected, actual, what);
  }
}
