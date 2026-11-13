package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import javax.persistence.PostLoad;
import javax.persistence.PreRemove;
import javax.persistence.PreUpdate;

/**
 * Includes API/repository edits and deletes; readonly forms alone do not protect imported books.
 */
public class MicroinvestLedgerListener {
  @PostLoad
  public void remember(Object record) {
    if (record instanceof Move) {
      Move move = (Move) record;
      move.setBgImportLocked(Boolean.TRUE.equals(move.getBgMigratedHistorical()));
    } else if (record instanceof MoveLine) {
      MoveLine line = (MoveLine) record;
      line.setBgImportLocked(imported(line.getMove()));
    }
  }

  @PreUpdate
  @PreRemove
  public void check(Object record) {
    if (MicroinvestImportScope.isActive()) {
      return;
    }
    boolean locked =
        record instanceof Move
            ? imported((Move) record)
            : record instanceof MoveLine
                && (Boolean.TRUE.equals(((MoveLine) record).getBgImportLocked())
                    || imported(((MoveLine) record).getMove()));
    if (locked) {
      throw new IllegalStateException("Мигрираните Microinvest записи са неизменими.");
    }
  }

  private boolean imported(Move move) {
    return move != null
        && (Boolean.TRUE.equals(move.getBgMigratedHistorical())
            || Boolean.TRUE.equals(move.getBgImportLocked()));
  }
}
