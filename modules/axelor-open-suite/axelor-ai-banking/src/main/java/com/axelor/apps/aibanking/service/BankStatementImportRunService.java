package com.axelor.apps.aibanking.service;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.BankStatementLine;
import com.axelor.apps.bankpayment.service.bankstatement.BankStatementImportService;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a bank statement's import — the Import button, through Axelor's own service.
 *
 * <h2>How the operation is scoped</h2>
 *
 * A statement now carries a company of its own (this module's domain extension), but it still never
 * names a bank account — the account appears only on the lines the import is about to create. So
 * the caller names the account the file is expected to be for, and the checks run in three parts:
 *
 * <ol>
 *   <li><b>The account is this company's.</b> The named {@link BankDetails} must belong to the
 *       active company: it turns "import file number 12" into "import file number 12 into this
 *       company's account", which is a scoped instruction.
 *   <li><b>The account is the statement's company's.</b> A statement marked for company A is not
 *       imported into an account of company B, even by somebody entitled to both. A statement with
 *       no company — a row predating the field — has nothing to contradict, and gets the account's
 *       company written onto it once the import has proved it.
 *   <li><b>Afterwards, every line sits on that account.</b> A file whose IBAN resolves elsewhere is
 *       caught here rather than discovered later by whoever reconciles it.
 * </ol>
 *
 * <h2>Atomicity, and the format that cannot have it</h2>
 *
 * Both the import and the after-check run inside <em>this</em> method's transaction. That is what
 * {@code @Transactional(rollbackOn = Exception.class)} below is for: {@code
 * BankStatementImportAFB120Service.runImport} carries the same annotation, and Guice-persist's
 * interceptor joins an already-open transaction rather than starting a second one, so the inner
 * call no longer commits when it returns. The after-check therefore rolls the whole import back —
 * lines, balances, status and all. Without the outer annotation the inner one commits first and
 * throwing afterwards changes nothing, which is what this class did before.
 *
 * <p>The CSV path cannot be made to behave that way and is refused instead. {@code
 * BankStatementImportClassicService} runs Axelor's {@code CSVImporter}, which drives the raw {@code
 * EntityTransaction} itself and <b>commits every batch</b> — {@code CSVImporter} lines 344-356:
 * commit, clear, begin again. Wrapping it would not merely fail to roll back; the importer would
 * commit this method's transaction at the first batch boundary and carry on inside a transaction
 * this code never opened. Batch committing is deliberate there — it is how a large file imports
 * without exhausting memory — so the honest response is not to run it from here. Such a statement
 * is imported from the Bank statement form, where a person sees the result.
 */
@Singleton
public class BankStatementImportRunService {

  private static final Logger LOG = LoggerFactory.getLogger(BankStatementImportRunService.class);

  /**
   * The file formats whose import can be rolled back.
   *
   * <p>{@code BankStatementImportService.runImport} routes {@code camt.xxx.cfonb120.rep} and {@code
   * .stm} to the AFB120 service, which is transactional; everything else goes to the classic CSV
   * importer, which is not and cannot be. Matching the prefix here mirrors the routing there — if
   * that routing gains another transactional format, this is the line to widen.
   */
  private static final String ATOMIC_FORMAT_PREFIX = "camt.xxx.cfonb120";

  /**
   * Imports the statement, atomically.
   *
   * <p>The transaction is this method's, so everything Axelor's importer writes and everything the
   * after-check rejects live and die together. See the class javadoc for why the inner service's
   * own annotation is not enough, and why the CSV path is refused rather than wrapped.
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> runImport(long bankStatementId, long bankDetailsId) {
    BankDetails details = requireOwnBankDetails(bankDetailsId);
    BankStatement statement = requireImportable(bankStatementId);
    requireAccountOfStatementCompany(statement, details, bankStatementId);
    requireAtomicFormat(statement, bankStatementId);

    try {
      // alertIfFormatNotSupported = true, the same argument the controller passes, so an unknown
      // format is refused with Axelor's own message rather than silently doing nothing.
      Beans.get(BankStatementImportService.class).runImport(statement, true);
    } catch (Exception e) {
      // Named by id, not by name: at this point the statement still has no lines and so no proven
      // company, and echoing its name would put a record this caller may not own into the answer.
      throw AiBankingBlocked.of(
          AiBlockerCode.BANK_STATEMENT_IMPORT_REFUSED,
          "Axelor refused to import bank statement "
              + bankStatementId
              + ": "
              + message(e)
              + " Nothing was imported.");
    }

    JPA.flush();
    JPA.refresh(statement);
    requireLinesOnExpectedAccount(statement, details, bankStatementId);

    // A legacy statement that arrived with no company now has one, proved rather than assumed: its
    // lines have just been checked to sit entirely on this account, and the account names the
    // company. Statements created since the field exists already have it, and it is never changed.
    if (statement.getCompany() == null) {
      statement.setCompany(details.getCompany());
    }

    AiWriteEffects.touched(BankStatement.class.getName(), statement.getId());
    LOG.info(
        "AI Accountant: bank statement {} imported into {} by {}.",
        statement.getId(),
        details.getIban(),
        AiExecutionContext.callerDescription());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("imported", true);
    out.putAll(Beans.get(BankStatementViewService.class).detail(statement));
    out.put(
        "nextStep",
        "The statement is IMPORTED. Its lines are not accounting entries — they become accounting"
            + " only through bank reconciliation, which is a separate screen and not something"
            + " this assistant does.");
    return out;
  }

  /**
   * The statement's status, read through the same company scoping the import uses.
   *
   * <p>The baseline a lost import is judged against. An import moves RECEIVED to IMPORTED and
   * creates the lines, so the status before the call is what makes the reading afterwards
   * unambiguous.
   *
   * @return the status, or null when the statement cannot be resolved in the active company
   */
  public Integer scopedStatus(long bankStatementId) {
    BankStatement statement =
        Beans.get(BankStatementSearchService.class).findScoped(bankStatementId);
    return statement == null ? null : statement.getStatusSelect();
  }

  /**
   * The same answer {@link #runImport} returns, rebuilt by re-reading the statement.
   *
   * <p>For an import whose answer was lost. Axelor's own duplicate and continuity checks exist
   * precisely because importing a statement twice is harmful, so "did it happen?" has to be
   * answered rather than guessed at — and it is answerable: the status moved and the lines are
   * there, both readable, both company-scoped.
   */
  public Map<String, Object> confirmedResult(long bankStatementId) {
    BankStatement statement =
        Beans.get(BankStatementSearchService.class).findScoped(bankStatementId);
    if (statement == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("imported", true);
    out.putAll(Beans.get(BankStatementViewService.class).detail(statement));
    out.put(
        "nextStep",
        "The statement is IMPORTED. Its lines are not accounting entries — they become accounting"
            + " only through bank reconciliation, which is a separate screen and not something"
            + " this assistant does. NOTE: the connection was lost while the import was running, so"
            + " this was confirmed by re-reading the statement rather than reported by the import"
            + " itself — check the line count against the file before reconciling.");
    return out;
  }

  // ---------------------------------------------------------------------------
  // The checks
  // ---------------------------------------------------------------------------

  /** The account the caller named, and the whole reason this operation can be scoped at all. */
  protected BankDetails requireOwnBankDetails(long bankDetailsId) {
    BankDetails details = Beans.get(BankDetailsRepository.class).find(bankDetailsId);
    // Absent and another company's are answered identically: knowing an id from another company
    // must not confirm that the account exists.
    if (details == null || !AiCompanyScope.owns(details.getCompany())) {
      throw AiBankingBlocked.of(
          AiBlockerCode.RECORD_NOT_FOUND,
          "No bank account with id "
              + bankDetailsId
              + " in the active company. Nothing was imported.");
    }
    return details;
  }

  /**
   * Received, with a file and a format — the Import button's own conditions.
   *
   * <p>Checked here so the refusal names what is missing, rather than letting Axelor's importer
   * report it after the record has been touched.
   */
  protected BankStatement requireImportable(long bankStatementId) {
    // Reaches only statements with no lines, which is the set that belongs to no company and the
    // only state this operation is for. An imported statement — this company's or anyone else's —
    // has lines, misses this filter, and is answered as not found, so a caller cannot use this
    // path to learn anything about one.
    BankStatement statement =
        Beans.get(BankStatementSearchService.class).findImportCandidate(bankStatementId);
    if (statement == null) {
      throw AiBankingBlocked.of(
          AiBlockerCode.RECORD_NOT_FOUND,
          "No bank statement with id "
              + bankStatementId
              + " is waiting to be imported. A statement that has already been imported cannot be"
              + " imported again. Nothing was imported.");
    }
    // Every message below names the id only. The statement has no lines yet, so nothing has proved
    // it is this company's, and its name is not this caller's to be told.
    if (statement.getBankStatementFile() == null) {
      throw AiBankingBlocked.of(
          AiBlockerCode.BANK_STATEMENT_NOT_IMPORTABLE,
          "Bank statement "
              + bankStatementId
              + " has no file attached, so there is nothing to import. Attaching the file is done"
              + " on the Bank statement form; this assistant cannot do it.");
    }
    if (statement.getBankStatementFileFormat() == null) {
      throw AiBankingBlocked.of(
          AiBlockerCode.BANK_STATEMENT_NOT_IMPORTABLE,
          "Bank statement "
              + bankStatementId
              + " has no file format, so Axelor cannot tell how to read the file. Set it on the"
              + " form first.");
    }
    return statement;
  }

  /**
   * The named account belongs to the statement's own company.
   *
   * <p>{@link #requireOwnBankDetails} already establishes that the account is the active company's,
   * and {@link #requireImportable} that the statement is not another company's. This closes the
   * remaining gap: a statement explicitly marked for company A must not be imported into an account
   * of company B, even by someone entitled to both. When the statement has no company — a row that
   * predates the field — there is nothing to contradict, and the account decides instead; the
   * company is written onto the statement afterwards, once the imported lines have proved it.
   */
  protected void requireAccountOfStatementCompany(
      BankStatement statement, BankDetails details, long bankStatementId) {
    Company owner = statement.getCompany();
    if (owner == null || owner.getId() == null) {
      return;
    }
    Company account = details.getCompany();
    if (account != null && owner.getId().equals(account.getId())) {
      return;
    }
    throw AiBankingBlocked.of(
        AiBlockerCode.BANK_STATEMENT_WRONG_BANK_DETAILS,
        "Bank statement "
            + bankStatementId
            + " belongs to "
            + owner.getName()
            + ", but the bank account given is not that company's. Import it into one of "
            + owner.getName()
            + "'s accounts, or correct the company on the statement first. Nothing was imported.");
  }

  /**
   * Only a format whose import can be undone.
   *
   * <p>CFONB120 imports inside a transaction this method owns, so a failed account check rolls it
   * back completely. The CSV importer commits in batches and cannot be rolled back at all, so it is
   * refused here rather than run and reported on afterwards — "no lines or status changes may
   * remain" is a guarantee or it is nothing.
   */
  protected void requireAtomicFormat(BankStatement statement, long bankStatementId) {
    String format = statement.getBankStatementFileFormat().getStatementFileFormatSelect();
    if (format != null && format.startsWith(ATOMIC_FORMAT_PREFIX)) {
      return;
    }
    throw AiBankingBlocked.of(
        AiBlockerCode.BANK_STATEMENT_FORMAT_NOT_ATOMIC,
        "Bank statement "
            + bankStatementId
            + " uses the file format '"
            + format
            + "', which Axelor imports in batches that are committed as it goes and cannot be"
            + " undone. This assistant only runs imports it can roll back completely, so import"
            + " this one from the Bank statement form, where the result is visible immediately."
            + " Nothing was imported.");
  }

  /**
   * Every imported line sits on the account the caller named.
   *
   * <p>The second half of the scope check. A statement whose file turns out to be for another
   * account — or another company — is stopped here rather than left for whoever reconciles it.
   */
  protected void requireLinesOnExpectedAccount(
      BankStatement statement, BankDetails expected, long bankStatementId) {
    List<BankStatementLine> lines = Beans.get(BankStatementViewService.class).linesOf(statement);
    if (lines.isEmpty()) {
      throw AiBankingBlocked.of(
          AiBlockerCode.BANK_STATEMENT_IMPORT_REFUSED,
          "The import produced no lines for bank statement "
              + bankStatementId
              + ", so the file was empty or unreadable. Nothing was imported.");
    }
    for (BankStatementLine line : lines) {
      BankDetails actual = line.getBankDetails();
      if (actual != null && actual.getId().equals(expected.getId())) {
        continue;
      }
      // Throwing rolls this method's transaction back, and the import ran inside it, so the lines
      // and the IMPORTED status go with it. The other account is named only by whether it is the
      // expected one — its IBAN belongs to whoever owns it, and this caller has just been told the
      // statement is not theirs.
      throw AiBankingBlocked.of(
          AiBlockerCode.BANK_STATEMENT_WRONG_BANK_DETAILS,
          "Bank statement "
              + bankStatementId
              + " is not for "
              + expected.getIban()
              + ": at least one imported line is on a different account. The import has been"
              + " rolled back and nothing was changed. Check which account the file is really for"
              + " before importing it again.");
    }
  }

  private static String message(Exception e) {
    String message = e.getMessage();
    return message == null || message.trim().isEmpty()
        ? e.getClass().getSimpleName()
        : message.trim();
  }
}
