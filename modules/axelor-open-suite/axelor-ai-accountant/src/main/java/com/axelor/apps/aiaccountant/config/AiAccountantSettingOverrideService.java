package com.axelor.apps.aiaccountant.config;

import com.axelor.apps.aiaccountant.db.AiAccountantSetting;
import com.axelor.apps.aiaccountant.db.repo.AiAccountantSettingRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the administrator's operational toggles. Reads only — this never creates the row.
 *
 * <h2>Why a config read must not write</h2>
 *
 * The obvious implementation of "find the settings row, or make one" put a database INSERT behind
 * every call to {@code isEnabled()}. That is wrong in three separate ways: a read that mutates is a
 * surprise to every caller, the insert joins whatever transaction the caller happens to have open,
 * and two concurrent first-reads race to create the same unique row so one of them fails on a
 * constraint — on the request path, for a setting nobody had configured. The row is created in
 * exactly one place, by {@code AiAccountantSettingController} when an administrator opens the
 * Settings screen. Its absence is not a hole to be filled; it is a meaningful state that means
 * "inherit everything".
 *
 * <h2>Three outcomes, not two</h2>
 *
 * <ul>
 *   <li><b>No row, or the toggle says inherit</b> — {@link AiAccountantOverride#INHERIT}. Not an
 *       error. {@code AiAccountantConfig} falls through to env and {@code AppSettings}, unchanged.
 *   <li><b>The toggle is set</b> — {@link AiAccountantOverride#ON}/{@link
 *       AiAccountantOverride#OFF}.
 *   <li><b>The read failed</b> — {@link AiAccountantOverride#UNAVAILABLE}. Conflating this with
 *       "inherit" is the dangerous mistake: it would mean that a database blip silently hands
 *       control back to an environment variable.
 * </ul>
 *
 * <p>Nothing is cached for speed: each call re-reads, so a change on the Settings screen applies to
 * the next call rather than to the next restart.
 */
@Singleton
public class AiAccountantSettingOverrideService {

  private static final Logger LOG =
      LoggerFactory.getLogger(AiAccountantSettingOverrideService.class);

  /** The value of the unique column that makes this entity a singleton. */
  public static final String SINGLETON_KEY = "default";

  public AiAccountantOverride enabled() {
    return read(AiAccountantSetting::getEnabledSelect);
  }

  public AiAccountantOverride routerEnabled() {
    return read(AiAccountantSetting::getRouterEnabledSelect);
  }

  /**
   * The router mode override, or null to inherit.
   *
   * <p>An unreadable row inherits here. The mode only decides whether a routing answer is acted on
   * or merely observed, and both are safe: the fallback adapter runs either way and every
   * authorisation check happens afterwards regardless.
   */
  public String routerMode() {
    AiAccountantSetting row = rowOrNull();
    if (row == null || row.getRouterModeSelect() == null) {
      return null;
    }
    switch (row.getRouterModeSelect()) {
      case ROUTER_MODE_SHADOW:
        return AiAccountantConfig.ROUTER_MODE_SHADOW;
      case ROUTER_MODE_ACTIVE:
        return AiAccountantConfig.ROUTER_MODE_ACTIVE;
      default:
        return null;
    }
  }

  /** The tool-iteration override, or null when the guard flag is off or the row cannot be read. */
  public Integer maxToolIterations() {
    AiAccountantSetting row = rowOrNull();
    if (row == null
        || !Boolean.TRUE.equals(row.getOverrideMaxToolIterations())
        || row.getMaxToolIterations() == null) {
      return null;
    }
    int value = row.getMaxToolIterations();
    // Bounded here rather than trusted: the field is an ordinary integer on a form, and a zero
    // would stop the tool loop before it started while a very large number would let one turn spend
    // the whole deadline. Clamped rather than rejected, so a mistyped value degrades instead of
    // breaking the assistant.
    if (value < MIN_TOOL_ITERATIONS || value > MAX_TOOL_ITERATIONS) {
      LOG.warn(
          "AI Accountant: max tool iterations {} is outside {}..{}; clamping.",
          value,
          MIN_TOOL_ITERATIONS,
          MAX_TOOL_ITERATIONS);
      value = Math.min(MAX_TOOL_ITERATIONS, Math.max(MIN_TOOL_ITERATIONS, value));
    }
    return value;
  }

  /**
   * The conversation-memory preset, defaulting to {@link AiConversationMemoryPreset#MEDIUM} when
   * there is no row, the field was never set, or the row cannot be read — the same {@code null}
   * behaves as {@link AiConversationMemoryPreset#MEDIUM} an upgrade needs with no data migration.
   */
  public AiConversationMemoryPreset conversationMemoryPreset() {
    AiAccountantSetting row = rowOrNull();
    return AiConversationMemoryPreset.fromStored(row == null ? null : row.getMemoryPresetSelect());
  }

  public static final int MIN_TOOL_ITERATIONS = 1;

  public static final int MAX_TOOL_ITERATIONS = 20;

  protected static final int ROUTER_MODE_SHADOW = 1;

  protected static final int ROUTER_MODE_ACTIVE = 2;

  /** Reads one tri-state column, mapping an unreadable row to {@code UNAVAILABLE}. */
  protected AiAccountantOverride read(Selector selector) {
    AiAccountantSetting row;
    try {
      row = fetch();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read the settings row: {}", e.getMessage());
      return AiAccountantOverride.UNAVAILABLE;
    }
    return row == null ? AiAccountantOverride.INHERIT : AiAccountantOverride.of(selector.of(row));
  }

  /** The row, or null both when there is none and when it cannot be read. */
  protected AiAccountantSetting rowOrNull() {
    try {
      return fetch();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read the settings row: {}", e.getMessage());
      return null;
    }
  }

  /** The one query in this class. No create, no save. */
  protected AiAccountantSetting fetch() {
    return Beans.get(AiAccountantSettingRepository.class)
        .all()
        .filter("self.singletonKey = :key")
        .bind("key", SINGLETON_KEY)
        .fetchOne();
  }

  /** So the tri-state readers can share one code path without reflection. */
  protected interface Selector {
    Integer of(AiAccountantSetting row);
  }
}
