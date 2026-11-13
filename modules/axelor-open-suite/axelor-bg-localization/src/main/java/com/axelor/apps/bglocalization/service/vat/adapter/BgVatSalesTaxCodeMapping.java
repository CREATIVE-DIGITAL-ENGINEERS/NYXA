package com.axelor.apps.bglocalization.service.vat.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a Bulgarian VAT tax code (as shipped in {@code l10n_bgr/BGR_COA/account_tax.csv}) to the NAP
 * sales-diary column(s) its base and VAT belong in.
 *
 * <p><b>Source of truth (NOT guessed).</b> Every entry here is derived from two vendored files:
 *
 * <ul>
 *   <li>{@code account_tax.csv} — the BG_* code catalogue and the {@code _S} (продажби / sales) vs
 *       {@code _P} (покупки / purchases) naming convention.
 *   <li>{@code account_accountingReportConfigLine.csv} report type {@code 42} — which base/VAT
 *       feeds which кл. of the sales journal (its {@code Sxx} formulae).
 * </ul>
 *
 * <p><b>PROVISIONAL — needs accountant/CTO sign-off before production filing.</b> The mapping below
 * covers the common regimes only. Codes intentionally left <b>unmapped</b> (special schemes:
 * margin, tourism, second-hand goods, investment gold, and the declaration-level adjustment
 * markers) are treated as a <b>blocking error</b> by the validator rather than being silently
 * placed in a column — so a document that needs a mapping decision cannot be mis-filed. Adding a
 * code here is the single, reviewable place to extend coverage.
 */
public final class BgVatSalesTaxCodeMapping {

  /** A base column plus an optional VAT column (null for 0-rated / no-VAT regimes). */
  public static final class ColumnTarget {
    private final BgVatSalesColumn baseColumn;
    private final BgVatSalesColumn vatColumn;

    private ColumnTarget(BgVatSalesColumn baseColumn, BgVatSalesColumn vatColumn) {
      this.baseColumn = baseColumn;
      this.vatColumn = vatColumn;
    }

    public BgVatSalesColumn getBaseColumn() {
      return baseColumn;
    }

    /** VAT column, or {@code null} when the regime carries no output VAT (ВОД, износ, ...). */
    public BgVatSalesColumn getVatColumn() {
      return vatColumn;
    }
  }

  private static final Map<String, ColumnTarget> MAPPING = buildMapping();

  private BgVatSalesTaxCodeMapping() {}

  private static Map<String, ColumnTarget> buildMapping() {
    Map<String, ColumnTarget> m = new LinkedHashMap<>();

    // Standard 20% domestic sales → к11 (base) / к12 (VAT).
    m.put("BG_SRS_20", target(BgVatSalesColumn.K11_BASE_20, BgVatSalesColumn.K12_VAT_20));

    // Reduced 9% sales (all sub-variants: general, books, baby goods, restaurant) → к17 / к18.
    m.put("BG_LRS_9", target(BgVatSalesColumn.K17_BASE_9, BgVatSalesColumn.K18_VAT_9));
    m.put("BG_LRS_9_BOOK", target(BgVatSalesColumn.K17_BASE_9, BgVatSalesColumn.K18_VAT_9));
    m.put("BG_LRS_9_BABY", target(BgVatSalesColumn.K17_BASE_9, BgVatSalesColumn.K18_VAT_9));
    m.put("BG_LRS_9_REST", target(BgVatSalesColumn.K17_BASE_9, BgVatSalesColumn.K18_VAT_9));

    // Intra-community acquisition, self-assessed (ВОП, чл.13) → base к13, VAT к15 (к.15 covers ВОП
    // and the к.14 received-supplies VAT together).
    m.put("BG_VOP_SELF", target(BgVatSalesColumn.K13_BASE_ICA, BgVatSalesColumn.K15_VAT_ICA_RC));

    // Received supplies self-charged under reverse charge → base к14, VAT к15 for BOTH articles.
    // The чл.82 vs чл.163а distinction is an identification code in к.8а, NOT a value column.
    m.put("BG_RCM_ART82_S", target(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("BG_RCM_BUILD_S", target(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("BG_RCM_SCRAP_S", target(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("BG_RCM_GRAIN_S", target(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("BG_RCM_MOBILE_S", target(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("BG_RCM_ENERGY_S", target(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));

    // Zero-VAT sales regimes (VAT column null):
    m.put("BG_VOD", target(BgVatSalesColumn.K20_BASE_ICS, null)); // ВОД (чл.7) → к20
    m.put(
        "BG_EU_S_RCM_S",
        target(BgVatSalesColumn.K22_BASE_EU_SERVICES, null)); // услуги към ЕС чл.21 ал.2 → к22
    // FLAG (accountant sign-off): export (чл.28) treated as 0%-rated under глава трета → к19.
    m.put("BG_EXP", target(BgVatSalesColumn.K19_BASE_ZERO_CH3, null));
    m.put("BG_EX_S", target(BgVatSalesColumn.K24_BASE_EXEMPT, null)); // освободени (чл.46-53) → к24
    // FLAG (accountant sign-off): services to a non-EU recipient reported under чл.69 ал.2 → к23.
    m.put("BG_NON_EU_SERV_S", target(BgVatSalesColumn.K23_BASE_ART69_2, null));
    // Продажби с нулева ставка → к.19 „ДО на доставките със ставка 0 % по глава трета от ЗДДС“.
    // Глава трета на ЗДДС (чл. 28–37) е „Освобождаване с право на приспадане на данъчен кредит“;
    // ВОД е чл. 53 (глава пета) и минава само през BG_VOD → к.20.
    m.put("BG_ZRS", target(BgVatSalesColumn.K19_BASE_ZERO_CH3, null));
    // OSS / MOSS: мястото на изпълнение е в друга държава членка — ЗДДС чл. 69 ал. 2 т. 1
    // („извършване на доставки в друга държава…“). к.23 „ДО на доставки по чл. 69, ал. 2 ЗДДС,
    // вкл. ДО на доставките при условията на дистанционни продажби с място на изпълнение на
    // територията на друга държава членка“. Данъкът по режима не е тук — той е в декларацията по
    // специалния режим; затова колона за данък няма.
    m.put("BG_OSS_EU", target(BgVatSalesColumn.K23_BASE_ART69_2, null));
    m.put("BG_MOSS", target(BgVatSalesColumn.K23_BASE_ART69_2, null));
    m.put(
        "BG_TRIANG_S",
        target(BgVatSalesColumn.K25_BASE_TRIANGULAR, null)); // тристранна посредник (чл.15) → к25

    return Collections.unmodifiableMap(m);
  }

  private static ColumnTarget target(BgVatSalesColumn base, BgVatSalesColumn vat) {
    return new ColumnTarget(base, vat);
  }

  /**
   * @return the column target for a tax code, or empty when the code is not (yet) mapped.
   */
  public static Optional<ColumnTarget> forTaxCode(String taxCode) {
    if (taxCode == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(MAPPING.get(taxCode.trim()));
  }

  public static boolean isMapped(String taxCode) {
    return forTaxCode(taxCode).isPresent();
  }

  /**
   * The „Доставка по чл.163а/чл.167а ЗДДС" code for поле 02-27: {@code "01"} (част I) or {@code
   * "02"} (част II) of Приложение №2 for чл.163а supplies, {@code ""} otherwise.
   *
   * <p><b>TODO (accountant/CTO sign-off):</b> there is no authoritative source yet mapping each
   * чл.163а BG_RCM_* code to Приложение №2 част I vs част II. Until confirmed this returns {@code
   * ""} for every code — byte-identical for ordinary sales — so the part-code is left blank rather
   * than guessed.
   */
  public static String art163aSupplyType(String taxCode) {
    return "";
  }

  /** True for any tax code this mapping recognises as belonging to the SALES journal. */
  public static boolean isSalesTaxCode(String taxCode) {
    return isMapped(taxCode);
  }
}
