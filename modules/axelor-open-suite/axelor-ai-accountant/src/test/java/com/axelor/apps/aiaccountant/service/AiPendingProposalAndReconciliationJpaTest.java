package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.AiAccountantJpaTestModule;
import com.axelor.apps.aiaccountant.db.AiWorkflowState;
import com.axelor.apps.aiaccountant.db.repo.AiChatMessageRepository;
import com.axelor.apps.aiaccountant.db.repo.AiChatSessionRepository;
import com.axelor.apps.aiaccountant.db.repo.AiPendingProposalRepository;
import com.axelor.apps.aiaccountant.db.repo.AiTurnMemoryRepository;
import com.axelor.apps.aiaccountant.db.repo.AiWorkflowStateRepository;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@link AiPendingProposalStore} and {@link AiWorkflowStore#reconcileCompletedTarget} against a
 * real database — a repository fake cannot prove either the unique-per-key replace semantics or
 * that {@link AiConversationEraser} really deletes a pending proposal in the same transaction as
 * everything else, and a JSON round trip through a fake store cannot prove {@code
 * reconcileCompletedTarget} reads and rewrites the actual persisted row. See {@code
 * AiChatTranscriptJpaTest} for the same reasoning about the transcript.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules({AiAccountantJpaTestModule.class})
class AiPendingProposalAndReconciliationJpaTest {

  private Long userA;
  private Long companyOne;

  @BeforeEach
  void setUp() {
    JPA.runInTransaction(
        () -> {
          Beans.get(AiPendingProposalRepository.class).all().remove();
          Beans.get(AiChatMessageRepository.class).all().remove();
          Beans.get(AiTurnMemoryRepository.class).all().remove();
          Beans.get(AiWorkflowStateRepository.class).all().remove();
          Beans.get(AiChatSessionRepository.class).all().remove();
        });
    userA = user("accountant.a");
    companyOne = company("ONE");
  }

  private Long user(String code) {
    return JPA.withTransaction(
        () -> {
          UserRepository users = Beans.get(UserRepository.class);
          User existing = users.findByCode(code);
          if (existing != null) {
            return existing.getId();
          }
          User user = new User(code, code);
          user.setPassword("not-a-real-password");
          return users.save(user).getId();
        });
  }

  private Long company(String code) {
    return JPA.withTransaction(
        () -> {
          CompanyRepository companies = Beans.get(CompanyRepository.class);
          Company existing =
              companies.all().filter("self.code = :code").bind("code", code).fetchOne();
          if (existing != null) {
            return existing.getId();
          }
          Company company = new Company();
          company.setCode(code);
          company.setName(code);
          return companies.save(company).getId();
        });
  }

  // ── AiPendingProposalStore ───────────────────────────────────────────────

  /**
   * {@code @Transactional} is a Guice method interceptor — see {@code AiWorkflowStore}'s own
   * javadoc on {@code persist} — so every transactional call in this test goes through {@code
   * Beans.get(...)} rather than {@code new ...()}, which would silently run with no transaction and
   * fail with {@code TransactionRequiredException} the moment the method tries to write.
   */
  private AiPendingProposalStore proposals() {
    return Beans.get(AiPendingProposalStore.class);
  }

  private AiWorkflowStore workflows() {
    return Beans.get(AiWorkflowStore.class);
  }

  @Test
  void recordThenFindReturnsWhatWasStored() {
    proposals()
        .record(
            userA,
            companyOne,
            "c-1",
            "PartnerCreate:2:831257890",
            "Upload#18",
            "CREATE_SUPPLIER_INVOICE");
    AiPendingProposalStore.Reading found = proposals().find(userA, companyOne, "c-1");

    assertNotNull(found);
    assertEquals("PartnerCreate:2:831257890", found.canonicalTarget());
    assertEquals("Upload#18", found.sourceRef());
    assertEquals("CREATE_SUPPLIER_INVOICE", found.task());
  }

  /** Reading a second document's evidence retires the first — only the latest may be confirmed. */
  @Test
  void recordingASecondProposalReplacesTheFirst() {
    proposals()
        .record(
            userA,
            companyOne,
            "c-1",
            "PartnerCreate:2:831257890",
            "Upload#18",
            "CREATE_SUPPLIER_INVOICE");

    proposals()
        .record(
            userA,
            companyOne,
            "c-1",
            "PartnerCreate:2:999999999",
            "Upload#20",
            "CREATE_SUPPLIER_INVOICE");

    AiPendingProposalStore.Reading found = proposals().find(userA, companyOne, "c-1");
    assertEquals("Upload#20", found.sourceRef(), "only the most recently read proposal survives");
    assertEquals(1L, Beans.get(AiPendingProposalRepository.class).all().count());
  }

  @Test
  void consumeRemovesItAndFindThenReturnsNull() {
    proposals()
        .record(
            userA,
            companyOne,
            "c-1",
            "PartnerCreate:2:831257890",
            "Upload#18",
            "CREATE_SUPPLIER_INVOICE");

    proposals().consume(userA, companyOne, "c-1");

    assertNull(proposals().find(userA, companyOne, "c-1"));
  }

  /** Clear/Delete conversation must not leave a confirmable proposal behind. */
  @Test
  void conversationEraserAlsoRemovesThePendingProposal() {
    proposals()
        .record(
            userA,
            companyOne,
            "c-1",
            "PartnerCreate:2:831257890",
            "Upload#18",
            "CREATE_SUPPLIER_INVOICE");

    Beans.get(AiConversationEraser.class).erase(userA, companyOne);

    assertNull(proposals().find(userA, companyOne, "c-1"));
  }

  // ── AiWorkflowStore.reconcileCompletedTarget ─────────────────────────────

  private AiWorkflowStore.Outcome save(AiWorkflow workflow, String conversationId) {
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE, null, null, AiScope.NAMED_RECORDS, AiExplicitness.EXPLICIT);
    return workflows().save(workflow, route, userA, companyOne, conversationId);
  }

  /**
   * Reproduces the exact defect: a job blocked on {@code PartnerCreate:...} whose target is later
   * proven complete must not keep rendering that blocker on the stored row.
   */
  @Test
  void reconcileClearsAStoredBlockerOnceTheTargetIsProvenComplete() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("CREATE_SUPPLIER_INVOICE");
    workflow.addTarget("AiOcrUpload#11");
    workflow.markBlocked(
        "PartnerCreate:2:831642181", "HUMAN_DECISION_REQUIRED", "write_not_authorised", "blocked");
    workflow.setAuthorisedTargets(java.util.Arrays.asList("AiOcrUpload#11"));
    save(workflow, "c-2");

    AiWorkflowState row = workflows().find(userA, companyOne, "c-2");
    assertNotNull(row, "the row must exist before it can be reconciled");
    assertTrue(row.getStateJson().contains("PartnerCreate:2:831642181"));

    workflows().reconcileCompletedTarget(userA, companyOne, "c-2", "PartnerCreate:2:831642181");

    AiWorkflow reloaded = workflows().load(userA, companyOne, "c-2");
    assertTrue(
        reloaded.getCompleted().contains("PartnerCreate:2:831642181"),
        "the reconciled row must show the target completed");
    assertFalse(
        reloaded.blocked().stream().anyMatch(r -> "PartnerCreate:2:831642181".equals(r.getRef())),
        "a completed target must never still render as blocked");
  }

  /**
   * A second, unrelated blocked target on the SAME row must survive reconciliation untouched —
   * completing one target is not licence to touch any other.
   */
  @Test
  void reconcileLeavesAnUnrelatedBlockedTargetOnTheSameRowUntouched() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("CREATE_SUPPLIER_INVOICE");
    workflow.addTarget("AiOcrUpload#11");
    workflow.markBlocked(
        "PartnerCreate:2:831642181", "HUMAN_DECISION_REQUIRED", "write_not_authorised", "blocked");
    workflow.markBlocked(
        "PartnerCreate:2:999999999",
        "HUMAN_DECISION_REQUIRED",
        "write_not_authorised",
        "unrelated");
    workflow.setAuthorisedTargets(java.util.Arrays.asList("AiOcrUpload#11"));
    save(workflow, "c-2b");

    workflows().reconcileCompletedTarget(userA, companyOne, "c-2b", "PartnerCreate:2:831642181");

    AiWorkflow reloaded = workflows().load(userA, companyOne, "c-2b");
    assertTrue(reloaded.getCompleted().contains("PartnerCreate:2:831642181"));
    assertTrue(
        reloaded.blocked().stream().anyMatch(r -> "PartnerCreate:2:999999999".equals(r.getRef())),
        "the unrelated target must still be blocked — reconciliation touches only the ref it was"
            + " asked about");
  }

  /**
   * A different company's row, even one blocked on the exact same-looking ref text, must never be
   * touched by a reconciliation call scoped to another company.
   */
  @Test
  void reconcileNeverTouchesAnotherCompanysMatchingRow() {
    Long companyTwo = company("TWO-RECONCILE");
    AiWorkflow workflowA = new AiWorkflow();
    workflowA.setWriteJob(true);
    workflowA.addTarget("AiOcrUpload#11");
    workflowA.markBlocked(
        "PartnerCreate:2:831642181", "HUMAN_DECISION_REQUIRED", "write_not_authorised", "blocked");
    workflowA.setAuthorisedTargets(java.util.Arrays.asList("AiOcrUpload#11"));
    AiRoute routeA =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE, null, null, AiScope.NAMED_RECORDS, AiExplicitness.EXPLICIT);
    workflows().save(workflowA, routeA, userA, companyOne, "c-2c");

    AiWorkflow workflowB = new AiWorkflow();
    workflowB.setWriteJob(true);
    workflowB.addTarget("AiOcrUpload#11");
    workflowB.markBlocked(
        "PartnerCreate:2:831642181", "HUMAN_DECISION_REQUIRED", "write_not_authorised", "blocked");
    workflowB.setAuthorisedTargets(java.util.Arrays.asList("AiOcrUpload#11"));
    workflows().save(workflowB, routeA, userA, companyTwo, "c-2c");

    workflows().reconcileCompletedTarget(userA, companyOne, "c-2c", "PartnerCreate:2:831642181");

    AiWorkflow reloadedA = workflows().load(userA, companyOne, "c-2c");
    AiWorkflow reloadedB = workflows().load(userA, companyTwo, "c-2c");
    assertTrue(
        reloadedA.getCompleted().contains("PartnerCreate:2:831642181"),
        "company ONE's row is reconciled");
    assertFalse(
        reloadedB.getCompleted().contains("PartnerCreate:2:831642181"),
        "company TWO's row must not be touched by a reconciliation scoped to company ONE");
    assertTrue(
        reloadedB.blocked().stream().anyMatch(r -> "PartnerCreate:2:831642181".equals(r.getRef())),
        "company TWO's blocker must remain exactly as it was");
  }

  /** A no-op when the row has nothing blocked under that ref — never invents a completion. */
  @Test
  void reconcileIsANoOpWhenTheTargetIsNotOnTheRow() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.addTarget("AiOcrUpload#11");
    workflow.setAuthorisedTargets(java.util.Arrays.asList("AiOcrUpload#11"));
    save(workflow, "c-3");

    workflows().reconcileCompletedTarget(userA, companyOne, "c-3", "PartnerCreate:2:000000000");

    AiWorkflow reloaded = workflows().load(userA, companyOne, "c-3");
    assertFalse(reloaded.getCompleted().contains("PartnerCreate:2:000000000"));
  }

  // ── the "one ACTIVE row per key" invariant ───────────────────────────────

  private AiWorkflowState rawRow(String conversationId, String ref, boolean blocked) {
    return JPA.withTransaction(
        () -> {
          AiWorkflowStateRepository repo = Beans.get(AiWorkflowStateRepository.class);
          AiWorkflowState row = new AiWorkflowState();
          row.setUser(Beans.get(UserRepository.class).find(userA));
          row.setCompany(Beans.get(CompanyRepository.class).find(companyOne));
          row.setConversationId(conversationId);
          row.setWorkflowId(java.util.UUID.randomUUID().toString());
          row.setStatusSelect(1); // AiWorkflowStore.STATUS_ACTIVE
          AiWorkflow blob = new AiWorkflow();
          blob.setWriteJob(true);
          blob.addTarget("AiOcrUpload#11");
          if (blocked) {
            blob.markBlocked(ref, "HUMAN_DECISION_REQUIRED", "write_not_authorised", "blocked");
          }
          try {
            row.setStateJson(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(blob));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          row.setTargetCount(blob.getTargets().size());
          row.setBlockedCount(blob.blocked().size());
          row.setCompletedCount(0);
          row.setTurnCount(1);
          return repo.save(row);
        });
  }

  /**
   * Proves the "one ACTIVE row per (user, company, conversation)" invariant is enforced only by
   * {@code AiWorkflowStore.persist}'s own abandon-before-create logic, not by the schema: two rows
   * inserted directly, both ACTIVE, for the identical key, both persist without error. This is why
   * {@link AiWorkflowStore#reconcileCompletedTarget} must not trust {@link AiWorkflowStore#find}'s
   * single-row answer to be the only ACTIVE row in existence.
   */
  @Test
  void noDatabaseConstraintStopsTwoActiveRowsForTheSameKey() {
    rawRow("c-4", "PartnerCreate:2:831642181", true);
    rawRow("c-4", "PartnerCreate:2:831642181", true);

    long activeCount =
        Beans.get(AiWorkflowStateRepository.class)
            .all()
            .filter(
                "self.user.id = :userId AND self.company.id = :companyId"
                    + " AND self.conversationId = :conversationId AND self.statusSelect = 1")
            .bind("userId", userA)
            .bind("companyId", companyOne)
            .bind("conversationId", "c-4")
            .count();

    assertEquals(
        2L,
        activeCount,
        "no unique/partial-unique constraint prevents two ACTIVE rows for the same key");
  }

  /**
   * The defensive half of the fix: with two ACTIVE rows for the same key both blocked on the same
   * target, {@code find()} (ordered by id descending) would only ever see the newest one — but
   * reconciliation must clear the blocker on BOTH, or the older, now-orphaned row keeps rendering
   * it with nothing left to ever revisit it.
   */
  @Test
  void reconcileClearsTheBlockerOnEveryActiveRowForTheKey() {
    Long olderId = rawRow("c-5", "PartnerCreate:2:831642181", true).getId();
    Long newerId = rawRow("c-5", "PartnerCreate:2:831642181", true).getId();
    assertTrue(newerId > olderId);
    assertEquals(
        newerId, workflows().find(userA, companyOne, "c-5").getId(), "find() sees only the newest");

    workflows().reconcileCompletedTarget(userA, companyOne, "c-5", "PartnerCreate:2:831642181");

    for (Long id : new Long[] {olderId, newerId}) {
      AiWorkflowState row = Beans.get(AiWorkflowStateRepository.class).find(id);
      AiWorkflow reloaded;
      try {
        reloaded =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(row.getStateJson(), AiWorkflow.class);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      assertTrue(
          reloaded.getCompleted().contains("PartnerCreate:2:831642181"),
          "row " + id + " must show the target completed");
      assertFalse(
          reloaded.blocked().stream().anyMatch(r -> "PartnerCreate:2:831642181".equals(r.getRef())),
          "row " + id + " must not still render the target as blocked");
    }
  }

  // ── AiPendingProposalStore.consumeOtherCompanies ─────────────────────────

  /** A company switch is itself an intervening event — see {@code endWorkflowsOfOtherCompanies}. */
  @Test
  void consumeOtherCompaniesRemovesOnlyTheOtherCompanysProposal() {
    Long companyTwo = company("TWO");
    proposals()
        .record(
            userA,
            companyOne,
            "c-1",
            "PartnerCreate:1:831257890",
            "Upload#18",
            "CREATE_SUPPLIER_INVOICE");
    proposals()
        .record(
            userA,
            companyTwo,
            "c-6",
            "PartnerCreate:2:999999999",
            "Upload#20",
            "CREATE_SUPPLIER_INVOICE");

    proposals().consumeOtherCompanies(userA, companyOne);

    assertNotNull(
        proposals().find(userA, companyOne, "c-1"), "the current company's proposal survives");
    assertNull(
        proposals().find(userA, companyTwo, "c-6"), "the other company's proposal is invalidated");
  }
}
