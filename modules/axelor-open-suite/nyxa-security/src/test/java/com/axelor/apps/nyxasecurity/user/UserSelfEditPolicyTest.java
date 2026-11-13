package com.axelor.apps.nyxasecurity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy.Actor;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserSelfEditPolicyTest {

  @Test
  void noLoggedInUserIsNotRestricted() {
    // Login provisioning writes group and roles before the subject is authenticated.
    Actor actor = UserSelfEditPolicy.classify(null, "jane", "staff", false);
    assertEquals(Actor.NONE, actor);
    assertNull(UserSelfEditPolicy.firstForbidden(actor, List.of("group", "roles")));
  }

  @Test
  void userMayChangeTheirPreferences() {
    Actor actor = UserSelfEditPolicy.classify("jane", "jane", "staff", false);
    assertEquals(Actor.SELF, actor);
    assertNull(
        UserSelfEditPolicy.firstForbidden(
            actor, List.of("localization", "language", "theme", "version", "updatedOn")));
  }

  @Test
  void userMayNotEscalateThemselves() {
    Actor actor = UserSelfEditPolicy.classify("jane", "jane", "staff", false);
    for (String field :
        List.of(
            "group",
            "roles",
            "permissions",
            "metaPermissions",
            "companySet",
            "password",
            "code",
            "blocked",
            "expiresOn",
            "isPfpValidator",
            "email")) {
      assertEquals(field, UserSelfEditPolicy.firstForbidden(actor, List.of("theme", field)));
    }
  }

  @Test
  void tradingNameMayBeClearedButNotSet() {
    // Switching company clears the previous company's trading name.
    Actor self = UserSelfEditPolicy.classify("jane", "jane", "staff", false);
    assertTrue(UserSelfEditPolicy.isAllowedClear(self, "tradingName", null));
    assertFalse(UserSelfEditPolicy.isAllowedClear(self, "tradingName", new Object()));
    assertFalse(UserSelfEditPolicy.isAllowedClear(self, "group", null));
    Actor other = UserSelfEditPolicy.classify("jane", "john", "staff", false);
    assertFalse(UserSelfEditPolicy.isAllowedClear(other, "tradingName", null));
  }

  @Test
  void unknownFieldsAreRefusedByDefault() {
    Actor actor = UserSelfEditPolicy.classify("jane", "jane", "staff", false);
    assertEquals(
        "someFieldAddedLater",
        UserSelfEditPolicy.firstForbidden(actor, List.of("someFieldAddedLater")));
  }

  @Test
  void adminIsDecidedFromThePreviousState() {
    // An update that moves jane into admins is judged by her old group, so it can't authorize
    // itself.
    assertEquals(Actor.SELF, UserSelfEditPolicy.classify("jane", "jane", "staff", false));
    assertEquals(Actor.ADMIN, UserSelfEditPolicy.classify("jane", "jane", "admins", false));
    assertEquals(Actor.ADMIN, UserSelfEditPolicy.classify("admin", "admin", null, false));
  }

  @Test
  void nonAdminMayNotChangeSomeoneElse() {
    Actor actor = UserSelfEditPolicy.classify("jane", "john", "staff", false);
    assertEquals(Actor.OTHER, actor);
    assertEquals("theme", UserSelfEditPolicy.firstForbidden(actor, List.of("theme")));
    assertNull(UserSelfEditPolicy.firstForbidden(actor, List.of("version", "updatedOn")));
  }

  @Test
  void onlyTheIdentityProviderSetsTheEmail() {
    Actor self = UserSelfEditPolicy.classify("jane", "jane", "staff", false);
    Actor other = UserSelfEditPolicy.classify("boss", "jane", "staff", false);
    Actor admin = UserSelfEditPolicy.classify("root", "jane", "staff", true);
    for (Actor actor : List.of(self, other, admin)) {
      assertEquals(
          "email",
          UserSelfEditPolicy.firstIdentityProviderOwned(actor, List.of("theme", "email"), false));
    }
    // Login (no actor) and the user directory mirror write it.
    assertNull(UserSelfEditPolicy.firstIdentityProviderOwned(Actor.NONE, List.of("email"), false));
    assertNull(UserSelfEditPolicy.firstIdentityProviderOwned(other, List.of("email"), true));
    assertNull(UserSelfEditPolicy.firstIdentityProviderOwned(admin, List.of("theme"), false));
  }

  @Test
  void nyxaAdminIsAnAdminsGroupLoginButNeverTheLocalAdmin() {
    assertTrue(UserSelfEditPolicy.isNyxaAdmin("ops@nyxa.example", "admins"));
    assertFalse(UserSelfEditPolicy.isNyxaAdmin("admin", "admins"));
    assertFalse(UserSelfEditPolicy.isNyxaAdmin("jane", "staff"));
    assertFalse(UserSelfEditPolicy.isNyxaAdmin("jane", null));
  }

  @Test
  void clientAdminChangesOnlyTheCompaniesOfANyxaAdmin() {
    Actor other = UserSelfEditPolicy.classify("boss", "ops@nyxa.example", "admins", false);
    assertEquals(Actor.OTHER, other);
    assertNull(
        UserSelfEditPolicy.firstForbidden(
            other,
            List.of("companySet", "activeCompany", "tradingName"),
            false,
            false,
            false,
            true));
    for (String field : List.of("roles", "group", "password", "theme", "partner", "blocked")) {
      assertEquals(
          field,
          UserSelfEditPolicy.firstForbidden(other, List.of(field), false, false, false, true));
    }
    // Without the flag (not a client administrator), not even the companies.
    assertEquals(
        "companySet",
        UserSelfEditPolicy.firstForbidden(
            other, List.of("companySet"), false, false, false, false));
  }

  @Test
  void adminMayChangeSomeoneElse() {
    Actor actor = UserSelfEditPolicy.classify("boss", "john", "staff", true);
    assertEquals(Actor.ADMIN, actor);
    assertNull(UserSelfEditPolicy.firstForbidden(actor, List.of("group", "roles")));
  }
}
