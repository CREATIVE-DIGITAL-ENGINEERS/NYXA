package com.axelor.apps.aiaccountant.client;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offline stand-in for the Foundry agent, selected with {@code aiaccountant.azure.mode=mock}.
 *
 * <p>Mirrors {@code MockOcrExtractor} in axelor-ai-ocr: it lets the whole pipeline — chat, tool
 * loop, OCR correction, validation — be exercised without Azure credentials or network access, and
 * keeps CI deterministic.
 *
 * <p>It reproduces the real wire shape recorded during the A0 spike (a {@code reasoning} item ahead
 * of the payload item), so parsing bugs surface here rather than in production.
 *
 * <p>Behaviour is deliberately simple and deterministic:
 *
 * <ul>
 *   <li>a turn carrying {@code function_call_output} items → final assistant message;
 *   <li>an OCR correction request (recognised by its marker line) → a corrections JSON document;
 *   <li>anything else → a canned reply, after one {@code get_current_user_info} tool call if that
 *       tool looks relevant, so the tool loop itself gets exercised.
 * </ul>
 */
@Singleton
public class MockFoundryResponsesClient implements FoundryResponsesClient {

  private static final Logger LOG = LoggerFactory.getLogger(MockFoundryResponsesClient.class);

  /** Marker the OCR correction prompt starts with; see {@code BgOcrAiCorrectionService}. */
  public static final String OCR_MARKER = "[NYXA-OCR-CORRECTION]";

  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicLong seq = new AtomicLong();

  @Override
  public FoundryResponse createResponse(String previousResponseId, JsonNode input) {
    String id = "resp_mock_" + seq.incrementAndGet();

    if (isToolOutput(input)) {
      LOG.info("AI Accountant [mock]: received tool output, returning a final answer.");
      return message(id, "Mock assistant: I used the tool and this is the final answer.");
    }

    String text = inputText(input);

    if (text.contains(OCR_MARKER)) {
      LOG.info("AI Accountant [mock]: answering an OCR correction request.");
      return message(id, mockCorrections(text));
    }

    if (text.toLowerCase().contains("who am i") || text.toLowerCase().contains("current user")) {
      LOG.info("AI Accountant [mock]: requesting get_current_user_info.");
      return functionCall(id, "call_mock_" + seq.get(), "get_current_user_info", "{}");
    }

    return message(id, "Mock assistant reply. (aiaccountant.azure.mode=mock)");
  }

  /**
   * A deterministic, deliberately conservative correction document.
   *
   * <p>It proposes nothing it cannot justify: only when the payload shows a supplier EIK that is
   * one digit short of the Bulgarian 9-digit form does it suggest anything, and even then Java
   * validation still has the final say. Everything else is reported as unresolved, which is exactly
   * the outcome the deterministic pipeline should keep producing when AI adds no value.
   */
  protected String mockCorrections(String payload) {
    ObjectNode root = mapper.createObjectNode();
    ArrayNode corrections = root.putArray("corrections");
    ArrayNode unresolved = root.putArray("unresolved");

    String eik = extractedField(payload, "supplierEik");
    if (eik != null && eik.matches("\\d{8}")) {
      ObjectNode c = corrections.addObject();
      c.put("field", "supplierEik");
      c.put("currentValue", eik);
      c.put("suggestedValue", eik + "6");
      c.put("confidence", 0.95);
      c.put("reason", "Mock: 8-digit EIK looks truncated; Bulgarian EIK is 9 digits.");
      c.put("action", "SET");
    } else {
      unresolved.add("supplierEik");
    }
    root.put("summary", "Mock correction result.");
    return root.toString();
  }

  /**
   * Reads one field out of the payload's {@code extracted} object.
   *
   * <p>Parses the embedded JSON rather than scanning for the key: the same field names appear in
   * {@code problemFields} too, so a naive text search matches the wrong occurrence.
   */
  protected String extractedField(String payload, String field) {
    int start = payload.indexOf('{');
    if (start < 0) {
      return null;
    }
    try {
      JsonNode root = mapper.readTree(payload.substring(start));
      JsonNode value = root.path("extracted").path(field);
      if (value.isMissingNode() || value.isNull()) {
        return null;
      }
      String text = value.asText("").trim();
      return text.isEmpty() ? null : text;
    } catch (Exception e) {
      return null;
    }
  }

  private boolean isToolOutput(JsonNode input) {
    if (input == null || !input.isArray()) {
      return false;
    }
    for (JsonNode item : input) {
      if ("function_call_output".equals(item.path("type").asText())) {
        return true;
      }
    }
    return false;
  }

  private String inputText(JsonNode input) {
    if (input == null) {
      return "";
    }
    if (input.isTextual()) {
      return input.asText();
    }
    return input.toString();
  }

  /** Builds the real output shape: a reasoning item first, then the message. */
  private FoundryResponse message(String id, String text) {
    ObjectNode root = mapper.createObjectNode();
    root.put("id", id);
    root.put("status", "completed");
    ArrayNode output = root.putArray("output");
    output.addObject().put("type", "reasoning");
    ObjectNode msg = output.addObject();
    msg.put("type", "message");
    msg.put("role", "assistant");
    ObjectNode part = msg.putArray("content").addObject();
    part.put("type", "output_text");
    part.put("text", text);
    return new FoundryResponse(root);
  }

  private FoundryResponse functionCall(String id, String callId, String name, String arguments) {
    ObjectNode root = mapper.createObjectNode();
    root.put("id", id);
    root.put("status", "completed");
    ArrayNode output = root.putArray("output");
    output.addObject().put("type", "reasoning");
    ObjectNode fc = output.addObject();
    fc.put("type", "function_call");
    fc.put("call_id", callId);
    fc.put("name", name);
    fc.put("arguments", arguments);
    return new FoundryResponse(root);
  }
}
