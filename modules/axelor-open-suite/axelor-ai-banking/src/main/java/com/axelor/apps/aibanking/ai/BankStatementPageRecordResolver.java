package com.axelor.apps.aibanking.ai;

import com.axelor.apps.aiaccountant.context.AiPageRecordResolver;
import com.axelor.apps.aibanking.service.BankStatementSearchService;
import com.axelor.apps.aibanking.service.BankStatementViewService;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Answers "what is this bank statement?" for a record the accountant has open.
 *
 * <h2>No lifecycle, deliberately</h2>
 *
 * {@link #lifecycle} returns null. The workflow vocabulary is DRAFT / VALIDATED / POSTED /
 * CANCELLED, and those are stages of an accounting document on its way into the books. A bank
 * statement never enters the books — importing it produces a record of what the bank says happened,
 * and it becomes accounting only through reconciliation. Mapping IMPORTED onto VALIDATED or POSTED
 * would be the same class of untruth as calling a DAYBOOK move posted, so the honest answer is that
 * this record is not on that scale at all.
 *
 * <p>The practical effect is nil: "import these statements" is not one of the goals {@code
 * AiWorkflowIntent} recognises, so no workflow opens for it and nothing is waiting for a completion
 * signal that never arrives.
 */
@Singleton
public class BankStatementPageRecordResolver implements AiPageRecordResolver {

  static final String STATEMENT_MODEL = "com.axelor.apps.bankpayment.db.BankStatement";

  @Override
  public String describe(String model, long recordId) {
    BankStatement statement = statementFor(model, recordId);
    if (statement == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("This is bank statement bankStatementId=")
        .append(statement.getId())
        .append(statement.getName() == null ? "" : " (" + statement.getName() + ")")
        .append(", status ")
        .append(BankStatementViewService.statusName(statement.getStatusSelect()))
        .append(". Use bankStatementId=")
        .append(statement.getId())
        .append(" directly and do NOT call search_bank_statements.");

    return sb.toString();
  }

  /** The statement's position in one clause, for a truncation summary. */
  @Override
  public String state(String model, long recordId) {
    BankStatement statement = statementFor(model, recordId);
    if (statement == null || statement.getStatusSelect() == null) {
      return null;
    }
    String label =
        "bank statement " + (statement.getName() == null ? statement.getId() : statement.getName());
    return label + " is " + BankStatementViewService.statusName(statement.getStatusSelect());
  }

  /**
   * The statement an id names, company-scoped, or null for any other model.
   *
   * <p>Strictly scoped, and so silent about a statement that has not been imported. That is a real
   * cost — an accountant looking at a statement awaiting import gets no context sentence about it —
   * and it is the correct trade: the page context is model-visible text, and an unattributed
   * statement is a record this assistant cannot prove belongs to the caller's company.
   */
  protected BankStatement statementFor(String model, long recordId) {
    if (!STATEMENT_MODEL.equals(model)) {
      return null;
    }
    return Beans.get(BankStatementSearchService.class).findScoped(recordId);
  }
}
