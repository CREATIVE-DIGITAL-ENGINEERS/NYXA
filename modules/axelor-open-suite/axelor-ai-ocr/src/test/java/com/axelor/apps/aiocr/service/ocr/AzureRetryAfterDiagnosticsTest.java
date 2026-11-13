package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor.AzureResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2.1 diagnostics: the RAW {@code Retry-After} Azure asked for must reach the perf trace,
 * kept apart for POST (:analyze) and GET (result poll), and the 202's own defer hint must be
 * honoured before the first poll instead of being dropped.
 *
 * <p>No Azure call is made — the HTTP seams are stubbed, and sleeps are recorded rather than slept.
 */
class AzureRetryAfterDiagnosticsTest {

  private static final String SUCCEEDED =
      "{\"status\":\"succeeded\",\"analyzeResult\":{\"content\":\"c\",\"pages\":[]}}";

  /** Extractor with stubbed transport, no real sleeping and no rate limiting. */
  private static class Stub extends AzureDocumentIntelligenceExtractor {
    final Deque<AzureResponse> submits = new ArrayDeque<>();
    final Deque<AzureResponse> polls = new ArrayDeque<>();
    final List<String> sleepArgs = new ArrayList<>();
    int getCalls = 0;

    /** Index in {@link #sleepArgs} at the moment the first GET was issued. */
    int sleepsBeforeFirstGet = -1;

    @Override
    protected String endpoint() {
      return "https://x.cognitiveservices.azure.com";
    }

    @Override
    protected String key() {
      return "k";
    }

    @Override
    protected int maxRetries() {
      return 2;
    }

    @Override
    protected int pollMaxSeconds() {
      return 100;
    }

    @Override
    protected AzureRateLimiter limiter(AzureRateLimiter.Kind kind) {
      return new AzureRateLimiter() {
        @Override
        protected boolean enabled() {
          return false; // pacing is not under test here
        }
      };
    }

    @Override
    protected void backoff(int attempt, String retryAfter) {
      sleepArgs.add("backoff:" + retryAfter);
    }

    @Override
    protected void sleepBeforeNextPoll(String retryAfter) {
      sleepArgs.add("poll:" + retryAfter);
    }

    @Override
    protected AzureResponse httpSubmit(String u, byte[] b, String m) {
      return submits.poll();
    }

    @Override
    protected AzureResponse httpGet(String u) {
      if (sleepsBeforeFirstGet < 0) {
        sleepsBeforeFirstGet = sleepArgs.size();
      }
      getCalls++;
      AzureResponse next = polls.poll();
      return next != null ? next : new AzureResponse(200, SUCCEEDED, null, null);
    }
  }

  @AfterEach
  void clearTrace() {
    OcrPerfTrace.end();
  }

  // ── POST vs GET attribution ─────────────────────────────────────────────────

  @Test
  void aPostThrottleRecordsTheRawRetryAfterUnderThePostKey() {
    Stub e = new Stub();
    // Azure asks for 45s — far above the 30s we are willing to honour. The RAW value must survive
    // into the trace, because 45 and 3600 mean very different things and the cap hides both.
    e.submits.add(new AzureResponse(429, "{}", null, "45"));
    e.submits.add(new AzureResponse(202, "", "https://x/op/1", null));

    OcrPerfTrace trace = OcrPerfTrace.begin("test", 1L, -1L);
    AzureResponse resp = e.submitWithRetry(new byte[] {1}, "application/pdf", "pages 1-2");

    assertEquals(202, resp.status);
    String s = trace.summary();
    assertTrue(s.contains("azureRetryAfterPostMaxS=45"), s);
    assertTrue(s.contains("azureRetryAfterPostSumS=45"), s);
    assertFalse(s.contains("azureRetryAfterGet"), "a submit throttle is not a poll throttle: " + s);
    assertTrue(s.contains("azure429Count=1"), s);
  }

  @Test
  void aPollThrottleRecordsTheRawRetryAfterUnderTheGetKey() {
    Stub e = new Stub();
    e.polls.add(new AzureResponse(429, "{}", null, "60"));
    e.polls.add(new AzureResponse(200, SUCCEEDED, null, null));

    OcrPerfTrace trace = OcrPerfTrace.begin("test", 1L, -1L);
    e.pollUntilDone("https://x/op/1");

    String s = trace.summary();
    assertTrue(s.contains("azureRetryAfterGetMaxS=60"), s);
    assertFalse(
        s.contains("azureRetryAfterPost"), "a poll throttle is not a submit throttle: " + s);
  }

  @Test
  void theLargestRetryAfterSurvivesRatherThanTheLast() {
    Stub e = new Stub();
    e.polls.add(new AzureResponse(429, "{}", null, "90"));
    e.polls.add(new AzureResponse(429, "{}", null, "5"));
    e.polls.add(new AzureResponse(200, SUCCEEDED, null, null));

    OcrPerfTrace trace = OcrPerfTrace.begin("test", 1L, -1L);
    e.pollUntilDone("https://x/op/1");

    String s = trace.summary();
    assertTrue(s.contains("azureRetryAfterGetMaxS=90"), "the peak must not be averaged away: " + s);
    assertTrue(s.contains("azureRetryAfterGetSumS=95"), s);
  }

  // ── the 202 defer hint is honoured before the first poll ────────────────────

  @Test
  void theAcceptedSubmitsRetryAfterIsWaitedOutBeforeTheFirstPoll() {
    Stub e = new Stub();
    OcrPerfTrace.begin("test", 1L, -1L);

    e.pollUntilDone("https://x/op/1", "3");

    assertEquals(
        1,
        e.sleepsBeforeFirstGet,
        "the 202's Retry-After must be waited out BEFORE the first GET, not after it");
    assertEquals("poll:3", e.sleepArgs.get(0), "the announced delay is the one honoured");
    assertEquals(1, e.getCalls, "one poll sufficed once the analysis had time to finish");
  }

  @Test
  void withoutA202HintTheFirstPollStillFiresImmediately() {
    Stub e = new Stub();
    OcrPerfTrace.begin("test", 1L, -1L);

    e.pollUntilDone("https://x/op/1", null);

    assertEquals(0, e.sleepsBeforeFirstGet, "no hint → no added latency before the first poll");
    assertEquals(1, e.getCalls);
  }
}
