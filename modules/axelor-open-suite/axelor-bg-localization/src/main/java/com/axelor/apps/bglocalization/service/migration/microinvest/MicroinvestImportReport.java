package com.axelor.apps.bglocalization.service.migration.microinvest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what happened (or would happen, for a preflight) during one run of the Microinvest pilot
 * importer against one company. Same shape for preflight (no writes) and import (real writes) so
 * the two show up the same way in the wizard's report field.
 */
public final class MicroinvestImportReport {

  public int employeesFound;
  public int created;
  public int reused;
  public int skipped;
  public int failed;

  /** Заварен запис, чието поле този пробег попълни (вж. {@link #updated(String, String)}). */
  public int updated;

  /** Заварен запис, който вече е такъв, какъвто изворът го иска — нула записи в базата. */
  public int unchanged;

  /** Ръчна стойност, различна от изворовата — запазена, не презаписана. */
  public int conflict;

  /**
   * The active company this run targets, named at the very top of both reports -- the operator must
   * be able to see WHERE the data went (or would go) without reading a single detail line. Set
   * once, before anything else runs; see {@link MicroinvestCompanyGuard}.
   */
  private String targetCompanyLine;

  /**
   * Records the export file CONTAINS for the record kinds this importer handles, counted
   * independently of whether any of them can be imported. Separated from {@link #created}/{@link
   * #reused} so the report can tell "nothing to do" from "everything blocked": otherwise a summary
   * of created 0 / failed 0 hides rows that silently fell out one at a time.
   */
  public int sourceRecords;

  /**
   * Records that are BLOCKED, not intentionally skipped -- a missing prerequisite or unresolvable
   * reference, i.e. something the operator is expected to act on. Kept apart from {@link #skipped},
   * which now means only deliberate exclusions (soft-deleted source rows, values this migration
   * refuses to guess at).
   */
  public int blocked;

  // ── PAYROLL calendar prerequisite (Year.typeSelect = PAYROLL, never fiscal) ──
  public int payrollMonthsRequired;
  public int payrollMonthsMissing;
  public int payrollYearsToCreate;

  /** Distinct EGNs among {@link #employeesFound} employees; see {@link #duplicateEgnMerges}. */
  public int uniqueEgnCount;

  /** Employees sharing an EGN with an earlier one in the same export (merged, not duplicated). */
  public int duplicateEgnMerges;

  // ── Payroll (§1: deleted-payroll protection) ──
  public int payrollActive;
  public int payrollDeletedExcluded;

  /**
   * Chains where a deleted root has one or more active (non-deleted) annex rows referencing it --
   * fail-closed: the whole chain is skipped, nothing promoted, nothing orphaned. Counted separately
   * from {@link #payrollDeletedExcluded} so it is never mistaken for an ordinary, unremarkable
   * exclusion -- this one needs a human to look at the source data.
   */
  public int payrollChainsFailClosed;

  // ── Contracts ──
  public int contractRootsCreated;
  public int contractRootsReused;
  public int amendmentsCreated;
  public int amendmentsReused;
  public int amendmentsSkippedUnmappedGround;

  // ── Contract payroll fields (вид осигурен / основна заплата / НКПД) ──
  //
  // ⚠️ ЧЕТИРИ БРОЯЧА, А НЕ ДВА, И ТОВА Е РАЗЛИКАТА, КОЯТО ОПЕРАТОРЪТ ТЪРСИ. „Обновен“ и
  // „непроменен“ заедно значат „наред е“; „пропуснат“ значи „изворът мълчи, попълни го на ръка“;
  // „отказ“ значи „изворът си противоречи, иска решение“. Слети, вторият пробег на същия износ
  // щеше да изглежда като нулев резултат — а той е ДОКАЗАТЕЛСТВОТО за идемпотентност.
  public int contractFieldsUpdated;
  public int contractFieldsUnchanged;
  public int contractFieldsSkipped;
  public int contractFieldsFailed;

  /**
   * Заварена РЪЧНА стойност, различна от изворовата — запазена, не презаписана. Отделен брояч от
   * {@link #contractFieldsSkipped}, защото двете искат различно действие: пропуснатото значи
   * „изворът мълчи, попълни го“, а конфликтът значи „и двете страни твърдят нещо, реши кое е
   * вярното“.
   */
  public int contractFieldsConflict;

  // ── Край на договора, решен по цялата анексна верига ──
  //
  // ⚠️ СОБСТВЕНИ БРОЯЧИ, ЗАЩОТО ТОВА Е ДАТА, А НЕ ПОЛЕ ОТ ФИША. Краят на правоотношението идва от
  // ТЕРМИНАЛНИЯ запис на веригата, не от корена ѝ (виж {@link MicroinvestPayrollChainResolver}), и
  // поправката му е UPDATE върху заварен договор. Операторът трябва да види предложената промяна в
  // Preflight ПРЕДИ да пусне истинския внос — затова има и отделен ред в доклада, не само брояч.
  public int contractEndDatesUpdated;
  public int contractEndDatesUnchanged;

  /** Веригата не е еднозначна — заварената (вероятно ръчна) дата се ЗАПАЗВА. */
  public int contractEndDatesRefused;

  // ── Addresses (§2) ──
  public enum AddressOutcome {
    IMPORTED,
    EMPTY,
    UNMATCHED,
    AMBIGUOUS
  }

  private final Map<AddressOutcome, Integer> addressOutcomes = new EnumMap<>(AddressOutcome.class);

  void addressOutcome(AddressOutcome outcome) {
    addressOutcomes.merge(outcome, 1, Integer::sum);
  }

  // ── Bank accounts ──
  public int bankAccountsImported;
  public int bankAccountsMissingBic;

  // ── Leave ──
  public int sickLeaveCount;
  public int paidLeaveCount;
  public int unpaidLeaveCount;

  // ── Salary (Phase 2) ──
  public int salaryRowsExported;
  public int salaryRowsMapped;
  public int salaryRowsUnmapped;
  private final java.math.BigDecimal[] mappedValueTotalHolder = {java.math.BigDecimal.ZERO};
  private final java.math.BigDecimal[] unmappedValueTotalHolder = {java.math.BigDecimal.ZERO};

  void mappedComponent(java.math.BigDecimal value) {
    salaryRowsMapped++;
    if (value != null) {
      mappedValueTotalHolder[0] = mappedValueTotalHolder[0].add(value.abs());
    }
  }

  public java.math.BigDecimal getMappedValueTotal() {
    return mappedValueTotalHolder[0];
  }

  public java.math.BigDecimal getUnmappedValueTotal() {
    return unmappedValueTotalHolder[0];
  }

  // ── D1/D6 exported-vs-consumed fields ──
  public int d1FieldsExported;
  public int d1FieldsConsumed;
  public int d6FieldsExported;
  public int d6FieldsConsumed;

  private final List<String> lines = new ArrayList<>();

  /**
   * Phase 2: {@code FormulaSalary} components encountered with a nonzero value that are NOT in
   * {@code microinvest/formula-line-mapping.csv} -- counted, not listed one by one, so a component
   * used in every month of every employee's history does not drown the report. Keyed by "{category}
   * -- {name}" so the summary groups by the heuristic category (see {@link
   * MicroinvestFormulaClassification#categorize}).
   */
  private final Map<String, Integer> unmappedComponentCounts = new LinkedHashMap<>();

  void unmappedComponent(String name, String category, java.math.BigDecimal value) {
    salaryRowsUnmapped++;
    if (value != null) {
      unmappedValueTotalHolder[0] = unmappedValueTotalHolder[0].add(value.abs());
    }
    unmappedComponentCounts.merge(category + " -- " + name, 1, Integer::sum);
  }

  void created(String what) {
    created++;
    lines.add("CREATED  " + what);
  }

  void reused(String what) {
    reused++;
    lines.add("REUSED   " + what + " (already present, not re-created)");
  }

  void skipped(String what, String reason) {
    skipped++;
    lines.add("SKIPPED  " + what + " -- " + reason);
  }

  /** Заварен запис, чието поле изворът попълни. Не създава нищо — виж {@link #created}. */
  void updated(String what, String detail) {
    updated++;
    lines.add("UPDATED  " + what + " -- " + detail);
  }

  /** Заварен запис, който вече носи точно тази стойност: нито един SQL не тръгва. */
  void unchanged(String what, String detail) {
    unchanged++;
    lines.add("UNCHANGED " + what + " -- " + detail);
  }

  /** Ръчна стойност срещу изворова — запазва се ръчната и се иска решение. */
  void conflict(String what, String detail) {
    conflict++;
    lines.add("CONFLICT " + what + " -- " + detail);
  }

  /**
   * A record that COULD have been imported but was stopped by a prerequisite -- see {@link
   * #blocked}.
   */
  void blocked(String what, String reason) {
    blocked++;
    lines.add("BLOCKED  " + what + " -- " + reason);
  }

  void targetCompany(String line) {
    targetCompanyLine = line;
  }

  public String getTargetCompanyLine() {
    return targetCompanyLine;
  }

  void failed(String what, String reason) {
    failed++;
    lines.add("FAILED   " + what + " -- " + reason);
  }

  void note(String text) {
    lines.add(text);
  }

  public String toText(String heading) {
    StringBuilder sb = new StringBuilder();
    sb.append(heading).append('\n');
    if (targetCompanyLine != null) {
      sb.append("ЦЕЛЕВА КОМПАНИЯ: ").append(targetCompanyLine).append("\n");
    }
    sb.append(
            String.format(
                "employees found: %d (%d unique EGN, %d duplicate-EGN merges)",
                employeesFound, uniqueEgnCount, duplicateEgnMerges))
        .append("\n");
    sb.append(
            String.format(
                "source records: %d | importable: %d | created: %d | reused: %d | updated: %d |"
                    + " unchanged: %d | skipped (intentional): %d | blocked: %d | failed: %d",
                sourceRecords,
                created + reused,
                created,
                reused,
                updated,
                unchanged,
                skipped,
                blocked,
                failed))
        .append("\n");
    sb.append(
            String.format(
                "contract payroll fields (вид осигурен / основна заплата / НКПД): %d updated | %d"
                    + " unchanged | %d skipped | %d conflict | %d failed",
                contractFieldsUpdated,
                contractFieldsUnchanged,
                contractFieldsSkipped,
                contractFieldsConflict,
                contractFieldsFailed))
        .append("\n");
    sb.append(
            String.format(
                "contract end date (терминален запис на анексната верига): %d updated | %d"
                    + " unchanged | %d refused (веригата не е еднозначна — заварената дата се"
                    + " запазва)",
                contractEndDatesUpdated, contractEndDatesUnchanged, contractEndDatesRefused))
        .append("\n");
    sb.append(
            String.format(
                "payroll calendar (Year.typeSelect=PAYROLL): %d months required | %d missing | %d"
                    + " years to create/created",
                payrollMonthsRequired, payrollMonthsMissing, payrollYearsToCreate))
        .append("\n");
    sb.append(
            String.format(
                "payroll: %d active | %d excluded (IsDeleted=1) | %d chains fail-closed (deleted"
                    + " root, active descendant -- needs manual review)",
                payrollActive, payrollDeletedExcluded, payrollChainsFailClosed))
        .append("\n");
    sb.append(
            String.format(
                "contracts: %d roots created | %d reused | %d amendments created | %d reused | %d"
                    + " amendments skipped (unmapped change-ground)",
                contractRootsCreated,
                contractRootsReused,
                amendmentsCreated,
                amendmentsReused,
                amendmentsSkippedUnmappedGround))
        .append("\n");
    sb.append(
            String.format(
                "addresses: %d imported | %d empty | %d unmatched | %d ambiguous",
                addressOutcomes.getOrDefault(AddressOutcome.IMPORTED, 0),
                addressOutcomes.getOrDefault(AddressOutcome.EMPTY, 0),
                addressOutcomes.getOrDefault(AddressOutcome.UNMATCHED, 0),
                addressOutcomes.getOrDefault(AddressOutcome.AMBIGUOUS, 0)))
        .append("\n");
    sb.append(
            String.format(
                "bank accounts: %d imported | %d missing BIC",
                bankAccountsImported, bankAccountsMissingBic))
        .append("\n");
    sb.append(
            String.format(
                "leave: %d sick | %d paid | %d unpaid",
                sickLeaveCount, paidLeaveCount, unpaidLeaveCount))
        .append("\n");
    sb.append(
            String.format(
                "salary (Phase 2): %d exported | %d mapped (total %s) | %d unmapped (total %s)"
                    + " | %d distinct unmapped names",
                salaryRowsExported,
                salaryRowsMapped,
                getMappedValueTotal(),
                salaryRowsUnmapped,
                getUnmappedValueTotal(),
                unmappedComponentCounts.size()))
        .append("\n");
    sb.append(
            String.format(
                "D1: %d fields exported | %d consumed || D6: %d fields exported | %d consumed",
                d1FieldsExported, d1FieldsConsumed, d6FieldsExported, d6FieldsConsumed))
        .append("\n\n");
    for (String line : lines) {
      sb.append(line).append('\n');
    }
    if (!unmappedComponentCounts.isEmpty()) {
      sb.append(
          "\n--- Unmapped FormulaSalary components (nonzero value, not in"
              + " formula-line-mapping.csv; NOT imported) ---\n");
      unmappedComponentCounts.entrySet().stream()
          .sorted((a, b) -> b.getValue() - a.getValue())
          .forEach(
              e ->
                  sb.append("  ")
                      .append(e.getValue())
                      .append("x  ")
                      .append(e.getKey())
                      .append('\n'));
    }
    return sb.toString();
  }
}
