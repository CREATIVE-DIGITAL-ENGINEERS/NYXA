package com.axelor.apps.napsubmissions.common.audit;

import com.axelor.apps.napsubmissions.db.NapAuditEvent;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * Writes the append-only compliance trail. Every workflow transition goes through here; nothing
 * else creates {@link NapAuditEvent}s and they are never edited afterwards.
 */
@Singleton
public class NapAuditService {

  protected final NapAuditEventRepository auditRepo;

  @Inject
  public NapAuditService(NapAuditEventRepository auditRepo) {
    this.auditRepo = auditRepo;
  }

  /** Log a human-initiated event (actor = current user). */
  @Transactional
  public NapAuditEvent logHuman(
      SaftReport report,
      NapSubmission submission,
      String eventType,
      String previousStatus,
      String newStatus,
      String details) {
    return log(
        report,
        submission,
        eventType,
        previousStatus,
        newStatus,
        NapAuditEventRepository.ACTOR_HUMAN,
        AuthUtils.getUser(),
        details);
  }

  /** Full event write. Jobs/system callers pass {@link NapAuditEvent#ACTOR_SYSTEM}/ACTOR_JOB. */
  @Transactional
  public NapAuditEvent log(
      SaftReport report,
      NapSubmission submission,
      String eventType,
      String previousStatus,
      String newStatus,
      String actorType,
      User actorUser,
      String details) {
    NapAuditEvent event = new NapAuditEvent();
    event.setSaftReport(report);
    event.setSubmission(submission);
    event.setEventTypeSelect(eventType);
    event.setPreviousStatus(previousStatus);
    event.setNewStatus(newStatus);
    event.setActorTypeSelect(actorType);
    event.setActorUser(actorUser);
    event.setDetails(details);
    if (report != null) {
      event.setTargetModel(SaftReport.class.getName());
      event.setTargetRecordId(report.getId());
    } else if (submission != null) {
      event.setTargetModel(NapSubmission.class.getName());
      event.setTargetRecordId(submission.getId());
    }
    return auditRepo.save(event);
  }
}
