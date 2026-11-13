package com.axelor.apps.aiaccountant.client;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Talks to the dedicated Foundry agent Responses endpoint.
 *
 * <p>The agent identity is carried by the endpoint URL, so requests send no {@code agent_reference}
 * and no {@code model} — instructions, model and active version stay portal-managed.
 *
 * <p>Tools are NOT sent per request: the service rejects that with {@code "Not allowed when agent
 * is specified"} (A0). They are declared on the agent definition instead; see {@code
 * AiToolPublisher}.
 */
public interface FoundryResponsesClient {

  /**
   * Create a response.
   *
   * @param previousResponseId prior response to continue from, or null to start fresh
   * @param input either a text string node or an array of input items (e.g. {@code
   *     function_call_output})
   */
  FoundryResponse createResponse(String previousResponseId, JsonNode input);
}
