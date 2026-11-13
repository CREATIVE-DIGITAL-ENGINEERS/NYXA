package com.axelor.apps.aiaccountant.resilience;

import java.util.Locale;

/**
 * Why a turn did or did not continue the model's retained conversation.
 *
 * <h2>Why these are named once</h2>
 *
 * The reasons used to be string literals scattered across {@code chainDecision}, compared with
 * {@code equals} in one place and written to a metric line in another. Two of them meant the same
 * thing under different spellings, one was tested for by string equality to decide whether to send
 * a continuity handoff, and adding a reason meant remembering both sites. They are a small closed
 * vocabulary describing one decision, so they are an enum.
 *
 * <h2>The two families, and the difference that matters</h2>
 *
 * <ul>
 *   <li><b>Budget resets</b> — {@link #CONTEXT_LIMIT}, {@link #GROWTH_PROJECTION}. The conversation
 *       is unchanged and the same person is carrying on with the same work; only the retained
 *       history has grown too large to keep re-reading. These carry a continuity handoff, because
 *       the authorisation and the job survive the reset.
 *   <li><b>Boundary resets</b> — {@link #NEW_CONVERSATION}, {@link #COMPANY_CHANGE}, {@link
 *       #CONVERSATION_CHANGE}, {@link #NO_CONVERSATION}, {@link #UNSTAMPED_STATE}. Something about
 *       <em>whose</em> conversation this is has changed or cannot be established. These carry
 *       nothing: handing the new chain the old job would be reviving state that this turn has just
 *       decided does not belong to it.
 * </ul>
 *
 * <p>{@link #carriesContinuity()} is that distinction, stated once, so the question is never
 * answered again by comparing a string.
 */
public enum AiChainResetReason {

  /** The chain continues. */
  NONE(false),

  /** No response id at all: a first message, or the client was cleared. */
  NEW_CONVERSATION(false),

  /** The active company changed, so the retained history is about another set of books. */
  COMPANY_CHANGE(false),

  /** The state blob belongs to a different conversation — another tab, a copied session. */
  CONVERSATION_CHANGE(false),

  /** The turn carries no usable conversation id, so no chain can be attributed to it. */
  NO_CONVERSATION(false),

  /** Legacy or missing state that cannot be traced to this conversation. Fails closed. */
  UNSTAMPED_STATE(false),

  /**
   * The next request would not fit inside the model's context window.
   *
   * <p>The bound that is not negotiable. Past it the provider does not degrade, it refuses, and the
   * user loses the turn — so this fires on a <em>projection</em> of the next request rather than on
   * a measurement of the last one.
   */
  CONTEXT_LIMIT(true),

  /**
   * The chain is inside both bounds now but would cross one during the turn about to run.
   *
   * <p>A turn is several requests, each larger than the last. Deciding only from what the previous
   * turn measured means the decision is always one turn stale, which is exactly how a chain sails
   * past a limit it was being checked against.
   */
  GROWTH_PROJECTION(true),

  /**
   * Nothing the server measured describes this chain.
   *
   * <p>No row, an unreadable one, or one too old to be about the conversation the client is still
   * sending an id for. The bounds cannot be checked against a number that does not exist, and the
   * only two options are to continue on the client's word or to start fresh. Continuing on the
   * client's word is precisely what this phase removed.
   */
  UNMEASURED_CHAIN(true),

  /**
   * The client's account of the chain's size and the server's do not match.
   *
   * <p>They should be equal: the client stores what the last turn told it. A material difference
   * means the two are describing different conversations — a stale tab, a replayed blob, an edited
   * number — and there is no way to tell which is right. Reset rather than pick one.
   */
  MEASUREMENT_CONFLICT(true);

  private final boolean carriesContinuity;

  AiChainResetReason(boolean carriesContinuity) {
    this.carriesContinuity = carriesContinuity;
  }

  /**
   * Whether a fresh chain started for this reason should be given the continuity handoff.
   *
   * <p>True only for the budget resets. The user is mid-job, they have already authorised the work,
   * and the only thing that changed is that the history got expensive — dropping the job there
   * would make a cost optimisation look to them like the assistant forgetting. False for every
   * boundary reset, where carrying the job across would be reviving state that does not belong to
   * this turn.
   */
  public boolean carriesContinuity() {
    return carriesContinuity;
  }

  /** True when the previous chain is not continued, whatever the reason. */
  public boolean isReset() {
    return this != NONE;
  }

  /** The form written to metric lines. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
