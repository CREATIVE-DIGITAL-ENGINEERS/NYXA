package com.axelor.apps.bglocalization.service.vat.adapter;

/**
 * NAP purchase-diary (Дневник за покупките) value columns, <b>к.09–к.15 — exactly 7 columns</b>, in
 * the physical order of Приложение №12 към ППЗДДС (byte-verified against the accepted POKUPKI_1.txt
 * golden fixture: value area {@code [167:272]} = 7 × 15 chars). Unlike the sales diary, there is NO
 * summing (total) column — к.09 is reported directly.
 *
 * <ul>
 *   <li>к.09 (03-30) = ДО и данък на получените доставки, ВОП, получените доставки по чл.82 ал.2-6
 *       без право на данъчен кредит или без данък
 *   <li>к.10 (03-31) = ДО на получените доставки с право на ПЪЛЕН данъчен кредит
 *   <li>к.11 (03-41) = Начислен ДДС с право на пълен данъчен кредит
 *   <li>к.12 (03-32) = ДО на получените доставки с право на ЧАСТИЧЕН данъчен кредит
 *   <li>к.13 (03-42) = Начислен ДДС с право на частичен данъчен кредит
 *   <li>к.14 (03-43) = Годишна корекция по чл.73 ал.8 (може да е отрицателна)
 *   <li>к.15 (03-44) = ДО при придобиване на стоки от посредник в тристранна операция
 * </ul>
 */
public enum BgVatPurchaseColumn {
  K09_BASE_NO_CREDIT("09"),
  K10_BASE_FULL_CREDIT("10"),
  K11_VAT_FULL_CREDIT("11"),
  K12_BASE_PARTIAL_CREDIT("12"),
  K13_VAT_PARTIAL_CREDIT("13"),
  K14_ANNUAL_ADJUSTMENT("14"),
  K15_BASE_TRIANGULAR_ACQ("15");

  /** The NAP column label (кл. number) as printed in the journal, e.g. "09", "14". */
  private final String napCode;

  BgVatPurchaseColumn(String napCode) {
    this.napCode = napCode;
  }

  public String getNapCode() {
    return napCode;
  }
}
