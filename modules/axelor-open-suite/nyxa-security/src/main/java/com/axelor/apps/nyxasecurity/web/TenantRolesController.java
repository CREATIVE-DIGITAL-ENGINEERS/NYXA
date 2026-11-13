package com.axelor.apps.nyxasecurity.web;

import com.axelor.apps.nyxasecurity.directory.DirectoryRules;
import com.axelor.auth.db.Role;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.stream.Collectors;

/**
 * Application Config › Roles: the catalogue roles a client administrator may grant. Opened from
 * code because the list comes from the catalogue ({@link DirectoryRules#grantableRoles()}), which a
 * view domain can't name. Who may edit is decided by permissions alone: read-only for {@code
 * client.admin}, editable for technical admins.
 */
public class TenantRolesController {

  public void open(ActionRequest request, ActionResponse response) {
    response.setView(
        ActionView.define("Roles")
            .model(Role.class.getName())
            .add("grid", "nx-tenant-role-grid")
            .add("form", "nx-tenant-role-form")
            .domain(
                "self.name IN ("
                    + DirectoryRules.grantableRoles().stream()
                        .map(name -> "'" + name + "'")
                        .collect(Collectors.joining(", "))
                    + ")")
            .map());
  }
}
