package com.axelor.apps.aiocr.service.supplier;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.Query;
import java.util.Locale;

/**
 * The single canonical supplier-ЕИК rule used everywhere OCR resolves a supplier: the intake
 * pipeline ({@code BgOcrIntakeService.matchSupplier}) and the assistant's lookup tool ({@code
 * FindPartnerByEikTool}). Keeping one implementation guarantees the AI cannot "resolve" a supplier
 * the intake would then reject, and vice-versa.
 *
 * <p><b>Canonical ЕИК = digits only.</b> A BG VAT number is just {@code BG} + ЕИК, an "ЕИК:"/
 * "БУЛСТАТ" label may precede it, and OCR often adds spaces/punctuation — all of that is stripped
 * so that {@code "BG203970390"}, {@code "ЕИК: 203 970 390"} and {@code "203970390"} all
 * canonicalize to {@code "203970390"}. The VAT string itself is never mutated and {@code BG}/labels
 * are never stored inside the ЕИК value; this class only derives the match key. A foreign VAT (a
 * non-BG country prefix) is not a BG ЕИК and yields {@code null}.
 *
 * <p><b>Identity fields.</b> On {@code Partner} the ЕИК lives in {@code registrationCode}
 * (\"Registration number\") and the VAT number in {@code taxNbr} (\"Tax N°\"). Suppliers are
 * matched ONLY by this key — never by name, IBAN or address — and a Partner is NEVER created here
 * (supplier master data is maintained by the accountant).
 */
public final class SupplierEik {

  private SupplierEik() {}

  /**
   * The canonical ЕИК (digits only, 9–13) for the given raw ЕИК or BG VAT number, or {@code null}
   * when the input is not a usable BG ЕИК/VAT (blank, a name, a foreign VAT, or a wrong length).
   */
  public static String canonical(String raw) {
    if (raw == null) {
      return null;
    }
    // Upper-case and keep only [A-Z0-9]: this alone drops the Cyrillic "ЕИК"/"БУЛСТАТ" label,
    // spaces,
    // colons, dots and slashes. What survives is digits, optionally a Latin country prefix.
    String c = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (c.isEmpty()) {
      return null;
    }
    if (c.chars().allMatch(Character::isDigit)) {
      return c.matches("\\d{9,13}") ? c : null;
    }
    // Letters remain: only a leading BG country code is allowed (a BG VAT number is BG + ЕИК). Any
    // other prefix (FR, DE, …) is a foreign VAT, not a BG ЕИК.
    if (c.startsWith("BG")) {
      String digits = c.substring(2);
      return digits.matches("\\d{9,13}") ? digits : null;
    }
    return null;
  }

  /**
   * Partners whose registration number OR the ЕИК embedded in their VAT number equals the given
   * canonical ЕИК, with both stored sides normalized the same way (upper-cased, spaces and a {@code
   * BG} prefix removed). Archived (soft-deleted) partners are never matched — OCR must not resolve
   * an invoice to a partner the accountant has retired. Pass a value already run through {@link
   * #canonical(String)}. The caller decides how many rows to fetch and interprets the count:
   * exactly one → use it; zero → not found; several → ambiguous (never guess).
   */
  public static Query<Partner> match(PartnerRepository partnerRepo, String canonicalEik) {
    return partnerRepo.all().filter(EIK_FILTER).bind("eik", canonicalEik);
  }

  /**
   * As {@link #match}, additionally restricted to partners usable by one company.
   *
   * <p>This is the form every AI-facing lookup uses: {@code companySet} empty means the partner is
   * shared across all companies, a non-empty set restricts it to those, and the filter runs in SQL
   * so a partner outside the caller's company is never loaded, counted or reported — it reads as
   * "no such EIK".
   *
   * <p>The deterministic intake keeps using the unscoped {@link #match}: it runs before any AI
   * scope exists and it must see the same candidate set the accountant sees in the form, or an
   * invoice would silently resolve to a different supplier depending on who triggered processing.
   */
  public static Query<Partner> matchForCompany(
      PartnerRepository partnerRepo, String canonicalEik, Long companyId) {
    return partnerRepo
        .all()
        .filter(EIK_FILTER + " AND " + AiCompanyScope.partnerFilter("self"))
        .bind("eik", canonicalEik)
        .bind(AiCompanyScope.COMPANY_PARAM, companyId);
  }

  /**
   * The ЕИК comparison on any Partner-valued path, against both {@code registrationCode} and {@code
   * taxNbr}, each with spaces and a {@code BG} prefix stripped — the stored-side half of {@link
   * #canonical}. Bind {@code param} to a canonical ЕИК. No archive or company condition: callers
   * scope it themselves.
   *
   * @param partnerPath e.g. {@code "self"} on a Partner query, {@code "self.invoice.partner"} on an
   *     upload query
   */
  public static String identityCondition(String partnerPath, String param) {
    return "(REPLACE(REPLACE(UPPER(COALESCE("
        + partnerPath
        + ".registrationCode, '')), ' ', ''), 'BG', '') = :"
        + param
        + " OR REPLACE(REPLACE(UPPER(COALESCE("
        + partnerPath
        + ".taxNbr, '')), ' ', ''), 'BG', '') = :"
        + param
        + ")";
  }

  /** The ЕИК match itself, without any company scope. Bind {@code eik}. */
  static final String EIK_FILTER =
      identityCondition("self", "eik") + " AND (self.archived IS NULL OR self.archived = FALSE)";
}
