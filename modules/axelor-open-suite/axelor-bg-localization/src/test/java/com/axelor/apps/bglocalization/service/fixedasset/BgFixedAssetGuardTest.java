package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.repo.FixedAssetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИНВЕНТАРНИЯТ НОМЕР И КАКВО ТОЙ ОЗНАЧАВА (SAF-T A1) — правилата на {@link BgFixedAssetGuard} по
 * старото и новото състояние, без база. Пътят през Hibernate и REST е в {@code
 * BgFixedAssetIdentityRestTest}.
 */
class BgFixedAssetGuardTest {

  private static final int DRAFT = FixedAssetRepository.STATUS_DRAFT;
  private static final int VALIDATED = FixedAssetRepository.STATUS_VALIDATED;
  private static final int TRANSFERRED = FixedAssetRepository.STATUS_TRANSFERRED;
  private static final int MANUAL = FixedAssetRepository.ORIGINAL_SELECT_MANUAL;
  private static final int SPLIT = FixedAssetRepository.ORIGINAL_SELECT_SCISSION;
  private static final String NSS = BgAccountingFramework.NSS;
  private static final String IFRS = BgAccountingFramework.IFRS;

  private static BgFixedAssetGuard.State state(
      int status,
      String seq,
      Long account,
      Long category,
      Long splitFrom,
      String kind,
      int origin) {
    return new BgFixedAssetGuard.State(status, seq, account, category, splitFrom, kind, origin);
  }

  private static BgFixedAssetGuard.State validated(String seq) {
    return state(VALIDATED, seq, 20L, 7L, null, BgFixedAssetKinds.TANGIBLE, MANUAL);
  }

  private static String update(BgFixedAssetGuard.State old, BgFixedAssetGuard.State now) {
    return BgFixedAssetGuard.updateRefusal(5L, old, now, NSS);
  }

  // ─────────────── номерът ───────────────

  @Test
  @DisplayName("Валидиран актив: номерът не се сменя и не се изтрива; интервалите в краищата — не")
  void номерът_не_се_мени() {
    String refusal = update(validated("IMMO2026001"), validated("IMMO2026002"));
    assertNotNull(refusal);
    assertTrue(refusal.contains("IMMO2026001") && refusal.contains("AssetID"), refusal);
    assertNotNull(update(validated("IMMO2026001"), validated(null)), "изтриване на номера");
    assertNotNull(update(validated("IMMO2026001"), validated("")), "празен вместо номера");
    assertNull(update(validated("IMMO2026001"), validated(" IMMO2026001 ")), "само интервали");
  }

  @Test
  @DisplayName(
      "Празният номер на валидиран актив се попълва (Microinvest); в чернова номерът е свободен")
  void празният_се_попълва() {
    assertNull(update(validated(null), validated("INV-17")));
    assertNull(update(validated(""), validated("INV-17")));
    BgFixedAssetGuard.State draft = state(DRAFT, "A", 20L, 7L, null, null, MANUAL);
    assertNull(update(draft, state(DRAFT, "B", 21L, 8L, null, null, MANUAL)), "чернова");
    assertNull(
        update(draft, state(VALIDATED, "IMMO2026001", 20L, 7L, null, null, MANUAL)),
        "валидирането пише номера");
  }

  // ─────────────── сметката, категорията, статусът, триенето ───────────────

  @Test
  @DisplayName("Валидиран актив: сметката и категорията не се сменят — това е вътрешен трансфер")
  void сметката_и_категорията() {
    String account =
        update(
            validated("N1"),
            state(VALIDATED, "N1", 21L, 7L, null, BgFixedAssetKinds.TANGIBLE, MANUAL));
    assertTrue(account != null && account.contains("сметката"), account);
    String category =
        update(
            validated("N1"),
            state(VALIDATED, "N1", 20L, 8L, null, BgFixedAssetKinds.TANGIBLE, MANUAL));
    assertTrue(category != null && category.contains("категорията"), category);
  }

  @Test
  @DisplayName("Валидиран и изведен актив не се връщат в чернова и не се трият; чернова се трие")
  void статусът_и_триенето() {
    assertNotNull(
        update(
            validated("N1"),
            state(DRAFT, "N1", 20L, 7L, null, BgFixedAssetKinds.TANGIBLE, MANUAL)));
    assertNull(
        update(
            validated("N1"),
            state(TRANSFERRED, "N1", 20L, 7L, null, BgFixedAssetKinds.TANGIBLE, MANUAL)),
        "извеждането е напред, не назад");
    assertNotNull(BgFixedAssetGuard.deleteRefusal(5L, validated("N1")));
    assertNotNull(
        BgFixedAssetGuard.deleteRefusal(5L, state(TRANSFERRED, "N1", 20L, 7L, null, null, MANUAL)));
    assertNull(
        BgFixedAssetGuard.deleteRefusal(5L, state(DRAFT, null, 20L, 7L, null, null, MANUAL)));
  }

  // ─────────────── „отделен от“ ───────────────

  @Test
  @DisplayName(
      "„Отделен от“: само на отделен със split актив (произход 4/5/6), веднъж; после не се мени")
  void отделен_от() {
    assertNull(
        BgFixedAssetGuard.insertRefusal(state(VALIDATED, "N2", 20L, 7L, 1L, null, SPLIT), NSS),
        "split при вмъкване");
    assertNotNull(
        BgFixedAssetGuard.insertRefusal(state(VALIDATED, "N2", 20L, 7L, 1L, null, MANUAL), NSS),
        "ръчен актив с „отделен от“");
    for (int origin :
        new int[] {
          FixedAssetRepository.ORIGINAL_SELECT_PARTIAL_SCRAPPING,
          FixedAssetRepository.ORIGINAL_SELECT_PARTIAL_SALE
        }) {
      assertNull(
          BgFixedAssetGuard.insertRefusal(state(VALIDATED, "N2", 20L, 7L, 1L, null, origin), NSS));
    }
    BgFixedAssetGuard.State linked = state(VALIDATED, "N2", 20L, 7L, 1L, null, SPLIT);
    assertNotNull(update(linked, state(VALIDATED, "N2", 20L, 7L, 9L, null, SPLIT)), "друг");
    assertNotNull(update(linked, state(VALIDATED, "N2", 20L, 7L, null, null, SPLIT)), "изтрит");
    assertNotNull(
        update(
            validated("N1"),
            state(VALIDATED, "N1", 20L, 7L, 1L, BgFixedAssetKinds.TANGIBLE, MANUAL)),
        "добавен по-късно на ръчен актив");
  }

  // ─────────────── видът по рамката ───────────────

  @Test
  @DisplayName(
      "Държан за продажба и право на ползване — само при МСФО; при НСС и без рамка — отказ")
  void видът_по_рамката() {
    for (String kind :
        new String[] {BgFixedAssetKinds.HELD_FOR_SALE, BgFixedAssetKinds.RIGHT_OF_USE}) {
      BgFixedAssetGuard.State asset = state(DRAFT, null, 20L, 7L, null, kind, MANUAL);
      assertNotNull(BgFixedAssetGuard.insertRefusal(asset, NSS), kind + " при НСС");
      String none = BgFixedAssetGuard.insertRefusal(asset, null);
      assertTrue(none != null && none.contains("не е зададена"), none);
      assertNull(BgFixedAssetGuard.insertRefusal(asset, IFRS), kind + " при МСФО");
      assertNotNull(
          BgFixedAssetGuard.updateRefusal(
              5L, validated("N1"), state(VALIDATED, "N1", 20L, 7L, null, kind, MANUAL), NSS),
          "смяна към " + kind + " при НСС");
    }
    for (String kind :
        new String[] {
          BgFixedAssetKinds.TANGIBLE,
          BgFixedAssetKinds.INTANGIBLE,
          BgFixedAssetKinds.INVESTMENT_PROPERTY_COST,
          BgFixedAssetKinds.INVESTMENT_PROPERTY_FAIR_VALUE
        }) {
      assertNull(
          BgFixedAssetGuard.insertRefusal(state(DRAFT, null, 20L, 7L, null, kind, MANUAL), null),
          kind + " — без рамка също");
    }
  }

  @Test
  @DisplayName(
      "Масовото обновяване не пипа номера, статуса, сметката, категорията, връзката и вида")
  void масовото() {
    for (String field :
        new String[] {
          "fixedAssetSeq",
          "statusSelect",
          "purchaseAccount",
          "fixedAssetCategory",
          "bgSplitFrom",
          "bgAssetKindSelect"
        }) {
      assertTrue(BgFixedAssetGuard.NOT_MASS_UPDATABLE.contains(field), field);
    }
  }
}
