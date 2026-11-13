package com.axelor.apps.napsubmissions.common.signing;

import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.UserQesProfileRepository;
import com.axelor.auth.db.User;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * The one-User → one personal cloud-QES profile mapping. Personal QES only: no company QES and no
 * private-key material.
 *
 * <p><b>⚠️ The stored {@code signerPin} is the signer's НАП IDENTIFIER, not a device code.</b> The
 * contract's {@code userPin} is "идентификатор на потребител, подаващ/подписващ документа" (ЕГН /
 * ЛН-ЛНЧ / служебен номер / ЕИК), so this value is personal data — encrypted at rest and kept out
 * of logs, messages and exceptions for that reason. A PIN that unlocks a КЕП / USB token is never
 * entered into and never stored by Nyxa.
 *
 * <p>Uniqueness is enforced twice on purpose: {@code unique="true"} on {@code UserQesProfile.user}
 * is the hard DB constraint, and {@link #createProfile} refuses a second profile with a friendly
 * message before the insert is attempted. {@link #findForUser} is a {@code protected} seam so the
 * orchestration and its tests resolve a profile without a database.
 */
@Singleton
public class UserQesProfileService {

  protected final UserQesProfileRepository profileRepo;

  @Inject
  public UserQesProfileService(UserQesProfileRepository profileRepo) {
    this.profileRepo = profileRepo;
  }

  /** The user's profile, or {@code null} when none exists. */
  protected UserQesProfile findForUser(User user) {
    if (user == null) {
      return null;
    }
    return profileRepo.all().filter("self.user = :user").bind("user", user).fetchOne();
  }

  /**
   * Resolve the enabled personal QES profile for {@code user}, or fail with a clear, user-facing
   * reason. Never discloses the stored PIN.
   *
   * @throws QesSigningException when no profile exists or it is disabled
   */
  public UserQesProfile requireEnabledProfile(User user) {
    if (user == null) {
      throw new QesSigningException(I18n.get("No authenticated user to sign on behalf of."));
    }
    UserQesProfile profile = findForUser(user);
    if (profile == null) {
      throw new QesSigningException(
          I18n.get("You have no personal QES signing profile. Create one before signing."));
    }
    if (!Boolean.TRUE.equals(profile.getEnabled())) {
      throw new QesSigningException(I18n.get("Your personal QES signing profile is disabled."));
    }
    return profile;
  }

  /**
   * Create the single profile for a user, refusing a duplicate. The DB unique constraint is the
   * final guarantee; this is the friendly pre-check.
   */
  @Transactional
  public UserQesProfile createProfile(UserQesProfile profile) {
    if (profile == null || profile.getUser() == null) {
      throw new QesSigningException(I18n.get("A QES signing profile must belong to a user."));
    }
    UserQesProfile existing = findForUser(profile.getUser());
    boolean sameRow =
        existing != null && existing.getId() != null && existing.getId().equals(profile.getId());
    if (existing != null && !sameRow) {
      throw new QesSigningException(
          I18n.get("This user already has a personal QES signing profile."));
    }
    return profileRepo.save(profile);
  }

  /**
   * Persist an already-existing profile. Used by the signing orchestration to fold the non-secret
   * certificate description onto the profile after a completed signature, including when the
   * signer's certificate has been renewed.
   */
  @Transactional
  public UserQesProfile save(UserQesProfile profile) {
    return profileRepo.save(profile);
  }

  /**
   * Профилът, с който ВЛЕЗЛИЯТ потребител подписва — винаги неговият собствен, никога избран от
   * списък. ЕГН/ЛНЧ се записва веднъж в „НАП › Профили за подписване“ (криптирано); екранът за
   * подаване не събира идентичност. Липсва ли — една конкретна причина, без подписване.
   */
  public UserQesProfile requireSigningProfile(User user) {
    UserQesProfile profile;
    try {
      profile = requireEnabledProfile(user);
    } catch (QesSigningException e) {
      throw new QesSigningException(missingIdentityMessage());
    }
    if (isBlank(profile.getSignerPin()) || isBlank(profile.getSignerPinTypeSelect())) {
      throw new QesSigningException(missingIdentityMessage());
    }
    return profile;
  }

  protected static String missingIdentityMessage() {
    return I18n.get(
        "You have no signing profile with an EGN/LNCh. An administrator records it once in"
            + " “NAP › QES signing profiles” (your user + EGN); after that you sign without"
            + " entering anything here.");
  }

  protected static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
