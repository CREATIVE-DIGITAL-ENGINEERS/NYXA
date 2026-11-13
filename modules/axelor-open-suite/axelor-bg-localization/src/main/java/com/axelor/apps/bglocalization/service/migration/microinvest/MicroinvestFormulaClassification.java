package com.axelor.apps.bglocalization.service.migration.microinvest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies Microinvest {@code FormulaSalary} component names for the Phase 2 historical-payslip
 * import. Two independent things happen here, and they must not be confused:
 *
 * <ul>
 *   <li>{@link #lineTypeCodeFor(String)} is the ONLY thing that decides what gets imported as a
 *       {@code BgPaySlipLine} -- a small, hand-curated allowlist loaded from {@code
 *       microinvest/formula-line-mapping.csv}. Not in the list means not imported, full stop.
 *   <li>{@link #categorize(String)} is a best-effort keyword heuristic used ONLY to make the
 *       Preflight report readable (grouping unmapped components under "looks like a flag" / "looks
 *       like an intermediate calculation" / "looks like a total" / "other"). It never decides what
 *       gets imported -- see {@code migration-plan.md} / {@code field-mapping.md} ("do not invent
 *       mappings when uncertain; report/skip is better").
 * </ul>
 */
final class MicroinvestFormulaClassification {

  private static final String MAPPING_RESOURCE = "/microinvest/formula-line-mapping.csv";

  private static final Map<String, String> LINE_TYPE_CODE_BY_NAME = load();

  // Heuristic-only keyword lists, in priority order. Purely cosmetic for the report -- see class
  // javadoc. ⚠️ (?iu), NOT (?i): plain CASE_INSENSITIVE in Java only folds US-ASCII case: without
  // UNICODE_CASE too, "Общо" (capital Cyrillic О) would not match a lowercase "общо" literal --
  // confirmed the hard way, this exact gap failed MicroinvestFormulaClassificationTest once.
  private static final int CASE_INSENSITIVE_UNICODE =
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
  private static final Pattern TOTAL_PATTERN =
      Pattern.compile(
          "(общо|тотал|за получаване|удръжки общо|удръжки тотал|нетно възнаграждение)",
          CASE_INSENSITIVE_UNICODE);
  private static final Pattern INTERMEDIATE_PATTERN =
      Pattern.compile(
          "(осигурителен доход|данъчна основа|ограничен|база\\b|изравнителна|ставка|"
              + "процент|коефициент|дневна|часова)",
          CASE_INSENSITIVE_UNICODE);
  private static final Pattern STAT_PATTERN =
      Pattern.compile(
          "(статистика|УП2|УП3|СИРВ|списъчен състав|календарни дни)", CASE_INSENSITIVE_UNICODE);
  private static final Pattern FLAG_PATTERN =
      Pattern.compile(
          "^(Учител|Морско лице|Служебно правоотношение|Пенсионер|Съдружник|Земеделски"
              + " производител|Балерина|Ученик в дуална|Лице (от женски пол|с ЛНЧ|без здравно"
              + " осигуряване)|Трудов договор|Граждански договор|Договор за управление и"
              + " контрол|Договор по чл\\.)",
          CASE_INSENSITIVE_UNICODE);

  private MicroinvestFormulaClassification() {}

  /** The confirmed {@code BgPaySlipLineType.code} for this exact formula name, or null. */
  static String lineTypeCodeFor(String formulaName) {
    return formulaName == null ? null : LINE_TYPE_CODE_BY_NAME.get(formulaName.trim());
  }

  /**
   * Best-effort label for the Preflight report only -- see class javadoc. Never used to decide
   * whether something is imported.
   */
  static String categorize(String formulaName) {
    if (formulaName == null || formulaName.isBlank()) {
      return "other";
    }
    String name = formulaName.trim();
    if (STAT_PATTERN.matcher(name).find()) {
      return "statistics/report (not a payslip line)";
    }
    if (TOTAL_PATTERN.matcher(name).find()) {
      return "total";
    }
    if (INTERMEDIATE_PATTERN.matcher(name).find()) {
      return "intermediate calculation";
    }
    if (FLAG_PATTERN.matcher(name).find()) {
      return "flag/property";
    }
    return "other";
  }

  private static Map<String, String> load() {
    Map<String, String> map = new HashMap<>();
    try (InputStream in =
        MicroinvestFormulaClassification.class.getResourceAsStream(MAPPING_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource: " + MAPPING_RESOURCE);
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String trimmed = line.trim();
          if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            continue;
          }
          int sep = trimmed.indexOf(';');
          if (sep < 0) {
            continue;
          }
          String name = trimmed.substring(0, sep).trim();
          String code = trimmed.substring(sep + 1).trim();
          if ("name".equals(name) && "code".equals(code)) {
            continue; // header row
          }
          if (!name.isEmpty() && !code.isEmpty()) {
            map.put(name, code);
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read " + MAPPING_RESOURCE, e);
    }
    return Collections.unmodifiableMap(map);
  }
}
