package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.Input;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.NidUse;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.Result;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.TaxableUse;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.AssetFacts;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.Carrier;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.CostFacts;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.Regime;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Вторият носител на начислен ДДС — чл. 79а ал. 9/10 и ППЗДДС чл. 67 ал. 4.
 *
 * <p>Числата НЕ са преписани отнякъде: всяко е изведено от формулата, а коментарът над него показва
 * извеждането.
 */
class BgArt79aCarriersTest {

  private static final Set<Integer> NONE = Collections.emptySet();

  private static BigDecimal bg(String value) {
    return new BigDecimal(value);
  }

  /** Заварен недвижим имот: кредит 2020 г., изцяло приспаднат → срок 2020–2039. */
  private static AssetFacts immovable2020() {
    return new AssetFacts(
        AssetKind.IMMOVABLE_PROPERTY, CreditType.FULL, bg("100000"), 2020, null, null, null);
  }

  /** Подобрение с кредит 2030 г., НДДС 20 000, изцяло приспаднат. */
  private static CostFacts improvement(boolean isBuilding, boolean newBuilding) {
    return new CostFacts(
        7L,
        true,
        isBuilding,
        newBuilding,
        AssetKind.IMMOVABLE_PROPERTY,
        CreditType.FULL,
        bg("20000"),
        2030,
        null,
        null,
        null);
  }

  // ───────────────────────────────────────────────── ППЗДДС чл. 67 ал. 4

  @Test
  @DisplayName("чл. 67 ал. 4 — носителят язди ОСТАТЪКА от срока на заварения актив")
  void remainderCarrier_ridesIncumbentTerm() {
    List<Carrier> carriers =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(false, false)), NONE);

    assertEquals(2, carriers.size());
    Carrier second = carriers.get(1);
    assertEquals(Regime.REMAINDER_ART67_4, second.regime());
    assertEquals(2030, second.startYear());
    // ⚠️ Знаменателят остава ЗАКОНОВИЯТ (20), не остатъкът — ал. 4 мени прозореца, не срока.
    assertEquals(20, second.periodYears());
    // R = 2030…2039 включително = 10. Не е знаменател и не е брой коригиращи години.
    assertEquals(10, second.remainingYears());
    assertNull(second.refusalReason());
  }

  @Test
  @DisplayName("чл. 67 ал. 4 — годишната база е НДДС × 1/20, а не НДДС × 1/R")
  void remainderCarrier_usesStatutoryDenominator() {
    Carrier carrier =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(false, false)), NONE).get(1);

    // Кх = 0 (само освободени доставки) при К₀ = 1 ⇒ множителят е (0 − 1) = −1.
    // Прочит А: 20000 × 1/20 × (−1) = −1000,00 на година.
    // Прочит Б (1/R) би дал 20000 × 1/10 × (−1) = −2000,00 — двойно.
    Result result = BgArt79aAnnualCorrectionRules.correction(inputFor(carrier, 2031));

    assertEquals(0, bg("-1000.00").compareTo(result.amount()));
    assertEquals(20, result.periodYears());
  }

  @Test
  @DisplayName("чл. 67 ал. 4 — целият остатък излага R/20 от НДДС, НЕ целия НДДС")
  void remainderCarrier_totalOverWindowIsPartial() {
    Carrier carrier =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(false, false)), NONE).get(1);

    // Коригиращи години: 2031…2039 (ал. 1 иска годините СЛЕДВАЩИ кредитната), тоест R − 1 = 9.
    BigDecimal total = BigDecimal.ZERO;
    int correcting = 0;
    for (int year = 2030; year <= 2045; year++) {
      if (!BgArt79aCorrectionWindow.containsWithinIncumbentTerm(
          carrier.startYear(), 2020, 20, NONE, year)) {
        continue;
      }
      correcting++;
      total = total.add(BgArt79aAnnualCorrectionRules.correction(inputFor(carrier, year)).amount());
    }

    assertEquals(9, correcting, "коригиращите години са R − 1 = 9 (2031…2039)");
    // 9 × (−1000) = −9000 — тоест 9/20 от НДДС. Прочит Б би изложил целите −20000.
    assertEquals(0, bg("-9000.00").compareTo(total));
    assertTrue(
        total.abs().compareTo(bg("20000")) < 0, "част от НДДС остава некоригиран — по текста");
  }

  @Test
  @DisplayName("чл. 67 ал. 4 — прозорецът свършва с този на ЗАВАРЕНИЯ актив, не 20 г. по-късно")
  void remainderCarrier_stopsWithIncumbent() {
    Carrier carrier =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(false, false)), NONE).get(1);

    assertTrue(
        BgArt79aCorrectionWindow.containsWithinIncumbentTerm(
            carrier.startYear(), 2020, 20, NONE, 2039));
    assertTrue(
        !BgArt79aCorrectionWindow.containsWithinIncumbentTerm(
            carrier.startYear(), 2020, 20, NONE, 2040),
        "2040 е извън срока на заварения имот, макар да е в рамките на 20 г. от 2030");
  }

  // ───────────────────────────────────────────────── чл. 79а ал. 9

  @Test
  @DisplayName("ал. 9 — нова сграда дава ПРЕСЕН 20-годишен срок, независим от заварения")
  void newBuildingCarrier_getsFreshTerm() {
    Carrier carrier =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(true, true)), NONE).get(1);

    assertEquals(Regime.NEW_TERM_ART9, carrier.regime());
    assertEquals(2030, carrier.startYear());
    assertEquals(20, carrier.periodYears());
    // Свой прозорец 2030–2049: 2040 е ВЪТРЕ, за разлика от носителя по чл. 67 ал. 4.
    assertTrue(BgArt79aCorrectionWindow.contains(carrier.startYear(), 20, NONE, 2040));
    assertTrue(!BgArt79aCorrectionWindow.contains(carrier.startYear(), 20, NONE, 2050));
    // Остатък не се ползва в този режим.
    assertEquals(0, carrier.remainingYears());
  }

  @Test
  @DisplayName("ал. 9 т. 2 — НДДС е данъкът за ПОДОБРЕНИЕТО, не този при придобиване")
  void newBuildingCarrier_usesImprovementVat() {
    List<Carrier> carriers =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(true, true)), NONE);

    assertEquals(0, bg("100000").compareTo(carriers.get(0).vatCharged()));
    assertEquals(0, bg("20000").compareTo(carriers.get(1).vatCharged()));
  }

  // ───────────────────────────────────────────────── празната трета пътека

  @Test
  @DisplayName("Сграда БЕЗ нова сграда — носител НЕ възниква, но причината се обявява")
  void buildingWithoutNewBuilding_hasNoCarrier() {
    Carrier carrier =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(true, false)), NONE).get(1);

    assertEquals(Regime.NONE, carrier.regime());
    assertNotNull(carrier.refusalReason());
    assertTrue(carrier.refusalReason().contains("§ 1 т. 32"));
    assertTrue(!carrier.isDue());
  }

  @Test
  @DisplayName("Разход, необявен за подобрение — няма носител; CAPITAL по ЗКПО не решава")
  void nonImprovement_hasNoCarrier() {
    CostFacts notImprovement =
        new CostFacts(
            9L,
            false,
            false,
            false,
            AssetKind.GOODS,
            CreditType.FULL,
            bg("500"),
            2030,
            null,
            null,
            null);
    Carrier carrier =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(notImprovement), NONE).get(1);

    assertEquals(Regime.NONE, carrier.regime());
    assertTrue(carrier.refusalReason().contains("§ 1 т. 32"));
  }

  @Test
  @DisplayName("Изтекъл срок на заварения актив — по чл. 67 ал. 4 остатък няма")
  void remainderCarrier_refusesWhenIncumbentExpired() {
    CostFacts late =
        new CostFacts(
            11L,
            true,
            false,
            false,
            AssetKind.IMMOVABLE_PROPERTY,
            CreditType.FULL,
            bg("20000"),
            2045,
            null,
            null,
            null);
    Carrier carrier = BgArt79aCarriers.of(immovable2020(), Arrays.asList(late), NONE).get(1);

    assertEquals(Regime.NONE, carrier.regime());
    assertTrue(carrier.refusalReason().contains("чл. 67 ал. 4"));
  }

  // ───────────────────────────────────────────────── споделено спиране и свои коефициенти

  @Test
  @DisplayName("ал. 8 — спряна година мести КРАЯ, тъй че остатъкът по чл. 67 ал. 4 РАСТЕ")
  void suspension_growsTheRemainder() {
    Set<Integer> suspended = new java.util.HashSet<>(Arrays.asList(2025, 2026));

    Carrier without =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(false, false)), NONE).get(1);
    Carrier with =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(false, false)), suspended)
            .get(1);

    // Без спиране 2030 е тикаща година № 11 ⇒ R = 10. С две спрени преди нея е № 9 ⇒ R = 12.
    assertEquals(10, without.remainingYears());
    assertEquals(12, with.remainingYears());
    // И краят се мести: 2041 вече е вътре в срока на заварения имот.
    assertTrue(
        BgArt79aCorrectionWindow.containsWithinIncumbentTerm(2030, 2020, 20, suspended, 2041));
  }

  @Test
  @DisplayName("Носителят на подобрението смята със СВОИТЕ К₀/ПрНИД₀, не с тези на заварения")
  void improvementCarrier_keepsItsOwnCoefficients() {
    AssetFacts asset =
        new AssetFacts(
            AssetKind.IMMOVABLE_PROPERTY,
            CreditType.PARTIAL,
            bg("100000"),
            2020,
            null,
            bg("0.40"),
            null);
    CostFacts cost =
        new CostFacts(
            13L,
            true,
            false,
            false,
            AssetKind.IMMOVABLE_PROPERTY,
            CreditType.PARTIAL,
            bg("20000"),
            2030,
            null,
            bg("0.90"),
            null);

    List<Carrier> carriers = BgArt79aCarriers.of(asset, Arrays.asList(cost), NONE);

    assertEquals(0, bg("0.40").compareTo(carriers.get(0).k0()));
    assertEquals(0, bg("0.90").compareTo(carriers.get(1).k0()));
  }

  @Test
  @DisplayName("Противоречиво обявяване „сграда/не-сграда“ за един актив се ОТКАЗВА")
  void contradictoryBuildingFlag_refuses() {
    List<Carrier> carriers =
        BgArt79aCarriers.of(
            immovable2020(),
            Arrays.asList(improvement(true, true), improvement(false, false)),
            NONE);

    assertEquals(Regime.NONE, carriers.get(1).regime());
    assertEquals(Regime.NONE, carriers.get(2).regime());
    assertTrue(carriers.get(1).refusalReason().contains("Противоречиво"));
  }

  @Test
  @DisplayName("Носителят при придобиване винаги е пръв и не зависи от разходите")
  void acquisitionCarrierIsAlwaysFirst() {
    List<Carrier> carriers =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(improvement(true, true)), NONE);

    assertEquals(Regime.ACQUISITION, carriers.get(0).regime());
    assertNull(carriers.get(0).subsequentCostId());
    assertEquals(2020, carriers.get(0).startYear());
    assertEquals(7L, carriers.get(1).subsequentCostId().longValue());
  }

  /** Вход за формулата от един носител: само освободени доставки през година х (Кх = 0). */
  private static Input inputFor(Carrier carrier, int year) {
    return new Input(
        carrier.kind(),
        carrier.creditType(),
        carrier.vatCharged(),
        year,
        carrier.k0(),
        carrier.proportion0(),
        TaxableUse.ONLY_WITHOUT_CREDIT,
        null,
        NidUse.ONLY_NID,
        null,
        false,
        false,
        false,
        carrier.periodYears());
  }
}
