package com.axelor.apps.nyxasecurity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.nyxasecurity.user.UserManagementRule.Operation;
import com.axelor.apps.nyxasecurity.user.UserManagementRule.Request;
import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy.Actor;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserManagementRuleTest {

  private static final List<String> CLIENT_ADMIN = List.of("client.admin");

  /** An administrator the owner appointed (actor 2). */
  private static Request byAppointed(Operation operation) {
    return new Request(operation).actor(2L, false, CLIENT_ADMIN, false);
  }

  /** The owner (actor 1). */
  private static Request byOwner(Operation operation) {
    return new Request(operation).actor(1L, false, CLIENT_ADMIN, true);
  }

  /** Nyxa staff: a technical admin (actor 9). */
  private static Request byNyxa(Operation operation) {
    return new Request(operation).actor(9L, true, List.of(), false);
  }

  private static String refusal(Request request) {
    return UserManagementRule.refusal(request);
  }

  @Test
  void onlyClientAdminsAndAdminsMayManage() {
    assertNull(refusal(byAppointed(Operation.INVITE)));
    assertNull(refusal(byNyxa(Operation.INVITE)));
    for (String role : List.of("all.write", "accounting.write", "Staff Baseline")) {
      assertNotNull(
          refusal(new Request(Operation.INVITE).actor(5L, false, List.of(role), false)), role);
    }
  }

  @Test
  void technicalAdminsAreNeverTargets() {
    assertNotNull(refusal(byOwner(Operation.CHANGE_ROLES).target(7L, true, false, false)));
    assertNotNull(refusal(byNyxa(Operation.REVOKE).target(7L, true, false, false)));
  }

  @Test
  void appointedAdministratorsManageEveryoneElse() {
    assertNull(refusal(byAppointed(Operation.CHANGE_ROLES).target(5L, false, false, false)));
    assertNull(refusal(byAppointed(Operation.REVOKE).target(5L, false, false, false)));
    assertNull(refusal(byAppointed(Operation.INVITE).target(null, false, false, false)));
  }

  @Test
  void onlyTheOwnerChangesRevokesOrCreatesClientAdministrators() {
    // Another client administrator, or the appointed administrator themselves.
    for (Long target : List.of(3L, 2L)) {
      assertNotNull(
          refusal(byAppointed(Operation.CHANGE_ROLES).target(target, false, false, true)));
      assertNotNull(refusal(byAppointed(Operation.REVOKE).target(target, false, false, true)));
      assertNotNull(refusal(byAppointed(Operation.INVITE).target(target, false, false, true)));
    }
    // Granting client.admin, by invitation or role change.
    assertNotNull(refusal(byAppointed(Operation.INVITE).grantsClientAdmin(true)));
    assertNotNull(
        refusal(
            byAppointed(Operation.CHANGE_ROLES)
                .target(5L, false, false, false)
                .grantsClientAdmin(true)));

    assertNull(refusal(byOwner(Operation.CHANGE_ROLES).target(3L, false, false, true)));
    assertNull(refusal(byOwner(Operation.REVOKE).target(3L, false, false, true)));
    assertNull(refusal(byOwner(Operation.INVITE).grantsClientAdmin(true)));
    assertNull(refusal(byNyxa(Operation.CHANGE_ROLES).target(3L, false, false, true)));
  }

  @Test
  void onlyNyxaChangesTheOwnersRolesAndNobodyRevokesThem() {
    assertNotNull(refusal(byAppointed(Operation.CHANGE_ROLES).target(1L, false, true, true)));
    assertNotNull(refusal(byAppointed(Operation.INVITE).target(1L, false, true, true)));
    // Not even the owner themselves.
    assertNotNull(refusal(byOwner(Operation.CHANGE_ROLES).target(1L, false, true, true)));
    assertNull(refusal(byNyxa(Operation.CHANGE_ROLES).target(1L, false, true, true)));
    assertNotNull(refusal(byNyxa(Operation.REVOKE).target(1L, false, true, true)));
    // Resending the invitation is harmless.
    assertNull(refusal(byAppointed(Operation.RESEND).target(1L, false, true, true)));
  }

  @Test
  void nobodyRevokesThemselves() {
    assertNotNull(refusal(byOwner(Operation.REVOKE).target(1L, false, false, false)));
    assertNotNull(refusal(byAppointed(Operation.REVOKE).target(2L, false, false, false)));
  }

  @Test
  void theManagementScopeOpensOnlyRolesBlockedAndEmail() {
    Actor other = UserSelfEditPolicy.classify("boss", "ana", "staff", false);
    assertNull(
        UserSelfEditPolicy.firstForbidden(
            other, List.of("roles", "blocked", "email"), false, true));
    for (String field : List.of("group", "password", "permissions", "companySet", "code")) {
      assertEquals(
          field, UserSelfEditPolicy.firstForbidden(other, List.of("roles", field), false, true));
    }
    // Closed scope: nothing changes for the existing behaviour.
    assertEquals("roles", UserSelfEditPolicy.firstForbidden(other, List.of("roles"), false, false));
    assertEquals("blocked", UserSelfEditPolicy.firstForbidden(other, List.of("blocked")));
  }
}
