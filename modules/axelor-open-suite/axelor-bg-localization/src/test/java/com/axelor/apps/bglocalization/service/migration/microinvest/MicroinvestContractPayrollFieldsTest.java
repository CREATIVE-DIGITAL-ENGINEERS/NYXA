package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.service.migration.microinvest.MicroinvestContractPayrollFields.ContractResult;
import com.axelor.apps.bglocalization.service.migration.microinvest.MicroinvestContractPayrollFields.Outcome;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Чисто логическите правила на {@link MicroinvestContractPayrollFields} — без база и без Guice.
 *
 * <p>Пазят ТОЧНО онова, което не бива да се случи: подразбиране на вида осигурен, извеждане на
 * заплата от платеното, налучкване на НКПД, писане при противоречив извор, презаписване на ръчна
 * стойност и втори пробег, който пипа базата.
 *
 * <p>Стойностите в тестовете са ИСТИНСКИ, от изворова база на Microinvest: 620.20 е точната
 * минимална работна заплата за 2026 г. в евро, 465.15 е 0,75 от нея, а 51202001/91120004 са
 * действителните НКПД кодове на дружеството.
 */
class MicroinvestContractPayrollFieldsTest {

  private static final Set<String> DICTIONARY =
      new HashSet<>(Arrays.asList("01", "04", "05", "08", "12", "28", "92"));

  private static final LocalDate SEPT_2026 = LocalDate.parse("2026-09-30");

  private static BgNkpd nkpd(String code, String name) {
    BgNkpd n = new BgNkpd();
    n.setCode(code);
    n.setName(name);
    return n;
  }

  private static Map<String, BgNkpd> nkpdDictionary() {
    Map<String, BgNkpd> m = new HashMap<>();
    m.put("51202001", nkpd("51202001", "Готвач"));
    m.put("91120004", nkpd("91120004", "Чистач/ Хигиенист"));
    m.put("51312004", nkpd("51312004", "Сервитьор"));
    return m;
  }

  private static MicroinvestSourceModels.Dekl1FieldValue d1(
      int payrollId, String date, String formRefNo, String value) {
    MicroinvestSourceModels.Dekl1FieldValue v = new MicroinvestSourceModels.Dekl1FieldValue();
    v.payrollId = payrollId;
    v.date = LocalDate.parse(date);
    v.formRefNo = formRefNo;
    v.value = value;
    return v;
  }

  private static MicroinvestSourceModels.Payroll payroll(int payrollId) {
    MicroinvestSourceModels.Payroll p = new MicroinvestSourceModels.Payroll();
    p.payrollId = payrollId;
    p.employeeId = payrollId;
    return p;
  }

  private static MicroinvestSourceModels.Payroll withSalary(
      MicroinvestSourceModels.Payroll p, String date, String value) {
    MicroinvestSourceModels.BaseSalaryPoint s = new MicroinvestSourceModels.BaseSalaryPoint();
    s.date = LocalDate.parse(date);
    s.value = new BigDecimal(value);
    p.baseSalaryHistory.add(s);
    return p;
  }

  private static MicroinvestSourceModels.Payroll withNkpd(
      MicroinvestSourceModels.Payroll p, int version, String section, String code, String desc) {
    MicroinvestSourceModels.NkpdAssignment a = new MicroinvestSourceModels.NkpdAssignment();
    a.payrollKpdId = version * 100 + p.payrollId;
    a.kpdId = version * 1000;
    a.kpdVersionId = version;
    a.section = section;
    a.code = code;
    a.fullCode = section + code;
    a.description = desc;
    p.nkpdCodes.add(a);
    return p;
  }

  private static Map<Integer, MicroinvestSourceModels.Payroll> byId(
      MicroinvestSourceModels.Payroll... ps) {
    Map<Integer, MicroinvestSourceModels.Payroll> m = new LinkedHashMap<>();
    for (MicroinvestSourceModels.Payroll p : ps) {
      m.put(p.payrollId, p);
    }
    return m;
  }

  private static Map<Integer, EmploymentContract> map(int payrollId, EmploymentContract c) {
    Map<Integer, EmploymentContract> m = new HashMap<>();
    m.put(payrollId, c);
    return m;
  }

  private static ContractResult only(List<ContractResult> results) {
    assertEquals(1, results.size(), "очаква се точно един договор в доклада");
    return results.get(0);
  }

  private static List<ContractResult> run(
      Map<Integer, EmploymentContract> contracts,
      Map<Integer, MicroinvestSourceModels.Payroll> payrolls,
      List<MicroinvestSourceModels.Dekl1FieldValue> d1) {
    return MicroinvestContractPayrollFields.resolve(
        contracts, payrolls, d1, DICTIONARY, nkpdDictionary(), SEPT_2026);
  }

  // ═══════════════════════ вид осигурен ═══════════════════════

  @Test
  @DisplayName("точка 12 на Д1 попълва вида осигурен и го нормализира до два знака")
  void mapsInsuredTypeFromD1Point12() {
    EmploymentContract c = new EmploymentContract();
    List<ContractResult> results =
        run(
            map(1022, c),
            byId(payroll(1022)),
            Collections.singletonList(d1(1022, "2026-07-01", "12", "1")));

    ContractResult r = only(results);
    assertEquals(Outcome.UPDATED, r.insuredType.outcome);
    assertEquals("01", r.insuredType.proposedValue);
    assertEquals(LocalDate.parse("2026-07-01"), r.insuredType.effectiveDate);

    MicroinvestContractPayrollFields.apply(results);
    assertEquals("01", c.getBgInsuredTypeCode());
  }

  @Test
  @DisplayName("различните кодове се разпознават поотделно — 04, 12 и 28 не стават 01")
  void doesNotCollapseDistinctCodes() {
    for (String[] pair : new String[][] {{"4", "04"}, {"12", "12"}, {"28", "28"}, {"92", "92"}}) {
      EmploymentContract c = new EmploymentContract();
      List<ContractResult> results =
          run(
              map(1, c),
              byId(payroll(1)),
              Collections.singletonList(d1(1, "2026-01-01", "12", pair[0])));
      MicroinvestContractPayrollFields.apply(results);
      assertEquals(pair[1], c.getBgInsuredTypeCode(), "код " + pair[0] + " от извора");
    }
  }

  @Test
  @DisplayName("БЕЗ подразбиране на 01: липсваща точка 12 оставя полето празно")
  void neverDefaultsInsuredTypeTo01() {
    EmploymentContract c = new EmploymentContract();
    List<ContractResult> results =
        run(
            map(1022, c),
            byId(payroll(1022)),
            Collections.singletonList(d1(1022, "2026-07-01", "21", "1000.00")));

    ContractResult r = only(results);
    assertEquals(Outcome.SKIPPED, r.insuredType.outcome);
    assertNull(r.insuredType.proposedValue);
    assertTrue(r.insuredType.detail.contains("НЕ се подразбира 01"), r.insuredType.detail);

    MicroinvestContractPayrollFields.apply(results);
    assertNull(c.getBgInsuredTypeCode());
  }

  @Test
  @DisplayName("противоречив извор → ОТКАЗ без запис, с изброени кодове и периоди")
  void refusesContradictorySource() {
    EmploymentContract c = new EmploymentContract();
    Map<Integer, EmploymentContract> contracts = new HashMap<>();
    contracts.put(500, c);
    contracts.put(501, c);

    List<ContractResult> results =
        run(
            contracts,
            byId(payroll(500), payroll(501)),
            Arrays.asList(d1(500, "2026-01-01", "12", "1"), d1(501, "2026-05-01", "12", "4")));

    ContractResult r = only(results);
    assertEquals(Outcome.FAILED, r.insuredType.outcome);
    assertNull(r.insuredType.proposedValue);
    assertTrue(r.insuredType.detail.contains("2026-01-01"), r.insuredType.detail);

    MicroinvestContractPayrollFields.apply(results);
    assertNull(c.getBgInsuredTypeCode());
  }

  /**
   * ⚠️ ИСТИНСКИ СЛУЧАЙ. Договор #70 (PayrollID 17) е управител по ДУК и изворът го подава под код
   * 12. Записан върху трудов договор, кодът дава фиш БЕЗ вноска за сметка на работодателя, който
   * изглежда съвсем редовен. Пазено и от {@code BgInsuredTypeHomeDbTest}.
   */
  @Test
  @DisplayName("самоосигуряващ се код (12) НЕ се записва върху трудов договор — ОТКАЗ")
  void refusesSelfInsuredCodeOnAnEmploymentContract() {
    EmploymentContract c = new EmploymentContract();
    List<ContractResult> results =
        MicroinvestContractPayrollFields.resolve(
            map(17, c),
            byId(payroll(17)),
            Collections.singletonList(d1(17, "2026-07-01", "12", "12")),
            DICTIONARY,
            new HashSet<>(Collections.singletonList("12")),
            nkpdDictionary(),
            SEPT_2026);

    ContractResult r = only(results);
    assertEquals(Outcome.FAILED, r.insuredType.outcome);
    assertTrue(r.insuredType.detail.contains("САМООСИГУРЯВАЩО СЕ"), r.insuredType.detail);

    MicroinvestContractPayrollFields.apply(results);
    assertNull(c.getBgInsuredTypeCode(), "кодът не бива да стигне до договора");
  }

  @Test
  @DisplayName("код извън речника → ОТКАЗ, не запис")
  void refusesCodeOutsideDictionary() {
    EmploymentContract c = new EmploymentContract();
    List<ContractResult> results =
        run(
            map(500, c),
            byId(payroll(500)),
            Collections.singletonList(d1(500, "2026-03-01", "12", "77")));

    assertEquals(Outcome.FAILED, only(results).insuredType.outcome);
    MicroinvestContractPayrollFields.apply(results);
    assertNull(c.getBgInsuredTypeCode());
  }

  // ═══════════════════════ основна заплата ═══════════════════════

  @Test
  @DisplayName("договорната заплата идва от VariablesHistory, с точната десетична стойност")
  void mapsBaseSalaryFromVariablesHistory() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p = withSalary(payroll(1035), "2026-07-01", "620.200000");

    List<ContractResult> results = run(map(1035, c), byId(p), Collections.emptyList());
    ContractResult r = only(results);

    assertEquals(Outcome.UPDATED, r.baseSalary.outcome);
    assertEquals(LocalDate.parse("2026-07-01"), r.baseSalary.effectiveDate);
    assertTrue(r.baseSalary.sourceRecord.contains("VariablesHistory"), r.baseSalary.sourceRecord);

    MicroinvestContractPayrollFields.apply(results);
    assertEquals(0, new BigDecimal("620.20").compareTo(c.getBgBaseSalary()));
    assertEquals(2, c.getBgBaseSalary().scale(), "точност 2 знака, както е колоната");
  }

  @Test
  @DisplayName("избира се стойността, ВАЛИДНА за периода — не последният ред в извора")
  void selectsSalaryEffectiveForThePeriod() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p = payroll(1022);
    withSalary(p, "2026-01-01", "465.150000");
    withSalary(p, "2026-05-01", "500.000000");
    withSalary(p, "2027-01-01", "999.990000"); // след оперативния период

    List<ContractResult> results = run(map(1022, c), byId(p), Collections.emptyList());
    MicroinvestContractPayrollFields.apply(results);

    assertEquals(
        0,
        new BigDecimal("500.00").compareTo(c.getBgBaseSalary()),
        "последната стойност В РАМКИТЕ на периода, не бъдещата");
    assertEquals(LocalDate.parse("2026-05-01"), only(results).baseSalary.effectiveDate);
  }

  /**
   * ⚠️ Точно този случай прави „най-новият ред“ невярно правило: Microinvest пренася стойността
   * напред завинаги, така че приключил през 2022 г. договор пак носи ред за 2026 г.
   */
  @Test
  @DisplayName("приключил ИЗВОРОВ РЕД взима стойността към КРАЯ СИ, не пренесената напред")
  void cappedBySourceRowEnd() {
    EmploymentContract c = new EmploymentContract();
    c.setEndDate(LocalDate.parse("2022-07-26"));

    MicroinvestSourceModels.Payroll p = payroll(1);
    p.fromDate = LocalDate.parse("2022-06-11");
    p.toDate = LocalDate.parse("2022-07-26");
    withSalary(p, "2022-06-01", "710.000000"); // предеврова стойност, валидна тогава
    withSalary(p, "2026-07-01", "710.000000"); // пренесена напред, вече без смисъл

    List<ContractResult> results = run(map(1, c), byId(p), Collections.emptyList());
    assertEquals(LocalDate.parse("2022-06-01"), only(results).baseSalary.effectiveDate);
  }

  /**
   * ⚠️ ДОГОВОР 72: край по КОРЕНА на веригата (28.02.2025 г.) би върнал 700,00 лв. вместо
   * действащите 465,15 лв. Тук изворовите редове са истинските: 18 [до 28.02.2025], 19 [до
   * 31.12.2025] и ОТВОРЕНИЯТ 1022, всеки с пренесена напред стойност за юли 2026 г.
   */
  @Test
  @DisplayName("отворена верига дава ТЕКУЩАТА заплата, не пренесената от приключил анекс")
  void openChainTakesCurrentSalary() {
    EmploymentContract c = new EmploymentContract();
    // Заварени грешни стойности: край по КОРЕНА на веригата и заплатата, която тази дата би
    // избрала. Нито една от двете не бива да участва в новия избор.
    c.setEndDate(LocalDate.parse("2025-02-28"));
    c.setBgBaseSalary(new BigDecimal("700.00"));

    MicroinvestSourceModels.Payroll root = payroll(18);
    root.fromDate = LocalDate.parse("2024-07-01");
    root.toDate = LocalDate.parse("2025-02-28");
    withSalary(root, "2025-02-01", "700.000000");
    withSalary(root, "2026-07-01", "700.000000"); // пренесена напред след края на реда

    MicroinvestSourceModels.Payroll mid = payroll(19);
    mid.fromDate = LocalDate.parse("2025-03-01");
    mid.toDate = LocalDate.parse("2025-12-31");
    withSalary(mid, "2025-12-01", "465.150000");
    withSalary(mid, "2026-07-01", "465.150000");

    MicroinvestSourceModels.Payroll tail = payroll(1022);
    tail.fromDate = LocalDate.parse("2026-01-01");
    withSalary(tail, "2026-07-01", "465.150000");

    Map<Integer, EmploymentContract> contracts = new HashMap<>();
    contracts.put(18, c);
    contracts.put(19, c);
    contracts.put(1022, c);

    List<ContractResult> results = run(contracts, byId(root, mid, tail), Collections.emptyList());
    ContractResult r = only(results);

    assertEquals(Outcome.CONFLICT, r.baseSalary.outcome, "полето вече носи 700 — иска решение");
    assertEquals("465.150000", r.baseSalary.proposedValue);
    assertTrue(r.baseSalary.sourceRecord.contains("1022"), r.baseSalary.sourceRecord);
  }

  /** Обратната страна: приключилата верига НЕ бива да поеме стойност отвъд края си. */
  @Test
  @DisplayName("прекратена верига капва заплатата по края на ТЕРМИНАЛНИЯ си запис")
  void terminatedChainCapsSalary() {
    EmploymentContract c = new EmploymentContract();

    MicroinvestSourceModels.Payroll root = payroll(1024);
    root.fromDate = LocalDate.parse("2026-02-16");
    root.toDate = LocalDate.parse("2026-04-30");
    withSalary(root, "2026-02-01", "155.050000");
    withSalary(root, "2026-07-01", "999.990000"); // пренесена напред, вече без смисъл

    MicroinvestSourceModels.Payroll tail = payroll(1031);
    tail.fromDate = LocalDate.parse("2026-05-01");
    tail.toDate = LocalDate.parse("2026-05-17");
    withSalary(tail, "2026-05-01", "155.050000");
    withSalary(tail, "2026-07-01", "888.880000"); // също пренесена след края

    Map<Integer, EmploymentContract> contracts = new HashMap<>();
    contracts.put(1024, c);
    contracts.put(1031, c);

    List<ContractResult> results = run(contracts, byId(root, tail), Collections.emptyList());
    MicroinvestContractPayrollFields.apply(results);

    assertEquals(0, new BigDecimal("155.05").compareTo(c.getBgBaseSalary()));
    assertEquals(LocalDate.parse("2026-05-01"), only(results).baseSalary.effectiveDate);
  }

  @Test
  @DisplayName("втори идентичен внос не пише нищо — всичко излиза UNCHANGED")
  void secondIdenticalImportWritesNothing() {
    EmploymentContract c = new EmploymentContract();

    MicroinvestSourceModels.Payroll root = payroll(18);
    root.fromDate = LocalDate.parse("2024-07-01");
    root.toDate = LocalDate.parse("2025-02-28");
    withSalary(root, "2025-02-01", "700.000000");

    MicroinvestSourceModels.Payroll tail = payroll(1022);
    tail.fromDate = LocalDate.parse("2026-01-01");
    withSalary(tail, "2026-07-01", "465.150000");
    MicroinvestSourceModels.BaseSalaryPoint h = new MicroinvestSourceModels.BaseSalaryPoint();
    h.date = LocalDate.parse("2026-07-01");
    h.value = new BigDecimal("6.000000");
    tail.hoursPerDayHistory.add(h);
    withNkpd(tail, 1, "5120", "2001", "Готвач");

    Map<Integer, EmploymentContract> contracts = new HashMap<>();
    contracts.put(18, c);
    contracts.put(1022, c);
    List<MicroinvestSourceModels.Dekl1FieldValue> d1 =
        Collections.singletonList(d1(1022, "2026-07-01", "12", "1"));

    // Първи пробег: попълва.
    List<ContractResult> first = run(contracts, byId(root, tail), d1);
    assertEquals(1, MicroinvestContractPayrollFields.apply(first), "първият пробег пише");

    // Втори пробег върху същия договор и същия извор: нито едно UPDATED.
    List<ContractResult> second = run(contracts, byId(root, tail), d1);
    ContractResult r = only(second);
    assertEquals(Outcome.UNCHANGED, r.insuredType.outcome);
    assertEquals(Outcome.UNCHANGED, r.baseSalary.outcome);
    assertEquals(Outcome.UNCHANGED, r.nkpd.outcome);
    assertEquals(Outcome.UNCHANGED, r.hoursPerDay.outcome);
  }

  @Test
  @DisplayName("заплатата НЕ се извежда от платеното — ValueSalary изобщо не участва")
  void neverDerivesBaseSalaryFromPay() {
    EmploymentContract c = new EmploymentContract();
    // payroll БЕЗ baseSalaryHistory, но с пълна payslip история на служителя
    List<ContractResult> results =
        run(
            map(1022, c),
            byId(payroll(1022)),
            Collections.singletonList(d1(1022, "2026-07-01", "12", "1")));

    ContractResult r = only(results);
    assertEquals(Outcome.SKIPPED, r.baseSalary.outcome);
    MicroinvestContractPayrollFields.apply(results);
    assertEquals(0, BigDecimal.ZERO.compareTo(c.getBgBaseSalary()), "заплата не се измисля");
  }

  /** Заличеният PayrollID 1019 носи точно 0,00 — ако нулата минеше, фишът щеше да излезе с нула. */
  @Test
  @DisplayName("нулева заплата в извора не се записва")
  void zeroSalaryIsNotASalary() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p = withSalary(payroll(1019), "2026-07-01", "0.000000");

    List<ContractResult> results = run(map(1019, c), byId(p), Collections.emptyList());
    assertEquals(Outcome.SKIPPED, only(results).baseSalary.outcome);
    MicroinvestContractPayrollFields.apply(results);
    assertEquals(0, BigDecimal.ZERO.compareTo(c.getBgBaseSalary()));
  }

  // ═══════════════════════ НКПД ═══════════════════════

  @Test
  @DisplayName("НКПД се сглобява от Section+Code и се сверява с речника")
  void mapsNkpdFromSectionAndCode() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p = withNkpd(payroll(1022), 22, "9112", "0004", "Чистач");

    List<ContractResult> results = run(map(1022, c), byId(p), Collections.emptyList());
    ContractResult r = only(results);

    assertEquals(Outcome.UPDATED, r.nkpd.outcome);
    assertEquals("91120004", r.nkpd.proposedValue);

    MicroinvestContractPayrollFields.apply(results);
    assertNotNull(c.getBgNkpd());
    assertEquals("91120004", c.getBgNkpd().getCode());
  }

  @Test
  @DisplayName("взима се НАЙ-НОВАТА версия на номенклатурата, детерминирано")
  void picksNewestNkpdVersion() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p = payroll(1);
    withNkpd(p, 18, "5131", "2004", "Сервитьор");
    withNkpd(p, 22, "5120", "2001", "Готвач");
    withNkpd(p, 20, "5131", "2004", "Сервитьор");

    List<ContractResult> results = run(map(1, c), byId(p), Collections.emptyList());
    assertEquals("51202001", only(results).nkpd.proposedValue, "версия 22 печели");
  }

  @Test
  @DisplayName("код, който го няма в BgNkpd → ОТКАЗ; не се налучква приблизителен")
  void refusesNkpdOutsideDictionary() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p = withNkpd(payroll(1), 22, "1234", "5678", "Няма такъв");

    List<ContractResult> results = run(map(1, c), byId(p), Collections.emptyList());
    ContractResult r = only(results);
    assertEquals(Outcome.FAILED, r.nkpd.outcome);
    assertTrue(r.nkpd.detail.contains("НЕ се налучква"), r.nkpd.detail);

    MicroinvestContractPayrollFields.apply(results);
    assertNull(c.getBgNkpd());
  }

  @Test
  @DisplayName("две различни длъжности в една и съща версия → ОТКАЗ, не избор по ред")
  void refusesAmbiguousNkpd() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p = payroll(1);
    withNkpd(p, 22, "5120", "2001", "Готвач");
    withNkpd(p, 22, "5131", "2004", "Сервитьор");

    List<ContractResult> results = run(map(1, c), byId(p), Collections.emptyList());
    assertEquals(Outcome.FAILED, only(results).nkpd.outcome);
    MicroinvestContractPayrollFields.apply(results);
    assertNull(c.getBgNkpd());
  }

  @Test
  @DisplayName("йерархичен ред (празен Code) не е длъжност и се пропуска")
  void hierarchyRowIsNotAPosition() {
    MicroinvestSourceModels.NkpdAssignment a = new MicroinvestSourceModels.NkpdAssignment();
    a.section = "1111";
    a.code = "";
    assertNull(MicroinvestContractPayrollFields.normalizeNkpd(a));

    a.code = "22"; // не дава осем знака
    assertNull(MicroinvestContractPayrollFields.normalizeNkpd(a));

    a.code = "2001";
    assertEquals("11112001", MicroinvestContractPayrollFields.normalizeNkpd(a));
  }

  // ═══════════════════════ заличени редове ═══════════════════════

  @Test
  @DisplayName("Д1 ред на ЗАЛИЧЕН Payroll не създава и не обновява договор (PayrollID 1019)")
  void ignoresRowsOfDeletedPayrolls() {
    EmploymentContract live = new EmploymentContract();
    // Само живият Payroll е в картата — точно както MicroinvestPilotImportService я строи.
    List<ContractResult> results =
        run(
            map(1022, live),
            byId(payroll(1022)),
            Arrays.asList(d1(1019, "2026-07-01", "12", "12"), d1(1022, "2026-07-01", "12", "1")));

    ContractResult r = only(results);
    assertEquals(Outcome.UPDATED, r.insuredType.outcome);
    assertEquals("01", r.insuredType.proposedValue, "заличеният ред НЕ надделява");
    assertTrue(r.sourcePayrollIds.contains(1022) && !r.sourcePayrollIds.contains(1019));
  }

  @Test
  @DisplayName("САМО заличени редове → нито един договор в доклада")
  void deletedOnlySourceProducesNothing() {
    List<ContractResult> results =
        run(
            Collections.emptyMap(),
            byId(payroll(1019)),
            Collections.singletonList(d1(1019, "2026-07-01", "12", "12")));
    assertTrue(results.isEmpty());
  }

  // ═══════════════════════ конфликт, идемпотентност, ръчни данни ═══════════════════════

  @Test
  @DisplayName("втори пробег със същия извор: UNCHANGED по трите полета, нула записа")
  void secondRunChangesNothing() {
    EmploymentContract c = new EmploymentContract();
    MicroinvestSourceModels.Payroll p =
        withNkpd(withSalary(payroll(1022), "2026-07-01", "465.150000"), 22, "9112", "0004", "Ч");
    List<MicroinvestSourceModels.Dekl1FieldValue> src =
        Collections.singletonList(d1(1022, "2026-07-01", "12", "1"));

    List<ContractResult> first = run(map(1022, c), byId(p), src);
    assertEquals(1, MicroinvestContractPayrollFields.apply(first));

    List<ContractResult> second = run(map(1022, c), byId(p), src);
    ContractResult r = only(second);
    assertEquals(Outcome.UNCHANGED, r.insuredType.outcome);
    assertEquals(Outcome.UNCHANGED, r.baseSalary.outcome);
    assertEquals(Outcome.UNCHANGED, r.nkpd.outcome);
    assertEquals(0, MicroinvestContractPayrollFields.apply(second), "нула записа");
  }

  @Test
  @DisplayName("ръчна стойност срещу изворова → CONFLICT, ръчната се ЗАПАЗВА по трите полета")
  void manualValueWins() {
    EmploymentContract c = new EmploymentContract();
    c.setBgInsuredTypeCode("04");
    c.setBgBaseSalary(new BigDecimal("1234.56"));
    c.setBgNkpd(nkpd("51312004", "Сервитьор"));

    MicroinvestSourceModels.Payroll p =
        withNkpd(withSalary(payroll(1022), "2026-07-01", "465.150000"), 22, "9112", "0004", "Ч");

    List<ContractResult> results =
        run(map(1022, c), byId(p), Collections.singletonList(d1(1022, "2026-07-01", "12", "1")));
    ContractResult r = only(results);

    assertEquals(Outcome.CONFLICT, r.insuredType.outcome);
    assertEquals(Outcome.CONFLICT, r.baseSalary.outcome);
    assertEquals(Outcome.CONFLICT, r.nkpd.outcome);
    assertEquals("04", r.insuredType.existingValue);
    assertEquals("01", r.insuredType.proposedValue, "предложението пак се ПОКАЗВА");

    assertEquals(0, MicroinvestContractPayrollFields.apply(results));
    assertEquals("04", c.getBgInsuredTypeCode());
    assertEquals(0, new BigDecimal("1234.56").compareTo(c.getBgBaseSalary()));
    assertEquals("51312004", c.getBgNkpd().getCode());
  }

  @Test
  @DisplayName("несвързаните полета на договора остават непокътнати")
  void preservesUnrelatedContractFields() {
    EmploymentContract c = new EmploymentContract();
    c.setPosition("Счетоводител");
    c.setStartDate(LocalDate.parse("2025-12-01"));
    c.setStatus(1);

    MicroinvestSourceModels.Payroll p =
        withNkpd(withSalary(payroll(1022), "2026-07-01", "465.150000"), 22, "9112", "0004", "Ч");
    List<ContractResult> results =
        run(map(1022, c), byId(p), Collections.singletonList(d1(1022, "2026-07-01", "12", "1")));
    MicroinvestContractPayrollFields.apply(results);

    assertEquals("Счетоводител", c.getPosition());
    assertEquals(LocalDate.parse("2025-12-01"), c.getStartDate());
    assertEquals(Integer.valueOf(1), c.getStatus());
  }

  // ═══════════════════════ нормализация ═══════════════════════

  @Test
  @DisplayName("нормализацията на кода: водещи нули, празно и негодно")
  void normalizesCode() {
    assertEquals("01", MicroinvestContractPayrollFields.normalizeInsuredTypeCode("1"));
    assertEquals("01", MicroinvestContractPayrollFields.normalizeInsuredTypeCode(" 001 "));
    assertEquals("12", MicroinvestContractPayrollFields.normalizeInsuredTypeCode("12"));
    assertNull(MicroinvestContractPayrollFields.normalizeInsuredTypeCode(""));
    assertNull(MicroinvestContractPayrollFields.normalizeInsuredTypeCode(null));
    assertNull(MicroinvestContractPayrollFields.normalizeInsuredTypeCode("N/A"));
  }

  @Test
  @DisplayName("договор без нито един източников ред пак влиза в доклада по трите полета")
  void reportsContractsWithoutAnySource() {
    EmploymentContract c = new EmploymentContract();
    List<ContractResult> results = run(map(500, c), byId(payroll(500)), new ArrayList<>());

    ContractResult r = only(results);
    assertEquals(Outcome.SKIPPED, r.insuredType.outcome);
    assertEquals(Outcome.SKIPPED, r.baseSalary.outcome);
    assertEquals(Outcome.SKIPPED, r.nkpd.outcome);
  }
}
