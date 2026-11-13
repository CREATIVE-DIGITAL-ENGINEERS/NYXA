package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.AzureRateLimiter.Kind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared Azure request pacing.
 *
 * <p>Most tests drive a virtual clock so the reservation arithmetic is asserted exactly and
 * instantly; one test uses real threads and real (short) sleeps, because serialization and
 * deadlock-freedom are properties of actual concurrent execution and cannot be proven on a fake
 * clock.
 */
class AzureRateLimiterTest {

  /** Limiter on a virtual clock: {@code sleepMs} advances time instead of blocking. */
  private static class FakeLimiter extends AzureRateLimiter {
    long clock;
    long interval = 1000L;
    long maxWait = 120_000L;
    final List<Long> sleeps = new ArrayList<>();

    /** Fired once, at the start of the first sleep — simulates a 429 landing mid-wait. */
    Runnable onFirstSleep;

    @Override
    protected long intervalMs(Kind kind) {
      return interval;
    }

    @Override
    protected boolean enabled() {
      return true;
    }

    @Override
    protected long maxWaitMs() {
      return maxWait;
    }

    @Override
    protected long now() {
      return clock;
    }

    @Override
    protected void sleepMs(long ms) {
      if (onFirstSleep != null) {
        Runnable hook = onFirstSleep;
        onFirstSleep = null;
        hook.run();
      }
      sleeps.add(ms);
      clock += ms;
    }
  }

  // ── spacing ──────────────────────────────────────────────────────────────────

  @Test
  void spacesConsecutiveRequestsByTheInterval() {
    FakeLimiter l = new FakeLimiter();
    assertEquals(0L, l.acquire(Kind.ANALYZE)); // first one goes straight through
    assertEquals(1000L, l.acquire(Kind.ANALYZE));
    assertEquals(1000L, l.acquire(Kind.ANALYZE));
    assertEquals(2000L, l.clock); // three requests spread over exactly two intervals
  }

  @Test
  void idleTimeDoesNotAccumulateIntoABurst() {
    FakeLimiter l = new FakeLimiter();
    l.acquire(Kind.ANALYZE);
    l.clock = 60_000L; // a long quiet period

    // Exactly ONE request may go immediately; the next still waits a full interval. A token bucket
    // would have banked 60 credits here and let the whole pool fire at once — the precise burst
    // that makes F0 answer 429.
    assertEquals(0L, l.acquire(Kind.ANALYZE));
    assertEquals(1000L, l.acquire(Kind.ANALYZE));
  }

  @Test
  void disabledPacingNeverWaits() {
    FakeLimiter l = new FakeLimiter();
    l.interval = 0L;
    assertEquals(0L, l.acquire(Kind.ANALYZE));
    assertEquals(0L, l.acquire(Kind.ANALYZE));
    assertTrue(l.sleeps.isEmpty());
  }

  // ── Retry-After / 429 feedback ───────────────────────────────────────────────

  @Test
  void penaltyDelaysEverySubsequentCaller() {
    FakeLimiter l = new FakeLimiter();
    l.penalize(5000L); // one worker was told to back off…
    assertEquals(5000L, l.acquire(Kind.ANALYZE)); // …so the next one waits too
  }

  @Test
  void penaltyIsCappedSoAMultiHourRetryAfterCannotParkAWorker() {
    FakeLimiter l = new FakeLimiter();
    l.penalize(TimeUnit.HOURS.toMillis(2));
    assertEquals(AzureRateLimiter.PENALTY_CAP_MS, l.acquire(Kind.ANALYZE));
  }

  @Test
  void penaltyNeverBringsTheSlotForward() {
    FakeLimiter l = new FakeLimiter();
    l.penalize(10_000L);
    l.penalize(1_000L); // a shorter hint must not undo the longer one
    assertEquals(10_000L, l.acquire(Kind.ANALYZE));
  }

  @Test
  void aPenaltyBindsACallerThatIsAlreadySleepingOnAReservedSlot() {
    FakeLimiter l = new FakeLimiter();
    l.acquire(Kind.ANALYZE); // caller A takes slot 0; the cursor is now at 1000

    // Caller B reserves slot 1000 and sleeps. While it sleeps, A gets a 429 with Retry-After 5s.
    l.onFirstSleep = () -> l.penalize(5000L);
    long waited = l.acquire(Kind.ANALYZE);

    // B must NOT fire at its original slot (1000) — that would send a request into a resource
    // that has just told us to back off, which is the whole failure mode penalize() exists for.
    assertEquals(5000L, waited);
    assertEquals(5000L, l.clock);
  }

  @Test
  void callersReleasedAfterAPenaltyAreStillPacedNotBurst() {
    FakeLimiter l = new FakeLimiter();
    l.penalize(5000L);
    // Three callers all held behind one penalty must come out one interval apart, not together:
    // releasing the queue in a burst the instant the penalty lifts re-creates the 429 storm.
    assertEquals(5000L, l.acquire(Kind.ANALYZE));
    assertEquals(1000L, l.acquire(Kind.ANALYZE));
    assertEquals(1000L, l.acquire(Kind.ANALYZE));
    assertEquals(7000L, l.clock);
  }

  // ── max-wait-ms must never release an unpaced request ────────────────────────

  @Test
  void exceedingMaxWaitFailsInsteadOfSendingUnpaced() {
    FakeLimiter l = new FakeLimiter();
    l.maxWait = 250L;
    l.penalize(20_000L);

    // Capping the sleep and returning would turn "wait your turn" into "go now" — an unpaced
    // request into a resource that is already refusing traffic.
    OcrServiceException ex = assertThrows(OcrServiceException.class, () -> l.acquire(Kind.ANALYZE));
    assertTrue(ex.getMessage().contains("was not sent"), ex.getMessage());
    assertTrue(l.sleeps.isEmpty(), "it must not have slept past the budget either");
  }

  @Test
  void zeroMaxWaitMeansNoCeilingNotNoPacing() {
    FakeLimiter l = new FakeLimiter();
    l.maxWait = 0L;
    l.penalize(20_000L);
    // 0 must mean "wait as long as it takes", never "skip the wait".
    assertEquals(20_000L, l.acquire(Kind.ANALYZE));
  }

  // ── scoping ──────────────────────────────────────────────────────────────────

  @Test
  void limitersAreInternedPerResourceAndKind() {
    AzureRateLimiter.resetAll();
    String a = "https://a.cognitiveservices.azure.com";
    String b = "https://b.cognitiveservices.azure.com";

    // Same resource + kind → the SAME limiter, which is what makes pacing process-wide.
    assertSame(
        AzureRateLimiter.forResource(a, Kind.ANALYZE),
        AzureRateLimiter.forResource(a + "/", Kind.ANALYZE));
    // POST and GET are paced independently, so a long poll loop cannot eat the submit allowance.
    assertNotSame(
        AzureRateLimiter.forResource(a, Kind.ANALYZE), AzureRateLimiter.forResource(a, Kind.POLL));
    // A second Azure resource has its own allowance.
    assertNotSame(
        AzureRateLimiter.forResource(a, Kind.ANALYZE),
        AzureRateLimiter.forResource(b, Kind.ANALYZE));
    AzureRateLimiter.resetAll();
  }

  // ── real concurrency ─────────────────────────────────────────────────────────

  @Test
  void concurrentCallersAreSerializedAndNoneDeadlock() throws Exception {
    final long interval = 40L;
    final int threads = 6;
    // Real sleeps, real threads: only the interval is overridden (so AppSettings is not needed).
    AzureRateLimiter limiter =
        new AzureRateLimiter() {
          @Override
          protected long intervalMs(Kind kind) {
            return interval;
          }

          @Override
          protected boolean enabled() {
            return true;
          }

          @Override
          protected long maxWaitMs() {
            return 10_000L;
          }
        };

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Long> grantedAt = Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(
            () -> {
              ready.countDown();
              try {
                go.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              limiter.acquire(Kind.ANALYZE);
              grantedAt.add(System.nanoTime() / 1_000_000L);
            });
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      long start = System.nanoTime() / 1_000_000L;
      go.countDown();

      pool.shutdown();
      // The decisive assertion: every thread finishes. A lock held across the sleep would hang
      // here.
      assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "a caller never got its slot");
      long elapsed = (System.nanoTime() / 1_000_000L) - start;

      assertEquals(threads, grantedAt.size());
      // N callers need at least (N-1) intervals, i.e. they were paced rather than let through
      // together. A generous lower bound keeps this stable on a loaded CI box.
      assertTrue(
          elapsed >= (threads - 1) * interval - 15,
          "expected pacing of ~" + ((threads - 1) * interval) + "ms, got " + elapsed + "ms");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aPenaltyHoldsBackCallersThatAreAlreadyQueuedOnRealThreads() throws Exception {
    final long interval = 150L;
    final long penaltyMs = 400L;
    final int threads = 4;
    // Fires, with the slot already reserved, the instant each caller is about to sleep on it —
    // lets the test wait for "all four have reserved" deterministically instead of guessing a
    // wall-clock margin (a fixed Thread.sleep(50) could be outrun by scheduling jitter, letting a
    // worker's real sleep finish — and grantedAt get recorded — before penalize() ever ran).
    //
    // Each worker is then HELD in the hook until penalize() has run. Waiting for "all have
    // reserved" was not enough on its own: the first worker's slot comes up 150ms after slot 0, and
    // on a loaded machine the test thread could reach penalize() later than that, so that worker
    // was legitimately granted before any penalty existed and the assertion below misfired. Held
    // there, a worker has reserved its slot (it is in flight, which is what this test is about)
    // but cannot be granted before the penalty lands, whatever the scheduling. The test thread's
    // own slot-0 acquire passes straight through.
    CountDownLatch reserved = new CountDownLatch(threads);
    CountDownLatch penalized = new CountDownLatch(1);
    Thread testThread = Thread.currentThread();
    AzureRateLimiter limiter =
        new AzureRateLimiter() {
          @Override
          protected long intervalMs(Kind kind) {
            return interval;
          }

          @Override
          protected boolean enabled() {
            return true;
          }

          @Override
          protected long maxWaitMs() {
            return 0L; // no ceiling: this test is about the penalty, not the budget
          }

          @Override
          protected void onSlotReserved() {
            if (Thread.currentThread() == testThread) {
              return; // slot 0
            }
            reserved.countDown();
            try {
              penalized.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };

    limiter.acquire(Kind.ANALYZE); // occupy slot 0 so the workers below must queue behind it

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Long> grantedAt = Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(
            () -> {
              limiter.acquire(Kind.ANALYZE);
              grantedAt.add(System.nanoTime() / 1_000_000L);
            });
      }
      // All four have now reserved slots at ~150/300/450/600ms and are held in the hook.
      assertTrue(reserved.await(5, TimeUnit.SECONDS));

      long penalizedAt = System.nanoTime() / 1_000_000L;
      limiter.penalize(penaltyMs);
      penalized.countDown();

      pool.shutdown();
      assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "a caller never got its slot");

      assertEquals(threads, grantedAt.size());
      long floor = penalizedAt + penaltyMs - 30L; // 30ms slack for scheduling
      for (Long at : grantedAt) {
        assertTrue(
            at >= floor,
            "a request already queued before the penalty was released "
                + (floor - at)
                + "ms too early — penalize() did not bind in-flight reservations");
      }
    } finally {
      penalized.countDown(); // never leave a worker held if an assertion above failed
      pool.shutdownNow();
    }
  }

  // ── penalty-wait attribution (azurePenaltyWaitMs) ────────────────────────────

  /**
   * Regression: ordinary pacing must NOT be reported as penalty wait.
   *
   * <p>{@code penaltyUntilMs} starts at {@link Long#MIN_VALUE}, and {@code Long.MIN_VALUE - now}
   * overflows to a large POSITIVE value for any positive clock — which would make a limiter that
   * has never been penalized attribute its entire pacing wait to a 429 that never happened. The
   * clock here is deliberately non-zero: at {@code now()==0} the overflow does not trigger and the
   * problem hides.
   */
  @Test
  void pacingWaitIsNotReportedAsPenaltyWaitWhenNoPenaltyEverHappened() {
    FakeLimiter limiter = new FakeLimiter();
    limiter.clock = 50_000L; // a realistic monotonic reading, never 0
    OcrPerfTrace trace = OcrPerfTrace.begin("test", 1L, -1L);
    try {
      assertEquals(0L, limiter.acquire(Kind.ANALYZE), "first caller gets an immediate slot");
      assertEquals(1000L, limiter.acquire(Kind.ANALYZE), "second caller waits one interval");
      assertEquals(List.of(1000L), limiter.sleeps);
      assertFalse(
          trace.summary().contains("azurePenaltyWaitMs"),
          "no 429 ever occurred, so no penalty wait may be reported: " + trace.summary());
    } finally {
      OcrPerfTrace.end();
    }
  }

  /** The counterpart: a real penalty IS attributed, so the fix did not just silence the metric. */
  @Test
  void waitingBehindARealPenaltyIsReportedAsPenaltyWait() {
    FakeLimiter limiter = new FakeLimiter();
    limiter.clock = 50_000L;
    OcrPerfTrace trace = OcrPerfTrace.begin("test", 1L, -1L);
    try {
      limiter.penalize(5_000L);
      assertEquals(5000L, limiter.acquire(Kind.ANALYZE), "the caller waits out the penalty");
      assertTrue(
          trace.summary().contains("azurePenaltyWaitMs=5000"),
          "the penalty wait must be attributed: " + trace.summary());
    } finally {
      OcrPerfTrace.end();
    }
  }
}
