package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕНЕРАТОРЪТ НА ГРУПА 7xx — данъкът по чл. 42 ЗДДФЛ става перо.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА.</b> {@code BgPaySlipResolver#taxBase} и {@code
 * #taxAmount} се викат ИСТИНСКИ — основата и данъкът са техни. Подменени са само речникът и
 * създаването на перото.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgIncomeTaxGeneratorTest {

  /** {@code INCOME_TAX_RATE} за 2026 г. — чл. 42 ЗДДФЛ. */
  private static final BigDecimal RATE = new BigDecimal("10.0000");

  /**
   * {@code INCOME_TAX_RATE_SEAFARER} за 2026 г. — чл. 42 ал. 4 ЗДДФЛ, доходи, придобити като МОРСКО
   * ЛИЦЕ.
   *
   * <p>⚠️ ЕДИНСТВЕНАТА разлика е ставката. Основата се смята по СЪЩИЯ ред: „месечната данъчна
   * основа по ал. 3 се умножи по данъчна ставка 10 на сто, а за доходите, придобити като морско
   * лице — по данъчна ставка 1 на сто“. Затова тук няма втора фикстура — същият фиш, друго число.
   *
   * <p>⚠️ Кой вид осигурен е морско лице НЕ се решава тук: изборът е на {@code
   * BgPaySlipService#incomeTaxRate} по {@code BgInsuredType.bgIsSeafarer} и си има свои тестове
   * ({@code BgSeafarerIncomeTaxRateTest} и {@code BgSeafarerIncomeTaxRateDbTest}). Този генератор
   * получава ставката наготово и това е нарочно.
   */
  private static final BigDecimal RATE_SEAFARER = new BigDecimal("1.0000");

  /**
   * ⚠️ „ТОВА ЛИЦЕ НЯМА ОБЛЕКЧЕНИЕ“ — НУЛА, НЕ {@code null}.
   *
   * <p>Заварените случаи тук не описват лице с решение за намалена работоспособност, тоест
   * облекчението им е нула и НИТО ЕДНО от числата им не се мени. {@code null} на това място значи
   * друго — ОТКАЗ, — и си има свой тест.
   */
  private static final BigDecimal NO_RELIEF = BigDecimal.ZERO;

  /**
   * {@code DISABILITY_RELIEF_MONTHLY} за 2026 г. — 337,45 EUR (660 лв. / 1,95583).
   *
   * <p>ЗДДФЛ чл. 18 ал. 2, ДОСЛОВНО: „Месечната данъчна основа по чл. 42, ал. 2 за доходи от
   * трудови правоотношения на лица с 50 и с над 50 на сто намалена работоспособност се намалява с
   * 660 лв., включително за месеца на настъпване на неработоспособността и за месеца на изтичане
   * срока на валидност на решението.“
   *
   * <p>⚠️ Полага ли се изобщо и за кой месец НЕ се решава тук: това е на {@code
   * BgPaySlipService#disabilityRelief} и си има свои тестове. Генераторът получава числото наготово
   * — по същия довод като ставката.
   */
  private static final BigDecimal RELIEF = new BigDecimal("337.45");

  // ═══ ЕНДЖИНЪТ С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static final class Service extends BgIncomeTaxService {

    /** Речникът носи ли реда на данъка. Изключен — за да се види отказът при непуснат seed. */
    boolean dictionaryHasTheRow = true;

    @Override
    protected BgPaySlipLineType lineType(String code) {
      if (!dictionaryHasTheRow || !BgIncomeTaxService.LINE_INCOME_TAX.equals(code)) {
        return null;
      }
      // Редът от bg_pay_slip_line_type.csv: 700, TAX, DEBIT, необлагаем, не е осиг. доход.
      return type(
          700, BgIncomeTaxService.LINE_INCOME_TAX, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      // ДОСЛОВНО копие на BgPaySlipService#lineFrom — замразяване чрез копиране.
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

  private static BgPaySlip slip() {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setName("03/PY2026");
    p.setFromDate(LocalDate.of(2026, 3, 1));
    p.setToDate(LocalDate.of(2026, 3, 31));
    s.setPeriod(p);
    s.setBgPaySlipLineList(new ArrayList<>());
    return s;
  }

  /** Заплата 1000,00 (облагаема) и лична вноска 137,80 от 5xx DEBIT — основата е 862,20. */
  private static BgPaySlip slipWithSalaryAndEmployeeContribution(Service s) {
    BgPaySlip slip = slip();
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "pl_osnovna", "Основно възнаграждение", "CREDIT", true, true),
            new BigDecimal("1000.00")));
    s.addLine(
        slip,
        s.lineFrom(
            type(501, "pl_vn_pension_lice", "Пенсии — лице", "DEBIT", false, false),
            new BigDecimal("137.80")));
    return slip;
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, int number) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == number) {
        return line;
      }
    }
    return null;
  }

  // ═══ 1 · ПЕРОТО ═══════════════════════════════════════════════════════════

  /**
   * ⚠️ FAIL-FIRST: без {@link BgIncomeTaxService} група 7xx стои празна и всеки фиш с данък иска
   * ръчно перо, за да мине {@code refuseTaxMismatch}. Тогава този тест пада на {@code
   * assertNotNull}.
   */
  @Test
  @DisplayName("⚠️ ДОД = 10% × (1000,00 − 137,80) = 86,22, перо 700 със знак DEBIT")
  void theTaxLineIsGenerated() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, RATE, NO_RELIEF);

    assertEquals(1, outcomes.size());
    assertEquals(
        BgPaySlipLineOutcome.Status.COMPUTED,
        outcomes.get(0).status,
        String.valueOf(outcomes.get(0).detail));

    BgPaySlipLine line = lineOf(slip, 700);
    assertNotNull(line, "перото на данъка трябва да е на фиша");
    assertEquals("pl_dod", line.getBgPaySlipLineType().getCode());
    assertEquals(
        "DEBIT", line.getBgSign(), "⚠️ данъкът се УДЪРЖА — CREDIT би го добавил към нетото");
    assertEquals(
        0,
        new BigDecimal("86.22").compareTo(line.getBgAmount()),
        "⚠️ основата е 862,20, НЕ 1000,00: личните вноски (5xx DEBIT) я намаляват. Намерено: "
            + line.getBgAmount());
    assertEquals(
        0,
        new BigDecimal("862.20").compareTo(line.getBgQuantity()),
        "количеството е ОСНОВАТА — документацията на сумата");
    assertEquals(0, RATE.compareTo(line.getBgRate()), "ставката е процентът");
    assertTrue(
        !line.getBgIsTaxable() && !line.getBgIsInsuranceIncome(),
        "данъкът не се облага сам и не е осигурителен доход");
  }

  /**
   * ⚠️ СЪЩАТА ОСНОВА, ДРУГА СТАВКА — чл. 42 ал. 4 ЗДДФЛ за морско лице.
   *
   * <p>Фикстурата е ДОСЛОВНО тази на {@link #theTaxLineIsGenerated}: 1000,00 облагаемо начисление и
   * 137,80 лична вноска от 5xx DEBIT, тоест основа 862,20. Разликата в перото е само ставката —
   * 8,62 вместо 86,22.
   *
   * <p>⚠️ ЕДНА ДЕСЕТА, А НЕ „КРЪГЛО ЧИСЛО ПО-МАЛКО“. 862,20 × 1% = 8,622 и закръглянето по § 5.3
   * (два знака, половина нагоре) го прави 8,62. Тоест числото проверява и закръглянето, не само
   * умножението.
   */
  @Test
  @DisplayName(
      "⚠️ МОРСКО ЛИЦЕ · ДОД = 1% × (1000,00 − 137,80) = 8,62 — същата основа, друга ставка")
  void theSeafarerRateProducesOneTenthOfTheTax() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, RATE_SEAFARER, NO_RELIEF);

    assertEquals(
        BgPaySlipLineOutcome.Status.COMPUTED,
        outcomes.get(0).status,
        String.valueOf(outcomes.get(0).detail));

    BgPaySlipLine line = lineOf(slip, 700);
    assertNotNull(line, "перото на данъка трябва да е на фиша и при 1 на сто");
    assertEquals("pl_dod", line.getBgPaySlipLineType().getCode());
    assertEquals("DEBIT", line.getBgSign(), "⚠️ данъкът се УДЪРЖА и при намалената ставка");
    assertEquals(
        0,
        new BigDecimal("8.62").compareTo(line.getBgAmount()),
        "⚠️ 862,20 × 1% = 8,622 → 8,62 по § 5.3. Намерено: " + line.getBgAmount());
    assertEquals(
        0,
        new BigDecimal("862.20").compareTo(line.getBgQuantity()),
        "⚠️ ОСНОВАТА НЕ СЕ ПРОМЕНЯ ОТ СТАВКАТА — чл. 42 ал. 4 сменя само нея. Намерено: "
            + line.getBgQuantity());
    assertEquals(
        0,
        RATE_SEAFARER.compareTo(line.getBgRate()),
        "⚠️ перото носи СВОЯТА ставка — тя е документацията на сумата и без нея 8,62 е число без"
            + " произход");
  }

  /**
   * ⚠️ КОТВАТА: 1 на сто и 10 на сто дават РАЗЛИЧНИ числа върху една и съща основа.
   *
   * <p>Свидетелят мери двете страни наведнъж. Върне ли {@code BgPaySlipService#incomeTaxRate}
   * 10,0000 и за морско лице, тези две числа СЪВПАДАТ и разликата от 77,60 се удържа от лицето.
   * Съвпаднат ли някога, изборът на ставка е спрял да пази каквото и да е.
   */
  @Test
  @DisplayName("⚠️ 1% и 10% върху една и съща основа дават 8,62 и 86,22 — десетократна разлика")
  void theTwoRatesDoNotAgree() {
    Service atTen = new Service();
    BgPaySlip ten = slipWithSalaryAndEmployeeContribution(atTen);
    atTen.generate(ten, RATE, NO_RELIEF);

    Service atOne = new Service();
    BgPaySlip one = slipWithSalaryAndEmployeeContribution(atOne);
    atOne.generate(one, RATE_SEAFARER, NO_RELIEF);

    assertEquals(0, new BigDecimal("86.22").compareTo(lineOf(ten, 700).getBgAmount()));
    assertEquals(0, new BigDecimal("8.62").compareTo(lineOf(one, 700).getBgAmount()));
    assertEquals(
        0,
        new BigDecimal("77.60")
            .compareTo(lineOf(ten, 700).getBgAmount().subtract(lineOf(one, 700).getBgAmount())),
        "⚠️ ТОЛКОВА се удържаше в повече от всяко морско лице, докато ставката не се четеше");
  }

  // ═══ 1в · ОБЛЕКЧЕНИЕТО ЗА НАМАЛЕНА РАБОТОСПОСОБНОСТ (чл. 42 ал. 3 т. 1) ══

  /**
   * ⚠️ СЪЩАТА ФИКСТУРА, НАМАЛЕНА ОСНОВА. ЗДДФЛ чл. 42 ал. 3, ДОСЛОВНО: „Месечната данъчна основа се
   * намалява със: 1. данъчното облекчение за намалена работоспособност по чл. 18, ал. 2 ...“
   *
   * <p>862,20 − 337,45 = 524,75; 10 на сто от нея е 52,475 → 52,48 по § 5.3. Тоест облекчението
   * спестява 33,74 месечно — точно разликата между 86,22 и 52,48.
   *
   * <p>⚠️ И {@code bgQuantity} НОСИ НАМАЛЕНАТА ОСНОВА. Перото документира сумата си: записана там
   * пълната основа 862,20, документацията щеше да противоречи на числото — 10 на сто от 862,20 не е
   * 52,48 и всеки, който провери с калкулатор, щеше да намери „грешка“, каквато няма.
   */
  @Test
  @DisplayName("⚠️ ТЕЛК · основа 862,20 − облекчение 337,45 = 524,75 → ДОД 52,48 при 10%")
  void theDisabilityReliefReducesTheBaseBeforeTheRate() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, RATE, RELIEF);

    assertEquals(
        BgPaySlipLineOutcome.Status.COMPUTED,
        outcomes.get(0).status,
        String.valueOf(outcomes.get(0).detail));

    BgPaySlipLine line = lineOf(slip, 700);
    assertNotNull(line, "перото на данъка трябва да е на фиша");
    assertEquals(
        0,
        new BigDecimal("52.48").compareTo(line.getBgAmount()),
        "⚠️ 524,75 × 10% = 52,475 → 52,48. Намерено: " + line.getBgAmount());
    assertEquals(
        0,
        new BigDecimal("524.75").compareTo(line.getBgQuantity()),
        "⚠️ документираната основа Е НАМАЛЕНАТА — инак перото си противоречи. Намерено: "
            + line.getBgQuantity());
  }

  /**
   * ⚠️ FAIL-FIRST. Срещу вариант БЕЗ приспадане същият фиш дава основа 862,20 и данък 86,22 — тоест
   * 33,74 в повече, удържани всеки месец от лице с решение на ТЕЛК. Съвпаднат ли някога двете
   * числа, приспадането е спряло да прави каквото и да е.
   */
  @Test
  @DisplayName("⚠️ БЕЗ приспадане същият фиш дава 86,22 вместо 52,48 — 33,74 разлика")
  void withoutTheReliefTheTaxWouldBeOverstated() {
    Service withRelief = new Service();
    BgPaySlip reduced = slipWithSalaryAndEmployeeContribution(withRelief);
    withRelief.generate(reduced, RATE, RELIEF);

    Service withoutRelief = new Service();
    BgPaySlip full = slipWithSalaryAndEmployeeContribution(withoutRelief);
    withoutRelief.generate(full, RATE, NO_RELIEF);

    assertEquals(0, new BigDecimal("52.48").compareTo(lineOf(reduced, 700).getBgAmount()));
    assertEquals(0, new BigDecimal("86.22").compareTo(lineOf(full, 700).getBgAmount()));
    assertEquals(
        0,
        new BigDecimal("33.74")
            .compareTo(
                lineOf(full, 700).getBgAmount().subtract(lineOf(reduced, 700).getBgAmount())),
        "⚠️ ТОЛКОВА се удържаше в повече всеки месец от лице с решение на ТЕЛК");
  }

  /**
   * ⚠️ ОБЛЕКЧЕНИЕТО РАБОТИ И ПРИ 1 НА СТО. Чл. 42 ал. 3 намалява ОСНОВАТА, а ал. 4 избира ставката
   * — тоест редът е един и същ за двете ставки. 524,75 × 1% = 5,2475 → 5,25.
   */
  @Test
  @DisplayName("⚠️ ТЕЛК + морско лице: 524,75 × 1% = 5,25 — облекчението е ПРЕДИ ставката")
  void theReliefAppliesToTheSeafarerRateToo() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    s.generate(slip, RATE_SEAFARER, RELIEF);

    assertEquals(
        0,
        new BigDecimal("5.25").compareTo(lineOf(slip, 700).getBgAmount()),
        "⚠️ приложено СЛЕД ставката, облекчението щеше да отнеме една стотна от себе си");
  }

  /**
   * ⚠️ ОБЛЕКЧЕНИЕ, ПО-ГОЛЯМО ОТ ОСНОВАТА, ДАВА ЗАКОННА НУЛА — не отрицателен данък и не перо с
   * 0,00.
   *
   * <p>Основата се ограничава до нула от резолвера; перо с 0,00 в група 7xx би заело място на фиша
   * и би твърдяло, че данък е удържан.
   */
  @Test
  @DisplayName("⚠️ облекчение над основата → законна нула, перо НЕ се създава")
  void aReliefLargerThanTheBaseLeavesNoTax() {
    Service s = new Service();
    BgPaySlip slip = slip();
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "pl_osnovna", "Основно възнаграждение", "CREDIT", true, true),
            new BigDecimal("300.00")));

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, RATE, RELIEF);

    assertEquals(BgPaySlipLineOutcome.Status.LEGAL_ZERO, outcomes.get(0).status);
    assertTrue(outcomes.get(0).detail.contains("337.45"), outcomes.get(0).detail);
    assertNull(lineOf(slip, 700), "⚠️ перо с 0,00 би твърдяло, че данък е удържан");
  }

  /**
   * ⚠️ {@code null} ОБЛЕКЧЕНИЕ Е ОТКАЗ, НЕ НУЛА — лицето НОСИ решение, а параметърът за периода не
   * е зареден. Прочетено като „без облекчение“, това би удържало пълния данък и фишът щеше да
   * изглежда сметнат.
   */
  @Test
  @DisplayName(
      "⚠️ липсващ DISABILITY_RELIEF_MONTHLY → ОТКАЗ, който сочи параметъра, и НИТО едно перо")
  void aMissingReliefParameterIsRefused() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, RATE, null);

    assertEquals(BgPaySlipLineOutcome.Status.REFUSED, outcomes.get(0).status);
    assertTrue(
        outcomes.get(0).detail.contains("DISABILITY_RELIEF_MONTHLY"), outcomes.get(0).detail);
    assertNull(outcomes.get(0).amount);
    assertNull(lineOf(slip, 700), "⚠️ отказът НЕ създава перо");
  }

  /**
   * ⚠️ ЛИЧНИТЕ ВНОСКИ СА ЦЯЛАТА ПРИЧИНА ГЕНЕРАТОРЪТ ДА ВЪРВИ СЛЕД ОСИГУРОВКИТЕ.
   *
   * <p>Свидетелят мери и двете страни: същият фиш БЕЗ перото от 5xx дава данък 100,00 вместо 86,22.
   * Тоест разменен ред в {@code prepare()} завишава данъка с 13,78 и фишът изглежда сметнат.
   */
  @Test
  @DisplayName("⚠️ БЕЗ перата от 5xx същият фиш дава 100,00 вместо 86,22 — оттам идва редът")
  void withoutTheContributionLinesTheTaxIsOverstated() {
    Service withContributions = new Service();
    BgPaySlip full = slipWithSalaryAndEmployeeContribution(withContributions);
    withContributions.generate(full, RATE, NO_RELIEF);

    Service without = new Service();
    BgPaySlip bare = slip();
    without.addLine(
        bare,
        without.lineFrom(
            type(100, "pl_osnovna", "Основно възнаграждение", "CREDIT", true, true),
            new BigDecimal("1000.00")));
    without.generate(bare, RATE, NO_RELIEF);

    assertEquals(0, new BigDecimal("86.22").compareTo(lineOf(full, 700).getBgAmount()));
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(lineOf(bare, 700).getBgAmount()),
        "⚠️ КОТВАТА НА СВИДЕТЕЛЯ. Ако това някога съвпадне с 86,22, редът в prepare() спира да пази"
            + " каквото и да е.");
  }

  /**
   * ⚠️ ПЕРОТО НА ДАНЪКА НЕ ВЛИЗА В СВОЯТА СИ ОСНОВА — тоест втори проход не се иска и обратна
   * връзка няма.
   */
  @Test
  @DisplayName("⚠️ второ generate() дава СЪЩИЯ данък — 7xx не се брои в данъчната основа")
  void theTaxLineDoesNotFeedItsOwnBase() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    s.generate(slip, RATE, NO_RELIEF);
    BigDecimal first = lineOf(slip, 700).getBgAmount();
    s.generate(slip, RATE, NO_RELIEF);

    assertEquals(
        0,
        first.compareTo(lineOf(slip, 700).getBgAmount()),
        "⚠️ Ако перото влизаше в основата си, вторият проход щеше да даде друго число");
    assertEquals(
        0,
        new BigDecimal("862.20").compareTo(BgPaySlipResolver.taxBase(slip.getBgPaySlipLineList())),
        "основата не се мени от собственото си перо");
  }

  /** ⚠️ Това е числото, което {@code refuseTaxMismatch} сверява — генераторът затваря пазача. */
  @Test
  @DisplayName("⚠️ перата от 7xx съвпадат със заглавния данък — refuseTaxMismatch мълчи")
  void theGuardIsSatisfiedWithoutManualWork() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    s.generate(slip, RATE, NO_RELIEF);
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();
    BigDecimal header = BgPaySlipResolver.taxAmount(BgPaySlipResolver.taxBase(lines), RATE);

    assertEquals(
        0,
        header.compareTo(BgPaySlipResolver.taxFromLines(lines)),
        "заглавният данък и перата от 7xx трябва да са едно число");
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(header, lines),
        "⚠️ пазачът трябва да мълчи БЕЗ ръчно перо — точно това затваря генераторът");
    assertEquals(
        0,
        new BigDecimal("775.98").compareTo(BgPaySlipResolver.netTotal(lines)),
        "1000,00 − 137,80 (лична вноска) − 86,22 (данък)");
  }

  // ═══ 2 · ИДЕМПОТЕНТНОСТ И АДИТИВНОСТ ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ ИДЕМПОТЕНТНО · три пъти generate() дава едно перо с една сума")
  void generateIsIdempotent() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    s.generate(slip, RATE, NO_RELIEF);
    s.generate(slip, RATE, NO_RELIEF);
    s.generate(slip, RATE, NO_RELIEF);

    assertEquals(3, slip.getBgPaySlipLineList().size(), "заплата, вноска и ЕДИН данък");
    assertEquals(
        0,
        new BigDecimal("86.22").compareTo(lineOf(slip, 700).getBgAmount()),
        "⚠️ ПРИСВОЯВА СЕ, не се натрупва");
  }

  @Test
  @DisplayName("⚠️ обновяването пренаписва и основата, и ставката — инак обясняват друга сума")
  void anUpdateRewritesTheDocumentationToo() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);
    s.generate(slip, RATE, NO_RELIEF);

    // Морско лице по чл. 42 ал. 4 ЗДДФЛ — 1 на сто. ⚠️ Ставката се ПОДАВА; днес нищо в
    // производството не я избира (виж обявената липса в BgIncomeTaxService).
    s.generate(slip, new BigDecimal("1.0000"), NO_RELIEF);

    BgPaySlipLine line = lineOf(slip, 700);
    assertEquals(0, new BigDecimal("8.62").compareTo(line.getBgAmount()), "1% от 862,20");
    assertEquals(
        0,
        new BigDecimal("1.0000").compareTo(line.getBgRate()),
        "⚠️ ставката трябва да е новата — стара ставка обяснява стара сума");
  }

  // ═══ 3 · ЗАКОННАТА НУЛА ═══════════════════════════════════════════════════

  /**
   * ⚠️ ОСНОВА НУЛА → БЕЗ РЕД, И ТОВА НЕ Е ОТКАЗ. Точно случаят на златния фиш: болничният е
   * необлагаем, а личните вноски свалят основата под нула и резолверът я ограничава до нула.
   */
  @Test
  @DisplayName("⚠️ основа нула → БЕЗ перо, и това е законна нула, не отказ")
  void aZeroBaseProducesNoLine() {
    Service s = new Service();
    BgPaySlip slip = slip();
    // Болничен: осигурителен доход, но НЕОБЛАГАЕМ (ЗДДФЛ чл. 24 ал. 2 т. 14).
    s.addLine(
        slip,
        s.lineFrom(
            type(195, "pl_bolnichen_rabotodatel", "Болничен от осигурител", "CREDIT", false, true),
            new BigDecimal("109.87")));
    s.addLine(
        slip,
        s.lineFrom(
            type(501, "pl_vn_pension_lice", "Пенсии — лице", "DEBIT", false, false),
            new BigDecimal("15.15")));

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, RATE, NO_RELIEF);

    assertEquals(
        BgPaySlipLineOutcome.Status.LEGAL_ZERO,
        outcomes.get(0).status,
        "⚠️ нито COMPUTED (би направил перо с 0,00), нито REFUSED (би поискал поправка)");
    assertTrue(!outcomes.get(0).isRefusal());
    assertNull(outcomes.get(0).amount, "⚠️ сума при нула-без-ред би се сумирала някъде");
    assertNull(lineOf(slip, 700), "перо с 0,00 в 7xx би твърдяло, че данък е удържан");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(BgPaySlipResolver.taxFromLines(slip.getBgPaySlipLineList())),
        "и пазачът пак съвпада: 0,00 = 0,00");
  }

  /** Фиш без нито едно перо — основата е нула, и ред пак не се прави. */
  @Test
  @DisplayName("празен фиш дава законна нула, не отказ и не перо")
  void anEmptySlipProducesNoLine() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, RATE, NO_RELIEF);

    assertEquals(BgPaySlipLineOutcome.Status.LEGAL_ZERO, outcomes.get(0).status);
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
  }

  // ═══ 4 · ГРАНИЦИТЕ И ОТКАЗИТЕ ═════════════════════════════════════════════

  @Test
  @DisplayName("данъкът на коригиращото копие се ПИШЕ, на заличаващото — не")
  void theCorrectionIsTaxedAndTheDeletionIsSkipped() {
    // Ставката и облекчението се подават по ПЕРИОДА на копието и по замразения режим — виж
    // BgPaySlipService#generateIncomeTax; генераторът само пише подаденото.
    Service s = new Service();
    BgPaySlip correction = slipWithSalaryAndEmployeeContribution(s);
    correction.setBgIsCorrection(true);
    assertFalse(s.generate(correction, RATE, NO_RELIEF).isEmpty(), "коригиращото: данъкът се пише");
    assertNotNull(lineOf(correction, 700), "перото 700 е на копието");

    Service t = new Service();
    BgPaySlip deletion = slipWithSalaryAndEmployeeContribution(t);
    deletion.setBgIsDeletion(true);
    assertTrue(t.generate(deletion, RATE, NO_RELIEF).isEmpty(), "заличаващото: пропуска се");
    assertNull(lineOf(deletion, 700), "⚠️ заличаващото носи данъка на оригинала дословно");
  }

  @Test
  @DisplayName("потвърден фиш не стига до генератора")
  void aConfirmedSlipIsSkipped() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);

    assertTrue(s.generate(slip, RATE, NO_RELIEF).isEmpty());
    assertNull(lineOf(slip, 700));
  }

  /**
   * ⚠️ ЛИПСВАЩА СТАВКА Е ОТКАЗ, НЕ НУЛА НА СТО. Нула на сто данък е твърдение, което никой отчет не
   * може да защити пред проверка — а фишът остава непотвърдим, вместо тихо да не удържи данък.
   */
  @Test
  @DisplayName("⚠️ липсваща ставка → ОТКАЗ, който сочи параметъра, и НИТО едно перо")
  void aMissingRateIsRefused() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, null, NO_RELIEF);

    assertEquals(BgPaySlipLineOutcome.Status.REFUSED, outcomes.get(0).status);
    assertTrue(outcomes.get(0).detail.contains("INCOME_TAX_RATE"), outcomes.get(0).detail);
    assertNull(outcomes.get(0).amount);
    assertNull(lineOf(slip, 700), "⚠️ перо с 0,00 щеше да мине пазача и да скрие липсата");
  }

  @Test
  @DisplayName("⚠️ липсващ ред в речника вдига ИЗКЛЮЧЕНИЕ със сумата и App Install")
  void aMissingDictionaryRowThrows() {
    Service s = new Service();
    s.dictionaryHasTheRow = false;
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> s.generate(slip, RATE, NO_RELIEF));

    assertTrue(e.getMessage().contains("pl_dod"), e.getMessage());
    assertTrue(
        e.getMessage().contains("86.22"), "сумата, която не може да се удържи: " + e.getMessage());
    assertTrue(e.getMessage().contains("bg_pay_slip_line_type.csv"), e.getMessage());
    assertTrue(e.getMessage().contains("App Install"), e.getMessage());
  }

  /**
   * ⚠️ ОБЛЕКЧЕНИЯТА НЕ СЕ ПРИСПАДАТ, И ТОВА Е ИЗМЕРЕНО, НЕ ПРЕМЪЛЧАНО.
   *
   * <p>Свидетелят закрепва СЕГАШНОТО поведение и числото, което би било вярно за лице с решение на
   * ТЕЛК по чл. 18 ал. 2 ЗДДФЛ. Появи ли се поле във входа, този тест се сменя нарочно — а дотогава
   * липсата е видима, вместо да живее само в javadoc.
   */
  @Test
  @DisplayName("⚠️ ОБЯВЕНА ЛИПСА · облекчението по чл. 18 ал. 2 НЕ се приспада — измерено")
  void theDisabilityReliefIsNotDeducted() {
    Service s = new Service();
    BgPaySlip slip = slipWithSalaryAndEmployeeContribution(s);

    s.generate(slip, RATE, NO_RELIEF);

    BigDecimal relief = new BigDecimal("337.45"); // DISABILITY_RELIEF_MONTHLY от seed-а
    BigDecimal withRelief =
        BgPaySlipResolver.taxAmount(new BigDecimal("862.20").subtract(relief), RATE);

    assertEquals(
        0,
        new BigDecimal("86.22").compareTo(lineOf(slip, 700).getBgAmount()),
        "днешното поведение: 10% върху цялата основа");
    assertEquals(
        0,
        new BigDecimal("52.48").compareTo(withRelief),
        "⚠️ ТОЛКОВА би бил данъкът с облекчението — 33,74 евро разлика в месеца. Входът НЯМА поле за"
            + " решение на ТЕЛК (BgPayrollInput), а параметърът DISABILITY_RELIEF_MONTHLY е в"
            + " seed-а и се чака. Същото важи и за заглавната сума в recompute() — перото не"
            + " въвежда нова грешка, а прави съществуващата видима.");
  }
}
