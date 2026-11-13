package com.axelor.apps.aiaccountant.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin read-only view over a Foundry Responses payload. Deliberately does not model the whole
 * schema — only what the tool loop needs.
 *
 * <p>Shape confirmed against the live service (A0):
 *
 * <pre>
 * { "id": "resp_…", "status": "completed",
 *   "output": [ {"type":"reasoning", …},
 *               {"type":"function_call", "call_id":"call_…", "name":"…", "arguments":"{…}"},
 *               {"type":"message", "role":"assistant",
 *                "content":[{"type":"output_text","text":"…"}]} ] }
 * </pre>
 *
 * <p>Note {@code output} mixes item types and {@code reasoning} usually comes first, so the
 * assistant text must be found by scanning for {@code type == "message"} — never {@code output[0]}.
 */
public class FoundryResponse {

  private final JsonNode root;

  public FoundryResponse(JsonNode root) {
    this.root = root;
  }

  public JsonNode raw() {
    return root;
  }

  public String getId() {
    return root.path("id").asText(null);
  }

  public String getStatus() {
    return root.path("status").asText(null);
  }

  /**
   * The model the agent version actually ran on, as the service reports it (e.g. {@code gpt-5}).
   */
  public String getModel() {
    return root.path("model").asText(null);
  }

  /** The response this one was chained onto; null on the first request of a conversation. */
  public String getPreviousResponseId() {
    return root.path("previous_response_id").asText(null);
  }

  /** Which published agent version served the request — {@code agent_reference.version}. */
  public String getAgentVersion() {
    return root.path("agent_reference").path("version").asText(null);
  }

  /** {@code error.code} when the service reported one, else null. */
  public String getErrorCode() {
    JsonNode error = root.path("error");
    return error.isMissingNode() || error.isNull() ? null : error.path("code").asText(null);
  }

  /** Token accounting for this one request. Never null; absent numbers read as zero. */
  public Usage usage() {
    return new Usage(root.path("usage"));
  }

  /**
   * The {@code usage} object, exactly as the Responses API returns it.
   *
   * <p>Field names are taken from a captured live payload, not from documentation:
   *
   * <pre>
   * "usage": { "input_tokens": 5839,
   *            "input_tokens_details":  { "cached_tokens": 5632, "cache_write_tokens": 0 },
   *            "output_tokens": 489,
   *            "output_tokens_details": { "reasoning_tokens": 256 },
   *            "total_tokens": 6328 }
   * </pre>
   *
   * <p>{@code cached_tokens} is the part of the input that was served from the prompt cache, so
   * {@code input_tokens - cached_tokens} is what a turn actually pays for on the way in — the
   * number that matters when judging whether the stable prefix is staying stable.
   */
  public static final class Usage {
    private final JsonNode node;

    Usage(JsonNode node) {
      this.node =
          node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node;
    }

    public boolean isPresent() {
      return node.isObject();
    }

    public int inputTokens() {
      return node.path("input_tokens").asInt(0);
    }

    public int cachedInputTokens() {
      return node.path("input_tokens_details").path("cached_tokens").asInt(0);
    }

    public int cacheWriteTokens() {
      return node.path("input_tokens_details").path("cache_write_tokens").asInt(0);
    }

    public int outputTokens() {
      return node.path("output_tokens").asInt(0);
    }

    public int reasoningTokens() {
      return node.path("output_tokens_details").path("reasoning_tokens").asInt(0);
    }

    public int totalTokens() {
      return node.path("total_tokens").asInt(0);
    }

    /** Input tokens that were not served from cache — the part that is actually billed as new. */
    public int uncachedInputTokens() {
      return Math.max(0, inputTokens() - cachedInputTokens());
    }
  }

  /** Concatenated text of all assistant message items, in order. */
  public String assistantText() {
    StringBuilder sb = new StringBuilder();
    for (JsonNode item : root.path("output")) {
      if (!"message".equals(item.path("type").asText())) {
        continue;
      }
      for (JsonNode part : item.path("content")) {
        String text = part.path("text").asText(null);
        if (text != null && !text.isEmpty()) {
          if (sb.length() > 0) {
            sb.append("\n");
          }
          sb.append(text);
        }
      }
    }
    return sb.toString();
  }

  /** Every function call the model wants executed this turn (may be several — parallel calls). */
  public List<FunctionCall> functionCalls() {
    List<FunctionCall> calls = new ArrayList<>();
    for (JsonNode item : root.path("output")) {
      if ("function_call".equals(item.path("type").asText())) {
        calls.add(
            new FunctionCall(
                item.path("call_id").asText(null),
                item.path("name").asText(null),
                item.path("arguments").asText("")));
      }
    }
    return calls;
  }

  /** A single requested tool invocation. {@code arguments} is a JSON *string*, not an object. */
  public static final class FunctionCall {
    private final String callId;
    private final String name;
    private final String arguments;

    public FunctionCall(String callId, String name, String arguments) {
      this.callId = callId;
      this.name = name;
      this.arguments = arguments;
    }

    public String getCallId() {
      return callId;
    }

    public String getName() {
      return name;
    }

    public String getArguments() {
      return arguments;
    }
  }
}
