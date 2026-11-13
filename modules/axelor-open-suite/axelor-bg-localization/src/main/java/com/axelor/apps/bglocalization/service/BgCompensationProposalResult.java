package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import java.util.Collections;
import java.util.List;

/**
 * What a proposal pass did to one contract.
 *
 * <p>Carries the rows it created and the count it left alone, because "nothing happened" has two
 * very different meanings — the contract is not terminated, or every compensation is already there
 * — and a user who re-runs the proposal deserves to be told which.
 */
public class BgCompensationProposalResult {

  private final List<BgTerminationCompensation> created;
  private final int alreadyPresent;
  private final int retracted;
  private final String notRunReason;

  private BgCompensationProposalResult(
      List<BgTerminationCompensation> created,
      int alreadyPresent,
      int retracted,
      String notRunReason) {
    this.created = created == null ? Collections.emptyList() : created;
    this.alreadyPresent = alreadyPresent;
    this.retracted = retracted;
    this.notRunReason = notRunReason;
  }

  /** The proposal ran. */
  public static BgCompensationProposalResult of(
      List<BgTerminationCompensation> created, int alreadyPresent, int retracted) {
    return new BgCompensationProposalResult(created, alreadyPresent, retracted, null);
  }

  /** The proposal did not run, and this is why — no contract, or no termination registered. */
  public static BgCompensationProposalResult notRun(String reason) {
    return new BgCompensationProposalResult(Collections.emptyList(), 0, 0, reason);
  }

  public List<BgTerminationCompensation> getCreated() {
    return created;
  }

  public int getCreatedCount() {
    return created.size();
  }

  /** Rows the contract already had, which this pass deliberately did not touch. */
  public int getAlreadyPresent() {
    return alreadyPresent;
  }

  /** Proposals withdrawn because the current ground no longer gives rise to them. */
  public int getRetracted() {
    return retracted;
  }

  /** Null when the proposal ran, whether or not it created anything. */
  public String getNotRunReason() {
    return notRunReason;
  }

  public boolean hasRun() {
    return notRunReason == null;
  }
}
