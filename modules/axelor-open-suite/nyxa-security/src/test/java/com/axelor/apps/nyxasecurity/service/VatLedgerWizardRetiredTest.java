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
 * "Генериране на ДДС дневници (NAP)" is removed (2026-10-26): the BgVatReturnWizard model is gone
 * and its menu stays only as a hidden tombstone in axelor-bg-localization's Menu.xml. The catalogue
 * grants neither, and the grants existing databases still hold are retired.
 */
class VatLedgerWizardRetiredTest {

  private static final String MENU = "bg-compliance-vat-export";
  private static final String[] ROLES = {
    "accounting.nap.read", "accounting.nap.write", "all.read", "all.write", "client.admin"
  };

  @Test
  void noRoleOpensTheMenu() throws IOException {
    String menus = read("nx_menu.csv");
    String retired = read("nx_retired.csv");
    List<Executable> checks = new ArrayList<>();
    checks.add(
        () ->
            assertFalse(
                menus.contains("\"" + MENU + "\";"), MENU + " is still granted in nx_menu.csv"));
    for (String role : ROLES) {
      String line = "\"role-menu\";\"" + role + "\";\"" + MENU + "\"";
      checks.add(() -> assertTrue(retired.contains(line), "missing in nx_retired.csv: " + line));
    }
    assertAll(checks);
  }

  @Test
  void noPermissionOnTheRemovedModel() throws IOException {
    String permissions = read("nx_permission.csv");
    String retired = read("nx_retired.csv");
    List<Executable> checks = new ArrayList<>();
    checks.add(
        () ->
            assertFalse(
                permissions.contains("BgVatReturnWizard"),
                "BgVatReturnWizard is still granted in nx_permission.csv"));
    for (String role : ROLES) {
      String line = "\"permission\";\"nx." + role + ".bgVatReturnWizard\";\"\"";
      checks.add(() -> assertTrue(retired.contains(line), "missing in nx_retired.csv: " + line));
    }
    assertAll(checks);
  }

  private static String read(String file) throws IOException {
    try (InputStream in =
        VatLedgerWizardRetiredTest.class
            .getClassLoader()
            .getResourceAsStream(RoleCatalogueImporter.RESOURCE_DIR + file)) {
      assertNotNull(in, "missing resource " + file);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
