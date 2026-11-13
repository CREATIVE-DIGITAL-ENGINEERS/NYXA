package com.axelor.apps.aiaccountant.resilience;

import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.tool.AiWriteTargetStateRegistry;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * After a write whose outcome is unknown: did it happen?
 *
 * <h2>The question a retry counter cannot answer</h2>
 *
 * A write that times out is the single most dangerous failure this system can produce, because the
 * request that ran out of time is exactly the one the server had long enough to execute. Retrying
 * it is how one ventilation becomes two accounting moves and one payment becomes two. Reporting it
 * as failed is how an invoice that <em>is</em> in the books is described to an accountant as
 * unposted.
 *
 * <p>So neither is done on a guess. The record's own state is re-read from the database and
 * compared with the fingerprint taken immediately before the call, and only one of the three
 * possible answers permits anything to be tried again.
 *
 * <h2>The identity has to be canonical, or there is nothing to compare</h2>
 *
 * Both fingerprints are taken against the record's <em>canonical, company-scoped</em> identity
 * resolved through {@link AiTargetRegistry} — not against the reference the arguments happened to
 * carry. Two things follow, and both are load-bearing:
 *
 * <ul>
 *   <li>One supplier invoice reaches the tools as {@code Upload#946} and is written as {@code
 *       Invoice#606}. Fingerprinting whichever name the call used would compare two different keys
 *       across a retry and answer "changed" for a record nothing had touched.
 *   <li>A reference the active company cannot resolve yields no canonical at all, and is therefore
 *       never reconciled. Reading another company's record to decide whether this company's write
 *       landed is not evidence; it is a scoping hole with a confident answer attached.
 * </ul>
 *
 * <h2>The three answers</h2>
 *
 * <ul>
 *   <li>{@link Verdict#NOT_WRITTEN} — both fingerprints were readable and are identical. Nothing
 *       about the record moved, so the write did not land. This is the <em>only</em> verdict that
 *       allows a retry, and it is a proof rather than an inference: the fingerprint covers the
 *       Hibernate version counter, which moves on any save from any source.
 *   <li>{@link Verdict#CHANGED} — the record moved. Very probably this write landed and only the
 *       answer was lost; possibly somebody else changed the row in the same seconds. Deliberately
 *       not distinguished, because both readings forbid the same things: no retry, and no claim
 *       that the operation succeeded. A tool that can settle it precisely does so through {@link
 *       AiWriteVerifier}, which is consulted first.
 *   <li>{@link Verdict#UNKNOWN} — no canonical identity, no fingerprint provider, or the read
 *       failed. Nothing is proven, so nothing is permitted.
 * </ul>
 *
 * <p>Note what is <em>not</em> here: any attempt to decide whether the change that landed is the
 * change that was asked for. That is the tool's own question, answered by its verifier against an
 * identity established before the call, or not answered at all.
 */
public final class AiWriteReconciliation {

  private static final Logger LOG = LoggerFactory.getLogger(AiWriteReconciliation.class);

  /** What the record's own state says about a write whose result was lost. */
  public enum Verdict {

    /** Proven not to have happened. The only verdict a retry may be built on. */
    NOT_WRITTEN,

    /** The record moved. Neither retryable nor reportable as a failure. */
    CHANGED,

    /** Nothing could be established. Treated exactly as {@link #CHANGED} by every caller. */
    UNKNOWN;

    /** True when Java can prove the write did not take effect. */
    public boolean provenUnwritten() {
      return this == NOT_WRITTEN;
    }

    /** True when the outcome cannot be stated, so the turn must say so rather than pick one. */
    public boolean isUncertain() {
      return this != NOT_WRITTEN;
    }

    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private AiWriteReconciliation() {}

  /**
   * Re-reads the target and compares it with the state captured before the call.
   *
   * @param probe what was captured before the attempt; its canonical identity is what is read
   * @return the verdict; never null, and {@link Verdict#UNKNOWN} for anything unprovable
   */
  public static Verdict reconcile(AiWriteProbe probe) {
    if (probe == null || probe.canonical() == null || probe.fingerprintBefore() == null) {
      // Either the record has no identity this company can establish, or nothing was able to
      // describe its state before the call. There was never a baseline, so there is nothing to
      // conclude — and a creation legitimately lands here, which is what its verifier is for.
      return Verdict.UNKNOWN;
    }
    String after = authoritativeFingerprint(probe.canonical());
    if (after == null) {
      LOG.warn(
          "AI Accountant: could not re-read {} after an ambiguous write; the outcome is unknown.",
          probe.canonical());
      return Verdict.UNKNOWN;
    }
    if (after.equals(probe.fingerprintBefore())) {
      LOG.info(
          "AI Accountant: {} is unchanged after an ambiguous write; nothing was written.",
          probe.canonical());
      return Verdict.NOT_WRITTEN;
    }
    LOG.warn(
        "AI Accountant: {} changed during an ambiguous write; the outcome cannot be attributed.",
        probe.canonical());
    return Verdict.CHANGED;
  }

  /**
   * The company-scoped canonical identity of a short ref, or null when it cannot be established.
   *
   * <p>Defensive: outside a running application there is no registry, and a reconciliation that
   * threw would replace a knowable outcome with a broken turn. Null degrades to "unprovable", which
   * every caller already handles.
   */
  public static String canonical(String ref) {
    if (ref == null) {
      return null;
    }
    try {
      return Beans.get(AiTargetRegistry.class).canonical(ref);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not canonicalise {}: {}", ref, e.getMessage());
      return null;
    }
  }

  /** The fingerprint of a canonical ref as it stands now, without clearing the session. */
  public static String fingerprint(String canonicalRef) {
    if (canonicalRef == null) {
      return null;
    }
    try {
      return Beans.get(AiWriteTargetStateRegistry.class).fingerprint(canonicalRef);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not fingerprint {}: {}", canonicalRef, e.getMessage());
      return null;
    }
  }

  /**
   * The record's state as the database has it, not as this session remembers it.
   *
   * <p>The persistence context is cleared first. After a rolled-back write the session can still
   * hold the managed entities as they were before the attempt, and a reconciliation that read those
   * would answer "unchanged" for a write that had in fact committed — the one wrong answer this
   * whole mechanism exists to prevent, arriving through a cache.
   *
   * <p>Safe at this point by construction: the failed tool's transaction is already over, tools
   * load their own records by id and hold nothing across calls, and this runs between calls rather
   * than inside one.
   */
  public static String authoritativeFingerprint(String canonicalRef) {
    if (!clearSession()) {
      return null;
    }
    return fingerprint(canonicalRef);
  }

  /**
   * Discards the first-level cache so the next read reaches the database.
   *
   * <p>Public because a tool's own verifier needs the same guarantee before it queries — an
   * existence check answered from a session that still holds the rolled-back entity is not a check.
   * The dispatcher calls it once before consulting either mechanism.
   *
   * @return false when the session could not be cleared, in which case nothing read afterwards is
   *     evidence
   */
  public static boolean clearSession() {
    try {
      JPA.em().clear();
      return true;
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not clear the session before reconciling: {}", e.getMessage());
      return false;
    }
  }
}
