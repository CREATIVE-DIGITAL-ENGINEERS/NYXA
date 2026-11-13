package com.axelor.apps.aibanking.ai;

import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aibanking.service.BankStatementSearchService;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Identifies a bank statement reference, for the write-authorisation check.
 *
 * <p>A statement is canonically itself — there is no second name for one, as there is for an
 * invoice that is also an upload. What this contributes is the company scoping: the lookup goes
 * through {@link BankStatementSearchService#findScoped}, the same one every read tool uses, so a
 * statement belonging to another company resolves to nothing at all rather than to a record that
 * could satisfy an authorisation.
 *
 * <p>Without this class, a write aimed at {@code BankStatement#12} would be refused by {@code
 * AiTargetRegistry} — unidentifiable is a refusal there, deliberately. A reference kind whose
 * module contributes no resolver simply does not work, which is a visible failure rather than a
 * silent permission.
 */
@Singleton
public class BankStatementTargetIdentity implements AiTargetIdentity {

  @Override
  public String canonical(String ref) {
    if (ref == null) {
      return null;
    }
    int hash = ref.lastIndexOf('#');
    if (hash <= 0 || !"BankStatement".equals(ref.substring(0, hash))) {
      return null;
    }
    long id;
    try {
      id = Long.parseLong(ref.substring(hash + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    BankStatement statement = Beans.get(BankStatementSearchService.class).findScoped(id);
    return statement == null ? null : "BankStatement#" + id;
  }

  /**
   * Whether this user could import the statement by hand.
   *
   * <p>Axelor's own permission layer, on the statement itself — the same check {@code
   * run_bank_statement_import} already makes before it does anything. Answered only for statements
   * this resolver could identify in the active company; anything else is null, so the registry
   * refuses rather than this class claiming a reference it does not own.
   */
  @Override
  public Boolean writable(String ref) {
    String canonical = canonical(ref);
    if (canonical == null) {
      return null;
    }
    long id = Long.parseLong(canonical.substring(canonical.lastIndexOf('#') + 1));
    return Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_WRITE, BankStatement.class, id);
  }
}
