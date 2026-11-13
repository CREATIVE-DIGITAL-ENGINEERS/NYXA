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
import com.axelor.apps.bglocalization.db.BgMinInsuranceIncome;
import com.axelor.apps.bglocalization.db.BgMinInsuranceIncomeAmount;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgMinIncomeResolver;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДОГОВОР ЗА УПРАВЛЕНИЕ И КОНТРОЛ (вид осигурен 10) — какво излиза на Декларация обр. 1.
 *
 * <p><b>Нормата.</b> КСО чл. 4 ал. 1 т. 7, ДОСЛОВНО: „управителите и прокуристите на търговски
 * дружества, на еднолични търговци, на техните клонове и на клоновете на чуждестранни юридически
 * лица, членовете на съвети на директорите, на управителни и надзорни съвети и контрольорите на
 * търговски дружества, синдиците и ликвидаторите, както и лицата, работещи по договори за
 * управление на неперсонифицирани дружества …“. Точката е в АЛ. 1, тоест наборът е ПЪЛЕН —
 * „задължително осигурени за общо заболяване и майчинство, инвалидност поради общо заболяване,
 * старост и смърт, трудова злополука и професионална болест и безработица“.
 *
 * <p><b>Базата.</b> КСО чл. 6 ал. 3, ДОСЛОВНО: „Осигурителните вноски за работниците и служителите
 * и за лицата по чл. 4, ал. 1, т. 7 и 8 се дължат върху получените, включително начислените и
 * неизплатените, брутни месечни възнаграждения … но върху не по-малко от минималния осигурителен
 * доход по ал. 2, т. 3 …“ — тоест управленското възнаграждение, БЕЗ приспадане на нормативни
 * разходи. Приспадането по чл. 6 ал. 4 е за лицата по чл. 4 ал. 3 т. 5 и 6 (граждански договор, код
 * 14) и е ГРАНИЦА на тази фаза, не неин предмет.
 *
 * <p><b>⚠️ ЕДНО ПРАВООТНОШЕНИЕ, ДВЕ ПРОТИВОПОЛОЖНИ КВАЛИФИКАЦИИ, И ТОЧНО ТУК Е КАПАНЪТ.</b> За
 * ДАНЪКА ДУК Е трудово правоотношение — § 1 т. 26 б. „з“ ДР ЗДДФЛ, дословно: „правоотношенията по
 * договори за управление и контрол, включително с членовете на управителни и контролни органи на
 * предприятия“. Оттам т. 31 и т. 31а се пълнят и ставката е 10 на сто по чл. 42. За ОСИГУРЯВАНЕТО и
 * за КОДЕКСА НА ТРУДА то НЕ Е трудово правоотношение: няма трудов договор, няма брутна РАБОТНА
 * ЗАПЛАТА по НСОРЗ чл. 16 ал. 2 и няма фонд ГВРС. Тоест т. 31 се пълни, а т. 29 и т. 30 остават
 * празни — две съседни точки на един запис, които се решават по два различни закона.
 *
 * <p><b>Ставките са ДЕЙСТВИТЕЛНИТЕ за вид осигурен 10, III категория, 2026 г.</b> — {@code
 * bg_insurance_contribution.csv}, редове IC-046…IC-051: Пенсии 8,22/6,58 · УПФ 2,80/2,20 · ОЗМ
 * 2,10/1,40 · Безработица 0,60/0,40 · здравно 4,80/3,20. Сборът им е дословно този на наетия по код
 * 01 и това НЕ е съвпадение: чл. 6 ал. 3 разпределя вноските за т. 7 по същите съотношения, по
 * които ги разпределя и за работниците и служителите. Разликата между двата кода не е в ставките, а
 * в ГВРС и в т. 29.
 */
class BgDukRecordTest {

  private static final LocalDate MAY_2026 = LocalDate.of(2026, 5, 1);

  /** Управленското възнаграждение по договора — база на вноските по чл. 6 ал. 3. */
  private static final BigDecimal DUK_BASE = new BigDecimal("2000.00");

  /** Максималният месечен осигурителен доход за 01.01–31.07.2026 — ЗБДОО чл. 9 т. 1 б. „в“. */
  private static final BigDecimal CAP_2026_H1 = new BigDecimal("2111.64");

  /**
   * Кодовете, за които речникът на вноските дава ред за фонд ГВРС — тоест ТРУДОВИТЕ правоотношения,
   * както системата ги знае.
   *
   * <p>⚠️ Код 10 НЕ Е В НЕГО, и това е самата фикстура, не настройка на теста: шестте реда
   * IC-046…IC-051 носят бележка „ГВРС не се дължи“ и нито един от тях не е за фонд GVRS. Списъкът
   * тук е това, което {@code BgD1Emitter#gvrsEligibleCodes} чете от базата.
   */
  private static final Set<String> LABOUR_CODES =
      new LinkedHashSet<>(Arrays.asList("01", "02", "03", "04", "16"));

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

  /** Перо на ВНОСКА (група 5xx) — сума, база и ставка, както ги пише BgContributionLineService. */
  private static BgPaySlipLine contribution(
      int number, String sign, String amount, String ratePercent, BgContributionFund f) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgQuantity(DUK_BASE);
    l.setBgRate(new BigDecimal(ratePercent));
    l.setBgContributionFund(f);
    l.setBgIsInsuranceIncome(Boolean.FALSE);
    l.setBgIsTaxable(Boolean.FALSE);
    return l;
  }

  /**
   * ВЪЗНАГРАЖДЕНИЕТО ПО ДУК — начисление, осигурителен доход и облагаем доход.
   *
   * <p><b>⚠️ {@code bgFormsGrossWage} се вдига ИЗРИЧНО, и точно това прави свидетеля за т. 29
   * истински.</b> Днес перото се ражда през {@code BgPaySlipService#lineFrom}, който копира от
   * речника четири флага и НЕ копира този — тоест в производството т. 29 е празна за ВСЕКИ, включно
   * за наетия, комуто се полага. Фикстура, която повтаря този пропуск, би минала зелена, без да е
   * проверила нищо: точката щеше да е празна по погрешната причина. Вдигнат тук, флагът пита
   * същинския въпрос — „ще спре ли ДУК-ът т. 29 ПО НОРМА, а не по случайност“.
   */
  private static BgPaySlipLine remuneration(String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(100);
    l.setBgSign(BgPaySlipResolver.CREDIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsInsuranceIncome(Boolean.TRUE);
    l.setBgIsTaxable(Boolean.TRUE);
    l.setBgFormsGrossWage(Boolean.TRUE);
    return l;
  }

  /**
   * Видът осигурен, както е зареден в {@code bg_insured_type.csv} — ред IT-10-2024.
   *
   * <p>Петте флага са ДОСЛОВНО от seed-а: МОД се прилага, т. 31 се пълни, дни и часове са нула
   * (управителят няма отчетено работно време), а нето по т. 34 СЕ ПОЛАГА — управителят получава
   * парични обезщетения от НОИ, защото чл. 4 ал. 1 т. 7 го осигурява и за ОЗМ.
   */
  private static BgInsuredType dukType() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("10");
    t.setBgAppliesMod(Boolean.TRUE);
    t.setBgAppliesModConditional(Boolean.FALSE);
    t.setBgFillsT31(Boolean.TRUE);
    t.setBgZeroWorkTime(Boolean.TRUE);
    t.setBgNoHours(Boolean.TRUE);
    t.setBgNoNetPay(Boolean.FALSE);
    return t;
  }

  private static BgInsuredType employeeType() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("01");
    t.setBgAppliesMod(Boolean.TRUE);
    t.setBgFillsT31(Boolean.TRUE);
    t.setBgNoNetPay(Boolean.FALSE);
    return t;
  }

  /**
   * Фиш по профила на код 10: пълните рискове по чл. 4 ал. 1 т. 7 + ТЗПБ по дейност.
   *
   * <p>⚠️ ФОНД ГВРС ГО НЯМА СРЕД ПЕРАТА, и това е самият профил: речникът не дава ред за него при
   * код 10. Перо с нулева ставка тук би било ДРУГО твърдение — „вноската е нула“ вместо „вноска не
   * се дължи“.
   */
  private static BgPaySlip dukSlip() {
    BgContributionFund pension = fund("PENSION", BgD1Point.T22_DOO);
    BgContributionFund ozm = fund("OZM", BgD1Point.T22_DOO);
    BgContributionFund unemployment = fund("UNEMPLOYMENT", BgD1Point.T22_DOO);
    BgContributionFund tzpb = fund("TZPB", BgD1Point.T22_2_TZPB);
    BgContributionFund health = fund("HEALTH", BgD1Point.T22_1_HEALTH);
    BgContributionFund upf = fund("UPF", BgD1Point.T26_UPF);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(remuneration("2000.00"));

    // ═══ фондовете на ДОО — IC-046, IC-049, IC-050 ═══
    lines.add(contribution(500, BgPaySlipResolver.CREDIT, "164.40", "8.22", pension));
    lines.add(contribution(501, BgPaySlipResolver.DEBIT, "131.60", "6.58", pension));
    lines.add(contribution(504, BgPaySlipResolver.CREDIT, "42.00", "2.10", ozm));
    lines.add(contribution(505, BgPaySlipResolver.DEBIT, "28.00", "1.40", ozm));
    lines.add(contribution(506, BgPaySlipResolver.CREDIT, "12.00", "0.60", unemployment));
    lines.add(contribution(507, BgPaySlipResolver.DEBIT, "8.00", "0.40", unemployment));

    // ТЗПБ — САМО осигурител, и ставката идва от ДЕЙНОСТТА (bg_tzpb_rate), не от профила на код 10.
    // 0,47 е ставка на конкретна група дейности; фиксирано число в профила биха имали само кодове
    // 05 и 24.
    lines.add(contribution(508, BgPaySlipResolver.CREDIT, "9.40", "0.47", tzpb));

    // Здравно — IC-051, двете половини на 8 на сто по ЗЗО чл. 40 ал. 1 т. 1.
    lines.add(contribution(516, BgPaySlipResolver.CREDIT, "96.00", "4.80", health));
    lines.add(contribution(517, BgPaySlipResolver.DEBIT, "64.00", "3.20", health));

    // УПФ — IC-048, ДЗПО по чл. 157 ал. 3, тоест т. 26, а НЕ т. 22.
    lines.add(contribution(512, BgPaySlipResolver.CREDIT, "56.00", "2.80", upf));
    lines.add(contribution(513, BgPaySlipResolver.DEBIT, "44.00", "2.20", upf));

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-DUK-2026-05-0001");
    slip.setBgPaySlipLineList(lines);
    // 10 на сто върху 2000,00 − 275,60 лични вноски = 172,44 (ЗДДФЛ чл. 42 ал. 2 и ал. 4).
    slip.setBgTaxAmount(new BigDecimal("172.44"));
    slip.setEmploymentContract(contract("10"));
    return slip;
  }

  private static EmploymentContract contract(String insuredCode) {
    Partner companyPartner = new Partner();
    companyPartner.setName("Никса ЕООД");
    companyPartner.setRegistrationCode("831919536");
    companyPartner.setTaxNbr("BG831919536");

    BgNkid nkid = new BgNkid();
    nkid.setCode("27.11");
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
    employee.setBgPersonalIdType("1");

    // ⚠️ КВАЛИФИКАЦИОННАТА ГРУПА Е 1 — „Ръководители“, първата колона на Приложение № 1 към ЗБДОО.
    // Тя идва от ПЪРВАТА ЦИФРА НА НКПД, не от вида осигурен: чл. 6 ал. 2 т. 3 КСО дава МОД „по
    // основни икономически дейности и квалификационни групи ПРОФЕСИИ“, а професията виси на кода по
    // НКПД. Изведена от код 10, тя щеше да е грешна за контрольора, синдика и ликвидатора — те са
    // по същата т. 7, но не са ръководители по НКПД.
    BgNkpd nkpd = new BgNkpd();
    nkpd.setQualificationGroup(1);

    EmploymentContract contract = new EmploymentContract();
    contract.setStartDate(LocalDate.of(2020, 1, 15));
    contract.setBgInsuredTypeCode(insuredCode);
    contract.setPayCompany(company);
    contract.setEmployee(employee);
    contract.setBgNkpd(nkpd);
    return contract;
  }

  private static BgD1Record build(BgPaySlip slip, BgInsuredType type, Set<String> labourCodes) {
    return BgD1RecordBuilder.build(
        slip,
        type,
        period(MAY_2026),
        new BgD1Facts(null, null, null, CAP_2026_H1, labourCodes),
        new ArrayList<>());
  }

  // ═══ свидетелите ═══

  @Test
  @DisplayName("т. 12 · кодът идва от договора и е 10, без превод и без подразбиране")
  void point12CarriesTheManagementCode() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    assertEquals(
        "10",
        record.getT12InsuredTypeCode(),
        "т. 12 е ПРЕПИСАН от EmploymentContract.bgInsuredTypeCode. Празно тук би значело, че"
            + " договорният път не приема код 10 — тогава фишът изобщо няма да се сглоби.");
  }

  @Test
  @DisplayName("т. 22 / 22.1 / 26 · сплитът по чл. 6 ал. 3 — пълните рискове на чл. 4 ал. 1 т. 7")
  void contributionSplitFollowsTheCode10Profile() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    // 8,22 (Пенсии) + 2,10 (ОЗМ) + 0,60 (Безработица)
    assertEquals(
        0,
        new BigDecimal("10.92").compareTo(record.getT22DooRateEmployer()),
        "т. 22 А · ДОО без ТЗПБ за сметка на осигурителя. 13,72 би било със събран УПФ —"
            + " и точно това число публикуваните таблици наричат „ДОО за сметка на работодателя“.");
    // 6,58 + 1,40 + 0,40
    assertEquals(
        0,
        new BigDecimal("8.38").compareTo(record.getT22DooRateInsured()),
        "т. 22 Б · същият сбор за сметка на ЛИЦЕТО.");

    assertEquals(
        0,
        new BigDecimal("4.80").compareTo(record.getT221HealthRateEmployer()),
        "т. 22.1 А · здравно за сметка на осигуряващия — 60 на сто от осемте по ЗЗО чл. 40 ал. 1"
            + " т. 1.");
    assertEquals(
        0,
        new BigDecimal("3.20").compareTo(record.getT221HealthRateInsured()),
        "т. 22.1 Б · здравно за сметка на лицето — останалите 40 на сто.");

    assertEquals(
        0,
        new BigDecimal("2.80").compareTo(record.getT26UpfRateEmployer()),
        "т. 26 А · УПФ за сметка на осигурителя, чл. 157 ал. 3 КСО.");
    assertEquals(
        0,
        new BigDecimal("2.20").compareTo(record.getT26UpfRateInsured()),
        "т. 26 Б · УПФ за сметка на лицето. ⚠️ УПФ е т. 26, НЕ т. 22 — събран в т. 22, сборът"
            + " става 13,72 и изглежда напълно правдоподобен.");
  }

  @Test
  @DisplayName("т. 22.2 · ТЗПБ идва от ДЕЙНОСТТА, а не от фиксирана ставка на код 10")
  void tzpbComesFromTheActivity() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    assertEquals(
        0,
        new BigDecimal("0.47").compareTo(record.getT222TzpbRate()),
        "ставката е на ПЕРОТО, а перото е сглобено от bg_tzpb_rate по КИД на дружеството —"
            + " профилът на код 10 няма ред за фонд TZPB и затова не я надписва. Фиксирана ставка"
            + " носят само кодове 05 и 24 (0,70 на сто, независимо от дейността).");
    assertTrue(
        new BigDecimal("0.70").compareTo(record.getT222TzpbRate()) != 0,
        "0,70 е ставката на кодове 05 и 24 — попадне ли тук, значи че ТЗПБ е закован, а не четен"
            + " по дейност.");
  }

  @Test
  @DisplayName("т. 30 · ГВРС НЕ се дължи — профилът на код 10 няма ред за фонда")
  void noGvrsForManagementContract() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    // ⚠️ signum(), НЕ assertNull — и това не е отстъпка, а самият модел. Генерираният getter за
    // decimal връща ZERO за непопълнена колона, тоест „не е писано“ и „нула“ са НЕРАЗЛИЧИМИ тук.
    // Свидетел с assertNull пада винаги и учи следващия читател на грешното: че празното се
    // разпознава. Че двете съвпадат и НАДОЛУ, е решено от самия файлов формат — „Numeric полета
    // без стойност → 0“, — така че на записа в НАП разлика няма.
    assertEquals(
        0,
        record.getT30GvrsRate().signum(),
        "фонд „Гарантирани вземания на работниците и служителите“ се дължи по ТРУДОВО"
            + " правоотношение. ДУК не е такова — управителят по чл. 4 ал. 1 т. 7 КСО няма трудов"
            + " договор. Шестте реда IC-046…IC-051 носят бележката „ГВРС не се дължи“ и нито един"
            + " от тях не е за фонд GVRS, тоест перо няма и ставка не се събира. 0,60 тук би било"
            + " ставката на фонд „Безработица“, попаднала в чужда точка.");
  }

  @Test
  @DisplayName("т. 29 · брутна РАБОТНА заплата няма — ДУК не е трудово правоотношение")
  void noGrossWageForManagementContract() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    assertEquals(
        0,
        record.getT29GrossWage().signum(),
        "заб. към т. 29/30 на Приложение № 1: попълват се за лицата, работещи по ТРУДОВИ"
            + " правоотношения. „Брутна работна заплата“ по НСОРЗ чл. 16 ал. 2 предполага трудов"
            + " договор, а управителят по чл. 4 ал. 1 т. 7 КСО няма такъв.\n"
            + "  ⚠️ 2000,00 тук значи, че точката се пълни от флага на перото и НИКОЙ не пита за"
            + " вида на правоотношението — тогава BgD1Validator вдига D1_T30_NOT_LABOUR_RELATION"
            + " върху ВЕРЕН фиш и декларацията излиза с грешка, която операторът не може да"
            + " поправи от екрана.");
  }

  @Test
  @DisplayName("т. 31 · облагаемият доход СЕ ПЪЛНИ — за данъка ДУК Е трудово правоотношение")
  void taxableIncomeIsFilled() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    assertEquals(
        0,
        DUK_BASE.compareTo(record.getT31TaxableIncome()),
        "§ 1 т. 26 б. „з“ ДР ЗДДФЛ прави ДУК трудово правоотношение ЗА ДАНЪКА, и bgFillsT31 = true"
            + " в речника го носи. ⚠️ Съседката т. 29 е празна по ДРУГ закон — двете не се"
            + " решават заедно.");
    assertEquals(
        0,
        new BigDecimal("172.44").compareTo(record.getT31aTax()),
        "т. 31а · 10 на сто върху 2000,00 − 275,60 лични вноски = 1724,40 → 172,44.");
  }

  @Test
  @DisplayName("т. 34 · нетото СЕ ПЪЛНИ — управителят получава обезщетения от НОИ")
  void netPayIsFilled() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    // ⚠️ signum() > 0, не assertNotNull — по същия довод като при т. 29 и т. 30: decimal getter-ът
    // никога не връща null и assertNotNull тук би минавал винаги, включително при празна точка.
    assertTrue(
        record.getT34NetPay().signum() > 0,
        "bgNoNetPay = false за код 10: чл. 4 ал. 1 т. 7 осигурява управителя и за общо заболяване"
            + " и майчинство, тоест НОИ му смята обезщетение и има от какво. Празна точка тук би"
            + " оставила базата на обезщетението неизвестна.");
    // 2000,00 − 275,60 (лични вноски) − 172,44 (данък) = 1551,96; под тавана 2111,64.
    assertEquals(
        0,
        new BigDecimal("1551.96").compareTo(record.getT34NetPay()),
        "НПОПДОО чл. 32 ал. 1: възнаграждението, намалено с личните вноски и с данъка, но не над"
            + " максималния месечен осигурителен доход.");
  }

  @Test
  @DisplayName("т. 21 · базата е управленското възнаграждение ЦЯЛО, без нормативни разходи")
  void insuranceIncomeIsTheFullRemuneration() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    assertEquals(
        0,
        DUK_BASE.compareTo(record.getT21InsuranceIncome()),
        "чл. 6 ал. 3 КСО дължи вноските върху БРУТНОТО месечно възнаграждение. Приспадането на"
            + " нормативно признати разходи е по ал. 4 и е САМО за лицата по чл. 4 ал. 3 т. 5 и 6"
            + " — граждански договор, код 14. 1500,00 (2000 − 25 на сто) е числото на ГД и е"
            + " ГРАНИЦА на тази фаза.");
  }

  @Test
  @DisplayName("т. 12.1/12.2/12.3 · трите оси на МОД се попълват — код 10 прилага МОД")
  void modAxesAreFilledForTheManagementContract() {
    BgD1Record record = build(dukSlip(), dukType(), LABOUR_CODES);

    assertEquals(
        "1",
        record.getT121QualificationGroup(),
        "т. 12.1 · квалификационната група от НКПД на договора. Управителят е клас 1 по НКПД —"
            + " „Ръководители“, първата колона на Приложение № 1 към ЗБДОО.");
    assertEquals(
        "2711",
        record.getT122ActivityCode(),
        "т. 12.2 · кодът по КИД 2008 на ОСИГУРИТЕЛЯ, БЕЗ точката — полето е с размер 4. Възложителят"
            + " по ДУК е дружеството и НЕГОВАТА дейност определя реда, не професията на"
            + " управителя.");
    assertEquals(
        "42",
        record.getT123ActivityRow(),
        "т. 12.3 · редът по приложението към ЗБДОО. ⚠️ ДРУГА КОЛОНА НА СЪЩИЯ РЕД по НКИД, не"
            + " същата: еднакви стойности в 12.2 и 12.3 биха скрили размяната им.");
  }

  // ═══ Декларация обр. 6 ═══

  @Test
  @DisplayName("Д6 · сумите падат по фонд, а т. 15 (ГВРС) остава без нито един лев")
  void declaration6SumsByFundAndCarriesNoGvrs() {
    Map<String, BigDecimal> byPoint = BgD6Aggregator.byPoint(Collections.singletonList(dukSlip()));

    // 164,40 + 131,60 (Пенсии) + 42,00 + 28,00 (ОЗМ) + 12,00 + 8,00 (Безраб.) + 9,40 (ТЗПБ)
    assertEquals(
        0,
        new BigDecimal("395.40").compareTo(byPoint.get(BgD6Point.T10_DOO)),
        "т. 10 · четирите фонда на ДОО се сливат в ЕДНА точка, и ТЗПБ е сред тях. ⚠️ Д6 иска"
            + " ЛЕВОВЕ по фонд, за разлика от Д1, която иска ПРОЦЕНТИ по точка — двете декларации"
            + " не носят едни и същи числа за един и същи фиш.");
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(byPoint.get(BgD6Point.T12_UPF)),
        "т. 12 · УПФ е ДЗПО и има СВОЯ точка. Слят в т. 10, преводът щеше да отиде в НОИ вместо в"
            + " пенсионноосигурителното дружество.");
    assertEquals(
        0,
        new BigDecimal("160.00").compareTo(byPoint.get(BgD6Point.T14_HEALTH)),
        "т. 14 · здравното, двете половини заедно.");

    assertNull(
        byPoint.get(BgD6Point.T15_GVRS),
        "⚠️ ТУК null Е РАЗЛИЧИМ ОТ НУЛА, за разлика от полетата на записа: това е Map, а не"
            + " генериран getter. Липсващият ключ значи „нито едно перо не сочи този фонд“, тоест"
            + " ГВРС НЕ СЕ ДЪЛЖИ по ДУК — а не че се дължи и е нула.");
  }

  // ═══ МОД · ръководната квалификационна група ═══

  /** Ред от Приложение № 1 към ЗБДОО, с деветте му колони. */
  private static BgMinInsuranceIncome modRow(String code, Object... groupAmountPairs) {
    BgMinInsuranceIncome r = new BgMinInsuranceIncome();
    r.setBgRow(code);
    List<BgMinInsuranceIncomeAmount> list = new ArrayList<>();
    for (int i = 0; i < groupAmountPairs.length; i += 2) {
      BgMinInsuranceIncomeAmount a = new BgMinInsuranceIncomeAmount();
      a.setBgQualificationGroup((Integer) groupAmountPairs[i]);
      a.setAmount(new BigDecimal(groupAmountPairs[i + 1].toString()));
      a.setBgMinInsuranceIncome(r);
      list.add(a);
    }
    r.setBgAmountList(list);
    return r;
  }

  /**
   * Ред 42 „Производство на електрически съоръжения“, ЗБДОО за 2026 г. — първите три колони.
   *
   * <p>Колона 1 е „Ръководители“ — така я озаглавява самото приложение.
   */
  private static BgMinInsuranceIncome row42() {
    return modRow("42", 1, "936.00", 2, "826.00", 3, "771.00");
  }

  @Test
  @DisplayName("МОД · управителят пада в група 1 (Ръководители) и прагът е 936,00 по ред 42")
  void managerLandsInTheExecutiveQualificationGroup() {
    // НКПД 11207002 „Изпълнителен директор“ — клас 1 по НКПД, тоест „Ръководители“.
    BgMinIncomeResult group = BgMinIncomeResolver.qualificationGroup("11207002");

    assertEquals(
        1,
        group.getQualificationGroup(),
        "квалификационната група е ПЪРВАТА ЦИФРА на НКПД кода. Клас 1 по НКПД са ръководителите и"
            + " точно те са първата колона на Приложение № 1 към ЗБДОО.");

    BgMinIncomeResult amount = BgMinIncomeResolver.amountFor(row42(), 1);
    assertEquals(
        0,
        new BigDecimal("936.00").compareTo(amount.getAmount()),
        "прагът на ръководителя по ред 42. 826,00 е съседната колона (Специалисти) и е числото,"
            + " което една отместена колона би дала — по-нисък праг, тоест по-малка осигурителна"
            + " база, и нищо на екрана не би го издало.");
  }

  @Test
  @DisplayName("МОД · група 1 НЕ се извежда от код 10 — синдикът е по същата т. 7 и е група 2")
  void theGroupComesFromNkpdNotFromTheInsuredType() {
    // НКПД 24116013 „Синдик“ — клас 2 по НКПД. Синдикът е ЛИЦЕ ПО ЧЛ. 4 АЛ. 1 Т. 7 КСО, тоест
    // носи същия вид осигурен 10 като управителя.
    BgMinIncomeResult group = BgMinIncomeResolver.qualificationGroup("24116013");

    assertEquals(
        2,
        group.getQualificationGroup(),
        "⚠️ ТОЧНО ТОЗИ СВИДЕТЕЛ ЗАБРАНЯВА УДОБНАТА ГРЕШКА „код 10 ⇒ ръководна група“. Чл. 4 ал. 1"
            + " т. 7 КСО изброява в едно и също изречение управителите И синдиците, ликвидаторите"
            + " и контрольорите; всички носят код 10, а по НКПД не всички са ръководители."
            + " Изведена от вида осигурен, групата би вдигнала прага на синдика от 826,00 на"
            + " 936,00 — завишена вноска, за която няма основание.");

    BgMinIncomeResult amount = BgMinIncomeResolver.amountFor(row42(), 2);
    assertEquals(
        0,
        new BigDecimal("826.00").compareTo(amount.getAmount()),
        "прагът на специалиста по същия ред 42.");
  }

  // ═══ наетият остава непроменен ═══

  @Test
  @DisplayName("код 01 · т. 29 се пълни — трудовото правоотношение НЕ се променя от тази фаза")
  void employeeKeepsGrossWage() {
    BgPaySlip slip = dukSlip();
    slip.setEmploymentContract(contract("01"));

    BgD1Record record = build(slip, employeeType(), LABOUR_CODES);

    assertEquals(
        0,
        DUK_BASE.compareTo(record.getT29GrossWage()),
        "⚠️ ОБРАТНАТА ПОЛОВИНА НА СВИДЕТЕЛЯ ЗА ДУК, и без нея той щеше да мине и при т. 29,"
            + " занулена за ВСИЧКИ. Код 01 носи ред за фонд ГВРС в речника, тоест Е трудово"
            + " правоотношение, и брутната работна заплата му се полага.");
  }

  @Test
  @DisplayName("речникът недостъпен · т. 29 се пълни и правилото ОСТАВА за валидатора")
  void unreadDictionaryDoesNotSilenceThePoint() {
    BgD1Record record = build(dukSlip(), dukType(), null);

    assertEquals(
        0,
        DUK_BASE.compareTo(record.getT29GrossWage()),
        "⚠️ null НЕ Е „няма трудово правоотношение“, а „не е четено“ — липсваща база е състояние"
            + " на средата. Занулена тук, точката би изчезнала тихо и за наетия; попълнена,"
            + " BgD1Validator вдига предупреждението си „правилото НЕ е проверено“ и човек научава"
            + " какво е станало.");

    List<BgD1FindingDto> findings = new ArrayList<>();
    List<BgD1FindingDto> validated =
        new BgD1Validator().validate(build(dukSlip(), dukType(), null), null);
    findings.addAll(validated);
    assertTrue(
        findings.stream().anyMatch(f -> BgD1Validator.GVRS_NOT_LABOUR.equals(f.getCode())),
        "валидаторът остава втората врата: при непрочетен речник той обявява, че правилото не е"
            + " проверено, вместо да мине зелен.");
  }
}
