package com.axelor.apps.aiaccountant.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AiTargetRegistry#relatedTarget}: every discovered {@link AiTargetRelationship} is asked,
 * silence does not count as a match, and a resolver that throws refuses the whole answer rather
 * than being skipped — see the method javadoc.
 */
class AiTargetRegistryTest {

  /** An identity resolver that treats every ref as already canonical, so tests need no database. */
  private static AiTargetRegistry registryWith(AiTargetRelationship relationship) {
    return new AiTargetRegistry() {
      @Override
      protected List<AiTargetIdentity> discover() {
        return Collections.singletonList(ref -> ref);
      }

      @Override
      protected List<AiTargetRelationship> discoverRelationships() {
        return Collections.singletonList(relationship);
      }
    };
  }

  @Test
  void trueWhenAResolverConfirmsTheRelationship() {
    AiTargetRegistry registry =
        registryWith(
            (a, b) -> "Invoice#606".equals(a) && "Partner#12".equals(b) ? Boolean.TRUE : null);

    assertTrue(registry.relatedTarget("Invoice#606", "Partner#12"));
  }

  @Test
  void falseWhenNoResolverClaimsThePairing() {
    AiTargetRegistry registry = registryWith((a, b) -> null);

    assertFalse(registry.relatedTarget("Invoice#606", "Partner#12"));
  }

  @Test
  void falseWhenAResolverThrows() {
    AiTargetRegistry registry =
        registryWith(
            (a, b) -> {
              throw new RuntimeException("boom");
            });

    assertFalse(registry.relatedTarget("Invoice#606", "Partner#12"));
  }

  /**
   * {@link AiEvidenceNotReadyException} is a different signal from an ordinary refusal: the
   * evidence a resolver would compare against has not been committed yet, which is a fact about
   * timing, not about whether the two records are related. It must escape rather than being
   * swallowed to {@code false} the way any other exception is — see the method's own javadoc and
   * {@code AiWriteAuthorisation.checkTargets}, which classifies it as retryable rather than a
   * permanent refusal.
   */
  @Test
  void evidenceNotReadyEscapesRatherThanBeingSwallowed() {
    AiTargetRegistry registry =
        registryWith(
            (a, b) -> {
              throw new AiEvidenceNotReadyException("not ready yet");
            });

    assertThrows(
        AiEvidenceNotReadyException.class,
        () -> registry.relatedTarget("AiOcrUpload#18", "PartnerCreate:2:831257890"));
  }
}
