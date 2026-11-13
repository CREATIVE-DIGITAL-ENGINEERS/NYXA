package com.axelor.apps.aiocr.service.ocr;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure decision logic for selecting/creating the supplier's {@code BankDetails} from an OCR IBAN.
 * The standard Supplier Invoice bankDetails field is filtered to the SUPPLIER partner's own active
 * bankDetailsList (see {@code BankDetailsDomainServiceAccountImpl}); so a match/creation must be
 * owned by the resolved supplier. No side effects, so it is fully unit-testable.
 */
public final class BgOcrBankDetails {

  private BgOcrBankDetails() {}

  // An IBAN token: country code + 2 check digits + 11..30 alphanumerics, allowing single spaces
  // between groups (Azure keeps them as "BG18 UBBS 8888 …"). The surrounding text may add a label
  // ("IBAN:", "Банкова сметка") or a trailing currency suffix ("(EUR)"); those are excluded because
  // '(' and ':' are not part of the token.
  private static final Pattern IBAN_TOKEN =
      Pattern.compile("[A-Z]{2}[0-9]{2}(?:[ ]?[A-Z0-9]){11,30}");

  // A strict Bulgarian IBAN: BG + 2 check digits + a 4-LETTER bank code + 14 more alphanumerics.
  // The 4-letter bank code makes false positives (VAT numbers, references) essentially impossible,
  // so this can be searched anywhere in the text even without a nearby label.
  private static final Pattern IBAN_BG_STRICT =
      Pattern.compile("BG[0-9]{2}[A-Z]{4}(?:[ ]?[0-9A-Z]){14}");

  // Labels that precede a bank account on Bulgarian invoices.
  private static final Pattern BANK_MARKER =
      Pattern.compile("(?iu)(iban|банкова\\s+сметка|сметка)");

  /** Outcome of the OCR bank-details decision; the intake service performs the matching I/O. */
  public enum Decision {
    /** No supplier or no structurally valid IBAN → do nothing. */
    SKIP,
    /** Exactly one active supplier-owned record matches the IBAN → select it. */
    SELECT,
    /** Several active supplier-owned records match → warn, select none. */
    WARN_MULTIPLE,
    /** IBAN exists but is owned by another partner/company → warn, do not reuse/duplicate. */
    WARN_OTHER_OWNER,
    /**
     * IBAN is new for this supplier and the bank is resolvable → create a supplier-owned record.
     */
    CREATE,
    /** IBAN is new but the bank (BIC/SWIFT) is unknown → warn, cannot create safely. */
    WARN_NO_BANK
  }

  /** Remove all whitespace and upper-case; null/blank → null. */
  public static String normalizeIban(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    return normalized.isEmpty() ? null : normalized;
  }

  /**
   * Extract a normalized IBAN from a value that may carry a label, internal spaces, or a trailing
   * suffix such as {@code " (EUR)"} — Azure returns the STSA account as {@code "BG42STSA…3828
   * (EUR)"} and the UBB account as {@code "BG18 UBBS 8888 …"}. Both must yield a clean IBAN.
   * Returns the space-free upper-cased IBAN token, or {@code null} when the value contains no
   * structurally-valid IBAN (so arbitrary strings are not accepted).
   */
  public static String extractIban(String value) {
    if (value == null) {
      return null;
    }
    Matcher m = IBAN_TOKEN.matcher(value.toUpperCase(Locale.ROOT));
    while (m.find()) {
      String candidate = m.group().replaceAll("\\s+", "");
      if (isValidIban(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Recover a supplier IBAN from the full OCR text when Azure produced no structured PaymentDetails
   * IBAN. Prefers an IBAN that follows a bank label ({@code IBAN:} / {@code Банкова сметка} /
   * {@code сметка}); otherwise accepts a strict Bulgarian IBAN (4-letter bank code) anywhere. Does
   * NOT depend on bold/formatting and tolerates spaces inside the number. Returns {@code null} when
   * nothing structurally valid is found — never a loose BG-looking string (a VAT/EIK such as {@code
   * BG100000026} is too short to pass {@link #isValidIban}).
   */
  public static String findIbanInText(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    Matcher marker = BANK_MARKER.matcher(text);
    while (marker.find()) {
      int start = marker.end();
      int end = Math.min(text.length(), start + 60);
      String iban = firstValid(IBAN_TOKEN, text.substring(start, end).toUpperCase(Locale.ROOT));
      if (iban != null) {
        return iban;
      }
    }
    return firstValid(IBAN_BG_STRICT, text.toUpperCase(Locale.ROOT));
  }

  /**
   * All structurally-valid strict Bulgarian IBANs (BG + 2 check digits + 4-letter bank code + 14)
   * in the text, in order of appearance and de-duplicated. The 4-letter bank code makes false
   * positives essentially impossible (a VAT/EIK such as {@code BG100000026} is too short to match).
   * Used for the multiple-IBAN fallback: when a normal invoice lists several supplier accounts and
   * none can be uniquely resolved, the caller takes the LAST of these.
   */
  public static java.util.List<String> findAllBgIbans(String text) {
    java.util.List<String> out = new java.util.ArrayList<>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    Matcher m = IBAN_BG_STRICT.matcher(text.toUpperCase(Locale.ROOT));
    while (m.find()) {
      String iban = m.group().replaceAll("\\s+", "");
      if (isValidIban(iban) && !out.contains(iban)) {
        out.add(iban);
      }
    }
    return out;
  }

  private static String firstValid(Pattern pattern, String haystack) {
    Matcher m = pattern.matcher(haystack);
    while (m.find()) {
      String candidate = m.group().replaceAll("\\s+", "");
      if (isValidIban(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** Structural IBAN check: 2 letters + 2 digits + 11..30 alphanumerics. */
  public static boolean isValidIban(String iban) {
    return iban != null && iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
  }

  /**
   * @param hasSupplier whether a supplier partner was resolved on the invoice.
   * @param normalizedIban IBAN already normalized (see {@link #normalizeIban}).
   * @param supplierMatchCount active supplier-owned records with this exact IBAN.
   * @param ibanOwnedByOther the IBAN is present on another partner/company (or null-partner).
   * @param bankResolvable a Bank can be resolved/created (a BIC/SWIFT is available).
   */
  public static Decision decide(
      boolean hasSupplier,
      String normalizedIban,
      int supplierMatchCount,
      boolean ibanOwnedByOther,
      boolean bankResolvable) {
    if (!hasSupplier || !isValidIban(normalizedIban)) {
      return Decision.SKIP;
    }
    if (supplierMatchCount == 1) {
      return Decision.SELECT;
    }
    if (supplierMatchCount > 1) {
      return Decision.WARN_MULTIPLE;
    }
    if (ibanOwnedByOther) {
      return Decision.WARN_OTHER_OWNER;
    }
    return bankResolvable ? Decision.CREATE : Decision.WARN_NO_BANK;
  }
}
