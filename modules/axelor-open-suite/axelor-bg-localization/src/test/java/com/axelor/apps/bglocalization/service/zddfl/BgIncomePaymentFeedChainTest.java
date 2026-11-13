package com.axelor.apps.bglocalization.service.zddfl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.service.art55.BgArt55Aggregator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВЕРИГАТА ОТ ДАТАТА НА ДЕКЛАРАЦИЯТА ДО КОЛОНА 3 НА ОБРАЗЕЦ 4001 — <b>без база и без Guice</b>.
 *
 * <p><b>ЗАЩО ОТДЕЛЕН ТЕСТ ОТ {@code BgIncomePaymentFeedMapperTest}.</b> Онзи проверява ПРАВИЛАТА
 * поотделно: „декларация от октомври не отменя плащане от март“. Този проверява, че правилото СТИГА
 * дотам, докъдето трябва — до реда на регистъра, до резолвера и до вече готовия агрегатор на чл.
 * 55. Двете са различни твърдения: правило може да е вярно и да не се чете от никого, а точно това
 * беше състоянието на {@code INCOME_TAX_RATE_SEAFARER} цели десет дни.
 *
 * <p>⚠️ {@code BgArt55Aggregator} НЕ се променя от този клон. Тук той е СВИДЕТЕЛ, не обект на
 * проверка: ако утре някой махне изричната проверка за {@code selfInsuredDeclared} от него, този
 * тест ще падне и ще каже защо.
 */
class BgIncomePaymentFeedChainTest {

  private static final int TAX_YEAR = 2026;

  /** Плащането по граждански договор — 10 март 2026 г., първо тримесечие. */
  private static final LocalDate PAID_Q1 = LocalDate.of(2026, 3, 10);

  /** Второ плащане — 15 април 2026 г., ВТОРО тримесечие. */
  private static final LocalDate PAID_Q2 = LocalDate.of(2026, 4, 15);

  // ═══ СВИДЕТЕЛ · УСЛУГА КЪМ ДЕКЛАРИРАЛО СОЛ ══════════════════════════════════

  /**
   * ⚠️ ЦЕЛИЯТ ПЪТ: датата на писмената декларация (на ЛИЦЕТО) → изведеният признак на реда → нула
   * данък от резолвера → и редът, който НЕ влиза в колона 3 на Част ІV, а се БРОИ.
   *
   * <p>Числата: 2000,00 при 25 на сто разходи по чл. 29 ал. 1 т. 3 → основа 1500,00. Данъкът е
   * 0,00, защото чл. 43 ал. 6 го прехвърля на лицето — но брутото остава в колона 11 на справката
   * по чл. 73 ал. 1.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · услуга към декларирало СОЛ → withheldByPayer отпада и к.3 остава 0")
  void serviceToDeclaredSelfInsuredStaysOutOfColumnThree() {
    BgIncomePaymentFeedOutcome outcome =
        BgIncomePaymentFeedMapper.decide(
            candidate(PAID_Q1, LocalDate.of(2026, 1, 20), BgIncomeTaxResolver.PATTERN_BUSINESS));

    assertTrue(outcome.isAccepted(), outcome.getReason());
    assertTrue(
        outcome.isSelfInsuredDeclared(),
        "декларацията е от 20 януари, плащането — от 10 март: ал. 5 действа");

    BgIndividualIncomePayment row = row(business(), PAID_Q1, outcome);
    BgIncomeTaxResult tax = compute(row, business());

    assertEquals(new BigDecimal("1500.00"), tax.taxableBase, "основата се показва за сведение");
    assertEquals(0, tax.taxAmount.signum(), "чл. 43 ал. 6 — данъкът се внася от лицето");
    assertEquals(BgIncomeTaxResolver.TAX_NONE, tax.taxKind);

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(Collections.singletonList(row), TAX_YEAR, 1);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(out.getRow1()),
        "данъкът е в колона 4 на НЕГОВАТА декларация, не в нашата колона 3");
    assertEquals(1, out.getSelfInsuredCount(), "редът се БРОИ, а не изчезва тихо");
  }

  /**
   * ⚠️ ОБРАТНАТА ПОЛОВИНА НА СЪЩИЯ СВИДЕТЕЛ. Без декларация (или с декларация СЛЕД плащането)
   * данъкът се удържа и влиза в колона 3 — иначе горният тест би минавал и при правило „винаги
   * false“.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · същата услуга БЕЗ декларация → 150,00 в к.3, ред 1")
  void sameServiceWithoutDeclarationIsWithheld() {
    BgIncomePaymentFeedOutcome outcome =
        BgIncomePaymentFeedMapper.decide(
            candidate(PAID_Q1, null, BgIncomeTaxResolver.PATTERN_BUSINESS));

    assertFalse(outcome.isSelfInsuredDeclared());

    BgIndividualIncomePayment row = row(business(), PAID_Q1, outcome);
    BgIncomeTaxResult tax = compute(row, business());

    assertEquals(new BigDecimal("150.00"), tax.taxAmount, "чл. 43 ал. 3 — 10 на сто върху 1500,00");
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, tax.taxKind);

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(Collections.singletonList(row), TAX_YEAR, 1);

    assertEquals(0, new BigDecimal("150.00").compareTo(out.getRow1()));
    assertEquals(1, out.getRow1Rows());
  }

  /**
   * ⚠️ И ДЕКЛАРАЦИЯ СЛЕД ПЛАЩАНЕТО НЕ ГО ЗАВАРВА — това е разликата между дата и отметка, измерена
   * докрай: до колона 3 на образеца.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · декларация от октомври НЕ отменя удържането по мартенското плащане")
  void octoberDeclarationDoesNotUndoMarch() {
    BgIncomePaymentFeedOutcome outcome =
        BgIncomePaymentFeedMapper.decide(
            candidate(PAID_Q1, LocalDate.of(2026, 10, 1), BgIncomeTaxResolver.PATTERN_BUSINESS));

    assertFalse(outcome.isSelfInsuredDeclared());

    BgIndividualIncomePayment row = row(business(), PAID_Q1, outcome);
    compute(row, business());

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(Collections.singletonList(row), TAX_YEAR, 1);
    assertEquals(0, new BigDecimal("150.00").compareTo(out.getRow1()));
  }

  // ═══ СВИДЕТЕЛ · ТРИМЕСЕЧИЯТА СЕ РАЗДЕЛЯТ ════════════════════════════════════

  /**
   * ⚠️ НАВРЪЗКАТА С ЧЛ. 55: датата по чл. 11 определя тримесечието, а не периодът на фиша. Плащане
   * от 15 април има СВОЯ декларация и не се долепя към първото тримесечие.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · плащане от 15 април е във ВТОРОТО тримесечие, отделно от мартенското")
  void aprilPaymentBelongsToTheSecondQuarter() {
    BgIndividualIncomePayment march =
        row(
            business(),
            PAID_Q1,
            BgIncomePaymentFeedMapper.decide(
                candidate(PAID_Q1, null, BgIncomeTaxResolver.PATTERN_BUSINESS)));
    BgIndividualIncomePayment april =
        row(
            business(),
            PAID_Q2,
            BgIncomePaymentFeedMapper.decide(
                candidate(PAID_Q2, null, BgIncomeTaxResolver.PATTERN_BUSINESS)));
    compute(march, business());
    compute(april, business());

    BgArt55Aggregator.Result q1 =
        BgArt55Aggregator.aggregate(Arrays.asList(march, april), TAX_YEAR, 1);
    BgArt55Aggregator.Result q2 =
        BgArt55Aggregator.aggregate(Arrays.asList(march, april), TAX_YEAR, 2);

    assertEquals(0, new BigDecimal("150.00").compareTo(q1.getRow1()), "само мартенското");
    assertEquals(1, q1.getRow1Rows());
    assertEquals(0, new BigDecimal("150.00").compareTo(q2.getRow1()), "само априлското");
    assertEquals(1, q2.getRow1Rows());

    // И обхватът на прогона ги разделя по същата граница.
    assertTrue(!PAID_Q2.isBefore(BgIncomePaymentFeedMapper.firstDayOf(TAX_YEAR, 2)));
    assertTrue(PAID_Q1.isBefore(BgIncomePaymentFeedMapper.firstDayOf(TAX_YEAR, 2)));
  }

  // ═══ СВИДЕТЕЛ · НАЕМ ПРЕЗ ФАКТУРА ═══════════════════════════════════════════

  /**
   * ⚠️ НАЕМЪТ НЕ Е ОТДЕЛЕН МОСТ — той е същият фактурен обход, различаван по вида доход. Тук
   * веригата се проверява докрай: 1000,00 наем → разходи 10 на сто по чл. 31 → основа 900,00 →
   * авансов данък 90,00 по чл. 44 ал. 4 → ред 1, колона 3 на образец 4001.
   *
   * <p><b>⚠️ И СЪС СЪЩАТА ДЕКЛАРАЦИЯ ПО ЧЛ. 43 АЛ. 5, КОЯТО ПРИ УСЛУГА ОТМЕНЯ УДЪРЖАНЕТО.</b> При
   * наем тя НЕ отменя нищо: чл. 44 няма съответна алинея и неговата ал. 4 е безусловна. Това е
   * най-скъпият случай за сбъркване — същото лице, същата декларация, две различни поведения, —
   * затова свидетелят го мери с число, а не с флаг.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · наем 1000,00 към СЪЩОТО декларирало СОЛ → 90,00 в к.3, ред 1")
  void rentIsWithheldEvenFromDeclaredSelfInsured() {
    BgIncomePaymentFeedOutcome outcome =
        BgIncomePaymentFeedMapper.decide(rentCandidate(PAID_Q1, LocalDate.of(2026, 1, 20)));

    assertTrue(outcome.isAccepted(), outcome.getReason());
    assertFalse(
        outcome.isSelfInsuredDeclared(),
        "чл. 44 няма алинея, съответна на чл. 43 ал. 5 — платецът удържа като на всеки друг");

    BgIndividualIncomePayment row = new BgIndividualIncomePayment();
    BgIncomeType rent = rent();
    row.setIncomeType(rent);
    row.setPaymentDate(PAID_Q1);
    row.setGrossAmount(new BigDecimal("1000.00"));
    row.setInsuranceAmount(BigDecimal.ZERO);
    row.setSelfInsuredDeclared(outcome.isSelfInsuredDeclared());
    row.setSource(BgIncomePaymentFeedMapper.SOURCE_INVOICE);
    row.setSourceRef("InvoicePayment#88");
    row.setCorrectionCode(BgIndividualIncomePaymentService.CORRECTION_PRIMARY);

    BgIncomeTaxResult tax = compute(row, rent);
    assertEquals(new BigDecimal("100.00"), tax.expenseAmount, "чл. 31 ал. 1 — 10 на сто");
    assertEquals(new BigDecimal("900.00"), tax.taxableBase);
    assertEquals(new BigDecimal("90.00"), tax.taxAmount, "чл. 44 ал. 4 — 10 на сто върху 900,00");

    BgArt55Aggregator.Result out =
        BgArt55Aggregator.aggregate(Collections.singletonList(row), TAX_YEAR, 1);
    assertEquals(0, new BigDecimal("90.00").compareTo(out.getRow1()));
    assertEquals(1, out.getRow1Rows());
    assertEquals(0, out.getSelfInsuredCount(), "наемът НЕ отива в колона 4");
  }

  // ═══ СВИДЕТЕЛ · РЪЧНИЯТ РЕД ═════════════════════════════════════════════════

  /**
   * ⚠️ РЪЧНО ВЪВЕДЕНИЯТ РЕД НЯМА РЕФЕРЕНЦИЯ И ЗАТОВА НЕ СЕ НАМИРА ОТ ЗАХРАНВАНЕТО.
   *
   * <p>Свидетелят е за КЛЮЧА, не за заявката: {@code refOf} никога не връща стойността, която
   * ръчният ред носи ({@code null}), тоест няма аргумент, при който търсенето по референция да го
   * улучи. Че самата заявка ограничава по {@code sourceRef}, се пази отделно — от {@code
   * BgIncomePaymentFeedQueryGuardTest}, който чете кода.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · ръчен ред (без референция) е недостижим за ключа на захранването")
  void manualRowIsUnreachableByTheFeedKey() {
    BgIndividualIncomePayment manual = new BgIndividualIncomePayment();
    manual.setSource(BgIndividualIncomePaymentService.SOURCE_MANUAL);
    manual.setSourceRef(null);

    assertEquals(null, manual.getSourceRef(), "ръчният ред няма референция — това го пази");
    assertTrue(
        BgIncomePaymentFeedMapper.refOf(BgIncomePaymentFeedMapper.MODEL_PAY_SLIP, 1L) != null,
        "а всяка референция, която захранването строи, е непразна");
  }

  // ═══ ПОМОЩНИ ════════════════════════════════════════════════════════════════

  /** Код 3073 — чл. 29 ал. 1 т. 3: 25 на сто разходи, 10 на сто авансов данък. */
  private static BgIncomeType business() {
    BgIncomeType type = new BgIncomeType();
    type.setCode("3073");
    type.setArticle("чл. 29, ал. 1, т. 3");
    type.setTaxPattern(BgIncomeTaxResolver.PATTERN_BUSINESS);
    type.setExpenseRate(new BigDecimal("25.00"));
    type.setAdvanceTaxRate(new BigDecimal("10.00"));
    type.setFinalTaxRate(BigDecimal.ZERO);
    type.setWithheldByPayer(true);
    type.setDeclarations("73-1");
    return type;
  }

  /** Код 401 — чл. 31: 10 на сто разходи, 10 на сто авансов данък по чл. 44 ал. 4. */
  private static BgIncomeType rent() {
    BgIncomeType type = new BgIncomeType();
    type.setCode("401");
    type.setArticle("чл. 31");
    type.setTaxPattern(BgIncomeTaxResolver.PATTERN_RENT);
    type.setExpenseRate(new BigDecimal("10.00"));
    type.setAdvanceTaxRate(new BigDecimal("10.00"));
    type.setFinalTaxRate(BigDecimal.ZERO);
    type.setWithheldByPayer(true);
    type.setDeclarations("73-1");
    return type;
  }

  /** Кандидат за наем по фактура — СЪЩОТО лице, СЪЩАТА декларация, друг шаблон. */
  private static BgIncomePaymentFeedCandidate rentCandidate(
      LocalDate paymentDate, LocalDate declaredOn) {
    return new BgIncomePaymentFeedCandidate(
        BgIncomePaymentFeedMapper.SOURCE_INVOICE,
        "InvoicePayment#88",
        paymentDate,
        new BigDecimal("1000.00"),
        BigDecimal.ZERO,
        true,
        true,
        BgIncomeTaxResolver.PATTERN_RENT,
        null,
        "7503151233",
        declaredOn);
  }

  private static BgIncomePaymentFeedCandidate candidate(
      LocalDate paymentDate, LocalDate declaredOn, String taxPattern) {
    return new BgIncomePaymentFeedCandidate(
        BgIncomePaymentFeedMapper.SOURCE_PAYROLL,
        "BgPaySlip#42",
        paymentDate,
        new BigDecimal("2000.00"),
        BigDecimal.ZERO,
        true,
        true,
        taxPattern,
        null,
        "7503151233",
        declaredOn);
  }

  /** Редът, какъвто захранването го попълва преди сметката. */
  private static BgIndividualIncomePayment row(
      BgIncomeType type, LocalDate paymentDate, BgIncomePaymentFeedOutcome outcome) {
    BgIndividualIncomePayment row = new BgIndividualIncomePayment();
    row.setIncomeType(type);
    row.setPaymentDate(paymentDate);
    row.setGrossAmount(new BigDecimal("2000.00"));
    row.setInsuranceAmount(BigDecimal.ZERO);
    row.setIdentifierType(outcome.getIdentifierType());
    row.setSelfInsuredDeclared(outcome.isSelfInsuredDeclared());
    row.setSource(BgIncomePaymentFeedMapper.SOURCE_PAYROLL);
    row.setSourceRef("BgPaySlip#42");
    row.setCorrectionCode(BgIndividualIncomePaymentService.CORRECTION_PRIMARY);
    return row;
  }

  /**
   * СМЕТКАТА — през същия резолвер, който вика и {@code BgIndividualIncomePaymentService}.
   *
   * <p>⚠️ Услугата не се вика направо, защото тя препрочита каталога от базата ({@code
   * Beans.get(BgIncomeTypeRepository.class)}) и би довлякла Guice в свидетел, който говори за две
   * дати и три числа. Ставките тук са СЪЩИТЕ, които каталогът носи за код 3073.
   */
  private static BgIncomeTaxResult compute(BgIndividualIncomePayment row, BgIncomeType type) {
    BgIncomeTaxResult result =
        BgIncomeTaxResolver.resolve(
            new BgIncomeTaxRequest(
                type.getTaxPattern(),
                type.getExpenseRate(),
                type.getFinalTaxRate(),
                type.getAdvanceTaxRate(),
                Boolean.TRUE.equals(type.getWithheldByPayer()),
                row.getGrossAmount(),
                row.getInsuranceAmount(),
                Boolean.TRUE.equals(row.getSelfInsuredDeclared())));
    if (result.isResolved()) {
      row.setExpenseAmount(result.expenseAmount);
      row.setTaxableBase(result.taxableBase);
      row.setTaxAmount(result.taxAmount);
      row.setTaxKind(result.taxKind);
    }
    return result;
  }
}
