package com.axelor.apps.aiaccountant.config;

/**
 * The four conversation-memory presets offered on the Settings screen, and the one place their
 * token sizes are decided.
 *
 * <h2>What this actually bounds</h2>
 *
 * The number behind each preset is a self-imposed ceiling on {@code
 * AiContextBudget#usableChainTokens()} — how much retained conversation the chain is allowed to
 * grow to before it resets and starts over with a continuity note. It is a voluntary choice about
 * cost and continuity, not a safety limit: {@code AiContextBudget} always clamps it to the model's
 * real context window minus its output reserve, so a preset can never itself cause a request to be
 * refused, whatever it is set to.
 *
 * <h2>Why a stored code, not the token count itself</h2>
 *
 * {@code AiAccountantSetting.memoryPresetSelect} stores this enum's {@link #storedValue()}, never a
 * token number — the whole point of the simplified Settings screen is that nobody ever types one
 * in. {@link #fromStored} is the one place a stored value becomes a preset, and it reads anything
 * it does not recognise — {@code null} included, which is every row that existed before this
 * feature shipped — as {@link #MEDIUM}, so an upgrade needs no data migration.
 */
public enum AiConversationMemoryPreset {
  MINIMAL(1, 40_000),
  LOW(2, 80_000),
  MEDIUM(3, 160_000),
  HIGH(4, 240_000);

  private final int storedValue;
  private final int tokens;

  AiConversationMemoryPreset(int storedValue, int tokens) {
    this.storedValue = storedValue;
    this.tokens = tokens;
  }

  /** The value persisted in {@code AiAccountantSetting.memoryPresetSelect}. */
  public int storedValue() {
    return storedValue;
  }

  /** The self-imposed ceiling this preset applies to the retained conversation, in tokens. */
  public int tokens() {
    return tokens;
  }

  /**
   * {@code stored} mapped back to a preset, or {@link #MEDIUM} for {@code null} and for any value
   * this enum does not recognise — a hand-edited row or one from a future version included.
   */
  public static AiConversationMemoryPreset fromStored(Integer stored) {
    if (stored != null) {
      for (AiConversationMemoryPreset preset : values()) {
        if (preset.storedValue == stored) {
          return preset;
        }
      }
    }
    return MEDIUM;
  }
}
