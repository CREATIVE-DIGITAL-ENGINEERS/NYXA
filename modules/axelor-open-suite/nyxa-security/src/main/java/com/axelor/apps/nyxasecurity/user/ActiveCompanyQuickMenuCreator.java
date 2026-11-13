package com.axelor.apps.nyxasecurity.user;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.quickmenu.ActiveCompanyUpdateQuickMenuCreator;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.web.UserController;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.rpc.Context;
import com.axelor.ui.QuickMenuItem;
import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The header's active company menu, shown also to users with a single company.
 *
 * <p>The stock creator lists companies only when the user has more than one, and the platform drops
 * a quick menu with no items, so a one-company user saw no active company at all. Here such a user
 * gets their company as the only, already selected, item. A user with no company still gets no
 * menu: there is nothing to show.
 */
public class ActiveCompanyQuickMenuCreator extends ActiveCompanyUpdateQuickMenuCreator {

  @Inject
  public ActiveCompanyQuickMenuCreator(
      AppBaseService appBaseService, CompanyRepository companyRepository) {
    super(appBaseService, companyRepository);
  }

  @Override
  protected List<QuickMenuItem> getItems() {
    List<QuickMenuItem> items = super.getItems();
    User user = AuthUtils.getUser();
    if (!items.isEmpty() || user == null) {
      return items;
    }
    Company company = user.getActiveCompany();
    Set<Company> assigned = user.getCompanySet();
    if (company == null && assigned != null && assigned.size() == 1) {
      company = assigned.iterator().next();
    }
    if (company == null) {
      return items;
    }
    List<QuickMenuItem> single = new ArrayList<>();
    single.add(
        new QuickMenuItem(
            company.getName(),
            UserController.class.getName() + ":setActiveCompany",
            new Context(company.getId(), Company.class),
            company.equals(user.getActiveCompany())));
    return single;
  }
}
