package com.axelor.apps.aiaccountant.route;

/**
 * How firmly the turn's goal was actually stated.
 *
 * <h2>Why this is separate from the goal</h2>
 *
 * A goal of {@code POSTED} can arrive three ways, and they are not interchangeable. The user said
 * «осчетоводи» in this message; the user said it two turns ago and now says «продължи»; or nobody
 * said it and the assistant is looking at a validated invoice and inferring. The first authorises a
 * posting outright, the second authorises it for the records already in scope, and the third
 * authorises nothing at all.
 *
 * <p>Collapsing the three into "the goal is POSTED" is how an authorisation drifts. Recording the
 * provenance beside the goal keeps that distinction available to whatever reads it.
 */
public enum AiExplicitness {

  /** This turn's own words named the destination. */
  EXPLICIT,

  /** «Продължи» — the goal comes from the instruction already on record, verbatim. */
  INHERITED,

  /**
   * Nothing named a destination, but the turn is plainly about a document.
   *
   * <p>Not an authorisation. It says the subject is known, not that anything may be written to it.
   */
  IMPLIED,

  /** No destination and nothing to infer one from. */
  NONE
}
