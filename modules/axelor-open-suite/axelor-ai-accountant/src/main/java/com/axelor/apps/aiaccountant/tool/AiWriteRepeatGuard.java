package com.axelor.apps.aiaccountant.tool;

import com.google.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stops the assistant from staging a write that has already failed, against state that has not
 * changed since it failed.
 *
 * <h2>The loop this exists to break</h2>
 *
 * <p>An approved change fails on a deterministic blocker — a missing account, a supplier that is
 * not a supplier, a record that has no invoice. The model reads the failure, apologises, and
 * immediately proposes the identical call again. The user is asked to approve the same thing a
 * second time, it fails for the same reason, and the turn budget is spent on a conversation that
 * could not have succeeded. Nothing in the write path noticed, because each proposal looked new.
 *
 * <p>A deterministic failure is a fact about the current state, so the only thing that can make the
 * same call worth trying again is the state changing. This tracks exactly that: a failure is
 * remembered together with a {@link AiWriteTargetState fingerprint} of the target at the moment it
 * failed, and the identical call is refused for as long as that fingerprint is unchanged.
 *
 * <h2>Why the fingerprint replaced a read counter</h2>
 *
 * <p>This used to release a failure once the model had <em>read</em> the target again. Counting
 * looks is not the same as looking at state, and it was wrong both ways round. A read that returned
 * an identical record released the block, so the same call ran and failed identically. Worse, a
 * real change made outside the chat released nothing: after the accountant fixed a configuration
 * record in another tab and said "готово", the assistant went on refusing a call that would now
 * have worked, and — having no true reason to give — started inventing them, including telling the
 * user that Axelor had "recorded the failed attempt". Nothing in Axelor had; the refusal was this
 * class.
 *
 * <p>With a fingerprint the release condition is the real one: relevant state differs from what it
 * was when the call failed. A manual UI edit changes it, a corrective write changes it, and reading
 * the record a second time does not — which is correct, because reading changes nothing.
 *
 * <p>When no provider can fingerprint a target the value is null, meaning unknown, and the older
 * read-based release applies unchanged. Unknown state must never harden into a permanent block.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <ul>
 *   <li>It never blocks a <em>different</em> call. Fixing the cause and proposing something else —
 *       a corrected argument, an extra part, another tool — is the intended way forward and is
 *       untouched.
 *   <li>It never blocks the first attempt, and it forgets a target completely once a write on it
 *       succeeds: after a successful apply the previous failure says nothing about the new state.
 *   <li>It is not a security control. It refuses a pointless repetition, not an unauthorised one —
 *       every permission, allowlist and scope check is elsewhere and unchanged.
 * </ul>
 *
 * <p>Keyed by user as well as target, so two accountants working on the same record never inherit
 * each other's failures. In-memory and bounded because the app runs as a single instance, and
 * losing this on restart costs one redundant attempt rather than correctness.
 */
@Singleton
public class AiWriteRepeatGuard {

  /**
   * Most remembered failures. Oldest is evicted first; this is a loop-breaker, not an audit log.
   */
  private static final int MAX_FAILURES = 200;

  /** Most tracked (user, target) read counters. */
  private static final int MAX_TARGETS = 200;

  /** A remembered failure, with the state of its target at the moment it failed. */
  private static final class Failure {
    private final String reason;
    private final long readsAtFailure;

    /** Target state when the call failed; null when nothing could describe it. */
    private final String fingerprintAtFailure;

    Failure(String reason, long readsAtFailure, String fingerprintAtFailure) {
      this.reason = reason;
      this.readsAtFailure = readsAtFailure;
      this.fingerprintAtFailure = fingerprintAtFailure;
    }
  }

  /** (user, target) → how many times that target has been read. */
  private final Map<String, Long> reads = new LinkedHashMap<>();

  /** (user, target, action, arguments) → why it failed. */
  private final Map<String, Failure> failures = new LinkedHashMap<>();

  /**
   * Notes that the caller has just read this target.
   *
   * <p>Only used as the fallback release when no fingerprint is available for the target; where one
   * is, a read releases nothing, because reading is not a change.
   *
   * <p>Called for successful READ tools only. A failed read proves nothing about the state and must
   * not unlock anything.
   */
  public synchronized void recordRead(Long userId, String targetKey) {
    if (userId == null || targetKey == null) {
      return;
    }
    String key = targetKey(userId, targetKey);
    reads.merge(key, 1L, Long::sum);
    evict(reads, MAX_TARGETS);
  }

  /**
   * Remembers that this exact call failed, so an identical one is refused while the target stays as
   * it is.
   *
   * @param reason the failure as the tool reported it, reused verbatim in the refusal so the model
   *     is told the same thing twice rather than something new to interpret
   * @param fingerprint the target's state right now, or null when nothing can describe it
   */
  public synchronized void recordFailure(
      Long userId,
      String targetKey,
      String actionKey,
      String argumentsJson,
      String reason,
      String fingerprint) {
    if (userId == null || targetKey == null || actionKey == null) {
      return;
    }
    failures.put(
        actionKey(userId, targetKey, actionKey, argumentsJson),
        new Failure(reason, reads.getOrDefault(targetKey(userId, targetKey), 0L), fingerprint));
    evict(failures, MAX_FAILURES);
  }

  /**
   * Why this call must not be made again, or {@code null} to allow it.
   *
   * <p>Released as soon as the target's state differs from what it was when the call failed —
   * whoever changed it and however. That is the whole condition when a fingerprint is available: a
   * repeat read does not release anything, because reading is not a change.
   *
   * <p>When the fingerprint is unknown on either side, the older rule applies instead: one read of
   * the target is enough. Unknown state is not a reason to refuse forever.
   *
   * @param fingerprint the target's state now, or null when nothing can describe it
   */
  public synchronized String blockedReason(
      Long userId, String targetKey, String actionKey, String argumentsJson, String fingerprint) {
    if (userId == null || targetKey == null || actionKey == null) {
      return null;
    }
    Failure failure = failures.get(actionKey(userId, targetKey, actionKey, argumentsJson));
    if (failure == null) {
      return null;
    }
    if (fingerprint != null && failure.fingerprintAtFailure != null) {
      return fingerprint.equals(failure.fingerprintAtFailure) ? failure.reason : null;
    }
    long now = reads.getOrDefault(targetKey(userId, targetKey), 0L);
    if (now > failure.readsAtFailure) {
      return null; // state was re-read since — a second attempt is legitimate
    }
    return failure.reason;
  }

  /**
   * Forgets every remembered failure for a target, because a write on it has just succeeded.
   *
   * <p>Load-bearing: without it, a part that failed earlier in a session would stay blocked after
   * the very change that fixed it, and the assistant would refuse to do something that now works.
   */
  public synchronized void clearTarget(Long userId, String targetKey) {
    if (userId == null || targetKey == null) {
      return;
    }
    String prefix = targetKey(userId, targetKey) + "|";
    failures.keySet().removeIf(k -> k.startsWith(prefix));
    // The read counter is kept: it is a monotonic generation number, and resetting it would make a
    // later failure look older than reads that already happened.
  }

  /** Test/diagnostic hook: forget everything. */
  public synchronized void reset() {
    reads.clear();
    failures.clear();
  }

  private static String targetKey(Long userId, String targetKey) {
    return userId + "@" + targetKey;
  }

  private static String actionKey(
      Long userId, String targetKey, String actionKey, String argumentsJson) {
    // The arguments are part of the identity: the same tool on the same record with a corrected
    // value is a genuinely different attempt and must not be blocked by the previous one.
    return targetKey(userId, targetKey)
        + "|"
        + actionKey
        + "|"
        + (argumentsJson == null ? "" : Integer.toHexString(argumentsJson.hashCode()));
  }

  /** Oldest-first eviction; LinkedHashMap keeps insertion order for us. */
  private static <V> void evict(Map<String, V> map, int max) {
    while (map.size() > max) {
      map.remove(map.keySet().iterator().next());
    }
  }
}
