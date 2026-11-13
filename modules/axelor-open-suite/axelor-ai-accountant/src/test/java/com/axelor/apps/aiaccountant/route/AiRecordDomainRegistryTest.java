package com.axelor.apps.aiaccountant.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one registry that refuses instead of picking a winner.
 *
 * <p>{@code MetaScanner} returns implementations in classpath order, which nothing specifies. Every
 * other registry here takes the first non-null answer, and for a description or a fingerprint the
 * cost of a collision is a worse answer. This one decides whether a tool call belongs to the job
 * the user authorised, so a collision resolved by scan order would be an authorisation resolved by
 * scan order — and which resolver won could change between builds with no code change at all.
 */
class AiRecordDomainRegistryTest {

  /** A registry with a fixed resolver list, so the test does not need a MetaScanner sweep. */
  private static AiRecordDomainRegistry registryOf(AiRecordDomainResolver... resolvers) {
    List<AiRecordDomainResolver> fixed = Arrays.asList(resolvers);
    return new AiRecordDomainRegistry() {
      @Override
      protected List<AiRecordDomainResolver> discover() {
        return fixed;
      }
    };
  }

  private static AiRecordDomainResolver answering(String model, AiDomain domain) {
    return new AiRecordDomainResolver() {
      @Override
      public AiDomain domainOfModel(String candidate, Long recordId) {
        return model.equals(candidate) ? domain : null;
      }

      @Override
      public AiDomain domainOfRef(String ref) {
        return ref != null && ref.startsWith(model + "#") ? domain : null;
      }
    };
  }

  @Test
  void oneAnswerIsTheAnswer() {
    AiRecordDomainRegistry registry = registryOf(answering("Invoice", AiDomain.CUSTOMER_INVOICE));
    assertEquals(AiDomain.CUSTOMER_INVOICE, registry.domainOfModel("Invoice", 1L));
    assertEquals(AiDomain.CUSTOMER_INVOICE, registry.domainOfRef("Invoice#1"));
  }

  @Test
  void nobodyOwningItIsNoAnswer() {
    AiRecordDomainRegistry registry = registryOf(answering("Invoice", AiDomain.CUSTOMER_INVOICE));
    assertNull(registry.domainOfModel("Move", 1L));
    assertNull(registry.domainOfRef("Move#1"));
    assertNull(registry.domainOfModel(null, null));
    assertNull(registry.domainOfRef("  "));
  }

  /** Two resolvers agreeing is a duplicate, not a contradiction. */
  @Test
  void agreementIsFine() {
    AiRecordDomainRegistry registry =
        registryOf(
            answering("Invoice", AiDomain.SUPPLIER_INVOICE),
            answering("Invoice", AiDomain.SUPPLIER_INVOICE));
    assertEquals(AiDomain.SUPPLIER_INVOICE, registry.domainOfModel("Invoice", 1L));
  }

  /** The point of the class: disagreement fails closed rather than resolving by scan order. */
  @Test
  void disagreementFailsClosed() {
    AiRecordDomainRegistry registry =
        registryOf(
            answering("Invoice", AiDomain.SUPPLIER_INVOICE),
            answering("Invoice", AiDomain.CUSTOMER_INVOICE));
    assertNull(registry.domainOfModel("Invoice", 1L), "two answers, so no answer");
    assertNull(registry.domainOfRef("Invoice#1"), "two answers, so no answer");
  }

  /** And it fails closed whichever order the scan happened to return them in. */
  @Test
  void disagreementFailsClosedInEitherOrder() {
    AiRecordDomainRegistry reversed =
        registryOf(
            answering("Invoice", AiDomain.CUSTOMER_INVOICE),
            answering("Invoice", AiDomain.SUPPLIER_INVOICE));
    assertNull(reversed.domainOfModel("Invoice", 1L));
  }

  /** A resolver that throws has not answered; the others still get asked. */
  @Test
  void athrowingResolverIsSkippedNotFatal() {
    AiRecordDomainResolver broken =
        new AiRecordDomainResolver() {
          @Override
          public AiDomain domainOfModel(String model, Long recordId) {
            throw new IllegalStateException("no session");
          }

          @Override
          public AiDomain domainOfRef(String ref) {
            throw new IllegalStateException("no session");
          }
        };
    AiRecordDomainRegistry registry =
        registryOf(broken, answering("Invoice", AiDomain.CUSTOMER_INVOICE));
    assertEquals(AiDomain.CUSTOMER_INVOICE, registry.domainOfModel("Invoice", 1L));
    assertEquals(AiDomain.CUSTOMER_INVOICE, registry.domainOfRef("Invoice#1"));
  }

  @Test
  void noResolversAtAllIsNoAnswer() {
    AiRecordDomainRegistry registry = registryOf();
    assertNull(registry.domainOfModel("Invoice", 1L));
    assertEquals(Collections.emptyList(), registry.all());
  }
}
