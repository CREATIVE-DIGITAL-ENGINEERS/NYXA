package com.axelor.apps.bglocalization.service.bankstatement;

import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.BankStatementLine;
import com.axelor.apps.bankpayment.db.repo.BankStatementLineRepository;
import com.axelor.apps.bankpayment.db.repo.BankStatementRepository;
import com.axelor.apps.bankpayment.service.bankstatementline.BankStatementLineCreationService;
import com.axelor.apps.base.db.Bank;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.apps.base.db.repo.BankRepository;
import com.axelor.apps.base.db.repo.CurrencyRepository;
import com.axelor.apps.bglocalization.service.bankstatement.BgPostbankStatementParser.BalanceCheck;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgBankMovement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgParsedStatement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgStatementImportResult;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgStatementRowError;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

/**
 * Imports a Bulgarian Postbank XLSX statement into the stock {@link BankStatement} / {@link
 * BankStatementLine} model. Reads the file with {@link BgXlsxGridReader}, parses it with the
 * bank-specific {@link BgPostbankStatementParser}, links lines to the matching {@link BankDetails}
 * by IBAN, protects against duplicate movements, validates balances from the running balance, and
 * records a BG import status + a row-level report. Never validates/ventilates/reconciles — it only
 * produces a saved draft the user reviews on the standard Bank Statement screen.
 */
@Singleton
public class BgBankStatementImportService {

  /** statementFileFormatSelect value that routes a BankStatement to this Postbank XLSX importer. */
  public static final String FORMAT_SELECT = "xlsx.bg.postbank";

  // bg.bank.statement.import.status.select
  public static final int STATUS_IMPORTED = 1;
  public static final int STATUS_VALIDATION_FAILED = 2;
  public static final int STATUS_MANUAL_EDIT_REQUIRED = 3;
  public static final int STATUS_DUPLICATE = 4;

  protected final BankStatementRepository bankStatementRepo;
  protected final BankStatementLineRepository bankStatementLineRepo;
  protected final BankDetailsRepository bankDetailsRepo;

  @Inject
  public BgBankStatementImportService(
      BankStatementRepository bankStatementRepo,
      BankStatementLineRepository bankStatementLineRepo,
      BankDetailsRepository bankDetailsRepo) {
    this.bankStatementRepo = bankStatementRepo;
    this.bankStatementLineRepo = bankStatementLineRepo;
    this.bankDetailsRepo = bankDetailsRepo;
  }

  /** Import the XLSX attached to the given statement (bankStatementFile) into it, in place. */
  @Transactional
  public BgStatementImportResult importInto(Long bankStatementId) throws Exception {
    BgStatementImportResult result = new BgStatementImportResult();
    BankStatement statement = bankStatementRepo.find(bankStatementId);
    if (statement == null) {
      throw new IllegalArgumentException(I18n.get("Bank statement not found."));
    }
    result.bankStatementId = statement.getId();

    MetaFile file = statement.getBankStatementFile();
    if (file == null) {
      throw new IllegalArgumentException(
          I18n.get("Attach the Postbank XLSX to \"File\" before importing."));
    }

    // 1) Read the grid (dependency-free; rejects a PDF renamed to .xlsx with a clear message).
    List<String[]> grid;
    Path path = MetaFiles.getPath(file);
    try (InputStream in = Files.newInputStream(path)) {
      grid = new BgXlsxGridReader().read(in);
    }

    // 1b) Reject re-importing the exact same file: hash the bytes and look for another statement
    // that was already imported from an identical file.
    String hash = sha256(path);
    statement.setBgStatementHash(hash);
    if (hash != null) {
      BankStatement existing =
          bankStatementRepo
              .all()
              .filter("self.bgStatementHash = :h AND self.id != :id")
              .bind("h", hash)
              .bind("id", statement.getId())
              .fetchOne();
      if (existing != null) {
        statement.setBgImportStatusSelect(STATUS_DUPLICATE);
        statement.setBgImportReport(
            String.format(
                I18n.get(
                    "This statement file was already imported (Bank Statement #%d) — nothing"
                        + " imported to avoid duplicates."),
                existing.getId()));
        result.importStatus = "manualEditRequired";
        result.addMessage(
            String.format(
                I18n.get("Duplicate statement: the same file is already imported (#%d)."),
                existing.getId()));
        return result;
      }
    }

    // 2) Parse (bank-specific) then persist through the shared movement→line pipeline.
    BgParsedStatement parsed = new BgPostbankStatementParser().parse(grid);
    return persistMovements(statement, parsed);
  }

  /**
   * Shared persistence: turn already-parsed {@link BgParsedStatement} movements into {@link
   * BankStatementLine}s on {@code statement}, with duplicate-skip, running-balance validation and
   * the BG status/report. Bank-source-agnostic — reused by the Postbank <b>XLSX</b> import ({@link
   * #importInto}) and by the <b>PDF/OCR</b> path ({@code OcrBankStatementCreatorImpl}), so the two
   * never rebuild statement creation independently. The statement must already be persisted (it
   * owns the lines). Never validates/reconciles; an incomplete/mismatched parse yields a reviewable
   * draft (STATUS_MANUAL_EDIT_REQUIRED / STATUS_VALIDATION_FAILED), never a failure that drops the
   * record.
   */
  @Transactional
  public BgStatementImportResult persistMovements(
      BankStatement statement, BgParsedStatement parsed) {
    BgStatementImportResult result = new BgStatementImportResult();
    result.bankStatementId = statement.getId();

    if (parsed == null || !parsed.recognized) {
      statement.setBgImportStatusSelect(STATUS_MANUAL_EDIT_REQUIRED);
      statement.setBgImportReport(
          I18n.get("Unrecognized statement layout — expected the movement columns.")
              + (parsed == null ? "" : report(parsed.errors)));
      statement.setStatusSelect(BankStatementRepository.STATUS_RECEIVED);
      bankStatementRepo.save(statement);
      result.importStatus = "manualEditRequired";
      result.manualCorrection = 1;
      result.addMessage(I18n.get("Unrecognized statement layout — review and complete manually."));
      return result;
    }

    // 3) Resolve the account + currency.
    BankDetails bankDetails = resolveBankDetails(parsed.iban);
    Currency currency = resolveCurrency(parsed.currencyCode);
    boolean bankDetailsMissing = bankDetails == null;

    // 4) Create lines, skipping duplicates (within this file and against already-imported lines).
    BankStatementLineCreationService lineCreation =
        Beans.get(BankStatementLineCreationService.class);
    int sequence = nextSequence(statement);
    Set<String> seenKeys = new HashSet<>();
    LocalDate minDate = null;
    LocalDate maxDate = null;

    for (BgBankMovement m : parsed.movements) {
      String key = BgPostbankStatementParser.duplicateKey(parsed.iban, m);
      if (seenKeys.contains(key) || isDuplicateInDb(bankDetails, m, key)) {
        result.duplicates++;
        continue;
      }
      seenKeys.add(key);

      BigDecimal debit = m.debit ? m.amount : BigDecimal.ZERO;
      BigDecimal credit = m.debit ? BigDecimal.ZERO : m.amount;
      String origin = joinNonBlank(m.counterpartyName, m.counterpartyAccount);

      BankStatementLine line =
          lineCreation.createBankStatementLine(
              statement,
              sequence++,
              bankDetails,
              debit,
              credit,
              currency,
              m.description,
              m.operationDate,
              m.valueDate,
              null,
              null,
              origin,
              m.reference);
      line.setLineTypeSelect(BankStatementLineRepository.LINE_TYPE_MOVEMENT);
      bankStatementLineRepo.save(line);
      result.imported++;

      minDate = earliest(minDate, m.valueDate != null ? m.valueDate : m.operationDate);
      maxDate = latest(maxDate, m.valueDate != null ? m.valueDate : m.operationDate);
    }
    result.invalid = parsed.errors.size();

    // 5) Statement header fields. Prefer an explicit statement period (the OCR path reads it from
    // the PDF header) over the min/max of movement dates.
    statement.setBgStatementIban(parsed.iban);
    if (statement.getFromDate() == null) {
      statement.setFromDate(parsed.periodFrom != null ? parsed.periodFrom : minDate);
    }
    if (statement.getToDate() == null) {
      statement.setToDate(parsed.periodTo != null ? parsed.periodTo : maxDate);
    }

    // 6) Balance validation + status precedence: mismatch > manual-edit-needed > imported.
    BalanceCheck balance = BgPostbankStatementParser.validateBalances(parsed.movements);
    int status;
    if (balance == BalanceCheck.MISMATCH) {
      status = STATUS_VALIDATION_FAILED;
    } else if (bankDetailsMissing || result.invalid > 0 || balance == BalanceCheck.NOT_AVAILABLE) {
      status = STATUS_MANUAL_EDIT_REQUIRED;
    } else {
      status = STATUS_IMPORTED;
    }
    statement.setBgImportStatusSelect(status);
    // Only flip the stock status to Imported when the import is fully clean.
    if (status == STATUS_IMPORTED) {
      statement.setStatusSelect(BankStatementRepository.STATUS_IMPORTED);
    }

    // 7) Build the human report + UI messages.
    StringBuilder report = new StringBuilder();
    report
        .append(I18n.get("Imported"))
        .append(": ")
        .append(result.imported)
        .append(" | ")
        .append(I18n.get("Duplicates"))
        .append(": ")
        .append(result.duplicates)
        .append(" | ")
        .append(I18n.get("Invalid rows"))
        .append(": ")
        .append(result.invalid)
        .append("\n")
        .append(I18n.get("Balance check"))
        .append(": ")
        .append(balance);
    if (bankDetailsMissing) {
      report
          .append("\n")
          .append(I18n.get("No bank account (BankDetails) matches IBAN"))
          .append(" ")
          .append(parsed.iban)
          .append(" — ")
          .append(I18n.get("set it manually before reconciliation."));
    }
    report.append(report(parsed.errors));
    statement.setBgImportReport(report.toString());
    bankStatementRepo.save(statement);

    result.importStatus =
        status == STATUS_IMPORTED
            ? "imported"
            : status == STATUS_VALIDATION_FAILED ? "validationFailed" : "manualEditRequired";
    result.manualCorrection = status == STATUS_MANUAL_EDIT_REQUIRED ? 1 : 0;
    result.addMessage(
        String.format(
            I18n.get("Imported %d, duplicates %d, invalid %d."),
            result.imported,
            result.duplicates,
            result.invalid));
    if (balance == BalanceCheck.MISMATCH) {
      result.addMessage(
          I18n.get("Balance validation FAILED (running balances do not chain with the amounts)."));
    } else if (balance == BalanceCheck.NOT_AVAILABLE) {
      result.addMessage(I18n.get("Balances could not be validated — please review."));
    }
    if (bankDetailsMissing) {
      result.addMessage(I18n.get("No matching bank account for the statement IBAN — set it."));
    }
    return result;
  }

  /**
   * Auto-create (and link) the company BankDetails for the IBAN parsed from the imported statement,
   * so reconciliation can proceed without leaving this screen. If a BankDetails already matches the
   * IBAN it is reused. The matching Bank (BIC) is resolved from the IBAN's 4-letter bank code, and
   * every line of this statement that still has no bank account is linked to it.
   */
  @Transactional
  public BankDetails createBankDetailsForStatement(Long bankStatementId) {
    BankStatement statement = bankStatementRepo.find(bankStatementId);
    if (statement == null) {
      throw new IllegalArgumentException(I18n.get("Bank statement not found."));
    }
    String iban = statement.getBgStatementIban();
    if (iban == null || iban.trim().isEmpty()) {
      throw new IllegalStateException(
          I18n.get("No IBAN was parsed from this statement; import the Postbank XLSX first."));
    }
    String normalized = iban.replaceAll("\\s+", "").toUpperCase();

    BankDetails bankDetails = resolveBankDetails(normalized);
    if (bankDetails == null) {
      Bank bank = resolveBankFromIban(normalized);
      if (bank == null) {
        throw new IllegalStateException(
            I18n.get(
                "No Bank (BIC) is configured for this IBAN's bank code; create the Bank first."));
      }
      bankDetails = new BankDetails();
      bankDetails.setIban(normalized);
      bankDetails.setBank(bank);
      bankDetails.setActive(true);
      Company company = AuthUtils.getUser() != null ? AuthUtils.getUser().getActiveCompany() : null;
      if (company != null) {
        bankDetails.setCompany(company);
        bankDetails.setOwnerName(company.getName());
      }
      Currency currency = firstLineCurrency(statement);
      if (currency != null) {
        bankDetails.setCurrency(currency);
      }
      bankDetails = bankDetailsRepo.save(bankDetails);
    }

    // Link this statement's still-unassigned lines to the account so they can be reconciled.
    int linked = 0;
    for (BankStatementLine line :
        bankStatementLineRepo
            .all()
            .filter("self.bankStatement = :s AND self.bankDetails IS NULL")
            .bind("s", statement)
            .fetch()) {
      line.setBankDetails(bankDetails);
      bankStatementLineRepo.save(line);
      linked++;
    }

    String report = statement.getBgImportReport();
    statement.setBgImportReport(
        (report == null ? "" : report)
            + "\n"
            + String.format(
                I18n.get("Bank Details auto-created/linked for IBAN %s (%d line(s) linked)."),
                normalized,
                linked));
    // The account now exists — the manual-correction reason is resolved, so mark the import as
    // done. This also hides the "Auto create Bank Details" button (shown only at status 3).
    if (statement.getBgImportStatusSelect() == STATUS_MANUAL_EDIT_REQUIRED) {
      statement.setBgImportStatusSelect(STATUS_IMPORTED);
      statement.setStatusSelect(BankStatementRepository.STATUS_IMPORTED);
    }
    bankStatementRepo.save(statement);
    return bankDetails;
  }

  /**
   * Resolve the Bank whose BIC/code starts with the IBAN's 4-letter bank identifier (BG layout).
   */
  protected Bank resolveBankFromIban(String normalizedIban) {
    if (normalizedIban == null || normalizedIban.length() < 8) {
      return null;
    }
    String bankCode = normalizedIban.substring(4, 8); // BGkk BBBB … — BBBB is the bank code
    return Beans.get(BankRepository.class)
        .all()
        .filter("upper(self.code) LIKE :c")
        .bind("c", bankCode.toUpperCase() + "%")
        .fetchOne();
  }

  protected Currency firstLineCurrency(BankStatement statement) {
    BankStatementLine line =
        bankStatementLineRepo
            .all()
            .filter("self.bankStatement = :s AND self.currency IS NOT NULL")
            .bind("s", statement)
            .fetchOne();
    return line == null ? null : line.getCurrency();
  }

  protected BankDetails resolveBankDetails(String iban) {
    if (iban == null || iban.trim().isEmpty()) {
      return null;
    }
    String normalized = iban.replaceAll("\\s+", "").toUpperCase();
    // Exact match first; then a space/case-insensitive comparison in case the stored IBAN is
    // formatted with spaces or lower case.
    BankDetails exact = bankDetailsRepo.findByIban(normalized);
    if (exact != null) {
      return exact;
    }
    return bankDetailsRepo
        .all()
        .filter("replace(upper(self.iban), ' ', '') = :norm")
        .bind("norm", normalized)
        .fetchOne();
  }

  /**
   * SHA-256 of the file bytes, hex-encoded; null on any read/hash error (dedup then just skips).
   */
  protected String sha256(Path path) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }

  protected Currency resolveCurrency(String code) {
    if (code == null || code.trim().isEmpty()) {
      return null;
    }
    return Beans.get(CurrencyRepository.class)
        .all()
        .filter("upper(self.codeISO) = :c")
        .bind("c", code.trim().toUpperCase())
        .fetchOne();
  }

  /** True when a non-cancelled line with the same duplicate key already exists for this account. */
  protected boolean isDuplicateInDb(BankDetails bankDetails, BgBankMovement m, String key) {
    if (bankDetails == null) {
      return false; // cannot scope a DB duplicate check without the account
    }
    BigDecimal debit = m.debit ? m.amount : BigDecimal.ZERO;
    BigDecimal credit = m.debit ? BigDecimal.ZERO : m.amount;
    if (m.reference != null && !m.reference.trim().isEmpty()) {
      return bankStatementLineRepo
              .all()
              .filter(
                  "self.bankDetails = :bd AND self.reference = :ref AND self.valueDate = :vd"
                      + " AND self.debit = :d AND self.credit = :c")
              .bind("bd", bankDetails)
              .bind("ref", m.reference.trim())
              .bind("vd", m.valueDate)
              .bind("d", debit)
              .bind("c", credit)
              .count()
          > 0;
    }
    return bankStatementLineRepo
            .all()
            .filter(
                "self.bankDetails = :bd AND self.valueDate = :vd AND self.operationDate = :od"
                    + " AND self.debit = :d AND self.credit = :c AND self.description = :desc")
            .bind("bd", bankDetails)
            .bind("vd", m.valueDate)
            .bind("od", m.operationDate)
            .bind("d", debit)
            .bind("c", credit)
            .bind("desc", m.description)
            .count()
        > 0;
  }

  protected int nextSequence(BankStatement statement) {
    Long max =
        bankStatementLineRepo
            .all()
            .filter("self.bankStatement = :bs")
            .bind("bs", statement)
            .count();
    return max == null ? 0 : max.intValue();
  }

  private static String report(List<BgStatementRowError> errors) {
    if (errors == null || errors.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("\n\n").append(I18n.get("Row errors")).append(":");
    for (BgStatementRowError e : errors) {
      sb.append("\n- ").append(e.toString());
    }
    return sb.toString();
  }

  private static String joinNonBlank(String a, String b) {
    String x = a == null ? "" : a.trim();
    String y = b == null ? "" : b.trim();
    if (x.isEmpty()) {
      return y.isEmpty() ? null : y;
    }
    return y.isEmpty() ? x : x + " / " + y;
  }

  private static LocalDate earliest(LocalDate a, LocalDate b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isBefore(b) ? a : b;
  }

  private static LocalDate latest(LocalDate a, LocalDate b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }
}
