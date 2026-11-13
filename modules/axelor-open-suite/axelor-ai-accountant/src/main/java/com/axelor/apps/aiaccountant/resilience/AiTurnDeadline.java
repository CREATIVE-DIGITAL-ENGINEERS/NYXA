package com.axelor.apps.aiaccountant.resilience;

/**
 * How much of this turn's time is left, readable from anywhere inside it.
 *
 * <h2>Why a deadline and not a set of timeouts</h2>
 *
 * Per-request timeouts bound one call. They say nothing about a turn that makes five model requests
 * with two retries each, every one of them just inside its own limit: the individual numbers all
 * look reasonable and the person waiting has been staring at a spinner for four minutes. The only
 * bound that describes what the user experiences is a single clock started when the turn starts.
 *
 * <p>So everything that is about to spend time asks this first — a model request, a backoff sleep,
 * another loop iteration, a tool. The answer is advisory in one direction only: it can stop work
 * from being <em>started</em>, and it never interrupts work already running. Cancelling a write
 * halfway through is precisely the thing this phase exists to avoid producing.
 *
 * <h2>Why a deadline here cannot detach a write</h2>
 *
 * The dangerous shape a deadline can take is "the caller gives up and returns while the work
 * carries on somewhere else": the turn reports a timeout, the transaction commits a second later,
 * and the record ends up in a state nobody was told about. That shape is structurally absent here,
 * and the reasons are worth writing down because they are properties of the code rather than
 * intentions:
 *
 * <ul>
 *   <li><b>Nothing about a write is asynchronous.</b> A chat turn runs on one request thread.
 *       {@code AiToolDispatcher} calls {@code tool.execute} directly, the tool calls its service,
 *       and the service's {@code @Transactional} commits before {@code execute} returns. There is
 *       no queue, no executor and no future anywhere between the turn and the database.
 *   <li><b>This class never interrupts anybody.</b> It offers {@link #isExpired()}, {@link
 *       #remainingMillis()} and a sleep that shortens itself. It holds no thread reference, and
 *       there is no cancel path — so an expired deadline cannot reach a transaction even in
 *       principle.
 *   <li><b>The two background executors in the module never carry a write.</b> {@code
 *       AiRouteShadow}'s thread makes one stateless, tool-free HTTP classification and writes a log
 *       line; it touches no ERP record. {@code AiOcrProcessingExecutor} is fed only by the upload
 *       controller and by intake itself — never by a chat turn — and runs under its own lifecycle
 *       with no turn deadline bound at all.
 *   <li><b>The interrupt flag is only ever restored, never raised.</b> Every {@code
 *       Thread.currentThread().interrupt()} in this module sits in a {@code catch
 *       (InterruptedException)} around a sleep or an HTTP call, which is the standard way to hand
 *       the flag back to the caller. None of them is inside a transaction, and none targets another
 *       thread.
 * </ul>
 *
 * <p>What remains is the honest residue: a write can still lose its <em>answer</em> — the commit
 * happens and the caller never learns of it. That is not something a deadline can prevent, and it
 * is the case {@code AiWriteReconciliation} and {@code AiWriteVerifier} exist to settle afterwards.
 *
 * <h2>Lifetime</h2>
 *
 * Thread-local and bound around the whole turn, like {@code AiWriteEffects}, {@code
 * AiTurnReadMemory} and {@code AiTurnToolLog}. {@link #end()} must run in a {@code finally}.
 * Outside a turn — the OCR intake pipeline, a test, a direct Java caller — nothing is bound and
 * {@link #remainingMillis()} answers {@link Long#MAX_VALUE}, so every code path behaves exactly as
 * it did before this existed.
 */
public final class AiTurnDeadline {

  private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

  private AiTurnDeadline() {}

  /** Starts the clock. Always pair with {@link #end()} in a {@code finally}. */
  public static void begin(long budgetMillis) {
    if (budgetMillis <= 0) {
      // An unbounded turn is a legitimate configuration (0 disables the bound); binding nothing is
      // how the rest of the code reads "no deadline".
      DEADLINE_NANOS.remove();
      return;
    }
    DEADLINE_NANOS.set(System.nanoTime() + budgetMillis * 1_000_000L);
  }

  public static void end() {
    DEADLINE_NANOS.remove();
  }

  /** True when a deadline is in force on this thread. */
  public static boolean isBound() {
    return DEADLINE_NANOS.get() != null;
  }

  /**
   * Milliseconds left before the turn's budget is spent.
   *
   * <p>{@link Long#MAX_VALUE} when no deadline is bound — every caller treats that as "no bound"
   * rather than special-casing the unbound path.
   */
  public static long remainingMillis() {
    Long deadline = DEADLINE_NANOS.get();
    if (deadline == null) {
      return Long.MAX_VALUE;
    }
    long remaining = (deadline - System.nanoTime()) / 1_000_000L;
    return Math.max(0L, remaining);
  }

  /** True when the budget is spent, so nothing further should be started. */
  public static boolean isExpired() {
    Long deadline = DEADLINE_NANOS.get();
    return deadline != null && System.nanoTime() >= deadline;
  }

  /**
   * Sleeps, but never past the deadline, and never at all once it has passed.
   *
   * @return false when the thread was interrupted, so the caller stops rather than looping
   */
  public static boolean sleep(long millis) {
    long capped = Math.min(millis, remainingMillis());
    if (capped <= 0) {
      return true;
    }
    try {
      Thread.sleep(capped);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
