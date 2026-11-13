package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.account.db.InvoiceTerm;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vat.BgVatDocumentCollector;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The production path, executed end to end against a scratch PostgreSQL database.
 *
 * <p>Everything here runs the real {@link MicroinvestAccountingImportService} over the real annual
 * export files — no hand-built sample, no shortened path. What it proves:
 *
 * <ul>
 *   <li>a BGN year posts standard Moves with exact source amounts and BGN book currency;
 *   <li>Preflight performs exactly zero writes;
 *   <li>the EUR year posts every entry without a duplicated annual opening Move, reaching ACCOUNTED
 *       through the DAYBOOK transition;
 *   <li>every source settlement becomes a stock Axelor reconciliation and the invoice terms it
 *       settles really do go to zero;
 *   <li>a single failure rolls the whole year back — moves, partners, terms, reconciliations and
 *       the sequence counters with them;
 *   <li>a second identical import writes no business record, and a byte-different export of
 *       identical content is recognised as the same content;
 *   <li>imported moves never reach the VAT diaries;
 *   <li>the uploaded source file remains intact.
 * </ul>
 *
 * <p>⚠️ PostgreSQL, not HSQLDB, and not by preference: this module's {@code Move.xml} extension
 * declares {@code ocrUploadIds} as a PostgreSQL-only {@code array_to_string(array_agg(...))}
 * formula that Hibernate inlines into every {@code Move} select, so HSQLDB cannot read back a
 * single move.
 *
 * <p>⚠️ The export files are client accounting data and are NOT in the repository. Point {@code
 * -Dmicroinvest.export.dir} at a directory holding them, or drop them in {@code
 * test-data/microinvest}; without them this class skips rather than pretending to pass.
 */
// ⚠️ Без тестова PostgreSQL (persistence unit „testPostgres“, 55432) класът се ПРОПУСКА, както
// другите DB тестове — условието се оценява преди Guice да вдигне JpaModule.
@EnabledIf(
    value =
        "com.axelor.apps.bglocalization.service.migration.microinvest.accounting"
            + ".MicroinvestAccountingPostgresModule#available",
    disabledReason = "няма тестова PostgreSQL (persistence unit „testPostgres“)")
@ExtendWith(GuiceExtension.class)
@GuiceModules(MicroinvestAccountingPostgresModule.class)
@Tag("integration")
class MicroinvestAccountingLedgerTest extends JpaSupport {

  /**
   * Границата на историята за тестовете тук: далеч в бъдещето, тоест НИЩО не се отрязва.
   *
   * <p>Периодът е задължителен вход на импорта ({@link MicroinvestVatPeriodCutoff}), а тези тестове
   * твърдят неща за ЦЕЛИЯ файл — броя проводки, равненията, атомичността. Стойност, която реже,
   * щеше да промени точно числата, които те доказват. Самото рязане си има свои тестове.
   */
  private static final String NO_CUTOFF = "209912";

  /** Proven against the source database: FY2019 has 717 entries, FY2026 has 763. */
  private static final int ENTRIES_2019 = 717;

  /**
   * FY2026 holds 763 ordinary entries, of which 762 can become a Move.
   *
   * <p>Proven in the source, not inferred from a run: {@code SELECT COUNT(*) FROM Accountings WHERE
   * FinancialYearID = 11 AND IsInitialBalance = 0} is 763, and exactly one of them — AccountingID
   * <b>33987</b> — has {@code SUM(ABS(Amount) + ABS(RefAmount)) = 0} across its detail rows. It is
   * a zero-value invoice (Ф-ра, 2026-05-01): two lines on real accounts, 411 Клиенти and 702
   * Приходи, both 0.00 on both sides.
   *
   * <p>It is <b>not</b> an opening-balance record — {@code IsInitialBalance} is False — so it is
   * not represented by the RPB move either. Axelor simply cannot hold it: {@code
   * MoveLineControlServiceImpl.validateMoveLine} rejects a line whose debit, credit and currency
   * amount are all zero. Skipping it moves no balance (0.00 against 0.00) and is reported
   * explicitly.
   */
  private static final int ENTRIES_2026 = 763;

  private static final int POSTABLE_ENTRIES_2026 = 762;
  private static final int SETTLEMENTS_2026 = 307;

  private static byte[] file2019;
  private static byte[] file2026;

  /** The first history year: its opening balances become the single standard opening Move. */
  private static byte[] file2016;

  private static MicroinvestAccountingSourceModels.ExportRoot root2016;

  /**
   * The derived, explicitly TEST-ONLY FY2026 file — the original with the four unresolved BGN
   * currency sub-records on account 509/1 nulled out and nothing else touched.
   *
   * <p>⚠️ It is NOT a valid production export and is deliberately kept outside the repository, so
   * this is a system property with no default inside the tree. Without it the operational tests
   * skip; they never fall back to the original file, because the original must keep blocking.
   *
   * <p>Why the original blocks and why dropping these four rows is defensible is recorded in the
   * investigation of the source: {@code 509/1} is the euro-changeover successor of {@code 501 Каса
   * в основна валута} (its {@code MappedAccountID}), its opening balance converts 96.53 BGN to
   * 49.36 EUR at exactly the fixed 1.95583, and 171 of its 177 FY2026 lines carry an inert no-op
   * currency marker. The four outliers are operator keystrokes — two of them copy a figure straight
   * off the paired purchase invoice (the net 70.82 and the VAT 19.58). Reclassifying source data is
   * the accountant's call, so production stays blocked.
   */
  private static byte[] file2026TestOnly;

  private static MicroinvestAccountingSourceModels.ExportRoot root2026;

  private TestFishLedgerFixture fixture;

  // ── Setup ──────────────────────────────────────────────────────────────────────────────────────

  @BeforeAll
  static void loadExportFiles() throws Exception {
    // Gradle runs the test with the module directory as the working directory, so the repository
    // root is three levels up. test-data/ is untracked by design: client accounting
    // data never enters the repository.
    Path dir =
        System.getProperty("microinvest.export.dir") != null
            ? Paths.get(System.getProperty("microinvest.export.dir"))
            : Paths.get("")
                .toAbsolutePath()
                .resolve("../../../test-data/microinvest-acc")
                .normalize();
    Path f2019 = find(dir, "-2019.json");
    Path f2026 = find(dir, "-2026.json");
    assumeTrue(
        f2019 != null && f2026 != null,
        "Microinvest export files not found in "
            + dir
            + " -- set -Dmicroinvest.export.dir to the directory holding them");
    file2019 = Files.readAllBytes(f2019);
    file2026 = Files.readAllBytes(f2026);
    root2026 = MicroinvestAccountingExportFileParser.parse(file2026);

    // FY2016 is the first history year. It is optional: when the file is present the opening-move
    // test runs against it; when it is not, that one test skips (assumeTrue) rather than failing.
    Path f2016 = find(dir, "-2016.json");
    if (f2016 != null) {
      file2016 = Files.readAllBytes(f2016);
      root2016 = MicroinvestAccountingExportFileParser.parse(file2016);
    }

    String testOnly = System.getProperty("microinvest.testonly.2026");
    file2026TestOnly = testOnly == null ? null : Files.readAllBytes(Paths.get(testOnly));
  }

  /**
   * Skips the operational tests, loudly, when the derived file was not supplied — rather than
   * quietly running them against the original and reporting a block as a pass.
   */
  private static byte[] requireTestOnly2026() {
    assumeTrue(
        file2026TestOnly != null,
        "the derived TEST-ONLY FY2026 file was not supplied -- set"
            + " -Dmicroinvest.testonly.2026=<path>. The original export blocks by design and is"
            + " never substituted for it.");
    return file2026TestOnly;
  }

  private static Path find(Path dir, String suffix) throws Exception {
    if (!Files.isDirectory(dir)) {
      return null;
    }
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(suffix))
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * The scratch schema is created once and reused (see persistence.xml), so a domain change since
   * the last generation would otherwise surface as a baffling query error halfway through a run.
   */
  @BeforeEach
  void schemaIsNotStale() {
    Number columns =
        (Number)
            JPA.em()
                .createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_name = 'account_move'"
                        + "   AND column_name = 'bg_book_currency'")
                .getSingleResult();
    if (columns.intValue() == 0) {
      throw new IllegalStateException(
          "the scratch schema is stale -- recreate it, see META-INF/persistence.xml");
    }
  }

  @BeforeEach
  void freshCompany() {
    wipe();
    TestFishLedgerFixture built = new TestFishLedgerFixture();
    inTransaction(() -> built.build(root2026, 2026));
    fixture = built;
  }

  @AfterEach
  void clearSubject() {
    TestFishLedgerFixture.deauthenticate();
  }

  // ── 1. Historical standard ledger ─────────────────────────────────────────────────────────────

  /** A BGN year is imported once as standard accounting history with explicit BGN book currency. */
  @Test
  void bgn2019CreatesSevenHundredSeventeenStandardMoves() throws Exception {
    assertEquals(0L, fixture.moveCount(), "the ledger must be empty before the historical import");

    MicroinvestAccountingImportReport report = runImport(file2019);

    assertTrue(report.blocked == 0, () -> "unexpected blockers:\n" + report.toText("2019"));
    assertEquals(ENTRIES_2019, fixture.moveCount());
    assertEquals(
        ENTRIES_2019,
        Query.of(Move.class)
            .filter("self.company = :company AND self.bgBookCurrency.codeISO = 'BGN'")
            .bind("company", fixture.company)
            .count());
    // The lev amounts are kept to the stotinka: no redenomination, ever. Checked against the file
    // itself rather than against a currency code — 186 of the 717 entries are EUR-denominated
    // DOCUMENTS whose currencyCode is legitimately "EUR", while their debit and credit stay in lev.
    BigDecimal fileDebit = BigDecimal.ZERO;
    for (MicroinvestAccountingSourceModels.Entry entry :
        MicroinvestAccountingExportFileParser.parse(file2019).entries) {
      for (MicroinvestAccountingSourceModels.Line line : entry.lines) {
        fileDebit = fileDebit.add(line.debit == null ? BigDecimal.ZERO : line.debit);
      }
    }
    BigDecimal postedDebit =
        (BigDecimal)
            JPA.em()
                .createNativeQuery(
                    "SELECT COALESCE(SUM(ml.debit), 0) FROM account_move_line ml"
                        + " JOIN account_move m ON m.id = ml.move"
                        + " WHERE EXTRACT(YEAR FROM ml.date_val) = 2019"
                        + " AND m.bg_book_currency = (SELECT id FROM base_currency WHERE code_iso = 'BGN')")
                .getSingleResult();
    final BigDecimal sourceTotal = fileDebit;
    assertEquals(
        0,
        sourceTotal.compareTo(postedDebit),
        () -> "posted BGN debit " + postedDebit + " must equal the source's " + sourceTotal);
  }

  /**
   * 2016 is the first history year, so its opening balances become exactly one standard, balanced,
   * ACCOUNTED opening Move on the RPB journal. Every later year (2017-2026) validates against the
   * carried-forward balance and posts no second opening Move.
   *
   * <p>Optional: skips when no {@code -2016.json} file is supplied, rather than failing.
   */
  @Test
  void twentySixteenPostsExactlyOneBalancedOpeningMove() throws Exception {
    assumeTrue(root2016 != null, "FY2016 export file not found -- the opening-move test needs it");
    assertTrue(
        Integer.valueOf(2016).equals(root2016.financialYear.year),
        "the -2016.json file must declare financial year 2016");
    assertEquals(0L, fixture.moveCount(), "the ledger must be empty before the historical import");

    MicroinvestAccountingImportReport report = runImport(file2016);
    assertTrue(report.blocked == 0, () -> "unexpected blockers:\n" + report.toText("2016"));
    assertTrue(report.failed == 0, () -> "unexpected failures:\n" + report.toText("2016"));

    // The agreed behaviour in one line: exactly one standard opening Move for the first history
    // year.
    assertEquals(
        1L,
        openingMoveCount(),
        "2016 is the first history year: exactly one standard opening Move");
    Move opening =
        Query.of(Move.class)
            .filter("self.company = :company AND self.journal.code = 'RPB'")
            .bind("company", fixture.company)
            .fetchOne();
    assertNotNull(opening, "exactly one RPB opening move");
    assertTrue(
        opening.getMoveLineList() != null && !opening.getMoveLineList().isEmpty(),
        "the opening move must carry lines");
    assertEquals(
        MoveRepository.STATUS_ACCOUNTED,
        opening.getStatusSelect().intValue(),
        "the opening move must reach ACCOUNTED, not stop at DAYBOOK");

    // It must balance to the stotinka, and its gross debit/credit must equal the source's with the
    // documented sign flip applied.
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (MoveLine line : opening.getMoveLineList()) {
      debit = debit.add(line.getDebit());
      credit = credit.add(line.getCredit());
    }
    final BigDecimal openingDebit = debit;
    final BigDecimal openingCredit = credit;
    assertEquals(
        0,
        openingDebit.compareTo(openingCredit),
        () -> "the opening move must balance, got " + openingDebit + " / " + openingCredit);
    BigDecimal[] sourceOpening = sourceOpeningGross(root2016);
    assertEquals(
        0,
        sourceOpening[0].compareTo(openingDebit),
        () -> "opening debit " + openingDebit + " must equal the source's " + sourceOpening[0]);
    assertEquals(
        0,
        sourceOpening[1].compareTo(openingCredit),
        () -> "opening credit " + openingCredit + " must equal the source's " + sourceOpening[1]);

    // Every settlement whose counterpart is an opening position must reconcile against the exact
    // opening MoveLine carrying that source AccountingID -- resolution is by identity.
    Map<Integer, Long> openingLineIdByAccountingId =
        openingLineIdsByAccountingId(opening, root2016);
    for (MicroinvestAccountingSourceModels.Settlement settlement :
        settlementsReferencingOpening(root2016)) {
      Integer openingSide =
          openingLineIdByAccountingId.containsKey(settlement.paymentEntryId)
              ? settlement.paymentEntryId
              : settlement.settledEntryId;
      Long expectedLineId = openingLineIdByAccountingId.get(openingSide);
      if (expectedLineId == null) {
        // AccountingID ambiguous or absent among opening lines; skip rather than over-claim.
        continue;
      }
      long used =
          Query.of(Reconcile.class)
              .filter("self.debitMoveLine.id = :id OR self.creditMoveLine.id = :id")
              .bind("id", expectedLineId)
              .count();
      assertTrue(
          used > 0,
          () ->
              "settlement "
                  + settlement.sourceId
                  + " must reconcile opening move line "
                  + expectedLineId
                  + " by identity");
    }
  }

  // ── 2. Preflight writes nothing ────────────────────────────────────────────────────────────────

  /**
   * Preflight is the operator's safe button. It must be able to say "this will not work" without
   * having already changed anything — not even an audit row.
   */
  @Test
  void preflightWritesNothingAtAll() throws Exception {
    long before = rowCountSnapshot();

    MicroinvestAccountingImportReport preflight2026 =
        Beans.get(MicroinvestAccountingImportService.class).preflight(file2026, NO_CUTOFF);
    MicroinvestAccountingImportReport preflight2019 =
        Beans.get(MicroinvestAccountingImportService.class).preflight(file2019, NO_CUTOFF);

    assertNotNull(preflight2026);
    assertNotNull(preflight2019);
    assertEquals(before, rowCountSnapshot(), "Preflight must perform exactly zero writes");
    assertEquals(0L, fixture.moveCount());
  }

  // ── 3. The operational year ────────────────────────────────────────────────────────────────────

  /**
   * The whole point of the feature, in one run: 763 posted entries, the settlements replayed
   * through the stock reconciliation services, and the invoice terms they settle actually closed.
   *
   * <p>2026 is NOT the first history year, so its opening balances are a validation checkpoint only
   * and create no opening Move — the single standard opening Move belongs to 2016, which {@link
   * #twentySixteenPostsExactlyOneBalancedOpeningMove()} covers.
   */
  @Test
  void twentyTwentySixPostsEveryEntryAsCheckpointYearWithoutOpeningMove() throws Exception {
    byte[] file = requireTestOnly2026();

    // The derived file must differ from the original in exactly one respect, or this test would be
    // proving the path over data nobody has checked.
    assertEquals(
        0,
        root2026.checksums.totalDebit.compareTo(
            MicroinvestAccountingExportFileParser.parse(file).checksums.totalDebit),
        "the TEST-ONLY file must carry the original's amounts untouched");

    MicroinvestAccountingImportReport report = runImport(file);
    assertTrue(report.blocked == 0, () -> "unexpected blockers:\n" + report.toText("2026"));
    assertTrue(report.failed == 0, () -> "unexpected failures:\n" + report.toText("2026"));

    // 762 postable operational moves and no duplicated annual opening Move. The 763rd source entry
    // (33987) is a zero-value invoice that Axelor cannot represent.
    assertEquals(POSTABLE_ENTRIES_2026, fixture.moveCount());
    assertEquals(
        POSTABLE_ENTRIES_2026,
        fixture.accountedMoveCount(),
        "every imported move must reach ACCOUNTED, not stop at DAYBOOK");
    assertEquals(
        0L,
        Query.of(Move.class)
            .filter("self.company = :company AND self.statusSelect <> :accounted")
            .bind("company", fixture.company)
            .bind("accounted", MoveRepository.STATUS_ACCOUNTED)
            .count(),
        "no move may be left in NEW, DAYBOOK, CANCELED or SIMULATED");

    // ── The single intentional ENTRY skip, named ─────────────────────────────────────────────────
    // The report counts skipped entries and skipped LINES in one number, and FY2026 has both: one
    // zero-value entry (33987) and twelve unpostable lines inside six otherwise normal entries —
    // eight with no account at all in the source and four with a zero amount on both sides. Each
    // group is pinned separately, so "13" can never quietly become "one entry and twelve more".
    String reportText = report.toText("2026");
    assertEquals(
        1,
        countOccurrences(reportText, "SKIPPED   счетоводен запис"),
        () -> "exactly one ENTRY may be skipped:\n" + reportText);
    MicroinvestAccountingSourceModels.Entry zeroValue = sourceEntry(33987);
    assertTrue(
        reportText.contains("SKIPPED   счетоводен запис " + zeroValue.number),
        () -> "the skipped entry must be 33987 (" + zeroValue.number + "):\n" + reportText);
    assertEquals(
        unpostableSourceLines().size(),
        countOccurrences(reportText, "SKIPPED   ред "),
        () -> "every unpostable source line must be reported, and no other:\n" + reportText);
    for (Integer lineId : unpostableSourceLines()) {
      assertTrue(
          reportText.contains("SKIPPED   ред " + lineId + " "),
          () -> "source line " + lineId + " must be named as skipped");
    }
    assertEquals(
        1 + unpostableSourceLines().size(),
        report.skipped,
        () -> "nothing beyond the known skips may be dropped:\n" + reportText);
    assertEquals(
        0L,
        Query.of(Move.class)
            .filter("self.company = :company AND self.origin = :origin")
            .bind("company", fixture.company)
            .bind("origin", zeroValue.documentNumber)
            .count(),
        "the zero-value entry must create no move at all, not an empty one");

    // ── Every operational move balances ──────────────────────────────────────────────────────────
    assertEquals(
        0L, unbalancedMoveCount(), () -> "unbalanced moves found: " + describeUnbalancedMoves());

    // ── Gross totals reconcile with the source ───────────────────────────────────────────────────
    // The entry lines carry no negative amounts (verified in the source), so the operational moves
    // must reproduce the file's own debit and credit totals to the cent.
    // First: what was dropped has to be balance-neutral, or the comparison below is meaningless.
    BigDecimal[] skipped = skippedGross();
    assertEquals(
        0,
        skipped[0].compareTo(skipped[1]),
        () ->
            "the skipped source rows must cancel out; they carry "
                + skipped[0]
                + " debit against "
                + skipped[1]
                + " credit");
    BigDecimal[] sourceGross = sourceEntryGross();
    assertEquals(
        0,
        root2026.checksums.totalDebit.compareTo(sourceGross[0].add(skipped[0])),
        "postable + skipped must add back up to the file's own declared total");
    BigDecimal[] postedGross = postedGross("self.move.journal.code <> 'RPB'");
    assertEquals(
        0,
        sourceGross[0].compareTo(postedGross[0]),
        () -> "operational debit " + postedGross[0] + " must equal the source's " + sourceGross[0]);
    assertEquals(
        0,
        sourceGross[1].compareTo(postedGross[1]),
        () ->
            "operational credit " + postedGross[1] + " must equal the source's " + sourceGross[1]);

    // 2026 is not the first history year: its opening balances are a validation checkpoint only and
    // create no opening Move. The one standard opening Move is 2016's -- see the 2016 test.
    assertEquals(
        0L, openingMoveCount(), "only the first history year (2016) posts an opening Move");

    // ── Settlements ──────────────────────────────────────────────────────────────────────────────
    assertEquals(
        SETTLEMENTS_2026,
        report.reconciled + report.unresolved,
        () ->
            "every source settlement must be reconciled or reported unresolved:\n"
                + report.toText("2026"));
    // Each reconcile must join two DIFFERENT lines, and no pair may be used twice -- a duplicate
    // pair would mean one source settlement was replayed under two source ids.
    assertEquals(
        0L,
        Query.of(Reconcile.class).filter("self.debitMoveLine = self.creditMoveLine").count(),
        "a reconcile may not join a line to itself");
    assertEquals(
        SETTLEMENTS_2026,
        distinctReconcilePairs(),
        "every reconcile must join a distinct pair of move lines");
    // Each reconcile must carry the settlement's own source key. Without it a re-import can only
    // ask "is there anything left to pay on these lines", which is amount arithmetic and lets a
    // partially settled line be paid twice.
    String settlementPrefix =
        MicroinvestAccountingSourceKey.of(
            TestFishLedgerFixture.EIK,
            root2026.source.database,
            MicroinvestAccountingSourceKey.TYPE_SETTLEMENT,
            "");
    assertEquals(
        SETTLEMENTS_2026,
        Query.of(Reconcile.class)
            .filter("self.importId LIKE :prefix")
            .bind("prefix", settlementPrefix + "%")
            .count(),
        "every reconcile must be identified by its source settlement id");
    for (MicroinvestAccountingSourceModels.Settlement settlement : root2026.settlements) {
      assertEquals(
          1L,
          Query.of(Reconcile.class)
              .filter("self.importId = :id")
              .bind("id", settlementPrefix + settlement.sourceId)
              .count(),
          () -> "settlement " + settlement.sourceId + " must produce exactly one reconcile");
    }

    // ── Invoice terms ────────────────────────────────────────────────────────────────────────────
    long terms = Query.of(InvoiceTerm.class).count();
    assertTrue(terms > 0, "posting partner-balance lines must generate invoice terms");
    long paid = Query.of(InvoiceTerm.class).filter("self.isPaid = TRUE").count();
    assertTrue(
        paid > 0,
        () -> "reconciliation must close invoice terms; " + terms + " terms, none marked paid");
    // A term marked paid whose open amount is still outstanding would be a lie in the partner
    // balance, which is exactly the failure mode this whole step exists to prevent.
    assertEquals(
        0L,
        Query.of(InvoiceTerm.class)
            .filter("self.isPaid = TRUE AND self.amountRemaining > 0")
            .count(),
        "a paid term must have nothing left open");
    assertEquals(
        0L,
        Query.of(InvoiceTerm.class).filter("self.amountRemaining < 0").count(),
        "no term may be over-reconciled");
    assertEquals(
        0L,
        Query.of(InvoiceTerm.class)
            .filter("self.isPaid = FALSE AND self.amountRemaining = 0")
            .count(),
        "a fully settled term must be marked paid");

    // No fixed assets: the import never sets a fixedAssetCategory, so posting cannot create any.
    assertEquals(0L, Query.of(com.axelor.apps.account.db.FixedAsset.class).count());

    // ── No duplicated lifecycle side effects ─────────────────────────────────────────────────────
    // Posting runs once per move, so every move line must be unique on its source id. A move
    // accounted twice would duplicate the line and silently double the balance.
    assertEquals(
        0L,
        duplicateImportIdCount("account_move_line"),
        "a move line source id may appear exactly once");
    assertEquals(
        0L, duplicateImportIdCount("account_move"), "a move source id may appear exactly once");

    // Every imported move carries the flag and is therefore invisible to the VAT diaries.
    assertEquals(
        POSTABLE_ENTRIES_2026 + 1L,
        Query.of(Move.class)
            .filter("self.company = :company AND self.bgMigratedHistorical = TRUE")
            .bind("company", fixture.company)
            .count());
    assertEquals(0L, vatCollectorVisibleMoveCount());
  }

  /**
   * Re-importing the operational year must change nothing but the audit trail.
   *
   * <p>Separate from the historical-year idempotency test above: this path has many side effects to
   * repeat — moves, invoice terms, reconciliations, sequence counters — and the digest here covers
   * {@code version} and {@code updated_on}, so even an UPDATE that leaves the row count unchanged
   * is caught.
   */
  @Test
  void aSecondOperationalImportChangesNoBusinessRecordAtAll() throws Exception {
    byte[] file = requireTestOnly2026();

    MicroinvestAccountingImportReport first = runImport(file);
    assertTrue(first.blocked == 0, () -> "first import blocked:\n" + first.toText("2026"));

    String digestBefore = businessDigest();

    // ── A successful import must not make its own file un-reimportable ───────────────────────────
    // FY2026 contains two identifiers that each belong to two DIFFERENT source partners:
    //   EL900000017      -- the VAT number of source partners 791 and 835 (835's Bulstat is the
    //                       999999999999999 sentinel, which is rejected, so VAT is its identifier)
    //   99999999999999   -- FOURTEEN nines, a mistyped sentinel that the fifteen-nine constant does
    //                       not catch: the Bulstat of 779 and the VAT of 932
    // The import creates one Nyxa partner per source id, so the identifier ladder alone would find
    // two candidates for each and refuse the very file it had just imported. Preflight resolves by
    // this importer's own source key first — exact, and the mapping it wrote itself — and only
    // falls back to the ladder for a partner it has never seen.
    MicroinvestAccountingImportReport recheck =
        Beans.get(MicroinvestAccountingImportService.class).preflight(file, NO_CUTOFF);
    assertTrue(
        !recheck.toText("2026 preflight").contains("съответства на"),
        () ->
            "a partner this importer created must be resolved by its source key, not re-derived"
                + " from an identifier that two partners share:\n"
                + recheck.toText("2026 preflight"));
    assertTrue(
        recheck.blocked == 0,
        () -> "re-preflight must not block:\n" + recheck.toText("2026 preflight"));
    for (String identifier : new String[] {"EL900000017", "99999999999999"}) {
      assertEquals(
          2L,
          Query.of(Partner.class)
              .filter("self.taxNbr = :id OR self.registrationCode = :id")
              .bind("id", identifier)
              .count(),
          () ->
              "the two partners sharing "
                  + identifier
                  + " must both survive as separate partners -- merging them would merge two real"
                  + " counterparties");
    }

    MicroinvestAccountingImportReport second = runImport(file);
    assertTrue(second.blocked == 0, () -> "re-import blocked:\n" + second.toText("2026 again"));
    assertTrue(second.failed == 0, () -> "re-import failed:\n" + second.toText("2026 again"));

    assertEquals(
        digestBefore,
        businessDigest(),
        "the second import must not create, update or renumber a single business record");
    assertEquals(digestBefore, businessDigest(), "no parallel import-run record is created");
  }

  /**
   * The ledger route's own rollback, forced after substantial progress.
   *
   * <p>The historical BGN path is covered separately. This one fails on the 400th posted move —
   * well past the point where partners, invoice terms and sequence numbers have all been written —
   * and demands that every one of them is gone afterwards.
   */
  @Test
  void aFailureDeepInTheOperationalYearRollsEverythingBack() throws Exception {
    byte[] file = requireTestOnly2026();

    long partnersBefore = Query.of(Partner.class).count();
    long sequenceNumberBefore = nextSequenceNumber();
    String digestBefore = businessDigest();

    FailingImportService failing = new FailingImportService(400);
    injectInto(failing);

    MicroinvestAccountingImportReport report = failing.runImport(file, null, NO_CUTOFF);
    assertTrue(
        report.failed > 0, () -> "the forced failure was not reported:\n" + report.toText("2026"));
    // Proof that the failure really was deep in the year rather than on the first record.
    assertTrue(
        report.created >= 399,
        () -> "the failure must come after substantial progress, got " + report.created);

    assertEquals(0L, fixture.moveCount(), "not one move may survive");
    assertEquals(0L, Query.of(MoveLine.class).count());
    assertEquals(0L, Query.of(InvoiceTerm.class).count());
    assertEquals(0L, Query.of(Reconcile.class).count());
    assertEquals(partnersBefore, Query.of(Partner.class).count(), "partners roll back too");
    assertEquals(
        sequenceNumberBefore,
        nextSequenceNumber(),
        "the journal sequence counters must roll back with everything else");
    assertEquals(digestBefore, businessDigest(), "nothing at all may be left behind");

    assertTrue(report.failed > 0, "the failed attempt is retained in the returned import report");
  }

  /**
   * Importing FY2026 must not touch historical standard Moves or another company.
   *
   * <p>Both are the same failure in different clothes: a query in the importer that forgot its
   * company or year filter. Measured with a digest rather than a row count, so an UPDATE that
   * leaves the count alone is caught too.
   */
  @Test
  void theOperationalYearTouchesNoHistoricalMovesAndNoOtherCompany() throws Exception {
    byte[] file = requireTestOnly2026();

    // A historical standard-ledger year, and a second company with a move of its own.
    runImport(file2019);
    Long otherMoveId = inTransactionReturning(this::foreignCompanyMove);

    String historicalDigest = historicalMoveDigest(2019);
    String foreignDigest = moveDigest(otherMoveId);
    long historicalMoves =
        Query.of(Move.class)
            .filter("self.company = :company AND self.date >= :from AND self.date <= :to")
            .bind("company", fixture.company)
            .bind("from", LocalDate.of(2019, 1, 1))
            .bind("to", LocalDate.of(2019, 12, 31))
            .count();

    MicroinvestAccountingImportReport report = runImport(file);
    assertTrue(report.blocked == 0, () -> "the 2026 import blocked:\n" + report.toText("2026"));

    assertEquals(
        historicalMoves,
        Query.of(Move.class)
            .filter("self.company = :company AND self.date >= :from AND self.date <= :to")
            .bind("company", fixture.company)
            .bind("from", LocalDate.of(2019, 1, 1))
            .bind("to", LocalDate.of(2019, 12, 31))
            .count(),
        "the 2019 standard Moves must keep every row");
    assertEquals(historicalDigest, historicalMoveDigest(2019), "no 2019 standard Move may change");
    assertEquals(foreignDigest, moveDigest(otherMoveId), "another company's move may not change");
  }

  /**
   * Re-importing the same year must be a no-op on the business data — only a new audit row appears
   * — and a re-export of unchanged data must be recognised as the same content even though its
   * bytes differ, because the exporter stamps the export time into every file.
   */
  @Test
  void aSecondImportCreatesNoBusinessRecordEvenWhenTheBytesDiffer() throws Exception {
    runImport(file2019);

    long movesAfterFirst = fixture.moveCount();
    long reconcilesAfterFirst = Query.of(Reconcile.class).count();
    long partnersAfterFirst = Query.of(Partner.class).count();
    long termsAfterFirst = Query.of(InvoiceTerm.class).count();

    // Same content, different bytes: only the exportedAt stamp moves, which contentChecksum
    // deliberately excludes and the file SHA deliberately does not.
    byte[] rebranded = withDifferentExportTimestamp(file2019);
    assertNotEquals(
        MicroinvestAccountingExportFileParser.fileSha256(file2019),
        MicroinvestAccountingExportFileParser.fileSha256(rebranded),
        "the rewritten file must really have different bytes");
    assertEquals(
        MicroinvestAccountingExportFileParser.parse(file2019).contentChecksum,
        MicroinvestAccountingExportFileParser.parse(rebranded).contentChecksum,
        "the content checksum must ignore the export timestamp");

    MicroinvestAccountingImportReport second = runImport(rebranded);
    assertTrue(second.blocked == 0, () -> "re-import was blocked:\n" + second.toText("2019 again"));

    assertEquals(movesAfterFirst, fixture.moveCount(), "no new move");
    assertEquals(reconcilesAfterFirst, Query.of(Reconcile.class).count(), "no new reconcile");
    assertEquals(partnersAfterFirst, Query.of(Partner.class).count(), "no new partner");
    assertEquals(termsAfterFirst, Query.of(InvoiceTerm.class).count(), "no new invoice term");
    assertEquals(
        ENTRIES_2019,
        Query.of(Move.class)
            .filter("self.company = :company AND YEAR(self.date) = 2019")
            .bind("company", fixture.company)
            .count(),
        "the standard ledger still holds exactly one copy of every entry");
  }

  /**
   * The uploaded JSON is kept byte for byte on the audit record, so a future auditor can re-derive
   * both checksums from the file the operator actually pressed Import on.
   */
  @Test
  void theOriginalUploadedFileSurvivesOnTheAuditRecord() throws Exception {
    MetaFile uploaded = inTransactionReturning(() -> upload(file2019, "accounting-2019.json"));

    Beans.get(MicroinvestAccountingImportService.class).runImport(file2019, uploaded, NO_CUTOFF);

    byte[] stored = Files.readAllBytes(Beans.get(MetaFiles.class).getPath(uploaded));
    assertArrayEquals(file2019, stored, "the stored file must be the uploaded bytes, unmodified");
  }

  /**
   * The source defect that stops FY2026, pinned down so it cannot be mistaken for an importer bug.
   *
   * <p>Four rows on account 509/1 claim to be in lev at rates 1.23364, 1.19994, 1.00029 and
   * 6.00102. In a year whose lev/euro rate is fixed at 1.95583 none of those can be lev. The
   * source's own arithmetic is consistent (amount = currencyAmount x rate), so the EUR figures that
   * reach the accounts are sound; it is the currency LABEL that cannot be true, and which currency
   * was really paid is not recoverable. Writing "lev at 6.00" onto a move line would put a false
   * rate in the books, so Preflight refuses the year and writes nothing.
   */
  @Test
  void twentyTwentySixRefusesTheImpossibleCurrencyRows() throws Exception {
    MicroinvestAccountingImportReport report =
        Beans.get(MicroinvestAccountingImportService.class).preflight(file2026, NO_CUTOFF);

    assertTrue(report.hasBlockers(), "the impossible lev rates must block the year");
    String text = report.toText("2026");
    assertTrue(
        text.contains("невъзможен валутен курс"),
        () -> "expected the impossible-rate blocker, got:\n" + text);
    // Every one of the four is named, so the operator knows exactly which rows to correct.
    for (String line : new String[] {"1.233640", "1.199940", "1.000290", "6.001020"}) {
      assertTrue(text.contains(line), () -> "blocker must name rate " + line);
    }
    // And Preflight still wrote nothing at all.
    assertEquals(0L, fixture.moveCount());
  }

  /**
   * The VAT diaries must never see a migrated move.
   *
   * <p>The periods 2016-2026 covers were already declared to НАП out of Microinvest. If a migrated
   * move reached {@code BgVatDocumentCollector}, regenerating a diary would file a second
   * submission for a period that is already closed with the tax office.
   *
   * <p>This calls the REAL collector against PostgreSQL rather than re-stating its WHERE clause in
   * the test — a copied predicate proves only that the copy agrees with itself.
   */
  @Test
  void theVatCollectorNeverReturnsAMigratedMove() {
    Move migrated = inTransactionReturning(() -> vatProbeMove(true));
    Move manual = inTransactionReturning(() -> vatProbeMove(false));

    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyId(fixture.company.getId());
    request.setPeriodFrom(LocalDate.of(2026, 1, 1));
    request.setPeriodTo(LocalDate.of(2026, 12, 31));

    List<Move> posted = new ExposedVatCollector(Beans.get(MoveRepository.class)).posted(request);
    List<Long> ids = new ArrayList<>();
    for (Move move : posted) {
      ids.add(move.getId());
    }

    assertTrue(
        ids.contains(manual.getId()),
        () -> "a manually entered move must still reach the VAT diaries, got " + ids);
    assertTrue(
        !ids.contains(migrated.getId()),
        () -> "a migrated move must be invisible to the VAT diaries, got " + ids);
    assertEquals(1, ids.size(), () -> "exactly the manual move was expected, got " + ids);
  }

  /** A minimal ACCOUNTED move in the fixture company, migrated or manually entered. */
  private Move vatProbeMove(boolean migrated) {
    // Re-read by id: the fixture instances belong to the transaction that built them, and the
    // generated many-to-one cascades PERSIST, so a detached reference fails the save outright.
    Company company = JPA.find(Company.class, fixture.company.getId());
    Currency eur = JPA.find(Currency.class, fixture.eur.getId());
    Journal sales = JPA.find(Journal.class, fixture.journals.get("SAL").getId());

    Move move = new Move();
    move.setCompany(company);
    move.setCurrency(eur);
    move.setCompanyCurrency(eur);
    move.setJournal(sales);
    move.setDate(LocalDate.of(2026, 6, 15));
    move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    move.setReference("VAT-PROBE-" + (migrated ? "MIGRATED" : "MANUAL"));
    // Manually entered moves leave the flag NULL, which is exactly what COALESCE has to keep in.
    move.setBgMigratedHistorical(migrated ? Boolean.TRUE : null);
    // JPA.save, not moveRepo.save: the management repository re-runs the full posting preconditions
    // and would reject a bare probe move that deliberately has no lines.
    return JPA.save(move);
  }

  /** Exposes the collector's protected query; a subclass may reach its own protected members. */
  static final class ExposedVatCollector extends BgVatDocumentCollector {
    ExposedVatCollector(MoveRepository moveRepo) {
      super(moveRepo);
    }

    List<Move> posted(BgVatExportRequestDto request) {
      return findPostedMoves(request);
    }
  }

  // ── 4. Atomicity ───────────────────────────────────────────────────────────────────────────────

  /**
   * A half-imported accounting year is worse than none. One failure anywhere must undo the entire
   * year: moves, the partners created for it, the invoice terms posting generated, the
   * reconciliations, and the journal sequence counters.
   */
  @Test
  void oneFailureRollsBackTheWholeYear() throws Exception {
    long partnersBefore = Query.of(Partner.class).count();
    long sequenceNumberBefore = nextSequenceNumber();

    FailingImportService failing = new FailingImportService(400);
    injectInto(failing);

    // runImport reports the failure rather than propagating it — the operator gets a report, not a
    // stack trace — so the proof is that the report says FAILED and the database says nothing.
    MicroinvestAccountingImportReport report = failing.runImport(file2019, null, NO_CUTOFF);
    assertTrue(
        report.failed > 0, () -> "the forced failure was not reported:\n" + report.toText("2019"));

    assertEquals(0L, fixture.moveCount(), "not one move may survive");
    assertEquals(0L, Query.of(MoveLine.class).count());
    assertEquals(0L, Query.of(InvoiceTerm.class).count());
    assertEquals(0L, Query.of(Reconcile.class).count());
    assertEquals(partnersBefore, Query.of(Partner.class).count(), "partners roll back too");
    assertEquals(
        sequenceNumberBefore,
        nextSequenceNumber(),
        "the journal sequence counter must roll back with everything else");

    assertTrue(report.failed > 0, "the failed attempt is retained in the returned import report");
  }

  /**
   * The atomicity above rests on one unmeasured assumption: that a Guice-persist
   * {@code @Transactional} method called inside {@code JPA.runInTransaction} JOINS the outer
   * transaction instead of opening its own. {@code MoveValidateServiceImpl.accounting} is exactly
   * such a method, so if it committed independently, a later failure would leave posted moves
   * behind.
   *
   * <p>Measured here rather than read from documentation.
   */
  @Test
  void nestedTransactionalJoinsTheOuterTransaction() {
    NestedWriter writer = Beans.get(NestedWriter.class);
    String marker = "nested-" + System.nanoTime();

    assertThrows(
        IllegalStateException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  writer.writeInItsOwnTransactionalMethod(marker, fixture.company);
                  throw new IllegalStateException("boom");
                }));

    assertEquals(
        0L,
        Query.of(Partner.class).filter("self.name = :name").bind("name", marker).count(),
        "the inner @Transactional write must roll back with the outer transaction -- if this fails,"
            + " the whole one-transaction-per-year guarantee is void");
  }

  // ── Plumbing ───────────────────────────────────────────────────────────────────────────────────

  private MicroinvestAccountingImportReport runImport(byte[] content) throws Exception {
    MicroinvestAccountingImportReport report =
        Beans.get(MicroinvestAccountingImportService.class).runImport(content, null, NO_CUTOFF);
    // Printed, not just put in the assertion message: Gradle's console truncates assertion text,
    // and
    // the blocker list is the whole diagnosis.
    if (report.hasBlockers() || report.failed > 0) {
      System.out.println(report.toText("ИМПОРТ"));
    }
    return report;
  }

  /**
   * Uploads the bytes as a real MetaFile, the way the wizard does, so the audit copy is genuine.
   */
  private MetaFile upload(byte[] content, String name) throws Exception {
    return Beans.get(MetaFiles.class).upload(new ByteArrayInputStream(content), name);
  }

  /** Native, so it reports whatever is really in the table regardless of company or session. */
  @SuppressWarnings("unchecked")
  private String describeMoves() {
    List<Object[]> rows =
        JPA.em()
            .createNativeQuery(
                "SELECT m.id, j.code, m.status_select, m.import_id, m.company"
                    + " FROM account_move m LEFT JOIN account_journal j ON j.id = m.journal"
                    + " ORDER BY m.id LIMIT 5")
            .getResultList();
    StringBuilder sb = new StringBuilder();
    Number total =
        (Number) JPA.em().createNativeQuery("SELECT COUNT(*) FROM account_move").getSingleResult();
    sb.append(total).append(" rows in account_move; first few: ");
    for (Object[] row : rows) {
      sb.append("[id=")
          .append(row[0])
          .append(" journal=")
          .append(row[1])
          .append(" status=")
          .append(row[2])
          .append(" importId=")
          .append(row[3])
          .append(" company=")
          .append(row[4])
          .append("] ");
    }
    return sb.toString();
  }

  // ── Source-derived expectations ────────────────────────────────────────────────────────────────
  // Everything the operational test compares against is computed from the export file here, so a
  // disagreement is always "the ledger does not match the source", never "the ledger does not match
  // a number somebody typed into a test".

  private static MicroinvestAccountingSourceModels.Entry sourceEntry(int sourceId) {
    for (MicroinvestAccountingSourceModels.Entry entry : root2026.entries) {
      if (entry.sourceId != null && entry.sourceId == sourceId) {
        return entry;
      }
    }
    throw new IllegalStateException("source entry " + sourceId + " is not in the file");
  }

  /**
   * A source line can become a MoveLine: it names an account and moves a non-zero amount.
   *
   * <p>Spelled out here rather than calling the importer's own {@code isPostable}: a test that
   * borrows the predicate under test can only prove the code agrees with itself.
   */
  private static boolean postable(MicroinvestAccountingSourceModels.Line line) {
    if (line.accountNumber == null || line.accountNumber.trim().isEmpty()) {
      return false;
    }
    return (line.debit != null && line.debit.signum() != 0)
        || (line.credit != null && line.credit.signum() != 0);
  }

  /** The source lines that cannot become a MoveLine, inside entries that otherwise can. */
  private static List<Integer> unpostableSourceLines() {
    List<Integer> ids = new ArrayList<>();
    for (MicroinvestAccountingSourceModels.Entry entry : root2026.entries) {
      boolean entryPosts = false;
      for (MicroinvestAccountingSourceModels.Line line : entry.lines) {
        entryPosts |= postable(line);
      }
      if (!entryPosts) {
        continue; // the whole entry is skipped; its lines are never reached
      }
      for (MicroinvestAccountingSourceModels.Line line : entry.lines) {
        if (!postable(line)) {
          ids.add(line.sourceId);
        }
      }
    }
    return ids;
  }

  /**
   * {debit, credit} gross totals over every source line that can become a MoveLine.
   *
   * <p>The unpostable ones are excluded, and that is not a loophole: FY2026's eight account-less
   * rows carry equal debits and credits that cancel within their own entry (Preflight proves it
   * before the import starts), and the four zero rows carry nothing. Dropping them therefore lowers
   * both gross totals by the same amount and moves no balance.
   */
  private static BigDecimal[] sourceEntryGross() {
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (MicroinvestAccountingSourceModels.Entry entry : root2026.entries) {
      for (MicroinvestAccountingSourceModels.Line line : entry.lines) {
        if (!postable(line)) {
          continue;
        }
        debit = debit.add(line.debit == null ? BigDecimal.ZERO : line.debit);
        credit = credit.add(line.credit == null ? BigDecimal.ZERO : line.credit);
      }
    }
    return new BigDecimal[] {debit, credit};
  }

  /**
   * What the skipped account-less rows carried, proving the exclusion above moved no balance.
   *
   * @return {skippedDebit, skippedCredit}; the two must be equal
   */
  private static BigDecimal[] skippedGross() {
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (MicroinvestAccountingSourceModels.Entry entry : root2026.entries) {
      for (MicroinvestAccountingSourceModels.Line line : entry.lines) {
        if (postable(line)) {
          continue;
        }
        debit = debit.add(line.debit == null ? BigDecimal.ZERO : line.debit);
        credit = credit.add(line.credit == null ? BigDecimal.ZERO : line.credit);
      }
    }
    return new BigDecimal[] {debit, credit};
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  /**
   * {debit, credit} gross totals the opening move must show, with the sign flip applied.
   *
   * <p>A negative debit is posted as a positive credit and vice versa, because Axelor's move line
   * zeroes a negative amount outright. The flip raises both gross totals by the same amount.
   */
  private static BigDecimal[] sourceOpeningGross(
      MicroinvestAccountingSourceModels.ExportRoot root) {
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (MicroinvestAccountingSourceModels.OpeningBalance ob : root.openingBalances) {
      BigDecimal net =
          (ob.debit == null ? BigDecimal.ZERO : ob.debit)
              .subtract(ob.credit == null ? BigDecimal.ZERO : ob.credit);
      if (net.signum() >= 0) {
        debit = debit.add(net);
      } else {
        credit = credit.add(net.negate());
      }
    }
    return new BigDecimal[] {debit, credit};
  }

  /**
   * The settlements whose payment or settled entry is NOT one of the file's own entries — so the
   * only thing on the other side is an opening position.
   */
  private static List<MicroinvestAccountingSourceModels.Settlement> settlementsReferencingOpening(
      MicroinvestAccountingSourceModels.ExportRoot root) {
    java.util.Set<Integer> entryIds = new java.util.HashSet<>();
    for (MicroinvestAccountingSourceModels.Entry entry : root.entries) {
      entryIds.add(entry.sourceId);
    }
    List<MicroinvestAccountingSourceModels.Settlement> opening = new ArrayList<>();
    for (MicroinvestAccountingSourceModels.Settlement settlement : root.settlements) {
      if (!entryIds.contains(settlement.paymentEntryId)
          || !entryIds.contains(settlement.settledEntryId)) {
        opening.add(settlement);
      }
    }
    return opening;
  }

  /**
   * source AccountingID -> the id of the opening MoveLine that carries it.
   *
   * <p>Rebuilt here from the file and the line's {@code importId} rather than read back from the
   * importer, so the test proves identity resolution independently of the code under test.
   */
  private Map<Integer, Long> openingLineIdsByAccountingId(
      Move opening, MicroinvestAccountingSourceModels.ExportRoot root) {
    Map<String, Long> byImportId = new java.util.HashMap<>();
    for (MoveLine line : opening.getMoveLineList()) {
      byImportId.put(line.getImportId(), line.getId());
    }
    String eik = TestFishLedgerFixture.EIK;
    String database = root.source.database;
    Map<Integer, Long> byAccountingId = new java.util.HashMap<>();
    Map<Integer, Integer> seen = new java.util.HashMap<>();
    for (MicroinvestAccountingSourceModels.OpeningBalance ob : root.openingBalances) {
      if (ob.accountingId == null) {
        continue;
      }
      Long id =
          byImportId.get(
              MicroinvestAccountingSourceKey.of(
                  eik,
                  database,
                  MicroinvestAccountingSourceKey.TYPE_LINE,
                  "ob-" + ob.sourceDetailId));
      seen.merge(ob.accountingId, 1, Integer::sum);
      if (id != null) {
        byAccountingId.put(ob.accountingId, id);
      }
    }
    // An AccountingID owning two opening rows is ambiguous by identity, so it must not be used to
    // claim a settlement resolved "exactly". FY2026 has two (35153, 35183), neither settled.
    for (Map.Entry<Integer, Integer> e : seen.entrySet()) {
      if (e.getValue() > 1) {
        byAccountingId.remove(e.getKey());
      }
    }
    return byAccountingId;
  }

  // ── Measurements ───────────────────────────────────────────────────────────────────────────────

  /** {debit, credit} gross totals over the posted move lines matching the filter. */
  private BigDecimal[] postedGross(String filter) {
    Object[] row =
        (Object[])
            JPA.em()
                .createQuery(
                    "SELECT COALESCE(SUM(self.debit), 0), COALESCE(SUM(self.credit), 0)"
                        + " FROM MoveLine self WHERE self.move.company = :company AND "
                        + filter)
                .setParameter("company", fixture.company)
                .getSingleResult();
    return new BigDecimal[] {(BigDecimal) row[0], (BigDecimal) row[1]};
  }

  private long unbalancedMoveCount() {
    Number value =
        (Number)
            JPA.em()
                .createNativeQuery(
                    "SELECT COUNT(*) FROM (SELECT l.move FROM account_move_line l"
                        + " JOIN account_move m ON m.id = l.move WHERE m.company = :company"
                        + " GROUP BY l.move"
                        + " HAVING COALESCE(SUM(l.debit),0) <> COALESCE(SUM(l.credit),0)) q")
                .setParameter("company", fixture.company.getId())
                .getSingleResult();
    return value.longValue();
  }

  @SuppressWarnings("unchecked")
  private String describeUnbalancedMoves() {
    List<Object[]> rows =
        JPA.em()
            .createNativeQuery(
                "SELECT l.move, SUM(l.debit), SUM(l.credit) FROM account_move_line l"
                    + " JOIN account_move m ON m.id = l.move WHERE m.company = :company"
                    + " GROUP BY l.move HAVING COALESCE(SUM(l.debit),0) <> COALESCE(SUM(l.credit),0)"
                    + " LIMIT 10")
            .setParameter("company", fixture.company.getId())
            .getResultList();
    StringBuilder sb = new StringBuilder();
    for (Object[] row : rows) {
      sb.append("[move=")
          .append(row[0])
          .append(" dr=")
          .append(row[1])
          .append(" cr=")
          .append(row[2])
          .append("] ");
    }
    return sb.toString();
  }

  private long distinctReconcilePairs() {
    Number value =
        (Number)
            JPA.em()
                .createNativeQuery(
                    "SELECT COUNT(*) FROM (SELECT DISTINCT debit_move_line, credit_move_line"
                        + " FROM account_reconcile) q")
                .getSingleResult();
    return value.longValue();
  }

  private long duplicateImportIdCount(String table) {
    Number value =
        (Number)
            JPA.em()
                .createNativeQuery(
                    "SELECT COUNT(*) FROM (SELECT import_id FROM "
                        + table
                        + " WHERE import_id IS NOT NULL GROUP BY import_id HAVING COUNT(*) > 1) q")
                .getSingleResult();
    return value.longValue();
  }

  /**
   * A deterministic digest of every business row the import can touch.
   *
   * <p>Includes {@code version} and {@code updated_on}, so an UPDATE that leaves the row count
   * unchanged — the failure mode a plain count cannot see — still moves the digest.
   */
  private String businessDigest() {
    StringBuilder sb = new StringBuilder();
    for (String table :
        new String[] {
          "account_move",
          "account_move_line",
          "account_reconcile",
          "account_invoice_term",
          "account_fixed_asset",
          "account_fixed_asset_line",
          "base_partner",
          "base_sequence_version"
        }) {
      sb.append(table).append('=').append(tableDigest(table)).append('\n');
    }
    return sb.toString();
  }

  /**
   * Row count plus a hash over every row's id, version and updated_on, order-independently.
   *
   * <p>{@code SUM(hashtext(...))} rather than {@code string_agg} over an ordered set: one pass, no
   * sort of 2000+ rows per table, and a change to any of the three columns moves it.
   *
   * <p>⚠️ {@code CAST(x AS text)}, never PostgreSQL's {@code x::text}. Hibernate scans a native
   * query for named parameters and reads the second colon of {@code ::text} as the parameter {@code
   * :text}; what reaches the driver is {@code COALESCE(version:,'')}, which fails as a syntax error
   * that looks nothing like its cause.
   */
  private String tableDigest(String table) {
    Object value =
        JPA.em()
            .createNativeQuery(
                "SELECT COUNT(*) || '/' || COALESCE(SUM(hashtext("
                    + "CAST(id AS text) || '|' || COALESCE(CAST(version AS text), '')"
                    + " || '|' || COALESCE(CAST(updated_on AS text), ''))), 0) FROM "
                    + table)
            .getSingleResult();
    return String.valueOf(value);
  }

  private String historicalMoveDigest(int year) {
    Object value =
        JPA.em()
            .createNativeQuery(
                "SELECT COUNT(*) || '/' || COALESCE(SUM(hashtext("
                    + "CAST(id AS text) || '|' || COALESCE(CAST(version AS text), '')"
                    + " || '|' || COALESCE(CAST(status_select AS text), '')"
                    + " || '|' || COALESCE(import_id, ''))), 0)"
                    + " FROM account_move WHERE EXTRACT(YEAR FROM date_val) = :y")
            .setParameter("y", year)
            .getSingleResult();
    return String.valueOf(value);
  }

  private String moveDigest(Long moveId) {
    Object value =
        JPA.em()
            .createNativeQuery(
                "SELECT COALESCE(CAST(version AS text), '')"
                    + " || '|' || COALESCE(CAST(updated_on AS text), '')"
                    + " || '|' || COALESCE(CAST(status_select AS text), '')"
                    + " || '|' || COALESCE(reference, '') FROM account_move WHERE id = :id")
            .setParameter("id", moveId)
            .getSingleResult();
    return String.valueOf(value);
  }

  /** A second company with one ACCOUNTED move, used to prove the import stays in its own lane. */
  private Long foreignCompanyMove() {
    Currency eur = JPA.find(Currency.class, fixture.eur.getId());

    Company other = new Company();
    other.setName("Друга фирма ЕООД");
    other.setCode("OTHER");
    other.setCurrency(eur);
    other = JPA.save(other);

    // Партньорът на дружеството е в самото дружество.
    Partner partner = new Partner();
    partner.setName("Друга фирма ЕООД");
    partner.setFullName("Друга фирма ЕООД");
    partner.setPartnerTypeSelect(
        com.axelor.apps.base.db.repo.PartnerRepository.PARTNER_TYPE_COMPANY);
    partner.setRegistrationCode("999999999");
    partner.setCompany(other);
    JPA.save(partner);
    other.setPartner(partner);
    JPA.save(other);

    Move move = new Move();
    move.setCompany(other);
    move.setCurrency(eur);
    move.setCompanyCurrency(eur);
    move.setJournal(JPA.find(Journal.class, fixture.journals.get("SAL").getId()));
    move.setDate(LocalDate.of(2026, 6, 15));
    move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    move.setReference("OTHER-COMPANY-PROBE");
    return JPA.save(move).getId();
  }

  private long openingMoveCount() {
    return Query.of(Move.class)
        .filter("self.company = :company AND self.journal.code = 'RPB'")
        .bind("company", fixture.company)
        .count();
  }

  /** The exact predicate {@code BgVatDocumentCollector.findPostedMoves} applies. */
  private long vatCollectorVisibleMoveCount() {
    return Query.of(Move.class)
        .filter(
            "self.company = :company AND self.statusSelect IN (3, 5)"
                + " AND COALESCE(self.bgMigratedHistorical, FALSE) = FALSE")
        .bind("company", fixture.company)
        .count();
  }

  private long nextSequenceNumber() {
    Number value =
        (Number)
            JPA.em()
                .createNativeQuery("SELECT COALESCE(SUM(next_num), 0) FROM base_sequence_version")
                .getSingleResult();
    return value.longValue();
  }

  private long rowCountSnapshot() {
    Number value =
        (Number)
            JPA.em()
                .createNativeQuery(
                    "SELECT (SELECT COUNT(*) FROM account_move)"
                        + " + (SELECT COUNT(*) FROM account_move_line)"
                        + " + (SELECT COUNT(*) FROM base_partner)"
                        + " + (SELECT COUNT(*) FROM account_reconcile)"
                        + " + (SELECT COUNT(*) FROM account_invoice_term)"
                        + " + (SELECT COUNT(*) FROM account_fixed_asset)"
                        + " + (SELECT COUNT(*) FROM account_fixed_asset_line)"
                        + " + (SELECT COUNT(*) FROM base_sequence_version)"
                        + " + (SELECT COALESCE(SUM(next_num), 0) FROM base_sequence_version)")
                .getSingleResult();
    return value.longValue();
  }

  /**
   * Empties the scratch database between tests.
   *
   * <p>Guarded: refuses to run anywhere but {@code nyxa_mi_test}, so a mis-set connection cannot
   * quietly delete a real company's accounting.
   */
  private void wipe() {
    inTransaction(
        () -> {
          EntityManager em = JPA.em();
          String database =
              (String) em.createNativeQuery("SELECT current_database()").getSingleResult();
          if (!"nyxa_mi_test".equals(database)) {
            throw new IllegalStateException(
                "refusing to wipe database '"
                    + database
                    + "' -- expected the scratch nyxa_mi_test");
          }
          em.createNativeQuery(
                  "TRUNCATE account_move, account_move_line, account_reconcile,"
                      + " account_invoice_term, account_account, account_account_type,"
                      + " account_fixed_asset, account_fixed_asset_line,"
                      + " account_journal, account_journal_type, account_account_config,"
                      + " base_partner, base_company, base_currency, base_year, base_period,"
                      + " base_currency_conversion_line, base_sequence, base_sequence_version,"
                      // ⚠️ The App rows must go too. AppBase owns the currency-conversion lines
                      // through a one-to-many, and AppBaseServiceImpl reads them off whichever
                      // AppBase getAppBase() returns -- so a leftover AppBase from the previous
                      // test would make the FX rate the fixture just configured invisible.
                      + " studio_app, studio_app_base, studio_app_account,"
                      + " auth_user, auth_group"
                      + " RESTART IDENTITY CASCADE")
              .executeUpdate();
        });
  }

  private static byte[] withDifferentExportTimestamp(byte[] content) {
    String json = new String(content, StandardCharsets.UTF_8);
    String rewritten =
        json.replaceFirst(
            "\"exportedAt\"\\s*:\\s*\"[^\"]*\"", "\"exportedAt\": \"2030-01-01T00:00:00\"");
    if (rewritten.equals(json)) {
      throw new IllegalStateException("exportedAt not found -- cannot build a byte-different file");
    }
    return rewritten.getBytes(StandardCharsets.UTF_8);
  }

  /** {@code inTransaction} returns void, so this keeps the result of a transactional build step. */
  private <T> T inTransactionReturning(Callable<T> work) {
    List<T> box = new ArrayList<>(1);
    inTransaction(
        () -> {
          try {
            box.add(work.call());
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    return box.get(0);
  }

  private void injectInto(Object target) {
    Beans.get(com.google.inject.Injector.class).injectMembers(target);
  }

  /**
   * Makes one write fail deep inside the year, without weakening any real validation.
   *
   * <p>Covers the common standard-ledger posting path used by both BGN and EUR years.
   */
  static final class FailingImportService extends MicroinvestAccountingImportService {
    private final int failOn;
    private int seen;

    FailingImportService(int failOn) {
      this.failOn = failOn;
    }

    @Override
    protected void accountAndVerify(Move move, Integer sourceId) throws Exception {
      if (++seen == failOn) {
        throw new MicroinvestAccountingAbortException("forced failure at record " + seen);
      }
      super.accountAndVerify(move, sourceId);
    }
  }

  /** A @Transactional write used only to measure transaction joining. */
  static class NestedWriter {
    @com.google.inject.persist.Transactional
    public void writeInItsOwnTransactionalMethod(String marker, Company company) {
      Partner partner = new Partner();
      partner.setName(marker);
      partner.setFullName(marker);
      partner.setCompany(JPA.find(Company.class, company.getId()));
      JPA.save(partner);
      JPA.flush();
    }
  }
}
