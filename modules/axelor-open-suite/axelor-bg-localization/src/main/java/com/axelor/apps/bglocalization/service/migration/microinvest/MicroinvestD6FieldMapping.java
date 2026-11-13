package com.axelor.apps.bglocalization.service.migration.microinvest;

import com.axelor.apps.bglocalization.db.BgD6Declaration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Microinvest {@code Dekl6} field -&gt; {@code BgD6Declaration} field, for the Phase 2 historical
 * import. Matched on {@code Dekl6.Name} (a short, fixed, non-hidden set of 13 fields confirmed
 * against the pilot company's own Dekl6 table -- no homoglyph-suffix risk like Dekl1's а/б points,
 * so unlike {@link MicroinvestD1FieldMapping} this one matches by name directly).
 *
 * <p>Microinvest's {@code Dekl6Value} carries up to four parallel "section" values per field per
 * month (see {@code source-data-inventory.md} §9: the pilot company's data shows two of the four
 * populated in the same month, one carrying the contribution totals and the other the withheld
 * tax). {@code BgD6Declaration} models ONE row per company per period, so the four sections are
 * merged here: for an identity/text/date field (name, email...) the first non-blank section wins
 * (all populated sections carried the identical value in every case checked); for a EUR-amount
 * field the first NON-ZERO section wins. A genuine conflict (two sections both non-zero and
 * different) is not expected from the samples checked and is not specially detected -- this merge
 * is a deliberate simplification for a one-time historical import, not a live reconciliation.
 *
 * <p>Deliberately NOT mapped: т. 7.1 (код корекция) and т. 8.1 (вид плащане) -- Microinvest's raw
 * "Флаг за промяна"/"Флаг за вид плащане" values (seen as plain integers like "0"/"52"/"8" in the
 * pilot data) do not visibly correspond to Nyxa's letter/single-digit codes for one of the two
 * fields checked ("52" matches none of Nyxa's documented 5/6/8), so guessing a translation was
 * rejected in favor of leaving both blank -- see migration-plan.md for the report on this.
 */
final class MicroinvestD6FieldMapping {

  private static final String PAYMENT_DATE_FIELD = "Дата на изплащане";
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private static final Map<String, BiConsumer<BgD6Declaration, String>> STRING_SETTERS =
      new HashMap<>();
  private static final Map<String, BiConsumer<BgD6Declaration, BigDecimal>> DECIMAL_SETTERS =
      new HashMap<>();

  static {
    STRING_SETTERS.put("Код на осигурителя (Булстат)", BgD6Declaration::setT1ObligedPersonCode);
    STRING_SETTERS.put("Наименование на осигурителя", BgD6Declaration::setT2Name);
    STRING_SETTERS.put("Член на осигурителна каса", BgD6Declaration::setT3InsuranceFundMember);
    STRING_SETTERS.put("E-mail", BgD6Declaration::setT4Email);
    STRING_SETTERS.put("Телефон", BgD6Declaration::setT5Phone);
    STRING_SETTERS.put("GSM", BgD6Declaration::setT6Gsm);

    DECIMAL_SETTERS.put("Дължими вноски за ДОО", BgD6Declaration::setT10Doo);
    DECIMAL_SETTERS.put(
        "Дължими вноски за учителски пенсионен фонд", BgD6Declaration::setT11TeacherPf);
    DECIMAL_SETTERS.put(
        "Дължими вноски за ДЗПО - Универсален пенсионен фонд", BgD6Declaration::setT12Upf);
    DECIMAL_SETTERS.put(
        "Дължими вноски за ДЗПО - Професионален пенсионен фонд", BgD6Declaration::setT13Ppf);
    DECIMAL_SETTERS.put("Дължими вноски за здравно осигуряване", BgD6Declaration::setT14Health);
    DECIMAL_SETTERS.put(
        "Дължими вноски за фонд \"Гарантиране на вземанията на работниците и служителите\"",
        BgD6Declaration::setT15Gvrs);
    DECIMAL_SETTERS.put("Авансово удържан данък по чл.42 от ЗДДФЛ", BgD6Declaration::setT16Tax);
    DECIMAL_SETTERS.put("Авансов данък по чл.42, ал.5 от ЗДДФЛ", BgD6Declaration::setT17AdvanceTax);
  }

  private MicroinvestD6FieldMapping() {}

  /**
   * {@code sections} is the four ValueSection1..4 raw strings, in order, blanks/nulls allowed.
   *
   * @return {@code true} if {@code fieldName} is a known field (regardless of whether a value was
   *     actually set from it -- an unparsable/blank value under a known field is still "consumed"
   *     for reporting purposes, same as {@link MicroinvestD1FieldMapping#apply})
   */
  static boolean apply(BgD6Declaration declaration, String fieldName, List<String> sections) {
    if (fieldName == null) {
      return false;
    }
    String name = fieldName.trim();
    if (PAYMENT_DATE_FIELD.equals(name)) {
      String raw = firstNonBlank(sections);
      if (raw != null) {
        try {
          declaration.setT18PaymentDate(LocalDate.parse(raw.trim(), DATE_FORMAT));
        } catch (Exception e) {
          // Left unset -- an unparsable historical date is not guessed at.
        }
      }
      return true;
    }
    BiConsumer<BgD6Declaration, String> stringSetter = STRING_SETTERS.get(name);
    if (stringSetter != null) {
      String raw = firstNonBlank(sections);
      if (raw != null) {
        stringSetter.accept(declaration, raw.trim());
      }
      return true;
    }
    BiConsumer<BgD6Declaration, BigDecimal> decimalSetter = DECIMAL_SETTERS.get(name);
    if (decimalSetter != null) {
      BigDecimal value = firstNonZero(sections);
      if (value != null) {
        decimalSetter.accept(declaration, value);
      }
      return true;
    }
    return false;
  }

  private static String firstNonBlank(List<String> sections) {
    for (String s : sections) {
      if (s != null && !s.isBlank()) {
        return s;
      }
    }
    return null;
  }

  private static BigDecimal firstNonZero(List<String> sections) {
    for (String s : sections) {
      if (s == null || s.isBlank()) {
        continue;
      }
      try {
        BigDecimal value = new BigDecimal(s.replace(',', '.').trim());
        if (value.signum() != 0) {
          return value;
        }
      } catch (NumberFormatException e) {
        // Not a number -- ignore this section for a decimal field.
      }
    }
    return null;
  }
}
