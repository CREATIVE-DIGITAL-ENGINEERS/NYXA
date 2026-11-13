package com.axelor.apps.bglocalization.service.vat.adapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Maps a purchase line's {@code bgVatCreditType} (selection {@code bg.vat.credit.type.select}, on
 * both InvoiceLine and MoveLine) to its purchase-diary columns. Pure and stateless: the collector
 * calls it per line, and a per-line consumer can call it the same way without a document.
 *
 * <p>"1" → к.10/к.11 (full), "2" → к.12/к.13 (partial), "3" → к.09 (base + VAT, none). The value is
 * trimmed. Blank defaults to full credit — {@link #isDefaulted(String)} tells the caller so it can
 * warn. ⚠️ Any OTHER non-blank value also falls to full credit, and {@code isDefaulted} is {@code
 * false} for it: that silence is inherited diary behaviour, kept as is.
 */
public final class BgVatPurchaseCreditTypeMapping {

  public static final String CREDIT_FULL = "1";
  public static final String CREDIT_PARTIAL = "2";
  public static final String CREDIT_NONE = "3";

  private static final Set<String> CREDIT_TYPES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(Arrays.asList(CREDIT_FULL, CREDIT_PARTIAL, CREDIT_NONE)));

  private BgVatPurchaseCreditTypeMapping() {}

  /** Columns for {@code creditType}; never {@code null}. */
  public static BgVatPurchaseTarget forCreditType(String creditType) {
    String type = isDefaulted(creditType) ? CREDIT_FULL : creditType.trim();
    switch (type) {
      case CREDIT_PARTIAL:
        return BgVatPurchaseTarget.PARTIAL_CREDIT;
      case CREDIT_NONE:
        return BgVatPurchaseTarget.NO_CREDIT;
      case CREDIT_FULL:
      default:
        return BgVatPurchaseTarget.FULL_CREDIT;
    }
  }

  /** {@code true} when {@code creditType} is blank and {@link #forCreditType} assumed full. */
  public static boolean isDefaulted(String creditType) {
    return creditType == null || creditType.trim().isEmpty();
  }

  /** The credit-type codes this mapping recognises. */
  public static Set<String> creditTypes() {
    return CREDIT_TYPES;
  }
}
