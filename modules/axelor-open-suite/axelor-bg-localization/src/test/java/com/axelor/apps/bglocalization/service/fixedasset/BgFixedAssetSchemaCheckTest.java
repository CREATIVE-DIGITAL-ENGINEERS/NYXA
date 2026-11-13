package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Решението на проверката при старт (SAF-T A1). Срещу истинска PostgreSQL — {@code
 * BgFixedAssetInventoryNumberMigrationDbTest}, заедно с файла за пайплайна.
 */
class BgFixedAssetSchemaCheckTest {

  @Test
  @DisplayName("Колоните и уникалността са налице — стартът минава")
  void готово() {
    assertNull(BgFixedAssetSchemaCheck.refusal(List.of(), true));
  }

  @Test
  @DisplayName("Липсва колона или уникалността — отказ с имената и с файла за пайплайна")
  void липсва() {
    String columns =
        BgFixedAssetSchemaCheck.refusal(List.of("account_fixed_asset.bg_split_from"), true);
    assertTrue(columns.contains("account_fixed_asset.bg_split_from"), columns);
    assertTrue(columns.contains(BgFixedAssetSchemaCheck.MIGRATION_FILE), columns);
    assertTrue(columns.contains("срещу базата"), columns);
    String unique = BgFixedAssetSchemaCheck.refusal(List.of(), false);
    assertTrue(unique.contains("уникалност (company, fixed_asset_seq)"), unique);
  }
}
