package com.axelor.apps.nyxasecurity.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.auth.pac4j.local.AxelorAjaxRequestResolver;
import com.axelor.auth.pac4j.local.AxelorFormClient;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.exception.http.FoundAction;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.exception.http.UnauthorizedAction;
import org.pac4j.core.redirect.RedirectionActionBuilder;

/**
 * The platform's AxelorAjaxRequestResolver (patched in axelor-core, whose own test suite isn't part
 * of this build): an unauthenticated XHR must not build an identity-provider redirect. Building one
 * for OIDC saves a new state in the session and breaks a sign-in already under way in the browser
 * ("State parameter is different").
 */
class AxelorAjaxRequestResolverTest {

  @Test
  void anXhrForAnotherClientGetsA401WithoutBuildingTheRedirect() {
    AtomicInteger built = new AtomicInteger();
    RedirectionActionBuilder oidc =
        (context, store) -> {
          built.incrementAndGet();
          return Optional.of(new FoundAction("https://idp.example.com/auth?state=new"));
        };

    HttpAction action = resolver(false).buildAjaxResponse(xhr(), store(), oidc);

    assertEquals(0, built.get());
    assertEquals(HttpConstants.UNAUTHORIZED, action.getCode());
  }

  @Test
  void anXhrForTheFormClientStillGetsItsUnauthorizedAction() {
    RedirectionActionBuilder form =
        (context, store) -> Optional.of(new FoundAction(AxelorFormClient.LOGIN_URL));

    HttpAction action = resolver(true).buildAjaxResponse(xhr(), store(), form);

    assertTrue(action instanceof UnauthorizedAction);
  }

  private static AxelorAjaxRequestResolver resolver(boolean formClient) {
    return new AxelorAjaxRequestResolver() {
      @Override
      protected boolean isFormClient(RedirectionActionBuilder redirectionActionBuilder) {
        return formClient;
      }
    };
  }

  /** A request marked as XHR; every other lookup is empty. */
  private static WebContext xhr() {
    return stub(
        WebContext.class,
        (name, args) ->
            "getRequestHeader".equals(name) && HttpConstants.AJAX_HEADER_NAME.equals(args[0])
                ? Optional.of(HttpConstants.AJAX_HEADER_VALUE)
                : null);
  }

  private static SessionStore store() {
    return stub(SessionStore.class, (name, args) -> null);
  }

  private interface Answer {
    Object answer(String method, Object[] args);
  }

  /** An interface stub: the answer, else empty / false / null by return type. */
  @SuppressWarnings("unchecked")
  private static <T> T stub(Class<T> type, Answer answer) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              Object value = answer.answer(method.getName(), args == null ? new Object[0] : args);
              if (value != null) {
                return value;
              }
              Class<?> returns = method.getReturnType();
              if (returns == Optional.class) {
                return Optional.empty();
              }
              if (returns == boolean.class) {
                return false;
              }
              return null;
            });
  }
}
