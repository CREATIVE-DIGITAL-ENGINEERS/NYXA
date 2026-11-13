package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The French FEC import is removed, not fixed : it picks the target company from the file name and
 * falls back to the first company in the database. No role may open it, import with it or edit its
 * import types; the import record stays readable where a move links to it.
 */
class FecImportRetiredTest {

  private static final String[] MENUS = {"fec-import", "account-conf-import-fec-type"};
  private static final String[] ROLES = {
    "accounting.read", "accounting.write", "all.read", "all.write", "client.admin"
  };

  @Test
  void noRoleOpensTheFecMenus() throws IOException {
    String menus = read("nx_menu.csv");
    String retired = read("nx_retired.csv");
    List<Executable> checks = new ArrayList<>();
    for (String menu : MENUS) {
      checks.add(
          () ->
              assertFalse(
                  menus.contains("\"" + menu + "\";"), menu + " is still granted in nx_menu.csv"));
      for (String role : ROLES) {
        String line = "\"role-menu\";\"" + role + "\";\"" + menu + "\"";
        checks.add(() -> assertTrue(retired.contains(line), "missing in nx_retired.csv: " + line));
      }
    }
    assertAll(checks);
  }

  @Test
  void noRoleImportsOrEditsImportTypes() throws IOException {
    List<Executable> checks = new ArrayList<>();
    for (String line : read("nx_permission.csv").split("\n")) {
      checks.add(
          () ->
              assertFalse(
                  line.contains("\"com.axelor.apps.account.db.ImportFECType\""),
                  "import types still granted: " + line));
      if (line.contains("\"com.axelor.apps.account.db.FECImport\"")) {
        String[] cells = line.split(";", -1);
        // name; object; read; write; create; remove; export; ...
        checks.add(
            () ->
                assertTrue(
                    cells[3].isEmpty() && cells[4].isEmpty() && cells[5].isEmpty(),
                    "FEC import must be read-only: " + line));
      }
    }
    String retired = read("nx_retired.csv");
    for (String role : ROLES) {
      String line = "\"permission\";\"nx." + role + ".importFECType\";\"\"";
      checks.add(() -> assertTrue(retired.contains(line), "missing in nx_retired.csv: " + line));
    }
    assertAll(checks);
  }

  private static String read(String file) throws IOException {
    try (InputStream in =
        FecImportRetiredTest.class
            .getClassLoader()
            .getResourceAsStream(RoleCatalogueImporter.RESOURCE_DIR + file)) {
      assertNotNull(in, "missing resource " + file);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
