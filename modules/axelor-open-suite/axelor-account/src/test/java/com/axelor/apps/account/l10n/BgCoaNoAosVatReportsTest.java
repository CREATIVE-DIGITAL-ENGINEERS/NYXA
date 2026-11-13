package com.axelor.apps.account.l10n;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ШАБЛОНЪТ BG_COA НЕ НОСИ ДДС СПРАВКИТЕ НА AOS.
 *
 * <p>Справките 41–44 („ДДС Дневник за покупките (НАП)“, „ДДС Дневник за продажбите (НАП)“, „ДДС
 * Справка-декларация (НАП)“, „ВИЕС декларация (НАП)“) бяха персонализирани справки от вид 3000
 * върху салдата на сметките. Дневниците, справката-декларация и VIES се правят от модула за ДДС по
 * документите, не по сметки — две места с едно име дават две различни числа. Махнати са само от
 * шаблона, тоест за нови дружества; заварените бази не се мигрират.
 *
 * <p>С тях са махнати и шестте реда „Стойност“ без справка (81, 95, 1, 14, 116, 147) — колоните на
 * същия блок, които никога не бяха закачени към отчет.
 */
class BgCoaNoAosVatReportsTest {

  /** От папката на модула (Gradle пуска тестовете от нея), не от classpath-а. */
  private static final Path COA = Paths.get("src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path REPORT_TYPES = COA.resolve("account_accountingReportType.csv");

  private static final Path REPORT_LINES = COA.resolve("account_accountingReportConfigLine.csv");

  /**
   * Персонализирана справка (typeSelect 3000). Стандартните „Справка ДДС по фактури“ (12) и „по
   * получени плащания“ (15) на AOS остават — те четат документите, не салдата.
   */
  private static final String CUSTOM = "3000";

  private static final Set<String> REMOVED =
      new LinkedHashSet<>(Arrays.asList("41", "42", "43", "44"));

  @Test
  @DisplayName("в BG_COA няма справка 41–44 и няма персонализирана справка за ДДС или ВИЕС")
  void няма_ддс_справки() {
    List<String> found = new ArrayList<>();
    for (String[] row : rows(REPORT_TYPES)) {
      String id = row[0];
      String name = row[1];
      boolean custom = CUSTOM.equals(row[2]);
      if (REMOVED.contains(id) || (custom && (name.contains("ДДС") || name.contains("ВИЕС")))) {
        found.add(id + " „" + name + "“");
      }
    }
    assertTrue(
        found.isEmpty(), "ДДС справките на AOS пак са в шаблона (" + REPORT_TYPES + "): " + found);
  }

  @Test
  @DisplayName("всеки ред на справка сочи справка, която шаблонът носи; ред без справка няма")
  void няма_висящи_редове() {
    Set<String> types = new LinkedHashSet<>();
    for (String[] row : rows(REPORT_TYPES)) {
      types.add(row[0]);
    }
    List<String> dangling = new ArrayList<>();
    for (String[] row : rows(REPORT_LINES)) {
      String id = row[0];
      String reportType = row[1];
      String column = row[2];
      if (reportType.isEmpty() && column.isEmpty()) {
        dangling.add(id + " (без справка и без колона)");
      } else if (!reportType.isEmpty() && !types.contains(reportType)) {
        dangling.add(id + " → справка " + reportType);
      } else if (!column.isEmpty() && !types.contains(column)) {
        dangling.add(id + " → колона на справка " + column);
      }
    }
    assertTrue(
        dangling.isEmpty(), "редове в " + REPORT_LINES + " без справка в шаблона: " + dangling);
  }

  private static List<String[]> rows(Path file) {
    assertTrue(Files.isRegularFile(file), "не намирам " + file.toAbsolutePath());
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError(file + " не се чете: " + e.getMessage(), e);
    }
    List<String[]> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      if (!lines.get(i).isEmpty()) {
        out.add(split(lines.get(i)));
      }
    }
    assertTrue(!out.isEmpty(), file + " е празен");
    return out;
  }

  private static String[] split(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == ';' && !inQuotes) {
        out.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    out.add(current.toString());
    return out.toArray(new String[0]);
  }
}
