package com.axelor.apps.bglocalization.service.partner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Промяна на системен партньор: само nyxa.admin или без потребител — и в пътя „нов потребител“ за
 * всеки, който създава потребителя (client.admin при покана). Системният партньор (на потребител)
 * вижда и променя само nyxa.admin.
 */
class BgPartnerCompanyListenerSystemChangeTest {

  @Test
  void aNonAdminMayNotChangeAnExistingSystemPartner() {
    assertTrue(BgPartnerCompanyListener.systemChangeRefused(true, false, true));
  }

  @Test
  void theNewUserPathMayWriteItsSystemPartnerAgainWhoeverCreatesTheUser() {
    assertFalse(BgPartnerCompanyListener.systemChangeRefused(true, true, true));
  }

  @Test
  void anAdminOrNoUserMayChangeIt() {
    assertFalse(BgPartnerCompanyListener.systemChangeRefused(true, false, false));
  }

  @Test
  void anOrdinaryPartnerIsNotThisRule() {
    assertFalse(BgPartnerCompanyListener.systemChangeRefused(false, false, true));
  }

  @Test
  void creatingIsTrueOnlyInsideTheNewUserPath() {
    assertFalse(BgSystemPartner.isCreating());
    assertTrue(BgSystemPartner.creating(BgSystemPartner::isCreating));
    assertFalse(BgSystemPartner.isCreating());
  }
}
