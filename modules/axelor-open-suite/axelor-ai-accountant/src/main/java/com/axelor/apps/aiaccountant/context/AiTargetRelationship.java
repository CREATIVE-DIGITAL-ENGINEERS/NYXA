package com.axelor.apps.aiaccountant.context;

/**
 * Says whether two already-canonical references name related records — a narrower question than
 * {@link AiTargetIdentity#canonical}, which says whether they name the <em>same</em> one.
 *
 * <h2>Why this is not a method on {@code AiTargetIdentity}</h2>
 *
 * {@code AiTargetIdentity} is discovered in {@code MetaScanner} order and the registry takes the
 * first answer, because identity has exactly one owner: a reference names one record, and a second
 * opinion could only disagree. A relationship has no such owner — "is this Partner the current
 * partner of this Invoice?" is answered by reading the invoice, not by whichever resolver happened
 * to be asked first — so it needs its own discovery and its own aggregation rule, not a second
 * method riding on an interface built for single-owner answers.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Both arguments are already canonical — the caller resolves them through {@link
 *       AiTargetRegistry#canonical} first, so a resolver here never parses a raw, unproven ref.
 *   <li>Returns {@code null} when this resolver does not recognise the <em>pairing</em> at all —
 *       not "no". Null lets the registry tell "nobody has an opinion" apart from "someone looked
 *       and said no", which is what makes fail-closed-on-silence possible.
 *   <li>Returns {@code true} or {@code false} only for a pairing it owns, from a fresh,
 *       company-scoped read of the record the relationship is actually about — never a guess and
 *       never a cached answer from earlier in the turn.
 *   <li>Must not throw, and must be cheap — it can run before every related-record write.
 * </ul>
 */
public interface AiTargetRelationship {

  /**
   * @param canonicalA one canonical reference
   * @param canonicalB the other
   * @return {@code true} or {@code false} for a pairing this resolver recognises, or {@code null}
   *     when it is not a pairing this resolver has an opinion about
   */
  Boolean isRelated(String canonicalA, String canonicalB);
}
