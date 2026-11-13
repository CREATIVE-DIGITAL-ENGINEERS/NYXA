package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import java.util.HashSet;
import java.util.Set;

/**
 * Who may use the AI Accountant. The roles are the per-area Keycloak role names; this module does
 * not depend on nyxa-security, so the two sets are repeated here and must stay equal to {@code
 * Capabilities.AI_READ} / {@code AI_WRITE} there.
 *
 * <p>Read lets a user chat and run the read-only tools; write additionally lets the assistant
 * create or change drafts. Either way a tool acts with the user's own permissions, so write here
 * never reaches further than the user's own area roles do. Admins pass, and so does system-context
 * work, which has no user to check.
 */
public final class AiRoles {

  public static final Set<String> READ =
      Set.of("accounting.ai.read", "accounting.ai.write", "all.read", "all.write", "client.admin");

  public static final Set<String> WRITE =
      Set.of("accounting.ai.write", "all.write", "client.admin");

  private AiRoles() {}

  public static boolean mayChat(User user) {
    return user != null && (AuthUtils.isAdmin(user) || hasAny(user, READ));
  }

  public static boolean mayWrite() {
    if (AiExecutionContext.isSystem()) {
      return true;
    }
    User user = AuthUtils.getUser();
    return user != null && (AuthUtils.isAdmin(user) || hasAny(user, WRITE));
  }

  private static boolean hasAny(User user, Set<String> anyOf) {
    Set<String> names = new HashSet<>();
    if (user.getRoles() != null) {
      user.getRoles().stream().map(Role::getName).forEach(names::add);
    }
    if (user.getGroup() != null && user.getGroup().getRoles() != null) {
      user.getGroup().getRoles().stream().map(Role::getName).forEach(names::add);
    }
    return anyOf.stream().anyMatch(names::contains);
  }
}
