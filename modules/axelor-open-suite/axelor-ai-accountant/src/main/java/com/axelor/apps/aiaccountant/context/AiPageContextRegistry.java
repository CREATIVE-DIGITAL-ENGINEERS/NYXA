package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the {@link AiPageRecordResolver}s and asks each in turn.
 *
 * <p>Same discovery idiom as {@code AiToolRegistry}: {@code MetaScanner} plus Guice, resolved once
 * and cached, so a feature module contributes a resolver by implementing the interface and nothing
 * here changes.
 *
 * <p>Every resolver is asked and every non-null answer is kept. Two modules may legitimately have
 * something to say about the same record — an invoice is both an OCR record and an accounting
 * document — and dropping the second answer would make the result depend on scan order.
 */
@Singleton
public class AiPageContextRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiPageContextRegistry.class);

  private volatile List<AiPageRecordResolver> resolvers;

  public List<AiPageRecordResolver> all() {
    List<AiPageRecordResolver> local = resolvers;
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

  /** Every resolver's answer for this record, in discovery order; never null, often empty. */
  public List<String> describe(String model, Long recordId) {
    List<String> out = new ArrayList<>();
    if (model == null || recordId == null) {
      return out;
    }
    for (AiPageRecordResolver resolver : all()) {
      try {
        String line = resolver.describe(model, recordId);
        if (line != null && !line.trim().isEmpty()) {
          out.add(line.trim());
        }
      } catch (Exception e) {
        // Context enrichment is an optimisation. A resolver that fails costs a round trip that
        // would have happened anyway; it must never cost the turn.
        LOG.debug(
            "AI Accountant: page-context resolver {} failed: {}",
            resolver.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return out;
  }

  /**
   * The first resolver that can state this record's current lifecycle state, or null.
   *
   * <p>First rather than all: unlike {@link #describe}, where two modules may each have something
   * to add, a record has one state and repeating it would only pad a message that exists to be read
   * quickly under a failure.
   */
  public String state(String model, Long recordId) {
    if (model == null || recordId == null) {
      return null;
    }
    for (AiPageRecordResolver resolver : all()) {
      try {
        String line = resolver.state(model, recordId);
        if (line != null && !line.trim().isEmpty()) {
          return line.trim();
        }
      } catch (Exception e) {
        LOG.debug(
            "AI Accountant: state resolver {} failed: {}",
            resolver.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return null;
  }

  /**
   * The first resolver that can name this record's lifecycle stage, or null.
   *
   * <p>First, for the same reason as {@link #state}: a record has one lifecycle. Unlike {@code
   * state} this feeds a decision rather than a sentence, so an unrecognised token is discarded here
   * instead of being passed on — a stage nobody defined must not be ranked against one that is.
   */
  public String lifecycle(String model, Long recordId) {
    if (model == null || recordId == null) {
      return null;
    }
    for (AiPageRecordResolver resolver : all()) {
      try {
        String token = resolver.lifecycle(model, recordId);
        if (token == null) {
          continue;
        }
        token = token.trim();
        if (AiWorkflowIntent.DRAFT.equals(token)
            || AiWorkflowIntent.VALIDATED.equals(token)
            || AiWorkflowIntent.POSTED.equals(token)
            || AiWorkflowIntent.CANCELLED.equals(token)) {
          return token;
        }
        LOG.debug(
            "AI Accountant: resolver {} returned an unknown lifecycle token '{}'; ignored.",
            resolver.getClass().getSimpleName(),
            token);
      } catch (Exception e) {
        LOG.debug(
            "AI Accountant: lifecycle resolver {} failed: {}",
            resolver.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return null;
  }

  protected List<AiPageRecordResolver> discover() {
    List<AiPageRecordResolver> found = new ArrayList<>();
    for (Class<? extends AiPageRecordResolver> type :
        MetaScanner.findSubTypesOf(AiPageRecordResolver.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} page-context resolver(s).", found.size());
    return found;
  }
}
