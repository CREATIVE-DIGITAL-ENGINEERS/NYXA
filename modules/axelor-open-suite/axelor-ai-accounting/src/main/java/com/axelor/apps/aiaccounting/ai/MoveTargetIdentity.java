package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccounting.service.MoveSearchService;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Says which record a {@code Move#id} reference names, and whether this user could change it.
 *
 * <h2>Why it is here and not in the OCR module any more</h2>
 *
 * It was one branch of {@code OcrTargetIdentity}, which answered for uploads, invoices and moves
 * alike — reasonable while one module owned all three, and wrong once journal entries had their
 * own. A reference is answered by the module that owns the document, so the OCR module keeps the
 * two names of an invoice and this one keeps the entry.
 *
 * <p>The OCR module still maps an upload that produced a move to {@code Move#id}: that is a fact
 * about the upload, and reading it needs no move tooling. From there the two agree, because both
 * ask {@link MoveSearchService} the same company-scoped question.
 *
 * <h2>Company scoping is part of the answer</h2>
 *
 * A move of another company resolves to null, exactly as though it did not exist. The canonical
 * form decides whether a write may proceed, so a reference the caller cannot read must never
 * satisfy an authorisation — and answering "not found" rather than "not yours" is the same rule
 * every read tool here follows.
 */
@Singleton
public class MoveTargetIdentity implements AiTargetIdentity {

  @Override
  public String canonical(String ref) {
    Long id = moveId(ref);
    if (id == null) {
      // Not a move reference. Not this module's to answer: the registry asks the others and, if
      // none of them owns it, refuses — which is the intended outcome for a reference nobody can
      // identify.
      return null;
    }
    Move move = Beans.get(MoveSearchService.class).findScoped(id);
    return move == null ? null : "Move#" + id;
  }

  /**
   * Whether this user could change the entry by hand.
   *
   * <p>Asked of Axelor's own permission layer, so a move the accountant has no write access to
   * cannot enter an authorised set.
   */
  @Override
  public Boolean writable(String ref) {
    String canonical = canonical(ref);
    if (canonical == null) {
      // Either not ours, or not identifiable in this company. Both are the registry's to turn into
      // a refusal; saying "false" here would claim ownership of a reference we may not own.
      return null;
    }
    Long id = moveId(canonical);
    if (id == null) {
      return false;
    }
    return Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_WRITE, Move.class, id);
  }

  /** The id of a {@code Move#id} reference, or null for anything else. */
  private static Long moveId(String ref) {
    if (ref == null) {
      return null;
    }
    int hash = ref.lastIndexOf('#');
    if (hash <= 0 || !"Move".equals(ref.substring(0, hash))) {
      return null;
    }
    try {
      return Long.parseLong(ref.substring(hash + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
