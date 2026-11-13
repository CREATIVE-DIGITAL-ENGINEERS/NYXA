package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Т. 21 — <b>ОСИГУРИТЕЛНИЯТ ДОХОД Е КАПНАТ, и това е реквизит на закона, не закръгляне</b>.
 *
 * <p><b>УКАЗАНИЕ Т. 21, ДОСЛОВНО:</b> „Осигурителен доход, върху който СЕ ДЪЛЖАТ осигурителни
 * вноски, включително сумата по чл. 40, ал. 5 от КСО — попълва се доходът съгласно чл. 6, ал. 2 и
 * 12 от КСО, включително сумата по чл. 40, ал. 5 от КСО.“
 *
 * <p><b>КСО ЧЛ. 6 АЛ. 3, ДОСЛОВНО:</b> „Осигурителните вноски за работниците и служителите … се
 * дължат върху получените, включително начислените и неизплатените, брутни месечни възнаграждения …
 * но върху НЕ ПО-МАЛКО от минималния осигурителен доход по ал. 2, т. 3 … и НЕ ПОВЕЧЕ ОТ МАКСИМАЛНИЯ
 * месечен размер на осигурителния доход.“
 *
 * <p>Тоест т. 21 НЕ е сборът на начисленията — тя е базата, върху която вноските НАИСТИНА са
 * начислени, с наложен под (МОД) и таван.
 *
 * <p><b>⚠️ ЗАЩО ЧИСЛОТО НЕ Е КОЗМЕТИЧНО.</b> НАП сверява т. 21 × ставката от т. 22 срещу левовите
 * суми в Д6. При некапната т. 21 за лице над тавана произведението е 327,60, а действителната
 * вноска — 230,59: разлика от 97,01 лв. на едно лице за един месец, и на реда, който помирява ДВЕ
 * декларации. Всяко от двете числа изглежда законно само за себе си.
 *
 * <p><b>⚠️ И ЗАЩО Т. 34 НЕ СЕ ПИПА.</b> Т. 21 иска „доходът, ВЪРХУ КОЙТО СЕ ДЪЛЖАТ вноски“
 * (капнат); т. 34 иска „изплатеното или начисленото месечно ВЪЗНАГРАЖДЕНИЕ“ по НПОПДОО чл. 32 ал.
 * 4, което таван НЯМА — там таванът се налага върху РЕЗУЛТАТА. За високодоходно лице двете точки
 * ЗАКОННО се различават и това е закрепено по-долу.
 */
class BgD1Point21CapTest {

  private static final LocalDate MAY_2026 = LocalDate.of(2026, 5, 1);

  /** Максималният месечен осигурителен доход, 01.01–31.07.2026 — ЗБДОО чл. 9 т. 1 б. „в“. */
  private static final BigDecimal CAP = new BigDecimal("2111.64");

  /** Начисленото възнаграждение — НАД тавана. */
  private static final BigDecimal GROSS = new BigDecimal("3000.00");

  private static Period period() {
    Period p = new Period();
    p.setFromDate(MAY_2026);
    p.setToDate(MAY_2026.withDayOfMonth(31));
    return p;
  }

  private static BgContributionFund fund(String code, String point) {
    BgContributionFund f = new BgContributionFund();
    f.setCode(code);
    f.setBgD1Field(point);
    return f;
  }

  /**
   * Перо на ВНОСКА, каквото производството наистина създава.
   *
   * <p><b>⚠️ {@code bgQuantity} Е БАЗАТА, и точно това прави фикстурата производствена.</b> {@code
   * BgContributionLineService#upsert} пише три неща на всяко перо от 5xx: {@code
   * setBgAmount(amount)}, {@code setBgQuantity(base)} и {@code setBgRate(ratePercent)} — а базата е
   * {@code BgInsurableIncomeResult#getAmount()}, тоест ВЕЧЕ капната и с наложен МОД под. Фикстура
   * без количество описва фиш, който този код не може да произведе.
   */
  private static BgPaySlipLine contribution(
      int number, String sign, String amount, String ratePercent, BgContributionFund f) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgQuantity(CAP);
    l.setBgRate(new BigDecimal(ratePercent));
    l.setBgContributionFund(f);
    l.setBgIsInsuranceIncome(Boolean.FALSE);
    l.setBgIsTaxable(Boolean.FALSE);
    return l;
  }

  private static BgPaySlipLine earning(int number, String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(BgPaySlipResolver.CREDIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsInsuranceIncome(Boolean.TRUE);
    l.setBgIsTaxable(Boolean.TRUE);
    return l;
  }

  private static EmploymentContract contract() {
    Partner companyPartner = new Partner();
    companyPartner.setName("Никса ЕООД");
    companyPartner.setRegistrationCode("831919536");

    Company company = new Company();
    company.setPartner(companyPartner);

    Partner person = new Partner();
    person.setName("Петров");
    person.setFirstName("Иван");

    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber("7501014567");

    EmploymentContract c = new EmploymentContract();
    c.setStartDate(LocalDate.of(2020, 1, 15));
    c.setBgInsuredTypeCode("01");
    c.setPayCompany(company);
    c.setEmployee(employee);
    return c;
  }

  /**
   * ФИШ НАД ТАВАНА · начислено 3000,00, вноски върху 2111,64.
   *
   * <p>Ставките са за вид осигурен 01 през 2026 г., а сумите са ставка × 2111,64, закръглени
   * ПООТДЕЛНО по фонд и по платец (§ 2 ал. 2).
   */
  private static BgPaySlip slipOverTheCap() {
    BgContributionFund pension = fund("PENSION", BgD1Point.T22_DOO);
    BgContributionFund ozm = fund("OZM", BgD1Point.T22_DOO);
    BgContributionFund unemployment = fund("UNEMPLOYMENT", BgD1Point.T22_DOO);
    BgContributionFund tzpb = fund("TZPB", BgD1Point.T22_2_TZPB);
    BgContributionFund health = fund("HEALTH", BgD1Point.T22_1_HEALTH);
    BgContributionFund upf = fund("UPF", BgD1Point.T26_UPF);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(earning(100, "3000.00"));

    // ДОО без ТЗПБ · осигурител 10,92 % = 8,22 + 2,10 + 0,60 · лице 8,38 % = 6,58 + 1,40 + 0,40
    lines.add(contribution(500, BgPaySlipResolver.CREDIT, "173.58", "8.22", pension));
    lines.add(contribution(501, BgPaySlipResolver.DEBIT, "138.95", "6.58", pension));
    lines.add(contribution(504, BgPaySlipResolver.CREDIT, "44.34", "2.10", ozm));
    lines.add(contribution(505, BgPaySlipResolver.DEBIT, "29.56", "1.40", ozm));
    lines.add(contribution(506, BgPaySlipResolver.CREDIT, "12.67", "0.60", unemployment));
    lines.add(contribution(507, BgPaySlipResolver.DEBIT, "8.45", "0.40", unemployment));

    lines.add(contribution(508, BgPaySlipResolver.CREDIT, "9.92", "0.47", tzpb));
    lines.add(contribution(516, BgPaySlipResolver.CREDIT, "101.36", "4.80", health));
    lines.add(contribution(517, BgPaySlipResolver.DEBIT, "67.57", "3.20", health));
    lines.add(contribution(512, BgPaySlipResolver.CREDIT, "59.13", "2.80", upf));
    lines.add(contribution(513, BgPaySlipResolver.DEBIT, "46.46", "2.20", upf));

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-05-9001");
    slip.setBgPaySlipLineList(lines);
    slip.setBgTaxAmount(new BigDecimal("270.90"));
    slip.setEmploymentContract(contract());
    return slip;
  }

  private static BgInsuredType insuredType() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("01");
    t.setBgAppliesMod(Boolean.TRUE);
    return t;
  }

  private static BgD1Record build(BgPaySlip slip) {
    return BgD1RecordBuilder.build(slip, insuredType(), period(), new ArrayList<>());
  }

  // ═══ САМОТО ТВЪРДЕНИЕ ═════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ т. 21 · за лице НАД тавана е 2111,64, а НЕ 3000,00")
  void point21IsCappedNotTheSumOfEarnings() {
    BgD1Record record = build(slipOverTheCap());

    assertEquals(
        0,
        CAP.compareTo(record.getT21InsuranceIncome()),
        "⚠️ УКАЗАНИЕ Т. 21 ИСКА ДОХОДА, „ВЪРХУ КОЙТО СЕ ДЪЛЖАТ“ вноски, а КСО чл. 6 ал. 3 казва"
            + " „не повече от максималния месечен размер на осигурителния доход“. Тоест т. 21 е"
            + " БАЗАТА НА ВНОСКИТЕ, а не сборът на начисленията.");
    assertTrue(
        GROSS.compareTo(record.getT21InsuranceIncome()) != 0,
        "⚠️ 3000,00 Е ЗАКОВАНО КАТО ОТХВЪРЛЕНО. То е сборът на перата (insuranceIncomeBase), тоест"
            + " начисленото възнаграждение — вярното число за т. 34, невярното за т. 21. Двете точки"
            + " стоят на един и същи запис и разликата между тях е невидима от самите числа.");
  }

  @Test
  @DisplayName("⚠️ РЕКОНСИЛИАЦИЯ · т. 21 × т. 22 = действителната вноска, точно до стотинката")
  void point21TimesTheRateEqualsTheRealContribution() {
    BgPaySlip slip = slipOverTheCap();
    BgD1Record record = build(slip);

    // Действителните суми от перата, по платец.
    BigDecimal employerDoo =
        new BigDecimal("173.58").add(new BigDecimal("44.34")).add(new BigDecimal("12.67"));
    BigDecimal insuredDoo =
        new BigDecimal("138.95").add(new BigDecimal("29.56")).add(new BigDecimal("8.45"));
    assertEquals(0, new BigDecimal("230.59").compareTo(employerDoo), "фикстурата е сверена");
    assertEquals(0, new BigDecimal("176.96").compareTo(insuredDoo), "фикстурата е сверена");

    assertEquals(
        0,
        employerDoo.compareTo(
            rateOn(record.getT21InsuranceIncome(), record.getT22DooRateEmployer())),
        "т. 21 × т. 22 А (10,92 %) = 230,59 = сборът на перата на ОСИГУРИТЕЛЯ за фондовете на ДОО");
    assertEquals(
        0,
        insuredDoo.compareTo(rateOn(record.getT21InsuranceIncome(), record.getT22DooRateInsured())),
        "т. 21 × т. 22 Б (8,38 %) = 176,96 = сборът на перата на ЛИЦЕТО");

    assertEquals(
        0,
        new BigDecimal("327.60").compareTo(rateOn(GROSS, record.getT22DooRateEmployer())),
        "⚠️ И НЕПОМИРЕНОТО ЧИСЛО Е ЗАКОВАНО: некапната т. 21 (3000,00) × 10,92 % дава 327,60 срещу"
            + " действителни 230,59 — разлика 97,01 лв. на едно лице за един месец. Тя се вижда САМО"
            + " ако някой умножи, а НАП умножава.");
  }

  @Test
  @DisplayName("РЕКОНСИЛИАЦИЯ Д1 ↔ Д6 · т. 21 × ставките = сумата в Д6 т. 10 (ВКЛ. ТЗПБ)")
  void theD1TimesRateReconcilesWithD6() {
    BgPaySlip slip = slipOverTheCap();
    BgD1Record record = build(slip);

    BgD6Declaration d6 = new BgD6Declaration();
    Partner partner = new Partner();
    partner.setRegistrationCode("831919536");
    partner.setName("Никса ЕООД");
    Company company = new Company();
    company.setPartner(partner);
    d6.setCompany(company);
    new BgD6Emitter().emit(d6, Collections.singletonList(slip));

    // Д6 т. 10 = фондовете на ДОО ВКЛЮЧИТЕЛНО ТЗПБ, и двамата платци.
    BigDecimal viaD1 =
        rateOn(record.getT21InsuranceIncome(), record.getT22DooRateEmployer())
            .add(rateOn(record.getT21InsuranceIncome(), record.getT22DooRateInsured()))
            .add(rateOn(record.getT21InsuranceIncome(), record.getT222TzpbRate()));

    assertEquals(
        0,
        new BigDecimal("417.47").compareTo(d6.getT10Doo()),
        "Д6 т. 10 = 230,59 осигурител + 176,96 лице + 9,92 ТЗПБ = 417,47");
    assertEquals(
        0,
        d6.getT10Doo().compareTo(viaD1),
        "⚠️ ТОВА Е САМАТА РЕКОНСИЛИАЦИЯ ПО ЧЛ. 2 АЛ. 4: базата и ставките от Д1 трябва да дадат"
            + " левовете от Д6. С НЕКАПНАТА т. 21 лявата страна е 3000,00 × (10,92 + 8,38 + 0,47) ="
            + " 593,10 срещу 417,47 — разлика 175,63, и декларацията се отхвърля.");
  }

  @Test
  @DisplayName("⚠️ т. 21 и т. 34 ЗАКОННО се различават при висок доход — и това е нарочно")
  void point21AndPoint34LegitimatelyDiffer() {
    BgPaySlip slip = slipOverTheCap();
    BgD1Record record = build(slip);

    assertEquals(
        0, CAP.compareTo(record.getT21InsuranceIncome()), "т. 21 е капната (КСО чл. 6 ал. 3)");
    assertEquals(
        0,
        GROSS.compareTo(BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList())),
        "⚠️ А СБОРЪТ НА ПЕРАТА ОСТАВА 3000,00 И НЕ СЕ ПИПА: точно него ползва т. 34, защото НПОПДОО"
            + " чл. 32 ал. 4 определя базата ѝ като „изплатеното или начисленото месечно"
            + " ВЪЗНАГРАЖДЕНИЕ“ — без таван. Слети в едно число, двете точки биха се счупили една"
            + " заради друга.");
  }

  @Test
  @DisplayName("нормален доход ПОД тавана · т. 21 остава непроменена")
  void belowTheCapNothingChanges() {
    // Същият фиш, но базата на вноските Е сборът на начисленията — тоест таван не се е налагал.
    BgPaySlip slip = slipOverTheCap();
    List<BgPaySlipLine> lines = new ArrayList<>();
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgLineNumber() == 100) {
        lines.add(earning(100, "2000.00"));
      } else {
        l.setBgQuantity(new BigDecimal("2000.00"));
        lines.add(l);
      }
    }
    slip.setBgPaySlipLineList(lines);

    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(build(slip).getT21InsuranceIncome()),
        "под тавана базата на вноските и сборът на перата СЪВПАДАТ — тоест таванът не мени нищо"
            + " за обикновения фиш");
  }

  @Test
  @DisplayName("чл. 40 ал. 5 КСО · болничният от осигурителя ВЛИЗА в базата на т. 21")
  void theEmployerSickPayEntersPoint21() {
    // Перо 195 „Болничен от осигурител (чл. 40 ал. 5 КСО)“ носи bgIsInsuranceIncome = true в
    // речника, тоест влиза в брутото, което услугата подава на BgInsurableIncomeService — и оттам в
    // БАЗАТА на всяко перо от 5xx. Тук базата е 2200,00 при начисления 2000 + 200.
    BgPaySlip slip = slipOverTheCap();
    List<BgPaySlipLine> lines = new ArrayList<>();
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgLineNumber() == 100) {
        lines.add(earning(100, "2000.00"));
        lines.add(earning(195, "200.00"));
      } else {
        l.setBgQuantity(new BigDecimal("2200.00"));
        lines.add(l);
      }
    }
    slip.setBgPaySlipLineList(lines);

    assertEquals(
        0,
        new BigDecimal("2200.00").compareTo(build(slip).getT21InsuranceIncome()),
        "⚠️ УКАЗАНИЕТО ГО ИСКА ИЗРИЧНО: „включително сумата по чл. 40, ал. 5 от КСО“. Тя влиза не"
            + " защото т. 21 я търси, а защото перото ѝ носи признак „осигурителен доход“ и затова е"
            + " част от брутото, върху което базата се е образувала.");
  }

  @Test
  @DisplayName("⚠️ РЕЗЕРВНИЯТ ПЪТ · без база на перата т. 21 пада на сбора и това се ОБЯВЯВА")
  void withoutABaseOnTheLinesTheFallbackIsDeclared() {
    // Фиш с начисления и с пера от 5xx, но БЕЗ база на тях — тоест вноска не е установила база.
    // Това е и случаят „таванът е изчерпан от друг платец“: всяка вноска се закръгля до 0,00 и
    // BgContributionLineService нарочно НЕ прави перо.
    BgPaySlip slip = slipOverTheCap();
    List<BgPaySlipLine> lines = new ArrayList<>();
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      l.setBgQuantity(BigDecimal.ZERO);
      lines.add(l);
    }
    slip.setBgPaySlipLineList(lines);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), findings);

    assertEquals(
        0,
        GROSS.compareTo(record.getT21InsuranceIncome()),
        "⚠️ НАЙ-ДОБРОТО НАЛИЧНО ЧИСЛО, а не нула и не отказ: нула би твърдяла „осигурителен доход"
            + " нула“, а отказ на записа щеше да загуби т. 29, 31, 31а и всичко останало, което е"
            + " напълно определимо.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T21_NOT_CAPPED".equals(f.getCode())),
        "⚠️ И ИМЕННО ЗАТОВА ЧИСЛОТО ИДВА С НАХОДКА: то НЕ е минало през тавана, тоест за лице над"
            + " него е ГРЕШНО. Премълчано, разминаването се вижда само ако някой умножи т. 21 по"
            + " ставката — а това го прави НАП. Находки: "
            + findings);
  }

  // ═══ ВТОРА СТЕПЕН · КООРДИНАЦИЯТА ПО КСО ЧЛ. 6 АЛ. 11 ═══════════════════

  /**
   * ФИШ НА МНОГОПЛАТЕЦ С ИЗЧЕРПАН ТАВАН — какъвто производството наистина прави.
   *
   * <p>⚠️ ПЕРА ОТ 5xx ИЗОБЩО НЯМА, и това не е опростяване на фикстурата: при изчерпан таван базата
   * е НУЛА, всяка вноска се закръгля до 0,00, а {@code BgContributionLineService#problem} нарочно
   * НЕ прави перо („ред с 0,00 би твърдял, че вноска се дължи“). Фикстура с нулеви пера би описала
   * фиш, който този код не може да произведе — и щеше да мери третата степен вместо втората.
   */
  private static BgPaySlip slipWithExhaustedCapElsewhere(String coordinatedBase) {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-05-9003");
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(earning(100, "3000.00"));
    slip.setBgPaySlipLineList(lines);
    slip.setEmploymentContract(contract());
    // Декларираният чужд доход — САМИЯТ разграничител: без него заглавното поле е сборът на перата.
    slip.setBgOtherPayerInsurableIncome(CAP);
    // Заглавното поле, каквото BgPaySlipService#insuranceIncomeHeader записва по КООРДИНИРАНИЯ път:
    // базата на вноската-обект, тоест остатъкът до тавана.
    slip.setBgInsuranceIncome(new BigDecimal(coordinatedBase));
    slip.setBgTaxAmount(new BigDecimal("300.00"));
    return slip;
  }

  @Test
  @DisplayName(
      "⚠️ ВТОРА СТЕПЕН · изчерпан от друг платец таван → т. 21 = 0,00, а НЕ сборът 3000,00")
  void anExhaustedCapYieldsTheCoordinatedZeroNotTheSum() {
    BgPaySlip slip = slipWithExhaustedCapElsewhere("0.00");

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), findings);

    assertEquals(
        0,
        record.getT21InsuranceIncome().signum(),
        "⚠️ КООРДИНИРАНАТА СТОЙНОСТ, не сборът. Лицето е декларирало 2111,64 при друг платец, тоест"
            + " целият максимален месечен размер е изчерпан там и по ТОВА правоотношение вноски не се"
            + " дължат (КСО чл. 6 ал. 11). Сборът на перата на нейно място щеше да декларира 3000,00"
            + " осигурителен доход, върху който НИТО ЕДНА стотинка вноска не е внесена.");
    assertTrue(
        GROSS.compareTo(BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList())) == 0,
        "а сборът на перата ОСТАВА 3000,00 — тоест двата източника наистина се разминават тук");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T21_CAP_EXHAUSTED_ELSEWHERE".equals(f.getCode())),
        "⚠️ Нулата е ЗАКОННА, но се ОБЯВЯВА: празна т. 21 до попълнени т. 29 и т. 31 изглежда като"
            + " пропуск. Находки: "
            + findings);
    assertTrue(
        findings.stream().noneMatch(f -> "D1_T21_NOT_CAPPED".equals(f.getCode())),
        "⚠️ И НЕ Е „некапната“: числото Е минало през тавана — просто остатъкът е нула. Двете находки"
            + " описват РАЗЛИЧНИ фишове и не бива да се смесват. Находки: "
            + findings);
  }

  @Test
  @DisplayName("⚠️ ВТОРА СТЕПЕН · остатък до тавана → т. 21 = остатъкът, не сборът")
  void aPartiallyExhaustedCapYieldsTheRemainder() {
    // Деклариран чужд доход 1600,00 → остатък 511,64 от тавана 2111,64.
    BgPaySlip slip = slipWithExhaustedCapElsewhere("511.64");
    slip.setBgOtherPayerInsurableIncome(new BigDecimal("1600.00"));

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), findings);

    assertEquals(
        0,
        new BigDecimal("511.64").compareTo(record.getT21InsuranceIncome()),
        "т. 21 е ОСТАТЪКЪТ до тавана (2111,64 − 1600,00), а не начислените 3000,00");
    assertTrue(
        findings.stream().noneMatch(f -> f.getCode().startsWith("D1_T21_")),
        "⚠️ Положителна координирана база не иска НИКАКВА находка: числото е вярното и е минало през"
            + " тавана. Находки: "
            + findings);
  }

  @Test
  @DisplayName(
      "⚠️ разграничителят е КООРДИНАЦИЯТА, не стойността — без деклариран доход не се чете")
  void theHeaderIsReadOnlyWhenCoordinationIsDeclared() {
    // Същият фиш, но БЕЗ деклариран чужд доход. Тогава заглавното поле е сборът на перата (така го
    // пише insuranceIncomeHeader) и НЕ бива да се чете като капнато.
    BgPaySlip slip = slipWithExhaustedCapElsewhere("0.00");
    slip.setBgOtherPayerInsurableIncome(null);
    slip.getEmploymentContract().setBgOtherPayerInsurableIncome(null);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), findings);

    assertEquals(
        0,
        GROSS.compareTo(record.getT21InsuranceIncome()),
        "⚠️ БЕЗ КООРДИНАЦИЯ ЗАГЛАВНОТО ПОЛЕ НЕ Е КАПНАТО — BgPaySlipService#insuranceIncomeHeader"
            + " връща сбора на перата. Прочетено безусловно, то би дало 0,00 тук и щеше да декларира"
            + " нулев осигурителен доход за лице с 3000,00 начисление. Затова се пита за"
            + " КООРДИНАЦИЯТА, а не за стойността.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T21_NOT_CAPPED".equals(f.getCode())),
        "и третата степен обявява, че числото не е минало през тавана. Находки: " + findings);
  }

  @Test
  @DisplayName("стоящата стойност на ДОГОВОРА също включва втората степен")
  void theStandingValueOnTheContractAlsoCounts() {
    BgPaySlip slip = slipWithExhaustedCapElsewhere("0.00");
    slip.setBgOtherPayerInsurableIncome(null);
    slip.getEmploymentContract().setBgOtherPayerInsurableIncome(CAP);

    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), new ArrayList<>());

    assertEquals(
        0,
        record.getT21InsuranceIncome().signum(),
        "⚠️ Месечната стойност на ФИША надписва стоящата на ДОГОВОРА, но и двете вдигат"
            + " координацията — точно както BgPaySlipResolver#otherPayerInsurableIncome го чете."
            + " Проверка само върху фиша би пропуснала всеки договор със стояща стойност.");
  }

  @Test
  @DisplayName("⚠️ ТРЕТОТО СЪСТОЯНИЕ · чекнат признак → т. 21 пада на ТРЕТАТА степен, не на нулата")
  void theDeclaredAbsenceTakesTheRecordOffTheCoordinatedTier() {
    // СЪЩИЯТ фиш като горния — стояща стойност на договора, изчерпан таван, заглавно поле 0,00, —
    // но за ТОЗИ месец лицето е заявило, че при другия платец няма доход (отпуск/болничен/напускане
    // при него). НЕВДПОВ чл. 4: изявлението е за МЕСЕЦ, защото сборът по КСО чл. 6 ал. 11 е
    // месечен.
    BgPaySlip slip = slipWithExhaustedCapElsewhere("0.00");
    slip.setBgOtherPayerInsurableIncome(null);
    slip.getEmploymentContract().setBgOtherPayerInsurableIncome(CAP);
    slip.setBgNoOtherPayerIncome(true);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), findings);

    assertEquals(
        0,
        GROSS.compareTo(record.getT21InsuranceIncome()),
        "⚠️ ЗАГЛАВНОТО ПОЛЕ ВЕЧЕ НЕ СЕ ЧЕТЕ КАТО КАПНАТО. Без признака т. 21 щеше да вземе 0,00 от"
            + " bgInsuranceIncome и да декларира НУЛЕВ осигурителен доход за лице с 3000,00"
            + " начисление — законната нула на чл. 6 ал. 11 за месец, в който координация НЯМА."
            + " Разграничителят е КООРДИНАЦИЯТА, а признакът я спира.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T21_NOT_CAPPED".equals(f.getCode())),
        "и третата степен си обявява, че числото не е минало през тавана — точно както при фиш без"
            + " никаква декларация. Находки: "
            + findings);
    assertTrue(
        findings.stream().noneMatch(f -> "D1_T21_CAP_EXHAUSTED_ELSEWHERE".equals(f.getCode())),
        "⚠️ И НЕ обявява изчерпан от друг платец таван: за ТОЗИ месец такъв платец няма. Находки: "
            + findings);
  }

  @Test
  @DisplayName("⚠️ ЗАВАРЕН РЕД · непипнат признак → т. 21 е точно каквато беше (нула backfill)")
  void legacyRecordsKeepTheirCoordinatedPoint21() {
    BgPaySlip slip = slipWithExhaustedCapElsewhere("0.00");
    slip.setBgOtherPayerInsurableIncome(null);
    slip.getEmploymentContract().setBgOtherPayerInsurableIncome(CAP);
    // ⚠️ Признакът НЕ се пипа — точно състоянието на всеки заварен ред. Генерираният getter за
    // Boolean връща FALSE, никога null, тоест миграция не се иска.

    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), new ArrayList<>());

    assertEquals(
        0,
        record.getT21InsuranceIncome().signum(),
        "заварен координационен запис дава СЪЩАТА нула като преди признака — нула backfill");
  }

  @Test
  @DisplayName("празен фиш · т. 21 остава празна БЕЗ находка — нула и нула са съгласни")
  void anEmptySlipRaisesNoFinding() {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-05-9002");
    slip.setBgPaySlipLineList(new ArrayList<>());
    slip.setEmploymentContract(contract());

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = BgD1RecordBuilder.build(slip, insuredType(), period(), findings);

    assertEquals(0, record.getT21InsuranceIncome().signum());
    assertTrue(
        findings.stream().noneMatch(f -> "D1_T21_NOT_CAPPED".equals(f.getCode())),
        "⚠️ Находка тук би била шум: празната т. 21 при нула начисления не твърди нищо невярно."
            + " Празният фиш се хваща от друг пазач (refuseEmptyPaySlip). Находки: "
            + findings);
  }

  /** Ставка върху база, закръглена като перо: два знака, половина нагоре (§ 2 ал. 2). */
  private static BigDecimal rateOn(BigDecimal base, BigDecimal ratePercent) {
    return base.multiply(ratePercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
  }
}
