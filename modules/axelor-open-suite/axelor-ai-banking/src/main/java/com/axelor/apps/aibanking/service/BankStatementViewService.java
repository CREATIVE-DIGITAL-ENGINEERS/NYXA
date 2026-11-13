package com.axelor.apps.aibanking.service;

import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.BankStatementLine;
import com.axelor.apps.bankpayment.db.repo.BankStatementLineRepository;
import com.axelor.apps.bankpayment.db.repo.BankStatementRepository;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the model is told about a bank statement.
 *
 * <h2>The balance block is the point</h2>
 *
 * A statement's lines are of three kinds — one opening balance, the movements, one closing balance
 * — and the question actually asked of a statement is whether they agree: opening plus the sum of
 * the movements should equal the closing balance. Axelor checks exactly that at import time ({@code
 * BankStatementImportCheckServiceImpl.checkBankStatementBalanceIncoherence}), and when it refuses,
 * the useful answer is the arithmetic rather than the refusal text. So the same sum is computed
 * here and reported per bank account, because one file can carry several.
 *
 * <p>Amounts are rendered as plain strings. A decimal serialised as a JSON number cannot be told
 * apart from a near-miss when it is read back; the string is what was stored.
 */
@Singleton
public class BankStatementViewService {

  /**
   * Lines returned in one detail call.
   *
   * <p>A statement is routinely hundreds of movements, and the whole set would blow the tool result
   * ceiling to answer a question the balance block already answers. The lines are a sample; {@code
   * linesTruncated} says so rather than letting a short list read as the whole file.
   */
  public static final int MAX_LINES = 40;

  /** Identity, dates and status — enough to pick a statement out of a list. */
  public Map<String, Object> summarise(BankStatement statement) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("bankStatementId", statement.getId());
    out.put("name", statement.getName());
    out.put("status", statusName(statement.getStatusSelect()));
    out.put(
        "companyName", statement.getCompany() == null ? null : statement.getCompany().getName());
    out.put("fromDate", text(statement.getFromDate()));
    out.put("toDate", text(statement.getToDate()));
    out.put(
        "fileFormat",
        statement.getBankStatementFileFormat() == null
            ? null
            : statement.getBankStatementFileFormat().getName());
    out.put("hasFile", statement.getBankStatementFile() != null);
    out.put("fullyReconciled", Boolean.TRUE.equals(statement.getIsFullyReconciled()));
    // The OCR upload this statement was generated from, when it came from a scanned PDF. Its id
    // comes from a different sequence than the statement's own, so reporting only one of the two
    // lets the same document be called by two numbers in consecutive sentences. Included in the
    // summary, not just the detail, because a search result is where the id is first quoted.
    Long uploadId = ocrUploadId(statement);
    if (uploadId != null) {
      out.put("ocrUploadId", uploadId);
    }
    return out;
  }

  /**
   * The id of {@code BankStatement.aiOcrUpload}, or null when this statement did not come from OCR.
   *
   * <p>Read reflectively because that link is merged onto {@code BankStatement} by {@code
   * axelor-bg-localization}, which this module does not depend on and should not: a bank statement
   * tool has no business knowing about a localisation. An install without it has no such property
   * and gets nothing — not an error.
   *
   * <p>The upload's own company is checked against the statement's rather than trusted. The
   * statement is already scoped by the time it reaches here, but a stored link is data, and a link
   * that points across companies would otherwise hand the assistant an id from books it may not
   * read. Nothing legitimate is lost: intake writes both records with the same company.
   */
  private static Long ocrUploadId(BankStatement statement) {
    try {
      Mapper mapper = Mapper.of(BankStatement.class);
      if (mapper.getProperty("aiOcrUpload") == null) {
        return null;
      }
      Object value = mapper.get(statement, "aiOcrUpload");
      if (!(value instanceof Model)) {
        return null;
      }
      return sameCompany(statement, (Model) value) ? ((Model) value).getId() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Whether the upload belongs to the same company as the statement that points at it. */
  private static boolean sameCompany(BankStatement statement, Model upload) {
    Company statementCompany = statement.getCompany();
    Object uploadCompany = Mapper.of(upload.getClass()).get(upload, "company");
    if (statementCompany == null || !(uploadCompany instanceof Model)) {
      return false;
    }
    return statementCompany.getId() != null
        && statementCompany.getId().equals(((Model) uploadCompany).getId());
  }

  /** The statement with its balance check per bank account, and a sample of its lines. */
  public Map<String, Object> detail(BankStatement statement) {
    Map<String, Object> out = summarise(statement);
    List<BankStatementLine> lines = linesOf(statement);
    out.put("lineCount", lines.size());

    List<Map<String, Object>> accounts = new ArrayList<>();
    for (BankDetails details : distinctBankDetails(lines)) {
      accounts.add(balanceFor(lines, details));
    }
    out.put("accounts", accounts);

    List<Map<String, Object>> rows = new ArrayList<>();
    for (BankStatementLine line : lines) {
      if (rows.size() >= MAX_LINES) {
        break;
      }
      rows.add(line(line));
    }
    out.put("lines", rows);
    out.put("linesTruncated", lines.size() > MAX_LINES);

    BigDecimal remaining = BigDecimal.ZERO;
    int unreconciledLineCount = 0;
    for (BankStatementLine line : lines) {
      BigDecimal lineRemaining = nz(line.getAmountRemainToReconcile());
      remaining = remaining.add(lineRemaining);
      if (lineRemaining.signum() != 0) {
        unreconciledLineCount++;
      }
    }
    out.put("amountRemainingToReconcile", text(remaining));
    // The count, not only the summed amount: the line list is capped at MAX_LINES, so on a long
    // statement the summed total was the only clue to how much was left and the model could not say
    // how MANY lines still need reconciling. The figure is free — the sum loop already visits every
    // line.
    out.put("unreconciledLineCount", unreconciledLineCount);
    out.put("importable", isImportable(statement));
    return out;
  }

  /**
   * Opening + movements against closing, for one bank account.
   *
   * <p>The same subtraction Axelor makes: a balance line's amount is {@code debit - credit}, and a
   * statement hangs together when opening plus every movement equals closing. Reported whether or
   * not it agrees, because "does this file add up?" is a question worth answering before somebody
   * presses Import and gets a refusal.
   */
  protected Map<String, Object> balanceFor(List<BankStatementLine> lines, BankDetails details) {
    BigDecimal initial = BigDecimal.ZERO;
    BigDecimal movements = BigDecimal.ZERO;
    BigDecimal fin = BigDecimal.ZERO;
    int movementCount = 0;
    for (BankStatementLine line : lines) {
      if (!sameDetails(line.getBankDetails(), details)) {
        continue;
      }
      BigDecimal amount = nz(line.getDebit()).subtract(nz(line.getCredit()));
      Integer type = line.getLineTypeSelect();
      if (type == null) {
        continue;
      }
      if (type == BankStatementLineRepository.LINE_TYPE_INITIAL_BALANCE) {
        initial = initial.add(amount);
      } else if (type == BankStatementLineRepository.LINE_TYPE_FINAL_BALANCE) {
        fin = fin.add(amount);
      } else if (type == BankStatementLineRepository.LINE_TYPE_MOVEMENT) {
        movements = movements.add(amount);
        movementCount++;
      }
    }
    BigDecimal expected = initial.add(movements);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("bankDetailsId", details == null ? null : details.getId());
    out.put("bankDetails", details == null ? null : details.getFullName());
    out.put("iban", details == null ? null : details.getIban());
    out.put(
        "company",
        details == null || details.getCompany() == null ? null : details.getCompany().getName());
    out.put("initialBalance", text(initial));
    out.put("movementTotal", text(movements));
    out.put("movementCount", movementCount);
    out.put("finalBalance", text(fin));
    out.put("difference", text(expected.subtract(fin)));
    out.put("balanced", expected.compareTo(fin) == 0);
    return out;
  }

  protected Map<String, Object> line(BankStatementLine line) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("bankStatementLineId", line.getId());
    row.put("sequence", line.getSequence());
    row.put("lineType", lineTypeName(line.getLineTypeSelect()));
    row.put("operationDate", text(line.getOperationDate()));
    row.put("valueDate", text(line.getValueDate()));
    row.put("debit", text(line.getDebit()));
    row.put("credit", text(line.getCredit()));
    row.put("currency", line.getCurrency() == null ? null : line.getCurrency().getCodeISO());
    row.put("description", line.getDescription());
    row.put("reference", line.getReference());
    row.put("origin", line.getOrigin());
    row.put("amountRemainToReconcile", text(line.getAmountRemainToReconcile()));
    // Whether this line has been matched to an accounting entry. The MoveLine itself belongs to
    // the reconciliation screen, so only its presence is reported here.
    row.put("hasMoveLine", line.getMoveLine() != null);
    return row;
  }

  /** Every line of the statement. There is no collection on the entity — it is a query. */
  public List<BankStatementLine> linesOf(BankStatement statement) {
    return Beans.get(BankStatementLineRepository.class)
        .all()
        .filter("self.bankStatement.id = :id")
        .bind("id", statement.getId())
        .order("sequence")
        .order("id")
        .fetch();
  }

  /** The distinct bank accounts a statement's lines touch, in first-seen order. */
  public List<BankDetails> distinctBankDetails(List<BankStatementLine> lines) {
    List<BankDetails> out = new ArrayList<>();
    for (BankStatementLine line : lines) {
      BankDetails details = line.getBankDetails();
      if (details == null) {
        continue;
      }
      boolean seen = false;
      for (BankDetails known : out) {
        if (sameDetails(known, details)) {
          seen = true;
          break;
        }
      }
      if (!seen) {
        out.add(details);
      }
    }
    return out;
  }

  /** What the Import button's own conditions come to: received, with a file and a format. */
  public static boolean isImportable(BankStatement statement) {
    return statement.getStatusSelect() != null
        && statement.getStatusSelect() == BankStatementRepository.STATUS_RECEIVED
        && statement.getBankStatementFile() != null
        && statement.getBankStatementFileFormat() != null;
  }

  public static String statusName(Integer status) {
    if (status == null) {
      return null;
    }
    switch (status) {
      case BankStatementRepository.STATUS_RECEIVED:
        return "RECEIVED";
      case BankStatementRepository.STATUS_IMPORTED:
        return "IMPORTED";
      default:
        return "UNKNOWN(" + status + ")";
    }
  }

  /** The integer for a status word, or null when it is not one. */
  public static Integer statusValue(String name) {
    if (name == null) {
      return null;
    }
    switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "RECEIVED":
        return BankStatementRepository.STATUS_RECEIVED;
      case "IMPORTED":
        return BankStatementRepository.STATUS_IMPORTED;
      default:
        return null;
    }
  }

  public static String lineTypeName(Integer type) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case BankStatementLineRepository.LINE_TYPE_INITIAL_BALANCE:
        return "INITIAL_BALANCE";
      case BankStatementLineRepository.LINE_TYPE_MOVEMENT:
        return "MOVEMENT";
      case BankStatementLineRepository.LINE_TYPE_FINAL_BALANCE:
        return "FINAL_BALANCE";
      default:
        return "UNKNOWN(" + type + ")";
    }
  }

  private static boolean sameDetails(BankDetails a, BankDetails b) {
    return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String text(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static String text(LocalDate value) {
    return value == null ? null : value.toString();
  }
}
