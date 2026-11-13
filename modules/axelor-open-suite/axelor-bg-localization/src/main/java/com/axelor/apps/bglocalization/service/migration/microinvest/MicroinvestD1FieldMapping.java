package com.axelor.apps.bglocalization.service.migration.microinvest;

import com.axelor.apps.bglocalization.db.BgD1Record;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Microinvest {@code Dekl1} field -&gt; {@code BgD1Record} field, for the Phase 2 historical
 * import. Both sides are keyed by the SAME external identifier -- the point numbers of Приложение №
 * 1 to Наредба Н-13 ("т. 21", "т. 22а"...) -- which is why {@code BgD1Record}'s own field names
 * literally spell out the point number (see its domain-XML class comment). This is a recognition of
 * a shared external numbering, not an invented mapping.
 *
 * <p>Matched on Dekl1's {@code FormRefNo} alone (normalized -- see {@link #normalize}), NOT on
 * {@code Dekl1.Name}: the full field-label text is long, free-form Bulgarian legal prose that is
 * fragile to transcribe exactly (line wraps, а/б suffix rendered as Cyrillic or Latin depending on
 * schema version, non-breaking spaces), while {@code FormRefNo} is short and, for the fields listed
 * below, unambiguous against the pilot company's own Dekl1 table.
 *
 * <p>Deliberately NOT mapped, even though a source value exists for some of them:
 *
 * <ul>
 *   <li>т. 1 (код корекция) -- Microinvest's raw value convention for this field was not confirmed
 *       (К/З letters vs. a numeric flag); {@code BgD1Record}'s own domain comment shows this exact
 *       class of letter-vs-number translation trap for т. 5.1.
 *   <li>т. 5.1 (код за ЕГН/ЛНЧ) -- same reason: a confirmed 0/2-vs-Microinvest's-own convention
 *       mismatch, documented on {@code BgD1Record.t51PersonalIdCode} itself.
 *   <li>т. 13 (СИРВ период/пореден месец) -- Microinvest's own Dekl1 table has TWO rows both named
 *       "Позиция 1"/"Позиция 2" sharing {@code FormRefNo} "13", not distinguishable by FormRefNo
 *       alone, and this point only applies to the rare "код 16" insured-type. Skipped rather than
 *       guessed at.
 *   <li>т. 16.1-16.5 and т. 16.А (the day-count sub-buckets) -- Nyxa's own domain comment says the
 *       correct bucket is read from {@code BgAbsenceType.d1Code} (a business rule), not copied
 *       as-is, and the pilot company's own Dekl1 table has an internal FormRefNo collision (two
 *       unrelated rows both claim "16.5"). Only the already-aggregated т. 16 total is imported.
 *   <li>The un-split "22", "22.1", "25", "26" combined-rate rows -- {@code BgD1Record}'s own domain
 *       comment explains why combining the employer+insured shares into one number is wrong; only
 *       the a/b (employer/insured) split rows are used, for the same reason.
 * </ul>
 */
final class MicroinvestD1FieldMapping {

  @FunctionalInterface
  private interface FieldSetter {
    void apply(BgD1Record record, String rawValue);
  }

  private static final Map<String, FieldSetter> SETTERS_BY_FORM_REF_NO = new HashMap<>();

  /**
   * Fallback keyed by {@code Dekl1.Name} instead of {@code FormRefNo}, for the one confirmed case
   * where Microinvest's own Dekl1 catalog leaves {@code FormRefNo} NULL for every row of a field
   * that otherwise has an exact, unambiguous, short name (not the long legal-prose names this class
   * avoids matching on elsewhere -- see class javadoc): "Вид осигурен" (т. 12), whose rows carry
   * FormRefNo NULL and short insured-type codes such as 1/4/12/28 -- exactly т. 12's shape.
   */
  private static final Map<String, FieldSetter> SETTERS_BY_NAME_WHEN_NO_FORM_REF_NO =
      Map.of("Вид осигурен", (BgD1Record r, String v) -> r.setT12InsuredTypeCode(v.trim()));

  static {
    string("2", BgD1Record::setT2Month);
    string("3", BgD1Record::setT3Year);
    string("4", BgD1Record::setT4ObligedPersonCode);
    string("5", BgD1Record::setT5PersonalId);
    string("6", BgD1Record::setT6Surname);
    string("7", BgD1Record::setT7Initials);
    string("12", BgD1Record::setT12InsuredTypeCode);
    string("12.1", BgD1Record::setT121QualificationGroup);
    string("12.2", BgD1Record::setT122ActivityCode);
    string("12.3", BgD1Record::setT123ActivityRow);

    // Five arise/interrupt day pairs (полета 9-18). The plain, unsuffixed "14"/"15" FormRefNo
    // values are an alias of the first pair (/1/) for older records -- both land on the same field.
    string("14", BgD1Record::setT14Day1);
    string("14.1", BgD1Record::setT14Day1);
    string("15", BgD1Record::setT15Day1);
    string("15.1", BgD1Record::setT15Day1);
    string("14.2", BgD1Record::setT14Day2);
    string("15.2", BgD1Record::setT15Day2);
    string("14.3", BgD1Record::setT14Day3);
    string("15.3", BgD1Record::setT15Day3);
    string("14.4", BgD1Record::setT14Day4);
    string("15.4", BgD1Record::setT15Day4);
    string("14.5", BgD1Record::setT14Day5);
    string("15.5", BgD1Record::setT15Day5);

    string("16", BgD1Record::setT16Days);
    string("16.7", BgD1Record::setT167WorkedHours);
    string("16.8", BgD1Record::setT168OvertimeHours);

    decimal("17", BgD1Record::setT17Art4015Income);
    decimal("18", BgD1Record::setT18HealthRateOnT17);
    decimal("21", BgD1Record::setT21InsuranceIncome);
    decimal("22a", BgD1Record::setT22DooRateEmployer);
    decimal("22b", BgD1Record::setT22DooRateInsured);
    decimal("22.1a", BgD1Record::setT221HealthRateEmployer);
    decimal("22.1b", BgD1Record::setT221HealthRateInsured);
    decimal("22.2", BgD1Record::setT222TzpbRate);
    decimal("23", BgD1Record::setT23TeacherPfRate);
    decimal("25a", BgD1Record::setT25PpfRate);
    decimal("26a", BgD1Record::setT26UpfRateEmployer);
    decimal("26b", BgD1Record::setT26UpfRateInsured);
    decimal("27", BgD1Record::setT27HealthOnlyIncome);
    decimal("28", BgD1Record::setT28HealthRateOnT27);
    decimal("29", BgD1Record::setT29GrossWage);
    decimal("30", BgD1Record::setT30GvrsRate);
    decimal("31", BgD1Record::setT31TaxableIncome);
    decimal("31.a", BgD1Record::setT31aTax); // т. 31.А -- Cyrillic/Latin "А" both normalize here
    decimal("34", BgD1Record::setT34NetPay);
  }

  private MicroinvestD1FieldMapping() {}

  /**
   * Applies one Dekl1 field value onto {@code record}, if {@code formRefNo} is a known point number
   * and the raw value is non-blank. Does nothing otherwise -- Д1 is a closed, official form (unlike
   * the open FormulaSalary catalogue), so an unrecognized field is not reported line-by-line the
   * same way an unmapped payslip component is; the caller tallies the return value instead (see
   * {@code MicroinvestImportReport#d1FieldsConsumed}).
   *
   * @return {@code true} if {@code formRefNo} (or, absent that, {@code fieldName} via the small
   *     name-keyed fallback above) is a known point and a value was applied
   */
  static boolean apply(BgD1Record record, String formRefNo, String rawValue) {
    return apply(record, formRefNo, null, rawValue);
  }

  static boolean apply(BgD1Record record, String formRefNo, String fieldName, String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return false;
    }
    FieldSetter setter = SETTERS_BY_FORM_REF_NO.get(normalize(formRefNo));
    if (setter == null && (formRefNo == null || formRefNo.isBlank()) && fieldName != null) {
      setter = SETTERS_BY_NAME_WHEN_NO_FORM_REF_NO.get(fieldName.trim());
    }
    if (setter == null) {
      return false;
    }
    setter.apply(record, rawValue.trim());
    return true;
  }

  /**
   * Lower-cases and folds the handful of Cyrillic suffix letters (а/б/А) Microinvest sometimes uses
   * in a {@code FormRefNo} like "22.1а" onto their Latin lookalikes, so a hand-typed Java literal
   * here matches the source value regardless of which script the source database actually used --
   * see class javadoc.
   */
  private static String normalize(String formRefNo) {
    if (formRefNo == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(formRefNo.trim().length());
    for (int i = 0; i < formRefNo.length(); i++) {
      char c = formRefNo.charAt(i);
      switch (c) {
        case 'а':
        case 'A':
        case 'А':
          sb.append('a');
          break;
        case 'б':
        case 'B':
        case 'Б':
          sb.append('b');
          break;
        default:
          sb.append(Character.toLowerCase(c));
      }
    }
    return sb.toString().trim();
  }

  private static void string(String formRefNo, BiConsumer<BgD1Record, String> setter) {
    SETTERS_BY_FORM_REF_NO.put(
        normalize(formRefNo), (BgD1Record r, String v) -> setter.accept(r, v));
  }

  private static void decimal(String formRefNo, BiConsumer<BgD1Record, BigDecimal> setter) {
    SETTERS_BY_FORM_REF_NO.put(
        normalize(formRefNo),
        (BgD1Record r, String v) -> {
          try {
            setter.accept(r, normalizeToFieldScale(new BigDecimal(v.replace(',', '.').trim())));
          } catch (NumberFormatException e) {
            // Left unset -- a malformed historical value is not guessed at.
          }
        });
  }

  /**
   * Rounds to {@code BgD1Record}'s own domain scale -- EVERY decimal field on {@code BgD1Record} is
   * {@code @Digits(fraction = 2)} (confirmed against its generated class; only the integer part
   * varies, 4 or 18), so one shared scale applies uniformly here, the SAME way {@code
   * MicroinvestPayrollHistoryImporter#normalizeMonetary} does for {@code BgPaySlipLine.bgAmount}.
   * Microinvest's own Dekl1Value rows carry rates like "0.500" (scale 3, a trailing zero, not a
   * genuinely more precise number) which the {@code @Digits(fraction = 2)} constraint rejects
   * outright at persist time -- confirmed via the live source (т.22.2/TZPB rate: only 0.000/0.500/
   * 0.900 ever appear) that this is excess scale, not a real fractional-cent rate; the domain
   * precision is therefore NOT widened. Standard HALF_UP rounding, applied at this single point
   * every Dekl1 decimal value crosses on its way into a {@code BgD1Record} -- never a per-field
   * special case, and this is also what Preflight's own throwaway {@code BgD1Record} tally goes
   * through (see {@code MicroinvestPayrollHistoryImporter#tallySalaryAndDeclarationCoverage}), so a
   * value Preflight reports as mapped is now the SAME value Import would actually persist.
   */
  private static BigDecimal normalizeToFieldScale(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.HALF_UP);
  }
}
