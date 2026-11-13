package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Последващи разходи по чл. 63 и чл. 64 ЗКПО.
 *
 * <p>Еталонът е машината от WS-A1: ДАС 10 000 EUR, категория II (30% годишно) → 250 EUR на месец за
 * 40 месеца. Капиталов последващ разход 2 000 EUR вдига ДАС на 12 000 → 300 EUR на месец.
 */
class BgSubsequentCostRulesTest {

  private static final BigDecimal НОРМА = new BigDecimal("30.00");

  @Test
  @DisplayName("⚠️ датата е началото на МЕСЕЦА на завършване, НЕ 1 януари")
  void датата_не_е_първи_януари() {
    // Чл. 63 изр. 2: „Данъчната амортизируема стойност се увеличава от началото на месеца, през
    // който са завършени последващите разходи." Вр. чл. 62 ал. 5 — новото обстоятелство се отразява
    // „към момента на установяване през текущата година", а не ретроспективно към 1 януари (ал. 2,
    // която урежда случаите, в които нещо е ТРЯБВАЛО да се знае по-рано — ал. 3).
    assertEquals(
        LocalDate.parse("2027-03-01"),
        BgSubsequentCostRules.effectiveFrom(LocalDate.parse("2027-03-15")),
        "разход, завършен на 15 март, важи от 1 март — не от 1 януари и не от 15 март");
    assertEquals(
        LocalDate.parse("2027-12-01"),
        BgSubsequentCostRules.effectiveFrom(LocalDate.parse("2027-12-31")),
        "декемврийски разход НЕ се мести в началото на годината");
    assertNull(BgSubsequentCostRules.effectiveFrom(null));
  }

  @Test
  @DisplayName("текущият разход не мени нищо — чл. 63/64 не се прилагат")
  void текущият_разход_не_мени_дас() {
    FixedAsset asset = assetInTaxPlan("10000.000");
    assertEquals(
        BgSubsequentCostRules.Routing.NONE,
        BgSubsequentCostRules.routing(asset, BgSubsequentCostRules.Classification.CURRENT),
        "ремонтът е текущ разход и остава такъв — и двата члена искат разход, който „води до бъдещи"
            + " икономически изгоди“");
    assertEquals(
        new BigDecimal("10000.000"),
        BgSubsequentCostRules.amortizableValue(asset),
        "ДАС е непроменена");
  }

  @Test
  @DisplayName("рутингът се решава от ПРИСЪСТВИЕТО в ДАП, не от състоянието на актива")
  void рутингът_по_присъствие_в_дап() {
    FixedAsset вДап = assetInTaxPlan("10000.000");
    assertEquals(
        BgSubsequentCostRules.Routing.ART63,
        BgSubsequentCostRules.routing(вДап, BgSubsequentCostRules.Classification.CAPITAL),
        "чл. 63 иска актив „наличен в данъчния амортизационен план“");

    FixedAsset извънДап = assetInTaxPlan("10000.000");
    извънДап.setDepreciationPlanSelect(FixedAssetRepository.DEPRECIATION_PLAN_ECONOMIC);
    assertEquals(
        BgSubsequentCostRules.Routing.ART64,
        BgSubsequentCostRules.routing(извънДап, BgSubsequentCostRules.Classification.CAPITAL),
        "чл. 64 ал. 1 — „отписан от данъчния амортизационен план, но не отписан за счетоводни цели“");

    FixedAsset отписан = assetInTaxPlan("10000.000");
    отписан.setStatusSelect(FixedAssetRepository.STATUS_TRANSFERRED);
    assertEquals(
        BgSubsequentCostRules.Routing.NONE,
        BgSubsequentCostRules.routing(отписан, BgSubsequentCostRules.Classification.CAPITAL),
        "отписан И счетоводно не попада в нито един от двата — чл. 64 иска активът ДА Е на баланс");
  }

  @Test
  @DisplayName("ДАС пада обратно на отчетната, докато не е зададена — заварените активи не мърдат")
  void дас_пада_обратно_на_отчетната() {
    FixedAsset asset = assetInTaxPlan("10000.000");
    assertEquals(
        new BigDecimal("10000.000"),
        BgSubsequentCostRules.amortizableValue(asset),
        "празното поле значи „както казва отчетната“");
    asset.setBgTaxAmortizableValue(new BigDecimal("12000.000"));
    assertEquals(
        new BigDecimal("12000.000"),
        BgSubsequentCostRules.amortizableValue(asset),
        "щом е зададена, ДАС е СОБСТВЕНА величина и се разминава с отчетната — оттам разликите по"
            + " чл. 54");
  }

  @Test
  @DisplayName("еталон: 10 000 + 2 000 → 12 000, месечната става 300 (нормата НЕ се мени)")
  void еталонът_на_чл_63() {
    FixedAsset asset = assetInTaxPlan("10000.000");
    assertEquals(
        new BigDecimal("250.00"),
        BgSubsequentCostRules.monthlyCharge(new BigDecimal("10000.000"), НОРМА),
        "10 000 × 30 / 1200 = 250");
    assertEquals(
        new BigDecimal("12000.000"),
        BgSubsequentCostRules.increasedAmortizableValue(asset, new BigDecimal("2000.000")));
    assertEquals(
        new BigDecimal("300.00"),
        BgSubsequentCostRules.monthlyCharge(new BigDecimal("12000.000"), НОРМА),
        "12 000 × 30 / 1200 = 300 — базата расте, НОРМАТА остава таванът по чл. 55 ал. 2");
  }

  @Test
  @DisplayName("⚠️ остатъкът се реже по тавана на чл. 53 ал. 3, последната вноска е остатък")
  void графикът_след_увеличението() {
    // 14 месеца по 250 вече са начислени (3 500). Нова ДАС 12 000 → остават 8 500 по 300 на месец:
    // 28 пълни вноски от 300 (8 400) и една от 100.
    List<BigDecimal> график =
        BgSubsequentCostRules.remainingSchedule(
            new BigDecimal("12000.000"), new BigDecimal("3500.000"), НОРМА);
    assertEquals(29, график.size(), "28 пълни месеца плюс остатъка");
    assertEquals(new BigDecimal("300.00"), график.get(0));
    assertEquals(new BigDecimal("300.00"), график.get(27));
    assertEquals(
        new BigDecimal("100.000"),
        график.get(28),
        "последната вноска е ОСТАТЪКЪТ: чл. 53 ал. 3 — начислената амортизация не може да превишава"
            + " данъчната амортизируема стойност");

    BigDecimal сбор = BigDecimal.ZERO;
    for (BigDecimal x : график) {
      сбор = сбор.add(x);
    }
    assertEquals(
        0,
        сбор.compareTo(new BigDecimal("8500.000")),
        "сборът на остатъка е точно 12 000 − 3 500, нито стотинка повече");
  }

  @Test
  @DisplayName("чл. 64 ал. 3: категорията се НАСЛЕДЯВА от стария актив, не се избира")
  void категорията_се_наследява() {
    FixedAsset source = assetInTaxPlan("10000.000");
    source.setBgTaxCategorySelect("II");
    assertEquals(
        "II",
        BgSubsequentCostRules.inheritedCategory(source),
        "„се разпределя в категорията, в която е бил разпределен активът, във връзка с който са"
            + " извършени последващите разходи“");
    assertNull(BgSubsequentCostRules.inheritedCategory(null));
  }

  @Test
  @DisplayName("⚠️ прагът по чл. 50/51 върху актива по чл. 64 — приложен, но е ТЪЛКУВАНЕ")
  void прагът_върху_самостоятелния_актив() {
    BigDecimal праг = new BigDecimal("357.90");
    assertTrue(
        BgSubsequentCostRules.entersTaxPlan(new BigDecimal("2000.000"), праг),
        "2 000 превишава прага → самостоятелният актив влиза в ДАП");
    assertFalse(
        BgSubsequentCostRules.entersTaxPlan(new BigDecimal("300.000"), праг),
        "300 е под прага. ⚠️ Това е ТЪЛКУВАНЕ: чл. 64 ал. 1 казва императивно „се завежда“, без да"
            + " препраща към праг — да се потвърди");
    assertTrue(
        BgSubsequentCostRules.entersTaxPlan(new BigDecimal("357.90"), праг),
        "точно на прага влиза — чл. 50 ал. 1 казва „равна или превишава“");
  }

  @Test
  @DisplayName("⚠️ чл. 63 НЯМА праг — асиметрията е в самия закон")
  void чл_63_няма_праг() {
    // Наличният в ДАП актив увеличава ДАС с разхода, колкото и малък да е: чл. 63 не препраща към
    // чл. 50/51. Само чл. 64 поражда НОВ актив, а новият актив е там, където прагът има предмет.
    FixedAsset asset = assetInTaxPlan("10000.000");
    assertEquals(
        new BigDecimal("10000.300"),
        BgSubsequentCostRules.increasedAmortizableValue(asset, new BigDecimal("0.300")),
        "дори 30 стотинки увеличават ДАС по чл. 63 — прагът няма предмет тук");
  }

  private static FixedAsset assetInTaxPlan(String grossValue) {
    FixedAsset asset = new FixedAsset();
    asset.setGrossValue(new BigDecimal(grossValue));
    asset.setDepreciationPlanSelect(
        FixedAssetRepository.DEPRECIATION_PLAN_FISCAL
            + ","
            + FixedAssetRepository.DEPRECIATION_PLAN_ECONOMIC);
    asset.setStatusSelect(FixedAssetRepository.STATUS_VALIDATED);
    return asset;
  }
}
