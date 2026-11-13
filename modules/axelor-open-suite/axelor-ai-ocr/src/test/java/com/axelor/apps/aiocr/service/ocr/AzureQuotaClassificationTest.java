package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor.AzureResponse;
import com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor.QuotaState;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/**
 * A spent monthly allowance and a momentary rate dip both arrive as HTTP 403 with the word "quota"
 * in the body. Telling them apart is what decides whether the document waits out the full
 * retry/backoff budget before failing, or fails at once with a reason the accountant can act on.
 */
class AzureQuotaClassificationTest {

  /** Extractor with stubbed transport + config, so no live Azure/AppSettings/sleeps are needed. */
  private static class TestExtractor extends AzureDocumentIntelligenceExtractor {
    final Deque<Object> submitScript = new ArrayDeque<>();
    int submitCalls = 0;

    @Override
    protected String endpoint() {
      return "https://quota-test.cognitiveservices.azure.com";
    }

    @Override
    protected String key() {
      return "k";
    }

    @Override
    protected int maxRetries() {
      return 2; // → 3 total attempts when a response IS retryable
    }

    @Override
    protected int quotaExhaustedThresholdSeconds() {
      return 300;
    }

    @Override
    protected void backoff(int attempt, String retryAfter) {}

    @Override
    protected AzureResponse httpSubmit(String u, byte[] b, String m) throws IOException {
      submitCalls++;
      Object next = submitScript.poll();
      if (next instanceof IOException) {
        throw (IOException) next;
      }
      return (AzureResponse) next;
    }
  }

  private static AzureResponse quota(String message, String retryAfter) {
    return new AzureResponse(
        403, "{\"error\":{\"code\":\"403\",\"message\":\"" + message + "\"}}", null, retryAfter);
  }

  // ── classification ───────────────────────────────────────────────────────────

  @Test
  void monthlyAllowanceWithLongReplenishmentIsExhausted() {
    TestExtractor e = new TestExtractor();
    assertEquals(
        QuotaState.EXHAUSTED,
        e.classifyQuota(
            quota("Out of call volume quota. Quota will be replenished in 2 days.", null)));
    assertEquals(
        QuotaState.EXHAUSTED,
        e.classifyQuota(
            quota("Out of call volume quota. Quota will be replenished in 1 hour.", null)));
  }

  @Test
  void shortReplenishmentIsOnlyTemporary() {
    TestExtractor e = new TestExtractor();
    assertEquals(
        QuotaState.TEMPORARY,
        e.classifyQuota(
            quota("Out of call volume quota. Quota will be replenished in 12 seconds.", null)));
  }

  @Test
  void quotaWithNoStatedDelayIsTreatedAsExhausted() {
    // An allowance that does not say when it returns is not evidence that it returns soon;
    // assuming "soon" is exactly what produced the futile retries.
    TestExtractor e = new TestExtractor();
    assertEquals(QuotaState.EXHAUSTED, e.classifyQuota(quota("Out of call volume quota.", null)));
  }

  @Test
  void aLongRetryAfterAloneMarksItExhausted() {
    TestExtractor e = new TestExtractor();
    assertEquals(
        QuotaState.EXHAUSTED, e.classifyQuota(quota("Out of call volume quota.", "86400")));
    assertEquals(QuotaState.TEMPORARY, e.classifyQuota(quota("out of quota", "5")));
  }

  @Test
  void nonQuota403AndOther403sAreNotQuotaAtAll() {
    TestExtractor e = new TestExtractor();
    // A permission / disabled-key / network-ACL refusal must never be classified as quota.
    assertEquals(
        QuotaState.NONE,
        e.classifyQuota(quota("Access denied due to invalid subscription key.", null)));
    // 429 is rate limiting, not a quota 403.
    assertEquals(QuotaState.NONE, e.classifyQuota(new AzureResponse(429, "", null, "1")));
  }

  @Test
  void replenishmentDelayIsParsedPerUnit() {
    assertEquals(
        30L, AzureDocumentIntelligenceExtractor.replenishSeconds("replenished in 30 secs"));
    assertEquals(
        120L, AzureDocumentIntelligenceExtractor.replenishSeconds("replenished in 2 minutes"));
    assertEquals(
        7200L, AzureDocumentIntelligenceExtractor.replenishSeconds("replenished in 2 hrs"));
    assertEquals(
        172_800L, AzureDocumentIntelligenceExtractor.replenishSeconds("replenished in 2 days"));
    assertEquals(0L, AzureDocumentIntelligenceExtractor.replenishSeconds("no delay mentioned"));
  }

  // ── retry behaviour ──────────────────────────────────────────────────────────

  @Test
  void exhaustedQuotaFailsImmediatelyWithoutBurningRetries() {
    TestExtractor e = new TestExtractor();
    e.submitScript.add(
        quota("Out of call volume quota. Quota will be replenished in 2 days.", null));

    OcrServiceException ex =
        assertThrows(
            OcrServiceException.class, () -> e.submitWithRetry(new byte[] {1}, "application/pdf"));

    assertEquals(1, e.submitCalls, "a confirmed exhausted allowance must not be retried");
    assertTrue(ex.getMessage().contains("NOT retried"), ex.getMessage());
    assertTrue(ex.getMessage().contains("not temporary rate limiting"), ex.getMessage());
    // Azure's own wording is still quoted, so the reader sees when it replenishes.
    assertTrue(ex.getMessage().contains("replenished in 2 days"), ex.getMessage());
  }

  @Test
  void quotaWithNoStatedDelayFailsFastButDoesNotClaimAConfirmedExhaustion() {
    TestExtractor e = new TestExtractor();
    e.submitScript.add(quota("Out of call volume quota.", null));

    OcrServiceException ex =
        assertThrows(
            OcrServiceException.class, () -> e.submitWithRetry(new byte[] {1}, "application/pdf"));

    // Still fail-fast — retrying a quota refusal inside 30s almost never succeeds…
    assertEquals(1, e.submitCalls);
    // …but Azure named no recovery time, so the message must not assert that the monthly
    // allowance is spent. That would send the reader to check a quota that may be healthy.
    assertTrue(
        ex.getMessage().contains("did not state when the allowance returns"), ex.getMessage());
    assertTrue(ex.getMessage().contains("retry this document"), ex.getMessage());
    assertFalse(ex.getMessage().contains("not temporary rate limiting"), ex.getMessage());
    assertFalse(ex.getMessage().contains("allowance exhausted"), ex.getMessage());
  }

  @Test
  void temporaryQuotaStillUsesTheFullRetryBudget() {
    TestExtractor e = new TestExtractor();
    for (int i = 0; i < 3; i++) {
      e.submitScript.add(
          quota("Out of call volume quota. Quota will be replenished in 5 seconds.", null));
    }
    assertThrows(
        OcrServiceException.class, () -> e.submitWithRetry(new byte[] {1}, "application/pdf"));
    assertEquals(3, e.submitCalls, "a short, self-clearing dip is still worth waiting out");
  }

  @Test
  void plain429IsStillRetried() {
    TestExtractor e = new TestExtractor();
    e.submitScript.add(new AzureResponse(429, "", null, "1"));
    e.submitScript.add(new AzureResponse(202, "", "https://op", null));
    AzureResponse r = e.submitWithRetry(new byte[] {1}, "application/pdf");
    assertEquals(202, r.status);
    assertEquals(2, e.submitCalls);
  }
}
