package com.axelor.apps.napsubmissions.common.immutability;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Решението на проверката при старт по колоните на таблицата (T1). Срещу истинска PostgreSQL —
 * {@code BgSaftReportMigrationDbTest} в bg-localization, заедно с файла за пайплайна.
 */
class SaftReportSchemaCheckTest {

  @Test
  @DisplayName("Всичките пет колони — стартът минава")
  void всички() {
    Set<String> present = new HashSet<>(SaftReportSchemaCheck.COLUMNS);
    present.add("id");
    assertNull(SaftReportSchemaCheck.refusal(present));
  }

  @Test
  @DisplayName("Липсва колона — отказ с имената ѝ и с файла за пайплайна")
  void липсва() {
    Set<String> present = new HashSet<>(SaftReportSchemaCheck.COLUMNS);
    present.remove("nap_request_number");
    present.remove("report_year");
    String refusal = SaftReportSchemaCheck.refusal(present);
    assertTrue(refusal.contains("report_year, nap_request_number"), refusal);
    assertTrue(refusal.contains(SaftReportSchemaCheck.MIGRATION_FILE), refusal);
    assertTrue(refusal.contains("срещу базата"), refusal);
  }
}
