package com.axelor.apps.nyxasecurity.mail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NxEmailAddressRepositoryTest {

  @Test
  void acceptsAddressesTheAddOnRefused() {
    for (String address :
        List.of(
            "jane.doe+11@example.com",
            "first.last+tag.more@example.co.uk",
            "o'brien@example.com",
            "ana@sub.example.bg")) {
      assertTrue(NxEmailAddressRepository.isValid(address), address);
    }
  }

  @Test
  void stillAcceptsPlainAddresses() {
    assertTrue(NxEmailAddressRepository.isValid("ana@example.com"));
    assertTrue(NxEmailAddressRepository.isValid("ana-maria_1@example.com"));
  }

  @Test
  void refusesWhatIsNotOnePlainAddress() {
    for (String address :
        List.of(
            "",
            "ana",
            "ana@",
            "@example.com",
            "ana@example",
            "ana@example.",
            "ana example@example.com",
            " ana@example.com",
            "Ana <ana@example.com>",
            "ana@example.com, bob@example.com",
            "ana@@example.com")) {
      assertFalse(NxEmailAddressRepository.isValid(address), address);
    }
  }
}
