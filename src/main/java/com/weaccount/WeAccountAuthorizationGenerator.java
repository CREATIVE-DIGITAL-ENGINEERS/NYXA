package com.weaccount;

import com.axelor.app.AppSettings;
import com.axelor.auth.pac4j.AccessRefusedException;
import com.axelor.auth.pac4j.AuthPac4jProfileService;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.pac4j.core.authorization.generator.AuthorizationGenerator;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.exception.CredentialsException;
import org.pac4j.core.profile.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces that the Keycloak-issued token's {@code clientId} custom claim and its standard {@code
 * azp} (authorized party) claim both match the Keycloak client this deployment logs in as ({@code
 * auth.provider.oidc.client.id}, set per tenant), and that at least one role was granted.
 *
 * <p>Roles pass through unchanged: the Keycloak role names ({@code accounting.write}, {@code
 * client.admin}, ...) are the Axelor role names of the nyxa-security catalogue. {@code
 * auth.provider.oidc.role.mapping} can still rename them if that is ever needed. Every user goes to
 * the staff group except holders of {@code auth.provider.oidc.admin.role} ({@code nyxa.admin}), who
 * join the technical admins group. Both are re-evaluated on every login.
 *
 * <p>{@code AuthPac4jProfileService.getRoles()}/{@code getGroup()} (unmodified, platform code)
 * later resolve those names against {@code RoleRepository}/{@code GroupRepository} and assign them
 * to the Axelor {@code User}.
 */
public class WeAccountAuthorizationGenerator implements AuthorizationGenerator {

  private static final Logger logger =
      LoggerFactory.getLogger(WeAccountAuthorizationGenerator.class);

  private static final String AZP_CLAIM = "azp";

  @Override
  public Optional<UserProfile> generate(
      WebContext context, SessionStore sessionStore, UserProfile profile) {
    checkClientId(profile);

    final Collection<String> roles = getRoleClaimValues(profile);
    mapRoles(profile, roles);
    assignGroup(profile, roles);

    return Optional.of(profile);
  }

  private void checkClientId(UserProfile profile) {
    final AppSettings settings = AppSettings.get();
    final String clientIdClaim = settings.get("auth.provider.oidc.client.id.claim", "clientId");
    // The client this app authenticates as: a token minted for another tenant's client must not
    // log in here, even when the user belongs to both.
    final String expectedClientId = settings.get("auth.provider.oidc.client.id");

    if (expectedClientId == null || expectedClientId.isBlank()) {
      return;
    }

    checkClaimMatches(profile, clientIdClaim, expectedClientId);
    checkClaimMatches(profile, AZP_CLAIM, expectedClientId);
  }

  private void checkClaimMatches(UserProfile profile, String claimName, String expectedValue) {
    final Object actualValue = profile.getAttribute(claimName);

    if (!Objects.equals(expectedValue, String.valueOf(actualValue))) {
      throw new CredentialsException(
          "Token '"
              + claimName
              + "' claim ('"
              + actualValue
              + "') does not match auth.provider.oidc.client.id");
    }
  }

  private Collection<String> getRoleClaimValues(UserProfile profile) {
    final AppSettings settings = AppSettings.get();
    final String rolesClaim = settings.get("auth.provider.oidc.roles.claim", "roles");
    final Object claim = profile.getAttribute(rolesClaim);
    final Collection<?> raw =
        claim == null
            ? List.of()
            : claim instanceof Collection ? (Collection<?>) claim : List.of(claim);

    if (raw.isEmpty()) {
      // No role means no access here (revoked, or never granted): the login page says so.
      throw new AccessRefusedException(
          "Token has no '" + rolesClaim + "' claim; at least one role is required");
    }

    return raw.stream().map(String::valueOf).collect(Collectors.toList());
  }

  private void mapRoles(UserProfile profile, Collection<String> roles) {
    final AppSettings settings = AppSettings.get();
    final Map<String, List<String>> roleMapping =
        parseMapping(settings.get("auth.provider.oidc.role.mapping", ""));

    for (String roleName : roles) {
      final List<String> mappedNames = roleMapping.getOrDefault(roleName, List.of(roleName));
      mappedNames.forEach(profile::addRole);
    }

    logger.debug("Mapped roles for profile {}: {}", profile.getId(), profile.getRoles());
  }

  private void assignGroup(UserProfile profile, Collection<String> roles) {
    final AppSettings settings = AppSettings.get();
    // Only nyxa.admin makes a technical admin (the admins group, which bypasses every permission
    // and code check). client.admin is a business role in the staff group.
    // auth.provider.oidc.admin.role accepts a comma-separated list so a LOCAL, uncommitted
    // override (a local env file) can add client.admin for dev-only testing, without changing
    // this committed default or affecting any other deployment.
    final String adminRoleSetting = settings.get("auth.provider.oidc.admin.role", "nyxa.admin");
    final List<String> adminRoles =
        Arrays.stream(adminRoleSetting.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    final String adminGroup = settings.get("auth.provider.oidc.admin.group", "admins");
    final String staffGroup = settings.get("auth.provider.oidc.staff.group", "staff");
    final boolean isAdmin = adminRoles.stream().anyMatch(roles::contains);
    final String groupCode = isAdmin ? adminGroup : staffGroup;

    profile.addAttribute(AuthPac4jProfileService.GROUP_ATTRIBUTE, groupCode);
    logger.debug("Assigned group for profile {}: {}", profile.getId(), groupCode);
  }

  private static Map<String, List<String>> parseMapping(String raw) {
    final Map<String, List<String>> mapping = new HashMap<>();

    if (raw == null || raw.isBlank()) {
      return mapping;
    }

    for (String pair : raw.split(",")) {
      final String[] parts = pair.split(":", 2);
      if (parts.length == 2) {
        final List<String> axelorRoles =
            Arrays.stream(parts[1].split("\\|")).map(String::trim).collect(Collectors.toList());
        mapping.put(parts[0].trim(), axelorRoles);
      }
    }

    return mapping;
  }
}
