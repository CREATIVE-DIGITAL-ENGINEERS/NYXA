package com.axelor.apps.bglocalization.service.payroll;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ПРОВЕРКА ПРИ СТАРТ, САМО ЧЕТЕНЕ: пуснат ли е файлът, който прави номера на фиша и на
 * обезщетението уникален в дружеството.
 *
 * <p>Приложението не променя схемата — колоната {@code company} и свалянето на глобалното уникално
 * по {@code reference} идват от миграцията {@link #MIGRATION_FILE}. Докато файлът не е пуснат,
 * новата версия НЕ БИВА да тръгне:
 *
 * <ul>
 *   <li>без колоната всеки запис на фиш или обезщетение пада на SQL ниво, далеч от причината;
 *   <li>с глобалното уникално второто дружество получава отказ от базата за номер, който първото
 *       вече е дало (заварените номера са от една обща редица — разпръснати между дружествата).
 * </ul>
 *
 * <p>{@link BgTrzIntegrator} вика проверката при създаването на Hibernate — преди JPA, тоест преди
 * {@code StartupEvent} и стъпката {@link BgTrzSequenceBackfill} — и при отказ спира приложението с
 * името на файла.
 *
 * <p>Чете през {@link DatabaseMetaData}, а не през {@code pg_catalog}: същата проверка върви и на
 * HSQLDB, където свидетелите я мерят. Таблица още няма (нова база) — няма какво да се проверява:
 * схемата ще дойде от домейна вече с (company, reference). Глобално уникално = уникален индекс
 * точно върху {@code reference} без условие (така изглеждат и ограничението на Hibernate, и
 * самостоятелен уникален индекс).
 */
public final class BgTrzSchemaCheck {

  public static final String MIGRATION_FILE =
      "modules/axelor-open-suite/axelor-bg-localization/src/main/resources/db/migration/"
          + "2026-10-21-trz-numbering-per-company.sql";

  /** Таблиците на номерираните документи. */
  static final List<String> TABLES =
      List.of("bglocalization_bg_pay_slip", "bglocalization_bg_compensation");

  private BgTrzSchemaCheck() {}

  /** Състоянието на една таблица. */
  static final class TableState {
    final boolean exists;
    final boolean hasCompany;
    final int globalUniqueOnReference;

    TableState(boolean exists, boolean hasCompany, int globalUniqueOnReference) {
      this.exists = exists;
      this.hasCompany = hasCompany;
      this.globalUniqueOnReference = globalUniqueOnReference;
    }
  }

  /** Отказът, ако схемата не е готова; {@code null} — готова или още няма таблици. */
  public static String refusal(Connection connection) throws SQLException {
    DatabaseMetaData md = connection.getMetaData();
    Map<String, TableState> states = new LinkedHashMap<>();
    for (String table : TABLES) {
      states.put(table, state(md, table));
    }
    return refusal(states);
  }

  /** Решението по състоянието на таблиците. */
  static String refusal(Map<String, TableState> states) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, TableState> e : states.entrySet()) {
      TableState s = e.getValue();
      if (!s.exists) {
        continue;
      }
      if (!s.hasCompany) {
        problems.add(e.getKey() + " няма колона company");
      }
      if (s.globalUniqueOnReference > 0) {
        problems.add(
            e.getKey()
                + " още има глобалното уникално по reference ("
                + s.globalUniqueOnReference
                + ")");
      }
    }
    if (problems.isEmpty()) {
      return null;
    }
    return "Номерът на фиша и на обезщетението е уникален в дружеството, а схемата още не е:"
        + " "
        + String.join("; ", problems)
        + ". Приложението не променя схемата: пуснете "
        + MIGRATION_FILE
        + " срещу базата и стартирайте отново.";
  }

  private static TableState state(DatabaseMetaData md, String table) throws SQLException {
    String name = actualName(md, table);
    if (name == null) {
      return new TableState(false, false, 0);
    }
    boolean hasCompany = false;
    try (ResultSet columns = md.getColumns(null, null, name, null)) {
      while (columns.next()) {
        if ("company".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasCompany = true;
        }
      }
    }
    Map<String, List<String>> uniqueIndexes = new LinkedHashMap<>();
    Map<String, Boolean> partial = new LinkedHashMap<>();
    try (ResultSet indexes = md.getIndexInfo(null, null, name, true, false)) {
      while (indexes.next()) {
        String index = indexes.getString("INDEX_NAME");
        String column = indexes.getString("COLUMN_NAME");
        if (index == null || column == null) {
          continue;
        }
        uniqueIndexes.computeIfAbsent(index, k -> new ArrayList<>()).add(column);
        if (indexes.getString("FILTER_CONDITION") != null) {
          partial.put(index, true);
        }
      }
    }
    int global = 0;
    for (Map.Entry<String, List<String>> index : uniqueIndexes.entrySet()) {
      List<String> cols = index.getValue();
      if (cols.size() == 1
          && "reference".equalsIgnoreCase(cols.get(0))
          && !partial.containsKey(index.getKey())) {
        global++;
      }
    }
    return new TableState(true, hasCompany, global);
  }

  /** Името, под което таблицата я има: PostgreSQL пази малки букви, HSQLDB — главни. */
  private static String actualName(DatabaseMetaData md, String table) throws SQLException {
    for (String candidate : new String[] {table, table.toUpperCase()}) {
      try (ResultSet tables = md.getTables(null, null, candidate, new String[] {"TABLE"})) {
        if (tables.next()) {
          return tables.getString("TABLE_NAME");
        }
      }
    }
    return null;
  }
}
