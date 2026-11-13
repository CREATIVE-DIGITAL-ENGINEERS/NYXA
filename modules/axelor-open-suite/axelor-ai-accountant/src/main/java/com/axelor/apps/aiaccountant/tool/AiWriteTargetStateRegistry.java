package com.axelor.apps.aiaccountant.tool;

import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the {@link AiWriteTargetState} providers and asks each until one answers.
 *
 * <p>Same discovery idiom as {@code AiToolRegistry} and {@code AiPageContextRegistry}: MetaScanner
 * plus Guice, resolved once and cached.
 *
 * <p>First non-null answer wins, rather than concatenating every answer the way page context does.
 * A fingerprint is compared for equality, so combining two providers' opinions would make the
 * result depend on scan order — and a target key belongs to exactly one kind of record anyway.
 */
@Singleton
public class AiWriteTargetStateRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiWriteTargetStateRegistry.class);

  private volatile List<AiWriteTargetState> providers;

  public List<AiWriteTargetState> all() {
    List<AiWriteTargetState> local = providers;
    if (local == null) {
      synchronized (this) {
        if (providers == null) {
          providers = discover();
        }
        local = providers;
      }
    }
    return local;
  }

  /**
   * The current fingerprint of a target, or null when nothing can describe it.
   *
   * <p>Null is a meaningful answer and must stay distinguishable from a real value: the guard
   * treats it as "unknown" and falls back, whereas treating it as a constant would make every
   * target look permanently unchanged.
   */
  public String fingerprint(String targetKey) {
    if (targetKey == null) {
      return null;
    }
    List<AiWriteTargetState> found;
    try {
      found = all();
    } catch (RuntimeException e) {
      // No MetaScanner runtime (a directly-constructed dispatcher in a test). Unknown state, which
      // the guard already handles, rather than an exception thrown into someone's turn.
      LOG.debug("AI Accountant: no write-target state providers available: {}", e.getMessage());
      return null;
    }
    for (AiWriteTargetState provider : found) {
      try {
        String value = provider.fingerprint(targetKey);
        if (value != null && !value.isEmpty()) {
          return value;
        }
      } catch (Exception e) {
        // A loop-breaker must never become the reason a turn fails. Unknown state degrades to the
        // read-based behaviour, which is what this replaced.
        LOG.debug(
            "AI Accountant: write-target state provider {} failed for {}: {}",
            provider.getClass().getSimpleName(),
            targetKey,
            e.getMessage());
      }
    }
    return null;
  }

  protected List<AiWriteTargetState> discover() {
    List<AiWriteTargetState> found = new ArrayList<>();
    for (Class<? extends AiWriteTargetState> type :
        MetaScanner.findSubTypesOf(AiWriteTargetState.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} write-target state provider(s).", found.size());
    return found;
  }
}
