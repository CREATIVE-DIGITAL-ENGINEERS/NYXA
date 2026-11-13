package com.axelor.apps.bglocalization.service.fixedasset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * ПРОВЕРКА ПРИ СТАРТ, САМО ЧЕТЕНЕ: пуснат ли е файлът на инвентарния номер (SAF-T A1).
 *
 * <p>Приложението не променя схемата — колоните {@code account_fixed_asset.bg_asset_kind_select},
 * {@code account_fixed_asset.bg_split_from}, {@code
 * account_asset_disposal_reason.bg_nap_movement_code} и частичната уникалност на (дружество,
 * инвентарен номер) идват от {@link #MIGRATION_FILE}. Без тях четенето на актив гърми, а
 * уникалността не е гарантирана, тоест новата версия не бива да тръгне: {@link
 * BgFixedAssetIntegrator} спира приложението с името на файла.
 *
 * <p><b>Чиста база</b> (схемата идва от домейна, файловете от {@code db/migration} не се пускат).
 * Първият старт не намира таблица — няма какво да се проверява. {@code ddl=update} създава колоните
 * и уникалността от домейна ({@code <unique-constraint columns="company, fixedAssetSeq"/>}), тоест
 * всеки следващ старт намира всичко. Измерено с истински Hibernate в {@code
 * BgFixedAssetCleanSchemaDbTest}.
 *
 * <p>Уникалността се търси по колоните: уникален индекс точно върху (company, fixed_asset_seq) —
 * ограничението на Hibernate или файла (едно и също име), а и самостоятелен индекс. Само
 * PostgreSQL.
 */
public final class BgFixedAssetSchemaCheck {

  public static final String MIGRATION_FILE =
      "modules/axelor-open-suite/axelor-bg-localization/src/main/resources/db/migration/"
          + "2026-10-23-fixed-asset-inventory-number.sql";

  /** Колоните, които новият код чете: (таблица, колона). */
  public static final List<List<String>> COLUMNS =
      List.of(
          List.of("account_fixed_asset", "bg_asset_kind_select"),
          List.of("account_fixed_asset", "bg_split_from"),
          List.of("account_asset_disposal_reason", "bg_nap_movement_code"));

  /** Уникалността на инвентарния номер — тези колони, без значение от реда. */
  public static final List<String> UNIQUE_COLUMNS = List.of("company", "fixed_asset_seq");

  static final String TABLE_SQL = "SELECT to_regclass('account_fixed_asset') IS NOT NULL";

  static final String COLUMN_SQL =
      "SELECT count(*) FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = ?"
          + " AND attnum > 0 AND NOT attisdropped";

  /** Уникален индекс точно върху (company, fixed_asset_seq). */
  static final String UNIQUE_SQL =
      "SELECT count(*) FROM pg_index i"
          + " WHERE i.indrelid = to_regclass('account_fixed_asset') AND i.indisunique"
          + " AND i.indnatts = 2"
          + " AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM unnest(i.indkey) k"
          + "      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k)"
          + "     = ARRAY['company', 'fixed_asset_seq']";

  private BgFixedAssetSchemaCheck() {}

  /** Отказът, ако схемата не е готова; {@code null} — готова, без таблица или не е PostgreSQL. */
  public static String refusal(Connection connection) throws SQLException {
    if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")) {
      return null;
    }
    try (PreparedStatement ps = connection.prepareStatement(TABLE_SQL);
        ResultSet rs = ps.executeQuery()) {
      if (!rs.next() || !rs.getBoolean(1)) {
        return null;
      }
    }
    List<String> missing = new ArrayList<>();
    for (List<String> tableColumn : COLUMNS) {
      column(connection, tableColumn.get(0), tableColumn.get(1), missing);
    }
    boolean unique;
    try (PreparedStatement ps = connection.prepareStatement(UNIQUE_SQL);
        ResultSet rs = ps.executeQuery()) {
      unique = rs.next() && rs.getLong(1) > 0;
    }
    return refusal(missing, unique);
  }

  /** Решението по липсващите колони и уникалността. */
  static String refusal(List<String> missingColumns, boolean unique) {
    if (missingColumns.isEmpty() && unique) {
      return null;
    }
    List<String> missing = new ArrayList<>(missingColumns);
    if (!unique) {
      missing.add("уникалност (company, fixed_asset_seq)");
    }
    return "Активите (SAF-T A1): в базата липсва "
        + String.join(", ", missing)
        + ". Приложението не променя схемата: пуснете "
        + MIGRATION_FILE
        + " срещу базата и стартирайте отново.";
  }

  private static void column(Connection c, String table, String column, List<String> missing)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(COLUMN_SQL)) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next() || rs.getLong(1) == 0) {
          missing.add(table + "." + column);
        }
      }
    }
  }
}
