package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgContributionLine;
import com.axelor.apps.bglocalization.service.BgContributionRequest;
import com.axelor.apps.bglocalization.service.BgContributionResolver;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
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
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГРАЖДАНСКИ ДОГОВОР (вид осигурен 14) — какво излиза на Декларация обр. 1 и обр. 6.
 *
 * <p><b>Нормата.</b> КСО чл. 4 ал. 3 т. 5 и 6 — „лицата, които полагат труд без трудово
 * правоотношение“. Точките са в АЛ. 3, тоест наборът е СТЕСНЕНИЯТ: „Задължително осигурени за
 * инвалидност поради общо заболяване за старост и за смърт“ — и НИЩО повече. Към него ЗЗО чл. 40
 * ал. 1 т. 3 добавя здравното, а чл. 127 ал. 1 КСО — универсалния пенсионен фонд за родените след
 * 31.12.1959 г.
 *
 * <p><b>⚠️ ТОЧНО ТУК Е РАЗЛИКАТА С ДУК, И ТЯ Е В НОМЕРА НА АЛИНЕЯТА.</b> Управителят е по чл. 4 АЛ.
 * 1 т. 7 — пълен набор, включително общо заболяване и майчинство и безработица. Лицето по ГД е по
 * чл. 4 АЛ. 3 — без ОЗМ, без безработица, без ТЗПБ и без ГВРС. Един и същ фиш, две алинеи, шест
 * реда разлика в речника с вноски.
 *
 * <p><b>Базата.</b> КСО чл. 6 ал. 4, ДОСЛОВНО: „Възнаграждението за лицата по чл. 4, ал. 3, т. 5 и
 * 6, върху което се дължат осигурителни вноски, се определя след намаляването му с разходите за
 * дейността, определени по реда на Закона за данъците върху доходите на физическите лица.“ НАП
 * Приложение № 1 (ВК-30-10237/12.08.2026) разд. 11.2 отпечатва същото: „Възнаграждението след
 * намаляване на разходите за дейността, но върху не повече от 2111,64 евро до 31.07.2026 г.“
 *
 * <p><b>Ставките са ДЕЙСТВИТЕЛНИТЕ за вид осигурен 14, III категория, 2026 г.</b> — {@code
 * bg_insurance_contribution.csv}, редове IC-052…IC-055: Пенсии 8,22/6,58 · УПФ 2,80/2,20 · здравно
 * 4,80/3,20. Същите ставки като на наетия по код 01, и това НЕ е съвпадение: чл. 6 ал. 3 изр. 2
 * разпределя вноските „за работниците и служителите и за лицата по чл. 4, ал. 1, т. 5, 7 и 8 И АЛ.
 * 3, Т. 5 И 6“ по едни и същи съотношения. Разликата между кодовете не е в ставките, а в това КОИ
 * фондове изобщо ги има — и в базата.
 *
 * <p><b>Разграничителят т. 5 / т. 6 и прагът от една МРЗ</b> живеят в {@code
 * BgCivilContractResolverTest} — там е аритметиката. Тук е емисията: какво стига до двете
 * декларации, след като базата вече е определена.
 */
class BgGdRecordTest {

  private static final LocalDate MAY_2026 = LocalDate.of(2026, 5, 1);

  /** Договореното възнаграждение — БРУТО, преди нормативните разходи. */
  private static final BigDecimal GROSS = new BigDecimal("2000.00");

  /** Осигурителната база по чл. 6 ал. 4 — 2000,00 намалено с 25 на сто по чл. 29 ал. 1 т. 3. */
  private static final BigDecimal GD_BASE = new BigDecimal("1500.00");

  /** Максималният месечен осигурителен доход за 01.01–31.07.2026 — ЗБДОО чл. 9 т. 1 б. „в“. */
  private static final BigDecimal CAP_2026_H1 = new BigDecimal("2111.64");

  /**
   * АВАНСОВИЯТ ДАНЪК ПО ЧЛ. 43 АЛ. 1 върху този фиш — числото, което перото 7xx удържа.
   *
   * <p>Сметката, знак по знак: облагаемо начисление 2000,00 − лични осигуровки 179,70 (98,70 Пенсии
   * + 48,00 здравно + 33,00 УПФ) = 1820,30 данъчна основа по чл. 42 ал. 2; минус нормативните
   * разходи 500,00 (25 на сто по чл. 29 ал. 1 т. 3) = 1320,30 основа по чл. 43 ал. 1; 10 на сто от
   * нея = 132,03.
   *
   * <p>⚠️ 150,00 Е СЪЩИЯТ ДАНЪК ВЪРХУ ФИШ БЕЗ ОСИГУРОВКИ (10 на сто върху 2000,00 − 500,00). Тук
   * осигуровки ИМА, тоест числото е по-малко. И двете са едно и също твърдение за т. 31а: каквото и
   * да е числото, точката не е негово място.
   */
  private static final BigDecimal CHL43_TAX = new BigDecimal("132.03");

  private static final String CONTRIBUTIONS_FILE = "bg_insurance_contribution.csv";

  /**
   * Кодовете, за които речникът с вноски дава ред за фонд ГВРС — тоест ТРУДОВИТЕ правоотношения.
   *
   * <p>⚠️ Код 14 НЕ Е В НЕГО, и това е самата норма: гаранционният фонд се дължи по ТРУДОВО
   * правоотношение, а чл. 4 ал. 3 т. 5 и 6 назовават лицето отрицателно — „БЕЗ трудово
   * правоотношение“. Списъкът е това, което {@code BgD1Emitter#gvrsEligibleCodes} чете от базата.
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

  /**
   * Перо на ВНОСКА (група 5xx).
   *
   * <p>⚠️ {@code bgQuantity} НОСИ БАЗАТА, И ТЯ Е 1500,00, А НЕ 2000,00. Точка 21 се чете именно
   * оттам ({@code BgD1ContributionAggregator#insuranceIncomeBaseFromLines}) — доходът, „върху който
   * се дължат вноски“, а не сборът на начисленията. Тоест ако приспадането по чл. 6 ал. 4 някога
   * изчезне от веригата, това число ще се върне на 2000,00 и свидетелят за т. 21 ще падне.
   */
  private static BgPaySlipLine contribution(
      int number, String sign, String amount, String ratePercent, BgContributionFund f) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgQuantity(GD_BASE);
    l.setBgRate(new BigDecimal(ratePercent));
    l.setBgContributionFund(f);
    l.setBgIsInsuranceIncome(Boolean.FALSE);
    l.setBgIsTaxable(Boolean.FALSE);
    return l;
  }

  /**
   * ВЪЗНАГРАЖДЕНИЕТО ПО ГРАЖДАНСКИЯ ДОГОВОР — начисление в ПЪЛЕН размер.
   *
   * <p><b>⚠️ ПЕРОТО НОСИ 2000,00, А ОСИГУРИТЕЛНАТА БАЗА Е 1500,00, И ДВЕТЕ СА ВЕРНИ.</b> Лицето е
   * получило 2000,00 — начислението не се реже. Реже се основата, върху която се дължат вноски, и
   * то по чл. 6 ал. 4. Слети в едно число, или фишът щеше да показва изплатено, което не е
   * изплатено, или т. 21 щеше да декларира доход, върху който вноски не са внасяни.
   *
   * <p><b>⚠️ {@code bgFormsGrossWage} се вдига ИЗРИЧНО, по същия довод като в {@code
   * BgDukRecordTest}: инак свидетелят за т. 29 щеше да мине зелен, без да е проверил нищо — точката
   * е празна за всекиго, комуто перото не носи флага.
   *
   * <p><b>⚠️ {@code bgIsTaxable} Е ВДИГНАТ.</b> Перото 7xx се смята по ЗДДФЛ чл. 43 ал. 1
   * (облагаемият доход по чл. 29 МИНУС удържаните задължителни осигуровки), а не по чл. 42. Свален,
   * флагът би направил свидетеля за т. 31 зелен, без да е проверил каквото и да е: точката е празна
   * за всеки фиш без обложено перо, тоест свидетелят не би различил „наредбата не иска точката“ от
   * „фикстурата не носи доход“. Виж {@link #taxPointsStayEmptyForTheCivilContract()}.
   */
  private static BgPaySlipLine remuneration() {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(100);
    l.setBgSign(BgPaySlipResolver.CREDIT);
    l.setBgAmount(GROSS);
    l.setBgIsInsuranceIncome(Boolean.TRUE);
    l.setBgIsTaxable(Boolean.TRUE);
    l.setBgFormsGrossWage(Boolean.TRUE);
    return l;
  }

  /**
   * Видът осигурен, както е зареден в {@code bg_insured_type.csv} — ред IT-14-2024.
   *
   * <p>Флаговете са ДОСЛОВНО от seed-а: МОД НЕ се прилага (Приложение № 1 към ЗБДОО е по
   * икономическа дейност и квалификационна група — оси, каквито гражданският договор няма), т. 31
   * се пълни, дни и часове са нула, а нето по т. 34 НЕ се полага. Последното е самата норма: лицето
   * по чл. 4 ал. 3 не е осигурено за общо заболяване и майчинство, тоест парично обезщетение от НОИ
   * не му се смята и нето няма от какво да бъде база.
   */
  private static BgInsuredType gdType() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("14");
    t.setBgAppliesMod(Boolean.FALSE);
    t.setBgAppliesModConditional(Boolean.FALSE);
    t.setBgFillsT31(Boolean.TRUE);
    t.setBgZeroWorkTime(Boolean.TRUE);
    t.setBgNoHours(Boolean.TRUE);
    t.setBgNoNetPay(Boolean.TRUE);
    t.setBgEntitledToLengthOfServiceBonus(Boolean.FALSE);
    t.setBgFloorType("NONE");
    return t;
  }

  /** Наетият по трудов договор — регресия-пазачът. */
  private static BgInsuredType employeeType() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("01");
    t.setBgAppliesMod(Boolean.TRUE);
    t.setBgFillsT31(Boolean.TRUE);
    t.setBgNoNetPay(Boolean.FALSE);
    return t;
  }

  /**
   * Управителят по договор за управление и контрол — ВТОРИЯТ регресия-пазач.
   *
   * <p>⚠️ Съседът, който най-лесно би паднал заедно с гражданския договор: и двамата са по чл. 4
   * без трудов договор по КТ, и на двамата т. 29 остава празна. За ЗДДФЛ обаче § 1 т. 26 б. „з“ ги
   * разделя — виж {@link #theManagementContractKeepsBothTaxPoints()}. Пълният му профил, с
   * действителните ставки и числа, е в {@code BgDukRecordTest}.
   */
  private static BgInsuredType managementType() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("10");
    t.setBgAppliesMod(Boolean.TRUE);
    t.setBgFillsT31(Boolean.TRUE);
    t.setBgNoNetPay(Boolean.FALSE);
    return t;
  }

  /**
   * Фиш по профила на код 14: ТРИ фонда и нито един повече.
   *
   * <p>⚠️ ОЗМ, БЕЗРАБОТИЦА, ТЗПБ И ГВРС ГИ НЯМА СРЕД ПЕРАТА, и това е самият профил, а не настройка
   * на теста: речникът не дава ред за нито един от тях при код 14. Перо с нулева ставка тук би било
   * ДРУГО твърдение — „вноската е нула“ вместо „вноска не се дължи“.
   */
  private static BgPaySlip gdSlip() {
    BgContributionFund pension = fund("PENSION", BgD1Point.T22_DOO);
    BgContributionFund health = fund("HEALTH", BgD1Point.T22_1_HEALTH);
    BgContributionFund upf = fund("UPF", BgD1Point.T26_UPF);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(remuneration());

    // ═══ фонд „Пенсии“ — IC-052, върху 1500,00 ═══
    lines.add(contribution(500, BgPaySlipResolver.CREDIT, "123.30", "8.22", pension));
    lines.add(contribution(501, BgPaySlipResolver.DEBIT, "98.70", "6.58", pension));

    // Здравно — IC-055, двете половини на осемте на сто по ЗЗО чл. 40 ал. 1 т. 3.
    lines.add(contribution(516, BgPaySlipResolver.CREDIT, "72.00", "4.80", health));
    lines.add(contribution(517, BgPaySlipResolver.DEBIT, "48.00", "3.20", health));

    // УПФ — IC-054, ДЗПО по чл. 157 ал. 3, тоест т. 26, а НЕ т. 22.
    lines.add(contribution(512, BgPaySlipResolver.CREDIT, "42.00", "2.80", upf));
    lines.add(contribution(513, BgPaySlipResolver.DEBIT, "33.00", "2.20", upf));

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-GD-2026-05-0001");
    slip.setBgPaySlipLineList(lines);
    slip.setEmploymentContract(contract("14"));
    slip.setBgTaxAmount(CHL43_TAX);
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
    person.setName("Илиев");
    person.setFirstName("Мартин");
    person.setBgMiddleName("Стоянов");

    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber("7501014567");
    employee.setBgPersonalIdType("1");

    BgNkpd nkpd = new BgNkpd();
    nkpd.setQualificationGroup(2);

    EmploymentContract contract = new EmploymentContract();
    contract.setStartDate(LocalDate.of(2026, 4, 1));
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

  // ═══ РЕЧНИКЪТ · стесненият набор, четен ОТ ФАЙЛА ═══

  @Test
  @DisplayName("профил · код 14 начислява ТОЧНО три фонда: Пенсии, УПФ и здравно")
  void theProfileChargesExactlyThreeFunds() {
    List<String> header = BgSeed.header(CONTRIBUTIONS_FILE);
    Set<String> charged = new TreeSet<>();
    Set<String> declaredNotDue = new TreeSet<>();
    for (String[] row : BgSeed.rows(CONTRIBUTIONS_FILE)) {
      if (!"14".equals(BgSeed.col(header, row, "bgInsuredTypeCode"))) {
        continue;
      }
      String fundCode = BgSeed.col(header, row, "fundCode");
      boolean any =
          new BigDecimal(BgSeed.col(header, row, "employerRate")).signum() != 0
              || new BigDecimal(BgSeed.col(header, row, "employeeRate")).signum() != 0;
      (any ? charged : declaredNotDue).add(fundCode);
    }

    assertEquals(
        new TreeSet<>(Arrays.asList("HEALTH", "PENSION", "UPF")),
        charged,
        "⚠️ ЗАКОВАН НАБОР, И ТОЧНО ТОВА Е СМИСЪЛЪТ МУ. КСО чл. 4 ал. 3 осигурява лицето без"
            + " трудово правоотношение САМО за „инвалидност поради общо заболяване за старост и за"
            + " смърт“; ЗЗО чл. 40 ал. 1 т. 3 добавя здравното, чл. 127 ал. 1 — УПФ. Всеки"
            + " следващ фонд, добавен утре мимоходом, поваля този тест вместо да мине като"
            + " редакция на речника. OZM и UNEMPLOYMENT тук биха били профилът на ДУК (ал. 1"
            + " т. 7); GVRS — на трудовия договор.");

    assertEquals(
        new TreeSet<>(Collections.singletonList("TZPB")),
        declaredNotDue,
        "⚠️ И ЕДИН ФОНД, КОЙТО Е ЗАВЕДЕН ИМЕННО ЗА ДА НЕ СЕ ДЪЛЖИ. Липсващ ред за ТЗПБ значи"
            + " ОБРАТНОТО на „не се дължи“: BgContributionResolver добавя ТЗПБ по ставката от"
            + " заявката — тази по КИД на осигурителя — единствено когато наборът НЕ носи собствен"
            + " ТЗПБ ред. Така е при кодове 01 и 10, където ставката наистина зависи от"
            + " икономическата дейност. IC-335-2026 казва липсата с данни, вместо да я остави да"
            + " се подразбира — виж noTzpbEvenWhenTheActivityCarriesARate().");
  }

  @Test
  @DisplayName("ТЗПБ · ставката по КИД НЕ се начислява на ГД, дори когато дружеството носи такава")
  void noTzpbEvenWhenTheActivityCarriesARate() {
    // Наборът на код 14, както заявката на BgInsuranceCalculationService го връща за WITH_UPF.
    List<BgInsuranceContribution> rows =
        Arrays.asList(
            rateRow("IC-052-2026", "PENSION", "8.2200", "6.5800"),
            rateRow("IC-054-2026", "UPF", "2.8000", "2.2000"),
            rateRow("IC-055-2026", "HEALTH", "4.8000", "3.2000"),
            rateRow("IC-335-2026", "TZPB", "0.0000", "0.0000"));

    // ⚠️ ЗАЯВКАТА НОСИ СТАВКА ПО ДЕЙНОСТ, и точно това прави свидетеля истински: 0,47 на сто е
    // реална ставка от bg_tzpb_rate и BgPaySlipService#tzpbRate я чете от КИД на ДРУЖЕСТВОТО,
    // без да пита за вида осигурен. Подадена null тук, тестът щеше да мине зелен, без да е
    // проверил нищо.
    BgContributionResult result =
        BgContributionResolver.resolve(
            rows,
            new BgContributionRequest(
                GD_BASE, "14", "3", true, null, null, MAY_2026, new BigDecimal("0.4700")));

    assertTrue(result.isResolved(), String.valueOf(result.getStatus()));
    for (BgContributionLine line : result.getLines()) {
      assertTrue(
          !"TZPB".equals(line.getFundCode()),
          "⚠️ ФОНД ТЗПБ ПРИ ГРАЖДАНСКИ ДОГОВОР Е ВНОСКА, КОЯТО НЕ СЕ ДЪЛЖИ. Трудовата злополука и"
              + " професионалната болест са риск на КСО чл. 4 АЛ. 1; ал. 3 не ги носи, а НАП"
              + " Приложение № 1 разд. 11.2 не изписва ТЗПБ нито в обхвата, нито в ставките."
              + " 7,05 (0,47 на сто от 1500,00) за сметка на работодателя е числото, което"
              + " ставката по КИД би дала — правдоподобно и без основание.");
    }
    assertEquals(
        6,
        result.getLines().size(),
        "шест реда: по два на всеки от трите дължими фонда. Седми ред значи, че ТЗПБ се е върнал"
            + " по някой от двата пътя — от собствен ред със ставка или от ставката по дейност.");
  }

  private static BgInsuranceContribution rateRow(
      String importId, String fund, String employerRate, String employeeRate) {
    BgInsuranceContribution c = new BgInsuranceContribution();
    c.setImportId(importId);
    c.setFundCode(fund);
    c.setEmployerRate(new BigDecimal(employerRate));
    c.setEmployeeRate(new BigDecimal(employeeRate));
    c.setBgEmployerRateKind(c.getEmployerRate().signum() == 0 ? "STRUCTURAL_ZERO" : "VALUE");
    c.setBgEmployeeRateKind(c.getEmployeeRate().signum() == 0 ? "STRUCTURAL_ZERO" : "VALUE");
    return c;
  }

  @Test
  @DisplayName("профил · ставките са тези на наетия — чл. 6 ал. 3 изр. 2 ги разпределя еднакво")
  void theRatesAreTheEmployeeRates() {
    List<String> header = BgSeed.header(CONTRIBUTIONS_FILE);
    for (String[] row : BgSeed.rows(CONTRIBUTIONS_FILE)) {
      if (!"14".equals(BgSeed.col(header, row, "bgInsuredTypeCode"))) {
        continue;
      }
      String fundCode = BgSeed.col(header, row, "fundCode");
      String regime = BgSeed.col(header, row, "bgUpfRegime");
      String employer = BgSeed.col(header, row, "employerRate");
      String insured = BgSeed.col(header, row, "employeeRate");

      if ("PENSION".equals(fundCode) && "WITH_UPF".equals(regime)) {
        assertEquals("8.2200", employer, "родените след 1959 г. — работодателска част на Пенсии");
        assertEquals("6.5800", insured, "и личната ѝ част; сборът е 14,8 на сто");
      } else if ("PENSION".equals(fundCode)) {
        assertEquals(
            "11.0200", employer, "родените преди 1960 г. — цялата вноска е във фонд Пенсии");
        assertEquals(
            "8.7800", insured, "сборът е 19,8 на сто, точно както го печата НАП разд. 11.1");
      } else if ("TZPB".equals(fundCode)) {
        // ⚠️ РЕДЪТ СЪЩЕСТВУВА ИМЕННО ЗА ДА Е НУЛА — виж theProfileChargesExactlyThreeFunds().
        assertEquals("0.0000", employer, "ТЗПБ не се дължи по КСО чл. 4 ал. 3");
        assertEquals(
            "0.0000", insured, "и от двете страни — рискът не съществува по това основание");
      } else if ("UPF".equals(fundCode)) {
        assertEquals("2.8000", employer, "чл. 157 ал. 3 КСО — 5 на сто, разпределени 2,80/2,20");
        assertEquals("2.2000", insured);
      } else {
        assertEquals("4.8000", employer, "ЗЗО чл. 40 ал. 1 т. 3 б. „в“ препраща към съотношението");
        assertEquals("3.2000", insured, "осемте на сто, разпределени 60:40");
      }
    }
  }

  // ═══ ДЕКЛАРАЦИЯ ОБР. 1 ═══

  @Test
  @DisplayName("т. 12 · кодът идва от договора и е 14, без превод и без подразбиране")
  void point12CarriesTheCivilContractCode() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        "14",
        record.getT12InsuredTypeCode(),
        "т. 12 е ПРЕПИСАН от EmploymentContract.bgInsuredTypeCode. Празно тук би значело, че"
            + " договорният път не приема код 14 — тогава фишът изобщо няма да се сглоби.");
  }

  @Test
  @DisplayName("т. 21 · базата е НАМАЛЕНАТА — 1500,00, не начислените 2000,00")
  void insuranceIncomeIsTheReducedRemuneration() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        0,
        GD_BASE.compareTo(record.getT21InsuranceIncome()),
        "⚠️ ТОВА Е ЦЯЛАТА РАЗЛИКА С ДУК В ЕДНО ЧИСЛО. КСО чл. 6 ал. 4 определя възнаграждението,"
            + " върху което се дължат вноски, СЛЕД намаляването му с разходите за дейността; чл. 6"
            + " ал. 3 (управителят) — не. 2000,00 тук значи, че приспадането е изчезнало от"
            + " веригата: вноски, завишени с една трета, върху доход, който изглежда правдоподобен"
            + " точно защото е сумата на перата.");
  }

  @Test
  @DisplayName("т. 22 · ДОО е САМО фонд „Пенсии“ — 8,22/6,58, без ОЗМ и без безработица")
  void theDooRateIsPensionOnly() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        0,
        new BigDecimal("8.22").compareTo(record.getT22DooRateEmployer()),
        "⚠️ 10,92 ТУК Е ЧИСЛОТО НА ДУК И НА НАЕТИЯ — Пенсии 8,22 + ОЗМ 2,10 + безработица 0,60."
            + " То е ставката, която публикуваните таблици наричат „ДОО за сметка на"
            + " работодателя“, и е точно грешката, която прави гражданския договор да изглежда"
            + " като трудов. Чл. 4 ал. 3 осигурява САМО за инвалидност поради общо заболяване, за"
            + " старост и за смърт.");
    assertEquals(
        0,
        new BigDecimal("6.58").compareTo(record.getT22DooRateInsured()),
        "т. 22 Б · същото за сметка на ЛИЦЕТО. 8,38 би било със събрани ОЗМ и безработица.");
  }

  @Test
  @DisplayName("т. 22.1 и т. 26 · здравното и УПФ вървят по общите съотношения")
  void healthAndUpfFollowTheStandardSplit() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        0,
        new BigDecimal("4.80").compareTo(record.getT221HealthRateEmployer()),
        "т. 22.1 А · ЗЗО чл. 40 ал. 1 т. 3 б. „в“: вноските се внасят „в СЪОТНОШЕНИЕТО ПО Т. 1 от"
            + " ВЪЗЛОЖИТЕЛЯ“ — тоест 60:40, както при наетия, и от платеца на възнаграждението.");
    assertEquals(
        0, new BigDecimal("3.20").compareTo(record.getT221HealthRateInsured()), "т. 22.1 Б");

    assertEquals(
        0,
        new BigDecimal("2.80").compareTo(record.getT26UpfRateEmployer()),
        "т. 26 А · УПФ по чл. 157 ал. 3. ⚠️ УПФ е т. 26, НЕ т. 22 — събран в т. 22, сборът става"
            + " 11,02 и изглежда напълно правдоподобен: това е ставката на родения ПРЕДИ 1960 г.,"
            + " за когото УПФ изобщо няма.");
    assertEquals(0, new BigDecimal("2.20").compareTo(record.getT26UpfRateInsured()), "т. 26 Б");
  }

  @Test
  @DisplayName("т. 22.2 · ТЗПБ НЕ се дължи — трудова злополука е риск на чл. 4 ал. 1")
  void noTzpbForTheCivilContract() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        0,
        record.getT222TzpbRate().signum(),
        "⚠️ И ТУК ЛИПСАТА Е ДВОЙНА, ЗАТОВА СВИДЕТЕЛЯТ Е ОТДЕЛЕН ОТ ГВРС. Фонд „Трудова злополука"
            + " и професионална болест“ се дължи по чл. 4 ал. 1, където рискът е изброен; ал. 3 не"
            + " го носи. При ДУК ставката идва от ДЕЙНОСТТА (bg_tzpb_rate по КИД) и е 0,47 — тоест"
            + " механизъм, който би написал число и тук, ако някой го извика безусловно. Профилът"
            + " на код 14 няма ред за фонд TZPB и перо няма от какво да се роди.");
  }

  @Test
  @DisplayName("т. 30 · ГВРС НЕ се дължи — гражданският договор не е трудово правоотношение")
  void noGvrsForTheCivilContract() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    // ⚠️ signum(), НЕ assertNull: генерираният getter за decimal връща ZERO за непопълнена колона,
    // тоест „не е писано“ и „нула“ са неразличими тук. Свидетел с assertNull пада винаги.
    assertEquals(
        0,
        record.getT30GvrsRate().signum(),
        "фонд „Гарантирани вземания на работниците и служителите“ иска работник или служител."
            + " КСО чл. 4 ал. 3 т. 5 и 6 назовават лицето отрицателно — „БЕЗ трудово"
            + " правоотношение“. 0,60 тук би било ставката на фонд „Безработица“, попаднала в"
            + " чужда точка.");
  }

  @Test
  @DisplayName("т. 29 · брутна РАБОТНА заплата няма — няма и работна заплата")
  void noGrossWageForTheCivilContract() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        0,
        record.getT29GrossWage().signum(),
        "заб. към т. 29/30 на Приложение № 1: попълват се за лицата, работещи по ТРУДОВИ"
            + " правоотношения. „Брутна работна заплата“ по НСОРЗ чл. 16 ал. 2 предполага трудов"
            + " договор — а възнаграждението по ЗЗД не е работна заплата.\n"
            + "  ⚠️ 2000,00 тук значи, че точката се пълни от флага на перото и НИКОЙ не пита за"
            + " вида на правоотношението — фикстурата вдига bgFormsGrossWage ИЗРИЧНО точно за да"
            + " не мине този свидетел по случайност.");
  }

  @Test
  @DisplayName("т. 34 · нетото НЕ се пълни — лицето по ал. 3 няма обезщетения от ДОО")
  void netPayIsNotFilled() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        0,
        record.getT34NetPay().signum(),
        "bgNoNetPay = true за код 14, и това е самата норма, не настройка на речника: чл. 4 ал. 3"
            + " осигурява лицето за инвалидност, старост и смърт — НЕ за общо заболяване и"
            + " майчинство и НЕ за безработица. Парично обезщетение от НОИ то не получава, тоест"
            + " нето, което да му бъде база (НПОПДОО чл. 32 ал. 1), няма. ⚠️ Същият изход като при"
            + " самоосигуряващото се лице и обратният на ДУК, комуто т. 34 СЕ полага — управителят"
            + " по ал. 1 т. 7 е осигурен и за ОЗМ.");
  }

  @Test
  @DisplayName("т. 31 и 31а · ПРАЗНИ за обикновения ГД — попълва ги само акционерът над 5 на сто")
  void taxPointsStayEmptyForTheCivilContract() {
    // ⚠️ ФИКСТУРАТА НОСИ И ДВЕТЕ ЧИСЛА, и точно затова свидетелят проверява нещо. Перото е обложено
    // (bgIsTaxable = true, 2000,00), а фишът носи удържания данък по чл. 43 (132,03). Без гейта
    // точката щеше да излезе с числото си — тоест този тест е ЧЕРВЕН без гейта.
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals(
        0,
        record.getT31TaxableIncome().signum(),
        "УКАЗАНИЕТО КЪМ Т. 31, ДОСЛОВНО: „Полето на т. 31 за лицата с код за вид осигурен 14 се"
            + " попълва САМО от акционери, притежаващи повече от 5 на сто от капитала на"
            + " акционерното дружество, за доходи по § 1, т. 26, букви „и“ и „л“.“ Обикновеният"
            + " граждански договор НЕ е такъв акционер, тоест точката му е празна.\n"
            + "  ⚠️ 2000,00 ТУК ЗНАЧИ, ЧЕ ГЕЙТЪТ ГО НЯМА и се пита само bgFillsT31. Флагът Е"
            + " вдигнат за код 14 и това е ВЯРНО — той преписва изброяването на указанието, в което"
            + " 14 наистина стои, и се сверява машинно от BgD1FlagsAgainstNaredbaTest. Условието"
            + " ОТГОРЕ на изброяването е второ правило и живее в"
            + " BgD1InsuredTypeRules#taxPointsUndecidable.\n"
            + "  ⚠️ И ПРАЗНО ТУК ЗНАЧИ „НЕПОПЪЛНЕНА ТОЧКА“, не „нула лева“. Числовото поле на Д1 не"
            + " различава двете: и записът, и файлът изписват непопълнената сума като 0 (виж"
            + " BgD1RecordAdapter#amount — „Numeric полета без стойност → 0“). Същият изход, който"
            + " т. 34 вече има за същия код 14.");
    assertEquals(
        0,
        record.getT31aTax().signum(),
        "т. 31а · СЪЩАТА граница, и указанието дава двете точки на акционера ЗАЕДНО (§ 1 т. 26 б."
            + " „и“ и „л“). 132,03 тук значи, че т. 31а още копира bgTaxAmount безусловно.\n"
            + "  ⚠️ И ЧИСЛОТО НЕ Е ОТ ТОЗИ ЧЛЕН. Т. 31а е „начислен месечен данък“ по чл. 42 ал. 4 —"
            + " данъкът, който ОСИГУРИТЕЛЯТ удържа месечно по трудово правоотношение. Данъкът на"
            + " лицето по чл. 4 ал. 3 т. 5 и 6 е АВАНСОВ по чл. 43 ал. 1, удържа се ТРИМЕСЕЧНО и се"
            + " декларира по чл. 55 и в справката по чл. 73 ал. 1 — през регистъра"
            + " BgIndividualIncomePayment. Изписан и в Д1, той е деклариран ДВА ПЪТИ и по два"
            + " несъвместими реда.");
  }

  @Test
  @DisplayName("т. 31/31а · празнотата се ОБЯВЯВА — акционерът над 5 на сто няма носител в модела")
  void theShareholderBoundaryIsAnnounced() {
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1RecordBuilder.build(
        gdSlip(),
        gdType(),
        period(MAY_2026),
        new BgD1Facts(null, null, null, CAP_2026_H1, LABOUR_CODES),
        findings);

    BgD1FindingDto announced = null;
    for (BgD1FindingDto f : findings) {
      if ("D1_T31_SHAREHOLDER_UNDECIDABLE".equals(f.getCode())) {
        announced = f;
      }
    }

    assertTrue(
        announced != null,
        "⚠️ ПРАЗНА ТОЧКА БЕЗ НАХОДКА Е РЕШЕНИЕ, ПРЕДСТАВЕНО КАТО ДАННИ. Признакът „акционер с над 5"
            + " на сто от капитала“ няма носител в модела, тоест за код 14 условието на т. 31 не"
            + " може да бъде проверено — нито потвърдено, нито отречено. Мълчаливо празната точка е"
            + " вярна за огромното мнозинство и НЕВЯРНА за акционера, а човекът пред екрана няма от"
            + " какво да разбере, че тук изобщо е било взето решение. Същият избор като при код 94"
            + " и D1_ART6_AL3_UNDECIDABLE.");
    assertEquals(
        BgD1FindingDto.SEVERITY_WARNING,
        announced.getSeverity(),
        "WARNING, не ERROR: записът е ПРИЕМЛИВ за НАП такъв, какъвто излиза — за всекиго освен за"
            + " акционера той е и верният. ERROR би спрял подаването на декларация, в която няма"
            + " какво да се поправи от екрана.");
    assertEquals(
        BgD1Point.T31_TAXABLE_INCOME,
        announced.getPoint(),
        "находката сочи т. 31 — точката, чието указание носи условието; т. 31а я следва по него.");
  }

  @Test
  @DisplayName("осигурителните точки на код 14 НЕ мърдат — изпразват се само данъчните")
  void theInsuranceSideIsUntouchedByTheTaxGate() {
    BgD1Record record = build(gdSlip(), gdType(), LABOUR_CODES);

    assertEquals("14", record.getT12InsuredTypeCode(), "т. 12 · кодът си остава 14");
    assertEquals(
        0,
        GD_BASE.compareTo(record.getT21InsuranceIncome()),
        "т. 21 · 1500,00 — базата по чл. 6 ал. 4 не се променя от това, че т. 31 е празна. ⚠️ Гейтът"
            + " е за ЗДДФЛ полетата; осигуряването по КСО чл. 4 ал. 3 т. 5 и 6 се декларира изцяло."
            + " Празна т. 21 тук значи, че изпразването е прехвърлило и осигурителната страна.");
    assertTrue(
        record.getT22DooRateEmployer().signum() > 0 && record.getT22DooRateInsured().signum() > 0,
        "т. 22 · пенсионните ставки стоят — 8,22/6,58");
    assertTrue(
        record.getT221HealthRateEmployer().signum() > 0
            && record.getT221HealthRateInsured().signum() > 0,
        "т. 22.1 · здравното стои — 4,80/3,20");
    assertTrue(
        record.getT26UpfRateEmployer().signum() > 0 && record.getT26UpfRateInsured().signum() > 0,
        "т. 26 · УПФ стои — 2,80/2,20");
  }

  // ═══ ДЕКЛАРАЦИЯ ОБР. 6 ═══

  @Test
  @DisplayName("Д6 · т. 10 носи САМО пенсионната вноска, а т. 15 (ГВРС) няма нито един лев")
  void declaration6CarriesOnlyTheThreeFunds() {
    Map<String, BigDecimal> byPoint = BgD6Aggregator.byPoint(Collections.singletonList(gdSlip()));

    assertEquals(
        0,
        new BigDecimal("222.00").compareTo(byPoint.get(BgD6Point.T10_DOO)),
        "123,30 + 98,70 — и НИЩО ДРУГО. ⚠️ При ДУК същата точка носи 395,40, защото в нея се"
            + " сливат четири фонда (Пенсии, ОЗМ, безработица и ТЗПБ). Тук фондът е един, тоест"
            + " всяко число над 222,00 значи, че в т. 10 е попаднал фонд, който по чл. 4 ал. 3 не"
            + " се дължи. ⚠️ И Д6 иска ЛЕВОВЕ по фонд, за разлика от Д1, която иска ПРОЦЕНТИ по"
            + " точка — един зелен свидетел за едната не значи нищо за другата.");
    assertEquals(
        0,
        new BigDecimal("75.00").compareTo(byPoint.get(BgD6Point.T12_UPF)),
        "т. 12 · 42,00 + 33,00. УПФ е ДЗПО и има СВОЯ точка — слят в т. 10, преводът щеше да отиде"
            + " в НОИ вместо в пенсионноосигурителното дружество.");
    assertEquals(
        0,
        new BigDecimal("120.00").compareTo(byPoint.get(BgD6Point.T14_HEALTH)),
        "т. 14 · 72,00 + 48,00, двете половини на здравното заедно.");

    assertNull(
        byPoint.get(BgD6Point.T15_GVRS),
        "⚠️ ТУК null Е РАЗЛИЧИМ ОТ НУЛА, за разлика от полетата на записа: това е Map, а не"
            + " генериран getter. Липсващият ключ значи „нито едно перо не сочи този фонд“, тоест"
            + " ГВРС НЕ СЕ ДЪЛЖИ — а не че се дължи и е нула.");
  }

  @Test
  @DisplayName("Д6 · сборът на всички точки е точно сборът на шестте вноски")
  void declaration6HasNoOtherPoints() {
    Map<String, BigDecimal> byPoint = BgD6Aggregator.byPoint(Collections.singletonList(gdSlip()));

    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal v : byPoint.values()) {
      total = total.add(v);
    }

    assertEquals(
        0,
        new BigDecimal("417.00").compareTo(total),
        "222,00 + 75,00 + 120,00. ⚠️ ОБРАТНАТА ПОЛОВИНА НА ТРИТЕ СВИДЕТЕЛЯ ОТГОРЕ: те казват какво"
            + " ИМА в трите точки, а този — че НЯМА нищо другаде. Без него нова точка (ТЗПБ, ГВРС,"
            + " учителски фонд) би се появила, без нито едно твърдение да падне.");
  }

  // ═══ наетите и ДУК остават непроменени ═══

  @Test
  @DisplayName("код 01 · т. 29 се пълни — трудовото правоотношение НЕ се променя от тази фаза")
  void theEmployeeKeepsGrossWage() {
    BgPaySlip slip = gdSlip();
    slip.setEmploymentContract(contract("01"));

    BgD1Record record = build(slip, employeeType(), LABOUR_CODES);

    assertEquals(
        0,
        GROSS.compareTo(record.getT29GrossWage()),
        "⚠️ ОБРАТНАТА ПОЛОВИНА НА СВИДЕТЕЛЯ ЗА ГД, и без нея той щеше да мине и при т. 29,"
            + " занулена за ВСИЧКИ. Код 01 носи ред за фонд ГВРС в речника, тоест Е трудово"
            + " правоотношение, и брутната работна заплата му се полага.");
  }

  @Test
  @DisplayName("код 01 · т. 34 се пълни — bgNoNetPay е свойство на КОДА, не поведение на строителя")
  void theEmployeeKeepsNetPay() {
    BgPaySlip slip = gdSlip();
    slip.setEmploymentContract(contract("01"));

    BgD1Record record = build(slip, employeeType(), LABOUR_CODES);

    assertTrue(
        record.getT34NetPay().signum() > 0,
        "⚠️ ОБРАТНАТА ПОЛОВИНА НА СВИДЕТЕЛЯ ЗА Т. 34. Занулена за всички, точката щеше да изглежда"
            + " вярна за ГД и щеше да е грешна за наетия, комуто НОИ смята обезщетение.");
  }

  @Test
  @DisplayName("код 01 · т. 31 и 31а се пълнят С ЧИСЛАТА СИ — гейтът е за ЕДИН код")
  void theEmployeeKeepsBothTaxPoints() {
    BgPaySlip slip = gdSlip();
    slip.setEmploymentContract(contract("01"));

    BgD1Record record = build(slip, employeeType(), LABOUR_CODES);

    assertEquals(
        0,
        GROSS.compareTo(record.getT31TaxableIncome()),
        "⚠️ ОБРАТНАТА ПОЛОВИНА НА СВИДЕТЕЛЯ ЗА ГД, и без нея изпразването щеше да мине зелено,"
            + " докато е занулило т. 31 за ВСЕКИГО. Наетият по код 01 е § 1 т. 26 б. „а“ ЗДДФЛ —"
            + " трудово правоотношение, — данъкът му е месечният по чл. 42 ал. 4 и точката носи"
            + " начисления облагаем доход. ⚠️ Един и същ фиш и в двата свидетеля: единствената"
            + " разлика е кодът в т. 12, тоест ако този падне заедно с другия, гейтът е по фиша, а"
            + " не по кода.");
    assertEquals(
        0,
        CHL43_TAX.compareTo(record.getT31aTax()),
        "т. 31а · числото на фиша се пренася непроменено. ⚠️ Стойността тук е ирелевантна за"
            + " нормата — важното е, че точката НОСИ каквото фишът е удържал. Занулена, тя щеше да"
            + " каже на НАП, че работодателят не е удържал данък.");
  }

  @Test
  @DisplayName("код 10 · ДУК запазва двете точки — § 1 т. 26 б. „з“ го прави ТПО за данъка")
  void theManagementContractKeepsBothTaxPoints() {
    BgPaySlip slip = gdSlip();
    slip.setEmploymentContract(contract("10"));

    BgD1Record record = build(slip, managementType(), LABOUR_CODES);

    assertEquals(
        0,
        GROSS.compareTo(record.getT31TaxableIncome()),
        "⚠️ ДУК Е СЪСЕДЪТ, КОЙТО НАЙ-ЛЕСНО БИ ПАДНАЛ ЗАЕДНО С ГД: и двамата са без трудов договор по"
            + " КТ, и двамата нямат брутна работна заплата по т. 29. За ДАНЪКА обаче § 1 т. 26 б."
            + " „з“ ДР ЗДДФЛ дословно нарежда „правоотношенията по договори за управление и"
            + " контрол“ сред трудовите — управителят се облага по чл. 42, не по чл. 43, и двете му"
            + " точки се полагат. Гейт „без трудов договор“ вместо „код 14“ би изпразнил и него.");
    assertEquals(
        0,
        CHL43_TAX.compareTo(record.getT31aTax()),
        "т. 31а · пренася се както преди. ⚠️ Числото идва от фиша на фикстурата и тук не твърди"
            + " нищо за размера на данъка на ДУК — той се проверява с ЧИСЛА в BgDukRecordTest"
            + " (172,44 върху истинския му фиш).");
  }

  @Test
  @DisplayName("речникът недостъпен · т. 29 се пълни и правилото ОСТАВА за валидатора")
  void unreadDictionaryDoesNotSilenceThePoint() {
    BgD1Record record = build(gdSlip(), gdType(), null);

    assertEquals(
        0,
        GROSS.compareTo(record.getT29GrossWage()),
        "⚠️ null НЕ Е „няма трудово правоотношение“, а „не е четено“ — липсваща база е състояние"
            + " на средата. Занулена тук, точката би изчезнала тихо и за наетия; попълнена,"
            + " BgD1Validator обявява, че правилото НЕ е проверено.");
  }
}
