package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВИДЕТЕЛИТЕ НА Т. 34 — <b>формулата на НПОПДОО чл. 32, изречение по изречение</b>.
 *
 * <p><b>⚠️ КАКВО ТЕЗИ СВИДЕТЕЛИ ДОКАЗВАТ И КАКВО НЕ.</b> Те сверяват сметката срещу ДОСЛОВНИЯ текст
 * на чл. 32 (НПОПДОО, ПМС 188/2015, ред. ДВ бр. 72 от 2020 г.), прочетен от самия акт. Тоест зелен
 * тест значи „числото следва текста, както е прочетен“. Той НЕ доказва, че НОИ ще сметне същото
 * обезщетение: това се вижда само от приета декларация и изплатено обезщетение, а те са човешки
 * стъпки.
 *
 * <p><b>⚠️ И ЗАЩО ТОЧКАТА ИМА СОБСТВЕН ТЕСТОВ КЛАС.</b> Т. 34 е ЕДИНСТВЕНАТА точка на Д1, която не
 * се ПРЕНАСЯ, а се СМЯТА — останалите доходни точки идват готови от агрегатите на фиша, а вноските
 * са ставки на перата. Смятана точка иска свидетел на всяка своя съставка поотделно, не един ред в
 * теста на записа: разликата между трите правдоподобни бази (т. 21, т. 29, т. 31) е точно колкото
 * едно обезщетение по КТ, а разликата между „таван върху нетото“ и „таван върху базата“ — колкото
 * вноските и данъка.
 */
class BgD1NetPayTest {

  /** Максималният месечен осигурителен доход за 01.01–31.07.2026 г. — ЗБДОО чл. 9 т. 1 б. „в“. */
  private static final BigDecimal CAP = new BigDecimal("2111.64");

  private static BgPaySlipLine earning(int number, String amount, boolean insuranceIncome) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(BgPaySlipResolver.CREDIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsInsuranceIncome(insuranceIncome);
    l.setBgIsTaxable(Boolean.TRUE);
    return l;
  }

  /** Перо на ЛИЧНА вноска: група 5xx, DEBIT. */
  private static BgPaySlipLine employeeContribution(int number, String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(BgPaySlipResolver.DEBIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsInsuranceIncome(Boolean.FALSE);
    l.setBgIsTaxable(Boolean.FALSE);
    BgContributionFund f = new BgContributionFund();
    f.setCode("PENSION");
    l.setBgContributionFund(f);
    return l;
  }

  /** Перо на вноска ЗА СМЕТКА НА ОСИГУРИТЕЛЯ: група 5xx, CREDIT. */
  private static BgPaySlipLine employerContribution(int number, String amount) {
    BgPaySlipLine l = employeeContribution(number, amount);
    l.setBgSign(BgPaySlipResolver.CREDIT);
    return l;
  }

  private static BgPaySlip slip(String tax, BgPaySlipLine... lines) {
    BgPaySlip s = new BgPaySlip();
    s.setReference("FISH-2026-05-0001");
    List<BgPaySlipLine> list = new ArrayList<>();
    for (BgPaySlipLine l : lines) {
      list.add(l);
    }
    s.setBgPaySlipLineList(list);
    s.setBgTaxAmount(new BigDecimal(tax));
    return s;
  }

  // ═══ АЛ. 1 — ТРИЧЛЕННАТА ФОРМУЛА ═══

  @Test
  @DisplayName("чл. 32 ал. 1 · възнаграждение − лични вноски − данък")
  void theThreeTermFormula() {
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip(
                "176.42",
                earning(100, "2000.00", true),
                employeeContribution(501, "131.60"),
                employeeContribution(505, "28.00"),
                employeeContribution(507, "8.00"),
                employeeContribution(517, "64.00"),
                employeeContribution(513, "44.00")),
            CAP);

    assertEquals(
        0,
        new BigDecimal("1547.98").compareTo(outcome.getNetPay()),
        "ДОСЛОВНО: „изплатеното или начисленото месечно възнаграждение се намали с определените със"
            + " закон задължителни осигурителни вноски за сметка на лицата и с данъците по ЗДДФЛ“."
            + " 2000,00 − 275,60 − 176,42 = 1547,98.");
    assertNull(outcome.getProblem(), "напълно определима сметка не носи проблем");
    assertFalse(outcome.isCapped(), "1547,98 е под тавана 2111,64");
  }

  @Test
  @DisplayName("⚠️ ал. 1 · ВНОСКИТЕ НА РАБОТОДАТЕЛЯ НЕ СЕ ВАДЯТ — „за сметка на ЛИЦАТА“")
  void onlyTheEmployeeSideIsDeducted() {
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip(
                "176.42",
                earning(100, "2000.00", true),
                employeeContribution(501, "275.60"),
                // Вноските на осигурителя за същия доход — те са НЕГОВ разход, не удръжка.
                employerContribution(500, "339.80")),
            CAP);

    assertEquals(
        0,
        new BigDecimal("1547.98").compareTo(outcome.getNetPay()),
        "⚠️ АЛ. 1 КАЗВА „ЗА СМЕТКА НА ЛИЦАТА“, тоест САМО DEBIT перата от 5xx. Извадени и двете"
            + " страни, нетото щеше да е 1208,18 — правдоподобно число, по-малко от вярното с точно"
            + " вноската на работодателя, и НОИ би платил по-малко обезщетение по него.");
  }

  @Test
  @DisplayName("⚠️ ал. 4 · базата е доходът от ТРУДОВА ДЕЙНОСТ, не всяко облагаемо плащане")
  void theBaseIsIncomeFromLabourActivity() {
    // Обезщетение по КТ: облагаемо, но НЕ доход от трудова дейност (НЕВД чл. 1 ал. 8) — тоест
    // речникът му дава bgIsInsuranceIncome = false и то остава ИЗВЪН базата на т. 34.
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip(
                "176.42",
                earning(100, "2000.00", true),
                earning(901, "800.00", false),
                employeeContribution(501, "275.60")),
            CAP);

    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(outcome.getBase()),
        "⚠️ АЛ. 4 ДОСЛОВНО: базата „включва брутното трудово възнаграждение и други допълнителни"
            + " ДОХОДИ ОТ ТРУДОВА ДЕЙНОСТ за съответния месец“. Обезщетението при уволнение не е"
            + " такъв доход. Броено в базата, нетото щеше да е 2547,98 → над тавана, тоест точката"
            + " щеше да излезе РЯЗАНА на 2111,64 вместо сметната 1547,98.");
    assertEquals(
        0,
        new BigDecimal("1547.98").compareTo(outcome.getNetPay()),
        "2000,00 − 275,60 − 176,42 = 1547,98");
  }

  @Test
  @DisplayName("⚠️ ал. 5 · социалните разходи, които са доход на лицето, ВЛИЗАТ в базата")
  void socialExpensesEnterTheBase() {
    // Перо 271 „Социален разход НЕ в натура, доход на лицето“ — речникът му дава
    // bgIsInsuranceIncome = true (НЕВД чл. 2 ал. 1).
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip(
                "176.42",
                earning(100, "2000.00", true),
                earning(271, "100.00", true),
                employeeContribution(501, "275.60")),
            CAP);

    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(outcome.getBase()),
        "⚠️ АЛ. 5 ДОСЛОВНО: „При определяне на месечното нетно възнаграждение се вземат предвид и"
            + " средствата, предоставени от работодателя за сметка на СОЦИАЛНИТЕ РАЗХОДИ, както и"
            + " определените върху тях … вноски за сметка на лицата и дължимият данък.“ Тоест базата"
            + " е по-широка от брутната работна заплата по НСОРЗ чл. 16 ал. 2 (т. 29) — а точно т. 29"
            + " е най-съблазнителният грешен източник за т. 34.");
    assertEquals(0, new BigDecimal("1647.98").compareTo(outcome.getNetPay()));
  }

  // ═══ ТАВАНЪТ ═══

  @Test
  @DisplayName("⚠️ таванът реже НЕТОТО, не базата — разликата е вноските и данъка")
  void theCapAppliesToTheNetNotTheBase() {
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip("250.00", earning(100, "3000.00", true), employeeContribution(501, "413.40")),
            CAP);

    assertEquals(
        0,
        CAP.compareTo(outcome.getNetPay()),
        "⚠️ ПОДЛОГЪТ на „не може да бъде повече от максималния месечен осигурителен доход“ в ал. 1 е"
            + " МЕСЕЧНОТО НЕТНО ВЪЗНАГРАЖДЕНИЕ. Сметнато: 3000,00 − 413,40 − 250,00 = 2336,60 → над"
            + " тавана, тоест 2111,64. Рязана БАЗАТА, точката щеше да е 2111,64 − 413,40 − 250,00 ="
            + " 1448,24 — по-малко число, което изглежда точно толкова пресметнато.");
    assertTrue(outcome.isCapped(), "рязането се вижда отвън, за да може да се обяви");
    assertNull(outcome.getProblem(), "рязането НЕ е проблем на сметката — то Е сметката");
  }

  @Test
  @DisplayName("⚠️ БАЗАТА ВЛИЗА НЕКАПНАТА · осигуряемо 3000 при таван 2111,64 — разликата е 561,89")
  void theBaseEntersUncappedAndOnlyTheNetIsCapped() {
    // ═══ РЕАЛИСТИЧЕН ФИШ НАД ТАВАНА ═══
    // Начислено 3000,00. ⚠️ ВНОСКИТЕ обаче са смятани върху КАПНАТАТА база (2111,64), защото точно
    // това прави производственият път: BgInsurableIncomeService налага тавана и записва РЕЗУЛТАТА
    // на
    // фиша, а BgContributionResolver смята перата върху него. Оттам личните вноски са 13,78 на сто
    // от 2111,64, закръглени ПООТДЕЛНО по фонд (§ 2 ал. 2):
    //   Пенсии 6,58 % → 138,95 · ОЗМ 1,40 % → 29,56 · Безработица 0,40 % → 8,45
    //   здравно 3,20 % → 67,57 · УПФ 2,20 % → 46,46        общо = 290,99
    // Данък: облагаем 3000,00 − 290,99 = 2709,01 → 10 % = 270,90.
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip(
                "270.90",
                earning(100, "3000.00", true),
                employeeContribution(501, "138.95"),
                employeeContribution(505, "29.56"),
                employeeContribution(507, "8.45"),
                employeeContribution(517, "67.57"),
                employeeContribution(513, "46.46")),
            CAP);

    assertEquals(
        0,
        new BigDecimal("3000.00").compareTo(outcome.getBase()),
        "⚠️ БАЗАТА Е НЕКАПНАТИТЕ 3000,00, И ТОВА Е ВЯРНОТО. Чл. 32 ал. 4 казва, че базата е"
            + " „изплатеното или начисленото месечно ВЪЗНАГРАЖДЕНИЕ … брутното трудово"
            + " възнаграждение и други допълнителни доходи от трудова дейност“ — БЕЗ таван. Таванът"
            + " в ал. 1 е върху РЕЗУЛТАТА. ⚠️ И insuranceIncomeBase го дава некапнат по устройство:"
            + " javadoc-ът му казва „ОСИГУРИТЕЛЕН ДОХОД преди под и таван“ — перата носят пълното"
            + " начисление, а таванът живее в заглавното bgInsuranceIncome, което тази сметка НЕ"
            + " чете.");
    assertEquals(
        0,
        new BigDecimal("290.99").compareTo(outcome.getEmployeeContributions()),
        "⚠️ И ВНОСКИТЕ СА ДЕЙСТВИТЕЛНИТЕ, не 13,78 % от 3000,00 (което би било 413,40). Те се четат"
            + " от ПЕРАТА, а перата вече са смятани върху капнатата база — тоест таванът влиза в"
            + " сметката на т. 34 през УДРЪЖКИТЕ, без да пипа базата.");
    assertEquals(0, new BigDecimal("270.90").compareTo(outcome.getTax()));

    assertTrue(outcome.isCapped(), "сметнатото нето 2438,11 е НАД тавана");
    assertEquals(
        0,
        CAP.compareTo(outcome.getNetPay()),
        "3000,00 − 290,99 − 270,90 = 2438,11 → рязано на 2111,64");

    // ⚠️ КОНТРАФАКТУАЛЪТ, С ЧИСЛО. Ако базата се капнеше ПРЕДИ сметката, т. 34 щеше да е
    // 2111,64 − 290,99 − 270,90 = 1549,75 — с 561,89 по-малко, и изглеждащо точно толкова
    // пресметнато. НОИ смята обезщетенията от това число.
    assertEquals(
        0,
        new BigDecimal("561.89")
            .compareTo(
                outcome
                    .getNetPay()
                    .subtract(
                        CAP.subtract(outcome.getEmployeeContributions())
                            .subtract(outcome.getTax()))),
        "разликата между двата прочита е 561,89 лв. на едно лице за един месец");
  }

  @Test
  @DisplayName("липсващ таван е ОТКАЗ, а не „без горна граница“")
  void aMissingCapIsARefusal() {
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip("250.00", earning(100, "3000.00", true), employeeContribution(501, "413.40")),
            null);

    assertNull(
        outcome.getNetPay(),
        "⚠️ null, НЕ 2336,60. Без таван сметката е вярна за обикновения фиш и невярна за"
            + " високоплатеното лице — и двете изглеждат еднакво пресметнати.");
    assertEquals(BgD1NetPayResolver.PROBLEM_CAP_MISSING, outcome.getProblem());
    assertEquals(
        0,
        new BigDecimal("3000.00").compareTo(outcome.getBase()),
        "съставките остават видими и при отказ — иначе човекът, който попълва на ръка, започва от"
            + " нищо");
  }

  // ═══ АЛ. 1 ИЗРЕЧЕНИЕ 2 — НЕНАЧИСЛЕНО ВЪЗНАГРАЖДЕНИЕ ═══

  @Test
  @DisplayName("чл. 32 ал. 1 изр. 2 · неначислено възнаграждение → назована хипотеза, не нула")
  void notAccruedIsDeclaredNotZero() {
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(slip("0.00", employeeContribution(501, "44.05")), CAP);

    assertNull(outcome.getNetPay());
    assertEquals(
        BgD1NetPayResolver.PROBLEM_NOT_ACCRUED,
        outcome.getProblem(),
        "⚠️ ХИПОТЕЗАТА ИМА СВОЙ ОТГОВОР В НАРЕДБАТА: „минималният месечен осигурителен доход по чл. 6"
            + " ал. 2 т. 3 КСО ИЛИ минималната месечна работна заплата за страната, ако нямат"
            + " определен минимален осигурителен доход“. Кое от двете зависи от ДЛЪЖНОСТТА — тоест"
            + " това е решение с два правдоподобни изхода и не се взима в чиста функция.");
  }

  @Test
  @DisplayName("⚠️ неначисленото НЕ се обявява като отрицателно — двете искат различни действия")
  void notAccruedIsNotReportedAsNegative() {
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(slip("120.00", employeeContribution(501, "44.05")), CAP);

    assertEquals(
        BgD1NetPayResolver.PROBLEM_NOT_ACCRUED,
        outcome.getProblem(),
        "нула минус вноски и данък Е отрицателно число, но причината е ДРУГА: липсва начисление, а не"
            + " противоречие между източниците. Обявено като отрицателно, то би пратило човек да"
            + " проверява фиша, вместо да впише минималния осигурителен доход.");
  }

  // ═══ ОТРИЦАТЕЛНОТО НЕТО ═══

  @Test
  @DisplayName("отрицателно нето е НАХОДКА, а не изгладена нула")
  void aNegativeNetIsReportedNotFlattened() {
    // Голямо обезщетение вдига данъка, а базата на т. 34 не го брои — двата източника се
    // разминават.
    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(
            slip(
                "900.00",
                earning(100, "1000.00", true),
                earning(901, "8000.00", false),
                employeeContribution(501, "137.80")),
            CAP);

    assertNull(
        outcome.getNetPay(),
        "⚠️ НУЛА НЕ СЕ ЗАПИСВА: „0,00“ е валидно число, върху което НОИ би сметнал обезщетение за"
            + " работил човек. Изгладено, противоречието между двата източника изчезва безшумно.");
    assertEquals(BgD1NetPayResolver.PROBLEM_NEGATIVE, outcome.getProblem());
  }

  @Test
  @DisplayName("описанието носи ВСЯКА съставка — човекът, който сверява, не смята наум")
  void describeShowsEveryTerm() {
    String text =
        BgD1NetPayResolver.describe(
            BgD1NetPayResolver.resolve(
                slip("176.42", earning(100, "2000.00", true), employeeContribution(501, "275.60")),
                CAP));

    assertTrue(text.contains("2000.00"), text);
    assertTrue(text.contains("275.60"), text);
    assertTrue(text.contains("176.42"), text);
    assertTrue(text.contains("2111.64"), text);
  }
}
