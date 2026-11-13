package com.axelor.apps.aiaccountant.route;

import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the {@link AiRecordDomainResolver}s which domain a model or reference belongs to.
 *
 * <h2>Why this one refuses on disagreement rather than taking the first answer</h2>
 *
 * Every other registry here takes the first non-null answer, which is correct when the resolvers
 * own disjoint kinds — and they are only disjoint by convention, because {@code MetaScanner}
 * returns implementations in classpath-scan order and nothing enforces it. For a description or a
 * fingerprint the cost of a collision is a worse answer. Here the answer decides whether a tool
 * call belongs to the job the user authorised, so a collision resolved by scan order is an
 * authorisation decided by scan order, and which of the two resolvers wins could change between
 * builds without a line of code moving.
 *
 * <p>So every resolver is asked, and disagreement is an error rather than a race: the domain comes
 * back null, the caller treats the record as one it cannot classify, and the mistake is a log line
 * naming both answers instead of an invisible coin flip. Agreement between two resolvers is fine —
 * it is only a duplicate, not a contradiction.
 */
@Singleton
public class AiRecordDomainRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiRecordDomainRegistry.class);

  private volatile List<AiRecordDomainResolver> resolvers;

  public List<AiRecordDomainResolver> all() {
    List<AiRecordDomainResolver> local = resolvers;
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

  /** The domain owning this entity name, or null when nobody owns it or two disagree. */
  public AiDomain domainOfModel(String model, Long recordId) {
    if (model == null || model.trim().isEmpty()) {
      return null;
    }
    String trimmed = model.trim();
    AiDomain answer = null;
    AiRecordDomainResolver answered = null;
    for (AiRecordDomainResolver resolver : all()) {
      AiDomain candidate;
      try {
        candidate = resolver.domainOfModel(trimmed, recordId);
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: domain resolver {} failed on model '{}': {}",
            resolver.getClass().getSimpleName(),
            trimmed,
            e.getMessage());
        continue;
      }
      if (candidate == null) {
        continue;
      }
      if (answer != null && answer != candidate) {
        LOG.error(
            "AI Accountant: {} and {} disagree about model '{}' (id {}) — {} vs {}. Refusing to"
                + " classify it rather than letting scan order decide.",
            answered.getClass().getSimpleName(),
            resolver.getClass().getSimpleName(),
            trimmed,
            recordId,
            answer,
            candidate);
        return null;
      }
      answer = candidate;
      answered = resolver;
    }
    return answer;
  }

  /** The domain owning this canonical reference, or null when nobody owns it or two disagree. */
  public AiDomain domainOfRef(String ref) {
    if (ref == null || ref.trim().isEmpty()) {
      return null;
    }
    String trimmed = ref.trim();
    AiDomain answer = null;
    AiRecordDomainResolver answered = null;
    for (AiRecordDomainResolver resolver : all()) {
      AiDomain candidate;
      try {
        candidate = resolver.domainOfRef(trimmed);
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: domain resolver {} failed on ref '{}': {}",
            resolver.getClass().getSimpleName(),
            trimmed,
            e.getMessage());
        continue;
      }
      if (candidate == null) {
        continue;
      }
      if (answer != null && answer != candidate) {
        LOG.error(
            "AI Accountant: {} and {} disagree about ref '{}' — {} vs {}. Refusing to classify it"
                + " rather than letting scan order decide.",
            answered.getClass().getSimpleName(),
            resolver.getClass().getSimpleName(),
            trimmed,
            answer,
            candidate);
        return null;
      }
      answer = candidate;
      answered = resolver;
    }
    return answer;
  }

  protected List<AiRecordDomainResolver> discover() {
    List<AiRecordDomainResolver> found = new ArrayList<>();
    for (Class<? extends AiRecordDomainResolver> type :
        MetaScanner.findSubTypesOf(AiRecordDomainResolver.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} record-domain resolver(s).", found.size());
    return found;
  }
}
