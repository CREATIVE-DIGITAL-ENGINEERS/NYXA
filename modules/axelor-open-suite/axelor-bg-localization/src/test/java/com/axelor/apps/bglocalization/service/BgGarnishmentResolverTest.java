package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.db.BgGarnishmentScale;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗЛАТНИТЕ ЧИСЛА НА ЧЛ. 446 ГПК — колко се удържа и колко ОСТАВА, при МРЗ 620,20 EUR.
 *
 * <p><b>⚠️ ВСЯКО ТВЪРДЕНИЕ НОСИ И „ОСТАВА“, НЕ САМО „УДЪРЖИМО“.</b> Удържимото е число, което човек
 * не може да провери с поглед; оставащото се проверява веднага — 620,20 е точно една МРЗ, 1240,40 е
 * точно две. Записано само откъм удържимото, сгрешен праг би минал за верен.
 *
 * <p><b>⚠️ СКАЛАТА ИДВА ОТ SEED ФАЙЛА, не от числа в теста.</b> Шестте реда се четат от {@code
 * bg_garnishment_scale.csv}, а МРЗ — от {@code bg_payroll_parameter.csv}. Тоест ако някой смени 1/3
 * на 1/4 в seed-а, тези числа падат. Тест със собствени фракции щеше да мери себе си.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 *
 * <p>Основание: ГПК чл. 446 ал. 1 (ред. ДВ бр. 86/2017), ал. 2 и ал. 3 (нова, ДВ бр. 55/2025);
 * сверено срещу официалния ГПК (justice.government.bg) на 22.08.2026 г. Дословният текст на трите
 * алинеи стои в javadoc-а на {@link BgGarnishmentResolver} и не се преписва тук.
 */
class BgGarnishmentResolverTest {

  private static final boolean NO_CHILDREN = false;
  private static final boolean WITH_CHILDREN = true;

  // ═══ СКАЛАТА И МРЗ — ОТ SEED-А ════════════════════════════════════════════

  private static BigDecimal decimalOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : new BigDecimal(value.trim());
  }

  private static Integer integerOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : Integer.valueOf(value.trim());
  }

  /** Шестте реда на скалата — ОТ ФАЙЛА, с истинските си граници и дялове. */
  private static List<BgGarnishmentScale> scale() {
    List<String> header = BgSeed.quotedHeader("bg_garnishment_scale.csv");
    List<BgGarnishmentScale> out = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows("bg_garnishment_scale.csv")) {
      BgGarnishmentScale band = new BgGarnishmentScale();
      band.setBgThreshold(Integer.valueOf(BgSeed.col(header, row, "bgThreshold").trim()));
      band.setBgWithChildren(
          "true".equalsIgnoreCase(BgSeed.col(header, row, "bgWithChildren").trim()));
      band.setBgFromMultiple(decimalOrNull(BgSeed.col(header, row, "bgFromMultiple")));
      band.setBgToMultiple(decimalOrNull(BgSeed.col(header, row, "bgToMultiple")));
      band.setBgNumerator(integerOrNull(BgSeed.col(header, row, "bgNumerator")));
      band.setBgDenominator(integerOrNull(BgSeed.col(header, row, "bgDenominator")));
      band.setBgKeepMultiple(decimalOrNull(BgSeed.col(header, row, "bgKeepMultiple")));
      out.add(band);
    }
    return out;
  }

  /** МРЗ за 2026 г. — ОТ ФАЙЛА. ПМС № 243 от 13.11.2025 г., 620,20 EUR. */
  private static BigDecimal minWage() {
    List<String> header = BgSeed.quotedHeader("bg_payroll_parameter.csv");
    for (String[] row : BgSeed.quotedRows("bg_payroll_parameter.csv")) {
      if ("MIN_WAGE".equals(BgSeed.col(header, row, "code"))) {
        return new BigDecimal(BgSeed.col(header, row, "paramValue").trim());
      }
    }
    fail("параметър MIN_WAGE липсва в bg_payroll_parameter.csv");
    return null;
  }

  // ═══ ПОМОЩНИЦИТЕ НА ТВЪРДЕНИЯТА ═══════════════════════════════════════════

  /** Удържимо И оставащо в едно твърдение — второто прави първото проверимо с поглед. */
  private static void assertSeizure(
      String net, boolean withChildren, String expectedSeizable, String expectedRemaining) {
    BigDecimal n = new BigDecimal(net);
    BigDecimal m = minWage();
    List<BgGarnishmentScale> rows = scale();

    BigDecimal seizable = BgGarnishmentResolver.maxSeizable(n, m, withChildren, rows);
    BigDecimal remaining = BgGarnishmentResolver.remainingAfterSeizure(n, m, withChildren, rows);

    assertNotNull(
        seizable, "нето " + net + ", с деца " + withChildren + " → ОТКАЗ, а не се очакваше");
    assertEquals(
        0,
        new BigDecimal(expectedSeizable).compareTo(seizable),
        "УДЪРЖИМО при нето " + net + ", с деца " + withChildren + ". Намерено: " + seizable);
    assertEquals(
        0,
        new BigDecimal(expectedRemaining).compareTo(remaining),
        "ОСТАВА при нето " + net + ", с деца " + withChildren + ". Намерено: " + remaining);
  }

  // ═══ 1 · ПОД МРЗ — НИЩО НЕ СЕ УДЪРЖА ══════════════════════════════════════

  @Test
  @DisplayName("нето 600,00 (под МРЗ) → удържимо 0,00, остава 600,00")
  void belowTheMinimumWageNothingIsSeizable() {
    assertSeizure("600.00", NO_CHILDREN, "0.00", "600.00");
  }

  /**
   * ⚠️ ГРАНИЦАТА Е ВКЛЮЧВАЩА ОТДОЛУ: нето точно колкото МРЗ е несеквестируемо ЦЯЛО. Праг 1 започва
   * СЛЕД една МРЗ, не от нея.
   */
  @Test
  @DisplayName("⚠️ нето 620,20 (точно МРЗ) → удържимо 0,00, остава 620,20")
  void exactlyTheMinimumWageIsFullyProtected() {
    assertSeizure("620.20", NO_CHILDREN, "0.00", "620.20");
  }

  // ═══ 2 · ПРАГ 1 — ТУК ПОДЪТ ПО АЛ. 3 ОБВЪРЗВА ═════════════════════════════

  /**
   * ⚠️ СВИДЕТЕЛЯТ НА АЛ. 3. Една трета от 682,22 е 227,41 и би оставила 454,81 — ПОД МРЗ. Подът
   * сваля удържимото до 62,02 и лицето остава точно с една МРЗ.
   */
  @Test
  @DisplayName(
      "⚠️ нето 682,22 (1,1× МРЗ) без деца → удържимо 62,02 (подът обвързва), остава 620,20")
  void justAboveTheMinimumWageTheFloorBinds() {
    assertSeizure("682.22", NO_CHILDREN, "62.02", "620.20");
  }

  @Test
  @DisplayName("нето 930,30 (1,5× МРЗ) без деца → удържимо 310,10 (1/3), остава 620,20")
  void oneAndAHalfMinimumWagesWithoutChildren() {
    assertSeizure("930.30", NO_CHILDREN, "310.10", "620.20");
  }

  /** ⚠️ 930,30 / 4 = 232,575 — закръглянето по § 5.3 (половина НАГОРЕ) дава 232,58, не 232,57. */
  @Test
  @DisplayName("⚠️ нето 930,30 с деца → удържимо 232,58 (1/4, 232,575 → нагоре), остава 697,72")
  void oneAndAHalfMinimumWagesWithChildren() {
    assertSeizure("930.30", WITH_CHILDREN, "232.58", "697.72");
  }

  // ═══ 3 · ПРАГ 2 ═══════════════════════════════════════════════════════════

  @Test
  @DisplayName("нето 1500,00 (2,4× МРЗ) без деца → удържимо 750,00 (1/2), остава 750,00")
  void secondBandWithoutChildren() {
    assertSeizure("1500.00", NO_CHILDREN, "750.00", "750.00");
  }

  @Test
  @DisplayName("нето 1500,00 с деца → удържимо 500,00 (1/3), остава 1000,00")
  void secondBandWithChildren() {
    assertSeizure("1500.00", WITH_CHILDREN, "500.00", "1000.00");
  }

  // ═══ 4 · ПРАГ 3 — ОСТАТЪК, НЕ ДЯЛ ═════════════════════════════════════════

  /** ⚠️ Оставащото Е точно 2× МРЗ — числото, което прави твърдението проверимо с поглед. */
  @Test
  @DisplayName("⚠️ нето 3000,00 (>4× МРЗ) без деца → удържимо 1759,60, остава 1240,40 = 2× МРЗ")
  void aboveFourMinimumWagesWithoutChildren() {
    assertSeizure("3000.00", NO_CHILDREN, "1759.60", "1240.40");
  }

  /** ⚠️ И тук оставащото Е точно 2,5× МРЗ. */
  @Test
  @DisplayName("⚠️ нето 3000,00 с деца → удържимо 1449,50, остава 1550,50 = 2,5× МРЗ")
  void aboveFourMinimumWagesWithChildren() {
    assertSeizure("3000.00", WITH_CHILDREN, "1449.50", "1550.50");
  }

  // ═══ 5 · FAIL-FIRST: ПОДЪТ НЕ Е УКРАСА ════════════════════════════════════

  /**
   * ⚠️ КОТВАТА НА АЛ. 3. Вариантът „удържимо = дял × нето“ БЕЗ пода дава при 682,22 сумата 227,41
   * вместо 62,02 — тоест 165,39 повече, отнети от длъжник, който остава под МРЗ. Написан пръв,
   * {@link #justAboveTheMinimumWageTheFloorBinds} падаше точно с 227,41.
   *
   * <p>Съвпаднат ли някога двете числа, {@code min}-ът е спрял да пази каквото и да е.
   */
  @Test
  @DisplayName("⚠️ БЕЗ пода същият случай дава 227,41 вместо 62,02 — 165,39 разлика")
  void withoutTheFloorTheSeizureWouldBeOverstated() {
    BigDecimal net = new BigDecimal("682.22");
    BigDecimal m = minWage();

    BigDecimal withFloor = BgGarnishmentResolver.maxSeizable(net, m, NO_CHILDREN, scale());
    // Голият дял, сметнат както би го сметнал вариант без min(...): 1/3 × нето.
    BigDecimal bareShare =
        net.divide(new BigDecimal("3"), 10, java.math.RoundingMode.HALF_UP)
            .setScale(2, java.math.RoundingMode.HALF_UP);

    assertEquals(0, new BigDecimal("62.02").compareTo(withFloor), "с пода");
    assertEquals(0, new BigDecimal("227.41").compareTo(bareShare), "без пода");
    assertEquals(
        0,
        new BigDecimal("165.39").compareTo(bareShare.subtract(withFloor)),
        "⚠️ ТОЛКОВА би се удържало в повече всеки месец, ако min(...) отпадне — и лицето щеше да"
            + " остане с 454,81 вместо с една МРЗ");
  }

  // ═══ 6 · ГРАНИЦИТЕ МЕЖДУ ПРАГОВЕТЕ ════════════════════════════════════════

  /**
   * ⚠️ ГОРНАТА ГРАНИЦА Е ВКЛЮЧВАЩА, ДОЛНАТА — ИЗКЛЮЧВАЩА. Проверява се самият ИЗБОР на праг, а не
   * сумата: при доход точно на границата двата съседни прага дават близки числа и сгрешената
   * граница не се вижда от сумата.
   */
  @Test
  @DisplayName("⚠️ границите: 2× МРЗ още е праг 1, стотинка над нея е праг 2; 4× МРЗ е праг 2")
  void theBandBoundariesAreClosedAboveAndOpenBelow() {
    BigDecimal m = minWage();
    List<BgGarnishmentScale> rows = scale();

    assertEquals(
        1,
        BgGarnishmentResolver.bandFor(new BigDecimal("1240.40"), m, NO_CHILDREN, rows)
            .getBgThreshold()
            .intValue(),
        "2× МРЗ = 1240,40 е ВКЛЮЧЕНО в праг 1");
    assertEquals(
        2,
        BgGarnishmentResolver.bandFor(new BigDecimal("1240.41"), m, NO_CHILDREN, rows)
            .getBgThreshold()
            .intValue(),
        "стотинка над 2× МРЗ вече е праг 2");
    assertEquals(
        2,
        BgGarnishmentResolver.bandFor(new BigDecimal("2480.80"), m, NO_CHILDREN, rows)
            .getBgThreshold()
            .intValue(),
        "4× МРЗ = 2480,80 е ВКЛЮЧЕНО в праг 2");
    assertEquals(
        3,
        BgGarnishmentResolver.bandFor(new BigDecimal("2480.81"), m, NO_CHILDREN, rows)
            .getBgThreshold()
            .intValue(),
        "стотинка над 4× МРЗ вече е праг 3");
  }

  /**
   * ⚠️ Редовете за другата редакция НЕ трябва да се хващат — инак признакът „с деца“ е без
   * значение.
   */
  @Test
  @DisplayName("⚠️ изборът на праг зачита „с деца“, а не само границите")
  void theBandIsPickedForTheRightVariant() {
    BigDecimal m = minWage();
    List<BgGarnishmentScale> rows = scale();
    BigDecimal net = new BigDecimal("1500.00");

    assertEquals(
        Boolean.FALSE,
        BgGarnishmentResolver.bandFor(net, m, NO_CHILDREN, rows).getBgWithChildren());
    assertEquals(
        Boolean.TRUE,
        BgGarnishmentResolver.bandFor(net, m, WITH_CHILDREN, rows).getBgWithChildren());
  }

  // ═══ 7 · ОТКАЗИТЕ ═════════════════════════════════════════════════════════

  /**
   * ⚠️ ЛИПСВАЩА СКАЛА Е ОТКАЗ, НЕ НУЛА. Нула значи „нищо не се удържа“ и би оставила запор без
   * удръжка на фиш, който изглежда сметнат — взискателят губи, а нищо не се обажда.
   */
  @Test
  @DisplayName("⚠️ празна скала → null (ОТКАЗ), НЕ 0,00")
  void anEmptyScaleIsRefusedNotZero() {
    assertNull(
        BgGarnishmentResolver.maxSeizable(
            new BigDecimal("1500.00"), minWage(), NO_CHILDREN, Collections.emptyList()));
    assertNull(
        BgGarnishmentResolver.maxSeizable(new BigDecimal("1500.00"), minWage(), NO_CHILDREN, null));
  }

  /** ⚠️ Без МРЗ нито един праг не може да се намери — границите ѝ са кратни. */
  @Test
  @DisplayName("⚠️ липсваща МРЗ за периода → null (ОТКАЗ)")
  void aMissingMinimumWageIsRefused() {
    assertNull(
        BgGarnishmentResolver.maxSeizable(new BigDecimal("1500.00"), null, NO_CHILDREN, scale()));
    assertNull(
        BgGarnishmentResolver.maxSeizable(
            new BigDecimal("1500.00"), BigDecimal.ZERO, NO_CHILDREN, scale()));
  }

  /** Нето нула или отрицателно — няма от какво да се удържа; законна нула, не отказ. */
  @Test
  @DisplayName("нето 0,00 → удържимо 0,00, без отказ")
  void aZeroNetSeizesNothing() {
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(
            BgGarnishmentResolver.maxSeizable(BigDecimal.ZERO, minWage(), NO_CHILDREN, scale())),
        "нула нето е законна нула, не липсваща скала");
  }
}
