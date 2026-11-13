package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgD1Record;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic Dekl1 -&gt; BgD1Record field mapping -- matched by FormRefNo, not by the long
 * free-text Name, precisely because FormRefNo is short enough to get exactly right. See {@link
 * MicroinvestD1FieldMapping}'s class javadoc for what is deliberately NOT mapped.
 */
class MicroinvestD1FieldMappingTest {

  @Test
  @DisplayName("a known point number sets the matching decimal field")
  void appliesKnownDecimalField() {
    BgD1Record record = new BgD1Record();
    assertTrue(MicroinvestD1FieldMapping.apply(record, "21", "1000.00"));
    assertEquals(0, new BigDecimal("1000.00").compareTo(record.getT21InsuranceIncome()));
  }

  @Test
  @DisplayName("a known point number sets the matching string field")
  void appliesKnownStringField() {
    BgD1Record record = new BgD1Record();
    MicroinvestD1FieldMapping.apply(record, "6", "Петров");
    assertEquals("Петров", record.getT6Surname());
  }

  @Test
  @DisplayName("Cyrillic and Latin suffix letters both resolve to the same employer-side field")
  void normalizesCyrillicAndLatinSuffixes() {
    BgD1Record cyrillic = new BgD1Record();
    MicroinvestD1FieldMapping.apply(cyrillic, "22.1а", "10.92"); // Cyrillic а
    assertEquals(0, new BigDecimal("10.92").compareTo(cyrillic.getT221HealthRateEmployer()));

    BgD1Record latin = new BgD1Record();
    MicroinvestD1FieldMapping.apply(latin, "22.1a", "10.92"); // Latin a
    assertEquals(0, new BigDecimal("10.92").compareTo(latin.getT221HealthRateEmployer()));
  }

  @Test
  @DisplayName(
      "an unknown point number, a blank value, or a deliberately excluded point does nothing")
  void doesNotGuessUnknownOrExcludedPoints() {
    BgD1Record record = new BgD1Record();
    assertFalse(MicroinvestD1FieldMapping.apply(record, "99", "1.00"));
    assertFalse(MicroinvestD1FieldMapping.apply(record, "21", ""));
    assertFalse(MicroinvestD1FieldMapping.apply(record, "21", null));
    assertFalse(
        MicroinvestD1FieldMapping.apply(record, "5.1", "0")); // т.5.1 -- convention mismatch
    assertFalse(
        MicroinvestD1FieldMapping.apply(
            record, "1", "К")); // т.1 -- convention not confirmed, excluded
    // ⚠️ Axelor's generated getter for a decimal field returns BigDecimal.ZERO, never null, for an
    // unset column (the same defaulted-getter behavior BgPaySlip/BgPaySlipLine's own domain
    // comments warn about) -- so "left unset" is checked against ZERO here, not null.
    assertTrue(BigDecimal.ZERO.compareTo(record.getT21InsuranceIncome()) == 0);
    assertNull(record.getT51PersonalIdCode());
    assertNull(record.getT1CorrectionCode());
  }

  @Test
  @DisplayName("a malformed decimal value is left unset rather than guessed at")
  void leavesUnparsableDecimalUnset() {
    BgD1Record record = new BgD1Record();
    MicroinvestD1FieldMapping.apply(record, "21", "not-a-number");
    assertTrue(BigDecimal.ZERO.compareTo(record.getT21InsuranceIncome()) == 0);
  }

  @Test
  @DisplayName(
      "\"Вид осигурен\" falls back to name matching when Microinvest's own FormRefNo is null"
          + " (confirmed against the live pilot company's Dekl1 catalog)")
  void fallsBackToNameForConfirmedNullFormRefNoField() {
    BgD1Record record = new BgD1Record();
    assertTrue(MicroinvestD1FieldMapping.apply(record, null, "Вид осигурен", "12"));
    assertEquals("12", record.getT12InsuredTypeCode());
  }

  @Test
  @DisplayName("the name fallback never fires when a FormRefNo is present, or for any other name")
  void nameFallbackIsNarrow() {
    BgD1Record record = new BgD1Record();
    // FormRefNo present (even if unknown) takes precedence -- the fallback is only for a
    // confirmed-null FormRefNo, not a general "try the name too" retry.
    assertFalse(MicroinvestD1FieldMapping.apply(record, "99", "Вид осигурен", "12"));
    assertFalse(MicroinvestD1FieldMapping.apply(record, null, "Някакво друго поле", "12"));
    assertFalse(MicroinvestD1FieldMapping.apply(record, "", "Вид осигурен", ""));
  }

  @Test
  @DisplayName(
      "a rate with excess decimal scale (e.g. Microinvest's own \"0.400000\") is rounded to"
          + " BgD1Record's own @Digits(fraction = 2) scale, not left to violate it at persist time")
  void roundsExcessScaleToFieldScale() {
    BgD1Record record = new BgD1Record();
    // т.22.2 (TZPB rate) -- confirmed against the live pilot company's own Dekl1Value rows
    // (0.000/0.500/0.900, always 3 decimals) that this is excess scale, not a genuinely more
    // precise rate; BgD1Record.t222TzpbRate is @Digits(integer = 4, fraction = 2).
    assertTrue(MicroinvestD1FieldMapping.apply(record, "22.2", "0.400000"));
    assertEquals(0, new BigDecimal("0.40").compareTo(record.getT222TzpbRate()));
    assertEquals(2, record.getT222TzpbRate().scale());
  }
}
