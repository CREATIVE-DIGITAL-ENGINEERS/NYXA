package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgMinIncomeOverride;
import com.axelor.apps.bglocalization.db.BgMinInsuranceIncome;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.repo.BgInsuredTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgMinIncomeOverrideRepository;
import com.axelor.apps.bglocalization.db.repo.BgMinInsuranceIncomeRepository;
import com.axelor.apps.bglocalization.db.repo.BgNkidRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Database layer for the minimum insurance income (МОД) lookup.
 *
 * <p>Fetches the temporal reference data — insured type, economic activity, Appendix 1 rows and
 * their amounts, and position-level overrides — and delegates every decision to the pure {@link
 * BgMinIncomeResolver}. It is a POJO with no injection; repositories are obtained through {@link
 * Beans}, matching the style of {@code BgEgnValidationService}.
 *
 * <p>The service is a lookup, not a policy. It never throws {@code AxelorException}: whether a
 * blocking status stops payroll or falls back to another floor is the caller's decision. The result
 * carries both the Appendix 1 row (Declaration 1 field 12.3) and the qualification group (field
 * 12.1) so the caller does not have to repeat the lookup downstream.
 */
public class BgMinInsuranceIncomeService {

  /**
   * Resolves the minimum insurance income, then substitutes a manual employee-level override.
   *
   * <p>{@code Employee.bgMinIncomeOverride} is an escape hatch for positions the Appendix 1 table
   * does not cover. It replaces <b>only the amount</b> — the base lookup still runs first and its
   * result stands for everything else. The Appendix 1 row (Declaration 1 field 12.3) belongs to the
   * employer's economic activity and the qualification group (field 12.1) comes from the НКПД;
   * neither changes because an amount was typed on one employee, so both are carried over from the
   * base resolution unchanged.
   *
   * <p>A manual amount does not bypass a blocking base resolution: an ambiguous or unmapped НКИД, a
   * missing НКПД, or a not-applicable insured type all still stop or pass through as-is, because
   * they concern the row and group, not the amount. There is no minimum-wage validation here — МОД
   * is prorated for part-time work and the payroll formula {@code max(salary, МОД, minimum wage)}
   * already enforces the floor.
   *
   * @param employeeOverride the manual override amount ({@code Employee.bgMinIncomeOverride}); a
   *     null or zero value means "unset" and the base result is returned untouched. The generated
   *     decimal getter returns {@code BigDecimal.ZERO} for a null column, so the check is {@code
   *     signum}, not {@code != null} (see axelor-decimal-getter-zero).
   * @param nkidCode the company's economic activity code (НКИД), may be null or blank
   * @param companyOverrideRow the company's explicit Appendix 1 row for an ambiguous НКИД, may be
   *     null or blank
   * @param nkpdCode the position code (НКПД) from the employment contract
   * @param insuredTypeCode the NAP insured-type code
   * @param date the period for which the temporal rows are selected
   * @return a resolved amount, a not-applicable marker, or a blocking failure — never null
   */
  public BgMinIncomeResult findMinInsuranceIncome(
      BigDecimal employeeOverride,
      String nkidCode,
      String companyOverrideRow,
      String nkpdCode,
      String insuredTypeCode,
      LocalDate date) {
    BgMinIncomeResult base =
        findMinInsuranceIncome(nkidCode, companyOverrideRow, nkpdCode, insuredTypeCode, date);
    if (base.getStatus() != BgMinIncomeResult.Status.RESOLVED) {
      // A manual amount cannot paper over a blocking or not-applicable resolution: field 12.3 (row)
      // and 12.1 (group) are independent of it.
      return base;
    }
    if (employeeOverride != null && employeeOverride.signum() > 0) {
      return BgMinIncomeResult.resolved(
          employeeOverride, base.getRowCode(), base.getQualificationGroup());
    }
    return base;
  }

  /**
   * Resolves the minimum insurance income for one employment situation at a given date.
   *
   * @param nkidCode the company's economic activity code (НКИД), may be null or blank
   * @param companyOverrideRow the company's explicit Appendix 1 row for an ambiguous НКИД, may be
   *     null or blank; when set it wins and the НКИД is not consulted
   * @param nkpdCode the position code (НКПД) from the employment contract
   * @param insuredTypeCode the NAP insured-type code
   * @param date the period for which the temporal rows are selected
   * @return a resolved amount, a not-applicable marker, or a blocking failure — never null
   */
  public BgMinIncomeResult findMinInsuranceIncome(
      String nkidCode,
      String companyOverrideRow,
      String nkpdCode,
      String insuredTypeCode,
      LocalDate date) {

    // 1. Insured type for the period.
    BgInsuredType insuredType =
        Beans.get(BgInsuredTypeRepository.class)
            .all()
            .filter(
                "self.code = :c AND self.validFrom <= :d "
                    + "AND (self.validTo IS NULL OR self.validTo >= :d)")
            .bind("c", insuredTypeCode)
            .bind("d", date)
            .fetchOne();
    if (insuredType == null) {
      return BgMinIncomeResult.failure(
          BgMinIncomeResult.Status.INSURED_TYPE_NOT_FOUND, "вид осигурен: " + insuredTypeCode);
    }

    // 2. МОД may simply not apply to this insured type (e.g. codes 05, 12, 14, 24). Not an error —
    // the caller uses its own floor (minimum wage or self-insured minimum).
    if (!Boolean.TRUE.equals(insuredType.getBgAppliesMod())) {
      return BgMinIncomeResult.notApplicable("вид осигурен: " + insuredTypeCode);
    }

    // 3. Qualification group from the НКПД code.
    BgMinIncomeResult groupResult = BgMinIncomeResolver.qualificationGroup(nkpdCode);
    if (groupResult.isBlocking()) {
      return groupResult;
    }
    int group = groupResult.getQualificationGroup();

    // 4. Resolve the Appendix 1 row. A company override wins and short-circuits the НКИД lookup.
    // Without an override, a missing НКИД code is NKID_MISSING (fixable in the company card),
    // distinct from a code that is present but not in the nomenclature (NKID_NOT_FOUND).
    BgNkid nkid = null;
    boolean hasOverride = companyOverrideRow != null && !companyOverrideRow.trim().isEmpty();
    if (!hasOverride) {
      if (nkidCode == null || nkidCode.trim().isEmpty()) {
        return BgMinIncomeResult.failure(
            BgMinIncomeResult.Status.NKID_MISSING, "дружеството няма зададен НКИД");
      }
      // BgNkid is not temporal — no validFrom filter.
      nkid =
          Beans.get(BgNkidRepository.class)
              .all()
              .filter("self.code = :c")
              .bind("c", nkidCode.trim())
              .fetchOne();
    }
    BgMinIncomeResult rowResult = BgMinIncomeResolver.rowCodeFor(companyOverrideRow, nkid);
    if (rowResult.isBlocking()) {
      return rowResult;
    }
    String rowCode = rowResult.getRowCode();

    // 5. Position-level override, keyed on the НКИД code (not temporal).
    List<BgMinIncomeOverride> overrides = Collections.emptyList();
    if (nkidCode != null && !nkidCode.trim().isEmpty()) {
      overrides =
          Beans.get(BgMinIncomeOverrideRepository.class)
              .all()
              .filter("self.bgNkidCode = :n")
              .bind("n", nkidCode.trim())
              .fetch();
    }
    rowCode = BgMinIncomeResolver.applyPositionOverride(overrides, nkpdCode, rowCode);

    // 6. Appendix 1 row for the period.
    BgMinInsuranceIncome row =
        Beans.get(BgMinInsuranceIncomeRepository.class)
            .all()
            .filter(
                "self.bgRow = :r AND self.validFrom <= :d "
                    + "AND (self.validTo IS NULL OR self.validTo >= :d)")
            .bind("r", rowCode)
            .bind("d", date)
            .fetchOne();

    // 7. Amount for the qualification group.
    return BgMinIncomeResolver.amountFor(row, group);
  }

  /**
   * Builds a human-readable Bulgarian message for a lookup result.
   *
   * <p>Intended for surfacing blocking statuses to the user. Follows the module convention of
   * inlining Cyrillic in {@link I18n#get(String)}. The result's detail is appended where it adds
   * context (which code or row triggered the block).
   *
   * @param result a lookup result, never null
   * @return a localized message
   */
  public String describe(BgMinIncomeResult result) {
    String detail = result.getDetail();
    String suffix = (detail != null && !detail.isEmpty()) ? " (" + detail + ")" : "";
    switch (result.getStatus()) {
      case RESOLVED:
        return I18n.get("Минималният осигурителен доход е определен.");
      case NOT_APPLICABLE:
        return I18n.get("Минималният осигурителен доход не се прилага за този вид осигурен.")
            + suffix;
      case NKID_MISSING:
        return I18n.get("На дружеството не е зададен код по НКИД.");
      case NKID_NOT_FOUND:
        return I18n.get("Код по НКИД не е намерен в номенклатурата.") + suffix;
      case NO_MOD_ROW_FOR_NKID:
        return I18n.get(
                "За тази икономическа дейност няма определен ред по Приложение №1 към ЗБДОО.")
            + suffix;
      case AMBIGUOUS_REQUIRES_OVERRIDE:
        return I18n.get(
                "Икономическата дейност съответства на повече от един ред по Приложение №1."
                    + " Задайте изрично ред в картона на дружеството.")
            + suffix;
      case NKPD_MISSING:
        return I18n.get("Не е посочен код по НКПД в трудовия договор.");
      case NKPD_INVALID:
        return I18n.get("Кодът по НКПД е с невалиден формат.") + suffix;
      case QUALIFICATION_GROUP_ZERO:
        return I18n.get("НКПД клас 0 (въоръжени сили) няма определен минимален осигурителен доход.")
            + suffix;
      case INSURED_TYPE_NOT_FOUND:
        return I18n.get("Видът осигурен не е намерен за този период.") + suffix;
      case MOD_ROW_NOT_FOUND:
        return I18n.get("Редът по Приложение №1 не е намерен за този период.") + suffix;
      case AMOUNT_NOT_DEFINED:
        return I18n.get(
                "Редът по Приложение №1 няма стойност за тази квалификационна група."
                    + " Проверете таблицата МОД override по длъжност.")
            + suffix;
      default:
        return I18n.get("Неизвестен статус при определяне на МОД.") + suffix;
    }
  }
}
