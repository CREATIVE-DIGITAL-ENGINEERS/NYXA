package com.axelor.apps.bglocalization.service.vat.adapter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ПОКУПНИ ДАНЪЦИ С НЕПОДВИЖНА КОЛОНА — чиста функция. За тях колоната на дневника за покупки идва
 * от данъка на реда, а не от кредитния му тип ({@link BgVatPurchaseCreditTypeMapping}).
 *
 * <p>Заглавията са по ППЗДДС Прил. № 11:
 *
 * <ul>
 *   <li><b>BG_EX_P</b> (освободени, чл. 46–53) и <b>BG_ZRP</b> (0 %) → к.9 „ДО и данък на
 *       получените доставки, ВОП, получените доставки по чл. 82, ал. 2 - 6 от ЗДДС и вносът без
 *       право на данъчен кредит или без данък“. Доставка без данък няма какво да даде като кредит,
 *       тоест к.10 „… с право на пълен данъчен кредит“ не е нейна, каквото и да пише на реда.
 *       Същото дава и SAF-T: 100330 (QA с. 81 — „фактури без начислен ДДС“).
 *   <li><b>BG_TRIANG_P</b> (придобиване от посредник по чл. 15) → к.15 „ДО от придобиване на стоки
 *       от посредник в тристранна операция“; бел. 5: „…не участва при определянето на резултата за
 *       периода“. Данъкът е изискуем от придобиващия (ЗДДС чл. 82 ал. 3) и се начислява с протокол
 *       по чл. 117 ал. 1 т. 1 — отделен документ. SAF-T: 100344.
 * </ul>
 *
 * <p>Изрично въведен кредитен тип, който противоречи на колоната, не я мени — колекторът
 * предупреждава ({@link #agreesWith}).
 */
public final class BgVatPurchaseTaxCodeMapping {

  private static final Map<String, BgVatPurchaseTarget> FIXED = buildFixed();

  private BgVatPurchaseTaxCodeMapping() {}

  private static Map<String, BgVatPurchaseTarget> buildFixed() {
    Map<String, BgVatPurchaseTarget> m = new LinkedHashMap<>();
    m.put("BG_EX_P", BgVatPurchaseTarget.NO_CREDIT);
    m.put("BG_ZRP", BgVatPurchaseTarget.NO_CREDIT);
    m.put("BG_TRIANG_P", BgVatPurchaseTarget.TRIANGULAR);
    return Collections.unmodifiableMap(m);
  }

  /** The column for {@code taxCode} (trimmed), or empty when the credit type decides. */
  public static Optional<BgVatPurchaseTarget> forTaxCode(String taxCode) {
    return Optional.ofNullable(taxCode == null ? null : FIXED.get(taxCode.trim()));
  }

  /** The column of the first fixed tax among {@code taxCodes}, or empty when none is fixed. */
  public static Optional<BgVatPurchaseTarget> forTaxCodes(Collection<String> taxCodes) {
    if (taxCodes != null) {
      for (String taxCode : taxCodes) {
        Optional<BgVatPurchaseTarget> target = forTaxCode(taxCode);
        if (target.isPresent()) {
          return target;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Does an EXPLICIT credit type say the same as the fixed column? „3“ (без право на кредит) is
   * к.9; к.15 is none of the credit columns, so no credit type agrees with it. Blank always agrees
   * — the tax decides and nothing was defaulted.
   */
  public static boolean agreesWith(BgVatPurchaseTarget target, String creditType) {
    if (BgVatPurchaseCreditTypeMapping.isDefaulted(creditType)) {
      return true;
    }
    return target == BgVatPurchaseTarget.NO_CREDIT
        && BgVatPurchaseCreditTypeMapping.CREDIT_NONE.equals(creditType.trim());
  }

  /** The tax codes this mapping fixes. */
  public static Set<String> taxCodes() {
    return FIXED.keySet();
  }
}
