package com.weaccount;

import com.axelor.auth.pac4j.AuthPac4jInfo;
import com.axelor.web.service.InfoService;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Tells the front-end that Keycloak is the one and only login client.
 *
 * <p>The React login route branches on {@code authentication.defaultClient} / {@code
 * authentication.exclusive}, but the platform's {@link InfoService#authInfo} returns only the
 * callback URL — those two fields are filled in by the Enterprise edition, which this build does
 * not have. Without them, a client-side navigation to {@code /login} (after a session expires, say)
 * renders the local username/password form, which posts to a client that is no longer registered.
 *
 * <p>With {@code exclusive}, the front-end redirects to {@code /login} straight away, and the
 * server sends the browser on to Keycloak.
 */
public class WeAccountInfoService extends InfoService {

  @Inject
  public WeAccountInfoService(AuthPac4jInfo pac4jInfo) {
    super(pac4jInfo);
  }

  @Override
  protected Map<String, Object> authInfo(HttpServletRequest request, HttpServletResponse response) {
    final Map<String, Object> map = super.authInfo(request, response);

    map.put("defaultClient", KeycloakClientListService.CLIENT_NAME);
    map.put("exclusive", true);
    map.put("clients", List.of(Map.of("name", KeycloakClientListService.CLIENT_NAME)));

    return map;
  }
}
