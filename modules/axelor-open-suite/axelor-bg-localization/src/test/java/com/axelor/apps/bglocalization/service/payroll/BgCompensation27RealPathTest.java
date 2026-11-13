package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.bglocalization.service.d1.BgD1DeclarationKind;
import com.axelor.apps.bglocalization.service.d1.BgD1GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD6Blocks;
import com.axelor.apps.bglocalization.service.d1.BgD6GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD6Point;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ОБЕЗЩЕТЕНИЕТО ПО ЧЛ. 222 АЛ. 1 КТ ПО РЕАЛНИЯ ПЪТ ({@link BgPayrollRealPath}): документ →
 * „Преизчисли“ → „Потвърди“ → Д1 за месеца на прекратяването → Д1 за следващия месец → Д6 за двата.
 *
 * <p>Прекратяване 23.10.2026, обезщетение 1077,00 (едно месечно бруто), изплатено с октомврийския
 * фиш на 31.10.2026. Периодът е 24.10–23.11; частите са по работните дни на целия период, по
 * календара на сийда. Номерът на документа носи измисленото лице P0041.
 *
 * <p>⚠️ 2026 г., а не образецът от 2025 г.: каталогът носи ставките на вид 27 от 01.01.2026 г., а
 * сийдът — календара за 2026 г. Сметката на самия образец е в {@code BgCompensation27FileTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgCompensation27RealPathTest extends JpaSupport {

  private static final String CLIENT_ADMIN = "client.admin";
  private static final LocalDate OCT = LocalDate.of(2026, 10, 1);
  private static final LocalDate NOV = LocalDate.of(2026, 11, 1);
  private static final LocalDate TERMINATED = LocalDate.of(2026, 10, 23);
  private static final LocalDate PAID = LocalDate.of(2026, 10, 31);
  private static final BigDecimal AMOUNT = new BigDecimal("1077.00");

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  @DisplayName(
      "Д1 октомври: ред 01 + ред 27 (24–31.10); Д1 ноември: ред 27 (01–23.11) от октомврийския"
          + " фиш; Д6 ноември: вид 6 с датата на изплащане — сверката брои носителя")
  void theCompensationSplitsAcrossTwoMonths() throws Exception {
    Fixture f = scene("Ca");
    Long oct = month(f, OCT);
    Long nov = month(f, NOV);
    Long docId = compensation(f, "P0041-Ca");
    Long slipId = BgPayrollRealPath.slip(f, oct, "0041");

    BgPayrollRealPath.Result octResult = BgPayrollRealPath.run(f, slipId, oct);

    // ═══ ФИШЪТ: вноски по вид 27 върху ЦЯЛАТА сума, белязани с документа ═══
    List<BgPaySlipLine> tagged = taggedContributions(slipId, docId);
    assertEquals(4, tagged.size(), "само Пенсии и УПФ: " + octResult.trace);
    assertEquals(
        0,
        AMOUNT.compareTo(tagged.get(0).getBgQuantity()),
        "базата на вид 27 е цялото обезщетение: " + octResult.trace);

    // ═══ ЧАСТИТЕ — по работните дни на целия период ═══
    int octDays =
        BgPayrollRealPath.workingDays(LocalDate.of(2026, 10, 24), OCT.plusMonths(1).minusDays(1));
    int novDays = BgPayrollRealPath.workingDays(NOV, LocalDate.of(2026, 11, 23));
    BigDecimal daily =
        AMOUNT.divide(BigDecimal.valueOf(octDays + novDays), 2, RoundingMode.HALF_UP);
    BigDecimal octPart = daily.multiply(BigDecimal.valueOf(octDays)).setScale(2);
    BigDecimal novPart = AMOUNT.subtract(octPart);

    // ═══ Д1 ОКТОМВРИ — два реда ═══
    List<BgD1Record> octRecords = recordsOf(octResult.declarationId);
    assertEquals(2, octRecords.size(), "ред 01 и ред 27: " + octResult.trace);
    BgD1Record oct27 = byType(octRecords, "27");
    assertNotNull(oct27, "ред 27: " + octResult.trace);
    assertEquals("24", oct27.getT14Day1());
    assertEquals("31", oct27.getT15Day1());
    assertEquals(String.valueOf(octDays), oct27.getT161WorkedDays());
    pin(octPart, oct27.getT21InsuranceIncome(), "т. 21 на октомврийската част");
    assertEquals(0, oct27.getT29GrossWage().signum(), "т. 29 — обезщетението не е заплата");
    BgD1Record oct01 = byType(octRecords, "01");
    pin(
        regularPensionBase(slipId),
        oct01.getT21InsuranceIncome(),
        "т. 21 на ред 01 = базата на вноските по вид 01 — без обезщетението");

    // ═══ Д1 НОЕМВРИ — ред 27 от ОКТОМВРИЙСКИЯ фиш ═══
    Long novD1 = BgPayrollRealPath.declaration(f, nov, BgD1DeclarationKind.REGULAR, null);
    Beans.get(BgD1GenerationService.class).generate(novD1);
    List<BgD1Record> novRecords = recordsOf(novD1);
    assertEquals(1, novRecords.size(), "само ред 27: " + BgPayrollRealPath.validationReport(novD1));
    BgD1Record nov27 = novRecords.get(0);
    assertEquals("27", nov27.getT12InsuredTypeCode());
    assertEquals(slipId, nov27.getPaySlip().getId(), "записът сочи октомврийския фиш");
    assertEquals("01", nov27.getT14Day1());
    assertEquals("23", nov27.getT15Day1());
    pin(novPart, nov27.getT21InsuranceIncome(), "т. 21 на ноемврийската част — остатъкът");

    // ═══ Д6 НОЕМВРИ — сверката брои носителя; блок вид 6 с датата на изплащане ═══
    Long novD6 = d6Declaration(f, nov, null, null);
    Beans.get(BgD6GenerationService.class).generate(novD6);
    BgD6Declaration generated = fresh(em -> em.find(BgD6Declaration.class, novD6));
    assertNotNull(generated.getT10Doo(), "т. 10 — ноемврийската част");
    assertEquals(0, generated.getT16Tax().signum(), "данъкът е удържан през октомври");
    List<BgD6Blocks.Block> blocks = blocksOf(novD6);
    assertEquals(1, blocks.size(), "само блокът на обезщетението");
    assertEquals(BgD6Point.PAYMENT_KIND_LATER_MONTH, blocks.get(0).getKind());
    assertEquals(NOV, blocks.get(0).getMonth().withDayOfMonth(1), "т. 9 — месецът на частта");
    assertEquals(PAID, blocks.get(0).getDate(), "т. 18 — последният ден на месеца на изплащане");

    // ═══ Д6 ОКТОМВРИ БЕЗ ВИД ПЛАЩАНЕ — заплати има, значи 5/6 се пита; отказът не оставя суми ═══
    Long octWithoutKind = d6Declaration(f, oct, null, null);
    IllegalStateException noKind =
        assertThrows(
            IllegalStateException.class,
            () -> Beans.get(BgD6GenerationService.class).generate(octWithoutKind));
    assertTrue(noKind.getMessage().contains("т. 8.1"), noKind.getMessage());
    BgD6Declaration refused = fresh(em -> em.find(BgD6Declaration.class, octWithoutKind));
    assertEquals("draft", refused.getStatusSelect(), "отказаната Д6 не е генерирана");
    assertEquals(0, refused.getT10Doo().signum(), "сумите на емитера са върнати с транзакцията");

    // ═══ Д6 ОКТОМВРИ — заплатите (5), обезщетението (6), данъкът (8) ═══
    Long octD6 = d6Declaration(f, oct, BgD6Point.PAYMENT_KIND_SAME_MONTH, PAID);
    Beans.get(BgD6GenerationService.class).generate(octD6);
    BgD6Declaration octGenerated = fresh(em -> em.find(BgD6Declaration.class, octD6));
    List<BgD6Blocks.Block> octBlocks = blocksOf(octD6);
    assertEquals(3, octBlocks.size(), "смесен месец — три блока");
    assertEquals(BgD6Point.PAYMENT_KIND_SAME_MONTH, octBlocks.get(0).getKind());
    assertEquals(BgD6Point.PAYMENT_KIND_LATER_MONTH, octBlocks.get(1).getKind());
    assertEquals(BgD6Point.PAYMENT_KIND_TAX, octBlocks.get(2).getKind());
    pin(
        octGenerated.getT16Tax(),
        octBlocks.get(2).getTax(),
        "данъкът на заплатата и на обезщетението — един блок, удържан през октомври");
  }

  @Test
  @DisplayName(
      "⚠️ ОБЩ ТАВАН по истинския път (КСО чл. 6 ал. 11): вид 01 близо до тавана → ред 27 за"
          + " октомври само върху остатъка; ноември — без промяна; Д6 за октомври се връзва")
  void theCeilingIsSharedOnTheRealPath() throws Exception {
    Fixture f = scene("Cc");
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(EmploymentContract.class, f.contractId)
                .setBgBaseSalary(new BigDecimal("1600.00")));
    Long oct = month(f, OCT);
    Long nov = month(f, NOV);
    Long docId = compensation(f, "P0041-Cc");
    Long slipId = BgPayrollRealPath.slip(f, oct, "0043");

    BgPayrollRealPath.Result octResult = BgPayrollRealPath.run(f, slipId, oct);

    int octDays =
        BgPayrollRealPath.workingDays(LocalDate.of(2026, 10, 24), OCT.plusMonths(1).minusDays(1));
    int novDays = BgPayrollRealPath.workingDays(NOV, LocalDate.of(2026, 11, 23));
    BigDecimal daily =
        AMOUNT.divide(BigDecimal.valueOf(octDays + novDays), 2, RoundingMode.HALF_UP);
    BigDecimal octPart = daily.multiply(BigDecimal.valueOf(octDays)).setScale(2);
    BigDecimal novPart = AMOUNT.subtract(octPart);
    BigDecimal ceiling = ceilingOn(OCT);
    BigDecimal base01 = regularPensionBase(slipId);
    BigDecimal room = ceiling.subtract(base01);
    assertTrue(
        room.signum() > 0 && room.compareTo(octPart) < 0,
        "свидетелят иска вид 01 БЛИЗО до тавана: база "
            + base01
            + ", таван "
            + ceiling
            + "; перата: "
            + linesOf(slipId)
            + "; "
            + octResult.trace);

    // ═══ ФИШЪТ — вид 27 върху остатъка до тавана + ноемврийската част ═══
    List<BgPaySlipLine> tagged = taggedContributions(slipId, docId);
    assertEquals(4, tagged.size(), octResult.trace);
    for (BgPaySlipLine line : tagged) {
      pin(room.add(novPart), line.getBgQuantity(), "базата на вид 27 под общия таван");
    }

    // ═══ Д1 ОКТОМВРИ — т. 21 на двата реда = таванът ═══
    List<BgD1Record> octRecords = recordsOf(octResult.declarationId);
    BgD1Record oct27 = byType(octRecords, "27");
    BgD1Record oct01 = byType(octRecords, "01");
    assertNotNull(oct27, octResult.trace);
    pin(base01, oct01.getT21InsuranceIncome(), "т. 21 на ред 01 — вид 01 не се реже");
    pin(room, oct27.getT21InsuranceIncome(), "т. 21 на ред 27 — остатъкът до тавана");
    pin(octPart, oct27.getT31TaxableIncome(), "т. 31 на ред 27 — цялата част");
    pin(ceiling, base01.add(oct27.getT21InsuranceIncome()), "т. 21 на двата реда = таванът");
    String report = BgPayrollRealPath.validationReport(octResult.declarationId);
    assertTrue(
        report == null || !report.contains("D1_T27_CEILING_STALE"),
        "фишът и Д1 са на една база: " + report);

    // ═══ Д1 НОЕМВРИ — само вид 27, без промяна ═══
    Long novD1 = BgPayrollRealPath.declaration(f, nov, BgD1DeclarationKind.REGULAR, null);
    Beans.get(BgD1GenerationService.class).generate(novD1);
    List<BgD1Record> novRecords = recordsOf(novD1);
    assertEquals(1, novRecords.size(), BgPayrollRealPath.validationReport(novD1));
    pin(novPart, novRecords.get(0).getT21InsuranceIncome(), "ноември — цялата част");

    // ═══ Д6 ОКТОМВРИ — блокът с вид 6 = т. 21 × ставките на реда 27 ═══
    Long octD6 = d6Declaration(f, oct, BgD6Point.PAYMENT_KIND_SAME_MONTH, PAID);
    Beans.get(BgD6GenerationService.class).generate(octD6);
    List<BgD6Blocks.Block> blocks = blocksOf(octD6);
    BgD6Blocks.Block six = blocks.get(1);
    assertEquals(BgD6Point.PAYMENT_KIND_LATER_MONTH, six.getKind());
    BigDecimal doo = BigDecimal.ZERO;
    BigDecimal upf = BigDecimal.ZERO;
    List<Object[]> rates = ratesByFund(slipId, docId);
    assertEquals(4, rates.size(), "Пенсии и УПФ, по платец: " + linesOf(slipId));
    for (Object[] r : rates) {
      BigDecimal part =
          room.multiply((BigDecimal) r[1]).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
      if ("UPF".equals(r[0])) {
        upf = upf.add(part);
      } else {
        doo = doo.add(part);
      }
    }
    pin(doo, six.sum(BgD6Point.T10_DOO), "Д6 блок 6, ДОО = Д1 т. 21 × ставките");
    pin(upf, six.sum(BgD6Point.T12_UPF), "Д6 блок 6, УПФ");
  }

  @Test
  @DisplayName(
      "Д1 за ноември вече подадена → находка в октомври и ДОПЪЛНИТЕЛНА Д1 за ноември: нов ред 27,"
          + " без код корекция; редовната за ноември НЕ се пренаписва")
  void aFiledMonthGetsASupplementaryDeclaration() throws Exception {
    Fixture f = scene("Cs");
    Long oct = month(f, OCT);
    Long nov = month(f, NOV);

    // Ноември е подаден, преди обезщетението да е изплатено.
    Long novRegular = BgPayrollRealPath.declaration(f, nov, BgD1DeclarationKind.REGULAR, null);
    Beans.get(BgD1GenerationService.class).generate(novRegular);
    BgPayrollRealPath.accepted(f, nov);

    compensation(f, "P0041-Cs");
    Long slipId = BgPayrollRealPath.slip(f, oct, "0042");
    BgPayrollRealPath.Result octResult = BgPayrollRealPath.run(f, slipId, oct);
    String report = BgPayrollRealPath.validationReport(octResult.declarationId);
    assertTrue(
        report != null && report.contains("D1_T27_PART_NEEDS_SUPPLEMENTARY"),
        "находката казва какво да се подаде: " + report);

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> Beans.get(BgD1GenerationService.class).generate(novRegular),
            "подадената редовна не се пренаписва");
    assertNotNull(refused.getMessage());

    Long supplementary =
        BgPayrollRealPath.declaration(f, nov, BgD1DeclarationKind.SUPPLEMENTARY, null);
    Beans.get(BgD1GenerationService.class).generate(supplementary);
    List<BgD1Record> records = recordsOf(supplementary);
    assertEquals(1, records.size(), BgPayrollRealPath.validationReport(supplementary));
    assertEquals("27", records.get(0).getT12InsuredTypeCode());
    assertEquals(slipId, records.get(0).getPaySlip().getId());
    assertNull(records.get(0).getT1CorrectionCode(), "⚠️ нов ред, НЕ корекция");

    // Генерирана наново, допълнителната носи същия ред веднъж — себе си не брои за подадена.
    Beans.get(BgD1GenerationService.class).generate(supplementary);
    assertEquals(1, recordsOf(supplementary).size());
  }

  // ═══ строители ═══

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

  /**
   * Потвърдено обезщетение по чл. 222 ал. 1 КТ за октомврийския фиш, изплатено на 31.10.2026.
   *
   * <p>⚠️ Записва се направо в базата: потвърждаването иска право и прекратен договор — друга
   * задача, със свои свидетели. Тук се проверява пътят ОТ потвърдения документ нататък.
   */
  private static Long compensation(Fixture f, String reference) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgCompensationType type =
              JPA.em()
                  .createQuery(
                      "SELECT t FROM BgCompensationType t WHERE t.code = 'comp_kt_222_1'",
                      BgCompensationType.class)
                  .getSingleResult();
          BgCompensation doc = new BgCompensation();
          doc.setReference(reference);
          doc.setCompany(JPA.em().find(Company.class, f.companyId));
          doc.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
          doc.setBgCompensationType(type);
          doc.setBgEventDate(TERMINATED);
          doc.setBgBaseKind(type.getBaseKind());
          doc.setBgQuantity(BigDecimal.ONE);
          doc.setBgComputedAmount(AMOUNT);
          doc.setBgFinalAmount(AMOUNT);
          doc.setBgIsTaxable(type.getIsTaxable());
          doc.setBgSocialContributionsDue(type.getSocialContributionsDue());
          doc.setBgPayoutPeriod(LocalDate.of(2026, 10, 15));
          doc.setBgPaidDate(PAID);
          doc.setStatusSelect(BgCompensationResolver.CONFIRMED);
          JPA.em().persist(doc);
          id[0] = doc.getId();
        });
    return id[0];
  }

  private static Long d6Declaration(Fixture f, Long periodId, String kind, LocalDate paid) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgD6Declaration d = new BgD6Declaration();
          d.setCompany(JPA.em().find(Company.class, f.companyId));
          d.setBgPeriod(JPA.em().find(Period.class, periodId));
          d.setT81PaymentKind(kind);
          d.setT18PaymentDate(paid);
          JPA.em().persist(d);
          id[0] = d.getId();
        });
    return id[0];
  }

  /**
   * Блоковете на Д6 — както ги вижда файлът при подаването: в сесия, през същия {@code ledgerFor}.
   * Откъснатата от {@code fresh} декларация не става — периодът ѝ е мързелив.
   */
  private static List<BgD6Blocks.Block> blocksOf(Long d6Id) {
    List<List<BgD6Blocks.Block>> out = new ArrayList<>();
    JPA.runInTransaction(
        () -> {
          BgD6Declaration d = JPA.em().find(BgD6Declaration.class, d6Id);
          JPA.em().refresh(d);
          out.add(BgD6Blocks.of(d, Beans.get(BgD6GenerationService.class).ledgerFor(d)));
        });
    return out.get(0);
  }

  private static List<BgPaySlipLine> taggedContributions(Long slipId, Long docId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT l FROM BgPaySlipLine l WHERE l.paySlip.id = :slip"
                        + " AND l.bgCompensation.id = :doc"
                        + " AND l.bgLineNumber BETWEEN 500 AND 599 ORDER BY l.bgLineNumber",
                    BgPaySlipLine.class)
                .setParameter("slip", slipId)
                .setParameter("doc", docId)
                .getResultList());
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

  /** Базата на вноската за Пенсии по вид 01 — перото 501 БЕЗ препратка към обезщетение. */
  /** „номер:сума/база“ за всяко перо на фиша — за съобщенията на свидетелите. */
  private static String linesOf(Long slipId) {
    List<Object[]> rows =
        fresh(
            em ->
                em.createQuery(
                        "SELECT l.bgLineNumber, l.bgAmount, l.bgQuantity, c.id"
                            + " FROM BgPaySlipLine l LEFT JOIN l.bgCompensation c"
                            + " WHERE l.paySlip.id = :slip ORDER BY l.bgLineNumber",
                        Object[].class)
                    .setParameter("slip", slipId)
                    .getResultList());
    StringBuilder out = new StringBuilder();
    for (Object[] r : rows) {
      out.append(r[0]).append(':').append(r[1]).append('/').append(r[2]);
      out.append(r[3] == null ? "" : "(вид 27)").append(' ');
    }
    return out.toString();
  }

  /** „код на фонда, ставка“ за вноските по вид 27 — фондът от перото, иначе от типа му. */
  private static List<Object[]> ratesByFund(Long slipId, Long docId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT COALESCE(f.code, tf.code), l.bgRate FROM BgPaySlipLine l"
                        + " LEFT JOIN l.bgContributionFund f LEFT JOIN l.bgPaySlipLineType t"
                        + " LEFT JOIN t.bgContributionFund tf"
                        + " WHERE l.paySlip.id = :slip AND l.bgCompensation.id = :doc"
                        + " AND l.bgLineNumber BETWEEN 500 AND 599",
                    Object[].class)
                .setParameter("slip", slipId)
                .setParameter("doc", docId)
                .getResultList());
  }

  private static BigDecimal ceilingOn(LocalDate day) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT p.paramValue FROM BgPayrollParameter p WHERE p.code = :code"
                        + " AND p.validFrom <= :date AND (p.validTo IS NULL OR p.validTo >= :date)",
                    BigDecimal.class)
                .setParameter("code", "MAX_INSURABLE_INCOME")
                .setParameter("date", day)
                .getSingleResult());
  }

  private static BigDecimal regularPensionBase(Long slipId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT l.bgQuantity FROM BgPaySlipLine l WHERE l.paySlip.id = :slip"
                        + " AND l.bgCompensation IS NULL AND l.bgLineNumber = 501",
                    BigDecimal.class)
                .setParameter("slip", slipId)
                .getSingleResult());
  }

  private static BgD1Record byType(List<BgD1Record> records, String code) {
    for (BgD1Record r : records) {
      if (code.equals(r.getT12InsuredTypeCode())) {
        return r;
      }
    }
    return null;
  }

  private static BgPaySlip slip(Long id) {
    return fresh(em -> em.find(BgPaySlip.class, id));
  }

  private static <T> T fresh(java.util.function.Function<javax.persistence.EntityManager, T> read) {
    return BgPayrollRealPath.fresh(read);
  }

  private static void pin(BigDecimal expected, BigDecimal actual, String what) {
    assertNotNull(actual, what + " — празно");
    assertEquals(0, expected.compareTo(actual), what + ": " + actual + " срещу " + expected);
  }
}
