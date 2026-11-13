package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.db.JpaSecurity;
import com.axelor.db.Model;
import com.axelor.inject.Beans;

/**
 * G9: a search tool lists records only for a user who may read that model at all.
 *
 * <p>The search services already restrict results to the active company, which matches the company
 * condition of every catalogue read permission. What they did not check is whether the user may
 * read the model in the first place: a user holding, say, only a stock role could list the
 * company's moves, invoices and bank statements through the chat. Tools fetching one record by id
 * check that record's permission themselves.
 *
 * <p>Admins pass (the permission engine lets them through), and so does system-context work, which
 * has no user to check.
 */
public final class AiReadGuard {

  private AiReadGuard() {}

  public static void requireRead(Class<? extends Model> model, String what) throws AiToolException {
    if (AiExecutionContext.isSystem()) {
      return;
    }
    if (!Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_READ, model)) {
      throw new AiToolException("You do not have permission to read " + what + ".");
    }
  }
}
