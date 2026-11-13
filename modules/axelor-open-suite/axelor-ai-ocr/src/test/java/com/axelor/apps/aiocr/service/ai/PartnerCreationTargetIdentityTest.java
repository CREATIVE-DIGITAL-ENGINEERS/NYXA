package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link PartnerCreationTargetIdentity#parse}: the pure parsing half of the synthetic {@code
 * PartnerCreate:<companyId>:<eik>} target — {@code canonical}/{@code writable} additionally need
 * the active company scope, which this module's test suite has no injector for.
 */
class PartnerCreationTargetIdentityTest {

  @Test
  void parsesTheCompanyIdAndEik() {
    PartnerCreationTargetIdentity.Parsed parsed =
        PartnerCreationTargetIdentity.parse("PartnerCreate:1:203970390");

    assertEquals(1L, parsed.companyId);
    assertEquals("203970390", parsed.eik);
  }

  @Test
  void nullForAnythingWithoutThePrefix() {
    assertNull(PartnerCreationTargetIdentity.parse("Partner#12"));
  }

  @Test
  void nullForAMalformedReference() {
    assertNull(PartnerCreationTargetIdentity.parse("PartnerCreate:1:"));
    assertNull(PartnerCreationTargetIdentity.parse("PartnerCreate:notanumber:203970390"));
  }
}
