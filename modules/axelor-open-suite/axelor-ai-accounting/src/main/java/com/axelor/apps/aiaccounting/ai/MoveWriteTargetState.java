package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiWriteTargetState;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.util.List;
import javax.persistence.Query;

/**
 * Fingerprints an accounting move, so a posting that failed is retried exactly when something
 * relevant has changed.
 *
 * <h2>What is included, and why</h2>
 *
 * <ul>
 *   <li><b>the move</b> — its version, last update and status. Hibernate's {@code version} moves on
 *       any save, whether from here or from the Move form, so an accountant who corrects a line in
 *       the UI releases the guard without anyone having to report that they did;
 *   <li><b>the line count and the debit/credit totals</b>. The commonest refusal by far is an
 *       unbalanced entry, and it is cleared by editing lines — which changes the totals but need
 *       not change the move row itself. Leaving them out would mean a move that was corrected into
 *       balance still had the same fingerprint, and the guard would go on refusing a call that
 *       would now succeed;
 *   <li><b>the period's status</b>. A move refused because its period was closed becomes postable
 *       the moment the period is reopened, and nothing about the move changes when that happens.
 * </ul>
 *
 * <p>The rule this follows, the same one the OCR module's {@code OcrWriteTargetState} follows in
 * its own module: fingerprint what the refusals actually depend on, not just the record the tool
 * names. Anything left out silently turns "retry when state changes" back into "never retry".
 *
 * <p>One projection query, no entities loaded. The value is opaque — compared for equality, never
 * parsed, never logged, never shown to the model. Unreadable degrades to null, which the guard
 * treats as unknown rather than as a constant.
 */
@Singleton
public class MoveWriteTargetState implements AiWriteTargetState {

  /** The canonical form {@code MoveTargetIdentity} produces; everything else is not ours. */
  private static final String MOVE_PREFIX = "Move#";

  @Override
  public String fingerprint(String targetKey) {
    if (targetKey == null || !targetKey.startsWith(MOVE_PREFIX)) {
      return null;
    }
    long moveId;
    try {
      moveId = Long.parseLong(targetKey.substring(MOVE_PREFIX.length()).trim());
    } catch (NumberFormatException e) {
      return null;
    }

    Query query =
        JPA.em()
            .createQuery(
                "select m.version, m.updatedOn, m.statusSelect, p.statusSelect,"
                    + " (select count(l) from MoveLine l where l.move = m),"
                    + " (select coalesce(sum(l.debit), 0) from MoveLine l where l.move = m),"
                    + " (select coalesce(sum(l.credit), 0) from MoveLine l where l.move = m),"
                    + " c.id"
                    + " from Move m"
                    + " left join m.period p"
                    + " left join m.company c"
                    + " where m.id = :id");
    query.setParameter("id", moveId);
    List<?> rows = query.getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = (Object[]) rows.get(0);
    Long companyId =
        row[row.length - 1] == null ? null : ((Number) row[row.length - 1]).longValue();
    if (companyId == null || !AiCompanyScope.ownsId(companyId)) {
      // Another company's entry fingerprints as null, exactly as though it did not exist. The value
      // decides whether a write landed, and one company's row must never settle a question about
      // another company's books.
      return null;
    }
    StringBuilder sb = new StringBuilder(MOVE_PREFIX).append(moveId);
    for (int i = 0; i < row.length - 1; i++) {
      sb.append('|').append(row[i] == null ? "-" : row[i]);
    }
    return sb.toString();
  }
}
