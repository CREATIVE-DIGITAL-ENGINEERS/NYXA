package com.axelor.apps.bglocalization.service.art49;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.db.BgArt73Para6Record;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.service.art73.BgArt73Para6Aggregator;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГОДИШНОТО ПРЕИЗЧИСЛЯВАНЕ ПО ЧЛ. 49 — с числа, без база и без Guice.
 *
 * <p>Свидетелите тръгват от най-простия случай, който човек може да провери наум: 24 000 доход,
 * 3000 осигуровки, 2100 авансово удържан данък. Всяко следващо твърдение мени ЕДНО нещо спрямо
 * него.
 */
class BgArt49RecalculationTest {

  /** Ставката по чл. 42/чл. 48 — в проценти, както я носи {@code BgPayrollParameter}. */
  private static final BigDecimal TEN_PERCENT = new BigDecimal("10.0000");

  @Test
  @DisplayName(
      "СВИДЕТЕЛ · доход 24000 − осигуровки 3000 = основа 21000, данък 2100, авансово 2100 →"
          + " изравняване 0")
  void theTextbookCaseSettlesAtZero() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "24000.00", "3000.00", "0.00", "2100.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0,
        new BigDecimal("21000.00").compareTo(result.getBaseArt25Para1()),
        "ред 8.1, колона 6 = сборът в колона 3 − сборът в колони 4 и 5: " + result);
    assertEquals(
        0,
        new BigDecimal("21000.00").compareTo(result.getBaseArt49()),
        "ред 8.3, колона 6 = 8.1 + 8.2, а 8.2 е нула без доход по код 103: " + result);
    assertEquals(
        0,
        new BigDecimal("21000.00").compareTo(result.getTaxableBase()),
        "колона 17 без облекчения е равна на колона 6 на ред 8.3: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getAnnualTax()),
        "колона 18 = колона 17 х 10 на сто: " + result);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(result.getSettlement()),
        "колона 19 = колона 18 − авансово удържаното: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getToWithhold()), "колона 20: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getToRefund()), "колона 21: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getTotalWithheld()),
        "колона 22 = колона 7 + колона 20 − колона 21: " + result);
  }

  /**
   * ⚠️ ПОСОКАТА НА ЗНАКА Е ТОВА, КОЕТО РЕШАВА ДАЛИ ЛИЦЕТО ПЛАЩА ИЛИ ПОЛУЧАВА. Чл. 49 ал. 5:
   * по-висок годишен данък → разликата се УДЪРЖА. Ал. 6: по-нисък → работодателят ВЪЗСТАНОВЯВА.
   * Разменени, двете биха дали правдоподобно число с обратен знак — грешка, която не пада никъде и
   * излиза при лицето.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · авансово удържано 2000 при годишен данък 2100 → колона 20 = 100")
  void tooLittleWithheldGoesToColumnTwenty() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "24000.00", "3000.00", "0.00", "2000.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0, new BigDecimal("100").compareTo(result.getSettlement()), "колона 19: " + result);
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(result.getToWithhold()),
        "чл. 49 ал. 5 — довнасяне до 31 януари: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getToRefund()), "колона 21: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getTotalWithheld()),
        "колона 22 = 2000 + 100 − 0: " + result);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · авансово удържано 2200 при годишен данък 2100 → колона 21 = 100")
  void tooMuchWithheldGoesToColumnTwentyOne() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "24000.00", "3000.00", "0.00", "2200.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0, new BigDecimal("-100").compareTo(result.getSettlement()), "колона 19: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getToWithhold()), "колона 20: " + result);
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(result.getToRefund()),
        "чл. 49 ал. 6 — възстановяване до 31 януари: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getTotalWithheld()),
        "колона 22 = 2200 + 0 − 100: " + result);
  }

  /**
   * ⚠️ ОТРЯЗВАНЕ, А НЕ ЗАКРЪГЛЯНЕ — ВТОРИЯТ ПРИМЕР В УКАЗАНИЕТО Е ЕДИНСТВЕНОТО, КОЕТО ГО ДОКАЗВА.
   * Указание т. 7 към образеца: „Ако сумата в колона 19 е 1000,31 лв., се вписва 1000 лв. Ако тази
   * сума е 1000,95 лв., СЪЩО се вписва 1000 лв.“ При {@code HALF_UP} второто щеше да даде 1001.
   *
   * <p>Числата тук са нагласени така, че колона 18 да излезе точно 1000,95: основа 10 009,50 при
   * ставка 10 на сто. Авансово удържан данък няма, тоест колона 19 = колона 18.
   */
  @Test
  @DisplayName("⚠️ ЗАКРЪГЛЯНЕ · 1000,95 в колона 19 се вписва като 1000, а не като 1001")
  void columnNineteenTruncatesInsteadOfRounding() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "10009.50", "0.00", "0.00", "0.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0,
        new BigDecimal("1000.95").compareTo(result.getAnnualTax()),
        "колона 18 остава със стотинки — отрязва се САМО колона 19: " + result);
    assertEquals(
        0,
        new BigDecimal("1000").compareTo(result.getSettlement()),
        "указание т. 7: 1000,95 → 1000, а не 1001 — това е DOWN, не HALF_UP: " + result);
  }

  @Test
  @DisplayName("⚠️ ЗАКРЪГЛЯНЕ · и надолу от нулата: −1000,95 се вписва като −1000")
  void truncationTowardsZeroAlsoHoldsForRefunds() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "10009.50", "0.00", "0.00", "2001.90"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0,
        new BigDecimal("-1000").compareTo(result.getSettlement()),
        "отрязването е КЪМ НУЛАТА и в двете посоки — образецът дава само положителни примери, но"
            + " HALF_UP тук щеше да даде −1001: "
            + result);
    assertEquals(
        0, new BigDecimal("1000.00").compareTo(result.getToRefund()), "колона 21: " + result);
  }

  /**
   * ⚠️ МОРСКОТО ЛИЦЕ ВЛИЗА СЪС СВИТА ОСНОВА, А НЕ С НАМАЛЕНА СТАВКА. Чл. 25 ал. 3: годишната основа
   * е 10 на сто от определената по ал. 1. Върху нея ляга ОБИКНОВЕНАТА ставка от 10 на сто — и
   * произведението дава 1 на сто ефективно, тоест точно ставката по чл. 42 ал. 4, с която фишът
   * вече е удържал авансово. Затова изравняването на морско лице с изряден фиш излиза нула.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · код 103: основа 21000 → ред 8.2 = 2100, данък 210, изравняване 0")
  void aSeafarerBaseIsTenPercentOfTheOrdinaryOne() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(BgArt73Para6Aggregator.CODE_SEAFARER, "24000.00", "3000.00", "0.00", "210.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(result.getBaseArt25Para1()),
        "ред 8.1 брои САМО код 101: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getBaseArt25Para3()),
        "ред 8.2 = (24000 − 3000) х 10 на сто по чл. 25 ал. 3: " + result);
    assertEquals(
        0,
        new BigDecimal("210.00").compareTo(result.getAnnualTax()),
        "колона 18 = 2100 х 10 на сто — 1 на сто ефективно, както чл. 42 ал. 4: " + result);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(result.getSettlement()),
        "фиш по чл. 42 ал. 4 и преизчисляване по чл. 25 ал. 3 се схождат: " + result);
  }

  /**
   * ⚠️ КОД 102 НЕ ВЛИЗА В ОСНОВАТА — ЧЛ. 49 АЛ. 8, ДОСЛОВНО. „При определянето на годишната данъчна
   * основа и на годишния данък по реда на предходните алинеи не се включват доходите от трудови
   * правоотношения по § 1, т. 26, буква „и“.“ Затова в образеца колона 6 на ред с код 102 стои „Х“.
   *
   * <p>⚠️ НО КОЛОНА 22 ГО БРОИ. Формулата ѝ казва само „сборът от сумите, посочени в колона 7“, без
   * ограничението „за кодове 101 и 103“, което колона 19 носи изрично. Разликата тук е ЦЕЛИЯТ
   * свидетел: 2100 в колона 18, 2100 в колона 19-умалителя, но 2400 в колона 22.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 49 АЛ. 8 · код 102 стои извън основата, но влиза в колона 22")
  void codeOneHundredAndTwoIsExcludedFromTheBaseButNotFromColumnTwentyTwo() {
    List<BgArt73Para6Record> rows =
        Arrays.asList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "24000.00", "3000.00", "0.00", "2100.00"),
            row(
                BgArt73Para6Aggregator.CODE_PERSONAL_LABOUR,
                "5000.00",
                "1000.00",
                "0.00",
                "300.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0,
        new BigDecimal("21000.00").compareTo(result.getBaseArt49()),
        "доходът по код 102 НЕ вдига годишната основа: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getAdvanceTaxForSettlement()),
        "умалителят на колона 19 брои само кодове 101 и 103: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getSettlement()), "колона 19: " + result);
    assertEquals(
        0,
        new BigDecimal("2400.00").compareTo(result.getTotalWithheld()),
        "колона 22 брои И данъка по код 102 — 2100 + 300: " + result);
  }

  /**
   * ⚠️ КОЛОНА 5 ВЛИЗА В ОСНОВАТА ТОЧНО КАКТО КОЛОНА 4. Чл. 25 ал. 1 изброява и двете: удържаните от
   * работодателя вноски за сметка на лицето И „внесените в чужбина задължителни осигурителни
   * вноски, които са за сметка на физическото лице“. Пропусната, колона 5 вдига основата и лицето
   * плаща данък върху осигуровки, които е платило.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · колона 5 (вноски в чужбина) намалява основата наравно с колона 4")
  void contributionsPaidAbroadReduceTheBaseToo() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(
                BgArt73Para6Aggregator.CODE_EMPLOYMENT,
                "24000.00",
                "1000.00",
                "2000.00",
                "2100.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0,
        new BigDecimal("21000.00").compareTo(result.getBaseArt49()),
        "24000 − 1000 − 2000: " + result);
  }

  /**
   * ⚠️ ЗАЛИЧАВАЩИЯТ РЕД НЕ НОСИ СУМИ. Указание т. 3: „При заличаване на данни се попълват само
   * точки 1, 2, 3, 4, 5 и 6 в Част ІІ.“ Тоест сумите му не са „нула“, а „не се попълва“ — и ако
   * някой ден такъв ред запази старите числа за следа, броени мълчаливо, те биха удвоили дохода на
   * лицето.
   */
  @Test
  @DisplayName("⚠️ КОД КОРЕКЦИЯ 8 · заличаващият ред не влиза в нито един сбор")
  void aDeletingRowIsNotCounted() {
    BgArt73Para6Record deleted =
        row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "9999.00", "0.00", "0.00", "999.00");
    deleted.setCorrectionCode(BgArt49Recalculation.CORRECTION_DELETE);

    List<BgArt73Para6Record> rows =
        Arrays.asList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "24000.00", "3000.00", "0.00", "2100.00"),
            deleted);

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0,
        new BigDecimal("21000.00").compareTo(result.getBaseArt49()),
        "заличаващият ред е добавил 9999 към основата: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getTotalWithheld()),
        "заличаващият ред е добавил 999 към колона 22: " + result);
  }

  /**
   * ⚠️ ОТРИЦАТЕЛНА ГОДИШНА ОСНОВА Е ВЕЛИЧИНА, КАКВАТО ЗАКОНЪТ НЕ ПОЗНАВА. Пропусната надолу, тя би
   * направила колона 18 отрицателна и колона 19 би поискала на лицето да МУ СЕ ВЪРНЕ данък, какъвто
   * то не е плащало.
   */
  @Test
  @DisplayName("⚠️ ПОД · осигуровки над дохода дават основа нула, а не отрицателно число")
  void theBaseNeverGoesBelowZero() {
    List<BgArt73Para6Record> rows =
        Collections.singletonList(
            row(BgArt73Para6Aggregator.CODE_EMPLOYMENT, "1000.00", "1500.00", "0.00", "0.00"));

    BgArt49Result result = BgArt49Recalculation.recalculate(rows, TEN_PERCENT);

    assertEquals(
        0, BigDecimal.ZERO.compareTo(result.getBaseArt49()), "колона 6 на ред 8.3: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getAnnualTax()), "колона 18: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getSettlement()), "колона 19: " + result);
  }

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  private static BgArt73Para6Record row(
      String code, String income, String insurance, String abroad, String advanceTax) {
    BgIncomeType type = new BgIncomeType();
    type.setCode(code);

    BgArt73Para6Record record = new BgArt73Para6Record();
    record.setCorrectionCode("0");
    record.setIncomeType(type);
    record.setIncome(new BigDecimal(income));
    record.setInsurance(new BigDecimal(insurance));
    record.setInsuranceAbroad(new BigDecimal(abroad));
    record.setAdvanceTax(new BigDecimal(advanceTax));
    return record;
  }
}
