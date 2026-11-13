package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgSelfInsuredActivityPeriod;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ЕДИН ФИШ НА САМООСИГУРЯВАЩО СЕ ЛИЦЕ → ЕДИН ЗАПИС В Д1.
 *
 * <p><b>Какво се проверява.</b> Че шестте неща, които строителят на записа иначе чете от трудовия
 * договор, идват от РЕГИСТРАЦИЯТА, когато фишът е неин: лицето, видът осигурен, кодът на
 * задълженото лице (т. 4), НКПД/НКИД (т. 12.1–12.3) и границите на осигуряването (т. 14/15).
 *
 * <p><b>⚠️ И ЧЕ ТРИ ТОЧКИ ОСТАВАТ ПРАЗНИ ПО НОРМА, а не по липса на данни.</b> Разликата е
 * измерима: празна т. 12.1 при наетия значи „НКПД не е попълнен“ и се поправя; празна т. 12.1 при
 * самоосигуряващия се значи „наредбата не я иска“ и поправка НЯМА. Ако някой ден флагът {@code
 * bgAppliesMod} на код 12 стане {@code true}, този тест ще падне — и това е точно целта му.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate: {@link BgD1RecordBuilder#build} е чиста функция.
 */
class BgD1SelfInsuredRecordTest {

  private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

  /** Избраният доход, върху който вноските на фиша са сметнати — КСО чл. 6 ал. 8. */
  private static final BigDecimal BASE = new BigDecimal("1000.00");

  // ═══ строители ═══

  private static Period march() {
    Period p = new Period();
    p.setFromDate(MARCH);
    p.setToDate(LocalDate.of(2026, 3, 31));
    return p;
  }

  private static BgContributionFund fund(String code, String point) {
    BgContributionFund f = new BgContributionFund();
    f.setCode(code);
    f.setBgD1Field(point);
    return f;
  }

  /**
   * Перо на вноска, ИЗЦЯЛО за сметка на лицето.
   *
   * <p>⚠️ Само DEBIT редове, и това е самото твърдение за самоосигуряващото се лице: КСО чл. 6 ал.
   * 8 казва „за сметка на осигурените лица“. CREDIT перо тук би значело осигурител, какъвто няма.
   *
   * <p>⚠️ {@code bgQuantity} НОСИ БАЗАТА и точно тя пълни т. 21 — виж {@code
   * BgD1ContributionAggregator#insuranceIncomeBaseFromLines}. Фикстура без количество щеше да
   * прекара т. 21 през резервния ѝ път (сбора на перата) и свидетелят щеше да мери друго.
   */
  private static BgPaySlipLine own(int number, String amount, String rate, BgContributionFund f) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(BgPaySlipResolver.DEBIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgQuantity(BASE);
    l.setBgRate(new BigDecimal(rate));
    l.setBgContributionFund(f);
    l.setBgIsInsuranceIncome(Boolean.FALSE);
    l.setBgIsTaxable(Boolean.FALSE);
    return l;
  }

  /**
   * Речниковият ред на самоосигуряващ се код, с флаговете КАКТО СА В SEED-А.
   *
   * <p>⚠️ И четирите са измерени, не избрани: {@code bgAppliesMod = false} (т. 12.1–12.3 не се
   * попълват), {@code bgZeroWorkTime = true} (т. 16 позиция 3 е НУЛА — указанието изброява дословно
   * 9, 10, 12, 13, 14, 15, 22, 41, 71, 82, 86, 87, 88, 89, 91, 92 и 96), {@code bgNoHours = true}
   * (т. 16.7/16.8 празни) и {@code bgNoNetPay = true} (т. 34 празна).
   */
  private static BgInsuredType selfInsuredType(String code, boolean fillsT31) {
    BgInsuredType t = new BgInsuredType();
    t.setCode(code);
    t.setBgAppliesMod(Boolean.FALSE);
    t.setBgZeroWorkTime(Boolean.TRUE);
    t.setBgNoHours(Boolean.TRUE);
    t.setBgNoNetPay(Boolean.TRUE);
    t.setBgFillsT31(fillsT31);
    return t;
  }

  private static BgSelfInsuredActivityPeriod activity(LocalDate from, LocalDate to) {
    BgSelfInsuredActivityPeriod p = new BgSelfInsuredActivityPeriod();
    p.setFromDate(from);
    p.setToDate(to);
    return p;
  }

  private static BgSelfInsuredRegistration registration(
      String insuredCode, String obligedCode, List<BgSelfInsuredActivityPeriod> periods) {
    Partner person = new Partner();
    person.setName("ПЕТРОВ");
    person.setFirstName("Иван");
    person.setBgMiddleName("Георгиев");

    Employee employee = new Employee();
    employee.setBgPersonalIdNumber("7501015678");
    employee.setBgPersonalIdType("EGN");
    employee.setContactPartner(person);
    employee.setBgIsSelfInsured(Boolean.TRUE);

    BgSelfInsuredRegistration reg = new BgSelfInsuredRegistration();
    reg.setEmployee(employee);
    reg.setBgIsSelfInsured(Boolean.TRUE);
    reg.setBgInsuredTypeCode(insuredCode);
    reg.setBgObligedPersonCode(obligedCode);
    reg.setBgSolScope("ALL_RISKS");
    reg.setBgScopeYear(2026);
    reg.setBgSelectedIncome(BASE);
    reg.setBgActivityPeriodList(new ArrayList<>(periods));
    return reg;
  }

  /** Фиш на самоосигуряващо се лице: четири вноски, всичките лични. */
  private static BgPaySlip slip(BgSelfInsuredRegistration reg) {
    BgContributionFund pension = fund("PENSION", BgD1Point.T22_DOO);
    BgContributionFund ozm = fund("OZM", BgD1Point.T22_DOO);
    BgContributionFund upf = fund("UPF", BgD1Point.T26_UPF);
    BgContributionFund health = fund("HEALTH", BgD1Point.T22_1_HEALTH);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(own(501, "148.00", "14.80", pension));
    lines.add(own(505, "35.00", "3.50", ozm));
    lines.add(own(513, "50.00", "5.00", upf));
    lines.add(own(517, "80.00", "8.00", health));

    BgPaySlip s = new BgPaySlip();
    s.setReference("FISH-2026-03-0007");
    s.setBgPaySlipLineList(lines);
    s.setBgSelfInsuredRegistration(reg);
    return s;
  }

  private static BgD1Record build(BgPaySlip slip, BgInsuredType type, List<BgD1FindingDto> out) {
    return BgD1RecordBuilder.build(slip, type, march(), BgD1Facts.none(), out);
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  /**
   * ⚠️ Т. 4 ИДВА ОТ РЕГИСТРАЦИЯТА, А НЕ ОТ ДРУЖЕСТВОТО — то просто не съществува.
   *
   * <p>Указанието към т. 4 иска „ЕИК по БУЛСТАТ, ЕИК по ЗТРРЮЛНЦ, служебен номер, издаден от НАП“ и
   * не различава случаите. Ако строителят стига до него ЕДИНСТВЕНО през {@code
   * contract.getPayCompany().getPartner().getRegistrationCode()}, за фиш без договор т. 4 остава
   * празна и декларацията тръгва без задължено лице.
   */
  @Test
  @DisplayName("⚠️ т. 4 · кодът на задълженото лице идва от РЕГИСТРАЦИЯТА, не от payCompany")
  void theObligedPersonCodeComesFromTheRegistration() {
    BgSelfInsuredRegistration reg =
        registration("12", "1234567890", Arrays.asList(activity(LocalDate.of(2024, 5, 1), null)));
    BgD1Record record = build(slip(reg), selfInsuredType("12", true), new ArrayList<>());

    assertEquals("1234567890", record.getT4ObligedPersonCode());
    assertEquals("12", record.getT12InsuredTypeCode());
    // Лицето минава по същия път като при наетия — Employee е общият дом на двата носителя.
    assertEquals("7501015678", record.getT5PersonalId());
    assertEquals("ПЕТРОВ", record.getT6Surname());
    assertEquals("ИГ", record.getT7Initials());
  }

  /**
   * ⚠️ ТРИТЕ ТОЧКИ, КОИТО ОСТАВАТ ПРАЗНИ ПО НОРМА — и една, която остава празна по флаг.
   *
   * <p>т. 12.1/12.2/12.3 не се попълват, защото {@code bgAppliesMod = false}; т. 34 — защото {@code
   * bgNoNetPay = true}. И четирите са измерени в seed-а за кодове 12, 13, 22 и 41.
   */
  @Test
  @DisplayName("⚠️ т. 12.1–12.3 и т. 34 остават ПРАЗНИ — наредбата не ги иска за СОЛ")
  void theModAndNetPayPointsStayEmpty() {
    BgSelfInsuredRegistration reg =
        registration("12", "1234567890", Arrays.asList(activity(LocalDate.of(2024, 5, 1), null)));
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = build(slip(reg), selfInsuredType("12", true), findings);

    // ⚠️ ТРИТЕ СА НИЗОВЕ — за тях null е истински отговор.
    assertNull(record.getT121QualificationGroup());
    assertNull(record.getT122ActivityCode());
    assertNull(record.getT123ActivityRow());
    // ⚠️ А Т. 34 Е DECIMAL, И ТОВА Е СЪЩИЯТ КАПАН, СРЕЩУ КОЙТО МОДУЛЪТ ИМА ЦЯЛ ТЕСТ.
    // Генерираният getter връща BigDecimal.ZERO за непопълнена колона, тоест assertNull ТУК
    // ПАДА ВИНАГИ. ⚠️ BgDefaultedGetterTest НЕ го хваща: той търси
    // сравнения „getFoo() == null“, а „assertNull(getFoo())“ е друга форма на същата грешка.
    // Записано, защото следващият свидетел за празна точка ще стъпи на същия ръб.
    assertEquals(0, record.getT34NetPay().signum(), "т. 34 остава ненапълнена, тоест нула");

    // ⚠️ И НИТО ЕДНА НАХОДКА ЗА ТЯХ. „Липсва нето“ би пратило човек да попълва нещо, което
    // наредбата забранява — точно разликата между празно по норма и празно по липса.
    for (BgD1FindingDto f : findings) {
      assertTrue(
          !"34".equals(f.getPoint()),
          "т. 34 не бива да ражда находка при bgNoNetPay: " + f.describe());
    }
  }

  /**
   * ⚠️ Т. 21 РАБОТИ БЕЗ НИТО ЕДИН НОВ РЕД — и това е самата причина носителят да е един.
   *
   * <p>Осигурителният доход се чете от {@code bgQuantity} на перата от 5xx, тоест от КАПНАТАТА
   * база, с която вноските са сметнати. Тя не знае откъде е дошъл фишът. Същото важи за т. 22 и т.
   * 26: ставките идват от самите пера.
   */
  @Test
  @DisplayName(
      "⚠️ т. 21 и ставките идват от ПЕРАТА — 1000,00 и 18,30 % при лицето, 0 при осигурителя")
  void theIncomeAndRatesComeFromTheLines() {
    BgSelfInsuredRegistration reg =
        registration("12", "1234567890", Arrays.asList(activity(LocalDate.of(2024, 5, 1), null)));
    BgD1Record record = build(slip(reg), selfInsuredType("12", true), new ArrayList<>());

    assertEquals(0, BASE.compareTo(record.getT21InsuranceIncome()));
    // 14,80 (Пенсии) + 3,50 (ОЗМ) = 18,30 за ДОО без ТЗПБ, ИЗЦЯЛО при лицето.
    assertEquals(0, new BigDecimal("18.30").compareTo(record.getT22DooRateInsured()));
    assertEquals(0, BigDecimal.ZERO.compareTo(record.getT22DooRateEmployer()));
    assertEquals(0, new BigDecimal("8.00").compareTo(record.getT221HealthRateInsured()));
    assertEquals(0, BigDecimal.ZERO.compareTo(record.getT221HealthRateEmployer()));
    assertEquals(0, new BigDecimal("5.00").compareTo(record.getT26UpfRateInsured()));
    assertEquals(0, BigDecimal.ZERO.compareTo(record.getT26UpfRateEmployer()));
    // ⚠️ ТЗПБ НЯМА — КСО чл. 4 ал. 3 не изброява този фонд за самоосигуряващите се.
    assertEquals(0, BigDecimal.ZERO.compareTo(record.getT222TzpbRate()));
  }

  /**
   * ⚠️ Т. 14/15 ИДВАТ ОТ ПЕРИОДИТЕ НА ДЕЙНОСТ, и прекъсването е ПРОЛУКАТА между два реда.
   *
   * <p>Дейност 01.03–10.03 и 20.03–31.03 значи: осигуряването не възниква вътре в месеца (то тече
   * от по-рано? — не, тук първият период започва точно на 01.03, тоест НЕ е възникване вътре, а
   * начало на месеца), прекъсва на 10-о и се възобновява на 20-о.
   */
  @Test
  @DisplayName("⚠️ т. 14/15 · прекъсването между два периода на дейност дава втора двойка")
  void theActivityGapProducesASecondPair() {
    BgSelfInsuredRegistration reg =
        registration(
            "12",
            "1234567890",
            Arrays.asList(
                activity(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 10)),
                activity(LocalDate.of(2026, 3, 20), null)));
    BgD1Record record = build(slip(reg), selfInsuredType("12", true), new ArrayList<>());

    // Първият отрязък свършва на 10-о — прекъсване, тоест т. 15 (1) се попълва.
    assertEquals("10", record.getT15Day1());
    // Вторият започва на 20-о — възобновяване, тоест т. 14 (2).
    assertEquals("20", record.getT14Day2());
    // ⚠️ И НЯМА трета двойка: две дейности дават точно едно прекъсване.
    assertNull(record.getT14Day3());
    assertNull(record.getT15Day3());
  }

  /**
   * ⚠️ ЗАПОЧВАНЕ НА ДЕЙНОСТ ВЪТРЕ В МЕСЕЦА пълни т. 14 (1); продължаващата дейност — не.
   *
   * <p>Указанието към т. 14 иска ден „от който осигуряването е ВЪЗНИКНАЛО/ВЪЗОБНОВЕНО“. Записано
   * „01“ на всеки месец, полето би твърдяло, че осигуряването е възниквало наново дванайсет пъти в
   * годината.
   */
  @Test
  @DisplayName("⚠️ т. 14 · започване на дейност на 12-о се вижда; текуща отпреди — не")
  void onlyAnActivityStartingInsideTheMonthFillsPoint14() {
    BgSelfInsuredRegistration starting =
        registration("12", "1234567890", Arrays.asList(activity(LocalDate.of(2026, 3, 12), null)));
    assertEquals(
        "12", build(slip(starting), selfInsuredType("12", true), new ArrayList<>()).getT14Day1());

    BgSelfInsuredRegistration ongoing =
        registration("12", "1234567890", Arrays.asList(activity(LocalDate.of(2025, 9, 1), null)));
    assertNull(
        build(slip(ongoing), selfInsuredType("12", true), new ArrayList<>()).getT14Day1(),
        "дейност отпреди месеца НЕ е възникване в него");
  }

  /**
   * ⚠️ КОД 13 НЕ ПОПЪЛВА Т. 31, а код 12 попълва — и разликата идва от seed-а, не от кода.
   *
   * <p>Заб. 1 към т. 31 дава полето на съдружниците и собствениците на ЕООД за доходи по § 1 т. 26
   * б. „и“ ЗДДФЛ; земеделският стопанин не е сред тях. Измерено: {@code bgFillsT31} е {@code true}
   * за 12 и {@code false} за 13.
   */
  @Test
  @DisplayName("⚠️ т. 31 · флагът решава, не носителят — код 12 я попълва, код 13 не")
  void theTaxableIncomePointFollowsTheFlagNotTheCarrier() {
    BgSelfInsuredRegistration reg =
        registration("13", "1234567890", Arrays.asList(activity(LocalDate.of(2024, 5, 1), null)));
    BgPaySlip s = slip(reg);
    // ⚠️ ОБЛАГАЕМО ПЕРО, БЕЗ КОЕТО СВИДЕТЕЛЯТ НЕ РАЗЛИЧАВА НИЩО. Без него и двата кода дават нула
    // — код 13 защото флагът забранява точката, а код 12 защото няма какво да се сумира — и тестът
    // би минал ЗЕЛЕН, без да е доказал, че флагът изобщо се чете. Сумата е доход по § 1 т. 26
    // б. „и“ ЗДДФЛ: заб. 1 към т. 31 дава полето точно на съдружниците и собствениците на ЕООД.
    BgPaySlipLine taxable = new BgPaySlipLine();
    taxable.setBgLineNumber(100);
    taxable.setBgSign(BgPaySlipResolver.CREDIT);
    taxable.setBgAmount(new BigDecimal("900.00"));
    taxable.setBgIsTaxable(Boolean.TRUE);
    taxable.setBgIsInsuranceIncome(Boolean.FALSE);
    s.getBgPaySlipLineList().add(taxable);

    // ⚠️ signum(), НЕ assertNull: генерираният getter за decimal връща ZERO, не null.
    assertEquals(
        0,
        build(s, selfInsuredType("13", false), new ArrayList<>()).getT31TaxableIncome().signum(),
        "код 13 НЕ е сред 30-те кода на т. 31 — точката остава ненапълнена");
    assertEquals(
        0,
        new BigDecimal("900.00")
            .compareTo(
                build(s, selfInsuredType("12", true), new ArrayList<>()).getT31TaxableIncome()),
        "код 12 Е сред тях — заб. 1 към т. 31 за доходи по § 1 т. 26 б. „и“ ЗДДФЛ");
  }

  /**
   * ⚠️ НОСИТЕЛЯТ КАТО ЧИСТА ФУНКЦИЯ — двете страни и трите ѝ отговора.
   *
   * <p>{@link BgD1Carrier} е мястото, където разклонението живее ВЕДНЪЖ. Проверено тук поотделно,
   * защото шест точки стъпват на него и всяка от тях би скрила дефекта му зад собственото си число.
   */
  @Test
  @DisplayName("НОСИТЕЛ · договор, регистрация и празен фиш дават три различни отговора")
  void theCarrierAnswersForBothSides() {
    BgPaySlip empty = new BgPaySlip();
    assertTrue(!BgD1Carrier.of(empty).isPresent());
    assertTrue(!BgD1Carrier.of(empty).isSelfInsured());
    assertNull(BgD1Carrier.of(empty).insuredTypeCode());
    assertNull(BgD1Carrier.of(null).employee());

    EmploymentContract contract = new EmploymentContract();
    contract.setBgInsuredTypeCode("01");
    BgPaySlip employed = new BgPaySlip();
    employed.setEmploymentContract(contract);
    assertTrue(BgD1Carrier.of(employed).isPresent());
    assertTrue(!BgD1Carrier.of(employed).isSelfInsured());
    assertEquals("01", BgD1Carrier.of(employed).insuredTypeCode());
    // ⚠️ И дружеството ОСТАВА достъпно по пътя на договора — т. 12.2/12.3 не бива да се счупят.
    assertNull(
        BgD1Carrier.of(employed).company(), "този договор няма payCompany — но пътят е негов");

    BgSelfInsuredRegistration reg =
        registration("12", "999", Arrays.asList(activity(LocalDate.of(2026, 1, 1), null)));
    BgD1Carrier self = BgD1Carrier.of(slip(reg));
    assertTrue(self.isSelfInsured());
    assertEquals("12", self.insuredTypeCode());
    assertEquals("999", self.obligedPersonCode());
    // ⚠️ ПРИ САМООСИГУРЯВАЩИЯ СЕ ДРУЖЕСТВО НЯМА — т. 12.1–12.3 нямат откъде да се напълнят и това
    // е вярно, а не липса.
    assertNull(self.company());
    assertEquals(LocalDate.of(2026, 1, 1), self.insuranceStart());
    assertNull(self.insuranceEnd(), "празното „до“ значи ВСЕ ОЩЕ ТЕЧЕ, не един ден");
  }
}
