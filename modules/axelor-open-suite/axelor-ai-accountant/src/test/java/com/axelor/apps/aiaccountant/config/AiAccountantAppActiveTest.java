package com.axelor.apps.aiaccountant.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * App activation is the outermost gate: it must override every finer toggle, and it must fail
 * closed when its own state cannot be resolved.
 */
class AiAccountantAppActiveTest {

  private static AiAccountantConfig config(boolean appActive, Map<String, String> settings) {
    return new AiAccountantConfig() {
      @Override
      public boolean isAppActive() {
        return appActive;
      }

      @Override
      protected String cfg(String key, String def) {
        return settings.getOrDefault(key, def);
      }
    };
  }

  @Test
  void inactiveAppDisablesEverythingRegardlessOfEnvironment() {
    Map<String, String> settings = new HashMap<>();
    settings.put("aiaccountant.enabled", "true");

    AiAccountantConfig config = config(false, settings);

    assertFalse(config.isEnabled(), "app inactive must win over aiaccountant.enabled=true");
  }

  @Test
  void activeAppFallsThroughToTheOrdinaryEnabledChain() {
    Map<String, String> settings = new HashMap<>();
    settings.put("aiaccountant.enabled", "true");

    assertTrue(config(true, settings).isEnabled());
  }

  @Test
  void unresolvableAppStateFailsClosed() {
    // No isAppActive() override here: this exercises the real implementation, which calls
    // Beans.get(AppBaseService.class). No injector exists in a plain unit test, so the lookup
    // throws and must resolve to "not active" — never to "assume installed".
    AiAccountantConfig config =
        new AiAccountantConfig() {
          @Override
          protected String cfg(String key, String def) {
            return "aiaccountant.enabled".equals(key) ? "true" : def;
          }
        };

    assertFalse(config.isAppActive(), "an unresolvable app state must fail closed, not open");
    assertFalse(config.isEnabled());
  }
}
