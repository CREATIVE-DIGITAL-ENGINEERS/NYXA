package com.axelor.apps.aiocr.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the poller's failure handling. A job whose {@code process()} throws must be
 * recorded FAILED via {@code markFailed()} (a fresh transaction) rather than left to revert to
 * QUEUED and loop forever, and one bad job must not stop the batch.
 */
class AiOcrPollJobTest {

  /** A job whose {@code trace()} is a no-op, so no Guice/JPA runtime is needed. */
  private AiOcrPollJob job() {
    return new AiOcrPollJob() {
      @Override
      protected void trace(Throwable t) {
        // swallow — TraceBackService needs the runtime
      }
    };
  }

  private AiOcrUpload upload(long id) {
    AiOcrUpload u = mock(AiOcrUpload.class);
    when(u.getId()).thenReturn(id);
    return u;
  }

  @Test
  void failedProcessIsRecordedFailedNotRequeued() throws Exception {
    BgOcrIntakeService intake = mock(BgOcrIntakeService.class);
    doThrow(new RuntimeException("boom")).when(intake).process(1L);

    job().runBatch(Collections.singletonList(upload(1L)), intake);

    // Terminal FAILED status is recorded in a fresh transaction; the poller never leaves it QUEUED.
    verify(intake).markFailed(1L, "boom");
  }

  @Test
  void oneBadJobDoesNotStopTheBatch() throws Exception {
    BgOcrIntakeService intake = mock(BgOcrIntakeService.class);
    doThrow(new RuntimeException("boom")).when(intake).process(1L);
    // process(2L) is a no-op by default (healthy job).

    job().runBatch(Arrays.asList(upload(1L), upload(2L)), intake);

    verify(intake).markFailed(1L, "boom");
    verify(intake).process(2L); // batch continued past the failure
    verify(intake, never()).markFailed(eq(2L), anyString()); // healthy job untouched
  }

  @Test
  void rootMessageUnwrapsToDeepestCause() {
    Throwable t = new RuntimeException("outer", new IllegalStateException("real reason"));
    assertEquals("real reason", AiOcrPollJob.rootMessage(t));
  }

  @Test
  void rootMessageFallsBackToToStringWhenNoMessage() {
    Throwable t = new RuntimeException();
    assertEquals(t.toString(), AiOcrPollJob.rootMessage(t));
  }
}
