package com.axelor.apps.napsubmissions.common.immutability;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ПРОВЕРКА ПРИ СТАРТ, САМО ЧЕТЕНЕ: пуснат ли е файлът с колоните на годишния SAF-T и SAF-T при
 * поискване (T1).
 *
 * <p>Приложението не променя схемата (решение от 19.10.2026) — колоните на {@code SaftReport} за
 * годината, датите и искането на НАП идват от {@link #MIGRATION_FILE} през временния пайплайн за
 * писане. Без тях всяко четене на отчет гърми, тоест новата версия не бива да тръгне: {@code
 * NapSubmissionsIntegrator} вика проверката при създаването на Hibernate и при отказ спира
 * приложението с името на файла.
 *
 * <p>Само PostgreSQL; таблица още няма (нова база) — няма какво да се проверява: колоните ги прави
 * първото създаване на таблицата.
 */
public final class SaftReportSchemaCheck {

  public static final String MIGRATION_FILE =
      "modules/axelor-open-suite/axelor-bg-localization/src/main/resources/db/migration/"
          + "2026-10-21-saft-report-annual-ondemand.sql";

  public static final String TABLE = "napsubmissions_saft_report";

  /** Колоните на T1 — в реда на домейна. */
  public static final List<String> COLUMNS =
      Collections.unmodifiableList(
          Arrays.asList(
              "report_year", "from_date", "to_date", "nap_request_number", "nap_request_date"));

  static final String TABLE_SQL = "SELECT to_regclass('" + TABLE + "') IS NOT NULL";

  static final String COLUMNS_SQL =
      "SELECT attname FROM pg_attribute WHERE attrelid = to_regclass('"
          + TABLE
          + "') AND attnum > 0 AND NOT attisdropped";

  private SaftReportSchemaCheck() {}

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
    Set<String> present = new HashSet<>();
    try (PreparedStatement ps = connection.prepareStatement(COLUMNS_SQL);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        present.add(rs.getString(1));
      }
    }
    return refusal(present);
  }

  /** Решението по колоните, които таблицата има. */
  static String refusal(Set<String> present) {
    List<String> missing = new ArrayList<>();
    for (String column : COLUMNS) {
      if (!present.contains(column)) {
        missing.add(column);
      }
    }
    if (missing.isEmpty()) {
      return null;
    }
    return TABLE
        + " няма колоните "
        + String.join(", ", missing)
        + " (годишният SAF-T и SAF-T при поискване). Приложението не променя схемата: пуснете "
        + MIGRATION_FILE
        + " срещу базата и стартирайте отново.";
  }
}
