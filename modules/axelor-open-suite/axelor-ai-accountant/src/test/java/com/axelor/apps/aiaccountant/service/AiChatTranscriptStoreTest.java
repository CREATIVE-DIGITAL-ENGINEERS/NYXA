package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The store's FAILURE contract, which its DB-backed sibling cannot provoke.
 *
 * <p>{@link AiChatTranscriptJpaTest} proves what happens when the database works. This proves what
 * happens when it does not, and that is the part with a rule of its own: unlike every other store
 * in this package, this one must never report a message as stored when it is not, and every retry
 * must be a genuinely fresh transaction rather than a second flush into the unit of work that has
 * just rolled back.
 *
 * <p>No mocking framework: the two seams that matter — the transaction boundary and the persistence
 * context — are {@code protected} methods, so a subclass records the order they were called in,
 * which is the whole assertion. That is the same idiom the rest of this module's unit tests use.
 */
class AiChatTranscriptStoreTest {

  /** Records what the store did, and fails the given number of attempts. */
  private static class RecordingStore extends AiChatTranscriptStore {

    /** Attempts that must throw, counted from the first. */
    int failures;

    /** When true, every attempt refuses because a caller already holds a transaction. */
    boolean ambient;

    final List<String> events = new ArrayList<>();
    int attempts;

    @Override
    protected void runInTransaction(Runnable task) {
      attempts++;
      events.add("begin");
      if (ambient) {
        throw new AiTranscriptPersistenceException(
            "The chat transcript cannot be written inside a caller's transaction.", null);
      }
      if (attempts <= failures) {
        // What a unique-constraint collision looks like from here: the transaction is gone and the
        // entity it tried to write is still sitting in the persistence context.
        events.add("rollback");
        throw new IllegalStateException("constraint violation on attempt " + attempts);
      }
      task.run();
      events.add("commit");
    }

    @Override
    protected void clearPersistenceContext() {
      events.add("clear");
    }

    @Override
    protected void persist(
        Long userId,
        Long companyId,
        String conversationId,
        String turnKey,
        int role,
        String content,
        List<String> tools) {
      events.add("persist:" + content);
    }
  }

  @Test
  void aCollidingAttemptIsRetriedInAFreshTransaction() {
    RecordingStore store = new RecordingStore();
    store.failures = 1;

    store.append(7L, 3L, "c-1", "t-1", AiChatTranscriptStore.ROLE_USER, "hello", null);

    // The order is the point: the failed attempt's persistence context is dropped BEFORE the next
    // transaction opens. A retry that skipped the clear would re-flush the row the database just
    // refused, into a unit of work already marked rollback-only.
    assertEquals(
        Arrays.asList("begin", "rollback", "clear", "begin", "persist:hello", "commit"),
        store.events);
    assertEquals(2, store.attempts);
  }

  @Test
  void everyAttemptGetsItsOwnTransaction() {
    RecordingStore store = new RecordingStore();
    store.failures = 2;

    store.append(7L, 3L, "c-1", "t-1", AiChatTranscriptStore.ROLE_ASSISTANT, "done", null);

    assertEquals(AiChatTranscriptStore.MAX_ATTEMPTS, store.attempts);
    // One begin per attempt, and a clear after each failure — never two attempts sharing one.
    assertEquals(3, store.events.stream().filter("begin"::equals).count());
    assertEquals(2, store.events.stream().filter("clear"::equals).count());
    assertEquals(1, store.events.stream().filter("commit"::equals).count());
  }

  /**
   * The rule that separates this store from its neighbours: an append that did not commit is
   * reported as a failure, never swallowed.
   */
  @Test
  void anAppendThatCannotCommitThrowsRatherThanReportingSuccess() {
    RecordingStore store = new RecordingStore();
    store.failures = AiChatTranscriptStore.MAX_ATTEMPTS;

    AiTranscriptPersistenceException thrown =
        assertThrows(
            AiTranscriptPersistenceException.class,
            () ->
                store.append(7L, 3L, "c-1", "t-1", AiChatTranscriptStore.ROLE_USER, "lost", null));

    assertTrue(thrown.getMessage().contains("could not be stored"));
    // The original database failure is carried, not replaced by a message about retrying.
    assertSame(IllegalStateException.class, thrown.getCause().getClass());
    assertEquals(AiChatTranscriptStore.MAX_ATTEMPTS, store.attempts);
  }

  /**
   * A turn with no company in scope has no books to act in and no key to be read back by. It is
   * logged and skipped — never reported as a failure, which would block a turn that was already
   * going to refuse every tool for the same reason.
   */
  @Test
  void aTurnWithNoCompanyInScopeStoresNothingAndDoesNotFail() {
    RecordingStore store = new RecordingStore();

    store.append(7L, null, "c-1", "t-1", AiChatTranscriptStore.ROLE_USER, "no books", null);
    store.append(null, 3L, "c-1", "t-1", AiChatTranscriptStore.ROLE_USER, "nobody", null);

    assertEquals(0, store.attempts);
    assertTrue(store.events.isEmpty());
  }

  /**
   * A refusal to join a caller's transaction is not retried.
   *
   * <p>Retrying would spend two more attempts against the same transaction, which is still there
   * and still not ours to commit, and report the identical refusal three attempts later. Worse, the
   * {@link AiChatTranscriptStore#clearPersistenceContext} between attempts would be discarding the
   * caller's own pending work.
   */
  @Test
  void anAmbientTransactionIsReportedAtOnceAndNotRetried() {
    RecordingStore store = new RecordingStore();
    store.ambient = true;

    assertThrows(
        AiTranscriptPersistenceException.class,
        () -> store.append(7L, 3L, "c-1", "t-1", AiChatTranscriptStore.ROLE_USER, "joined", null));

    assertEquals(1, store.attempts);
    // And the caller's persistence context was left alone: clearing it would discard their pending
    // work, which is not this store's to throw away.
    assertEquals(Arrays.asList("begin"), store.events);
  }

  @Test
  void theToolListSurvivesARoundTrip() {
    String json = AiChatTranscriptStore.serializeTools(Arrays.asList("readInvoice", "postInvoice"));
    assertEquals(
        Arrays.asList("readInvoice", "postInvoice"), AiChatTranscriptStore.parseTools(json));
  }

  @Test
  void anUnreadableToolListCostsTheChipsAndNotTheMessage() {
    assertTrue(AiChatTranscriptStore.parseTools("{not json").isEmpty());
    assertTrue(AiChatTranscriptStore.parseTools(null).isEmpty());
    assertTrue(AiChatTranscriptStore.parseTools("{\"a\":1}").isEmpty());
  }
}
