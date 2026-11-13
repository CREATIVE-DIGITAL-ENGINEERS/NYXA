package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СПРАВКАТА ПО ЧЛ. 73 АЛ. 1 (ОБРАЗЕЦ 5-2001) — сливането на регистъра в редовете на Част ІІ.
 *
 * <p>Чист тест: без база и без Guice. Числата по-долу са ИЗВЕДЕНИ от ставките в каталога за 2025
 * г., а не избрани: код 3073 носи 25 на сто нормативни разходи по чл. 29 ал. 1 т. 3 и 10 на сто
 * авансов данък по чл. 43 ал. 4, тоест 1000,00 лв. бруто дават основа 750,00 и данък 75,00. Двете
 * плащания дават 2000,00 в колона 11 и 150,00 в колона 13 — а основата 1500,00 НЕ се появява никъде
 * в справката.
 */
class BgArt73Para1AggregatorTest {

  private static final int TAX_YEAR = 2025;

  // ═══ СВИДЕТЕЛ 1 · ДВЕ ПЛАЩАНИЯ ПО ЕДИН КОД СА ЕДИН РЕД ═══════════════════════════════

  @Test
  @DisplayName(
      "СВИДЕТЕЛ · две плащания по 1000 лв. с код 3073 дават ЕДИН ред: к.11 = 2000, к.13 = 150")
  void twoPaymentsOfOneCodeCollapseIntoOneAnnualRow() {
    BgIncomeType consulting = type("3073", false, false);
    Partner person = partner(1L, "Петров");

    List<BgIndividualIncomePayment> register =
        Arrays.asList(
            payment(person, consulting, "2025-03-14", "1000.00", "0.00", "75.00"),
            payment(person, consulting, "2025-11-02", "1000.00", "0.00", "75.00"));

    List<BgArt73Para1Line> lines = BgArt73Para1Aggregator.aggregate(register, TAX_YEAR).getLines();

    assertEquals(1, lines.size(), "едно лице, един код, една година — един ред: " + lines);
    BgArt73Para1Line line = lines.get(0);
    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(line.getGrossAmount()),
        "колона 11 е БРУТО — сборът на двете плащания, без приспаднати разходи: " + line);
    assertEquals(
        0,
        new BigDecimal("150.00").compareTo(line.getTaxAmount()),
        "колона 13 е сборът на удържания авансов данък 2 × 75,00: " + line);
    assertEquals(
        0, BigDecimal.ZERO.compareTo(line.getInsuranceAmount()), "колона 12 без ЗОВ: " + line);
    assertEquals(2, line.getPaymentCount(), "два слети реда от регистъра");
    assertEquals(BgArt73Para1Aggregator.CORRECTION_MAIN, line.getCorrectionCode());
    assertSame(person, line.getPartner(), "редът сочи лицето — колони 4 до 8");
  }

  /**
   * ⚠️ РАЗХОДИТЕ И ОСНОВАТА НЯМАТ КОЛОНА В ТАЗИ СПРАВКА. Указание т. 8, дословно: „в колона 11 се
   * посочва брутният размер на дохода, т.е. без приспадане на признатите разходи и/или направените
   * удръжки от платеца на дохода“. Свидетелят пази точно това: регистърът носи основа 1500,00 за
   * двете плащания, а справката показва 2000,00.
   */
  @Test
  @DisplayName("⚠️ КОЛОНА 11 · брутото, а не основата — 2000, при основа 1500 в регистъра")
  void columnElevenIsGrossAndNotTheTaxableBase() {
    BgIncomeType consulting = type("3073", false, false);
    Partner person = partner(1L, "Петров");
    BgIndividualIncomePayment first =
        payment(person, consulting, "2025-03-14", "1000.00", "0.00", "75.00");
    first.setExpenseAmount(new BigDecimal("250.00"));
    first.setTaxableBase(new BigDecimal("750.00"));
    BgIndividualIncomePayment second =
        payment(person, consulting, "2025-11-02", "1000.00", "0.00", "75.00");
    second.setExpenseAmount(new BigDecimal("250.00"));
    second.setTaxableBase(new BigDecimal("750.00"));

    BgArt73Para1Line line =
        BgArt73Para1Aggregator.aggregate(Arrays.asList(first, second), TAX_YEAR).getLines().get(0);

    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(line.getGrossAmount()),
        "основата 1500,00 не е стигнала до колона 11: " + line);
  }

  // ═══ СВИДЕТЕЛ 2 · СБОРНИТЕ КОДОВЕ ════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "СВИДЕТЕЛ · код 5031 с три лица дава ЕДИН ред: к.14 = 3, без имена, обща сума в к.11")
  void theAggregateCodeGivesOneRowWithThePersonCountAndNoNames() {
    BgIncomeType scrap = type("5031", true, true);

    List<BgIndividualIncomePayment> register =
        Arrays.asList(
            payment(partner(11L, "Иванов"), scrap, "2025-02-01", "8.00", "0.00", "0.00"),
            payment(partner(12L, "Георгиев"), scrap, "2025-05-06", "9.50", "0.00", "0.00"),
            payment(partner(13L, "Димитров"), scrap, "2025-09-19", "7.00", "0.00", "0.00"));

    List<BgArt73Para1Line> lines = BgArt73Para1Aggregator.aggregate(register, TAX_YEAR).getLines();

    assertEquals(1, lines.size(), "три лица по сборен код дават ЕДИН ред: " + lines);
    BgArt73Para1Line line = lines.get(0);
    assertTrue(line.isAggregate(), "кодът е сборен");
    assertEquals(3, line.getPersonCount(), "колона 14 — брой лица: " + line);
    assertEquals(
        0,
        new BigDecimal("24.50").compareTo(line.getGrossAmount()),
        "колона 11 — общата сума за всички лица: " + line);
    assertNull(line.getPartner(), "⚠️ колони 2–9 не се попълват: редът НЕ сочи лице");
    assertNull(line.getIdentifierType(), "колона 3 не се попълва за сборен ред");
    assertNull(line.getCountryCode(), "колона 9 не се попълва за сборен ред");
    assertFalse(line.isForeign(), "колона 2 не се попълва за сборен ред");
  }

  /**
   * ⚠️ ТОВА Е РАЗЛИКАТА, КОЯТО {@code isAggregate} НЕ НОСИ. Указание т. 5, дословно: „Колони от 2
   * до 9, Част ІІ не се попълват за изплатени доходи с кодове 5031, 8141 и 820. Колона 14 „Брой
   * лица“ се попълва САМО за доходи с кодове 5031 и 8141.“ Тоест 820 е сборен ред БЕЗ брой лица.
   */
  @Test
  @DisplayName("⚠️ КОЛОНА 14 · код 820 е СБОРЕН, но брой лица НЯМА — за разлика от 5031 и 8141")
  void theCondominiumCodeIsAggregateButCarriesNoPersonCount() {
    BgIncomeType condominium = type("820", true, false);

    List<BgIndividualIncomePayment> register =
        Arrays.asList(
            payment(partner(21L, "Стоянов"), condominium, "2025-04-01", "300.00", "0.00", "30.00"),
            payment(partner(22L, "Николов"), condominium, "2025-08-01", "200.00", "0.00", "20.00"));

    List<BgArt73Para1Line> lines = BgArt73Para1Aggregator.aggregate(register, TAX_YEAR).getLines();

    assertEquals(1, lines.size(), "сборният код е един ред: " + lines);
    BgArt73Para1Line line = lines.get(0);
    assertTrue(line.isAggregate(), "820 е сборен ред — колони 2–9 не се пълнят");
    assertNull(line.getPartner(), "без имена");
    assertNull(
        line.getPersonCount(),
        "⚠️ колона 14 се пълни САМО за 5031 и 8141 — 820 не е сред тях: " + line);
    assertEquals(
        0, new BigDecimal("500.00").compareTo(line.getGrossAmount()), "общата сума: " + line);
  }

  @Test
  @DisplayName("СБОРЕН · код 8141 (дивиденти до 100 лв.) също носи брой лица")
  void theSmallDividendCodeAlsoCarriesThePersonCount() {
    BgIncomeType smallDividends = type("8141", true, true);

    List<BgArt73Para1Line> lines =
        BgArt73Para1Aggregator.aggregate(
                Arrays.asList(
                    payment(
                        partner(31L, "Петкова"),
                        smallDividends,
                        "2025-06-30",
                        "80.00",
                        "0.00",
                        "4.00"),
                    payment(
                        partner(32L, "Тодоров"),
                        smallDividends,
                        "2025-06-30",
                        "60.00",
                        "0.00",
                        "3.00")),
                TAX_YEAR)
            .getLines();

    assertEquals(1, lines.size());
    assertEquals(2, lines.get(0).getPersonCount(), "колона 14 — брой лица");
  }

  // ═══ СВИДЕТЕЛ 3 · НЕОБЛАГАЕМИТЕ ДОХОДИ ═══════════════════════════════════════════════

  /**
   * ⚠️ ЧЛ. 73 АЛ. 1 Т. 4 ИЗРИЧНО ИСКА И НЕОБЛАГАЕМИТЕ. Част ІV на номенклатурата е озаглавена
   * „Необлагаеми доходи по чл. 13, ал. 1 от ЗДДФЛ, изплатени на местни физически лица, които на
   * основание чл. 73, ал. 1, т. 4 от ЗДДФЛ се описват в справката“. Тоест нулата в колона 13 е
   * ЗАКОНЕН отговор, а изпуснат ред би направил справката непълна.
   */
  @Test
  @DisplayName(
      "СВИДЕТЕЛ · код 907 (рента за земеделска земя) влиза с бруто в к.11 и данък 0 в к.13")
  void theExemptRentCodeIsReportedWithGrossAndZeroTax() {
    BgIncomeType farmRent = type("907", false, false);
    Partner owner = partner(41L, "Ангелов");

    List<BgArt73Para1Line> lines =
        BgArt73Para1Aggregator.aggregate(
                Collections.singletonList(
                    payment(owner, farmRent, "2025-12-15", "4500.00", "0.00", "0.00")),
                TAX_YEAR)
            .getLines();

    assertEquals(1, lines.size(), "необлагаемият доход ВЛИЗА в справката: " + lines);
    BgArt73Para1Line line = lines.get(0);
    assertEquals(
        0, new BigDecimal("4500.00").compareTo(line.getGrossAmount()), "колона 11: " + line);
    assertNotNull(line.getTaxAmount(), "колона 13 се попълва — с нула, а не празна");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(line.getTaxAmount()),
        "колона 13 — данък не се дължи: " + line);
  }

  // ═══ СВИДЕТЕЛ 4 · ТРУДОВИТЕ ДОХОДИ НЕ ВЛИЗАТ ═════════════════════════════════════════

  /**
   * ⚠️ ОТДЕЛЯНЕТО Е ПО {@code declarations}, НЕ ПО СПИСЪК „101–111“ В JAVA. Кодът тук е 101, но
   * тестът пази ПРАВИЛОТО, а не числото: същият код с {@code declarations = "73-1"} би влязъл.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · трудовият доход (код 101) НЕ се появява — той е в справката по ал. 6")
  void employmentIncomeNeverReachesThisForm() {
    BgIncomeType employment = type("101", false, false);
    employment.setDeclarations("73-6");
    BgIncomeType consulting = type("3073", false, false);
    Partner person = partner(51L, "Маринов");

    BgArt73Para1Aggregator.Result result =
        BgArt73Para1Aggregator.aggregate(
            Arrays.asList(
                payment(person, employment, "2025-05-31", "1500.00", "206.70", "129.33"),
                payment(person, consulting, "2025-05-31", "1000.00", "0.00", "75.00")),
            TAX_YEAR);

    assertEquals(
        1, result.getLines().size(), "остава само извънтрудовият ред: " + result.getLines());
    assertEquals("3073", result.getLines().get(0).getCode());
    assertEquals(
        1, result.getOtherFormCount(), "трудовият ред е БРОЕН, а не изчезнал — той е на ал. 6");
  }

  // ═══ СВИДЕТЕЛ 5 · КОД КОРЕКЦИЯ ═══════════════════════════════════════════════════════

  /**
   * ⚠️ Указание т. 2, дословно: „При заличаване на данни се попълват само колони 1, 2, 3, 4 или 5,
   * 6, 7, 8 и 10 в Част ІІ.“ Тоест колони 11, 12, 13, 14 и 15 остават ПРАЗНИ — а празно и нула са
   * различни твърдения.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · заличаващият ред (код 8) носи идентификация и код, но НИТО ЕДНА сума")
  void theDeletingRowFillsOnlyTheColumnsTheInstructionNames() {
    BgIncomeType consulting = type("3073", false, false);
    Partner person = partner(61L, "Василев");
    BgIndividualIncomePayment deleted =
        payment(person, consulting, "2025-03-14", "1000.00", "0.00", "75.00");
    deleted.setCorrectionCode(BgArt73Para1Aggregator.CORRECTION_DELETE);
    deleted.setIdentifierType("0");

    BgArt73Para1Line line =
        BgArt73Para1Aggregator.aggregate(Collections.singletonList(deleted), TAX_YEAR)
            .getLines()
            .get(0);

    assertFalse(line.carriesAmounts(), "заличаващият ред не носи суми");
    assertNull(line.getGrossAmount(), "колона 11 не се попълва при заличаване: " + line);
    assertNull(line.getInsuranceAmount(), "колона 12 не се попълва при заличаване: " + line);
    assertNull(line.getTaxAmount(), "колона 13 не се попълва при заличаване: " + line);
    assertNull(line.getPersonCount(), "колона 14 не се попълва при заличаване: " + line);
    // Колони 1, 3 и 10 се попълват — точно тях указанието изброява.
    assertEquals(BgArt73Para1Aggregator.CORRECTION_DELETE, line.getCorrectionCode(), "колона 1");
    assertEquals("0", line.getIdentifierType(), "колона 3");
    assertEquals("3073", line.getCode(), "колона 10");
    assertSame(person, line.getPartner(), "колони 4–8 се попълват");
  }

  @Test
  @DisplayName("КОРЕКЦИЯ · основните и коригиращите данни за едно лице по един код са ДВА реда")
  void mainAndAmendingDataNeverCollapseIntoOneRow() {
    BgIncomeType consulting = type("3073", false, false);
    Partner person = partner(71L, "Колев");
    BgIndividualIncomePayment main =
        payment(person, consulting, "2025-03-14", "1000.00", "0.00", "75.00");
    BgIndividualIncomePayment amend =
        payment(person, consulting, "2025-03-14", "1200.00", "0.00", "90.00");
    amend.setCorrectionCode(BgArt73Para1Aggregator.CORRECTION_AMEND);

    List<BgArt73Para1Line> lines =
        BgArt73Para1Aggregator.aggregate(Arrays.asList(main, amend), TAX_YEAR).getLines();

    assertEquals(2, lines.size(), "код корекция е в ключа на групиране: " + lines);
    assertEquals(
        0,
        new BigDecimal("1000.00").compareTo(lines.get(0).getGrossAmount()),
        "основните данни остават 1000,00, а не 2200,00: " + lines.get(0));
    assertEquals(
        0,
        new BigDecimal("1200.00").compareTo(lines.get(1).getGrossAmount()),
        "коригиращите са свой ред: " + lines.get(1));
  }

  // ═══ ГОДИНАТА ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("ГОДИНА · плащане от друга година не влиза — справката е за годината на придобиване")
  void paymentsOfAnotherYearAreLeftOut() {
    BgIncomeType consulting = type("3073", false, false);
    Partner person = partner(81L, "Райчев");

    List<BgArt73Para1Line> lines =
        BgArt73Para1Aggregator.aggregate(
                Arrays.asList(
                    payment(person, consulting, "2024-12-31", "1000.00", "0.00", "75.00"),
                    payment(person, consulting, "2025-01-02", "1000.00", "0.00", "75.00"),
                    payment(person, consulting, "2026-01-02", "1000.00", "0.00", "75.00")),
                TAX_YEAR)
            .getLines();

    assertEquals(1, lines.size(), "само плащането от 2025 г.: " + lines);
    assertEquals(0, new BigDecimal("1000.00").compareTo(lines.get(0).getGrossAmount()));
  }

  // ═══ НЕПЪЛНИТЕ РЕДОВЕ И РАЗМИНАВАНЕТО ════════════════════════════════════════════════

  @Test
  @DisplayName("НЕПЪЛЕН · ред без вид доход не изчезва тихо, а се БРОИ")
  void aRowWithoutAnIncomeTypeIsCountedAndNotSwallowed() {
    Partner person = partner(91L, "Христов");
    BgIndividualIncomePayment orphan =
        payment(person, null, "2025-03-14", "1000.00", "0.00", "75.00");

    BgArt73Para1Aggregator.Result result =
        BgArt73Para1Aggregator.aggregate(Collections.singletonList(orphan), TAX_YEAR);

    assertTrue(result.getLines().isEmpty(), "без код на дохода редът не може да влезе в колона 10");
    assertEquals(1, result.getUnusableCount(), "но се брои — справката е непълна, а не пълна");
  }

  @Test
  @DisplayName("⚠️ РАЗМИНАВАНЕ · два реда за едно лице с различен тип идентификатор се съобщават")
  void disagreeingIdentityAcrossMergedPaymentsIsReported() {
    BgIncomeType consulting = type("3073", false, false);
    Partner person = partner(101L, "Ковачев");
    BgIndividualIncomePayment first =
        payment(person, consulting, "2025-03-14", "1000.00", "0.00", "75.00");
    first.setIdentifierType("0");
    BgIndividualIncomePayment second =
        payment(person, consulting, "2025-09-14", "1000.00", "0.00", "75.00");
    second.setIdentifierType("2");

    BgArt73Para1Line line =
        BgArt73Para1Aggregator.aggregate(Arrays.asList(first, second), TAX_YEAR).getLines().get(0);

    assertTrue(
        line.hasIdentityDisagreement(),
        "редът се строи с признаците на първото плащане, но разминаването се съобщава");
    assertEquals("0", line.getIdentifierType(), "взема се първото, а не последното");
  }

  @Test
  @DisplayName("ПРАЗНО · празен регистър не дава редове, а не ред с нула")
  void anEmptyRegisterGivesNoRows() {
    assertTrue(BgArt73Para1Aggregator.aggregate(null, TAX_YEAR).getLines().isEmpty());
    assertTrue(
        BgArt73Para1Aggregator.aggregate(Collections.emptyList(), TAX_YEAR).getLines().isEmpty());
  }

  // ═══ СТРОИТЕЛИ ═══════════════════════════════════════════════════════════════════════

  private static BgIncomeType type(String code, boolean aggregate, boolean personCount) {
    BgIncomeType type = new BgIncomeType();
    type.setCode(code);
    type.setValidFrom(LocalDate.of(2025, 1, 1));
    type.setIsAggregate(aggregate);
    type.setReportsPersonCount(personCount);
    type.setDeclarations(BgArt73Para1Aggregator.DECLARATION_PARA1);
    return type;
  }

  private static Partner partner(Long id, String name) {
    Partner partner = new Partner();
    partner.setId(id);
    partner.setName(name);
    return partner;
  }

  private static BgIndividualIncomePayment payment(
      Partner partner, BgIncomeType type, String date, String gross, String insurance, String tax) {
    BgIndividualIncomePayment payment = new BgIndividualIncomePayment();
    payment.setPartner(partner);
    payment.setIncomeType(type);
    payment.setPaymentDate(LocalDate.parse(date));
    payment.setGrossAmount(new BigDecimal(gross));
    payment.setInsuranceAmount(new BigDecimal(insurance));
    payment.setTaxAmount(new BigDecimal(tax));
    payment.setCorrectionCode(BgArt73Para1Aggregator.CORRECTION_MAIN);
    return payment;
  }
}
