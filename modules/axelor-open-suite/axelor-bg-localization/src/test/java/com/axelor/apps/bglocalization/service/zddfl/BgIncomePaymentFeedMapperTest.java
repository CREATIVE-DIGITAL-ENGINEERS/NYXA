package com.axelor.apps.bglocalization.service.zddfl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВИДЕТЕЛИТЕ НА АВТО-ЗАХРАНВАНЕТО — с ДАТИ и ЧИСЛА, без база.
 *
 * <p>Аритметиката се проверява през {@link BgIncomeTaxResolver}, тоест през СЪЩИЯ път, по който
 * минава и ръчно въведеният ред. Това е нарочно: свидетел, който смята сам, би доказал, че тестът
 * умее да умножава, а не че захранването и формата дават едно и също число за едно и също плащане.
 */
class BgIncomePaymentFeedMapperTest {

  private static final LocalDate PAID = LocalDate.of(2026, 3, 10);

  // ═══ ЧЛ. 43 АЛ. 5 — ДАТАТА, НЕ ФЛАГЪТ ═══════════════════════════════════════

  @Test
  @DisplayName("чл. 43 ал. 5 · декларация ПРЕДИ плащането отменя удържането")
  void declarationBeforePaymentSuppressesWithholding() {
    assertTrue(
        BgIncomePaymentFeedMapper.selfInsuredDeclared(
            BgIncomeTaxResolver.PATTERN_BUSINESS, LocalDate.of(2026, 1, 5), PAID));
  }

  @Test
  @DisplayName("чл. 43 ал. 5 · декларация В ДЕНЯ на плащането заварва плащането")
  void declarationOnTheSameDayCounts() {
    assertTrue(
        BgIncomePaymentFeedMapper.selfInsuredDeclared(
            BgIncomeTaxResolver.PATTERN_BUSINESS, PAID, PAID));
  }

  /**
   * ⚠️ НАЙ-ОСТРИЯТ РЪБ НА ТОВА ПРАВИЛО. Записан като булев флаг, признакът щеше да отмени
   * удържането и по мартенското плащане — със задна дата, върху вече внесен данък и вече подадена
   * декларация по чл. 55 за първото тримесечие.
   */
  @Test
  @DisplayName("чл. 43 ал. 5 · декларация СЛЕД плащането НЕ действа със задна дата")
  void declarationAfterPaymentDoesNotApplyRetroactively() {
    assertFalse(
        BgIncomePaymentFeedMapper.selfInsuredDeclared(
            BgIncomeTaxResolver.PATTERN_BUSINESS, LocalDate.of(2026, 10, 15), PAID));
  }

  /**
   * ⚠️ Чл. 44 няма алинея, съответна на чл. 43 ал. 5: неговата ал. 4 е безусловна. Пренесено „по
   * симетрия“, правилото би оставило данък неудържан без норма върху наем.
   */
  @Test
  @DisplayName("чл. 43 ал. 5 · при НАЕМ (шаблон B) декларацията НЕ отменя нищо")
  void rentHasNoSuchException() {
    assertFalse(
        BgIncomePaymentFeedMapper.selfInsuredDeclared(
            BgIncomeTaxResolver.PATTERN_RENT, LocalDate.of(2026, 1, 5), PAID));
  }

  @Test
  @DisplayName("чл. 43 ал. 5 · при доходи по чл. 35 (шаблон E) — също не")
  void otherSourcesHaveNoSuchException() {
    assertFalse(
        BgIncomePaymentFeedMapper.selfInsuredDeclared(
            BgIncomeTaxResolver.PATTERN_OTHER, LocalDate.of(2026, 1, 5), PAID));
  }

  @Test
  @DisplayName("чл. 43 ал. 5 · без декларация няма изключение")
  void noDeclarationNoException() {
    assertFalse(
        BgIncomePaymentFeedMapper.selfInsuredDeclared(
            BgIncomeTaxResolver.PATTERN_BUSINESS, null, PAID));
  }

  // ═══ ИДЕМПОТЕНТНИЯТ КЛЮЧ ════════════════════════════════════════════════════

  @Test
  @DisplayName("референцията носи И модела, И id-то — фиш 7 не е плащане 7")
  void refCarriesModelAndId() {
    assertEquals("BgPaySlip#7", BgIncomePaymentFeedMapper.refOf("BgPaySlip", 7L));
    assertEquals("InvoicePayment#7", BgIncomePaymentFeedMapper.refOf("InvoicePayment", 7L));
  }

  @Test
  @DisplayName("преходен обект (без id) НЕ получава измислена референция")
  void transientObjectGetsNoRef() {
    assertNull(BgIncomePaymentFeedMapper.refOf("BgPaySlip", null));
  }

  // ═══ ТИПЪТ НА ИДЕНТИФИКАТОРА ════════════════════════════════════════════════

  @Test
  @DisplayName("обявеният тип печели над извеждането")
  void declaredTypeWins() {
    // ЕГН по вид, но лицето е обявено с БУЛСТАТ — обявеното е истината.
    assertEquals("2", BgIncomePaymentFeedMapper.identifierType("2", "7503151233"));
  }

  @Test
  @DisplayName("проверимо ЕГН се чете като „0“, проверимо ЛНЧ — като „1“")
  void checksumDrivesTheDefault() {
    assertEquals(
        BgIncomePaymentFeedMapper.ID_TYPE_EGN,
        BgIncomePaymentFeedMapper.identifierType(null, "7503151233"));
    assertEquals(
        BgIncomePaymentFeedMapper.ID_TYPE_OTHER,
        BgIncomePaymentFeedMapper.identifierType(null, "7588151234"));
  }

  /**
   * ⚠️ БУЛСТАТ и чуждестранното лице без идентификатор НЯМАТ контролна цифра, тоест не се
   * разпознават. Познати наслуки, те биха дали правдоподобна колона 3 върху ред, който НАП отхвърля
   * — по-скъпо от преброен отказ.
   */
  @Test
  @DisplayName("непроверим номер НЕ се превръща в тип по догадка")
  void unverifiableNumberYieldsNothing() {
    assertNull(BgIncomePaymentFeedMapper.identifierType(null, "831641791"));
    assertNull(BgIncomePaymentFeedMapper.identifierType(null, null));
  }

  // ═══ ГОДНОСТТА НА КАНДИДАТА ═════════════════════════════════════════════════

  @Test
  @DisplayName("пълен кандидат по граждански договор се приема")
  void completeCandidateIsAccepted() {
    BgIncomePaymentFeedOutcome outcome = BgIncomePaymentFeedMapper.decide(civilContract());
    assertTrue(outcome.isAccepted(), outcome.getReason());
    assertEquals(BgIncomePaymentFeedMapper.ID_TYPE_EGN, outcome.getIdentifierType());
    assertFalse(outcome.isSelfInsuredDeclared());
  }

  @Test
  @DisplayName("липсващата дата на изплащане е ОТКАЗ, а не изведена от периода")
  void missingPaymentDateIsRefused() {
    BgIncomePaymentFeedOutcome outcome =
        BgIncomePaymentFeedMapper.decide(
            candidate(null, new BigDecimal("2000.00"), "7503151233", null, true, true, "A"));
    assertFalse(outcome.isAccepted());
    assertEquals(BgIncomePaymentFeedOutcome.NO_PAYMENT_DATE, outcome.getReasonCode());
  }

  @Test
  @DisplayName("нулевото бруто е ЗАКОННА нула — ред НЕ се прави, но се брои отделно")
  void zeroGrossIsItsOwnReason() {
    BgIncomePaymentFeedOutcome outcome =
        BgIncomePaymentFeedMapper.decide(
            candidate(PAID, BigDecimal.ZERO, "7503151233", null, true, true, "A"));
    assertFalse(outcome.isAccepted());
    assertEquals(BgIncomePaymentFeedOutcome.ZERO_GROSS, outcome.getReasonCode());
  }

  @Test
  @DisplayName("отрицателното бруто е отделна причина от нулевото")
  void negativeGrossIsADifferentReason() {
    BgIncomePaymentFeedOutcome outcome =
        BgIncomePaymentFeedMapper.decide(
            candidate(PAID, new BigDecimal("-5.00"), "7503151233", null, true, true, "A"));
    assertEquals(BgIncomePaymentFeedOutcome.BAD_GROSS, outcome.getReasonCode());
  }

  @Test
  @DisplayName(
      "без лице, без вид доход, без платец и без тип идентификатор — четири различни отказа")
  void eachMissingFactHasItsOwnName() {
    assertEquals(
        BgIncomePaymentFeedOutcome.NO_PARTNER,
        BgIncomePaymentFeedMapper.decide(
                candidate(PAID, new BigDecimal("2000.00"), "7503151233", null, false, true, "A"))
            .getReasonCode());
    assertEquals(
        BgIncomePaymentFeedOutcome.NO_INCOME_TYPE,
        BgIncomePaymentFeedMapper.decide(
                candidate(PAID, new BigDecimal("2000.00"), "7503151233", null, true, true, null))
            .getReasonCode());
    assertEquals(
        BgIncomePaymentFeedOutcome.NO_COMPANY,
        BgIncomePaymentFeedMapper.decide(
                candidate(PAID, new BigDecimal("2000.00"), "7503151233", null, true, false, "A"))
            .getReasonCode());
    assertEquals(
        BgIncomePaymentFeedOutcome.NO_IDENTIFIER_TYPE,
        BgIncomePaymentFeedMapper.decide(
                candidate(PAID, new BigDecimal("2000.00"), "831641791", null, true, true, "A"))
            .getReasonCode());
  }

  // ═══ ЧИСЛАТА — ГРАЖДАНСКИ ДОГОВОР ═══════════════════════════════════════════

  /**
   * ⚠️ СВИДЕТЕЛЯТ НА ЗАДАНИЕТО: 2000,00 при 25 на сто разходи дава основа 1500,00 и авансов данък
   * 150,00 по чл. 43 ал. 1 и ал. 3.
   *
   * <p>⚠️ И ТОЧНО ТОВА ЧИСЛО ФИШЪТ ДНЕС НЕ ДАВА. {@code BgIncomeTaxService} прилага чл. 42 върху
   * всеки фиш без разклонение по вид осигурен: основата му е „облагаеми начисления − лични вноски“
   * и НЕ приспада нормативните разходи, тоест перото удържа 200,00. Регистърът тук е верният, а
   * разминаването е обявено в границите на прогона и е отделен етап.
   */
  @Test
  @DisplayName("ГД · 2000,00 при 25 на сто → разходи 500,00, основа 1500,00, данък 150,00")
  void civilContractTwoThousandAtTwentyFive() {
    BgIncomeTaxResult result = businessTax(new BigDecimal("2000.00"), BigDecimal.ZERO, false);

    assertEquals(new BigDecimal("500.00"), result.expenseAmount);
    assertEquals(new BigDecimal("1500.00"), result.taxableBase);
    assertEquals(new BigDecimal("150.00"), result.taxAmount);
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, result.taxKind);
  }

  /**
   * ⚠️ ЗОВ СЕ ПРИСПАДАТ — чл. 43 ал. 1 дословно иска „разликата между облагаемия доход и удържаните
   * задължителни осигурителни вноски“. При наем (чл. 44 ал. 4) това НЕ важи.
   */
  @Test
  @DisplayName("ГД · същите 2000,00 с 240,00 ЗОВ → основа 1260,00, данък 126,00")
  void civilContractDeductsInsurance() {
    BgIncomeTaxResult result =
        businessTax(new BigDecimal("2000.00"), new BigDecimal("240.00"), false);

    assertEquals(new BigDecimal("500.00"), result.expenseAmount);
    assertEquals(new BigDecimal("1260.00"), result.taxableBase);
    assertEquals(new BigDecimal("126.00"), result.taxAmount);
  }

  /**
   * ⚠️ ДЕКЛАРИРАЛО СОЛ: данък НЕ се удържа, но редът СЕ ЗАПИСВА с бруто в колона 11. Основата се
   * показва за сведение — виж {@code BgIncomeTaxResolver#business}.
   */
  @Test
  @DisplayName("ГД към декларирало СОЛ · данък 0,00 и вид данък NONE, а брутото остава")
  void declaredSelfInsuredIsNotWithheld() {
    BgIncomeTaxResult result = businessTax(new BigDecimal("2000.00"), BigDecimal.ZERO, true);

    assertEquals(new BigDecimal("1500.00"), result.taxableBase);
    assertEquals(0, result.taxAmount.signum());
    assertEquals(BgIncomeTaxResolver.TAX_NONE, result.taxKind);
  }

  // ═══ ЧИСЛАТА — НАЕМ ════════════════════════════════════════════════════════

  /**
   * ⚠️ ВТОРИЯТ СВИДЕТЕЛ НА ЗАДАНИЕТО: наем 1000,00 при 10 на сто разходи по чл. 31 ал. 1 дава
   * основа 900,00 и авансов данък 90,00 по чл. 44 ал. 4.
   */
  @Test
  @DisplayName("НАЕМ · 1000,00 при 10 на сто → разходи 100,00, основа 900,00, данък 90,00")
  void rentThousandAtTen() {
    BgIncomeTaxResult result = rentTax(new BigDecimal("1000.00"), BigDecimal.ZERO);

    assertEquals(new BigDecimal("100.00"), result.expenseAmount);
    assertEquals(new BigDecimal("900.00"), result.taxableBase);
    assertEquals(new BigDecimal("90.00"), result.taxAmount);
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, result.taxKind);
  }

  /**
   * ⚠️ НАЙ-ОСТРАТА АСИМЕТРИЯ МЕЖДУ ДВАТА ШАБЛОНА, ИЗМЕРЕНА С ЕДНО ЧИСЛО. Чл. 44 ал. 4 определя
   * данъка като „облагаемият доход по чл. 31 × 10 на сто“ — БЕЗ приспадане на осигуровки, за
   * разлика от чл. 43 ал. 1. Подадени ЗОВ при наем НЕ мърдат нито основата, нито данъка.
   */
  @Test
  @DisplayName("НАЕМ · ЗОВ НЕ се приспадат — 240,00 подадени не менят нито 900,00, нито 90,00")
  void rentIgnoresInsuranceUnlikeBusiness() {
    BgIncomeTaxResult withInsurance = rentTax(new BigDecimal("1000.00"), new BigDecimal("240.00"));

    assertEquals(new BigDecimal("900.00"), withInsurance.taxableBase, "чл. 44 ал. 4 не вади ЗОВ");
    assertEquals(new BigDecimal("90.00"), withInsurance.taxAmount);
  }

  // ═══ ЧАСТИЧНОТО ПЛАЩАНЕ ПО ФАКТУРА ══════════════════════════════════════════

  @Test
  @DisplayName("фактура · пълно плащане дава дял 1 и целия доход без данък")
  void fullPaymentTakesTheWholeNetAmount() {
    BigDecimal share =
        BgIncomePaymentFeedMapper.share(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
    assertEquals(
        new BigDecimal("1000.00"),
        BgIncomePaymentFeedMapper.incomeFor(share, new BigDecimal("1000.00")));
  }

  /**
   * ⚠️ ЧЛ. 11: придобитото е ПЛАТЕНОТО. Взета цялата фактура, първата вноска би обложила доход,
   * който лицето още не е получило — и то в тримесечие, което не е неговото.
   */
  @Test
  @DisplayName("фактура · половин плащане дава половин доход")
  void halfPaymentTakesHalfTheIncome() {
    BigDecimal share =
        BgIncomePaymentFeedMapper.share(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    assertEquals(
        new BigDecimal("500.00"),
        BgIncomePaymentFeedMapper.incomeFor(share, new BigDecimal("1000.00")));
  }

  /**
   * ⚠️ ДДС-ТО НЕ Е ДОХОД. Плащането е за сумата С данък (1200,00), но облагаемият доход по ЗДДФЛ е
   * данъчната основа (1000,00): ДДС-то е задължение към бюджета. Оттам и защо делителят е {@code
   * inTaxTotal}, а множителят — {@code exTaxTotal}.
   */
  @Test
  @DisplayName("фактура · платени 1200,00 с ДДС при основа 1000,00 → доход 1000,00")
  void vatIsNotIncome() {
    BigDecimal share =
        BgIncomePaymentFeedMapper.share(new BigDecimal("1200.00"), new BigDecimal("1200.00"));
    assertEquals(
        new BigDecimal("1000.00"),
        BgIncomePaymentFeedMapper.incomeFor(share, new BigDecimal("1000.00")));
  }

  @Test
  @DisplayName("фактура · нулева или липсваща обща сума НЕ дава дял (и се брои)")
  void zeroTotalYieldsNoShare() {
    assertNull(BgIncomePaymentFeedMapper.share(new BigDecimal("10.00"), BigDecimal.ZERO));
    assertNull(BgIncomePaymentFeedMapper.share(new BigDecimal("10.00"), null));
    assertNull(BgIncomePaymentFeedMapper.share(null, new BigDecimal("10.00")));
  }

  /**
   * ⚠️ ДЕЛЪТ СЕ ПАЗИ С ПОВЕЧЕ ЗНАЦИ И СЕ ЗАКРЪГЛЯ ЧАК НАКРАЯ. Една трета от 3000,00 е 1000,00;
   * закръглен делът до стотинка (0,33), резултатът щеше да е 990,00 — десет лева разлика на дребна
   * фактура и стотици на голяма.
   */
  @Test
  @DisplayName("фактура · една трета от 3000,00 е 1000,00, а не 990,00")
  void theShareIsRoundedOnlyAtTheEnd() {
    BigDecimal share =
        BgIncomePaymentFeedMapper.share(new BigDecimal("1000.00"), new BigDecimal("3000.00"));
    assertEquals(
        new BigDecimal("1000.00"),
        BgIncomePaymentFeedMapper.incomeFor(share, new BigDecimal("3000.00")));
  }

  // ═══ ОБХВАТЪТ — ТРИМЕСЕЧИЕ СРЕЩУ ГОДИНА ═════════════════════════════════════

  /** Навръзката с чл. 55: плащане от април е във ВТОРОТО тримесечие и има своя декларация. */
  @Test
  @DisplayName("обхват · Q2 е 1 април – 30 юни, отделно от Q1")
  void secondQuarterIsItsOwnScope() {
    assertEquals(LocalDate.of(2026, 4, 1), BgIncomePaymentFeedMapper.firstDayOf(2026, 2));
    assertEquals(LocalDate.of(2026, 6, 30), BgIncomePaymentFeedMapper.lastDayOf(2026, 2));
    assertEquals(LocalDate.of(2026, 3, 31), BgIncomePaymentFeedMapper.lastDayOf(2026, 1));
  }

  @Test
  @DisplayName("обхват · празно тримесечие значи ЦЯЛАТА година")
  void emptyQuarterMeansTheWholeYear() {
    assertEquals(LocalDate.of(2026, 1, 1), BgIncomePaymentFeedMapper.firstDayOf(2026, null));
    assertEquals(LocalDate.of(2026, 12, 31), BgIncomePaymentFeedMapper.lastDayOf(2026, null));
  }

  // ═══ ПОМОЩНИ ════════════════════════════════════════════════════════════════

  /** Данъкът по шаблон B със ставките на код 401 от каталога: разходи 10, авансов 10. */
  private static BgIncomeTaxResult rentTax(BigDecimal gross, BigDecimal insurance) {
    return BgIncomeTaxResolver.resolve(
        new BgIncomeTaxRequest(
            BgIncomeTaxResolver.PATTERN_RENT,
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            new BigDecimal("10.00"),
            true,
            gross,
            insurance,
            false));
  }

  /** Данъкът по шаблон A със ставките на код 3073 от каталога: разходи 25, авансов 10. */
  private static BgIncomeTaxResult businessTax(
      BigDecimal gross, BigDecimal insurance, boolean selfInsuredDeclared) {
    return BgIncomeTaxResolver.resolve(
        new BgIncomeTaxRequest(
            BgIncomeTaxResolver.PATTERN_BUSINESS,
            new BigDecimal("25.00"),
            BigDecimal.ZERO,
            new BigDecimal("10.00"),
            true,
            gross,
            insurance,
            selfInsuredDeclared));
  }

  private static BgIncomePaymentFeedCandidate civilContract() {
    return candidate(PAID, new BigDecimal("2000.00"), "7503151233", null, true, true, "A");
  }

  private static BgIncomePaymentFeedCandidate candidate(
      LocalDate paymentDate,
      BigDecimal gross,
      String registrationCode,
      LocalDate declaredOn,
      boolean hasPartner,
      boolean hasCompany,
      String taxPattern) {
    return new BgIncomePaymentFeedCandidate(
        BgIncomePaymentFeedMapper.SOURCE_PAYROLL,
        "BgPaySlip#42",
        paymentDate,
        gross,
        BigDecimal.ZERO,
        hasPartner,
        hasCompany,
        taxPattern,
        null,
        registrationCode,
        declaredOn);
  }
}
