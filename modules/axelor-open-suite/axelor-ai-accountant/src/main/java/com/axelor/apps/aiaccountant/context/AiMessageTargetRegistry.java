package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Finds the {@link AiMessageTargetResolver}s and asks each in turn. */
@Singleton
public class AiMessageTargetRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiMessageTargetRegistry.class);

  private volatile List<AiMessageTargetResolver> resolvers;

  /** Every distinct ref the resolvers name; a resolver that throws contributes nothing. */
  public List<String> resolve(AiDomain domain, String message) {
    // A domain and nothing else: no task, no operation. A resolver that reads the structured
    // intent therefore sees it as unstated, which is the correct reading of a caller that had no
    // route to give — see AiWriteOperation on why unstated is never read as CREATE.
    return resolve(domain == null ? null : new AiRoute(domain, null, null, null, null), message);
  }

  /**
   * As above, given the whole route — see {@link AiMessageTargetResolver#resolve(AiRoute, String)}.
   *
   * <p>This is what {@code AiWorkflowController} calls, so a resolver can read the structured
   * intent the router established rather than guessing it from verbs in the sentence.
   */
  public List<String> resolve(AiRoute route, String message) {
    Set<String> out = new LinkedHashSet<>();
    AiDomain domain = route == null ? null : route.domain();
    if (domain == null || message == null || message.trim().isEmpty()) {
      return new ArrayList<>(out);
    }
    for (AiMessageTargetResolver resolver : all()) {
      try {
        for (String ref : resolver.resolve(route, message)) {
          if (ref != null && !ref.trim().isEmpty()) {
            out.add(ref.trim());
          }
        }
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: message target resolver {} failed: {}",
            resolver.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return new ArrayList<>(out);
  }

  /**
   * See {@link AiMessageTargetResolver#resolveFromContext}; a resolver that throws adds nothing.
   */
  public List<String> resolveFromContext(
      AiRoute route, String message, com.axelor.apps.aiaccountant.dto.PageContext page) {
    Set<String> out = new LinkedHashSet<>();
    if (route == null || route.domain() == null) {
      return new ArrayList<>(out);
    }
    for (AiMessageTargetResolver resolver : all()) {
      try {
        for (String ref : resolver.resolveFromContext(route, message, page)) {
          if (ref != null && !ref.trim().isEmpty()) {
            out.add(ref.trim());
          }
        }
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: context target resolver {} failed: {}",
            resolver.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return new ArrayList<>(out);
  }

  protected List<AiMessageTargetResolver> all() {
    List<AiMessageTargetResolver> local = resolvers;
    if (local == null) {
      synchronized (this) {
        if (resolvers == null) {
          List<AiMessageTargetResolver> found = new ArrayList<>();
          for (Class<? extends AiMessageTargetResolver> type :
              MetaScanner.findSubTypesOf(AiMessageTargetResolver.class).find()) {
            if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
              found.add(Beans.get(type));
            }
          }
          LOG.info("AI Accountant: registered {} message target resolver(s).", found.size());
          resolvers = found;
        }
        local = resolvers;
      }
    }
    return local;
  }
}
