package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Entry;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Line;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Рязането на обхвата: какво ОСТАВА за импорта след границата и какво казва докладът.
 *
 * <p>Тестът е върху {@code root}, защото точно него — и само него — обхожда импортът. Документ,
 * който не е в {@code root.entries}, не може да породи Move, MoveLine, importId, партньор или
 * актив: за импорта той не съществува. Затова „няма странични ефекти“ се доказва тук, без база.
 *
 * <p>Сценарият: последен подаден от Microinvest период 202608, миграция на 15 септември.
 */
class MicroinvestCutoffScopeTest {

  private static final LocalDate CUTOFF = LocalDate.of(2026, 8, 31);

  private static Entry entry(int sourceId, LocalDate date) {
    Entry e = new Entry();
    e.sourceId = sourceId;
    e.number = "000000" + sourceId;
    e.date = date;
    Line line = new Line();
    line.accountNumber = "601";
    line.debit = new BigDecimal("100.00");
    line.credit = BigDecimal.ZERO;
    e.lines = Arrays.asList(line);
    return e;
  }

  private static JsonNode asset(int sourceId, String acquisitionDate) throws IOException {
    return new ObjectMapper()
        .readTree(
            "{\"sourceId\":"
                + sourceId
                + ",\"accountingDepreciation\":{\"acquisitionDate\":\""
                + acquisitionDate
                + "\"}}");
  }

  private static List<Integer> idsOf(List<Entry> entries) {
    return entries.stream().map(e -> e.sourceId).collect(Collectors.toList());
  }

  @Test
  void entriesAfterCutoffLeaveTheImportScopeEntirely() throws IOException {
    ExportRoot root = new ExportRoot();
    root.entries =
        Arrays.asList(
            entry(1, LocalDate.of(2026, 7, 15)), // подаден период — внася се
            entry(2, LocalDate.of(2026, 8, 31)), // последният ден на границата — внася се
            entry(3, LocalDate.of(2026, 9, 1)), // първият неподаден ден — НЕ се внася
            entry(4, LocalDate.of(2026, 9, 14))); // денят преди миграцията — НЕ се внася
    root.fixedAssets =
        Arrays.asList(asset(10, "2016-03-01"), asset(11, "2026-09-05")); // вторият е след границата

    MicroinvestAccountingImportReport report = new MicroinvestAccountingImportReport();
    new MicroinvestAccountingPreflightService().applyVatPeriodCutoff(root, CUTOFF, report);

    assertEquals(
        Arrays.asList(1, 2),
        idsOf(root.entries),
        "след границата импортът не вижда нито една проводка — няма от какво да създаде запис");
    assertEquals(2, report.skippedAfterCutoff);

    assertEquals(1, root.fixedAssets.size(), "активът, придобит след границата, също отпада");
    assertEquals(10, root.fixedAssets.get(0).path("sourceId").asInt());
    assertEquals(1, report.skippedAssetsAfterCutoff);
  }

  // Активът без дата на придобиване СЕ запазва: незнанието не е основание да се изхвърли история.
  @Test
  void assetWithoutAcquisitionDateIsKept() throws IOException {
    ExportRoot root = new ExportRoot();
    root.entries = Arrays.asList(entry(1, LocalDate.of(2026, 7, 1)));
    root.fixedAssets = Arrays.asList(new ObjectMapper().readTree("{\"sourceId\":12}"));

    MicroinvestAccountingImportReport report = new MicroinvestAccountingImportReport();
    new MicroinvestAccountingPreflightService().applyVatPeriodCutoff(root, CUTOFF, report);

    assertEquals(1, root.fixedAssets.size());
    assertEquals(0, report.skippedAssetsAfterCutoff);
  }

  // Докладът казва границата И колко е останало извън нея, с указание какво да се прави.
  @Test
  void reportShowsTheCutoffAndTheSkippedCount() throws IOException {
    ExportRoot root = new ExportRoot();
    root.entries =
        Arrays.asList(entry(1, LocalDate.of(2026, 8, 20)), entry(2, LocalDate.of(2026, 9, 10)));
    root.fixedAssets = Arrays.asList();

    MicroinvestAccountingImportReport report = new MicroinvestAccountingImportReport();
    report.entriesFound = 2;
    report.lastFiledVatPeriod = "202608";
    report.vatCutoffDate = CUTOFF;
    new MicroinvestAccountingPreflightService().applyVatPeriodCutoff(root, CUTOFF, report);

    String text = report.toText("Preflight");
    assertTrue(text.contains("202608"), () -> text);
    assertTrue(text.contains("31.08.2026"), () -> text);
    assertTrue(text.contains("СЛЕД ГРАНИЦАТА"), () -> text);
    assertTrue(text.contains("обикновени фактури"), () -> text);
    assertEquals(1, report.skippedAfterCutoff);
  }
}
