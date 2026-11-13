package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.db.AiPendingProposal;
import com.axelor.apps.aiaccountant.db.repo.AiPendingProposalRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the one structured write proposal a trusted read tool may have on record for a
 * chat panel — see {@code AiPendingProposal}.
 *
 * <h2>Never an authorisation by itself</h2>
 *
 * Recording or finding a proposal here decides nothing about whether a write may run. It only lets
 * a caller — {@code AiAccountantServiceImpl}, before it opens this turn's job — recognise a bare
 * confirmation as naming one specific, previously-read canonical target, so that target can be
 * frozen into a new job's {@code authorisedTargets} exactly where an explicit instruction naming it
 * would be. Every write against it still runs the full {@code AiWriteAuthorisation} chain,
 * including {@code AiTargetRegistry.relatedTarget}, unchanged.
 */
@Singleton
public class AiPendingProposalStore {

  private static final Logger LOG = LoggerFactory.getLogger(AiPendingProposalStore.class);

  /** One proposal, read out for a caller that must not see the entity itself. */
  public static final class Reading {

    private final String canonicalTarget;
    private final String sourceRef;
    private final String task;

    public Reading(String canonicalTarget, String sourceRef, String task) {
      this.canonicalTarget = canonicalTarget;
      this.sourceRef = sourceRef;
      this.task = task;
    }

    public String canonicalTarget() {
      return canonicalTarget;
    }

    public String sourceRef() {
      return sourceRef;
    }

    public String task() {
      return task;
    }
  }

  /** The one proposal on record for this user, company and conversation, or null. */
  public Reading find(Long userId, Long companyId, String conversationId) {
    AiPendingProposal row = row(userId, companyId, conversationId);
    return row == null
        ? null
        : new Reading(row.getCanonicalTarget(), row.getSourceRef(), row.getTask());
  }

  protected AiPendingProposal row(Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      return null;
    }
    try {
      return Beans.get(AiPendingProposalRepository.class)
          .all()
          .filter(
              "self.user.id = :userId AND self.company.id = :companyId"
                  + " AND self.conversationId = :conversationId")
          .bind("userId", userId)
          .bind("companyId", companyId)
          .bind("conversationId", conversationId)
          .order("-id")
          .fetchOne();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not look up the pending proposal: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Records one proposal, replacing whatever was pending for this key.
   *
   * <p>At most one row per (user, company, conversation): only the most recently read fact can ever
   * be confirmed, so reading a second record's evidence retires whatever the first one offered
   * rather than letting both sit on record at once.
   */
  @Transactional(rollbackOn = Exception.class)
  public void record(
      Long userId,
      Long companyId,
      String conversationId,
      String canonicalTarget,
      String sourceRef,
      String task) {
    if (userId == null
        || companyId == null
        || conversationId == null
        || canonicalTarget == null
        || sourceRef == null) {
      return;
    }
    AiPendingProposalRepository repo = Beans.get(AiPendingProposalRepository.class);
    for (AiPendingProposal existing : existingRows(repo, userId, companyId, conversationId)) {
      repo.remove(existing);
    }
    AiPendingProposal row = new AiPendingProposal();
    row.setUser(Beans.get(UserRepository.class).find(userId));
    row.setCompany(Beans.get(CompanyRepository.class).find(companyId));
    row.setConversationId(conversationId);
    row.setCanonicalTarget(canonicalTarget);
    row.setSourceRef(sourceRef);
    row.setTask(task);
    row.setCreatedOn(LocalDateTime.now());
    repo.save(row);
  }

  /**
   * Deletes whatever is pending for this key.
   *
   * <p>Called on every outcome of a promoted confirmation — success, refusal, and supersession
   * alike — and by {@code AiConversationEraser}: a proposal is single-use, so once a following turn
   * has tried to act on it (or the conversation was cleared), it must not be confirmable again.
   */
  @Transactional(rollbackOn = Exception.class)
  public void consume(Long userId, Long companyId, String conversationId) {
    AiPendingProposalRepository repo = Beans.get(AiPendingProposalRepository.class);
    for (AiPendingProposal existing : existingRows(repo, userId, companyId, conversationId)) {
      repo.remove(existing);
    }
  }

  /**
   * Deletes every pending proposal this user has outside {@code companyId}.
   *
   * <p>Called alongside {@code AiWorkflowStore.abandonOtherCompanies}, for the same reason: a
   * company switch is exactly the kind of intervening event that must invalidate a proposal, and
   * without this a proposal recorded in a company the user has since left would sit there and still
   * be confirmable the moment they switched back — possibly turns later, which is precisely what
   * "valid only for the immediately following turn" rules out.
   */
  @Transactional(rollbackOn = Exception.class)
  public void consumeOtherCompanies(Long userId, Long companyId) {
    if (userId == null || companyId == null) {
      return;
    }
    try {
      AiPendingProposalRepository repo = Beans.get(AiPendingProposalRepository.class);
      for (AiPendingProposal row :
          repo.all()
              .filter("self.user.id = :userId AND self.company.id != :companyId")
              .bind("userId", userId)
              .bind("companyId", companyId)
              .fetch()) {
        repo.remove(row);
      }
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not consume pending proposals of other companies: {}",
          e.getMessage());
    }
  }

  private List<AiPendingProposal> existingRows(
      AiPendingProposalRepository repo, Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      return new ArrayList<>();
    }
    try {
      return repo.all()
          .filter(
              "self.user.id = :userId AND self.company.id = :companyId"
                  + " AND self.conversationId = :conversationId")
          .bind("userId", userId)
          .bind("companyId", companyId)
          .bind("conversationId", conversationId)
          .fetch();
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not read the pending proposal to remove it: {}", e.getMessage());
      return new ArrayList<>();
    }
  }
}
