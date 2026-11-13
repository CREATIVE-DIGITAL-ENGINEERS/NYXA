package com.axelor.apps.aiaccountant.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** URL assembly. Overrides the accessors so no AppSettings/env is needed. */
class AiAccountantConfigTest {

  private static AiAccountantConfig with(String agentEndpoint, String projectEndpoint) {
    return new AiAccountantConfig() {
      @Override
      protected String agentEndpoint() {
        return agentEndpoint;
      }

      @Override
      protected String projectEndpoint() {
        return projectEndpoint;
      }

      @Override
      protected String agentName() {
        return "ai-accountant";
      }

      @Override
      protected String apiVersion() {
        return "v1";
      }
    };
  }

  @Test
  void chatUrlAppendsMandatoryApiVersion() {
    // The agent endpoint 400s with "Missing required query parameter: api-version".
    AiAccountantConfig c =
        with(
            "https://example-foundry.services.ai.azure.com/api/projects/example-project/agents/"
                + "ai-accountant/endpoint/protocols/openai/responses",
            "");
    assertTrue(c.chatUrl().endsWith("/responses?api-version=v1"));
    assertTrue(c.isChatConfigured());
  }

  @Test
  void chatUrlUsesAmpersandWhenQueryAlreadyPresent() {
    assertEquals(
        "https://x/responses?foo=1&api-version=v1",
        with("https://x/responses?foo=1", "").chatUrl());
  }

  @Test
  void chatUrlIsEmptyAndUnconfiguredWhenEndpointMissing() {
    AiAccountantConfig c = with("", "");
    assertEquals("", c.chatUrl());
    assertFalse(c.isChatConfigured());
  }

  @Test
  void agentDefinitionAndCreateUrlsStripTrailingSlash() {
    AiAccountantConfig c = with("", "https://host/api/projects/example-project/");
    assertEquals(
        "https://host/api/projects/example-project/agents/ai-accountant?api-version=v1",
        c.agentDefinitionUrl());
    // The versions route, not /agents — the latter answers 409 once the agent exists.
    assertEquals(
        "https://host/api/projects/example-project/agents/ai-accountant/versions?api-version=v1",
        c.agentCreateUrl());
  }

  @Test
  void projectUrlsAreEmptyWithoutProjectEndpoint() {
    AiAccountantConfig c = with("https://x/responses", "");
    assertEquals("", c.agentDefinitionUrl());
    assertEquals("", c.agentCreateUrl());
  }
}
