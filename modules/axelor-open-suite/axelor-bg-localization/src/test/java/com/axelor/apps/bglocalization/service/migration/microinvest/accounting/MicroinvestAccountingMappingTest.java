package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.MicroinvestAccountingImportWizard;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.web.MicroinvestAccountingImportController;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The rules that decide what the imported accounting data MEANS, proven without a database.
 *
 * <p>Every fixture here is synthetic but shaped on real findings from a pilot database. Client
 * accounting data is deliberately not committed as a fixture.
 */
class MicroinvestAccountingMappingTest {

  // ── Composite source identity ──────────────────────────────────────────────────────────────────

  /**
   * importId carries a UNIQUE index that is global per table, not scoped by company. The same
   * Microinvest AccountingID exists in every Delta Pro database ever created, so the key has to
   * carry the whole coordinate or two migrations collide on their first row.
   */
  @Test
  void sourceKey_carriesCompanyAndDatabaseBeforeTheId() {
    String key =
        MicroinvestAccountingSourceKey.of(
            "100000065",
            "DeltaPro_00000000000000",
            MicroinvestAccountingSourceKey.TYPE_ENTRY,
            29944);
    assertEquals("mi:100000065:DELTAPRO_00000000000000:acc:29944", key);
    assertTrue(key.length() < 255, "must fit importId varchar(255)");
  }

  @Test
  void sourceKey_sameSourceIdInTwoDatabasesDoesNotCollide() {
    String a = MicroinvestAccountingSourceKey.of("100000065", "DeltaPro_AAA", "acc", 29944);
    String b = MicroinvestAccountingSourceKey.of("100000065", "DeltaPro_BBB", "acc", 29944);
    assertNotEquals(a, b);
  }

  @Test
  void sourceKey_sameSourceIdForTwoCompaniesDoesNotCollide() {
    String a = MicroinvestAccountingSourceKey.of("100000065", "DeltaPro_AAA", "acc", 29944);
    String b = MicroinvestAccountingSourceKey.of("100000026", "DeltaPro_AAA", "acc", 29944);
    assertNotEquals(a, b);
  }

  /** A cosmetic difference in how the EIK was typed must not produce a parallel set of keys. */
  @Test
  void sourceKey_normalisesTheEikTheSameWayTheGuardMatchesIt() {
    assertEquals(
        MicroinvestAccountingSourceKey.of("100000065", "db", "acc", 1),
        MicroinvestAccountingSourceKey.of("BG 100 000 065", "db", "acc", 1));
  }

  /** The prefix is what tells an earlier run of this importer from a manually entered move. */
  @Test
  void sourceKey_prefixIsAPrefixOfEveryKeyItProduces() {
    String prefix = MicroinvestAccountingSourceKey.prefix("100000065", "DeltaPro_X");
    assertTrue(
        MicroinvestAccountingSourceKey.of("100000065", "DeltaPro_X", "accd", 7).startsWith(prefix));
  }

  // ── Company guard ──────────────────────────────────────────────────────────────────────────────

  @Test
  void companyGuard_matchesOnDigitsOnly() {
    assertEquals(
        MicroinvestAccountingCompanyGuard.Verdict.MATCH,
        MicroinvestAccountingCompanyGuard.verdict("100000065", "BG100000065"));
  }

  @Test
  void companyGuard_refusesADifferentRegistration() {
    assertEquals(
        MicroinvestAccountingCompanyGuard.Verdict.MISMATCH,
        MicroinvestAccountingCompanyGuard.verdict("100000065", "100000026"));
  }

  /** A 13-digit branch registration is a different obligation from its 9-digit parent. */
  @Test
  void companyGuard_doesNotTreatABranchAsItsParent() {
    assertEquals(
        MicroinvestAccountingCompanyGuard.Verdict.MISMATCH,
        MicroinvestAccountingCompanyGuard.verdict("1000000650001", "100000065"));
  }

  @Test
  void companyGuard_reportsMissingIdentifiersSeparately() {
    assertEquals(
        MicroinvestAccountingCompanyGuard.Verdict.NO_SOURCE_IDENTIFIER,
        MicroinvestAccountingCompanyGuard.verdict("", "100000065"));
    assertEquals(
        MicroinvestAccountingCompanyGuard.Verdict.NO_ACTIVE_IDENTIFIER,
        MicroinvestAccountingCompanyGuard.verdict("100000065", null));
    assertEquals(
        MicroinvestAccountingCompanyGuard.Verdict.NO_ACTIVE_COMPANY,
        MicroinvestAccountingCompanyGuard.verdict("100000065", "100000065", false));
  }

  // ── Journal mapping ────────────────────────────────────────────────────────────────────────────

  @Test
  void journalMapping_invoiceFollowsTheDirection() {
    assertEquals("SAL", MicroinvestJournalMapper.map("Ф-ра", "SALE").journalCode);
    assertEquals(
        MoveRepository.FUNCTIONAL_ORIGIN_SALE,
        MicroinvestJournalMapper.map("Ф-ра", "SALE").functionalOrigin);
    assertEquals("PCH", MicroinvestJournalMapper.map("Ф-ра", "PURCHASE").journalCode);
    assertEquals(
        MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE,
        MicroinvestJournalMapper.map("Ф-ра", "PURCHASE").functionalOrigin);
  }

  @Test
  void journalMapping_creditNoteGoesToTheRefundJournals() {
    assertEquals("RFS", MicroinvestJournalMapper.map("КИ", "SALE").journalCode);
    assertEquals("RFP", MicroinvestJournalMapper.map("КИ", "PURCHASE").journalCode);
  }

  /** A customs declaration is an import whatever the header direction says. */
  @Test
  void journalMapping_customsDeclarationIsAlwaysAPurchase() {
    assertEquals("PCH", MicroinvestJournalMapper.map("МД", "UNDEFINED").journalCode);
    assertEquals(
        MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE,
        MicroinvestJournalMapper.map("МД", "UNDEFINED").functionalOrigin);
  }

  @Test
  void journalMapping_cashAndBankArePayments() {
    assertEquals("CSH", MicroinvestJournalMapper.map("ПКО", "UNDEFINED").journalCode);
    assertEquals("CSH", MicroinvestJournalMapper.map("РКО", "UNDEFINED").journalCode);
    assertEquals("BNK", MicroinvestJournalMapper.map("БИ", "UNDEFINED").journalCode);
    assertEquals(
        MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT,
        MicroinvestJournalMapper.map("БИ", "UNDEFINED").functionalOrigin);
  }

  /**
   * Axelor's enumeration has no value for a Bulgarian мемориален ордер or a ЗДДС протокол. Leaving
   * it unset is the honest answer — {@code checkFunctionalOriginSelect} returns silently for null,
   * whereas picking one of the nine would misclassify the document in every downstream report.
   */
  @Test
  void journalMapping_leavesFunctionalOriginUnsetWhereAxelorHasNoValueForIt() {
    assertEquals("MOM", MicroinvestJournalMapper.map("МО", "UNDEFINED").journalCode);
    assertNull(MicroinvestJournalMapper.map("МО", "UNDEFINED").functionalOrigin);
    assertEquals("VAT", MicroinvestJournalMapper.map("ПЗДДС", "PURCHASE").journalCode);
    assertNull(MicroinvestJournalMapper.map("ПЗДДС", "PURCHASE").functionalOrigin);
  }

  /** An unmapped document type returns null so Preflight can block rather than guess a journal. */
  @Test
  void journalMapping_refusesToGuessAnUnknownDocumentType() {
    assertNull(MicroinvestJournalMapper.map("Акр", "UNDEFINED"));
    assertNull(MicroinvestJournalMapper.map("Ф-ра", "UNDEFINED"));
  }

  @Test
  void journalMapping_openingGoesToTheCarriedBalanceJournal() {
    assertEquals("RPB", MicroinvestJournalMapper.OPENING.journalCode);
    assertEquals(
        MoveRepository.FUNCTIONAL_ORIGIN_OPENING,
        MicroinvestJournalMapper.OPENING.functionalOrigin);
  }

  // ── Partner identity ladder ────────────────────────────────────────────────────────────────────

  @Test
  void partnerIdentity_prefersTheBulstat() {
    MicroinvestAccountingSourceModels.Partner partner =
        partner("КЛИЙН ПЛЮС", "206213270", "BG206213270");
    assertEquals("206213270", MicroinvestAccountingPreflightService.usableIdentifier(partner));
  }

  /**
   * Microinvest writes the sentinel for a foreign counterparty and keeps its EU VAT number: all
   * seven such partners used on FY2026 settlement accounts carry a real EL/RO number.
   */
  @Test
  void partnerIdentity_fallsBackToTheVatNumberWhenTheBulstatIsTheForeignSentinel() {
    MicroinvestAccountingSourceModels.Partner partner =
        partner("BLUEDOG", "999999999999999", "EL900000030");
    assertEquals("EL900000030", MicroinvestAccountingPreflightService.usableIdentifier(partner));
  }

  @Test
  void partnerIdentity_isNullWhenBothSidesAreSentinelOrBlank() {
    assertNull(
        MicroinvestAccountingPreflightService.usableIdentifier(
            partner("Неизвестен", "999999999999999", "999999999999999")));
    assertNull(
        MicroinvestAccountingPreflightService.usableIdentifier(partner("Неизвестен", "", null)));
  }

  @Test
  void partnerIdentity_stripsTheBgPrefixAndSpaces() {
    assertEquals(
        "100000065", MicroinvestAccountingPreflightService.normalizeIdentifier(" bg 100 000 065 "));
  }

  // ── Parsing and checksums ──────────────────────────────────────────────────────────────────────

  /**
   * The traced purchase chain from the pilot database: Dt 304 / Ct 401 for the goods and Dt 453/1 /
   * Ct 401 for the VAT, one supplier, direction resolved from the VAT term.
   */
  @Test
  void parser_readsTheAccountingDateAndTheSplitDebitCreditColumns() throws Exception {
    ExportRoot root =
        MicroinvestAccountingExportFileParser.parse(
            PURCHASE_FIXTURE.getBytes(StandardCharsets.UTF_8));

    assertEquals("EUR", root.financialYear.baseCurrency);
    assertEquals(1, root.entries.size());
    MicroinvestAccountingSourceModels.Entry entry = root.entries.get(0);

    // AccountingDate, not the data-entry timestamp.
    assertEquals(LocalDate.of(2026, 7, 27), entry.date);
    assertEquals("PURCHASE", entry.direction);
    assertEquals("VAT_TERM", entry.directionSource);
    assertEquals("01", entry.napDocumentType);

    assertEquals(4, entry.lines.size());
    assertEquals(new BigDecimal("1320.00"), entry.lines.get(1).debit);
    assertEquals(BigDecimal.ZERO.setScale(2), entry.lines.get(1).credit);
    assertEquals(new BigDecimal("1320.00"), entry.lines.get(0).credit);
    assertEquals("453/1", entry.lines.get(3).accountNumber);
  }

  /** Analytic sub-accounts survive parsing untouched; nothing collapses 453/1 onto 453. */
  @Test
  void parser_keepsAnalyticSubAccountNumbersExactly() throws Exception {
    ExportRoot root =
        MicroinvestAccountingExportFileParser.parse(
            PURCHASE_FIXTURE.getBytes(StandardCharsets.UTF_8));
    assertTrue(
        root.entries.get(0).lines.stream().anyMatch(l -> "453/1".equals(l.accountNumber)),
        "the analytic sub-account must not be collapsed to its synthetic parent");
  }

  @Test
  void parser_toleratesUnknownSectionsSoOlderFilesKeepWorking() throws Exception {
    String withExtra =
        PURCHASE_FIXTURE.replace(
            "\"warnings\": []", "\"warnings\": [], \"somethingNew\": {\"a\":1}");
    ExportRoot root =
        MicroinvestAccountingExportFileParser.parse(withExtra.getBytes(StandardCharsets.UTF_8));
    assertEquals(1, root.entries.size());
  }

  @Test
  void parser_failsLoudlyOnMalformedJson() {
    assertThrows(
        JsonProcessingException.class,
        () ->
            MicroinvestAccountingExportFileParser.parse(
                "{ not json".getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * The file hash is audit evidence only. Two exports of identical accounting content differ in
   * bytes (a new exportedAt), which is exactly why idempotency is decided on contentChecksum.
   */
  @Test
  void fileHash_changesWithVolatileFieldsWhileContentChecksumDoesNot() throws Exception {
    String second = PURCHASE_FIXTURE.replace("2026-10-01T09:00:00", "2026-10-02T11:30:00");
    String hashA =
        MicroinvestAccountingExportFileParser.fileSha256(
            PURCHASE_FIXTURE.getBytes(StandardCharsets.UTF_8));
    String hashB =
        MicroinvestAccountingExportFileParser.fileSha256(second.getBytes(StandardCharsets.UTF_8));
    assertNotEquals(hashA, hashB, "byte hash must react to the export timestamp");

    ExportRoot rootA =
        MicroinvestAccountingExportFileParser.parse(
            PURCHASE_FIXTURE.getBytes(StandardCharsets.UTF_8));
    ExportRoot rootB =
        MicroinvestAccountingExportFileParser.parse(second.getBytes(StandardCharsets.UTF_8));
    assertEquals(
        rootA.contentChecksum,
        rootB.contentChecksum,
        "accounting content is unchanged, so the content checksum must be too");
  }

  @Test
  void fileHash_isStableAndHex() {
    String hash = MicroinvestAccountingExportFileParser.sha256Utf8("test");
    assertEquals(64, hash.length());
    assertEquals(hash, MicroinvestAccountingExportFileParser.sha256Utf8("test"));
    assertTrue(hash.matches("[0-9a-f]{64}"));
  }

  // ── Currency evidence ──────────────────────────────────────────────────────────────────────────

  /**
   * A source row whose rate is exactly 1 with the currency amount equal to the base amount is a
   * Microinvest no-op marker, not FX (193 of the 333 FY2026 currency rows are such markers).
   * Emitting it as FX would assert "8864.07 USD = 8864.07 EUR". The exporter drops them, so a
   * parsed line simply has no currencyDetail and nothing downstream invents a rate of 1.
   */
  @Test
  void currency_noOpMarkersArriveAsNoForeignCurrencyAtAll() throws Exception {
    ExportRoot root =
        MicroinvestAccountingExportFileParser.parse(
            PURCHASE_FIXTURE.getBytes(StandardCharsets.UTF_8));
    assertNull(root.entries.get(0).currency);
    root.entries.get(0).lines.forEach(line -> assertNull(line.currencyDetail));
  }

  @Test
  void currency_realForeignLinesKeepTheirOwnAmountAndRate() throws Exception {
    ExportRoot root =
        MicroinvestAccountingExportFileParser.parse(FX_FIXTURE.getBytes(StandardCharsets.UTF_8));
    MicroinvestAccountingSourceModels.Entry entry = root.entries.get(0);
    assertEquals("USD", entry.currency);
    assertEquals(new BigDecimal("0.848460"), entry.exchangeRate);
    assertEquals("USD", entry.lines.get(0).currencyDetail.currency);
    assertEquals(new BigDecimal("1000.00"), entry.lines.get(0).currencyDetail.currencyAmount);
    assertFalse(entry.mixedCurrency);
  }

  /**
   * An entry mixing two currencies cannot become one Move and is flagged for Preflight to block.
   */
  @Test
  void currency_mixedCurrencyEntryIsFlaggedRatherThanSilentlyFlattened() throws Exception {
    String mixed = FX_FIXTURE.replace("\"mixedCurrency\": false", "\"mixedCurrency\": true");
    ExportRoot root =
        MicroinvestAccountingExportFileParser.parse(mixed.getBytes(StandardCharsets.UTF_8));
    assertTrue(root.entries.get(0).mixedCurrency);
  }

  // ── Import confirmation ────────────────────────────────────────────────────────────────────────

  /** A BGN year creates standard Moves and uses its later opening balance only for verification. */
  @Test
  void confirmation_bgnYearUsesStandardMovesAndNoRepeatedOpening() {
    MicroinvestAccountingPreflightService.Result result =
        confirmationResult(2019, 717, 0, 707, 198);

    String text = MicroinvestAccountingPreflightService.importConfirmation(result);

    assertTrue(text.contains("Тест Фиш България ООД"), text);
    assertTrue(text.contains("ОСЧЕТОВОДИ 717 стандартни Moves в BGN"), text);
    assertTrue(text.contains("Началното салдо от 707 реда"), text);
    assertTrue(text.contains("само за сверка"), text);
  }

  /**
   * A blocked Preflight is not a confirmation prompt. There is nothing to agree to — the run cannot
   * proceed — and asking "Продължавате ли?" over a list of blockers teaches the operator to click
   * through warnings.
   */
  @Test
  void confirmation_blockersProduceNoConfirmationAndStopImport() {
    MicroinvestAccountingPreflightService.Result result =
        confirmationResult(2026, 762, 1, 588, 307);
    result.report.blocked("сметки", "липсва сметка 509/1");
    result.report.blocked("журнали", "PCH не допуска 304");

    assertNull(MicroinvestAccountingPreflightService.importConfirmation(result));

    MicroinvestAccountingImportWizard form = wizard("готово", 42L, 7L);
    form.setBlockedCount(2);
    IllegalStateException refusal =
        assertThrows(
            IllegalStateException.class,
            () -> MicroinvestAccountingImportController.assertImportAllowed(form, 42L, 7L));
    assertTrue(refusal.getMessage().contains("Preflight намери 2 блокера"), refusal.getMessage());
  }

  private MicroinvestAccountingImportWizard wizard(
      String confirmText, Long preflightFileId, Long preflightCompanyId) {
    MicroinvestAccountingImportWizard form = new MicroinvestAccountingImportWizard();
    form.setImportConfirmText(confirmText);
    form.setPreflightFileId(preflightFileId);
    form.setPreflightCompanyId(preflightCompanyId);
    return form;
  }

  /**
   * Builds the Result the way {@code preflight()} does: postable and unpostable entries are counted
   * with the importer's own rule, not asserted by the fixture.
   */
  private MicroinvestAccountingPreflightService.Result confirmationResult(
      int year, int postable, int zeroValue, int opening, int settlements) {
    MicroinvestAccountingPreflightService.Result result =
        new MicroinvestAccountingPreflightService.Result();
    result.report = new MicroinvestAccountingImportReport();
    result.root = new ExportRoot();
    result.root.financialYear.year = year;
    result.root.financialYear.baseCurrency = year <= 2025 ? "BGN" : "EUR";
    for (int i = 0; i < postable; i++) {
      result.root.entries.add(entryWith(new BigDecimal("10.00")));
    }
    for (int i = 0; i < zeroValue; i++) {
      result.root.entries.add(entryWith(BigDecimal.ZERO));
    }
    for (MicroinvestAccountingSourceModels.Entry entry : result.root.entries) {
      if (MicroinvestAccountingImportService.hasPostableLine(entry)) {
        result.postableEntries++;
      } else {
        result.unpostableEntries++;
      }
    }
    for (int i = 0; i < opening; i++) {
      result.root.openingBalances.add(new MicroinvestAccountingSourceModels.OpeningBalance());
    }
    for (int i = 0; i < settlements; i++) {
      result.root.settlements.add(new MicroinvestAccountingSourceModels.Settlement());
    }
    Company company = new Company();
    company.setName("Тест Фиш България ООД");
    result.company = company;
    return result;
  }

  private MicroinvestAccountingSourceModels.Entry entryWith(BigDecimal debit) {
    MicroinvestAccountingSourceModels.Entry entry = new MicroinvestAccountingSourceModels.Entry();
    MicroinvestAccountingSourceModels.Line line = new MicroinvestAccountingSourceModels.Line();
    line.accountNumber = "411";
    line.debit = debit;
    line.credit = BigDecimal.ZERO;
    entry.lines.add(line);
    return entry;
  }

  // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

  private MicroinvestAccountingSourceModels.Partner partner(
      String name, String bulstat, String vat) {
    MicroinvestAccountingSourceModels.Partner partner =
        new MicroinvestAccountingSourceModels.Partner();
    partner.name = name;
    partner.bulstat = bulstat;
    partner.vatNumber = vat;
    return partner;
  }

  /** Shaped on the traced FY2026 purchase invoice; amounts and ids are synthetic. */
  private static final String PURCHASE_FIXTURE =
      "{"
          + "\"schemaVersion\": \"accounting-1.0\","
          + "\"source\": {\"database\": \"DeltaPro_TEST\", \"exportedAt\": \"2026-10-01T09:00:00\","
          + " \"exporter\": \"test\"},"
          + "\"company\": {\"name\": \"Тест ООД\", \"bulstat\": \"100000065\", \"vatId\": \"BG100000065\"},"
          + "\"financialYear\": {\"sourceId\": 11, \"year\": 2026, \"startDate\": \"2026-01-01\","
          + " \"baseCurrency\": \"EUR\"},"
          + "\"accounts\": [{\"number\": \"401\", \"name\": \"Доставчици\", \"isEuro\": true},"
          + " {\"number\": \"304\", \"name\": \"Стоки\", \"isEuro\": true},"
          + " {\"number\": \"453/1\", \"name\": \"ДДС Покупки\", \"isEuro\": true}],"
          + "\"partners\": [{\"sourceId\": 826, \"name\": \"КЛИЙН ПЛЮС ЕООД\","
          + " \"bulstat\": \"206213270\", \"vatNumber\": \"BG206213270\", \"isDeleted\": false}],"
          + "\"vatTerms\": [{\"sourceId\": 2, \"direction\": \"PURCHASE\","
          + " \"name\": \"Получени доставки с право на ПДК\", \"vatRate\": 20}],"
          + "\"documentTypes\": [{\"sourceId\": 1, \"napCode\": \"01\", \"shortName\": \"Ф-ра\"}],"
          + "\"entries\": [{"
          + "  \"sourceId\": 34147, \"number\": \"0000000581\", \"date\": \"2026-07-27\","
          + "  \"dueDate\": \"2026-07-27\", \"direction\": \"PURCHASE\", \"directionSource\": \"VAT_TERM\","
          + "  \"documentNumber\": \"0600000141\", \"documentDate\": \"2026-07-27\","
          + "  \"napDocumentType\": \"01\", \"documentType\": \"Ф-ра\","
          + "  \"description\": \"стоки\", \"reference\": null,"
          + "  \"currency\": null, \"exchangeRate\": null, \"mixedCurrency\": false,"
          + "  \"rawRelations\": {\"enteredAt\": \"2026-08-04T15:19:04\", \"branchId\": 1},"
          + "  \"lines\": ["
          + "    {\"sourceId\": 77193, \"accountNumber\": \"401\", \"partnerSourceId\": 826,"
          + "     \"debit\": 0.00, \"credit\": 1320.00, \"vatTermId\": 2, \"vatRate\": 20,"
          + "     \"currencyDetail\": null},"
          + "    {\"sourceId\": 77194, \"accountNumber\": \"304\", \"partnerSourceId\": 826,"
          + "     \"debit\": 1320.00, \"credit\": 0.00, \"vatTermId\": 2, \"vatRate\": 20,"
          + "     \"currencyDetail\": null},"
          + "    {\"sourceId\": 77195, \"accountNumber\": \"401\", \"partnerSourceId\": 826,"
          + "     \"debit\": 0.00, \"credit\": 264.00, \"vatTermId\": 2, \"vatRate\": 20,"
          + "     \"currencyDetail\": null},"
          + "    {\"sourceId\": 77196, \"accountNumber\": \"453/1\", \"partnerSourceId\": 826,"
          + "     \"debit\": 264.00, \"credit\": 0.00, \"vatTermId\": 2, \"vatRate\": 20,"
          + "     \"currencyDetail\": null}"
          + "  ]}],"
          + "\"openingBalances\": [],"
          + "\"settlements\": [],"
          + "\"counts\": {\"accounts\": 3, \"partners\": 1, \"entries\": 1, \"lines\": 4,"
          + " \"openingBalances\": 0, \"settlements\": 0},"
          + "\"checksums\": {\"totalDebit\": 1584.00, \"totalCredit\": 1584.00, \"balanced\": true,"
          + " \"openingDebit\": 0.00, \"openingCredit\": 0.00},"
          + "\"contentChecksum\": \"deadbeef\","
          + "\"warnings\": []"
          + "}";

  /** A genuine foreign-currency document: one currency, one rate, exact source amounts. */
  private static final String FX_FIXTURE =
      PURCHASE_FIXTURE
          .replace(
              "\"currency\": null, \"exchangeRate\": null",
              "\"currency\": \"USD\", \"exchangeRate\": 0.848460")
          .replace(
              "{\"sourceId\": 77193, \"accountNumber\": \"401\", \"partnerSourceId\": 826,"
                  + "     \"debit\": 0.00, \"credit\": 1320.00, \"vatTermId\": 2, \"vatRate\": 20,"
                  + "     \"currencyDetail\": null}",
              "{\"sourceId\": 77193, \"accountNumber\": \"401\", \"partnerSourceId\": 826,"
                  + "     \"debit\": 0.00, \"credit\": 848.46, \"vatTermId\": 2, \"vatRate\": 20,"
                  + "     \"currencyDetail\": {\"currency\": \"USD\", \"currencyAmount\": 1000.00,"
                  + "     \"exchangeRate\": 0.848460}}");
}
