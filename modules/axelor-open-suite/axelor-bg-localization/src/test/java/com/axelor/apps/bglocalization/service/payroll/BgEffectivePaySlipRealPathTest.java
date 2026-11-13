package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.d1.BgD1DeclarationKind;
import com.axelor.apps.bglocalization.service.d1.BgD1GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD6Aggregator;
import com.axelor.apps.bglocalization.service.d1.BgD6GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD6Point;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
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
import java.util.Map;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ЕДИН ДЕЙСТВАЩ ФИШ ЗА ДОГОВОР И ПЕРИОД — по реалния път ({@link BgPayrollRealPath}): „Преизчисли“
 * → „Потвърди“ → Д1 → Д6 → „Коригирай“ → Д6 → Д1 наново → Д6, с {@code client.admin}.
 *
 * <p><b>Рискът.</b> Фиш и коригиращото му копие, и двата потвърдени: ако Д1 носи оригинала, а Д6 т.
 * 10 сбира оригинала И корекцията, двете декларации за един месец твърдят различни числа. Тук: един
 * фиш, една корекция с еднократен бонус 140,00.
 *
 * <p><b>Случаят е (а): Д1 още НЕ е подадена.</b> Периодът е затворен (иначе корекция няма), а
 * подаване за него няма — точно прозорецът „затворен, още неподаден“.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgEffectivePaySlipRealPathTest extends JpaSupport {

  private static final String CLIENT_ADMIN = "client.admin";
  private static final String BONUS = "pl_ednokraten_bonus";
  private static final LocalDate SEP = LocalDate.of(2026, 9, 1);
  private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ ОРИГИНАЛ + КОРЕКЦИЯ В СЪЩИЯ МЕСЕЦ → Д1 И Д6 СЪВПАДАТ ══════════════

  @Test
  @DisplayName(
      "оригинал + корекция · Д6 отказва срещу старата Д1, Д1 наново носи копието веднъж, Д6 = Д1")
  void originalPlusCorrectionGivesOneEffectiveSlipInBothDeclarations() throws Exception {
    Fixture f = scene("Ea");
    Long sep = month(f, SEP);
    Long originalId = BgPayrollRealPath.slip(f, sep, "0014");
    BgPayrollRealPath.Result regular = BgPayrollRealPath.run(f, originalId, sep);
    Long d1 = regular.declarationId;

    // Д6 срещу Д1 преди корекцията — минава: и двете стъпват на оригинала.
    Long d6 = d6Declaration(f, sep);
    Beans.get(BgD6GenerationService.class).generate(d6);
    BgPaySlip original = slip(originalId);

    // „Коригирай“ — периодът се затваря, копието получава еднократен бонус 140,00.
    Long copyId = correctWithBonus(f, sep, originalId, "140.00");
    BgPaySlip copy = slip(copyId);
    assertNotEquals(
        0,
        original.getBgInsuranceIncome().compareTo(copy.getBgInsuranceIncome()),
        "корекцията мени осигурителния доход — иначе свидетелят не различава двата фиша");

    // ⚠️ Д6 ОТКАЗВА: Д1 е генерирана преди корекцията. Текстът е дословно поисканият.
    IllegalStateException stale =
        assertThrows(
            IllegalStateException.class,
            () -> Beans.get(BgD6GenerationService.class).generate(d6),
            "⚠️ Д6 по действащите фишове срещу Д1 по оригинала — разминаване, което не бива"
                + " да минава мълчаливо.");
    String periodName = fresh(em -> em.find(Period.class, sep)).getName();
    assertTrue(
        stale
            .getMessage()
            .startsWith(
                "Д1 за „"
                    + periodName
                    + "“ е генерирана преди корекцията на "
                    + original.getReference()
                    + "; генерирайте Д1 наново."),
        stale.getMessage());
    assertTrue(
        stale.getMessage().contains("т. 10: +"), "разликата по точки: " + stale.getMessage());

    // ⚠️ ДОБАВКА 1: само генерираната (неподадена) редовна Д1 се генерира НАНОВО след корекцията.
    Beans.get(BgD1GenerationService.class).generate(d1);
    List<BgD1Record> records = recordsOf(d1);
    assertEquals(
        1, records.size(), "лицето е в Д1 ВЕДНЪЖ, не по веднъж на фиш: " + describe(records));
    assertEquals(
        copyId,
        records.get(0).getPaySlip().getId(),
        "⚠️ записът сочи КОПИЕТО — действащия фиш, не оригинала");
    assertNull(
        records.get(0).getT1CorrectionCode(),
        "⚠️ и е ОБИКНОВЕН запис, без „К“: НАП не е виждала оригинала (чл. 9 ал. 4)");
    pin(
        copy.getBgInsuranceIncome(),
        records.get(0).getT21InsuranceIncome(),
        "т. 21 — осигурителният доход на копието");

    // ⚠️ Д6 = Д1: т. 10 е по копието, а НЕ по оригинала + копието.
    Beans.get(BgD6GenerationService.class).generate(d6);
    BgD6Declaration generated = fresh(em -> em.find(BgD6Declaration.class, d6));
    Map<String, BigDecimal> byCopy = pointsOf(copyId);
    Map<String, BigDecimal> byBoth = pointsOf(originalId, copyId);
    pin(byCopy.get(BgD6Point.T10_DOO), generated.getT10Doo(), "Д6 т. 10 — по действащия фиш");
    pin(byCopy.get(BgD6Point.T14_HEALTH), generated.getT14Health(), "Д6 т. 14");
    pin(byCopy.get(BgD6Point.T16_TAX), generated.getT16Tax(), "Д6 т. 16");
    assertNotEquals(
        0,
        byBoth.get(BgD6Point.T10_DOO).compareTo(generated.getT10Doo()),
        "⚠️ изборът „всички потвърдени за периода“ би дал сбора на двата фиша");

    // ⚠️ КОРИГИРАЩА Д1 ПРЕДИ НАП ДА ДЪРЖИ РЕДОВНАТА — шумен отказ.
    Long correcting = BgPayrollRealPath.declaration(f, sep, BgD1DeclarationKind.CORRECTING, d1);
    IllegalStateException early =
        assertThrows(
            IllegalStateException.class,
            () -> Beans.get(BgD1GenerationService.class).generate(correcting),
            "⚠️ коригираща сега би подала „К“ на запис, който НАП не познава — и копието два пъти");
    assertTrue(early.getMessage().contains("НАП още не държи РЕДОВНАТА"), early.getMessage());
    assertEquals(0, recordsOf(correcting).size(), "отказът е преди записите");
  }

  // ═══ ВТОРИ РЕДОВЕН ФИШ → ОТКАЗ ПРИ ЗАПИС ═════════════════════════════

  @Test
  @DisplayName("втори редовен фиш до ПОТВЪРДЕН → отказ при запис, назовава номер и статус")
  void aSecondRegularSlipNextToAConfirmedOneIsRefusedOnSave() throws Exception {
    Fixture f = scene("Eb");
    Long oct = month(f, OCT);
    Long firstId = BgPayrollRealPath.slip(f, oct, "0011");
    BgPayrollRealPath.run(f, firstId, oct);
    String first = slip(firstId).getReference();

    PersistenceException refused =
        assertThrows(PersistenceException.class, () -> saveRegular(f, oct, "0021"));
    assertTrue(refused.getMessage().contains(first), "назовава съществуващия: " + refused);
    assertTrue(refused.getMessage().contains("потвърден"), "и статуса му: " + refused);
    assertEquals(1, regularCount(f, oct), "вторият НЕ е записан");
  }

  @Test
  @DisplayName("втори редовен до ЧЕРНОВА → отказ при запис")
  void aDraftCounts() throws Exception {
    Fixture f = scene("Ec");
    Long oct = month(f, OCT);
    saveRegular(f, oct, "0030");

    PersistenceException refused =
        assertThrows(PersistenceException.class, () -> saveRegular(f, oct, "0031"));
    assertTrue(refused.getMessage().contains("чернова"), "черновата се брои: " + refused);
    assertEquals(1, regularCount(f, oct), "вторият НЕ е записан");
  }

  @Test
  @DisplayName("втори редовен до ОТКАЗАН → минава")
  void aCancelledDoesNotCount() throws Exception {
    Fixture f = scene("Ee");
    Long oct = month(f, OCT);
    Long firstId = saveRegular(f, oct, "0040");
    JPA.runInTransaction(
        () -> JPA.em().find(BgPaySlip.class, firstId).setStatusSelect(BgPaySlipResolver.CANCELLED));

    Long second = saveRegular(f, oct, "0041");
    assertNotNull(second, "⚠️ отказаният не се брои — на негово място се прави нов");
  }

  @Test
  @DisplayName("заварен дубликат · по-старият се потвърждава, по-новият — не")
  void aLegacyDuplicateIsRefusedOnConfirmOnlyForTheNewerSlip() throws Exception {
    Fixture f = scene("Ed");
    Long oct = month(f, OCT);
    Long olderId = BgPayrollRealPath.slip(f, oct, "0011");
    // Заварен дубликат — роден преди пазача, пряко в базата.
    Long newerId = legacyDuplicate(f, oct, "0021");
    String older = slip(olderId).getReference();

    BgPayrollRealPath.run(f, olderId, oct);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        slip(olderId).getStatusSelect(),
        "⚠️ по-старият остава свободен — дубликатът е по-новият");

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът откача контекста.
    ActionResponse refused = BgPayrollRealPath.press("confirm", newerId);
    assertEquals(
        BgPaySlipResolver.DRAFT,
        slip(newerId).getStatusSelect(),
        "по-новият НЕ е потвърден: " + refused.getData());
    assertTrue(
        String.valueOf(refused.getData()).contains(older),
        "отказът назовава по-стария: " + refused.getData());
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /**
   * ⚠️ ЛИЦЕТО И ДРУЖЕСТВОТО СА БЕЗ Partner — образецът на {@code BgSocialCapUsageCountTest}.
   * Отказите тук връщат транзакция, а rollback-ът чисти контекста: следващото четене на договора
   * зарежда служителя наново, а с него и Partner, чиито @Formula са за PostgreSQL и HSQLDB не ги
   * изпълнява. Д1 и Д6 четат и двете null-безопасно; мерилото тук е изборът на фиш.
   */
  private static Fixture scene(String letter) throws Exception {
    BgPayrollRealPath.seedData();
    Fixture f = BgPayrollRealPath.fixture(letter, 12);
    JPA.runInTransaction(
        () -> {
          JPA.em().find(Employee.class, f.employeeId).setContactPartner(null);
          JPA.em().find(Company.class, f.companyId).setPartner(null);
        });
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, f.companyId), CLIENT_ADMIN);
    return f;
  }

  private static Long month(Fixture f, LocalDate from) {
    LocalDate to = from.plusMonths(1).minusDays(1);
    Long id = BgPayrollRealPath.period(f, from, to);
    BgPayrollRealPath.timesheet(f, from, to, null, null);
    return id;
  }

  /** „Коригирай“ → еднократен бонус в копието → „Преизчисли“ → „Потвърди“. */
  private static Long correctWithBonus(Fixture f, Long periodId, Long originalId, String bonus) {
    BgPayrollRealPath.openToday(f);
    BgPayrollRealPath.periodStatus(periodId, PeriodRepository.STATUS_CLOSED);
    ActionResponse correct = BgPayrollRealPath.press("correct", originalId);
    Long copy =
        fresh(
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
    JPA.runInTransaction(
        () -> {
          BgPaySlip slip = JPA.em().find(BgPaySlip.class, copy);
          BgPaySlipService service = Beans.get(BgPaySlipService.class);
          service.addLine(
              slip, service.lineFrom(BgPayrollRealPath.lineType(BONUS), new BigDecimal(bonus)));
        });
    ActionResponse recalc = BgPayrollRealPath.press("recalculate", copy);
    ActionResponse confirm = BgPayrollRealPath.press("confirm", copy);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        slip(copy).getStatusSelect(),
        "копието е потвърдено: " + recalc.getData() + " / " + confirm.getData());
    return copy;
  }

  /** Ид-тата на редовете, записани със SQL — далеч над поредиците на теста. */
  private static long sqlIds = 8_500_000L;

  /**
   * ЗАВАРЕН ДУБЛИКАТ — редовна чернова, записана пряко със SQL, покрай repository-то: както се
   * раждат дубликати отпреди пазача.
   */
  private static Long legacyDuplicate(Fixture f, Long periodId, String suffix) {
    long id = ++sqlIds;
    JPA.runInTransaction(
        () ->
            JPA.em()
                .createNativeQuery(
                    "INSERT INTO bglocalization_bg_pay_slip (id, version, employment_contract,"
                        + " period, reference, status_select, bg_is_correction, bg_is_deletion)"
                        + " VALUES (?, 0, ?, ?, ?, ?, FALSE, FALSE)")
                .setParameter(1, id)
                .setParameter(2, f.contractId)
                .setParameter(3, periodId)
                .setParameter(4, "FISH-" + suffix + "-" + f.tag)
                .setParameter(5, BgPaySlipResolver.DRAFT)
                .executeUpdate());
    return id;
  }

  /** Нов РЕДОВЕН фиш през ИСТИНСКОТО repository — пътят на формата и на REST. */
  private static Long saveRegular(Fixture f, Long periodId, String suffix) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgPaySlip slip = new BgPaySlip();
          slip.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
          slip.setPeriod(JPA.em().find(Period.class, periodId));
          slip.setReference("FISH-" + suffix + "-" + f.tag);
          slip.setStatusSelect(BgPaySlipResolver.DRAFT);
          slip.setBgIsCorrection(false);
          slip.setBgIsDeletion(false);
          id[0] = Beans.get(BgPaySlipRepository.class).save(slip).getId();
        });
    return id[0];
  }

  private static long regularCount(Fixture f, Long periodId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT COUNT(s) FROM BgPaySlip s WHERE s.employmentContract.id = :c"
                        + " AND s.period.id = :p AND s.bgIsCorrection = FALSE",
                    Long.class)
                .setParameter("c", f.contractId)
                .setParameter("p", periodId)
                .getSingleResult());
  }

  private static Long d6Declaration(Fixture f, Long periodId) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgD6Declaration d = new BgD6Declaration();
          d.setCompany(JPA.em().find(Company.class, f.companyId));
          d.setBgPeriod(JPA.em().find(Period.class, periodId));
          // т. 8.1 — ръчният избор, без който екранът не генерира (вече го пита и услугата).
          d.setT81PaymentKind(BgD6Point.PAYMENT_KIND_SAME_MONTH);
          JPA.em().persist(d);
          id[0] = d.getId();
        });
    return id[0];
  }

  private static List<BgD1Record> recordsOf(Long declarationId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT r FROM BgD1Record r JOIN FETCH r.paySlip"
                        + " WHERE r.bgD1Declaration.id = :id ORDER BY r.id",
                    BgD1Record.class)
                .setParameter("id", declarationId)
                .getResultList());
  }

  private static String describe(List<BgD1Record> records) {
    StringBuilder out = new StringBuilder();
    for (BgD1Record r : records) {
      out.append(r.getPaySlip().getReference())
          .append(" код=")
          .append(r.getT1CorrectionCode())
          .append("; ");
    }
    return out.toString();
  }

  /**
   * Сумите по точки на Д6 за тези фишове — смятани ВЪТРЕ в EntityManager-а: фондът на перото е
   * мързелива връзка.
   */
  private static Map<String, BigDecimal> pointsOf(Long... ids) {
    return fresh(
        em -> {
          List<BgPaySlip> slips = new ArrayList<>();
          for (Long id : ids) {
            slips.add(em.find(BgPaySlip.class, id));
          }
          return BgD6Aggregator.byPoint(slips);
        });
  }

  private static BgPaySlip slip(Long id) {
    return fresh(em -> em.find(BgPaySlip.class, id));
  }

  private static <T> T fresh(java.util.function.Function<javax.persistence.EntityManager, T> read) {
    return BgPayrollRealPath.fresh(read);
  }

  private static void pin(BigDecimal expected, BigDecimal actual, String what) {
    assertNotNull(expected, what + ": очакваното е празно");
    assertNotNull(actual, what + ": празно");
    assertEquals(0, expected.compareTo(actual), what + ": " + expected + " срещу " + actual);
  }
}
