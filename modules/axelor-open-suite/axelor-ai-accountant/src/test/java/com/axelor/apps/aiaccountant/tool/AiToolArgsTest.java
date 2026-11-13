package com.axelor.apps.aiaccountant.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Handler-side argument validation — the model's schema is documentation, this is the guard. */
class AiToolArgsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode node(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  @Test
  void requireIdAcceptsNumberAndNumericString() throws Exception {
    assertEquals(283L, AiToolArgs.requireId(node("{\"id\":283}"), "id"));
    assertEquals(283L, AiToolArgs.requireId(node("{\"id\":\"283\"}"), "id"));
  }

  @Test
  void requireIdRejectsMissingZeroNegativeAndText() throws Exception {
    assertThrows(AiToolException.class, () -> AiToolArgs.requireId(node("{}"), "id"));
    assertThrows(AiToolException.class, () -> AiToolArgs.requireId(node("{\"id\":0}"), "id"));
    assertThrows(AiToolException.class, () -> AiToolArgs.requireId(node("{\"id\":-5}"), "id"));
    assertThrows(AiToolException.class, () -> AiToolArgs.requireId(node("{\"id\":\"abc\"}"), "id"));
  }

  @Test
  void requireTextRejectsBlank() throws Exception {
    assertEquals("x", AiToolArgs.requireText(node("{\"a\":\" x \"}"), "a"));
    assertThrows(AiToolException.class, () -> AiToolArgs.requireText(node("{\"a\":\"  \"}"), "a"));
    assertThrows(AiToolException.class, () -> AiToolArgs.requireText(node("{}"), "a"));
  }

  @Test
  void optionalsReturnNullWhenAbsentOrNull() throws Exception {
    assertNull(AiToolArgs.optText(node("{}"), "a"));
    assertNull(AiToolArgs.optText(node("{\"a\":null}"), "a"));
    assertNull(AiToolArgs.optId(node("{}"), "a"));
  }

  @Test
  void limitIsClampedIntoRange() throws Exception {
    assertEquals(20, AiToolArgs.limit(node("{}"), "limit", 20, 100));
    assertEquals(100, AiToolArgs.limit(node("{\"limit\":9999}"), "limit", 20, 100));
    assertEquals(1, AiToolArgs.limit(node("{\"limit\":0}"), "limit", 20, 100));
    assertEquals(20, AiToolArgs.limit(node("{\"limit\":\"junk\"}"), "limit", 20, 100));
  }
}
