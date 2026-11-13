package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgArt73Para6Annual;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.art49.BgArt49MainEmployer;
import com.axelor.apps.bglocalization.service.d1.BgD6Aggregator;
import com.axelor.apps.bglocalization.service.d1.BgD6Point;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГОДИШНОТО ИЗРАВНЯВАНЕ ПО ЧЛ. 49 КАТО ПЕРО — двете посоки и трите състояния.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ НА БАЗАТА.</b> Справката и речникът се подават от теста;
 * правилата ({@code BgArt49Settlement}), нетото ({@code BgPaySlipResolver#netTotal}), сверяването
 * ({@code refuseTaxMismatch}) и агрегатът на Д6 ({@code BgD6Aggregator}) се викат ИСТИНСКИ. Тоест
 * числата тук минават през същия код, който ги смята в производството.
 *
 * <p><b>⚠️ И НИТО ЕДНО ЧИСЛО НЕ СЕ ПРЕИЗЧИСЛЯВА.</b> Колона 19 се подава такава, каквато {@code
 * BgArt49Recalculation} я е записала. Този енджин само я носи до фиша — и точно това се проверява.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgArt49SettlementGeneratorTest {

  /** Данъчната година, която януарският фиш от 2027 г. изравнява. */
  private static final int TAX_YEAR = 2026;

  // ═══ ЕНДЖИНЪТ С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static final class Service extends BgArt49SettlementService {

    BgArt73Para6Report stored;
    boolean dictionaryHasTheRow = true;

    @Override
    protected BgArt73Para6Report report(Company company, int taxYear) {
      if (stored == null) {
        return null;
      }
      return company.equals(stored.getCompany())
              && Integer.valueOf(taxYear).equals(stored.getTaxYear())
          ? stored
          : null;
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      if (!dictionaryHasTheRow || !LINE_ART49_SETTLEMENT.equals(code)) {
        return null;
      }
      return type(710, LINE_ART49_SETTLEMENT, "Годишно изравняване по чл. 49 ЗДДФЛ", "DEBIT");
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      // ДОСЛОВНО копие на BgPaySlipService#lineFrom — замразяване чрез копиране.
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
      line.setBgAmount(amount);
      return line;
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static BgPaySlipLineType type(int number, String code, String name, String sign) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setCode(code);
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(false);
    t.setBgIsInsuranceIncome(false);
    t.setBgIsPermanentCharacter(false);
    t.setActive(true);
    return t;
  }

  private static BgPaySlipLine line(int number, String code, String sign, String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgPaySlipLineType(type(number, code, code, sign));
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    return l;
  }

  private static Company company(String name) {
    Company c = new Company();
    c.setName(name);
    return c;
  }

  private static Employee employee(String name) {
    Employee e = new Employee();
    e.setName(name);
    return e;
  }

  private static Period period(LocalDate from) {
    Period p = new Period();
    p.setFromDate(from);
    p.setToDate(from.withDayOfMonth(from.lengthOfMonth()));
    return p;
  }

  /**
   * Януарският фиш на 2027 г.: заплата 1000,00, лични вноски 138,80, месечен данък по чл. 42 86,12.
   *
   * <p>⚠️ Числата са вътрешно свързани: 1000,00 − 138,80 = 861,20 основа, × 10 на сто = 86,12. Така
   * {@code refuseTaxMismatch} може да се пита истински, а не срещу измислена заглавна сума.
   */
  private static BgPaySlip januarySlip(Company company, Employee emp) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setPeriod(period(LocalDate.of(2027, 1, 1)));
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(emp);
    contract.setPayCompany(company);
    contract.setBgIsPrimaryContract(true);
    contract.setStartDate(LocalDate.of(2020, 3, 1));
    slip.setEmploymentContract(contract);
    slip.setBgPaySlipLineList(
        new ArrayList<>(
            Arrays.asList(
                line(100, "pl_osn", "CREDIT", "1000.00"),
                line(500, "pl_vn_pension_lice", "DEBIT", "138.80"),
                line(700, BgIncomeTaxService.LINE_INCOME_TAX, "DEBIT", "86.12"))));
    slip.setBgTaxAmount(new BigDecimal("86.12"));
    return slip;
  }

  /** Справка за (дружество, година) с точно тези редове на преизчисляване. */
  private static BgArt73Para6Report report(
      Company company, int taxYear, BgArt73Para6Annual... rows) {
    BgArt73Para6Report r = new BgArt73Para6Report();
    r.setCompany(company);
    r.setTaxYear(taxYear);
    r.setStatusSelect("GENERATED");
    r.setAnnualList(new ArrayList<>(Arrays.asList(rows)));
    return r;
  }

  /** Ред на преизчисляване — точно колоните, които перото чете. */
  private static BgArt73Para6Annual annual(Employee emp, String annualTax, String settlement) {
    BgArt73Para6Annual a = new BgArt73Para6Annual();
    a.setEmployee(emp);
    a.setAnnualTax(new BigDecimal(annualTax));
    a.setSettlement(new BigDecimal(settlement));
    BigDecimal s = new BigDecimal(settlement);
    a.setToWithhold(s.signum() > 0 ? s : BigDecimal.ZERO.setScale(2));
    a.setToRefund(s.signum() < 0 ? s.negate() : BigDecimal.ZERO.setScale(2));
    return a;
  }

  private static BgPaySlipLine settlementLine(BgPaySlip slip) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType t = l.getBgPaySlipLineType();
      if (t != null && BgPaySlipResolver.LINE_ART49_SETTLEMENT.equals(t.getCode())) {
        return l;
      }
    }
    return null;
  }

  // ═══ GOLDEN (а) — ДОПЛАЩАНЕ ═══════════════════════════════════════════════

  @Test
  @DisplayName(
      "(а) доплащане: перо DEBIT, нетото ПАДА с колона 19, числото реконсилира до стотинка")
  void goldenWithholding() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    BgPaySlip slip = januarySlip(c, emp);
    BigDecimal netBefore = BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList());

    Service service = new Service();
    // Колона 18 = 1240,00; авансово удържано през годината = 1199,00 → колона 19 = 41,00.
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip);

    assertEquals(1, outcomes.size());
    assertEquals(BgPaySlipLineOutcome.Status.COMPUTED, outcomes.get(0).status);

    BgPaySlipLine line = settlementLine(slip);
    assertNotNull(line, "перото трябва да е записано");
    assertEquals(BgPaySlipResolver.DEBIT, line.getBgSign(), "доплащането се УДЪРЖА");
    assertEquals(0, new BigDecimal("41.00").compareTo(line.getBgAmount()));
    assertEquals(710, line.getBgLineNumber().intValue());
    assertEquals("Годишно изравняване по чл. 49 ЗДДФЛ за 2026 (доплащане)", line.getName());

    // ⚠️ РЕКОНСИЛИАЦИЯТА: перото Е колона 19, до стотинка. Не „приблизително“ и не „по сметка“ —
    // числото е ЕДНО и се пренася, а не се преизчислява.
    assertEquals(
        0,
        service.stored.getAnnualList().get(0).getSettlement().abs().compareTo(line.getBgAmount()),
        "перото = |колона 19|");

    BigDecimal netAfter = BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList());
    assertEquals(
        0,
        netBefore.subtract(new BigDecimal("41.00")).compareTo(netAfter),
        "нетото пада точно с колона 19");
  }

  @Test
  @DisplayName(
      "(а) доплащането ВЛИЗА в т. 16 на Д6 — указанието иска „чл. 41а, чл. 42 и чл. 49 ал. 5“")
  void withholdingEntersD6Point16() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    BgPaySlip slip = januarySlip(c, emp);
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));
    service.generate(slip);

    Map<String, BigDecimal> d6 = BgD6Aggregator.byPoint(Collections.singletonList(slip));
    // 86,12 месечен данък по чл. 42 + 41,00 изравняване по чл. 49 ал. 5 = 127,12.
    assertEquals(0, new BigDecimal("127.12").compareTo(d6.get(BgD6Point.T16_TAX)));
  }

  // ═══ GOLDEN (б) — ВЪЗСТАНОВЯВАНЕ ══════════════════════════════════════════

  @Test
  @DisplayName("(б) възстановяване: перо CREDIT, нетото РАСТЕ с колона 19, реконсилира до стотинка")
  void goldenRefund() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Боряна");
    BgPaySlip slip = januarySlip(c, emp);
    BigDecimal netBefore = BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList());

    Service service = new Service();
    // Облекчение за дете по чл. 22в, ползвано САМО годишно по чл. 49 ал. 3: колона 18 пада до
    // 1132,00 при авансово удържани 1199,00 → колона 19 = −67,00.
    service.stored = report(c, TAX_YEAR, annual(emp, "1132.00", "-67.00"));

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip);
    assertEquals(BgPaySlipLineOutcome.Status.COMPUTED, outcomes.get(0).status);

    BgPaySlipLine line = settlementLine(slip);
    assertNotNull(line);
    assertEquals(BgPaySlipResolver.CREDIT, line.getBgSign(), "възстановяването се ДОБАВЯ");
    assertEquals(0, new BigDecimal("67.00").compareTo(line.getBgAmount()), "сумата е АБСОЛЮТНА");
    assertEquals("Годишно изравняване по чл. 49 ЗДДФЛ за 2026 (възстановяване)", line.getName());

    assertEquals(
        0,
        service.stored.getAnnualList().get(0).getSettlement().abs().compareTo(line.getBgAmount()),
        "перото = |колона 19|");

    BigDecimal netAfter = BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList());
    assertEquals(
        0,
        netBefore.add(new BigDecimal("67.00")).compareTo(netAfter),
        "нетото расте точно с колона 19");
  }

  @Test
  @DisplayName(
      "(б) възстановяването ОСТАВА ИЗВЪН т. 16 на Д6 — чл. 49 ал. 7 го прихваща, не го декларира")
  void refundStaysOutOfD6Point16() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Боряна");
    BgPaySlip slip = januarySlip(c, emp);
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1132.00", "-67.00"));
    service.generate(slip);

    Map<String, BigDecimal> d6 = BgD6Aggregator.byPoint(Collections.singletonList(slip));
    // Само месечният данък по чл. 42. Върнатото се прихваща от СЛЕДВАЩИ вноски към бюджета
    // (чл. 49 ал. 7) и не се декларира като отрицателен данък в т. 16.
    assertEquals(0, new BigDecimal("86.12").compareTo(d6.get(BgD6Point.T16_TAX)));
  }

  // ═══ СВЕРЯВАНЕТО НА МЕСЕЧНИЯ ДАНЪК ОСТАВА НЕПОКЪТНАТО ═════════════════════

  @Test
  @DisplayName("⚠️ refuseTaxMismatch МИНАВА и в двете посоки — месечният чл. 42 не е пипнат")
  void monthlyTaxReconciliationSurvives() {
    for (String settlement : new String[] {"41.00", "-67.00"}) {
      Company c = company("Нукс ООД");
      Employee emp = employee("Асен");
      BgPaySlip slip = januarySlip(c, emp);
      Service service = new Service();
      service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", settlement));
      service.generate(slip);

      assertEquals(
          0,
          new BigDecimal("86.12")
              .compareTo(BgPaySlipResolver.taxFromLines(slip.getBgPaySlipLineList())),
          "taxFromLines брои САМО месечния данък при колона 19 = " + settlement);
      assertNull(
          BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
          "пазачът не блокира януарски фиш с изравняване при колона 19 = " + settlement);
    }
  }

  // ═══ ТРИТЕ СЪСТОЯНИЯ ══════════════════════════════════════════════════════

  @Test
  @DisplayName("справка НЯМА → ОТКАЗ, който сочи какво да се направи — не тиха нула")
  void missingReportIsRefusal() {
    Company c = company("Нукс ООД");
    BgPaySlip slip = januarySlip(c, employee("Асен"));
    Service service = new Service();
    service.stored = null;

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip);

    assertEquals(1, outcomes.size());
    assertTrue(outcomes.get(0).isRefusal());
    assertTrue(outcomes.get(0).detail.contains("2026"), outcomes.get(0).detail);
    assertTrue(outcomes.get(0).detail.contains("чл. 73 ал. 6"), outcomes.get(0).detail);
    assertNull(settlementLine(slip), "при отказ перо НЕ се създава");
  }

  @Test
  @DisplayName(
      "справка ИМА, ред за лицето НЯМА → законна нула: не е основен работодател (чл. 49 ал. 1)")
  void reportWithoutRowIsLegalZero() {
    Company c = company("Нукс ООД");
    Employee mine = employee("Асен");
    BgPaySlip slip = januarySlip(c, mine);
    Service service = new Service();
    // Справката е генерирана, но в нея има ред само за ДРУГО лице.
    service.stored = report(c, TAX_YEAR, annual(employee("Боряна"), "1240.00", "41.00"));

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip);

    assertEquals(BgPaySlipLineOutcome.Status.LEGAL_ZERO, outcomes.get(0).status);
    assertFalse(outcomes.get(0).isRefusal(), "⚠️ НЕ е отказ: това е отговорът на чл. 49 ал. 1");
    assertTrue(outcomes.get(0).detail.contains("ОСНОВНОТО"), outcomes.get(0).detail);
    assertNull(settlementLine(slip));
  }

  @Test
  @DisplayName(
      "⚠️ ДВЕТЕ ЗАКОННИ НУЛИ СЕ РАЗЛИЧАВАТ С ТЕКСТ — „не преизчислява“ срещу „няма разлика“")
  void theTwoLegalZerosAreDistinguishable() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");

    Service notMine = new Service();
    notMine.stored = report(c, TAX_YEAR, annual(employee("Боряна"), "1240.00", "41.00"));
    String whenNotMainEmployer = notMine.generate(januarySlip(c, emp)).get(0).detail;

    Service noDelta = new Service();
    noDelta.stored = report(c, TAX_YEAR, annual(emp, "1199.00", "0.00"));
    String whenZeroDelta = noDelta.generate(januarySlip(c, emp)).get(0).detail;

    // И в двата случая ред няма и статусът е един и същ — различава ги само изречението, а то е
    // адресирано до различен читател: първото не иска нищо, второто потвърждава, че е смятано.
    assertTrue(whenNotMainEmployer.contains("НЯМА ред"), whenNotMainEmployer);
    assertTrue(whenZeroDelta.contains("Е преизчислено"), whenZeroDelta);
    assertFalse(whenZeroDelta.contains("НЯМА ред"), whenZeroDelta);
  }

  @Test
  @DisplayName("колона 19 = 0,00 → перо НЕ се създава: 0,00 в 7xx би твърдяло, че е удържано")
  void zeroSettlementWritesNoLine() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    BgPaySlip slip = januarySlip(c, emp);
    BigDecimal netBefore = BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList());
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1199.00", "0.00"));

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip);

    assertEquals(BgPaySlipLineOutcome.Status.LEGAL_ZERO, outcomes.get(0).status);
    assertNull(settlementLine(slip));
    assertEquals(
        0,
        netBefore.compareTo(BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList())),
        "нетото не помръдва");
  }

  // ═══ ПЕРИОДЪТ ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("неянуарски фиш → НИТО перо, НИТО присъда: чл. 49 просто не се отнася до него")
  void nonJanuaryProducesNothing() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    for (int month : new int[] {2, 6, 12}) {
      BgPaySlip slip = januarySlip(c, emp);
      slip.setPeriod(period(LocalDate.of(2027, month, 1)));
      Service service = new Service();
      service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));

      assertTrue(service.generate(slip).isEmpty(), "месец " + month + " не носи присъда");
      assertNull(settlementLine(slip), "месец " + month + " не носи перо");
    }
  }

  @Test
  @DisplayName("⚠️ ДЕКЕМВРИ 2026 не носи изравняването за 2026 — носи го януари 2027")
  void decemberOfTheTaxYearCarriesNothing() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    BgPaySlip december = januarySlip(c, emp);
    december.setPeriod(period(LocalDate.of(2026, 12, 1)));
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));

    assertTrue(service.generate(december).isEmpty());
    assertNull(settlementLine(december));
  }

  // ═══ РЪБОВЕ ═══════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "нает в средата на годината: изравняването е това, което справката е сметнала — и точка")
  void hiredMidYear() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Владимир");
    BgPaySlip slip = januarySlip(c, emp);
    slip.getEmploymentContract().setStartDate(LocalDate.of(2026, 7, 15));
    Service service = new Service();
    // ⚠️ Този енджин НЕ намалява нищо заради непълната година и НЕ бива: пропорцията (ако има
    // такава) вече е в колона 19 — основата е сборът на реалните месеци, а облекченията са
    // годишни по устройство. Второ смятане тук би дало второ число за същата норма.
    service.stored = report(c, TAX_YEAR, annual(emp, "620.00", "-38.00"));

    service.generate(slip);

    BgPaySlipLine line = settlementLine(slip);
    assertNotNull(line);
    assertEquals(BgPaySlipResolver.CREDIT, line.getBgSign());
    assertEquals(0, new BigDecimal("38.00").compareTo(line.getBgAmount()));
  }

  @Test
  @DisplayName(
      "⚠️ ГРАНИЦА: договор, свършващ на 31.12, Е основен работодател — и НЯМА януарски фиш")
  void terminatedOnDecember31HasNoCarrier() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Галина");
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(emp);
    contract.setPayCompany(c);
    contract.setBgIsPrimaryContract(true);
    contract.setStartDate(LocalDate.of(2020, 3, 1));
    contract.setEndDate(LocalDate.of(2026, 12, 31));

    // ⚠️ ПЪРВАТА ПОЛОВИНА НА ДУПКАТА: лицето СЕ ПРЕИЗЧИСЛЯВА. Чл. 49 ал. 1 гледа „към 31 декември“,
    // а договор, свършващ НА 31 декември, действа на тази дата.
    assertTrue(
        BgArt49MainEmployer.recalculates(Collections.singletonList(contract), c, TAX_YEAR),
        "договор с край 31.12 Е основно ТПО към 31.12");

    // ⚠️ ВТОРАТА ПОЛОВИНА: януарски фиш за него НЯМА и този генератор се вика ОТ фиш. Тоест
    // изравняването му остава непреминало през ведомост — не защото правилото го изключва, а
    // защото носител липсва. Закована е ЛИПСАТА, за да не изглежда като недоглеждане: поправката е
    // от страната на СПРАВКАТА (тя знае за лицето) и е ОТВОРЕН ВЪПРОС към възложителя.
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));
    BgPaySlip december = januarySlip(c, emp);
    december.setEmploymentContract(contract);
    december.setPeriod(period(LocalDate.of(2026, 12, 1)));

    assertTrue(service.generate(december).isEmpty(), "декемврийският фиш не е носител");
    assertNull(settlementLine(december));
  }

  // ═══ ИДЕМПОТЕНТНОСТ И ЗНАКЪТ ══════════════════════════════════════════════

  @Test
  @DisplayName("второ пускане не удвоява перото — търси се по СТАБИЛНИЯ КОД")
  void idempotent() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    BgPaySlip slip = januarySlip(c, emp);
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));

    service.generate(slip);
    int afterFirst = slip.getBgPaySlipLineList().size();
    service.generate(slip);

    assertEquals(afterFirst, slip.getBgPaySlipLineList().size());
    assertEquals(0, new BigDecimal("41.00").compareTo(settlementLine(slip).getBgAmount()));
  }

  @Test
  @DisplayName("⚠️ ПОПРАВЕНА СПРАВКА ОБРЪЩА И ЗНАКА, не само сумата")
  void correctedReportFlipsTheSign() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    BgPaySlip slip = januarySlip(c, emp);

    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));
    service.generate(slip);
    assertEquals(BgPaySlipResolver.DEBIT, settlementLine(slip).getBgSign());

    // Внесено е облекчение по чл. 49 ал. 3 и справката е генерирана наново: доплащането става
    // възстановяване. Знакът, написан само при създаване, щеше да замръзне на DEBIT и да УДЪРЖИ
    // сумата, вместо да я върне — двойна грешка в нетото, с вярна абсолютна стойност.
    service.stored = report(c, TAX_YEAR, annual(emp, "1132.00", "-67.00"));
    service.generate(slip);

    BgPaySlipLine line = settlementLine(slip);
    assertEquals(BgPaySlipResolver.CREDIT, line.getBgSign());
    assertEquals(0, new BigDecimal("67.00").compareTo(line.getBgAmount()));
    assertTrue(line.getName().contains("възстановяване"), line.getName());
  }

  // ═══ РЕЧНИКЪТ И ЧЕРНОВАТА ═════════════════════════════════════════════════

  @Test
  @DisplayName("липсващ ред в речника ХВЪРЛИ и сочи seed-а — не оставя лице без изравняване")
  void missingDictionaryRowThrows() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    BgPaySlip slip = januarySlip(c, emp);
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));
    service.dictionaryHasTheRow = false;

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> service.generate(slip));
    assertTrue(e.getMessage().contains(BgPaySlipResolver.LINE_ART49_SETTLEMENT), e.getMessage());
    assertTrue(e.getMessage().contains("App Install"), e.getMessage());
  }

  @Test
  @DisplayName("потвърден, коригиращ и заличаващ фиш се пропускат")
  void onlyPlainDraft() {
    Company c = company("Нукс ООД");
    Employee emp = employee("Асен");
    Service service = new Service();
    service.stored = report(c, TAX_YEAR, annual(emp, "1240.00", "41.00"));

    BgPaySlip confirmed = januarySlip(c, emp);
    confirmed.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    assertTrue(service.generate(confirmed).isEmpty());

    BgPaySlip correction = januarySlip(c, emp);
    correction.setBgIsCorrection(true);
    assertTrue(service.generate(correction).isEmpty());

    BgPaySlip deletion = januarySlip(c, emp);
    deletion.setBgIsDeletion(true);
    assertTrue(service.generate(deletion).isEmpty());
  }

  @Test
  @DisplayName(
      "фиш без трудов договор → ОТКАЗ: справката не може да се намери, значи не се твърди нищо")
  void slipWithoutContractIsRefusal() {
    BgPaySlip slip = januarySlip(company("Нукс ООД"), employee("Асен"));
    slip.setEmploymentContract(null);
    Service service = new Service();

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip);

    assertEquals(1, outcomes.size());
    assertTrue(outcomes.get(0).isRefusal());
    assertNull(settlementLine(slip));
  }
}
