package com.axelor.apps.nyxasecurity.service;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Puts the closing roles on every company's period-closure role lists.
 *
 * <p>{@code AccountConfig} carries three role lists that axelor-account checks before closing or
 * temporarily closing a period, and before posting into a temporarily closed one. They are checked
 * with {@code UserRoleToolService.checkUserRolesPermissionExcludingEmpty}: an empty list means
 * <em>nobody</em>, with no admin bypass, and every chart ships them empty. So without this, nobody
 * can close a period, not even administrators.
 *
 * <p>Runs at every boot, for every company, so a company created since the last boot gets its lists
 * on the next restart. Only adds; a role removed from {@link #CLOSING_ROLES} has to be retired from
 * the lists explicitly.
 */
@Singleton
public class ClosureRoleSeeder {

  /**
   * {@code client.admin} closes periods. {@code nyxa.admin} is the technical administrator's role
   * (on the {@code admins} group); the admin bypass doesn't reach these lists, so it is named
   * explicitly. The lists are checked against the user's and the group's roles.
   */
  static final List<String> CLOSING_ROLES = List.of("client.admin", "nyxa.admin");

  private final RoleRepository roleRepo;

  @Inject
  public ClosureRoleSeeder(RoleRepository roleRepo) {
    this.roleRepo = roleRepo;
  }

  /**
   * @return a description of each role added; empty when every list was already complete
   */
  public List<String> apply() {
    List<String> added = new ArrayList<>();
    JPA.runInTransaction(
        () -> {
          List<Role> roles = new ArrayList<>();
          for (String name : CLOSING_ROLES) {
            Role role = roleRepo.findByName(name);
            if (role != null) {
              roles.add(role);
            }
          }
          for (AccountConfig config : Query.of(AccountConfig.class).fetch()) {
            String company = config.getCompany() == null ? "?" : config.getCompany().getName();
            for (Role role : roles) {
              add(
                  config,
                  role,
                  company,
                  "closure",
                  AccountConfig::getClosureAuthorizedRoleList,
                  AccountConfig::addClosureAuthorizedRoleListItem,
                  added);
              add(
                  config,
                  role,
                  company,
                  "temporary closure",
                  AccountConfig::getTemporaryClosureAuthorizedRoleList,
                  AccountConfig::addTemporaryClosureAuthorizedRoleListItem,
                  added);
              add(
                  config,
                  role,
                  company,
                  "posting on temporary closure",
                  AccountConfig::getMoveOnTempClosureAuthorizedRoleList,
                  AccountConfig::addMoveOnTempClosureAuthorizedRoleListItem,
                  added);
            }
          }
        });
    return added;
  }

  private static void add(
      AccountConfig config,
      Role role,
      String company,
      String list,
      Function<AccountConfig, Set<Role>> getter,
      BiConsumer<AccountConfig, Role> adder,
      List<String> added) {
    Set<Role> current = getter.apply(config);
    if (current == null || !current.contains(role)) {
      adder.accept(config, role);
      added.add(company + " / " + list + " / " + role.getName());
    }
  }
}
