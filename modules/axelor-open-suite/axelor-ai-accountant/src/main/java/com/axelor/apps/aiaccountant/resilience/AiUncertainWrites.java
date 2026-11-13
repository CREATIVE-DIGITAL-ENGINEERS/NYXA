package com.axelor.apps.aiaccountant.resilience;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Records whose write outcome this turn could not establish.
 *
 * <h2>Why it is separate from the tool ledger</h2>
 *
 * {@code AiTurnToolLog} answers "what happened to each requested call". This answers a different
 * and narrower question that several unrelated places need: is there a record in this turn about
 * which nothing may be asserted? The reply needs it to warn the user, the workflow needs it to stay
 * unresolved, the batch runner needs it to stop dependent writes, and the metrics need to count it.
 * Deriving it four times by scanning the ledger for a particular status would be four chances to
 * derive it differently.
 *
 * <p>An entry here is the strongest statement this system makes about not knowing something. It
 * never expires within the turn, is never cleared by a later success, and nothing removes an entry
 * — a subsequent successful write on the same record establishes the record's <em>current</em>
 * state but not what the lost call did, and merging those two facts is exactly the reasoning that
 * turns an uncertain outcome into a confident wrong one.
 *
 * <p>Thread-local and turn-scoped, like its neighbours. {@link #end()} must run in a {@code
 * finally}.
 *
 * <p>Outside a turn nothing is bound and every method is a no-op. That is correct for the OCR
 * intake pipeline as it stands — it makes one write per document and has no conversation to warn —
 * but it does mean the cross-call block below is not in force there. The refusal itself still is:
 * the dispatcher returns {@code uncertain_write} on either channel, so an unattended caller is told
 * the outcome is unknown and cannot mistake it for a failure. Opening a window around a multi-write
 * unattended pass is the change to make if one ever exists.
 */
public final class AiUncertainWrites {

  /** Ceiling, so a pathological batch cannot grow the turn's memory without bound. */
  private static final int MAX_RECORDS = 20;

  private static final ThreadLocal<Set<String>> TARGETS = new ThreadLocal<>();

  private AiUncertainWrites() {}

  public static void begin() {
    TARGETS.set(new LinkedHashSet<>());
  }

  public static void end() {
    TARGETS.remove();
  }

  /**
   * Notes that a write on this record ended without a knowable outcome.
   *
   * @param target the record's short ref, or null when the write named none — a nameless uncertain
   *     write is still recorded, under a placeholder, because "something may have been written and
   *     we cannot say to what" is the most serious case of all and must not vanish for want of a
   *     key
   */
  public static void record(String target) {
    Set<String> set = TARGETS.get();
    if (set == null || set.size() >= MAX_RECORDS) {
      return;
    }
    set.add(target == null ? "?" : target);
  }

  /** True when this turn has at least one write it cannot account for. */
  public static boolean any() {
    Set<String> set = TARGETS.get();
    return set != null && !set.isEmpty();
  }

  /** True when this particular record has an unaccounted write this turn. */
  public static boolean contains(String target) {
    Set<String> set = TARGETS.get();
    return set != null && target != null && set.contains(target);
  }

  /** The affected records, in the order they became uncertain. Never null. */
  public static List<String> snapshot() {
    Set<String> set = TARGETS.get();
    return set == null ? new ArrayList<>() : new ArrayList<>(set);
  }
}
