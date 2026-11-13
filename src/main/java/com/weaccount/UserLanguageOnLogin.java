package com.weaccount;

import com.axelor.app.AppSettings;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.common.StringUtils;
import com.axelor.db.JPA;
import com.axelor.event.Observes;
import com.axelor.events.PostLogin;
import com.axelor.inject.Beans;
import javax.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives a user without a language the application's default one at login.
 *
 * <p>Users provisioned from Keycloak are created with no language. The UI copes (it falls back to
 * {@code application.locale}), but several stock reporting charts pass {@code
 * __user__.getLanguage()} as a SQL parameter, and a null there fails in PostgreSQL with "operator
 * does not exist: character varying = bytea". Storing the default the user already sees changes
 * nothing visible; they can still pick another language in their preferences.
 *
 * <p>Never fails the login: a problem is logged and the user keeps an empty language.
 */
public class UserLanguageOnLogin {

  private static final Logger logger = LoggerFactory.getLogger(UserLanguageOnLogin.class);

  void onLoginSuccess(@Observes @Named(PostLogin.SUCCESS) PostLogin event) {
    User loggedIn = event.getUser();
    if (loggedIn == null || StringUtils.notBlank(loggedIn.getLanguage())) {
      return;
    }
    String language = AppSettings.get().get("application.locale", "en");
    try {
      JPA.runInTransaction(
          () -> {
            User user = Beans.get(UserRepository.class).find(loggedIn.getId());
            if (user != null && StringUtils.isBlank(user.getLanguage())) {
              user.setLanguage(language);
              logger.info("User {} had no language; set to {}.", user.getCode(), language);
            }
          });
    } catch (Exception e) {
      logger.error("Could not set the language of user {} at login.", loggedIn.getCode(), e);
    }
  }
}
