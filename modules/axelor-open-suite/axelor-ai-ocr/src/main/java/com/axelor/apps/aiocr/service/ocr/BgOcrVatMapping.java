package com.axelor.apps.aiocr.service.ocr;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps an extracted VAT rate (%) to a Bulgarian <b>purchase</b> tax code (supplier invoices), as
 * shipped in {@code l10n_bgr/BGR_COA/account_tax.csv}. Pure, unit-testable.
 *
 * <p>PROVISIONAL — only the common rates are mapped (20% / 9% / 0%). An unmapped rate returns
 * {@code null}; the intake service then leaves the line's tax empty for the accountant to complete
 * (it does not guess). Special regimes (reverse charge, ВОП, import, exempt sub-cases) are resolved
 * by the accountant on the draft for now.
 */
public final class BgOcrVatMapping {

  public static final String BG_STD_20 = "BG_SRP_20"; // Покупки – стандартна ставка 20%
  public static final String BG_REDUCED_9 = "BG_LRP_9"; // Покупки – намалена ставка 9%
  public static final String BG_ZERO = "BG_ZRP"; // Покупки с нулева ставка

  // The SALE (output VAT) mirrors of the three codes above, from the same l10n_bgr chart. They
  // exist because a document can be manually rerouted to a Customer Invoice: the lines were built
  // with input-VAT codes, and leaving those on a sale would post the VAT to the purchase side of
  // the ledger. Never used by automatic intake, which only ever builds supplier invoices.
  public static final String BG_STD_SALE_20 = "BG_SRS_20"; // Продажби – стандартна ставка 20%
  public static final String BG_REDUCED_SALE_9 = "BG_LRS_9"; // Продажби – намалена ставка 9%
  public static final String BG_ZERO_SALE = "BG_ZRS"; // Продажби с нулева ставка

  /**
   * The only tax codes a purchase line may be given.
   *
   * <p>A closed allowlist rather than a free-form string: it stops an output-VAT code (or a typo)
   * from ever landing on a supplier invoice line, which would misstate the VAT ledger in a way that
   * is hard to spot afterwards.
   */
  public static Set<String> purchaseTaxCodes() {
    return Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(BG_STD_20, BG_REDUCED_9, BG_ZERO)));
  }

  /**
   * The only tax codes a SALE line may be given — the closed allowlist mirroring {@link
   * #purchaseTaxCodes()}, so an input-VAT code can never survive onto a customer invoice line.
   */
  public static Set<String> saleTaxCodes() {
    return Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(BG_STD_SALE_20, BG_REDUCED_SALE_9, BG_ZERO_SALE)));
  }

  /**
   * The rates this mapping supports, as percentages — the single source of truth for "which rates
   * may a BG purchase line carry". {@link #taxCodeForRate} maps exactly these and nothing else, so
   * anything reasoning about candidate rates (see {@code BgOcrVatRateInference}) reads them here
   * rather than repeating the list and drifting from it.
   */
  public static List<BigDecimal> supportedRates() {
    return Collections.unmodifiableList(
        Arrays.asList(new BigDecimal("20"), new BigDecimal("9"), BigDecimal.ZERO));
  }

  private BgOcrVatMapping() {}

  /**
   * @return the BG purchase tax code for a rate, or {@code null} if the rate is not mapped.
   */
  public static String taxCodeForRate(BigDecimal ratePercent) {
    if (ratePercent == null) {
      return null;
    }
    int rate = ratePercent.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    switch (rate) {
      case 20:
        return BG_STD_20;
      case 9:
        return BG_REDUCED_9;
      case 0:
        return BG_ZERO;
      default:
        return null;
    }
  }

  /**
   * The BG <b>sale</b> (output VAT) tax code for a rate, or {@code null} if the rate is not mapped
   * — same three rates and the same "never guess an unmapped rate" rule as {@link #taxCodeForRate}.
   */
  public static String saleTaxCodeForRate(BigDecimal ratePercent) {
    String purchaseCode = taxCodeForRate(ratePercent);
    if (purchaseCode == null) {
      return null;
    }
    switch (purchaseCode) {
      case BG_STD_20:
        return BG_STD_SALE_20;
      case BG_REDUCED_9:
        return BG_REDUCED_SALE_9;
      case BG_ZERO:
        return BG_ZERO_SALE;
      default:
        return null;
    }
  }
}
