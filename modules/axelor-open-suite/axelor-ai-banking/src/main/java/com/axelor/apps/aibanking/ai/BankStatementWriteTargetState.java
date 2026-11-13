package com.axelor.apps.aibanking.ai;

import com.axelor.apps.aiaccountant.tool.AiWriteTargetState;
import com.axelor.apps.aibanking.service.BankStatementSearchService;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.List;
import javax.persistence.Query;

/**
 * Fingerprints a bank statement, so an import whose answer was lost can be reconciled.
 *
 * <h2>Why this had to exist</h2>
 *
 * {@code run_bank_statement_import} named its target correctly but nothing could describe that
 * target's state, so every ambiguous failure on it was unprovable — and an import is exactly the
 * operation where "did it happen?" matters, because running it twice is what Axelor's own duplicate
 * checks exist to prevent.
 *
 * <h2>Company scoping</h2>
 *
 * {@code BankStatement} has no company column: the account the lines land on is what ties it to a
 * company, which is why the tool requires {@code bankDetailsId} in the first place. So the scoping
 * question is delegated to {@link BankStatementSearchService#findScoped}, the same lookup every
 * read here uses — one rule, in one place. A statement it does not return fingerprints as null.
 *
 * <h2>What is included</h2>
 *
 * The statement's version, last update and status, plus the number of lines and the id range they
 * occupy. An import's whole effect is "lines appeared and the status became IMPORTED", and the
 * count is the part that moves even where a status was already set.
 */
@Singleton
public class BankStatementWriteTargetState implements AiWriteTargetState {

  private static final String PREFIX = "BankStatement#";

  @Override
  public String fingerprint(String targetKey) {
    if (targetKey == null || !targetKey.startsWith(PREFIX)) {
      return null;
    }
    long id;
    try {
      id = Long.parseLong(targetKey.substring(PREFIX.length()).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    BankStatement statement = Beans.get(BankStatementSearchService.class).findScoped(id);
    if (statement == null) {
      // Not this company's, or not there at all. Both answer "cannot say", which the caller treats
      // as unproven rather than as permission.
      return null;
    }
    Query query =
        JPA.em()
            .createQuery(
                "select s.version, s.updatedOn, s.statusSelect,"
                    + " (select count(l) from BankStatementLine l where l.bankStatement = s),"
                    + " (select coalesce(max(l.id), 0) from BankStatementLine l"
                    + "  where l.bankStatement = s)"
                    + " from BankStatement s where s.id = :id");
    query.setParameter("id", id);
    List<?> rows = query.getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = (Object[]) rows.get(0);
    StringBuilder sb = new StringBuilder(PREFIX).append(id);
    for (Object value : row) {
      sb.append('|').append(value == null ? "-" : value);
    }
    return sb.toString();
  }
}
