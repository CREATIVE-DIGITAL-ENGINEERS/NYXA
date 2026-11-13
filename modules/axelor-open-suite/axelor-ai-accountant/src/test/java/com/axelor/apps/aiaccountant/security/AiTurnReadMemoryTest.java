package com.axelor.apps.aiaccountant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.tool.AiToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A repeated identical read is answered with the earlier data — as a success, not an {@code
 * already_answered} refusal — and only for the same user and company in the same turn.
 */
class AiTurnReadMemoryTest {

  @AfterEach
  void tearDown() {
    AiTurnReadMemory.end();
  }

  @Test
  void aRepeatedReadReturnsTheEarlierDataOnlyForTheSameUserAndCompany() {
    AiTurnReadMemory.begin();
    Map<String, Object> data = Collections.singletonMap("uploadId", 946);
    AiTurnReadMemory.remember(7L, 5L, "get_ocr_record", "{\"uploadId\":946}", data);

    assertTrue(AiTurnReadMemory.alreadyAnswered(7L, 5L, "get_ocr_record", "{\"uploadId\":946}"));
    assertSame(
        data, AiTurnReadMemory.earlierAnswer(7L, 5L, "get_ocr_record", "{\"uploadId\":946}"));
    assertFalse(
        AiTurnReadMemory.alreadyAnswered(7L, 6L, "get_ocr_record", "{\"uploadId\":946}"),
        "another company is never answered from this company's read");
    assertFalse(
        AiTurnReadMemory.alreadyAnswered(8L, 5L, "get_ocr_record", "{\"uploadId\":946}"),
        "another user is never answered from this user's read");

    AiTurnReadMemory.invalidate();
    assertFalse(
        AiTurnReadMemory.alreadyAnswered(7L, 5L, "get_ocr_record", "{\"uploadId\":946}"),
        "a write in between makes the earlier answer stale");
  }

  @Test
  void anEarlierAnswerIsSentAsTheSuccessItWasWithTheDispatcherNoticeBesideIt() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AiToolResult repeated =
        AiToolResult.ok(Collections.singletonMap("uploadId", 946), "Upload#946")
            .withNotice("Ignored the companyId argument.");

    String json = repeated.toJson(mapper);

    assertTrue(repeated.isSuccess());
    assertFalse(json.contains("already_answered"));
    assertEquals(946, mapper.readTree(json).path("uploadId").asInt());
    assertEquals(
        "Ignored the companyId argument.",
        mapper.readTree(json).path(AiToolResult.ARGUMENT_NOTICE).asText());
    assertEquals(
        "{\"uploadId\":946}",
        AiToolResult.ok(Collections.singletonMap("uploadId", 946), null).toJson(mapper),
        "with no notice the wire format is unchanged");
  }
}
