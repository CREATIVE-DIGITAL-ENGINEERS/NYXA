package com.axelor.apps.aiaccountant.context;

import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the {@link AiCompletionResolver}s whether a target is done.
 *
 * <p>First non-null answer wins: a record is in one state, and a second opinion could only disagree
 * with no way to choose between them.
 *
 * <p>Silence means not done. No resolver for the domain, a resolver that throws, a reference nobody
 * owns — all of them leave the target active. That is the conservative direction here: a job that
 * wrongly stays open costs the user a status line saying work remains, and one that wrongly closes
 * tells them a document is finished when it is not.
 */
@Singleton
public class AiCompletionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiCompletionRegistry.class);

  private volatile List<AiCompletionResolver> resolvers;

  public List<AiCompletionResolver> all() {
    List<AiCompletionResolver> local = resolvers;
    if (local == null) {
      synchronized (this) {
        if (resolvers == null) {
          resolvers = discover();
        }
        local = resolvers;
      }
    }
    return local;
  }

  /** True only when a resolver verified the outcome from ERP state. */
  public boolean isComplete(String ref, String task) {
    if (ref == null) {
      return false;
    }
    for (AiCompletionResolver resolver : all()) {
      try {
        Boolean answer = resolver.isComplete(ref, task);
        if (answer != null) {
          return answer;
        }
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: completion resolver {} failed on '{}': {}",
            resolver.getClass().getSimpleName(),
            ref,
            e.getMessage());
        return false;
      }
    }
    LOG.debug("AI Accountant: no completion resolver for '{}' ({}); left active.", ref, task);
    return false;
  }

  protected List<AiCompletionResolver> discover() {
    List<AiCompletionResolver> found = new ArrayList<>();
    for (Class<? extends AiCompletionResolver> type :
        MetaScanner.findSubTypesOf(AiCompletionResolver.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} completion resolver(s).", found.size());
    return found;
  }
}
