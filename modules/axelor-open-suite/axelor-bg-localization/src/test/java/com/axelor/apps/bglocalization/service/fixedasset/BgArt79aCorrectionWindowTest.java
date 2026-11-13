package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Прозорецът на годишната корекция — чл. 79а ал. 8 и чл. 79б ал. 7 ЗДДС. */
class BgArt79aCorrectionWindowTest {

  private static final Set<Integer> NONE = Collections.emptySet();

  private static Set<Integer> years(int... values) {
    Set<Integer> set = new HashSet<>();
    for (int value : values) {
      set.add(value);
    }
    return set;
  }

  @Test
  @DisplayName("Годината на кредита НЕ е корекционна — ал. 1 иска годините, СЛЕДВАЩИ я")
  void creditYearItselfIsNotCorrected() {
    assertFalse(BgArt79aCorrectionWindow.contains(2020, 5, NONE, 2020));
    assertTrue(BgArt79aCorrectionWindow.contains(2020, 5, NONE, 2021));
  }

  @Test
  @DisplayName("Прозорецът се затваря след n БРОЕНИ години — чл. 80 ал. 1 т. 3/т. 4")
  void windowClosesAfterTerm() {
    // Срок 2020-2024 при n = 5: корекционни са 2021, 2022, 2023, 2024.
    assertTrue(BgArt79aCorrectionWindow.contains(2020, 5, NONE, 2024));
    assertFalse(BgArt79aCorrectionWindow.contains(2020, 5, NONE, 2025));
    assertFalse(BgArt79aCorrectionWindow.contains(2020, 5, NONE, 2019));
  }

  @Test
  @DisplayName("чл. 79а ал. 8 — спряна година не се коригира И НЕ изразходва срока")
  void suspendedYearExtendsTheTerm() {
    Set<Integer> suspended = years(2022);

    // 2022 не се използва за чл. 69/70: няма ред за нея.
    assertFalse(BgArt79aCorrectionWindow.contains(2020, 5, suspended, 2022));
    // Срокът се възобновява и краят му се мести с ЕДНА година напред: 2025 вече е вътре.
    assertTrue(BgArt79aCorrectionWindow.contains(2020, 5, suspended, 2025));
    assertFalse(BgArt79aCorrectionWindow.contains(2020, 5, suspended, 2026));
  }

  @Test
  @DisplayName("Броеният номер прескача спрените години")
  void countedIndexSkipsSuspended() {
    Set<Integer> suspended = years(2022, 2023);

    assertEquals(1, BgArt79aCorrectionWindow.countedIndex(2020, suspended, 2020));
    assertEquals(2, BgArt79aCorrectionWindow.countedIndex(2020, suspended, 2021));
    assertEquals(0, BgArt79aCorrectionWindow.countedIndex(2020, suspended, 2022));
    assertEquals(3, BgArt79aCorrectionWindow.countedIndex(2020, suspended, 2024));
    assertEquals(0, BgArt79aCorrectionWindow.countedIndex(2020, suspended, 2019));
  }

  @Test
  @DisplayName("Основанието по чл. 80 ал. 1 излиза само при изтекъл срок, и зависи от актива")
  void timeBarGroundNamesThePoint() {
    assertNull(BgArt79aCorrectionWindow.timeBarGround(AssetKind.GOODS, 2020, NONE, 2024));
    assertEquals(
        BgAssetRemoval.VatGround.ART80_1_4,
        BgArt79aCorrectionWindow.timeBarGround(AssetKind.GOODS, 2020, NONE, 2025));
    assertEquals(
        BgAssetRemoval.VatGround.ART80_1_3,
        BgArt79aCorrectionWindow.timeBarGround(AssetKind.IMMOVABLE_PROPERTY, 2020, NONE, 2040));
  }

  // ───────────────────────────────────────────────── началото на срока

  @Test
  @DisplayName("Без упражнен кредит срокът тръгва от годината, в която изтича чл. 72 ал. 1")
  void startsFromArt72DeadlineWhenNoCreditTaken() {
    assertEquals(2021, BgArt79aCorrectionWindow.periodStartYear(AssetKind.GOODS, 0, 2021, null));
    assertEquals(2020, BgArt79aCorrectionWindow.periodStartYear(AssetKind.GOODS, 2020, 2021, null));
  }

  @Test
  @DisplayName("Годината на фактическо използване мести срока САМО при недвижим имот")
  void firstUseYearMovesImmovableOnly() {
    // Имотът не е използван повече от година след годината на кредита → срокът тръгва от 2023.
    assertEquals(
        2023,
        BgArt79aCorrectionWindow.periodStartYear(AssetKind.IMMOVABLE_PROPERTY, 2020, 0, 2023));
    // Същите факти при движима вещ и при услуга не местят нищо.
    assertEquals(2020, BgArt79aCorrectionWindow.periodStartYear(AssetKind.GOODS, 2020, 0, 2023));
    assertEquals(2020, BgArt79aCorrectionWindow.periodStartYear(AssetKind.SERVICE, 2020, 0, 2023));
  }

  @Test
  @DisplayName("Използване в рамките на година след кредита НЕ мести срока")
  void firstUseWithinOneYearDoesNotMove() {
    assertEquals(
        2020,
        BgArt79aCorrectionWindow.periodStartYear(AssetKind.IMMOVABLE_PROPERTY, 2020, 0, 2021));
  }
}
