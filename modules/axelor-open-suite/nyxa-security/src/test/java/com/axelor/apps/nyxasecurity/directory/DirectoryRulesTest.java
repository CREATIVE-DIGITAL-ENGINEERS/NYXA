package com.axelor.apps.nyxasecurity.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirectoryRulesTest {

  @Test
  void grantableRolesAreTheCatalogueWithoutBaselineAndNyxaAdmin() {
    Set<String> roles = DirectoryRules.grantableRoles();
    assertTrue(roles.contains("client.admin"));
    assertTrue(roles.contains("all.read"));
    assertTrue(roles.contains("accounting.write"));
    assertFalse(roles.contains("nyxa.admin"));
    assertFalse(roles.contains("Staff Baseline"));
    assertFalse(roles.contains(DirectoryRules.ACCESS_ROLE));
  }

  @Test
  void emailIsTrimmedAndLowercased() {
    assertEquals("ana+ops@example.com", DirectoryRules.normalizeEmail("  Ana+Ops@Example.COM "));
  }

  @Test
  void implausibleEmailsAreRefused() {
    for (String bad : new String[] {null, "", "ana", "ana@example", "a b@example.com", "@x.bg"}) {
      DirectoryException e =
          assertThrows(DirectoryException.class, () -> DirectoryRules.normalizeEmail(bad));
      assertEquals(400, e.getStatus());
      assertEquals("invalid_email", e.getCode());
    }
  }

  @Test
  void everyUngrantableRoleIsNamedAndNothingPasses() {
    DirectoryException e =
        assertThrows(
            DirectoryException.class,
            () ->
                DirectoryRules.checkGrantable(
                    List.of("all.read", "nyxa.admin", "restricted-access", "accounting.admni")));
    assertEquals(400, e.getStatus());
    assertTrue(e.getMessage().contains("nyxa.admin"));
    assertTrue(e.getMessage().contains("restricted-access"));
    assertTrue(e.getMessage().contains("accounting.admni"));
    assertFalse(e.getMessage().contains("all.read"));
  }

  @Test
  void grantableRolesAreDeduplicatedAndBlanksIgnored() {
    assertEquals(
        Set.of("all.read", "client.admin"),
        DirectoryRules.checkGrantable(List.of("all.read", " all.read ", "", "client.admin")));
  }

  @Test
  void onlyTheLastAdminLosingTheRoleIsRefused() {
    assertTrue(DirectoryRules.removesLastAdmin(0, false));
    assertFalse(DirectoryRules.removesLastAdmin(0, true));
    assertFalse(DirectoryRules.removesLastAdmin(1, false));
  }
}
