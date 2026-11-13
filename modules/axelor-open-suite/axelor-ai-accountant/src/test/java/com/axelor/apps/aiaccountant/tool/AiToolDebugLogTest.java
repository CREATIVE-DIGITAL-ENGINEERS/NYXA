package com.axelor.apps.aiaccountant.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiToolDebugLogTest {

  @Test
  void theMessageIsNeverLoggedOnlyItsLength() {
    String described = AiToolDebugLog.describeMessage("създай доставчик ЕИК 130159785 IBAN BG80");
    assertFalse(described.contains("доставчик"));
    assertTrue(described.contains("chars"));
  }

  @Test
  void argumentsShowIdentifiersAndOnlyNameTheRest() {
    String summary =
        AiToolDebugLog.summariseArguments(
            "{\"operation\":\"create\",\"eik\":\"130159785\",\"name\":\"SAP ЕООД\","
                + "\"bankAccount\":{\"iban\":\"BG80BNBG96611020345678\"},\"fixedPhone\":\"0888123456\"}");
    assertTrue(summary.contains("operation=create"));
    assertTrue(summary.contains("eik=130159785"));
    assertTrue(summary.contains("bankAccount"));
    assertFalse(summary.contains("BG80"));
    assertFalse(summary.contains("0888123456"));
    assertFalse(summary.contains("SAP"));
  }

  @Test
  void resultsKeepTheOutcomeAndMaskBankDetails() {
    String error =
        AiToolDebugLog.summariseResult(
            "{\"error\":\"IBAN BG80BNBG96611020345678 is held by a@b.bg\",\"existingSupplierId\":7}");
    assertTrue(error.contains("existingSupplierId=7"));
    assertTrue(error.contains("[IBAN]"));
    assertFalse(error.contains("BG80BNBG"));
    assertFalse(error.contains("a@b.bg"));

    String ok =
        AiToolDebugLog.summariseResult(
            "{\"created\":true,\"supplierId\":9,\"verified\":false,"
                + "\"verificationProblems\":[\"the IBAN BG80BNBG96611020345678 is missing\"],"
                + "\"supplier\":{\"name\":\"x\"},\"changes\":[\"phone 0888123456\"]}");
    assertTrue(ok.contains("created=true"));
    assertTrue(ok.contains("verified=false"));
    assertFalse(ok.contains("BG80BNBG"));
    assertFalse(ok.contains("0888123456"));
  }

  @Test
  void theFileRollsOnceAtItsCapSoAtMostTwoFilesExist(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("debug.txt");
    Method append =
        AiToolDebugLog.class.getDeclaredMethod("append", String.class, int.class, String.class);
    append.setAccessible(true);
    String record = "x".repeat(60) + "\n";

    append.invoke(null, file.toString(), 100, record);
    append.invoke(null, file.toString(), 100, record);
    assertFalse(Files.exists(dir.resolve("debug.txt.1")), "still under the cap: no roll yet");

    // Over the cap now, so the next record starts a fresh file and the full one becomes .1.
    append.invoke(null, file.toString(), 100, "second\n");
    assertEquals(2 * record.length(), Files.size(dir.resolve("debug.txt.1")));
    assertEquals("second\n", Files.readString(file));

    for (int i = 0; i < 5; i++) {
      append.invoke(null, file.toString(), 100, record);
    }
    try (Stream<Path> files = Files.list(dir)) {
      assertEquals(2, files.count(), "a roll replaces .1, it never adds a third file");
    }
  }
}
