package com.axelor.apps.aiocr.service.readiness;

import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.i18n.I18n;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the invoice form says about an OCR draft's review flag, in the user's language.
 *
 * <p>{@link BgOcrIntakeService#missingReviewFields} and {@link BgOcrIntakeService#reviewWarnings}
 * return stable English keys, because the AI tools quote them and {@code ocrMissingFields} stores
 * them. The form turns each key into a sentence that says which field it is and where it is, so a
 * read-only Validate button is never left without a reason. The keys below are English; the
 * Bulgarian text is in this module's {@code i18n/messages_bg.csv}.
 */
public final class OcrReviewMessages {

  private OcrReviewMessages() {}

  public static final String VALIDATE_LOCKED =
      /*$$(*/ "Validate is locked until this OCR draft has: %s. The button unlocks as soon as they are filled in." /*)*/;

  public static final String MISSING_PARTNER =
      /*$$(*/ "the supplier (field Partner, at the top of the invoice)" /*)*/;

  public static final String MISSING_ADDRESS =
      /*$$(*/ "the supplier's address (field Address, under the partner)" /*)*/;

  public static final String MISSING_CURRENCY =
      /*$$(*/ "the currency (field Currency, at the top of the invoice)" /*)*/;

  public static final String MISSING_TOTAL =
      /*$$(*/ "a total above zero (enter the invoice lines with their prices; the total is calculated from them)" /*)*/;

  public static final String WARNING_BANK_DETAILS =
      /*$$(*/ "No bank account: it will be needed to pay the invoice (field Bank details). It does not block validation." /*)*/;

  public static final String WARNING_PAYMENT_CONDITION =
      /*$$(*/ "No payment condition: it will be needed to post the invoice (field Payment condition). It does not block validation." /*)*/;

  private static final Map<String, String> TEXT =
      Map.of(
          BgOcrIntakeService.REVIEW_PARTNER, MISSING_PARTNER,
          BgOcrIntakeService.REVIEW_ADDRESS, MISSING_ADDRESS,
          BgOcrIntakeService.REVIEW_CURRENCY, MISSING_CURRENCY,
          BgOcrIntakeService.REVIEW_TOTAL, MISSING_TOTAL,
          BgOcrIntakeService.REVIEW_BANK_DETAILS, WARNING_BANK_DETAILS,
          BgOcrIntakeService.REVIEW_PAYMENT_CONDITION, WARNING_PAYMENT_CONDITION);

  /** One key in the user's language; an unknown key is shown as it is rather than dropped. */
  public static String describe(String key) {
    return describe(key, I18n::get);
  }

  /** The missing fields as one line, {@code ; }-separated; null when nothing is missing. */
  public static String missingText(List<String> missing) {
    return missingText(missing, I18n::get);
  }

  /** Why Validate is read-only; null when nothing is missing and the button is not locked. */
  public static String lockHint(List<String> missing) {
    return lockHint(missing, I18n::get);
  }

  /** The payment-side warnings, one per line; null when there is none. */
  public static String warningsText(List<String> warnings) {
    return warningsText(warnings, I18n::get);
  }

  // The same, with the translation passed in: I18n reads the database, so a test renders the
  // Bulgarian text from this module's messages_bg.csv instead.

  static String describe(String key, Function<String, String> translate) {
    String text = TEXT.get(key);
    return text == null ? key : translate.apply(text);
  }

  static String missingText(List<String> missing, Function<String, String> translate) {
    if (missing == null || missing.isEmpty()) {
      return null;
    }
    return missing.stream().map(k -> describe(k, translate)).collect(Collectors.joining("; "));
  }

  static String lockHint(List<String> missing, Function<String, String> translate) {
    String text = missingText(missing, translate);
    return text == null ? null : String.format(translate.apply(VALIDATE_LOCKED), text);
  }

  static String warningsText(List<String> warnings, Function<String, String> translate) {
    if (warnings == null || warnings.isEmpty()) {
      return null;
    }
    return warnings.stream().map(k -> describe(k, translate)).collect(Collectors.joining("\n"));
  }
}
