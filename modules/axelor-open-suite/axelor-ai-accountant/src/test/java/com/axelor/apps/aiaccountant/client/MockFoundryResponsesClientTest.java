package com.axelor.apps.aiaccountant.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The offline client. Worth testing in its own right: it is what Phase B development runs against
 * until Azure RBAC is granted, so if it drifts from the real wire shape the tests stop meaning
 * anything.
 */
class MockFoundryResponsesClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode text(String s) {
    return MAPPER.getNodeFactory().textNode(s);
  }

  @Test
  void reproducesTheRealOutputShapeWithReasoningFirst() {
    FoundryResponse r = new MockFoundryResponsesClient().createResponse(null, text("hello"));

    assertNotNull(r.getId());
    assertEquals("completed", r.getStatus());
    // The live service emits a reasoning item ahead of the message; the mock must too, so that a
    // parser assuming output[0] fails here rather than in production.
    assertEquals("reasoning", r.raw().path("output").get(0).path("type").asText());
    assertFalse(r.assistantText().isEmpty());
  }

  @Test
  void asksForTheUserInfoToolThenAnswersOnOutput() {
    MockFoundryResponsesClient client = new MockFoundryResponsesClient();

    FoundryResponse first = client.createResponse(null, text("Who am I?"));
    List<FoundryResponse.FunctionCall> calls = first.functionCalls();
    assertEquals(1, calls.size());
    assertEquals("get_current_user_info", calls.get(0).getName());
    assertNotNull(calls.get(0).getCallId());

    ArrayNode outputs = MAPPER.createArrayNode();
    ObjectNode item = outputs.addObject();
    item.put("type", "function_call_output");
    item.put("call_id", calls.get(0).getCallId());
    item.put("output", "{\"userCode\":\"admin\"}");

    FoundryResponse second = client.createResponse(first.getId(), outputs);
    assertTrue(second.functionCalls().isEmpty(), "the loop must terminate");
    assertFalse(second.assistantText().isEmpty());
  }

  @Test
  void returnsParseableCorrectionsForAnOcrRequest() throws Exception {
    String payload =
        MockFoundryResponsesClient.OCR_MARKER
            + "\n{\"problemFields\":[\"supplierEik\"],"
            + "\"extracted\":{\"supplierEik\":\"83191953\"}}";

    FoundryResponse r = new MockFoundryResponsesClient().createResponse(null, text(payload));
    JsonNode doc = MAPPER.readTree(r.assistantText());

    assertTrue(doc.path("corrections").isArray());
    JsonNode c = doc.path("corrections").get(0);
    assertEquals("supplierEik", c.path("field").asText());
    // Completes the truncated 8-digit EIK to the Bulgarian 9-digit form.
    assertEquals("831919536", c.path("suggestedValue").asText());
    assertEquals("SET", c.path("action").asText());
    assertTrue(c.path("confidence").asDouble() > 0.9);
  }

  @Test
  void reportsUnresolvedWhenItHasNoHeuristicToOffer() throws Exception {
    String payload =
        MockFoundryResponsesClient.OCR_MARKER + "\n{\"extracted\":{\"supplierEik\":\"831919536\"}}";

    FoundryResponse r = new MockFoundryResponsesClient().createResponse(null, text(payload));
    JsonNode doc = MAPPER.readTree(r.assistantText());

    assertEquals(0, doc.path("corrections").size(), "must not invent a correction");
    assertEquals("supplierEik", doc.path("unresolved").get(0).asText());
  }
}
