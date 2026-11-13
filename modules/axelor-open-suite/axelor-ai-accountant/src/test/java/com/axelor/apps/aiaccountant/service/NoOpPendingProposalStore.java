package com.axelor.apps.aiaccountant.service;

/**
 * A pending-proposal store that never has anything pending — for tests built with no Guice injector
 * at all, which override {@link AiWorkflowController#pendingProposalStore()} (via {@link
 * AiAccountantServiceImpl#workflowController()}) to supply this instead of a real, Guice-bound one.
 * See {@link AiWorkflowController#promoteIfConfirmed}'s javadoc: that method no longer catches a
 * broken injector itself, on purpose, so a test with none must provide a working substitute.
 */
final class NoOpPendingProposalStore extends AiPendingProposalStore {

  @Override
  public Reading find(Long userId, Long companyId, String conversationId) {
    return null;
  }
}
