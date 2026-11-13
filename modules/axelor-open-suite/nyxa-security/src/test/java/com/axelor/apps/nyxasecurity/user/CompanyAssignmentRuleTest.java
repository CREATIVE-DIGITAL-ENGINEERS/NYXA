package com.axelor.apps.nyxasecurity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy.Actor;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompanyAssignmentRuleTest {

  private static final List<String> MANAGER = List.of("client.admin", "accounting.write");
  private static final List<Long> MANAGER_COMPANIES = List.of(1L, 2L, 3L);

  private static String refusal(
      boolean admin, List<String> roles, Long target, String group, List<Long> companies) {
    return CompanyAssignmentRule.refusal(
        new CompanyAssignmentRule.Request(
            admin, roles, 10L, MANAGER_COMPANIES, target, group, companies));
  }

  @Test
  void managerAssignsOwnCompaniesToStaff() {
    assertNull(refusal(false, MANAGER, 20L, "staff", List.of(1L, 3L)));
  }

  @Test
  void accountantsCannotAssign() {
    assertNotNull(
        refusal(false, List.of("accounting.write", "all.write"), 20L, "staff", List.of(1L)));
  }

  @Test
  void clientAdminMayAssignAnyCompanyOfTheTenant() {
    assertNull(refusal(false, MANAGER, 20L, "staff", List.of(1L, 99L)));
  }

  @Test
  void clientAdminMayAssignCompaniesToItself() {
    assertNull(refusal(false, MANAGER, 10L, "staff", List.of(99L)));
  }

  @Test
  void technicalAdminsAreNeverTargets() {
    assertNotNull(refusal(false, MANAGER, 20L, "admins", List.of(1L)));
  }

  @Test
  void adminMayAssignAnyCompanyToStaff() {
    assertNull(refusal(true, List.of(), 20L, "staff", List.of(99L)));
  }

  @Test
  void theGuardOpensOnlyTheCompanyFieldsAndOnlyInsideTheScope() {
    Actor other = UserSelfEditPolicy.classify("manager", "jane", "staff", false);
    assertNull(
        UserSelfEditPolicy.firstForbidden(other, List.of("companySet", "activeCompany"), true));
    assertEquals(
        "group", UserSelfEditPolicy.firstForbidden(other, List.of("companySet", "group"), true));
    assertEquals("roles", UserSelfEditPolicy.firstForbidden(other, List.of("roles"), true));
    // Inside the scope a user may change their own companies too, but nothing else of their own.
    Actor self = UserSelfEditPolicy.classify("manager", "manager", "staff", false);
    assertNull(UserSelfEditPolicy.firstForbidden(self, List.of("companySet"), true));
    assertEquals("roles", UserSelfEditPolicy.firstForbidden(self, List.of("roles"), true));
    // Outside the scope, nobody changes their own companies.
    assertEquals(
        "companySet", UserSelfEditPolicy.firstForbidden(self, List.of("companySet"), false));
    // Outside the assignment scope, nothing on another user may change.
    assertEquals(
        "companySet", UserSelfEditPolicy.firstForbidden(other, List.of("companySet"), false));
  }
}
