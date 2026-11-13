package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.account.db.repo.PaymentModeRepository;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Classifies Bulgarian invoice payment wording into Axelor payment mode types. A concrete
 * PaymentMode is selected later and only when both the OCR phrase and database match are unique.
 */
public final class BgOcrPaymentMethod {

  private BgOcrPaymentMethod() {}

  /**
   * @return the only payment type mentioned, or {@code null} when none or several are present.
   */
  public static Integer detectPaymentType(String text) {
    Set<Integer> matches = detectPaymentTypes(text);
    return matches.size() == 1 ? matches.iterator().next() : null;
  }

  /** Return all mentioned methods so callers can report conflicting OCR wording. */
  public static Set<Integer> detectPaymentTypes(String text) {
    Set<Integer> matches = new LinkedHashSet<>();
    if (text == null) {
      return matches;
    }
    String t = text.toLowerCase();
    if (containsAny(
        t, "банков път", "банков превод", "по банка", "bank transfer", "wire transfer")) {
      matches.add(PaymentModeRepository.TYPE_TRANSFER);
    }
    if (containsAny(t, "в брой", "в кеш", "кеш", "cash")) {
      matches.add(PaymentModeRepository.TYPE_CASH);
    }
    // Clear card-PAYMENT wording only. "кредитна карта" / "дебитна карта" name a card as the
    // payment instrument (e.g. "Плащане: Дебитна/Кредитна карта"); a bare "карта" is deliberately
    // NOT matched, so a loyalty-card number ("Номер на карта ...") or the plural "карти" on a
    // fiscal-receipt line never triggers card detection.
    if (containsAny(
        t,
        "с карта",
        "банкова карта",
        "кредитна карта",
        "дебитна карта",
        "картово",
        "bank card",
        "credit card",
        "debit card")) {
      matches.add(PaymentModeRepository.TYPE_BANK_CARD);
    }
    if (containsAny(t, "чек", "cheque", "check payment")) {
      matches.add(PaymentModeRepository.TYPE_CHEQUE);
    }
    return matches;
  }

  private static boolean containsAny(String text, String... values) {
    for (String value : values) {
      if (text.contains(value)) {
        return true;
      }
    }
    return false;
  }

  /** Short English label for a detected payment type, for readiness notes. */
  public static String label(Integer typeSelect) {
    if (typeSelect == null) {
      return "unknown";
    }
    switch (typeSelect) {
      case PaymentModeRepository.TYPE_TRANSFER:
        return "bank transfer";
      case PaymentModeRepository.TYPE_CASH:
        return "cash";
      case PaymentModeRepository.TYPE_BANK_CARD:
        return "bank card";
      case PaymentModeRepository.TYPE_CHEQUE:
        return "cheque";
      default:
        return "type " + typeSelect;
    }
  }
}
