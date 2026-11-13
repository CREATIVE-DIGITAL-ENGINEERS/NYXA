package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.account.service.moveline.MoveLineToolService;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlocked;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Corrects an accounting move that is still a NEW draft.
 *
 * <h2>NEW only, and why the other four statuses are refused</h2>
 *
 * <ul>
 *   <li><b>ACCOUNTED</b> — it is in the books. Changing a posted entry behind the ledger's back is
 *       not a correction, it is a falsification; the accounting answer is a reversing entry.
 *   <li><b>CANCELED</b> — a cancelled entry is a record that something was abandoned. Editing it
 *       would quietly turn it back into a live document.
 *   <li><b>DAYBOOK</b> — recorded, and Axelor has its own path for changing one ({@code
 *       MoveValidateService.updateInDayBookMode}), which re-runs the validation the daybook entry
 *       already passed. Editing the fields underneath it would leave a daybook record that no
 *       longer matches what was checked when it got there.
 *   <li><b>SIMULATED</b> — not in the caller's list of refusals, and refused anyway. {@code
 *       MoveSimulateService.simulate} runs the preconditions and then <em>freezes</em> fields on
 *       the lines; editing afterwards leaves a simulation that no longer describes the entry it
 *       simulated. It is also outside "NEW draft moves", which is the scope this was asked for. If
 *       simulated entries should be editable, this is the single check to relax — see {@link
 *       #requireEditable}.
 * </ul>
 *
 * <h2>What it does not enforce, deliberately</h2>
 *
 * It does not require the result to balance, and here it differs from {@link MoveCreationService}
 * on purpose. Creation refuses an unbalanced entry because the alternative is an orphan draft
 * nobody asked for. Editing operates on a move that already exists, and Axelor itself lets a draft
 * be saved out of balance and refuses only at posting — so requiring balance here would block a
 * perfectly ordinary instruction ("delete the third line") for a document the user is in the middle
 * of correcting. The totals and the difference come back in the result, and {@code post_move} is
 * still the thing that refuses.
 */
@Singleton
public class MoveEditService {

  private static final Logger LOG = LoggerFactory.getLogger(MoveEditService.class);

  /** What may be done to one line in a single request. */
  public enum LineOp {
    ADD,
    UPDATE,
    DELETE
  }

  /** One line change. Absent fields on an UPDATE mean "leave as it is", never "clear it". */
  public static class LineOperation {
    public LineOp operation;
    public Long lineId;
    public String accountCode;
    public BigDecimal amount;
    public Boolean debit;
    public Long partnerId;
    public String description;
    public LocalDate dueDate;
  }

  /** The header changes. Every field is optional; an absent one is left alone. */
  public static class EditRequest {
    public long moveId;
    public String journalCode;
    public LocalDate date;
    public Long partnerId;
    public String origin;
    public LocalDate originDate;
    public String description;
    public Integer functionalOriginSelect;
    public List<LineOperation> lineOperations = new ArrayList<>();
  }

  /**
   * Applies every change in one transaction.
   *
   * <p>Header first, then lines in the order updates → deletes → adds, so a {@code lineId} the
   * caller quoted stays valid for the whole request however the operations were listed. Rolling
   * back on anything: a move with two of its four corrections applied is worse than one with none,
   * because nobody can tell by looking which two landed.
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> edit(EditRequest request) {
    MoveSearchService search = Beans.get(MoveSearchService.class);
    Move move = search.findScoped(request.moveId);
    if (move == null) {
      throw new AiBlocked(
          AiBlockerCode.RECORD_NOT_FOUND, "No move with id " + request.moveId + ".");
    }
    requireEditable(move);

    Company company = move.getCompany();
    if (company == null) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "Move " + reference(move) + " has no company. Nothing was changed.");
    }

    MoveCreationService resolver = Beans.get(MoveCreationService.class);
    List<String> changed = new ArrayList<>();

    applyHeader(move, request, company, resolver, changed);
    applyLines(move, request, company, resolver, changed);

    if (changed.isEmpty()) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_INVALID_STATUS,
          "Nothing to change on move "
              + reference(move)
              + " — no header field and no line operation was given. Nothing was changed.");
    }

    // Renumber from one, in list order, so the counters an accountant sees match the order the
    // lines are in after adds and deletes. Axelor recomputes these at accounting time anyway; doing
    // it here is only so the draft reads correctly in the meantime.
    renumber(move);

    // Through the repository, not around it: MoveManagementRepository.save is where the journal's
    // authorised-role check and the partner-consistency check live, so an edit can only produce
    // what the same user could have produced on the form.
    Move saved;
    try {
      saved = Beans.get(MoveRepository.class).save(move);
      JPA.flush();
    } catch (Exception e) {
      throw new AiBlocked(
          AiBlockerCode.JOURNAL_NOT_AUTHORISED,
          "The move was refused when saving: " + message(e) + " Nothing was changed.");
    }

    AiWriteEffects.touched(Move.class.getName(), saved.getId());
    LOG.info(
        "AI Accountant: move {} edited by {} — {}.",
        saved.getId(),
        AiExecutionContext.callerDescription(),
        String.join(", ", changed));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("updated", true);
    out.put("changed", changed);
    out.putAll(Beans.get(MoveViewService.class).detail(saved));
    Object balance = out.get("balance");
    if (balance instanceof Map && !Boolean.TRUE.equals(((Map<?, ?>) balance).get("balanced"))) {
      // Saved, and said plainly. A draft may sit out of balance; it simply cannot be posted, and
      // reporting the save without the consequence would read as though it were ready.
      out.put(
          "nextStep",
          "The move is saved but does NOT balance, so post_move will refuse it. Correct the lines"
              + " with another edit_move call before posting.");
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Status
  // ---------------------------------------------------------------------------

  /**
   * NEW and nothing else.
   *
   * <p>Stated per status rather than as one "not editable" message, because the useful answer
   * differs every time: a posted entry needs a reversal, a daybook entry needs Axelor's own daybook
   * path, and a cancelled one needs a new entry. A single refusal would leave the assistant to
   * guess which, and guessing an accounting remedy is exactly what it must not do.
   */
  protected void requireEditable(Move move) {
    Integer status = move.getStatusSelect();
    if (status != null && status == MoveRepository.STATUS_NEW) {
      return;
    }
    String label = reference(move);
    if (status == null) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_INVALID_STATUS,
          "Move " + label + " has no status and cannot be edited. Nothing was changed.");
    }
    switch (status) {
      case MoveRepository.STATUS_ACCOUNTED:
        throw new AiBlocked(
            AiBlockerCode.MOVE_INVALID_STATUS,
            "Move "
                + label
                + " is ACCOUNTED — it is in the books and cannot be edited. A posted entry is"
                + " corrected by a reversing entry, which is not something this assistant does."
                + " Nothing was changed.");
      case MoveRepository.STATUS_DAYBOOK:
        throw new AiBlocked(
            AiBlockerCode.MOVE_INVALID_STATUS,
            "Move "
                + label
                + " is in DAYBOOK and cannot be edited here. A daybook entry has already passed"
                + " validation, and changing it goes through Axelor's own daybook path on the Move"
                + " form. Nothing was changed.");
      case MoveRepository.STATUS_CANCELED:
        throw new AiBlocked(
            AiBlockerCode.MOVE_INVALID_STATUS,
            "Move "
                + label
                + " is CANCELED and cannot be edited. Create a new entry instead. Nothing was"
                + " changed.");
      case MoveRepository.STATUS_SIMULATED:
        throw new AiBlocked(
            AiBlockerCode.MOVE_INVALID_STATUS,
            "Move "
                + label
                + " is SIMULATED. Simulation froze the fields on its lines, so editing it would"
                + " leave a simulation that no longer matches the entry. Nothing was changed.");
      default:
        throw new AiBlocked(
            AiBlockerCode.MOVE_INVALID_STATUS,
            "Move "
                + label
                + " is in status "
                + MoveViewService.statusName(status)
                + " and only a NEW draft can be edited. Nothing was changed.");
    }
  }

  // ---------------------------------------------------------------------------
  // Header
  // ---------------------------------------------------------------------------

  protected void applyHeader(
      Move move,
      EditRequest request,
      Company company,
      MoveCreationService resolver,
      List<String> changed) {

    if (request.journalCode != null) {
      Journal journal = resolver.resolveJournal(request.journalCode, company);
      if (move.getJournal() == null || !journal.getId().equals(move.getJournal().getId())) {
        move.setJournal(journal);
        changed.add("journal=" + journal.getCode());
      }
    }

    if (request.date != null && !request.date.equals(move.getDate())) {
      move.setDate(request.date);
      // The period is derived from the date, so moving the date without moving the period leaves an
      // entry whose lines fall outside their own period — which Axelor refuses at posting with a
      // message about the line rather than about the date that caused it.
      //
      // getActivePeriod throws when there is no open period for the date, which is the answer
      // rather than a fault: it means the date is outside anything this company is still posting
      // into, and its own message names the company and the date.
      try {
        move.setPeriod(
            Beans.get(PeriodService.class)
                .getActivePeriod(request.date, company, YearRepository.TYPE_FISCAL));
      } catch (Exception e) {
        throw new AiBlocked(
            AiBlockerCode.PERIOD_NOT_AVAILABLE,
            "There is no open accounting period for "
                + request.date
                + " in "
                + company.getName()
                + ": "
                + message(e)
                + " Nothing was changed.");
      }
      if (move.getPeriod() == null) {
        throw new AiBlocked(
            AiBlockerCode.PERIOD_NOT_AVAILABLE,
            "There is no open accounting period for "
                + request.date
                + " in "
                + company.getName()
                + ". Nothing was changed.");
      }
      // Lines carry their own date and are checked against the period one by one, so a line still
      // sitting on the old date would fail posting even though the header now looks right.
      for (MoveLine line : lines(move)) {
        line.setDate(request.date);
      }
      changed.add("date=" + request.date);
    }

    if (request.partnerId != null) {
      Partner partner = resolver.resolvePartner(request.partnerId);
      move.setPartner(partner);
      changed.add("partner=" + (partner == null ? "-" : partner.getFullName()));
    }
    if (request.origin != null) {
      move.setOrigin(request.origin);
      changed.add("origin");
    }
    if (request.originDate != null) {
      move.setOriginDate(request.originDate);
      changed.add("originDate=" + request.originDate);
    }
    if (request.description != null) {
      move.setDescription(request.description);
      changed.add("description");
    }
    if (request.functionalOriginSelect != null) {
      move.setFunctionalOriginSelect(request.functionalOriginSelect);
      changed.add("functionalOrigin=" + request.functionalOriginSelect);
    }
  }

  // ---------------------------------------------------------------------------
  // Lines
  // ---------------------------------------------------------------------------

  /**
   * Updates, then deletes, then adds.
   *
   * <p>Fixed regardless of the order the caller listed them, so a {@code lineId} quoted anywhere in
   * the request still refers to the line it referred to when the request was written. Adding first
   * would renumber; deleting first would invalidate an id the caller was about to update.
   */
  protected void applyLines(
      Move move,
      EditRequest request,
      Company company,
      MoveCreationService resolver,
      List<String> changed) {

    if (request.lineOperations == null || request.lineOperations.isEmpty()) {
      return;
    }
    for (LineOperation op : request.lineOperations) {
      if (op.operation == LineOp.UPDATE) {
        update(move, op, company, resolver, changed);
      }
    }
    for (LineOperation op : request.lineOperations) {
      if (op.operation == LineOp.DELETE) {
        delete(move, op, changed);
      }
    }
    for (LineOperation op : request.lineOperations) {
      if (op.operation == LineOp.ADD) {
        add(move, op, company, resolver, changed);
      }
    }
    if (lines(move).isEmpty()) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_NOT_BALANCED,
          "That would delete every line and leave an empty move, which Axelor cannot post"
              + " (MOVE_8). Delete the move itself if it is not wanted. Nothing was changed.");
    }
  }

  protected void update(
      Move move,
      LineOperation op,
      Company company,
      MoveCreationService resolver,
      List<String> changed) {

    MoveLine line = requireLine(move, op.lineId, "update");

    if (op.accountCode != null) {
      Account account =
          resolver.resolveAccount(
              op.accountCode, company, line.getCounter() == null ? 0 : line.getCounter());
      line.setAccount(account);
    }
    if (op.amount != null || op.debit != null) {
      // Either alone is meaningful: an amount keeps the side it was on, a side flips the amount
      // already there. What is never allowed is a line ending up with both columns filled, which is
      // Axelor's MOVE_6 — so both are always written, one of them zero.
      BigDecimal amount = op.amount != null ? op.amount : currentAmount(line);
      boolean debit = op.debit != null ? op.debit : isDebit(line);
      if (amount.signum() <= 0) {
        throw new AiBlocked(
            AiBlockerCode.MOVE_NOT_BALANCED,
            "Line "
                + op.lineId
                + " must carry a positive amount; a negative one is expressed by moving it to the"
                + " other side. Nothing was changed.");
      }
      line.setDebit(debit ? amount : BigDecimal.ZERO);
      line.setCredit(debit ? BigDecimal.ZERO : amount);
      // The move-currency amount is derived from the company-currency one and would otherwise keep
      // the figure it had before the edit.
      Beans.get(MoveLineToolService.class).setCurrencyAmount(line);
    }
    if (op.partnerId != null) {
      line.setPartner(resolver.resolvePartner(op.partnerId));
    }
    if (op.description != null) {
      line.setDescription(op.description);
    }
    if (op.dueDate != null) {
      line.setDueDate(op.dueDate);
    }
    changed.add("line#" + op.lineId + " updated");
  }

  protected void delete(Move move, LineOperation op, List<String> changed) {
    MoveLine line = requireLine(move, op.lineId, "delete");
    move.removeMoveLineListItem(line);
    changed.add("line#" + op.lineId + " deleted");
  }

  /**
   * A new line, built by Axelor's own service so it carries the same derived fields as any other.
   */
  protected void add(
      Move move,
      LineOperation op,
      Company company,
      MoveCreationService resolver,
      List<String> changed) {

    if (op.amount == null || op.debit == null) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_NOT_BALANCED,
          "An added line needs both an amount and a side (debit or credit). Nothing was changed.");
    }
    int counter = lines(move).size() + 1;
    Account account = resolver.resolveAccount(op.accountCode, company, counter);
    BigDecimal amount = resolver.requirePositive(op.amount, counter);
    Partner partner =
        op.partnerId == null ? move.getPartner() : resolver.resolvePartner(op.partnerId);
    LocalDate date = move.getDate() == null ? LocalDate.now() : move.getDate();
    try {
      move.addMoveLineListItem(
          Beans.get(MoveLineCreateService.class)
              .createMoveLine(
                  move,
                  partner,
                  account,
                  amount,
                  op.debit,
                  date,
                  op.dueDate,
                  counter,
                  move.getOrigin(),
                  op.description == null ? move.getDescription() : op.description));
    } catch (Exception e) {
      throw new AiBlocked(
          AiBlockerCode.AXELOR_REFUSED_MOVE,
          "The new line could not be built: " + message(e) + " Nothing was changed.");
    }
    changed.add("line added on " + account.getCode());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  protected MoveLine requireLine(Move move, Long lineId, String operation) {
    if (lineId == null) {
      throw new AiBlocked(
          AiBlockerCode.RECORD_NOT_FOUND,
          "A '" + operation + "' line operation needs a lineId. Nothing was changed.");
    }
    for (MoveLine line : lines(move)) {
      if (lineId.equals(line.getId())) {
        return line;
      }
    }
    throw new AiBlocked(
        AiBlockerCode.RECORD_NOT_FOUND,
        "Move "
            + reference(move)
            + " has no line with id "
            + lineId
            + ". Read it with get_move first — do not guess a line id. Nothing was changed.");
  }

  protected void renumber(Move move) {
    int counter = 1;
    for (MoveLine line : lines(move)) {
      line.setCounter(counter++);
    }
  }

  protected static List<MoveLine> lines(Move move) {
    return move.getMoveLineList() == null ? new ArrayList<>() : move.getMoveLineList();
  }

  /** The line's amount, whichever column it is in. */
  protected static BigDecimal currentAmount(MoveLine line) {
    BigDecimal debit = line.getDebit() == null ? BigDecimal.ZERO : line.getDebit();
    return debit.signum() > 0
        ? debit
        : (line.getCredit() == null ? BigDecimal.ZERO : line.getCredit());
  }

  protected static boolean isDebit(MoveLine line) {
    return line.getDebit() != null && line.getDebit().signum() > 0;
  }

  private static String reference(Move move) {
    return move.getReference() == null ? String.valueOf(move.getId()) : move.getReference();
  }

  private static String message(Exception e) {
    String message = e.getMessage();
    return message == null || message.trim().isEmpty()
        ? e.getClass().getSimpleName()
        : message.trim();
  }
}
