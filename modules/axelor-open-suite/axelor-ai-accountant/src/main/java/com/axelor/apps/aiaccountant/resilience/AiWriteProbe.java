package com.axelor.apps.aiaccountant.resilience;

import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that has to be known <em>before</em> a write runs in order to say afterwards what it
 * did.
 *
 * <h2>Why it is taken in advance</h2>
 *
 * After an ambiguous failure there is nothing left to reason from. The exception says the answer
 * was lost; the database shows a record in some state, and without a "before" that state proves
 * nothing — an invoice that is VENTILATED may have been ventilated by this call or by the
 * accountant ten minutes ago. Every honest answer to "did it happen?" is a comparison, so both
 * sides of it have to exist, and one of them can only be captured while the outcome is still
 * certain.
 *
 * <h2>The three things it captures</h2>
 *
 * <ul>
 *   <li><b>The canonical target</b> — the company-scoped identity of the record being written to,
 *       resolved through {@code AiTargetRegistry} rather than taken from the arguments. A reference
 *       the active company cannot resolve yields null, and a null canonical is never reconciled:
 *       proving something about a record nobody can identify is not proof.
 *   <li><b>Its fingerprint</b> — the generic evidence, good for exactly one conclusion: unchanged
 *       means unwritten.
 *   <li><b>The write effects already recorded</b> — so anything a service reports <em>during</em>
 *       the attempt can be told apart from what earlier tools in the same turn reported. For a
 *       creation this is the whole mechanism: the service records the id it saved before the
 *       transaction commits, so the id exists as an identity even when the commit's outcome does
 *       not. Checking whether that exact row is now in the database is an existence test on a
 *       server-generated primary key — not a search for something that looks similar.
 * </ul>
 */
public final class AiWriteProbe {

  private final String target;
  private final String canonical;
  private final String fingerprintBefore;
  private final Set<String> effectsBefore;
  private final Object toolState;

  AiWriteProbe(
      String target,
      String canonical,
      String fingerprintBefore,
      Set<String> effectsBefore,
      Object toolState) {
    this.target = target;
    this.canonical = canonical;
    this.fingerprintBefore = fingerprintBefore;
    this.effectsBefore = effectsBefore;
    this.toolState = toolState;
  }

  /**
   * Whatever the tool's own {@code beforeExecute} captured, or null when it captured nothing.
   *
   * <p>Opaque here on purpose: only the tool that produced it knows what it means, and a dispatcher
   * that understood it would be a dispatcher with an opinion about accounting.
   */
  public Object toolState() {
    return toolState;
  }

  /** The short ref the tool named, e.g. {@code Upload#946}; null when it named none. */
  public String target() {
    return target;
  }

  /**
   * The company-scoped canonical identity of that record, or null when it could not be established.
   *
   * <p>Null is not "the same as the target". It means the active company cannot identify the
   * record, which disqualifies every conclusion that would otherwise be drawn from reading it.
   */
  public String canonical() {
    return canonical;
  }

  /** The record's state immediately before the call, or null when nothing could describe it. */
  public String fingerprintBefore() {
    return fingerprintBefore;
  }

  /**
   * Records this turn had already written to before the call started.
   *
   * <p>Only interesting as the thing {@link #newEffects()} subtracts.
   */
  public Set<String> effectsBefore() {
    return effectsBefore;
  }

  /**
   * Records the attempt itself reported, as {@code com.axelor…Move#42}.
   *
   * <p>For a creation these are the ids the service generated and saved before the commit whose
   * outcome was lost. They are candidates, not conclusions: whether those rows are actually in the
   * database is what the tool's own verifier goes and checks.
   */
  public List<String> newEffects() {
    List<String> fresh = new ArrayList<>();
    for (AiWriteEffects.Ref ref : AiWriteEffects.snapshot()) {
      String key = key(ref);
      if (!effectsBefore.contains(key)) {
        fresh.add(key);
      }
    }
    return fresh;
  }

  /** The id the attempt reported for this model, or null when it reported none. */
  public Long newId(Class<?> model) {
    String prefix = model.getName() + '#';
    for (String ref : newEffects()) {
      if (ref.startsWith(prefix)) {
        try {
          return Long.parseLong(ref.substring(prefix.length()));
        } catch (NumberFormatException ignore) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Drops the effects the attempt reported.
   *
   * <p>Called when reconciliation proves the write did not land. The service recorded them inside
   * the transaction that then rolled back, so leaving them in place would tell the client to
   * refresh a record that does not exist and would put a phantom on the workflow's target list.
   */
  public void forgetNewEffects() {
    for (String ref : newEffects()) {
      int hash = ref.lastIndexOf('#');
      if (hash <= 0) {
        continue;
      }
      try {
        AiWriteEffects.forget(ref.substring(0, hash), Long.parseLong(ref.substring(hash + 1)));
      } catch (NumberFormatException ignore) {
        // Not a ref this can undo. Leaving it is the conservative direction — it means a refresh
        // that finds nothing, rather than a change nobody is told about.
      }
    }
  }

  /** Captures the "before" side, at the last moment the outcome is still certain. */
  public static AiWriteProbe capture(
      String target, String canonical, String fingerprintBefore, Object toolState) {
    Set<String> before = new LinkedHashSet<>();
    for (AiWriteEffects.Ref ref : AiWriteEffects.snapshot()) {
      before.add(key(ref));
    }
    return new AiWriteProbe(target, canonical, fingerprintBefore, before, toolState);
  }

  private static String key(AiWriteEffects.Ref ref) {
    return ref.getModel() + "#" + ref.getId();
  }
}
