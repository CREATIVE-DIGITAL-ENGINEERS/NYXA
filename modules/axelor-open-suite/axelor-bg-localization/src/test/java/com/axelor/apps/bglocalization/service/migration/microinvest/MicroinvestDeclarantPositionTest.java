package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Длъжността на внесения декларатор — наименованието на ролята в Microinvest, ако е 2–100 знака.
 */
class MicroinvestDeclarantPositionTest {

  @Test
  @DisplayName("„Управител“ влиза; празно, един знак и над 100 знака остават празни")
  void onlyAUsablePositionIsCarried() {
    assertEquals("Управител", MicroinvestPilotImportService.positionOf("  Управител "));
    assertNull(MicroinvestPilotImportService.positionOf(null));
    assertNull(MicroinvestPilotImportService.positionOf("   "));
    assertNull(MicroinvestPilotImportService.positionOf("У"));
    StringBuilder longName = new StringBuilder();
    for (int i = 0; i < 101; i++) {
      longName.append('а');
    }
    assertNull(MicroinvestPilotImportService.positionOf(longName.toString()));
  }
}
