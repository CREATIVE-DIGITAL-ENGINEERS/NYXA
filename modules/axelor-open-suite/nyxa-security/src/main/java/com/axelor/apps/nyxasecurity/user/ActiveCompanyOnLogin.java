package com.axelor.apps.nyxasecurity.user;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.service.user.UserService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.event.Observes;
import com.axelor.events.PostLogin;
import com.axelor.inject.Beans;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies {@link ActiveCompanyRule} on every successful login, after Keycloak provisioning has set
 * the user's group and roles. Admins are left alone: they bypass company scoping anyway.
 *
 * <p>Never fails the login. A problem here is logged, and the user simply sees what their current
 * active company allows.
 */
public class ActiveCompanyOnLogin {

  private static final Logger LOG = LoggerFactory.getLogger(ActiveCompanyOnLogin.class);

  void onLoginSuccess(@Observes @Named(PostLogin.SUCCESS) PostLogin event) {
    User loggedIn = event.getUser();
    if (loggedIn == null || AuthUtils.isAdmin(loggedIn)) {
      return;
    }
    try {
      JPA.runInTransaction(() -> apply(loggedIn.getId()));
    } catch (Exception e) {
      LOG.error("Could not set the active company of user {} at login.", loggedIn.getCode(), e);
    }
  }

  protected void apply(Long userId) {
    User user = Beans.get(UserRepository.class).find(userId);
    if (user == null) {
      return;
    }
    Company active = user.getActiveCompany();
    List<Long> assigned =
        user.getCompanySet() == null
            ? List.of()
            : user.getCompanySet().stream().map(Company::getId).collect(Collectors.toList());

    ActiveCompanyRule.Decision decision =
        ActiveCompanyRule.decide(active == null ? null : active.getId(), assigned);
    if (!decision.isChange()) {
      return;
    }

    Company target =
        decision.getCompanyId() == null
            ? null
            : Beans.get(CompanyRepository.class).find(decision.getCompanyId());
    // Also clears the trading name, which belongs to the previous company.
    Beans.get(UserService.class).setActiveCompany(user, target);
    if (target == null) {
      LOG.info(
          "User {} has no assigned companies; cleared active company {}.",
          user.getCode(),
          active == null ? "-" : active.getName());
    } else {
      LOG.info(
          "User {}: active company set to {} (was {}).",
          user.getCode(),
          target.getName(),
          active == null ? "none" : active.getName());
    }
  }
}
