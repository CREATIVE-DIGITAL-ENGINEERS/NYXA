package com.axelor.apps.aiaccountant.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The recorder's contract: it loses rows rather than costing the caller anything.
 *
 * <p>A metrics write that could block a chat turn, throw into it, or take its transaction down with
 * it would be worse than no metrics at all — hence the emphasis here on what happens when the
 * writer fails or the queue fills, rather than on the happy path.
 */
class AiUsageStatRecorderTest {

  /** Recorder whose write step is stubbed, so no injector, scope or database is involved. */
  private static class TestRecorder extends AiUsageStatRecorder {
    final List<AiUsageSnapshot> written = new CopyOnWriteArrayList<>();
    final AtomicInteger attempts = new AtomicInteger();
    final CountDownLatch latch;
    volatile boolean failWrites;

    TestRecorder(int expected, boolean failWrites) {
      this.latch = new CountDownLatch(expected);
      this.failWrites = failWrites;
    }

    @Override
    protected void writeInOwnScope(AiUsageSnapshot snapshot) {
      // Replaces the request-scope + transaction step; the isolation that step provides is a
      // property of Guice and JPA, not something a unit test can observe.
      attempts.incrementAndGet();
      try {
        if (failWrites) {
          throw new IllegalStateException("insert failed");
        }
        written.add(snapshot);
      } catch (RuntimeException e) {
        // Mirrors the real method, which swallows everything: nothing may escape this thread.
      } finally {
        latch.countDown();
      }
    }
  }

  private static AiUsageSnapshot snapshot() {
    return new AiUsageSnapshot()
        .at(LocalDateTime.now(ZoneOffset.UTC))
        .caller("chat")
        .agentRole(AiRequestMetrics.AGENT_MAIN)
        .outcome("ok");
  }

  @Test
  void recordsWhatItIsGiven() throws Exception {
    TestRecorder recorder = new TestRecorder(1, false);
    recorder.start();
    try {
      recorder.record(snapshot());
      assertTrue(recorder.latch.await(5, TimeUnit.SECONDS), "the worker should drain the queue");
      assertEquals(1, recorder.written.size());
    } finally {
      recorder.shutdown();
    }
  }

  @Test
  void aFailingWriteNeverPropagatesToTheCaller() throws Exception {
    TestRecorder recorder = new TestRecorder(1, true);
    recorder.start();
    try {
      // record() is what a chat turn calls. It must return normally even though the write behind it
      // is broken — the turn is not the place to learn that a metrics table is unavailable.
      recorder.record(snapshot());
      assertTrue(recorder.latch.await(5, TimeUnit.SECONDS));
      assertEquals(1, recorder.attempts.get());
      assertTrue(recorder.written.isEmpty(), "the write failed, and was dropped silently");
    } finally {
      recorder.shutdown();
    }
  }

  @Test
  void dropsInsteadOfBlockingWhenTheQueueIsFull() {
    // A recorder that is running but whose worker never consumes anything, so the queue genuinely
    // fills. Reaching this state in production means a burst outran the writer.
    AiUsageStatRecorder stalled =
        new AiUsageStatRecorder() {
          @Override
          protected void drainUntilStopped() {
            // Deliberately idle.
          }
        };
    stalled.start();
    try {
      int overflow = 25;
      for (int i = 0; i < AiUsageStatRecorder.QUEUE_CAPACITY + overflow; i++) {
        // A blocking put would hang here forever the moment the queue filled; record() must not.
        stalled.record(snapshot());
      }
      assertEquals(
          overflow,
          stalled.droppedCount(),
          "everything past the capacity is dropped, and counted, rather than queued or awaited");
    } finally {
      stalled.shutdown();
    }
  }

  @Test
  void recordIsIgnoredWhenTheRecorderIsNotRunning() {
    TestRecorder recorder = new TestRecorder(1, false);
    // No start(): queuing without a writer would be an unbounded leak on an instance whose
    // assistant is switched off.
    recorder.record(snapshot());
    assertEquals(0, recorder.attempts.get());
    assertFalse(recorder.isRunning());
  }

  @Test
  void startAndShutdownAreIdempotent() {
    AiUsageStatRecorder recorder = new AiUsageStatRecorder();
    recorder.start();
    recorder.start();
    assertTrue(recorder.isRunning());
    recorder.shutdown();
    recorder.shutdown();
    assertFalse(recorder.isRunning(), "a redeploy must not leave the worker behind");
  }

  @Test
  void cachedAndReasoningTokensAreSubsetsAndAreNeverAddedToTheirParents() throws Exception {
    // The exact payload shape FoundryResponse documents from a live call.
    String json =
        "{\"usage\":{\"input_tokens\":5839,"
            + "\"input_tokens_details\":{\"cached_tokens\":5632,\"cache_write_tokens\":0},"
            + "\"output_tokens\":489,"
            + "\"output_tokens_details\":{\"reasoning_tokens\":256},"
            + "\"total_tokens\":6328}}";
    FoundryResponse.Usage usage = new FoundryResponse(new ObjectMapper().readTree(json)).usage();

    assertTrue(usage.isPresent());
    assertTrue(usage.cachedInputTokens() < usage.inputTokens(), "cached is part of input");
    assertTrue(usage.reasoningTokens() < usage.outputTokens(), "reasoning is part of output");
    assertEquals(
        usage.totalTokens(),
        usage.inputTokens() + usage.outputTokens(),
        "the provider's total is input + output; the subsets are already inside those");

    AiUsageSnapshot snapshot =
        snapshot()
            .tokens(
                usage.inputTokens(),
                usage.cachedInputTokens(),
                usage.outputTokens(),
                usage.reasoningTokens(),
                usage.totalTokens());

    // Stored verbatim: no arithmetic, so nothing downstream can be double-counted by construction.
    assertEquals(5839, snapshot.getInputTokens());
    assertEquals(5632, snapshot.getCachedInputTokens());
    assertEquals(489, snapshot.getOutputTokens());
    assertEquals(256, snapshot.getReasoningTokens());
    assertEquals(6328, snapshot.getTotalTokens());
  }
}
