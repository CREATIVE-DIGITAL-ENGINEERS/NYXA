package com.axelor.apps.aiocr.service.ocr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free review checks for an OCR-extracted supplier invoice. These produce
 * human-facing readiness warnings only; they never mutate the invoice, never pick master data, and
 * never block draft creation (that is the hard gate's job). Kept side-effect-free so they are fully
 * unit-testable without Guice/JPA.
 *
 * <p>The two checks guard against the classic prebuilt-invoice failure modes: (1) Azure labelling
 * the WeAccount company as the vendor (vendor/customer swapped), and (2) processing a document that
 * was actually addressed to a different company than the one selected on the upload.
 */
public final class BgOcrReviewChecks {

  private BgOcrReviewChecks() {}

  /**
   * What the extracted identities say about which direction this document runs, relative to the
   * company the upload is being filed under.
   *
   * <p>{@link #SUPPLIER_INVOICE} is every case OCR intake already handles: the company is the
   * buyer, or nothing in the document contradicts that (customer identity absent, the common case
   * for Bulgarian invoices — never treated as a mismatch). {@link #CUSTOMER_INVOICE} means the
   * company's own identity was found on the VENDOR side instead — this looks like a sale the
   * company issued, not a purchase. {@link #AMBIGUOUS} covers everything else that must not be
   * guessed at: the company matches both sides, or the document names a customer that is neither
   * side of this company.
   */
  public enum Direction {
    SUPPLIER_INVOICE,
    CUSTOMER_INVOICE,
    AMBIGUOUS
  }

  /**
   * Whether a party carries at least one identifier that is well-formed enough to decide a
   * direction with — a BG ЕИК/Булстат (9 or 13 digits) or a foreign VAT number.
   *
   * <p>Callers use this on the COMPANY side as a precondition: {@link #resolveDirection} answers
   * {@link Direction#SUPPLIER_INVOICE} when it has no company identity to compare against, which is
   * the right default for an advisory check but the wrong one for a gate — it would let a document
   * between two other parties be filed as a purchase of a company that is simply missing its ЕИК.
   * Routing asks this first and refuses rather than assuming (see {@code
   * BgOcrIntakeService.checkDirectionOrReview}). Placeholder master data ({@code "N/A"}, {@code
   * "-"}, a company name typed into the ЕИК field) is not an identity and answers {@code false}.
   */
  public static boolean hasUsableIdentity(String eik, String vat) {
    return !ids(eik, vat).isEmpty();
  }

  /**
   * The canonical identifier set for one party, for callers that need to compare parties themselves
   * rather than ask a question this class already answers.
   *
   * <p>Exposed so that every comparison in OCR — direction, the company's own identity, a supplier
   * against the extracted vendor — canonicalizes identically. A second normalization elsewhere is
   * how {@code "BG100000019"} and {@code "100000019"} eventually stop being the same party in one
   * place and stay the same party in another.
   *
   * @return an unmodifiable set, possibly empty; never null
   */
  public static Set<String> canonicalIds(String eik, String vat) {
    return java.util.Collections.unmodifiableSet(ids(eik, vat));
  }

  /** Whether two parties share at least one canonical identifier. */
  public static boolean sameParty(String eikA, String vatA, String eikB, String vatB) {
    return intersects(ids(eikA, vatA), ids(eikB, vatB));
  }

  /**
   * Decides {@link Direction} from the same identifiers {@link #identityWarnings} compares, before
   * anything about this document is matched, deduplicated or written.
   *
   * <p>Deliberately conservative in the direction that matters: an ABSENT customer identity — the
   * ordinary case, since Azure does not always extract the recipient as cleanly as the vendor —
   * never counts as a mismatch, so the overwhelming majority of ordinary supplier invoices are
   * completely unaffected. Only a genuine signal (the company's own identity found where the vendor
   * should be, or a customer identity that was extracted and points nowhere useful) resolves to
   * anything other than {@link Direction#SUPPLIER_INVOICE}.
   */
  public static Direction resolveDirection(
      String supplierEik,
      String supplierVat,
      String customerEik,
      String customerVat,
      String companyRegistrationCode,
      String companyVat) {
    Set<String> supplier = ids(supplierEik, supplierVat);
    Set<String> customer = ids(customerEik, customerVat);
    Set<String> company = ids(companyRegistrationCode, companyVat);

    if (company.isEmpty()) {
      // Nothing to compare against — the existing "no company resolvable" failure elsewhere in the
      // pipeline is the right place for that, not this check.
      return Direction.SUPPLIER_INVOICE;
    }

    boolean matchesSupplier = intersects(supplier, company);
    boolean matchesCustomer = !customer.isEmpty() && intersects(customer, company);
    boolean customerExtracted = !customer.isEmpty();

    if (matchesSupplier && matchesCustomer) {
      return Direction.AMBIGUOUS;
    }
    if (matchesSupplier) {
      return Direction.CUSTOMER_INVOICE;
    }
    if (matchesCustomer) {
      return Direction.SUPPLIER_INVOICE;
    }
    if (customerExtracted) {
      // A customer WAS read off the document and it is neither this company nor (as checked above)
      // already flagged as a swap — the existing "wrong company" warning, promoted to a gate.
      return Direction.AMBIGUOUS;
    }
    // No customer identity at all: the ordinary case. Proceed exactly as before this check existed.
    return Direction.SUPPLIER_INVOICE;
  }

  /**
   * Cross-check the extracted supplier/customer identities against the WeAccount company's own
   * identity.
   *
   * <ul>
   *   <li>Supplier identity == company identity → likely vendor/customer swap.
   *   <li>Customer identity present and does NOT match company identity → invoice may belong to a
   *       different company.
   * </ul>
   *
   * All identifiers are normalised (spaces removed, a leading {@code BG} VAT prefix stripped, upper
   * cased) before comparison, so {@code "BG 131391369"} and {@code "131391369"} are treated as the
   * same party.
   *
   * @return the list of warnings (possibly empty; never null).
   */
  public static List<String> identityWarnings(
      String supplierEik,
      String supplierVat,
      String customerEik,
      String customerVat,
      String companyRegistrationCode,
      String companyVat) {
    List<String> warnings = new ArrayList<>();
    Set<String> supplier = ids(supplierEik, supplierVat);
    Set<String> customer = ids(customerEik, customerVat);
    Set<String> company = ids(companyRegistrationCode, companyVat);

    if (!company.isEmpty() && intersects(supplier, company)) {
      warnings.add(
          "OCR supplier identity matches your own company — Azure may have swapped vendor and "
              + "customer. Verify the supplier before approving.");
    }
    if (!company.isEmpty() && !customer.isEmpty() && !intersects(customer, company)) {
      warnings.add(
          "OCR invoice recipient does not match the selected company — verify this invoice "
              + "belongs to this company.");
    }
    return warnings;
  }

  /**
   * @return a warning when the OCR currency code differs from the currency actually resolved onto
   *     the invoice (from partner/company config), or {@code null} when they agree or either side
   *     is unknown. Comparison is case-insensitive on the ISO code.
   */
  public static String currencyMismatchNote(String ocrCurrencyCode, String resolvedIsoCode) {
    if (isBlank(ocrCurrencyCode) || isBlank(resolvedIsoCode)) {
      return null;
    }
    if (ocrCurrencyCode.trim().equalsIgnoreCase(resolvedIsoCode.trim())) {
      return null;
    }
    return "OCR currency ("
        + ocrCurrencyCode.trim()
        + ") differs from the invoice currency ("
        + resolvedIsoCode.trim()
        + ") resolved from partner/company — verify before approving.";
  }

  // ---------------------------------------------------------------------------

  /**
   * Canonical identifier set for one party (ЕИК + VAT), anything unusable dropped.
   *
   * <p>Canonical means the same thing here as in {@code SupplierEik}: punctuation and spaces
   * removed, upper-cased, a {@code BG} VAT prefix stripped so {@code "BG 100 000 019"} and {@code
   * "100000019"} are one party, and the result kept ONLY when it is a real identifier shape — a BG
   * ЕИК/Булстат (9 or 13 digits) or a foreign VAT ({@code XX} + 6–12 digits). Everything else is
   * dropped rather than compared: a placeholder or a name typed into the ЕИК field must never make
   * two parties "differ" (a false mismatch) nor count as an identity a gate can rely on.
   */
  private static Set<String> ids(String eik, String vat) {
    Set<String> set = new LinkedHashSet<>();
    addCanonical(set, eik);
    addCanonical(set, vat);
    return set;
  }

  private static void addCanonical(Set<String> set, String raw) {
    if (raw == null) {
      return;
    }
    String v = raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
    if (v.startsWith("BG")) {
      v = v.substring(2);
    }
    if (v.isEmpty()) {
      return;
    }
    if (v.chars().allMatch(Character::isDigit)) {
      if (v.length() == 9 || v.length() == 13) {
        set.add(v); // BG ЕИК / Булстат
      }
      return;
    }
    if (FOREIGN_VAT.matcher(v).matches()) {
      set.add(v); // foreign VAT — the country prefix is part of the identity
    }
  }

  private static final Pattern FOREIGN_VAT = Pattern.compile("[A-Z]{2}[0-9]{6,12}");

  private static boolean intersects(Set<String> a, Set<String> b) {
    for (String x : a) {
      if (b.contains(x)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }
}
