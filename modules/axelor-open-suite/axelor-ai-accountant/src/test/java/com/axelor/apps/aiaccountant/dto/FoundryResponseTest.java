package com.axelor.apps.aiaccountant.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing of the real Foundry response shape recorded during the A0 spike. */
class FoundryResponseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static FoundryResponse parse(String json) throws Exception {
    return new FoundryResponse(MAPPER.readTree(json));
  }

  @Test
  void assistantTextSkipsReasoningItems() throws Exception {
    // The live service puts a 'reasoning' item first; output[0] is NOT the message.
    FoundryResponse r =
        parse(
            "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"content\":[]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"PONG\"}]}]}");
    assertEquals("resp_1", r.getId());
    assertEquals("completed", r.getStatus());
    assertEquals("PONG", r.assistantText());
    assertTrue(r.functionCalls().isEmpty());
  }

  @Test
  void readsFunctionCalls() throws Exception {
    FoundryResponse r =
        parse(
            "{\"id\":\"resp_2\",\"output\":["
                + "{\"type\":\"reasoning\"},"
                + "{\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"get_demo_value\","
                + "\"arguments\":\"{\\\"key\\\":\\\"alpha\\\"}\"}]}");
    List<FoundryResponse.FunctionCall> calls = r.functionCalls();
    assertEquals(1, calls.size());
    assertEquals("call_abc", calls.get(0).getCallId());
    assertEquals("get_demo_value", calls.get(0).getName());
    // arguments is a JSON *string*, not an object
    assertEquals("{\"key\":\"alpha\"}", calls.get(0).getArguments());
  }

  @Test
  void readsParallelFunctionCalls() throws Exception {
    FoundryResponse r =
        parse(
            "{\"id\":\"r\",\"output\":["
                + "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"a\",\"arguments\":\"{}\"},"
                + "{\"type\":\"function_call\",\"call_id\":\"c2\",\"name\":\"b\",\"arguments\":\"{}\"}]}");
    assertEquals(2, r.functionCalls().size());
  }

  @Test
  void emptyOutputYieldsEmptyText() throws Exception {
    assertEquals("", parse("{\"id\":\"r\",\"output\":[]}").assistantText());
  }

  @Test
  void concatenatesMultipleMessageParts() throws Exception {
    FoundryResponse r =
        parse(
            "{\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"one\"},"
                + "{\"type\":\"output_text\",\"text\":\"two\"}]}]}");
    assertEquals("one\ntwo", r.assistantText());
  }
}
