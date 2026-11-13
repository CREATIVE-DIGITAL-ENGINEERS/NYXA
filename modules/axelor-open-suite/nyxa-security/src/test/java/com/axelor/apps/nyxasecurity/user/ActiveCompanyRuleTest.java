package com.axelor.apps.nyxasecurity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.nyxasecurity.user.ActiveCompanyRule.Decision;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveCompanyRuleTest {

  @Test
  void keepsAValidChoice() {
    assertFalse(ActiveCompanyRule.decide(7L, List.of(3L, 7L, 9L)).isChange());
  }

  @Test
  void picksTheLowestAssignedWhenNoneIsActive() {
    Decision d = ActiveCompanyRule.decide(null, List.of(9L, 3L, 7L));
    assertTrue(d.isChange());
    assertEquals(3L, d.getCompanyId());
  }

  @Test
  void replacesAnActiveCompanyTheUserWasRemovedFrom() {
    Decision d = ActiveCompanyRule.decide(5L, List.of(9L, 7L));
    assertTrue(d.isChange());
    assertEquals(7L, d.getCompanyId());
  }

  @Test
  void clearsTheActiveCompanyWhenNothingIsAssigned() {
    // Otherwise the user keeps seeing a company's books after being removed from all companies.
    Decision d = ActiveCompanyRule.decide(5L, List.of());
    assertTrue(d.isChange());
    assertNull(d.getCompanyId());
  }

  @Test
  void neverAssignsACompanyByItself() {
    // Option (a): nothing assigned and nothing active stays that way until an admin assigns.
    assertFalse(ActiveCompanyRule.decide(null, List.of()).isChange());
  }
}
