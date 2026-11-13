package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ActiveCompanyShortcutSeederTest {

  @Test
  void addsTheActiveCompanyShortcutAndKeepsTheOthers() {
    assertEquals("0", ActiveCompanyShortcutSeeder.withActiveCompany(null));
    assertEquals("0", ActiveCompanyShortcutSeeder.withActiveCompany(""));
    assertEquals("0,1", ActiveCompanyShortcutSeeder.withActiveCompany("1"));
  }

  @Test
  void leavesAnAlreadyTickedShortcutAlone() {
    assertEquals("0", ActiveCompanyShortcutSeeder.withActiveCompany("0"));
    assertEquals("1,0", ActiveCompanyShortcutSeeder.withActiveCompany("1,0"));
  }
}
