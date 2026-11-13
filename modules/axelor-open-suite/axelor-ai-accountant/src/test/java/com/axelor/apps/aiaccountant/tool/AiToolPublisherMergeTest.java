package com.axelor.apps.aiaccountant.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.client.AiAccountantException;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What publishing tool declarations must NOT change about the agent.
 *
 * <p>The agent definition is portal-owned and holds more than tools: the model, the instructions,
 * and a {@code reasoning} block the live agent really does carry. Rebuilding the definition from a
 * hand-listed set of fields would quietly drop anything not on that list — {@code reasoning}
 * included — turning "refresh the tool list" into a behaviour change nobody asked for. Publishing
 * copies the definition and replaces only the function tools.
 */
class AiToolPublisherMergeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A definition shaped like the live one, including a field this code knows nothing about. */
  private static final String LIVE_DEFINITION =
      "{\"kind\":\"prompt\",\"model\":\"gpt-5\",\"instructions\":\"You are AI Accountant.\","
          + "\"reasoning\":{\"effort\":\"low\"},"
          + "\"tools\":[{\"type\":\"web_search\"},"
          + "{\"type\":\"function\",\"name\":\"gone_tool\",\"description\":\"d\","
          + "\"parameters\":{}}],"
          + "\"someFutureSetting\":true}";

  /** Captures the published body instead of sending it. */
  private static final class CapturingPublisher extends AiToolPublisher {
    String body;

    CapturingPublisher(AiToolRegistry registry) {
      super(publishAllowed(), null, registry);
    }

    @Override
    protected JsonNode readDefinition() {
      try {
        return MAPPER.readTree(LIVE_DEFINITION);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    protected HttpResult http(String method, String url, String requestBody) {
      this.body = requestBody;
      return new HttpResult(200, "{\"id\":\"ai-accountant:4\"}");
    }
  }

  private static AiAccountantConfig publishAllowed() {
    return new AiAccountantConfig() {
      @Override
      public boolean isAgentPublishAllowed() {
        return true;
      }
    };
  }

  private static AiToolRegistry registryWith(String... names) {
    return new AiToolRegistry() {
      @Override
      protected Map<String, AiTool> discover() {
        Map<String, AiTool> m = new LinkedHashMap<>();
        for (String name : names) {
          m.put(name, tool(name));
        }
        return m;
      }
    };
  }

  private static AiTool tool(String name) {
    return tool(name, true, "does " + name);
  }

  private static AiTool tool(String name, boolean published, String description) {
    return new AiTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public boolean published() {
        return published;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public JsonNode parametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return schema;
      }

      @Override
      public AiToolKind kind() {
        return AiToolKind.READ;
      }

      @Override
      public AiCapability capability() {
        // Irrelevant to publishing, which merges by name — but the interface has no default, so a
        // stub has to say something. CORE is the honest answer for a tool that does nothing.
        return AiCapability.CORE;
      }

      @Override
      public Object execute(JsonNode args) {
        return null;
      }
    };
  }

  @Test
  void everythingExceptFunctionToolsIsPreservedVerbatim() throws Exception {
    CapturingPublisher publisher =
        new CapturingPublisher(registryWith("get_ocr_record", "get_ocr_evidence"));
    assertEquals("ai-accountant:4", publisher.publish());

    JsonNode definition = MAPPER.readTree(publisher.body).path("definition");
    assertEquals("gpt-5", definition.path("model").asText());
    assertEquals("prompt", definition.path("kind").asText());
    assertEquals("You are AI Accountant.", definition.path("instructions").asText());
    // The two that a hand-listed copy lost.
    assertEquals("low", definition.path("reasoning").path("effort").asText());
    assertEquals(true, definition.path("someFutureSetting").asBoolean());
  }

  @Test
  void hostedToolsSurviveAndStaleFunctionToolsDoNot() throws Exception {
    CapturingPublisher publisher =
        new CapturingPublisher(registryWith("get_ocr_record", "get_ocr_evidence"));
    publisher.publish();

    JsonNode tools = MAPPER.readTree(publisher.body).path("definition").path("tools");
    assertEquals(3, tools.size(), "web_search + the two registered tools");
    assertEquals("web_search", tools.get(0).path("type").asText());

    StringBuilder names = new StringBuilder();
    for (JsonNode t : tools) {
      if ("function".equals(t.path("type").asText())) {
        names.append(t.path("name").asText()).append(' ');
      }
    }
    assertTrue(names.toString().contains("get_ocr_record"), names.toString());
    assertTrue(names.toString().contains("get_ocr_evidence"), names.toString());
    assertTrue(
        !names.toString().contains("gone_tool"),
        "a tool no longer in Java must not stay declared: " + names);
  }

  @Test
  void publishingIsRefusedUnlessExplicitlyEnabled() {
    AiToolPublisher publisher =
        new AiToolPublisher(new AiAccountantConfig(), null, registryWith("get_ocr_record")) {
          @Override
          protected JsonNode readDefinition() {
            throw new AssertionError("must not even read the definition");
          }
        };
    assertTrue(
        assertThrows(AiAccountantException.class, publisher::publish)
            .getMessage()
            .contains("allow.agent.publish"));
  }

  /** A definition declaring exactly one function tool, with a schema given as text. */
  private static AiToolPublisher publisherSeeing(String toolJson, AiToolRegistry registry) {
    return new AiToolPublisher(publishAllowed(), null, registry) {
      @Override
      protected JsonNode readDefinition() {
        try {
          return MAPPER.readTree("{\"kind\":\"prompt\",\"tools\":[" + toolJson + "]}");
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
    };
  }

  private static final String DECLARED =
      "{\"type\":\"function\",\"name\":\"a\",\"description\":\"does a\","
          + "\"parameters\":{\"type\":\"object\"}}";

  /**
   * The regression that made the check useless. {@code diff()} compared the agent against every
   * REGISTERED tool while {@code publish()} writes only the PUBLISHED ones, so a tool that opts out
   * of publication was permanently reported as missing — a warning publishing could never clear,
   * because publishing is what caused it.
   */
  @Test
  void anUnpublishedToolIsNotReportedAsMissingFromTheAgent() {
    AiToolRegistry registry =
        new AiToolRegistry() {
          @Override
          protected Map<String, AiTool> discover() {
            Map<String, AiTool> m = new LinkedHashMap<>();
            m.put("a", tool("a"));
            m.put("internal", tool("internal", false, "does internal"));
            return m;
          }
        };

    AiToolPublisher.ToolDiff diff = publisherSeeing(DECLARED, registry).diff();

    assertTrue(diff.isInSync(), "expected IN SYNC, got: " + diff);
    assertTrue(diff.getMissingOnAgent().isEmpty());
    assertTrue(diff.getStaleOnAgent().isEmpty(), "an unpublished tool is not stale on the agent");
  }

  /**
   * The drift the name-only comparison could not see: {@code search_ocr_records} gained an {@code
   * offset} parameter, the name was still declared, and the agent reported IN SYNC while the model
   * could not see the argument the operating procedure was telling it to use.
   */
  @Test
  void aChangedSchemaIsOutOfDateEvenThoughTheNameStillMatches() {
    AiToolRegistry registry = registryWith("a");
    String stale =
        "{\"type\":\"function\",\"name\":\"a\",\"description\":\"does a\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"gone\":{\"type\":\"string\"}}}}";

    AiToolPublisher.ToolDiff diff = publisherSeeing(stale, registry).diff();

    assertFalse(diff.isInSync());
    assertEquals(List.of("a"), diff.getOutOfDateOnAgent());
    assertTrue(diff.getMissingOnAgent().isEmpty(), "the name is declared; only the schema drifted");
  }

  /** Descriptions are contract too — the model chooses a tool by reading one. */
  @Test
  void aChangedDescriptionIsAlsoOutOfDate() {
    String stale =
        "{\"type\":\"function\",\"name\":\"a\",\"description\":\"an older wording\","
            + "\"parameters\":{\"type\":\"object\"}}";

    AiToolPublisher.ToolDiff diff = publisherSeeing(stale, registryWith("a")).diff();

    assertEquals(List.of("a"), diff.getOutOfDateOnAgent());
  }

  /** A tool the agent declares and Java no longer has is still stale, as it always was. */
  @Test
  void aToolTheRegistryNoLongerHasIsStale() {
    AiToolPublisher.ToolDiff diff = publisherSeeing(DECLARED, registryWith("b")).diff();

    assertEquals(List.of("a"), diff.getStaleOnAgent());
    assertEquals(List.of("b"), diff.getMissingOnAgent());
    assertFalse(diff.isInSync());
  }

  /** Field order in a re-serialised schema is not a change. */
  @Test
  void schemaComparisonIgnoresFieldOrder() {
    String reordered =
        "{\"name\":\"a\",\"type\":\"function\",\"parameters\":{\"type\":\"object\"},"
            + "\"description\":\"does a\"}";

    assertTrue(publisherSeeing(reordered, registryWith("a")).diff().isInSync());
  }

  /**
   * Every clash is named in one failure, with its contributing class, and a failed registry stays
   * failed on every access — nothing is chosen, dropped or cached.
   */
  @Test
  void aRegistryWithClashesNamesEveryOneAndKeepsFailing() {
    List<AiTool> discovered = List.of(tool("a"), tool("b"), tool("a"), tool("b"), tool(" "));
    AiToolRegistry registry =
        new AiToolRegistry() {
          @Override
          protected Map<String, AiTool> discover() {
            return index(discovered);
          }
        };

    IllegalStateException first = assertThrows(IllegalStateException.class, registry::all);
    String message = first.getMessage();
    assertTrue(message.contains("3 problem(s)"), message);
    assertTrue(message.contains("duplicate name 'a'"), message);
    assertTrue(message.contains("duplicate name 'b'"), message);
    assertTrue(message.contains("blank name()"), message);
    assertTrue(message.contains(AiToolPublisherMergeTest.class.getName()), message);
    assertThrows(IllegalStateException.class, registry::all, "a failed discovery is not cached");

    // What availability and turn entry ask: a problem, never an exception.
    assertTrue(registry.problem().contains("duplicate name 'a'"), registry.problem());
    // And a tool call against it is a clean refusal, not an exception escaping the tool loop.
    AiToolResult refused = new AiToolDispatcher(registry, null).dispatchInternal("a", "{}", null);
    assertFalse(refused.isSuccess());
    assertEquals(
        com.axelor.apps.aiaccountant.service.AiBlockerCode.TOOL_REGISTRY_INVALID.wire(),
        refused.getCode());
  }
}
