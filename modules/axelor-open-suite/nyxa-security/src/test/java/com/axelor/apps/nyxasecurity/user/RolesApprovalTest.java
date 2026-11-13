package com.axelor.apps.nyxasecurity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy.Actor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RolesApprovalTest {

  @Test
  void anApprovalIsForThatActorUserAndExactRolesAndUsedOnce() {
    RolesApproval.approve("boss", 7L, Set.of("all.read", "hr.read"));

    assertFalse(RolesApproval.consume("other", 7L, Set.of("all.read", "hr.read")), "other actor");
    assertFalse(RolesApproval.consume("boss", 8L, Set.of("all.read", "hr.read")), "other user");
    assertFalse(RolesApproval.consume("boss", 7L, Set.of("all.read")), "other roles");
    assertTrue(RolesApproval.consume("boss", 7L, Set.of("hr.read", "all.read")));
    assertFalse(RolesApproval.consume("boss", 7L, Set.of("all.read", "hr.read")), "used up");
  }

  @Test
  void aClientAdministratorMayChangeTheConfigurationButNotWhoTheUserIs() {
    Actor other = UserSelfEditPolicy.classify("boss", "ana", "staff", false);
    assertNull(
        UserSelfEditPolicy.firstForbidden(
            other,
            List.of(
                "theme",
                "localization",
                "language",
                "companySet",
                "activeCompany",
                "tradingName",
                "partner",
                "employee"),
            false,
            false,
            true));
    for (String field :
        List.of("group", "roles", "password", "permissions", "code", "blocked", "email")) {
      assertEquals(
          field, UserSelfEditPolicy.firstForbidden(other, List.of(field), false, false, true));
    }
    // Without the client administrator's edit, nothing on another user, as before.
    assertEquals("theme", UserSelfEditPolicy.firstForbidden(other, List.of("theme")));
  }
}
