package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import java.util.Locale;

/**
 * The single place that defines what a Microinvest-sourced {@code importId} looks like.
 *
 * <p>⚠️ {@code importId} carries a UNIQUE index that is GLOBAL PER TABLE, not scoped by company. In
 * this database that is {@code uk_j0lhx8qhp90jced8gg52x49fb} on {@code account_move}, {@code
 * uk_a5hqs0vxahsty91d3ad6e6clw} on {@code account_move_line} and {@code
 * uk_7g6wmbcwxu6q898utc275fisj} on {@code base_partner} — the constraint comes from
 * {@code @Column(unique = true)} on {@code Model.importId}, declared in a jar-shipped {@code
 * Model.xml} (NOT in the {@code modules/axelor-open-platform} checkout, which is excluded from the
 * build and has no importId at all).
 *
 * <p>So a bare source id is unusable: {@code AccountingID 29944} exists in every Delta Pro database
 * ever created, and two client companies migrating in the same Nyxa instance would collide on the
 * first row. The key therefore carries the whole coordinate — target company, source database,
 * object type, source id — and the discriminating parts come BEFORE the id:
 *
 * <pre>mi:&lt;companyEik&gt;:&lt;sourceDatabase&gt;:&lt;type&gt;:&lt;sourceId&gt;</pre>
 *
 * e.g. {@code mi:100000065:DeltaPro_00000000000000:acc:29944} — about 50 of the 255 available
 * characters.
 *
 * <p>The prefix without the type is also the "is this row mine?" test used to tell an earlier run
 * of this importer apart from a manually entered move ({@link #prefix}).
 */
public final class MicroinvestAccountingSourceKey {

  /** Accounting entry -> Move. */
  public static final String TYPE_ENTRY = "acc";

  /** Accounting detail -> MoveLine. */
  public static final String TYPE_LINE = "accd";

  /** Opening-balance move, one per financial year. */
  public static final String TYPE_OPENING = "ob";

  /** Partner. */
  public static final String TYPE_PARTNER = "prt";

  /** Reconciliation of a settlement row. */
  public static final String TYPE_SETTLEMENT = "setl";

  /** Standard FixedAsset sourced from the Microinvest register. */
  public static final String TYPE_ASSET = "asset";

  /** Standard FixedAssetLine sourced from a Microinvest depreciation schedule. */
  public static final String TYPE_ASSET_LINE = "assetl";

  private MicroinvestAccountingSourceKey() {}

  /**
   * The company/database prefix every key produced by this importer starts with. A Move whose
   * importId does not start with this was not written by this importer against this source — which
   * is exactly what the duplicate-ledger guard needs to know.
   */
  public static String prefix(String companyEik, String sourceDatabase) {
    // The EIK goes through the SAME normalization the company guard matched on, so a cosmetic
    // difference in how it was typed ("BG100000065" vs "100 000 065") can never produce a second,
    // parallel set of keys for one company.
    String eik = MicroinvestAccountingCompanyGuard.normalizeBulstat(companyEik);
    return "mi:" + (eik == null ? "" : eik) + ":" + normalizeDatabase(sourceDatabase) + ":";
  }

  public static String of(String companyEik, String sourceDatabase, String type, Object sourceId) {
    return prefix(companyEik, sourceDatabase) + type + ":" + sourceId;
  }

  private static String normalizeDatabase(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
  }
}
