package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Entry;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Line;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Database-free rules of the accountant-approved Microinvest mapping layer. */
class MicroinvestAccountMappingTest {

  private static ExportRoot root(int year, String currency) {
    ExportRoot root = new ExportRoot();
    root.financialYear.year = year;
    root.financialYear.baseCurrency = currency;
    return root;
  }

  private static Line line(int id, String account, Integer partner, String debit, String credit) {
    Line line = new Line();
    line.sourceId = id;
    line.accountNumber = account;
    line.partnerSourceId = partner;
    line.debit = debit == null ? null : new BigDecimal(debit);
    line.credit = credit == null ? null : new BigDecimal(credit);
    return line;
  }

  private static Entry entry(int id, String documentType, String direction, Line... lines) {
    Entry entry = new Entry();
    entry.sourceId = id;
    entry.documentType = documentType;
    entry.direction = direction;
    entry.lines.addAll(java.util.List.of(lines));
    return entry;
  }

  @Test
  void accountRemapping_appliesApprovedTargetsAndRefusesUnmapped489() {
    ExportRoot root = root(2019, "BGN");
    Entry exception =
        entry(
            5245,
            "МО",
            "UNDEFINED",
            line(1, "489", null, "10", null),
            line(2, "159/1", 7, null, "10"));
    Entry other =
        entry(
            9999,
            "МО",
            "UNDEFINED",
            line(3, "489", null, "5", null),
            line(4, "405/4", 11, null, "5"),
            line(5, "419/1", 12, "5", null),
            line(6, "493", 13, null, "5"),
            line(7, "461", null, "1", null),
            line(8, "304", null, null, "1"));
    root.entries.add(exception);
    root.entries.add(other);

    MicroinvestAccountMapping.Outcome outcome = MicroinvestAccountMapping.apply(root);

    // the eight FY2019 entries: 489 -> 151 with source partner 642 where the line has none
    assertEquals("151", exception.lines.get(0).accountNumber);
    assertEquals(Integer.valueOf(642), exception.lines.get(0).partnerSourceId);
    assertEquals("151", exception.lines.get(1).accountNumber); // 159/1 -> 151, partner kept
    assertEquals(Integer.valueOf(7), exception.lines.get(1).partnerSourceId);
    // 489 anywhere else has no approved mapping and stays a blocker
    assertEquals("489", other.lines.get(0).accountNumber);
    assertTrue(outcome.unmapped.contains("489"));
    assertEquals("401", other.lines.get(1).accountNumber); // 405/4 -> 401 with source partner
    assertEquals(Integer.valueOf(11), other.lines.get(1).partnerSourceId);
    assertEquals("411", other.lines.get(2).accountNumber);
    assertEquals(Integer.valueOf(12), other.lines.get(2).partnerSourceId);
    assertEquals("493", other.lines.get(3).accountNumber); // 493 -> 493 without a partner
    assertNull(other.lines.get(3).partnerSourceId);
    assertEquals("456", other.lines.get(4).accountNumber); // one pass only, never 456 -> 459
    assertEquals("304", other.lines.get(5).accountNumber);
    assertEquals("489", other.lines.get(0).sourceAccountNumber);
  }

  @Test
  void journalRouting_nullDocumentTypeAndNegativeNormalization() {
    ExportRoot root = root(2019, "BGN");
    Entry cash =
        entry(1, null, "PURCHASE", line(1, "501", null, "5", null), line(2, "401", 1, null, "5"));
    Entry bank =
        entry(2, null, "SALE", line(3, "503/2", null, "5", null), line(4, "411", 1, null, "5"));
    Entry purchase =
        entry(
            3, null, "PURCHASE", line(5, "601/1", null, "5", null), line(6, "405/4", 1, null, "5"));
    Entry sale =
        entry(4, null, "SALE", line(7, "411", 1, "5", null), line(8, "702", null, null, "5"));
    Entry memo =
        entry(
            5,
            null,
            "UNDEFINED",
            line(9, "609", null, "5", null),
            line(10, "123", null, null, "5"));
    Entry typed =
        entry(
            6,
            "Ф-ра",
            "SALE",
            line(11, "509/1", null, "5", null),
            line(12, "702", null, null, "5"));
    Entry negative =
        entry(
            7,
            "МО",
            "UNDEFINED",
            line(13, "304", null, "-52.90", null),
            line(14, "123", null, null, "-52.90"));
    root.entries.addAll(java.util.List.of(cash, bank, purchase, sale, memo, typed, negative));

    MicroinvestAccountMapping.Outcome outcome = MicroinvestAccountMapping.apply(root);

    assertEquals("CSH", MicroinvestJournalMapper.map(cash).journalCode); // 501 beats PURCHASE
    assertEquals("BNK", MicroinvestJournalMapper.map(bank).journalCode); // 503/2 beats SALE
    assertEquals("PCH", MicroinvestJournalMapper.map(purchase).journalCode);
    assertEquals("SAL", MicroinvestJournalMapper.map(sale).journalCode);
    assertEquals("MOM", MicroinvestJournalMapper.map(memo).journalCode);
    // routing uses the SOURCE account (509/1 is remapped to 501) and a typed entry keeps its map
    assertEquals("SAL", MicroinvestJournalMapper.map(typed).journalCode);
    assertEquals("BNK", MicroinvestJournalMapper.map(bank).journalCode);

    Line debitSide = negative.lines.get(0);
    Line creditSide = negative.lines.get(1);
    assertEquals(0, BigDecimal.ZERO.compareTo(debitSide.debit));
    assertEquals(0, new BigDecimal("52.90").compareTo(debitSide.credit));
    assertEquals(0, new BigDecimal("52.90").compareTo(creditSide.debit));
    assertEquals(0, BigDecimal.ZERO.compareTo(creditSide.credit));
    assertTrue(debitSide.negativeNormalized);
    assertEquals(2, outcome.negativesFlipped);
  }

  @Test
  void openingMoveRule_onlyFy2016AndEuroFy2026() {
    assertTrue(MicroinvestAccountingPreflightService.hasOpeningMove(root(2016, "BGN")));
    assertTrue(MicroinvestAccountingPreflightService.hasOpeningMove(root(2026, "EUR")));
    for (int year = 2017; year <= 2025; year++) {
      assertFalse(
          MicroinvestAccountingPreflightService.hasOpeningMove(root(year, "BGN")), "" + year);
    }
    assertFalse(MicroinvestAccountingPreflightService.hasOpeningMove(root(2026, "BGN")));
  }

  @Test
  void fixedAssetCategories_useApprovedNyxaNames() {
    java.util.Map<String, String> approved = MicroinvestFixedAssetImportService.APPROVED_CATEGORIES;
    assertEquals("Машини и оборудване", approved.get("Категория II - машини"));
    assertEquals("Компютърна техника", approved.get("Категория IV - компютри"));
    assertEquals("Автомобили", approved.get("Категория V - автомобили"));
    assertEquals("Автомобили", approved.get("Категория VI - ДДМА и ДДНА"));
    assertEquals("Офис обзавеждане", approved.get("Категория VII - други"));
    assertEquals(
        "Категория II - машини",
        MicroinvestFixedAssetImportService.normalizeCategory(" Категория II – машини "));
  }
}
