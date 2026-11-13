package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report shown on the accounting import wizard.
 *
 * <p>Same construction as the payroll {@code MicroinvestImportReport}: public counters read
 * directly by the controller, one list of fixed-prefix lines, and aggregate maps so a repeated
 * problem is summarised rather than printed thousands of times.
 *
 * <p>⚠️ The report object lives OUTSIDE the import transaction, so everything recorded here
 * survives a rollback and the operator still sees why the year was refused.
 */
public final class MicroinvestAccountingImportReport {

  public int entriesFound;
  public int sourceRecords;
  public int created;
  public int reused;
  public int reconciled;
  public int unresolved;
  public int skipped;
  public int blocked;
  public int failed;

  /** Заявеният от счетоводителя последен ДДС период, подаден от Microinvest (ГГГГММ). */
  public String lastFiledVatPeriod;

  /** Включващата гранична дата — последният ден на {@link #lastFiledVatPeriod}. */
  public LocalDate vatCutoffDate;

  /** Проводки след границата: НЕ са внесени и не са оставили нито един запис. */
  public int skippedAfterCutoff;

  /** Активи с дата на придобиване след границата: също не са внесени. */
  public int skippedAssetsAfterCutoff;

  public int partnersCreated;
  public int partnersReused;
  public int accountsMatched;
  public int movesAccounted;
  public int openingMoveLines;

  private String targetCompanyLine;
  private String sourceLine;

  private final List<String> lines = new ArrayList<>();
  private final Map<String, Integer> aggregated = new LinkedHashMap<>();

  public void targetCompany(String line) {
    this.targetCompanyLine = line;
  }

  public String getTargetCompanyLine() {
    return targetCompanyLine;
  }

  public void source(String line) {
    this.sourceLine = line;
  }

  public String getSourceLine() {
    return sourceLine;
  }

  void created(String what) {
    created++;
    lines.add("CREATED   " + what);
  }

  void reused(String what) {
    reused++;
    lines.add("REUSED    " + what + " (вече наличен, не е създаван наново)");
  }

  void reconciled(String what) {
    reconciled++;
    lines.add("RECONCILED " + what);
  }

  void unresolved(String what, String reason) {
    unresolved++;
    lines.add("UNRESOLVED " + what + " -- " + reason);
  }

  void skipped(String what, String reason) {
    skipped++;
    lines.add("SKIPPED   " + what + " -- " + reason);
  }

  void blocked(String what, String reason) {
    blocked++;
    lines.add("BLOCKED   " + what + " -- " + reason);
  }

  void failed(String what, String reason) {
    failed++;
    lines.add("FAILED    " + what + " -- " + reason);
  }

  void note(String text) {
    lines.add(text);
  }

  /** Counts a repeated observation instead of adding one line per occurrence. */
  void aggregate(String category, String detail) {
    aggregated.merge(category + " -- " + detail, 1, Integer::sum);
  }

  public boolean hasBlockers() {
    return blocked > 0;
  }

  public String toText(String heading) {
    StringBuilder sb = new StringBuilder();
    sb.append(heading).append('\n');
    if (targetCompanyLine != null) {
      sb.append("ЦЕЛЕВА КОМПАНИЯ: ").append(targetCompanyLine).append('\n');
    }
    if (sourceLine != null) {
      sb.append("ИЗТОЧНИК: ").append(sourceLine).append('\n');
    }
    if (lastFiledVatPeriod != null && !lastFiledVatPeriod.trim().isEmpty()) {
      sb.append("ПОСЛЕДЕН ПОДАДЕН ОТ Microinvest ДДС ПЕРИОД: ")
          .append(MicroinvestVatPeriodCutoff.describe(lastFiledVatPeriod, vatCutoffDate))
          .append('\n');
    }
    sb.append(String.format("Проводки в файла: %d | записи: %d%n", entriesFound, sourceRecords));
    if (skippedAfterCutoff > 0 || skippedAssetsAfterCutoff > 0) {
      sb.append(
          String.format(
              "СЛЕД ГРАНИЦАТА (НЕ са внесени): проводки %d | активи %d — въведете тези документи в"
                  + " Nyxa като обикновени фактури, за да влязат в ДДС дневниците.%n",
              skippedAfterCutoff, skippedAssetsAfterCutoff));
    }
    sb.append(
        String.format(
            "Създадени: %d | преизползвани: %d | равнения: %d | неразрешени равнения: %d%n",
            created, reused, reconciled, unresolved));
    sb.append(
        String.format(
            "Пропуснати: %d | блокирани: %d | провалени: %d%n", skipped, blocked, failed));
    sb.append(
        String.format(
            "Партньори: създадени %d, преизползвани %d | сметки съпоставени: %d%n",
            partnersCreated, partnersReused, accountsMatched));
    if (movesAccounted > 0 || openingMoveLines > 0) {
      sb.append(
          String.format(
              "Осчетоводени записи: %d | редове в началното салдо: %d%n",
              movesAccounted, openingMoveLines));
    }
    sb.append('\n');
    for (String line : lines) {
      sb.append(line).append('\n');
    }
    if (!aggregated.isEmpty()) {
      sb.append('\n').append("Обобщени наблюдения:").append('\n');
      aggregated.entrySet().stream()
          .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
          .forEach(
              e ->
                  sb.append("  ")
                      .append(e.getValue())
                      .append(" x ")
                      .append(e.getKey())
                      .append('\n'));
    }
    return sb.toString();
  }
}
