package com.axelor.apps.aiaccountant.service;

import java.util.regex.Pattern;

/**
 * The identity of one chat conversation, as the server is willing to read it.
 *
 * <h2>Validated, never trusted</h2>
 *
 * The value arrives from the browser and is used as part of a key: which model chain may be
 * continued, and which authorised job may be resumed. It is never rendered, never logged as a
 * record identifier and never resolved to anything, so the risk is not injection — it is a value so
 * loose that two conversations could collide, or so long that it turns a lookup into a scan. A
 * short, bounded, opaque token is checked here and everywhere else reads only the checked form.
 *
 * <p>What it does <em>not</em> do is decide who may act. A caller can put any conversation id they
 * like in the request; what it gets them is their own conversation. The user and the company are
 * still resolved server-side from the session, and every authorisation is scoped by all three.
 * Guessing another tab's id gains nothing that the user could not already do in that tab.
 */
public final class AiConversationKey {

  /** Long enough for a UUID with a prefix; short enough that a key stays a key. */
  public static final int MAX_LENGTH = 64;

  /** Opaque token: letters, digits, dash and underscore. No spaces, no punctuation, no unicode. */
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8," + MAX_LENGTH + "}");

  private AiConversationKey() {}

  /**
   * The conversation id, or null when the client did not send a usable one.
   *
   * <p>Null is a legitimate answer and not an error: it means this turn belongs to no conversation
   * the server can name, so it chains onto nothing and resumes nothing. A client that has not been
   * updated therefore keeps working — it simply gets a fresh model conversation each turn and no
   * standing authorisation, which is the behaviour of a first message.
   */
  public static String of(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return VALID.matcher(trimmed).matches() ? trimmed : null;
  }

  /** True when both sides name the same conversation. Null never matches, not even another null. */
  public static boolean same(String a, String b) {
    String left = of(a);
    String right = of(b);
    return left != null && left.equals(right);
  }
}
