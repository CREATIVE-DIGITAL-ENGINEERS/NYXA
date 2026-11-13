package com.axelor.apps.bglocalization.service.zddfl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВИДЕТЕЛИТЕ, ЗАХРАНЕНИ ОТ САМИЯ КАТАЛОГ — не от изписани в теста ставки.
 *
 * <p><b>Какво пита този тест и защо не е дубликат на {@link BgIncomeTaxResolverTest}.</b> Онзи пита
 * „вярна ли е формулата при 25 на сто“ и ставките му са изписани. Този пита другата половина: „носи
 * ли код 3073 наистина 25 на сто, шаблон A и удържане от платеца“ — и след това пуска РЕАЛНИЯ ред
 * през РЕАЛНИЯ решател. Слети в един тест, повреден seed щеше да смени едновременно очакването и
 * резултата и червено нямаше да се появи никога.
 *
 * <p>⚠️ Чете се ФАЙЛЪТ, не базата: така тестът е зелен на чиста машина, без App Install и без
 * Postgres. Дали редът е ДОСТАВЕН до базата е друг въпрос и той се пита от манифестните тестове.
 *
 * <p>⚠️ Минава през {@link BgSeed#quotedRows}, а не през голото цепене: наименованията в този
 * каталог носят точка и запетая вътре в кавички (напр. кодовете по чл. 38) и разцепени наивно,
 * дават над 13 колони, при което ставката се чете от съседна клетка.
 */
class BgIncomeTaxSeedWitnessTest {

  private static final String FILE = "bg_income_type.csv";
  private static final BigDecimal THOUSAND = new BigDecimal("1000.00");
  private static final BigDecimal ZERO = new BigDecimal("0.00");

  @Test
  @DisplayName("SEED · 3073 консултантска услуга: каталогът дава 25 на сто → 250 / 750 / 75")
  void consultingService() {
    BgIncomeTaxResult r = resolveFromSeed("3073", ZERO, false);

    assertEquals(new BigDecimal("250.00"), r.expenseAmount);
    assertEquals(new BigDecimal("750.00"), r.taxableBase);
    assertEquals(new BigDecimal("75.00"), r.taxAmount);
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, r.taxKind);
  }

  @Test
  @DisplayName("SEED · 401 наем на недвижимо имущество: 10 на сто → 100 / 900 / 90")
  void immovableRent() {
    BgIncomeTaxResult r = resolveFromSeed("401", ZERO, false);

    assertEquals(new BigDecimal("100.00"), r.expenseAmount);
    assertEquals(new BigDecimal("900.00"), r.taxableBase);
    assertEquals(new BigDecimal("90.00"), r.taxAmount);
    assertEquals(BgIncomeTaxResolver.TAX_ADVANCE, r.taxKind);
  }

  @Test
  @DisplayName("SEED · 814 дивидент: чл. 46, ал. 3 → окончателен 50, без разходи")
  void dividend() {
    BgIncomeTaxResult r = resolveFromSeed("814", ZERO, false);

    assertEquals(ZERO, r.expenseAmount);
    assertEquals(THOUSAND, r.taxableBase);
    assertEquals(new BigDecimal("50.00"), r.taxAmount);
    assertEquals(BgIncomeTaxResolver.TAX_FINAL, r.taxKind);
  }

  @Test
  @DisplayName("SEED · 8171 застраховка „Живот“ 15+ г.: чл. 46, ал. 2 → окончателен 70")
  void lifeInsurance() {
    BgIncomeTaxResult r = resolveFromSeed("8171", ZERO, false);

    assertEquals(new BigDecimal("70.00"), r.taxAmount);
    assertEquals(BgIncomeTaxResolver.TAX_FINAL, r.taxKind);
  }

  @Test
  @DisplayName("SEED · 817 и 8171 са по ЕДИН член, но каталогът им дава различна ставка — 100 и 70")
  void theTwoLifeInsuranceCodesDiffer() {
    // ⚠️ Двата реда съществуват отделно именно заради това. Ако някой ги слее „защото са по чл. 38,
    // ал. 8“, този тест пада — и точно затова е тук.
    assertEquals(new BigDecimal("100.00"), resolveFromSeed("817", ZERO, false).taxAmount);
    assertEquals(new BigDecimal("70.00"), resolveFromSeed("8171", ZERO, false).taxAmount);
  }

  @Test
  @DisplayName("SEED · 508 продажба на акции (чл. 33): данък 0, брутото се записва")
  void shareSale() {
    BgIncomeTaxResult r = resolveFromSeed("508", ZERO, false);

    assertTrue(r.isResolved());
    assertEquals(ZERO, r.taxAmount);
    assertEquals(ZERO, r.taxableBase);
    assertEquals(BgIncomeTaxResolver.TAX_NONE, r.taxKind);
    assertNotNull(r.note);
    assertTrue(r.note.contains("чл. 33"), r.note);
  }

  @Test
  @DisplayName("SEED · 907 рента от земеделска земя (чл. 13): нула данък, само ред")
  void agriculturalRent() {
    BgIncomeTaxResult r = resolveFromSeed("907", ZERO, false);

    assertTrue(r.isResolved());
    assertEquals(ZERO, r.taxAmount);
    assertEquals(BgIncomeTaxResolver.TAX_NONE, r.taxKind);
    assertNotNull(r.note);
    assertTrue(r.note.contains("чл. 13"), r.note);
  }

  @Test
  @DisplayName("SEED · СОЛ-получател на услуга по 3073: данък не се удържа, но редът се записва")
  void selfInsuredRecipientOfAService() {
    BgIncomeTaxResult r = resolveFromSeed("3073", ZERO, true);

    assertTrue(r.isResolved(), "редът СЕ ЗАПИСВА");
    assertEquals(new BigDecimal("750.00"), r.taxableBase, "основата остава изчислена за сведение");
    assertEquals(ZERO, r.taxAmount, "чл. 43, ал. 6 — лицето внася данъка само");
    assertEquals(BgIncomeTaxResolver.TAX_NONE, r.taxKind);
  }

  // ═══ И ЦЕЛИЯТ КАТАЛОГ, НЕ САМО ШЕСТТЕ СВИДЕТЕЛЯ ═════════════════════════════

  /**
   * ⚠️ Свидетелите показват, че формулата работи за шест реда. Този тест пита дали каталогът не
   * крие СЕДМИ, който би отказал — тоест дали някой код не е заведен с шаблон, който иска ставка, а
   * ставката липсва. Отказът е правилното поведение при празна клетка, но е дефект в seed-а, ако го
   * има днес: 90-те кода трябва да минават без нито един отказ.
   */
  @Test
  @DisplayName(
      "SEED · всеки код в каталога се изчислява без отказ (освен трудовите, които са другаде)")
  void everyCatalogCodeResolves() {
    List<String> header = BgSeed.quotedHeader(FILE);
    int checked = 0;
    StringBuilder failures = new StringBuilder();

    for (String[] row : BgSeed.quotedRows(FILE)) {
      String code = BgSeed.col(header, row, "code");
      String pattern = BgSeed.col(header, row, "taxPattern");
      if (BgIncomeTaxResolver.PATTERN_EMPLOYMENT.equals(pattern)) {
        continue; // чл. 73 ал. 6 се събира от фишовете — виж BgArt73Para6Aggregator
      }
      BgIncomeTaxResult r = BgIncomeTaxResolver.resolve(requestFrom(header, row, ZERO, false));
      checked++;
      if (!r.isResolved()) {
        failures
            .append("\n  код ")
            .append(code)
            .append(" (")
            .append(pattern)
            .append("): ")
            .append(r.refusal);
      }
    }

    assertTrue(checked > 80, "очаквах близо 90 кода, а прочетох " + checked);
    assertEquals("", failures.toString(), "кодове, които каталогът не може да изчисли:" + failures);
  }

  // ═══ СРЕДАТА ════════════════════════════════════════════════════════════════

  private static BgIncomeTaxResult resolveFromSeed(
      String code, BigDecimal insurance, boolean selfInsuredDeclared) {
    List<String> header = BgSeed.quotedHeader(FILE);
    String[] row = rowFor(header, code);
    return BgIncomeTaxResolver.resolve(requestFrom(header, row, insurance, selfInsuredDeclared));
  }

  private static BgIncomeTaxRequest requestFrom(
      List<String> header, String[] row, BigDecimal insurance, boolean selfInsuredDeclared) {
    return new BgIncomeTaxRequest(
        BgSeed.col(header, row, "taxPattern"),
        new BigDecimal(BgSeed.col(header, row, "expenseRate")),
        new BigDecimal(BgSeed.col(header, row, "finalTaxRate")),
        new BigDecimal(BgSeed.col(header, row, "advanceTaxRate")),
        Boolean.parseBoolean(BgSeed.col(header, row, "withheldByPayer")),
        THOUSAND,
        insurance,
        selfInsuredDeclared);
  }

  /**
   * Редът за кода — и то ЕДИНСТВЕНИЯТ.
   *
   * <p>⚠️ Уникалността в каталога е по (code, validFrom), не по code: един код може да има повече
   * от една редакция във времето. Днес образецът за 2025 г. дава по един ред на код и тестът
   * настоява на това — вторият ред би направил избора мълчалив, а мълчаливият избор при темпорална
   * номенклатура е точно начинът да се смята по грешната ставка.
   */
  private static String[] rowFor(List<String> header, String code) {
    Map<String, String[]> found = new HashMap<>();
    int seen = 0;
    for (String[] row : BgSeed.quotedRows(FILE)) {
      if (code.equals(BgSeed.col(header, row, "code"))) {
        found.put(code + "#" + seen++, row);
      }
    }
    assertEquals(1, seen, "код " + code + ": очаквах точно един ред в каталога, намерих " + seen);
    return found.get(code + "#0");
  }
}
