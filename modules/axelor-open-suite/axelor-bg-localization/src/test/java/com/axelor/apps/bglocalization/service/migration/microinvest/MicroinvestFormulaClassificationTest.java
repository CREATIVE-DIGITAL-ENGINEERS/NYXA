package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FormulaSalary -&gt; BgPaySlipLineType allowlist (loaded from {@code
 * microinvest/formula-line-mapping.csv}) and the report-only heuristic categorizer. See {@link
 * MicroinvestFormulaClassification}'s class javadoc for why these are two independent things.
 */
class MicroinvestFormulaClassificationTest {

  @Test
  @DisplayName("a curated formula name resolves to its BgPaySlipLineType code")
  void resolvesMappedLineItem() {
    assertEquals("pl_avans", MicroinvestFormulaClassification.lineTypeCodeFor("Аванс"));
    assertEquals(
        "pl_osnovna",
        MicroinvestFormulaClassification.lineTypeCodeFor("Възнаграждение за отработени дни"));
    assertEquals(
        "pl_zapor_446",
        MicroinvestFormulaClassification.lineTypeCodeFor(
            "Запор върху възнаграждението - ограничен според чл.446 ал.1 от ГПК"));
    // Both names for chl.224 compensation map to the same code -- confirmed via live SQL that
    // the pilot company's Microinvest instance only ever populates the "Сума за ... по" variant,
    // the plain name has zero nonzero rows, so this can never double-count in practice.
    assertEquals(
        "pl_obez_kt_224_1",
        MicroinvestFormulaClassification.lineTypeCodeFor("Обезщетение чл. 224 от КТ"));
    assertEquals(
        "pl_obez_kt_224_1",
        MicroinvestFormulaClassification.lineTypeCodeFor("Сума за Обезщетение по чл. 224 от КТ"));
  }

  @Test
  @DisplayName("an unlisted or unknown formula name resolves to no code")
  void doesNotGuessUnlistedNames() {
    // Both "Основно възнаграждение" and "...по договор" are the constant contractual figure, not
    // this month's payable line -- see formula-line-mapping.csv's own comment on why only
    // "Възнаграждение за отработени дни" (the actual, prorated-when-partial amount) is mapped.
    assertNull(
        MicroinvestFormulaClassification.lineTypeCodeFor("Основно възнаграждение по договор"));
    assertNull(MicroinvestFormulaClassification.lineTypeCodeFor("Основно възнаграждение"));
    assertNull(MicroinvestFormulaClassification.lineTypeCodeFor("Брутна заплата"));
    assertNull(MicroinvestFormulaClassification.lineTypeCodeFor("Нещо си напълно измислено"));
    assertNull(MicroinvestFormulaClassification.lineTypeCodeFor(null));
  }

  @Test
  @DisplayName("the report-only heuristic labels totals, intermediates and flags for readability")
  void categorizesForReportingOnly() {
    assertEquals("total", MicroinvestFormulaClassification.categorize("Общо начисления"));
    assertEquals("total", MicroinvestFormulaClassification.categorize("Сума за получаване"));
    assertEquals(
        "intermediate calculation",
        MicroinvestFormulaClassification.categorize("Осигурителен доход ограничен"));
    assertEquals("flag/property", MicroinvestFormulaClassification.categorize("Учител"));
    assertEquals(
        "statistics/report (not a payslip line)",
        MicroinvestFormulaClassification.categorize("1000.1 - статистика"));
    assertEquals("other", MicroinvestFormulaClassification.categorize("Аванс"));
  }
}
