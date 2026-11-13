package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgMinIncomeOverride;
import com.axelor.apps.bglocalization.db.BgMinInsuranceIncome;
import com.axelor.apps.bglocalization.db.BgMinInsuranceIncomeAmount;
import com.axelor.apps.bglocalization.db.BgNkid;
import java.math.BigDecimal;
import java.util.List;

/**
 * Pure resolution logic for the minimum insurance income (МОД) lookup.
 *
 * <p>Contains no database access and no Guice injection, so every branch is unit testable by
 * constructing entities in memory. Database fetching lives in {@link BgMinInsuranceIncomeService}.
 *
 * <p>The lookup has two axes, both of which are Declaration 1 fields:
 *
 * <ul>
 *   <li>the row from Appendix 1, derived from the employer's economic activity (НКИД)
 *   <li>the qualification group column, derived from the first digit of the employee's НКПД code
 * </ul>
 */
public class BgMinIncomeResolver {

  private BgMinIncomeResolver() {}

  /**
   * Extracts the qualification group from an НКПД code.
   *
   * <p>The group is the first digit of the 8-digit code. {@code BgNkpd} already stores it in a
   * dedicated field, but the contract may carry the code as a plain string, so this is the
   * authoritative derivation.
   *
   * @param nkpdCode the 8-digit НКПД code, may be null or blank
   * @return a RESOLVED-shaped result carrying only the group, or a blocking failure
   */
  public static BgMinIncomeResult qualificationGroup(String nkpdCode) {
    if (nkpdCode == null || nkpdCode.trim().isEmpty()) {
      return BgMinIncomeResult.failure(
          BgMinIncomeResult.Status.NKPD_MISSING, "НКПД кодът не е зададен на трудовия договор");
    }
    String code = nkpdCode.trim();
    char first = code.charAt(0);
    if (first < '0' || first > '9') {
      return BgMinIncomeResult.failure(BgMinIncomeResult.Status.NKPD_INVALID, "НКПД код: " + code);
    }
    int group = first - '0';
    if (group == 0) {
      // Class 0 is the armed forces. Appendix 1 has no column for it.
      return BgMinIncomeResult.failure(
          BgMinIncomeResult.Status.QUALIFICATION_GROUP_ZERO, "НКПД код: " + code);
    }
    return BgMinIncomeResult.resolved(null, null, group);
  }

  /**
   * Decides which Appendix 1 row applies, honouring the company-level override.
   *
   * <p>An override always wins. Without one, an ambiguous НКИД blocks: code 35.40 maps to row 49
   * for electricity brokers and row 50 for natural gas brokers, and those rows differ (936 versus
   * 1270 in group 1). The distinction is by commodity and is derivable from neither НКИД nor НКПД.
   *
   * @param companyOverride the company's explicit row code, may be null or blank
   * @param nkid the economic activity, may be null when the company has none assigned
   */
  public static BgMinIncomeResult rowCodeFor(String companyOverride, BgNkid nkid) {
    if (companyOverride != null && !companyOverride.trim().isEmpty()) {
      return BgMinIncomeResult.resolved(null, companyOverride.trim(), 0);
    }
    if (nkid == null) {
      return BgMinIncomeResult.failure(
          BgMinIncomeResult.Status.NKID_NOT_FOUND, "НКИД кодът на дружеството не е намерен");
    }
    if (Boolean.TRUE.equals(nkid.getBgAmbiguous())) {
      String note = nkid.getBgMinIncomeNote();
      return BgMinIncomeResult.failure(
          BgMinIncomeResult.Status.AMBIGUOUS_REQUIRES_OVERRIDE,
          "НКИД " + nkid.getCode() + (note != null ? ". " + note : ""));
    }
    String row = nkid.getBgMinIncomeRowCode();
    if (row == null || row.trim().isEmpty()) {
      // Legitimate for 98.10 and 98.20 — households as employers of domestic staff have no row.
      return BgMinIncomeResult.failure(
          BgMinIncomeResult.Status.NO_MOD_ROW_FOR_NKID,
          "НКИД " + nkid.getCode() + " няма ред по Приложение №1");
    }
    return BgMinIncomeResult.resolved(null, row.trim(), 0);
  }

  /**
   * Applies a position-level override if one matches, otherwise returns the base row unchanged.
   *
   * <p>Four rows in Appendix 1 apply only to a specific occupation: professional footballers (row
   * 81), heads of hospital clinics (84), nurses and midwives (85), and clergy (86). The longest
   * matching НКПД prefix wins, so a more specific rule beats a broader one.
   *
   * @param overrides candidates already filtered to the relevant НКИД, never null
   */
  public static String applyPositionOverride(
      List<BgMinIncomeOverride> overrides, String nkpdCode, String baseRowCode) {
    if (overrides == null || overrides.isEmpty() || nkpdCode == null) {
      return baseRowCode;
    }
    String code = nkpdCode.trim();
    String best = null;
    int bestLength = -1;
    for (BgMinIncomeOverride o : overrides) {
      String prefix = o.getBgNkpdPrefix();
      if (prefix == null || prefix.trim().isEmpty()) {
        continue;
      }
      prefix = prefix.trim();
      if (code.startsWith(prefix) && prefix.length() > bestLength) {
        bestLength = prefix.length();
        best = o.getBgMinIncomeRowCode();
      }
    }
    return best != null ? best : baseRowCode;
  }

  /**
   * Picks the amount for a qualification group from a row's amount list.
   *
   * <p>A missing entry is not a default — Appendix 1 leaves cells empty only on rows restricted to
   * one occupation, and those are reachable only through a position-level override whose group
   * always matches. Reaching this failure means the override table is misconfigured.
   */
  public static BgMinIncomeResult amountFor(BgMinInsuranceIncome row, int group) {
    if (row == null) {
      return BgMinIncomeResult.failure(
          BgMinIncomeResult.Status.MOD_ROW_NOT_FOUND, "няма ред за периода");
    }
    List<BgMinInsuranceIncomeAmount> amounts = row.getBgAmountList();
    if (amounts != null) {
      for (BgMinInsuranceIncomeAmount a : amounts) {
        // The generated Integer getter returns 0, never null, so a null guard here is dead code.
        // Dropping it is safe rather than merely tidy: an amount row with no group reads as 0, and
        // 0 is not a reachable group — qualificationGroup() blocks НКПД class 0 (armed forces)
        // before any lookup — so such a row matches nothing, which is the intended outcome.
        if (a.getBgQualificationGroup() == group) {
          BigDecimal value = a.getAmount();
          // Same trap on the amount: the decimal getter returns ZERO rather than null. A zero or
          // negative floor cannot be legitimate — the lowest value in Appendix 1 is the minimum
          // wage — so it signals corrupt seed data.
          if (value.signum() > 0) {
            return BgMinIncomeResult.resolved(value, row.getBgRow(), group);
          }
        }
      }
    }
    return BgMinIncomeResult.failure(
        BgMinIncomeResult.Status.AMOUNT_NOT_DEFINED,
        row.getBgRow(),
        group,
        "ред " + row.getBgRow() + " няма стойност за квалификационна група " + group);
  }
}
