package com.axelor.apps.aiaccountant.security;

import java.util.Locale;

/**
 * The one normalised ЕИК/VAT match rule for every assistant partner lookup, in any module.
 *
 * <p>The same semantics as {@code SupplierEik} in the OCR module, lifted here because that module
 * sits downstream of the accounting one and cannot be depended on from it. Module-neutral: it only
 * builds strings and JPQL fragments over {@code Partner}, which lives in axelor-base.
 *
 * <p><b>Canonical ЕИК = digits only.</b> A BG VAT number is {@code BG} + ЕИК, an "ЕИК:"/"БУЛСТАТ"
 * label may precede it, and spaces and punctuation are noise — so {@code "BG203970390"}, {@code
 * "ЕИК: 203 970 390"} and {@code "203970390"} all canonicalise to {@code "203970390"}. A foreign
 * VAT (a non-BG country prefix) is not a BG ЕИК and yields {@code null}.
 *
 * <p><b>Both stored fields.</b> On {@code Partner} the ЕИК lives in {@code registrationCode} and
 * the VAT number in {@code taxNbr}. A BG VAT number and an ЕИК are the same key, so a match on
 * either input must find a partner that stored it in the other field.
 *
 * <p>Company scope, archiving and company-identity exclusion are separate fragments so each caller
 * states all of them in its own query; nothing here widens or narrows a scope on its own.
 */
public final class AiPartnerIdentity {

  private AiPartnerIdentity() {}

  /**
   * The canonical ЕИК (digits only, 9–13) for a raw ЕИК or BG VAT number, or {@code null} when the
   * input is not a usable BG ЕИК/VAT (blank, a name, a foreign VAT, or a wrong length).
   */
  public static String canonicalEik(String raw) {
    if (raw == null) {
      return null;
    }
    // Upper-case and keep only [A-Z0-9]: this alone drops the Cyrillic "ЕИК"/"БУЛСТАТ" label,
    // spaces, colons, dots and slashes. What survives is digits, optionally a Latin country prefix.
    String c = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (c.isEmpty()) {
      return null;
    }
    if (c.chars().allMatch(Character::isDigit)) {
      return c.matches("\\d{9,13}") ? c : null;
    }
    // Letters remain: only a leading BG country code is allowed. Any other prefix is a foreign VAT.
    if (c.startsWith("BG")) {
      String digits = c.substring(2);
      return digits.matches("\\d{9,13}") ? digits : null;
    }
    return null;
  }

  /**
   * A VAT number that is not a BG one, as it is compared against {@code taxNbr}: whitespace removed
   * and upper-cased. Null for a blank input.
   */
  public static String foreignVatKey(String raw) {
    if (raw == null) {
      return null;
    }
    String key = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    return key.isEmpty() ? null : key;
  }

  /**
   * Partners whose registration code OR the ЕИК inside their VAT number equals a canonical ЕИК,
   * both stored sides normalised the same way (upper-cased, spaces and a {@code BG} prefix
   * removed). Bind {@code param} to a value from {@link #canonicalEik}.
   *
   * @param path the partner-valued path, e.g. {@code "self"}
   * @param param the bind name, without the colon
   */
  public static String eikCondition(String path, String param) {
    return "(REPLACE(REPLACE(UPPER(COALESCE("
        + path
        + ".registrationCode, '')), ' ', ''), 'BG', '') = :"
        + param
        + " OR REPLACE(REPLACE(UPPER(COALESCE("
        + path
        + ".taxNbr, '')), ' ', ''), 'BG', '') = :"
        + param
        + ")";
  }

  /** Partners whose stored VAT number equals a {@link #foreignVatKey}. Bind {@code param}. */
  public static String foreignVatCondition(String path, String param) {
    return "REPLACE(UPPER(COALESCE(" + path + ".taxNbr, '')), ' ', '') = :" + param;
  }

  /** Never an archived (soft-deleted) partner. */
  public static String notArchived(String path) {
    return "(" + path + ".archived IS NULL OR " + path + ".archived = FALSE)";
  }

  /**
   * Never any company's own identity — the {@code Partner} row a form shows as "the company" rather
   * than as anybody's supplier or customer.
   */
  public static String notCompanyIdentity(String path) {
    return "NOT EXISTS (SELECT 1 FROM Company aiIdentity WHERE aiIdentity.partner = " + path + ")";
  }
}
