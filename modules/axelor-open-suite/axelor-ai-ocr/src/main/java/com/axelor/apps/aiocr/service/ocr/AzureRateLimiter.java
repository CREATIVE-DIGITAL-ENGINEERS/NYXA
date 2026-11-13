package com.axelor.apps.aiocr.service.ocr;

import com.axelor.app.AppSettings;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide request pacing for Azure Document Intelligence, scoped <b>per Azure resource and per
 * request kind</b>.
 *
 * <h3>Why this exists</h3>
 *
 * The OCR worker pool runs several documents at once ({@code aiocr.process.pool-size}, 3–5) and the
 * split/extract pool adds more, while Azure's free tier (F0) accepts <b>1 transaction per
 * second</b>. Without a shared gate every worker submits whenever it is ready, so most requests
 * collide, come back 429, and are paid for twice — once in the wasted round trip and again in the
 * exponential backoff that follows. The throttling was therefore self-inflicted: the pipeline was
 * its own heaviest competitor for the F0 allowance.
 *
 * <p>This limiter does NOT reduce local parallelism. PDF normalization, slicing, compression and
 * all routing/persistence keep running on as many threads as before; only the HTTP call to Azure is
 * paced. A worker that is waiting for a slot is waiting for a resource it could not have used
 * anyway.
 *
 * <h3>Algorithm — reservation, not a token bucket</h3>
 *
 * Each caller atomically <em>reserves</em> the next free slot and then sleeps until it arrives:
 *
 * <pre>
 *   synchronized { slot = max(now, nextSlot); nextSlot = slot + interval; wait = slot - now; }
 *   sleep(wait);   // OUTSIDE the lock
 * </pre>
 *
 * This gives strict, burst-free spacing (a quiet period does not accumulate credit that would let
 * four workers fire at once), and it is deadlock-free by construction: the lock is held only for a
 * few arithmetic operations and is never held across a sleep, a network call or a database call.
 * Each waiter sleeps on its own reserved slot rather than contending on a condition, so wake-up
 * order matches reservation order and no caller can be starved. Worst-case wait is bounded by
 * {@code (number of threads calling Azure) × interval}, which with the bounded pools above is a few
 * seconds; {@code max-wait-ms} is a final safety cap so a pathological pile-up can never park a
 * worker indefinitely.
 *
 * <h3>Retry-After / 429 feedback</h3>
 *
 * {@link #penalize(long)} raises a shared floor, so when ONE worker is told to back off,
 * <b>every</b> worker on that resource backs off with it instead of continuing to hammer a resource
 * that has just said no. Critically this binds callers that had already reserved a slot and are
 * sleeping on it, not just ones that arrive afterwards — during a 429 storm those sleepers are the
 * majority, and releasing them on their original schedule is precisely what sustains the storm. The
 * penalty is capped ({@link #PENALTY_CAP_MS}) for the same reason {@code
 * AzureDocumentIntelligenceExtractor.RETRY_AFTER_CAP_MS} exists: a multi-hour replenishment hint
 * must not hold an OCR worker thread.
 *
 * <p>Limiters are interned per {@code resource|kind}, so ANALYZE (POST) and POLL (GET) are paced
 * independently — a slow poll loop never consumes the submit allowance.
 */
public class AzureRateLimiter {

  /** Which Azure call is being paced; each kind gets its own independent limiter. */
  public enum Kind {
    /** {@code :analyze} submissions (POST) — the request F0 bills and rate-limits. */
    ANALYZE,
    /** Operation-location result polls (GET). */
    POLL
  }

  /** Longest a single {@link #penalize} may push the shared slot out. */
  static final long PENALTY_CAP_MS = 30_000L;

  private static final ConcurrentHashMap<String, AzureRateLimiter> REGISTRY =
      new ConcurrentHashMap<>();

  private final Object lock = new Object();

  /** Monotonic millisecond timestamp of the next slot nobody has reserved yet. */
  private long nextSlotMs = Long.MIN_VALUE;

  /**
   * Monotonic millisecond timestamp before which NOTHING may be sent to this resource, set by
   * {@link #penalize}. Kept separate from {@link #nextSlotMs} because it has to bind callers that
   * reserved their slot <em>before</em> the penalty arrived and are already sleeping on it.
   */
  private long penaltyUntilMs = Long.MIN_VALUE;

  public AzureRateLimiter() {}

  /**
   * The shared limiter for one Azure resource and one request kind. Interned, so every call site in
   * the process — the whole-document path, the chunked path, and any future caller — paces against
   * the same slot sequence.
   */
  public static AzureRateLimiter forResource(String endpoint, Kind kind) {
    String key = resourceKey(endpoint) + "|" + kind.name();
    return REGISTRY.computeIfAbsent(key, k -> new AzureRateLimiter());
  }

  /** Normalize an endpoint to a stable per-resource key (scheme/case/trailing slash agnostic). */
  static String resourceKey(String endpoint) {
    if (endpoint == null) {
      return "";
    }
    return endpoint.trim().toLowerCase(Locale.ROOT).replaceAll("/+$", "");
  }

  /** Drop every interned limiter. Test-only hook; never called by the pipeline. */
  public static void resetAll() {
    REGISTRY.clear();
  }

  /**
   * Reserve and wait for this limiter's next slot.
   *
   * <p>The wait is re-evaluated after every sleep rather than computed once up front. That is what
   * makes {@link #penalize} binding on callers that had <em>already</em> reserved a slot and were
   * sleeping on it: a caller that wakes into an active penalty does not fire, it re-reserves a
   * fresh slot behind the penalty. Re-reserving (rather than simply waiting for the penalty to
   * lift) is what stops the whole queue being released in one burst the instant it expires — which
   * would hand Azure exactly the spike the backoff was meant to prevent.
   *
   * @return how long this caller waited, in milliseconds (0 when it got an immediate slot)
   * @throws OcrServiceException when the thread is interrupted, or when no slot can be had within
   *     {@code max-wait-ms} — never by proceeding unpaced
   */
  public long acquire(Kind kind) {
    long interval = intervalMs(kind);
    if (interval <= 0) {
      return 0L; // pacing disabled for this kind
    }
    long budget = maxWaitMs();
    long startedAt = now();
    // How much of this caller's wait is attributable to a 429 penalty rather than to ordinary
    // pacing. Reported separately (azurePenaltyWaitMs) because the two have different fixes: more
    // pacing wait means the pipeline is asking for more Azure calls than the tier allows, while
    // penalty wait means ONE 429 is holding every document on this resource. Without the split,
    // both land in azureRateWaitMs and a throttling storm is indistinguishable from normal queuing.
    long penaltyWaitMs = 0L;
    long mySlot;
    synchronized (lock) {
      long now = now();
      mySlot = Math.max(now, nextSlotMs);
      nextSlotMs = mySlot + interval;
    }
    onSlotReserved();
    while (true) {
      long sleepFor;
      synchronized (lock) {
        long now = now();
        if (now >= mySlot && now >= penaltyUntilMs) {
          if (penaltyWaitMs > 0) {
            OcrPerfTrace.add("azurePenaltyWaitMs", penaltyWaitMs);
          }
          return now - startedAt;
        }
        if (now < mySlot) {
          sleepFor = mySlot - now;
        } else {
          // Our slot came up but a penalty landed while we were sleeping on it. Take a new slot
          // after the penalty, keeping this caller in the paced queue instead of releasing it the
          // moment the penalty lifts.
          mySlot = Math.max(penaltyUntilMs, nextSlotMs);
          nextSlotMs = mySlot + interval;
          sleepFor = mySlot - now;
        }
        // The part of this leg that lies before the penalty expiry exists only because of the 429.
        // The `penaltyUntilMs > now` guard is load-bearing, not a micro-optimization: the field
        // starts at Long.MIN_VALUE, and `Long.MIN_VALUE - now` OVERFLOWS to a large POSITIVE
        // value, which made every ordinary pacing wait report as penalty wait on a limiter that
        // had never been penalized at all.
        long behindPenalty = penaltyUntilMs > now ? Math.min(mySlot, penaltyUntilMs) - now : 0L;
        if (behindPenalty > 0) {
          penaltyWaitMs += Math.min(behindPenalty, sleepFor);
        }
      }
      if (sleepFor <= 0) {
        continue;
      }
      // Budget exhausted: fail rather than send. Capping the sleep and returning would turn
      // "wait your turn" into "go now", i.e. the one outcome this class exists to prevent —
      // an unpaced request into a resource that is already refusing traffic. Failing here is
      // recoverable: the document is marked failed with a clear reason and can be retried.
      if (budget > 0 && (now() - startedAt) + sleepFor > budget) {
        throw new OcrServiceException(
            "Azure request pacing could not obtain a slot within "
                + budget
                + " ms (aiocr.azure.rate.max-wait-ms); the request was not sent. The Azure"
                + " resource is heavily rate-limited or backed off — retry this document later.");
      }
      sleepMs(sleepFor);
    }
  }

  /**
   * Hold back <b>every</b> request to this resource for {@code delayMs} — those still to arrive AND
   * those that already hold a reservation and are sleeping on it (see {@link #acquire}). Called
   * when any one caller sees a 429 or a {@code Retry-After}, because the other workers are about to
   * make the same request against the same allowance. Never brings the slot forward.
   */
  public void penalize(long delayMs) {
    if (delayMs <= 0) {
      return;
    }
    long effective = Math.min(delayMs, PENALTY_CAP_MS);
    synchronized (lock) {
      long target = now() + effective;
      if (target > penaltyUntilMs) {
        penaltyUntilMs = target;
      }
      // Also move the reservation cursor, so callers arriving after the penalty queue up behind
      // it instead of all reserving the same instant it expires.
      if (target > nextSlotMs) {
        nextSlotMs = target;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Config + seams (overridable in tests)
  // ---------------------------------------------------------------------------

  /**
   * Minimum spacing between two requests of this kind, in milliseconds. {@code 0} disables pacing.
   * Derived from a requests-per-second setting so the configuration reads like the Azure limit it
   * mirrors (F0 = 1/s for both kinds).
   */
  protected long intervalMs(Kind kind) {
    if (!enabled()) {
      return 0L;
    }
    double perSecond =
        cfgDouble(
            kind == Kind.ANALYZE
                ? "aiocr.azure.rate.analyze-per-second"
                : "aiocr.azure.rate.poll-per-second",
            1.0d);
    if (perSecond <= 0) {
      return 0L;
    }
    return (long) Math.ceil(1000.0d / perSecond);
  }

  protected boolean enabled() {
    return Boolean.parseBoolean(AppSettings.get().get("aiocr.azure.rate.enabled", "true").trim());
  }

  /**
   * Hard ceiling on how long one caller may wait for a slot, so a pathological pile-up can never
   * park a worker. Exceeding it fails the request; it never releases it unpaced. {@code 0} means no
   * ceiling.
   */
  protected long maxWaitMs() {
    return Math.max(0L, cfgLong("aiocr.azure.rate.max-wait-ms", 120_000L));
  }

  /** Monotonic clock in milliseconds (never the wall clock — it must not jump). */
  protected long now() {
    return System.nanoTime() / 1_000_000L;
  }

  /**
   * Test hook: fired right after a caller's slot has been reserved (outside the lock), before it
   * starts sleeping on it. No-op in production; overridden in tests to get a deterministic signal
   * for "this caller has reserved and is about to sleep" instead of racing on wall-clock sleeps.
   */
  protected void onSlotReserved() {}

  protected void sleepMs(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OcrServiceException("Interrupted while waiting for the Azure request rate limit.");
    }
  }

  private static double cfgDouble(String key, double def) {
    try {
      return Double.parseDouble(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (RuntimeException e) {
      return def;
    }
  }

  private static long cfgLong(String key, long def) {
    try {
      return Long.parseLong(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (RuntimeException e) {
      return def;
    }
  }
}
