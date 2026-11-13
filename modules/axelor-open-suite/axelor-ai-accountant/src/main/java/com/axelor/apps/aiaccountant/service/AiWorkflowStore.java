package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.db.AiWorkflowState;
import com.axelor.apps.aiaccountant.db.repo.AiWorkflowStateRepository;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.AiWorkflowRecord;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the authoritative copy of a job.
 *
 * <h2>Why the server needed its own copy</h2>
 *
 * The workflow already travelled in the conversation blob, and that was enough while it was only
 * describing progress. It is not enough to authorise a write. The blob is held by the browser: a
 * second tab has never seen it, a reload can lose it, and what arrives is whatever the client chose
 * to send. Deciding "may this change be made?" from a value the caller supplies is not a decision.
 *
 * <p>So the row is authoritative and the blob is a hint. Where they disagree — a stale tab, an
 * edited blob, a job the user has since abandoned elsewhere — the row wins.
 *
 * <h2>Concurrency</h2>
 *
 * Two tabs on the same job is the ordinary case, not the exotic one, and both will write at the end
 * of their turn. Axelor's {@code version} column makes the second write of a stale copy fail rather
 * than quietly overwrite the first; this class catches that, re-reads and re-applies.
 *
 * <p>Re-applying is safe because no update here replaces anything. Targets are added, completions
 * are read from the records' own lifecycle, and a blocker is set on a record that is not already
 * completed — so applying the same turn's findings to a newer row produces the union, which is what
 * both tabs would have agreed on had they run in sequence. Nothing is lost by losing the race.
 */
@Singleton
public class AiWorkflowStore {

  private static final Logger LOG = LoggerFactory.getLogger(AiWorkflowStore.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The values of {@code AiWorkflowState.statusSelect}.
   *
   * <p>Declared here rather than taken from the generated entity: the domain XML's {@code
   * extra-code} block is not emitted into the generated class by this platform version — stock
   * entities that declare one (AccountClearance, for instance) come out without their constants too
   * — so referring to them from the entity does not compile. The domain XML documents the meanings
   * beside the field; these are the same three values, and the two must be kept in step.
   *
   * <p>1 ACTIVE — resumable. 2 SETTLED — every target done or blocked. 3 ABANDONED — superseded, or
   * the company changed under it. Only an ACTIVE row is ever resumed.
   */
  static final int STATUS_ACTIVE = 1;

  static final int STATUS_SETTLED = 2;

  static final int STATUS_ABANDONED = 3;

  /**
   * The active job for this user in this company, or null.
   *
   * <p>Never throws: a workflow that cannot be read is a workflow the turn proceeds without, which
   * is how the assistant behaved before the row existed. The consequence of failing to read one is
   * that «продължи» has less to go on, not that the turn dies.
   */
  public AiWorkflow load(Long userId, Long companyId, String conversationId) {
    AiWorkflowState row = find(userId, companyId, conversationId);
    if (row == null) {
      return null;
    }
    try {
      AiWorkflow workflow = MAPPER.readValue(row.getStateJson(), AiWorkflow.class);
      // The row's own columns win over anything the JSON carries about identity: they are what the
      // lookup was keyed on, so they cannot be inconsistent with the row that was found.
      workflow.setCompanyId(companyId);
      workflow.setConversationId(conversationId);
      return workflow;
    } catch (Exception e) {
      LOG.warn("AI Accountant: could not read stored workflow {}: {}", row.getId(), e.getMessage());
      return null;
    }
  }

  /** The active row for this user and company, or null. Public so callers can read its version. */
  public AiWorkflowState find(Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      return null;
    }
    try {
      return Beans.get(AiWorkflowStateRepository.class)
          .all()
          .filter(
              "self.user.id = :userId AND self.company.id = :companyId"
                  + " AND self.conversationId = :conversationId"
                  + " AND self.statusSelect = :status")
          .bind("userId", userId)
          .bind("companyId", companyId)
          .bind("conversationId", conversationId)
          .bind("status", STATUS_ACTIVE)
          .order("-id")
          .fetchOne();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not look up the stored workflow: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Writes this turn's view of the job, creating the row if this is where it began.
   *
   * <p>Best-effort by design. A job that cannot be persisted still ran, still wrote what it wrote,
   * and still answers the user; what is lost is the next turn's memory of it, which is worth a
   * warning and not an exception thrown at somebody mid-sentence.
   */
  public Outcome save(
      AiWorkflow workflow, AiRoute route, Long userId, Long companyId, String conversationId) {
    if (workflow == null
        || userId == null
        || companyId == null
        || conversationId == null
        || !workflow.isActive()) {
      // No conversation, no stored job. A turn the server cannot attribute to a chat panel must
      // not leave an authorisation behind that another turn could pick up.
      return Outcome.NOT_APPLICABLE;
    }
    // Through the injector, not directly: @Transactional is a Guice method interceptor, and a
    // self-call inside this object would bypass the proxy and run outside any transaction — the
    // classic way an annotation ends up decorating nothing.
    AiWorkflowStore proxied = Beans.get(AiWorkflowStore.class);
    try {
      proxied.persist(workflow, route, userId, companyId, conversationId, false);
      return Outcome.SAVED;
    } catch (RuntimeException e) {
      // Includes the optimistic-lock failure. One retry, against the row as it now stands: the
      // first attempt's transaction has been rolled back, so the retry re-reads and re-applies.
      // The retry is stricter — see persist: something wrote in between, and if what it wrote was
      // a different job then this is a divergence rather than a race to be smoothed over.
      LOG.info("AI Accountant: retrying workflow save after {}", e.getMessage());
      try {
        proxied.persist(workflow, route, userId, companyId, conversationId, true);
        return Outcome.SAVED;
      } catch (ConcurrentJobException conflict) {
        LOG.warn("AI Accountant: {}", conflict.getMessage());
        return Outcome.CONFLICT;
      } catch (RuntimeException retry) {
        LOG.warn("AI Accountant: could not store the workflow: {}", retry.getMessage());
        return Outcome.FAILED;
      }
    }
  }

  /** What became of a save. Returned rather than logged away: a conflict is a fact about a job. */
  public enum Outcome {

    /** The row now holds this turn's view of the job. */
    SAVED,

    /**
     * Another job is active for this user and company, and it is not this one.
     *
     * <p>Two tabs, two different instructions. Neither is wrong and neither may be merged into the
     * other: their target sets, goals and authorisations were each given for something specific,
     * and a union of them is an authorisation nobody gave. The turn's own work already happened —
     * every tool call was authorised against the job as it stood — so this reports a divergence
     * rather than undoing anything.
     */
    CONFLICT,

    /** The row could not be written for some other reason. The job survives only in this turn. */
    FAILED,

    /** There was nothing to store: no job, or no identity to store it under. */
    NOT_APPLICABLE
  }

  /** Raised when the stored row belongs to a different job from the one being saved. */
  public static class ConcurrentJobException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentJobException(String message) {
      super(message);
    }
  }

  /**
   * Writes the row. Public only so the transaction interceptor can see the call — see {@link
   * #save}; callers should use that.
   *
   * <h2>Same job, or a different one</h2>
   *
   * Everything turns on the workflow id. Where the stored row carries the same one, this turn and
   * whatever else has written are two turns of a single authorised job, and their progress is
   * merged. Where it carries a different one, they are two jobs, and nothing is merged: their
   * target sets and authorisations were each given for something specific, so a union would be an
   * authorisation nobody granted.
   *
   * <p>What happens then depends on when it was noticed. On the first attempt a different job in
   * the row is the ordinary case of a new instruction superseding an old one — the previous job is
   * marked abandoned and this one takes over. On the retry it is not: something wrote between our
   * read and our write, and what it wrote was another job. That is a genuine divergence and is
   * reported as one.
   *
   * @param afterConflict true when this is the retry following a failed write
   */
  @Transactional(rollbackOn = Exception.class)
  public void persist(
      AiWorkflow workflow,
      AiRoute route,
      Long userId,
      Long companyId,
      String conversationId,
      boolean afterConflict) {
    AiWorkflowStateRepository repo = Beans.get(AiWorkflowStateRepository.class);
    AiWorkflowState row = find(userId, companyId, conversationId);
    if (row != null && sameJob(row, workflow)) {
      mergeProgress(row, workflow);
    } else if (row != null) {
      if (afterConflict) {
        throw new ConcurrentJobException(
            "workflow "
                + workflow.getWorkflowId()
                + " could not be stored: job "
                + row.getWorkflowId()
                + " is now active for this user and company. Nothing was merged — two different"
                + " authorisations are not one.");
      }
      // A new instruction replaces the job that was running. «Осчетоводи 946» after a four-invoice
      // run is a new authorisation, and widening the old target set with it would authorise records
      // nobody named.
      row.setStatusSelect(STATUS_ABANDONED);
      repo.save(row);
      row = null;
    }
    if (row == null) {
      row = new AiWorkflowState();
      row.setUser(Beans.get(UserRepository.class).find(userId));
      row.setCompany(Beans.get(CompanyRepository.class).find(companyId));
      row.setConversationId(conversationId);
      row.setTurnCount(0);
    }

    row.setWorkflowId(workflow.getWorkflowId());
    row.setStatusSelect(workflow.isSettled() ? STATUS_SETTLED : STATUS_ACTIVE);
    row.setTask(workflow.getTask());
    row.setGoal(workflow.getGoal());
    row.setRouteDomain(route == null ? null : route.domain().name());
    row.setRouteScope(workflow.getScope());
    row.setExplicitness(workflow.getExplicitness());
    row.setRequest(workflow.getRequest());
    row.setStateJson(json(workflow));
    row.setTargetCount(workflow.getTargets().size());
    row.setCompletedCount(workflow.getCompleted().size());
    row.setBlockedCount(workflow.blocked().size());
    row.setTurnCount((row.getTurnCount() == null ? 0 : row.getTurnCount()) + 1);
    row.setLastTurnDateTime(LocalDateTime.now());
    repo.save(row);
  }

  /**
   * Makes sure a target this turn proved complete is not left blocked on ANY active row for this
   * user, company and conversation, whichever in-memory job actually wrote it.
   *
   * <h2>Why this exists beside the ordinary merge in {@link #persist}</h2>
   *
   * The ordinary path already clears a blocker when the SAME in-memory {@link AiWorkflow} that
   * carried the blocked target is the one {@link AiWorkflowController#markWorkDone} marks it
   * completed on, because that object is what gets persisted. This is the belt beside that
   * suspender: it reads the row(s) fresh, by key, and corrects any that still show {@code ref}
   * blocked and not completed. A target must never be simultaneously completed in one workflow and
   * blocked in another for the same user, company and conversation; this is what makes that true
   * regardless of which object's turn happened to do the writing.
   *
   * <h2>Every matching ACTIVE row, not only the one {@link #find} would return</h2>
   *
   * {@code (user, company, conversation)} is meant to carry at most one ACTIVE row at a time — see
   * {@link #persist}'s {@code sameJob}/abandon logic — but that is an invariant this store's own
   * write path maintains, not one the schema enforces: there is no database constraint stopping two
   * ACTIVE rows for the same key from existing (a write that raced ahead of an abandon, for
   * instance), and {@link #find}'s {@code order("-id").fetchOne()} would then silently reconcile
   * only the newest of them, leaving an older, orphaned row's copy of this same target blocked
   * forever with nothing left to ever revisit it. So every ACTIVE row for the key is corrected
   * here, not just the one turn resolution would pick.
   *
   * <p>A no-op, safely, when there is no active row, the ref is not on it, or it is already
   * completed there — so calling it for every ref this turn completed costs nothing extra on the
   * ordinary path where the two would have agreed anyway.
   */
  @Transactional(rollbackOn = Exception.class)
  public void reconcileCompletedTarget(
      Long userId, Long companyId, String conversationId, String ref) {
    if (userId == null || companyId == null || conversationId == null || ref == null) {
      return;
    }
    AiWorkflowStateRepository repo = Beans.get(AiWorkflowStateRepository.class);
    List<AiWorkflowState> rows;
    try {
      rows =
          repo.all()
              .filter(
                  "self.user.id = :userId AND self.company.id = :companyId"
                      + " AND self.conversationId = :conversationId AND self.statusSelect = :status")
              .bind("userId", userId)
              .bind("companyId", companyId)
              .bind("conversationId", conversationId)
              .bind("status", STATUS_ACTIVE)
              .fetch();
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not look up active workflows to reconcile: {}", e.getMessage());
      return;
    }
    for (AiWorkflowState row : rows) {
      reconcileOneRow(repo, row, ref);
    }
  }

  private void reconcileOneRow(AiWorkflowStateRepository repo, AiWorkflowState row, String ref) {
    if (row.getStateJson() == null) {
      return;
    }
    AiWorkflow stored;
    try {
      stored = MAPPER.readValue(row.getStateJson(), AiWorkflow.class);
    } catch (Exception e) {
      LOG.warn(
          "AI Accountant: could not reconcile '{}' on stored workflow {}: {}",
          ref,
          row.getId(),
          e.getMessage());
      return;
    }
    if (stored.getCompleted().contains(ref)) {
      return;
    }
    AiWorkflowRecord record = stored.find(ref);
    if (record == null || !record.isBlocked()) {
      return;
    }
    stored.markCompleted(ref);
    row.setStateJson(json(stored));
    row.setCompletedCount(stored.getCompleted().size());
    row.setBlockedCount(stored.blocked().size());
    row.setStatusSelect(stored.isSettled() ? STATUS_SETTLED : STATUS_ACTIVE);
    repo.save(row);
  }

  /**
   * Ends every active job this user has in <em>other</em> companies.
   *
   * <p>Called when the active company changes. An authorisation is given inside one set of books
   * and does not follow the user out of them: the invoices it named are not visible from here, and
   * a «продължи» typed after a company switch must not resume work on records the caller can no
   * longer even read. The rows are marked abandoned rather than deleted, because what was
   * authorised and what came of it is worth keeping.
   */
  @Transactional(rollbackOn = Exception.class)
  public void abandonOtherCompanies(Long userId, Long companyId) {
    if (userId == null || companyId == null) {
      return;
    }
    try {
      AiWorkflowStateRepository repo = Beans.get(AiWorkflowStateRepository.class);
      for (AiWorkflowState row :
          repo.all()
              .filter(
                  "self.user.id = :userId AND self.statusSelect = :status"
                      + " AND self.company.id != :companyId")
              .bind("userId", userId)
              .bind("companyId", companyId)
              .bind("status", STATUS_ACTIVE)
              .fetch()) {
        row.setStatusSelect(STATUS_ABANDONED);
        repo.save(row);
        LOG.info(
            "AI Accountant: abandoned workflow {} — the active company is now {}.",
            row.getId(),
            companyId);
      }
    } catch (RuntimeException e) {
      LOG.warn("AI Accountant: could not abandon workflows of other companies: {}", e.getMessage());
    }
  }

  /**
   * The stored row and this workflow are the same job.
   *
   * <p>Identity only. Not the request, which repeats — «осчетоводи 946» said twice is two
   * authorisations — and not the goal or the task, which repeat constantly. A row with no id at all
   * predates this field and is treated as a different job, so it is superseded once rather than
   * merged into on a guess about what it was.
   */
  protected boolean sameJob(AiWorkflowState row, AiWorkflow workflow) {
    String stored = row.getWorkflowId();
    String current = workflow.getWorkflowId();
    return stored != null && current != null && stored.equals(current);
  }

  /**
   * Folds what the row already knows into this turn's copy, for one and the same job.
   *
   * <h2>Progress only</h2>
   *
   * Targets, completions and blockers are things that happened; two turns of one job can each have
   * seen some of them, and the truth is the union. The authorisation is not in that category. The
   * goal, the task, the scope and the frozen target snapshot were decided when the job opened and
   * are identical in both copies by construction — {@code setAuthorisedTargets} refuses to replace
   * a snapshot in any case, so even a corrupted stored copy cannot widen this one.
   */
  protected void mergeProgress(AiWorkflowState row, AiWorkflow workflow) {
    AiWorkflow stored;
    try {
      stored = MAPPER.readValue(row.getStateJson(), AiWorkflow.class);
    } catch (Exception e) {
      LOG.warn("AI Accountant: could not merge stored progress: {}", e.getMessage());
      return;
    }
    for (AiWorkflowRecord record : stored.getTargets()) {
      workflow.addTarget(record.getRef());
    }
    for (String ref : stored.getCompleted()) {
      workflow.markCompleted(ref);
    }
    for (AiWorkflowRecord record : stored.getTargets()) {
      if (record.isBlocked()) {
        // markBlocked is itself a no-op on a completed record, so a target this turn finished is
        // not re-blocked by the other turn's older failure.
        workflow.markBlocked(
            record.getRef(), record.getBlocker(), record.getBlockerCode(), record.getReason());
      }
    }
  }

  protected String json(AiWorkflow workflow) {
    try {
      return MAPPER.writeValueAsString(workflow);
    } catch (Exception e) {
      // A job whose records cannot be serialised is one the next turn will not remember; the row
      // still records what was authorised, which is the part that governs a write.
      LOG.warn("AI Accountant: could not serialise the workflow: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The user this store keys on, or null when there is none.
   *
   * <p>Null is the intake pipeline, which has no conversation and therefore no workflow: it writes
   * under its own rules, and nothing here applies to it.
   */
  public static Long userIdOf(User user) {
    return user == null ? null : user.getId();
  }

  public static Long companyIdOf(Company company) {
    return company == null ? null : company.getId();
  }

  /** Blocked records of a stored job, for a caller that wants them without the whole workflow. */
  public static int blockedCount(AiWorkflow workflow) {
    if (workflow == null) {
      return 0;
    }
    int count = 0;
    for (AiWorkflowRecord record : workflow.getTargets()) {
      if (record.isBlocked()) {
        count++;
      }
    }
    return count;
  }
}
