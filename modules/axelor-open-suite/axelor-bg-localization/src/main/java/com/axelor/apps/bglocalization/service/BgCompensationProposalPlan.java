package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import java.util.Collections;
import java.util.List;

/**
 * What one proposal pass should do to a contract's compensation rows.
 *
 * <p>Two lists rather than one, because the pass now does two different things and they must not be
 * confused: it creates the compensations this termination gives rise to and does not yet have, and
 * it withdraws the ones it previously proposed which the current ground no longer gives rise to.
 *
 * <p><b>Rows it must never touch are simply absent from both lists.</b> A confirmed row is a
 * decision, an excluded row is a decision with a written reason, and an adjusted amount is
 * somebody's correction — none of them is the pass's to revisit. Only {@code PROPOSED} rows are
 * withdrawable, because a proposal is the only thing the pass itself authored.
 */
public class BgCompensationProposalPlan {

  private final List<BgCompensationType> toCreate;
  private final List<BgTerminationCompensation> toRetract;

  BgCompensationProposalPlan(
      List<BgCompensationType> toCreate, List<BgTerminationCompensation> toRetract) {
    this.toCreate = toCreate == null ? Collections.emptyList() : toCreate;
    this.toRetract = toRetract == null ? Collections.emptyList() : toRetract;
  }

  /** Types the contract should gain a row for. */
  public List<BgCompensationType> getToCreate() {
    return toCreate;
  }

  /** Rows that were proposed and are no longer given rise to; always {@code PROPOSED}. */
  public List<BgTerminationCompensation> getToRetract() {
    return toRetract;
  }

  public boolean isEmpty() {
    return toCreate.isEmpty() && toRetract.isEmpty();
  }
}
