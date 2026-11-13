package com.axelor.apps.aiaccountant.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * The one place the four conversation-memory presets are mapped to tokens, and the one place a
 * stored value that means nothing (including {@code null}, every row from before this feature
 * shipped) is read as {@link AiConversationMemoryPreset#MEDIUM}.
 */
class AiConversationMemoryPresetTest {

  @Test
  void minimalIsFortyThousandTokens() {
    assertEquals(40_000, AiConversationMemoryPreset.MINIMAL.tokens());
    assertSame(AiConversationMemoryPreset.MINIMAL, AiConversationMemoryPreset.fromStored(1));
  }

  @Test
  void lowIsEightyThousandTokens() {
    assertEquals(80_000, AiConversationMemoryPreset.LOW.tokens());
    assertSame(AiConversationMemoryPreset.LOW, AiConversationMemoryPreset.fromStored(2));
  }

  @Test
  void mediumIsOneHundredSixtyThousandTokensAndIsTheRecommendedDefault() {
    assertEquals(160_000, AiConversationMemoryPreset.MEDIUM.tokens());
    assertSame(AiConversationMemoryPreset.MEDIUM, AiConversationMemoryPreset.fromStored(3));
  }

  @Test
  void highIsTwoHundredFortyThousandTokens() {
    assertEquals(240_000, AiConversationMemoryPreset.HIGH.tokens());
    assertSame(AiConversationMemoryPreset.HIGH, AiConversationMemoryPreset.fromStored(4));
  }

  @Test
  void nullStoredValueReadsAsMedium() {
    // Every AiAccountantSetting row that existed before this feature shipped has NULL here — no
    // migration back-fills it. This is what makes that safe.
    assertSame(AiConversationMemoryPreset.MEDIUM, AiConversationMemoryPreset.fromStored(null));
  }

  @Test
  void unrecognisedStoredValueReadsAsMediumTooNotJustNull() {
    // A hand-edited row, or one written by a future version with a fifth preset this build does
    // not know about, must degrade the same safe way as a missing value.
    assertSame(AiConversationMemoryPreset.MEDIUM, AiConversationMemoryPreset.fromStored(99));
    assertSame(AiConversationMemoryPreset.MEDIUM, AiConversationMemoryPreset.fromStored(0));
  }
}
