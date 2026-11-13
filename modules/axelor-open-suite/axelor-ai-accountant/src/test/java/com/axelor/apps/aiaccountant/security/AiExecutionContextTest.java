package com.axelor.apps.aiaccountant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The trust boundary. These tests exist because "no user" used to mean "trusted"; the point now is
 * that trust must be opened explicitly, is scoped, and always closes.
 */
class AiExecutionContextTest {

  @AfterEach
  void ensureNoLeak() {
    assertFalse(AiExecutionContext.isSystem(), "system context leaked out of a test");
  }

  @Test
  void defaultIsUntrusted() {
    assertFalse(AiExecutionContext.isSystem());
    assertNull(AiExecutionContext.systemScope());
    assertNull(AiExecutionContext.systemCompanyId());
    // No authenticated user and no system context => not authorised.
    assertFalse(AiExecutionContext.isAuthorisedCaller());
    assertEquals("unauthenticated", AiExecutionContext.callerDescription());
  }

  @Test
  void systemModeIsScopedToOneCompanyAndDescribesItself() {
    String inside =
        AiExecutionContext.callAsSystem(
            42L,
            "ocr-intake",
            () -> {
              assertTrue(AiExecutionContext.isSystem());
              assertTrue(AiExecutionContext.isAuthorisedCaller());
              assertEquals(42L, AiExecutionContext.systemCompanyId());
              return AiExecutionContext.callerDescription();
            });
    assertEquals("system:ocr-intake", inside);
    assertFalse(AiExecutionContext.isSystem(), "closed on the way out");
  }

  @Test
  void contextIsClearedEvenWhenTheWorkThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            AiExecutionContext.runAsSystem(
                1L,
                "boom",
                () -> {
                  throw new IllegalStateException("boom");
                }));
    assertFalse(AiExecutionContext.isSystem(), "a throwing block must still close the context");
  }

  @Test
  void nestingIsRefused() {
    AiExecutionContext.runAsSystem(
        1L,
        "outer",
        () ->
            assertThrows(
                IllegalStateException.class,
                () -> AiExecutionContext.runAsSystem(2L, "inner", () -> {})));
  }

  @Test
  void contextDoesNotLeakToAnotherThread() throws Exception {
    boolean[] seenBySibling = {true};
    AiExecutionContext.runAsSystem(
        1L,
        "outer",
        () -> {
          Thread t = new Thread(() -> seenBySibling[0] = AiExecutionContext.isSystem());
          t.start();
          try {
            t.join();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertFalse(seenBySibling[0], "trust must not spread to other threads");
  }

  @Test
  void runtimeExceptionsPropagateUnwrapped() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AiExecutionContext.callAsSystem(
                    1L,
                    "x",
                    () -> {
                      throw new IllegalArgumentException("original");
                    }));
    assertEquals("original", e.getMessage());
  }
}
