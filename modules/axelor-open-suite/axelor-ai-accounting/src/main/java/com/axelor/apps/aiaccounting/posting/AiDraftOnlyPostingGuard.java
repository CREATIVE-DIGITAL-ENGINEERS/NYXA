package com.axelor.apps.aiaccounting.posting;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiaccountant.security.AiDraftOnlyRefusal;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import java.util.Objects;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * AI write tools only prepare drafts ({@code accounting.ai.write}): while one runs, no accounting
 * entry may become Accounted, whoever the user is and whatever service the tool went through —
 * post_move, ventilating a customer or supplier invoice, anything added later.
 *
 * <p>Checked when the move is written, like the former posting gate (G1), but only inside {@link
 * AiExecutionContext#callAsToolWrite}. The user validates from the form; that path is not touched.
 * Where a company runs a daybook, a move may still go to Daybook, as before.
 */
public class AiDraftOnlyPostingGuard implements PreUpdateEventListener, PreInsertEventListener {

  private static final long serialVersionUID = 1L;

  private static final String STATUS = "statusSelect";

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    if (event.getEntity() instanceof Move && event.getOldState() != null) {
      String[] names = event.getPersister().getPropertyNames();
      check(status(names, event.getOldState()), status(names, event.getState()));
    }
    return false;
  }

  @Override
  public boolean onPreInsert(PreInsertEvent event) {
    if (event.getEntity() instanceof Move) {
      check(null, status(event.getPersister().getPropertyNames(), event.getState()));
    }
    return false;
  }

  static void check(Integer oldStatus, Integer newStatus) {
    if (isPosting(oldStatus, newStatus) && AiExecutionContext.isToolWrite()) {
      throw new AiDraftOnlyRefusal();
    }
  }

  /** Whether this status change makes the entry Accounted. */
  static boolean isPosting(Integer oldStatus, Integer newStatus) {
    return Objects.equals(newStatus, MoveRepository.STATUS_ACCOUNTED)
        && !Objects.equals(oldStatus, MoveRepository.STATUS_ACCOUNTED);
  }

  private static Integer status(String[] names, Object[] state) {
    for (int i = 0; i < names.length; i++) {
      if (STATUS.equals(names[i])) {
        return (Integer) state[i];
      }
    }
    return null;
  }
}
