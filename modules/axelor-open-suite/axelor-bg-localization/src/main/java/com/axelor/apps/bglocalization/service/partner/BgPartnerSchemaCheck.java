package com.axelor.apps.bglocalization.service.partner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * ПРОВЕРКА ПРИ СТАРТ, САМО ЧЕТЕНЕ: пуснат ли е файлът, който сменя уникалността на номера.
 *
 * <p>Приложението не променя схемата — глобалното {@code UNIQUE (partner_seq)} на axelor-base се
 * маха от {@link #MIGRATION_FILE}. Докато то е там, второто дружество получава отказ от базата за
 * първия си номер (P0001 вече е даден в първото), тоест новата версия не бива да тръгне: {@link
 * BgPartnerIntegrator} вика проверката при създаването на Hibernate и при отказ спира приложението
 * с името на файла.
 *
 * <p>Глобалната уникалност се търси по колоните: уникален индекс точно върху {@code partner_seq}
 * без условие (така изглеждат и ограничението на Hibernate, и самостоятелен уникален индекс). Само
 * PostgreSQL; таблица още няма (нова база) — няма какво да се проверява.
 */
public final class BgPartnerSchemaCheck {

  public static final String MIGRATION_FILE =
      "modules/axelor-open-suite/axelor-bg-localization/src/main/resources/db/migration/"
          + "2026-10-20-partner-seq-per-company.sql";

  static final String GLOBAL_UNIQUE_SQL =
      "SELECT count(*) FROM pg_index i"
          + " WHERE i.indrelid = to_regclass('base_partner')"
          + " AND i.indisunique AND i.indpred IS NULL AND i.indnatts = 1"
          + " AND i.indkey[0] = (SELECT attnum FROM pg_attribute"
          + "   WHERE attrelid = to_regclass('base_partner') AND attname = 'partner_seq')";

  private BgPartnerSchemaCheck() {}

  /** Отказът, ако схемата не е готова; {@code null} — готова или не е PostgreSQL. */
  public static String refusal(Connection connection) throws SQLException {
    if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")) {
      return null;
    }
    try (PreparedStatement ps = connection.prepareStatement(GLOBAL_UNIQUE_SQL);
        ResultSet rs = ps.executeQuery()) {
      return rs.next() ? refusal(rs.getLong(1)) : null;
    }
  }

  /** Решението по броя на глобалните уникалности на {@code partner_seq}. */
  static String refusal(long globalUniqueCount) {
    if (globalUniqueCount == 0) {
      return null;
    }
    return "base_partner още има глобалната уникалност на partner_seq ("
        + globalUniqueCount
        + "), а номерът на партньора вече е уникален по дружество. Приложението не променя схемата:"
        + " пуснете "
        + MIGRATION_FILE
        + " срещу базата и стартирайте отново.";
  }
}
