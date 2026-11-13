package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgCivilContractResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomeTaxRequest;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomeTaxResolver;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomeTaxResult;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * АВАНСОВИЯТ ДАНЪК ПО ЧЛ. 43 ЗДДФЛ НА ФИША ПО ГРАЖДАНСКИ ДОГОВОР (вид осигурен 14).
 *
 * <p><b>⚠️ ЗАЩО ИЗОБЩО СЪЩЕСТВУВА ТОЗИ ФАЙЛ.</b> Смятано по чл. 42 за ВСЕКИ фиш, перото 7xx взима
 * за основа „облагаеми начисления − лични вноски“ и НЕ вади нормативните разходи по чл. 29. За
 * граждански договор от 2000,00 при 25 на сто това удържа 200,00 вместо 150,00 — една четвърт данък
 * без основание. Регистърът по чл. 73 ал. 1 ({@code BgIncomeTaxResolver#business}) смята верните
 * 150,00, тоест УДЪРЖАНОТО и ДЕКЛАРИРАНОТО биха се разминали пред НАП с 50,00 на един фиш.
 *
 * <p><b>⚠️ РАЗЛИКАТА Е В ЕДНА ДУМА НА НОРМАТА.</b> Чл. 42 ал. 1 урежда „авансовия данък за доходи
 * от ТРУДОВИ ПРАВООТНОШЕНИЯ“; код 14 е КСО чл. 4 ал. 3 т. 5 и 6 — труд БЕЗ трудово правоотношение.
 * Неговият авансов данък е по чл. 43 ал. 1: „разликата между ОБЛАГАЕМИЯ ДОХОД (по чл. 29) и
 * удържаните задължителни осигурителни вноски“, а облагаемият доход по чл. 29 е придобитият МИНУС
 * разходите за дейността.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgCivilContractIncomeTaxTest {

  /** {@code INCOME_TAX_RATE} от seed-а. Чл. 43 ал. 3 иска същите 10 на сто като чл. 42 ал. 4. */
  private static final BigDecimal RATE = new BigDecimal("10.0000");

  /**
   * Чл. 29 ал. 1 т. 3 — „25 на сто за доходи от упражняване на свободна професия … или
   * възнаграждения по ИЗВЪНТРУДОВИ ПРАВООТНОШЕНИЯ“.
   *
   * <p>⚠️ Стойността идва от каталога ({@code BgIncomeType.expenseRate}), не от кода: т. 2 признава
   * 40 на сто (авторски, занаят, адвокат, нотариус), т. 1 — 60 (непреработена земеделска
   * продукция). Затова има и {@link #theExpenseRateComesFromTheCatalogueNotFromTheCode}.
   */
  private static final BigDecimal EXPENSE_RATE_25 = new BigDecimal("25.00");

  private static final BigDecimal EXPENSE_RATE_40 = new BigDecimal("40.00");

  /** „Това лице няма облекчение“ — нула, не {@code null}. Виж {@code BgIncomeTaxGeneratorTest}. */
  private static final BigDecimal NO_RELIEF = BigDecimal.ZERO;

  // ═══ ЕНДЖИНЪТ С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  /** ДОСЛОВНО стубът на {@code BgIncomeTaxGeneratorTest} — подменени са само речникът и перото. */
  private static final class Generator extends BgIncomeTaxService {

    @Override
    protected BgPaySlipLineType lineType(String code) {
      if (!BgIncomeTaxService.LINE_INCOME_TAX.equals(code)) {
        return null;
      }
      return type(
          700, BgIncomeTaxService.LINE_INCOME_TAX, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
      line.setBgAmount(amount);
      return line;
    }
  }

  /**
   * ФИШОВАТА УСЛУГА С ПОДМЕНЕНА САМО СТАВКАТА.
   *
   * <p>⚠️ {@code taxBaseHeader} и {@code businessExpense} се викат ИСТИНСКИ — те са предметът на
   * теста. Подменена е само {@code incomeTaxRate}, защото тя чете {@code BgPayrollParameter} от
   * базата; същият идиом като в {@code BgPaySlipServiceTest}.
   */
  private static final class Service extends BgPaySlipService {

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return RATE;
    }

    BigDecimal headerBase(BgPaySlip slip) {
      return taxBaseHeader(slip, slip.getBgPaySlipLineList());
    }

    BigDecimal expense(BgPaySlip slip) {
      return businessExpense(slip);
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static BgPaySlipLineType type(
      int number, String code, String name, String sign, boolean taxable, boolean insurable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setCode(code);
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(insurable);
    t.setBgIsPermanentCharacter(false);
    t.setActive(true);
    return t;
  }

  /**
   * Фиш с вид осигурен {@code code} и вид доход с {@code expenseRate} ({@code null} — без вид
   * доход).
   */
  private static BgPaySlip slip(String insuredTypeCode, BigDecimal expenseRate) {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setName("03/PY2026");
    p.setFromDate(LocalDate.of(2026, 3, 1));
    p.setToDate(LocalDate.of(2026, 3, 31));
    s.setPeriod(p);
    s.setBgPaySlipLineList(new ArrayList<>());

    EmploymentContract contract = new EmploymentContract();
    contract.setBgInsuredTypeCode(insuredTypeCode);
    if (expenseRate != null) {
      BgIncomeType incomeType = new BgIncomeType();
      incomeType.setExpenseRate(expenseRate);
      contract.setBgIncomeType(incomeType);
    }
    s.setEmploymentContract(contract);
    return s;
  }

  /** Възнаграждение 2000,00 (облагаемо) и, ако е над нула, ЗОВ от 5xx DEBIT. */
  private static BgPaySlip slipWith(
      Generator g, String insuredTypeCode, BigDecimal expenseRate, String contributions) {
    BgPaySlip slip = slip(insuredTypeCode, expenseRate);
    g.addLine(
        slip,
        g.lineFrom(
            type(100, "pl_osnovna", "Възнаграждение по договор", "CREDIT", true, true),
            new BigDecimal("2000.00")));
    BigDecimal zov = new BigDecimal(contributions);
    if (zov.signum() > 0) {
      g.addLine(
          slip,
          g.lineFrom(type(501, "pl_vn_pension_lice", "Пенсии — лице", "DEBIT", false, false), zov));
    }
    return slip;
  }

  private static BgPaySlipLine taxLine(BgPaySlip slip) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgIncomeTaxService.LINE_INCOME_TAX.equals(line.getBgPaySlipLineType().getCode())) {
        return line;
      }
    }
    return null;
  }

  // ═══ 1 · ГЛАВНИЯТ СВИДЕТЕЛ · 150,00, НЕ 200,00 ═══════════════════════════

  /**
   * ⚠️ FAIL-FIRST: по чл. 42 основата е 2000,00 (разходите не се вадеха) и перото носеше 200,00.
   * Този тест падаше на числото — 200,00 срещу очакваните 150,00.
   */
  @Test
  @DisplayName("⚠️ ГД 2000,00 · 25% разходи · ЗОВ 0,00 → перо 150,00, НЕ 200,00 (чл. 43 ал. 1)")
  void theCivilContractTaxDeductsTheStatutoryExpenses() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, EXPENSE_RATE_25, "0");

    List<BgPaySlipLineOutcome> outcomes = g.generateBusiness(slip, RATE, s.expense(slip));

    assertEquals(
        BgPaySlipLineOutcome.Status.COMPUTED,
        outcomes.get(0).status,
        String.valueOf(outcomes.get(0).detail));

    BgPaySlipLine line = taxLine(slip);
    assertNotNull(line, "перото на данъка трябва да е на фиша");
    assertEquals(
        0,
        new BigDecimal("150.00").compareTo(line.getBgAmount()),
        "⚠️ 10% × (2000,00 − 500,00 разходи по чл. 29 − 0,00 ЗОВ). По чл. 42 щеше да е 200,00."
            + " Намерено: "
            + line.getBgAmount());
    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(line.getBgQuantity()),
        "количеството е ОСНОВАТА по чл. 43 ал. 1 — придобитото минус разходите минус ЗОВ");
    assertEquals(0, RATE.compareTo(line.getBgRate()), "ставката по чл. 43 ал. 3 е 10 на сто");
    assertEquals(
        "DEBIT", line.getBgSign(), "⚠️ данъкът се УДЪРЖА — CREDIT би го добавил към нетото");
    assertEquals(
        "Данък по чл. 43 ЗДДФЛ",
        line.getName(),
        "⚠️ перото трябва да НАЗОВАВА нормата, по която е сметнато — речникът казва чл. 42");
  }

  /** ⚠️ ЗОВ-ът се вади СЛЕД разходите — чл. 43 ал. 1 брои от облагаемия доход по чл. 29. */
  @Test
  @DisplayName("⚠️ ГД 2000,00 · 25% разходи · ЗОВ 240,00 → основа 1260,00, данък 126,00")
  void theContributionsComeOffTheAlreadyReducedIncome() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, EXPENSE_RATE_25, "240");

    g.generateBusiness(slip, RATE, s.expense(slip));

    BgPaySlipLine line = taxLine(slip);
    assertNotNull(line);
    assertEquals(
        0,
        new BigDecimal("1260.00").compareTo(line.getBgQuantity()),
        "⚠️ 2000,00 − 500,00 разходи − 240,00 ЗОВ. Намерено: " + line.getBgQuantity());
    assertEquals(
        0,
        new BigDecimal("126.00").compareTo(line.getBgAmount()),
        "⚠️ 10% × 1260,00. По чл. 42 щеше да е 176,00. Намерено: " + line.getBgAmount());
  }

  /**
   * ⚠️ СТАВКАТА Е ОТ КАТАЛОГА, НЕ ЗАКОВАНА 25.
   *
   * <p>Същият фиш с вид доход по чл. 29 ал. 1 т. 2 (авторски, занаят, адвокат, нотариус) признава
   * 40 на сто: 2000,00 − 800,00 = 1200,00 и данък 120,00. Закована „25“ в кода би дала 150,00 и
   * тестът пада.
   */
  @Test
  @DisplayName("⚠️ КАТАЛОГЪТ РЕШАВА · 40% по чл. 29 ал. 1 т. 2 → основа 1200,00, данък 120,00")
  void theExpenseRateComesFromTheCatalogueNotFromTheCode() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, EXPENSE_RATE_40, "0");

    g.generateBusiness(slip, RATE, s.expense(slip));

    BgPaySlipLine line = taxLine(slip);
    assertNotNull(line);
    assertEquals(
        0,
        new BigDecimal("120.00").compareTo(line.getBgAmount()),
        "⚠️ 10% × (2000,00 − 800,00). Закована ставка 25 щеше да даде 150,00. Намерено: "
            + line.getBgAmount());
  }

  // ═══ 2 · ГАРАНЦИЯТА, ЧЕ ДВИГАТЕЛЯТ НЕ Е ПИПНАТ ЗА ОСТАНАЛИТЕ ══════════════

  /**
   * ⚠️ НАЕТИЯТ (01) ОСТАВА ПО ЧЛ. 42 — разходи по чл. 29 НЕ се признават.
   *
   * <p>Същите числа: 2000,00 и 240,00 ЗОВ. По чл. 42 ал. 2 основата е 1760,00 и данъкът 176,00, а
   * НЕ 126,00. Това е свидетелят, че чл. 43 е РАЗКЛОНЕНИЕ за код 14, а не смяна на двигателя.
   */
  @Test
  @DisplayName("⚠️ НАЕТИ (01) 2000,00 · ЗОВ 240,00 → основа 1760,00, данък 176,00 — НЕПРОМЕНЕНО")
  void theEmploymentSlipIsUntouchedByTheArticle43Branch() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, "01", null, "240");

    assertTrue(
        !BgCivilContractResolver.isCivilContract("01"), "код 01 не е граждански договор — предпос");

    g.generate(slip, RATE, NO_RELIEF);

    BgPaySlipLine line = taxLine(slip);
    assertNotNull(line);
    assertEquals(
        0,
        new BigDecimal("1760.00").compareTo(line.getBgQuantity()),
        "⚠️ по чл. 42 ал. 2 разходи НЕ се вадят: 2000,00 − 240,00. Намерено: "
            + line.getBgQuantity());
    assertEquals(
        0,
        new BigDecimal("176.00").compareTo(line.getBgAmount()),
        "⚠️ 10% × 1760,00. Намерено: " + line.getBgAmount());
    assertEquals("Данък по чл. 42 ЗДДФЛ", line.getName(), "името на наетия остава това от речника");
    assertEquals(
        0,
        new BigDecimal("1760.00").compareTo(s.headerBase(slip)),
        "⚠️ и ЗАГЛАВНАТА основа на наетия минава по чл. 42 — разклонението не го докосва");
  }

  /** ⚠️ ДУК (10) също остава по чл. 42 — той е чл. 4 ал. 1 т. 7 КСО, не т. 5 и 6. */
  @Test
  @DisplayName("⚠️ ДУК (10) 2000,00 · ЗОВ 240,00 → заглавна основа 1760,00 — НЕПРОМЕНЕНО")
  void theDukSlipIsUntouchedByTheArticle43Branch() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, "10", null, "240");

    assertEquals(
        0,
        new BigDecimal("1760.00").compareTo(s.headerBase(slip)),
        "⚠️ ДУК-ът се облага по чл. 42: разходи по чл. 29 не се признават");
    assertNull(
        s.expense(slip),
        "⚠️ ДУК няма вид доход по ЗДДФЛ и НЕ бива да получава приспадане — но и не се пита за него");
  }

  // ═══ 3 · ЗАГЛАВНАТА СУМА И ПАЗАЧЪТ ═══════════════════════════════════════

  /**
   * ⚠️ ПЕРОТО И ЗАГЛАВНАТА ЧАСТ ЧЕТАТ ЕДНО ЧИСЛО, тоест {@code refuseTaxMismatch} МИНАВА.
   *
   * <p>Разклонен само генераторът, перото щеше да носи 150,00, а заглавната част — 200,00, и
   * пазачът щеше да блокира ВЕРЕН фиш с необяснима разлика от 50,00.
   */
  @Test
  @DisplayName("⚠️ ЗАГЛАВНА СУМА = ПЕРО = 150,00 → refuseTaxMismatch МЪЛЧИ")
  void theHeaderAndTheLineAgreeSoTheGuardStaysSilent() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, EXPENSE_RATE_25, "0");

    BigDecimal headerBase = s.headerBase(slip);
    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(headerBase),
        "⚠️ заглавната основа минава по чл. 43. Намерено: " + headerBase);

    BigDecimal headerTax = BgPaySlipResolver.taxAmount(headerBase, RATE);
    assertEquals(0, new BigDecimal("150.00").compareTo(headerTax), "заглавната сума е 150,00");

    g.generateBusiness(slip, RATE, s.expense(slip));

    assertEquals(
        0,
        headerTax.compareTo(BgPaySlipResolver.taxFromLines(slip.getBgPaySlipLineList())),
        "⚠️ перата от 7xx трябва да носят точно заглавната сума");
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(headerTax, slip.getBgPaySlipLineList()),
        "⚠️ пазачът трябва да МЪЛЧИ — иначе верен фиш остава непотвърдим");
  }

  // ═══ 4 · ФИШЪТ И РЕГИСТЪРЪТ КАЗВАТ ЕДНО И СЪЩО ═══════════════════════════

  /**
   * ⚠️ УДЪРЖАНОТО = ДЕКЛАРИРАНОТО. Регистърът по чл. 73 ал. 1 ({@code
   * BgIncomeTaxResolver#business}) смята 150,00; фиш, който удържа 200,00, дава 50,00 разлика пред
   * НАП на един-единствен фиш.
   *
   * <p>⚠️ Тестът кара ДВАТА пътя да сметнат СЪЩОТО плащане и сверява трите числа: разходи, основа и
   * данък. Не се сравняват формули, а резултати.
   */
  @Test
  @DisplayName("⚠️ ФИШ = РЕГИСТЪР · 2000,00 при 25% → и двата дават 150,00")
  void theSlipAndTheRegisterAgreeOnTheSamePayment() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, EXPENSE_RATE_25, "0");
    g.generateBusiness(slip, RATE, s.expense(slip));
    BgPaySlipLine line = taxLine(slip);

    // Регистърът: шаблон A (чл. 29), същото бруто, същите разходи, същата авансова ставка.
    BgIncomeTaxResult register =
        BgIncomeTaxResolver.resolve(
            new BgIncomeTaxRequest(
                BgIncomeTaxResolver.PATTERN_BUSINESS,
                EXPENSE_RATE_25,
                null,
                RATE,
                true,
                new BigDecimal("2000.00"),
                BigDecimal.ZERO,
                false));

    assertTrue(register.isResolved(), String.valueOf(register.refusal));
    assertEquals(
        0,
        register.taxAmount.compareTo(line.getBgAmount()),
        "⚠️ фишът удържа "
            + line.getBgAmount()
            + ", регистърът декларира "
            + register.taxAmount
            + " — двете трябва да са ЕДНО число");
    assertEquals(
        0,
        register.taxableBase.compareTo(line.getBgQuantity()),
        "⚠️ и ОСНОВИТЕ трябва да съвпадат, не само данъкът");
    assertEquals(
        0,
        register.expenseAmount.compareTo(s.expense(slip)),
        "⚠️ и приспаднатите разходи — те отиват в колона 13 на справката по чл. 73 ал. 1");
  }

  /** ⚠️ Същото сверяване, но с ненулеви ЗОВ — асиметрията на чл. 43 ал. 1 се проверява и там. */
  @Test
  @DisplayName("⚠️ ФИШ = РЕГИСТЪР · 2000,00 при 25% и ЗОВ 240,00 → и двата дават 126,00")
  void theSlipAndTheRegisterAgreeWithContributionsToo() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, EXPENSE_RATE_25, "240");
    g.generateBusiness(slip, RATE, s.expense(slip));

    BgIncomeTaxResult register =
        BgIncomeTaxResolver.resolve(
            new BgIncomeTaxRequest(
                BgIncomeTaxResolver.PATTERN_BUSINESS,
                EXPENSE_RATE_25,
                null,
                RATE,
                true,
                new BigDecimal("2000.00"),
                new BigDecimal("240.00"),
                false));

    assertEquals(
        0, register.taxAmount.compareTo(taxLine(slip).getBgAmount()), "⚠️ 126,00 и от двата пътя");
  }

  // ═══ 5 · ОТКАЗИТЕ ════════════════════════════════════════════════════════

  /**
   * ⚠️ БЕЗ ВИД ДОХОД — ОТКАЗ, А НЕ ТИХО ЗАВИШЕН ДАНЪК.
   *
   * <p>Посоката е причината: сметнат без приспадането, данъкът излиза 200,00 вместо 150,00, тоест
   * удържан В ПОВЕЧЕ от лицето, при чист зелен екран.
   */
  @Test
  @DisplayName("⚠️ ГД без вид доход по ЗДДФЛ → ОТКАЗ, перо НЯМА (не 200,00)")
  void theMissingIncomeTypeRefusesInsteadOfOvertaxing() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, null, "0");

    assertNull(s.expense(slip), "без вид доход разходите са null — ОТКАЗ, не нула");

    List<BgPaySlipLineOutcome> outcomes = g.generateBusiness(slip, RATE, s.expense(slip));

    assertTrue(outcomes.get(0).isRefusal(), "трябва да е отказ");
    assertTrue(
        outcomes.get(0).detail.contains("чл. 29"),
        "причината трябва да назовава нормата: " + outcomes.get(0).detail);
    assertNull(taxLine(slip), "⚠️ перо НЕ се създава — инак фишът щеше да удържи завишен данък");
  }

  /**
   * ⚠️ И ЗАГЛАВНАТА СУМА ОСТАВА ЗАВИШЕНА ПРИ ОТКАЗ, ЗА ДА СЕ ВИДИ ОТКАЗЪТ.
   *
   * <p>Дословно доводът на {@code taxableBase} за {@code null} облекчение: прочетен като нула
   * ОСНОВА, заглавният данък също щеше да стане нула и щеше да СЪВПАДНЕ с липсващото перо — {@code
   * refuseTaxMismatch} би замълчал и фишът щеше да се потвърди БЕЗ данък.
   */
  @Test
  @DisplayName(
      "⚠️ ОТКАЗЪТ СЕ ВИЖДА · заглавна основа 2000,00 срещу липсващо перо → пазачът БЛОКИРА")
  void theRefusalStaysVisibleThroughTheGuard() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, null, "0");

    BigDecimal headerBase = s.headerBase(slip);
    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(headerBase),
        "⚠️ без ставка основата остава НЕнамалена — не нула");

    g.generateBusiness(slip, RATE, s.expense(slip));

    BigDecimal headerTax = BgPaySlipResolver.taxAmount(headerBase, RATE);
    assertNotEquals(
        0,
        headerTax.compareTo(BgPaySlipResolver.taxFromLines(slip.getBgPaySlipLineList())),
        "заглавната сума и перата трябва да се РАЗМИНАТ");
    assertNotNull(
        BgPaySlipResolver.refuseTaxMismatch(headerTax, slip.getBgPaySlipLineList()),
        "⚠️ пазачът трябва да БЛОКИРА — инак фишът се потвърждава без данък");
  }

  // ═══ 6 · ОБЛЕКЧЕНИЕТО ПО ЧЛ. 18 АЛ. 2 НЕ СЕ ПРОСМУКВА В ЧЛ. 43 ═══════════

  /**
   * ⚠️ ЧЛ. 18 АЛ. 2 Е САМО ЗА ЧЛ. 42, И ТОВА Е ДОСЛОВНО В НОРМАТА: „Месечната данъчна основа ПО ЧЛ.
   * 42, АЛ. 2 за доходи от ТРУДОВИ ПРАВООТНОШЕНИЯ на лица с 50 и с над 50 на сто намалена
   * работоспособност се намалява с 660 лв.“
   *
   * <p>Свидетелят е СТРУКТУРЕН: вратата по чл. 43 НЕ приема облекчение изобщо, тоест няма как то да
   * бъде приложено по погрешка. Основата остава 1500,00 и при лице с решение на ТЕЛК — за него по
   * чл. 43 действа ГОДИШНИЯТ праг по ал. 2 (7920 лв. от всички източници), който един месечен фиш
   * на един платец не носи. Виж обявената граница в {@code BgIncomeTaxService#generateBusiness}.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 43 НЯМА месечно облекчение по чл. 18 ал. 2 → основата остава 1500,00")
  void theMonthlyDisabilityReliefDoesNotApplyUnderArticle43() {
    Generator g = new Generator();
    Service s = new Service();
    BgPaySlip slip = slipWith(g, BgCivilContractResolver.INSURED_TYPE_CODE, EXPENSE_RATE_25, "0");

    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(s.headerBase(slip)),
        "⚠️ 337,45 облекчение НЕ бива да се вади — обхватът на чл. 18 ал. 2 е чл. 42 ал. 2");
  }

  // ═══ 7 · ЕДНАТА ФОРМУЛА ЗА ДВЕТЕ СТРАНИ НА ФИША ══════════════════════════

  /**
   * ⚠️ ДАНЪЧНОТО И ОСИГУРИТЕЛНОТО ПРИСПАДАНЕ СА ЕДНО ЧИСЛО, СМЕТНАТО ВЕДНЪЖ.
   *
   * <p>КСО чл. 6 ал. 4 реже осигурителната база с „разходите за дейността, определени по реда на
   * ЗДДФЛ“; чл. 43 ал. 1 през чл. 29 реже данъчната със същите разходи. Преписани поотделно, двете
   * са свободни да се разминат по ЗАКРЪГЛЯНЕ — а стотинка разлика между двете основи на един фиш е
   * точно правдоподобното число, което никой контрол не хваща. Затова и двете минават през {@code
   * BgCivilContractResolver#expenseOf}.
   *
   * <p>⚠️ Числото е избрано да СЕ ЗАКРЪГЛЯ: 1333,33 × 25% = 333,3325 → 333,33.
   */
  @Test
  @DisplayName("⚠️ ЕДНА ФОРМУЛА · осигурителното и данъчното приспадане съвпадат до стотинка")
  void bothSidesOfTheSlipShareTheSameExpenseFormula() {
    BigDecimal gross = new BigDecimal("1333.33");

    BigDecimal taxSide = BgCivilContractResolver.expenseOf(gross, EXPENSE_RATE_25);
    BgCivilContractResolver.Outcome insuranceSide =
        BgCivilContractResolver.resolve(gross, EXPENSE_RATE_25, true, null);

    assertEquals(
        0,
        new BigDecimal("333.33").compareTo(taxSide),
        "⚠️ 1333,33 × 25% = 333,3325 → 333,33 по § 5.3. Намерено: " + taxSide);
    assertEquals(
        0,
        insuranceSide.getExpense().compareTo(taxSide),
        "⚠️ двете страни на фиша трябва да приспаднат ЕДНО число");
  }

  /** ⚠️ {@code null} ставка дава {@code null} разход и от общата врата — ОТКАЗ, не нула. */
  @Test
  @DisplayName("⚠️ expenseOf(2000,00, null) → null (ОТКАЗ), а не 0,00")
  void theSharedFormulaRefusesOnAMissingRate() {
    assertNull(BgCivilContractResolver.expenseOf(new BigDecimal("2000.00"), null));
  }
}
