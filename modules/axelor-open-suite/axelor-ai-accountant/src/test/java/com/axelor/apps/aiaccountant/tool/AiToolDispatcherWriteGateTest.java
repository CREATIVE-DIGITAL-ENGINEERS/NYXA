package com.axelor.apps.aiaccountant.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * There is no global write switch any more: a WRITE tool's fate is decided entirely by the
 * chat-write allowlist (and, further up the chain, authorisation/company/record checks this test
 * does not reach). This replaces the old on/off/inherit master-switch coverage.
 */
class AiToolDispatcherWriteGateTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNode EMPTY_ARGS = MAPPER.createObjectNode();

  private static AiTool writeTool(String name) {
    return new AiTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return name;
      }

      @Override
      public JsonNode parametersSchema() {
        return MAPPER.createObjectNode();
      }

      @Override
      public AiToolKind kind() {
        return AiToolKind.WRITE;
      }

      @Override
      public AiCapability capability() {
        return AiCapability.SUPPLIER_INVOICE;
      }

      @Override
      public Object execute(JsonNode args) {
        return null;
      }
    };
  }

  private static AiToolDispatcher dispatcher(Set<String> chatWriteTools) {
    AiAccountantConfig config =
        new AiAccountantConfig() {
          @Override
          public Set<String> chatWriteTools() {
            return chatWriteTools;
          }

          @Override
          public Set<String> unattendedWriteTools() {
            return Collections.emptySet();
          }
        };
    return new AiToolDispatcher(null, config);
  }

  @Test
  void aWriteToolAllowlistedForChatIsNotBlocked() {
    AiTool tool = writeTool("edit_supplier_invoice");
    AiToolDispatcher dispatcher = dispatcher(Collections.singleton("edit_supplier_invoice"));

    // No global feature switch left to consult; only the per-tool chat allowlist. With no
    // AiRequestContext on this thread, AiWriteAuthorisation.check() also stands aside (it is about
    // whether the conversation asked for this, which is out of scope here), so null means
    // "proceed".
    assertNull(dispatcher.checkWriteChannel(tool, EMPTY_ARGS));
  }

  @Test
  void aWriteToolNotInTheChatAllowlistIsStillBlocked() {
    AiTool tool = writeTool("edit_supplier_invoice");
    AiToolDispatcher dispatcher = dispatcher(Collections.emptySet());

    AiToolResult refusal = dispatcher.checkWriteChannel(tool, EMPTY_ARGS);

    assertEquals("write_not_allowed", refusal.getCode());
  }
}
