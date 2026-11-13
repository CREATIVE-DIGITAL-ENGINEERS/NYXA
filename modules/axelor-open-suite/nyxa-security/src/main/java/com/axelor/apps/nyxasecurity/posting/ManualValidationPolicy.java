package com.axelor.apps.nyxasecurity.posting;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Who may validate an accounting entry.
 *
 * <p>Validating is as in standard Axelor: it gives Accounted (or Daybook, where a company runs a
 * daybook). The permission engine checks only read before running an action, so a read-only user
 * could call a validating action directly — on a draft, or on the document a module posts from.
 * This closes both:
 *
 * <ul>
 *   <li>An entry that a module creates and validates in the same flow (technical origin Automatic,
 *       inserted in the current transaction) needs the actor's own write permission on its
 *       <em>source document</em>: the invoice it ventilates, the payment, the stock move. So a
 *       stock issue follows {@code stock.write}, a ventilation {@code invoicing.write}.
 *   <li>Every other entry needs write on {@code Move}: a draft that was already saved, whatever its
 *       origin (an imported or automatic draft validated later), an unknown origin, and an
 *       automatic entry whose source cannot be told from the move.
 * </ul>
 *
 * <p>The source is read from the move itself, as Axelor links it: {@code invoice}, {@code
 * paymentVoucher}, {@code paymentSession}, {@code stockMove} (supplychain), {@code expense} /
 * {@code expensePayment} (hr). One source is read from the journal instead, because the move has no
 * link to it: the pay slip's entry (bg-localization), which is the only automatic entry written in
 * the company's payroll journal ({@code AccountConfig.bgPayrollJournal}) — so paying a slip needs
 * write on the slip, as ventilating an invoice needs write on the invoice, see {@link
 * #payrollSource}. Many automatic entries name none of them — an invoice payment's move (the
 * payment points at the move, not back), bank orders, bank reconciliation, reversals — and the
 * functional origin does not tell them apart, so they need write on {@code Move}. Every role that
 * writes invoice payments also writes moves.
 *
 * <p><b>The automatic posting user</b> ({@code nyxa-auto-posting}, bg-localization) is not a person
 * and does not go through permissions at all: it may post exactly one thing — the stock issue it
 * creates in the same transaction (origin Automatic, linked to its {@code StockMove}) in the
 * company's stock issue journal ({@code AccountConfig.bgStockIssueJournal}). Anything else it
 * validates is refused. There is no role for this, so it cannot be given to a person.
 *
 * <p>With no logged-in user at all (Quartz jobs, startup) nothing is checked here.
 *
 * <p>Pure decision logic, so it can be tested without a database. {@link ManualValidationGuard}
 * applies it when moves are flushed.
 */
public final class ManualValidationPolicy {

  static final String MOVE = Move.class.getName();
  static final String STOCK_MOVE = "com.axelor.apps.stock.db.StockMove";

  /** The pay slip (bg-localization) — the source of an automatic entry in the payroll journal. */
  public static final String PAY_SLIP = "com.axelor.apps.bglocalization.db.BgPaySlip";

  /** The automatic posting user's code — {@code BgAutoPostingUserService.USER_CODE}. */
  public static final String AUTO_POSTING_USER = "nyxa-auto-posting";

  /** Move property → the model it names, most specific first. */
  static final List<String[]> SOURCES =
      List.of(
          new String[] {"paymentVoucher", "com.axelor.apps.account.db.PaymentVoucher"},
          new String[] {"paymentSession", "com.axelor.apps.account.db.PaymentSession"},
          new String[] {"expensePayment", "com.axelor.apps.hr.db.Expense"},
          new String[] {"expense", "com.axelor.apps.hr.db.Expense"},
          new String[] {"invoice", "com.axelor.apps.account.db.Invoice"},
          new String[] {"stockMove", STOCK_MOVE});

  private ManualValidationPolicy() {}

  /**
   * The model of the move's source document, or null when the move names none.
   *
   * @param property the move's value for a property name; null for an absent property
   */
  public static String sourceModel(Function<String, Object> property) {
    for (String[] source : SOURCES) {
      if (property.apply(source[0]) != null) {
        return source[1];
      }
    }
    return null;
  }

  /**
   * The source of an entry that names none: the pay slip, when a module wrote it in this
   * transaction (origin Automatic) in the company's payroll journal. Otherwise {@code sourceModel}
   * unchanged.
   *
   * <p>Paying a slip posts its entry in the same transaction ({@code BgPaySlipMoveService}), and
   * the person paying has {@code hr.payroll.write}, which writes slips but not moves. The journal
   * is what tells the entry apart: the payroll journal takes no other automatic entry, and a draft
   * saved earlier, or an entry typed by hand, is not "created in this transaction" as Automatic.
   *
   * @param payrollJournalId the company's payroll journal; null when it has none
   */
  public static String payrollSource(
      Integer technicalOrigin,
      boolean createdInThisTransaction,
      String sourceModel,
      Long journalId,
      Long payrollJournalId) {
    if (sourceModel == null
        && createdInThisTransaction
        && Objects.equals(technicalOrigin, MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC)
        && journalId != null
        && journalId.equals(payrollJournalId)) {
      return PAY_SLIP;
    }
    return sourceModel;
  }

  /**
   * The model whose write permission this status change needs, or null when it needs none.
   *
   * @param createdInThisTransaction whether the move was inserted in the transaction being flushed,
   *     i.e. it was not in the database before this request
   * @param sourceModel from {@link #sourceModel}
   */
  public static String requiredModel(
      Integer oldStatus,
      Integer newStatus,
      Integer technicalOrigin,
      boolean createdInThisTransaction,
      String sourceModel) {
    boolean validated =
        Objects.equals(newStatus, MoveRepository.STATUS_DAYBOOK)
            || Objects.equals(newStatus, MoveRepository.STATUS_ACCOUNTED);
    if (!validated || Objects.equals(oldStatus, newStatus)) {
      return null;
    }
    boolean moduleFlow =
        createdInThisTransaction
            && Objects.equals(technicalOrigin, MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC);
    return moduleFlow && sourceModel != null ? sourceModel : MOVE;
  }

  /**
   * Whether the automatic posting user may make this validation: its own stock issue only.
   *
   * @param journalId the move's journal
   * @param stockIssueJournalId the company's stock issue journal; null when it has none
   */
  public static boolean autoPostingMay(
      Integer technicalOrigin,
      boolean createdInThisTransaction,
      String sourceModel,
      Long journalId,
      Long stockIssueJournalId) {
    return createdInThisTransaction
        && Objects.equals(technicalOrigin, MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC)
        && STOCK_MOVE.equals(sourceModel)
        && journalId != null
        && journalId.equals(stockIssueJournalId);
  }

  /** Permission objects that cover a model: the class itself, or its package wildcard. */
  public static Set<String> objectsFor(String model) {
    return Set.of(model, model.substring(0, model.lastIndexOf('.')) + ".*");
  }

  /**
   * @param admin whether the actor is an administrator (they bypass the permission engine anyway)
   * @param writePermissions the actor's permissions with write on {@link #objectsFor} the model,
   *     counted over the user, their roles, their group and the group's roles, as Axelor resolves
   */
  public static boolean mayValidate(boolean admin, long writePermissions) {
    return admin || writePermissions > 0;
  }
}
