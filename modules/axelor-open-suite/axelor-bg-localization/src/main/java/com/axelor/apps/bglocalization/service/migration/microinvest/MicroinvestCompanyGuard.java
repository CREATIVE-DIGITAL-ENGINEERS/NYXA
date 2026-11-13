package com.axelor.apps.bglocalization.service.migration.microinvest;

import java.util.Locale;

/**
 * The single place that decides WHICH Nyxa company a Microinvest export may be imported into.
 *
 * <p>⚠️ THE IMPORTER NEVER CREATES A COMPANY. A find-or-create by the source Bulstat produces a
 * second Company whenever the source name/Bulstat pair does not resolve to the already-configured
 * company, and then hangs every contract, payslip and declaration off that invented row instead of
 * the real one. A migration tool has no business creating the legal entity it migrates into -- the
 * operator sets that up (chart of accounts, payroll config, bank details) long before any import
 * runs.
 *
 * <p>So the target is the operator's ACTIVE company, always, and the JSON company block is reduced
 * to source IDENTITY: its Bulstat is compared against the active company's own EIK and a mismatch
 * aborts the run before a single write. Comparison is on digits only ({@link #normalizeBulstat}) --
 * never on name, which is not a key (Microinvest's "ACME" vs Nyxa's "ACME ООД" is the same legal
 * entity, while two unrelated companies may share a trade name).
 */
public final class MicroinvestCompanyGuard {

  private MicroinvestCompanyGuard() {}

  /**
   * Digits of a Bulgarian EIK/Bulstat, comparable across the formats the two systems use: an
   * optional "BG" VAT prefix, spaces, dashes and slashes are dropped, case is irrelevant. A blank
   * or digit-free value normalizes to {@code null} ("no identifier"), never to an empty string that
   * would then compare equal to another blank one.
   *
   * <p>⚠️ Deliberately does NOT treat a 13-digit branch Bulstat as equal to its 9-digit parent:
   * they are different registrations with different obligations, and silently importing a branch's
   * payroll into the parent (or the reverse) is exactly the kind of cross-company contamination
   * this guard exists to stop. If a client really needs that, it is an explicit decision, not a
   * normalization rule.
   */
  public static String normalizeBulstat(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (cleaned.startsWith("BG")) {
      cleaned = cleaned.substring(2);
    }
    cleaned = cleaned.replaceAll("[^0-9]", "");
    return cleaned.isEmpty() ? null : cleaned;
  }

  /** Why an import may or may not proceed against the active company. */
  public enum Verdict {
    /** Source Bulstat and active-company EIK are the same registration -- proceed. */
    MATCH,
    /** Both identifiers are present and different -- abort before any write. */
    MISMATCH,
    /** No active company on the current user -- nothing to import into. */
    NO_ACTIVE_COMPANY,
    /** The active company's Partner carries no registrationCode (EIK) to compare against. */
    NO_ACTIVE_IDENTIFIER,
    /** The export file's company block carries no Bulstat. */
    NO_SOURCE_IDENTIFIER
  }

  /**
   * Pure decision, given the two already-extracted identifiers. {@code activeEik} is {@code
   * Company.partner.registrationCode}; pass {@code null} for "no active company at all" only
   * through {@link #verdict(String, String, boolean)}.
   */
  public static Verdict verdict(String sourceBulstat, String activeEik) {
    return verdict(sourceBulstat, activeEik, true);
  }

  public static Verdict verdict(String sourceBulstat, String activeEik, boolean hasActiveCompany) {
    if (!hasActiveCompany) {
      return Verdict.NO_ACTIVE_COMPANY;
    }
    String source = normalizeBulstat(sourceBulstat);
    if (source == null) {
      return Verdict.NO_SOURCE_IDENTIFIER;
    }
    String active = normalizeBulstat(activeEik);
    if (active == null) {
      return Verdict.NO_ACTIVE_IDENTIFIER;
    }
    return source.equals(active) ? Verdict.MATCH : Verdict.MISMATCH;
  }

  /** The confirmation banner shown on the wizard form and at the top of both reports. */
  public static String targetBanner(String activeName, String activeEik) {
    return "Данните ще бъдат импортирани в активната компания '"
        + (activeName == null ? "?" : activeName)
        + "' (ЕИК "
        + (activeEik == null || activeEik.isBlank() ? "липсва" : activeEik)
        + "). Нова компания няма да бъде създадена.";
  }

  /**
   * One error line naming BOTH companies and BOTH identifiers -- the operator has to be able to see
   * from the report alone whether they uploaded the wrong file or switched to the wrong active
   * company, without opening either system.
   */
  public static String problemMessage(
      Verdict verdict,
      String sourceName,
      String sourceBulstat,
      String activeName,
      String activeEik) {
    switch (verdict) {
      case NO_ACTIVE_COMPANY:
        return "няма активна компания за текущия потребител -- изберете активна компания"
            + " (Preferences -> Active company) и стартирайте отново. Импортът НЕ създава компания.";
      case NO_SOURCE_IDENTIFIER:
        return "експортният файл няма БУЛСТАТ на компанията -- не може да се докаже, че данните са"
            + " за активната компания '"
            + activeName
            + "' (ЕИК "
            + activeEik
            + ")";
      case NO_ACTIVE_IDENTIFIER:
        return "активната компания '"
            + activeName
            + "' няма попълнен ЕИК (Partner.registrationCode) -- попълнете го, за да може да се"
            + " сравни с БУЛСТАТ "
            + sourceBulstat
            + " от файла (източник: '"
            + sourceName
            + "')";
      case MISMATCH:
        return "БУЛСТАТ от файла не съвпада с ЕИК на активната компания: източник '"
            + sourceName
            + "' (БУЛСТАТ "
            + sourceBulstat
            + ") срещу активна компания '"
            + activeName
            + "' (ЕИК "
            + activeEik
            + "). Нищо не е записано. Сменете активната компания или качете верния файл.";
      default:
        return "";
    }
  }
}
