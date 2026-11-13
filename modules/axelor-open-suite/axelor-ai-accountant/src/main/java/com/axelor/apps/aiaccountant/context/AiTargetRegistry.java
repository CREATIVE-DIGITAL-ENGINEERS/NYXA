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
 * Asks the {@link AiTargetIdentity} resolvers what a reference really names.
 *
 * <h2>Fail closed, deliberately</h2>
 *
 * Every other registry in this module degrades to "no extra context" when nothing answers, because
 * what it is producing is an enrichment. This one produces the answer to "may this write proceed?",
 * so silence has to mean no.
 *
 * <p>{@link #sameTarget} returns true only when both references resolve, and resolve to the same
 * canonical record. A reference nobody owns, a record that does not exist, a record belonging to
 * another company — all of them come back unresolved, and unresolved is not a match. The cost of
 * that is a refusal on a reference kind whose module has not contributed a resolver yet, which is a
 * missing line of code and shows up the first time it is used. The cost of the other direction is a
 * write against a record nobody authorised.
 */
@Singleton
public class AiTargetRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiTargetRegistry.class);

  private volatile List<AiTargetIdentity> resolvers;
  private volatile List<AiTargetRelationship> relationships;

  public List<AiTargetIdentity> all() {
    List<AiTargetIdentity> local = resolvers;
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

  private List<AiTargetRelationship> allRelationships() {
    List<AiTargetRelationship> local = relationships;
    if (local == null) {
      synchronized (this) {
        if (relationships == null) {
          relationships = discoverRelationships();
        }
        local = relationships;
      }
    }
    return local;
  }

  /**
   * The canonical record a reference names, or null when it cannot be established.
   *
   * <p>First answer wins. Unlike a description, of which two modules may each have a useful one, a
   * reference names one record: a second opinion could only disagree, and there would be no way to
   * decide between them.
   */
  public String canonical(String ref) {
    if (ref == null || ref.trim().isEmpty()) {
      return null;
    }
    String trimmed = ref.trim();
    for (AiTargetIdentity resolver : all()) {
      try {
        String canonical = resolver.canonical(trimmed);
        if (canonical != null && !canonical.trim().isEmpty()) {
          return canonical.trim();
        }
      } catch (Exception e) {
        // A resolver that fails has not identified anything, which is the same as not owning the
        // reference: the caller refuses. Logged rather than swallowed silently, because a resolver
        // throwing on every call would otherwise look like an authorisation rule.
        LOG.warn(
            "AI Accountant: target resolver {} failed on '{}': {}",
            resolver.getClass().getSimpleName(),
            trimmed,
            e.getMessage());
      }
    }
    LOG.debug("AI Accountant: no resolver could identify target '{}'.", trimmed);
    return null;
  }

  /**
   * True only when Java can prove both references name the same record.
   *
   * <p>The literal comparison first, because {@code Invoice#606} against {@code Invoice#606} needs
   * no database at all and that is the common case.
   */
  public boolean sameTarget(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.trim().equals(b.trim())) {
      return true;
    }
    String left = canonical(a);
    if (left == null) {
      return false;
    }
    String right = canonical(b);
    return right != null && left.equals(right);
  }

  /**
   * Whether this record may be written to by the caller, as its own module reports.
   *
   * <p>False unless a resolver says otherwise: no resolver, an unrecognised reference, an exception
   * and an explicit refusal all come back the same way. This gates entry into an authorised target
   * set, so silence has to mean no for the same reason it does in {@link #sameTarget}.
   */
  public boolean writable(String ref) {
    if (ref == null || ref.trim().isEmpty()) {
      return false;
    }
    String trimmed = ref.trim();
    for (AiTargetIdentity resolver : all()) {
      try {
        Boolean answer = resolver.writable(trimmed);
        if (answer != null) {
          return answer;
        }
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: target resolver {} could not check write access to '{}': {}",
            resolver.getClass().getSimpleName(),
            trimmed,
            e.getMessage());
        return false;
      }
    }
    return false;
  }

  protected List<AiTargetIdentity> discover() {
    List<AiTargetIdentity> found = new ArrayList<>();
    for (Class<? extends AiTargetIdentity> type :
        MetaScanner.findSubTypesOf(AiTargetIdentity.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} target-identity resolver(s).", found.size());
    return found;
  }

  /**
   * Whether a target already in the authorised set proves a related record, via {@link
   * AiTargetRelationship}.
   *
   * <h2>Canonicalise first, then ask, then aggregate</h2>
   *
   * Both references are resolved to canonical, company-scoped records before any relationship
   * resolver sees them, for the same reason {@link #sameTarget} does it: a resolver should never
   * have to parse an unproven ref, and a reference that cannot even be identified proves no
   * relationship to anything.
   *
   * <p>Every discovered resolver is asked, not just the first: unlike identity, a relationship has
   * no single owner, so order must not matter. A resolver that throws, or two that disagree, both
   * refuse the whole answer — a relationship this module cannot pin down consistently is not one to
   * authorise a write on.
   */
  public boolean relatedTarget(String authorisedRef, String candidateRef) {
    String left = canonical(authorisedRef);
    if (left == null) {
      return false;
    }
    String right = canonical(candidateRef);
    if (right == null) {
      return false;
    }
    Boolean agreed = null;
    for (AiTargetRelationship relationship : allRelationships()) {
      Boolean answer;
      try {
        answer = relationship.isRelated(left, right);
      } catch (AiEvidenceNotReadyException notReady) {
        // Not a refusal: the resolver has not yet been able to read the evidence it would compare
        // against. Left to escape so the caller can tell "not yet" apart from "no" — swallowing it
        // here the way an ordinary exception is swallowed below would make the two
        // indistinguishable.
        throw notReady;
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: target relationship {} failed on '{}'/'{}': {}",
            relationship.getClass().getSimpleName(),
            left,
            right,
            e.getMessage());
        return false;
      }
      if (answer == null) {
        continue;
      }
      if (agreed == null) {
        agreed = answer;
      } else if (!agreed.equals(answer)) {
        LOG.warn(
            "AI Accountant: target relationship resolvers disagree on '{}'/'{}'; refusing.",
            left,
            right);
        return false;
      }
    }
    return agreed != null && agreed;
  }

  protected List<AiTargetRelationship> discoverRelationships() {
    List<AiTargetRelationship> found = new ArrayList<>();
    for (Class<? extends AiTargetRelationship> type :
        MetaScanner.findSubTypesOf(AiTargetRelationship.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} target-relationship resolver(s).", found.size());
    return found;
  }
}
