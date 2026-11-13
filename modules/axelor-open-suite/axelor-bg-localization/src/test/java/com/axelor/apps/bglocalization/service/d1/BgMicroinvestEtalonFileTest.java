package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD6FileAdapter;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЦЕЛИЯТ ФАЙЛ, БАЙТ ПО БАЙТ — Д1 И Д6 ПО ФОРМАТА НА MICROINVEST, ПО РЕАЛНИЯ ПЪТ.
 *
 * <p><b>Какво е еталонът.</b> Подадени в НАП Д1 ({@code EMPL2021}) и Д6 ({@code NRA62007}) на
 * Microinvest за 09.2026 и за 01.2025 бяха сверени поле по поле (10.2026). Оригиналите НЕ са в
 * репото — съдържат реални лица. Еталоните в {@code src/test/resources/d1/etalon/microinvest/} са
 * сглобени на ръка по тяхната форма, с ИЗМИСЛЕНИ лица (P0041, P0043) и фирма, и с числа, сметнати
 * на ръка от перата долу. Тоест сверяват се ФОРМАТЪТ на Microinvest и сметката, а не изходът на
 * генератора срещу самия себе си.
 *
 * <p><b>Реалният път:</b> фиш → {@link BgD1RecordBuilder#build} → {@link BgD1FileService#d1Bytes};
 * фишове → {@link BgD6Emitter#emit} → ръчният вид плащане и датата → {@link
 * BgD1FileService#d6Bytes}. Windows-1251, CRLF след всеки ред и CTRL+Z след последния — без обявени
 * разлики.
 *
 * <p><b>Какво НЕ покрива.</b> Само вид осигурен 01 за Д1: подаден ред за вид 12 и за вид 27 от
 * Microinvest има, но вид 12 при нас е в отделна Д1 по регистрацията, а вид 27 е задача на
 * обезщетенията [2/3]. Образецът за 01.2025 (вид 27) влиза тук само с Д6 — вид плащане 6.
 */
class BgMicroinvestEtalonFileTest {

  private static final Charset CP1251 = Charset.forName("windows-1251");

  /** Поле 53 на Д1 и поле 59 на Д6 — ЕИК на счетоводната къща (фиктивен). */
  private static final String SOURCE_FLAG = "876543215";

  private static final BgD1FileService FILES =
      new BgD1FileService(new BgVatFileWriter(), new BgD1FileAdapter(), new BgD6FileAdapter());

  // ═══ Д1 ═══

  @Test
  @DisplayName("Д1 09.2026 · P0041 и P0043, вид 01 — целият файл = еталонът, байт по байт")
  void theD1FileIsTheEtalon() throws IOException {
    Company company = company();
    Period september = period(LocalDate.of(2026, 9, 1));
    List<BgD1Record> records =
        Arrays.asList(
            build(p0041(company), september), //
            build(p0043(company), september));

    byte[] ours = FILES.d1Bytes(records, SOURCE_FLAG);

    assertArrayEquals(
        etalon("EMPL2021_2026_09_P0041_P0043.txt"),
        ours,
        "Д1 срещу формата на Microinvest:\n" + new String(ours, CP1251));
  }

  // ═══ Д6 ═══

  @Test
  @DisplayName("Д6 09.2026 · вид 5, ДДФЛ в блок 2 с вид 8 — целият файл = еталонът, байт по байт")
  void theD6FileIsTheEtalon() throws IOException {
    Company company = company();
    BgD6Declaration d6 = new BgD6Declaration();
    d6.setCompany(company);
    d6.setBgPeriod(period(LocalDate.of(2026, 9, 1)));
    new BgD6Emitter().emit(d6, Arrays.asList(p0041(company), p0043(company)));
    // ⚠️ Двете ръчни решения на човека: видът плащане и датата на изплащане.
    d6.setT81PaymentKind(BgD6Point.PAYMENT_KIND_SAME_MONTH);
    d6.setT18PaymentDate(LocalDate.of(2026, 9, 30));

    byte[] ours = FILES.d6Bytes(d6, SOURCE_FLAG);

    assertArrayEquals(
        etalon("NRA62007_2026_09.txt"),
        ours,
        "Д6 срещу формата на Microinvest:\n" + new String(ours, CP1251));
  }

  @Test
  @DisplayName(
      "Д6 01.2025 · вид 6, изплатено 28.02.2025 — блок 2 е с месеца на ИЗПЛАЩАНЕ (02), байт по"
          + " байт")
  void theKindSixD6FileIsTheEtalon() throws IOException {
    Company company = company();
    BgD6Declaration d6 = new BgD6Declaration();
    d6.setCompany(company);
    d6.setBgPeriod(period(LocalDate.of(2025, 1, 1)));
    new BgD6Emitter().emit(d6, Collections.singletonList(compensation222(company)));
    d6.setT81PaymentKind(BgD6Point.PAYMENT_KIND_LATER_MONTH);
    d6.setT18PaymentDate(LocalDate.of(2025, 2, 28));

    byte[] ours = FILES.d6Bytes(d6, SOURCE_FLAG);

    assertArrayEquals(
        etalon("NRA62007_2025_01_vid6.txt"),
        ours,
        "Д6 вид 6 срещу формата на Microinvest:\n" + new String(ours, CP1251));
  }

  // ═══ ФИШОВЕТЕ — измислени лица, числата сметнати на ръка ═══

  /**
   * P0041 · вид 01, III категория, С УПФ: бруто 1500,00, 21 дни, 168 часа.
   *
   * <p>Лични вноски 98,70 + 21,00 + 6,00 + 33,00 + 48,00 = 206,70; данък (1500,00 − 206,70) × 10 %
   * = 129,33; нето 1500,00 − 206,70 − 129,33 = 1163,97.
   */
  private static BgPaySlip p0041(Company company) {
    BgPaySlip s = slip(company, "P0041", "Петрова", "Мария", "Иванова", "8506150012", 2);
    BigDecimal base = new BigDecimal("1500.00");
    List<BgPaySlipLine> l = s.getBgPaySlipLineList();
    l.add(salary(base));
    l.add(contribution(501, CREDIT, "PENSION", "22", "8.22", "123.30", base));
    l.add(contribution(502, DEBIT, "PENSION", "22", "6.58", "98.70", base));
    l.add(contribution(503, CREDIT, "OZM", "22", "2.10", "31.50", base));
    l.add(contribution(504, DEBIT, "OZM", "22", "1.40", "21.00", base));
    l.add(contribution(505, CREDIT, "UNEMPLOYMENT", "22", "0.60", "9.00", base));
    l.add(contribution(506, DEBIT, "UNEMPLOYMENT", "22", "0.40", "6.00", base));
    l.add(contribution(507, CREDIT, "TZPB", "22.2", "0.50", "7.50", base));
    l.add(contribution(508, CREDIT, "UPF", "26", "2.80", "42.00", base));
    l.add(contribution(509, DEBIT, "UPF", "26", "2.20", "33.00", base));
    l.add(contribution(510, CREDIT, "HEALTH", "22.1", "4.80", "72.00", base));
    l.add(contribution(511, DEBIT, "HEALTH", "22.1", "3.20", "48.00", base));
    // ГВРС — ред със СТАВКА 0 от параметрите за 2026 г.; т. 30 и Д6 т. 15 излизат от него.
    l.add(contribution(512, CREDIT, "GVRS", "30", "0.00", "0.00", base));
    l.add(tax("129.33"));
    s.setBgTaxAmount(new BigDecimal("129.33"));
    return s;
  }

  /**
   * P0043 · вид 01, III категория, БЕЗ УПФ (роден преди 1960): бруто 2000,00.
   *
   * <p>Лични вноски 175,60 + 28,00 + 8,00 + 64,00 = 275,60; данък (2000,00 − 275,60) × 10 % =
   * 172,44; нето 2000,00 − 275,60 − 172,44 = 1551,96.
   */
  private static BgPaySlip p0043(Company company) {
    BgPaySlip s = slip(company, "P0043", "Георгиев", "Стоян", "Петров", "5807200034", 3);
    BigDecimal base = new BigDecimal("2000.00");
    List<BgPaySlipLine> l = s.getBgPaySlipLineList();
    l.add(salary(base));
    l.add(contribution(501, CREDIT, "PENSION", "22", "11.02", "220.40", base));
    l.add(contribution(502, DEBIT, "PENSION", "22", "8.78", "175.60", base));
    l.add(contribution(503, CREDIT, "OZM", "22", "2.10", "42.00", base));
    l.add(contribution(504, DEBIT, "OZM", "22", "1.40", "28.00", base));
    l.add(contribution(505, CREDIT, "UNEMPLOYMENT", "22", "0.60", "12.00", base));
    l.add(contribution(506, DEBIT, "UNEMPLOYMENT", "22", "0.40", "8.00", base));
    l.add(contribution(507, CREDIT, "TZPB", "22.2", "0.50", "10.00", base));
    l.add(contribution(510, CREDIT, "HEALTH", "22.1", "4.80", "96.00", base));
    l.add(contribution(511, DEBIT, "HEALTH", "22.1", "3.20", "64.00", base));
    l.add(contribution(512, CREDIT, "GVRS", "30", "0.00", "0.00", base));
    l.add(tax("172.44"));
    s.setBgTaxAmount(new BigDecimal("172.44"));
    return s;
  }

  /**
   * Образец 2 · 01.2025, обезщетение по чл. 222 ал. 1 КТ (вид 27), 1800,00: САМО фонд „Пенсии“
   * (8,22/6,58) и УПФ (2,80/2,20), без ОЗМ, безработица, ТЗПБ и здравно.
   *
   * <p>ДОО 147,96 + 118,44 = 266,40; УПФ 50,40 + 39,60 = 90,00; данък (1800,00 − 158,04) × 10 % =
   * 164,20 — числата на подадения файл.
   */
  private static BgPaySlip compensation222(Company company) {
    BgPaySlip s = slip(company, "P0043", "Георгиев", "Стоян", "Петров", "5807200034", 3);
    s.getEmploymentContract().setBgInsuredTypeCode("27");
    BigDecimal base = new BigDecimal("1800.00");
    List<BgPaySlipLine> l = s.getBgPaySlipLineList();
    l.add(contribution(501, CREDIT, "PENSION", "22", "8.22", "147.96", base));
    l.add(contribution(502, DEBIT, "PENSION", "22", "6.58", "118.44", base));
    l.add(contribution(508, CREDIT, "UPF", "26", "2.80", "50.40", base));
    l.add(contribution(509, DEBIT, "UPF", "26", "2.20", "39.60", base));
    l.add(tax("164.20"));
    s.setBgTaxAmount(new BigDecimal("164.20"));
    return s;
  }

  // ═══ строители ═══

  private static final String CREDIT = BgPaySlipResolver.CREDIT;
  private static final String DEBIT = BgPaySlipResolver.DEBIT;

  private static Company company() {
    Partner partner = new Partner();
    partner.setName("Тестова фирма ЕООД");
    partner.setRegistrationCode("123456786");

    BgNkid nkid = new BgNkid();
    nkid.setCode("69.20");
    nkid.setBgMinIncomeRowCode("64");

    Company company = new Company();
    company.setPartner(partner);
    company.setBgNkid(nkid);
    return company;
  }

  private static Period period(LocalDate from) {
    Period p = new Period();
    p.setFromDate(from);
    p.setToDate(from.withDayOfMonth(from.lengthOfMonth()));
    return p;
  }

  private static BgPaySlip slip(
      Company company,
      String reference,
      String surname,
      String firstName,
      String middleName,
      String personalId,
      int qualificationGroup) {
    Partner person = new Partner();
    person.setName(surname);
    person.setFirstName(firstName);
    person.setBgMiddleName(middleName);

    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber(personalId);
    employee.setBgPersonalIdType("1");

    BgNkpd nkpd = new BgNkpd();
    nkpd.setQualificationGroup(qualificationGroup);

    EmploymentContract contract = new EmploymentContract();
    contract.setStartDate(LocalDate.of(2020, 1, 15));
    contract.setBgInsuredTypeCode("01");
    contract.setPayCompany(company);
    contract.setEmployee(employee);
    contract.setBgNkpd(nkpd);
    contract.setBgHoursPerDay(new BigDecimal("8"));
    contract.setBgLegalHoursPerDay(new BigDecimal("8"));

    BgPaySlip s = new BgPaySlip();
    s.setReference(reference);
    s.setEmploymentContract(contract);
    s.setBgPaySlipLineList(new ArrayList<>());
    s.setBgWorkedDays(21);
    return s;
  }

  /** Перо 100 „Основно възнаграждение“ — бруто, облагаемо, осигурителен доход. */
  private static BgPaySlipLine salary(BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(100);
    line.setName("Основно възнаграждение");
    line.setBgSign(CREDIT);
    line.setBgFormsGrossWage(true);
    line.setBgIsTaxable(true);
    line.setBgIsInsuranceIncome(true);
    line.setBgAmount(amount);
    return line;
  }

  /**
   * Перо на вноска — фонд, ставка, сума и база ({@code bgQuantity}), както ги пише резолверът.
   *
   * <p>⚠️ „Облагаемо“ и „осигурителен доход“ са ИЗРИЧНО false — така ги носят типовете 5xx в
   * каталога ({@code bg_pay_slip_line_type_contribution.csv}). Домейнът ги подразбира true;
   * оставени така, вноските на работодателя биха влезли в т. 31 и в базата на нетото.
   */
  private static BgPaySlipLine contribution(
      int number,
      String sign,
      String fundCode,
      String d1Field,
      String rate,
      String amount,
      BigDecimal base) {
    BgContributionFund fund = new BgContributionFund();
    fund.setCode(fundCode);
    fund.setBgD1Field(d1Field);

    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setBgSign(sign);
    line.setBgContributionFund(fund);
    line.setBgRate(new BigDecimal(rate));
    line.setBgAmount(new BigDecimal(amount));
    line.setBgQuantity(base);
    line.setBgIsTaxable(false);
    line.setBgIsInsuranceIncome(false);
    return line;
  }

  /** Перо 700 — удържаният данък по чл. 42 ЗДДФЛ; флаговете — както в каталога. */
  private static BgPaySlipLine tax(String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(700);
    line.setBgSign(DEBIT);
    line.setBgAmount(new BigDecimal(amount));
    line.setBgIsTaxable(false);
    line.setBgIsInsuranceIncome(false);
    return line;
  }

  /** Вид 01 така, както е в речника: МОД, т. 31, с часове и с нето. */
  private static BgInsuredType type01() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("01");
    t.setBgAppliesMod(Boolean.TRUE);
    t.setBgFillsT31(Boolean.TRUE);
    return t;
  }

  /** Кофите, каквито {@code BgD1DaySource} връща за месец без отсъствия. */
  private static Map<String, Integer> noAbsences() {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (String bucket : BgD1DayBuckets.BUCKETS) {
      out.put(bucket, 0);
    }
    return out;
  }

  private static BgD1Record build(BgPaySlip slip, Period period) {
    BgD1Facts facts =
        new BgD1Facts(
            noAbsences(),
            null,
            new BgD1HourSource.Hours(new BigDecimal("168"), null, 21),
            new BigDecimal("2111.64"),
            Collections.singleton("01"),
            0);
    List<BgD1FindingDto> findings = new ArrayList<>();
    return BgD1RecordBuilder.build(slip, type01(), period, facts, findings);
  }

  private static byte[] etalon(String name) throws IOException {
    try (InputStream in =
        BgMicroinvestEtalonFileTest.class.getResourceAsStream("/d1/etalon/microinvest/" + name)) {
      assertNotNull(in, "еталонът липсва в classpath-а: " + name);
      return in.readAllBytes();
    }
  }
}
