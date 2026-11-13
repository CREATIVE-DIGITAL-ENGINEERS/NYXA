package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.JournalRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.service.accounting.BgAccountingCurrencyPolicy;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Entry;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Line;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.OpeningBalance;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Settlement;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.Query;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;

/**
 * Validates one annual accounting export file and reports everything wrong with it.
 *
 * <p>⚠️ THIS CLASS PERFORMS ZERO DATABASE WRITES. It holds no repository {@code save} call and is
 * never given a transaction. Preflight is a read-only opinion. Keep it that way — the operator has
 * to be able to press Preflight on a production company without consequences.
 *
 * <p>It collects ALL problems rather than stopping at the first, because the operator's job is to
 * fix a configuration list once, not to rediscover it one blocker per run.
 */
@Singleton
public class MicroinvestAccountingPreflightService {

  /** Amounts equal to the cent. */
  static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.005");

  /** Partner lookup by EIK or VAT number, normalised the way both systems spell them. */
  static final String PARTNER_IDENTIFIER_FILTER =
      "(REPLACE(REPLACE(UPPER(COALESCE(self.registrationCode, '')), ' ', ''), 'BG', '') = :ident"
          + " OR REPLACE(REPLACE(UPPER(COALESCE(self.taxNbr, '')), ' ', ''), 'BG', '') = :ident)"
          + " AND (self.archived IS NULL OR self.archived = FALSE)";

  /** Microinvest's placeholder for "no Bulgarian registration" — a foreign counterparty. */
  static final String SENTINEL_IDENTIFIER = "999999999999999";

  @Inject protected AccountRepository accountRepo;
  @Inject protected JournalRepository journalRepo;
  @Inject protected PeriodRepository periodRepo;
  @Inject protected MoveRepository moveRepo;
  @Inject protected PartnerRepository partnerRepo;
  @Inject protected AccountConfigService accountConfigService;

  /** Everything the import needs to know, plus the report the operator reads. */
  public static final class Result {
    public ExportRoot root;
    public MicroinvestAccountingImportReport report;
    public Company company;
    public String companyEik;
    public String fileSha256;

    /**
     * Source entries that will actually become a Move, and the ones that intentionally will not.
     *
     * <p>Counted with {@link MicroinvestAccountingImportService#hasPostableLine} — the SAME rule
     * the importer applies — so the confirmation promises the number of Moves the run will really
     * produce. FY2026 has exactly one such entry (AccountingID 33987, a zero-value invoice), but
     * nothing here is specific to that file.
     */
    public int postableEntries;

    public int unpostableEntries;

    /** Source partner id -> the Nyxa Partner it resolved to, when one already exists. */
    public final Map<Integer, Long> resolvedPartnerIds = new HashMap<>();

    /** Source partner ids that will have to be created. */
    public final Set<Integer> partnersToCreate = new LinkedHashSet<>();

    /**
     * Включващата граница на историята — последният ден на заявения последен подаден ДДС период.
     * Никога {@code null} при непрекъснат Preflight: липсващ период е блокер.
     */
    public LocalDate vatCutoff;
  }

  /** The active company of the current user, or null. */
  public static Company activeCompany() {
    User user = AuthUtils.getUser();
    return user == null ? null : user.getActiveCompany();
  }

  public Result preflight(byte[] content, String lastFiledVatPeriod) throws IOException {
    Result result = new Result();
    MicroinvestAccountingImportReport report = new MicroinvestAccountingImportReport();
    result.report = report;

    ExportRoot root = MicroinvestAccountingExportFileParser.parse(content);
    result.root = root;
    result.fileSha256 = MicroinvestAccountingExportFileParser.fileSha256(content);

    // Броячите на ФАЙЛА — какво съдържа той, не какво ще бъде внесено. Обхватът се реже по-долу.
    report.entriesFound = root.entries.size();
    report.sourceRecords =
        root.entries.size()
            + root.entries.stream().mapToInt(e -> e.lines.size()).sum()
            + root.openingBalances.size()
            + root.settlements.size()
            + root.fixedAssets.size();

    String baseCurrency =
        root.financialYear.baseCurrency == null
            ? ""
            : root.financialYear.baseCurrency.trim().toUpperCase(Locale.ROOT);
    report.source(
        String.format(
            "%s, финансова година %s, счетоводна валута %s -> стандартна главна книга",
            root.source == null ? "?" : root.source.database,
            root.financialYear.year,
            baseCurrency.isEmpty() ? "?" : baseCurrency));

    // ── A. Which company are we writing into ────────────────────────────────────────────────────
    Company company = activeCompany();
    result.company = company;
    String activeEik =
        company == null || company.getPartner() == null
            ? null
            : company.getPartner().getRegistrationCode();
    result.companyEik = activeEik;
    report.targetCompany(
        MicroinvestAccountingCompanyGuard.targetBanner(
            company == null ? null : company.getName(), activeEik));

    MicroinvestAccountingCompanyGuard.Verdict verdict =
        MicroinvestAccountingCompanyGuard.verdict(root.company.bulstat, activeEik, company != null);
    if (verdict != MicroinvestAccountingCompanyGuard.Verdict.MATCH) {
      report.blocked(
          "идентичност на компанията",
          MicroinvestAccountingCompanyGuard.problemMessage(
              verdict,
              root.company.name,
              root.company.bulstat,
              company == null ? null : company.getName(),
              activeEik));
      return result;
    }

    if (!List.of("BGN", "EUR").contains(baseCurrency)) {
      report.blocked(
          "базова валута",
          "очаква се BGN или EUR, а файлът съдържа '"
              + (baseCurrency.isEmpty() ? "?" : baseCurrency)
              + "' -- преекспортирайте с актуалния скрипт");
      return result;
    }

    // ── B. Does the file describe itself honestly ───────────────────────────────────────────────
    // ⚠️ ЦЕЛОСТТА СЕ ПРОВЕРЯВА ПРЕДИ ОБХВАТА. Файлът обявява броячи и контролни суми за ЦЯЛОТО си
    // съдържание; отрязан първи, той нямаше да съвпада сам със себе си и проверката щеше да пада.
    checkCountsAndChecksums(root, report);
    checkOpeningBalanceIdentity(root, report);

    // ── Б1. Границата на историята ──────────────────────────────────────────────────────────────
    String cutoffRefusal = MicroinvestVatPeriodCutoff.refusal(lastFiledVatPeriod);
    if (cutoffRefusal != null) {
      report.blocked("обхват на импорта", cutoffRefusal);
      return result;
    }
    result.vatCutoff = MicroinvestVatPeriodCutoff.cutoffOf(lastFiledVatPeriod);
    report.lastFiledVatPeriod = lastFiledVatPeriod.trim();
    report.vatCutoffDate = result.vatCutoff;
    applyVatPeriodCutoff(root, result.vatCutoff, report);

    // Одобреното съответствие на сметките и нормализацията на отрицателните суми се прилагат ВЕДНЪЖ
    // върху разчетения файл, след проверката на целостта и преди всяка друга проверка: оттук
    // нататък
    // и Preflight, и импортът виждат само целевите сметки на Nyxa.
    MicroinvestAccountMapping.Outcome mapping = MicroinvestAccountMapping.apply(root);
    if (!mapping.unmapped.isEmpty()) {
      report.blocked(
          "съответствие на сметки",
          "няма одобрено съответствие за сметки "
              + String.join(", ", mapping.unmapped)
              + " -- те не се импортират и не се заместват със сметка със същия код");
    }
    if (mapping.remapped > 0 || mapping.negativesFlipped > 0) {
      report.note(
          mapping.remapped
              + " реда са пренасочени към одобрена сметка на Nyxa; "
              + mapping.negativesFlipped
              + " реда с отрицателна сума са записани с абсолютна стойност на обратната страна.");
    }

    // Броят на проводките, които РЕАЛНО ще станат Move — след рязането на обхвата, за да обещае
    // потвърждението точно толкова, колкото ще бъдат създадени.
    for (Entry entry : root.entries) {
      if (MicroinvestAccountingImportService.hasPostableLine(entry)) {
        result.postableEntries++;
      } else {
        result.unpostableEntries++;
      }
    }

    // Every year uses the standard ledger. The company remains EUR while each fiscal year supplies
    // the immutable book currency carried by its Moves.
    checkBookCurrency(root, company, baseCurrency, report);
    checkDocumentCurrencies(root, report);
    checkMixedCurrencyEntries(root, report);
    checkEntryBalances(root, report);
    checkOpeningBalance(root, company, report);
    Map<String, Account> accounts = checkAccounts(root, company, report);
    checkForeignLedgerData(root, company, activeEik, report);
    checkPeriods(root, company, report);
    checkJournals(root, company, accounts, report);
    checkSpecialAccountBuckets(root, accounts, report);
    checkTaxLines(root, accounts, report);
    checkUnpostableLines(root, report);
    checkCurrencyScale(root, company, report);
    checkForeignCurrencyUsable(root, company, report);
    checkPartners(root, company, result, report);
    checkPartnerCreationPrerequisites(company, result, report);
    checkSettlements(root, company, report);
    MicroinvestFixedAssetImportService.preflight(root, company, report);

    return result;
  }

  /**
   * РЕЖЕ ОБХВАТА НА ЕДНО МЯСТО — всичко след границата изчезва от {@code root} и затова не може да
   * стигне до нито един запис.
   *
   * <p><b>Защо тук, а не в импорта.</b> След това рязане ВСЯКА следваща проверка и самият импорт
   * виждат само историята: сметките, които трябва да съществуват, партньорите за създаване,
   * периодите, журналите и равненията се смятат върху внасяното, а не върху целия файл. Проверка в
   * импорта щеше да остави Preflight да иска сметка, ползвана само от изключен документ, и да
   * обещава Move-ове, които няма да се създадат.
   *
   * <p><b>Пропуснатото не оставя СЛЕД СЕБЕ СИ нищо.</b> Няма Move, MoveLine, importId, партньор,
   * актив — записът просто не съществува за импорта. Единствената следа е броячът в доклада.
   *
   * <p><b>Активите се режат по дата на придобиване.</b> Актив, купен след границата, ще дойде в
   * Nyxa с фактурата си, която счетоводителят въвежда ръчно; внесен и оттук, той щеше да е втори
   * път. Актив без дата на придобиване се ЗАПАЗВА — незнанието не е основание да се изхвърли
   * история.
   *
   * <p>Равненията НЕ се режат: равнение към изключен документ излиза като „неразрешено“, което е
   * вярното му описание — другият му край вече е в Nyxa, въведен на ръка.
   */
  // Package-private, не private: рязането на обхвата е гаранцията „нищо след границата не стига до
  // запис“ и се проверява директно, без база — целият preflight() иска компания, сметкоплан и
  // периоди.
  void applyVatPeriodCutoff(
      ExportRoot root, LocalDate cutoff, MicroinvestAccountingImportReport report) {
    List<Entry> inScope = new ArrayList<>(root.entries.size());
    for (Entry entry : root.entries) {
      if (MicroinvestVatPeriodCutoff.isAfterCutoff(entry.date, cutoff)) {
        report.skippedAfterCutoff++;
        report.aggregate(
            "След границата — не се внася (въведете го в Nyxa)", "проводки с дата след " + cutoff);
        continue;
      }
      inScope.add(entry);
    }
    root.entries = inScope;

    List<JsonNode> assetsInScope = new ArrayList<>(root.fixedAssets.size());
    for (JsonNode asset : root.fixedAssets) {
      String acquired = asset.path("accountingDepreciation").path("acquisitionDate").asText(null);
      LocalDate acquisitionDate = acquired == null ? null : LocalDate.parse(acquired);
      if (MicroinvestVatPeriodCutoff.isAfterCutoff(acquisitionDate, cutoff)) {
        report.skippedAssetsAfterCutoff++;
        report.aggregate(
            "След границата — не се внася (въведете го в Nyxa)",
            "активи с дата на придобиване след " + cutoff);
        continue;
      }
      assetsInScope.add(asset);
    }
    root.fixedAssets = assetsInScope;

    if (report.skippedAfterCutoff > 0 || report.skippedAssetsAfterCutoff > 0) {
      report.note(
          "Обхват: внасят се документите до "
              + cutoff
              + " включително. След тази дата остават "
              + report.skippedAfterCutoff
              + " проводки и "
              + report.skippedAssetsAfterCutoff
              + " активи — те НЕ се внасят и трябва да се въведат в Nyxa по обичайния ред, за да"
              + " влязат в ДДС дневниците и в справка-декларацията.");
    }
  }

  /**
   * The confirmation the operator sees before pressing Import. It is derived from the parsed file,
   * never from the file name.
   *
   * <p>A blocked Preflight produces NO confirmation at all (null): there is nothing to agree to,
   * because the run cannot proceed. Asking "Продължавате ли?" over a list of blockers would invite
   * the operator to confirm an action that is already refused.
   */
  public static String importConfirmation(Result result) {
    if (result == null || result.root == null) {
      return null;
    }
    if (result.report != null && result.report.hasBlockers()) {
      return null;
    }
    String company =
        result.company == null || result.company.getName() == null
            ? "(няма активна компания)"
            : result.company.getName();
    int entries = result.root.entries.size();
    int opening = result.root.openingBalances.size();

    boolean openingMove = hasOpeningMove(result.root) && opening > 0;
    // Обхватът стои ПРЕДИ обещанието: числата по-долу са вече отрязани по границата, тъй че
    // потвърждението трябва да каже докъде стига, иначе „%d проводки“ се чете като „целият файл“.
    String scope =
        String.format(
            "ОБХВАТ: до %s включително (последен подаден от Microinvest период %s).%s%n",
            result.vatCutoff,
            result.report == null ? "?" : result.report.lastFiledVatPeriod,
            result.report == null || result.report.skippedAfterCutoff == 0
                ? ""
                : String.format(
                    " СЛЕД границата остават %d проводки — те НЕ се внасят и се въвеждат в Nyxa"
                        + " като обикновени фактури.",
                    result.report.skippedAfterCutoff));
    return scope
        + String.format(
            "⚠️ В обхвата има %d проводки. Импортът ще СЪЗДАДЕ и ОСЧЕТОВОДИ %d стандартни Moves в %s"
                + " в активната компания „%s“. Началното салдо от %d реда %s. %s и ще бъдат"
                + " обработени %d равнения; неразрешимите ще останат в доклада без да отменят валидните"
                + " Moves. Записите стават ACCOUNTED и действието"
                + " е практически необратимо. Продължавате ли?",
            entries,
            result.postableEntries,
            result.root.financialYear.baseCurrency,
            company,
            opening,
            openingMove
                ? "ще стане един стандартен opening Move"
                : "се използва само за сверка и няма да създаде Move",
            skippedPhrase(result.unpostableEntries),
            result.root.settlements.size());
  }

  /** Says what happens to entries with nothing postable in them — never hides them. */
  private static String skippedPhrase(int unpostable) {
    if (unpostable == 0) {
      return "Няма нулеви проводки за пропускане";
    }
    if (unpostable == 1) {
      return "Една нулева проводка ще бъде пропусната";
    }
    return unpostable + " нулеви проводки ще бъдат пропуснати";
  }

  // ───────────────────────────────────────────────────────────────────────────────────────────────

  private void checkCountsAndChecksums(ExportRoot root, MicroinvestAccountingImportReport report) {
    int lines = root.entries.stream().mapToInt(e -> e.lines.size()).sum();
    if (root.counts.entries != root.entries.size()
        || root.counts.lines != lines
        || root.counts.openingBalances != root.openingBalances.size()
        || root.counts.settlements != root.settlements.size()) {
      report.blocked(
          "контролни броячи",
          String.format(
              "файлът обявява entries=%d lines=%d opening=%d settlements=%d, а съдържа %d/%d/%d/%d"
                  + " -- файлът е непълен или повреден",
              root.counts.entries,
              root.counts.lines,
              root.counts.openingBalances,
              root.counts.settlements,
              root.entries.size(),
              lines,
              root.openingBalances.size(),
              root.settlements.size()));
    }

    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (Entry entry : root.entries) {
      for (Line line : entry.lines) {
        debit = debit.add(nz(line.debit));
        credit = credit.add(nz(line.credit));
      }
    }
    if (!root.checksums.balanced || debit.subtract(credit).abs().compareTo(MONEY_TOLERANCE) > 0) {
      report.blocked(
          "баланс на файла", String.format("общо дебит %s срещу общо кредит %s", debit, credit));
    }
    if (root.checksums.totalDebit != null
        && root.checksums.totalDebit.subtract(debit).abs().compareTo(MONEY_TOLERANCE) > 0) {
      report.blocked(
          "контролна сума",
          String.format(
              "файлът обявява дебит %s, а редовете дават %s", root.checksums.totalDebit, debit));
    }
    if (root.contentChecksum == null || root.contentChecksum.isBlank()) {
      report.blocked(
          "контролна сума на съдържанието",
          "файлът няма contentChecksum -- преекспортирайте с актуалния скрипт");
    }
  }

  private void checkBookCurrency(
      ExportRoot root,
      Company company,
      String baseCurrency,
      MicroinvestAccountingImportReport report) {
    String companyCurrency =
        company.getCurrency() == null ? null : company.getCurrency().getCodeISO();
    if (!"EUR".equals(companyCurrency)) {
      report.blocked(
          "валута на компанията",
          "Microinvest импортът изисква една компания с основна валута EUR; текущата е "
              + companyCurrency
              + ".");
    }
    LocalDate date =
        root.financialYear.startDate != null
            ? root.financialYear.startDate
            : LocalDate.of(root.financialYear.year, 1, 1);
    try {
      Year year = BgAccountingCurrencyPolicy.fiscalYear(company, date);
      String configured = BgAccountingCurrencyPolicy.bookCurrency(year);
      if (!baseCurrency.equals(configured)) {
        report.blocked(
            "счетоводна валута на годината",
            "файлът е "
                + baseCurrency
                + ", а фискалната година "
                + year.getCode()
                + " е конфигурирана като "
                + configured
                + ".");
      }
    } catch (RuntimeException e) {
      report.blocked("счетоводна валута на годината", e.getMessage());
    }
  }

  /**
   * Every foreign document currency must exist as a Nyxa Currency, because the Move will carry it
   * as its header currency. Substituting the company currency instead would record a USD amount as
   * euros, so a missing Currency is a blocker rather than a fallback.
   */
  private void checkDocumentCurrencies(ExportRoot root, MicroinvestAccountingImportReport report) {
    Set<String> needed = new LinkedHashSet<>();
    needed.add(root.financialYear.baseCurrency);
    for (Entry entry : root.entries) {
      if (entry.currency != null) {
        needed.add(entry.currency);
      }
      for (Line line : entry.lines) {
        if (line.currencyDetail != null && line.currencyDetail.currency != null) {
          needed.add(line.currencyDetail.currency);
        }
      }
    }
    for (String iso : needed) {
      long count =
          Query.of(com.axelor.apps.base.db.Currency.class)
              .filter("self.codeISO = :iso")
              .bind("iso", iso)
              .count();
      if (count == 0) {
        report.blocked(
            "валута " + iso,
            "не съществува в Nyxa, а се ползва от документ в тази година -- създайте я");
      }
    }
    if (!needed.isEmpty()) {
      report.note("Документи в чужда валута: " + needed);
    }
  }

  /** Ambiguous source FX remains line-level evidence; book debit/credit still posts exactly. */
  private void checkMixedCurrencyEntries(
      ExportRoot root, MicroinvestAccountingImportReport report) {
    int ambiguous = 0;
    for (Entry entry : root.entries) {
      if (MicroinvestFxPolicy.ambiguous(entry, root)) {
        ambiguous++;
      }
    }
    if (ambiguous > 0) {
      report.note(
          ambiguous
              + " проводки имат нееднозначни source FX данни. Те ще носят book debit/credit в "
              + root.financialYear.baseCurrency
              + ", флаг bgSourceFxAmbiguous и точните FX полета на всеки MoveLine; няма да се"
              + " измисля header курс.");
    }
  }

  private void checkEntryBalances(ExportRoot root, MicroinvestAccountingImportReport report) {
    for (Entry entry : root.entries) {
      BigDecimal debit = BigDecimal.ZERO;
      BigDecimal credit = BigDecimal.ZERO;
      for (Line line : entry.lines) {
        debit = debit.add(nz(line.debit));
        credit = credit.add(nz(line.credit));
      }
      if (entry.lines.isEmpty()) {
        report.blocked("проводка " + entry.sourceId, "няма нито един ред");
      } else if (debit.subtract(credit).abs().compareTo(MONEY_TOLERANCE) > 0) {
        report.blocked(
            "проводка " + entry.sourceId,
            String.format("не балансира: дебит %s срещу кредит %s", debit, credit));
      }
    }
  }

  /**
   * The opening-balance grain is the source DETAIL row, not the balance record.
   *
   * <p>Microinvest stores every opening balance as a debit slot plus a credit slot. Usually one
   * half is zero, but 34 records in this source carry a non-zero value in BOTH halves on the SAME
   * account -- a gross carry-forward, e.g. FY2018 #69 on account 123 holds 26 993.69 Dt and 18
   * 993.74 Ct. So neither {@code sourceId} nor {@code (sourceId, accountNumber)} identifies a row,
   * and collapsing to one row per record also breaks the balance: FY2026 is Dt = Ct = 365 289.69
   * over 588 detail rows but 365 289.69 / 368 394.36 over 586 records.
   *
   * <p>The detail id is also the stable key of the 2016 opening MoveLines. Fails closed on a file
   * exported before the id was added rather than importing rows that cannot be identified.
   */
  private void checkOpeningBalanceIdentity(
      ExportRoot root, MicroinvestAccountingImportReport report) {
    Set<Integer> seen = new HashSet<>();
    int missing = 0;
    int duplicate = 0;
    for (OpeningBalance ob : root.openingBalances) {
      if (ob.sourceDetailId == null) {
        missing++;
      } else if (!seen.add(ob.sourceDetailId)) {
        duplicate++;
      }
    }
    if (missing > 0) {
      report.blocked(
          "идентичност на началните салда",
          missing
              + " реда начални салда нямат sourceDetailId -- файлът е от по-стара версия на"
              + " експортера; преекспортирайте с актуалния скрипт");
    }
    if (duplicate > 0) {
      report.blocked(
          "идентичност на началните салда",
          duplicate + " реда начални салда повтарят sourceDetailId -- изходният файл е повреден");
    }
  }

  /**
   * A tax-account line in a sale or purchase move must carry a tax line, or posting refuses it
   * ({@code MoveLineTaxServiceImpl}). The rate comes from the source row and has to exist as a
   * {@code TaxLine} of exactly that value — the importer never rounds one to the nearest rate.
   */
  private void checkTaxLines(
      ExportRoot root, Map<String, Account> accounts, MicroinvestAccountingImportReport report) {
    Set<Integer> missingRates = new LinkedHashSet<>();
    int withoutRate = 0;
    int taxLines = 0;
    for (Entry entry : root.entries) {
      if (!MicroinvestAccountingImportService.requiresTaxLine(entry)) {
        continue;
      }
      for (Line line : entry.lines) {
        if (!MicroinvestAccountingImportService.isTaxAccount(accounts.get(line.accountNumber))) {
          continue;
        }
        taxLines++;
        if (line.vatRate == null) {
          withoutRate++;
        } else if (Query.of(com.axelor.apps.account.db.TaxLine.class)
                .filter("self.value = :rate")
                .bind("rate", new BigDecimal(line.vatRate))
                .count()
            == 0) {
          missingRates.add(line.vatRate);
        }
      }
    }
    if (withoutRate > 0) {
      report.blocked(
          "данъчни редове",
          withoutRate
              + " реда по данъчна сметка нямат ставка в източника -- Axelor изисква данъчен ред за"
              + " такава сметка в покупка/продажба");
    }
    if (!missingRates.isEmpty()) {
      report.blocked(
          "данъчни ставки",
          "липсват данъчни редове (TaxLine) със ставки "
              + missingRates
              + " -- създайте ги, иначе осчетоводяването на покупките/продажбите отказва");
    }
    if (taxLines > 0) {
      report.note(taxLines + " реда по данъчни сметки ще получат данъчна ставка от източника.");
    }
  }

  /**
   * Source rows that cannot become a MoveLine, and entries left with none that can.
   *
   * <p>Two source conditions make a row unpostable: no account at all ({@code
   * AccountingDetails.AccountID} is NULL on 72 rows of this database, 8 of them in 2026) and a zero
   * amount, which {@code MoveLineControlServiceImpl.validateMoveLine} rejects outright.
   *
   * <p>Neither can be represented in Axelor, so the only question is whether leaving them out
   * changes the books. A zero row never does. An account-less row is dropped only when the
   * account-less rows of its entry cancel exactly, debit against credit — otherwise a real
   * imbalance is hiding in a broken row and the year is blocked. An entry left with nothing
   * postable is skipped whole, and that is a blocker if any settlement refers to it, because the
   * reconciliation would have no move to point at and is reported as unresolved.
   */
  private void checkUnpostableLines(ExportRoot root, MicroinvestAccountingImportReport report) {
    int accountless = 0;
    int zeroAmount = 0;
    Set<Integer> unbalanced = new LinkedHashSet<>();
    Set<Integer> emptyEntries = new LinkedHashSet<>();

    for (Entry entry : root.entries) {
      BigDecimal debit = BigDecimal.ZERO;
      BigDecimal credit = BigDecimal.ZERO;
      int accountlessHere = 0;
      for (Line line : entry.lines) {
        boolean noAccount = line.accountNumber == null || line.accountNumber.trim().isEmpty();
        if (noAccount) {
          accountlessHere++;
          debit = debit.add(nz(line.debit));
          credit = credit.add(nz(line.credit));
        } else if (!MicroinvestAccountingImportService.isPostable(line)) {
          zeroAmount++;
        }
      }
      accountless += accountlessHere;
      if (accountlessHere > 0 && debit.subtract(credit).abs().compareTo(MONEY_TOLERANCE) > 0) {
        unbalanced.add(entry.sourceId);
      }
      if (!MicroinvestAccountingImportService.hasPostableLine(entry)) {
        emptyEntries.add(entry.sourceId);
      }
    }

    if (!unbalanced.isEmpty()) {
      report.blocked(
          "редове без сметка",
          "проводки "
              + unbalanced
              + " съдържат редове без сметка, които НЕ се компенсират. Такъв ред не може да се"
              + " осчетоводи, а пропускането му би променило салдата -- поправете източника.");
    }
    if (!emptyEntries.isEmpty()) {
      Set<Integer> referenced = new LinkedHashSet<>();
      for (Settlement settlement : root.settlements) {
        if (emptyEntries.contains(settlement.paymentEntryId)
            || emptyEntries.contains(settlement.settledEntryId)) {
          referenced.add(settlement.sourceId);
        }
      }
      if (referenced.isEmpty()) {
        report.note(
            emptyEntries.size()
                + " проводки нямат нито един осчетоводим ред (всички суми са нула) и няма да"
                + " създадат запис в главната книга: "
                + emptyEntries
                + ".");
      } else {
        report.note(
            "проводки "
                + emptyEntries
                + " нямат осчетоводим ред, но равнения "
                + referenced
                + " сочат към тях -- тези равнения ще бъдат отчетени като UNRESOLVED, без да отменят"
                + " валидните Moves.");
      }
    }
    if (accountless > 0) {
      report.note(
          accountless
              + " реда без сметка в източника (AccountID е NULL) се компенсират по двойки в рамките"
              + " на проводката си и няма да влязат в главната книга: MoveLine без сметка не"
              + " съществува, а пропускането им не променя нито едно салдо.");
    }
    if (zeroAmount > 0) {
      report.note(
          zeroAmount
              + " реда с нулева сума няма да влязат в главната книга -- Axelor не приема ред с нула,"
              + " а нулата не променя нито едно салдо.");
    }
  }

  /**
   * Every currency the import will touch must keep two decimals.
   *
   * <p>{@code Currency.numberOfDecimals} is optional and its generated getter returns 0 when unset.
   * {@code CurrencyServiceImpl.getAmountCurrencyConvertedUsingExchangeRate} then rounds every
   * converted amount to whole units — 0.05 becomes 0.00, which posting at least rejects, and 100.77
   * becomes 101.00, which it accepts. The second case would rewrite the books without a word, so
   * this is a blocker rather than a warning.
   */
  private void checkCurrencyScale(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    Set<String> codes = new LinkedHashSet<>();
    codes.add(root.financialYear.baseCurrency);
    if (company.getCurrency() != null && company.getCurrency().getCodeISO() != null) {
      codes.add(company.getCurrency().getCodeISO());
    }
    for (Entry entry : root.entries) {
      if (entry.currency != null) {
        codes.add(entry.currency);
      }
      for (Line line : entry.lines) {
        if (line.currencyDetail != null && line.currencyDetail.currency != null) {
          codes.add(line.currencyDetail.currency);
        }
      }
    }
    Set<String> wrong = new LinkedHashSet<>();
    for (String code : codes) {
      com.axelor.apps.base.db.Currency currency =
          Query.of(com.axelor.apps.base.db.Currency.class)
              .filter("self.codeISO = :code")
              .bind("code", code)
              .fetchOne();
      if (currency != null && currency.getNumberOfDecimals() < 2) {
        wrong.add(code + " (" + currency.getNumberOfDecimals() + ")");
      }
    }
    if (!wrong.isEmpty()) {
      report.blocked(
          "точност на валутите",
          "валути с по-малко от 2 знака след десетичната запетая: "
              + wrong
              + " -- Axelor ще закръгли всяка сума до цели единици и ще промени салдата мълчаливо."
              + " Поставете 'Number of decimals' = 2.");
    }
  }

  /** The Bulgarian changeover rate. Fixed by law, so a 2026 lev document can carry no other. */
  private static final BigDecimal BGN_PER_EUR = new BigDecimal("1.95583");

  /**
   * Foreign-currency documents must be both believable and convertible.
   *
   * <p>Two separate problems, both fatal if let through:
   *
   * <ul>
   *   <li>A document denominated in BGN inside a EUR year whose rate is not 1.95583. FY2026 has
   *       four, all on account 509/1, at rates 1.23364, 1.19994, 1.00029 and 6.00102. The source's
   *       own arithmetic is consistent (amount = currencyAmount x rate), so the EUR figures that
   *       reach the accounts are sound — it is the CURRENCY LABEL that cannot be true. Which
   *       currency was actually paid is not recoverable from the data, and writing "lev at 6.00" on
   *       a move line would put a false rate in the books, so this is the accountant's call.
   *   <li>A currency with no conversion configured towards the company currency. Reconciliation
   *       converts through {@code CurrencyService}, so a missing rate fails the settlement step
   *       after every move of the year is already posted.
   * </ul>
   */
  private void checkForeignCurrencyUsable(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    String companyCode = root.financialYear.baseCurrency;
    Currency bookCurrency =
        Query.of(Currency.class)
            .filter("self.codeISO = :code")
            .bind("code", companyCode)
            .fetchOne();
    if (bookCurrency == null) {
      return;
    }

    List<String> impossible = new ArrayList<>();
    Set<String> foreign = new LinkedHashSet<>();
    for (Entry entry : root.entries) {
      for (Line line : entry.lines) {
        if (line.currencyDetail == null || line.currencyDetail.currency == null) {
          continue;
        }
        String code = line.currencyDetail.currency;
        BigDecimal rate = line.currencyDetail.exchangeRate;
        if ("BGN".equals(code)
            && "EUR".equals(companyCode)
            && rate != null
            && rate.subtract(BGN_PER_EUR).abs().compareTo(new BigDecimal("0.001")) > 0
            && rate.subtract(BigDecimal.ONE.divide(BGN_PER_EUR, 6, java.math.RoundingMode.HALF_UP))
                    .abs()
                    .compareTo(new BigDecimal("0.001"))
                > 0) {
          impossible.add(
              "проводка "
                  + entry.sourceId
                  + " ред "
                  + line.sourceId
                  + " сметка "
                  + line.accountNumber
                  + " курс "
                  + rate);
        } else if (!code.equals(companyCode)) {
          foreign.add(code);
        }
      }
    }

    if (!impossible.isEmpty()) {
      report.note(
          impossible.size()
              + " реда са в лев с курс, различен от фиксирания 1.95583 за "
              + root.financialYear.year
              + ". Сумите в евро са верни, но обозначената валута не може да е лев -- коя е реално"
              + " платената валута не следва от данните. Book сумите ще се запишат точно, а source"
              + " FX данните ще останат на MoveLine с bgSourceFxAmbiguous=true: "
              + String.join("; ", impossible));
    }

    for (String code : foreign) {
      com.axelor.apps.base.db.Currency currency =
          Query.of(com.axelor.apps.base.db.Currency.class)
              .filter("self.codeISO = :code")
              .bind("code", code)
              .fetchOne();
      if (currency == null) {
        continue; // already reported by checkDocumentCurrencies
      }
      long conversions =
          Query.of(com.axelor.apps.base.db.CurrencyConversionLine.class)
              .filter(
                  "(self.startCurrency = :currency AND self.endCurrency = :company)"
                      + " OR (self.startCurrency = :company AND self.endCurrency = :currency)")
              .bind("currency", currency)
              .bind("company", bookCurrency)
              .count();
      if (conversions == 0) {
        report.note(
            "няма конфигуриран курс между "
                + code
                + " и "
                + companyCode
                + ". Book сумите ще бъдат осчетоводени от точните source debit/credit стойности;"
                + " зависимо от това равнение ще бъде отчетено като UNRESOLVED, без да отменя Moves.");
      }
    }
  }

  private void checkOpeningBalance(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (OpeningBalance ob : root.openingBalances) {
      debit = debit.add(nz(ob.debit));
      credit = credit.add(nz(ob.credit));
    }
    if (root.openingBalances.isEmpty()) {
      report.note("Файлът няма начални салда.");
      return;
    }
    if (debit.subtract(credit).abs().compareTo(MONEY_TOLERANCE) > 0) {
      report.blocked(
          "начално салдо", String.format("не балансира: дебит %s срещу кредит %s", debit, credit));
    } else if (isFirstHistoryYear(root)) {
      report.note(
          String.format(
              "Начално салдо 2016: %d реда, дебит = кредит = %s -- ще стане един стандартен осчетоводен RPB Move.",
              root.openingBalances.size(), debit));
    } else {
      boolean euroOpening = isEuroOpeningYear(root);
      compareOpeningWithPreviousYear(root, company, report, euroOpening);
      if (euroOpening) {
        report.note(
            "Начално салдо "
                + root.financialYear.year
                + ": "
                + root.openingBalances.size()
                + " реда, дебит = кредит = "
                + debit
                + " -- ще стане един стандартен осчетоводен RPB Move директно от Microinvest, без"
                + " изчисляване от крайното салдо на предходната година.");
      }
    }
  }

  /** The migration's first historical opening is a business record. */
  public static boolean isFirstHistoryYear(ExportRoot root) {
    return root != null
        && root.financialYear != null
        && Integer.valueOf(2016).equals(root.financialYear.year);
  }

  /** FY2026, the only EUR year: its opening comes from Microinvest, never from FY2025. */
  public static boolean isEuroOpeningYear(ExportRoot root) {
    return root != null
        && root.financialYear != null
        && Integer.valueOf(2026).equals(root.financialYear.year)
        && root.financialYear.baseCurrency != null
        && "EUR".equalsIgnoreCase(root.financialYear.baseCurrency.trim());
  }

  /** Years that get an opening Move: 2016 and 2026. 2017-2025 carry theirs only for the check. */
  public static boolean hasOpeningMove(ExportRoot root) {
    return isFirstHistoryYear(root) || isEuroOpeningYear(root);
  }

  /**
   * Compares the Nyxa closing of the previous year with the source opening, per account.
   *
   * <p>For the EUR year ({@code advisory}) a difference is analysis, not a refusal: it is reported
   * as UNRESOLVED and nothing is corrected — the opening Move still carries Microinvest's own
   * balances and no correcting Move is created. Reconciling it later goes through the normal
   * reconciliation flow, whose sequence Axelor resolves itself. A MISSING previous year stays a
   * blocker either way: the years must be imported in order.
   */
  private void compareOpeningWithPreviousYear(
      ExportRoot root,
      Company company,
      MicroinvestAccountingImportReport report,
      boolean advisory) {
    int previousYear = root.financialYear.year - 1;
    LocalDate to = LocalDate.of(previousYear, 12, 31);
    List<MoveLine> previous =
        Query.of(MoveLine.class)
            .filter(
                "self.move.company = :company AND self.move.statusSelect = :status"
                    + " AND self.move.bgMigratedHistorical = true"
                    + " AND self.move.importOrigin = :origin"
                    + " AND self.date <= :to"
                    + " AND (self.move.ignoreInAccountingOk IS NULL"
                    + " OR self.move.ignoreInAccountingOk = false)")
            .bind("company", company)
            .bind("status", MoveRepository.STATUS_ACCOUNTED)
            .bind("origin", root.source.database)
            .bind("to", to)
            .fetch();

    Map<String, BigDecimal> expectedBgn = new LinkedHashMap<>();
    Map<String, BigDecimal> expectedEur = new LinkedHashMap<>();
    boolean previousYearPresent = false;
    for (MoveLine line : previous) {
      if (line.getDate() != null && line.getDate().getYear() == previousYear) {
        previousYearPresent = true;
      }
      if (line.getAccount() != null) {
        String currency =
            line.getMove().getBgBookCurrency() == null
                ? null
                : line.getMove().getBgBookCurrency().getCodeISO();
        Map<String, BigDecimal> component = "BGN".equals(currency) ? expectedBgn : expectedEur;
        component.merge(
            line.getAccount().getCode(),
            nz(line.getDebit()).subtract(nz(line.getCredit())),
            BigDecimal::add);
      }
    }
    Map<String, BigDecimal> source = new LinkedHashMap<>();
    for (OpeningBalance line : root.openingBalances) {
      if (line.accountNumber != null) {
        source.merge(line.accountNumber, nz(line.debit).subtract(nz(line.credit)), BigDecimal::add);
      }
    }

    Set<String> accounts = new LinkedHashSet<>();
    accounts.addAll(expectedBgn.keySet());
    accounts.addAll(expectedEur.keySet());
    accounts.addAll(source.keySet());
    int differences = 0;
    for (String account : accounts) {
      BigDecimal bgn = expectedBgn.getOrDefault(account, BigDecimal.ZERO);
      BigDecimal eur = expectedEur.getOrDefault(account, BigDecimal.ZERO);
      if ("BGN".equals(root.financialYear.baseCurrency) && eur.signum() != 0) {
        differences++;
        report.blocked(
            "начално салдо, сметка " + account,
            "предходните стандартни Moves съдържат EUR компонент "
                + eur
                + " преди смяната на счетоводната валута; валутите не се смесват.");
        continue;
      }
      BigDecimal expectedBalance =
          "EUR".equals(root.financialYear.baseCurrency)
              ? BgAccountingCurrencyPolicy.component(bgn, eur, "EUR")
              : bgn;
      BigDecimal sourceBalance = source.getOrDefault(account, BigDecimal.ZERO);
      if (expectedBalance.subtract(sourceBalance).abs().compareTo(MONEY_TOLERANCE) > 0) {
        differences++;
        String message =
            "очаквано "
                + expectedBalance
                + " "
                + root.financialYear.baseCurrency
                + " от крайното салдо за "
                + previousYear
                + ", източниково "
                + sourceBalance
                + " "
                + root.financialYear.baseCurrency;
        if (advisory) {
          report.unresolved("сверка на началното салдо, сметка " + account, message);
        } else {
          report.blocked("начално салдо, сметка " + account, message);
        }
      }
    }
    if (!previousYearPresent) {
      report.blocked(
          "сверка на началното салдо",
          "финансова година "
              + previousYear
              + " няма импортирани стандартни Moves; импортирайте годините последователно,"
              + " започвайки от 2016, и не пропускайте година.");
    } else if (differences == 0) {
      report.note(
          "Началното салдо за "
              + root.financialYear.year
              + " е сверено по сметки с крайното салдо за "
              + previousYear
              + (advisory ? " без разлики." : " и няма да създаде Move."));
    }
  }

  /**
   * Every source account number must already exist for the company, active.
   *
   * <p>⚠️ Analytic sub-accounts ({@code 453/1}, {@code 503/2}, {@code 601/1}) are NOT collapsed
   * onto their synthetic parent. {@code 503/2} and {@code 503/3} are different banks and {@code
   * 601/1..5} are different expense categories; collapsing them would destroy information
   * irreversibly. A missing account is an explicit blocker for the operator to create.
   */
  private Map<String, Account> checkAccounts(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    Set<String> needed = new LinkedHashSet<>();
    for (Entry entry : root.entries) {
      for (Line line : entry.lines) {
        if (line.accountNumber != null) {
          needed.add(line.accountNumber);
        }
      }
    }
    if (hasOpeningMove(root)) {
      for (OpeningBalance ob : root.openingBalances) {
        if (ob.accountNumber != null) {
          needed.add(ob.accountNumber);
        }
      }
    }

    Map<String, Account> found = new HashMap<>();
    List<String> missing = new ArrayList<>();
    List<String> inactive = new ArrayList<>();
    for (String number : needed) {
      Account account =
          accountRepo
              .all()
              .filter("self.company = :company AND self.code = :code")
              .bind("company", company)
              .bind("code", number)
              .fetchOne();
      if (account == null) {
        missing.add(number);
      } else {
        if (account.getStatusSelect() == null
            || account.getStatusSelect() != AccountRepository.STATUS_ACTIVE) {
          inactive.add(number);
        }
        found.put(number, account);
        report.accountsMatched++;
      }
    }
    if (!missing.isEmpty()) {
      report.blocked(
          "сметкоплан",
          missing.size()
              + " целеви сметки (след одобреното съответствие) липсват в сметкоплана на компанията;"
              + " импортът не създава и не променя сметки: "
              + String.join(", ", missing));
    }
    if (!inactive.isEmpty()) {
      report.blocked(
          "неактивни сметки",
          "осчетоводяването отказва неактивна сметка -- активирайте: "
              + String.join(", ", inactive));
    }
    return found;
  }

  /**
   * Refuses to import into a period that already holds operational data this importer did not
   * write.
   *
   * <p>There is no safe way to tell a manually entered duplicate from a legitimate entry — it has
   * no import id to match on — so the only correct answer is to refuse and let the operator decide.
   */
  private void checkForeignLedgerData(
      ExportRoot root, Company company, String eik, MicroinvestAccountingImportReport report) {
    LocalDate from = null;
    LocalDate to = null;
    for (Entry entry : root.entries) {
      if (entry.date == null) {
        continue;
      }
      from = from == null || entry.date.isBefore(from) ? entry.date : from;
      to = to == null || entry.date.isAfter(to) ? entry.date : to;
    }
    if (from == null) {
      return;
    }
    String prefix = MicroinvestAccountingSourceKey.prefix(eik, root.source.database);
    List<com.axelor.apps.account.db.Move> foreign =
        moveRepo
            .all()
            .filter(
                "self.company = :company AND self.date >= :from AND self.date <= :to"
                    + " AND self.statusSelect != :canceled"
                    + " AND (self.importId IS NULL OR self.importId NOT LIKE :prefix)")
            .bind("company", company)
            .bind("from", from)
            .bind("to", to)
            .bind("canceled", MoveRepository.STATUS_CANCELED)
            .bind("prefix", prefix + "%")
            .order("date")
            .fetch(50);
    if (foreign.isEmpty()) {
      return;
    }
    long total =
        moveRepo
            .all()
            .filter(
                "self.company = :company AND self.date >= :from AND self.date <= :to"
                    + " AND self.statusSelect != :canceled"
                    + " AND (self.importId IS NULL OR self.importId NOT LIKE :prefix)")
            .bind("company", company)
            .bind("from", from)
            .bind("to", to)
            .bind("canceled", MoveRepository.STATUS_CANCELED)
            .bind("prefix", prefix + "%")
            .count();
    StringBuilder sb = new StringBuilder();
    sb.append(total)
        .append(" счетоводни записа в периода ")
        .append(from)
        .append(" .. ")
        .append(to)
        .append(" не са създадени от този импорт и биха дублирали Microinvest.")
        .append(" Отменете/изтрийте ги или импортирайте друга година. Първите: ");
    for (com.axelor.apps.account.db.Move move : foreign) {
      sb.append("[id=")
          .append(move.getId())
          .append(' ')
          .append(move.getDate())
          .append(' ')
          .append(move.getJournal() == null ? "?" : move.getJournal().getCode())
          .append(' ')
          .append(move.getReference())
          .append("] ");
    }
    report.blocked("съществуващи записи в главната книга", sb.toString());
  }

  private void checkPeriods(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    Set<LocalDate> dates = new LinkedHashSet<>();
    for (Entry entry : root.entries) {
      if (entry.date != null) {
        dates.add(entry.date);
      }
    }
    if (hasOpeningMove(root)
        && !root.openingBalances.isEmpty()
        && root.financialYear.startDate != null) {
      dates.add(root.financialYear.startDate);
    }

    Set<LocalDate> missing = new LinkedHashSet<>();
    Set<String> closed = new LinkedHashSet<>();
    Set<String> ambiguous = new LinkedHashSet<>();
    for (LocalDate date : dates) {
      List<Period> periods =
          periodRepo
              .all()
              .filter(
                  "self.year.company = :company AND self.year.typeSelect = :type"
                      + " AND self.fromDate <= :date AND self.toDate >= :date")
              .bind("company", company)
              .bind("type", YearRepository.TYPE_FISCAL)
              .bind("date", date)
              .fetch(2);
      if (periods.isEmpty()) {
        missing.add(date);
      } else if (periods.size() > 1) {
        ambiguous.add(date.toString());
      } else {
        Integer status = periods.get(0).getStatusSelect();
        if (status != null
            && (status == PeriodRepository.STATUS_CLOSED
                || status == PeriodRepository.STATUS_TEMPORARILY_CLOSED)) {
          closed.add(periods.get(0).getCode());
        }
      }
    }
    if (!missing.isEmpty()) {
      report.blocked(
          "счетоводни периоди",
          "финансова година "
              + root.financialYear.year
              + " няма фискален период за "
              + missing.size()
              + " дати, напр. "
              + first(missing, 5)
              + " -- създайте годината и периодите ѝ; импортът не ги създава");
    }
    if (!ambiguous.isEmpty()) {
      report.blocked(
          "двусмислени периоди",
          "повече от една фискална година покрива "
              + first(ambiguous, 5)
              + " -- премахнете застъпващите се години");
    }
    if (!closed.isEmpty()) {
      report.blocked(
          "затворени периоди",
          "периодите " + first(closed, 10) + " са затворени и не приемат осчетоводяване");
    }
  }

  /**
   * Journals must exist, be active, carry a sequence, authorise the functional origin the mapper
   * returns, and accept the account types the entries actually use.
   *
   * <p>⚠️ Nothing here is auto-corrected. Each failure names the journal and the exact operator
   * action, because changing a journal's authorisations is an accounting decision.
   */
  private void checkJournals(
      ExportRoot root,
      Company company,
      Map<String, Account> accounts,
      MicroinvestAccountingImportReport report) {

    // journal code -> functional origin -> account types that will be posted through it
    // journal code -> (account code -> technical type). The ACCOUNT, not just its type: a journal
    // may authorise an individual account by name, and the type check alone would then report a
    // blocker that does not exist -- pushing the operator to widen validAccountTypeSet, which
    // grants
    // every present and future account of that type on that journal, permanently.
    Map<String, Map<String, String>> typesByJournal = new HashMap<>();
    Map<String, Set<Integer>> originsByJournal = new HashMap<>();
    Set<String> unmappedDocumentTypes = new LinkedHashSet<>();

    for (Entry entry : root.entries) {
      MicroinvestJournalMapper.Target target = MicroinvestJournalMapper.map(entry);
      if (target == null) {
        unmappedDocumentTypes.add(entry.documentType + " / " + entry.direction);
        continue;
      }
      collectJournalUsage(entry.lines, accounts, target, typesByJournal, originsByJournal);
    }
    if (hasOpeningMove(root) && !root.openingBalances.isEmpty()) {
      Map<String, String> used =
          typesByJournal.computeIfAbsent(
              MicroinvestJournalMapper.OPENING.journalCode, k -> new LinkedHashMap<>());
      for (OpeningBalance ob : root.openingBalances) {
        Account account = accounts.get(ob.accountNumber);
        if (account != null && account.getAccountType() != null) {
          used.put(account.getCode(), account.getAccountType().getTechnicalTypeSelect());
        }
      }
      originsByJournal
          .computeIfAbsent(MicroinvestJournalMapper.OPENING.journalCode, k -> new LinkedHashSet<>())
          .add(MicroinvestJournalMapper.OPENING.functionalOrigin);
    }

    if (!unmappedDocumentTypes.isEmpty()) {
      report.blocked(
          "вид документ без журнал",
          "няма съответствие към журнал за: " + String.join("; ", unmappedDocumentTypes));
    }

    for (Map.Entry<String, Map<String, String>> usage : typesByJournal.entrySet()) {
      String code = usage.getKey();
      Journal journal =
          journalRepo
              .all()
              .filter("self.company = :company AND self.code = :code")
              .bind("company", company)
              .bind("code", code)
              .fetchOne();
      if (journal == null) {
        report.blocked("журнал " + code, "не съществува за тази компания -- създайте го");
        continue;
      }
      if (journal.getStatusSelect() == null
          || journal.getStatusSelect() != JournalRepository.STATUS_ACTIVE) {
        report.blocked("журнал " + code, "не е активен -- активирайте го");
      }
      if (journal.getSequence() == null) {
        report.blocked(
            "журнал " + code,
            "няма конфигурирана последователност за номериране -- осчетоводяването ще откаже (MOVE_5)");
      }

      String authorized =
          journal.getAuthorizedFunctionalOriginSelect() == null
              ? ""
              : journal.getAuthorizedFunctionalOriginSelect();
      for (Integer origin : originsByJournal.getOrDefault(code, Set.of())) {
        if (origin == null) {
          continue; // Axelor has no value for this document kind; left unset on purpose.
        }
        boolean allowed =
            !authorized.isEmpty()
                && List.of(authorized.split(",")).stream()
                    .map(String::trim)
                    .anyMatch(v -> v.equals(String.valueOf(origin)));
        if (!authorized.isEmpty() && !allowed) {
          report.blocked(
              "журнал " + code,
              "не разрешава функционален произход "
                  + origin
                  + " -- добавете го в 'Authorized functional origin' на журнала");
        }
      }

      Set<String> allowedTypes = new HashSet<>();
      if (journal.getValidAccountTypeSet() != null) {
        for (AccountType type : journal.getValidAccountTypeSet()) {
          allowedTypes.add(type.getTechnicalTypeSelect());
        }
      }
      Set<String> namedAccounts = new HashSet<>();
      if (journal.getValidAccountSet() != null) {
        for (Account account : journal.getValidAccountSet()) {
          namedAccounts.add(account.getCode());
        }
      }
      if (allowedTypes.isEmpty() && namedAccounts.isEmpty()) {
        continue; // no restriction configured
      }
      // Mirrors MoveLineControlServiceImpl.controlAccountingAccount exactly: a line passes when its
      // ACCOUNT is in validAccountSet OR its TYPE is in validAccountTypeSet. Checking the type
      // alone
      // would refuse a chart that authorises a single account by name, which is the narrow, correct
      // way to let one stock account through the purchase journal.
      Set<String> rejected = new LinkedHashSet<>();
      for (Map.Entry<String, String> account : usage.getValue().entrySet()) {
        if (namedAccounts.contains(account.getKey())) {
          continue;
        }
        if (account.getValue() != null && !allowedTypes.contains(account.getValue())) {
          rejected.add(account.getKey() + " (" + account.getValue() + ")");
        }
      }
      if (!rejected.isEmpty()) {
        report.blocked(
            "журнал " + code,
            "не приема сметки "
                + rejected
                + " -- добавете сметката поименно в 'Valid accounts' на журнала (тясното решение)"
                + " или типа ѝ в 'Valid account types' (широкото), иначе осчетоводяването отказва"
                + " (MOVE_LINE_CONTROL_ACCOUNTING_ACCOUNT_FAIL)");
      }
    }
  }

  private void collectJournalUsage(
      List<Line> lines,
      Map<String, Account> accounts,
      MicroinvestJournalMapper.Target target,
      Map<String, Map<String, String>> typesByJournal,
      Map<String, Set<Integer>> originsByJournal) {
    Map<String, String> used =
        typesByJournal.computeIfAbsent(target.journalCode, k -> new LinkedHashMap<>());
    for (Line line : lines) {
      Account account = accounts.get(line.accountNumber);
      if (account != null && account.getAccountType() != null) {
        used.put(account.getCode(), account.getAccountType().getTechnicalTypeSelect());
      }
    }
    originsByJournal
        .computeIfAbsent(target.journalCode, k -> new LinkedHashSet<>())
        .add(target.functionalOrigin);
  }

  /**
   * Replays {@code MoveValidateServiceImpl.checkSpecialAccountAmount} against every real entry.
   *
   * <p>Posting requires the special, commitment and ordinary buckets to balance INDEPENDENTLY, not
   * just overall. An entry that balances as a whole can still be refused, so this is checked here
   * rather than discovered halfway through the year.
   */
  private void checkSpecialAccountBuckets(
      ExportRoot root, Map<String, Account> accounts, MicroinvestAccountingImportReport report) {
    for (Entry entry : root.entries) {
      BigDecimal[] special = {BigDecimal.ZERO, BigDecimal.ZERO};
      BigDecimal[] commitment = {BigDecimal.ZERO, BigDecimal.ZERO};
      BigDecimal[] other = {BigDecimal.ZERO, BigDecimal.ZERO};
      for (Line line : entry.lines) {
        Account account = accounts.get(line.accountNumber);
        String type =
            account == null || account.getAccountType() == null
                ? null
                : account.getAccountType().getTechnicalTypeSelect();
        BigDecimal[] bucket =
            "special".equals(type) ? special : "commitment".equals(type) ? commitment : other;
        bucket[0] = bucket[0].add(nz(line.debit));
        bucket[1] = bucket[1].add(nz(line.credit));
      }
      if (special[0].compareTo(special[1]) != 0
          || commitment[0].compareTo(commitment[1]) != 0
          || other[0].compareTo(other[1]) != 0) {
        report.blocked(
            "проводка " + entry.sourceId,
            String.format(
                "специалните/задбалансовите и обикновените сметки не балансират поотделно"
                    + " (спец. %s/%s, задбал. %s/%s, др. %s/%s) -- осчетоводяването отказва"
                    + " (MOVE_SPECIAL_ACCOUNTS_NOT_EQUALS)",
                special[0], special[1], commitment[0], commitment[1], other[0], other[1]));
      }
    }
  }

  /**
   * Resolves every partner the operational year references.
   *
   * <p>Identity ladder, in order: a valid Bulstat, then a valid VAT number (Microinvest writes the
   * sentinel {@code 999999999999999} for foreign counterparties and keeps their EU VAT number),
   * then the source key alone. Never by name — two unrelated companies share a trade name often
   * enough that name matching would merge real partners.
   */
  private void checkPartners(
      ExportRoot root, Company company, Result result, MicroinvestAccountingImportReport report) {
    Set<Integer> referenced = new LinkedHashSet<>();
    for (Entry entry : root.entries) {
      for (Line line : entry.lines) {
        if (line.partnerSourceId != null) {
          referenced.add(line.partnerSourceId);
        }
      }
    }
    if (hasOpeningMove(root)) {
      for (OpeningBalance ob : root.openingBalances) {
        if (ob.partnerSourceId != null) {
          referenced.add(ob.partnerSourceId);
        }
      }
    }

    Map<Integer, MicroinvestAccountingSourceModels.Partner> byId = new HashMap<>();
    for (MicroinvestAccountingSourceModels.Partner partner : root.partners) {
      byId.put(partner.sourceId, partner);
    }

    int noIdentity = 0;
    for (Integer sourceId : referenced) {
      MicroinvestAccountingSourceModels.Partner source = byId.get(sourceId);
      if (source == null) {
        report.blocked(
            "партньор " + sourceId,
            "реферира се от проводка, но липсва в секция partners на файла");
        continue;
      }
      // ⚠️ The source key comes FIRST, before the identifier ladder. Once this importer has created
      // a partner for a source id, that mapping is this importer's own and is exact; re-deriving it
      // from a Bulstat or a VAT number can only be worse. It is also what makes a re-import
      // possible at all: FY2026 has two distinct source partners sharing the Greek VAT number
      // EL900000017, so after a successful import the identifier ladder finds two Nyxa partners for
      // each of them and refuses the very file it has just imported. The ladder below is still the
      // whole answer for a partner this importer has never seen — adopting one the operator already
      // had — and a genuine duplicate among THOSE still blocks.
      Partner existing =
          partnerRepo
              .all()
              .filter("self.importId = :key")
              .bind(
                  "key",
                  MicroinvestAccountingSourceKey.of(
                      result.companyEik,
                      root.source.database,
                      MicroinvestAccountingSourceKey.TYPE_PARTNER,
                      sourceId))
              .fetchOne();
      if (existing != null) {
        result.resolvedPartnerIds.put(sourceId, existing.getId());
        report.partnersReused++;
        continue;
      }

      String identifier = usableIdentifier(source);
      if (identifier != null) {
        List<Partner> matches =
            partnerRepo.all().filter(PARTNER_IDENTIFIER_FILTER).bind("ident", identifier).fetch(2);
        if (matches.size() > 1) {
          report.blocked(
              "партньор " + source.name,
              "идентификатор "
                  + identifier
                  + " съответства на "
                  + matches.size()
                  + " партньора в Nyxa -- обединете ги преди импорта");
          continue;
        }
        existing = matches.isEmpty() ? null : matches.get(0);
      } else {
        // No Bulstat and no VAT number: the source key above was the only way to find it, and it
        // did not. Counted so the operator is told these are matched by key alone, never by name.
        noIdentity++;
      }
      if (existing != null) {
        result.resolvedPartnerIds.put(sourceId, existing.getId());
        report.partnersReused++;
      } else {
        result.partnersToCreate.add(sourceId);
        report.partnersCreated++;
      }
    }
    if (noIdentity > 0) {
      report.note(
          noIdentity
              + " партньора нямат нито ЕИК, нито ДДС номер. Те се свързват само по изходния ключ и"
              + " НИКОГА по име; никой ред по разчетна сметка не остава без партньор.");
    }
  }

  /**
   * The partner repository chain builds an AccountingSituation and the customer/supplier accounts
   * on save, and throws mid-save when the company is not configured for it — which would leave a
   * partner behind with no usable accounting. Checked here, before anything is created.
   */
  private void checkPartnerCreationPrerequisites(
      Company company, Result result, MicroinvestAccountingImportReport report) {
    if (result.partnersToCreate.isEmpty()) {
      return;
    }
    // ⚠️ PartnerServiceImpl.onSave dereferences appBaseService.getAppBase() with no null check, and
    // demands a partner number when generatePartnerSequence is on. Both failures surface as a
    // PersistenceException whose message is null, i.e. "провалени: 1" and nothing to act on — after
    // the year has already rolled back. Checked here instead, while it is still cheap.
    com.axelor.studio.db.AppBase appBase = Query.of(com.axelor.studio.db.AppBase.class).fetchOne();
    if (appBase == null) {
      report.blocked(
          "базова конфигурация",
          "липсва запис AppBase -- създаването на партньор ще падне с NullPointerException"
              + " (PartnerServiceImpl.onSave). Инсталирайте/активирайте приложение 'Base'.");
    } else if (Boolean.TRUE.equals(appBase.getGeneratePartnerSequence())
        && Query.of(com.axelor.apps.base.db.Sequence.class)
                .filter("self.codeSelect = :code AND self.company = :company")
                .bind("code", SequenceRepository.PARTNER)
                .bind("company", company)
                .count()
            == 0) {
      // Номерът идва от поредицата на ДРУЖЕСТВОТО; обща няма, тоест поредица на друго
      // дружество не помага.
      report.blocked(
          "последователност за партньори",
          "AppBase изисква автоматичен номер на партньор, а дружеството няма последователност с код"
              + " 'partner' (създава се при стартиране на приложението)."
              + " Импортът ще създаде "
              + result.partnersToCreate.size()
              + " партньора и ще откаже на първия.");
    }

    try {
      AccountConfig config = accountConfigService.getAccountConfig(company);
      if (config.getCustomerAccount() == null || config.getSupplierAccount() == null) {
        report.blocked(
            "счетоводна конфигурация",
            "компанията няма зададени клиентска/доставчическа сметка по подразбиране, а импортът ще"
                + " създаде "
                + result.partnersToCreate.size()
                + " партньора -- попълнете ги в Accounting configuration");
      }
    } catch (Exception e) {
      report.blocked(
          "счетоводна конфигурация", "компанията няма Accounting configuration: " + e.getMessage());
    }
  }

  /**
   * Settlements become stock Axelor reconciliations, so they must resolve exactly — by source entry
   * id, never by amount or date — and both sides must be inside this file.
   */
  private void checkSettlements(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    if (root.settlements.isEmpty()) {
      return;
    }
    int resolvable = 0;
    int unresolved = 0;
    String eik = company.getPartner() == null ? null : company.getPartner().getRegistrationCode();
    for (Settlement settlement : root.settlements) {
      boolean payment =
          settlementEndpointAvailable(
              root, company, eik, settlement.paymentEntryId, settlement.accountNumber);
      boolean settled =
          settlementEndpointAvailable(
              root, company, eik, settlement.settledEntryId, settlement.accountNumber);
      if (payment && settled) {
        resolvable++;
      } else {
        unresolved++;
        report.note(
            "UNRESOLVED preflight равнение "
                + settlement.sourceId
                + ": source endpoints "
                + settlement.paymentEntryId
                + "/"
                + settlement.settledEntryId
                + " по сметка "
                + settlement.accountNumber
                + " не водят до две точни стандартни MoveLine страни.");
      }
    }
    report.note(
        "Равнения: "
            + resolvable
            + " точно разрешими; "
            + unresolved
            + " предварително неразрешени. Неразрешено равнение не блокира и не връща валидните"
            + " Moves; остава като UNRESOLVED ред в import report.");

    long reconcileSequences =
        Query.of(com.axelor.apps.base.db.Sequence.class)
            .filter("self.company = :company AND self.codeSelect = :code")
            .bind("company", company)
            .bind("code", SequenceRepository.RECONCILE)
            .count();
    List<String> missingSequences = new ArrayList<>();
    for (String groupCode :
        new String[] {
          SequenceRepository.RECONCILE_GROUP_DRAFT, SequenceRepository.RECONCILE_GROUP_FINAL
        }) {
      if (Query.of(com.axelor.apps.base.db.Sequence.class)
              .filter(
                  "self.codeSelect = :code AND (self.company IS NULL OR self.company = :company)")
              .bind("code", groupCode)
              .bind("company", company)
              .count()
          == 0) {
        missingSequences.add(groupCode);
      }
    }
    if (reconcileSequences == 0) {
      missingSequences.add(SequenceRepository.RECONCILE);
    } else if (reconcileSequences > 1) {
      report.note(
          "WARNING: "
              + reconcileSequences
              + " reconcile последователности са дефинирани за компанията; равненията могат да"
              + " останат UNRESOLVED, докато операторът не остави една.");
    }
    if (!missingSequences.isEmpty()) {
      report.note(
          "WARNING: липсват последователности "
              + missingSequences
              + ". Moves ще бъдат запазени, а засегнатите равнения ще бъдат UNRESOLVED.");
    }
  }

  private boolean settlementEndpointAvailable(
      ExportRoot root, Company company, String eik, Integer sourceEntryId, String accountNumber) {
    for (Entry entry : root.entries) {
      if (!Objects.equals(entry.sourceId, sourceEntryId)) {
        continue;
      }
      return entry.lines.stream()
          .anyMatch(
              line ->
                  Objects.equals(line.accountNumber, accountNumber)
                      && MicroinvestAccountingImportService.isPostable(line));
    }
    if (hasOpeningMove(root)) {
      long openingMatches =
          root.openingBalances.stream()
              .filter(
                  line ->
                      Objects.equals(line.accountingId, sourceEntryId)
                          && Objects.equals(line.accountNumber, accountNumber)
                          && (nz(line.debit).signum() != 0 || nz(line.credit).signum() != 0))
              .count();
      if (openingMatches == 1) {
        return true;
      }
    }
    String moveImportId =
        MicroinvestAccountingSourceKey.of(
            eik, root.source.database, MicroinvestAccountingSourceKey.TYPE_ENTRY, sourceEntryId);
    if (Query.of(MoveLine.class)
            .filter(
                "self.move.company = :company AND self.move.importId = :moveId"
                    + " AND self.account.code = :account")
            .bind("company", company)
            .bind("moveId", moveImportId)
            .bind("account", accountNumber)
            .count()
        > 0) {
      return true;
    }
    return Query.of(MoveLine.class)
            .filter(
                "self.move.company = :company AND self.move.importOrigin = :origin"
                    + " AND self.bgSourceAccountingId = :sourceId"
                    + " AND self.account.code = :account")
            .bind("company", company)
            .bind("origin", root.source.database)
            .bind("sourceId", sourceEntryId)
            .bind("account", accountNumber)
            .count()
        == 1;
  }

  // ───────────────────────────────────────────────────────────────────────────────────────────────

  /** The Bulstat if usable, else the VAT number if usable, else null. */
  static String usableIdentifier(MicroinvestAccountingSourceModels.Partner partner) {
    String bulstat = normalizeIdentifier(partner.bulstat);
    if (bulstat != null) {
      return bulstat;
    }
    return normalizeIdentifier(partner.vatNumber);
  }

  /** Uppercased, stripped of spaces and a leading BG, with the sentinel rejected. */
  static String normalizeIdentifier(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    if (cleaned.startsWith("BG")) {
      cleaned = cleaned.substring(2);
    }
    if (cleaned.isEmpty() || SENTINEL_IDENTIFIER.equals(cleaned)) {
      return null;
    }
    return cleaned;
  }

  static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String first(Set<?> values, int limit) {
    List<String> out = new ArrayList<>();
    for (Object value : values) {
      if (out.size() >= limit) {
        out.add("...");
        break;
      }
      out.add(String.valueOf(value));
    }
    return String.join(", ", out);
  }
}
