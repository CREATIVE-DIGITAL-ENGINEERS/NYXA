package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.route.AiDomain;
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

/**
 * Finds the {@link AiWorklistProvider}s and asks each in turn.
 *
 * <p>Same discovery idiom and the same failure policy as {@link AiPageContextRegistry}: a provider
 * that throws costs the model a lookup it would have done anyway, never the turn.
 */
@Singleton
public class AiWorklistRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiWorklistRegistry.class);

  private volatile List<AiWorklistProvider> providers;

  public List<AiWorklistProvider> all() {
    List<AiWorklistProvider> local = providers;
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

  /** Every provider's line, in discovery order; never null, often empty. */
  public List<String> describe() {
    List<String> out = new ArrayList<>();
    for (AiWorklistProvider provider : all()) {
      try {
        String line = provider.describe();
        if (line != null && !line.trim().isEmpty()) {
          out.add(line.trim());
        }
      } catch (Exception e) {
        LOG.debug(
            "AI Accountant: worklist provider {} failed: {}",
            provider.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return out;
  }

  /**
   * The eligible records of this domain, as short refs, deduplicated and capped.
   *
   * <h2>Failure is emptiness, and emptiness refuses</h2>
   *
   * A provider that throws contributes nothing rather than a partial list. That matters more here
   * than in {@link #describe}: this list becomes an authorisation, so a provider that half-answered
   * would authorise half a job — the caller would have no way to tell that from a genuinely small
   * set, and the records it silently dropped would never be worked on.
   */
  public List<String> candidates(AiDomain domain, int limit) {
    return candidates(domain, null, limit);
  }

  /**
   * As above, narrowed to a named subset — see {@link AiWorklistProvider#filters}.
   *
   * <p>A filter no provider declared yields an empty list, exactly as an empty queue does, and the
   * caller refuses the instruction. It is deliberately NOT widened to the unfiltered queue: a
   * request the server cannot narrow is a request it cannot carry out, and answering it with every
   * eligible record authorises records nobody asked about.
   *
   * @param filter the subset name, or null for the whole eligible queue
   */
  public List<String> candidates(AiDomain domain, String filter, int limit) {
    List<String> out = new ArrayList<>();
    if (domain == null || limit <= 0) {
      return out;
    }
    if (filter != null && !supportsFilter(domain, filter)) {
      return out;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (AiWorklistProvider provider : all()) {
      try {
        for (String ref :
            filter == null
                ? provider.candidates(domain, limit)
                : provider.candidates(domain, normalise(filter), limit)) {
          if (ref != null && !ref.trim().isEmpty() && seen.add(ref.trim())) {
            out.add(ref.trim());
          }
          if (out.size() >= limit) {
            return out;
          }
        }
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: worklist provider {} could not resolve candidates: {}",
            provider.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return out;
  }

  /** Whether any provider declares this subset of the domain's queue. Case-insensitive. */
  public boolean supportsFilter(AiDomain domain, String filter) {
    String wanted = normalise(filter);
    if (domain == null || wanted == null) {
      return false;
    }
    for (AiWorklistProvider provider : all()) {
      try {
        Set<String> declared = provider.filters(domain);
        if (declared != null && declared.contains(wanted)) {
          return true;
        }
      } catch (Exception e) {
        LOG.debug(
            "AI Accountant: worklist provider {} could not list its filters: {}",
            provider.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return false;
  }

  /** Every subset name any provider declares for this domain, for a precise refusal message. */
  public List<String> filters(AiDomain domain) {
    Set<String> all = new LinkedHashSet<>();
    if (domain == null) {
      return new ArrayList<>(all);
    }
    for (AiWorklistProvider provider : all()) {
      try {
        Set<String> declared = provider.filters(domain);
        if (declared != null) {
          all.addAll(declared);
        }
      } catch (Exception e) {
        LOG.debug(
            "AI Accountant: worklist provider {} could not list its filters: {}",
            provider.getClass().getSimpleName(),
            e.getMessage());
      }
    }
    return new ArrayList<>(all);
  }

  /** The server's own spelling of a filter name: trimmed and upper case, or null for nothing. */
  static String normalise(String filter) {
    if (filter == null) {
      return null;
    }
    String trimmed = filter.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
    return trimmed.isEmpty() ? null : trimmed;
  }

  protected List<AiWorklistProvider> discover() {
    List<AiWorklistProvider> found = new ArrayList<>();
    for (Class<? extends AiWorklistProvider> type :
        MetaScanner.findSubTypesOf(AiWorklistProvider.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} worklist provider(s).", found.size());
    return found;
  }
}
