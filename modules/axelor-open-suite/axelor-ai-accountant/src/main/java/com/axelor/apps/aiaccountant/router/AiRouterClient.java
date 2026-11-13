package com.axelor.apps.aiaccountant.router;

/**
 * Talks to the Router Agent: one question in, one JSON answer out.
 *
 * <h2>Why this is not {@code FoundryResponsesClient}</h2>
 *
 * The two share a service and nothing else. The main agent is a conversation — it chains on {@code
 * previous_response_id}, calls tools, retries hard because losing a turn loses the user's work, and
 * takes as long as it takes. The router is a classifier: one request, no history, no tools, no
 * retry, and a short deadline, because an answer that arrives after the turn has been routed is
 * worth nothing. Sharing a client would mean either giving the router the conversation's patience
 * or taking it away from the conversation.
 *
 * <p>Kept as an interface for the same reason the chat client is: it is the seam where a test, or a
 * later offline mode, substitutes something that does not need Azure.
 */
public interface AiRouterClient {

  /**
   * Asks the router to classify one turn.
   *
   * @param input the complete request text — contract and context, built by {@link
   *     AiRouterContract}
   * @return the agent's raw reply text, never null; validation is the caller's job
   * @throws RuntimeException on timeout, transport failure or a non-200 answer. The caller treats
   *     any throw as "the router did not answer", which in shadow mode is a logged data point.
   */
  String classify(String input);

  /**
   * As above, but bounded by the caller's own deadline rather than the configured one.
   *
   * <p>In active mode the turn waits for this answer, so the router spends the user's time budget
   * and not a budget of its own. The caller works out how much of the turn it may have — always
   * less than what remains, so the main agent still has time to do the work — and states it here.
   *
   * @param timeoutSeconds the hard ceiling for this call; never longer than the configured timeout
   */
  String classify(String input, int timeoutSeconds);

  /** Whether this client has everything it needs to be called at all. */
  boolean isConfigured();
}
