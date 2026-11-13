package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.AssetFacts;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.Carrier;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.CostFacts;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.Regime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Свидетелите, които махнатият гардрейл нямаше.
 *
 * <p>До този клон услугата ОТКАЗВАШЕ да смята за актив с последващи разходи — и нито един тест не
 * заковаваше отказа. Сега отказът го няма, тъй че правилата, които го заместват, трябва да имат
 * свидетел: двойното броене по ЗКПО чл. 64, идемпотентността на ключа и споделеното спиране.
 *
 * <p>⚠️ Заявките се сверяват СТРУКТУРНО — модулът няма mock-подпора в тестовете. Затова филтърът е
 * изнесен като константа: това е единственото, което може да се провери без база, и е по-добро от
 * нищо.
 */
class BgVatAnnualCorrectionCarrierTest {

  private static final Set<Integer> NONE = Collections.emptySet();

  private static BigDecimal bg(String value) {
    return new BigDecimal(value);
  }

  private static AssetFacts immovable2020() {
    return new AssetFacts(
        AssetKind.IMMOVABLE_PROPERTY, CreditType.FULL, bg("100000"), 2020, null, null, null);
  }

  private static CostFacts cost(long id, boolean building, boolean newBuilding) {
    return new CostFacts(
        id,
        true,
        building,
        newBuilding,
        AssetKind.IMMOVABLE_PROPERTY,
        CreditType.FULL,
        bg("20000"),
        2030,
        null,
        null,
        null);
  }

  // ───────────────── Капан 1 (структурната половина; поведението е в BgVatHeldAssetsFilterDbTest)

  @Test
  @DisplayName("ЗКПО чл. 64 — филтърът на обхождането е ТОЧНО този; всяко отслабване пада тук")
  void heldAssetsFilterIsExactlyAsExpected() {
    // ⚠️ ТОЧЕН НИЗ, не `contains`. Проверка „съдържа NOT EXISTS“ минаваше и ако условието беше
    // пренаписано наопаки, стеснено до друга колона или залепено за чужд подзаявков корен — тоест
    // не пазеше нищо освен наличието на две думи. Точният низ прави всяко бъдещо отслабване видимо
    // на място, а умишлената промяна иска и този еталон да се обнови съзнателно.
    //
    // ⚠️ И ДВОЙКАТА С DB ТЕСТА ВИСИ ТОЧНО ТУК. BgVatHeldAssetsFilterDbTest превежда СЪЩАТА
    // константа в SQL и я пуска срещу истинската схема; ако някой смени JPQL-а, първо пада този
    // еталон, тоест преводът не може да остане зелен върху остарял израз.
    String expected =
        "self.company = :c AND self.statusSelect IN (:validated, :depreciated)"
            + " AND self.bgVatAssetKindSelect IS NOT NULL"
            + " AND NOT EXISTS (SELECT 1 FROM BgSubsequentCost c WHERE c.generatedFixedAsset"
            + " = self)";

    assertEquals(
        expected,
        BgVatAnnualCorrectionService.HELD_ASSETS_FILTER,
        "филтърът на heldAssets() се промени. Ако промяната е умишлена, обнови и еталона, и"
            + " превода в BgVatHeldAssetsFilterDbTest — без NOT EXISTS частта породеният по"
            + " ЗКПО чл. 64 актив се обхожда като отделен и корекцията влиза ДВА пъти в к.11.");
  }

  @Test
  @DisplayName("Огледалният филтър брои ТОЧНО пропуснатите — различава се само по NULL-а")
  void unclassifiedFilterIsTheExactMirror() {
    // ⚠️ ОГЛЕДАЛО, А НЕ ВТОРА ЗАЯВКА. Броячът отговаря на въпроса „кои активи НЕ бяха обходени“,
    // тъй че всяко друго разминаване с HELD_ASSETS_FILTER прави числото невярно: по-широк, той
    // брои и чернови; по-тесен — премълчава точно активите, заради които потокът мълчи.
    String expected =
        "self.company = :c AND self.statusSelect IN (:validated, :depreciated)"
            + " AND self.bgVatAssetKindSelect IS NULL"
            + " AND NOT EXISTS (SELECT 1 FROM BgSubsequentCost c WHERE c.generatedFixedAsset"
            + " = self)";

    assertEquals(
        expected,
        BgVatAnnualCorrectionService.UNCLASSIFIED_ASSETS_FILTER,
        "огледалният филтър се промени — обнови и еталона, и превода в"
            + " BgVatHeldAssetsFilterDbTest");
    assertEquals(
        BgVatAnnualCorrectionService.HELD_ASSETS_FILTER.replace("IS NOT NULL", "IS NULL"),
        BgVatAnnualCorrectionService.UNCLASSIFIED_ASSETS_FILTER,
        "двата филтъра трябва да се разделят САМО по отрицанието на класификацията");
  }

  @Test
  @DisplayName("Придобиване + едно подобрение = ТОЧНО два носителя, в стабилен ред")
  void acquisitionPlusOneImprovementYieldsTwoCarriers() {
    // ⚠️ ТОВА НЕ Е ТЕСТЪТ ЗА КАПАНА, и по-ранното му заглавие лъжеше, че е. Тук няма породен по
    // ЗКПО чл. 64 актив — сглобяването на носители изобщо не го вижда, защото работи върху ЕДИН
    // актив и разходите му. Дали породеният се обхожда като ОТДЕЛЕН актив се решава в заявката на
    // heldAssets() и се проверява от BgVatHeldAssetsFilterDbTest срещу истинската схема.
    List<Carrier> carriers =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(cost(5L, false, false)), NONE);

    assertEquals(2, carriers.size(), "придобиване + едно подобрение = точно два носителя");
    assertEquals(Regime.ACQUISITION, carriers.get(0).regime());
    assertEquals(Regime.REMAINDER_ART67_4, carriers.get(1).regime());
  }

  // ───────────────────────────────────────── Капан 2: идемпотентност на ключа

  @Test
  @DisplayName("Ключът НИКОГА не е null — иначе PostgreSQL не дедуплицира придобиването")
  void carrierKeyIsNeverNull() {
    List<Carrier> carriers =
        BgArt79aCarriers.of(
            immovable2020(), Arrays.asList(cost(5L, false, false), cost(6L, false, false)), NONE);

    List<String> keys = new ArrayList<>();
    for (Carrier carrier : carriers) {
      String key = BgVatAnnualCorrectionService.carrierKey(carrier);
      assertNotNull(key, "нулев ключ значи, че unique constraint не хваща дубликат");
      keys.add(key);
    }

    assertEquals("ACQUISITION", keys.get(0));
    assertEquals("COST:5", keys.get(1));
    assertEquals("COST:6", keys.get(2));
    assertEquals(new HashSet<>(keys).size(), keys.size(), "ключовете са различни за всеки носител");
  }

  @Test
  @DisplayName("Ключът на придобиването е стабилен — повторното предлагане не го дублира")
  void acquisitionKeyIsStableAcrossProposals() {
    String first =
        BgVatAnnualCorrectionService.carrierKey(
            BgArt79aCarriers.of(immovable2020(), Collections.emptyList(), NONE).get(0));
    String second =
        BgVatAnnualCorrectionService.carrierKey(
            BgArt79aCarriers.of(immovable2020(), Arrays.asList(cost(9L, false, false)), NONE)
                .get(0));

    assertEquals(first, second);
    assertNotEquals(
        first,
        BgVatAnnualCorrectionService.carrierKey(
            BgArt79aCarriers.of(immovable2020(), Arrays.asList(cost(9L, false, false)), NONE)
                .get(1)));
  }

  // ───────────────────────────────────────── споделеното спиране

  @Test
  @DisplayName("ал. 8 — спряната година е на АКТИВА: нула за ВСИЧКИ носители, и краят се бута")
  void suspensionAppliesToEveryCarrierOfTheAsset() {
    Set<Integer> suspended = new HashSet<>(Arrays.asList(2032));
    List<Carrier> carriers =
        BgArt79aCarriers.of(immovable2020(), Arrays.asList(cost(5L, false, false)), suspended);

    Carrier acquisition = carriers.get(0);
    Carrier improvement = carriers.get(1);

    // 2032 не се коригира за НИТО ЕДИН носител — подлогът на ал. 8 е имотът, не данъкът.
    assertTrue(
        !com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCorrectionWindow.contains(
            acquisition.startYear(), acquisition.periodYears(), suspended, 2032));
    assertTrue(
        !com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCorrectionWindow
            .containsWithinIncumbentTerm(improvement.startYear(), 2020, 20, suspended, 2032));

    // И краят на ДВАТА се мести с една година напред: 2040 вече е вътре.
    assertTrue(
        com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCorrectionWindow.contains(
            acquisition.startYear(), acquisition.periodYears(), suspended, 2040));
    assertTrue(
        com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCorrectionWindow
            .containsWithinIncumbentTerm(improvement.startYear(), 2020, 20, suspended, 2040));
  }
}
