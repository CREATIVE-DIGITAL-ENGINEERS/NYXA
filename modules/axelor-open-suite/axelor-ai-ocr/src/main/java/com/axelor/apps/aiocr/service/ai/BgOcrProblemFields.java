package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.aiocr.service.ocr.BgOcrGate;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether an extraction is worth sending to the AI, and what to ask about.
 *
 * <p>The trigger is deliberately NOT "a field is empty". A populated-but-wrong value — an EIK that
 * is one digit short, an IBAN that fails its checksum, totals that do not add up — is exactly the
 * class of error the deterministic pipeline cannot fix, and is invisible to a missing-field check.
 * So the problem set is {@code missing ∪ suspicious ∪ warnings ∪ inconsistent}.
 *
 * <p>Pure and dependency-free (no DB, no Guice), like {@link BgOcrGate}, so it is fully unit
 * testable and can be verified against real fixtures before any AI is involved.
 */
public final class BgOcrProblemFields {

  /** Below this per-field OCR confidence a present value is treated as suspicious. */
  public static final double LOW_FIELD_CONFIDENCE = 0.70;

  // Field codes. These are the closed vocabulary the AI may return corrections for.
  public static final String SUPPLIER_EIK = "supplierEik";
  public static final String SUPPLIER_VAT = "supplierVat";
  public static final String SUPPLIER_NAME = "supplierName";
  public static final String SUPPLIER_ADDRESS = "supplierAddress";
  public static final String INVOICE_NO = "invoiceNo";
  public static final String INVOICE_DATE = "invoiceDate";
  public static final String CURRENCY = "currency";
  public static final String TOTAL_BASE = "totalBase";
  public static final String TOTAL_VAT = "totalVat";
  public static final String TOTAL_GROSS = "totalGross";
  public static final String IBAN = "iban";
  public static final String SWIFT = "swift";

  private BgOcrProblemFields() {}

  /** Every field the AI is allowed to return a correction for. */
  public static Set<String> allowedFields() {
    Set<String> f = new LinkedHashSet<>();
    f.add(SUPPLIER_EIK);
    f.add(SUPPLIER_VAT);
    f.add(SUPPLIER_NAME);
    f.add(SUPPLIER_ADDRESS);
    f.add(INVOICE_NO);
    f.add(INVOICE_DATE);
    f.add(CURRENCY);
    f.add(TOTAL_BASE);
    f.add(TOTAL_VAT);
    f.add(TOTAL_GROSS);
    f.add(IBAN);
    f.add(SWIFT);
    return f;
  }

  /**
   * @param result the extraction, after all deterministic recovery has run
   * @param supplierResolved whether {@code matchSupplier} already found exactly one Partner
   * @param companyEik the receiving company's own EIK, to spot self-invoices (may be null)
   * @return ordered, de-duplicated problem codes; empty means "do not call the AI"
   */
  public static Set<String> compute(
      OcrExtractResult result, boolean supplierResolved, String companyEik) {
    Set<String> problems = new LinkedHashSet<>();
    if (result == null) {
      return problems;
    }

    // --- missing -----------------------------------------------------------
    if (isBlank(result.getSupplierEik()) && isBlank(result.getSupplierVat())) {
      problems.add(SUPPLIER_EIK);
    }
    if (isBlank(result.getSupplierName())) {
      problems.add(SUPPLIER_NAME);
    }
    if (isBlank(result.getInvoiceNumber())) {
      problems.add(INVOICE_NO);
    }
    if (result.getInvoiceDate() == null) {
      problems.add(INVOICE_DATE);
    }
    if (isBlank(result.getCurrency())) {
      problems.add(CURRENCY);
    }
    if (isNonPositive(result.getTotalGross())) {
      problems.add(TOTAL_GROSS);
    }
    if (isBlank(result.getIban())) {
      problems.add(IBAN);
    }
    if (isBlank(result.getSupplierAddress())) {
      problems.add(SUPPLIER_ADDRESS);
    }

    // --- suspicious: low per-field confidence, and the existing critical-field gate ----
    // Revives BgOcrGate.fieldIssues, which until now was referenced only by its own test.
    for (String issue : BgOcrGate.fieldIssues(result)) {
      if (issue.startsWith("supplier")) {
        problems.add(SUPPLIER_EIK);
      } else if (issue.startsWith("invoice number")) {
        problems.add(INVOICE_NO);
      } else if (issue.startsWith("total")) {
        problems.add(TOTAL_GROSS);
      }
    }
    Map<String, Double> confidences = result.getFieldConfidence();
    if (confidences != null) {
      for (Map.Entry<String, Double> e : confidences.entrySet()) {
        if (e.getValue() != null && e.getValue() < LOW_FIELD_CONFIDENCE) {
          String mapped = mapAzureField(e.getKey());
          if (mapped != null) {
            problems.add(mapped);
          }
        }
      }
    }

    // --- inconsistent: present but internally contradictory -----------------
    if (!totalsReconcile(result)) {
      problems.add(TOTAL_BASE);
      problems.add(TOTAL_VAT);
      problems.add(TOTAL_GROSS);
    }
    if (!linesReconcile(result)) {
      problems.add(TOTAL_BASE);
    }
    if (!isBlank(result.getIban()) && !isIbanChecksumValid(result.getIban())) {
      problems.add(IBAN);
    }
    if (isSelfInvoice(result, companyEik)) {
      // The "supplier" reads as our own company — the identity was almost certainly mis-assigned.
      problems.add(SUPPLIER_EIK);
      problems.add(SUPPLIER_NAME);
    }
    if (result.getInvoiceDate() != null && result.getInvoiceDate().isAfter(LocalDate.now())) {
      problems.add(INVOICE_DATE);
    }
    if (hasUnmappableVatRate(result)) {
      problems.add(TOTAL_VAT);
    }
    if (!supplierResolved) {
      // The single most valuable case: without a Partner no invoice is created at all.
      problems.add(SUPPLIER_EIK);
    }

    // --- warnings raised upstream ------------------------------------------
    if (notEmpty(result.getWarnings()) || notEmpty(result.getDiscrepancies())) {
      // Warnings are free text and don't map to one field; they widen the ask only when the
      // extraction already looks doubtful elsewhere, so a clean invoice is never sent to the AI
      // just because a cosmetic warning was logged.
      if (!problems.isEmpty()) {
        problems.add(TOTAL_GROSS);
      }
    }

    return problems;
  }

  /**
   * Full ISO 13616 mod-97 check.
   *
   * <p>{@link BgOcrBankDetails#isValidIban} is deliberately left alone: it is a STRUCTURAL check (2
   * letters, 2 digits, 11-30 alphanumerics) that the deterministic pipeline depends on, and
   * tightening it there would change existing intake behaviour. But a structurally-plausible IBAN
   * with a broken checksum is exactly the "present but wrong" case AI is here to catch, and an
   * AI-suggested IBAN deserves more scrutiny than one read off a page — so the arithmetic check
   * lives here and is applied only on this path.
   *
   * @return true when the IBAN is structurally valid AND its checksum verifies
   */
  public static boolean isIbanChecksumValid(String iban) {
    String v = BgOcrBankDetails.normalizeIban(iban);
    if (v == null || !BgOcrBankDetails.isValidIban(v)) {
      return false;
    }
    String rearranged = v.substring(4) + v.substring(0, 4);
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < rearranged.length(); i++) {
      char c = rearranged.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      } else if (c >= 'A' && c <= 'Z') {
        digits.append(c - 'A' + 10);
      } else {
        return false;
      }
    }
    // Piecewise mod-97: the number is far too long for any primitive.
    int remainder = 0;
    for (int i = 0; i < digits.length(); i++) {
      remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
    }
    return remainder == 1;
  }

  /** {@code base + vat == gross}, when all three are present. */
  public static boolean totalsReconcile(OcrExtractResult r) {
    BigDecimal base = r.getTotalBase();
    BigDecimal vat = r.getTotalVat();
    BigDecimal gross = r.getTotalGross();
    if (base == null || vat == null || gross == null) {
      return true; // nothing to contradict
    }
    return base.add(vat).subtract(gross).abs().compareTo(new BigDecimal("0.02")) <= 0;
  }

  /** Sum of line amounts against the net total, when both are usable. */
  public static boolean linesReconcile(OcrExtractResult r) {
    List<OcrExtractLine> lines = r.getLines();
    BigDecimal base = r.getTotalBase();
    if (lines == null || lines.isEmpty() || base == null || base.signum() <= 0) {
      return true;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (OcrExtractLine line : lines) {
      if (line == null || line.getNetAmount() == null) {
        return true; // incomplete line data — not evidence of a contradiction
      }
      sum = sum.add(line.getNetAmount());
    }
    // Tolerant: rounding per line accumulates, and some documents omit discount rows.
    return sum.subtract(base).abs().compareTo(base.multiply(new BigDecimal("0.02"))) <= 0;
  }

  /** True when the supplier identity equals the receiving company's own EIK. */
  public static boolean isSelfInvoice(OcrExtractResult r, String companyEik) {
    String company = digits(companyEik);
    if (company == null) {
      return false;
    }
    return company.equals(digits(stripBg(r.getSupplierEik())))
        || company.equals(digits(stripBg(r.getSupplierVat())));
  }

  /** A VAT rate on a line that {@link BgOcrVatMapping} cannot map to a BG tax code. */
  private static boolean hasUnmappableVatRate(OcrExtractResult r) {
    List<OcrExtractLine> lines = r.getLines();
    if (lines == null) {
      return false;
    }
    for (OcrExtractLine line : lines) {
      if (line != null
          && line.getVatRate() != null
          && BgOcrVatMapping.taxCodeForRate(line.getVatRate()) == null) {
        return true;
      }
    }
    return false;
  }

  /** Maps Azure prebuilt-invoice field names onto our codes; null when not of interest. */
  private static String mapAzureField(String azureField) {
    if (azureField == null) {
      return null;
    }
    switch (azureField) {
      case "VendorName":
        return SUPPLIER_NAME;
      case "VendorTaxId":
        return SUPPLIER_EIK;
      case "VendorAddress":
        return SUPPLIER_ADDRESS;
      case "InvoiceId":
        return INVOICE_NO;
      case "InvoiceDate":
        return INVOICE_DATE;
      case "InvoiceTotal":
        return TOTAL_GROSS;
      case "SubTotal":
        return TOTAL_BASE;
      case "TotalTax":
        return TOTAL_VAT;
      case "PaymentDetails":
      case "IBAN":
        return IBAN;
      default:
        return null;
    }
  }

  private static boolean notEmpty(List<String> list) {
    return list != null && !list.isEmpty();
  }

  private static boolean isBlank(String v) {
    return v == null || v.trim().isEmpty();
  }

  private static boolean isNonPositive(BigDecimal v) {
    return v == null || v.signum() <= 0;
  }

  private static String stripBg(String v) {
    if (v == null) {
      return null;
    }
    String s = v.trim();
    return (s.length() > 2 && s.toUpperCase().startsWith("BG")) ? s.substring(2) : s;
  }

  private static String digits(String v) {
    if (v == null) {
      return null;
    }
    String d = v.replaceAll("\\D", "");
    return d.isEmpty() ? null : d;
  }
}
