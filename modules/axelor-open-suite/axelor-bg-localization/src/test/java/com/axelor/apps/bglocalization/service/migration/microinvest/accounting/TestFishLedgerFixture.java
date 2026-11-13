package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.JournalTypeRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Entry;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Line;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppAccount;
import com.axelor.studio.db.AppBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

/**
 * A complete, temporary Тест Фиш ООД configured to post the 2026 ledger, built from scratch in a
 * SCRATCH PostgreSQL database — never the real {@code nyxa} one.
 *
 * <p>This exists because nothing in the repository could post a Move before: {@code
 * MoveValidateService} appeared in no test at all, and everything it needs — a company, a chart of
 * accounts, account types, journals with their sequences and authorised origins, a fiscal year with
 * open periods, an accounting configuration and an authenticated user — had to be assembled first.
 *
 * <p>Three configuration decisions here are deliberate and are what the production operator will
 * have to reproduce:
 *
 * <ul>
 *   <li><b>Every Microinvest account number is created exactly as the source spells it</b>, {@code
 *       453/1} and {@code 405/1/1} included, together with the parent levels they need. Nothing is
 *       collapsed onto a synthetic parent: {@code 503/2} and {@code 503/3} are different banks and
 *       {@code 601/1..5} are different expense groups, so collapsing would destroy information that
 *       cannot be recovered afterwards.
 *   <li><b>PCH authorises account 304 by name</b>, through {@code validAccountSet} — not by adding
 *       the whole {@code currentAsset} type to {@code validAccountTypeSet}, which would silently
 *       let every stock account through the purchase journal forever after.
 *   <li><b>Exactly one {@code reconcile} sequence exists.</b> The live company 2 carries two, and
 *       {@code Sequence.find(codeSelect, company)} is a {@code fetchOne()} over a non-unique key,
 *       so which one numbers the reconciliations would be decided by query order. Preflight blocks
 *       on that; the fixture configures it away rather than papering over it.
 * </ul>
 *
 * <p>{@code accountingDaybook} is left ON, matching the live deployment, precisely so the tests
 * exercise the DAYBOOK to ACCOUNTED double transition instead of avoiding it.
 */
final class TestFishLedgerFixture {

  static final String EIK = "100000065";
  static final String VAT = "BG100000065";
  static final String COMPANY_NAME = "Тест Фиш България ООД";
  static final String USER_CODE = "mi-import";

  Company company;
  Currency eur;
  User user;
  AppBase appBase;
  final Map<String, Account> accounts = new LinkedHashMap<>();
  final Map<String, Journal> journals = new LinkedHashMap<>();

  /** Journal codes the 2026 mapping can reach, with the origins each must authorise. */
  private static final String[][] JOURNALS = {
    {"SAL", "Продажби", "3,7"},
    {"PCH", "Покупки", "4"},
    {"BNK", "Банка", "5"},
    {"CSH", "Каса", "5"},
    {"MOM", "Мемориален ордер", "6,7"},
    {"VAT", "ДДС", "5,6,7"},
    {"RPB", "Начални салда", "1,2"},
    {"RFS", "Кредитни известия продажби", "3,7"},
    {"RFP", "Кредитни известия покупки", "4"},
    {"FIS", "Отчет продажби", "5"},
  };

  // ── Build ──────────────────────────────────────────────────────────────────────────────────────

  /**
   * Creates the whole company, every piece of it derived from the export file.
   *
   * <p>Nothing here is a guess: the chart comes from {@code root.accounts}, the VAT rates from the
   * rates the lines actually carry, and the currency-conversion windows from the exchange rates the
   * source itself publishes per entry. The operator setting up production has to reproduce exactly
   * this list, which is why it is built from the file rather than hard-coded.
   */
  TestFishLedgerFixture build(ExportRoot root, int year) {
    List<String> accountNumbers = new ArrayList<>();
    for (MicroinvestAccountingSourceModels.Account account : root.accounts) {
      accountNumbers.add(account.number);
    }
    apps();
    eur = currency("EUR", "Евро");
    // FY2026 carries foreign-currency lines in USD and in BGN (the latter through the changeover).
    // The importer refuses to record a foreign amount under the wrong currency, so both must exist
    // before a single move is posted — exactly what the production operator has to set up too.
    currency("USD", "Щатски долар");
    currency("BGN", "Български лев");
    company = company();
    accountTypes();
    chartOfAccounts(accountNumbers);
    journals();
    fiscalYear(year);
    accountConfig();
    taxes(root);
    currencyConversions(root, year);
    reconcileSequence();
    user = user();
    JPA.flush();
    authenticate();
    return this;
  }

  /**
   * Binds a Shiro subject so {@code AuthUtils.getUser()} resolves.
   *
   * <p>Not decoration: {@code PeriodCheckServiceImpl.isAuthorizedToAccountOnPeriod} returns FALSE
   * when the user is null, so without this every non-opening move fails with "period is closed",
   * and {@code MicroinvestAccountingPreflightService.activeCompany()} would find no company either.
   */
  void authenticate() {
    DefaultSecurityManager securityManager = new DefaultSecurityManager();
    ThreadContext.bind(securityManager);
    ThreadContext.bind(
        new Subject.Builder(securityManager)
            .principals(new SimplePrincipalCollection(USER_CODE, "test"))
            .authenticated(true)
            .buildSubject());
  }

  static void deauthenticate() {
    ThreadContext.remove();
  }

  // ── Pieces ─────────────────────────────────────────────────────────────────────────────────────

  /**
   * The {@code App} rows the save chain depends on.
   *
   * <p>Not scaffolding. {@code PartnerServiceImpl.onSave} dereferences {@code
   * appBaseService.getAppBase()} unconditionally, so with no AppBase row creating a partner throws
   * a NullPointerException — wrapped twice into a PersistenceException with a null message, which
   * is what an operator would otherwise see. And {@code PartnerAccountRepository.save} builds the
   * AccountingSituation only when the "account" app is active, so without it the test would post
   * moves against partners that production would have set up differently.
   */
  private void apps() {
    App base = new App();
    base.setCode("base");
    base.setName("Base");
    base.setActive(true);
    JPA.save(base);

    appBase = new AppBase();
    appBase.setApp(base);
    // Matches the live deployment: partner codes are sequence-generated, which is exactly why the
    // "partner" sequence below has to exist before the first partner is created.
    appBase.setGeneratePartnerSequence(true);
    appBase = JPA.save(appBase);

    App account = new App();
    account.setCode("account");
    account.setName("Account");
    account.setActive(true);
    JPA.save(account);

    AppAccount appAccount = new AppAccount();
    appAccount.setApp(account);
    JPA.save(appAccount);
  }

  private Currency currency(String code, String name) {
    Currency currency = new Currency();
    currency.setCode(code);
    currency.setCodeISO(code);
    currency.setName(name);
    currency.setSymbol(code);
    // ⚠️ Without this the generated getter returns 0 and Axelor rounds every converted amount to
    // whole units: 0.05 becomes 0.00 (which posting rejects as a zero line) and 100.77 becomes 101
    // (which it accepts, silently altering the books). Two decimals is what the source uses.
    currency.setNumberOfDecimals(2);
    return JPA.save(currency);
  }

  private Company company() {
    Company created = new Company();
    created.setName(COMPANY_NAME);
    created.setCode("LF");
    created.setCurrency(eur);
    created = JPA.save(created);

    // Партньорът на дружеството е в самото дружество (всеки партньор е на едно дружество),
    // затова се създава СЛЕД него.
    Partner partner = new Partner();
    partner.setName(COMPANY_NAME);
    partner.setFullName(COMPANY_NAME);
    partner.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
    partner.setRegistrationCode(EIK);
    partner.setTaxNbr(VAT);
    partner.setIsContact(false);
    partner.setCompany(created);
    JPA.save(partner);

    created.setPartner(partner);
    return JPA.save(created);
  }

  private void accountTypes() {
    for (String technical :
        new String[] {
          AccountTypeRepository.TYPE_VIEW,
          AccountTypeRepository.TYPE_EQUITY,
          AccountTypeRepository.TYPE_IMMOBILISATION,
          AccountTypeRepository.TYPE_CURRENT_ASSET,
          AccountTypeRepository.TYPE_PAYABLE,
          AccountTypeRepository.TYPE_RECEIVABLE,
          AccountTypeRepository.TYPE_TAX,
          AccountTypeRepository.TYPE_CASH,
          AccountTypeRepository.TYPE_CHARGE,
          AccountTypeRepository.TYPE_INCOME,
          AccountTypeRepository.TYPE_DEBT
        }) {
      AccountType type = new AccountType();
      type.setName(technical);
      type.setTechnicalTypeSelect(technical);
      JPA.save(type);
      accountTypeByTechnical.put(technical, type);
    }
  }

  private final Map<String, AccountType> accountTypeByTechnical = new LinkedHashMap<>();

  /**
   * Creates every source account number verbatim, parents first.
   *
   * <p>{@code 405/1/1} needs {@code 405/1} which needs {@code 405}; the intermediate levels are
   * created as {@code view} accounts so they carry no balance of their own but give the analytical
   * account the hierarchy Axelor expects.
   */
  private void chartOfAccounts(List<String> accountNumbers) {
    Set<String> wanted = new LinkedHashSet<>();
    for (String number : accountNumbers) {
      String[] parts = number.split("/");
      StringBuilder prefix = new StringBuilder();
      for (String part : parts) {
        if (prefix.length() > 0) {
          prefix.append('/');
        }
        prefix.append(part);
        wanted.add(prefix.toString());
      }
    }
    Set<String> leaves = new HashSet<>(accountNumbers);
    // Shortest first, so a parent always exists before its child.
    List<String> ordered = new ArrayList<>(wanted);
    ordered.sort((a, b) -> a.length() != b.length() ? a.length() - b.length() : a.compareTo(b));

    for (String number : ordered) {
      boolean isLeaf = leaves.contains(number);
      Account account = new Account();
      account.setCode(number);
      account.setName("Сметка " + number);
      account.setCompany(company);
      account.setStatusSelect(AccountRepository.STATUS_ACTIVE);
      account.setAccountType(
          accountTypeByTechnical.get(
              isLeaf ? technicalTypeFor(number) : AccountTypeRepository.TYPE_VIEW));
      int slash = number.lastIndexOf('/');
      if (slash > 0) {
        account.setParentAccount(accounts.get(number.substring(0, slash)));
      }
      // Partner balance drives InvoiceTerm creation on posting; reconcileOk is what lets a
      // settlement reconcile the line afterwards. Both belong on the payable/receivable accounts.
      String technical = technicalTypeFor(number);
      boolean partnerAccount =
          isLeaf
              && (AccountTypeRepository.TYPE_PAYABLE.equals(technical)
                  || AccountTypeRepository.TYPE_RECEIVABLE.equals(technical));
      account.setUseForPartnerBalance(partnerAccount);
      account.setReconcileOk(partnerAccount);
      accounts.put(number, JPA.save(account));
    }
  }

  /** Bulgarian national chart, by leading digits. */
  private static String technicalTypeFor(String number) {
    String head = number.split("/")[0];
    if (head.startsWith("10") || head.startsWith("12")) {
      return AccountTypeRepository.TYPE_EQUITY;
    }
    if (head.startsWith("15")) {
      return AccountTypeRepository.TYPE_DEBT;
    }
    if (head.startsWith("2")) {
      return AccountTypeRepository.TYPE_IMMOBILISATION;
    }
    if (head.startsWith("3")) {
      return AccountTypeRepository.TYPE_CURRENT_ASSET;
    }
    if (head.startsWith("40")) {
      return AccountTypeRepository.TYPE_PAYABLE;
    }
    if (head.startsWith("41")) {
      return AccountTypeRepository.TYPE_RECEIVABLE;
    }
    // ⚠️ Only the genuine tax accounts get TYPE_TAX. In the Bulgarian chart 452/453/454 are VAT and
    // profit tax, but 455/456/457/459 are social security, 46x are settlements with insurers and
    // 43x are payroll — payables, not taxes. Typing those as tax would drag them into Axelor's rule
    // that every tax-account line in a sale or purchase move must carry a tax line, which is simply
    // untrue of a social-security liability.
    if (head.startsWith("452") || head.startsWith("453") || head.startsWith("454")) {
      return AccountTypeRepository.TYPE_TAX;
    }
    if (head.startsWith("43") || head.startsWith("45") || head.startsWith("46")) {
      return AccountTypeRepository.TYPE_PAYABLE;
    }
    if (head.startsWith("49")) {
      return AccountTypeRepository.TYPE_PAYABLE;
    }
    if (head.startsWith("5")) {
      return AccountTypeRepository.TYPE_CASH;
    }
    if (head.startsWith("6")) {
      return AccountTypeRepository.TYPE_CHARGE;
    }
    if (head.startsWith("7")) {
      return AccountTypeRepository.TYPE_INCOME;
    }
    return AccountTypeRepository.TYPE_CURRENT_ASSET;
  }

  private void journals() {
    JournalType journalType = new JournalType();
    journalType.setCode("MI");
    journalType.setName("Миграция");
    journalType.setTechnicalTypeSelect(JournalTypeRepository.TECHNICAL_TYPE_SELECT_OTHER);
    JPA.save(journalType);

    // Every type actually used by the 2026 chart, EXCEPT currentAsset: account 304 is authorised by
    // name on PCH below, which is the narrow permission the ledger really needs.
    Set<AccountType> broadlyValid =
        new LinkedHashSet<>(
            List.of(
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_VIEW),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_EQUITY),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_IMMOBILISATION),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_PAYABLE),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_RECEIVABLE),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_TAX),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_CASH),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_CHARGE),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_INCOME),
                accountTypeByTechnical.get(AccountTypeRepository.TYPE_DEBT)));

    for (String[] spec : JOURNALS) {
      Sequence sequence =
          sequence("move-" + spec[0].toLowerCase(), SequenceRepository.MOVE, spec[0]);

      Journal journal = new Journal();
      journal.setCode(spec[0]);
      journal.setName(spec[1]);
      journal.setCompany(company);
      journal.setJournalType(journalType);
      journal.setSequence(sequence);
      journal.setStatusSelect(1);
      journal.setAuthorizedFunctionalOriginSelect(spec[2]);
      journal.setAllowAccountingDaybook(true);
      journal.setHasDuplicateDetectionOnOrigin(false);
      journal.setValidAccountTypeSet(new LinkedHashSet<>(broadlyValid));
      if ("RPB".equals(spec[0])) {
        // The opening move touches literally every account in the chart, stock accounts included.
        journal
            .getValidAccountTypeSet()
            .add(accountTypeByTechnical.get(AccountTypeRepository.TYPE_CURRENT_ASSET));
      }
      journals.put(spec[0], JPA.save(journal));
    }

    // ⚠️ Narrow by design: the two stock accounts the 2026 purchases actually touch are authorised
    // on PCH BY NAME — 304 Стоки and 302 Материали — instead of adding the whole currentAsset type
    // to validAccountTypeSet. Widening the type would authorise every present and future stock
    // account on the purchase journal, a permanent loosening of the company's controls made for a
    // one-off migration. Preflight names any account it needs, so the list stays auditable.
    Journal purchases = journals.get("PCH");
    Set<Account> validAccounts = new LinkedHashSet<>();
    for (String code : new String[] {"304", "302"}) {
      Account stock = accounts.get(code);
      if (stock != null) {
        validAccounts.add(stock);
      }
    }
    purchases.setValidAccountSet(validAccounts);
    JPA.save(purchases);
  }

  private Sequence sequence(String code, String codeSelect, String prefix) {
    Sequence sequence = new Sequence();
    sequence.setName(code);
    sequence.setCodeSelect(codeSelect);
    sequence.setCompany(company);
    sequence.setPrefixe(prefix + "/");
    sequence.setPadding(6);
    sequence.setToBeAdded(1);
    JPA.save(sequence);

    SequenceVersion version = new SequenceVersion();
    version.setSequence(sequence);
    version.setStartDate(LocalDate.of(2000, 1, 1));
    version.setEndDate(LocalDate.of(2099, 12, 31));
    version.setNextNum(1L);
    JPA.save(version);
    return sequence;
  }

  /**
   * The VAT rates the 2026 file actually uses.
   *
   * <p>Axelor requires a tax line on every tax-account move line in a sale or purchase move ({@code
   * MoveLineTaxServiceImpl.isMoveLineTaxAccountRequired}), so the rates the source records — 20%
   * and 0% — have to exist as {@code Tax}/{@code TaxLine} before the ledger can be posted. The
   * importer matches them by rate; it never invents one.
   */
  private void taxes(ExportRoot root) {
    Set<Integer> rates = new java.util.TreeSet<>();
    for (Entry entry : root.entries) {
      for (Line line : entry.lines) {
        rates.add(line.vatRate == null ? 0 : line.vatRate);
      }
    }
    for (int rate : rates) {
      Tax tax = new Tax();
      tax.setCode("BG-VAT-" + rate);
      tax.setName("ДДС " + rate + "%");
      JPA.save(tax);

      TaxLine taxLine = new TaxLine();
      taxLine.setTax(tax);
      taxLine.setName("ДДС " + rate + "%");
      taxLine.setValue(new BigDecimal(rate));
      taxLine.setStartDate(LocalDate.of(2000, 1, 1));
      JPA.save(taxLine);

      tax.setActiveTaxLine(taxLine);
      JPA.save(tax);
    }
  }

  /**
   * One {@code CurrencyConversionLine} per rate the source actually published, in non-overlapping
   * date windows covering the whole year.
   *
   * <p>Required, not decorative: {@code ReconcileInvoiceTermComputationServiceImpl} calls {@code
   * CurrencyService.getAmountCurrencyConvertedAtDate} for every reconciled line, so a foreign
   * currency with no conversion configured fails the settlement step — <b>after</b> all 762 moves
   * are already ACCOUNTED. Preflight blocks on it instead (see {@code checkForeignCurrencyUsable}).
   *
   * <p>⚠️ The rates are taken verbatim from the entries, never invented or looked up. A window runs
   * from the date its rate first appears until the day before the next rate appears, so FY2026's
   * 2026-08-26 rate of 0.86909 — which sits between two days of 0.87660 — gets a one-day window of
   * its own rather than being smoothed away.
   *
   * <p>Direction: the source states {@code currencyAmount x exchangeRate = base amount}, i.e. the
   * rate is foreign-to-company, so {@code startCurrency} is the foreign one.
   */
  private void currencyConversions(ExportRoot root, int year) {
    for (Map.Entry<String, Map<LocalDate, BigDecimal>> perCurrency : sourceRates(root).entrySet()) {
      Currency foreign =
          com.axelor.db.Query.of(Currency.class)
              .filter("self.codeISO = :iso")
              .bind("iso", perCurrency.getKey())
              .fetchOne();
      if (foreign == null) {
        continue; // Preflight reports the missing currency; nothing to configure against.
      }
      List<LocalDate> dates = new ArrayList<>(perCurrency.getValue().keySet());
      java.util.Collections.sort(dates);
      for (int i = 0; i < dates.size(); i++) {
        LocalDate from = i == 0 ? LocalDate.of(year, 1, 1) : dates.get(i);
        LocalDate to =
            i == dates.size() - 1 ? LocalDate.of(year, 12, 31) : dates.get(i + 1).minusDays(1);
        CurrencyConversionLine line = new CurrencyConversionLine();
        line.setAppBase(appBase);
        line.setStartCurrency(foreign);
        line.setEndCurrency(eur);
        line.setExchangeRate(perCurrency.getValue().get(dates.get(i)));
        line.setFromDate(from);
        line.setToDate(to);
        JPA.save(line);
        // ⚠️ Both sides. CurrencyServiceImpl never queries the table: it reads
        // AppBase.currencyConversionLineList, so a line that only carries the many-to-one is
        // invisible to every conversion inside the same session.
        appBase.addCurrencyConversionLineListItem(line);
        conversionLines++;
      }
    }
    JPA.save(appBase);
  }

  /** currency -> (first date the rate is seen -> rate), one entry per rate change. */
  private static Map<String, Map<LocalDate, BigDecimal>> sourceRates(ExportRoot root) {
    Map<String, Map<LocalDate, BigDecimal>> byCurrency = new LinkedHashMap<>();
    for (Entry entry : root.entries) {
      if (entry.currency == null || entry.exchangeRate == null || entry.date == null) {
        continue;
      }
      byCurrency
          .computeIfAbsent(entry.currency, code -> new java.util.TreeMap<>())
          .merge(entry.date, entry.exchangeRate, (existing, candidate) -> existing);
    }
    // Collapse runs of the same rate so only genuine changes open a new window.
    Map<String, Map<LocalDate, BigDecimal>> collapsed = new LinkedHashMap<>();
    for (Map.Entry<String, Map<LocalDate, BigDecimal>> e : byCurrency.entrySet()) {
      Map<LocalDate, BigDecimal> windows = new java.util.TreeMap<>();
      BigDecimal previous = null;
      for (Map.Entry<LocalDate, BigDecimal> point : e.getValue().entrySet()) {
        if (previous == null || previous.compareTo(point.getValue()) != 0) {
          windows.put(point.getKey(), point.getValue());
          previous = point.getValue();
        }
      }
      collapsed.put(e.getKey(), windows);
    }
    return collapsed;
  }

  /** How many conversion lines the fixture configured — asserted by the tests. */
  int conversionLines;

  /** Exactly ONE reconcile sequence — see the class comment. */
  private void reconcileSequence() {
    sequence("reconcile", SequenceRepository.RECONCILE, "REC");
    // Confirming a reconcile puts it in a ReconcileGroup, which numbers itself from these two.
    // Without them the very first settlement fails with "no configured sequence for temporary
    // reconcile group" — after all 762 moves have already been posted.
    sequence("reconcileGroupDraft", SequenceRepository.RECONCILE_GROUP_DRAFT, "RGD");
    sequence("reconcileGroupFinal", SequenceRepository.RECONCILE_GROUP_FINAL, "RGF");
    // Required because AppBase.generatePartnerSequence is on: PartnerServiceImpl.onSave refuses to
    // save a partner without a number. The company's own sequence — there is no common one.
    Sequence partnerSequence = new Sequence();
    partnerSequence.setCompany(company);
    partnerSequence.setName("partner");
    partnerSequence.setCodeSelect(SequenceRepository.PARTNER);
    partnerSequence.setPrefixe("P");
    partnerSequence.setPadding(6);
    partnerSequence.setToBeAdded(1);
    JPA.save(partnerSequence);

    SequenceVersion version = new SequenceVersion();
    version.setSequence(partnerSequence);
    version.setStartDate(LocalDate.of(2000, 1, 1));
    version.setEndDate(LocalDate.of(2099, 12, 31));
    version.setNextNum(1L);
    JPA.save(version);
  }

  private void fiscalYear(int year) {
    Year fiscalYear = new Year();
    fiscalYear.setName("FY" + year);
    fiscalYear.setCode("FY" + year);
    fiscalYear.setCompany(company);
    fiscalYear.setFromDate(LocalDate.of(year, 1, 1));
    fiscalYear.setToDate(LocalDate.of(year, 12, 31));
    fiscalYear.setTypeSelect(YearRepository.TYPE_FISCAL);
    fiscalYear.setStatusSelect(YearRepository.STATUS_OPENED);
    JPA.save(fiscalYear);

    for (int month = 1; month <= 12; month++) {
      LocalDate from = LocalDate.of(year, month, 1);
      Period period = new Period();
      period.setName(from.toString().substring(0, 7));
      period.setCode("P" + month + "-" + year);
      period.setYear(fiscalYear);
      period.setFromDate(from);
      period.setToDate(from.withDayOfMonth(from.lengthOfMonth()));
      period.setStatusSelect(PeriodRepository.STATUS_OPENED);
      JPA.save(period);
    }
  }

  private void accountConfig() {
    AccountConfig config = new AccountConfig();
    config.setCompany(company);
    config.setCustomerAccount(accounts.get("411"));
    config.setSupplierAccount(accounts.get("401"));
    // Matches the live deployment, so the tests exercise the DAYBOOK to ACCOUNTED double transition
    // rather than avoiding it.
    config.setAccountingDaybook(true);
    config.setIsDescriptionRequired(false);
    JPA.save(config);
  }

  private User user() {
    Group group = new Group();
    group.setCode("mi-admins");
    group.setName("mi-admins");
    JPA.save(group);

    User created = new User();
    created.setCode(USER_CODE);
    created.setName("Microinvest import");
    created.setPassword("x");
    created.setGroup(group);
    created.setActiveCompany(company);
    created.setCompanySet(new HashSet<>(List.of(company)));
    return JPA.save(created);
  }

  // ── Helpers the tests read ─────────────────────────────────────────────────────────────────────

  long moveCount() {
    return com.axelor.db.Query.of(com.axelor.apps.account.db.Move.class)
        .filter("self.company = :company")
        .bind("company", company)
        .count();
  }

  long accountedMoveCount() {
    return com.axelor.db.Query.of(com.axelor.apps.account.db.Move.class)
        .filter("self.company = :company AND self.statusSelect = :status")
        .bind("company", company)
        .bind("status", MoveRepository.STATUS_ACCOUNTED)
        .count();
  }
}
