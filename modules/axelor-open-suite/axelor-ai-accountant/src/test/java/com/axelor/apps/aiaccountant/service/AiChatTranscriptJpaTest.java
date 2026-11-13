package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.AiAccountantJpaTestModule;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.db.AiChatSession;
import com.axelor.apps.aiaccountant.db.AiTurnMemory;
import com.axelor.apps.aiaccountant.db.AiUsageStat;
import com.axelor.apps.aiaccountant.db.AiWorkflowState;
import com.axelor.apps.aiaccountant.db.repo.AiChatMessageRepository;
import com.axelor.apps.aiaccountant.db.repo.AiChatSessionRepository;
import com.axelor.apps.aiaccountant.db.repo.AiTurnMemoryRepository;
import com.axelor.apps.aiaccountant.db.repo.AiUsageStatRepository;
import com.axelor.apps.aiaccountant.db.repo.AiWorkflowStateRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

/**
 * The transcript against a real database.
 *
 * <h2>Why this exists rather than another Mockito test</h2>
 *
 * Everything this class proves is a property of the schema and of transactions, and a repository
 * fake can express none of it: that the unique constraint on {@code (user, company, seq)} really
 * stops two rows taking the same position, that the idempotency key really refuses a replayed send,
 * that reading back through a freshly constructed store returns what a different instance wrote,
 * and that {@link AiConversationEraser} really deletes four tables in one transaction or none of
 * them. A fake that returns whatever the test put into a map would pass all five while the deployed
 * schema failed them.
 *
 * <p>HSQLDB in memory, schema generated from the same domain XML the WAR uses — see {@code
 * src/test/resources/axelor-config.properties} and {@code AiAccountantJpaTestModule}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules({AiAccountantJpaTestModule.class})
class AiChatTranscriptJpaTest {

  private Long userA;
  private Long userB;
  private Long companyOne;
  private Long companyTwo;

  /**
   * A store built by hand on every call.
   *
   * <p>Deliberately not a field: "survives a fresh service instance" is one of the things under
   * test, so every assertion that reads back does so through an object that has never seen the
   * write.
   */
  private AiChatTranscriptStore store() {
    return new AiChatTranscriptStore();
  }

  @BeforeEach
  void setUp() {
    JPA.runInTransaction(
        () -> {
          Beans.get(AiChatMessageRepository.class).all().remove();
          Beans.get(AiTurnMemoryRepository.class).all().remove();
          Beans.get(AiWorkflowStateRepository.class).all().remove();
          Beans.get(AiChatSessionRepository.class).all().remove();
          Beans.get(AiUsageStatRepository.class).all().remove();
        });
    userA = user("accountant.a");
    userB = user("accountant.b");
    companyOne = company("ONE");
    companyTwo = company("TWO");
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
          // Only to satisfy User's own min-length constraint. Nothing here authenticates: the
          // stores
          // take user and company ids as arguments, which is the same seam that keeps company scope
          // out of the client's reach in production.
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

  private List<String> texts(List<AiChatTranscriptStore.Message> messages) {
    return messages.stream()
        .map(AiChatTranscriptStore.Message::text)
        .collect(java.util.stream.Collectors.toList());
  }

  // ── the property this whole store exists for ────────────────────────────────

  @Test
  void transcriptSurvivesAFreshStoreInstance() {
    store()
        .append(
            userA,
            companyOne,
            "c-one",
            "t1",
            AiChatTranscriptStore.ROLE_USER,
            "Осчетоводи фактура 576",
            null);
    store()
        .append(
            userA,
            companyOne,
            "c-one",
            "t1",
            AiChatTranscriptStore.ROLE_ASSISTANT,
            "Готово.",
            Arrays.asList("postInvoice"));

    // A different object, as a redeployed controller or a restarted container would be.
    List<AiChatTranscriptStore.Message> reloaded = store().load(userA, companyOne);

    assertEquals(Arrays.asList("Осчетоводи фактура 576", "Готово."), texts(reloaded));
    assertEquals(AiChatTranscriptStore.ROLE_USER, reloaded.get(0).role());
    assertEquals(AiChatTranscriptStore.ROLE_ASSISTANT, reloaded.get(1).role());
    assertEquals(Arrays.asList("postInvoice"), reloaded.get(1).tools());
  }

  @Test
  void reloadReturnsTheSameOrderedMessagesEveryTime() {
    for (int i = 1; i <= 12; i++) {
      store()
          .append(
              userA,
              companyOne,
              "c-one",
              "t" + i,
              AiChatTranscriptStore.ROLE_USER,
              "message " + i,
              null);
    }

    List<String> first = texts(store().load(userA, companyOne));
    List<String> second = texts(store().load(userA, companyOne));

    assertEquals(12, first.size());
    assertEquals(first, second);
    assertEquals("message 1", first.get(0));
    assertEquals("message 12", first.get(11));
  }

  /** F5, a retried POST, a replayed request: the same send must never produce two bubbles. */
  @Test
  void aReplayedSendDoesNotDuplicateTheMessage() {
    store()
        .append(userA, companyOne, "c-one", "t1", AiChatTranscriptStore.ROLE_USER, "Здравей", null);
    store()
        .append(userA, companyOne, "c-one", "t1", AiChatTranscriptStore.ROLE_USER, "Здравей", null);
    store()
        .append(userA, companyOne, "c-one", "t1", AiChatTranscriptStore.ROLE_USER, "Здравей", null);

    assertEquals(Arrays.asList("Здравей"), texts(store().load(userA, companyOne)));
  }

  /** Two bubbles of the SAME send are different rows: the key is the turn AND the role. */
  @Test
  void theSameTurnKeyStillStoresBothBubblesOfThatTurn() {
    store()
        .append(userA, companyOne, "c-one", "t1", AiChatTranscriptStore.ROLE_USER, "Питам", null);
    store()
        .append(
            userA,
            companyOne,
            "c-one",
            "t1",
            AiChatTranscriptStore.ROLE_ASSISTANT,
            "Отговарям",
            null);

    assertEquals(Arrays.asList("Питам", "Отговарям"), texts(store().load(userA, companyOne)));
  }

  @Test
  void positionsAreDistinctAndTheConstraintIsReal() {
    store().append(userA, companyOne, "c-one", "t1", AiChatTranscriptStore.ROLE_USER, "one", null);
    store().append(userA, companyOne, "c-one", "t2", AiChatTranscriptStore.ROLE_USER, "two", null);

    List<Long> positions =
        Beans.get(AiChatMessageRepository.class).all().order("seq").fetch().stream()
            .map(row -> row.getSeq())
            .collect(java.util.stream.Collectors.toList());

    assertEquals(2, positions.size());
    assertNotEquals(positions.get(0), positions.get(1));

    // And the database, not the store's arithmetic, is what forbids a collision: writing a second
    // row at an occupied position fails even when the store's own guard is bypassed.
    assertThrows(
        RuntimeException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  com.axelor.apps.aiaccountant.db.AiChatMessage clash =
                      new com.axelor.apps.aiaccountant.db.AiChatMessage();
                  clash.setUser(Beans.get(UserRepository.class).find(userA));
                  clash.setCompany(Beans.get(CompanyRepository.class).find(companyOne));
                  clash.setSeq(positions.get(0));
                  clash.setRoleSelect(AiChatTranscriptStore.ROLE_USER);
                  clash.setContent("collision");
                  Beans.get(AiChatMessageRepository.class).save(clash);
                  JPA.flush();
                }));
    JPA.clear();
  }

  // ── isolation ───────────────────────────────────────────────────────────────

  @Test
  void oneUsersChatIsNeverVisibleToAnother() {
    store()
        .append(userA, companyOne, "c-a", "t1", AiChatTranscriptStore.ROLE_USER, "A's chat", null);
    store()
        .append(userB, companyOne, "c-b", "t1", AiChatTranscriptStore.ROLE_USER, "B's chat", null);

    assertEquals(Arrays.asList("A's chat"), texts(store().load(userA, companyOne)));
    assertEquals(Arrays.asList("B's chat"), texts(store().load(userB, companyOne)));
  }

  @Test
  void oneCompanysChatIsNeverVisibleInAnother() {
    store()
        .append(userA, companyOne, "c-a", "t1", AiChatTranscriptStore.ROLE_USER, "books one", null);
    store()
        .append(userA, companyTwo, "c-a", "t2", AiChatTranscriptStore.ROLE_USER, "books two", null);

    assertEquals(Arrays.asList("books one"), texts(store().load(userA, companyOne)));
    assertEquals(Arrays.asList("books two"), texts(store().load(userA, companyTwo)));
  }

  /**
   * The transcript is keyed by user and company and NOT by conversation — the whole point of the
   * fix. A new tab, and therefore a new conversation id, still sees the same chat.
   */
  @Test
  void aNewConversationIdStillSeesTheSameTranscript() {
    store()
        .append(userA, companyOne, "c-first", "t1", AiChatTranscriptStore.ROLE_USER, "asked", null);
    store()
        .append(
            userA,
            companyOne,
            "c-second",
            "t2",
            AiChatTranscriptStore.ROLE_USER,
            "asked again",
            null);

    assertEquals(Arrays.asList("asked", "asked again"), texts(store().load(userA, companyOne)));
  }

  // ── delete ──────────────────────────────────────────────────────────────────

  @Test
  void deleteRemovesTheTranscriptAndTheChainStateTogether() {
    store().append(userA, companyOne, "c-a", "t1", AiChatTranscriptStore.ROLE_USER, "mine", null);
    store().append(userB, companyOne, "c-b", "t1", AiChatTranscriptStore.ROLE_USER, "theirs", null);
    store()
        .append(
            userA, companyTwo, "c-a", "t2", AiChatTranscriptStore.ROLE_USER, "other books", null);
    chainState(userA, companyOne, "c-a");
    chainState(userB, companyOne, "c-b");

    Beans.get(AiConversationEraser.class).erase(userA, companyOne);

    // Gone, all four tables, for this user in this company.
    assertTrue(store().load(userA, companyOne).isEmpty());
    assertEquals(0, count(AiTurnMemoryRepository.class, userA, companyOne));
    assertEquals(0, count(AiWorkflowStateRepository.class, userA, companyOne));
    assertEquals(0, count(AiChatSessionRepository.class, userA, companyOne));

    // Untouched for everybody else. This is the assertion that would catch a filter losing its
    // user or company binding, which would quietly erase the whole table.
    assertEquals(Arrays.asList("theirs"), texts(store().load(userB, companyOne)));
    assertEquals(Arrays.asList("other books"), texts(store().load(userA, companyTwo)));
    assertEquals(1, count(AiTurnMemoryRepository.class, userB, companyOne));
    assertEquals(1, count(AiChatSessionRepository.class, userB, companyOne));
  }

  /**
   * A failure part-way through the delete must leave everything, not a half-cleared conversation.
   *
   * <p>Forced by rolling the transaction back after the same four deletes have run: if they were
   * four independent commits rather than one, the rows that were already gone would stay gone.
   */
  @Test
  void aRolledBackDeleteLeavesTheTranscriptAndTheChainStateIntact() {
    store().append(userA, companyOne, "c-a", "t1", AiChatTranscriptStore.ROLE_USER, "kept", null);
    chainState(userA, companyOne, "c-a");

    assertThrows(
        RuntimeException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  Beans.get(AiConversationEraser.class).erase(userA, companyOne);
                  JPA.flush();
                  throw new IllegalStateException("forced failure after the deletes");
                }));
    JPA.clear();

    assertEquals(Arrays.asList("kept"), texts(store().load(userA, companyOne)));
    assertEquals(1, count(AiTurnMemoryRepository.class, userA, companyOne));
    assertEquals(1, count(AiWorkflowStateRepository.class, userA, companyOne));
    assertEquals(1, count(AiChatSessionRepository.class, userA, companyOne));
  }

  // ── the transaction boundary ────────────────────────────────────────────────

  /**
   * The append commits by itself, with no help from the caller.
   *
   * <p>This is the guarantee the write path depends on: the user's message has to be durable before
   * the model is called and before any write tool runs. Asserted by reading the row back through a
   * store that was never in the writing transaction, with no enclosing transaction anywhere.
   */
  @Test
  void anAppendCommitsOnItsOwnWithNoCallerTransaction() {
    assertFalse(JPA.em().getTransaction().isActive());

    store()
        .append(userA, companyOne, "c-a", "t1", AiChatTranscriptStore.ROLE_USER, "durable", null);

    // Nothing here committed anything, and the row is there.
    assertFalse(JPA.em().getTransaction().isActive());
    JPA.clear();
    assertEquals(Arrays.asList("durable"), texts(store().load(userA, companyOne)));
  }

  /**
   * The append refuses to run inside somebody else's transaction rather than joining it.
   *
   * <p>{@code JPA.runInTransaction} joins when one is already active and then commits nothing — the
   * commit belongs to whoever opened it. Using it here would leave the user's message uncommitted
   * for the whole tool loop, and a rollback would erase the message while leaving the invoice it
   * posted posted. Refusing is loud, immediate, and cannot be mistaken for a database fault months
   * later.
   */
  @Test
  void anAppendRefusesToJoinACallersTransaction() {
    AiTranscriptPersistenceException thrown =
        assertThrows(
            AiTranscriptPersistenceException.class,
            () ->
                JPA.runInTransaction(
                    () ->
                        store()
                            .append(
                                userA,
                                companyOne,
                                "c-a",
                                "t1",
                                AiChatTranscriptStore.ROLE_USER,
                                "joined",
                                null)));

    assertTrue(thrown.getMessage().contains("caller's transaction"));
    JPA.clear();
    assertTrue(store().load(userA, companyOne).isEmpty());
  }

  // ── what must NOT remove it ─────────────────────────────────────────────────

  /**
   * An Azure context reset does not touch the visible history.
   *
   * <p>A reset — a memory preset that shrinks the budget, a chain that outgrew it, a company change
   * — is exactly the deletion of the {@code AiChatSession} measurement performed here: with no row
   * the next turn resolves {@code UNMEASURED_CHAIN} and sends no {@code previous_response_id}. The
   * transcript is a separate table with a different key, so it cannot be collateral damage, and
   * this is the assertion that keeps it that way.
   */
  @Test
  void resettingTheAzureChainLeavesTheVisibleHistory() {
    store().append(userA, companyOne, "c-a", "t1", AiChatTranscriptStore.ROLE_USER, "before", null);
    chainState(userA, companyOne, "c-a");

    JPA.runInTransaction(
        () ->
            Beans.get(AiChatSessionRepository.class)
                .all()
                .filter("self.user.id = :userId")
                .bind("userId", userA)
                .remove());

    assertEquals(0, count(AiChatSessionRepository.class, userA, companyOne));
    assertEquals(Arrays.asList("before"), texts(store().load(userA, companyOne)));
  }

  /**
   * Scheduled retention prunes telemetry and nothing else.
   *
   * <p>Pinned against the real tables rather than by reading the source, because the failure mode
   * this guards against is somebody adding one more {@code DELETE FROM} to the nightly job. A
   * transcript is not telemetry, and the user deleting it is the only normal path by which it goes.
   */
  @Test
  void scheduledRetentionDoesNotRemoveTheTranscript() {
    store().append(userA, companyOne, "c-a", "t1", AiChatTranscriptStore.ROLE_USER, "kept", null);
    JPA.runInTransaction(
        () -> {
          AiUsageStat ancient = new AiUsageStat();
          ancient.setRequestDateTime(LocalDateTime.now().minusYears(5));
          Beans.get(AiUsageStatRepository.class).save(ancient);
        });

    AiAccountantConfig config = Mockito.mock(AiAccountantConfig.class);
    Mockito.when(config.usageStatRetentionDays()).thenReturn(180);
    AiAccountantRetentionService retention = new AiAccountantRetentionService(config);
    JPA.runInTransaction(retention::pruneUsageStats);

    assertEquals(0, Beans.get(AiUsageStatRepository.class).all().count());
    assertEquals(Arrays.asList("kept"), texts(store().load(userA, companyOne)));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** One row in each of the three conversation tables, as a live chat would have. */
  private void chainState(Long userId, Long companyId, String conversationId) {
    JPA.runInTransaction(
        () -> {
          User user = Beans.get(UserRepository.class).find(userId);
          Company company = Beans.get(CompanyRepository.class).find(companyId);

          AiTurnMemory memory = new AiTurnMemory();
          memory.setUser(user);
          memory.setCompany(company);
          memory.setConversationId(conversationId);
          memory.setHistoryJson("[]");
          memory.setLastTurnDateTime(LocalDateTime.now());
          Beans.get(AiTurnMemoryRepository.class).save(memory);

          AiWorkflowState workflow = new AiWorkflowState();
          workflow.setUser(user);
          workflow.setCompany(company);
          workflow.setConversationId(conversationId);
          workflow.setWorkflowId("w-" + conversationId);
          workflow.setStatusSelect(1);
          Beans.get(AiWorkflowStateRepository.class).save(workflow);

          AiChatSession session = new AiChatSession();
          session.setUser(user);
          session.setCompany(company);
          session.setConversationId(conversationId);
          session.setChainId("chain-" + conversationId);
          session.setLastInputTokens(1000);
          Beans.get(AiChatSessionRepository.class).save(session);
        });
  }

  private long count(Class<?> repositoryType, Long userId, Long companyId) {
    return ((com.axelor.db.JpaRepository<?>) Beans.get(repositoryType))
        .all()
        .filter("self.user.id = :userId AND self.company.id = :companyId")
        .bind("userId", userId)
        .bind("companyId", companyId)
        .count();
  }
}
