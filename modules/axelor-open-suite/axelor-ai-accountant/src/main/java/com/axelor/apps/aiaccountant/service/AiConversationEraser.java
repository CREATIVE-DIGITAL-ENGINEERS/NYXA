package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.db.repo.AiChatMessageRepository;
import com.axelor.apps.aiaccountant.db.repo.AiChatSessionRepository;
import com.axelor.apps.aiaccountant.db.repo.AiPendingProposalRepository;
import com.axelor.apps.aiaccountant.db.repo.AiTurnMemoryRepository;
import com.axelor.apps.aiaccountant.db.repo.AiWorkflowStateRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * Deletes every server-side row for one user's chat in one company — the visible transcript, the
 * stored conversation history, the authorised job and the chain-size measurement — for the "Delete
 * conversation" action.
 *
 * <h2>Why one class, and why one transaction</h2>
 *
 * The four rows live in independent tables but are one fact to the user: "this conversation is
 * gone." Deleting three of the four and failing on the last would leave the panel either showing a
 * transcript the user deleted or continuing a chain they ended. All four deletes run in one
 * transaction: either the conversation is gone, or nothing was touched and the caller is told it
 * failed. Dropping the {@code AiChatSession} row is what resets the model chain — the next turn
 * finds no measurement, resolves {@code UNMEASURED_CHAIN} and sends no {@code previous_response_id}
 * — so the transcript and the chain state can only die together.
 *
 * <h2>Why user and company, and not the conversation</h2>
 *
 * This used to be scoped to one {@code conversationId} as well. That was right while every stored
 * thing was per-tab, and wrong once the transcript became the durable, user-and-company-wide record
 * the panel actually displays: clearing it while leaving the previous tabs' chain and workflow rows
 * behind would mean the user pressed Delete, watched the chat empty, and left an authorised job on
 * record under an id their browser had already forgotten. "Clear this chat" now means what it says
 * for all four tables.
 *
 * <p>A consequence worth stating plainly: a job authorised in another tab of the same user and
 * company is cleared too. That is the intended direction — the user has just said this conversation
 * is over, and an authorisation surviving that statement is the more dangerous outcome.
 *
 * <h2>Scope is never taken from the caller</h2>
 *
 * This class trusts the {@code userId} and {@code companyId} it is given; it is {@code
 * AiAccountantServiceImpl} that must resolve them from the authenticated session and the active
 * company scope rather than from anything the client sent, exactly as every other lookup in this
 * module does. A caller that passed its own choice of user or company here would be able to erase
 * another person's conversation.
 */
@Singleton
public class AiConversationEraser {

  private static final String SCOPE = "self.user.id = :userId AND self.company.id = :companyId";

  @Transactional(rollbackOn = Exception.class)
  public void erase(Long userId, Long companyId) {
    // The transcript first: it is the one the user is looking at, and the one whose survival would
    // make the whole action look like it failed.
    Beans.get(AiChatMessageRepository.class)
        .all()
        .filter(SCOPE)
        .bind("userId", userId)
        .bind("companyId", companyId)
        .remove();
    Beans.get(AiTurnMemoryRepository.class)
        .all()
        .filter(SCOPE)
        .bind("userId", userId)
        .bind("companyId", companyId)
        .remove();
    Beans.get(AiWorkflowStateRepository.class)
        .all()
        .filter(SCOPE)
        .bind("userId", userId)
        .bind("companyId", companyId)
        .remove();
    // A stored proposal is scoped even narrower than a job — one specific canonical target, read
    // for one specific chat panel — but "this conversation is over" ends it exactly the same way:
    // nothing pending here may be confirmed by a conversation that starts afresh.
    Beans.get(AiPendingProposalRepository.class)
        .all()
        .filter(SCOPE)
        .bind("userId", userId)
        .bind("companyId", companyId)
        .remove();
    // Last, and the reason the chain resets: with no measurement row the next turn cannot claim a
    // chain to continue.
    Beans.get(AiChatSessionRepository.class)
        .all()
        .filter(SCOPE)
        .bind("userId", userId)
        .bind("companyId", companyId)
        .remove();
  }
}
