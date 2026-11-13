package com.axelor.apps.aiaccountant.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.persistence.RollbackException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The mark the drafts-only posting guard reads: open only around a write tool, always closed. */
class AiToolWriteScopeTest {

  @AfterEach
  void ensureNoLeak() {
    assertFalse(AiExecutionContext.isToolWrite(), "tool-write mark leaked out of a test");
  }

  @Test
  void markedOnlyInsideTheCall() {
    assertFalse(AiExecutionContext.isToolWrite());
    assertTrue(AiExecutionContext.callAsToolWrite(AiExecutionContext::isToolWrite));
    assertFalse(AiExecutionContext.isToolWrite());
  }

  @Test
  void nestedCallKeepsTheMarkUntilTheOuterOneEnds() {
    AiExecutionContext.callAsToolWrite(
        () -> {
          AiExecutionContext.callAsToolWrite(() -> null);
          assertTrue(AiExecutionContext.isToolWrite());
          return null;
        });
  }

  @Test
  void clearedWhenTheToolThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            AiExecutionContext.callAsToolWrite(
                () -> {
                  throw new IllegalStateException("boom");
                }));
  }

  @Test
  void refusalIsFoundThroughWrappers() {
    AiDraftOnlyRefusal refusal = new AiDraftOnlyRefusal();
    Exception wrapped =
        new IllegalArgumentException("Axelor refused", new RollbackException("commit", refusal));
    assertSame(refusal, AiDraftOnlyRefusal.find(wrapped));
    assertNull(AiDraftOnlyRefusal.find(new IllegalStateException("other")));
    assertNull(AiDraftOnlyRefusal.find(null));
  }
}
