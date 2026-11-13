package com.axelor.apps.aiaccountant.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The assistant does not post. Ventilating an invoice and accounting a move are a person's act on
 * the form, so {@code ventilate_supplier_invoice}, {@code ventilate_customer_invoice} and {@code
 * post_move} are not in the chat-write allowlist the application SHIPS with.
 *
 * <p>The witness reads the real {@code src/main/resources/axelor-config.properties} of the
 * application — not a hand-written set — and puts each posting call through the same gate the
 * dispatcher runs before any WRITE tool. {@link AiToolDispatcherWriteGateTest} proves the gate
 * refuses what is not listed; this test proves what IS listed.
 */
class AiToolDispatcherShippedConfigTest {

  private static final String KEY = "aiaccountant.write.chat.tools";

  /** The fallback {@link AiAccountantConfig#chatWriteTools()} reads when {@link #KEY} is empty. */
  private static final String LEGACY_KEY = "aiaccountant.write.confirmed.tools";

  private static final List<String> POSTING_TOOLS =
      Arrays.asList("ventilate_supplier_invoice", "ventilate_customer_invoice", "post_move");

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNode EMPTY_ARGS = MAPPER.createObjectNode();

  @Test
  void theShippedConfigRefusesEveryPostingCallFromTheChat() throws IOException {
    Set<String> shipped = shippedChatWriteTools();
    AiToolDispatcher dispatcher = dispatcher(shipped);

    for (String name : POSTING_TOOLS) {
      AiToolResult refusal = dispatcher.checkWriteChannel(writeTool(name), EMPTY_ARGS);

      assertNotNull(refusal, name + " is allowed from the chat by the shipped config: " + shipped);
      assertEquals("write_not_allowed", refusal.getCode(), name);
      assertTrue(
          String.valueOf(refusal.getHumanMessage()).contains("not enabled for use from the chat"),
          name + ": " + refusal.getHumanMessage());
    }
  }

  /**
   * Control: the refusal above is the allowlist speaking, not an empty or unreadable list that
   * refuses everything. A non-posting write the shipped config does name still passes the gate.
   */
  @Test
  void theShippedConfigStillAllowsTheNonPostingWrites() throws IOException {
    Set<String> shipped = shippedChatWriteTools();
    AiToolDispatcher dispatcher = dispatcher(shipped);

    for (String name : Arrays.asList("validate_supplier_invoice", "create_move", "edit_move")) {
      assertTrue(shipped.contains(name), name + " missing from " + shipped);
      assertNull(dispatcher.checkWriteChannel(writeTool(name), EMPTY_ARGS), name);
    }
  }

  private static Set<String> shippedChatWriteTools() throws IOException {
    Path cfg = applicationConfig();
    Properties props = new Properties();
    try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
      props.load(r);
    }
    String csv = props.getProperty(KEY);
    assertNotNull(csv, KEY + " is not set in " + cfg);
    assertFalse(
        props.containsKey(LEGACY_KEY),
        LEGACY_KEY + " in " + cfg + " would be read instead whenever " + KEY + " is empty");
    Set<String> out = new LinkedHashSet<>();
    for (String part : csv.split(",")) {
      if (!part.trim().isEmpty()) {
        out.add(part.trim());
      }
    }
    assertFalse(out.isEmpty(), KEY + " is empty in " + cfg);
    return out;
  }

  /** The application's config, found upwards from the module directory Gradle runs tests in. */
  private static Path applicationConfig() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path cfg = dir.resolve("src/main/resources/axelor-config.properties");
      if (Files.isRegularFile(cfg) && Files.isRegularFile(dir.resolve("settings.gradle"))) {
        return cfg;
      }
      dir = dir.getParent();
    }
    throw new AssertionError(
        "the application's src/main/resources/axelor-config.properties was not found above "
            + Paths.get("").toAbsolutePath());
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
}
