package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.nyxasecurity.directory.DirectoryException;
import com.axelor.apps.nyxasecurity.directory.MockTenantUserDirectory;
import com.axelor.apps.nyxasecurity.directory.TenantUserDirectory;
import com.axelor.apps.nyxasecurity.module.NyxaSecurityTestModule;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBase;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.persistence.PersistenceException;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Invite, change roles, revoke and re-invite through {@link TenantUserService}, against the
 * in-memory directory and a real flush, so the user self-edit guard judges every local write as a
 * client administrator's change to <em>another</em> user. That is what a unit test can't show: the
 * guard runs at flush time, on inserts' collections too.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(NyxaSecurityTestModule.class)
@Tag("integration")
class TenantUserServiceIntegrationTest extends JpaSupport {

  /** The tenant's owner: its initial client administrator, client.owner in Keycloak. */
  private static final String BOSS = "boss@example.com";

  private static final String CLERK = "clerk@example.com";
  private static final String NYXA = "nyxa@example.com";

  @BeforeAll
  static void fixtures() {
    JPA.runInTransaction(
        () -> {
          // Every installation has the base app's settings row; mail lookups read it.
          JPA.em().persist(new AppBase());
          Group staff = new Group("staff", "Staff");
          JPA.em().persist(staff);
          for (String name : List.of("client.admin", "all.read", "hr.read", "all.write")) {
            JPA.em().persist(new Role(name));
          }
          JPA.em().persist(user(BOSS, staff, "client.admin"));
          JPA.em().persist(user(CLERK, staff, "all.write"));
          // nyxa.admin logs in to the technical admins group, which is what AuthUtils.isAdmin
          // reads.
          Group admins = new Group("admins", "Administrators");
          JPA.em().persist(admins);
          JPA.em().persist(user(NYXA, admins, "client.admin"));
        });
    // Onboarding: the owner is in Keycloak with client.admin and client.owner.
    MockTenantUserDirectory directory = mockDirectory();
    directory.markOwner(directory.invite(BOSS, Set.of("client.admin")).getUser().getId());
  }

  @AfterEach
  void logout() {
    ThreadContext.unbindSubject();
    ThreadContext.unbindSecurityManager();
  }

  @Test
  void inviteCreatesAStaffUserWithTheGrantedRoles() {
    login(BOSS);
    TenantUserService.Invitation invitation =
        service().invite("  New.Person@Example.com ", Set.of("all.read", "hr.read"));

    User user = reload(invitation.getUser());
    assertTrue(invitation.getResult().isCreated());
    assertEquals("new.person@example.com", user.getCode());
    assertEquals("new.person@example.com", user.getEmail());
    assertEquals("staff", user.getGroup().getCode());
    assertEquals(Set.of("all.read", "hr.read"), roles(user));
    assertFalse(Boolean.TRUE.equals(user.getBlocked()));
    // No email account in this database: access is granted all the same, and the reason is shown.
    assertTrue(invitation.getMailProblem().contains("No email account"));
  }

  @Test
  void changeRevokeAndReinvite() {
    login(BOSS);
    User user = service().invite("cycle@example.com", Set.of("all.read")).getUser();

    service().changeRoles(user.getId(), Set.of("hr.read"));
    assertEquals(Set.of("hr.read"), roles(reload(user)));

    service().revoke(user.getId());
    User revoked = reload(user);
    assertTrue(revoked.getBlocked());
    assertTrue(roles(revoked).isEmpty());

    service().invite("cycle@example.com", Set.of("all.read"));
    User back = reload(user);
    assertFalse(back.getBlocked());
    assertEquals(Set.of("all.read"), roles(back));
  }

  @Test
  void onlyClientAdministratorsMayManage() {
    login(CLERK);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> service().invite("x@example.com", Set.of("all.read")));
    assertTrue(e.getMessage().contains("client administrator"));
  }

  @Test
  void nobodyRevokesThemselves() {
    login(BOSS);
    Long self = Beans.get(UserRepository.class).findByCode(BOSS).getId();
    assertThrows(IllegalArgumentException.class, () -> service().revoke(self));
  }

  @Test
  void aUserWithAccessKeepsARoleButARevokedOneMayHaveNone() {
    login(BOSS);
    User user = service().invite("keeps.a.role@example.com", Set.of("all.read")).getUser();

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> service().approveRolesForSave(user.getId(), Set.of()));
    assertEquals("Choose at least one role.", e.getMessage());
    assertThrows(
        IllegalArgumentException.class, () -> service().changeRoles(user.getId(), Set.of()));
    assertEquals(Set.of("all.read"), roles(reload(user)));

    // Revoked: no roles by design, and the page (with roles unchanged) still saves.
    service().revoke(user.getId());
    assertEquals(null, service().approveRolesForSave(user.getId(), Set.of()));
  }

  @Test
  void aRevokedUserIsBroughtBackWithRestoreAccess() {
    login(BOSS);
    User user = service().invite("comeback@example.com", Set.of("all.read")).getUser();
    service().revoke(user.getId());

    // Roles changed and saved on a revoked user's page: pointed to Restore access instead.
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> service().approveRolesForSave(user.getId(), Set.of("hr.read")));
    assertTrue(e.getMessage().contains("Restore access"));

    // (The mock forgets a revoked person, so it re-creates them; the identity provider keeps them.)
    service().restoreAccess(user.getId(), Set.of("hr.read"));
    User back = reload(user);
    assertFalse(back.getBlocked());
    assertEquals(Set.of("hr.read"), roles(back));
    assertTrue(
        mockDirectory().list().stream().anyMatch(p -> "comeback@example.com".equals(p.getEmail())));
  }

  @Test
  void anInvitationNeedsARole() {
    login(BOSS);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> service().invite("roleless@example.com", Set.of()));
    assertEquals("Choose at least one role.", e.getMessage());
    // Refused before anything happened, here or in the identity provider.
    assertEquals(null, Beans.get(UserRepository.class).findByCode("roleless@example.com"));
    assertTrue(
        mockDirectory().list().stream()
            .noneMatch(p -> "roleless@example.com".equals(p.getEmail())));
  }

  @Test
  void aRefusedRoleChangesNothing() {
    login(BOSS);
    DirectoryException e =
        assertThrows(
            DirectoryException.class,
            () -> service().invite("bad@example.com", Set.of("nyxa.admin")));
    assertEquals(400, e.getStatus());
    assertEquals(null, Beans.get(UserRepository.class).findByCode("bad@example.com"));
  }

  @Test
  void syncCreatesLocalUsersForPeopleInvitedElsewhere() {
    Beans.get(TenantUserDirectory.class).invite("onboarded@example.com", Set.of("client.admin"));
    login(BOSS);

    service().sync();

    User user = Beans.get(UserRepository.class).findByCode("onboarded@example.com");
    assertNotNull(user);
    assertEquals(Set.of("client.admin"), roles(user));
  }

  @Test
  void theOwnerAppointsAdministratorsWhoManageOnlyOrdinaryUsers() {
    login(BOSS);
    User appointed = service().invite("appointed@example.com", Set.of("client.admin")).getUser();
    User colleague = service().invite("colleague@example.com", Set.of("client.admin")).getUser();
    User plain = service().invite("plain@example.com", Set.of("all.read")).getUser();
    Long owner = Beans.get(UserRepository.class).findByCode(BOSS).getId();

    login(appointed.getCode());
    // Ordinary users: yes.
    service().changeRoles(plain.getId(), Set.of("hr.read"));
    assertEquals(Set.of("hr.read"), roles(reload(plain)));
    // Not the owner, not another administrator, not themselves, and no new administrators.
    assertThrows(
        IllegalArgumentException.class,
        () -> service().changeRoles(owner, Set.of("client.admin", "all.read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service().changeRoles(colleague.getId(), Set.of("all.read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service().changeRoles(appointed.getId(), Set.of("all.read")));
    assertThrows(IllegalArgumentException.class, () -> service().revoke(colleague.getId()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service().invite("new.admin@example.com", Set.of("client.admin")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service().changeRoles(plain.getId(), Set.of("client.admin")));
    assertEquals(Set.of("client.admin"), roles(reload(colleague)));
    assertEquals(Set.of("client.admin"), roles(reload(appointed)));

    // The owner manages the administrators.
    login(BOSS);
    service().changeRoles(colleague.getId(), Set.of("all.read"));
    assertEquals(Set.of("all.read"), roles(reload(colleague)));

    // Only Nyxa changes the owner's roles, and nobody revokes the owner here.
    assertThrows(
        IllegalArgumentException.class,
        () -> service().changeRoles(owner, Set.of("client.admin", "all.read")));
    login(NYXA);
    service().changeRoles(owner, Set.of("client.admin", "all.read"));
    assertEquals(
        Set.of("client.admin", "all.read"), roles(reload(JPA.em().find(User.class, owner))));
    assertThrows(IllegalArgumentException.class, () -> service().revoke(owner));
  }

  @Test
  void aClientAdministratorSavesAnotherUsersConfigurationWithAPlainSave() {
    login(BOSS);
    User user = service().invite("profile@example.com", Set.of("all.read")).getUser();
    Long companyId = company("ACME");

    // What the user page's Save does: a plain save, no scope opened.
    JPA.runInTransaction(
        () -> {
          User u = JPA.em().find(User.class, user.getId());
          u.setTheme("dark");
          u.setSingleTab(true);
          Company company = JPA.em().find(Company.class, companyId);
          u.addCompanySetItem(company);
          u.setActiveCompany(company);
          JPA.flush();
        });
    User saved = reload(user);
    assertEquals("dark", saved.getTheme());
    assertEquals(companyId, saved.getActiveCompany().getId());

    // Who the user is stays out of reach.
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  User u = JPA.em().find(User.class, user.getId());
                  u.setGroup(JPA.all(Group.class).filter("self.code = 'admins'").fetchOne());
                  JPA.flush();
                }));
  }

  @Test
  void theActiveCompanyStaysOneOfTheAssignedCompanies() {
    login(BOSS);
    User user = service().invite("g11@example.com", Set.of("all.read")).getUser();
    Long assigned = company("G11-A");
    Long other = company("G11-B");
    JPA.runInTransaction(
        () -> {
          User u = JPA.em().find(User.class, user.getId());
          Company a = JPA.em().find(Company.class, assigned);
          u.addCompanySetItem(a);
          u.setActiveCompany(a);
          JPA.flush();
        });

    // An active company outside the assigned ones: refused, even for a client administrator.
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  JPA.em()
                      .find(User.class, user.getId())
                      .setActiveCompany(JPA.em().find(Company.class, other));
                  JPA.flush();
                }));
    // Removing the assigned company that is active: refused the other way round.
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  User u = JPA.em().find(User.class, user.getId());
                  u.removeCompanySetItem(JPA.em().find(Company.class, assigned));
                  JPA.flush();
                }));
    assertEquals(assigned, reload(user).getActiveCompany().getId());
  }

  @Test
  void rolesAreSavedOnlyAsKeycloakAcceptedThem() {
    login(BOSS);
    User user = service().invite("saved.roles@example.com", Set.of("all.read")).getUser();

    // A plain save that changes roles without going through Keycloak first: refused.
    assertThrows(PersistenceException.class, () -> saveRoles(user.getId(), "all.read", "hr.read"));
    assertEquals(Set.of("all.read"), roles(reload(user)));

    // The user page's onSave: Keycloak accepts, then the same save goes through, once.
    Set<String> accepted =
        service().approveRolesForSave(user.getId(), Set.of("all.read", "hr.read"));
    assertEquals(Set.of("all.read", "hr.read"), accepted);
    saveRoles(user.getId(), "all.read", "hr.read");
    assertEquals(Set.of("all.read", "hr.read"), roles(reload(user)));
    assertThrows(PersistenceException.class, () -> saveRoles(user.getId(), "hr.read"));

    // Unchanged roles need nothing from Keycloak.
    assertEquals(null, service().approveRolesForSave(user.getId(), Set.of("all.read", "hr.read")));
  }

  @Test
  void anOrdinaryUserCannotChangeAnotherUsersConfiguration() {
    login(CLERK);
    Long boss = Beans.get(UserRepository.class).findByCode(BOSS).getId();
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  JPA.em().find(User.class, boss).setTheme("dark");
                  JPA.flush();
                }));
  }

  @Test
  void syncWritesOnlyWhatChangedAndUnblocks() {
    login(BOSS);
    User user = service().invite("steady@example.com", Set.of("all.read")).getUser();
    service().sync();
    long version = reload(user).getVersion();

    // Nothing changed in the identity provider: nothing is written, so no edit in progress clashes.
    service().sync();
    assertEquals(version, (long) reload(user).getVersion());

    // Blocked here by other means (no logged-in user, as a technical job would) but still with
    // access there: the identity provider wins.
    ThreadContext.unbindSubject();
    JPA.runInTransaction(
        () -> {
          JPA.em().find(User.class, user.getId()).setBlocked(true);
          JPA.flush();
        });
    ThreadContext.unbindSubject();
    login(BOSS);
    service().sync();
    assertFalse(reload(user).getBlocked());
  }

  @Test
  void syncRestoresAnEditedEmailBeforeCreatingWhoeverHasIt() {
    login(BOSS);
    User four = service().invite("four@example.com", Set.of("all.read")).getUser();
    // Edited locally before the email was locked: it now holds someone else's address.
    setEmailWithoutActor(four, "fortyfour@example.com");
    mockDirectory().invite("fortyfour@example.com", Set.of("all.read"));
    login(BOSS);
    TenantUserService.forgetLastSync();

    assertEquals(null, service().syncOnOpen());

    assertEquals("four@example.com", reload(four).getEmail());
    User fortyFour = Beans.get(UserRepository.class).findByCode("fortyfour@example.com");
    assertNotNull(fortyFour);
    assertEquals("fortyfour@example.com", fortyFour.getEmail());
  }

  @Test
  void aPersonWhoCannotBeWrittenDoesNotStopTheOthers() {
    login(BOSS);
    // A local user outside the identity provider holds the address of someone invited there.
    User stray = service().invite("stray@example.com", Set.of("all.read")).getUser();
    mockDirectory()
        .revoke(
            mockDirectory().list().stream()
                .filter(p -> "stray@example.com".equals(p.getEmail()))
                .findFirst()
                .get()
                .getId());
    setEmailWithoutActor(stray, "taken@example.com");
    mockDirectory().invite("taken@example.com", Set.of("all.read"));
    mockDirectory().invite("fine@example.com", Set.of("all.read"));
    login(BOSS);
    TenantUserService.forgetLastSync();

    try {
      assertNotNull(service().syncOnOpen());

      assertEquals(null, Beans.get(UserRepository.class).findByCode("taken@example.com"));
      assertNotNull(Beans.get(UserRepository.class).findByCode("fine@example.com"));
    } finally {
      // The directory and database outlive this test: leave no clash for the next sync.
      setEmailWithoutActor(stray, "stray@example.com");
      login(BOSS);
    }
  }

  /** As a technical job would (no logged-in user), so the self-edit guard doesn't apply. */
  private static void setEmailWithoutActor(User user, String email) {
    ThreadContext.unbindSubject();
    JPA.runInTransaction(
        () -> {
          JPA.em().find(User.class, user.getId()).setEmail(email);
          JPA.flush();
        });
  }

  @Test
  void openingTheListSyncsAtMostOnceInTheInterval() {
    login(BOSS);
    TenantUserService.forgetLastSync();
    assertEquals(null, service().syncOnOpen());

    // Invited elsewhere right after: not picked up until the interval has passed.
    mockDirectory().invite("later@example.com", Set.of("all.read"));
    assertEquals(null, service().syncOnOpen());
    assertEquals(null, Beans.get(UserRepository.class).findByCode("later@example.com"));

    TenantUserService.forgetLastSync();
    assertEquals(null, service().syncOnOpen());
    assertNotNull(Beans.get(UserRepository.class).findByCode("later@example.com"));
  }

  @Test
  void outsideTheScopeTheGuardStillRefusesRoleChangesOnOthers() {
    login(BOSS);
    Long clerk = Beans.get(UserRepository.class).findByCode(CLERK).getId();
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  User user = JPA.em().find(User.class, clerk);
                  user.addRole(Beans.get(RoleRepository.class).findByName("client.admin"));
                  JPA.flush();
                }));
  }

  // ---------------------------------------------------------------------------------------------

  private static MockTenantUserDirectory mockDirectory() {
    return (MockTenantUserDirectory) Beans.get(TenantUserDirectory.class);
  }

  private static TenantUserService service() {
    return Beans.get(TenantUserService.class);
  }

  private static User user(String code, Group group, String role) {
    User user = new User(code, code);
    user.setEmail(code);
    user.setPassword("not-used");
    user.setGroup(group);
    user.addRole(JPA.all(Role.class).filter("self.name = ?", role).fetchOne());
    return user;
  }

  private static void login(String code) {
    DefaultSecurityManager securityManager = new DefaultSecurityManager();
    ThreadContext.bind(securityManager);
    Subject subject =
        new Subject.Builder(securityManager)
            .principals(new SimplePrincipalCollection(code, "it"))
            .authenticated(true)
            .buildSubject();
    ThreadContext.bind(subject);
  }

  private static void saveRoles(Long userId, String... names) {
    JPA.runInTransaction(
        () -> {
          User u = JPA.em().find(User.class, userId);
          u.clearRoles();
          for (String name : names) {
            u.addRole(Beans.get(RoleRepository.class).findByName(name));
          }
          JPA.flush();
        });
  }

  private static Long company(String code) {
    Company[] created = new Company[1];
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setCode(code);
          company.setName(code);
          JPA.em().persist(company);
          created[0] = company;
        });
    return created[0].getId();
  }

  private static User reload(User user) {
    JPA.em().clear();
    return JPA.em().find(User.class, user.getId());
  }

  private static Set<String> roles(User user) {
    return user.getRoles() == null
        ? Set.of()
        : user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
  }
}
