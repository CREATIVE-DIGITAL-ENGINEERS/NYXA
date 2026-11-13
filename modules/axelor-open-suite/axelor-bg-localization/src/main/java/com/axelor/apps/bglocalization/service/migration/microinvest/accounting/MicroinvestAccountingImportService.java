package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.JournalRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.account.service.reconcile.ReconcileService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.db.MicroinvestSettlementAudit;
import com.axelor.apps.bglocalization.db.repo.MicroinvestSettlementAuditRepository;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Entry;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Line;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.OpeningBalance;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Settlement;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

/**
 * Imports one annual accounting export file.
 *
 * <p>Every year is written to the standard Axelor ledger. The file's financial-year base currency
 * becomes {@code Move.bgBookCurrency}; source debit/credit amounts are never redenominated.
 *
 * <p><b>Atomicity.</b> One transaction per annual file. This is where the accounting importer
 * deliberately departs from the payroll one, which commits each employee independently: an
 * accounting year is not divisible that way, and committing part of it leaves books nobody can
 * reconcile. Any failure throws {@link MicroinvestAccountingAbortException} and the whole year
 * rolls back. The report is built OUTSIDE the transaction so it survives the rollback, and the
 * reconciliations are attempted afterwards in isolated transactions so one unresolved source link
 * cannot roll back the valid annual Moves.
 */
@Singleton
public class MicroinvestAccountingImportService {

  /** Flush/clear stride. Large enough to be cheap, small enough that the context stays bounded. */
  private static final int FLUSH_EVERY = 200;

  @Inject protected MicroinvestAccountingPreflightService preflightService;
  @Inject protected AccountRepository accountRepo;
  @Inject protected JournalRepository journalRepo;
  @Inject protected MoveRepository moveRepo;
  @Inject protected PartnerRepository partnerRepo;
  @Inject protected com.axelor.apps.base.db.repo.CurrencyRepository currencyRepo;
  @Inject protected MoveCreateService moveCreateService;
  @Inject protected MoveLineCreateService moveLineCreateService;
  @Inject protected MoveValidateService moveValidateService;
  @Inject protected ReconcileService reconcileService;
  @Inject protected com.axelor.apps.account.db.repo.ReconcileRepository reconcileRepo;

  /** Read-only: validates and reports, writes nothing at all — not even an audit record. */
  public MicroinvestAccountingImportReport preflight(byte[] content, String lastFiledVatPeriod)
      throws IOException {
    return preflightResult(content, lastFiledVatPeriod).report;
  }

  /** The full Preflight outcome used by the import confirmation and the actual import. */
  public MicroinvestAccountingPreflightService.Result preflightResult(
      byte[] content, String lastFiledVatPeriod) throws IOException {
    return preflightService.preflight(content, lastFiledVatPeriod);
  }

  /**
   * Validates, imports the year's Moves atomically, then attempts each settlement independently.
   *
   * <p>⚠️ {@code lastFiledVatPeriod} е ЗАДЪЛЖИТЕЛЕН и се проверява в Preflight. Без валиден период
   * Preflight връща блокер и този метод излиза, преди да е отворена транзакция.
   */
  public MicroinvestAccountingImportReport runImport(
      byte[] content, MetaFile sourceFile, String lastFiledVatPeriod) throws IOException {
    MicroinvestAccountingPreflightService.Result result =
        preflightService.preflight(content, lastFiledVatPeriod);
    MicroinvestAccountingImportReport report = result.report;

    if (report.hasBlockers()) {
      report.note("Импортът е спрян от предпоставка. НИЩО не е записано.");
      return report;
    }

    boolean movesImported = false;
    try {
      JPA.runInTransaction(
          () -> {
            try (MicroinvestImportScope ignored = MicroinvestImportScope.open()) {
              importInTransaction(result);
            }
          });
      movesImported = true;
    } catch (MicroinvestAccountingAbortException e) {
      report.failed("финансова година " + result.root.financialYear.year, e.getMessage());
      report.note("Цялата година е върната назад. В базата няма нито един запис от този пробег.");
    } catch (RuntimeException e) {
      // Name the exception and its cause chain: a bare getMessage() on a Hibernate or Axelor
      // wrapper is very often null, which leaves the operator with "провалени: 1" and nothing else.
      report.failed("финансова година " + result.root.financialYear.year, describe(e));
      report.note("Цялата година е върната назад. В базата няма нито един запис от този пробег.");
    }

    if (movesImported) {
      importSettlements(result);
    }
    return report;
  }

  /**
   * Replays ONLY the settlements of a year whose Moves are already in the ledger.
   *
   * <p><b>Why this exists.</b> FY2016 imported its 394 Moves, its opening Move, its partners and
   * its assets, and then left 102 of 103 settlements unresolved on defects that have since been
   * fixed. The full Import is not the way back: it would run Preflight's duplicate-ledger guard,
   * then re-enter the annual write transaction. This entry point never opens that transaction at
   * all.
   *
   * <p><b>What it touches.</b> Nothing but {@code Reconcile}, the {@code ReconcileGroup} / {@code
   * MoveLine.amountPaid} state the stock reconciliation service maintains, and the settlement
   * audit. No Move, MoveLine, Partner, opening balance or FixedAsset is created, re-posted or
   * amended — {@link #importInTransaction} is not reachable from here.
   *
   * <p><b>Idempotency.</b> Every settlement is keyed by {@code mi:<eik>:<db>:setl:<sourceId>} and
   * skipped when a Reconcile already carries that key (bare, or suffixed for a 1→N allocation), so
   * a repeated run reconciles nothing twice — {@code setl:86} is reported as reused.
   */
  public MicroinvestAccountingImportReport retrySettlements(
      byte[] content, String lastFiledVatPeriod) throws IOException {
    MicroinvestAccountingPreflightService.Result result =
        preflightService.preflight(content, lastFiledVatPeriod);
    MicroinvestAccountingImportReport report = result.report;

    if (report.hasBlockers()) {
      report.note("Повторното равнение е спряно от предпоставка. НИЩО не е записано.");
      return report;
    }

    String prefix =
        MicroinvestAccountingSourceKey.prefix(result.companyEik, result.root.source.database);
    long moves =
        moveRepo
            .all()
            .filter("self.company = :company AND self.importId LIKE :prefix")
            .bind("company", result.company)
            .bind("prefix", prefix + "%")
            .count();
    if (moves == 0) {
      report.blocked(
          "повторно равнение",
          "Тази година още не е внесена за тази компания (няма нито един Move с ключ "
              + prefix
              + "). Повторното равнение равнява СЪЩЕСТВУВАЩИ записи и не създава нито един —"
              + " пуснете първо пълния Импорт.");
      return report;
    }

    report.note(
        "ПОВТОРНО РАВНЯВАНЕ на вече внесена година: намерени "
            + moves
            + " съществуващи Move записа с ключ "
            + prefix
            + ". Не се създават и не се променят Moves, MoveLines, партньори, начално салдо и"
            + " активи — пише се само в Reconcile/ReconcileGroup и в одита на равненията.");
    importSettlements(result);
    return report;
  }

  // ───────────────────────────────────────────────────────────────────────────────────────────────

  private void importInTransaction(MicroinvestAccountingPreflightService.Result result) {
    ExportRoot root = result.root;
    MicroinvestAccountingImportReport report = result.report;
    Company company = result.company;
    String eik = result.companyEik;
    String database = root.source.database;

    Map<Integer, Long> partnerIds = new HashMap<>(result.resolvedPartnerIds);
    createMissingPartners(root, result, partnerIds);

    // ⚠️ Re-read the company after EVERY step that may have cleared the persistence context. Each
    // step re-attaches its own copy, which does nothing for this one — and a stale instance here
    // kills the next step on its first save (the many-to-one cascades PERSIST).
    company = reattach(company);

    Map<String, Long> accountIds = new HashMap<>();
    Map<String, Long> journalIds = new HashMap<>();

    int counter = 0;
    for (Entry entry : root.entries) {
      importEntry(entry, root, company, eik, database, accountIds, journalIds, partnerIds, report);
      if (++counter % FLUSH_EVERY == 0) {
        // Push the SQL out and release the persistence context without ending the transaction. Only
        // ids are cached across the boundary, never entity instances.
        JPA.flush();
        JPA.clear();
        company = reattach(company);
      }
    }

    if (MicroinvestAccountingPreflightService.hasOpeningMove(root)) {
      importOpeningMove(root, company, eik, database, accountIds, partnerIds, result, report);
    } else if (!root.openingBalances.isEmpty()) {
      report.note(
          "Началното салдо за "
              + root.financialYear.year
              + " е използвано само за Preflight сверка; не е създаден повторен Move.");
    }

    JPA.flush();
    JPA.clear();
    company = reattach(company);
    MicroinvestFixedAssetImportService.importAssets(root, company, report);
  }

  // ── Partners ───────────────────────────────────────────────────────────────────────────────────

  /** Only partners referenced by standard Moves are created. */
  private void createMissingPartners(
      ExportRoot root,
      MicroinvestAccountingPreflightService.Result result,
      Map<Integer, Long> partnerIds) {
    Map<Integer, MicroinvestAccountingSourceModels.Partner> byId = new HashMap<>();
    for (MicroinvestAccountingSourceModels.Partner partner : root.partners) {
      byId.put(partner.sourceId, partner);
    }
    for (Integer sourceId : result.partnersToCreate) {
      MicroinvestAccountingSourceModels.Partner source = byId.get(sourceId);
      if (source == null) {
        throw new MicroinvestAccountingAbortException("партньор " + sourceId + " липсва във файла");
      }
      Partner partner = new Partner();
      partner.setName(source.name == null ? ("Microinvest " + sourceId) : source.name);
      partner.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
      partner.setIsCustomer(true);
      partner.setIsSupplier(true);
      String bulstat = MicroinvestAccountingPreflightService.normalizeIdentifier(source.bulstat);
      if (bulstat != null) {
        partner.setRegistrationCode(bulstat);
      }
      String vat = MicroinvestAccountingPreflightService.normalizeIdentifier(source.vatNumber);
      if (vat != null) {
        partner.setTaxNbr(source.vatNumber.trim());
      }
      partner.setImportId(
          MicroinvestAccountingSourceKey.of(
              result.companyEik,
              root.source.database,
              MicroinvestAccountingSourceKey.TYPE_PARTNER,
              sourceId));
      partner.setImportOrigin(root.source.database);
      // Adding the company is what makes the repository chain build the AccountingSituation and the
      // customer/supplier accounts. Preflight has already proven the company is configured for it.
      // Re-read it: the instance on the result came from Preflight's own persistence context.
      Company company = reattach(result.company);
      partner.addCompanySetItem(company);
      // Чий е партньорът — изрично дружеството на вноса, не каквото случайно е активно при записа:
      // оттук идва и номерът му (поредицата `partner` на дружеството).
      partner.setCompany(company);
      partner = partnerRepo.save(partner);
      partnerIds.put(sourceId, partner.getId());
      result.report.created("партньор " + partner.getName());
    }
  }

  // ── Ledger ─────────────────────────────────────────────────────────────────────────────────────

  private Long importEntry(
      Entry entry,
      ExportRoot root,
      Company company,
      String eik,
      String database,
      Map<String, Long> accountIds,
      Map<String, Long> journalIds,
      Map<Integer, Long> partnerIds,
      MicroinvestAccountingImportReport report) {

    String importId =
        MicroinvestAccountingSourceKey.of(
            eik, database, MicroinvestAccountingSourceKey.TYPE_ENTRY, entry.sourceId);
    Move existing = moveRepo.all().filter("self.importId = :id").bind("id", importId).fetchOne();
    if (existing != null) {
      report.reused("счетоводен запис " + entry.number + " (" + entry.date + ")");
      return existing.getId();
    }

    // An entry whose every line is postable-empty carries no accounting at all. FY2026 has exactly
    // one (33987, a zero-value sales invoice). A Move with no lines cannot exist, and inventing one
    // would put an empty document in the ledger, so the entry is skipped whole. A settlement that
    // refers to it is reported as unresolved without rolling back the other Moves.
    if (!hasPostableLine(entry)) {
      report.skipped(
          "счетоводен запис " + entry.number + " (" + entry.date + ")",
          "всички редове са с нулева сума -- няма какво да се осчетоводи");
      return null;
    }

    MicroinvestJournalMapper.Target target = MicroinvestJournalMapper.map(entry);
    if (target == null) {
      throw new MicroinvestAccountingAbortException(
          "проводка "
              + entry.sourceId
              + ": няма съответствие към журнал за вид '"
              + entry.documentType
              + "' и посока '"
              + entry.direction
              + "'");
    }
    Journal journal = journal(company, target.journalCode, journalIds);

    try {
      Move move =
          moveCreateService.createMove(
              journal,
              company,
              currencyOf(entry, root),
              partner(entry, partnerIds),
              entry.date,
              entry.documentDate == null ? entry.date : entry.documentDate,
              null,
              null,
              MoveRepository.TECHNICAL_ORIGIN_IMPORT,
              target.functionalOrigin == null ? 0 : target.functionalOrigin,
              origin(entry),
              entry.description,
              null);
      move.setNapDocumentTypeSelect(entry.napDocumentType);
      // ⚠️ The single reason imported history cannot regenerate an already-declared VAT period.
      move.setBgMigratedHistorical(true);
      move.setBgBookCurrency(bookCurrency(root));
      move.setBgSourceFxAmbiguous(MicroinvestFxPolicy.ambiguous(entry, root));
      move.setBgImportLocked(true);
      move.setImportId(importId);
      move.setImportOrigin(database);

      List<MoveLine> moveLines = new ArrayList<>();
      int counter = 1;
      for (Line line : entry.lines) {
        // Source rows with AccountID NULL cannot become a MoveLine. Preflight has already proven
        // that the account-less rows of this entry cancel out exactly, so skipping them moves no
        // balance.
        if (!isPostable(line)) {
          report.skipped(
              "ред " + line.sourceId,
              line.accountNumber == null || line.accountNumber.trim().isEmpty()
                  ? "няма сметка в източника"
                  : "нулева сума");
          continue;
        }
        moveLines.add(
            buildMoveLine(
                move,
                line,
                entry,
                root,
                company,
                eik,
                database,
                accountIds,
                partnerIds,
                counter++));
      }
      attachMoveLines(move, moveLines);
      move = moveRepo.save(move);

      accountAndVerify(move, entry.sourceId);
      report.created("счетоводен запис " + entry.number + " (" + entry.date + ")");
      report.movesAccounted++;
      return move.getId();
    } catch (MicroinvestAccountingAbortException e) {
      throw e;
    } catch (Exception e) {
      throw new MicroinvestAccountingAbortException(
          "проводка " + entry.sourceId + " (" + entry.number + "): " + describe(e), e);
    }
  }

  private MoveLine buildMoveLine(
      Move move,
      Line line,
      Entry entry,
      ExportRoot root,
      Company company,
      String eik,
      String database,
      Map<String, Long> accountIds,
      Map<Integer, Long> partnerIds,
      int counter)
      throws Exception {

    Account account = account(company, line.accountNumber, accountIds);
    Partner linePartner =
        line.partnerSourceId == null ? null : partnerById(partnerIds.get(line.partnerSourceId));
    boolean isDebit = nz(line.debit).signum() != 0;
    BigDecimal base = isDebit ? nz(line.debit) : nz(line.credit);

    MoveLine moveLine;
    boolean ambiguousFx = MicroinvestFxPolicy.ambiguous(entry, root);
    if (entry.currency == null || ambiguousFx) {
      // Book amounts are always exact. Ambiguous source FX stays on the BG evidence fields instead
      // of forcing a guessed single Move currency/rate.
      moveLine =
          moveLineCreateService.createMoveLine(
              move,
              linePartner,
              account,
              base,
              base,
              BigDecimal.ONE,
              isDebit,
              entry.date,
              null,
              entry.documentDate == null ? entry.date : entry.documentDate,
              counter,
              origin(entry),
              entry.description);
    } else {
      // Foreign-currency document. Lines that carry their own amount in that currency keep it
      // exactly; the others are derived from the entry's OWN proven header rate -- Preflight
      // refuses
      // the entry when the lines disagree on a rate, so nothing is ever invented here.
      BigDecimal rate =
          line.currencyDetail != null ? line.currencyDetail.exchangeRate : entry.exchangeRate;
      BigDecimal currencyAmount =
          line.currencyDetail != null
              ? line.currencyDetail.currencyAmount
              : base.divide(rate, 2, RoundingMode.HALF_UP);
      moveLine =
          moveLineCreateService.createMoveLine(
              move,
              linePartner,
              account,
              currencyAmount,
              base,
              rate,
              isDebit,
              entry.date,
              null,
              entry.documentDate == null ? entry.date : entry.documentDate,
              counter,
              origin(entry),
              entry.description);
    }
    applyTaxLine(moveLine, account, line, entry);
    moveLine.setImportId(
        MicroinvestAccountingSourceKey.of(
            eik, database, MicroinvestAccountingSourceKey.TYPE_LINE, line.sourceId));
    moveLine.setImportOrigin(database);
    moveLine.setBgImportLocked(true);
    if (line.currencyDetail != null) {
      moveLine.setBgSourceCurrencyCode(line.currencyDetail.currency);
      moveLine.setBgSourceCurrencyAmount(line.currencyDetail.currencyAmount);
      moveLine.setBgSourceExchangeRate(line.currencyDetail.exchangeRate);
    }
    return moveLine;
  }

  /**
   * Puts the source's own VAT rate on a line that sits on a tax account.
   *
   * <p>Axelor refuses to post a sale or purchase move whose tax-account line has no tax line
   * ({@code MoveLineTaxServiceImpl.isMoveLineTaxAccountRequired}). The rate is taken from the
   * source row and matched to a TaxLine of exactly that value — never rounded, never defaulted, and
   * never substituted by "the company's usual rate". If no such TaxLine exists the year aborts;
   * Preflight reports the same thing beforehand so it rarely gets this far.
   */
  private void applyTaxLine(MoveLine moveLine, Account account, Line line, Entry entry)
      throws Exception {
    if (!isTaxAccount(account) || !requiresTaxLine(entry)) {
      return;
    }
    if (line.vatRate == null) {
      throw new MicroinvestAccountingAbortException(
          "ред "
              + line.sourceId
              + " по данъчна сметка "
              + line.accountNumber
              + " няма данъчна ставка в източника -- Axelor изисква данъчен ред за такава сметка");
    }
    TaxLine taxLine = taxLineForRate(line.vatRate);
    if (taxLine == null) {
      throw new MicroinvestAccountingAbortException(
          "няма данъчен ред със ставка "
              + line.vatRate
              + "% -- създайте Tax/TaxLine с точно тази стойност");
    }
    Set<TaxLine> taxLines = new LinkedHashSet<>();
    taxLines.add(taxLine);
    moveLine.setTaxLineSet(taxLines);
  }

  /**
   * Whether a source row can become a MoveLine at all.
   *
   * <p>Two source conditions make that impossible: no account ({@code AccountingDetails.AccountID}
   * is NULL on 72 rows of this database) and a zero amount, which {@code
   * MoveLineControlServiceImpl.validateMoveLine} rejects outright. Neither carries any value, so
   * leaving them out moves no balance — and Preflight proves that before anything is written.
   */
  static boolean isPostable(Line line) {
    if (line.accountNumber == null || line.accountNumber.trim().isEmpty()) {
      return false;
    }
    BigDecimal debit = line.debit == null ? BigDecimal.ZERO : line.debit;
    BigDecimal credit = line.credit == null ? BigDecimal.ZERO : line.credit;
    return debit.signum() != 0 || credit.signum() != 0;
  }

  /** True when at least one row of the entry can become a MoveLine. */
  static boolean hasPostableLine(Entry entry) {
    for (Line line : entry.lines) {
      if (isPostable(line)) {
        return true;
      }
    }
    return false;
  }

  static boolean isTaxAccount(Account account) {
    return account != null
        && account.getAccountType() != null
        && AccountTypeRepository.TYPE_TAX.equals(account.getAccountType().getTechnicalTypeSelect());
  }

  /** Only sale and purchase moves demand it — the same two origins Axelor checks. */
  static boolean requiresTaxLine(Entry entry) {
    MicroinvestJournalMapper.Target target = MicroinvestJournalMapper.map(entry);
    Integer origin = target == null ? null : target.functionalOrigin;
    return origin != null
        && (origin == MoveRepository.FUNCTIONAL_ORIGIN_SALE
            || origin == MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE);
  }

  private TaxLine taxLineForRate(Integer rate) {
    return Query.of(TaxLine.class)
        .filter("self.value = :rate")
        .bind("rate", new BigDecimal(rate))
        .order("id")
        .fetchOne();
  }

  /**
   * Posts the move and proves it actually reached ACCOUNTED.
   *
   * <p>⚠️ With {@code AccountConfig.accountingDaybook} on and the journal allowing daybook — which
   * is this deployment's configuration — one call to {@code accounting()} only moves NEW to
   * DAYBOOK. A second call performs DAYBOOK to ACCOUNTED. The status is then asserted rather than
   * assumed: a move silently left in daybook would be invisible to the VAT diaries and to every
   * report that filters on posted entries.
   *
   * <p>{@code protected} only so a test can make one posting fail on demand and prove the whole
   * year rolls back. Nothing in production overrides it.
   */
  protected void accountAndVerify(Move move, Integer sourceId) throws Exception {
    moveValidateService.accounting(move);
    if (move.getStatusSelect() != null && move.getStatusSelect() == MoveRepository.STATUS_DAYBOOK) {
      moveValidateService.accounting(move);
    }
    if (move.getStatusSelect() == null
        || move.getStatusSelect() != MoveRepository.STATUS_ACCOUNTED) {
      throw new MicroinvestAccountingAbortException(
          "проводка "
              + sourceId
              + ": записът остана в статус "
              + move.getStatusSelect()
              + " вместо осчетоводен");
    }
  }

  private void importOpeningMove(
      ExportRoot root,
      Company company,
      String eik,
      String database,
      Map<String, Long> accountIds,
      Map<Integer, Long> partnerIds,
      MicroinvestAccountingPreflightService.Result result,
      MicroinvestAccountingImportReport report) {
    if (root.openingBalances.isEmpty()) {
      return;
    }
    LocalDate date =
        root.financialYear.startDate != null
            ? root.financialYear.startDate
            : LocalDate.of(root.financialYear.year, 1, 1);

    // ⚠️ НАЧАЛНОТО САЛДО СЪЩО Е ДОКУМЕНТ С ДАТА и минава през същата граница. То се пише на първия
    // ден на историческата година (2016-01-01) и при всяка смислена граница е далеч ПРЕДИ нея;
    // граница преди тази дата обаче значи „тази година изобщо не е моя история“ и тогава нито
    // салдото, нито проводките ѝ се внасят. Без тази проверка салдото щеше да е единственият
    // запис, който се промъква покрай обхвата.
    //
    // ⚠️ Началното салдо на ТЕКУЩАТА (неисторическа) година не минава оттук изобщо: то се създава
    // само за първата историческа година (isFirstHistoryYear), а за всяка следваща служи единствено
    // за Preflight сверка. Тоест салдо за изключен текущ период не може да бъде внесено.
    if (MicroinvestVatPeriodCutoff.isAfterCutoff(date, result.vatCutoff)) {
      report.skippedAfterCutoff++;
      report.skipped(
          "начално салдо " + root.financialYear.year,
          "датата му (" + date + ") е след границата " + result.vatCutoff);
      return;
    }

    String importId =
        MicroinvestAccountingSourceKey.of(
            eik, database, MicroinvestAccountingSourceKey.TYPE_OPENING, root.financialYear.year);
    Move existing = moveRepo.all().filter("self.importId = :id").bind("id", importId).fetchOne();
    if (existing != null) {
      report.reused("начално салдо " + root.financialYear.year);
      return;
    }

    try {
      Journal journal =
          journal(company, MicroinvestJournalMapper.OPENING.journalCode, new HashMap<>());
      Move move =
          moveCreateService.createMove(
              journal,
              company,
              bookCurrency(root),
              null,
              date,
              date,
              null,
              null,
              MoveRepository.TECHNICAL_ORIGIN_IMPORT,
              MicroinvestJournalMapper.OPENING.functionalOrigin,
              "Microinvest " + root.financialYear.year,
              "Начално салдо от Microinvest " + root.financialYear.year,
              null);
      move.setBgMigratedHistorical(true);
      move.setBgBookCurrency(bookCurrency(root));
      move.setBgImportLocked(true);
      move.setImportId(importId);
      move.setImportOrigin(database);

      List<MoveLine> moveLines = new ArrayList<>();
      int counter = 1;
      for (OpeningBalance ob : root.openingBalances) {
        Account account = account(company, ob.accountNumber, accountIds);
        Partner partner =
            ob.partnerSourceId == null ? null : partnerById(partnerIds.get(ob.partnerSourceId));
        // Negative source amounts were already turned into their absolute value on the opposite
        // side by MicroinvestAccountMapping (Axelor's cleanDebitCredit() would zero them).
        boolean isDebit = nz(ob.debit).signum() != 0;
        BigDecimal base = isDebit ? nz(ob.debit) : nz(ob.credit);
        if (base.signum() == 0) {
          continue;
        }
        MoveLine moveLine =
            moveLineCreateService.createMoveLine(
                move,
                partner,
                account,
                base,
                base,
                BigDecimal.ONE,
                isDebit,
                date,
                null,
                date,
                counter,
                "Microinvest",
                "Начално салдо " + ob.accountNumber);
        moveLine.setImportId(openingLineImportId(eik, database, ob));
        moveLine.setImportOrigin(database);
        moveLine.setBgSourceAccountingId(ob.accountingId);
        moveLine.setBgImportLocked(true);
        moveLines.add(moveLine);
        counter++;
      }
      attachMoveLines(move, moveLines);
      move = moveRepo.save(move);
      accountAndVerify(move, null);
      report.created(
          "начално салдо " + root.financialYear.year + " (" + moveLines.size() + " реда)");
      report.openingMoveLines = moveLines.size();
    } catch (MicroinvestAccountingAbortException e) {
      throw e;
    } catch (Exception e) {
      throw new MicroinvestAccountingAbortException(
          "начално салдо " + root.financialYear.year + ": " + e.getMessage(), e);
    }
  }

  // ── Settlements ────────────────────────────────────────────────────────────────────────────────

  /**
   * Replays source settlements after the annual Move transaction has committed. Each settlement
   * owns its own transaction: an unresolved link or a stock-service refusal is reported and rolled
   * back alone, never together with the valid Moves.
   */
  void importSettlements(MicroinvestAccountingPreflightService.Result result) {
    ExportRoot root = result.root;
    for (Settlement settlement : root.settlements) {
      try {
        JPA.runInTransaction(
            () -> {
              try (MicroinvestImportScope ignored = MicroinvestImportScope.open()) {
                reconcileOne(result, settlement);
              }
            });
      } catch (RuntimeException e) {
        String reason = describe(e);
        result.report.unresolved("равнение " + settlement.sourceId, reason);
        // The failing settlement's own transaction is gone; the audit row needs its own, or the
        // reason would be lost exactly for the settlements that need it most.
        auditFailure(result, settlement, reason);
      }
    }
  }

  private void reconcileOne(
      MicroinvestAccountingPreflightService.Result result, Settlement settlement) {
    ExportRoot root = result.root;
    String database = root.source.database;
    String settlementImportId =
        MicroinvestAccountingSourceKey.of(
            result.companyEik,
            database,
            MicroinvestAccountingSourceKey.TYPE_SETTLEMENT,
            settlement.sourceId);
    // ⚠️ Two shapes of key, on purpose. Runs before the 1→N allocation wrote ONE reconciliation
    // under the bare key; a settlement that needs several writes them as "<key>#1", "<key>#2"
    // (Reconcile.importId carries a GLOBAL unique index, so the pairs cannot share one). A retry
    // must recognise both, or it would reconcile an already-settled source row a second time.
    if (reconcileRepo
            .all()
            .filter("self.importId = :id OR self.importId LIKE :prefix")
            .bind("id", settlementImportId)
            .bind("prefix", settlementImportId + "#%")
            .count()
        > 0) {
      result.report.reused("равнение " + settlement.sourceId);
      audit(
          result,
          settlement,
          MicroinvestSettlementAuditRepository.STATUS_REUSED,
          null,
          null,
          "Вече равнено от предишен пробег — не е пипано.");
      return;
    }

    Company company = JPA.find(Company.class, result.company.getId());
    List<MoveLine> payment =
        endpointLines(
            company,
            result.companyEik,
            database,
            settlement.paymentEntryId,
            settlement.accountNumber);
    List<MoveLine> settled =
        endpointLines(
            company,
            result.companyEik,
            database,
            settlement.settledEntryId,
            settlement.accountNumber);

    // A settlement is one side paying the other: one endpoint entry must hold the open debits and
    // the other the open credits. Open amounts on BOTH sides of one entry would mean the source
    // row does not say which direction it settles, so it is refused rather than guessed.
    List<MoveLine> paymentDebits = side(payment, true);
    List<MoveLine> paymentCredits = side(payment, false);
    List<MoveLine> settledDebits = side(settled, true);
    List<MoveLine> settledCredits = side(settled, false);
    List<MoveLine> debitLines;
    List<MoveLine> creditLines;
    if (!paymentDebits.isEmpty() && paymentCredits.isEmpty() && settledDebits.isEmpty()) {
      debitLines = paymentDebits;
      creditLines = settledCredits;
    } else if (!settledDebits.isEmpty() && settledCredits.isEmpty() && paymentDebits.isEmpty()) {
      debitLines = settledDebits;
      creditLines = paymentCredits;
    } else {
      throw new MicroinvestAccountingAbortException(
          "source проводки "
              + settlement.paymentEntryId
              + "/"
              + settlement.settledEntryId
              + " по сметка "
              + settlement.accountNumber
              + " имат неравнени суми и от двете страни на една и съща проводка — посоката на"
              + " равнението не следва от source файла.");
    }

    List<MicroinvestSettlementAllocation.Endpoint> debits = endpoints(debitLines, true);
    List<MicroinvestSettlementAllocation.Endpoint> credits = endpoints(creditLines, false);
    BigDecimal amount = nz(settlement.amount);
    MicroinvestSettlementAllocation.Plan plan =
        MicroinvestSettlementAllocation.plan(debits, credits, amount);
    if (!plan.isPlanned()) {
      throw new MicroinvestAccountingAbortException(
          "сметка " + settlement.accountNumber + ": " + plan.refusal);
    }

    Map<Long, MoveLine> byId = new LinkedHashMap<>();
    for (MoveLine line : debitLines) {
      byId.put(line.getId(), line);
    }
    for (MoveLine line : creditLines) {
      byId.put(line.getId(), line);
    }
    StringBuilder pairs = new StringBuilder();
    try {
      int index = 0;
      for (MicroinvestSettlementAllocation.Allocation allocation : plan.allocations) {
        index++;
        Reconcile reconcile =
            reconcileService.createReconcile(
                byId.get(allocation.debitMoveLineId),
                byId.get(allocation.creditMoveLineId),
                allocation.amount,
                false);
        if (reconcile == null) {
          throw new MicroinvestAccountingAbortException(
              "Axelor отказа двойката " + allocation + " (account/reconcileOk)");
        }
        // One source settlement, several reconciliations: the suffix keeps each one's identity
        // stable across retries without colliding on the unique importId index.
        reconcile.setImportId(
            plan.allocations.size() == 1 ? settlementImportId : settlementImportId + "#" + index);
        reconcile.setImportOrigin(database);
        reconcileService.confirmReconcile(reconcile, true, true);
        if (pairs.length() > 0) {
          pairs.append('\n');
        }
        pairs.append(allocation);
      }
      result.report.reconciled(
          "равнение "
              + settlement.sourceId
              + " по сметка "
              + settlement.accountNumber
              + " за "
              + amount
              + " ("
              + plan.rule
              + ", "
              + plan.allocations.size()
              + " двойки)");
      audit(
          result,
          settlement,
          MicroinvestSettlementAuditRepository.STATUS_RECONCILED,
          plan.rule,
          pairs.toString(),
          null);
    } catch (MicroinvestAccountingAbortException e) {
      throw e;
    } catch (Exception e) {
      throw new MicroinvestAccountingAbortException(describe(e), e);
    }
  }

  /** Endpoint lines that still have an open amount on the given side. */
  private List<MoveLine> side(List<MoveLine> lines, boolean debit) {
    List<MoveLine> out = new ArrayList<>();
    for (MoveLine line : lines) {
      BigDecimal total = debit ? nz(line.getDebit()) : nz(line.getCredit());
      if (total.signum() > 0 && remaining(line, debit).signum() > 0) {
        out.add(line);
      }
    }
    return out;
  }

  private List<MicroinvestSettlementAllocation.Endpoint> endpoints(
      List<MoveLine> lines, boolean debit) {
    List<MicroinvestSettlementAllocation.Endpoint> out = new ArrayList<>();
    for (MoveLine line : lines) {
      out.add(
          new MicroinvestSettlementAllocation.Endpoint(
              line.getId(),
              line.getPartner() == null ? null : line.getPartner().getId(),
              remaining(line, debit)));
    }
    return out;
  }

  private List<MoveLine> endpointLines(
      Company company, String eik, String database, Integer sourceEntryId, String accountNumber) {
    Map<Long, MoveLine> exact = new LinkedHashMap<>();
    String moveImportId =
        MicroinvestAccountingSourceKey.of(
            eik, database, MicroinvestAccountingSourceKey.TYPE_ENTRY, sourceEntryId);
    Move move = moveRepo.all().filter("self.importId = :id").bind("id", moveImportId).fetchOne();
    if (move != null && move.getMoveLineList() != null) {
      for (MoveLine line : move.getMoveLineList()) {
        if (line.getAccount() != null
            && java.util.Objects.equals(accountNumber, line.getAccount().getCode())) {
          exact.put(line.getId(), line);
        }
      }
    }
    List<MoveLine> opening =
        Query.of(MoveLine.class)
            .filter(
                "self.move.company = :company AND self.move.importOrigin = :origin"
                    + " AND self.bgSourceAccountingId = :sourceId"
                    + " AND self.account.code = :account")
            .bind("company", company)
            .bind("origin", database)
            .bind("sourceId", sourceEntryId)
            .bind("account", accountNumber)
            .fetch();
    for (MoveLine line : opening) {
      exact.put(line.getId(), line);
    }
    return new ArrayList<>(exact.values());
  }

  // ── Settlement audit ───────────────────────────────────────────────────────────────────────────

  /**
   * Writes the outcome of one source settlement where it survives the session.
   *
   * <p>⚠️ The wizard is transient and its report lives only in the open screen. FY2016 left 102
   * unresolved settlements whose reasons were gone as soon as the page was reloaded. One row per
   * source settlement, overwritten by the next attempt, so a retry updates the verdict instead of
   * stacking copies.
   */
  private void audit(
      MicroinvestAccountingPreflightService.Result result,
      Settlement settlement,
      int status,
      String rule,
      String pairs,
      String reason) {
    MicroinvestSettlementAuditRepository repo =
        Beans.get(MicroinvestSettlementAuditRepository.class);
    String database = result.root.source.database;
    MicroinvestSettlementAudit audit =
        repo.all()
            .filter(
                "self.company = :company AND self.sourceDatabase = :database"
                    + " AND self.sourceId = :sourceId")
            .bind("company", result.company)
            .bind("database", database)
            .bind("sourceId", settlement.sourceId)
            .fetchOne();
    if (audit == null) {
      audit = new MicroinvestSettlementAudit();
      audit.setCompany(JPA.find(Company.class, result.company.getId()));
      audit.setSourceDatabase(database);
      audit.setSourceId(settlement.sourceId);
    }
    audit.setFinancialYear(
        result.root.financialYear == null ? null : result.root.financialYear.year);
    audit.setPaymentEntryId(settlement.paymentEntryId);
    audit.setSettledEntryId(settlement.settledEntryId);
    audit.setDocumentId(settlement.documentId);
    audit.setAccountNumber(settlement.accountNumber);
    audit.setAmount(settlement.amount);
    audit.setStatusSelect(status);
    audit.setRule(rule);
    audit.setMoveLinePairs(pairs);
    audit.setReason(reason);
    audit.setAttemptedAt(java.time.LocalDateTime.now());
    repo.save(audit);
  }

  /** The refusal path: its own transaction, because the settlement's own one has rolled back. */
  private void auditFailure(
      MicroinvestAccountingPreflightService.Result result, Settlement settlement, String reason) {
    try {
      JPA.runInTransaction(
          () ->
              audit(
                  result,
                  settlement,
                  MicroinvestSettlementAuditRepository.STATUS_UNRESOLVED,
                  null,
                  null,
                  reason));
    } catch (RuntimeException e) {
      result.report.note(
          "⚠️ Одитният ред за равнение "
              + settlement.sourceId
              + " не можа да се запише: "
              + describe(e));
    }
  }

  /**
   * Puts the lines on the move without replacing the collection instance.
   *
   * <p>⚠️ {@code move.setMoveLineList(newList)} looks equivalent and is not: {@code
   * MoveCreateServiceImpl.createMove} already installs a list, and {@code Move.moveLineList} is
   * mapped {@code cascade="all-delete-orphan"}. Swapping the instance makes Hibernate throw "a
   * collection with cascade=all-delete-orphan was no longer referenced by the owning entity" at
   * flush, which aborts the whole year on its very first entry.
   */
  private static void attachMoveLines(Move move, List<MoveLine> moveLines) {
    if (move.getMoveLineList() == null) {
      move.setMoveLineList(new ArrayList<>());
    } else {
      move.getMoveLineList().clear();
    }
    for (MoveLine moveLine : moveLines) {
      move.addMoveLineListItem(moveLine);
    }
  }

  /** Exception type plus message, following the cause chain — messages alone are often null. */
  private static String describe(Throwable error) {
    StringBuilder sb = new StringBuilder();
    Throwable current = error;
    int depth = 0;
    while (current != null && depth++ < 5) {
      if (sb.length() > 0) {
        sb.append(" <- ");
      }
      sb.append(current.getClass().getSimpleName());
      if (current.getMessage() != null) {
        sb.append(": ").append(current.getMessage());
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    // Plus where it actually happened. Without a frame, "PersistenceException <-
    // NullPointerException"
    // names the symptom and hides the cause.
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    StackTraceElement[] frames = root.getStackTrace();
    for (int i = 0; i < frames.length && i < 4; i++) {
      sb.append("\n    at ").append(frames[i]);
    }
    return sb.toString();
  }

  /**
   * Re-reads the company into the fresh persistence context after a {@code JPA.clear()}.
   *
   * <p>⚠️ Not defensive tidying — without it the import dies on the 201st record. Every move points
   * at the company, and the generated many-to-one cascades PERSIST, so a company instance left over
   * from before the clear is detached and the very next save fails with "detached entity passed to
   * persist". The same applies to any other long-lived reference: keep IDs across a clear, never
   * entity instances.
   */
  private Company reattach(Company company) {
    return company == null || company.getId() == null
        ? company
        : JPA.find(Company.class, company.getId());
  }

  /** The line's stable identity: the source DETAIL id, which is unique across the whole source. */
  private static String openingLineImportId(String eik, String database, OpeningBalance ob) {
    return MicroinvestAccountingSourceKey.of(
        eik,
        database,
        MicroinvestAccountingSourceKey.TYPE_LINE,
        // sourceDetailId, not sourceId+account: a balance record can emit two rows on the SAME
        // account, which would collide on the global unique importId index.
        "ob-" + ob.sourceDetailId);
  }

  private BigDecimal remaining(MoveLine line, boolean debit) {
    BigDecimal total = debit ? nz(line.getDebit()) : nz(line.getCredit());
    return total.subtract(nz(line.getAmountPaid()));
  }

  // ── Small helpers ──────────────────────────────────────────────────────────────────────────────

  private Journal journal(Company company, String code, Map<String, Long> cache) {
    Long id = cache.get(code);
    if (id != null) {
      return journalRepo.find(id);
    }
    Journal journal =
        journalRepo
            .all()
            .filter("self.company = :company AND self.code = :code")
            .bind("company", company)
            .bind("code", code)
            .fetchOne();
    if (journal == null) {
      throw new MicroinvestAccountingAbortException("журнал " + code + " не съществува");
    }
    cache.put(code, journal.getId());
    return journal;
  }

  private Account account(Company company, String number, Map<String, Long> cache) {
    Long id = cache.get(number);
    if (id != null) {
      return accountRepo.find(id);
    }
    Account account =
        accountRepo
            .all()
            .filter("self.company = :company AND self.code = :code")
            .bind("company", company)
            .bind("code", number)
            .fetchOne();
    if (account == null) {
      throw new MicroinvestAccountingAbortException("сметка " + number + " не съществува");
    }
    cache.put(number, account.getId());
    return account;
  }

  private Partner partnerById(Long id) {
    return id == null ? null : partnerRepo.find(id);
  }

  /** The header partner, when every line agrees on one; null otherwise. */
  private Partner partner(Entry entry, Map<Integer, Long> partnerIds) {
    Integer single = null;
    for (Line line : entry.lines) {
      if (line.partnerSourceId == null) {
        continue;
      }
      if (single == null) {
        single = line.partnerSourceId;
      } else if (!single.equals(line.partnerSourceId)) {
        return null;
      }
    }
    return single == null ? null : partnerById(partnerIds.get(single));
  }

  /**
   * The document currency: the source's own when the entry is in a foreign currency, otherwise the
   * financial year's book currency. Preflight has already proven a Currency record exists for every
   * code used, so a missing one here is a genuine failure, not something to fall back from —
   * silently substituting the company currency would record a USD amount as euros.
   */
  private Currency currencyOf(Entry entry, ExportRoot root) {
    if (entry.currency == null || MicroinvestFxPolicy.ambiguous(entry, root)) {
      return bookCurrency(root);
    }
    Currency currency =
        currencyRepo.all().filter("self.codeISO = :iso").bind("iso", entry.currency).fetchOne();
    if (currency == null) {
      throw new MicroinvestAccountingAbortException(
          "проводка " + entry.sourceId + ": валута " + entry.currency + " не съществува в Nyxa");
    }
    return currency;
  }

  private Currency bookCurrency(ExportRoot root) {
    Currency currency =
        currencyRepo
            .all()
            .filter("self.codeISO = :iso")
            .bind("iso", root.financialYear.baseCurrency)
            .fetchOne();
    if (currency == null) {
      throw new MicroinvestAccountingAbortException(
          "счетоводна валута " + root.financialYear.baseCurrency + " не съществува в Nyxa");
    }
    return currency;
  }

  private String origin(Entry entry) {
    return entry.documentNumber != null ? entry.documentNumber : entry.number;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
