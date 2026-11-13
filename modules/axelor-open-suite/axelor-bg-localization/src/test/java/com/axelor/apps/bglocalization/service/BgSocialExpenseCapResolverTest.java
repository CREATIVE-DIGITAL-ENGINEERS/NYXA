package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgSocialExpenseCap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВИДЕТЕЛИТЕ НА КАПАЧКИТЕ — числата, срещу които {@link BgSocialExpenseCapResolver} се сверява.
 *
 * <p>Без база, без Guice, без Hibernate: капачката се сглобява в Java, точно както {@code
 * BgGarnishmentResolverTest} сглобява скалата по чл. 446. Оттам следва, че този тест тече на всяка
 * машина и НЕ се пропуска — базовата половина ({@code BgSocialExpenseDbTest}) пита другото:
 * доставени ли са редовете.
 *
 * <p><b>⚠️ ЗАЩО ЧИСЛАТА СА ЗАКОВАНИ, А НЕ ПРЕСМЕТНАТИ В ТЕСТА.</b> Тест, който смята очакваното по
 * същата формула, минава и при сгрешена формула. Затова тук стоят самите суми — 102,26 и 47,74;
 * 10,23 / 10,23 / 10,22 — и всяка от тях е проверима на ръка.
 */
class BgSocialExpenseCapResolverTest {

  /** 60 лв. ÷ 1,95583 → 30,68 (ЗКПО чл. 208 · ЗДДФЛ чл. 24 ал. 2 т. 12). */
  private static final BigDecimal CAP_60 = new BigDecimal("30.68");

  /** 200 лв. ÷ 1,95583 → 102,26 (ЗКПО чл. 209а ал. 1 · чл. 214 ал. 2). */
  private static final BigDecimal CAP_200 = new BigDecimal("102.26");

  /** 2400 лв. ÷ 1,95583 → 1227,10 (ЗДДФЛ чл. 24 ал. 2 т. 11). */
  private static final BigDecimal CAP_2400 = new BigDecimal("1227.10");

  private static BgSocialExpenseCap monthly(
      String code, BigDecimal personal, BigDecimal company, boolean requiresDebtsClear) {
    BgSocialExpenseCap cap = new BgSocialExpenseCap();
    cap.setCode(code);
    cap.setName(code);
    cap.setBgPersonalCapAmount(personal);
    cap.setBgCompanyTaxApplies(true);
    cap.setBgCompanyCapAmount(company);
    cap.setBgPeriodicity(BgSocialExpenseCapResolver.MONTHLY);
    cap.setBgWholeAmountOnConditionFail(true);
    cap.setBgRequiresNoPublicDebts(requiresDebtsClear);
    return cap;
  }

  /** Ваучерите — единствената капачка с ТРИТЕ условия по чл. 209а ал. 1. */
  private static BgSocialExpenseCap vouchers() {
    BgSocialExpenseCap cap = monthly("FOOD_VOUCHERS", CAP_200, CAP_200, true);
    cap.setBgRequiresSalaryNotBelowAverage(true);
    cap.setBgRequiresLicensedOperator(true);
    return cap;
  }

  /**
   * Еднократните помощи: ГОДИШНА капачка и БЕЗ фирмена ос.
   *
   * <p>⚠️ {@code bgCompanyCapAmount} НЕ се задава нарочно — генерираният getter за decimal връща
   * ZERO и точно това е състоянието на реда в базата, където клетката е празна. Признакът, който
   * решава, е {@code bgCompanyTaxApplies = false}.
   */
  private static BgSocialExpenseCap oneOffAid() {
    BgSocialExpenseCap cap = new BgSocialExpenseCap();
    cap.setCode("ONE_OFF_AID");
    cap.setName("ONE_OFF_AID");
    cap.setBgPersonalCapAmount(CAP_2400);
    cap.setBgCompanyTaxApplies(false);
    cap.setBgPeriodicity(BgSocialExpenseCapResolver.ANNUAL);
    return cap;
  }

  private static List<BgSocialExpenseCapResolver.Item> items(String... codeAndAmount) {
    List<BgSocialExpenseCapResolver.Item> out = new ArrayList<>();
    for (int i = 0; i < codeAndAmount.length; i += 2) {
      out.add(
          new BgSocialExpenseCapResolver.Item(
              codeAndAmount[i], new BigDecimal(codeAndAmount[i + 1])));
    }
    return out;
  }

  // ═══ ВАУЧЕРИ ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("ваучер 150,00 при изпълнени условия → 102,26 необлагаем + 47,74 превишение")
  void voucherWithinAndAbove() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_vaucheri_v_ramkite", "150.00"),
            vouchers(),
            BgSocialExpenseConditions.allMet(),
            BigDecimal.ZERO);

    assertNull(out.refusal, "отказ няма — капачката и условията са налице");
    assertNull(out.failedCondition, "и трите условия са изпълнени");
    assertEquals(new BigDecimal("102.26"), out.exempt, "необлагаемата част е самата капачка");
    assertEquals(new BigDecimal("47.74"), out.excess, "превишението");
    assertEquals(
        new BigDecimal("47.74"),
        out.companyTaxBase,
        "ЗКПО чл. 214 ал. 2 — месечната основа Е превишението, когато условията са изпълнени");
    assertEquals(1, out.shares.size());
    assertEquals(new BigDecimal("102.26"), out.shares.get(0).exempt);
    assertEquals(new BigDecimal("47.74"), out.shares.get(0).excess);
  }

  @Test
  @DisplayName("⚠️ същият ваучер при ОТМЕТНАТИ публични задължения → ЦЕЛИТЕ 150,00 в основата")
  void voucherWithPublicDebtsTaxesTheWholeAmount() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_vaucheri_v_ramkite", "150.00"),
            vouchers(),
            new BgSocialExpenseConditions(true, false, false),
            BigDecimal.ZERO);

    assertNull(out.refusal);
    assertTrue(out.hasFailedCondition(), "условието по чл. 209а ал. 1 т. 2 е паднало");
    assertTrue(
        out.failedCondition.contains("чл. 208"),
        "отказът НАЗОВАВА кое условие е паднало: " + out.failedCondition);
    assertEquals(BigDecimal.ZERO.setScale(2), out.exempt, "облекчението е ЗАЛИЧЕНО, не намалено");
    assertEquals(new BigDecimal("150.00"), out.excess);
    assertEquals(
        new BigDecimal("150.00"),
        out.companyTaxBase,
        "ЗКПО чл. 214 ал. 3 — ЦЕЛИЯТ размер за календарния месец, не превишението. Разликата е"
            + " между 4,77 и 15,00 данък, и нищо на фиша не изглежда сгрешено");
  }

  @Test
  @DisplayName("⚠️ трите условия са РАЗЛИЧНИ — и трите падания се назовават поотделно")
  void eachConditionIsNamedSeparately() {
    BgSocialExpenseOutcome salary =
        BgSocialExpenseCapResolver.apply(
            items("v", "150.00"),
            vouchers(),
            new BgSocialExpenseConditions(false, true, false),
            BigDecimal.ZERO);
    assertTrue(salary.failedCondition.contains("209а ал. 1 т. 1"), salary.failedCondition);
    assertTrue(salary.failedCondition.contains("ПЕР-ЛИЦЕ"), salary.failedCondition);

    BgSocialExpenseOutcome operator =
        BgSocialExpenseCapResolver.apply(
            items("v", "150.00"),
            vouchers(),
            new BgSocialExpenseConditions(false, false, true),
            BigDecimal.ZERO);
    assertTrue(operator.failedCondition.contains("209а ал. 1 т. 3"), operator.failedCondition);

    // ⚠️ И ВСИЧКИТЕ ТРИ НАВЕДНЪЖ СЕ НАЗОВАВАТ ЗАЕДНО. Поправянето на едното не вдига облекчението,
    // ако другите две са паднали — съобщение с едно име щеше да прати счетоводителя два пъти.
    BgSocialExpenseOutcome all =
        BgSocialExpenseCapResolver.apply(
            items("v", "150.00"),
            vouchers(),
            new BgSocialExpenseConditions(true, true, true),
            BigDecimal.ZERO);
    assertTrue(all.failedCondition.contains("чл. 208"), all.failedCondition);
    assertTrue(all.failedCondition.contains("209а ал. 1 т. 1"), all.failedCondition);
    assertTrue(all.failedCondition.contains("209а ал. 1 т. 3"), all.failedCondition);
  }

  @Test
  @DisplayName("⚠️ лицензът на оператора НЕ чупи 60-те — чл. 208 не го изисква")
  void operatorLicenceDoesNotBreakTheSixtyLevaCap() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_ddo_v_ramkite", "20.00"),
            monthly("DDO_HEALTH_LIFE", CAP_60, CAP_60, true),
            new BgSocialExpenseConditions(false, false, true),
            BigDecimal.ZERO);

    assertNull(
        out.failedCondition,
        "чл. 208 изисква САМО липса на публични задължения — операторът е условие на чл. 209а");
    assertEquals(new BigDecimal("20.00"), out.exempt, "цялата вноска е под капачката");
    assertEquals(BigDecimal.ZERO.setScale(2), out.companyTaxBase);
  }

  // ═══ ОБЩАТА КАПАЧКА НА ТРИТЕ ПОЗИЦИИ ═════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ ДЗО 20 + здравно 20 + Живот 20 = 60 при капачка 30,68 → превишение 29,32, "
          + "пропорционално и БЕЗ изгубена стотинка")
  void sharedCapSplitsProportionallyWithoutLosingACent() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items(
                "pl_soc_ddo_v_ramkite", "20.00",
                "pl_soc_dzo_v_ramkite", "20.00",
                "pl_soc_zhivot_v_ramkite", "20.00"),
            monthly("DDO_HEALTH_LIFE", CAP_60, CAP_60, true),
            BgSocialExpenseConditions.allMet(),
            BigDecimal.ZERO);

    assertNull(out.refusal);
    assertEquals(
        new BigDecimal("30.68"),
        out.exempt,
        "капачката е ОБЩА за трите — ЗКПО чл. 213 ал. 2 дава превишението на ТЕЗИ разходи, не на"
            + " всеки поотделно. По 30,68 на позиция би било тройно облекчение");
    assertEquals(new BigDecimal("29.32"), out.excess);
    assertEquals(new BigDecimal("29.32"), out.companyTaxBase);

    // ⚠️ ТОЧНИЯТ ДЯЛ Е 10,2266… И НЕ СЕ ЗАКРЪГЛЯ НАГОРЕ НА ТРИТЕ. По 10,23 сборът е 30,69 — над
    // капачката. По 10,22 е 30,66 — две стотинки под нея. Затова: отсичане надолу плюс раздаване на
    // липсващите стотинки по най-голям остатък, при равенство по реда на подаване.
    assertEquals(
        Arrays.asList(new BigDecimal("10.23"), new BigDecimal("10.23"), new BigDecimal("10.22")),
        exempts(out),
        "освободеното по позиция, със сбор ТОЧНО 30,68");
    assertEquals(
        Arrays.asList(new BigDecimal("9.77"), new BigDecimal("9.77"), new BigDecimal("9.78")),
        excesses(out),
        "⚠️ „по 9,77 на перо“ е с ЕДНА СТОТИНКА по-малко от истината: 3 × 9,77 = 29,31, а"
            + " превишението е 29,32. Стотинката се ВИЖДА на третата позиция, вместо да се загуби");

    BigDecimal sumExempt = BigDecimal.ZERO;
    BigDecimal sumExcess = BigDecimal.ZERO;
    for (BgSocialExpenseOutcome.Share s : out.shares) {
      sumExempt = sumExempt.add(s.exempt);
      sumExcess = sumExcess.add(s.excess);
      assertEquals(
          s.amount,
          s.exempt.add(s.excess),
          "сборът на двете пера на една позиция е точно начисленото — иначе на фиша изчезва стотинка");
    }
    assertEquals(out.exempt, sumExempt, "сборът по позиции е ТОЧНО общото освободено");
    assertEquals(out.excess, sumExcess, "сборът по позиции е ТОЧНО общото превишение");
  }

  @Test
  @DisplayName("пропорцията НЕ е равно деление: 10,00 и 50,00 носят превишение 1:5")
  void splitFollowsShareNotHeadcount() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("малката", "10.00", "голямата", "50.00"),
            monthly("DDO_HEALTH_LIFE", CAP_60, CAP_60, true),
            BgSocialExpenseConditions.allMet(),
            BigDecimal.ZERO);

    // 10/60 × 30,68 = 5,1133… → 5,11 · 50/60 × 30,68 = 25,5666… → 25,57 (сбор точно 30,68)
    assertEquals(Arrays.asList(new BigDecimal("5.11"), new BigDecimal("25.57")), exempts(out));
    assertEquals(Arrays.asList(new BigDecimal("4.89"), new BigDecimal("24.43")), excesses(out));
    assertEquals(new BigDecimal("29.32"), out.excess, "общото превишение НЕ зависи от разбивката");
  }

  @Test
  @DisplayName(
      "⚠️ НАИМЕНУВАН ШЕВ: EXCESS_SPLIT_FOLLOWS_SHARE е ВДИГНАТ и това е решение, не случайност")
  void theSplitRuleIsANamedDecision() {
    assertTrue(
        BgSocialExpenseCapResolver.EXCESS_SPLIT_FOLLOWS_SHARE,
        "⚠️ Законът НЕ казва как се дели превишението между позициите под обща капачка: ЗКПО чл. 213"
            + " ал. 2 дава превишението върху СБОРА и с това приключва. Разбивката съществува само"
            + " защото фишът печата отделен ред за всяка позиция. Пропорцията е ИЗБОР; изчерпването"
            + " по ред дава същите сборове и различни числа по ред, тоест видът на фиша щеше да"
            + " зависи от подредбата на перата. Свалена, константата вали apply() с назована"
            + " причина, вместо да смята по неутвърдено правило.");
  }

  // ═══ ГОДИШНАТА КАПАЧКА ═══════════════════════════════════════════════════

  @Test
  @DisplayName("помощ при раждане 1500,00 → 1227,10 необлагаем + 272,90, и ЗКПО основа НУЛА")
  void oneOffAidHasNoCompanyAxis() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_pomosht_razhdane_v_ramkite", "1500.00"),
            oneOffAid(),
            BgSocialExpenseConditions.allMet(),
            BigDecimal.ZERO);

    assertNull(out.refusal);
    assertEquals(new BigDecimal("1227.10"), out.exempt);
    assertEquals(new BigDecimal("272.90"), out.excess);
    assertEquals(
        BigDecimal.ZERO.setScale(2),
        out.companyTaxBase,
        "⚠️ Помощта е в ПАРИ, тоест по § 1 т. 34 ДР ЗКПО не е социален разход в натура и не е обект"
            + " по чл. 204 ал. 1 т. 2. Важи чл. 205 — облага се при ЛИЦЕТО. Изведено от празната"
            + " фирмена капачка (която в базата е 0,00, не NULL), тук щеше да излезе 1500,00");
  }

  @Test
  @DisplayName(
      "⚠️ годишната капачка се ИЗЧЕРПВА — второто събитие ползва остатъка, не нова капачка")
  void theAnnualCapIsConsumed() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_pomosht_brak_v_ramkite", "500.00"),
            oneOffAid(),
            BgSocialExpenseConditions.allMet(),
            new BigDecimal("1000.00"));

    assertEquals(
        new BigDecimal("227.10"), out.exempt, "1227,10 − 1000,00 вече използвани = 227,10 остатък");
    assertEquals(new BigDecimal("272.90"), out.excess);
  }

  @Test
  @DisplayName("⚠️ изчерпана капачка → нула освободено, а НЕ отказ")
  void anExhaustedCapExemptsNothing() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_pomosht_smart_v_ramkite", "300.00"),
            oneOffAid(),
            BgSocialExpenseConditions.allMet(),
            new BigDecimal("1300.00"));

    assertNull(out.refusal, "изчерпана капачка е ОТГОВОР, не липсващ вход");
    assertEquals(BigDecimal.ZERO.setScale(2), out.exempt);
    assertEquals(new BigDecimal("300.00"), out.excess);
  }

  @Test
  @DisplayName("⚠️ ГОДИШНА капачка БЕЗ подадено използвано → ОТКАЗ, не нула")
  void anAnnualCapWithoutUsageIsARefusal() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_pomosht_razhdane_v_ramkite", "1500.00"),
            oneOffAid(),
            BgSocialExpenseConditions.allMet(),
            null);

    assertTrue(out.hasRefusal(), "прочетено като нула, капачката се подновява всеки месец");
    assertTrue(out.refusal.contains("ГОДИШНА"), out.refusal);
    assertNull(out.exempt, "при отказ числата НЕ се попълват — иначе изглеждат сметнати");
  }

  @Test
  @DisplayName("месечна капачка БЕЗ подадено използвано минава — периодът Е месецът")
  void aMonthlyCapReadsNullUsageAsZero() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_vaucheri_v_ramkite", "50.00"),
            vouchers(),
            BgSocialExpenseConditions.allMet(),
            null);

    assertNull(out.refusal);
    assertEquals(new BigDecimal("50.00"), out.exempt);
  }

  // ═══ ОТКАЗИТЕ ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ незаредена капачка е ОТКАЗ, не нула")
  void aMissingCapIsARefusal() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("v", "150.00"), null, BgSocialExpenseConditions.allMet(), BigDecimal.ZERO);

    assertTrue(out.hasRefusal());
    assertTrue(
        out.refusal.contains("App Install"), "отказът казва КАКВО да се направи: " + out.refusal);
    assertNull(out.companyTaxBase);
  }

  @Test
  @DisplayName("⚠️ ред с ПРАЗНА лична капачка е ОТКАЗ — празно и нула са неразличими в базата")
  void anEmptyPersonalCapIsARefusal() {
    BgSocialExpenseCap cap = new BgSocialExpenseCap();
    cap.setCode("НЕДОВЪРШЕН");
    cap.setBgPeriodicity(BgSocialExpenseCapResolver.MONTHLY);

    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("v", "10.00"), cap, BgSocialExpenseConditions.allMet(), BigDecimal.ZERO);

    assertTrue(out.hasRefusal());
    assertTrue(out.refusal.contains("НЕДОВЪРШЕН"), out.refusal);
  }

  @Test
  @DisplayName("⚠️ обявено условие БЕЗ обявено последствие е ОТКАЗ, не мълчалива капачка")
  void aConditionWithoutAConsequenceIsARefusal() {
    BgSocialExpenseCap cap = monthly("ПОЛОВИНЧАТ", CAP_60, CAP_60, true);
    cap.setBgWholeAmountOnConditionFail(false);

    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("v", "100.00"),
            cap,
            new BgSocialExpenseConditions(true, false, false),
            BigDecimal.ZERO);

    assertTrue(out.hasRefusal(), "чл. 213 ал. 3 и чл. 214 ал. 3 не дават друго последствие");
    assertTrue(out.refusal.contains("ПОЛОВИНЧАТ"), out.refusal);
  }

  @Test
  @DisplayName("месец без такъв разход дава нули БЕЗ отказ — законно състояние")
  void anEmptyMonthIsNotARefusal() {
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            new ArrayList<>(), vouchers(), BgSocialExpenseConditions.allMet(), BigDecimal.ZERO);

    assertNull(out.refusal);
    assertEquals(BigDecimal.ZERO.setScale(2), out.exempt);
    assertEquals(BigDecimal.ZERO.setScale(2), out.excess);
    assertEquals(BigDecimal.ZERO.setScale(2), out.companyTaxBase);
    assertTrue(out.shares.isEmpty());
  }

  @Test
  @DisplayName("⚠️ ЛИПСВАЩ ред с изключение → allMet(), тоест ВСИЧКО НАРЕД (оптимистично)")
  void theMissingExceptionRowMeansEverythingIsFine() {
    BgSocialExpenseConditions absent = BgSocialExpenseConditions.allMet();
    assertTrue(
        !absent.publicDebts && !absent.operatorUnlicensed && !absent.salaryBelowThreeMonthAverage,
        "⚠️ И трите полета са в ОТРИЦАТЕЛНАТА посока — три нули значат „изпълнени“. Написани"
            + " положително, липсващият ред щеше да обложи ЦЕЛИЯ размер на всяка фирма, която не"
            + " води списък с лоши месеци. Това е ОБРАТНОТО на BgGarnishmentScale, където липсата е"
            + " отказ, и обратното е нарочно: цената на грешката е обратна");

    // ⚠️ И null се чете като allMet() — по същия довод. Прочетен като „нищо не знаем, обложи“,
    // всеки фиш без прочетено изключение щеше да получи целия размер в основата.
    BgSocialExpenseOutcome out =
        BgSocialExpenseCapResolver.apply(
            items("pl_soc_vaucheri_v_ramkite", "150.00"), vouchers(), null, BigDecimal.ZERO);
    assertNull(out.failedCondition);
    assertEquals(new BigDecimal("102.26"), out.exempt);
  }

  // ── помощни ─────────────────────────────────────────────────────────────

  private static List<BigDecimal> exempts(BgSocialExpenseOutcome out) {
    List<BigDecimal> list = new ArrayList<>();
    for (BgSocialExpenseOutcome.Share s : out.shares) {
      list.add(s.exempt);
    }
    return list;
  }

  private static List<BigDecimal> excesses(BgSocialExpenseOutcome out) {
    List<BigDecimal> list = new ArrayList<>();
    for (BgSocialExpenseOutcome.Share s : out.shares) {
      list.add(s.excess);
    }
    return list;
  }
}
