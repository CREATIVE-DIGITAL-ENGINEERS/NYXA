package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.persistence.PersistenceException;
import org.hibernate.Session;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Schema-level claims for the standard-ledger importer: the old archive tables are absent, the
 * standard models carry the source fields, source keys stay globally unique, and migrated Moves
 * remain invisible to the VAT diaries.
 *
 * <p>Runs on the module's in-memory HSQLDB, never on the {@code nyxa} database.
 *
 * <p>⚠️ This class deliberately does NOT claim to prove the DAYBOOK to ACCOUNTED posting transition
 * or the settlement reconciliation. Posting a Move needs a complete accounting fixture (AppAccount,
 * AccountConfig, journal sequences, fiscal years, periods, account types) that this class does not
 * build, so it does not imply coverage of those two behaviours.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(MicroinvestAccountingTestModule.class)
@Tag("integration")
class MicroinvestAccountingPersistenceTest extends JpaSupport {

  private static final String DB = "DeltaPro_TEST";

  // ── Schema ─────────────────────────────────────────────────────────────────────────────────────

  /** No parallel archive schema is generated; source evidence lives on standard models. */
  @Test
  void removedArchiveTablesAreAbsentAndStandardColumnsExist() throws Exception {
    assertTableAbsent("BGLOCALIZATION_MICROINVEST_ARCHIVE_ENTRY");
    assertTableAbsent("BGLOCALIZATION_MICROINVEST_ARCHIVE_LINE");
    assertTableAbsent("BGLOCALIZATION_MICROINVEST_ARCHIVE_OPENING_BALANCE");
    assertTableAbsent("BGLOCALIZATION_MICROINVEST_ACCOUNTING_IMPORT_RUN");
    assertColumns("ACCOUNT_MOVE", Set.of("BG_BOOK_CURRENCY", "BG_MIGRATED_HISTORICAL"));
    assertColumns("ACCOUNT_MOVE_LINE", Set.of("BG_SOURCE_ACCOUNTING_ID"));
  }

  /** The Move flag that keeps migrated history out of the VAT diaries must be a real column. */
  @Test
  void moveCarriesTheMigratedHistoricalFlagColumn() throws Exception {
    assertColumns("ACCOUNT_MOVE", Set.of("BG_MIGRATED_HISTORICAL"));
  }

  // ── Composite identity ─────────────────────────────────────────────────────────────────────────

  /**
   * The source id alone is not unique. The standard Move importId includes company and source
   * database, and its inherited unique constraint enforces idempotency.
   */
  @Test
  void standardMoveIdentityIsCompanyPlusDatabasePlusSourceId() {
    Long companyId = createCompany("ARCH1");
    String first = MicroinvestAccountingSourceKey.of("ARCH1", DB, "acc", 4711);
    String otherDatabase =
        MicroinvestAccountingSourceKey.of("ARCH1", "DeltaPro_OTHER", "acc", 4711);
    JPA.runInTransaction(() -> saveBareAccountedMove(companyId, "FIRST", null, first));

    // Same source id, different database: allowed.
    JPA.runInTransaction(() -> saveBareAccountedMove(companyId, "OTHER", null, otherDatabase));

    // Same complete importId twice: refused by the standard model's database constraint.
    assertThrows(
        PersistenceException.class,
        () -> JPA.runInTransaction(() -> saveBareAccountedMove(companyId, "DUP", null, first)));
  }

  /**
   * An opening row is identified by its source DETAIL id, not by its balance record and account.
   *
   * <p>One {@code AccountInitialBalanceID} can emit several detail rows, and — this is the part
   * that rules out {@code (sourceId, accountNumber)} — two of them can sit on the SAME account,
   * because a balance record may carry a gross debit and a gross credit at once. Only {@code
   * AccountingDetailID} is unique, which is why the constraint is {@code (company, sourceDatabase,
   * sourceDetailId)} and why {@code openingLineImportId} keys the move line on {@code
   * ob-<sourceDetailId>}.
   */
  @Test
  void openingBalanceIdentityIsTheSourceDetailId() {
    MicroinvestAccountingSourceModels.OpeningBalance first =
        new MicroinvestAccountingSourceModels.OpeningBalance();
    first.sourceDetailId = 79880;
    MicroinvestAccountingSourceModels.OpeningBalance second =
        new MicroinvestAccountingSourceModels.OpeningBalance();
    second.sourceDetailId = 79881;
    String firstKey =
        MicroinvestAccountingSourceKey.of("ARCH2", DB, "accd", "ob-" + first.sourceDetailId);
    String secondKey =
        MicroinvestAccountingSourceKey.of("ARCH2", DB, "accd", "ob-" + second.sourceDetailId);
    assertNotEquals(firstKey, secondKey);
    assertEquals(firstKey, MicroinvestAccountingSourceKey.of("ARCH2", DB, "accd", "ob-79880"));
  }

  // ── Historical standard ledger ─────────────────────────────────────────────────────────────────

  /**
   * BGN history is represented by a standard Move/MoveLine while the exact source amount and the
   * book currency remain explicit.
   */
  @Test
  void bgnYearUsesAStandardMoveAndKeepsLevAmountsExact() {
    Currency bgn = new Currency();
    bgn.setCodeISO("BGN");
    Move move = new Move();
    move.setBgBookCurrency(bgn);
    move.setBgMigratedHistorical(true);
    move.setImportId(MicroinvestAccountingSourceKey.of("ARCH3", DB, "acc", 8801));
    MoveLine line = new MoveLine();
    line.setDebit(new BigDecimal("1234.56"));
    line.setCredit(BigDecimal.ZERO);
    line.setImportId(MicroinvestAccountingSourceKey.of("ARCH3", DB, "accd", 99001));
    move.addMoveLineListItem(line);

    assertEquals("BGN", move.getBgBookCurrency().getCodeISO());
    assertTrue(Boolean.TRUE.equals(move.getBgMigratedHistorical()));
    assertEquals(0, new BigDecimal("1234.56").compareTo(move.getMoveLineList().get(0).getDebit()));
  }

  // ── VAT diary exclusion ────────────────────────────────────────────────────────────────────────

  /**
   * The periods migrated history covers were already declared to НАП from Microinvest, so a
   * migrated Move must be invisible to the diary collector while a manually entered one — which
   * leaves the flag null — must still be collected.
   *
   * <p>⚠️ This asserts the exclusion PREDICATE against the real column, not the collector object.
   * {@code BgVatDocumentCollector.findPostedMoves} cannot be executed here: the module's existing
   * {@code Move.ocrUploadIds} formula field is PostgreSQL-only ({@code
   * array_to_string(array_agg(…))}) and HSQLDB rejects it with "unexpected token: SELECT", which
   * makes selecting the Move ENTITY impossible on the test database. That is a constraint of this
   * module's test setup. What is proven here is that {@code COALESCE(bg_migrated_historical, FALSE)
   * = FALSE} — the exact clause in the collector — keeps the manual row and drops the migrated one;
   * that the clause is spelled identically in the collector is verified by reading it.
   */
  @Test
  void theMigratedFlagPredicateKeepsManualMovesAndDropsMigratedOnes() {
    Long companyId = createCompany("VAT1");
    Long[] ids = new Long[2];
    JPA.runInTransaction(
        () -> {
          ids[0] = saveBareAccountedMove(companyId, "MANUAL", null).getId();
          ids[1] = saveBareAccountedMove(companyId, "MIGRATED", Boolean.TRUE).getId();
        });

    List<Long> selected = new ArrayList<>();
    Session session = JPA.em().unwrap(Session.class);
    session.doWork(
        (Connection connection) -> {
          String sql =
              "SELECT id FROM account_move"
                  + " WHERE status_select = ?"
                  + " AND company = ?"
                  + " AND date_val >= ? AND date_val <= ?"
                  + " AND COALESCE(bg_migrated_historical, FALSE) = FALSE";
          try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MoveRepository.STATUS_ACCOUNTED);
            statement.setLong(2, companyId);
            statement.setDate(3, java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)));
            statement.setDate(4, java.sql.Date.valueOf(LocalDate.of(2026, 12, 31)));
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                selected.add(rows.getLong(1));
              }
            }
          }
        });

    assertTrue(selected.contains(ids[0]), "a manually entered move must still reach the diary");
    assertTrue(
        !selected.contains(ids[1]),
        "a move migrated from Microinvest must never reach an already-declared VAT period");
  }

  // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

  private Long createCompany(String code) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          // Новото дружество получава партньора си при създаването, а PartnerServiceImpl
          // .onSave чете AppBase без проверка за null — празната тестова база иска реда. Проверката
          // за вече даден номер е SELECT на Partner, който HSQLDB не чете (формулата) — изключена.
          if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne()
              == null) {
            com.axelor.studio.db.AppBase appBase = new com.axelor.studio.db.AppBase();
            appBase.setCheckExistingSequenceOnGeneration(false);
            JPA.save(appBase);
          }
          Company company = new Company();
          company.setName("Тест " + code);
          company.setCode(code);
          id[0] = Beans.get(CompanyRepository.class).save(company).getId();
        });
    return id[0];
  }

  /**
   * A Move persisted directly in ACCOUNTED status.
   *
   * <p>⚠️ Saved with {@link JPA#save} rather than through {@code MoveRepository}: the managed
   * repository re-runs the full posting preconditions on save (journal, period, balanced lines) and
   * would reject a bare row. What is under test here is the collector's WHERE clause, so the row is
   * written straight to the table and the posting machinery is deliberately out of the picture.
   */
  private Move saveBareAccountedMove(Long companyId, String reference, Boolean migrated) {
    return saveBareAccountedMove(companyId, reference, migrated, null);
  }

  private Move saveBareAccountedMove(
      Long companyId, String reference, Boolean migrated, String importId) {
    Move move = new Move();
    move.setCompany(Beans.get(CompanyRepository.class).find(companyId));
    move.setDate(LocalDate.of(2026, 3, 15));
    move.setReference(reference);
    move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    move.setBgMigratedHistorical(migrated);
    move.setImportId(importId);
    return JPA.save(move);
  }

  private void assertTableAbsent(String table) throws Exception {
    Set<String> actual = new LinkedHashSet<>();
    Session session = JPA.em().unwrap(Session.class);
    session.doWork(
        (Connection connection) -> {
          DatabaseMetaData metaData = connection.getMetaData();
          try (ResultSet columns = metaData.getColumns(null, null, table, null)) {
            while (columns.next()) {
              actual.add(columns.getString("COLUMN_NAME"));
            }
          }
        });
    assertTrue(actual.isEmpty(), "removed archive table still exists: " + table);
  }

  private void assertColumns(String table, Set<String> expected) throws Exception {
    Set<String> actual = new LinkedHashSet<>();
    Session session = JPA.em().unwrap(Session.class);
    session.doWork(
        (Connection connection) -> {
          DatabaseMetaData metaData = connection.getMetaData();
          try (ResultSet columns = metaData.getColumns(null, null, table, null)) {
            while (columns.next()) {
              actual.add(columns.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
            }
          }
        });
    assertTrue(!actual.isEmpty(), "table " + table + " does not exist in the generated schema");
    for (String column : expected) {
      assertTrue(
          actual.contains(column), table + " is missing column " + column + "; has " + actual);
    }
  }
}
