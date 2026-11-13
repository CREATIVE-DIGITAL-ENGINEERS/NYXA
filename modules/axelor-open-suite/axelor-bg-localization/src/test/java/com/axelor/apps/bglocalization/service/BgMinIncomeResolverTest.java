package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgMinIncomeOverride;
import com.axelor.apps.bglocalization.db.BgMinInsuranceIncome;
import com.axelor.apps.bglocalization.db.BgMinInsuranceIncomeAmount;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult.Status;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the МОД resolution logic.
 *
 * <p>No database and no Guice: generated Axelor entities are plain objects with setters, so every
 * fixture is built in memory. Values are taken from Appendix 1 of the 2026 State Social Insurance
 * Budget Act.
 */
class BgMinIncomeResolverTest {

  // ── fixtures ────────────────────────────────────────────────────────────

  private static BgMinInsuranceIncome row(String code, Object... groupAmountPairs) {
    BgMinInsuranceIncome r = new BgMinInsuranceIncome();
    r.setBgRow(code);
    List<BgMinInsuranceIncomeAmount> list = new ArrayList<>();
    for (int i = 0; i < groupAmountPairs.length; i += 2) {
      BgMinInsuranceIncomeAmount a = new BgMinInsuranceIncomeAmount();
      a.setBgQualificationGroup((Integer) groupAmountPairs[i]);
      a.setAmount(new BigDecimal(groupAmountPairs[i + 1].toString()));
      a.setBgMinInsuranceIncome(r);
      list.add(a);
    }
    r.setBgAmountList(list);
    return r;
  }

  private static BgNkid nkid(String code, String rowCode, Boolean ambiguous) {
    BgNkid n = new BgNkid();
    n.setCode(code);
    n.setBgMinIncomeRowCode(rowCode);
    n.setBgAmbiguous(ambiguous);
    return n;
  }

  private static BgMinIncomeOverride override(String nkidCode, String prefix, String rowCode) {
    BgMinIncomeOverride o = new BgMinIncomeOverride();
    o.setBgNkidCode(nkidCode);
    o.setBgNkpdPrefix(prefix);
    o.setBgMinIncomeRowCode(rowCode);
    return o;
  }

  /** Row 58 — wholesale and retail trade. Group 1 is above the minimum wage. */
  private static BgMinInsuranceIncome row58() {
    return row(
        "58", 1, "1129.00", 2, "826.00", 3, "771.00", 4, "716.00", 5, "620.20", 6, "620.20", 7,
        "661.00", 8, "661.00", 9, "620.20");
  }

  /** Row 81 — professional footballers. Sparse: only group 3 is defined. */
  private static BgMinInsuranceIncome row81() {
    return row("81", 3, "771.00");
  }

  // ── qualification group ─────────────────────────────────────────────────

  @Test
  void groupIsFirstDigitOfNkpd() {
    BgMinIncomeResult r = BgMinIncomeResolver.qualificationGroup("11119001");
    assertTrue(r.isResolved());
    assertEquals(Integer.valueOf(1), r.getQualificationGroup());
  }

  @Test
  void groupNineIsValid() {
    assertEquals(
        Integer.valueOf(9),
        BgMinIncomeResolver.qualificationGroup("91120001").getQualificationGroup());
  }

  @Test
  void missingNkpdBlocks() {
    for (String bad : new String[] {null, "", "   "}) {
      BgMinIncomeResult r = BgMinIncomeResolver.qualificationGroup(bad);
      assertEquals(Status.NKPD_MISSING, r.getStatus());
      assertTrue(r.isBlocking());
    }
  }

  @Test
  void nonNumericNkpdBlocks() {
    assertEquals(
        Status.NKPD_INVALID, BgMinIncomeResolver.qualificationGroup("X1119001").getStatus());
  }

  /** Class 0 is the armed forces; Appendix 1 defines no column for it. */
  @Test
  void classZeroBlocksRatherThanFallingBack() {
    BgMinIncomeResult r = BgMinIncomeResolver.qualificationGroup("01110001");
    assertEquals(Status.QUALIFICATION_GROUP_ZERO, r.getStatus());
    assertTrue(r.isBlocking());
  }

  // ── row selection ───────────────────────────────────────────────────────

  @Test
  void plainNkidResolvesToItsRow() {
    BgMinIncomeResult r = BgMinIncomeResolver.rowCodeFor(null, nkid("46.11", "58", false));
    assertTrue(r.isResolved());
    assertEquals("58", r.getRowCode());
  }

  @Test
  void companyOverrideWinsOverNkid() {
    BgMinIncomeResult r = BgMinIncomeResolver.rowCodeFor("50", nkid("35.40", "49", true));
    assertTrue(r.isResolved());
    assertEquals("50", r.getRowCode());
  }

  /** 35.40 maps to row 49 (electricity) or 50 (gas) — 936 versus 1270 in group 1. */
  @Test
  void ambiguousNkidBlocksWithoutOverride() {
    BgNkid n = nkid("35.40", "49", true);
    n.setBgMinIncomeNote("Ред 49 = електроенергия · Ред 50 = природен газ");
    BgMinIncomeResult r = BgMinIncomeResolver.rowCodeFor(null, n);
    assertEquals(Status.AMBIGUOUS_REQUIRES_OVERRIDE, r.getStatus());
    assertTrue(r.getDetail().contains("35.40"));
    assertTrue(r.getDetail().contains("природен газ"));
  }

  /** 98.10 and 98.20 legitimately have no row — households employing domestic staff. */
  @Test
  void nkidWithoutRowBlocksInsteadOfFallingBackToMinimumWage() {
    for (String blank : new String[] {null, "", "  "}) {
      BgMinIncomeResult r = BgMinIncomeResolver.rowCodeFor(null, nkid("98.10", blank, false));
      assertEquals(Status.NO_MOD_ROW_FOR_NKID, r.getStatus());
      assertTrue(r.isBlocking());
    }
  }

  @Test
  void unknownNkidBlocks() {
    assertEquals(Status.NKID_NOT_FOUND, BgMinIncomeResolver.rowCodeFor(null, null).getStatus());
  }

  @Test
  void nullAmbiguousFlagIsTreatedAsFalse() {
    BgMinIncomeResult r = BgMinIncomeResolver.rowCodeFor(null, nkid("46.11", "58", null));
    assertTrue(r.isResolved());
  }

  // ── position-level override ─────────────────────────────────────────────

  @Test
  void positionOverrideRedirectsToAnotherRow() {
    List<BgMinIncomeOverride> ovr = Arrays.asList(override("86.10", "1342", "84"));
    assertEquals("84", BgMinIncomeResolver.applyPositionOverride(ovr, "13421001", "76"));
  }

  @Test
  void positionOverrideIgnoresNonMatchingPrefix() {
    List<BgMinIncomeOverride> ovr = Arrays.asList(override("86.10", "1342", "84"));
    assertEquals("76", BgMinIncomeResolver.applyPositionOverride(ovr, "22211001", "76"));
  }

  @Test
  void longestPrefixWins() {
    List<BgMinIncomeOverride> ovr =
        Arrays.asList(override("86.10", "13", "84"), override("86.10", "1342", "85"));
    assertEquals("85", BgMinIncomeResolver.applyPositionOverride(ovr, "13421001", "76"));
  }

  @Test
  void noOverridesLeavesBaseRow() {
    assertEquals("58", BgMinIncomeResolver.applyPositionOverride(null, "11119001", "58"));
    assertEquals(
        "58", BgMinIncomeResolver.applyPositionOverride(new ArrayList<>(), "11119001", "58"));
  }

  // ── amount selection ────────────────────────────────────────────────────

  /**
   * Six of nine groups sit above the minimum wage — the lookup is load bearing, not an edge case.
   */
  @Test
  void amountAboveMinimumWageIsReturned() {
    BgMinIncomeResult r = BgMinIncomeResolver.amountFor(row58(), 1);
    assertTrue(r.isResolved());
    assertEquals(0, new BigDecimal("1129.00").compareTo(r.getAmount()));
    assertEquals("58", r.getRowCode());
    assertEquals(Integer.valueOf(1), r.getQualificationGroup());
  }

  @Test
  void amountEqualToMinimumWageIsReturned() {
    assertEquals(
        0,
        new BigDecimal("620.20").compareTo(BgMinIncomeResolver.amountFor(row58(), 5).getAmount()));
  }

  @Test
  void sparseRowResolvesItsOnlyGroup() {
    BgMinIncomeResult r = BgMinIncomeResolver.amountFor(row81(), 3);
    assertTrue(r.isResolved());
    assertEquals(0, new BigDecimal("771.00").compareTo(r.getAmount()));
  }

  /** Unreachable by construction — reaching it means the override table is wrong. */
  @Test
  void sparseRowBlocksOnUndefinedGroup() {
    BgMinIncomeResult r = BgMinIncomeResolver.amountFor(row81(), 1);
    assertEquals(Status.AMOUNT_NOT_DEFINED, r.getStatus());
    assertTrue(r.isBlocking());
    assertFalse(r.isResolved());
    assertEquals("81", r.getRowCode());
  }

  @Test
  void missingRowBlocks() {
    assertEquals(Status.MOD_ROW_NOT_FOUND, BgMinIncomeResolver.amountFor(null, 1).getStatus());
  }

  /**
   * A zero floor cannot be legitimate — the lowest value in Appendix 1 is the minimum wage — so it
   * signals corrupt seed data. Axelor's generated decimal getter returns ZERO for a missing value,
   * which makes this the only reachable form of the "no amount" case through the getter.
   */
  @Test
  void zeroAmountIsTreatedAsUndefined() {
    BgMinInsuranceIncome r = row("58", 1, "0.00");
    BgMinIncomeResult res = BgMinIncomeResolver.amountFor(r, 1);
    assertEquals(Status.AMOUNT_NOT_DEFINED, res.getStatus());
    assertTrue(res.isBlocking());
  }

  // ── end to end through the pure layer ───────────────────────────────────

  @Test
  void managerInWholesaleTradeGetsElevenTwentyNine() {
    BgMinIncomeResult g = BgMinIncomeResolver.qualificationGroup("11209001");
    BgMinIncomeResult row = BgMinIncomeResolver.rowCodeFor(null, nkid("46.11", "58", false));
    assertTrue(g.isResolved() && row.isResolved());
    assertEquals("58", row.getRowCode());
    BgMinIncomeResult amount = BgMinIncomeResolver.amountFor(row58(), g.getQualificationGroup());
    assertEquals(0, new BigDecimal("1129.00").compareTo(amount.getAmount()));
  }

  /** A blocking failure must never be silently replaced by the minimum wage. */
  @Test
  void blockingFailuresCarryNoAmount() {
    List<BgMinIncomeResult> failures =
        Arrays.asList(
            BgMinIncomeResolver.qualificationGroup(null),
            BgMinIncomeResolver.qualificationGroup("01110001"),
            BgMinIncomeResolver.rowCodeFor(null, nkid("98.10", null, false)),
            BgMinIncomeResolver.rowCodeFor(null, nkid("35.40", "49", true)),
            BgMinIncomeResolver.amountFor(row81(), 1));
    for (BgMinIncomeResult f : failures) {
      assertTrue(f.isBlocking(), f.toString());
      assertNull(f.getAmount(), f.toString());
    }
  }
}
