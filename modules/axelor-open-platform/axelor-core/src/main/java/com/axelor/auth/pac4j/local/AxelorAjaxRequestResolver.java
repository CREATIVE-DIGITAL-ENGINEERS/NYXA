/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2026 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.auth.pac4j.local;

import com.axelor.auth.pac4j.AuthPac4jInfo;
import com.axelor.inject.Beans;
import java.util.Optional;
import javax.inject.Singleton;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.exception.http.UnauthorizedAction;
import org.pac4j.core.exception.http.WithLocationAction;
import org.pac4j.core.http.ajax.DefaultAjaxRequestResolver;
import org.pac4j.core.redirect.RedirectionActionBuilder;

@Singleton
public class AxelorAjaxRequestResolver extends DefaultAjaxRequestResolver {

  @Override
  public boolean isAjax(WebContext context, SessionStore sessionStore) {
    return super.isAjax(context, sessionStore) || AuthPac4jInfo.isXHR(context);
  }

  @Override
  public HttpAction buildAjaxResponse(
      WebContext context,
      SessionStore sessionStore,
      RedirectionActionBuilder redirectionActionBuilder) {
    // weAccount: only the form client's redirect is built here. Building an OIDC redirect generates
    // and saves a new state (and nonce) in the session, so every unauthenticated XHR, such as a
    // poll after the session expired, overwrote the state of a sign-in already under way in the
    // browser, whose callback then failed with "State parameter is different". The others get the
    // plain 401 from DefaultAjaxRequestResolver, which builds nothing.
    if (isAjax(context, sessionStore)
        && isFormClient(redirectionActionBuilder)
        && getUrl(context, sessionStore, redirectionActionBuilder)
            .filter(url -> url.endsWith(AxelorFormClient.LOGIN_URL))
            .isPresent()) {
      return new UnauthorizedAction();
    }
    return super.buildAjaxResponse(context, sessionStore, redirectionActionBuilder);
  }

  /** Whether this is the redirect builder of the form client (looked up when needed). */
  protected boolean isFormClient(RedirectionActionBuilder redirectionActionBuilder) {
    return Beans.get(Config.class).getClients().getClients().stream()
        .filter(AxelorFormClient.class::isInstance)
        .anyMatch(
            client ->
                ((AxelorFormClient) client).getRedirectionActionBuilder()
                    == redirectionActionBuilder);
  }

  protected Optional<String> getUrl(
      WebContext context,
      SessionStore sessionStore,
      RedirectionActionBuilder redirectionActionBuilder) {
    return redirectionActionBuilder
        .getRedirectionAction(context, sessionStore)
        .filter(WithLocationAction.class::isInstance)
        .map(action -> ((WithLocationAction) action).getLocation());
  }
}
