package com.axelor.apps.aiaccountant.client;

/**
 * Supplies an Entra bearer token for the Foundry data plane (scope {@code
 * https://ai.azure.com/.default}). The agent endpoint is Azure-RBAC protected, so a token is the
 * only accepted credential — API keys do not work there.
 *
 * <p>This interface is the seam that keeps production authentication flexible. Development uses
 * client credentials (or the Azure CLI); the deployed application will be granted Azure AI
 * permissions by the CTO, and whatever credential mechanism backs that grant becomes one more
 * implementation of this interface plus a binding — no change to the client, orchestrator, tools or
 * frontend.
 *
 * <p>Implementations must cache and refresh internally, and must never log the token.
 */
public interface AiTokenProvider {

  /**
   * @return a valid bearer token, never null/blank
   * @throws AiAccountantException when a token cannot be obtained
   */
  String bearerToken();
}
