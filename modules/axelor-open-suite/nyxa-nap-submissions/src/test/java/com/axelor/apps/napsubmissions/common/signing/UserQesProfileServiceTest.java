package com.axelor.apps.napsubmissions.common.signing;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.auth.db.User;
import org.junit.jupiter.api.Test;

/**
 * One-user-one-profile and the enabled-profile refusals. {@code findForUser} is stubbed, so the
 * service's rules are exercised without the repository/DB (the {@code unique} column is the final
 * guarantee).
 */
class UserQesProfileServiceTest {

  /** A profile service whose lookup and persistence are in-memory stubs (no repository/DB). */
  private static final class StubProfileService extends UserQesProfileService {
    private final UserQesProfile stored;
    UserQesProfile saved;

    StubProfileService(UserQesProfile stored) {
      super(null);
      this.stored = stored;
    }

    @Override
    protected UserQesProfile findForUser(User user) {
      return stored;
    }

    @Override
    public UserQesProfile save(UserQesProfile profile) {
      this.saved = profile;
      return profile;
    }
  }

  private UserQesProfile profile(User user, boolean enabled) {
    UserQesProfile profile = new UserQesProfile();
    profile.setUser(user);
    profile.setEnabled(enabled);
    return profile;
  }

  @Test
  void createProfileRefusesASecondProfileForTheSameUser() {
    User user = new User();
    UserQesProfile existing = profile(user, true);
    StubProfileService service = new StubProfileService(existing);

    assertThrows(QesSigningException.class, () -> service.createProfile(profile(user, true)));
  }

  @Test
  void requireEnabledProfileFailsWhenMissing() {
    StubProfileService service = new StubProfileService(null);
    assertThrows(QesSigningException.class, () -> service.requireEnabledProfile(new User()));
  }

  @Test
  void requireEnabledProfileFailsWhenDisabled() {
    User user = new User();
    StubProfileService service = new StubProfileService(profile(user, false));
    assertThrows(QesSigningException.class, () -> service.requireEnabledProfile(user));
  }

  @Test
  void requireEnabledProfileReturnsTheEnabledProfile() {
    User user = new User();
    UserQesProfile enabled = profile(user, true);
    StubProfileService service = new StubProfileService(enabled);
    assertSame(enabled, service.requireEnabledProfile(user));
  }

  // --- signing uses ONLY the logged-in user's saved profile; the sign screen collects nothing ----

  @Test
  void requireSigningProfileRefusesWithoutSavedEgn() {
    User user = new User();
    StubProfileService service = new StubProfileService(profile(user, true));
    QesSigningException refused =
        assertThrows(QesSigningException.class, () -> service.requireSigningProfile(user));
    assertTrue(refused.getMessage().contains("QES signing profiles"));
  }

  @Test
  void requireSigningProfileReturnsTheSavedIdentityWithoutProviderData() {
    User user = new User();
    UserQesProfile saved = profile(user, true);
    saved.setSignerPin("8001011234");
    saved.setSignerPinTypeSelect("IND_EGN");
    // No provider id / provider reference: USB must not need them.
    assertSame(saved, new StubProfileService(saved).requireSigningProfile(user));
  }
}
