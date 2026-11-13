package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Аритметиката на данъчния амортизационен план по чл. 55 и чл. 58 ЗКПО.
 *
 * <p><b>Защо чиста функция, а не изчисление през услугата.</b> Модулът няма mock-подпора в
 * тестовете, а {@code BgFixedAssetLineTaxComputationServiceImpl} иска {@code
 * FixedAssetFailOverControlService}, {@code AppBaseService} и {@code FixedAssetLineToolService},
 * тоест Guice и база. Правило, което трябва да се докаже с ЧИСЛА, отива в чиста функция — точно
 * затова {@link BgTaxDepreciation} съществува отделно от услугата.
 */
class BgTaxDepreciationTest {

  // ---------------------------------------------------------------- нормите на чл. 55 ал. 2

  @Test
  @DisplayName("всяка норма по чл. 55 дава ЦЯЛО число месеци — затова планът е месечен")
  void нормите_дават_цели_месеци() {
    assertMonths("4", 300); // кат. I — сгради и съоръжения
    assertMonths("10", 120); // кат. III — транспортни средства без автомобили
    assertMonths("15", 80); // кат. VII — офис обзавеждане, други ДНА
    assertMonths("20", 60); // кат. VI — право с петгодишен срок
    assertMonths("25", 48); // кат. V — автомобили
    assertMonths("30", 40); // кат. II — машини и оборудване
    assertMonths("50", 24); // кат. II ал. 3 и кат. IV
    assertMonths("100", 12); // кат. IV ал. 7 — софтуер СУПТО
  }

  @Test
  @DisplayName("годишният брой вноски НЕ дава същите норми — това е доводът за ставка")
  void годишните_вноски_не_попадат_в_тавана() {
    // Кат. II, 30%: точният срок е 3⅓ години. С цели ГОДИШНИ вноски има само два избора и
    // двата са грешни — 4 вноски подбиват признатия разход, 3 надхвърлят тавана.
    assertEquals(
        new BigDecimal("25.00"),
        annualRateFor(4),
        "4 годишни вноски дават 25% — под тавана 30%, тоест по-малко признат разход");
    assertEquals(
        new BigDecimal("33.33"),
        annualRateFor(3),
        "3 годишни вноски дават 33,33% — НАД тавана 30%, тоест данъчна грешка");

    // Същото за кат. VII, 15%: 6⅔ години.
    assertEquals(new BigDecimal("14.29"), annualRateFor(7), "7 вноски подбиват 15%");
    assertEquals(new BigDecimal("16.67"), annualRateFor(6), "6 вноски надхвърлят 15%");

    // А в МЕСЕЦИ и двете норми излизат точно.
    assertEquals(
        new BigDecimal("30.00"), annualRateForMonths(40), "40 месеца дават точно 30% годишно");
    assertEquals(
        new BigDecimal("15.00"), annualRateForMonths(80), "80 месеца дават точно 15% годишно");
  }

  // ---------------------------------------------------------------- еталоните от заданието

  @Test
  @DisplayName("еталон: машина, ДАС 10 000 €, кат. II 30% → ДАП 3 000 / 3 000 / 3 000 / 1 000")
  void еталон_машина_кат_2() {
    List<BigDecimal> години = годишниСуми(new BigDecimal("10000.00"), new BigDecimal("30"));

    assertEquals(4, години.size(), "планът тече 40 месеца, тоест влиза в четвърта година");
    assertEquals(new BigDecimal("3000.00"), години.get(0));
    assertEquals(new BigDecimal("3000.00"), години.get(1));
    assertEquals(new BigDecimal("3000.00"), години.get(2));
    assertEquals(
        new BigDecimal("1000.00"),
        години.get(3),
        "последната година поема остатъка: 40 месеца = 3 години и 4 месеца");
    assertEquals(
        new BigDecimal("10000.00"),
        години.stream().reduce(BigDecimal.ZERO, BigDecimal::add),
        "сборът е ДАС — нищо не се губи и нищо не се дублира в закръгленията");
  }

  @Test
  @DisplayName("еталон: софтуер СУПТО, ДАС 1 200 €, 100% → цялата сума в първата година")
  void еталон_супто() {
    List<BigDecimal> години = годишниСуми(new BigDecimal("1200.00"), new BigDecimal("100"));

    assertEquals(1, години.size(), "12 месеца при норма 100% значи една година");
    assertEquals(new BigDecimal("1200.00"), години.get(0));
  }

  @Test
  @DisplayName("еталон: сграда, ДАС 300 000 €, кат. I 4% → 12 000 годишно, 25 години")
  void еталон_сграда() {
    List<BigDecimal> години = годишниСуми(new BigDecimal("300000.00"), new BigDecimal("4"));

    assertEquals(25, години.size());
    for (int i = 0; i < 25; i++) {
      assertEquals(new BigDecimal("12000.00"), години.get(i), "година " + (i + 1));
    }
  }

  // ---------------------------------------------------------------- периодичност

  @Test
  @DisplayName("периодичността разбива същата норма, без да я мени")
  void периодичността_не_мени_нормата() {
    BigDecimal норма = new BigDecimal("30");
    // ⚠️ Сравнява се с intValueExact(), а НЕ с equals върху BigDecimal: numberOfPeriods връща
    // при мащаб 20, тоест „40.00000000000000000000“, а stripTrailingZeros() го свежда до 4E+1 с
    // мащаб −1. equals на BigDecimal гледа и мащаба, тъй че равенство с new BigDecimal("40")
    // (мащаб 0) е НЕВЯРНО, макар числата да са едно и също.
    assertEquals(
        40,
        BgTaxDepreciation.numberOfPeriods(норма, 1).stripTrailingZeros().intValueExact(),
        "месечна стъпка → 40 периода");
    BigDecimal годишно = BgTaxDepreciation.numberOfPeriods(норма, 12);
    assertEquals(
        0,
        годишно.setScale(4, RoundingMode.HALF_UP).compareTo(new BigDecimal("3.3333")),
        "годишна стъпка → 3⅓ периода, тоест 30% на година и остатък в четвъртата");

    // Празна периодичност се чете като един месец, а не като нула — деление на нула тук би
    // повалило генерирането на целия план.
    assertEquals(
        BgTaxDepreciation.numberOfPeriods(норма, 1),
        BgTaxDepreciation.numberOfPeriods(норма, null),
        "липсваща периодичност значи месец");
    assertEquals(
        BgTaxDepreciation.numberOfPeriods(норма, 1),
        BgTaxDepreciation.numberOfPeriods(норма, 0),
        "нулева периодичност значи месец");
  }

  @Test
  @DisplayName("липсващата норма дава нула периоди, а не деление на нула")
  void празната_норма_не_чупи() {
    assertEquals(BigDecimal.ZERO, BgTaxDepreciation.monthsForRate(null));
    assertEquals(BigDecimal.ZERO, BgTaxDepreciation.monthsForRate(BigDecimal.ZERO));
    assertEquals(BigDecimal.ZERO, BgTaxDepreciation.numberOfPeriods(BigDecimal.ZERO, 1));
  }

  // ---------------------------------------------------------------- таваните на чл. 55

  @Test
  @DisplayName("таванът на всяка категория е абсолютният, тоест с завишенията по ал. 3 и ал. 7")
  void таваните_включват_завишенията() {
    assertEquals(new BigDecimal("4"), BgTaxDepreciation.ceilingForCategory("I"));
    assertEquals(
        new BigDecimal("50"),
        BgTaxDepreciation.ceilingForCategory("II"),
        "ал. 2 дава 30, но ал. 3 вдига до 50 за фабрично нови машини, част от първоначална"
            + " инвестиция — а „фабрично нов“ е факт за АКТИВА и не се вижда от категорията");
    assertEquals(new BigDecimal("10"), BgTaxDepreciation.ceilingForCategory("III"));
    assertEquals(
        new BigDecimal("100"),
        BgTaxDepreciation.ceilingForCategory("IV"),
        "ал. 2 дава 50, но ал. 7 вдига до 100 за софтуер СУПТО по чл. 118 ал. 16 ЗДДС");
    assertEquals(
        new BigDecimal("50"),
        BgTaxDepreciation.ceilingForCategory("V"),
        "ал. 2 дава 25, но ал. 8 (нова, ДВ бр. 30 от 2026 г., в сила от 01.01.2026 г.) вдига до 50"
            + " за електрически автомобили — а „електрически“ е факт за АКТИВА и не се вижда от"
            + " категорията");
    assertEquals(
        new BigDecimal("33.34"),
        BgTaxDepreciation.ceilingForCategory("VI"),
        "ал. 6: 100/срока на правото, но не повече от 33⅓ — единствената категория без число в"
            + " закона, и таванът ѝ е твърд");
    assertEquals(new BigDecimal("15"), BgTaxDepreciation.ceilingForCategory("VII"));
  }

  @Test
  @DisplayName("непозната категория няма таван — не се сравнява, вместо да се предполага нула")
  void непознатата_категория_не_се_сравнява() {
    assertNull(BgTaxDepreciation.ceilingForCategory(null));
    assertNull(BgTaxDepreciation.ceilingForCategory(""));
    assertNull(BgTaxDepreciation.ceilingForCategory("VIII"));
    assertFalse(
        BgTaxDepreciation.exceedsCeiling("VIII", new BigDecimal("90")),
        "без известен таван няма с какво да се сравни — тук нула би отричала всяка норма");
  }

  @Test
  @DisplayName("надхвърлянето се хваща, а равното на тавана минава")
  void надхвърлянето_се_хваща() {
    assertTrue(BgTaxDepreciation.exceedsCeiling("I", new BigDecimal("5")));
    assertFalse(
        BgTaxDepreciation.exceedsCeiling("I", new BigDecimal("4")),
        "чл. 55 ал. 2 казва „не може да ПРЕВИШАВА“ — равното на тавана е законната максимална норма");
    assertFalse(
        BgTaxDepreciation.exceedsCeiling("V", new BigDecimal("50")),
        "електрически автомобил с 50% е законен по ал. 8");
    assertTrue(
        BgTaxDepreciation.exceedsCeiling("V", new BigDecimal("60")),
        "над 50% кат. V не минава по нито една алинея");
    assertFalse(
        BgTaxDepreciation.exceedsCeiling("VII", new BigDecimal("10")),
        "по-ниска от тавана норма е избор, който законът позволява");
    assertFalse(
        BgTaxDepreciation.exceedsCeiling("VII", BigDecimal.ZERO),
        "празна норма не е надхвърляне — тя е липса и се хваща на друго място");
  }

  // ---------------------------------------------------------------- помощни

  private static void assertMonths(String rate, int expected) {
    BigDecimal months = BgTaxDepreciation.monthsForRate(new BigDecimal(rate)).stripTrailingZeros();
    assertTrue(
        months.scale() <= 0, "норма " + rate + "% дава " + months + " месеца, а не цяло число");
    assertEquals(
        expected,
        months.intValueExact(),
        "норма " + rate + "% трябва да изчерпи ДАС за " + expected + " месеца");
  }

  /** Годишната норма, която БАЗОВИЯТ модел би дал при {@code n} ГОДИШНИ вноски: 100/n. */
  private static BigDecimal annualRateFor(int annualInstalments) {
    return new BigDecimal("100")
        .divide(BigDecimal.valueOf(annualInstalments), 2, RoundingMode.HALF_UP);
  }

  /** Годишната норма при {@code n} МЕСЕЧНИ вноски: 1200/n. */
  private static BigDecimal annualRateForMonths(int months) {
    return new BigDecimal("1200").divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
  }

  /**
   * Годишните суми на ДАП-а при месечна стъпка.
   *
   * <p><b>Огледало, и то нарочно.</b> Двете формули отдолу са същите, които {@code
   * AbstractFixedAssetLineComputationServiceImpl} прилага, и се държат тук ДОСЛОВНО, за да може
   * твърдението „30% дава 3 000 / 3 000 / 3 000 / 1 000“ да се провери с числа, без Guice и без
   * база:
   *
   * <ul>
   *   <li>{@code computeDepreciationRate}: {@code 1 / брой_периоди}, при мащаб {@code
   *       CALCULATION_SCALE};
   *   <li>{@code computeDepreciation}: сумата никога не надхвърля оставащата стойност — оттам идва
   *       остатъкът в последния период;
   *   <li>{@code FixedAssetLineGenerationServiceImpl}: цикълът върти, докато стойността стигне нула
   *       ({@code while (accountingValue.signum() != 0)}), а НЕ докато свърши броячът — точно това
   *       позволява нецяло число периоди.
   * </ul>
   *
   * <p>Огледалото не проверява услугата — проверява ТВЪРДЕНИЕТО за нормата. Смени ли се {@code
   * monthsForRate}, еталоните падат.
   */
  private static List<BigDecimal> годишниСуми(BigDecimal дас, BigDecimal норма) {
    BigDecimal периоди = BgTaxDepreciation.numberOfPeriods(норма, 1);
    BigDecimal ставка =
        BigDecimal.ONE.divide(периоди, BgTaxDepreciation.CALCULATION_SCALE, RoundingMode.HALF_UP);
    BigDecimal месечна = дас.multiply(ставка).setScale(2, RoundingMode.HALF_UP);

    List<BigDecimal> години = new ArrayList<>();
    BigDecimal остатък = дас;
    BigDecimal текуща = BigDecimal.ZERO.setScale(2);
    int месец = 0;
    while (остатък.signum() != 0 && месец < 1200) {
      BigDecimal вноска = месечна.min(остатък);
      текуща = текуща.add(вноска);
      остатък = остатък.subtract(вноска);
      месец++;
      if (месец % 12 == 0 || остатък.signum() == 0) {
        години.add(текуща);
        текуща = BigDecimal.ZERO.setScale(2);
      }
    }
    return години;
  }
}
