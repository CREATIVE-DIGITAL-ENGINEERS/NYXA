package com.axelor.apps.aiaccountant.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import org.junit.jupiter.api.Test;

/**
 * {@link AiContextBudget#projectedPeakTokens}: only the projected growth is inflated by the peak
 * factor, not the previous turn's already-measured input — see the method javadoc for why
 * multiplying the whole sum double-counted overhead the provider had already reported. With the
 * default configuration (falling back here since this test suite has no injector for {@code
 * AiAccountantConfig}: window 400,000, reserved 32,000, so physical headroom 368,000, clamped down
 * by the conversation-memory preset — which also falls back, to {@code MEDIUM} = 160,000 — to a
 * usable budget of 160,000) and no observed growth (floored to {@code TURN_FLOOR_TOKENS} = 10,000,
 * times the 1.6 peak factor = 16,000 of headroom consumed by projection alone), the trigger point
 * is a previous input of 144,000.
 */
class AiContextBudgetTest {

  private final AiContextBudget budget = new AiContextBudget();

  @Test
  void justBelowTheTriggerPointContinues() {
    assertEquals(AiChainResetReason.NONE, budget.verdict(143_999, 0));
  }

  @Test
  void atTheTriggerPointResets() {
    assertEquals(AiChainResetReason.GROWTH_PROJECTION, budget.verdict(144_000, 0));
  }

  @Test
  void justAboveTheTriggerPointResets() {
    assertEquals(AiChainResetReason.GROWTH_PROJECTION, budget.verdict(144_001, 0));
  }

  @Test
  void theContextLimitStillFiresUnconditionallyOnceOverTheUsableBudget() {
    assertEquals(AiChainResetReason.CONTEXT_LIMIT, budget.verdict(160_000, 0));
  }

  /**
   * The conversation-memory preset is a voluntary, self-imposed cap: choosing Minimal must shrink
   * the usable budget even though the model's real window is far larger.
   */
  @Test
  void aSmallerPresetThanThePhysicalWindowGovernsUsableTokens() {
    AiContextBudget small =
        new AiContextBudget(
            new AiAccountantConfig() {
              @Override
              public int contextWindowTokens() {
                return 400_000;
              }

              @Override
              public int contextReserveTokens() {
                return 32_000;
              }

              @Override
              public int conversationMemoryTokens() {
                return 40_000; // Minimal
              }
            });

    assertEquals(40_000, small.usableChainTokens());
  }

  /**
   * The other direction is the safety property that matters: no preset, however high, may ever push
   * the usable budget past what the model can actually accept.
   */
  @Test
  void thePhysicalWindowGovernsWhenSmallerThanThePreset() {
    AiContextBudget constrained =
        new AiContextBudget(
            new AiAccountantConfig() {
              @Override
              public int contextWindowTokens() {
                return 50_000;
              }

              @Override
              public int contextReserveTokens() {
                return 32_000;
              }

              @Override
              public int conversationMemoryTokens() {
                return 240_000; // High — must not win over an 18,000-token physical headroom.
              }
            });

    assertEquals(18_000, constrained.usableChainTokens());
  }
}
