package com.axelor.apps.nyxasecurity.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.nyxasecurity.gate.Refusals;
import com.axelor.apps.nyxasecurity.user.ActiveCompanyRule;
import com.axelor.apps.nyxasecurity.user.CompanyAssignmentRule;
import com.axelor.apps.nyxasecurity.user.CompanyAssignmentScope;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets an accounting manager add or remove client companies on a staff user.
 *
 * <p>The checks are {@link CompanyAssignmentRule}'s and run here, server-side, before anything is
 * written. The write itself happens inside a {@link CompanyAssignmentScope}, the only way the user
 * self-edit guard lets a non-admin change another user, and then only their companies.
 */
@Singleton
public class CompanyAssignmentService {

  private static final Logger LOG = LoggerFactory.getLogger(CompanyAssignmentService.class);

  private final UserRepository userRepo;
  private final CompanyRepository companyRepo;

  @Inject
  public CompanyAssignmentService(UserRepository userRepo, CompanyRepository companyRepo) {
    this.userRepo = userRepo;
    this.companyRepo = companyRepo;
  }

  /**
   * @param add true to assign the companies, false to remove them
   * @return a short summary for the user
   * @throws IllegalArgumentException with the reason, when the assignment is refused
   */
  public String change(Long targetId, Collection<Long> companyIds, boolean add) {
    User actor = AuthUtils.getUser();
    if (actor == null) {
      throw new IllegalArgumentException(Refusals.tr(Refusals.NOT_LOGGED_IN));
    }
    User target = targetId == null ? null : userRepo.find(targetId);
    String refusal =
        CompanyAssignmentRule.refusal(
            new CompanyAssignmentRule.Request(
                AuthUtils.isAdmin(actor),
                roleNames(actor),
                actor.getId(),
                ids(actor.getCompanySet()),
                target == null ? null : target.getId(),
                target == null || target.getGroup() == null ? null : target.getGroup().getCode(),
                companyIds));
    if (refusal != null) {
      throw new IllegalArgumentException(CompanyAssignmentRule.message(refusal));
    }

    List<String> changed = new ArrayList<>();
    CompanyAssignmentScope.call(
        () -> {
          JPA.runInTransaction(
              () -> {
                User user = userRepo.find(targetId);
                for (Long id : companyIds) {
                  Company company = companyRepo.find(id);
                  if (company == null) {
                    continue;
                  }
                  boolean has =
                      user.getCompanySet() != null && user.getCompanySet().contains(company);
                  if (add && !has) {
                    user.addCompanySetItem(company);
                    changed.add(company.getName());
                  } else if (!add && has) {
                    user.removeCompanySetItem(company);
                    changed.add(company.getName());
                  }
                }
                keepActiveCompanyValid(user);
                // Flush inside the scope: the self-edit guard runs at flush time.
                JPA.flush();
              });
          return null;
        });

    LOG.info(
        "Company assignment by {}: {} {} {}",
        actor.getCode(),
        add ? "assigned" : "removed",
        changed,
        (add ? "to " : "from ") + target.getCode());
    if (changed.isEmpty()) {
      return Refusals.tr(Refusals.NOTHING_TO_CHANGE);
    }
    return Refusals.tr(add ? Refusals.ASSIGNED : Refusals.REMOVED, String.join(", ", changed));
  }

  /** A removed company can't stay active; same rule as at login. */
  private void keepActiveCompanyValid(User user) {
    Company active = user.getActiveCompany();
    ActiveCompanyRule.Decision decision =
        ActiveCompanyRule.decide(active == null ? null : active.getId(), ids(user.getCompanySet()));
    if (decision.isChange()) {
      Company target =
          decision.getCompanyId() == null ? null : companyRepo.find(decision.getCompanyId());
      user.setActiveCompany(target);
      user.setTradingName(null);
    }
  }

  private static List<String> roleNames(User user) {
    List<String> names = new ArrayList<>();
    if (user.getRoles() != null) {
      user.getRoles().stream().map(Role::getName).forEach(names::add);
    }
    if (user.getGroup() != null && user.getGroup().getRoles() != null) {
      user.getGroup().getRoles().stream().map(Role::getName).forEach(names::add);
    }
    return names;
  }

  private static List<Long> ids(Set<Company> companies) {
    return companies == null
        ? List.of()
        : companies.stream().map(Company::getId).collect(Collectors.toList());
  }
}
