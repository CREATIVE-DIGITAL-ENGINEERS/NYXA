package com.axelor.apps.aiaccountant.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.db.AiAccountantSetting;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Override precedence, and how an unreadable settings row degrades for the remaining toggles. */
class AiAccountantSettingOverrideTest {

  /** Stub over the one query in the service, so no database or injector is needed. */
  private static class StubService extends AiAccountantSettingOverrideService {
    private final AiAccountantSetting row;
    private final boolean fail;
    final AtomicInteger fetches = new AtomicInteger();

    StubService(AiAccountantSetting row, boolean fail) {
      this.row = row;
      this.fail = fail;
    }

    @Override
    protected AiAccountantSetting fetch() {
      fetches.incrementAndGet();
      if (fail) {
        throw new IllegalStateException("database is unavailable");
      }
      return row;
    }
  }

  /**
   * Config whose env/AppSettings layer is a plain map, wired to the given override service.
   *
   * <p>{@code isAppActive} is stubbed to {@code true}: this suite is about override precedence, not
   * about the app-activation gate, which {@link AiAccountantAppActiveTest} covers on its own.
   */
  private static AiAccountantConfig config(
      AiAccountantSettingOverrideService overrides, Map<String, String> settings) {
    return new AiAccountantConfig() {
      @Override
      public boolean isAppActive() {
        return true;
      }

      @Override
      protected AiAccountantSettingOverrideService overridesOrNull() {
        return overrides;
      }

      @Override
      protected String cfg(String key, String def) {
        return settings.getOrDefault(key, def);
      }

      @Override
      protected int cfgInt(String key, int def) {
        return settings.containsKey(key) ? Integer.parseInt(settings.get(key)) : def;
      }
    };
  }

  private static AiAccountantSetting row() {
    AiAccountantSetting setting = new AiAccountantSetting();
    setting.setSingletonKey(AiAccountantSettingOverrideService.SINGLETON_KEY);
    return setting;
  }

  @Test
  void noRowMeansInheritAndNeverCreatesOne() {
    StubService overrides = new StubService(null, false);
    Map<String, String> settings = new HashMap<>();
    settings.put("aiaccountant.enabled", "true");

    // Inherit, not "off": an absent row is a meaningful state, not a hole to be filled. Had the
    // service created a row here, it would have answered with that row's default instead.
    assertEquals(AiAccountantOverride.INHERIT, overrides.enabled());
    assertTrue(config(overrides, settings).isEnabled());
    assertTrue(overrides.fetches.get() > 0, "the row must actually be read, not assumed");
  }

  @Test
  void explicitOverrideBeatsTheEnvironment() {
    AiAccountantSetting setting = row();
    setting.setEnabledSelect(AiAccountantOverride.VALUE_OFF);
    StubService overrides = new StubService(setting, false);

    Map<String, String> settings = new HashMap<>();
    settings.put("aiaccountant.enabled", "true");
    AiAccountantConfig config = config(overrides, settings);

    assertFalse(config.isEnabled(), "the row says off, the environment says on: the row wins");
  }

  @Test
  void otherTogglesInheritWhenTheRowCannotBeRead() {
    StubService overrides = new StubService(null, true);
    Map<String, String> settings = new HashMap<>();
    settings.put("aiaccountant.enabled", "true");

    // Unlike the write toggle, degrading these to the deployment's own configuration is safe.
    assertEquals(AiAccountantOverride.UNAVAILABLE, overrides.enabled());
    assertTrue(config(overrides, settings).isEnabled());
    assertNull(overrides.routerMode());
    assertNull(overrides.maxToolIterations());
  }

  @Test
  void unrecognisedStoredValueReadsAsInherit() {
    AiAccountantSetting setting = row();
    setting.setEnabledSelect(99);
    StubService overrides = new StubService(setting, false);
    assertEquals(AiAccountantOverride.INHERIT, overrides.enabled());
  }

  @Test
  void toolIterationOverrideIsGuardedAndClamped() {
    AiAccountantSetting setting = row();
    setting.setMaxToolIterations(9);
    StubService overrides = new StubService(setting, false);

    // The guard is off, so the typed number is ignored entirely.
    assertNull(overrides.maxToolIterations());

    setting.setOverrideMaxToolIterations(true);
    assertEquals(9, overrides.maxToolIterations());

    setting.setMaxToolIterations(0);
    assertEquals(
        AiAccountantSettingOverrideService.MIN_TOOL_ITERATIONS,
        overrides.maxToolIterations(),
        "zero would stop the tool loop before it started");

    setting.setMaxToolIterations(5000);
    assertEquals(
        AiAccountantSettingOverrideService.MAX_TOOL_ITERATIONS, overrides.maxToolIterations());
  }

  @Test
  void routerModeOverrideMapsToTheWireValues() {
    AiAccountantSetting setting = row();
    StubService overrides = new StubService(setting, false);
    assertNull(overrides.routerMode(), "unset inherits");

    setting.setRouterModeSelect(1);
    assertEquals(AiAccountantConfig.ROUTER_MODE_SHADOW, overrides.routerMode());

    setting.setRouterModeSelect(2);
    assertEquals(AiAccountantConfig.ROUTER_MODE_ACTIVE, overrides.routerMode());
    assertTrue(config(overrides, new HashMap<>()).isRouterActiveMode());
  }

  @Test
  void noRowMeansMediumConversationMemory() {
    StubService overrides = new StubService(null, false);
    assertSame(AiConversationMemoryPreset.MEDIUM, overrides.conversationMemoryPreset());
  }

  @Test
  void anExistingRowFromBeforeThisFeatureShippedReadsAsMedium() {
    // The column is added by a schema update with no SQL default, so every row that existed
    // before this feature shipped has NULL here. This is the "no data migration" guarantee.
    AiAccountantSetting setting = row();
    StubService overrides = new StubService(setting, false);
    assertSame(AiConversationMemoryPreset.MEDIUM, overrides.conversationMemoryPreset());
  }

  @Test
  void anExplicitPresetIsHonoured() {
    AiAccountantSetting setting = row();
    setting.setMemoryPresetSelect(AiConversationMemoryPreset.HIGH.storedValue());
    StubService overrides = new StubService(setting, false);
    assertSame(AiConversationMemoryPreset.HIGH, overrides.conversationMemoryPreset());
  }

  @Test
  void anUnreadableRowAlsoFallsBackToMedium() {
    StubService overrides = new StubService(null, true);
    assertSame(AiConversationMemoryPreset.MEDIUM, overrides.conversationMemoryPreset());
  }

  @Test
  void configConversationMemoryTokensMatchesTheStoredPreset() {
    AiAccountantSetting setting = row();
    setting.setMemoryPresetSelect(AiConversationMemoryPreset.LOW.storedValue());
    StubService overrides = new StubService(setting, false);

    AiAccountantConfig config = config(overrides, new HashMap<>());

    assertEquals(AiConversationMemoryPreset.LOW.tokens(), config.conversationMemoryTokens());
  }
}
