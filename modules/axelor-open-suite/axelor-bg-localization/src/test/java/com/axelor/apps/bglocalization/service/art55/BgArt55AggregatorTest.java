package com.axelor.apps.bglocalization.service.art55;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * ЧАСТ ІV НА ОБРАЗЕЦ 4001 — регистърът, разпределен по тримесечие и вид данък.
 *
 * <p>Чист тест: без база и без Guice.
 *
 * <p><b>ЧИСЛАТА СА ИЗВЕДЕНИ ОТ КАТАЛОГА ЗА 2025 Г., А НЕ ИЗБРАНИ.</b> Всяко плащане по-долу е
 * 1000,00 лв. бруто и данъкът му следва ставките на кода:
 *
 * <pre>
 *   код 3073 · чл. 29 ал. 1 т. 3 · 25 % разходи, 10 % аванс → основа 750,00 → данък 75,00
 *   код  401 · чл. 31          · 10 % разходи, 10 % аванс → основа 900,00 → данък 90,00
 *   код  814 · чл. 38 ал. 1    ·  5 % окончателен          → основа 1000,00 → данък 50,00
 *   код  820 · чл. 38 ал. 12   · 10 % окончателен          → основа 1000,00 → данък 100,00
 * </pre>
 *
 * <p>Оттам и очакваното: 75 + 90 = <b>165</b> на ред 1, а 50 стои самò на ред 2.
 */
class BgArt55AggregatorTest {

  private static final int TAX_YEAR = 2025;
  private static final int Q1 = 1;
  private static final int Q2 = 2;
  private static final int Q4 = 4;

  // ═══ СВИДЕТЕЛ 1 · ТРИТЕ РЕДА НА ЕДНО ТРИМЕСЕЧИЕ ═════════════════════════════════════

  @Test
  @DisplayName(
      "СВИДЕТЕЛ · Q1: 3073 (75) + 401 (90) + 814 (50) → ред 1 к.3 = 165, ред 2 к.3 = 50, ред 3 = 0")
  void firstQuarterSplitsAdvanceFromDividends() {
    List<BgIndividualIncomePayment> register =
        Arrays.asList(
            payment(business(), "2025-02-10", "75.00", ADVANCE),
            payment(rent(), "2025-03-31", "90.00", ADVANCE),
            payment(dividend(), "2025-01-20", "50.00", FINAL));

    BgArt55Aggregator.Result out = BgArt55Aggregator.aggregate(register, TAX_YEAR, Q1);

    assertEquals(
        0,
        new BigDecimal("165.00").compareTo(out.getRow1()),
        "ред 1 събира чл. 43 (75,00) и чл. 44 (90,00) НА ЕДИН РЕД — образецът ги изброява заедно");
    assertEquals(
        0,
        new BigDecimal("50.00").compareTo(out.getRow2()),
        "ред 2 е САМО дивидентът по чл. 38 ал. 1, с 5 на сто по чл. 46 ал. 3");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(out.getRow3()),
        "ред 3 е празен: други окончателни данъци по чл. 37/38 няма");
    assertEquals(2, out.getRow1Rows(), "два реда на регистъра стоят зад ред 1");
    assertEquals(1, out.getRow2Rows());
    assertEquals(3, out.getPeriodRows(), "и трите плащания са в първото тримесечие");
  }

  // ═══ СВИДЕТЕЛ 2 · ДЕКЛАРИРАЛОТО СОЛ Е ЧУЖДА КОЛОНА ═════════════════════════════════

  /**
   * Чл. 43 ал. 5 и 6: платецът не удържа, лицето внася само — тоест колона 4 на образеца, която
   * попълва ДРУГА декларация (тази по Част І, т. 2).
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · СОЛ-декларирал по код 3073 НЕ влиза в к.3 — ред 1 остава 0")
  void selfInsuredDeclaredStaysOutOfPayerColumn() {
    BgIndividualIncomePayment declared = payment(business(), "2025-02-10", "75.00", ADVANCE);
    declared.setSelfInsuredDeclared(true);

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(Collections.singletonList(declared), TAX_YEAR, Q1);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(out.getRow1()),
        "данъкът се внася от лицето по чл. 67 — в к.4 на НЕГОВАТА декларация, не в нашата к.3");
    assertEquals(1, out.getSelfInsuredCount(), "редът се БРОИ, а не изчезва тихо");
    assertEquals(0, out.getRow1Rows());
    assertTrue(out.isEmpty(), "Част ІV няма какво да декларира");
  }

  // ═══ СВИДЕТЕЛ 3 · ЧЕТВЪРТОТО ТРИМЕСЕЧИЕ ════════════════════════════════════════════

  /**
   * Чл. 65 ал. 13 — авансовият не се удържа за Q4; окончателният НЯМА такова изключение и се удържа
   * през всичките четири тримесечия.
   */
  @Test
  @DisplayName(
      "СВИДЕТЕЛ · Q4: авансов 3073 → ред 1 = 0 (чл. 65 ал. 13), а дивидент 814 → ред 2 = 50")
  void fourthQuarterSuppressesAdvanceButNotFinalTax() {
    List<BgIndividualIncomePayment> register =
        Arrays.asList(
            payment(business(), "2025-11-15", "75.00", ADVANCE),
            payment(dividend(), "2025-12-01", "50.00", FINAL));

    BgArt55Aggregator.Result out = BgArt55Aggregator.aggregate(register, TAX_YEAR, Q4);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(out.getRow1()),
        "чл. 65 ал. 13: не се удържа авансов данък за доходи, придобити през Q4");
    assertEquals(
        0,
        new BigDecimal("50.00").compareTo(out.getRow2()),
        "окончателният по чл. 38 ал. 1 се удържа и за Q4 — ал. 13 говори САМО за авансовия");
    assertEquals(1, out.getQ4SuppressedRows(), "потиснатият ред се брои");
    assertEquals(
        0,
        new BigDecimal("75.00").compareTo(out.getQ4SuppressedAmount()),
        "и сумата му се назовава: ако лицето е поискало ПИСМЕНО удържане, точно тя се връща ръчно");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · Q4 потиска и чл. 44а — ал. 13 назовава и трите разпоредби")
  void fourthQuarterSuppressesOtherSourcesToo() {
    BgIncomeType otherSources = type("1011", "чл. 35, т. 6", "E");

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(
            Collections.singletonList(payment(otherSources, "2025-10-05", "100.00", ADVANCE)),
            TAX_YEAR,
            Q4);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(out.getRow1()),
        "чл. 65 ал. 13 изброява чл. 43 ал. 4, чл. 44 ал. 4 И чл. 44а ал. 1 — трите заедно");
    assertEquals(1, out.getQ4SuppressedRows());
  }

  // ═══ СВИДЕТЕЛ 4 · ВСЯКО ТРИМЕСЕЧИЕ Е СВОЯ ДЕКЛАРАЦИЯ ═══════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · плащане от април е във ВТОРОТО тримесечие, не в първото")
  void aprilPaymentBelongsToTheSecondQuarter() {
    List<BgIndividualIncomePayment> register =
        Collections.singletonList(payment(business(), "2025-04-01", "75.00", ADVANCE));

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(BgArt55Aggregator.aggregate(register, TAX_YEAR, Q1).getRow1()),
        "първото тримесечие свършва на 31 март");
    assertEquals(
        0,
        new BigDecimal("75.00")
            .compareTo(BgArt55Aggregator.aggregate(register, TAX_YEAR, Q2).getRow1()),
        "и същият ред се явява във второто — своя декларация, свой срок по чл. 56 ал. 1");
  }

  @Test
  @DisplayName("границите на тримесечията — 31 март/1 април и 30 септември/1 октомври")
  void quarterBoundariesFollowTheCalendar() {
    assertEquals(1, BgArt55Aggregator.quarterOf(LocalDate.parse("2025-03-31")));
    assertEquals(2, BgArt55Aggregator.quarterOf(LocalDate.parse("2025-04-01")));
    assertEquals(3, BgArt55Aggregator.quarterOf(LocalDate.parse("2025-09-30")));
    assertEquals(4, BgArt55Aggregator.quarterOf(LocalDate.parse("2025-10-01")));
  }

  // ═══ СВИДЕТЕЛ 5 · НУЛЕВИЯТ ДАНЪК НЕ СЕ ДЕКЛАРИРА ═══════════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · необлагаем (907, шаблон F) и продажба (508, шаблон D) не влизат никъде")
  void untaxedAndTransferRowsAreNotDeclared() {
    BgIncomeType exempt = type("907", "чл. 13, ал. 1, т. 24", "F");
    BgIncomeType transfer = type("508", "чл. 33, ал. 3", "D");

    List<BgIndividualIncomePayment> register =
        Arrays.asList(
            payment(exempt, "2025-02-01", "0.00", NONE),
            payment(transfer, "2025-02-02", "0.00", NONE));

    BgArt55Aggregator.Result out = BgArt55Aggregator.aggregate(register, TAX_YEAR, Q1);

    assertTrue(out.isEmpty(), "и трите реда на Част ІV остават нула");
    assertEquals(2, out.getNotWithheldCount(), "двата реда се броят като „данък не е удържан“");
    assertEquals(2, out.getPeriodRows(), "но те СА в тримесечието — броят им не е нула");
  }

  // ═══ СВИДЕТЕЛ 6 · ЧЛ. 38 АЛ. 12 НЕ Е ЧЛ. 38 АЛ. 1 ══════════════════════════════════

  /**
   * ⚠️ КАПАНЪТ НА ПРЕФИКСА. „чл. 38, ал. 12“ започва със същите знаци като „чл. 38, ал. 1“. Код 820
   * е наем при етажна собственост с 10 на сто по чл. 46 ал. 1; дивидентът е 5 на сто по ал. 3, а
   * колона 4 на ред 2 е изрично забранена с „х“ на образеца. Слети, двата реда стават един.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · код 820 (чл. 38, ал. 12) отива на ред 3, а НЕ при дивидентите на ред 2")
  void articleTwelveIsNotArticleOne() {
    BgIncomeType condominium = type("820", "чл. 38, ал. 12", "C");

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(
            Arrays.asList(
                payment(condominium, "2025-02-01", "100.00", FINAL),
                payment(dividend(), "2025-02-02", "50.00", FINAL)),
            TAX_YEAR,
            Q1);

    assertEquals(
        0,
        new BigDecimal("50.00").compareTo(out.getRow2()),
        "ред 2 е САМО чл. 38 ал. 1 — 5 на сто, забранена колона 4");
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(out.getRow3()),
        "ал. 12 е „окончателен друг“ — ред 3, 10 на сто по чл. 46 ал. 1");
  }

  @Test
  @DisplayName("код 8141 (сборните дивиденти до 100 лв.) също е ред 2 — същият член")
  void aggregateDividendCodeAlsoLandsOnRowTwo() {
    BgIncomeType small = type("8141", "чл. 38, ал. 1", "C");

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(
            Collections.singletonList(payment(small, "2025-02-01", "5.00", FINAL)), TAX_YEAR, Q1);

    assertEquals(0, new BigDecimal("5.00").compareTo(out.getRow2()));
    assertEquals(0, BigDecimal.ZERO.compareTo(out.getRow3()));
  }

  // ═══ СВИДЕТЕЛ 7 · ТРУДОВИТЕ ДОХОДИ — ЧЛ. 55 АЛ. 3 ══════════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · трудов доход (справка по ал. 6) не се посочва — чл. 55 ал. 3")
  void employmentIncomeIsExcludedByLaw() {
    BgIncomeType salary = type("101", "§ 1, т. 26", "A");
    salary.setDeclarations(BgArt55Aggregator.DECLARATION_EMPLOYMENT);

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(
            Collections.singletonList(payment(salary, "2025-02-01", "120.00", ADVANCE)),
            TAX_YEAR,
            Q1);

    assertTrue(out.isEmpty(), "чл. 55 ал. 3 изважда трудовия данък от тази декларация");
    assertEquals(1, out.getEmploymentCount());
    assertEquals(0, out.getPeriodRows(), "изваден е ПРЕДИ периода — той не е ред на този образец");
  }

  // ═══ СВИДЕТЕЛ 8 · ЧЛ. 65 АЛ. 6 — ДАНЪКЪТ Е НА ПОЛУЧАТЕЛЯ ═══════════════════════════

  /**
   * Чл. 65 ал. 6: данъкът по чл. 37 ал. 1 т. 10–12 и чл. 38 ал. 5 се внася ОТ ЛИЦЕТО по реда на чл.
   * 67. Каталогът вече го казва ({@code withheldByPayer = false}) и такъв ред нормално не стига
   * дотук — виж {@link com.axelor.apps.bglocalization.service.zddfl.BgArt65Para6WitnessTest}.
   *
   * <p>⚠️ ЗАТОВА ВИДЪТ ДАНЪК ТУК Е ПОСТАВЕН НА РЪКА, а не изведен от каталога: този тест пази
   * ПРЕДПАЗИТЕЛЯ, не поправката. Ред с {@code FINAL} по тези четири разпоредби идва от база отпреди
   * App Install или от ръчно въведен запис — и точно тогава броенето е единственото, което го
   * показва. Махнато „защото каталогът е поправен“, разминаването би било невидимо.
   */
  @Test
  @DisplayName("ПРЕДПАЗИТЕЛ · ръчен FINAL по код 815 (чл. 38 ал. 5) СЕ БРОИ като чужда колона")
  void recipientPaidFinalTaxIsCounted() {
    BgIncomeType swap = type("815", "чл. 38, ал. 5", "C");

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(
            Collections.singletonList(payment(swap, "2025-02-01", "100.00", FINAL)), TAX_YEAR, Q1);

    assertEquals(1, out.getRecipientPaidFinalCount(), "чл. 65 ал. 6 праща данъка на получателя");
    assertFalse(out.isEmpty(), "числото пак стои на ред 3 — изключването е решение на човек");
  }

  // ═══ НЕПЪЛНИТЕ РЕДОВЕ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName("ред без вид доход или без дата се брои, а не се приписва по подразбиране")
  void incompleteRowsAreCounted() {
    BgIndividualIncomePayment noType = payment(null, "2025-02-01", "75.00", ADVANCE);
    BgIndividualIncomePayment noDate = payment(business(), null, "75.00", ADVANCE);

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(Arrays.asList(noType, noDate, null), TAX_YEAR, Q1);

    assertEquals(2, out.getUnusableCount());
    assertTrue(out.isEmpty());
  }

  @Test
  @DisplayName("празен регистър дава три нули, не изключение")
  void emptyRegisterIsThreeZeros() {
    BgArt55Aggregator.Result out = BgArt55Aggregator.aggregate(null, TAX_YEAR, Q1);
    assertTrue(out.isEmpty());
    assertEquals(0, out.getPeriodRows());
  }

  // ═══ ПОМОЩНИ ═══════════════════════════════════════════════════════════════════════

  private static final String ADVANCE = BgArt55Aggregator.TAX_ADVANCE;
  private static final String FINAL = BgArt55Aggregator.TAX_FINAL;
  private static final String NONE = "NONE";

  private static BgIncomeType business() {
    return type("3073", "чл. 29, ал. 1, т. 3", "A");
  }

  private static BgIncomeType rent() {
    return type("401", "чл. 31", "B");
  }

  private static BgIncomeType dividend() {
    return type("814", "чл. 38, ал. 1", "C");
  }

  private static BgIncomeType type(String code, String article, String pattern) {
    BgIncomeType type = new BgIncomeType();
    type.setCode(code);
    type.setArticle(article);
    type.setTaxPattern(pattern);
    type.setValidFrom(LocalDate.of(2025, 1, 1));
    type.setDeclarations("73-1");
    return type;
  }

  private static BgIndividualIncomePayment payment(
      BgIncomeType type, String date, String tax, String kind) {
    BgIndividualIncomePayment payment = new BgIndividualIncomePayment();
    payment.setIncomeType(type);
    payment.setPaymentDate(date == null ? null : LocalDate.parse(date));
    payment.setGrossAmount(new BigDecimal("1000.00"));
    payment.setTaxAmount(new BigDecimal(tax));
    payment.setTaxKind(kind);
    payment.setCorrectionCode("0");
    return payment;
  }
}
