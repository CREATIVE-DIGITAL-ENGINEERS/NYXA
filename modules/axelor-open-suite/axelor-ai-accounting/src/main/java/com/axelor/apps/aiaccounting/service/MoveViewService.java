package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveToolService;
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
 * What the model is told about an accounting move.
 *
 * <h2>A projection, never the entity</h2>
 *
 * Handing back a {@code Move} would serialise its journal, its period, its company, every line's
 * account and every account's own graph — thousands of tokens of things nobody asked about, and a
 * result that blows the 32 KB tool ceiling on a move of any size. So every field here was chosen
 * because a question an accountant actually asks needs it: what is this entry, does it balance, can
 * it be posted, and what is on its lines.
 *
 * <p>Amounts are rendered as plain strings rather than as {@code BigDecimal}. Jackson writes a
 * decimal as a JSON number, and a model reading {@code 1200.00} back out of JSON has no way to tell
 * it from {@code 1200.000000001}; the string is what was actually stored, exactly as stored.
 */
@Singleton
public class MoveViewService {

  /**
   * Lines returned in one detail call.
   *
   * <p>A manual journal entry is normally a handful of lines, and a payroll or year-end move can be
   * hundreds. The cap keeps the second case inside the tool result ceiling; {@code truncated} says
   * plainly that it happened, because a silently shortened list would be read as the whole entry
   * and the balance would appear wrong.
   */
  public static final int MAX_LINES = 60;

  /** Everything needed to identify a move in a list, and nothing more. */
  public Map<String, Object> summarise(Move move) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("moveId", move.getId());
    out.put("reference", move.getReference());
    out.put("status", statusName(move.getStatusSelect()));
    out.put("date", text(move.getDate()));
    out.put("journalCode", move.getJournal() == null ? null : move.getJournal().getCode());
    out.put("journalName", move.getJournal() == null ? null : move.getJournal().getName());
    out.put("partnerName", move.getPartner() == null ? null : move.getPartner().getFullName());
    out.put("origin", move.getOrigin());
    out.put("description", move.getDescription());
    out.put("currencyCode", move.getCurrencyCode());
    out.put("lineCount", move.getMoveLineList() == null ? 0 : move.getMoveLineList().size());
    out.put("totalDebit", text(totalDebit(move)));
    out.put("totalCredit", text(totalCredit(move)));
    // The OCR upload(s) this entry came from. A move gets its id from a different sequence than the
    // upload that produced it, so without this the assistant can call one document by two numbers —
    // upload 973 in one sentence, move 602 in the next — and neither the user nor a later tool call
    // can tell they are the same thing. Reported HERE rather than only in detail() because a search
    // result is where the id is first quoted.
    String uploadIds = ocrUploadIds(move);
    if (uploadIds != null) {
      out.put("ocrUploadIds", uploadIds);
    }
    return out;
  }

  /**
   * The value of {@code Move.ocrUploadIds}, or null when nothing links this move to an upload.
   *
   * <p>Read reflectively, for the same reason {@code BgOcrIntakeService} writes the NAP document
   * type reflectively: the field is merged onto {@code Move} by {@code axelor-bg-localization},
   * which this module must not depend on. An install without that module has no such property and
   * gets nothing — not an error. It is a derived, read-only field, so nothing here writes it.
   */
  private static String ocrUploadIds(Move move) {
    try {
      Mapper mapper = Mapper.of(Move.class);
      if (mapper.getProperty("ocrUploadIds") == null) {
        return null;
      }
      Object value = mapper.get(move, "ocrUploadIds");
      if (value == null || value.toString().trim().isEmpty()) {
        return null;
      }
      return value.toString().trim();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The whole entry: header, balance, lines, and what would stop it being posted.
   *
   * <p>The balance block is first-class rather than left for the model to add up. Summing a decimal
   * column in prose is exactly the kind of arithmetic a language model gets almost right, and
   * "almost" is the difference between an entry that posts and one that does not.
   */
  public Map<String, Object> detail(Move move) {
    Map<String, Object> out = summarise(move);
    out.put("periodName", move.getPeriod() == null ? null : move.getPeriod().getName());
    out.put("periodStatus", move.getPeriod() == null ? null : move.getPeriod().getStatusSelect());
    out.put("companyName", move.getCompany() == null ? null : move.getCompany().getName());
    out.put("accountingDate", text(move.getAccountingDate()));
    out.put("originDate", text(move.getOriginDate()));
    out.put("functionalOriginSelect", move.getFunctionalOriginSelect());
    out.put("technicalOriginSelect", move.getTechnicalOriginSelect());

    BigDecimal debit = totalDebit(move);
    BigDecimal credit = totalCredit(move);
    BigDecimal difference = debit.subtract(credit);
    Map<String, Object> balance = new LinkedHashMap<>();
    balance.put("totalDebit", text(debit));
    balance.put("totalCredit", text(credit));
    balance.put("difference", text(difference));
    balance.put("balanced", difference.signum() == 0);
    out.put("balance", balance);

    List<MoveLine> lines =
        move.getMoveLineList() == null ? new ArrayList<>() : move.getMoveLineList();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (MoveLine line : lines) {
      if (rows.size() >= MAX_LINES) {
        break;
      }
      rows.add(line(line));
    }
    out.put("lines", rows);
    out.put("linesTruncated", lines.size() > MAX_LINES);

    out.put("editable", isEditable(move));
    // "postable" means the entry is in a status that allows the Post button AND it balances — not a
    // full posting-readiness verdict. It was a status-only check before, so it read true for an
    // unbalanced NEW entry sitting beside balance.balanced=false, and a caller keying on it like
    // the
    // invoice tools' postReady could treat that as ready to post. Requiring balance means it is
    // never true for the one blocker this payload always proves; a closed period or missing config
    // can still refuse the post — that is what get_move with checkPosting=true is for. The
    // isPostable(move) method keeps its status-only meaning for its other callers (page
    // resolution).
    out.put("postable", isPostable(move) && difference.signum() == 0);
    return out;
  }

  protected Map<String, Object> line(MoveLine line) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("moveLineId", line.getId());
    row.put("counter", line.getCounter());
    Account account = line.getAccount();
    row.put("accountCode", account == null ? null : account.getCode());
    row.put("accountName", account == null ? null : account.getName());
    row.put("debit", text(line.getDebit()));
    row.put("credit", text(line.getCredit()));
    row.put("partnerName", line.getPartner() == null ? null : line.getPartner().getFullName());
    row.put("description", line.getDescription());
    row.put("date", text(line.getDate()));
    row.put("dueDate", text(line.getDueDate()));
    // Whether a line carries tax, not which tax: the codes are a per-line detail that would double
    // the size of every result to answer a question that is rarely the one being asked.
    row.put("hasTaxLine", line.getTaxLineSet() != null && !line.getTaxLineSet().isEmpty());
    return row;
  }

  /**
   * Debit and credit totals, from Axelor's own helper rather than a loop of our own.
   *
   * <p>It is the same computation the form's totals use, so what the assistant reports and what the
   * accountant sees on screen cannot drift apart.
   */
  public BigDecimal totalDebit(Move move) {
    if (move.getMoveLineList() == null || move.getMoveLineList().isEmpty()) {
      return BigDecimal.ZERO;
    }
    return Beans.get(MoveToolService.class).getTotalDebitAmount(move.getMoveLineList());
  }

  public BigDecimal totalCredit(Move move) {
    if (move.getMoveLineList() == null || move.getMoveLineList().isEmpty()) {
      return BigDecimal.ZERO;
    }
    return Beans.get(MoveToolService.class).getTotalCreditAmount(move.getMoveLineList());
  }

  /** The statuses whose lines the form itself leaves editable. */
  public static boolean isEditable(Move move) {
    Integer status = move.getStatusSelect();
    return status != null
        && (status == MoveRepository.STATUS_NEW
            || status == MoveRepository.STATUS_DAYBOOK
            || status == MoveRepository.STATUS_SIMULATED);
  }

  /**
   * Whether the Accounting button would be offered at all.
   *
   * <p>Only a statement about the status. Whether it would <em>succeed</em> is what Axelor's own
   * preconditions decide, and this deliberately does not try to predict them.
   */
  public static boolean isPostable(Move move) {
    Integer status = move.getStatusSelect();
    return status != null
        && (status == MoveRepository.STATUS_NEW
            || status == MoveRepository.STATUS_DAYBOOK
            || status == MoveRepository.STATUS_SIMULATED);
  }

  /** The status as a word, because an integer means nothing to the reader of an answer. */
  public static String statusName(Integer status) {
    if (status == null) {
      return null;
    }
    switch (status) {
      case MoveRepository.STATUS_NEW:
        return "NEW";
      case MoveRepository.STATUS_DAYBOOK:
        return "DAYBOOK";
      case MoveRepository.STATUS_ACCOUNTED:
        return "ACCOUNTED";
      case MoveRepository.STATUS_CANCELED:
        return "CANCELED";
      case MoveRepository.STATUS_SIMULATED:
        return "SIMULATED";
      default:
        return "UNKNOWN(" + status + ")";
    }
  }

  /** The integer for a status word, or null when it is not one. Case-insensitive. */
  public static Integer statusValue(String name) {
    if (name == null) {
      return null;
    }
    switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "NEW":
        return MoveRepository.STATUS_NEW;
      case "DAYBOOK":
        return MoveRepository.STATUS_DAYBOOK;
      case "ACCOUNTED":
        return MoveRepository.STATUS_ACCOUNTED;
      case "CANCELED":
      case "CANCELLED":
        return MoveRepository.STATUS_CANCELED;
      case "SIMULATED":
        return MoveRepository.STATUS_SIMULATED;
      default:
        return null;
    }
  }

  private static String text(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static String text(LocalDate value) {
    return value == null ? null : value.toString();
  }
}
