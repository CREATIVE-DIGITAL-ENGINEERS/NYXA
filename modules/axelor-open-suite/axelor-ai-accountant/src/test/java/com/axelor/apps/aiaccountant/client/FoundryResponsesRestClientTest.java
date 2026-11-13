package com.axelor.apps.aiaccountant.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Request shape and retry behaviour, with HTTP stubbed at the {@code httpPost} seam. */
class FoundryResponsesRestClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String URL = "https://example/agents/ai-accountant/responses?api-version=v1";

  private static AiAccountantConfig config() {
    return new AiAccountantConfig() {
      @Override
      public String chatUrl() {
        return URL;
      }

      @Override
      public int maxRetries() {
        return 2;
      }
    };
  }

  /** Captures request bodies and replays scripted results without touching the network. */
  private static class TestClient extends FoundryResponsesRestClient {
    final Deque<HttpResult> script = new ArrayDeque<>();
    final List<String> bodies = new ArrayList<>();
    final List<String> urls = new ArrayList<>();
    int backoffs;

    TestClient() {
      super(config(), () -> "test-token");
    }

    @Override
    protected HttpResult httpPost(String url, String body, String bearer) {
      urls.add(url);
      bodies.add(body);
      return script.removeFirst();
    }

    @Override
    protected void backoff(int attempt, String retryAfter) {
      backoffs++; // never actually sleep in tests
    }
  }

  @Test
  void sendsInputAndOmitsPreviousResponseIdWhenNull() throws Exception {
    TestClient c = new TestClient();
    c.script.add(new FoundryResponsesRestClient.HttpResult(200, "{\"id\":\"r1\"}", null));

    FoundryResponse r = c.createResponse(null, MAPPER.getNodeFactory().textNode("hello"));

    assertEquals("r1", r.getId());
    assertEquals(URL, c.urls.get(0));
    com.fasterxml.jackson.databind.JsonNode sent = MAPPER.readTree(c.bodies.get(0));
    assertEquals("hello", sent.path("input").asText());
    assertTrue(sent.path("previous_response_id").isMissingNode());
    // The agent is identified by the URL, so these must never be sent.
    assertTrue(sent.path("agent_reference").isMissingNode());
    assertTrue(sent.path("model").isMissingNode());
    assertTrue(sent.path("tools").isMissingNode(), "tools are refused when an agent is specified");
  }

  @Test
  void sendsPreviousResponseIdWhenPresent() throws Exception {
    TestClient c = new TestClient();
    c.script.add(new FoundryResponsesRestClient.HttpResult(200, "{\"id\":\"r2\"}", null));

    c.createResponse("resp_prev", MAPPER.getNodeFactory().textNode("again"));

    assertEquals(
        "resp_prev", MAPPER.readTree(c.bodies.get(0)).path("previous_response_id").asText());
  }

  @Test
  void retriesTransientThenSucceeds() {
    TestClient c = new TestClient();
    c.script.add(new FoundryResponsesRestClient.HttpResult(429, "{}", "1"));
    c.script.add(new FoundryResponsesRestClient.HttpResult(503, "{}", null));
    c.script.add(new FoundryResponsesRestClient.HttpResult(200, "{\"id\":\"r3\"}", null));

    assertEquals("r3", c.createResponse(null, MAPPER.getNodeFactory().textNode("x")).getId());
    assertEquals(2, c.backoffs);
  }

  @Test
  void doesNotRetryClientErrors() {
    TestClient c = new TestClient();
    c.script.add(
        new FoundryResponsesRestClient.HttpResult(
            400,
            "{\"error\":{\"code\":\"invalid_payload\",\"message\":\"Not allowed when agent is"
                + " specified.\"}}",
            null));

    AiAccountantException e =
        assertThrows(
            AiAccountantException.class,
            () -> c.createResponse(null, MAPPER.getNodeFactory().textNode("x")));
    assertTrue(e.getMessage().contains("invalid_payload"));
    assertEquals(0, c.backoffs);
  }

  @Test
  void authFailureIsReportedClearly() {
    TestClient c = new TestClient();
    c.script.add(new FoundryResponsesRestClient.HttpResult(403, "{}", null));

    AiAccountantException e =
        assertThrows(
            AiAccountantException.class,
            () -> c.createResponse(null, MAPPER.getNodeFactory().textNode("x")));
    assertTrue(e.getMessage().contains("RBAC"));
  }
}
