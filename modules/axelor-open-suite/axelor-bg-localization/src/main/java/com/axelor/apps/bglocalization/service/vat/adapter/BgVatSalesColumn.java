package com.axelor.apps.bglocalization.service.vat.adapter;

/**
 * NAP sales-diary (Дневник за продажбите) value columns, <b>к.09–к.25 — exactly 17 columns</b>, in
 * the physical order of Приложение №10 към ППЗДДС (byte-verified against the accepted PRODAGBI.TXT
 * golden fixture: value area {@code [167:422]} = 17 × 15 chars).
 *
 * <p><b>There is a single к.16.</b> Reverse charge is NOT modelled here as two sub-columns — both
 * чл.82 ал.2-6 and чл.163а route to the same base (к.14) and VAT (к.15) columns; the чл.163а
 * distinction is an identification code in к.8а, not a value column. к.16 is self-supply (лични
 * нужди) VAT, a different thing.
 *
 * <ul>
 *   <li>к.09 = Общ размер на данъчните основи за облагане с ДДС (row total base)
 *   <li>к.10 = Всичко начислен ДДС (row total VAT)
 *   <li>к.11 = ДО на облагаемите доставки със ставка 20% (вкл. дистанционни продажби с място в
 *       страната); к.12 = начислен ДДС по к.11 + начислен данък 20% в други случаи
 *   <li>к.13 = ДО на ВОП; к.14 = ДО на получените доставки по чл.82 ал.2-6 (обратно начисляване);
 *       к.15 = начислен ДДС за ВОП и за доставките по к.14
 *   <li>к.16 = начислен данък за доставки за лични нужди (self-supply, 20%)
 *   <li>к.17 = ДО на облагаемите доставки със ставка 9%; к.18 = начислен ДДС по к.17
 *   <li>к.19 = ДО на доставки със ставка 0% по глава трета; к.20 = ДО на ВОД
 *   <li>к.21 = ДО на доставки по чл.140, чл.146 и чл.173; к.22 = ДО на услуги по чл.21 ал.2 с място
 *       на изпълнение в друга държава членка
 *   <li>к.23 = ДО на доставки по чл.69 ал.2 (вкл. дистанционни продажби с място в друга държава
 *       членка); к.24 = ДО на освободените доставки и освободените ВОП
 *   <li>к.25 = ДО на доставки като посредник в тристранни операции
 * </ul>
 *
 * <p>кл.01–кл.08 (sequence, document type, number, date, counterparty, subject) are identification
 * fields carried on {@link com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto}
 * directly, not here.
 */
public enum BgVatSalesColumn {
  K09_TOTAL_BASE("09"),
  K10_TOTAL_VAT("10"),
  K11_BASE_20("11"),
  K12_VAT_20("12"),
  K13_BASE_ICA("13"),
  K14_BASE_RC("14"),
  K15_VAT_ICA_RC("15"),
  K16_VAT_SELF_SUPPLY("16"),
  K17_BASE_9("17"),
  K18_VAT_9("18"),
  K19_BASE_ZERO_CH3("19"),
  K20_BASE_ICS("20"),
  K21_BASE_ART140_146_173("21"),
  K22_BASE_EU_SERVICES("22"),
  K23_BASE_ART69_2("23"),
  K24_BASE_EXEMPT("24"),
  K25_BASE_TRIANGULAR("25");

  /** The NAP column label (кл. number) as printed in the journal, e.g. "11", "16". */
  private final String napCode;

  BgVatSalesColumn(String napCode) {
    this.napCode = napCode;
  }

  public String getNapCode() {
    return napCode;
  }
}
