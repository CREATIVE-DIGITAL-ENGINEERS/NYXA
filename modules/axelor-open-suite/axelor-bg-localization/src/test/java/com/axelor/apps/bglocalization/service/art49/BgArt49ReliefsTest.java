package com.axelor.apps.bglocalization.service.art49;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgArt73Para6Record;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.service.art73.BgArt73Para6Aggregator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОБЛЕКЧЕНИЯТА ПО ЧЛ. 49 АЛ. 3 — вратата на ал. 4, петте тавана и колона 17.
 *
 * <p>Основата навсякъде е 21 000: доход 24 000 минус осигуровки 3000 — същият свидетел, с който
 * тръгва {@code BgArt49RecalculationTest}, за да се вижда ЕДНО изменение наведнъж.
 */
class BgArt49ReliefsTest {

  private static final BigDecimal TEN_PERCENT = new BigDecimal("10.0000");
  private static final BigDecimal BASE = new BigDecimal("21000.00");

  /**
   * ⚠️ СВИДЕТЕЛЯТ НА ЗАДАНИЕТО: основата пада, данъкът пада, изравняването става ВЪЗСТАНОВЯВАНЕ, а
   * колона 19 се отрязва до цяла парична единица.
   *
   * <p>21 000 − 606,50 = 20 393,50 → данък 2039,35. Авансово удържани са 2100,00, тоест колона 19 е
   * −60,65 и се вписва като −60. ⚠️ При {@code HALF_UP} тя щеше да е −61 и работодателят щеше да
   * върне един лев повече, отколкото образецът иска.
   *
   * <p>⚠️ СУМАТА 606,50 Е НА СВИДЕТЕЛЯ, А НЕ НА ЗАКОНА. Годишният размер на облекчението за деца се
   * определя от § 3 от ПЗР на ЗДБРБ за съответната година (указание т. 7 към образеца), не от чл.
   * 22в ал. 1 — виж {@link BgArt49ReliefEntitlement}. Тестът мери МЕХАНИЗМА и затова подава числото
   * изрично.
   */
  @Test
  @DisplayName(
      "СВИДЕТЕЛ · облекчение за деца 606,50 → основа 20393,50, данък 2039,35, к.19 = −60 (не −61)")
  void aChildReliefLowersTheBaseAndTurnsTheSettlementIntoARefund() {
    BgArt49Reliefs requested = new BgArt49Reliefs();
    requested.documentsProvided = true;
    requested.children = new BigDecimal("606.50");

    BgArt49Result result =
        BgArt49Recalculation.recalculate(rows("2100.00"), TEN_PERCENT, requested);

    assertEquals(0, BASE.compareTo(result.getBaseArt49()), "колона 6 на ред 8.3: " + result);
    assertEquals(
        0,
        new BigDecimal("606.50").compareTo(result.getReliefTotal()),
        "сборът от колони 8 до 16: " + result);
    assertEquals(
        0,
        new BigDecimal("20393.50").compareTo(result.getTaxableBase()),
        "колона 17 = колона 6 − сборът: " + result);
    assertEquals(
        0,
        new BigDecimal("2039.35").compareTo(result.getAnnualTax()),
        "колона 18 = колона 17 х 10 на сто: " + result);
    assertEquals(
        0,
        new BigDecimal("-60").compareTo(result.getSettlement()),
        "колона 19: −60,65 се отрязва до −60, а не се закръгля до −61: " + result);
    assertEquals(
        0,
        new BigDecimal("60.00").compareTo(result.getToRefund()),
        "чл. 49 ал. 6 — възстановяване: " + result);
    assertEquals(
        0,
        new BigDecimal("2040.00").compareTo(result.getTotalWithheld()),
        "колона 22 = 2100 + 0 − 60: " + result);
  }

  /**
   * ⚠️ ЧЛ. 49 АЛ. 4 Е ВРАТА КЪМ АЛ. 3, А НЕ КЪМ ПРЕИЗЧИСЛЯВАНЕТО. Без документите нито едно
   * облекчение не се прилага — но колона 6 и колони 17–22 се смятат както винаги. Слети в едно
   * условие, лице без документи щеше да остане съвсем без годишен данък.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 49 АЛ. 4 · без документите нито едно облекчение не се прилага")
  void withoutTheDocumentsNoReliefIsApplied() {
    BgArt49Reliefs requested = new BgArt49Reliefs();
    requested.documentsProvided = false;
    requested.children = new BigDecimal("606.50");
    requested.disability = new BigDecimal("4049.43");

    BgArt49Result result =
        BgArt49Recalculation.recalculate(rows("2100.00"), TEN_PERCENT, requested);

    assertEquals(0, BigDecimal.ZERO.compareTo(result.getReliefTotal()), "колони 8–16: " + result);
    assertEquals(
        0,
        BASE.compareTo(result.getTaxableBase()),
        "колона 17 остава равна на колона 6 — но НЕ е празна: " + result);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(result.getAnnualTax()),
        "годишният данък пак се определя — ал. 1 не зависи от ал. 4: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getSettlement()), "колона 19: " + result);
  }

  /**
   * ⚠️ ДВА ОТДЕЛНИ ТАВАНА ОТ 10 НА СТО, А НЕ ЕДИН ОТ 10 ИЛИ ЕДИН ОТ 20. Чл. 19 ал. 1 изброява двете
   * групи поотделно, всяка със свое „в общ размер до 10 на сто“ — оттам и двете колони 9 и 10 на
   * образеца. При основа 21 000 всяка от тях се свива до 2100,00, тоест общо 4200,00.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 19 · колони 9 и 10 имат по СВОЙ таван от 10 на сто")
  void articleNineteenHasTwoSeparateTenPercentCaps() {
    BgArt49Reliefs requested = new BgArt49Reliefs();
    requested.documentsProvided = true;
    requested.voluntaryPension = new BigDecimal("5000.00");
    requested.voluntaryHealthLife = new BigDecimal("5000.00");

    BgArt49Reliefs applied = BgArt49Recalculation.applyCaps(requested, BASE);

    assertEquals(
        0, new BigDecimal("2100.00").compareTo(applied.voluntaryPension), "колона 9: " + applied);
    assertEquals(
        0,
        new BigDecimal("2100.00").compareTo(applied.voluntaryHealthLife),
        "колона 10: " + applied);
    assertEquals(0, new BigDecimal("4200.00").compareTo(applied.total()), "сборът им: " + applied);
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 22 · трите тавана поотделно са 5, 15 и 50 на сто")
  void donationsHaveTheirOwnThreeCaps() {
    BgArt49Reliefs requested = new BgArt49Reliefs();
    requested.documentsProvided = true;
    requested.donationsCode01 = new BigDecimal("9000.00");
    requested.donationsCode02 = new BigDecimal("9000.00");
    requested.donationsCode03 = new BigDecimal("9000.00");

    BgArt49Reliefs applied = BgArt49Recalculation.applyCaps(requested, BASE);

    assertEquals(
        0, new BigDecimal("1050.00").compareTo(applied.donationsCode01), "5 на сто: " + applied);
    assertEquals(
        0, new BigDecimal("3150.00").compareTo(applied.donationsCode02), "15 на сто: " + applied);
    assertEquals(
        0,
        new BigDecimal("9000.00").compareTo(applied.donationsCode03),
        "50 на сто е 10500: " + applied);
  }

  /**
   * ⚠️ ОБЩИЯТ ТАВАН НА ДАРЕНИЯТА Е 65 НА СТО, А НЕ 70. Сборът на трите поотделни е 5 + 15 + 50 =
   * 70, но указание т. 7 казва: „Сборът от сумите, посочени в колони 12, 13 и 14 на ред 8.3 не може
   * да превишава 65 на сто от сумата, посочена в колона 6 на същия ред.“ Изведен като сбор на
   * трите, общият таван би пуснал 5 на сто повече — при основа 21 000 това са 1050 лв. облекчение,
   * тоест 105 лв. неплатен данък на човек.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 22 · ЧЕТВЪРТИЯТ таван — общо 65 на сто, а не 5+15+50")
  void donationsHaveAFourthCommonCapOfSixtyFivePercent() {
    BgArt49Reliefs requested = new BgArt49Reliefs();
    requested.documentsProvided = true;
    requested.donationsCode01 = new BigDecimal("1050.00");
    requested.donationsCode02 = new BigDecimal("3150.00");
    requested.donationsCode03 = new BigDecimal("10500.00");

    BgArt49Reliefs applied = BgArt49Recalculation.applyCaps(requested, BASE);

    assertEquals(
        0,
        new BigDecimal("13650.00").compareTo(applied.donationsTotal()),
        "65 на сто от 21000 е 13650, а трите поотделно дават 14700: " + applied);
    assertEquals(
        0,
        new BigDecimal("9450.00").compareTo(applied.donationsCode03),
        "реже се по реда на колоните — 12 и 13 остават цели: " + applied);
  }

  /**
   * ⚠️ ПЕТИЯТ ТАВАН Е САМАТА ОСНОВА. Чл. 22в ал. 4 и чл. 22г ал. 4 го казват дословно за детските
   * („ползва се ДО РАЗМЕРА на сумата от годишните данъчни основи“), а за останалите го налага
   * колона 17: отрицателна данъчна основа не съществува.
   *
   * <p>⚠️ И превишението НЕ се губи за лицето: по ал. 4 то може да се ползва от другия родител с
   * годишна данъчна декларация по чл. 50. Точно затова се реже по реда на КОЛОНИТЕ — така
   * отрязаното пада върху 15 и 16, тоест там, където законът дава изход.
   */
  @Test
  @DisplayName("⚠️ ОСНОВАТА · сборът на облекченията не надхвърля колона 6, а колона 17 е нула")
  void theReliefsNeverExceedTheBase() {
    BgArt49Reliefs requested = new BgArt49Reliefs();
    requested.documentsProvided = true;
    requested.disability = new BigDecimal("20000.00");
    requested.children = new BigDecimal("5000.00");

    BgArt49Result result =
        BgArt49Recalculation.recalculate(rows("2100.00"), TEN_PERCENT, requested);
    BgArt49Reliefs applied = result.getReliefs();

    assertEquals(
        0,
        new BigDecimal("20000.00").compareTo(applied.disability),
        "колона 8 остава цяла: " + applied);
    assertEquals(
        0,
        new BigDecimal("1000.00").compareTo(applied.children),
        "колона 15 се свива до остатъка от основата: " + applied);
    assertEquals(0, BASE.compareTo(result.getReliefTotal()), "сборът е точно основата: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getTaxableBase()), "колона 17: " + result);
    assertEquals(0, BigDecimal.ZERO.compareTo(result.getAnnualTax()), "колона 18: " + result);
    assertEquals(
        0,
        new BigDecimal("-2100").compareTo(result.getSettlement()),
        "цялото авансово удържано се възстановява: " + result);
    assertEquals(
        0, new BigDecimal("2100.00").compareTo(result.getToRefund()), "колона 21: " + result);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(result.getTotalWithheld()),
        "колона 22 = 2100 − 2100: " + result);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · без облекчения колона 17 е равна на колона 6 на ред 8.3")
  void withoutReliefsColumnSeventeenEqualsColumnSix() {
    BgArt49Result result = BgArt49Recalculation.recalculate(rows("2100.00"), TEN_PERCENT);

    assertEquals(0, BigDecimal.ZERO.compareTo(result.getReliefTotal()), "" + result);
    assertEquals(0, BASE.compareTo(result.getTaxableBase()), "" + result);
    assertTrue(
        !result.getReliefs().documentsProvided,
        "непитано за облекчения НЕ значи „документите са предоставени“: " + result);
  }

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  /** Доход 24 000, осигуровки 3000 — основа 21 000. */
  private static List<BgArt73Para6Record> rows(String advanceTax) {
    BgIncomeType type = new BgIncomeType();
    type.setCode(BgArt73Para6Aggregator.CODE_EMPLOYMENT);

    BgArt73Para6Record record = new BgArt73Para6Record();
    record.setCorrectionCode("0");
    record.setIncomeType(type);
    record.setIncome(new BigDecimal("24000.00"));
    record.setInsurance(new BigDecimal("3000.00"));
    record.setInsuranceAbroad(BigDecimal.ZERO);
    record.setAdvanceTax(new BigDecimal(advanceTax));
    return Collections.singletonList(record);
  }
}
