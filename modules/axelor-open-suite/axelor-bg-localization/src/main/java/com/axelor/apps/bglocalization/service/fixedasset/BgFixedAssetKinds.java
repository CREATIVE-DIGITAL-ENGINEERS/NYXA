package com.axelor.apps.bglocalization.service.fixedasset;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ВИДЪТ НА АКТИВА — {@code FixedAsset.bgAssetKindSelect} (SAF-T A1).
 *
 * <ul>
 *   <li><b>ДМА / НМА</b> — по подразбиране от сметката на актива: 21x → НМА, всичко друго → ДМА.
 *       Само тези два се извеждат сами; инвестиционен имот и останалите ги избира човекът.
 *   <li><b>Инвестиционен имот по цена на придобиване</b> — във файла като останалите ДМА (сметка
 *       223, група 22 по номенклатурата на НАП).
 *   <li><b>Инвестиционен имот по справедлива стойност, държан за продажба (МСФО 5), право на
 *       ползване (МСФО 16)</b> — в годишния SAF-T ГРЕШКА „не се поддържа“ ({@link #UNSUPPORTED}):
 *       обезценката, преоценката и пълното МСФО са отделни задачи.
 *   <li><b>Държан за продажба и право на ползване</b> съществуват само по МСФО ({@link
 *       #IFRS_ONLY}): при дружество с рамка НСС (или без рамка) пазачът ги отказва.
 * </ul>
 */
public final class BgFixedAssetKinds {

  public static final String TANGIBLE = "TANGIBLE";
  public static final String INTANGIBLE = "INTANGIBLE";
  public static final String INVESTMENT_PROPERTY_COST = "INVESTMENT_PROPERTY_COST";
  public static final String INVESTMENT_PROPERTY_FAIR_VALUE = "INVESTMENT_PROPERTY_FAIR_VALUE";
  public static final String HELD_FOR_SALE = "HELD_FOR_SALE";
  public static final String RIGHT_OF_USE = "RIGHT_OF_USE";

  /** Всички стойности на селекцията {@code bg.fixed.asset.kind.select}. */
  public static final Set<String> ALL =
      set(
          TANGIBLE,
          INTANGIBLE,
          INVESTMENT_PROPERTY_COST,
          INVESTMENT_PROPERTY_FAIR_VALUE,
          HELD_FOR_SALE,
          RIGHT_OF_USE);

  /** Само при рамка МСФО: в НСС аналог няма (МСФО 5, МСФО 16). */
  public static final Set<String> IFRS_ONLY = set(HELD_FOR_SALE, RIGHT_OF_USE);

  /** В годишния SAF-T — ГРЕШКА „не се поддържа“ (A1–A6). */
  public static final Set<String> UNSUPPORTED =
      set(INVESTMENT_PROPERTY_FAIR_VALUE, HELD_FOR_SALE, RIGHT_OF_USE);

  private BgFixedAssetKinds() {}

  /**
   * Видът по подразбиране от кода на сметката на актива: 21x → НМА, иначе ДМА. Без сметка — {@code
   * null} (видът остава празен, докато сметката не се избере).
   */
  public static String defaultFor(String accountCode) {
    if (accountCode == null || accountCode.trim().isEmpty()) {
      return null;
    }
    return accountCode.trim().startsWith("21") ? INTANGIBLE : TANGIBLE;
  }

  /** Отказът за вид, който рамката на дружеството не допуска; {@code null} — позволено. */
  public static String refusal(String kind, String framework) {
    if (kind == null || !IFRS_ONLY.contains(kind)) {
      return null;
    }
    if (BgAccountingFramework.IFRS.equals(framework)) {
      return null;
    }
    return "Видът „"
        + (HELD_FOR_SALE.equals(kind)
            ? "държан за продажба (МСФО 5)"
            : "право на ползване (МСФО 16)")
        + "“ е само за дружество с рамка МСФО"
        + (framework == null ? " — рамката на дружеството не е зададена." : ", а рамката е НСС.");
  }

  private static Set<String> set(String... values) {
    Set<String> set = new LinkedHashSet<>();
    Collections.addAll(set, values);
    return Collections.unmodifiableSet(set);
  }
}
