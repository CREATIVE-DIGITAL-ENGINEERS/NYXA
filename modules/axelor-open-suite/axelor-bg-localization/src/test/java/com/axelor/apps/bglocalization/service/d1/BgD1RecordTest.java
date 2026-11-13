package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЕДИН ФИШ → ЕДИН ЗАПИС: числата, които декларацията носи, и откъде идват.
 *
 * <p><b>⚠️ ВНОСКИТЕ СА ПРОЦЕНТИ.</b> т. 22 = <b>414,20</b> — сборът на осемте пера на ДОО,
 * осигурител плюс лице — е вярно като СБОР НА ВНОСКИ и напълно грешно като СТОЙНОСТ НА ПОЛЕТО:
 * файловият формат обявява полета 35–47 за „(процент)“, а указание т. 22.2 иска „размерите на
 * осигурителните вноски, ПООТДЕЛНО за осигурителя и осигуреното лице“. Свидетел, който заковава
 * сбора, пази сметка, която никой не е поискал.
 *
 * <p><b>Ставките са ДЕЙСТВИТЕЛНИТЕ за вид осигурен 01, III категория, 2026 г.</b> — {@code
 * bg_insurance_contribution.csv}, редове IC-001…IC-007: Пенсии 8,22/6,58 · УПФ 2,80/2,20 · ОЗМ
 * 2,10/1,40 · Безработица 0,60/0,40 · здравно 4,80/3,20. Числата НЕ са измислени, защото точно тук
 * измислените биха скрили същинската грешка: сборът трябва да се познава от таблиците на НАП.
 *
 * <p><b>⚠️ И ТРИТЕ ПРАВДОПОДОБНИ ГРЕШКИ ДАВАТ ДРУГИ ЧИСЛА, ВСЯКО ОТ КОИТО СЕ ПОЗНАВА:</b>
 *
 * <ul>
 *   <li><b>10,92</b> — вярното за т. 22 А (8,22 + 2,10 + 0,60);
 *   <li><b>13,72</b> — със събран УПФ. ⚠️ НАЙ-ОПАСНОТО: това е числото, което публикуваните таблици
 *       наричат „ДОО за сметка на работодателя“, тоест грешката би изглеждала потвърдена отвън;
 *   <li><b>19,30</b> — с двата платеца в едно поле (10,92 + 8,38), тоест старата грешка;
 *   <li><b>8,22</b> — само фонд „Пенсии“, без ОЗМ и Безработица.
 * </ul>
 */
class BgD1RecordTest {

  private static final LocalDate MAY_2026 = LocalDate.of(2026, 5, 1);
  private static final LocalDate MARCH_2026 = LocalDate.of(2026, 3, 1);

  // ═══ строители ═══

  private static Period period(LocalDate from) {
    Period p = new Period();
    p.setFromDate(from);
    p.setToDate(from.withDayOfMonth(from.lengthOfMonth()));
    return p;
  }

  private static BgContributionFund fund(String code, String point) {
    BgContributionFund f = new BgContributionFund();
    f.setCode(code);
    f.setBgD1Field(point);
    return f;
  }

  /**
   * Перо на ВНОСКА (група 5xx).
   *
   * <p><b>⚠️ {@code bgIsInsuranceIncome} се слага на false ИЗРИЧНО, и това са самите данни, не
   * настройка на теста.</b> Домейнът дава на полето {@code default="true"} — тоест {@code new
   * BgPaySlipLine()} идва с TRUE, — а речникът с типове пера носи {@code false} за всичките 20 реда
   * на вноските ({@code bg_pay_slip_line_type_contribution.csv}). Разликата не е дребна: без нея
   * {@link BgPaySlipResolver#insuranceIncomeBase} брои и вноските на осигурителя за доход и т. 21
   * излиза 2339.80 вместо 2000.00 — правдоподобно число, което никой не би сметнал наум.
   *
   * <p>Този ред е онова, което прави фикстурата ЕДНАКВА с производствените данни, вместо просто
   * удобна.
   */
  private static BgPaySlipLine line(
      int number, String sign, String amount, String ratePercent, BgContributionFund f) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    // ⚠️ И ТРЕТАТА ПОЛОВИНА НА ПЕРОТО: БАЗАТА. BgContributionLineService#upsert пише три неща —
    // setBgAmount(amount), setBgQuantity(base) и setBgRate(ratePercent) — а базата е онази, върху
    // която вноската Е сметната, тоест ВЕЧЕ с наложен МОД под и таван. Точно тя пълни т. 21.
    // Фикстура без количество описва фиш, който производственият код не може да произведе, и щеше
    // да прекара т. 21 през РЕЗЕРВНИЯ ѝ път (сбора на перата) — тоест свидетелят щеше да мери
    // друго.
    l.setBgQuantity(FULL_DOO_BASE);
    // ⚠️ СТАВКАТА Е ВТОРАТА ПОЛОВИНА НА ПЕРОТО, и точно тя е нужна на Д1.
    // BgContributionLineService#upsert пише и двете: setBgAmount(outcome.amount) и
    // setBgRate(outcome.rate). Фикстура само със сума би оставила т. 22 празна и щеше да изглежда
    // като дефект в емитера, а щеше да е дефект в теста.
    l.setBgRate(new BigDecimal(ratePercent));
    l.setBgContributionFund(f);
    l.setBgIsInsuranceIncome(Boolean.FALSE);
    l.setBgIsTaxable(Boolean.FALSE);
    return l;
  }

  /** Начисление: група 1xx, CREDIT, влиза в осигурителния доход. */
  private static BgPaySlipLine earning(int number, String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(BgPaySlipResolver.CREDIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsInsuranceIncome(Boolean.TRUE);
    return l;
  }

  private static BgInsuredType insuredType(String code, boolean appliesMod) {
    BgInsuredType t = new BgInsuredType();
    t.setCode(code);
    t.setBgAppliesMod(appliesMod);
    return t;
  }

  /**
   * Базата, върху която вноските на {@link #slipWithFullDooSet} са сметнати.
   *
   * <p>⚠️ РАВНА НА НАЧИСЛЕНОТО НАРОЧНО: този фиш е ПОД тавана, тоест капнатата база и сборът на
   * перата съвпадат. Свидетелят за разминаването им живее в {@code BgD1Point21CapTest}.
   */
  private static final BigDecimal FULL_DOO_BASE = new BigDecimal("2000.00");

  /** Фиш по код 01 с пълния набор пера на ДОО + ТЗПБ. */
  private static BgPaySlip slipWithFullDooSet() {
    BgContributionFund pension = fund("PENSION", BgD1Point.T22_DOO);
    BgContributionFund ozm = fund("OZM", BgD1Point.T22_DOO);
    BgContributionFund unemployment = fund("UNEMPLOYMENT", BgD1Point.T22_DOO);
    BgContributionFund tzpb = fund("TZPB", BgD1Point.T22_2_TZPB);
    BgContributionFund health = fund("HEALTH", BgD1Point.T22_1_HEALTH);
    BgContributionFund upf = fund("UPF", BgD1Point.T26_UPF);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(earning(100, "2000.00"));

    // ═══ ФОНДОВЕТЕ НА ДОО — три фонда, шест пера, ставки от IC-001/004/005 ═══
    // ⚠️ ФОНД „ПЕНСИИ ПО ЧЛ. 69“ НЕ Е ТУК, и това е нарочно, не пропуск. Речникът на вноските
    // (bg_insurance_contribution.csv) НЕ дава ред PENSION_ART69 за вид осигурен 01 — фондът е за
    // военните и полицаите по чл. 69 КСО. Фикстура, която го носи, твърди, че едно лице се
    // осигурява едновременно в двата пенсионни фонда: невъзможен фиш, върху който сборът на
    // ставките пак излиза правдоподобен.
    lines.add(line(500, BgPaySlipResolver.CREDIT, "164.40", "8.22", pension));
    lines.add(line(501, BgPaySlipResolver.DEBIT, "131.60", "6.58", pension));
    lines.add(line(504, BgPaySlipResolver.CREDIT, "42.00", "2.10", ozm));
    lines.add(line(505, BgPaySlipResolver.DEBIT, "28.00", "1.40", ozm));
    lines.add(line(506, BgPaySlipResolver.CREDIT, "12.00", "0.60", unemployment));
    lines.add(line(507, BgPaySlipResolver.DEBIT, "8.00", "0.40", unemployment));

    // ТЗПБ — САМО осигурител. Лична част няма и това е самата норма. Ставка по КИД.
    lines.add(line(508, BgPaySlipResolver.CREDIT, "9.40", "0.47", tzpb));

    // Здравно (IC-006) — двете половини на 8 на сто.
    lines.add(line(516, BgPaySlipResolver.CREDIT, "96.00", "4.80", health));
    lines.add(line(517, BgPaySlipResolver.DEBIT, "64.00", "3.20", health));

    // ⚠️ УПФ (IC-003) — ДЗПО, тоест т. 26, а НЕ т. 22. Добавен нарочно: без него свидетелят не може
    // да различи вярното 10,92 от 13,72, а 13,72 е числото, което грешката би дала.
    lines.add(line(512, BgPaySlipResolver.CREDIT, "56.00", "2.80", upf));
    lines.add(line(513, BgPaySlipResolver.DEBIT, "44.00", "2.20", upf));

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-05-0001");
    slip.setBgPaySlipLineList(lines);
    slip.setBgTaxAmount(new BigDecimal("176.42"));
    slip.setEmploymentContract(contract("01"));
    return slip;
  }

  private static EmploymentContract contract(String insuredCode) {
    Partner companyPartner = new Partner();
    companyPartner.setName("Никса ЕООД");
    companyPartner.setRegistrationCode("831919536");
    // ⚠️ Нарочно РАЗЛИЧЕН от ЕИК-а: точно това е капанът на т. 4.
    companyPartner.setTaxNbr("BG831919536");

    BgNkid nkid = new BgNkid();
    // ⚠️ ДВЕ РАЗЛИЧНИ КОЛОНИ НА ЕДИН РЕД, с РАЗЛИЧНИ стойности нарочно: т. 12.2 иска кода по КИД,
    // т. 12.3 — реда по приложението към ЗБДОО. Еднакви стойности тук биха скрили размяната им.
    nkid.setCode("01.11");
    nkid.setBgMinIncomeRowCode("42");

    Company company = new Company();
    company.setPartner(companyPartner);
    company.setBgNkid(nkid);

    Partner person = new Partner();
    person.setName("Петров");
    person.setFirstName("Иван");
    person.setBgMiddleName("Георгиев");

    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber("7501014567");
    employee.setBgPersonalIdType("1"); // ЕГН по нашата селекция

    BgNkpd nkpd = new BgNkpd();
    nkpd.setQualificationGroup(3);

    EmploymentContract contract = new EmploymentContract();
    // Договор, започнал ПРЕДИ месеца и незавършил — тоест т. 14 и т. 15 остават празни. Това е
    // обичайният случай и той е базата, срещу която двата свидетеля по-долу менят по едно нещо.
    contract.setStartDate(LocalDate.of(2020, 1, 15));
    contract.setBgInsuredTypeCode(insuredCode);
    contract.setPayCompany(company);
    contract.setEmployee(employee);
    contract.setBgNkpd(nkpd);
    return contract;
  }

  private static BgD1Record build(BgPaySlip slip, BgInsuredType type) {
    return BgD1RecordBuilder.build(slip, type, period(MAY_2026), new ArrayList<>());
  }

  // ═══ свидетелите ═══

  @Test
  @DisplayName("т. 21 · осигурителният доход е точно insuranceIncomeBase, не bgInsuranceIncome")
  void point21IsTheResolversInsuranceIncomeBase() {
    BgPaySlip slip = slipWithFullDooSet();
    BgD1Record record = build(slip, insuredType("01", true));

    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(record.getT21InsuranceIncome()),
        "т. 21 е базата, ВЪРХУ КОЯТО вноските са сметнати (bgQuantity на перата от 5xx) — указание"
            + " т. 21: „доходът, върху който СЕ ДЪЛЖАТ осигурителни вноски“.");
    assertEquals(
        0,
        BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList())
            .compareTo(record.getT21InsuranceIncome()),
        "⚠️ ЗА ТОЗИ ФИШ ДВЕТЕ СЪВПАДАТ, И ТОВА Е ЦЯЛАТА ПРИЧИНА ТВЪРДЕНИЕТО ДА ИЗГЛЕЖДА БЕЗОБИДНО:"
            + " 2000,00 е ПОД тавана, тоест капнатата база и сборът на начисленията са едно и също"
            + " число. Разминават се САМО над тавана — виж BgD1Point21CapTest, където сборът е"
            + " 3000,00, а т. 21 е 2111,64.");
  }

  @Test
  @DisplayName("т. 22 А/Б · СБОРЪТ НА СТАВКИТЕ на трите фонда на ДОО, поотделно по платец")
  void point22SumsTheDooRatesPerPayer() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));

    // 8.22 (Пенсии) + 2.10 (ОЗМ) + 0.60 (Безработица)
    assertEquals(
        0,
        new BigDecimal("10.92").compareTo(record.getT22DooRateEmployer()),
        "т. 22 А (поле 35) е ЕДИН процент за фондовете на ДОО без ТЗПБ, за сметка на осигурителя.\n"
            + "  13.72 би било със събран УПФ — ⚠️ и точно това число публикуваните таблици наричат"
            + " „ДОО за сметка на работодателя“;\n"
            + "  19.30 би било с двата платеца в едно поле (старата грешка);\n"
            + "  8.22 би било само фонд „Пенсии“.");

    // 6.58 + 1.40 + 0.40
    assertEquals(
        0,
        new BigDecimal("8.38").compareTo(record.getT22DooRateInsured()),
        "т. 22 Б (поле 36) е същият сбор за сметка на ЛИЦЕТО. Указание т. 22.2: „размерите на"
            + " осигурителните вноски, ПООТДЕЛНО за осигурителя и осигуреното лице“.");

    assertTrue(
        record.getT22DooRateEmployer().compareTo(record.getT22DooRateInsured()) != 0,
        "двете половини НЕ са едно и също число — свидетел, който ги сравнява с една стойност, би"
            + " минал и при разменени полета");
  }

  @Test
  @DisplayName("т. 22 · ПРОЦЕНТ, не левове — 414,20 е сборът на вноските и НЕ е стойност на полето")
  void point22IsAPercentageNotAnAmount() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));

    // ⚠️ БЕЗ ТОЗИ СВИДЕТЕЛ 414,20 би минало зелено.
    assertTrue(
        new BigDecimal("414.20").compareTo(record.getT22DooRateEmployer()) != 0,
        "414,20 е СБОРЪТ НА ВНОСКИТЕ в левове — легитимно число, но на друга декларация."
            + " Файловият формат дава на поле 35 размер ≤5.2 и го обявява за „(процент)“;"
            + " левовите суми са предметът на Декларация обр. 6.");
    assertTrue(
        record.getT22DooRateEmployer().compareTo(new BigDecimal("100")) < 0,
        "ставка над 100 на сто е невъзможна — най-простата проверка, че полето носи процент, а не"
            + " пари. Тя пада първа при връщане към сумиране.");
  }

  @Test
  @DisplayName(
      "т. 22 · ТЗПБ и УПФ НЕ влизат в нея — § 2 ал. 1 т. 1 изключва първата, точка 26 втория")
  void point22ExcludesTzpbAndUpf() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));

    assertTrue(
        new BigDecimal("11.39").compareTo(record.getT22DooRateEmployer()) != 0,
        "11.39 е 10.92 + 0.47 (ТЗПБ). Т. 22 е формулирана „без размера на вноската за фонд „Трудова"
            + " злополука и професионална болест““ — тя е т. 22.2.");
    assertTrue(
        new BigDecimal("13.72").compareTo(record.getT22DooRateEmployer()) != 0,
        "13.72 е 10.92 + 2.80 (УПФ). УПФ е ДЗПО, не ДОО, и има СВОЯ точка — т. 26.");

    // И обратната половина: УПФ наистина е стигнал до т. 26, а не се е загубил.
    assertEquals(
        0,
        new BigDecimal("2.80").compareTo(record.getT26UpfRateEmployer()),
        "⚠️ Двете твърдения се държат ЗАЕДНО. „УПФ не е в т. 22“ е вярно и когато перото му е"
            + " изчезнало напълно — тогава декларацията губи 5 пункта тихо.");
    assertEquals(0, new BigDecimal("2.20").compareTo(record.getT26UpfRateInsured()));
  }

  @Test
  @DisplayName("т. 22.2 · ТЗПБ носи САМО ставката на осигурителя — едно поле, един платец")
  void point22_2CarriesOnlyTheEmployerRate() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals(
        0,
        new BigDecimal("0.47").compareTo(record.getT222TzpbRate()),
        "ТЗПБ е изцяло за сметка на осигурителя (или на морското лице) — поле 39 е ЕДНО и лична"
            + " част няма, тоест перо за нея не се създава.");
  }

  @Test
  @DisplayName("т. 22.1 А/Б · здравното също се разделя — 4,80 и 3,20, а не 8,00")
  void point22_1SplitsTheHealthRate() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals(
        0,
        new BigDecimal("4.80").compareTo(record.getT221HealthRateEmployer()),
        "поле 37 — ставката за сметка на ОСИГУРЯВАЩИЯ");
    assertEquals(
        0,
        new BigDecimal("3.20").compareTo(record.getT221HealthRateInsured()),
        "поле 38 — ставката за сметка на ЛИЦЕТО");
    assertTrue(
        new BigDecimal("8.00").compareTo(record.getT221HealthRateEmployer()) != 0,
        "⚠️ 8,00 е ЦЯЛАТА здравна вноска и е правдоподобно число: точно то се цитира в ЗБНЗОК и"
            + " точно него иска ЗЗО чл. 40 ал. 1 т. 1 б. „б“ при неплатен отпуск"
            + " (BgUnpaidLeaveHealthResolver#RATE_IS_THE_WHOLE_CONTRIBUTION). Тук обаче полетата са"
            + " ДВЕ и всяко иска своята половина.");
    assertEquals(
        0,
        new BigDecimal("8.00")
            .compareTo(record.getT221HealthRateEmployer().add(record.getT221HealthRateInsured())),
        "и все пак сборът им Е 8,00 — двете половини не са произволни числа");
  }

  @Test
  @DisplayName("§ 2 ал. 2 · ставката запазва мащаб 2 — файлът дава на полета 35–47 размер ≤5.2")
  void theRateKeepsTwoDecimals() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals(
        "10.92",
        record.getT22DooRateEmployer().toPlainString(),
        "⚠️ БЕЗ stripTrailingZeros(): то прави от 10.92 низа „10.92“, но от 4.80 — „4.8“, и тестът"
            + " щеше да пита как изглежда числото, а не какъв е мащабът му. Тук се пита точно"
            + " мащабът, защото него иска файлът.");
    assertEquals(
        2,
        record.getT221HealthRateEmployer().scale(),
        "речникът носи ставките с мащаб 4 („4.8000“); агрегаторът реже нулите ДО 2. Това е"
            + " отрязване на нули, не промяна на стойност — нито един от 265-те реда не иска трети"
            + " знак.");
  }

  @Test
  @DisplayName("липсваща вноска не носи ставка — и се ЧЕТЕ като нула, защото getter-ът е такъв")
  void anAbsentContributionCarriesNoRate() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));

    // ⚠️ КАПАНЪТ, ИЗМЕРЕН, А НЕ ПРЕДПОЛОЖЕН. Колоната остава NULL в базата, но генерираният
    // getter за BigDecimal връща BigDecimal.ZERO (виж BgDefaultedGetterTest). Тоест assertNull
    // тук би бил ЧЕРВЕН, а разликата „няма вноска“ / „вноската е нула“ е НЕВИДИМА за всеки
    // читател. Затова се пита signum(), както го прави и самият валидатор.
    assertEquals(
        0,
        record.getT23TeacherPfRate().signum(),
        "фишът няма перо за Учителския пенсионен фонд, тоест т. 23 не носи ставка.");
    assertEquals(0, record.getT25PpfRate().signum(), "същото за ППФ");
    assertEquals(
        0,
        record.getT30GvrsRate().signum(),
        "⚠️ И ГВРС Е НУЛА, НО ПО ДРУГА ПРИЧИНА: ЗБДОО чл. 15 ал. 1 спира вноската му за 2026 г.,"
            + " тоест ставката е 0,0000 с вид LEGAL_ZERO и резолверът НЕ строи перо за нея. Тук"
            + " липсата Е нулата и четенето е вярно — но същият механизъм губи ненулева ставка,"
            + " когато сумата се закръгли до 0,00. Затова валидаторът пита обратното:"
            + " BASE_WITHOUT_RATE.");

    // И обратната половина: онова, което Е попълнено, не бива да се чете като липсващо.
    assertTrue(
        record.getT22DooRateEmployer().signum() != 0,
        "т. 22 А носи ставка — ако и тя се чете като нула, филтърът е изял верните пера");
  }

  @Test
  @DisplayName("т. 4 · ЕИК по БУЛСТАТ (registrationCode), а НЕ ДДС номерът (taxNbr)")
  void point4IsTheBulstatCodeNotTheVatNumber() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals(
        "831919536",
        record.getT4ObligedPersonCode(),
        "т. 4 иска „ЕИК по БУЛСТАТ, ЕИК по ЗТРРЮЛНЦ, служебен номер, издаден от НАП“ —"
            + " Partner.registrationCode.");
    assertTrue(
        !"BG831919536".equals(record.getT4ObligedPersonCode()),
        "⚠️ ДДС-стекът чете company.partner.taxNbr и по инерция т. 4 би носила ДДС номера:"
            + " правдоподобен низ на верния ред, който никой не поглежда втори път.");
  }

  @Test
  @DisplayName("т. 5.1 · нашата „1“ (ЕГН) се ПРЕВЕЖДА на „0“, както иска наредбата")
  void point5_1TranslatesOurIdTypeInsteadOfForwardingIt() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals(
        "0",
        record.getT51PersonalIdCode(),
        "наредбата: „0 - ако лицето е с единен граждански номер; 2 - ако лицето е с личен номер на"
            + " чужденец…“. Нашата селекция bg.personal.id.type.select дава „1“ за ЕГН — препратена"
            + " сурово, тя е низ, който НАП не познава.");
    assertEquals("2", BgD1RecordBuilder.idCodeOf("2"), "ЛНЧ остава „2“ — там двете съвпадат");
    assertEquals("0", BgD1RecordBuilder.idCodeOf(null), "празно се чете като ЕГН");
  }

  @Test
  @DisplayName("т. 6/7 · фамилия и инициали от трите имена")
  void surnameAndInitials() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals("Петров", record.getT6Surname());
    assertEquals(
        "ИГ",
        record.getT7Initials(),
        "т. 7: „първата буква от името и първата буква от презимето“ — Иван Георгиев → ИГ.");
    assertEquals(
        "И",
        BgD1RecordBuilder.initials("Иван", null),
        "лице без вписано презиме има ЕДИН инициал. Измислен втори знак са данни, които никой не"
            + " е казал.");
  }

  @Test
  @DisplayName("т. 12.1/12.3 · попълват се при код с МОД и остават ПРАЗНИ при код без МОД")
  void modPointsFollowTheFlagNotTheData() {
    BgD1Record withMod = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals("3", withMod.getT121QualificationGroup());
    assertEquals("42", withMod.getT123ActivityRow());

    // Същият фиш, същият договор, същият НКПД — сменя се САМО флагът в речника.
    BgD1Record withoutMod = build(slipWithFullDooSet(), insuredType("12", false));
    assertNull(
        withoutMod.getT121QualificationGroup(),
        "т. 12.1 се попълва само за 18-те кода на заб. 1. Данните ги ИМА на договора — и точно"
            + " затова решението трябва да е на флага, не на наличието им.");
    assertNull(withoutMod.getT123ActivityRow(), "същото за т. 12.3");
  }

  @Test
  @DisplayName("т. 1 · буквите са КИРИЛИЦА — „К“ U+041A и „З“ U+0417, не латинските K и Z")
  void correctionCodesAreCyrillicLetters() {
    BgPaySlip correcting = slipWithFullDooSet();
    correcting.setBgIsCorrection(Boolean.TRUE);
    assertEquals("К", build(correcting, insuredType("01", true)).getT1CorrectionCode());

    BgPaySlip deleting = slipWithFullDooSet();
    deleting.setBgIsDeletion(Boolean.TRUE);
    assertEquals("З", build(deleting, insuredType("01", true)).getT1CorrectionCode());

    // ⚠️ Сравнението е по КОДОВА ТОЧКА, не по изглед: „К“ и „K“ се изписват еднакво в почти всеки
    // шрифт, тоест грешката би минала през всеки визуален преглед и през всеки diff.
    assertEquals(0x041A, BgD1RecordBuilder.CORRECTION_K.charAt(0), "„К“ трябва да е U+041A");
    assertEquals(0x0417, BgD1RecordBuilder.DELETION_Z.charAt(0), "„З“ трябва да е U+0417");

    assertNull(
        build(slipWithFullDooSet(), insuredType("01", true)).getT1CorrectionCode(),
        "редовният фиш няма код за корекция — празно е ЛИПСАТА на код, не трети код.");
  }

  @Test
  @DisplayName("т. 2/3 · месецът е в две позиции и идва от периода на ДАННИТЕ")
  void monthAndYearComeFromTheDataPeriod() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        BgD1RecordBuilder.build(
            slipWithFullDooSet(), insuredType("01", true), period(MAY_2026), findings);
    assertEquals(
        "05",
        record.getT2Month(),
        "„попълва се в две позиции месецът“ — водещата нула е" + " част от стойността");
    assertEquals("2026", record.getT3Year());
    // ⚠️ САМО ГРЕШКИ, не всички находки — и това е промяна в самия свидетел, не разхлабване.
    // Пълният фиш вече ражда ПРЕДУПРЕЖДЕНИЯ: т. 34 (таванът не е подаден на този вход),
    // т. 16 поз. 3 (законоустановеното работно време няма носител) и т. 16.7/16.8 (фишът замразява
    // дни, не часове). Всяко от тях е ОБЯВЕНА граница и трябва да се вижда при всеки запис — тоест
    // „нула находки“ вече би значело, че някоя от трите е замълчала.
    assertTrue(
        findings.stream().noneMatch(f -> BgD1FindingDto.SEVERITY_ERROR.equals(f.getSeverity())),
        "пълен фиш не бива да ражда ГРЕШКИ. Находки: " + findings);
  }

  @Test
  @DisplayName("т. 31а · данъкът се ПРЕНАСЯ от фиша, не се смята наново")
  void point31aCarriesTheSlipTax() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals(
        0,
        new BigDecimal("176.42").compareTo(record.getT31aTax()),
        "морското лице носи 1 на сто вместо 10 по чл. 42 ал. 4 ЗДДФЛ, но това е решено още на"
            + " фиша (BgPaySlipService#incomeTaxRate чете bgIsSeafarer). Второ решение за ставката"
            + " тук би било второ мнение по един и същи въпрос.");
  }

  // ═══ ЕТАП 0б · т. 12.2 и т. 14/15 ═══

  @Test
  @DisplayName("т. 12.2 · „01.11“ става „0111“ — точката се МАХА, не се реже")
  void point12_2StripsTheDotInsteadOfTruncating() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));

    assertEquals(
        "0111",
        record.getT122ActivityCode(),
        "указанието иска „4-значният код по КИД 2008 г.“, а поле 29 е Char с размер РАВЕН НА 4."
            + " Речникът носи „01.11“ — пет знака.");
    assertTrue(
        !"01.1".equals(record.getT122ActivityCode()),
        "⚠️ „01.1“ е онова, което ОТРЯЗВАНЕ до четири знака би дало: низ с ВЯРНА дължина и друг"
            + " смисъл, който минава всяка проверка за дължина. Затова се маха точката, а не се"
            + " реже.");
    assertEquals(4, record.getT122ActivityCode().length(), "поле 29 иска РАВНО на 4, не „до 4“");
  }

  @Test
  @DisplayName("т. 12.2 и т. 12.3 · четат РАЗЛИЧНИ колони на един и същи ред по НКИД")
  void point12_2AndPoint12_3ReadDifferentColumns() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));
    assertEquals("0111", record.getT122ActivityCode(), "т. 12.2 е КОДЪТ по КИД 2008");
    assertEquals(
        "42",
        record.getT123ActivityRow(),
        "т. 12.3 е РЕДЪТ по приложението към ЗБДОО. Двете идват от един ред на речника и точно"
            + " затова размяната им е лесна и невидима: и двете са кратки цифрови низа.");
  }

  @Test
  @DisplayName("т. 12.2 · остава ПРАЗНА при код без МОД — заб. 1 изброява 12.1, 12.2 И 12.3")
  void point12_2FollowsTheModFlagLikeItsTwoSiblings() {
    BgD1Record withoutMod = build(slipWithFullDooSet(), insuredType("12", false));
    assertNull(
        withoutMod.getT122ActivityCode(),
        "заб. 1 към т. 12: „Точки 12.1, 12.2 и 12.3 се попълват за вид осигурен 01, 02, 03, 04, 08,"
            + " 10, 16, 18, 19, 20, 25, 26, 42, 72, 82, 95, 97 и 99.“ ТРИТЕ ЗАЕДНО — тоест т. 12.2"
            + " не бива да е единствената, която се попълва винаги, само защото източникът ѝ е"
            + " фирмен и винаги наличен.");
  }

  @Test
  @DisplayName("т. 14 · попълва се САМО когато осигуряването възниква ВЪТРЕ в месеца")
  void point14IsFilledOnlyWhenInsuranceStartsInsideTheMonth() {
    BgD1Record continuing = build(slipWithFullDooSet(), insuredType("01", true));
    assertNull(
        continuing.getT14Day1(),
        "⚠️ „01“ ЗА ВСЕКИ МЕСЕЦ Е ГРЕШКАТА, КОЯТО ИЗГЛЕЖДА КАТО ПОДРЕДБА. Лице, осигурено през"
            + " целия месец, НЕ носи ден на възникване — попълнена, точката твърди, че"
            + " осигуряването е възниквало наново всеки месец.");

    BgPaySlip hired = slipWithFullDooSet();
    hired.getEmploymentContract().setStartDate(LocalDate.of(2026, 5, 12));
    assertEquals(
        "12",
        build(hired, insuredType("01", true)).getT14Day1(),
        "договор от 12 май → т. 14 поз. 1 = „12“");

    BgPaySlip hiredEarly = slipWithFullDooSet();
    hiredEarly.getEmploymentContract().setStartDate(LocalDate.of(2026, 5, 3));
    assertEquals(
        "03",
        build(hiredEarly, insuredType("01", true)).getT14Day1(),
        "„попълва се в ДВЕ ПОЗИЦИИ“ — водещата нула Е част от стойността, а Integer я губи");
  }

  @Test
  @DisplayName("т. 15 · чете ДЕЙСТВИТЕЛНОТО прекратяване, а НЕ уговорения край на срочен договор")
  void point15ReadsTheActualTerminationNotTheContractedEnd() {
    // Срочен договор до 31.12.2026 — уговореният край стои попълнен от подписването.
    BgPaySlip fixedTerm = slipWithFullDooSet();
    fixedTerm.getEmploymentContract().setEndDate(LocalDate.of(2026, 12, 31));
    assertNull(
        build(fixedTerm, insuredType("01", true)).getT15Day1(),
        "⚠️ Уговорен край в ДРУГ месец не е прекратяване ТОЗИ месец. Датата обаче е попълнена и"
            + " изглежда като край — прочетена наивно, т. 15 би обявила прекратяване, което не е"
            + " станало.");

    // Същият договор, прекратен предсрочно на 20 май.
    BgPaySlip terminated = slipWithFullDooSet();
    terminated.getEmploymentContract().setEndDate(LocalDate.of(2026, 12, 31));
    terminated.getEmploymentContract().setBgTerminationDate(LocalDate.of(2026, 5, 20));
    assertEquals(
        "20",
        build(terminated, insuredType("01", true)).getT15Day1(),
        "bgTerminationDate съществува точно за да носи ДЕЙСТВИТЕЛНОТО прекратяване, отделно от"
            + " уговорения край. Тук двете сочат различни месеци и само едното е т. 15.");
  }

  /** Един интервал, прекъсващ осигуряването — КСО чл. 10 ал. 2. */
  private static LocalDate[] gap(int fromDay, int toDay) {
    return new LocalDate[] {LocalDate.of(2026, 5, fromDay), LocalDate.of(2026, 5, toDay)};
  }

  private static BgD1Record buildWithGaps(BgPaySlip slip, LocalDate[]... gaps) {
    return BgD1RecordBuilder.build(
        slip,
        insuredType("01", true),
        period(MAY_2026),
        new BgD1Facts(null, java.util.Arrays.asList(gaps), null, null),
        new ArrayList<>());
  }

  @Test
  @DisplayName("⚠️ т. 14/15 · позиции 2–5 ВЕЧЕ имат източник — КСО чл. 10 ал. 2")
  void positionsTwoToFiveComeFromUncreditedAbsences() {
    // Самоотлъчка от 11 до 15 май: НЕ се зачита за осигурителен стаж → прекъсва осигуряването.
    BgD1Record record = buildWithGaps(slipWithFullDooSet(), gap(11, 15));

    assertNull(
        record.getT14Day1(),
        "договорът е от 2020 г. — осигуряването НЕ възниква вътре в месеца, тоест позиция 1 на т. 14"
            + " остава празна");
    assertEquals(
        "10",
        record.getT15Day1(),
        "⚠️ Т. 15 Е ПОСЛЕДНИЯТ ДЕН В ОСИГУРЯВАНЕ, А НЕ НАЧАЛОТО НА ОТСЪСТВИЕТО. Самоотлъчката започва"
            + " на 11-и → последният осигурен ден е 10-и. Записано „11“, полето би сочило ден, в"
            + " който лицето НЕ е осигурено — и цифрата е валидна.");
    assertEquals(
        "16",
        record.getT14Day2(),
        "⚠️ И Т. 14 НА СЛЕДВАЩАТА ПОЗИЦИЯ Е ДЕНЯТ НА ВЪЗОБНОВЯВАНЕТО, не краят на отсъствието."
            + " Отсъствието свършва на 15-и → осигуряването се възобновява на 16-и.");
    assertNull(
        record.getT15Day2(),
        "вторият отрязък свършва с месеца, а договорът не е прекратен — тоест няма прекъсване, което"
            + " да се обяви");
  }

  @Test
  @DisplayName("⚠️ т. 14/15 · КАЛЕНДАРНИ дни, не работни — уикендът НЕ мести границата")
  void theBoundariesAreCalendarDays() {
    // 2026: 8 май е петък, 11 май е понеделник. Прекъсване САМО за петъка.
    BgD1Record record = buildWithGaps(slipWithFullDooSet(), gap(8, 8));
    assertEquals(
        "07",
        record.getT15Day1(),
        "указанието казва „последният КАЛЕНДАРЕН ден в осигуряване“ — четвъртък 7 май");
    assertEquals(
        "09",
        record.getT14Day2(),
        "⚠️ И ВЪЗОБНОВЯВАНЕТО Е НА 9-и (СЪБОТА), а не в понеделник: производственият календар тук"
            + " няма работа. Прочетено като работен ден, полето би дало „11“ — валидна цифра, която"
            + " твърди два дни по-късно възобновяване.");
  }

  @Test
  @DisplayName("⚠️ ДОПИРАЩИ СЕ отсъствия са ЕДНО прекъсване — инак измислят празна двойка")
  void adjacentAbsencesMergeIntoOneBreak() {
    // Неплатен отпуск до 15-о и самоотлъчка от 16-о: две заявки, ЕДНО прекъсване.
    BgD1Record record = buildWithGaps(slipWithFullDooSet(), gap(11, 15), gap(16, 20));

    assertEquals("10", record.getT15Day1());
    assertEquals("21", record.getT14Day2());
    assertNull(
        record.getT14Day3(),
        "⚠️ БРОЕНИ ПООТДЕЛНО, ДВЕТЕ ЗАЯВКИ ДАВАТ ПРАЗЕН ОТРЯЗЪК МЕЖДУ СЕБЕ СИ — двойка т. 14/15, в"
            + " която лицето е осигурено НУЛА дни. При седем такива заявки записът щеше да изразходва"
            + " петте позиции на формата с нищо.");
  }

  @Test
  @DisplayName(
      "⚠️ болничният и платеният отпуск НЕ прекъсват — те се зачитат за стаж (чл. 9 ал. 2)")
  void creditedAbsencesDoNotBreakTheInsurance() {
    // Празен списък прекъсвания = точно каквото BgD1DaySource връща за фиш с болничен и отпуск:
    // техните видове носят countsAsInsuranceService = true.
    BgD1Record record = buildWithGaps(slipWithFullDooSet());

    assertNull(record.getT15Day1());
    assertNull(
        record.getT14Day2(),
        "⚠️ Прочетено като „отсъствието прекъсва осигуряването“, правилото щеше да нареже месеца на"
            + " всеки болничен и да произведе до пет двойки за напълно обикновен фиш. Разграничителят"
            + " е КСО чл. 10 ал. 2: прекъсват само периодите, които НЕ се зачитат за стаж.");
  }

  @Test
  @DisplayName("⚠️ повече от ПЕТ отрязъка е ЗАГУБА НА ДАННИ и се обявява като ГРЕШКА")
  void moreThanFiveSegmentsIsReported() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1RecordBuilder.build(
        slipWithFullDooSet(),
        insuredType("01", true),
        period(MAY_2026),
        new BgD1Facts(
            null,
            java.util.Arrays.asList(gap(3, 3), gap(6, 6), gap(9, 9), gap(12, 12), gap(15, 15)),
            null,
            null),
        findings);

    // Пет прекъсвания вътре в месеца дават ШЕСТ отрязъка — с един повече от полетата.
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T14_T15_MORE_THAN_FIVE".equals(f.getCode())),
        "⚠️ Форматът носи ПЕТ двойки. Премълчано, орязването дава декларация, която ИЗГЛЕЖДА пълна и"
            + " премълчава прекъсване — точно случаят, за който позициите съществуват. Находки: "
            + findings);
  }

  // ═══ ЕТАП 3 · т. 29 (БРЗ), т. 31, т. 34 ═══

  /** Начисление, което ФОРМИРА БРЗ по НСОРЗ чл. 16 ал. 2. */
  private static BgPaySlipLine wageLine(int number, String amount) {
    BgPaySlipLine l = earning(number, amount);
    l.setBgFormsGrossWage(Boolean.TRUE);
    return l;
  }

  /** Начисление, което Е облагаемо, но НЕ формира БРЗ — обезщетение или социален разход. */
  private static BgPaySlipLine nonWageLine(int number, String amount) {
    BgPaySlipLine l = earning(number, amount);
    l.setBgFormsGrossWage(Boolean.FALSE);
    return l;
  }

  /**
   * Фиш с ЧЕТИРИ вида начисление, всяко от които изпитва различен ръб:
   *
   * <ul>
   *   <li>100 основна 1500,00 — формира БРЗ, облагаема;
   *   <li>185 еднократен бонус 200,00 — формира БРЗ (чл. 16 ал. 2 т. 2 НЕ иска постоянен характер),
   *       облагаем;
   *   <li>900 обезщетение 800,00 — облагаемо, но НЕ работна заплата;
   *   <li>271 социален разход в натура 100,00 — облагаем (доход на лицето), НЕ работна заплата.
   * </ul>
   */
  private static BgPaySlip slipWithWages() {
    BgPaySlip slip = slipWithFullDooSet();
    List<BgPaySlipLine> lines = new ArrayList<>(slip.getBgPaySlipLineList());
    lines.removeIf(l -> l.getBgLineNumber() == 100);
    lines.add(wageLine(100, "1500.00"));
    lines.add(wageLine(185, "200.00"));
    lines.add(nonWageLine(900, "800.00"));
    lines.add(nonWageLine(271, "100.00"));
    slip.setBgPaySlipLineList(lines);
    return slip;
  }

  @Test
  @DisplayName(
      "т. 29 · САМО перата, които формират БРЗ — обезщетението и социалният разход остават вън")
  void point29CountsOnlyTheWageLines() {
    BgD1Record record = build(slipWithWages(), insuredType("01", true));

    assertEquals(
        0,
        new BigDecimal("1700.00").compareTo(record.getT29GrossWage()),
        "1500,00 основна + 200,00 еднократен бонус = 1700,00 по НСОРЗ чл. 16 ал. 2.\n"
            + "  ⚠️ 2600,00 би било с обезщетението (bgGrossTotal), а 2700,00 — и със социалния"
            + " разход. Точно затова т. 29 има СВОЙ агрегат: bgGrossTotal включва цялата група 9xx.");
    assertTrue(
        new BigDecimal("2600.00").compareTo(record.getT29GrossWage()) != 0,
        "обезщетението по чл. 222 КТ е начислено и облагаемо, но НЕ Е работна заплата — чл. 16 ал. 2"
            + " не го изброява сред седемте си съставки");
  }

  @Test
  @DisplayName(
      "т. 29 · ЕДНОКРАТНИЯТ бонус ВЛИЗА — постоянството е изискване на ЧЛ. 17, не на чл. 16")
  void point29IncludesOneOffPayments() {
    BgPaySlip slip = slipWithWages();
    // Същият фиш, но еднократният бонус е обявен за НЕпостоянен — както е в речника.
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgLineNumber() == 185) {
        l.setBgIsPermanentCharacter(Boolean.FALSE);
      }
    }
    assertEquals(
        0,
        new BigDecimal("1700.00").compareTo(build(slip, insuredType("01", true)).getT29GrossWage()),
        "⚠️ НАЙ-ЛЕСНАТА ГРЕШКА ТУК Е ПРЕИЗПОЛЗВАНЕ НА bgIsPermanentCharacter. Той служи на НСОРЗ"
            + " ЧЛ. 17 — базата за платен отпуск по чл. 177 и обезщетенията по чл. 228 КТ, — където"
            + " т. 3 иска ДТВ „които имат ПОСТОЯНЕН характер“. Чл. 16 ал. 2 т. 2 не иска такова"
            + " условие. Слети, флаговете биха дали 1500,00 — правдоподобно по-малко число.");
  }

  /** Вид осигурен, който ПОПЪЛВА т. 31 — код 01 е сред 30-те на указанието. */
  private static BgInsuredType fillsT31(String code) {
    BgInsuredType t = insuredType(code, true);
    t.setBgFillsT31(Boolean.TRUE);
    return t;
  }

  @Test
  @DisplayName("т. 31 · начисленият облагаем доход, ПРЕДИ приспадането на личните вноски")
  void point31StopsBeforeTheEmployeeContributions() {
    // ⚠️ ФЛАГЪТ СЕ ВДИГА ИЗРИЧНО, защото точката НЕ се попълва без него — без флага свидетелят би
    // сравнявал 2600,00 с ПРАЗНА точка (тоест с нула по пътя на генерирания getter) и би паднал,
    // обвинявайки агрегата вместо фикстурата.
    BgD1Record record = build(slipWithWages(), fillsT31("01"));

    assertEquals(
        0,
        new BigDecimal("2600.00").compareTo(record.getT31TaxableIncome()),
        "1500,00 + 200,00 + 800,00 обезщетение + 100,00 социален разход = 2600,00. ⚠️ ОБЛОЖЕНИЯТ"
            + " 2xx РЕД ВЛИЗА: указанието иска „всички други плащания в пари и/или в натура от"
            + " работодателя или за сметка на работодателя“, тоест филтър по група би го изпуснал.");
    assertTrue(
        new BigDecimal("1700.00").compareTo(record.getT31TaxableIncome()) != 0,
        "т. 31 НЕ е т. 29: брутната работна заплата изключва обезщетенията, облагаемият доход не");
    assertTrue(
        record
                .getT31TaxableIncome()
                .compareTo(BgPaySlipResolver.taxBase(slipWithWages().getBgPaySlipLineList()))
            != 0,
        "⚠️ И НЕ Е bgTaxBase. Чл. 42 ал. 2 ЗДДФЛ вади личните вноски (осемте DEBIT пера на фиша);"
            + " т. 31 спира преди тях. Двете числа са правдоподобни на един и същи ред.");
  }

  @Test
  @DisplayName("т. 31 · попълва се САМО за кодовете с флаг, макар доходът да е определим")
  void point31FollowsTheFlagNotTheData() {
    BgInsuredType fills = insuredType("01", true);
    fills.setBgFillsT31(Boolean.TRUE);
    assertTrue(
        build(slipWithWages(), fills).getT31TaxableIncome().signum() != 0,
        "код с вдигнат флаг носи числото");

    BgInsuredType doesNot = insuredType("13", false);
    doesNot.setBgFillsT31(Boolean.FALSE);
    // ⚠️ signum(), НЕ assertNull: колоната остава NULL в базата, но генерираният getter за
    // BigDecimal връща ZERO (BgDefaultedGetterTest). assertNull тук е МЪРТВО твърдение.
    assertEquals(
        0,
        build(slipWithWages(), doesNot).getT31TaxableIncome().signum(),
        "указанието изброява 30 кода поименно. Данните ГИ ИМА и точно затова решението е на флага —"
            + " попълнена за код извън списъка, точката твърди нещо за режима на лицето.");
  }

  /** Максималният месечен осигурителен доход за 01.01–31.07.2026 г. — ЗБДОО чл. 9 т. 1 б. „в“. */
  private static final BigDecimal CAP_MAY_2026 = new BigDecimal("2111.64");

  /** Записът с подаден таван — това е входът, който емитерът наистина ползва. */
  private static BgD1Record buildWithCap(
      BgPaySlip slip, BgInsuredType type, BigDecimal cap, List<BgD1FindingDto> findings) {
    return BgD1RecordBuilder.build(slip, type, period(MAY_2026), null, cap, findings);
  }

  @Test
  @DisplayName("т. 34 · нето по НПОПДОО чл. 32 = възнаграждение − лични вноски − данък")
  void point34IsTheNpopdooNet() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        buildWithCap(slipWithFullDooSet(), insuredType("01", true), CAP_MAY_2026, findings);

    // 2000,00 начислено (перо 100, признак „осигурителен доход“)
    // − 275,60 лични вноски (131,60 Пенсии + 28,00 ОЗМ + 8,00 Безработица + 64,00 здравно
    //                        + 44,00 УПФ — петте DEBIT пера от 5xx)
    // − 176,42 данък  =  1547,98
    assertEquals(
        0,
        new BigDecimal("1547.98").compareTo(record.getT34NetPay()),
        "⚠️ ТРИТЕ СЪСТАВКИ СА НА ЧЛ. 32, НЕ НА ФИША. Ал. 1: „изплатеното или начисленото месечно"
            + " възнаграждение се намали с определените със закон задължителни осигурителни вноски"
            + " ЗА СМЕТКА НА ЛИЦАТА и с данъците по ЗДДФЛ“. Ал. 4 казва кое е възнаграждението:"
            + " „брутното трудово възнаграждение и други допълнителни доходи от трудова дейност“."
            + " 2000,00 − 275,60 − 176,42 = 1547,98.");
    assertTrue(
        findings.stream().noneMatch(f -> f.getCode().startsWith("D1_T34_")),
        "напълно определима т. 34 не оставя находка. Находки: " + findings);
  }

  @Test
  @DisplayName("т. 34 · НЕ е bgNetTotal и НЕ е т. 31 — трите числа са различни на един и същи ред")
  void point34IsNeitherNetTotalNorTaxableIncome() {
    BgPaySlip slip = slipWithWages();
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = buildWithCap(slip, fillsT31("01"), CAP_MAY_2026, findings);

    // slipWithWages(): 1500 + 200 + 800 обезщетение + 100 социален разход = 2600,00 база
    // − 275,60 лични вноски − 176,42 данък = 2147,98 → НАД тавана 2111,64, тоест рязано.
    assertEquals(
        0,
        CAP_MAY_2026.compareTo(record.getT34NetPay()),
        "нето 2147,98 е над максималния осигурителен доход 2111,64 → точката е РЯЗАНА");
    assertTrue(
        record.getT34NetPay().compareTo(record.getT31TaxableIncome()) != 0,
        "⚠️ Т. 31 (2600,00) е НАЧИСЛЕНИЯТ ОБЛАГАЕМ ДОХОД — преди двете удръжки. Т. 34 е след тях."
            + " Двете стоят една до друга на образеца и всяка е правдоподобна на реда на другата.");
    assertTrue(
        record.getT34NetPay().compareTo(BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList()))
            != 0,
        "⚠️ И НЕ Е bgNetTotal: то е „нето за получаване“ СЛЕД заеми, издръжка и запор. Нетното по"
            + " НПОПДОО е базата, от която НОИ смята обезщетенията — слети, лице със запор би"
            + " получило по-малко обезщетение заради дълг към трето лице.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T34_CAPPED".equals(f.getCode())),
        "⚠️ Рязането се ОБЯВЯВА, макар числото да е вярно: чл. 6 ал. 16 КСО изключва тавана и няма"
            + " носител в модела, тоест находката е единственото място, където човек научава, че тук"
            + " е взето решение. Находки: "
            + findings);
  }

  @Test
  @DisplayName("т. 34 · таванът е върху НЕТОТО, а не върху базата — и разликата е измерима")
  void point34CapsTheNetNotTheBase() {
    // Наложен върху БАЗАТА, таванът дава 2111,64 − 275,60 − 176,42 = 1659,62; върху нетото —
    // 2111,64.
    BgD1Record record =
        buildWithCap(slipWithWages(), insuredType("01", true), CAP_MAY_2026, new ArrayList<>());
    assertEquals(
        0,
        CAP_MAY_2026.compareTo(record.getT34NetPay()),
        "⚠️ ПОДЛОГЪТ НА „НЕ МОЖЕ ДА БЪДЕ ПОВЕЧЕ“ В АЛ. 1 Е МЕСЕЧНОТО НЕТНО ВЪЗНАГРАЖДЕНИЕ, а не"
            + " възнаграждението, от което то се определя. Рязана базата, точката щеше да е 1659,62"
            + " — по-малко число, което изглежда точно толкова пресметнато.");
  }

  @Test
  @DisplayName("т. 34 · липсващ таван → точката остава ПРАЗНА, а не без горна граница")
  void point34RefusesToComputeWithoutTheCap() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = buildWithCap(slipWithFullDooSet(), insuredType("01", true), null, findings);

    assertEquals(
        0,
        record.getT34NetPay().signum(),
        "⚠️ БЕЗ ТАВАН СМЕТКАТА Е ВЯРНА ЗА ОБИКНОВЕНИЯ ФИШ И НЕВЯРНА ЗА ВИСОКОПЛАТЕНОТО ЛИЦЕ — а"
            + " двете изглеждат еднакво пресметнати. Затова липсващият параметър е ОТКАЗ, не"
            + " „без горна граница“.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T34_CAP_NOT_READ".equals(f.getCode())),
        "причината се НАЗОВАВА: параметърът MAX_INSURABLE_INCOME липсва за периода. Находки: "
            + findings);
  }

  @Test
  @DisplayName("т. 34 · неначислено възнаграждение е ДРУГА причина за същата празна точка")
  void point34DeclaresTheNotAccruedHypothesis() {
    BgPaySlip slip = slipWithFullDooSet();
    // Единственото начисление пада: остават само вноските и данъкът.
    List<BgPaySlipLine> lines = new ArrayList<>(slip.getBgPaySlipLineList());
    lines.removeIf(l -> l.getBgLineNumber() == 100);
    slip.setBgPaySlipLineList(lines);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = buildWithCap(slip, insuredType("01", true), CAP_MAY_2026, findings);

    assertEquals(0, record.getT34NetPay().signum());
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T34_NOT_ACCRUED".equals(f.getCode())),
        "⚠️ ЧЛ. 32 АЛ. 1 ИЗР. 2 ИМА СВОЙ ОТГОВОР: „Когато възнаграждението не е начислено, до"
            + " начисляването му за нетно възнаграждение се взема предвид минималният месечен"
            + " осигурителен доход по чл. 6 ал. 2 т. 3 КСО ИЛИ минималната месечна работна заплата"
            + " …“ Кое от двете зависи от ДЛЪЖНОСТТА — тоест това е решение с два правдоподобни"
            + " изхода и не се взима тук. Находки: "
            + findings);
    assertTrue(
        findings.stream().noneMatch(f -> "D1_T34_NEGATIVE".equals(f.getCode())),
        "⚠️ И НЕ СЕ ОБЯВЯВА КАТО ОТРИЦАТЕЛНО. Нула минус вноските и данъка Е отрицателно число, но"
            + " причината е ДРУГА и иска друго действие: да се впише минималният осигурителен доход,"
            + " а не да се проверява фишът. Находки: "
            + findings);
  }

  @Test
  @DisplayName("т. 34 · за 20-те кода с bgNoNetPay НЕ се полага — и находка НЯМА")
  void point34IsSilentForTheTwentyCodes() {
    BgInsuredType seafarer = insuredType("92", false);
    seafarer.setBgNoNetPay(Boolean.TRUE);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = buildWithCap(slipWithWages(), seafarer, CAP_MAY_2026, findings);

    assertEquals(
        0,
        record.getT34NetPay().signum(),
        "⚠️ signum(), НЕ assertNull: генерираният getter за BigDecimal връща ZERO за празна колона"
            + " (BgDefaultedGetterTest). Точката е ПРАЗНА, а не нула.");
    assertTrue(
        findings.stream().noneMatch(f -> f.getCode().startsWith("D1_T34_")),
        "⚠️ За морското лице точката НЕ СЕ ПОЛАГА по самата забележка към т. 34 — находка би пратила"
            + " човек да попълва нещо, което наредбата забранява. Флагът е верен за всичките 20 кода"
            + " (сверено машинно в BgD1FlagsAgainstNaredbaTest). Находки: "
            + findings);
  }

  // ═══ ЕТАП 2 · БАЗА-ДИСКРИМИНАТОРЪТ НА ЗДРАВНОТО ═══

  /** Здравно перо с ИЗРИЧНА база. Празната база се чете като нормалната (т. 22.1). */
  private static BgPaySlipLine healthLine(
      int number, String sign, String amount, String base, String ratePercent, String baseKind) {
    BgPaySlipLine l =
        line(number, sign, amount, ratePercent, fund("HEALTH", BgD1Point.T22_1_HEALTH));
    // ⚠️ КОЛИЧЕСТВОТО Е БАЗАТА за перата от 5xx — така го пише BgContributionLineService:
    // computed(code, contribution.getAmount(), contribution.getBase(), contribution.getRate()).
    // Оттам т. 17 и т. 27 четат сумите си, без да делят сума на ставка.
    l.setBgQuantity(new BigDecimal(base));
    l.setBgHealthBaseKind(baseKind);
    return l;
  }

  @Test
  @DisplayName("ТРИТЕ ЗДРАВНИ БАЗИ · всяка отива на СВОЯТА точка — 22.1, 18 и 28")
  void theThreeHealthBasesLandOnTheirOwnPoints() {
    BgPaySlip slip = slipWithFullDooSet();
    List<BgPaySlipLine> lines = new ArrayList<>(slip.getBgPaySlipLineList());

    // Перо върху ЗЗО чл. 40 ал. 1 т. 5: 502,78 × 4,80 % = 24,13 (златният фиш от 07.2026).
    lines.add(
        healthLine(
            516,
            BgPaySlipResolver.CREDIT,
            "24.13",
            "502.78",
            "4.80",
            BgD1ContributionAggregator.BASE_ART40_1_5));
    // Перо върху „доход само за здравно“: 310,10 × 8,00 % = 24,81 (неплатен отпуск по желание).
    lines.add(
        healthLine(
            517,
            BgPaySlipResolver.DEBIT,
            "24.81",
            "310.10",
            "8.00",
            BgD1ContributionAggregator.BASE_HEALTH_ONLY));
    slip.setBgPaySlipLineList(lines);

    BgD1Record record = build(slip, insuredType("01", true));

    // ⚠️ НОРМАЛНОТО ОСТАВА НЕПОКЪТНАТО. Това е половината от свидетелството: дискриминаторът трябва
    // да ИЗВАДИ специалните бази от т. 22.1, а не да разбърка нея.
    assertEquals(
        0,
        new BigDecimal("4.80").compareTo(record.getT221HealthRateEmployer()),
        "т. 22.1 А трябва да носи САМО нормалната здравна ставка. ⚠️ 9,60 би бил сборът ѝ с тази по"
            + " чл. 40 ал. 1 т. 5 — двете са 4,80 всяка и се събират в число, което изглежда като"
            + " удвоена ставка, тоест като друга категория осигурен.");
    assertEquals(
        0,
        new BigDecimal("3.20").compareTo(record.getT221HealthRateInsured()),
        "т. 22.1 Б също — 11,20 би бил сборът ѝ с 8,00 от неплатения отпуск");

    // Специалната база по чл. 40 ал. 1 т. 5 → т. 17 (сума) и т. 18 (ставка).
    assertEquals(
        0,
        new BigDecimal("502.78").compareTo(record.getT17Art4015Income()),
        "т. 17 е СУМАТА, и тя се чете от bgQuantity на перото. ⚠️ Обратната сметка"
            + " 24,13 × 100 / 4,80 дава 502,7083… — друго число, защото сумата вече е закръглена.");
    assertEquals(
        0,
        new BigDecimal("4.80").compareTo(record.getT18HealthRateOnT17()),
        "т. 18 е ПОЛОВИН вноска: ЗЗО чл. 40 ал. 1 т. 5 — „вноските са за сметка на работодателя … и"
            + " са РАВНИ НА ДЪЛЖИМАТА ОТ НЕГО ЧАСТ от вноската“. 8,00 тук би било цялата вноска.");

    // „Само здравно“ → т. 27 (сума) и т. 28 (ставка).
    assertEquals(
        0,
        new BigDecimal("310.10").compareTo(record.getT27HealthOnlyIncome()),
        "т. 27 е половината минимален осигурителен доход за СОЛ от 01.08.2026 г.");
    assertEquals(
        0,
        new BigDecimal("8.00").compareTo(record.getT28HealthRateOnT27()),
        "⚠️ Т. 28 Е ЦЯЛАТА ВНОСКА, НЕ ПОЛОВИНАТА, и точно тук е разликата с т. 18. ЗЗО чл. 40 ал. 1"
            + " т. 1 б. „б“: „вноската е ИЗЦЯЛО за сметка на …“ — платецът се сменя, вноската не се"
            + " дели. 3,20 (частта на лицето) би дало 9,92 вместо 24,81 — под една трета от"
            + " дължимото, с вид на пресметнато.");
  }

  @Test
  @DisplayName("празна база · чете се като НОРМАЛНАТА и остава в т. 22.1")
  void anEmptyBaseReadsAsTheStandardOne() {
    BgD1Record record = build(slipWithFullDooSet(), insuredType("01", true));

    assertEquals(
        0,
        new BigDecimal("4.80").compareTo(record.getT221HealthRateEmployer()),
        "фикстурата не слага bgHealthBaseKind на нито едно перо — всичките заварени пера са такива."
            + " Празното НЕ бива да падне в 18 или 28.");
    assertEquals(
        0,
        record.getT18HealthRateOnT17().signum(),
        "⚠️ Ако празното се четеше като „непозната база“, ставката щеше да изчезне от т. 22.1 и да не"
            + " се появи никъде — тоест здравната вноска на ВСЕКИ заварен фиш щеше да излезе от"
            + " декларацията, а числото пак щеше да изглежда наред.");
    assertEquals(0, record.getT27HealthOnlyIncome().signum());
    assertEquals(
        BgD1ContributionAggregator.BASE_STANDARD,
        BgD1ContributionAggregator.healthBaseOf(new BgPaySlipLine()),
        "прочитът е на ЕДНО място и е проверим директно");
  }

  @Test
  @DisplayName("базата НЕ се удвоява по платци · т. 17 е ЕДНА сума, не сбор от две пера")
  void theSpecialBaseIsNotDoubledAcrossPayers() {
    BgPaySlip slip = slipWithFullDooSet();
    List<BgPaySlipLine> lines = new ArrayList<>(slip.getBgPaySlipLineList());
    // Дефект в данните: две пера на ЕДНА и съща специална база, по едно за всеки платец.
    lines.add(
        healthLine(
            516,
            BgPaySlipResolver.CREDIT,
            "24.13",
            "502.78",
            "4.80",
            BgD1ContributionAggregator.BASE_ART40_1_5));
    lines.add(
        healthLine(
            517,
            BgPaySlipResolver.DEBIT,
            "16.09",
            "502.78",
            "3.20",
            BgD1ContributionAggregator.BASE_ART40_1_5));
    slip.setBgPaySlipLineList(lines);

    BgD1Record record = build(slip, insuredType("01", true));
    assertEquals(
        0,
        new BigDecimal("502.78").compareTo(record.getT17Art4015Income()),
        "⚠️ 1005,56 е числото, което сумиране по платци би дало — двойно по-висок деклариран доход,"
            + " който изглежда като лице с две заплати. Т. 17 е ЕДНА сума: ЗЗО чл. 40 ал. 1 т. 5"
            + " дава вноската само на работодателя, тоест по норма съществува ЕДНО перо.");
  }

  // ═══ ЕТАП 1 · т. 16 и кофите ѝ ═══

  /** Фиш с дни: 21 работни в периода, 3 болнични от работодателя, 2 от ДОО → 16 отработени. */
  private static BgPaySlip slipWithDays() {
    BgPaySlip slip = slipWithFullDooSet();
    slip.setBgWorkingDaysInPeriod(21);
    // ⚠️ bgWorkedDays вече Е намален от болничния енджин — 21 − 3 − 2 = 16. Фикстурата повтаря
    // производственото състояние, а не удобно число: точно това намаляване прави т. 16.1 остатък.
    slip.setBgWorkedDays(16);
    slip.setBgSickDaysEmployer(3);
    slip.setBgSickDaysDoo(2);
    slip.getEmploymentContract().setBgHoursPerDay(new BigDecimal("8.00"));
    return slip;
  }

  private static BgD1Record buildWithDays(
      BgPaySlip slip,
      BgInsuredType type,
      java.util.Map<String, Integer> buckets,
      List<BgD1FindingDto> findings) {
    return BgD1RecordBuilder.build(slip, type, period(MAY_2026), buckets, findings);
  }

  /** Както горното, но и с ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО — изваждаемото по КСО чл. 10 ал. 2. */
  private static BgD1Record buildWithDays(
      BgPaySlip slip,
      BgInsuredType type,
      java.util.Map<String, Integer> buckets,
      int outsideInsuranceDays,
      List<BgD1FindingDto> findings) {
    return BgD1RecordBuilder.build(
        slip,
        type,
        period(MAY_2026),
        new BgD1Facts(buckets, null, null, null, null, outsideInsuranceDays),
        findings);
  }

  @Test
  @DisplayName("т. 16 · сборът по заб. 1 е сборът на кофите, а т. 16.1 е ОСТАТЪКЪТ")
  void point16TotalIsTheSumAndPoint16_1IsTheRemainder() {
    java.util.Map<String, Integer> buckets =
        BgD1DayBuckets.sum(
            java.util.Arrays.asList(
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_A, 3),
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_2, 2),
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_5, 4)));

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = buildWithDays(slipWithDays(), insuredType("01", true), buckets, findings);

    assertEquals(
        "12",
        record.getT161WorkedDays(),
        "т. 16.1 = отработени 16 минус неплатения отпуск 4 (т. 16.5) = 12. ⚠️ Болничните НЕ се вадят"
            + " втори път: bgWorkedDays вече е намален от тях, тоест изваждане би отчело едни и същи"
            + " дни два пъти НАДОЛУ и би дало 7.");
    assertEquals("3", record.getT16aEmployerSickDays(), "чл. 40 ал. 5 КСО → т. 16.А");
    assertEquals("2", record.getT162SickDays(), "ВНР от ДОО → т. 16.2");
    assertEquals("4", record.getT165UnpaidLeaveDays(), "чл. 9 ал. 2 т. 3 КСО → т. 16.5");
    assertEquals(
        "21",
        record.getT16Days(),
        "заб. 1: позиции 1 и 2 са СБОРЪТ от т. 16.1 до 16.5 и т. 16.А — 12 + 2 + 4 + 3 = 21, тоест"
            + " точно работните дни на месеца. Записан независимо, общият брой може да се размине със"
            + " съставките си, а НАП сверява точно това равенство.");
  }

  @Test
  @DisplayName(
      "⚠️ GOLDEN · т. 16.1 вади и ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО — 12 → 9, а сборът т. 16 · 21 → 18")
  void point16_1AlsoSubtractsTheDaysOutsideInsurance() {
    java.util.Map<String, Integer> buckets =
        BgD1DayBuckets.sum(
            java.util.Arrays.asList(
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_A, 3),
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_2, 2),
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_5, 4)));

    List<BgD1FindingDto> findings = new ArrayList<>();
    // Същият фиш като горния свидетел, плюс ТРИ дни самоотлъчка (awol — празен d1Code,
    // countsAsInsuranceService = false).
    BgD1Record record =
        buildWithDays(slipWithDays(), insuredType("01", true), buckets, 3, findings);

    assertEquals(
        "9",
        record.getT161WorkedDays(),
        "⚠️ т. 16.1 = 16 отработени − 4 неплатен (т. 16.5) − 3 извън осигуряването = 9. Без"
            + " четвъртия изваждаем числото е 12 — тоест декларацията обявява три дни в"
            + " осигуряване, за които няма нито труд, нито възнаграждение, нито вноска. Указанието"
            + " иска в т. 16.1 „дните с осигурителни вноски“, а КСО чл. 10 ал. 2 казва, че през тези"
            + " периоди осигуряването СЕ ПРЕКЪСВА.");
    assertEquals(
        "18",
        record.getT16Days(),
        "⚠️ И СБОРЪТ ПАДА САМ — 9 + 2 + 4 + 3 = 18. Изваждаемото НЕ е седма кофа: вписано като кофа,"
            + " то щеше да влезе в сбора по заб. 1 и да върне същите три дни обратно в „дни в"
            + " осигуряване — общо“. Преди клона тук стоеше 21, тоест пълните работни дни на месеца"
            + " за човек, който три от тях е бил в самоотлъчка.");
    assertEquals("4", record.getT165UnpaidLeaveDays(), "кофата 16.5 НЕ се пипа — тя си е точка");
    assertEquals("3", record.getT16aEmployerSickDays(), "нито 16.А");
    assertEquals("2", record.getT162SickDays(), "нито 16.2");
    assertTrue(
        findings.stream().noneMatch(f -> "D1_T161_NEGATIVE_REMAINDER".equals(f.getCode())),
        "остатъкът е положителен — няма какво да се обявява: " + findings);
  }

  @Test
  @DisplayName("⚠️ Отрицателен брой дни извън осигуряването се чете като НУЛА, не вдига т. 16.1")
  void anegativeOutsideCountNeverRaisesPoint16_1() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        buildWithDays(
            slipWithDays(), insuredType("01", true), BgD1DayBuckets.sum(null), -5, findings);

    assertEquals(
        "16",
        record.getT161WorkedDays(),
        "⚠️ Сгрешен знак нагоре по веригата би УВЕЛИЧИЛ т. 16.1 до 21 — тоест би обявил повече"
            + " осигурени дни, отколкото фишът има отработени. Същият довод като в"
            + " BgEarningsResolver#carvedDays.");
  }

  @Test
  @DisplayName("⚠️ Без такива дни числата са БИТ ПО БИТ както преди клона")
  void withoutSuchAbsencesNothingMoves() {
    java.util.Map<String, Integer> buckets =
        BgD1DayBuckets.sum(
            java.util.Arrays.asList(
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_A, 3),
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_2, 2),
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_5, 4)));

    BgD1Record withZero =
        buildWithDays(slipWithDays(), insuredType("01", true), buckets, 0, new ArrayList<>());
    BgD1Record legacy =
        buildWithDays(slipWithDays(), insuredType("01", true), buckets, new ArrayList<>());

    assertEquals("12", legacy.getT161WorkedDays(), "старият вход (без изваждаемо) дава 12");
    assertEquals(
        legacy.getT161WorkedDays(),
        withZero.getT161WorkedDays(),
        "и нулевото изваждаемо дава СЪЩОТО — фиш без такова отсъствие не мърда");
    assertEquals(legacy.getT16Days(), withZero.getT16Days(), "нито сборът");
  }

  @Test
  @DisplayName("т. 16.1 · отрицателният остатък дава НУЛА и НАХОДКА, не изгладено число")
  void aNegativeRemainderIsClampedAndDeclared() {
    java.util.Map<String, Integer> buckets =
        BgD1DayBuckets.sum(
            java.util.Collections.singletonList(
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_5, 20)));

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = buildWithDays(slipWithDays(), insuredType("01", true), buckets, findings);

    assertEquals(
        "0",
        record.getT161WorkedDays(),
        "отработени 16 минус неплатен 20 = −4. Отрицателен брой дни не съществува, тоест числото се"
            + " клампва — нула дни Е валиден отговор за НАП.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T161_NEGATIVE_REMAINDER".equals(f.getCode())),
        "⚠️ НО КОНФЛИКТЪТ СЕ ОБЯВЯВА. Изгладен тихо, той дава т. 16.1 = 0 за човек, който е работил"
            + " — най-вероятната му причина е, че ТРЗ вече е приспаднал отпуска на ръка от"
            + " отработените дни и той се вади втори път. Находки: "
            + findings);
  }

  @Test
  @DisplayName("т. 16 · КОД 90 не попълва НИЩО от нея — заб. 3, проверена ПРЕДИ всяко число")
  void code90FillsNothingOfPoint16() {
    java.util.Map<String, Integer> buckets =
        BgD1DayBuckets.sum(
            java.util.Collections.singletonList(
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_2, 5)));

    // ⚠️ КОДЪТ СЕ ЧЕТЕ ОТ ДОГОВОРА, НЕ ОТ РЕДА В РЕЧНИКА: свидетел, който сменя само
    // BgInsuredType и оставя договора с код 01, проверява правило, което не се задейства. Двата
    // източника трябва да СЪВПАДАТ — точно както в
    // производството, където BgD1Emitter търси реда ПО кода от договора.
    BgPaySlip taxOnly = slipWithDays();
    taxOnly.getEmploymentContract().setBgInsuredTypeCode("90");
    BgD1Record record =
        buildWithDays(taxOnly, insuredType("90", false), buckets, new ArrayList<>());

    assertNull(
        record.getT16Days(),
        "заб. 3 дословно: „Когато в т. 12 Вид осигурен е попълнен код 90, т. 16 НЕ се попълва.“\n"
            + "  ⚠️ И ЗАТОВА ПОЛЕТАТА СА НИЗОВЕ: при Integer колона този assertNull е МЪРТЪВ —"
            + " генерираният getter връща 0, а „0000“ е ПОПЪЛНЕНА точка, която твърди нула дни в"
            + " осигуряване.");
    assertNull(record.getT162SickDays(), "и кофите ѝ остават празни");
    assertNull(
        record.getT16ContractedHours(),
        "проверката е ПЪРВА: попълнена и после изчистена, точката би оставила кофите си пълни");
  }

  @Test
  @DisplayName(
      "т. 16 поз. 4 · договореното РВ се закръгля по указанието — остатък ≥ половин час нагоре")
  void contractedHoursRoundHalfUp() {
    BgPaySlip slip = slipWithDays();
    slip.getEmploymentContract().setBgHoursPerDay(new BigDecimal("7.50"));
    BgD1Record record =
        buildWithDays(slip, insuredType("01", true), BgD1DayBuckets.sum(null), new ArrayList<>());
    assertEquals(
        "8",
        record.getT16ContractedHours(),
        "„когато при изчисляването се получи остатък, равен или по-голям от половин час, се закръглява"
            + " на пълен час“ — тоест HALF_UP, а не отсичане. Отсичането би дало 7.");

    slip.getEmploymentContract().setBgHoursPerDay(new BigDecimal("7.40"));
    assertEquals(
        "7",
        buildWithDays(slip, insuredType("01", true), BgD1DayBuckets.sum(null), new ArrayList<>())
            .getT16ContractedHours(),
        "остатък под половин час остава надолу");
  }

  @Test
  @DisplayName("т. 16 поз. 3/4 · 17-те кода с bgZeroWorkTime попълват НУЛА — и това е СТОЙНОСТ")
  void zeroWorkTimeCodesFillZeroNotEmpty() {
    BgInsuredType type = insuredType("10", false);
    type.setBgZeroWorkTime(Boolean.TRUE);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = buildWithDays(slipWithDays(), type, BgD1DayBuckets.sum(null), findings);

    assertEquals(
        "0",
        record.getT16LegalHours(),
        "„лицата с вид осигурен 9, 10, 12 … 96 попълват 0“ — НУЛАТА Е ТВЪРДЕНИЕ, не липса");
    assertEquals("0", record.getT16ContractedHours());
    assertTrue(
        findings.stream().noneMatch(f -> "D1_T16_LEGAL_WORKTIME_MISSING".equals(f.getCode())),
        "за тези кодове нулата Е отговорът — находка „липсва носител“ би пратила човек да попълва"
            + " нещо, което наредбата иска нулево. Находки: "
            + findings);
  }

  @Test
  @DisplayName("т. 16 поз. 3 и т. 16.7/16.8 · липсващият носител се ОБЯВЯВА, не се пълни с нула")
  void theMissingCarriersAreDeclared() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        buildWithDays(slipWithDays(), insuredType("01", true), BgD1DayBuckets.sum(null), findings);

    assertNull(
        record.getT16LegalHours(),
        "законоустановеното РВ е свойство на ДЛЪЖНОСТТА и няма носител. ⚠️ НЕ Е копие на позиция 4:"
            + " съвпадат при пълен щат, разминават се при чл. 137 КТ (7 или 6 часа) и чл. 138.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T16_LEGAL_WORKTIME_MISSING".equals(f.getCode())),
        "находки: " + findings);
    assertNull(
        record.getT167WorkedHours(),
        "⚠️ Нула НЕ се записва: „нула отработени часа“ е твърдение, което НАП отхвърля за работещо"
            + " лице. Фишът замразява ДНИ, не ЧАСОВЕ.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T167_T168_HOURS_MISSING".equals(f.getCode())),
        "находки: " + findings);
  }

  @Test
  @DisplayName("т. 16 · сбор над работните дни на месеца е НАХОДКА — двойно преброено отсъствие")
  void aTotalAbovoThePeriodIsFlagged() {
    java.util.Map<String, Integer> buckets =
        BgD1DayBuckets.sum(
            java.util.Arrays.asList(
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_2, 10),
                new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_A, 10)));

    List<BgD1FindingDto> findings = new ArrayList<>();
    buildWithDays(slipWithDays(), insuredType("01", true), buckets, findings);

    assertTrue(
        findings.stream().anyMatch(f -> "D1_T16_EXCEEDS_PERIOD".equals(f.getCode())),
        "16 отработени + 10 + 10 = 36 дни в месец с 21 работни. Най-вероятно едно отсъствие е"
            + " преброено в две кофи, или заявка за отпуск се припокрива с болничен епизод."
            + " Находки: "
            + findings);
  }

  @Test
  @DisplayName("дните НЕ са четени (null кофи) · т. 16 остава празна и НЕ ражда находки")
  void unreadDaysLeavePoint16AloneWithoutFindings() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        BgD1RecordBuilder.build(
            slipWithDays(), insuredType("01", true), period(MAY_2026), findings);

    assertNull(
        record.getT16Days(),
        "⚠️ null кофи значи „дните НЕ са четени“, а не „нула дни“. И двете дават празна т. 16, но"
            + " първото е състояние на извикването, второто — твърдение за лицето.");
    assertTrue(
        findings.stream().noneMatch(f -> f.getCode().startsWith("D1_T16")),
        "краткият вход е за свидетелите на ОСТАНАЛИТЕ точки — те имат право да не знаят за дните, а"
            + " находка за липсващи часове би ги заляла. Находки: "
            + findings);
  }

  @Test
  @DisplayName("ФИЛТЪР НА КОРЕКЦИЯТА · делта-ред за март НЕ влиза в майския запис")
  void theMarchDeltaLineStaysOutOfTheMayRecord() {
    BgPaySlip slip = slipWithFullDooSet();

    // Доплащане за МАРТ, сляло се в майския фиш: носи и доход, и вноска.
    BgPaySlipLine deltaEarning = earning(190, "500.00");
    deltaEarning.setBgCorrectsPeriod(period(MARCH_2026));
    BgPaySlipLine deltaContribution =
        line(500, BgPaySlipResolver.CREDIT, "40.75", "8.22", fund("PENSION", BgD1Point.T22_DOO));
    deltaContribution.setBgCorrectsPeriod(period(MARCH_2026));

    List<BgPaySlipLine> lines = new ArrayList<>(slip.getBgPaySlipLineList());
    lines.add(deltaEarning);
    lines.add(deltaContribution);
    slip.setBgPaySlipLineList(lines);

    BgD1Record record = build(slip, insuredType("01", true));

    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(record.getT21InsuranceIncome()),
        "т. 21 на МАЙ не бива да порасне с мартенското доплащане: осигурителният доход за март"
            + " вече е деклариран с коригиращата Д1 ЗА МАРТ. Броен и тук, същият доход се осигурява"
            + " втори път. 2500.00 е числото, което грешката би дала.");
    assertEquals(
        0,
        new BigDecimal("10.92").compareTo(record.getT22DooRateEmployer()),
        "⚠️ ПРИ СТАВКИТЕ ЕФЕКТЪТ Е ПО-ОСТЪР, ОТКОЛКОТО ПРИ СУМИТЕ. Мартенската делта носи СЪЩАТА"
            + " ставка 8,22 за същия фонд, тоест несъобразен, филтърът би дал 19,14 (10,92 + 8,22)"
            + " — двойна ставка за фонд „Пенсии“, число, което не е ничия ставка. Сумите поне се"
            + " различаваха една от друга; ставките съвпадат и грешката изглежда като по-висока"
            + " категория труд.");
  }
}
