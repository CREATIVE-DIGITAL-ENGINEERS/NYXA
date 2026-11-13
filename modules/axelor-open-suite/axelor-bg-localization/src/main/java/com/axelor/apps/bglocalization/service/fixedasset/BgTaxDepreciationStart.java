package com.axelor.apps.bglocalization.service.fixedasset;

/**
 * Стойностите на {@code FixedAsset.bgTaxStartSelect} — двете възможности по чл. 58 ал. 1 ЗКПО.
 *
 * <p>Огледални на {@code bg.tax.depreciation.start.select} в {@code views/Selects.xml}. Държат се
 * като константи, а не като низове по кода, по същия довод, по който базата държи {@code
 * FixedAssetRepository.DEPRECIATION_PLAN_*}: сгрешен низ не се обажда при компилация и планът
 * просто тръгва от грешния месец.
 */
public final class BgTaxDepreciationStart {

  /** „От началото на месеца, в който активът е въведен в експлоатация“ — по подразбиране. */
  public static final String SERVICE_MONTH = "SERVICE_MONTH";

  /** „От началото на месеца, следващ месеца на въвеждане в експлоатация“. */
  public static final String NEXT_MONTH = "NEXT_MONTH";

  private BgTaxDepreciationStart() {}
}
