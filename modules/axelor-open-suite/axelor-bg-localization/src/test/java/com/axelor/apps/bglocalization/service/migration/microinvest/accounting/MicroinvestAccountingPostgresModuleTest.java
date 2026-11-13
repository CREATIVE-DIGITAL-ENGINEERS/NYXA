package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Условието, което пропуска {@link MicroinvestAccountingLedgerTest} без тестова PostgreSQL, чете
 * САМИЯ persistence unit „testPostgres“ — не дублира адреса (BgTestDbTest забранява адрес на база в
 * тестов код). Без база, без Guice.
 */
class MicroinvestAccountingPostgresModuleTest {

  @Test
  @DisplayName("Условието чете връзката на „testPostgres“, а не на HSQLDB unit-а „testUnit“")
  void theConditionReadsTheTestPostgresUnit() throws Exception {
    String[] postgres =
        MicroinvestAccountingPostgresModule.connectionOf(MicroinvestAccountingPostgresModule.UNIT);
    String[] hsqldb = MicroinvestAccountingPostgresModule.connectionOf("testUnit");

    assertNotNull(postgres, "persistence unit „testPostgres“ не е намерен");
    assertNotNull(postgres[0], "„testPostgres“ няма URL");
    assertEquals("nyxa", postgres[1]);
    assertNotNull(hsqldb, "контролът „testUnit“ не е намерен");
    assertNotEquals(hsqldb[0], postgres[0], "блокът на unit-а е сбъркан");
    assertNull(MicroinvestAccountingPostgresModule.connectionOf("няма-такъв"));
  }
}
