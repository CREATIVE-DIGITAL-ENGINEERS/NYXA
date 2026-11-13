package com.axelor.apps.nyxasecurity.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.nyxasecurity.service.CompanyAssignmentService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The "Company assignments" screen. All checks are in the service. */
public class CompanyAssignmentController {

  public void refresh(ActionRequest request, ActionResponse response) {
    response.setValue(
        "assignedCompanies", visibleAssigned(id(request.getContext().get("assignUser"))));
  }

  public void assign(ActionRequest request, ActionResponse response) {
    change(request, response, true);
  }

  public void remove(ActionRequest request, ActionResponse response) {
    change(request, response, false);
  }

  private void change(ActionRequest request, ActionResponse response, boolean add) {
    Long userId = id(request.getContext().get("assignUser"));
    List<Long> companyIds = ids(request.getContext().get("companiesToChange"));
    try {
      String summary = Beans.get(CompanyAssignmentService.class).change(userId, companyIds, add);
      response.setInfo(summary);
      response.setValue("companiesToChange", new ArrayList<>());
      response.setValue("assignedCompanies", visibleAssigned(userId));
    } catch (IllegalArgumentException e) {
      response.setError(I18n.get(e.getMessage()));
    }
  }

  /**
   * The target's companies. Only admins and client.admin reach this screen, and both may assign any
   * company of the tenant, so all of them are shown.
   */
  private static List<Map<String, Object>> visibleAssigned(Long userId) {
    List<Map<String, Object>> out = new ArrayList<>();
    User actor = AuthUtils.getUser();
    User target = userId == null ? null : Beans.get(UserRepository.class).find(userId);
    if (actor == null || target == null || target.getCompanySet() == null) {
      return out;
    }
    for (Company company : target.getCompanySet()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", company.getId());
      item.put("name", company.getName());
      out.add(item);
    }
    return out;
  }

  private static Long id(Object value) {
    if (value instanceof Map) {
      Object id = ((Map<?, ?>) value).get("id");
      return id == null ? null : Long.valueOf(id.toString());
    }
    if (value instanceof com.axelor.db.Model) {
      return ((com.axelor.db.Model) value).getId();
    }
    return null;
  }

  private static List<Long> ids(Object value) {
    List<Long> ids = new ArrayList<>();
    if (value instanceof Collection) {
      for (Object item : (Collection<?>) value) {
        Long id = id(item);
        if (id != null) {
          ids.add(id);
        }
      }
    }
    return ids;
  }
}
