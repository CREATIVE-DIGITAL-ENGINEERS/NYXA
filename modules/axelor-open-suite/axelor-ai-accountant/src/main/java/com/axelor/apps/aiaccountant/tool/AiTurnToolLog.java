package com.axelor.apps.aiaccountant.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What actually happened to every tool call in this turn.
 *
 * <h2>Why this exists</h2>
 *
 * A real failure: a multi-invoice workflow hit the iteration cap after two of three edits, and the
 * assistant's closing sentence said all three invoices had been fixed. Nothing in the server
 * contradicted it, because nothing in the server knew. The turn metrics counted calls, but counts
 * live in a log line and are gone by the time the reply is assembled; {@code AiWriteEffects} knows
 * which records were touched but not which requested operations never ran.
 *
 * <p>So this records the outcome of each call as it happens, and the reply is assembled from it
 * rather than from the model's own account of its work. The model narrates; the server decides what
 * may be claimed.
 *
 * <h2>What it is not</h2>
 *
 * Not a metric — {@code AiTurnMetrics} keeps that job, unchanged. Not a permission check. It holds
 * tool <em>names</em> and outcomes only: no arguments, no results, nothing about a document. It is
 * read within the same turn that wrote it and then discarded.
 *
 * <p>Thread-local and bound by the service around the whole turn, exactly like {@code
 * AiWriteEffects} and {@code AiTurnReadMemory}. {@link #end()} must run in a {@code finally}.
 */
public final class AiTurnToolLog {

  /** How one requested call ended. */
  public enum Status {
    /** Ran and returned a result the model can use. */
    OK,
    /** Ran and was refused, or failed. Nothing was written by it. */
    FAILED,
    /** Never ran: an earlier failure in the same batch, or a duplicate. */
    SKIPPED,
    /**
     * Ran, and nobody can say what it did.
     *
     * <p>Distinct from {@link #FAILED} on the one point that matters: FAILED asserts that nothing
     * was written, and after a lost answer that assertion is not available. Everything that reads
     * this ledger treats it as not-OK — so it is never claimed, never counted as progress and never
     * settles a job — but it is also never described to the user as a failure, because it may well
     * be in the books.
     */
    UNCERTAIN
  }

  /** One call, as executed. */
  public static final class Entry {
    private final String name;
    private final boolean write;
    private final Status status;
    private final String target;
    private final String reason;
    private final String blocker;

    Entry(String name, boolean write, Status status, String target, String reason, String blocker) {
      this.name = name;
      this.write = write;
      this.status = status;
      this.target = target;
      this.reason = reason;
      this.blocker = blocker;
    }

    public String getName() {
      return name;
    }

    public boolean isWrite() {
      return write;
    }

    public Status getStatus() {
      return status;
    }

    /**
     * Which record the call was aimed at, as a short ref, or null when it had no single one.
     *
     * <p>An identity, not an argument. The no-arguments rule this class keeps is about supplier
     * names, IBANs and line descriptions — a record id is what {@code AiWriteEffects} already
     * stores, and without it a batch failure cannot be attributed to the invoice it belongs to,
     * which is what turned one blocked record into a stopped job.
     */
    public String getTarget() {
      return target;
    }

    /** The refusal in the tool's own words, for a failed call; null otherwise. */
    public String getReason() {
      return reason;
    }

    /**
     * What kind of refusal it was, as a code — the {@code blocker} detail, or the envelope's own
     * {@code error} when there was none.
     *
     * <p>Kept beside the reason rather than derived from it. The workflow decides whether to keep
     * working or turn to the user from this field; the reason is what the user is shown. Storing
     * only the reason is what forced that decision to be made by reading prose.
     */
    public String getBlocker() {
      return blocker;
    }
  }

  private static final ThreadLocal<List<Entry>> CURRENT = new ThreadLocal<>();

  private AiTurnToolLog() {}

  public static void begin() {
    CURRENT.set(new ArrayList<>());
  }

  public static void end() {
    CURRENT.remove();
  }

  /** Records one call's outcome. Never throws into the turn it is describing. */
  public static void record(String name, boolean write, Status status) {
    record(name, write, status, null, null, null);
  }

  /** As above, attributing the call to a record and keeping the refusal that stopped it. */
  public static void record(
      String name, boolean write, Status status, String target, String reason) {
    record(name, write, status, target, reason, null);
  }

  /** As above, with the refusal's own code — the field the workflow classifies from. */
  public static void record(
      String name, boolean write, Status status, String target, String reason, String blocker) {
    List<Entry> entries = CURRENT.get();
    if (entries == null || name == null || status == null) {
      return;
    }
    entries.add(new Entry(name, write, status, target, reason, blocker));
  }

  /** Every write that did not succeed, with the record and reason, in execution order. */
  public static List<Entry> failedWrites() {
    List<Entry> out = new ArrayList<>();
    for (Entry e : entries()) {
      if (e.isWrite() && e.getStatus() != Status.OK) {
        out.add(e);
      }
    }
    return out;
  }

  /** Targets of writes that succeeded this turn — the records something really changed on. */
  public static Set<String> writtenTargets() {
    Set<String> targets = new LinkedHashSet<>();
    for (Entry e : entries()) {
      if (e.isWrite() && e.getStatus() == Status.OK && e.getTarget() != null) {
        targets.add(e.getTarget());
      }
    }
    return targets;
  }

  /** Every call this turn, in execution order; empty when no window is open. */
  public static List<Entry> entries() {
    List<Entry> entries = CURRENT.get();
    return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
  }

  public static int count(Status status) {
    int n = 0;
    for (Entry e : entries()) {
      if (e.getStatus() == status) {
        n++;
      }
    }
    return n;
  }

  /** True when at least one write tool of this exact name completed successfully. */
  public static boolean wroteSuccessfully(String toolName) {
    for (Entry e : entries()) {
      if (e.isWrite() && e.getStatus() == Status.OK && e.getName().equals(toolName)) {
        return true;
      }
    }
    return false;
  }

  /** Names of write tools that succeeded this turn — the only writes that may be claimed. */
  public static Set<String> successfulWrites() {
    Set<String> names = new LinkedHashSet<>();
    for (Entry e : entries()) {
      if (e.isWrite() && e.getStatus() == Status.OK) {
        names.add(e.getName());
      }
    }
    return names;
  }

  /** True when nothing at all was written successfully in this turn. */
  public static boolean noSuccessfulWrite() {
    return successfulWrites().isEmpty();
  }

  /**
   * True when a call failed or was skipped and no later call of the same tool succeeded.
   *
   * <p>The distinction matters. A real turn edited an invoice, was refused, re-read the record,
   * edited it again successfully, validated and posted it: one failure, and the work finished. A
   * turn that reports "one call failed" as "the workflow is incomplete" would have called that a
   * failure, and the invoice was in the books. Conversely a batch where the second invoice's edit
   * was skipped leaves that record untouched and unreported, so completion must not be claimed.
   *
   * <p>Same tool NAME, not same target — the log holds no arguments by design. That makes this a
   * conservative approximation in one direction only: a failure on record B masked by a later
   * success on record A. The state check that follows is what bounds the consequence, since it only
   * ever inspects records that were actually written.
   */
  public static boolean hasUnrecoveredFailure() {
    List<Entry> entries = entries();
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      if (e.getStatus() == Status.OK) {
        continue;
      }
      if (e.getStatus() == Status.UNCERTAIN) {
        // An unknown outcome is never recovered by a later success. A second call establishes what
        // the record looks like now; it establishes nothing about what the lost call did, and
        // treating "the record is fine" as "so the timeout was harmless" is the reasoning that
        // turns a double posting into a clean-looking turn.
        return true;
      }
      boolean recovered = false;
      for (int j = i + 1; j < entries.size(); j++) {
        Entry later = entries.get(j);
        if (later.getStatus() == Status.OK && later.getName().equals(e.getName())) {
          recovered = true;
          break;
        }
      }
      if (!recovered) {
        return true;
      }
    }
    return false;
  }

  /** Names of calls that failed or never ran, in order, for a truthful truncation summary. */
  public static List<String> unfinished() {
    List<String> names = new ArrayList<>();
    for (Entry e : entries()) {
      if (e.getStatus() != Status.OK) {
        names.add(e.getName());
      }
    }
    return names;
  }
}
