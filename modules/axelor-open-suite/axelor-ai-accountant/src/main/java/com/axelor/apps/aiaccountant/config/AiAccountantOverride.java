package com.axelor.apps.aiaccountant.config;

/**
 * What the administrator has decided about one toggle, or that they have decided nothing.
 *
 * <h2>Why three values and not a boolean</h2>
 *
 * A boolean can only say true or false, and the interesting third case — nobody has ever touched
 * this setting — is exactly the one that must not be confused with "off". The database column
 * defaults to {@link #INHERIT} so that a freshly created settings row changes nothing at all: every
 * toggle keeps falling through to the environment and {@code AppSettings}, the way it did before
 * the row existed.
 *
 * <p>{@link #UNAVAILABLE} is not a stored value. It is what the reader returns when the row could
 * not be read at all, and it is deliberately distinct from {@link #INHERIT}: "no override" is a
 * decision, "I could not find out" is an incident, and the write toggle in particular treats them
 * very differently. See {@code AiAccountantSettingOverrideService}.
 */
public enum AiAccountantOverride {
  INHERIT,
  ON,
  OFF,
  UNAVAILABLE;

  /** The stored selection values; they are the domain's own, so they live beside the enum. */
  public static final int VALUE_INHERIT = 0;

  public static final int VALUE_ON = 1;
  public static final int VALUE_OFF = 2;

  public static AiAccountantOverride of(Integer stored) {
    if (stored == null) {
      return INHERIT;
    }
    switch (stored) {
      case VALUE_ON:
        return ON;
      case VALUE_OFF:
        return OFF;
      default:
        // Anything unrecognised reads as INHERIT rather than as a decision: a value from a future
        // version, or a hand-edited row, must not be able to silently switch something off.
        return INHERIT;
    }
  }

  public boolean isDecided() {
    return this == ON || this == OFF;
  }

  public boolean isOn() {
    return this == ON;
  }
}
