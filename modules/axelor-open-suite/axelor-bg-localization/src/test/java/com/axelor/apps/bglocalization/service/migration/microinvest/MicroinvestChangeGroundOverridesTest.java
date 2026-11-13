package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hand-reviewed (Bulstat, PayrollID) -&gt; ETZ change-ground override loaded from {@code
 * microinvest/change-ground-overrides.csv} -- see that file's header for why each row exists and
 * {@link MicroinvestChangeGroundOverrides}'s class javadoc for why the key includes the company
 * Bulstat, not just the PayrollID.
 */
class MicroinvestChangeGroundOverridesTest {

  @Test
  @DisplayName("the two confirmed ТЕСТЕКС overrides resolve to their reviewed codes")
  void resolvesConfirmedOverrides() {
    assertEquals("021", MicroinvestChangeGroundOverrides.overrideFor("100000026", 12));
    assertEquals("019", MicroinvestChangeGroundOverrides.overrideFor("100000026", 19));
  }

  @Test
  @DisplayName(
      "the same PayrollID under a different Bulstat, or an unlisted PayrollID, resolves to nothing")
  void isScopedToTheExactCompanyAndPayrollId() {
    assertNull(MicroinvestChangeGroundOverrides.overrideFor("000000000", 12));
    assertNull(MicroinvestChangeGroundOverrides.overrideFor("100000026", 999999));
    assertNull(MicroinvestChangeGroundOverrides.overrideFor(null, 12));
    assertNull(MicroinvestChangeGroundOverrides.overrideFor("", 12));
  }
}
