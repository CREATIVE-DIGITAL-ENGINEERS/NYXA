package com.axelor.apps.nyxasecurity.directory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MockTenantUserDirectoryTest {

  private final MockTenantUserDirectory directory = new MockTenantUserDirectory();

  @Test
  void inviteCreatesOnceAndIsRepeatable() {
    InviteResult first = directory.invite("Ana@Example.com", Set.of("all.read"));
    InviteResult again = directory.invite("ana@example.com", Set.of("all.read", "hr.read"));

    assertTrue(first.isCreated());
    assertFalse(again.isCreated());
    assertEquals(first.getUser().getId(), again.getUser().getId());
    assertEquals("ana@example.com", again.getUser().getEmail());
    assertEquals(Set.of("hr.read"), again.getRolesAdded());
    assertEquals(1, directory.list().size());
  }

  @Test
  void anInvalidRoleChangesNothing() {
    assertThrows(
        DirectoryException.class,
        () -> directory.invite("ana@example.com", Set.of("all.read", "nyxa.admin")));
    assertTrue(directory.list().isEmpty());
  }

  @Test
  void theLastClientAdminCannotBeDemotedOrRevoked() {
    String admin = directory.invite("boss@example.com", Set.of("client.admin")).getUser().getId();

    DirectoryException demote =
        assertThrows(DirectoryException.class, () -> directory.setRoles(admin, Set.of("all.read")));
    assertEquals(409, demote.getStatus());
    DirectoryException revoke =
        assertThrows(DirectoryException.class, () -> directory.revoke(admin));
    assertEquals("last_admin", revoke.getCode());

    directory.invite("second@example.com", Set.of("client.admin"));
    assertDoesNotThrow(() -> directory.revoke(admin));
  }

  @Test
  void revokedUsersAreGone() {
    String id = directory.invite("ana@example.com", Set.of("all.read")).getUser().getId();
    directory.revoke(id);

    assertEquals(404, assertThrows(DirectoryException.class, () -> directory.get(id)).getStatus());
    assertTrue(directory.list().isEmpty());
  }

  @Test
  void theOwnerIsNeitherRevokedNorReinvitedButTheirRolesMayChange() {
    String owner = directory.invite("owner@example.com", Set.of("client.admin")).getUser().getId();
    directory.markOwner(owner);
    directory.invite("second@example.com", Set.of("client.admin"));

    assertEquals(
        403, assertThrows(DirectoryException.class, () -> directory.revoke(owner)).getStatus());
    assertEquals(
        "owner",
        assertThrows(
                DirectoryException.class,
                () -> directory.invite("owner@example.com", Set.of("all.read")))
            .getCode());
    // Who may change the owner's roles is the app's decision (UserManagementRule).
    DirectoryUser changed = directory.setRoles(owner, Set.of("client.admin", "all.read"));
    assertTrue(changed.isOwner());
    assertFalse(changed.getRoles().contains(DirectoryRules.OWNER));
  }

  @Test
  void loggedInIsReported() {
    String id = directory.invite("ana@example.com", Set.of()).getUser().getId();
    assertFalse(directory.get(id).hasLoggedIn());
    directory.markLoggedIn(id);
    assertTrue(directory.get(id).hasLoggedIn());
  }
}
